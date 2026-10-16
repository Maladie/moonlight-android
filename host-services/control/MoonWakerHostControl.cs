using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Pipes;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using System.Windows.Interop;
using System.Windows.Threading;

[assembly: AssemblyVersion("0.7.141.0")]
[assembly: AssemblyFileVersion("0.7.141.0")]
[assembly: AssemblyInformationalVersion("0.7.141+2026.10.16-host-p1")]
// WPF follows the per-monitor DPI of the manifest only for a .NET Framework 4.6.2+ target.
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8")]

namespace MoonWaker.HostControl
{
    internal static class NativeMethods
    {
        [DllImport("user32.dll", SetLastError = true)]
        internal static extern bool RegisterHotKey(IntPtr window, int id, uint modifiers, uint key);

        [DllImport("user32.dll", SetLastError = true)]
        internal static extern bool UnregisterHotKey(IntPtr window, int id);
    }

    internal sealed class HostControlInstance : IDisposable
    {
        private readonly Mutex mutex;
        private readonly EventWaitHandle activationEvent;
        private readonly bool ownsMutex;

        internal bool IsPrimary { get; private set; }
        internal WaitHandle ActivationEvent { get { return activationEvent; } }

        internal static HostControlInstance CreateForCurrentUser()
        {
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
            {
                if (identity == null || identity.User == null)
                    throw new InvalidOperationException("Nie można ustalić identyfikatora SID bieżącego konta Windows.");
                return new HostControlInstance(
                    @"Local\MoonWakerHostControl." + identity.User.Value);
            }
        }

        internal HostControlInstance(string objectNamePrefix)
        {
            if (String.IsNullOrWhiteSpace(objectNamePrefix) ||
                !objectNamePrefix.StartsWith(@"Local\", StringComparison.OrdinalIgnoreCase) ||
                objectNamePrefix.Length <= 6)
                throw new ArgumentException("Host Control instance names must be session-local.",
                    "objectNamePrefix");

            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
            {
                if (identity == null || identity.User == null)
                    throw new InvalidOperationException("Nie można ustalić identyfikatora SID bieżącego konta Windows.");

                EventWaitHandle openedActivationEvent = null;
                Mutex openedMutex = null;
                bool ownsOpenedMutex = false;
                try
                {
                    bool activationEventCreated;
                    openedActivationEvent = new EventWaitHandle(false,
                        EventResetMode.AutoReset, objectNamePrefix + ".Activate",
                        out activationEventCreated, CreateEventSecurity(identity.User));

                    bool mutexCreated;
                    openedMutex = new Mutex(true, objectNamePrefix + ".Mutex",
                        out mutexCreated, CreateMutexSecurity(identity.User));
                    ownsOpenedMutex = mutexCreated;
                    if (!mutexCreated)
                    {
                        try { ownsOpenedMutex = openedMutex.WaitOne(0); }
                        catch (AbandonedMutexException) { ownsOpenedMutex = true; }
                    }
                }
                catch
                {
                    if (openedMutex != null) openedMutex.Dispose();
                    if (openedActivationEvent != null) openedActivationEvent.Dispose();
                    throw;
                }

                activationEvent = openedActivationEvent;
                mutex = openedMutex;
                ownsMutex = ownsOpenedMutex;
                IsPrimary = ownsOpenedMutex;
            }
        }

        internal bool ShouldRun(bool startInTray)
        {
            if (IsPrimary) return true;
            if (!startInTray) activationEvent.Set();
            return false;
        }

        public void Dispose()
        {
            try
            {
                if (ownsMutex) mutex.ReleaseMutex();
            }
            finally
            {
                mutex.Dispose();
                activationEvent.Dispose();
            }
        }

        private static MutexSecurity CreateMutexSecurity(SecurityIdentifier userSid)
        {
            MutexSecurity security = new MutexSecurity();
            security.SetAccessRuleProtection(true, false);
            security.AddAccessRule(new MutexAccessRule(userSid, MutexRights.FullControl,
                AccessControlType.Allow));
            security.AddAccessRule(new MutexAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                MutexRights.FullControl, AccessControlType.Allow));
            return security;
        }

