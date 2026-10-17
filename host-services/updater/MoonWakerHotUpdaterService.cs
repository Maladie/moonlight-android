using System;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MoonWaker.HotUpdater
{
    internal sealed class UpdaterSettings
    {
        internal readonly string HostRoot;
        internal readonly string ApplyScript;
        internal readonly string StateRoot;
        internal readonly SecurityIdentifier ClientSid;

        private UpdaterSettings(string hostRoot, SecurityIdentifier clientSid)
        {
            HostRoot = hostRoot;
            ApplyScript = Path.Combine(hostRoot, "updater", "Apply-MoonWakerHotUpdate.ps1");
            StateRoot = Path.Combine(Environment.GetFolderPath(
                Environment.SpecialFolder.CommonApplicationData), "MoonWaker", "HotUpdater");
            ClientSid = clientSid;
        }

        internal static UpdaterSettings Parse(string[] args)
        {
            string root = "";
            string clientSid = "";
            for (int i = 0; args != null && i < args.Length; i++)
                if (args[i] == "--host-root" && i + 1 < args.Length) root = args[++i];
                else if (args[i] == "--client-sid" && i + 1 < args.Length) clientSid = args[++i];
            if (String.IsNullOrWhiteSpace(root) || root.IndexOf('"') >= 0)
                throw new InvalidOperationException("A valid --host-root is required.");
            SecurityIdentifier pinned;
            try { pinned = new SecurityIdentifier(clientSid); }
            catch { throw new InvalidOperationException("A valid --client-sid is required."); }
            root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            UpdaterSettings result = new UpdaterSettings(root, pinned);
            if (!File.Exists(Path.Combine(root, "version.json")) ||
                !File.Exists(result.ApplyScript))
                throw new InvalidOperationException("MoonWaker host or updater payload is incomplete.");
            return result;
        }
    }

    internal sealed class BoundedProcessResult
    {
        internal readonly int ExitCode;
        internal readonly string Output;
        internal readonly string Error;
        internal BoundedProcessResult(int exitCode, string output, string error)
        { ExitCode = exitCode; Output = output; Error = error; }
    }

    internal static class BoundedProcess
    {
        internal const int MaximumCapturedCharacters = 1024 * 1024;

        internal static BoundedProcessResult Run(ProcessStartInfo info, int timeoutMs,
            Action<Process> started, Action<Process> finished)
        {
            using (Process process = new Process { StartInfo = info })
            {
                if (!process.Start()) throw new InvalidOperationException("Apply process did not start.");
                if (started != null) started(process);
                Task<string> output = Drain(process.StandardOutput);
                Task<string> error = Drain(process.StandardError);
                try
                {
                    if (!process.WaitForExit(timeoutMs))
                    {
                        try { process.Kill(); } catch { }
                        if (!process.WaitForExit(10000))
                            throw new System.TimeoutException("Apply process did not terminate after cancellation.");
                        throw new System.TimeoutException("Hot update exceeded its execution limit.");
                    }
                    if (!Task.WaitAll(new Task[] { output, error }, 10000))
                        throw new System.TimeoutException("Apply output did not close after process exit.");
                    return new BoundedProcessResult(process.ExitCode, output.Result, error.Result);
                }
                finally { if (finished != null) finished(process); }
            }
        }

        private static Task<string> Drain(StreamReader reader)
        {
            return Task.Factory.StartNew(delegate {
                char[] buffer = new char[8192];
                StringBuilder captured = new StringBuilder();
                int count;
                while ((count = reader.Read(buffer, 0, buffer.Length)) > 0)
                {
                    int keep = Math.Min(count, MaximumCapturedCharacters - captured.Length);
                    if (keep > 0) captured.Append(buffer, 0, keep);
                }
                return captured.ToString();
            }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
        }
    }

    internal sealed class UpdateServer : IDisposable
    {
        internal const string PipeName = "MoonWakerHotUpdater.v1";
        internal const long MaximumPackageBytes = 64L * 1024L * 1024L;
        private readonly UpdaterSettings settings;
        private readonly ManualResetEvent stopping = new ManualResetEvent(false);
        private Thread worker;
        private NamedPipeServerStream activePipe;
        private readonly object processGate = new object();
        private Process applyProcess;

        internal UpdateServer(UpdaterSettings settings) { this.settings = settings; }

        internal static PipeSecurity CreatePipeSecurity(SecurityIdentifier clientSid)
        {
            PipeSecurity security = new PipeSecurity();
            security.SetAccessRuleProtection(true, false);
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(
                WellKnownSidType.NetworkSid, null), PipeAccessRights.ReadWrite,
                AccessControlType.Deny));
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(
                WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl,
                AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(
                WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl,
                AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(clientSid, PipeAccessRights.ReadWrite,
                AccessControlType.Allow));
            return security;
        }

        internal void Start()
        {
            worker = new Thread(Run) { IsBackground = true, Name = "MoonWaker hot updater" };
            worker.Start();
        }

        private void Run()
        {
            try { Apply(null); } catch { }
            while (!stopping.WaitOne(0))
            {
                try
                {
                    using (NamedPipeServerStream pipe = new NamedPipeServerStream(PipeName,
                        PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous,
                        65536, 65536, CreatePipeSecurity(settings.ClientSid)))
                    {
                        activePipe = pipe;
                        IAsyncResult waiting = pipe.BeginWaitForConnection(null, null);
                        int winner = WaitHandle.WaitAny(new[] { waiting.AsyncWaitHandle, stopping });
                        if (winner != 0) continue;
                        pipe.EndWaitForConnection(waiting);
                        Handle(pipe);
                    }
                }
                catch { if (!stopping.WaitOne(0)) Thread.Sleep(250); }
                finally { activePipe = null; }
            }
        }

        private void Handle(NamedPipeServerStream pipe)
        {
            string package = null;
            try
            {
                byte[] header = ReadExact(pipe, 8, 15000);
                long length = BitConverter.ToInt64(header, 0);
                if (length < 1 || length > MaximumPackageBytes)
                    throw new InvalidDataException("Package size is outside the allowed range.");
                Directory.CreateDirectory(settings.StateRoot);
                package = Path.Combine(settings.StateRoot, "stage-" +
                    Guid.NewGuid().ToString("N") + ".mwhu");
                using (FileStream output = new FileStream(package, FileMode.CreateNew,
                    FileAccess.Write, FileShare.None))
                {
                    byte[] buffer = new byte[65536];
                    long remaining = length;
                    while (remaining > 0)
                    {
                        int count = (int)Math.Min(buffer.Length, remaining);
                        byte[] block = ReadExact(pipe, count, 15000);
                        output.Write(block, 0, block.Length);
                        remaining -= block.Length;
                    }
                    output.Flush(true);
                }
                WriteResponse(pipe, Apply(package));
            }
            catch (Exception error)
            {
                WriteResponse(pipe, "{\"ok\":false,\"error\":\"" +
                    Escape(error.Message) + "\"}");
            }
            finally { if (package != null) try { File.Delete(package); } catch { } }
        }

        private string Apply(string package)
        {
            string system = Environment.GetFolderPath(Environment.SpecialFolder.System);
            string powershell = Path.Combine(system, "WindowsPowerShell", "v1.0", "powershell.exe");
            ProcessStartInfo info = new ProcessStartInfo {
                FileName = powershell,
                Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" +
                    settings.ApplyScript + "\" -HostRoot \"" + settings.HostRoot + "\"" +
                    (package == null ? " -RecoverOnly" : " -PackagePath \"" + package + "\""),
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            info.EnvironmentVariables["PSModulePath"] = Path.Combine(
                Path.GetDirectoryName(powershell), "Modules");
            BoundedProcessResult result = BoundedProcess.Run(info, 120000,
                delegate(Process process) {
                    lock (processGate)
                    {
                        applyProcess = process;
                        if (stopping.WaitOne(0)) try { process.Kill(); } catch { }
                    }
                }, delegate(Process process) {
                    lock (processGate) if (Object.ReferenceEquals(applyProcess, process))
                        applyProcess = null;
                });
            string detail = (result.ExitCode == 0 ? result.Output : result.Error).Trim();
            return "{\"ok\":" + (result.ExitCode == 0 ? "true" : "false") +
                    ",\"detail\":\"" + Escape(detail) + "\"}";
        }

        private static byte[] ReadExact(Stream stream, int count, int timeoutMs)
        {
            byte[] result = new byte[count];
            int offset = 0;
            while (offset < count)
            {
                IAsyncResult pending = stream.BeginRead(result, offset, count - offset, null, null);
                if (!pending.AsyncWaitHandle.WaitOne(timeoutMs))
                    throw new System.TimeoutException("Package transfer timed out.");
                int read = stream.EndRead(pending);
                if (read == 0) throw new EndOfStreamException("Package transfer ended early.");
                offset += read;
            }
            return result;
        }

        private static void WriteResponse(Stream stream, string value)
        {
            try
            {
                byte[] body = Encoding.UTF8.GetBytes(value ?? "");
                byte[] header = BitConverter.GetBytes(body.Length);
                stream.Write(header, 0, header.Length);
                stream.Write(body, 0, body.Length);
                stream.Flush();
            }
            catch { }
        }

        private static string Escape(string value)
        {
            return (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"")
                .Replace("\r", "\\r").Replace("\n", "\\n");
        }

        public void Dispose()
        {
            stopping.Set();
            try { if (activePipe != null) activePipe.Dispose(); } catch { }
            Process current;
            lock (processGate) current = applyProcess;
            if (current != null)
            {
                try { if (!current.HasExited) current.Kill(); } catch { }
                try { current.WaitForExit(10000); } catch { }
            }
            if (worker != null && !worker.Join(20000))
                throw new System.TimeoutException("Hot updater did not quiesce during service stop.");
            stopping.Dispose();
        }
    }

    internal sealed class UpdaterService : ServiceBase
    {
        private readonly UpdaterSettings settings;
        private UpdateServer server;
        internal UpdaterService(UpdaterSettings settings)
        { this.settings = settings; ServiceName = "MoonWakerHotUpdater"; CanStop = true; }
        protected override void OnStart(string[] args)
        { server = new UpdateServer(settings); server.Start(); }
        protected override void OnStop()
        { if (server != null) { server.Dispose(); server = null; } }
    }

    internal static class Program
    {
        private static void Main(string[] args)
        {
            UpdaterSettings settings = UpdaterSettings.Parse(args);
            ServiceBase.Run(new UpdaterService(settings));
        }
    }
}
