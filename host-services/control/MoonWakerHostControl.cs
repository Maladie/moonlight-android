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
using Timer = System.Windows.Forms.Timer;

[assembly: AssemblyVersion("0.7.134.0")]
[assembly: AssemblyFileVersion("0.7.134.0")]
[assembly: AssemblyInformationalVersion("0.7.134+2026.10.15-h1h2")]

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
        internal const string ElevatedStatus = "TRAY JAKO ADMIN";
        internal const string AuthorityLostStatus = "BRAK UPRAWNIEŃ";

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
                if (child.Process != null) return child.Stopping ? "ZATRZYMYWANIE" : null;
                if (child.StartFailed) return "BŁĄD STARTU";
                int seconds = (int)Math.Ceiling((child.NotBeforeUtc - DateTime.UtcNow).TotalSeconds);
                return seconds > 0 ? "RESTART ZA " + seconds + " s" : null;
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
        [STAThread]
        private static void Main(string[] args)
        {
            bool startInTray = args != null && Array.IndexOf(args, "--tray") >= 0;
            using (HostControlInstance instance = HostControlInstance.CreateForCurrentUser())
            {
                if (!instance.ShouldRun(startInTray)) return;
                if (startInTray && !ProfileProcessHost.AccountHasProfile(ControlForm.InstallRoot)) return;
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new ControlForm(startInTray, instance.ActivationEvent));
            }
        }
    }

    internal sealed class ControlForm : Form
    {
        private readonly Color background = Color.FromArgb(17, 20, 28);
        private readonly Color panel = Color.FromArgb(28, 33, 45);
        private readonly Color accent = Color.FromArgb(116, 100, 255);
        private readonly Color muted = Color.FromArgb(164, 171, 193);
        private readonly Color good = Color.FromArgb(129, 226, 169);
        private readonly Color bad = Color.FromArgb(255, 170, 170);
        private readonly Label gatewayState = new Label();
        private readonly Label gatewayDetails = new Label();
        private readonly Label steamConnectionState = new Label();
        private readonly Label epicConnectionState = new Label();
        private readonly Label playniteConnectionState = new Label();
        private readonly Button steamConnectionButton;
        private readonly Button epicConnectionButton;
        private readonly Button playniteConnectionButton;
        private readonly Button integrationsButton;
        private readonly ListView profiles = new ListView();
        private readonly Label footer = new Label();
        private readonly Timer timer = new Timer();
        private static readonly Icon moonWakerIcon = MoonWakerIcon.Create();
        private readonly NotifyIcon trayIcon = new NotifyIcon();
        private readonly StreamHotkeyPipe streamHotkeyPipe;
        private readonly WaitHandle activationEvent;
        private HostControlActivationListener activationListener;
        private readonly bool startInTray;
        private readonly ProfileProcessHost processHost;
        private readonly HostBackend backend = new HostBackend(InstallRoot);
        private readonly bool discordInstalled;
        private bool refreshing;
        private bool sessionClosePending;
        private bool legendaryInstalled;
        private string microphone = "sprawdzam…";
        private readonly string hostVersion;
        private const int StreamHotkeyId = 0x4D57;
        private const int WmHotkey = 0x0312;
        private const uint StreamHotkeyModifiers = 0x0001 | 0x0002 | 0x0004 | 0x4000;
        private const uint StreamHotkeyKey = 0x23;

        internal static string InstallRoot
        {
            get { return Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..")); }
        }

        public ControlForm(bool startInTray, WaitHandle activationEvent)
        {
            this.startInTray = startInTray;
            this.activationEvent = activationEvent;
            processHost = new ProfileProcessHost(InstallRoot);
            discordInstalled = backend.DiscordInstalled;
            hostVersion = ReadHostVersion();
            Text = "MoonWaker Host Control " + hostVersion;
            Icon = moonWakerIcon;
            ClientSize = new Size(1040, 832);
            MinimumSize = new Size(940, 760);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = background;
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);

            GamepadBadge badge = new GamepadBadge();
            badge.SetBounds(34, 19, 52, 52);
            Controls.Add(badge);
            Label title = MakeLabel("MOONWAKER", 24F, FontStyle.Bold, Color.White);
            title.SetBounds(100, 18, 520, 45);
            Controls.Add(title);
            Label subtitle = MakeLabel("HOST CONTROL  •  " + hostVersion, 9F, FontStyle.Bold, muted);
            subtitle.SetBounds(103, 66, 260, 20);
            Controls.Add(subtitle);

            Panel gatewayPanel = NewPanel(34, 96, 972, 172);
            Controls.Add(gatewayPanel);
            Label gatewayTitle = MakeLabel("Gateway komputera", 16F, FontStyle.Bold, Color.White);
            gatewayTitle.SetBounds(24, 18, 300, 30);
            gatewayPanel.Controls.Add(gatewayTitle);
            gatewayState.SetBounds(26, 54, 280, 26);
            gatewayState.Font = new Font("Segoe UI", 11F, FontStyle.Bold);
            gatewayPanel.Controls.Add(gatewayState);
            gatewayDetails.SetBounds(26, 82, 520, 80);
            gatewayDetails.ForeColor = muted;
            gatewayPanel.Controls.Add(gatewayDetails);
            AddActionButton(gatewayPanel, "Napraw", 678, 27, delegate { Repair(); }, 116);
            Button pair = AddActionButton(gatewayPanel, "Sparuj TV", 802, 27, delegate { PairGateway(); }, 142);
            pair.BackColor = accent;
            pair.FlatAppearance.BorderSize = 0;
            AddActionButton(gatewayPanel, "Vibepollo…", 598, 74,
                delegate { LaunchConfigurator("vibepollo", null); }, 150);
            AddActionButton(gatewayPanel, "Eksportuj diagnostykę", 756, 74,
                delegate { ExportDiagnostics(); }, 188);

            Panel connectionsPanel = NewPanel(34, 284, 972, 128);
            Controls.Add(connectionsPanel);
            Label connectionsTitle = MakeLabel("Połączenia bibliotek", 16F, FontStyle.Bold, Color.White);
            connectionsTitle.SetBounds(24, 14, 280, 30);
            connectionsPanel.Controls.Add(connectionsTitle);
            Label connectionsHint = MakeLabel("Dotyczy zaznaczonego profilu Windows.", 9F, FontStyle.Regular, muted);
            connectionsHint.SetBounds(26, 42, 300, 22);
            connectionsPanel.Controls.Add(connectionsHint);

            Label steamTitle = MakeLabel("Steam", 10F, FontStyle.Bold, Color.White);
            steamTitle.SetBounds(330, 18, 120, 22);
            connectionsPanel.Controls.Add(steamTitle);
            steamConnectionState.SetBounds(330, 43, 180, 24);
            steamConnectionState.ForeColor = muted;
            connectionsPanel.Controls.Add(steamConnectionState);
            steamConnectionButton = AddActionButton(connectionsPanel, "Połącz Steam", 330, 74,
                delegate { SteamConnectionClicked(); }, 180);

            Label epicTitle = MakeLabel("Epic przez Legendary", 10F, FontStyle.Bold, Color.White);
            epicTitle.SetBounds(530, 18, 190, 22);
            connectionsPanel.Controls.Add(epicTitle);
            epicConnectionState.SetBounds(530, 43, 180, 24);
            epicConnectionState.ForeColor = muted;
            connectionsPanel.Controls.Add(epicConnectionState);
            epicConnectionButton = AddActionButton(connectionsPanel, "Połącz Epic", 530, 74,
                delegate { EpicConnectionClicked(); }, 180);

            Label playniteTitle = MakeLabel("Playnite (opcjonalnie)", 10F, FontStyle.Bold, Color.White);
            playniteTitle.SetBounds(730, 18, 200, 22);
            connectionsPanel.Controls.Add(playniteTitle);
            playniteConnectionState.SetBounds(730, 43, 190, 24);
            playniteConnectionState.ForeColor = muted;
            connectionsPanel.Controls.Add(playniteConnectionState);
            playniteConnectionButton = AddActionButton(connectionsPanel, "Połącz Playnite", 730, 74,
                delegate { PlayniteConnectionClicked(); }, 190);

            Panel profilePanel = NewPanel(34, 428, 972, 346);
            Controls.Add(profilePanel);
            Label profileTitle = MakeLabel("Profile", 16F, FontStyle.Bold, Color.White);
            profileTitle.SetBounds(24, 16, 400, 32);
            profilePanel.Controls.Add(profileTitle);
            Label profileHint = MakeLabel("Agent profilu działa w trayu konta Windows, które jest jego właścicielem", 9F, FontStyle.Regular, muted);
            profileHint.SetBounds(26, 48, 500, 24);
            profilePanel.Controls.Add(profileHint);
            profiles.SetBounds(24, 82, 924, 136);
            profiles.View = View.Details;
            profiles.FullRowSelect = true;
            profiles.HideSelection = false;
            profiles.MultiSelect = false;
            profiles.BackColor = Color.FromArgb(20, 24, 33);
            profiles.ForeColor = Color.White;
            profiles.BorderStyle = BorderStyle.None;
            profiles.Columns.Add("Profil", 230);
            profiles.Columns.Add("Użytkownik", 250);
            if (discordInstalled) profiles.Columns.Add("Discord", 130);
            profiles.Columns.Add("Agent", 130);
            profiles.Columns.Add("PIN aplikacji", 94);
            profiles.Columns.Add("Logowanie", 88);
            profiles.SelectedIndexChanged += delegate { UpdateConnectionControls(); };
            profilePanel.Controls.Add(profiles);

            Button addProfile = AddActionButton(profilePanel, "Dodaj profil", 24, 232,
                delegate { LaunchConfigurator("add", null); }, 120);
            addProfile.BackColor = accent;
            addProfile.FlatAppearance.BorderSize = 0;
            AddActionButton(profilePanel, "Edytuj profil", 154, 232,
                delegate { LaunchSelectedConfigurator("edit"); }, 120);
            AddActionButton(profilePanel, "Zdalne logowanie", 284, 232,
                delegate { LaunchSelectedConfigurator("remote-sign-in"); }, 150);
            AddActionButton(profilePanel, "Urządzenia i dostęp", 444, 232,
                delegate { LaunchSelectedConfigurator("devices"); }, 165);
            Button remove = AddActionButton(profilePanel, "Usuń profil", 619, 232,
                delegate { LaunchSelectedConfigurator("remove"); }, 120);
            remove.ForeColor = Color.FromArgb(255, 180, 180);
            AddActionButton(profilePanel, "Profile dzieci", 749, 232,
                delegate { LaunchSelectedConfigurator("children"); }, 160);
            integrationsButton = AddActionButton(profilePanel, "Integracje Discorda…", 24, 282,
                delegate { ConfigureIntegrations(); }, 190);
            integrationsButton.Visible = discordInstalled;
            AddActionButton(profilePanel, "Odśwież", 774, 282,
                delegate { RefreshStatus(); ProbeMicrophone(); }, 140);

            footer.SetBounds(36, 790, 968, 36);
            footer.ForeColor = muted;
            Controls.Add(footer);

            ConfigureTrayIcon();
            streamHotkeyPipe = new StreamHotkeyPipe(this, CloseSession);
            // A status read starts no process: files the Gateway publishes and the loopback
            // answers of the hosted processes.
            timer.Interval = 2000;
            timer.Tick += delegate { RefreshStatus(); };
            Shown += delegate
            {
                RefreshStatus();
                ProbeMicrophone();
                timer.Start();
                if (this.startInTray)
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        HideToTray();
                        StartActivationListener();
                    });
                }
                else StartActivationListener();
            };
            Resize += delegate { if (WindowState == FormWindowState.Minimized) HideToTray(); };
        }

        private void ConfigureTrayIcon()
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Otwórz MoonWaker Host Control", null, delegate { ShowControl(); });
            menu.Items.Add("Odśwież status", null, delegate { RefreshStatus(); });
            menu.Items.Add("Zamknij sesję  (Ctrl+Alt+Shift+End)", null,
                delegate { CloseSession(); });
            trayIcon.Icon = moonWakerIcon;
            trayIcon.Text = "MoonWaker Host Control";
            trayIcon.ContextMenuStrip = menu;
            trayIcon.Visible = true;
            trayIcon.DoubleClick += delegate { ShowControl(); };
        }

        private void HideToTray()
        {
            ShowInTaskbar = false;
            Hide();
        }

        private void ShowControl()
        {
            ShowInTaskbar = true;
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
            BringToFront();
        }

        private void StartActivationListener()
        {
            if (activationListener != null) return;
            activationListener = new HostControlActivationListener(activationEvent,
                delegate
                {
                    if (IsDisposed || !IsHandleCreated) return;
                    try
                    {
                        BeginInvoke((MethodInvoker)delegate
                        {
                            if (!IsDisposed && IsHandleCreated) ShowControl();
                        });
                    }
                    catch (InvalidOperationException) { }
                });
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // The tray hosts the profile's Agent, so a user close only hides it.
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                HideToTray();
                return;
            }
            processHost.Dispose();
            if (activationListener != null)
            {
                activationListener.Dispose();
                activationListener = null;
            }
            streamHotkeyPipe.Dispose();
            trayIcon.Visible = false;
            trayIcon.Dispose();
            base.OnFormClosing(e);
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            NativeMethods.RegisterHotKey(Handle, StreamHotkeyId,
                StreamHotkeyModifiers, StreamHotkeyKey);
        }

        protected override void OnHandleDestroyed(EventArgs e)
        {
            NativeMethods.UnregisterHotKey(Handle, StreamHotkeyId);
            base.OnHandleDestroyed(e);
        }

        protected override void WndProc(ref Message message)
        {
            if (message.Msg == WmHotkey && message.WParam.ToInt32() == StreamHotkeyId)
                CloseSession();
            base.WndProc(ref message);
        }

        // The tray menu, the global hotkey and its secure-desktop pipe all end the session
        // the same way: the Gateway closes the stream and the game on its own record.
        private async void CloseSession()
        {
            if (sessionClosePending) return;
            sessionClosePending = true;
            footer.Text = "Zamykam sesję…";
            try
            {
                CloseSessionResult result = await Task.Run(delegate { return backend.CloseSession(25); });
                string text = CloseSessionMessage(result.Reason);
                if (!result.Ok) throw new InvalidOperationException(text);
                footer.Text = text;
                // The stream is closed, but the game may still run: a warning, not a success.
                trayIcon.ShowBalloonTip(3000, "MoonWaker", text,
                    result.Reason == "stream_closed_game_unconfirmed"
                        ? ToolTipIcon.Warning : ToolTipIcon.Info);
            }
            catch (Exception ex)
            {
                footer.Text = ex.Message;
                trayIcon.ShowBalloonTip(5000, "MoonWaker — błąd", ex.Message,
                    ToolTipIcon.Error);
            }
            finally { sessionClosePending = false; }
        }

        internal static string CloseSessionMessage(string reason)
        {
            switch (reason)
            {
                case "stream_and_game_closed": return "Zamknięto stream i grę.";
                case "stream_closed": return "Zamknięto stream.";
                case "game_closed": return "Zamknięto grę.";
                case "stream_closed_game_unconfirmed":
                    return "Zamknięto stream. Nie udało się potwierdzić procesu gry — gra mogła pozostać uruchomiona na hoście.";
                case "session_closed": return "Zamknięto sesję dziecka: grę i stream.";
                case "nothing_to_close": return "Nie ma sesji do zamknięcia.";
                case "operation_in_progress":
                    return "Trwa inna operacja na hoście (start albo zamykanie z TV). Spróbuj ponownie za chwilę.";
                case "session_on_other_profile":
                    return "Sesja działa na innym profilu Windows. Zamknij ją z tamtego konta.";
                case "game_state_unverified":
                    return "Nie udało się potwierdzić procesu gry, a MoonWaker nie prowadzi streamu, więc niczego nie zamknięto.";
                case "game_stop_unconfirmed":
                    return "Gra nie potwierdziła zamknięcia. Stream pozostał otwarty.";
                case "streamer_identity_unconfirmed":
                    return "W streamerze działa aplikacja albo klient spoza tej sesji MoonWaker. Niczego nie zamknięto: gra i stream pozostały.";
                case "game_closed_stream_unconfirmed":
                    return "Zamknięto grę, ale streamer nie potwierdził zamknięcia streamu — stream mógł pozostać otwarty.";
                case "streamer_close_unconfirmed":
                    return "Streamer nie potwierdził zamknięcia streamu.";
                case "streamer_snapshot_unavailable":
                    return "Nie udało się odczytać stanu streamera. Niczego nie zamknięto.";
                case "child_cleanup_required":
                    return "Nie udało się dokończyć zamykania sesji dziecka. Spróbuj ponownie.";
                case "gateway_not_responding":
                    return "Gateway nie odpowiada. Sprawdź, czy usługa MoonWaker Gateway działa. Sesja nie została zamknięta.";
                case "result_timeout":
                    return "Gateway przyjął żądanie, ale nie potwierdził zamknięcia sesji.";
                case "profile_ambiguous":
                    return "Nie można jednoznacznie ustalić profilu tego konta Windows.";
                case "registry_unreadable":
                    return "Nie można odczytać rejestru Gatewaya (gateway.json), więc sesja nie została zamknięta. Spróbuj ponownie; jeśli błąd się powtarza, zainstaluj ponownie MoonWaker Host.";
                case "channel_missing":
                    return "Ten Gateway nie obsługuje zamykania sesji z hosta. Zainstaluj ponownie MoonWaker Host.";
                default: return "Gateway odmówił zamknięcia sesji (" + reason + ").";
            }
        }

        private Panel NewPanel(int left, int top, int width, int height)
        {
            Panel value = new Panel();
            value.SetBounds(left, top, width, height);
            value.BackColor = panel;
            return value;
        }

        private Label MakeLabel(string text, float size, FontStyle style, Color color)
        {
            Label value = new Label();
            value.Text = text;
            value.Font = new Font("Segoe UI", size, style);
            value.ForeColor = color;
            value.BackColor = Color.Transparent;
            return value;
        }

        private Button AddActionButton(Control parent, string text, int left, int top, EventHandler handler, int width)
        {
            Button button = new Button();
            button.Text = text;
            button.SetBounds(left, top, width, 38);
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderColor = Color.FromArgb(77, 85, 109);
            button.BackColor = panel;
            button.ForeColor = Color.White;
            button.Cursor = Cursors.Hand;
            button.Click += handler;
            parent.Controls.Add(button);
            return button;
        }

        private async void RefreshStatus()
        {
            if (refreshing) return;
            refreshing = true;
            try
            {
                HostStatus status = await Task.Run(delegate { return backend.ReadStatus(); });
                RenderStatus(status);
            }
            catch (Exception ex)
            {
                gatewayState.Text = "Status niedostępny";
                gatewayState.ForeColor = bad;
                footer.Text = ex.Message;
            }
            finally { refreshing = false; }
        }

        // The microphone worker's probe starts a process: once at start and on "Odśwież".
        private async void ProbeMicrophone()
        {
            string reason = await Task.Run(delegate { return backend.ProbeMicrophone(); });
            microphone = reason == "ready" ? "gotowy"
                : reason == "worker_missing" ? "brak renderera" : "problem z endpointem Steam";
            RefreshStatus();
        }

        private void RenderStatus(HostStatus status)
        {
            GatewayStatus gateway = status.Gateway;
            gatewayState.Text = !gateway.Installed ? "● NIE ZAINSTALOWANO"
                : gateway.State == "running" ? "● ONLINE"
                : gateway.State == "starting" ? "● URUCHAMIANIE"
                : gateway.State == "recovering" ? "● PONOWNY START" : "● ZATRZYMANY";
            gatewayState.ForeColor = gateway.State == "running" ? good : bad;
            string versionLine = gateway.State != "running"
                ? "Wersja: " + status.InstalledVersion
                : "Wersja: " + status.InstalledVersion + " / działa: " + gateway.RuntimeVersion;
            if (gateway.VersionMismatch) versionLine += "  ⚠ Różnica wersji — użyj „Napraw”";
            legendaryInstalled = status.LegendaryInstalled;
            gatewayDetails.Text = "Port " + gateway.Port + "  •  sparowane urządzenia: " +
                gateway.PairedClients + (gateway.PairingSeconds > 0
                    ? "  •  parowanie aktywne jeszcze " + (gateway.PairingSeconds + 59) / 60 + " min" : "") +
                "\n" + versionLine + "\nVibepollo: " +
                (status.VibepolloTokenSaved ? "token komputera zapisany" : "brak tokenu — użyj „Vibepollo…”") +
                "\nLegendary: " + (legendaryInstalled ? "jest" : "brak") + "  •  Mikrofon: " + microphone;

            string selected = SelectedProfileId();
            profiles.BeginUpdate();
            profiles.Items.Clear();
            foreach (ProfileStatus profile in status.Profiles)
            {
                ListViewItem row = new ListViewItem(profile.Name);
                row.Name = profile.Id;
                row.Tag = profile;
                row.SubItems.Add(profile.Owner.Length == 0 ? "—" : profile.Owner);
                if (discordInstalled) row.SubItems.Add(ProcessStatus(profile.Id, profile.Discord, true));
                row.SubItems.Add(ProcessStatus(profile.Id, profile.Agent, false));
                row.SubItems.Add(profile.PinRequired ? "WŁ." : "WYŁ.");
                // The Login Broker's state is the Configurator's to read (elevated).
                row.SubItems.Add(profile.RemoteSignInEnabled ? "SPRAWDŹ" : "WYŁ.");
                profiles.Items.Add(row);
                if (profile.Id == selected) row.Selected = true;
            }
            profiles.EndUpdate();
            if (profiles.SelectedItems.Count == 0 && profiles.Items.Count > 0)
                profiles.Items[0].Selected = true;
            UpdateConnectionControls();
            footer.Text = "Status odświeżony: " + DateTime.Now.ToString("HH:mm:ss") +
                ". Operacje dotyczące innych kont wymagają zalogowania na ich konto Windows.";
        }

        // The tray's own process host first; a running child is then judged by its answer.
        private string ProcessStatus(string id, string health, bool discordChild)
        {
            string hosted = processHost.Describe(id, discordChild);
            if (hosted != null) return hosted;
            if (health == "online") return "ONLINE";
            if (health == "disabled") return "WYŁ.";
            if (health == "error") return "BŁĄD";
            return "OFFLINE";
        }

        private string SelectedProfileId()
        {
            if (profiles.SelectedItems.Count == 0) return null;
            return profiles.SelectedItems[0].Name;
        }

        private ProfileStatus SelectedProfile()
        {
            if (profiles.SelectedItems.Count == 0) return null;
            return profiles.SelectedItems[0].Tag as ProfileStatus;
        }

        private void UpdateConnectionControls()
        {
            ProfileStatus profile = SelectedProfile();
            Label[] states = { steamConnectionState, epicConnectionState, playniteConnectionState };
            if (profile == null)
            {
                foreach (Label state in states)
                {
                    state.Text = "Wybierz profil";
                    state.ForeColor = muted;
                }
                steamConnectionButton.Enabled = false;
                epicConnectionButton.Enabled = false;
                playniteConnectionButton.Enabled = false;
                integrationsButton.Enabled = false;
                return;
            }
            bool available = profile.CurrentUser;
            ShowConnection(steamConnectionState, steamConnectionButton, profile.SteamConnected, available, "Steam");
            ShowConnection(epicConnectionState, epicConnectionButton, profile.EpicConnected, available, "Epic");
            ShowConnection(playniteConnectionState, playniteConnectionButton, profile.PlayniteConnected,
                available, "Playnite");
            epicConnectionButton.Enabled = available && legendaryInstalled;
            integrationsButton.Enabled = available;
            if (!available)
                foreach (Label state in states) state.Text = "Zaloguj się na ten profil";
            else if (!legendaryInstalled) epicConnectionState.Text = "Brak Legendary — zaktualizuj Host";
        }

        private void ShowConnection(Label state, Button button, bool connected, bool available, string name)
        {
            state.Text = connected ? "POŁĄCZONO" : "NIEPOŁĄCZONO";
            state.ForeColor = connected ? good : (available ? muted : bad);
            button.Text = (connected ? "Odłącz " : "Połącz ") + name;
            button.Enabled = available;
        }

        // One "Napraw": the Gateway service and this account's hosted processes, where needed.
        private async void Repair()
        {
            footer.Text = "Naprawiam…";
            try
            {
                RepairResult result = await Task.Run(delegate { return backend.Repair(processHost); });
                string restarted = result.Restarted.Count == 0 ? "" :
                    "Uruchomiono ponownie: " + String.Join(", ", result.Restarted.ToArray()) + ".";
                if (result.Problem != null)
                    MessageBox.Show(this, (restarted + "\n\n" + result.Problem).Trim(), "Napraw",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                else footer.Text = restarted.Length > 0 ? restarted :
                    "Gateway i procesy profilu tego konta działają; nie było czego naprawiać.";
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "Napraw", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            RefreshStatus();
        }

        private void SteamConnectionClicked()
        {
            ProfileStatus profile = SelectedProfile();
            if (profile == null) return;
            if (!profile.SteamConnected)
            {
                ConfigureSteamWebApi(profile.Id);
                return;
            }
            string warning = "Odłączenie Steam usunie zaszyfrowany klucz Web API dla tego profilu. " +
                "MoonWaker nie będzie mógł odświeżać pełnej biblioteki Steam. Lokalne manifesty nadal " +
                "potwierdzą zainstalowane gry, a ostatni snapshot może pozostać widoczny.\n\nKontynuować?";
            if (MessageBox.Show(this, warning, "Odłącz Steam", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) == DialogResult.Yes)
                RunPlatformAction(delegate { backend.RemoveSteamWebApiKey(profile.Id); },
                    "Steam został odłączony.", false);
        }

        private void ConfigureSteamWebApi(string id)
        {
            using (SteamApiKeyDialog dialog = new SteamApiKeyDialog(id))
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                string key = dialog.ApiKey;
                RunPlatformAction(delegate { backend.SaveSteamWebApiKey(id, key); },
                    "Klucz został zapisany dla profilu " + id + " i zabezpieczony przez Windows DPAPI.", false);
            }
        }

        private void EpicConnectionClicked()
        {
            ProfileStatus profile = SelectedProfile();
            if (profile == null) return;
            if (profile.EpicConnected)
            {
                string warning = "Odłączenie Epic usunie uwierzytelnienie Legendary. MoonWaker nie będzie " +
                    "odświeżać katalogu Epic ani uruchamiać, instalować lub odinstalowywać gier Epic do " +
                    "ponownego połączenia. Pliki gier nie zostaną usunięte.\n\nKontynuować?";
                if (MessageBox.Show(this, warning, "Odłącz Epic", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning) == DialogResult.Yes)
                    RunPlatformAction(delegate { backend.DisconnectEpic(profile.Id); },
                        "Epic został odłączony od Legendary.", false);
                return;
            }
            string explanation = "Obsługa Epic działa przez Legendary, nie przez Epic Games Launcher. " +
                "Legendary utrzymuje własny katalog i stan instalacji; gry nie będą synchronizowane z " +
                "Epic Games Launcherem.\n\nLogowanie otworzy interaktywny proces Legendary. MoonWaker nie " +
                "przechwytuje hasła Epic. Kontynuować?";
            if (MessageBox.Show(this, explanation, "Połącz Epic", MessageBoxButtons.YesNo,
                MessageBoxIcon.Information) == DialogResult.Yes)
                RunPlatformAction(delegate { backend.ConnectEpic(profile.Id); },
                    "Epic został połączony przez Legendary.", false);
        }

        private void PlayniteConnectionClicked()
        {
            ProfileStatus profile = SelectedProfile();
            if (profile == null) return;
            if (profile.PlayniteConnected)
            {
                if (MessageBox.Show(this,
                        "Odłączyć opcjonalny katalog Playnite? Steam i Epic nadal będą działać bez zmian.",
                        "Odłącz Playnite", MessageBoxButtons.YesNo,
                        MessageBoxIcon.Warning) == DialogResult.Yes)
                    RunPlatformAction(delegate { backend.DisconnectPlaynite(profile.Id); },
                        "Playnite został odłączony.", true);
                return;
            }
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Wybierz katalog instalacji Playnite z zainstalowanym SunshinePlaynite Connector.";
                dialog.ShowNewFolderButton = false;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                string directory = dialog.SelectedPath;
                RunPlatformAction(delegate { backend.ConnectPlaynite(profile.Id, directory); },
                    "Playnite został połączony z profilem. Uruchom ponownie Playnite, aby wczytał łatkę MoonWaker.",
                    true);
            }
        }

        // Saving what the Agent or Discord Bridge reads at start restarts the hosted processes.
        private async void RunPlatformAction(Action action, string successMessage, bool restartProcesses)
        {
            try
            {
                footer.Text = "Wykonuję operację…";
                await Task.Run(action);
                if (restartProcesses) processHost.RestartAll();
                MessageBox.Show(this, successMessage, "MoonWaker Host Control",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Control",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            RefreshStatus();
        }

        private async void PairGateway()
        {
            try
            {
                string code = await Task.Run(delegate { return backend.CreatePairingCode(); });
                MessageBox.Show(this, "Kod parowania:\n\n" + code + "\n\nKod jest ważny przez 10 minut.",
                    "Parowanie MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex) { MessageBox.Show(this, ex.Message, "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Error); }
            RefreshStatus();
        }

        private async void ExportDiagnostics()
        {
            try
            {
                footer.Text = "Eksportuję diagnostykę…";
                DiagnosticsExport export = await Task.Run(delegate { return backend.ExportDiagnostics(null); });
                MessageBox.Show(this, "Pakiet diagnostyczny zapisano w:\n\n" + export.Path,
                    "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Control",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            RefreshStatus();
        }

        private void ConfigureIntegrations()
        {
            ProfileStatus profile = SelectedProfile();
            if (profile == null) return;
            if (!profile.CurrentUser)
            {
                MessageBox.Show(this,
                    "Zaloguj się na konto Windows właściciela tego profilu, aby skonfigurować Discord.",
                    "Integracje Discorda", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            using (IntegrationsDialog dialog = new IntegrationsDialog(profile.Id, profile.DiscordConfigured))
            {
                DialogResult choice = dialog.ShowDialog(this);
                if (choice == DialogResult.Abort)
                {
                    RunPlatformAction(delegate { backend.DisconnectDiscord(profile.Id); },
                        "Discord został odłączony od profilu.", true);
                    return;
                }
                if (choice != DialogResult.OK) return;
                string clientId = dialog.ClientId;
                string secret = dialog.TakeSecret();
                RunPlatformAction(delegate { backend.ConfigureDiscord(profile.Id, clientId, secret); },
                    "Dane Discorda zostały zapisane. Autoryzację OAuth zatwierdzisz w aplikacji Discord na tym komputerze przy pierwszym użyciu Discorda z TV.",
                    true);
            }
        }

        private void LaunchSelectedConfigurator(string mode)
        {
            string id = SelectedProfileId();
            if (String.IsNullOrWhiteSpace(id)) { MessageBox.Show(this, "Wybierz profil.", "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            LaunchConfigurator(mode, id);
        }

        private async void LaunchConfigurator(string mode, string profileId)
        {
            string executable = Path.Combine(AppDomain.CurrentDomain.BaseDirectory,
                "MoonWakerHostConfigurator.exe");
            if (!File.Exists(executable))
            {
                MessageBox.Show(this,
                    "Brak bezpiecznego konfiguratora hosta. Zaktualizuj instalację MoonWaker Host.",
                    "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            if (!String.IsNullOrWhiteSpace(profileId) &&
                !System.Text.RegularExpressions.Regex.IsMatch(profileId, "^[A-Za-z0-9._-]{1,64}$"))
            {
                MessageBox.Show(this, "Identyfikator profilu jest nieprawidłowy.",
                    "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            try
            {
                footer.Text = "Otwieram chroniony konfigurator…";
                string arguments = "--mode " + Quote(mode) +
                    (String.IsNullOrWhiteSpace(profileId) ? "" : " --profile " + Quote(profileId));
                ProcessStartInfo info = new ProcessStartInfo(executable, arguments);
                info.UseShellExecute = true;
                info.Verb = "runas";
                info.WorkingDirectory = AppDomain.CurrentDomain.BaseDirectory;
                using (Process process = Process.Start(info))
                    await Task.Run(delegate { process.WaitForExit(); });
            }
            catch (System.ComponentModel.Win32Exception ex)
            {
                if (ex.NativeErrorCode != 1223)
                    MessageBox.Show(this, ex.Message, "MoonWaker Host Control",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            RefreshStatus();
        }

        private static string ReadHostVersion()
        {
            try
            {
                string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "version.json");
                string text = File.ReadAllText(Path.GetFullPath(path), Encoding.UTF8);
                System.Text.RegularExpressions.Match match = System.Text.RegularExpressions.Regex.Match(
                    text, "\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                if (match.Success) return "v" + match.Groups[1].Value;
            }
            catch { }
            return "v?";
        }

        private static string Quote(string value) { return "\"" + value.Replace("\"", "\\\"") + "\""; }
    }

    internal sealed class StreamHotkeyPipe : IDisposable
    {
        internal const string PipePrefix = "MoonWakerHostControl.StreamHotkey.";
        private readonly Control owner;
        private readonly Action action;
        private readonly string pipeName;
        private readonly System.Threading.Thread worker;
        private volatile bool stopping;

        internal StreamHotkeyPipe(Control owner, Action action)
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
                            owner.BeginInvoke((MethodInvoker)delegate { action(); });
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

    // Discord application data of the selected profile: Zapisz, or Odłącz (DialogResult.Abort)
    // when the profile has Discord configured.
    internal sealed class IntegrationsDialog : Form
    {
        private readonly bool discordWasConfigured;
        private readonly TextBox discordId = new TextBox();
        private readonly TextBox discordSecret = new TextBox();

        internal string ClientId { get { return discordId.Text.Trim(); } }

        internal string TakeSecret()
        {
            string value = discordSecret.Text;
            discordSecret.Clear();
            return value;
        }

        internal IntegrationsDialog(string profileId, bool discordConfigured)
        {
            discordWasConfigured = discordConfigured;
            Text = "Integracje Discorda";
            Icon = MoonWakerIcon.Create();
            ClientSize = new Size(720, 270);
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            BackColor = Color.FromArgb(17, 20, 28);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);
            AutoScaleMode = AutoScaleMode.Dpi;

            AddLabel("Discord", 26, 18, 620, 34, 16F, FontStyle.Bold, Color.White);
            AddLabel("Profil: " + profileId + ". Sekrety są zapisywane dla aktualnego konta Windows. " +
                (discordConfigured ? "Discord jest skonfigurowany; puste pola zachowają dane."
                    : "Discord nie jest skonfigurowany."),
                28, 55, 660, 40, 9F, FontStyle.Regular, Color.FromArgb(164, 171, 193));
            AddLabel("Client ID", 30, 100, 280, 22, 9F, FontStyle.Regular, Color.White);
            AddLabel("Client Secret", 365, 100, 280, 22, 9F, FontStyle.Regular, Color.White);
            ConfigureBox(discordId, 30, 124, 315, false);
            ConfigureBox(discordSecret, 365, 124, 325, true);
            AddLabel("Przy pierwszej konfiguracji podaj oba pola. Discord poprosi o autoryzację OAuth przy pierwszym użyciu z TV.",
                30, 162, 660, 34, 8.7F, FontStyle.Regular, Color.FromArgb(164, 171, 193));

            if (discordConfigured)
            {
                Button disconnect = NewButton("Odłącz", 30, 214, 108, Color.FromArgb(28, 33, 45));
                disconnect.ForeColor = Color.FromArgb(255, 180, 180);
                disconnect.Click += delegate
                {
                    if (MessageBox.Show(this, "Usunąć token OAuth i Client Secret Discorda dla profilu " +
                            profileId + "? Discord Bridge tego profilu przestanie działać.",
                            "Odłącz Discord", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
                        return;
                    DialogResult = DialogResult.Abort;
                    Close();
                };
            }
            Button save = NewButton("Zapisz", 468, 214, 102, Color.FromArgb(116, 100, 255));
            save.FlatAppearance.BorderSize = 0;
            save.Click += SaveClicked;
            Button cancel = NewButton("Anuluj", 582, 214, 108, Color.FromArgb(28, 33, 45));
            cancel.DialogResult = DialogResult.Cancel;
            AcceptButton = save;
            CancelButton = cancel;
            FormClosed += delegate { if (DialogResult != DialogResult.OK) discordSecret.Clear(); };
        }

        private void SaveClicked(object sender, EventArgs e)
        {
            bool hasId = !String.IsNullOrWhiteSpace(discordId.Text);
            bool hasSecret = !String.IsNullOrWhiteSpace(discordSecret.Text);
            if (hasId != hasSecret || (!discordWasConfigured && !hasId))
            {
                MessageBox.Show(this, "Przy pierwszej konfiguracji Discorda podaj Client ID i Client Secret.",
                    "Integracje Discorda", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            if (hasId && !System.Text.RegularExpressions.Regex.IsMatch(ClientId, "^[0-9]{17,20}$"))
            {
                MessageBox.Show(this, "Discord Client ID musi zawierać od 17 do 20 cyfr.",
                    "Integracje Discorda", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            DialogResult = DialogResult.OK;
            Close();
        }

        private Button NewButton(string text, int x, int y, int width, Color color)
        {
            Button button = new Button();
            button.Text = text;
            button.SetBounds(x, y, width, 38);
            button.BackColor = color;
            button.ForeColor = Color.White;
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderColor = Color.FromArgb(77, 85, 109);
            Controls.Add(button);
            return button;
        }

        private void AddLabel(string text, int x, int y, int width, int height,
            float size, FontStyle style, Color color)
        {
            Label label = new Label();
            label.Text = text;
            label.SetBounds(x, y, width, height);
            label.Font = new Font("Segoe UI", size, style);
            label.ForeColor = color;
            Controls.Add(label);
        }

        private void ConfigureBox(TextBox box, int x, int y, int width, bool secret)
        {
            box.SetBounds(x, y, width, 31);
            box.BackColor = Color.FromArgb(28, 33, 45);
            box.ForeColor = Color.White;
            box.BorderStyle = BorderStyle.FixedSingle;
            box.UseSystemPasswordChar = secret;
            Controls.Add(box);
        }
    }

    internal sealed class SteamApiKeyDialog : Form
    {
        private readonly TextBox keyBox = new TextBox();

        public string ApiKey { get { return keyBox.Text.Trim(); } }

        public SteamApiKeyDialog(string profileId)
        {
            Text = "Klucz Steam Web API";
            Icon = MoonWakerIcon.Create();
            ClientSize = new Size(620, 236);
            MinimumSize = new Size(620, 275);
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            BackColor = Color.FromArgb(17, 20, 28);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);
            AutoScaleMode = AutoScaleMode.Dpi;

            Label title = new Label();
            title.Text = "Połącz klucz Steam Web API";
            title.Font = new Font("Segoe UI", 14F, FontStyle.Bold);
            title.SetBounds(24, 20, 440, 30);
            Controls.Add(title);

            Label profile = new Label();
            profile.Text = "Profil: " + profileId;
            profile.ForeColor = Color.FromArgb(164, 171, 193);
            profile.SetBounds(26, 54, 500, 24);
            Controls.Add(profile);

            Label prompt = new Label();
            prompt.Text = "32-znakowy klucz Steam Web API";
            prompt.SetBounds(26, 88, 360, 22);
            Controls.Add(prompt);

            keyBox.Name = "SteamApiKeyInput";
            keyBox.SetBounds(26, 112, 500, 32);
            keyBox.UseSystemPasswordChar = true;
            keyBox.MaxLength = 32;
            keyBox.Font = new Font("Consolas", 11F);
            keyBox.BackColor = Color.FromArgb(28, 33, 45);
            keyBox.ForeColor = Color.White;
            keyBox.BorderStyle = BorderStyle.FixedSingle;
            Controls.Add(keyBox);

            Button help = new Button();
            help.Name = "SteamApiHelpButton";
            help.Text = "?";
            help.AccessibleName = "Jak uzyskać klucz Steam Web API";
            help.AccessibleDescription = "Pokazuje, jak zarejestrować klucz i jak MoonWaker go przechowuje.";
            help.Font = new Font("Segoe UI", 13F, FontStyle.Bold);
            help.SetBounds(536, 109, 46, 38);
            help.FlatStyle = FlatStyle.Flat;
            help.FlatAppearance.BorderColor = Color.FromArgb(116, 100, 255);
            help.BackColor = Color.FromArgb(28, 33, 45);
            help.ForeColor = Color.White;
            help.Cursor = Cursors.Hand;
            help.Click += delegate
            {
                using (SteamApiHelpForm instructions = new SteamApiHelpForm())
                    instructions.ShowDialog(this);
            };
            Controls.Add(help);
            ToolTip tip = new ToolTip();
            tip.SetToolTip(help, "Jak uzyskać i przechować klucz Steam Web API");

            Label storage = new Label();
            storage.Text = "Klucz jest zapisywany dla tego konta Windows przez DPAPI; Gateway nigdy go nie zwraca.";
            storage.ForeColor = Color.FromArgb(164, 171, 193);
            storage.SetBounds(26, 152, 560, 34);
            Controls.Add(storage);

            Button save = new Button();
            save.Text = "Zapisz klucz";
            save.DialogResult = DialogResult.None;
            save.SetBounds(354, 190, 108, 36);
            save.BackColor = Color.FromArgb(116, 100, 255);
            save.ForeColor = Color.White;
            save.FlatStyle = FlatStyle.Flat;
            save.FlatAppearance.BorderSize = 0;
            save.Click += delegate
            {
                if (!System.Text.RegularExpressions.Regex.IsMatch(
                    keyBox.Text.Trim(), "^[A-Fa-f0-9]{32}$"))
                {
                    MessageBox.Show(this,
                        "Wpisz dokładnie 32 znaki szesnastkowe.", "Steam Web API",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    keyBox.Focus();
                    return;
                }
                DialogResult = DialogResult.OK;
                Close();
            };
            Controls.Add(save);

            Button cancel = new Button();
            cancel.Text = "Anuluj";
            cancel.DialogResult = DialogResult.Cancel;
            cancel.SetBounds(474, 190, 108, 36);
            cancel.FlatStyle = FlatStyle.Flat;
            cancel.FlatAppearance.BorderColor = Color.FromArgb(77, 85, 109);
            cancel.BackColor = Color.FromArgb(28, 33, 45);
            cancel.ForeColor = Color.White;
            Controls.Add(cancel);

            AcceptButton = save;
            CancelButton = cancel;
            Shown += delegate { keyBox.Focus(); };
        }
    }

    internal sealed class SteamApiHelpForm : Form
    {
        private const string RegistrationUrl = "https://steamcommunity.com/dev/apikey";
        private const string Instructions =
            "JAK UZYSKAĆ KLUCZ STEAM WEB API\r\n\r\n" +
            "1. Otwórz oficjalną stronę rejestracji Steam:\r\n" + RegistrationUrl + "\r\n\r\n" +
            "2. Zaloguj się na konto Steam, którego bibliotekę ma czytać MoonWaker.\r\n\r\n" +
            "3. W polu Domain Name wpisz localhost (integracja lokalna).\r\n\r\n" +
            "4. Przeczytaj i zaakceptuj Steam Web API Terms of Use, a potem zarejestruj klucz.\r\n\r\n" +
            "5. Skopiuj wygenerowany 32-znakowy klucz do maskowanego pola w Host Control.\r\n\r\n" +
            "PRZECHOWYWANIE I BEZPIECZEŃSTWO\r\n\r\n" +
            "• Klucz jest zapisywany w wybranym profilu MoonWaker jako steam-web-api-key.dpapi.\r\n" +
            "• Windows DPAPI szyfruje go dla konta Windows, które jest właścicielem profilu.\r\n" +
            "• Klucza nie ma w config.json, argumentach wiersza poleceń, logach, plikach audytu ani odpowiedziach Gatewaya.\r\n" +
            "• MoonWaker wysyła go tylko do api.steampowered.com przez HTTPS ze zwykłą weryfikacją certyfikatu.\r\n" +
            "• Host Control nigdy nie pokazuje zapisanego klucza. Wpisanie nowego klucza zastępuje poprzedni.\r\n" +
            "• Nie wklejaj klucza na czacie i nie udostępniaj go. Jeśli wycieknie, unieważnij go na stronie rejestracji Steam i utwórz nowy.";

        public SteamApiHelpForm()
        {
            Text = "Pomoc: klucz Steam Web API";
            Icon = MoonWakerIcon.Create();
            ClientSize = new Size(760, 620);
            MinimumSize = new Size(640, 520);
            StartPosition = FormStartPosition.CenterParent;
            BackColor = Color.FromArgb(17, 20, 28);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 10F);
            AutoScaleMode = AutoScaleMode.Dpi;

            TableLayoutPanel layout = new TableLayoutPanel();
            layout.Name = "SteamApiHelpLayout";
            layout.Dock = DockStyle.Fill;
            layout.Padding = new Padding(20);
            layout.RowCount = 2;
            layout.ColumnCount = 1;
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
            Controls.Add(layout);

            TextBox text = new TextBox();
            text.Name = "SteamApiHelpText";
            text.Text = Instructions;
            text.Multiline = true;
            text.ReadOnly = true;
            text.WordWrap = true;
            text.ScrollBars = ScrollBars.Vertical;
            text.Dock = DockStyle.Fill;
            text.BackColor = Color.FromArgb(28, 33, 45);
            text.ForeColor = Color.White;
            text.BorderStyle = BorderStyle.FixedSingle;
            text.Font = new Font("Segoe UI", 10.5F);
            text.TabStop = false;
            layout.Controls.Add(text, 0, 0);

            FlowLayoutPanel buttons = new FlowLayoutPanel();
            buttons.Dock = DockStyle.Fill;
            buttons.FlowDirection = FlowDirection.RightToLeft;
            buttons.WrapContents = false;
            buttons.Padding = new Padding(0, 8, 0, 0);
            layout.Controls.Add(buttons, 0, 1);

            Button close = new Button();
            close.Text = "Zamknij";
            close.DialogResult = DialogResult.OK;
            close.Size = new Size(100, 34);
            close.FlatStyle = FlatStyle.Flat;
            close.BackColor = Color.FromArgb(28, 33, 45);
            close.ForeColor = Color.White;
            buttons.Controls.Add(close);

            Button open = new Button();
            open.Text = "Otwórz stronę klucza Steam";
            open.Size = new Size(220, 34);
            open.FlatStyle = FlatStyle.Flat;
            open.FlatAppearance.BorderColor = Color.FromArgb(116, 100, 255);
            open.BackColor = Color.FromArgb(28, 33, 45);
            open.ForeColor = Color.White;
            open.Click += delegate
            {
                try
                {
                    Process.Start(new ProcessStartInfo(RegistrationUrl) { UseShellExecute = true });
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "Steam Web API",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            };
            buttons.Controls.Add(open);
            AcceptButton = close;
            CancelButton = close;
        }
    }

    internal sealed class GamepadBadge : Control
    {
        public GamepadBadge()
        {
            DoubleBuffered = true;
            BackColor = Color.FromArgb(17, 20, 28);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using (SolidBrush body = new SolidBrush(Color.FromArgb(44, 51, 69)))
            using (SolidBrush accent = new SolidBrush(Color.FromArgb(116, 100, 255)))
            using (SolidBrush light = new SolidBrush(Color.FromArgb(220, 225, 240)))
            {
                e.Graphics.FillRoundRectangle(body, new Rectangle(1, 8, 50, 35), 10);
                e.Graphics.FillRectangle(light, new Rectangle(13, 22, 10, 3));
                e.Graphics.FillRectangle(light, new Rectangle(16, 19, 3, 10));
                e.Graphics.FillEllipse(accent, 32, 18, 6, 6);
                e.Graphics.FillEllipse(accent, 40, 24, 6, 6);
            }
        }
    }

    internal static class GraphicsExtensions
    {
        public static void FillRoundRectangle(this System.Drawing.Graphics graphics, System.Drawing.Brush brush,
            System.Drawing.Rectangle rectangle, int radius)
        {
            using (System.Drawing.Drawing2D.GraphicsPath path = new System.Drawing.Drawing2D.GraphicsPath())
            {
                int diameter = radius * 2;
                path.AddArc(rectangle.Left, rectangle.Top, diameter, diameter, 180, 90);
                path.AddArc(rectangle.Right - diameter, rectangle.Top, diameter, diameter, 270, 90);
                path.AddArc(rectangle.Right - diameter, rectangle.Bottom - diameter, diameter, diameter, 0, 90);
                path.AddArc(rectangle.Left, rectangle.Bottom - diameter, diameter, diameter, 90, 90);
                path.CloseFigure();
                graphics.FillPath(brush, path);
            }
        }
    }

    internal static class MoonWakerIcon
    {
        public static Icon Create()
        {
            using (Bitmap bitmap = new Bitmap(32, 32))
            using (Graphics graphics = Graphics.FromImage(bitmap))
            using (SolidBrush body = new SolidBrush(Color.FromArgb(116, 100, 255)))
            using (SolidBrush detail = new SolidBrush(Color.FromArgb(240, 242, 255)))
            {
                // MakeTransparent creates the AND mask used by the legacy
                // Windows notification-area API.  An alpha-only bitmap from
                // GetHicon otherwise appears as a black square in the tray.
                graphics.Clear(Color.Fuchsia);
                graphics.FillPolygon(body, new Point[] {
                    new Point(3, 15), new Point(7, 9), new Point(11, 9), new Point(14, 11),
                    new Point(18, 11), new Point(21, 9), new Point(25, 9), new Point(29, 15),
                    new Point(27, 22), new Point(23, 24), new Point(20, 19), new Point(12, 19),
                    new Point(9, 24), new Point(5, 22) });
                graphics.FillRectangle(detail, new Rectangle(8, 14, 7, 2));
                graphics.FillRectangle(detail, new Rectangle(10, 12, 2, 6));
                graphics.FillRectangle(detail, new Rectangle(21, 13, 3, 3));
                graphics.FillRectangle(detail, new Rectangle(25, 17, 3, 3));
                bitmap.MakeTransparent(Color.Fuchsia);
                IntPtr handle = bitmap.GetHicon();
                using (Icon temporary = Icon.FromHandle(handle))
                {
                    return (Icon)temporary.Clone();
                }
            }
        }
    }
}
