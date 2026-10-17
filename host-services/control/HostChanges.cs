using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Management;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;

// Compiled into both MoonWakerHostControl.exe (the unelevated UI, the client) and
// MoonWakerHostConfigurator.exe (the elevated helper, the server): the registry revision, the
// local accounts a profile may use, and the change channel between the two processes.
namespace MoonWaker.HostControl
{
    // The SHA-256 of gateway.json's bytes. Host Control builds a change set on the revision it
    // shows; the helper applies it only to that revision.
    internal static class RegistryRevision
    {
        internal static string Of(byte[] content)
        {
            using (SHA256 sha = SHA256.Create())
                return BitConverter.ToString(sha.ComputeHash(content)).Replace("-", "").ToLowerInvariant();
        }
    }

    // Names shown to the user: control (Cc), format (Cf, bidi among them), line and paragraph
    // separator (Zl, Zp) characters are never part of a name.
    internal static class DisplayText
    {
        private static bool Hidden(string value, int index)
        {
            System.Globalization.UnicodeCategory category = Char.GetUnicodeCategory(value, index);
            return category == System.Globalization.UnicodeCategory.Control ||
                category == System.Globalization.UnicodeCategory.Format ||
                category == System.Globalization.UnicodeCategory.LineSeparator ||
                category == System.Globalization.UnicodeCategory.ParagraphSeparator;
        }

        internal static bool HasHidden(string value)
        {
            for (int index = 0; index < value.Length; index++) if (Hidden(value, index)) return true;
            return false;
        }

