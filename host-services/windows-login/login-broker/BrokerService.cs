using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.ServiceProcess;
using System.Threading;

namespace MoonWaker.WindowsLogin
{
    internal sealed class LoginSessionSnapshot
    {
        internal int SessionId;
        internal string Sid;
        internal string AccountName;
        internal string WinStationName;
        internal string State;
        internal bool Locked;
    }

    internal enum BrokerChannel
    {
        Management,
        Gateway,
        Provider
    }

    internal static class PipeSecurityFactory
    {
        internal static PipeSecurity Create(BrokerChannel channel)
        {
            SecurityIdentifier gatewayService = null;
            if (channel == BrokerChannel.Gateway)
            {
                try
                {
                    gatewayService = (SecurityIdentifier)new NTAccount(
                        "NT SERVICE", "MoonWakerGateway").Translate(
                            typeof(SecurityIdentifier));
                }
                catch (IdentityNotMappedException) { }
            }
            return Create(channel, gatewayService);
        }

        internal static PipeSecurity Create(BrokerChannel channel,
            SecurityIdentifier gatewayService)
        {
            if (channel == BrokerChannel.Gateway && gatewayService == null)
                throw new InvalidOperationException(
                    "The MoonWaker Gateway service SID is unavailable.");
            PipeSecurity security = new PipeSecurity();
            security.SetAccessRuleProtection(true, false);
            // The Gateway pipe already has a closed allow-list (SYSTEM and the
            // Gateway service SID). A NETWORK deny is redundant there and can
            // also reject the restricted virtual-service token before its
            // service-SID allow rule is evaluated.
            if (channel != BrokerChannel.Gateway)
                security.AddAccessRule(new PipeAccessRule(
                    new SecurityIdentifier(WellKnownSidType.NetworkSid, null),
                    PipeAccessRights.FullControl, AccessControlType.Deny));
            security.AddAccessRule(new PipeAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                PipeAccessRights.FullControl, AccessControlType.Allow));
            if (channel == BrokerChannel.Management)
                security.AddAccessRule(new PipeAccessRule(
                    new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
                    PipeAccessRights.ReadWrite, AccessControlType.Allow));
            else if (channel == BrokerChannel.Gateway)
                security.AddAccessRule(new PipeAccessRule(
                    gatewayService,
                    PipeAccessRights.ReadWrite | PipeAccessRights.CreateNewInstance,
                    AccessControlType.Allow));
            return security;
        }
    }

    internal sealed class PipeEndpoint : IDisposable
    {
        private const int IoTimeoutMilliseconds = 3000;
        private readonly object gate = new object();
        private readonly string name;
        private readonly BrokerChannel channel;
        private readonly Func<PipeRequest, BrokerReply> handler;
        private Thread thread;
        private NamedPipeServerStream listening;
        private volatile bool stopping;

        internal PipeEndpoint(string name, BrokerChannel channel,
            Func<PipeRequest, BrokerReply> handler)
        {
            this.name = name;
            this.channel = channel;
            this.handler = handler;
        }

        internal void Start()
        {
            if (thread != null) throw new InvalidOperationException("Pipe endpoint already started.");
            stopping = false;
            thread = new Thread(Listen);
            thread.IsBackground = true;
            thread.Name = "MoonWaker " + channel + " pipe";
            thread.Start();
        }

        internal void Stop()
        {
            stopping = true;
            lock (gate)
            {
                if (listening != null)
                {
                    try { listening.Dispose(); }
                    catch { }
                }
            }
            Thread current = thread;
            if (current != null) current.Join(4000);
            thread = null;
        }

        private void Listen()
        {
            while (!stopping)
            {
                NamedPipeServerStream pipe = null;
                try
                {
                    pipe = new NamedPipeServerStream(name, PipeDirection.InOut, 1,
                        PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.WriteThrough,
                        PipeProtocol.MaximumMessageBytes, PipeProtocol.MaximumMessageBytes,
                        PipeSecurityFactory.Create(channel));
                    lock (gate) listening = pipe;
                    pipe.WaitForConnection();
                    if (stopping) return;
                    Serve(pipe);
                }
                catch (ObjectDisposedException) { if (!stopping) Thread.Sleep(50); }
                catch (IOException) { if (!stopping) Thread.Sleep(50); }
                catch { if (!stopping) Thread.Sleep(100); }
                finally
                {
                    lock (gate) if (Object.ReferenceEquals(listening, pipe)) listening = null;
                    if (pipe != null) pipe.Dispose();
                }
            }
        }

        private void Serve(Stream stream)
        {
            PipeRequest request = null;
            BrokerReply reply = null;
            try
            {
                request = PipeProtocol.ReadRequest(stream, IoTimeoutMilliseconds);
                reply = handler(request);
            }
            catch (BrokerFault fault)
            {
                reply = BrokerReply.Fail(fault.State, fault.Reason);
            }
            catch
            {
                reply = BrokerReply.Fail("action_required", "invalid_request");
            }
            finally
            {
                if (request != null) request.Dispose();
            }
            try { PipeProtocol.WriteReply(stream, reply, IoTimeoutMilliseconds); }
            finally { if (reply != null) reply.Dispose(); }
        }

        public void Dispose()
        {
            Stop();
        }
    }

    internal sealed class BrokerServiceHost : IDisposable
    {
        internal const string ManagementPipe = "MoonWakerLoginBroker.Management.v1";
        internal const string GatewayPipe = "MoonWakerLoginBroker.Gateway.v1";
        internal const string ProviderPipe = "MoonWakerLoginBroker.Provider.v1";
        internal const string AttemptEvent = "Global\\MoonWaker.LoginAttempt.v1";

        private readonly WindowsSessionStateBackend sessions;
        private readonly EventWaitHandle attemptEvent;
        private readonly PipeEndpoint[] endpoints;

        internal BrokerServiceHost(bool requireLocalSystem)
        {
            WindowsIdentity identity = WindowsIdentity.GetCurrent();
            if (requireLocalSystem && (identity.User == null ||
                    !identity.User.IsWellKnown(WellKnownSidType.LocalSystemSid)))
                throw new InvalidOperationException(
                    "MoonWaker Login Broker must run as LocalSystem.");
            attemptEvent = CreateAttemptEvent();
            sessions = new WindowsSessionStateBackend();
            BrokerCore core = new BrokerCore(new WindowsAccountValidator(),
                new LsaSecretStore(), sessions,
                new AttemptLedger(delegate { return DateTime.UtcNow; },
                    TimeSpan.FromMinutes(2), 128, delegate { attemptEvent.Set(); }),
                ProviderReady);
            endpoints = new PipeEndpoint[] {
                new PipeEndpoint(ManagementPipe, BrokerChannel.Management, core.HandleManagement),
                new PipeEndpoint(GatewayPipe, BrokerChannel.Gateway, core.HandleGateway),
                new PipeEndpoint(ProviderPipe, BrokerChannel.Provider, core.HandleProvider)
            };
        }

        internal void Start()
        {
            foreach (PipeEndpoint endpoint in endpoints) endpoint.Start();
        }

        internal void Stop()
        {
            foreach (PipeEndpoint endpoint in endpoints) endpoint.Stop();
        }

        internal void SessionChanged(SessionChangeDescription change)
        {
            sessions.Update(change);
        }

        public void Dispose()
        {
            Stop();
            attemptEvent.Dispose();
        }

        private static EventWaitHandle CreateAttemptEvent()
        {
            EventWaitHandleSecurity security = CreateAttemptEventSecurity();
            bool created;
            return new EventWaitHandle(false, EventResetMode.AutoReset,
                AttemptEvent, out created, security);
        }

        internal static EventWaitHandleSecurity CreateAttemptEventSecurity()
        {
            EventWaitHandleSecurity security = new EventWaitHandleSecurity();
            security.SetAccessRuleProtection(true, false);
            security.AddAccessRule(new EventWaitHandleAccessRule(
                new SecurityIdentifier(WellKnownSidType.NetworkSid, null),
                EventWaitHandleRights.FullControl, AccessControlType.Deny));
            security.AddAccessRule(new EventWaitHandleAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                EventWaitHandleRights.FullControl, AccessControlType.Allow));
            return security;
        }

        internal static bool ProviderReady()
        {
            const string clsid = "{9F7D193F-8057-4D2D-88A0-5C1B8D03C441}";
            try
            {
                using (RegistryKey settings = Registry.LocalMachine.OpenSubKey(
                    @"SOFTWARE\MoonWaker\WindowsLogin\CredentialProvider"))
                using (RegistryKey server = Registry.LocalMachine.OpenSubKey(
                    @"SOFTWARE\Classes\CLSID\" + clsid + @"\InprocServer32"))
                {
                    if (settings == null || server == null ||
                        Convert.ToInt32(settings.GetValue("Enabled", 0)) != 1 ||
                        Convert.ToInt32(settings.GetValue("ProtocolVersion", 0)) != 1) return false;
                    string path = Convert.ToString(server.GetValue(null));
                    return !String.IsNullOrWhiteSpace(path) && File.Exists(path);
                }
            }
            catch { return false; }
        }
    }

    internal sealed class WindowsSessionStateBackend : ISessionStateBackend
    {
        private static readonly IntPtr CurrentServer = IntPtr.Zero;
        private readonly object gate = new object();
        private readonly HashSet<int> lockedSessions = new HashSet<int>();

        private enum WtsConnectState
        {
            Active, Connected, ConnectQuery, Shadow, Disconnected, Idle,
            Listen, Reset, Down, Init
        }

        private enum WtsInfoClass
        {
            InitialProgram, ApplicationName, WorkingDirectory, OemId,
            SessionId, UserName, WinStationName, DomainName
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WtsSessionInfo
        {
            internal int SessionId;
            internal IntPtr WinStationName;
            internal WtsConnectState State;
        }

        [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode,
            EntryPoint = "WTSEnumerateSessionsW", ExactSpelling = true,
            SetLastError = true)]
        private static extern bool WTSEnumerateSessions(IntPtr server, int reserved,
            int version, out IntPtr sessions, out int count);
        [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode, SetLastError = true,
            EntryPoint = "WTSQuerySessionInformationW")]
        private static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId,
            WtsInfoClass informationClass, out IntPtr buffer, out int bytes);
        [DllImport("wtsapi32.dll")]
        private static extern void WTSFreeMemory(IntPtr memory);
        [DllImport("wtsapi32.dll", SetLastError = true)]
        private static extern bool WTSDisconnectSession(IntPtr server, int sessionId,
            bool wait);

        public string GetState(ProfileIdentity identity)
        {
            try { return Classify(identity, Snapshots()); }
            catch (BrokerFault) { return "unknown"; }
        }

        public string Switch(ProfileIdentity identity)
        {
            int sessionId = SelectDisconnectSession(identity, Snapshots(),
                FastUserSwitchingEnabled());
            if (sessionId == -2) return "ready";
            if (sessionId >= 0 && !WTSDisconnectSession(CurrentServer, sessionId, true))
                throw new BrokerFault("action_required", "disconnect_failed");
            return "logon_ui";
        }

        private List<LoginSessionSnapshot> Snapshots()
        {
            IntPtr sessions;
            int count;
            if (!WTSEnumerateSessions(CurrentServer, 0, 1, out sessions, out count))
                throw new BrokerFault("action_required", "session_enumeration_failed");
            List<LoginSessionSnapshot> snapshots = new List<LoginSessionSnapshot>();
            try
            {
                int size = Marshal.SizeOf(typeof(WtsSessionInfo));
                for (int index = 0; index < count; index++)
                {
                    WtsSessionInfo session = (WtsSessionInfo)Marshal.PtrToStructure(
                        IntPtr.Add(sessions, index * size), typeof(WtsSessionInfo));
                    string user = Query(session.SessionId, WtsInfoClass.UserName);
                    string domain = Query(session.SessionId, WtsInfoClass.DomainName);
                    string account = String.IsNullOrWhiteSpace(domain)
                        ? user : domain + "\\" + user;
                    if (String.IsNullOrWhiteSpace(user)) continue;
                    string sid = "";
                    try
                    {
                        sid = ((SecurityIdentifier)new NTAccount(account).Translate(
                            typeof(SecurityIdentifier))).Value;
                    }
                    catch { }
                    string state = session.State == WtsConnectState.Active ? "active" :
                        (session.State == WtsConnectState.Connected ||
                         session.State == WtsConnectState.ConnectQuery ? "connected" : "disconnected");
                    snapshots.Add(new LoginSessionSnapshot { SessionId = session.SessionId,
                        Sid = sid, AccountName = account,
                        WinStationName = Marshal.PtrToStringUni(session.WinStationName) ?? "",
                        State = state, Locked = IsLocked(session.SessionId) });
                }
            }
            finally { WTSFreeMemory(sessions); }
            return snapshots;
        }

        internal static int SelectDisconnectSession(ProfileIdentity identity,
            IEnumerable<LoginSessionSnapshot> snapshots, bool fastUserSwitchingEnabled)
        {
            List<LoginSessionSnapshot> active = new List<LoginSessionSnapshot>();
            foreach (LoginSessionSnapshot session in snapshots)
                if (session.State == "active") active.Add(session);
            if (active.Count > 1)
                throw new BrokerFault("action_required", "multiple_active_sessions");
            if (active.Count == 0) return -1;
            LoginSessionSnapshot current = active[0];
            if (String.IsNullOrWhiteSpace(current.Sid) ||
                String.IsNullOrWhiteSpace(current.WinStationName))
                throw new BrokerFault("action_required", "active_session_unresolved");
            if (!String.Equals(current.WinStationName, "Console",
                    StringComparison.OrdinalIgnoreCase))
                throw new BrokerFault("unsupported", "rdp_session_active");
            if (String.Equals(current.Sid, identity.Sid,
                    StringComparison.OrdinalIgnoreCase))
                return current.Locked ? -1 : -2;
            if (!fastUserSwitchingEnabled)
                throw new BrokerFault("unsupported", "fast_user_switching_disabled");
            return current.SessionId;
        }

        private static bool FastUserSwitchingEnabled()
        {
            try
            {
                using (RegistryKey policy = Registry.LocalMachine.OpenSubKey(
                    @"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System"))
                    return policy == null || Convert.ToInt32(
                        policy.GetValue("HideFastUserSwitching", 0)) == 0;
            }
            catch { throw new BrokerFault("action_required", "fast_user_switching_unknown"); }
        }

        internal static string Classify(ProfileIdentity identity,
            IEnumerable<LoginSessionSnapshot> snapshots)
        {
            string best = "signed_out";
            bool targetActive = false;
            bool targetLocked = false;
            bool otherUserActive = false;
            bool unresolvedActive = false;
            foreach (LoginSessionSnapshot session in snapshots)
            {
                bool resolved = !String.IsNullOrWhiteSpace(session.Sid);
                bool target = resolved && String.Equals(session.Sid, identity.Sid,
                    StringComparison.OrdinalIgnoreCase);
                if (session.State == "active")
                {
                    if (!resolved) unresolvedActive = true;
                    else if (!target) otherUserActive = true;
                    else if (session.Locked) targetLocked = true;
                    else targetActive = true;
                }
                else if (target && session.State == "connected") best = "connected";
                else if (target && best == "signed_out") best = "disconnected";
            }
            if (targetActive) return "active";
            if (otherUserActive) return "other_user_active";
            if (targetLocked) return "locked";
            if (unresolvedActive) return "unknown";
            return best;
        }

        internal void Update(SessionChangeDescription change)
        {
            lock (gate)
            {
                if (change.Reason == SessionChangeReason.SessionLock)
                    lockedSessions.Add(change.SessionId);
                else if (change.Reason == SessionChangeReason.SessionUnlock ||
                    change.Reason == SessionChangeReason.SessionLogoff ||
                    change.Reason == SessionChangeReason.SessionLogon)
                    lockedSessions.Remove(change.SessionId);
            }
        }

        private bool IsLocked(int sessionId)
        {
            lock (gate) return lockedSessions.Contains(sessionId);
        }

        private static string Query(int sessionId, WtsInfoClass informationClass)
        {
            IntPtr buffer;
            int bytes;
            if (!WTSQuerySessionInformation(CurrentServer, sessionId,
                    informationClass, out buffer, out bytes) || buffer == IntPtr.Zero) return "";
            try { return Marshal.PtrToStringUni(buffer) ?? ""; }
            finally { WTSFreeMemory(buffer); }
        }
    }

    internal sealed class MoonWakerLoginBrokerService : ServiceBase
    {
        private BrokerServiceHost host;

        internal MoonWakerLoginBrokerService()
        {
            ServiceName = "MoonWakerLoginBroker";
            CanStop = true;
            CanShutdown = true;
            CanHandleSessionChangeEvent = true;
            AutoLog = false;
        }

        protected override void OnStart(string[] args)
        {
            host = new BrokerServiceHost(true);
            host.Start();
        }

        protected override void OnStop()
        {
            if (host == null) return;
            host.Dispose();
            host = null;
        }

        protected override void OnShutdown()
        {
            OnStop();
            base.OnShutdown();
        }

        protected override void OnSessionChange(SessionChangeDescription changeDescription)
        {
            if (host != null) host.SessionChanged(changeDescription);
            base.OnSessionChange(changeDescription);
        }
    }

    internal static class Program
    {
        private static void Main(string[] args)
        {
            if (args != null && Array.IndexOf(args, "--console") >= 0)
            {
                using (BrokerServiceHost host = new BrokerServiceHost(false))
                using (ManualResetEvent stopped = new ManualResetEvent(false))
                {
                    Console.CancelKeyPress += delegate(object sender, ConsoleCancelEventArgs e) {
                        e.Cancel = true;
                        stopped.Set();
                    };
                    host.Start();
                    Console.WriteLine("MoonWaker Login Broker is running. Press Ctrl+C to stop.");
                    stopped.WaitOne();
                }
                return;
            }
            ServiceBase.Run(new MoonWakerLoginBrokerService());
        }
    }
}