        private static EventWaitHandleSecurity CreateEventSecurity(SecurityIdentifier userSid)
        {
            EventWaitHandleSecurity security = new EventWaitHandleSecurity();
            security.SetAccessRuleProtection(true, false);
            security.AddAccessRule(new EventWaitHandleAccessRule(userSid,
                EventWaitHandleRights.FullControl, AccessControlType.Allow));
            security.AddAccessRule(new EventWaitHandleAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                EventWaitHandleRights.FullControl, AccessControlType.Allow));
            return security;
        }
    }

    internal sealed class HostControlActivationListener : IDisposable
    {
        private RegisteredWaitHandle registration;

        internal HostControlActivationListener(WaitHandle activationEvent, Action activate)
        {
            if (activationEvent == null) throw new ArgumentNullException("activationEvent");
            if (activate == null) throw new ArgumentNullException("activate");
            registration = ThreadPool.RegisterWaitForSingleObject(activationEvent,
                delegate(object state, bool timedOut)
                {
                    if (!timedOut) activate();
                }, null, Timeout.Infinite, false);
        }

        public void Dispose()
        {
            RegisteredWaitHandle current = Interlocked.Exchange(ref registration, null);
            if (current != null) current.Unregister(null);
        }
    }

    // Picks the one standard profile of a Windows account (the Configurator allows one per
    // account; child profiles have no processes of their own). Authority itself is checked
    // by the Agent, which is the only owner of that decision.
    internal static class AgentProfileSelector
    {
        internal static string Select(Dictionary<string, object> registry, string sid)
        {
            object value;
            Dictionary<string, object> profiles = registry != null &&
                registry.TryGetValue("profiles", out value) ? value as Dictionary<string, object> : null;
            if (profiles == null || String.IsNullOrWhiteSpace(sid)) return null;
            string found = null;
            foreach (KeyValuePair<string, object> item in profiles)
            {
                Dictionary<string, object> entry = item.Value as Dictionary<string, object>;
                if (entry == null || String.Equals(Text(entry, "kind"), "child",
                        StringComparison.OrdinalIgnoreCase) ||
                    !String.Equals(Text(entry, "windows_account_sid"), sid,
                        StringComparison.OrdinalIgnoreCase)) continue;
                if (found != null) return null;
                found = item.Key;
            }
            return found != null && System.Text.RegularExpressions.Regex.IsMatch(
                found, "^[A-Za-z0-9._-]{1,64}$") ? found : null;
        }

        internal static string Text(Dictionary<string, object> entry, string key)
        {
            object value;
            return entry.TryGetValue(key, out value) && value != null ? value.ToString() : "";
        }
    }

    // Restart decision of one hosted child: backoff 2 s doubling to 30 s, reset after a
    // stable run; after the Agent's authority-lost exit no restart until the profile's
    // registry entry changes.
    internal sealed class ChildRestartPolicy
    {
        internal const int AuthorityLostExitCode = 78;
        internal static readonly TimeSpan StableRun = TimeSpan.FromSeconds(60);
        private int failures;
        private string blockedStamp;

        internal bool Blocked { get { return blockedStamp != null; } }

        internal TimeSpan OnExit(int exitCode, TimeSpan ranFor, string registryStamp)
        {
            if (exitCode == AuthorityLostExitCode)
            {
                blockedStamp = registryStamp ?? "";
                failures = 0;
                return TimeSpan.Zero;
            }
            failures = ranFor >= StableRun ? 1 : failures + 1;
            return Delay(failures);
        }

        internal bool MayStart(string registryStamp)
        {
            if (blockedStamp == null) return true;
            if (String.Equals(blockedStamp, registryStamp, StringComparison.Ordinal)) return false;
            Reset();
            return true;
        }

        internal void Reset()
        {
            failures = 0;
            blockedStamp = null;
        }

        internal static TimeSpan Delay(int failures)
        {
            if (failures < 1) return TimeSpan.Zero;
            return TimeSpan.FromSeconds(Math.Min(30, 2 << Math.Min(failures - 1, 4)));
        }
    }

    // Children are assigned to a job that kills them when the tray's handle closes (the tray
    // exits, crashes or is killed). A child is created suspended and runs only once it is in
    // the job, so no crash of the tray can leave an orphan. Their own children break away
    // silently, so games, Steam or Playnite started by the Agent never die with the tray.
    internal sealed class ChildProcessJob : IDisposable
    {
        private const int ExtendedLimitInformation = 9;
        private const uint KillOnJobClose = 0x2000;
        private const uint SilentBreakawayOk = 0x1000;
        private const uint CreateSuspended = 0x4;
        private const uint CreateNoWindow = 0x08000000;
        private IntPtr handle;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct StartupInfo
        {
            internal int Size;
            internal string Reserved, Desktop, Title;
            internal int X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
            internal short ShowWindow, Reserved2Size;
            internal IntPtr Reserved2, StandardInput, StandardOutput, StandardError;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ProcessInformation
        {
            internal IntPtr Process, Thread;
            internal int ProcessId, ThreadId;
        }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode,
            EntryPoint = "CreateProcessW")]
        private static extern bool CreateProcess(string application, StringBuilder commandLine,
            IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags,
            IntPtr environment, string directory, ref StartupInfo startup,
            out ProcessInformation information);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint ResumeThread(IntPtr thread);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool TerminateProcess(IntPtr process, uint exitCode);

        [StructLayout(LayoutKind.Sequential)]
        private struct BasicLimitInformation
        {
            internal long PerProcessUserTimeLimit;
            internal long PerJobUserTimeLimit;
            internal uint LimitFlags;
            internal UIntPtr MinimumWorkingSetSize;
            internal UIntPtr MaximumWorkingSetSize;
            internal uint ActiveProcessLimit;
            internal UIntPtr Affinity;
            internal uint PriorityClass;
            internal uint SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IoCounters
        {
            internal ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
            internal ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ExtendedLimitInformationData
        {
            internal BasicLimitInformation BasicLimitInformation;
            internal IoCounters IoInfo;
            internal UIntPtr ProcessMemoryLimit;
            internal UIntPtr JobMemoryLimit;
            internal UIntPtr PeakProcessMemoryUsed;
            internal UIntPtr PeakJobMemoryUsed;
        }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr CreateJobObject(IntPtr attributes, string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetInformationJobObject(IntPtr job, int infoClass,
            ref ExtendedLimitInformationData info, int length);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);

        internal ChildProcessJob()
        {
            handle = CreateJobObject(IntPtr.Zero, null);
            if (handle == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
            ExtendedLimitInformationData info = new ExtendedLimitInformationData();
            info.BasicLimitInformation.LimitFlags = KillOnJobClose | SilentBreakawayOk;
            if (!SetInformationJobObject(handle, ExtendedLimitInformation, ref info,
                Marshal.SizeOf(typeof(ExtendedLimitInformationData))))
            {
                int error = Marshal.GetLastWin32Error();
                CloseHandle(handle);
                handle = IntPtr.Zero;
                throw new System.ComponentModel.Win32Exception(error);
            }
        }

        // Command line: "<FileName>" <Arguments>, hidden, in WorkingDirectory.
        internal Process Start(ProcessStartInfo info)
        {
            StartupInfo startup = new StartupInfo();
            startup.Size = Marshal.SizeOf(typeof(StartupInfo));
            startup.Flags = 0x1; // STARTF_USESHOWWINDOW with SW_HIDE
            ProcessInformation created;
            if (!CreateProcess(info.FileName, new StringBuilder("\"" + info.FileName + "\" " + info.Arguments),
                    IntPtr.Zero, IntPtr.Zero, false, CreateSuspended | CreateNoWindow, IntPtr.Zero,
                    String.IsNullOrEmpty(info.WorkingDirectory) ? null : info.WorkingDirectory,
                    ref startup, out created))
                throw new System.ComponentModel.Win32Exception();
            Process process = null;
            bool running = false;
            try
            {
                if (!AssignProcessToJobObject(handle, created.Process))
                    throw new System.ComponentModel.Win32Exception();
                // Our handle pins the id; the Process object opens its own before ours closes.
                process = Process.GetProcessById(created.ProcessId);
                GC.KeepAlive(process.Handle);
                if (ResumeThread(created.Thread) == UInt32.MaxValue)
                    throw new System.ComponentModel.Win32Exception();
                running = true;
                return process;
            }
            finally
            {
                if (!running)
                {
                    TerminateProcess(created.Process, 1);
                    if (process != null) process.Dispose();
                }
                CloseHandle(created.Thread);
                CloseHandle(created.Process);
            }
        }

        public void Dispose()
        {
            IntPtr current = Interlocked.Exchange(ref handle, IntPtr.Zero);
            if (current != IntPtr.Zero) CloseHandle(current);
        }
    }

    // Hosts the processes of the current Windows account's profile: the Agent
    // (GameProviderBridge.py under pythonw) and, while the Agent runs and Discord is
    // installed and configured, Discord Bridge. Code runs from the installation; the profile directory holds
    // only data and configuration. A tray with an elevated token hosts nothing: profile
    // processes run only with the account's standard token.
    internal sealed class ProfileProcessHost : IDisposable
    {
        internal const string ElevatedStatus = "Tray działa jako administrator";
        internal const string AuthorityLostStatus = "Profil nie ma uprawnień na tym koncie";

        private sealed class Child
        {
            internal readonly ChildRestartPolicy Policy = new ChildRestartPolicy();
            internal Process Process;
            // Killed and not yet exited: nothing new starts until it is gone.
            internal bool Stopping;
            internal DateTime StartedUtc;
            internal DateTime NotBeforeUtc;
            internal bool StartFailed;

            internal bool Running
            {
                get { return Process != null && !Stopping && !Process.HasExited; }
            }
        }

        private readonly string installRoot;
        private readonly bool discordInstalled;
        private readonly string sid;
        private readonly bool elevated;
        private readonly Func<string, ProcessStartInfo> agentStartInfo;
        private readonly Func<string, ProcessStartInfo> discordStartInfo;
        private readonly ChildProcessJob job = new ChildProcessJob();
        private readonly Child agent = new Child();
        private readonly Child discord = new Child();
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();
        private readonly object gate = new object();
        private System.Threading.Timer timer;
        private int restartRequested;
        private string profileId;
        private bool disposed;

        internal ProfileProcessHost(string installRoot)
            : this(installRoot, CurrentSid(), TokenElevated(), null, null)
        {
            timer = new System.Threading.Timer(delegate { Tick(); }, null, 0, 1000);
        }

        // Without a timer: the caller drives Tick (headless tests).
        internal ProfileProcessHost(string installRoot, string sid, bool elevated,
            Func<string, ProcessStartInfo> agentStartInfo, Func<string, ProcessStartInfo> discordStartInfo)
        {
            this.installRoot = Path.GetFullPath(installRoot).TrimEnd('\\');
            discordInstalled = DiscordInstalled(this.installRoot);
            this.sid = sid ?? "";
            this.elevated = elevated;
            this.agentStartInfo = agentStartInfo ?? AgentStartInfo;
            this.discordStartInfo = discordStartInfo ?? DiscordStartInfo;
        }

        internal string ProfileId { get { lock (gate) return profileId; } }

        // Discord is an optional package component: it is installed exactly when its
        // Bridge is in bridge-source (Build-HostServices.ps1 -WithDiscord).
        internal static bool DiscordInstalled(string installRoot)
        {
            return File.Exists(DiscordScript(installRoot));
        }

        private static string DiscordScript(string installRoot)
        {
            return Path.Combine(installRoot, "bridge-source", "discord", "DiscordBridge.ps1");
        }

        // A tray started at sign-in stays only for an account that owns a profile.
        internal static bool AccountHasProfile(string installRoot)
        {
            Dictionary<string, object> registry = HostFiles.ReadJson(Path.Combine(installRoot,
                "gateway", "gateway.json"));
            return registry == null || AgentProfileSelector.Select(registry, CurrentSid()) != null;
        }

        [DllImport("advapi32.dll", SetLastError = true)]
        private static extern bool GetTokenInformation(IntPtr token, int informationClass,
            out int information, int length, out int returnLength);

        internal static bool TokenElevated()
        {
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
            {
                int elevation, size;
                if (!GetTokenInformation(identity.Token, 20 /* TokenElevation */, out elevation,
                        sizeof(int), out size))
                    throw new System.ComponentModel.Win32Exception();
                return elevation != 0;
            }
        }

        internal static string CurrentSid()
        {
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
                return identity.User == null ? "" : identity.User.Value;
        }

        // Never waits on the UI thread: the next tick (within 1 s) stops the children and
        // starts them again once they are gone.
        internal void RestartAll()
        {
            Interlocked.Exchange(ref restartRequested, 1);
        }

        // Null while the child runs (its /health or /observation then decides the label).
        internal string Describe(string id, bool discordChild)
        {
            lock (gate)
            {
                if (disposed || id == null || id != profileId) return null;
                if (elevated) return ElevatedStatus;
                Child child = discordChild ? discord : agent;
                if (agent.Policy.Blocked) return AuthorityLostStatus;
                if (child.Process != null) return child.Stopping ? "Zatrzymywanie" : null;
                if (child.StartFailed) return "Błąd startu";
                int seconds = (int)Math.Ceiling((child.NotBeforeUtc - DateTime.UtcNow).TotalSeconds);
                return seconds > 0 ? "Ponowny start za " + seconds + " s" : null;
            }
        }

        internal int ProcessId(bool discordChild)
        {
            lock (gate)
            {
                Child child = discordChild ? discord : agent;
                return child.Process == null ? 0 : child.Process.Id;
            }
        }

        internal void Tick()
        {
            if (!Monitor.TryEnter(gate)) return;
            try
            {
                if (disposed) return;
                Dictionary<string, object> registry = HostFiles.ReadJson(Path.Combine(installRoot,
                    "gateway", "gateway.json"));
                if (registry == null)
                {
                    Reap(agent, "unreadable");
                    Reap(discord, "unreadable");
                    return;
                }
                string id = AgentProfileSelector.Select(registry, sid);
                Dictionary<string, object> entry = id == null ? null :
                    ((Dictionary<string, object>)registry["profiles"])[id] as Dictionary<string, object>;
                string stamp = entry == null ? "" : json.Serialize(entry);
                Reap(agent, stamp);
                Reap(discord, stamp);
                if (Interlocked.Exchange(ref restartRequested, 0) == 1 || id != profileId)
                {
                    foreach (Child child in new[] { agent, discord })
                    {
                        Stop(child);
                        child.Policy.Reset();
                        child.NotBeforeUtc = DateTime.MinValue;
                    }
                    profileId = id;
                }
                string root = id == null ? null : Path.Combine(installRoot, "profiles", id);
                bool agentWanted = !elevated && id != null && agent.Policy.MayStart(stamp);
                if (agentWanted) EnsureRunning(agent, stamp, root, agentStartInfo);
                else Stop(agent);
                // Discord serves the profile only beside a running Agent.
                if (agentWanted && agent.Running && discordInstalled && DiscordConfigured(entry, root))
                    EnsureRunning(discord, stamp, root, discordStartInfo);
                else Stop(discord);
            }
            catch { }
            finally { Monitor.Exit(gate); }
        }

        private static void Reap(Child child, string stamp)
        {
            if (child.Process == null || !child.Process.HasExited) return;
            if (!child.Stopping)
            {
                TimeSpan delay = child.Policy.OnExit(child.Process.ExitCode,
                    DateTime.UtcNow - child.StartedUtc, stamp);
                child.NotBeforeUtc = DateTime.UtcNow + delay;
            }
            child.Process.Dispose();
            child.Process = null;
            child.Stopping = false;
        }

        private void EnsureRunning(Child child, string stamp, string root,
            Func<string, ProcessStartInfo> startInfo)
        {
            if (child.Process != null || DateTime.UtcNow < child.NotBeforeUtc) return;
            try
            {
                child.StartedUtc = DateTime.UtcNow;
                child.Process = job.Start(startInfo(root));
                child.StartFailed = false;
            }
            catch
            {
                child.StartFailed = true;
                child.NotBeforeUtc = DateTime.UtcNow + child.Policy.OnExit(-1, TimeSpan.Zero, stamp);
            }
        }

        // Kills without waiting; Reap forgets the process once it has really exited.
        private static void Stop(Child child)
        {
            if (child.Process == null || child.Stopping) return;
            child.Stopping = true;
            try { child.Process.Kill(); } catch { }
        }

        private ProcessStartInfo AgentStartInfo(string root)
        {
            string data = Path.Combine(root, "game-provider");
            string script = Path.Combine(installRoot, "bridge-source", "playnite", "GameProviderBridge.py");
            return HiddenStartInfo(GatewayPython("pythonw.exe"), Quote(script) + " --config " +
                Quote(Path.Combine(data, "config.json")), data);
        }

        private ProcessStartInfo DiscordStartInfo(string root)
        {
            string data = Path.Combine(root, "discord");
            string script = DiscordScript(installRoot);
            string powershell = Path.Combine(Environment.SystemDirectory,
                "WindowsPowerShell", "v1.0", "powershell.exe");
            return HiddenStartInfo(powershell, "-NoProfile -NonInteractive -WindowStyle Hidden " +
                "-ExecutionPolicy Bypass -File " + Quote(script) + " -ProfileDirectory " + Quote(data), data);
        }

        // ChildProcessJob starts it hidden as "<file>" <arguments>; the updater matches that shape.
        private static ProcessStartInfo HiddenStartInfo(string file, string arguments, string directory)
        {
            ProcessStartInfo info = new ProcessStartInfo(file, arguments);
            info.WorkingDirectory = directory;
            return info;
        }

        private static bool DiscordConfigured(Dictionary<string, object> entry, string root)
        {
            return entry != null &&
                !String.IsNullOrWhiteSpace(AgentProfileSelector.Text(entry, "discord_bridge")) &&
                File.Exists(Path.Combine(root, "discord", "discord_bridge_config.json")) &&
                File.Exists(Path.Combine(root, "discord", "client_secret.dpapi"));
        }

        // The one Python the installation already trusts: the Gateway service's --python
        // directory; "pythonw.exe" for the Agent, "python.exe" for hidden helpers.
        internal static string GatewayPython(string executable)
        {
            using (Microsoft.Win32.RegistryKey service = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
                @"SYSTEM\CurrentControlSet\Services\MoonWakerGateway", false))
            {
                string image = service == null ? "" : Convert.ToString(service.GetValue("ImagePath", ""));
                string python = PythonFromServiceImage(image);
                if (python == null) throw new FileNotFoundException("Usługa Gateway nie ma ścieżki Pythona.");
                string path = Path.Combine(Path.GetDirectoryName(python), executable);
                if (!File.Exists(path)) throw new FileNotFoundException("Brak " + executable + ".", path);
                return path;
            }
        }

        internal static string PythonFromServiceImage(string image)
        {
            System.Text.RegularExpressions.Match match = System.Text.RegularExpressions.Regex.Match(
                image ?? "", "(?:^|\\s)--python\\s+(?:\"(?<quoted>[^\"]+)\"|(?<plain>\\S+))(?=\\s|$)");
            if (!match.Success) return null;
            string value = match.Groups["quoted"].Success ? match.Groups["quoted"].Value
                : match.Groups["plain"].Value;
            return Path.IsPathRooted(value) ? Path.GetFullPath(value) : null;
        }

        private static string Quote(string value) { return "\"" + value.Replace("\"", "\\\"") + "\""; }

        public void Dispose()
        {
            lock (gate)
            {
                if (disposed) return;
                disposed = true;
            }
            if (timer != null) timer.Dispose();
            job.Dispose();
        }
    }

    internal static class Program
    {
        internal static string InstallRoot
        {
            get { return Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..")); }
        }

        [STAThread]
        private static void Main(string[] args)
        {
            bool startInTray = args != null && Array.IndexOf(args, "--tray") >= 0;
            using (HostControlInstance instance = HostControlInstance.CreateForCurrentUser())
            {
                if (!instance.ShouldRun(startInTray)) return;
                if (startInTray && !ProfileProcessHost.AccountHasProfile(InstallRoot)) return;
                System.Windows.Forms.Application.EnableVisualStyles();
                System.Windows.Application application = new System.Windows.Application();
                application.ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown;
                using (ProfileProcessHost processes = new ProfileProcessHost(InstallRoot))
                {
                    HostControlWindow window = new HostControlWindow(new HostBackend(InstallRoot), processes, null);
                    // An exception escaping to the dispatcher would end the tray, and its job the
                    // profile's Agent; show it in the status bar instead.
                    application.DispatcherUnhandledException += delegate(object sender,
                        System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e)
                    {
                        e.Handled = true;
                        window.ReportUnexpected(e.Exception);
                    };
                    using (new TrayHost(window, instance.ActivationEvent, startInTray))
                    {
                        // Signing out or shutting down ends the tray; its job then ends the profile's processes.
                        application.SessionEnding += delegate { application.Shutdown(); };
                        application.Run();
                        window.Exit();
                    }
                }
            }
        }
    }

    // The tray icon, the global close-session hotkey, its secure-desktop pipe and the
    // activation of a second start; the window itself is HostControlWindow.
    internal sealed class TrayHost : IDisposable
    {
        private const int StreamHotkeyId = 0x4D57;
        private const int WmHotkey = 0x0312;
        private const uint StreamHotkeyModifiers = 0x0001 | 0x0002 | 0x0004 | 0x4000;
        private const uint StreamHotkeyKey = 0x23;
        private static readonly Icon moonWakerIcon = new Icon(
            Assembly.GetExecutingAssembly().GetManifestResourceStream("MoonWaker.Icon.ico"),
            SystemInformation.SmallIconSize);
        private readonly HostControlWindow window;
        private readonly IntPtr handle;
        private readonly HwndSource source;
        private readonly NotifyIcon trayIcon = new NotifyIcon();
        private readonly StreamHotkeyPipe streamHotkeyPipe;
        private readonly HostControlActivationListener activationListener;

        internal TrayHost(HostControlWindow window, WaitHandle activationEvent, bool startInTray)
        {
            this.window = window;
            handle = new WindowInteropHelper(window).EnsureHandle();
            source = HwndSource.FromHwnd(handle);
            source.AddHook(WndProc);
            NativeMethods.RegisterHotKey(handle, StreamHotkeyId, StreamHotkeyModifiers, StreamHotkeyKey);

            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Otwórz MoonWaker Host Control", null, delegate { window.ShowControl(); });
            menu.Items.Add("Zamknij sesję  (Ctrl+Alt+Shift+End)", null, delegate { window.CloseSession(); });
            trayIcon.Icon = moonWakerIcon;
            trayIcon.Text = "MoonWaker Host Control";
            trayIcon.ContextMenuStrip = menu;
            trayIcon.Visible = true;
            trayIcon.DoubleClick += delegate { window.ShowControl(); };
            window.Balloon = delegate(string text, ToolTipIcon icon)
            {
                trayIcon.ShowBalloonTip(icon == ToolTipIcon.Info ? 3000 : 5000,
                    icon == ToolTipIcon.Error ? "MoonWaker — błąd" : "MoonWaker", text, icon);
            };
            streamHotkeyPipe = new StreamHotkeyPipe(window.Dispatcher, window.CloseSession);
            activationListener = new HostControlActivationListener(activationEvent, delegate
            {
                window.Dispatcher.BeginInvoke((Action)window.ShowControl);
            });
            if (!startInTray) window.ShowControl();
        }

        private IntPtr WndProc(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (message == WmHotkey && wParam.ToInt32() == StreamHotkeyId)
            {
                window.CloseSession();
                handled = true;
            }
            return IntPtr.Zero;
        }

        public void Dispose()
        {
            NativeMethods.UnregisterHotKey(handle, StreamHotkeyId);
            source.RemoveHook(WndProc);
            activationListener.Dispose();
            streamHotkeyPipe.Dispose();
            trayIcon.Visible = false;
            trayIcon.Dispose();
        }
    }
    internal sealed class StreamHotkeyPipe : IDisposable
    {
        internal const string PipePrefix = "MoonWakerHostControl.StreamHotkey.";
        private readonly Dispatcher owner;
        private readonly Action action;
        private readonly string pipeName;
        private readonly System.Threading.Thread worker;
        private volatile bool stopping;

        internal StreamHotkeyPipe(Dispatcher owner, Action action)
        {
            this.owner = owner;
            this.action = action;
            pipeName = PipePrefix + Process.GetCurrentProcess().SessionId;
            worker = new System.Threading.Thread(Listen);
            worker.IsBackground = true;
            worker.Name = "MoonWaker stream hotkey";
            worker.Start();
        }

        private void Listen()
        {
            while (!stopping)
            {
                try
                {
                    using (NamedPipeServerStream pipe = new NamedPipeServerStream(pipeName,
                        PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.None,
                        1, 1, CreatePipeSecurity()))
                    {
                        pipe.WaitForConnection();
                        if (!stopping && pipe.ReadByte() == 1)
                            owner.BeginInvoke(action);
                    }
                }
                catch
                {
                    if (!stopping) System.Threading.Thread.Sleep(250);
                }
            }
        }

        private static PipeSecurity CreatePipeSecurity()
        {
            PipeSecurity security = new PipeSecurity();
            security.SetAccessRuleProtection(true, false);
            security.AddAccessRule(new PipeAccessRule(WindowsIdentity.GetCurrent().User,
                PipeAccessRights.FullControl, AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                PipeAccessRights.ReadWrite, AccessControlType.Allow));
            return security;
        }

        public void Dispose()
        {
            stopping = true;
            try
            {
                using (NamedPipeClientStream wake = new NamedPipeClientStream(".", pipeName,
                    PipeDirection.Out))
                {
                    wake.Connect(250);
                    wake.WriteByte(0);
                }
            }
            catch { }
            worker.Join(1000);
        }
    }
}