        // value without hidden characters, at most max characters.
        internal static string Shown(string value, int max)
        {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < (value ?? "").Length; index++) if (!Hidden(value, index)) text.Append(value[index]);
            string shown = text.ToString().Trim();
            if (shown.Length <= max) return shown;
            int cut = Char.IsHighSurrogate(shown[max - 2]) ? max - 2 : max - 1;
            return shown.Substring(0, cut) + "…";
        }
    }

    // A child profile's avatars: the id in the registry and its name in Polish.
    internal static class ChildAvatars
    {
        internal static readonly string[] Ids = { "default", "green", "blue", "orange", "purple" };
        internal static readonly string[] Names = { "Domyślny", "Zielony", "Niebieski", "Pomarańczowy", "Fioletowy" };

        // "" is the default avatar; an id without a name is shown as it is.
        internal static string Name(string id)
        {
            int index = Array.IndexOf(Ids, String.IsNullOrEmpty(id) ? "default" : id);
            return index < 0 ? DisplayText.Shown(id, 40) : Names[index];
        }
    }

    internal sealed class LocalAccount
    {
        internal string Name;
        internal string QualifiedName;
        internal string Sid;
        public override string ToString() { return QualifiedName; }
    }

    // Enabled local accounts of this computer that a profile may use: WMI and a read of HKLM,
    // both available without elevation. Host Control lists them; the helper checks again.
    internal static class LocalAccountEnumerator
    {
        private static readonly HashSet<string> SpecialNames = new HashSet<string>(
            new string[] { "Administrator", "Guest", "DefaultAccount", "WDAGUtilityAccount",
                "defaultuser0", "HomeGroupUser$" }, StringComparer.OrdinalIgnoreCase);

        internal static List<LocalAccount> EnumerateSupported()
        {
            List<LocalAccount> result = new List<LocalAccount>();
            string machine = Environment.MachineName;
            using (ManagementObjectSearcher searcher = new ManagementObjectSearcher(
                "SELECT Name,Domain,SID,Disabled,LocalAccount FROM Win32_UserAccount " +
                "WHERE LocalAccount=True AND Disabled=False"))
            using (ManagementObjectCollection accounts = searcher.Get())
            {
                foreach (ManagementObject value in accounts)
                {
                    string name = Convert.ToString(value["Name"]);
                    string domain = Convert.ToString(value["Domain"]);
                    string sid = Convert.ToString(value["SID"]);
                    if (String.IsNullOrWhiteSpace(name) || String.IsNullOrWhiteSpace(sid) ||
                        !String.Equals(domain, machine, StringComparison.OrdinalIgnoreCase) ||
                        SpecialNames.Contains(name) || name.EndsWith("$", StringComparison.Ordinal) ||
                        !IsNormalLocalSid(sid) || IsCloudIdentity(sid)) continue;
                    result.Add(new LocalAccount {
                        Name = name, QualifiedName = machine + "\\" + name, Sid = sid
                    });
                }
            }
            result.Sort(delegate(LocalAccount left, LocalAccount right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.Name, right.Name);
            });
            return result;
        }

        private static bool IsNormalLocalSid(string sid)
        {
            if (!System.Text.RegularExpressions.Regex.IsMatch(sid,
                "^S-1-5-21-[0-9]+-[0-9]+-[0-9]+-[0-9]+$")) return false;
            int separator = sid.LastIndexOf('-');
            int rid;
            return separator > 0 && Int32.TryParse(sid.Substring(separator + 1), out rid) && rid >= 1000;
        }

        private static bool IsCloudIdentity(string sid)
        {
            string path = @"SOFTWARE\Microsoft\IdentityStore\Cache\" + sid;
            try
            {
                using (RegistryKey key = Registry.LocalMachine.OpenSubKey(path, false))
                {
                    if (key == null) return false;
                    string provider = Convert.ToString(key.GetValue("IdentityProvider", ""));
                    if (String.IsNullOrWhiteSpace(provider))
                        provider = Convert.ToString(key.GetValue("ProviderName", ""));
                    return provider.IndexOf("Microsoft", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        provider.IndexOf("Azure", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        provider.IndexOf("Entra", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        provider.IndexOf("Cloud", StringComparison.OrdinalIgnoreCase) >= 0;
                }
            }
            catch { return true; }
        }
    }

    // The helper received the change set but its answer did not arrive: whether it applied
    // the set is unknown.
    internal sealed class AnswerLostException : IOException
    {
        internal AnswerLostException(Exception inner)
            : base("Pomocnik administratora nie przekazał wyniku. Odświeżony stan pokazuje, co zapisano.", inner) { }
    }

    // Host Control's change set to the elevated helper and its answer. Host Control starts
    // "MoonWakerHostConfigurator.exe --apply --pipe <GUID> --client <its SID>" through UAC; the
    // helper creates the pipe (the first and only instance, local clients only, NETWORK denied,
    // its DACL naming only that SID) and accepts only the process that started it, in its
    // session, running as that SID; Host Control accepts only the server process it started.
    // Then one message each way: a 4-byte little-endian length and that many bytes of UTF-8
    // JSON, bounded in size and time.
    internal static class ChangeChannel
    {
        internal const string Protocol = "moonwaker-host-changes/1";
        internal const string PipePrefix = "MoonWakerHostConfigurator.Apply.";
        internal const int MaxRequestBytes = 256 * 1024;
        internal const int MaxAnswerBytes = 64 * 1024;
        // A message arrives whole within this time from when it is due.
        internal const int TransferMilliseconds = 10000;
        // The helper waits this long for Host Control, which connects right after UAC.
        internal const int ConnectMilliseconds = 30000;
        // Host Control waits this long for the answer: the user reads and confirms, then the work runs.
        internal const int AnswerMilliseconds = 30 * 60 * 1000;

        internal static string PipeName(Guid id) { return PipePrefix + id.ToString("D"); }

        // ---- Host Control: the client ----

        // Sends the request to the helper with the given process id and returns its answer.
        // serverRunning ends the wait for the pipe once the helper has exited.
        internal static byte[] Exchange(string pipeName, int serverProcessId, Func<bool> serverRunning,
            byte[] request, int connectMilliseconds = ConnectMilliseconds,
            int answerMilliseconds = AnswerMilliseconds, int transferMilliseconds = TransferMilliseconds)
        {
            if (request == null || request.Length == 0 || request.Length > MaxRequestBytes)
                throw new InvalidOperationException("Zestaw zmian jest pusty albo zbyt duży.");
            Stopwatch clock = Stopwatch.StartNew();
            while (true)
            {
                // ReadWrite, not InOut: GENERIC_WRITE would also ask for the right to create a
                // pipe instance, which the helper's DACL does not grant.
                NamedPipeClientStream pipe = new NamedPipeClientStream(".", pipeName, PipeAccessRights.ReadWrite,
                    PipeOptions.Asynchronous, TokenImpersonationLevel.Identification, HandleInheritability.None);
                try
                {
                    pipe.Connect(250);
                }
                catch (Exception error)
                {
                    pipe.Dispose();
                    if (error is UnauthorizedAccessException) throw;
                    if (!(error is TimeoutException) && !(error is IOException)) throw;
                    if (!serverRunning() || clock.ElapsedMilliseconds >= connectMilliseconds)
                        throw new InvalidOperationException("Pomocnik administratora nie otworzył kanału zmian.");
                    continue;
                }
                using (pipe)
                {
                    uint server;
                    if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out server) || server != serverProcessId)
                        throw new UnauthorizedAccessException(
                            "Kanał zmian nie należy do pomocnika uruchomionego przez Host Control.");
                    WriteMessage(pipe, request, transferMilliseconds);
                    // The request is delivered: from here the helper may have applied it.
                    try { return ReadMessage(pipe, MaxAnswerBytes, answerMilliseconds, transferMilliseconds); }
                    catch (Exception error)
                    {
                        if (!(error is IOException) && !(error is TimeoutException)) throw;
                        throw new AnswerLostException(error);
                    }
                }
            }
        }

        // ---- The elevated helper: the server ----

        // Serves one exchange: the client must be clientProcessId in this Windows session, its
        // token's user must be client (the DACL alone would also admit a group); answer turns
        // the request into the reply and may ask whether the client is still connected. Throws,
        // without calling answer, when nobody connects in time, another process or account
        // connects, or the request is malformed or late.
        internal static void Serve(string pipeName, SecurityIdentifier client, int clientProcessId,
            Func<byte[], Func<bool>, byte[]> answer, int connectMilliseconds = ConnectMilliseconds,
            int transferMilliseconds = TransferMilliseconds)
        {
            using (NamedPipeServerStream pipe = CreateServer(pipeName, client))
            {
                IAsyncResult waiting = pipe.BeginWaitForConnection(null, null);
                if (!waiting.AsyncWaitHandle.WaitOne(connectMilliseconds))
                    throw new TimeoutException("Host Control nie połączył się z pomocnikiem administratora.");
                pipe.EndWaitForConnection(waiting);
                uint connected, session;
                if (!GetNamedPipeClientProcessId(pipe.SafePipeHandle, out connected) ||
                    connected != (uint)clientProcessId)
                    throw new UnauthorizedAccessException(
                        "Z kanałem zmian połączył się inny proces niż Host Control, który uruchomił pomocnika.");
                int ownSession;
                using (Process self = Process.GetCurrentProcess()) ownSession = self.SessionId;
                if (!GetNamedPipeClientSessionId(pipe.SafePipeHandle, out session) || session != (uint)ownSession)
                    throw new UnauthorizedAccessException("Klient kanału zmian działa w innej sesji Windows.");
                byte[] request = ReadMessage(pipe, MaxRequestBytes, transferMilliseconds, transferMilliseconds);
                byte[] reply;
                try
                {
                    // Identification is enough to read the client's token (after its first write).
                    SecurityIdentifier user = null;
                    pipe.RunAsClient(delegate {
                        using (WindowsIdentity identity = WindowsIdentity.GetCurrent(TokenAccessLevels.Query))
                            user = identity.User;
                    });
                    if (user == null || !user.Equals(client))
                        throw new UnauthorizedAccessException("Klient kanału zmian działa na innym koncie Windows.");
                    reply = answer(request, delegate { return Connected(pipe); });
                }
                finally { Array.Clear(request, 0, request.Length); }
                if (reply.Length > MaxAnswerBytes)
                    throw new InvalidOperationException("Odpowiedź pomocnika jest zbyt duża.");
                WriteMessage(pipe, reply, transferMilliseconds);
                // The client closes after reading; closing first could discard the answer.
                try
                {
                    IAsyncResult closed = pipe.BeginRead(new byte[1], 0, 1, null, null);
                    closed.AsyncWaitHandle.WaitOne(transferMilliseconds);
                }
                catch (IOException) { }
            }
        }

        // A peek fails once the client has closed its end (or given up waiting).
        private static bool Connected(NamedPipeServerStream pipe)
        {
            uint available;
            return PeekNamedPipe(pipe.SafePipeHandle, IntPtr.Zero, 0, IntPtr.Zero, out available, IntPtr.Zero);
        }

        private static NamedPipeServerStream CreateServer(string pipeName, SecurityIdentifier client)
        {
            PipeSecurity security = new PipeSecurity();
            security.SetAccessRuleProtection(true, false);
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.NetworkSid, null),
                PipeAccessRights.FullControl, AccessControlType.Deny));
            security.AddAccessRule(new PipeAccessRule(client, PipeAccessRights.ReadWrite, AccessControlType.Allow));
            byte[] descriptor = security.GetSecurityDescriptorBinaryForm();
            GCHandle pinned = GCHandle.Alloc(descriptor, GCHandleType.Pinned);
            try
            {
                SecurityAttributes attributes = new SecurityAttributes {
                    Length = Marshal.SizeOf(typeof(SecurityAttributes)),
                    Descriptor = pinned.AddrOfPinnedObject() };
                SafePipeHandle handle = CreateNamedPipe(@"\\.\pipe\" + pipeName,
                    PipeAccessDuplex | FileFlagFirstPipeInstance | FileFlagOverlapped,
                    PipeRejectRemoteClients, 1, MaxAnswerBytes, MaxRequestBytes, 0, ref attributes);
                if (handle.IsInvalid)
                {
                    int error = Marshal.GetLastWin32Error();
                    handle.Dispose();
                    throw new System.ComponentModel.Win32Exception(error,
                        "Nie można utworzyć kanału zmian (" + error + ").");
                }
                return new NamedPipeServerStream(PipeDirection.InOut, true, false, handle);
            }
            finally { pinned.Free(); }
        }

        // ---- Framing ----

        private static void WriteMessage(PipeStream pipe, byte[] payload, int transferMilliseconds)
        {
            byte[] frame = new byte[4 + payload.Length];
            frame[0] = (byte)payload.Length;
            frame[1] = (byte)(payload.Length >> 8);
            frame[2] = (byte)(payload.Length >> 16);
            frame[3] = (byte)(payload.Length >> 24);
            Buffer.BlockCopy(payload, 0, frame, 4, payload.Length);
            try
            {
                IAsyncResult pending = pipe.BeginWrite(frame, 0, frame.Length, null, null);
                if (!pending.AsyncWaitHandle.WaitOne(transferMilliseconds))
                    throw new TimeoutException("Kanał zmian nie przyjął wiadomości w wyznaczonym czasie.");
                pipe.EndWrite(pending);
            }
            finally { Array.Clear(frame, 0, frame.Length); }
        }

        // firstByteMilliseconds: how long the message may take to start; from then on the
        // whole message must arrive within transferMilliseconds.
        private static byte[] ReadMessage(PipeStream pipe, int maxBytes, int firstByteMilliseconds,
            int transferMilliseconds)
        {
            byte[] header = new byte[4];
            Fill(pipe, header, 0, 1, Stopwatch.StartNew(), firstByteMilliseconds);
            Stopwatch clock = Stopwatch.StartNew();
            Fill(pipe, header, 1, 3, clock, transferMilliseconds);
            int length = header[0] | (header[1] << 8) | (header[2] << 16) | (header[3] << 24);
            if (length <= 0 || length > maxBytes)
                throw new InvalidDataException("Wiadomość kanału zmian ma nieprawidłowy rozmiar.");
            byte[] body = new byte[length];
            Fill(pipe, body, 0, length, clock, transferMilliseconds);
            return body;
        }

        private static void Fill(PipeStream pipe, byte[] buffer, int offset, int count, Stopwatch clock, int limit)
        {
            while (count > 0)
            {
                long remaining = limit - clock.ElapsedMilliseconds;
                IAsyncResult pending = pipe.BeginRead(buffer, offset, count, null, null);
                if (remaining <= 0 || !pending.AsyncWaitHandle.WaitOne((int)Math.Min(remaining, Int32.MaxValue)))
                    throw new TimeoutException("Wiadomość kanału zmian nie dotarła w wyznaczonym czasie.");
                int read = pipe.EndRead(pending);
                if (read <= 0) throw new EndOfStreamException("Kanał zmian został zamknięty przed końcem wiadomości.");
                offset += read;
                count -= read;
            }
        }

        private const uint PipeAccessDuplex = 0x00000003;
        private const uint FileFlagFirstPipeInstance = 0x00080000;
        private const uint FileFlagOverlapped = 0x40000000;
        private const uint PipeRejectRemoteClients = 0x00000008;

        [StructLayout(LayoutKind.Sequential)]
        private struct SecurityAttributes
        {
            internal int Length;
            internal IntPtr Descriptor;
            internal int InheritHandle;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafePipeHandle CreateNamedPipe(string name, uint openMode, uint pipeMode,
            uint maxInstances, uint outBufferSize, uint inBufferSize, uint defaultTimeout,
            ref SecurityAttributes attributes);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool PeekNamedPipe(SafePipeHandle pipe, IntPtr buffer, uint size, IntPtr read,
            out uint available, IntPtr left);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint processId);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetNamedPipeClientProcessId(SafePipeHandle pipe, out uint processId);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetNamedPipeClientSessionId(SafePipeHandle pipe, out uint sessionId);
    }
}
