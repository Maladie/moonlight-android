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

[assembly: AssemblyVersion("0.7.133.0")]
[assembly: AssemblyFileVersion("0.7.133.0")]
[assembly: AssemblyInformationalVersion("0.7.133+2026.10.13-child-close")]

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
                    throw new InvalidOperationException("The current Windows user SID is unavailable.");
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
                    throw new InvalidOperationException("The current Windows user SID is unavailable.");

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
    // configured, Discord Bridge. Code runs from the installation; the profile directory holds
    // only data and configuration. A tray with an elevated token hosts nothing: profile
    // processes run only with the account's standard token.
    internal sealed class ProfileProcessHost : IDisposable
    {
        internal const string ElevatedStatus = "TRAY JAKO ADMIN";

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
            this.sid = sid ?? "";
            this.elevated = elevated;
            this.agentStartInfo = agentStartInfo ?? AgentStartInfo;
            this.discordStartInfo = discordStartInfo ?? DiscordStartInfo;
        }

        internal string ProfileId { get { lock (gate) return profileId; } }

        // A tray started at sign-in stays only for an account that owns a profile.
        internal static bool AccountHasProfile(string installRoot)
        {
            Dictionary<string, object> registry = ReadRegistry(Path.Combine(installRoot,
                "gateway", "gateway.json"), new JavaScriptSerializer());
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

        private static string CurrentSid()
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
                if (agent.Policy.Blocked) return "BRAK UPRAWNIEŃ";
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
                Dictionary<string, object> registry = ReadRegistry(Path.Combine(installRoot,
                    "gateway", "gateway.json"), json);
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
                if (agentWanted && agent.Running && DiscordConfigured(entry, root))
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
            return HiddenStartInfo(GatewayPythonw(), Quote(script) + " --config " +
                Quote(Path.Combine(data, "config.json")), data);
        }

        private ProcessStartInfo DiscordStartInfo(string root)
        {
            string data = Path.Combine(root, "discord");
            string script = Path.Combine(installRoot, "bridge-source", "discord", "DiscordBridge.ps1");
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

        // The one Python the installation already trusts: the Gateway service's --python.
        private static string GatewayPythonw()
        {
            using (Microsoft.Win32.RegistryKey service = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
                @"SYSTEM\CurrentControlSet\Services\MoonWakerGateway", false))
            {
                string image = service == null ? "" : Convert.ToString(service.GetValue("ImagePath", ""));
                string python = PythonFromServiceImage(image);
                if (python == null) throw new FileNotFoundException("Usługa Gateway nie ma ścieżki Pythona.");
                string pythonw = Path.Combine(Path.GetDirectoryName(python), "pythonw.exe");
                if (!File.Exists(pythonw)) throw new FileNotFoundException("Brak pythonw.exe.", pythonw);
                return pythonw;
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

        // Delete sharing: this periodic reader must not block the registry's atomic replacement.
        private static Dictionary<string, object> ReadRegistry(string path, JavaScriptSerializer json)
        {
            try
            {
                using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete))
                using (StreamReader reader = new StreamReader(stream, Encoding.UTF8, true))
                    return json.Deserialize<Dictionary<string, object>>(reader.ReadToEnd());
            }
            catch { return null; }
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
        private const int ProfileCreatedExitCode = 10;
        private readonly Color background = Color.FromArgb(17, 20, 28);
        private readonly Color panel = Color.FromArgb(28, 33, 45);
        private readonly Color accent = Color.FromArgb(116, 100, 255);
        private readonly Color muted = Color.FromArgb(164, 171, 193);
        private readonly Label gatewayState = new Label();
        private readonly Label gatewayDetails = new Label();
        private readonly Label activeProfile = new Label();
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
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();
        private readonly string script;
        private static readonly Icon moonWakerIcon = MoonWakerIcon.Create();
        private readonly NotifyIcon trayIcon = new NotifyIcon();
        private readonly StreamHotkeyPipe streamHotkeyPipe;
        private readonly WaitHandle activationEvent;
        private HostControlActivationListener activationListener;
        private readonly bool startInTray;
        private readonly ProfileProcessHost processHost;
        private bool refreshing;
        private bool sessionClosePending;
        private bool legendaryInstalled;
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
            script = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Invoke-MoonWakerHostControl.ps1");
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
            gatewayDetails.SetBounds(26, 82, 390, 60);
            gatewayDetails.ForeColor = muted;
            gatewayPanel.Controls.Add(gatewayDetails);
            AddActionButton(gatewayPanel, "Uruchom", 430, 27, delegate { RunAction("StartGateway", null); }, 116);
            AddActionButton(gatewayPanel, "Zatrzymaj", 554, 27, delegate { RunAction("StopGateway", null); }, 116);
            AddActionButton(gatewayPanel, "Restart", 678, 27, delegate { RunAction("RestartGateway", null); }, 116);
            Button pair = AddActionButton(gatewayPanel, "Sparuj TV", 802, 27, delegate { PairGateway(); }, 142);
            pair.BackColor = accent;
            pair.FlatAppearance.BorderSize = 0;
            AddActionButton(gatewayPanel, "Vibepollo…", 598, 74,
                delegate { LaunchConfigurator("vibepollo", null); }, 150);
            AddActionButton(gatewayPanel, "Eksportuj diagnostykę", 756, 74,
                delegate { ExportDiagnostics(); }, 188);
            activeProfile.SetBounds(430, 122, 510, 25);
            activeProfile.ForeColor = muted;
            gatewayPanel.Controls.Add(activeProfile);

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
            steamConnectionButton = AddActionButton(connectionsPanel, "Connect Steam", 330, 74,
                delegate { SteamConnectionClicked(); }, 180);

            Label epicTitle = MakeLabel("Epic przez Legendary", 10F, FontStyle.Bold, Color.White);
            epicTitle.SetBounds(530, 18, 190, 22);
            connectionsPanel.Controls.Add(epicTitle);
            epicConnectionState.SetBounds(530, 43, 180, 24);
            epicConnectionState.ForeColor = muted;
            connectionsPanel.Controls.Add(epicConnectionState);
            epicConnectionButton = AddActionButton(connectionsPanel, "Connect Epic", 530, 74,
                delegate { EpicConnectionClicked(); }, 180);

            Label playniteTitle = MakeLabel("Playnite (opcjonalnie)", 10F, FontStyle.Bold, Color.White);
            playniteTitle.SetBounds(730, 18, 200, 22);
            connectionsPanel.Controls.Add(playniteTitle);
            playniteConnectionState.SetBounds(730, 43, 190, 24);
            playniteConnectionState.ForeColor = muted;
            connectionsPanel.Controls.Add(playniteConnectionState);
            playniteConnectionButton = AddActionButton(connectionsPanel, "Connect Playnite", 730, 74,
                delegate { PlayniteConnectionClicked(); }, 190);

            Panel profilePanel = NewPanel(34, 428, 972, 346);
            Controls.Add(profilePanel);
            Label profileTitle = MakeLabel("Profile i Bridge'e", 16F, FontStyle.Bold, Color.White);
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
            profiles.Columns.Add("Discord", 130);
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
            AddActionButton(profilePanel, "Napraw", 24, 282,
                delegate { RepairProfile(); }, 140);
            integrationsButton = AddActionButton(profilePanel, "Integracje…", 434, 282,
                delegate { ConfigureIntegrations(); }, 150);
            AddActionButton(profilePanel, "Usuń dane Discorda", 594, 282,
                delegate { ClearDiscord(); }, 170);
            AddActionButton(profilePanel, "Odśwież", 774, 282,
                delegate { RefreshStatus(); }, 140);

            footer.SetBounds(36, 790, 968, 36);
            footer.ForeColor = muted;
            Controls.Add(footer);

            ConfigureTrayIcon();
            streamHotkeyPipe = new StreamHotkeyPipe(this, CloseSession);
            timer.Interval = 5000;
            timer.Tick += delegate { RefreshStatus(); };
            Shown += delegate
            {
                RefreshStatus();
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
                Dictionary<string, object> result = await RunControlAsync("CloseSession", null);
                string reason = GetText(result, "reason", "");
                if (reason.Length == 0) throw new InvalidOperationException(
                    GetText(result, "error", "Nie udało się zamknąć sesji."));
                string text = CloseSessionMessage(reason);
                if (!IsOk(result)) throw new InvalidOperationException(text);
                footer.Text = text;
                // The stream is closed, but the game may still run: a warning, not a success.
                trayIcon.ShowBalloonTip(3000, "MoonWaker", text,
                    reason == "stream_closed_game_unconfirmed"
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

        private static string CloseSessionMessage(string reason)
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

        private Button AddActionButton(Control parent, string text, int left, int top, EventHandler handler)
        {
            return AddActionButton(parent, text, left, top, handler, 112);
        }

        private async void RefreshStatus()
        {
            if (refreshing) return;
            refreshing = true;
            footer.Text = "Sprawdzam usługi…";
            try
            {
                Dictionary<string, object> result = await RunControlAsync("Status", null);
                if (!IsOk(result)) throw new InvalidOperationException(GetText(result, "error", "Nie udało się odczytać statusu."));
                RenderStatus(result);
                footer.Text = "Status odświeżony: " + DateTime.Now.ToString("HH:mm:ss") + ". Operacje dotyczące innych kont mogą wymagać zalogowania na ten profil.";
            }
            catch (Exception ex)
            {
                gatewayState.Text = "Status niedostępny";
                gatewayState.ForeColor = Color.FromArgb(255, 170, 170);
                footer.Text = ex.Message;
            }
            finally { refreshing = false; }
        }

        private void RenderStatus(Dictionary<string, object> result)
        {
            Dictionary<string, object> gateway = AsDictionary(result["gateway"]);
            bool running = GetBool(gateway, "running");
            gatewayState.Text = running ? "● ONLINE" : "● ZATRZYMANY";
            gatewayState.ForeColor = running ? Color.FromArgb(129, 226, 169) : Color.FromArgb(255, 170, 170);
            string installedVersion = GetText(gateway, "installed_version", hostVersion);
            string runtimeVersion = GetText(gateway, "runtime_version", "");
            string versionLine = String.IsNullOrWhiteSpace(runtimeVersion)
                ? "Wersja: " + installedVersion + " (proces nie zgłosił wersji)"
                : "Wersja: " + installedVersion + " / działa: " + runtimeVersion;
            if (GetBool(gateway, "version_mismatch")) versionLine += "  ⚠ Różnica wersji";
            Dictionary<string, object> legendary = AsDictionary(result["legendary"]);
            legendaryInstalled = GetBool(legendary, "installed");
            Dictionary<string, object> microphone = AsDictionary(result["microphone"]);
            string microphoneReason = GetText(microphone, "reason", "worker_missing");
            string microphoneStatus = microphoneReason == "ready" ? "gotowy"
                : microphoneReason == "worker_missing" ? "brak renderera"
                : "problem z endpointem Steam";
            gatewayDetails.Text = "Port " + GetText(gateway, "port", "—") + "  •  sparowane urządzenia: " +
                GetText(gateway, "paired_clients", "0") + "\n" + versionLine + "\nLegendary: " +
                (legendaryInstalled ? "jest" : "brak") +
                "  •  Mikrofon: " + microphoneStatus;
            string active = GetText(result, "active_profile", "");
            activeProfile.Text = String.IsNullOrWhiteSpace(active) ? "Ostatnio używany profil: brak danych" : "Ostatnio używany profil: " + active;

            string selected = SelectedProfileId();
            profiles.BeginUpdate();
            profiles.Items.Clear();
            IEnumerable values = result["profiles"] as IEnumerable;
            if (values != null)
            {
                foreach (object item in values)
                {
                    Dictionary<string, object> profile = AsDictionary(item);
                    string id = GetText(profile, "id", "");
                    ListViewItem row = new ListViewItem(GetText(profile, "name", id));
                    row.Name = id;
                    row.Tag = profile;
                    row.SubItems.Add(GetText(profile, "owner", "—"));
                    row.SubItems.Add(ProcessStatus(id, profile, "discord"));
                    row.SubItems.Add(ProcessStatus(id, profile, "game_provider"));
                    row.SubItems.Add(GetBool(profile, "pin_required") ? "WŁ." : "WYŁ.");
                    row.SubItems.Add(RemoteSignInStatus(profile));
                    profiles.Items.Add(row);
                    if (id == selected) row.Selected = true;
                }
            }
            profiles.EndUpdate();
            if (profiles.SelectedItems.Count == 0 && profiles.Items.Count > 0)
                profiles.Items[0].Selected = true;
            UpdateConnectionControls();
        }

        private static string StatusLabel(string value)
        {
            if (value == "running" || value == "online") return "ONLINE";
            if (value == "disabled") return "WYŁ.";
            if (value == "unavailable") return "INNE KONTO";
            return "OFFLINE";
        }

        // The tray's own process host first; a running child is then judged by its /health.
        private string ProcessStatus(string id, Dictionary<string, object> profile, string name)
        {
            string hosted = processHost.Describe(id, name == "discord");
            return hosted ?? StatusLabel(GetText(profile, name, "stopped"));
        }

        private static string RemoteSignInStatus(Dictionary<string, object> profile)
        {
            if (!GetBool(profile, "remote_sign_in_enabled")) return "WYŁ.";
            string state = GetText(profile, "remote_sign_in", "unavailable");
            if (state == "ready") return "GOTOWE";
            if (state == "action_required") return "USTAW";
            if (state == "unsupported") return "NIEOBSŁ.";
            return "SPRAWDŹ";
        }

        private string SelectedProfileId()
        {
            if (profiles.SelectedItems.Count == 0) return null;
            return profiles.SelectedItems[0].Name;
        }

        private Dictionary<string, object> SelectedProfile()
        {
            if (profiles.SelectedItems.Count == 0) return null;
            return profiles.SelectedItems[0].Tag as Dictionary<string, object>;
        }

        private void UpdateConnectionControls()
        {
            Dictionary<string, object> profile = SelectedProfile();
            if (profile == null)
            {
                steamConnectionState.Text = "Wybierz profil";
                epicConnectionState.Text = "Wybierz profil";
                playniteConnectionState.Text = "Wybierz profil";
                steamConnectionState.ForeColor = muted;
                epicConnectionState.ForeColor = muted;
                playniteConnectionState.ForeColor = muted;
                steamConnectionButton.Enabled = false;
                epicConnectionButton.Enabled = false;
                playniteConnectionButton.Enabled = false;
                integrationsButton.Enabled = false;
                return;
            }
            bool available = GetBool(profile, "platform_controls_available");
            bool steamConnected = GetBool(profile, "steam_connected");
            bool epicConnected = GetBool(profile, "epic_connected");
            bool playniteConnected = GetBool(profile, "playnite_connected");
            steamConnectionState.Text = steamConnected ? "POŁĄCZONO" : "NIEPOŁĄCZONO";
            epicConnectionState.Text = epicConnected ? "POŁĄCZONO" : "NIEPOŁĄCZONO";
            playniteConnectionState.Text = playniteConnected ? "POŁĄCZONO" : "NIEPOŁĄCZONO";
            steamConnectionState.ForeColor = steamConnected
                ? Color.FromArgb(129, 226, 169) : (available ? muted : Color.FromArgb(255, 170, 170));
            epicConnectionState.ForeColor = epicConnected
                ? Color.FromArgb(129, 226, 169) : (available ? muted : Color.FromArgb(255, 170, 170));
            playniteConnectionState.ForeColor = playniteConnected
                ? Color.FromArgb(129, 226, 169) : (available ? muted : Color.FromArgb(255, 170, 170));
            steamConnectionButton.Text = steamConnected ? "Disconnect Steam" : "Connect Steam";
            epicConnectionButton.Text = epicConnected ? "Disconnect Epic" : "Connect Epic";
            playniteConnectionButton.Text = playniteConnected ? "Disconnect Playnite" : "Connect Playnite";
            steamConnectionButton.Enabled = available;
            epicConnectionButton.Enabled = available && legendaryInstalled;
            playniteConnectionButton.Enabled = available;
            integrationsButton.Enabled = available;
            if (!available)
            {
                steamConnectionState.Text = "Zaloguj się na ten profil";
                epicConnectionState.Text = "Zaloguj się na ten profil";
                playniteConnectionState.Text = "Zaloguj się na ten profil";
            }
            else if (!legendaryInstalled)
            {
                epicConnectionState.Text = "Brak Legendary — zaktualizuj Host";
            }
        }

        private void RepairProfile()
        {
            string id = SelectedProfileId();
            if (String.IsNullOrWhiteSpace(id)) { MessageBox.Show(this, "Wybierz profil.", "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            if (id != processHost.ProfileId)
            {
                MessageBox.Show(this, "Procesy tego profilu działają na jego koncie Windows. Zaloguj się na nie, aby je naprawić.",
                    "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            processHost.RestartAll();
            footer.Text = "Uruchamiam ponownie procesy profilu…";
        }

        private async void ConfigureSteamWebApi()
        {
            string id = SelectedProfileId();
            if (String.IsNullOrWhiteSpace(id))
            {
                MessageBox.Show(this, "Wybierz profil.", "MoonWaker",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            using (SteamApiKeyDialog dialog = new SteamApiKeyDialog(id))
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                string key = dialog.ApiKey;
                try
                {
                    footer.Text = "Zapisuję klucz Steam Web API…";
                    Dictionary<string, object> result = await RunControlAsync(
                        "ConfigureSteamWebApi", id, key);
                    if (!IsOk(result))
                        throw new InvalidOperationException(GetText(result, "error",
                            "Nie udało się zapisać klucza Steam Web API."));
                    if (!GetBool(result, "connected"))
                        throw new InvalidOperationException("Host Control nie potwierdził zapisu klucza Steam Web API.");
                    Dictionary<string, object> profile = SelectedProfile();
                    if (profile != null)
                    {
                        profile["steam_connected"] = true;
                        profile["steam_web_api_configured"] = true;
                        UpdateConnectionControls();
                    }
                    MessageBox.Show(this,
                        "Klucz został zapisany dla profilu " + id + " i zabezpieczony przez Windows DPAPI.",
                        "Steam Web API", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    RefreshStatus();
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "Steam Web API",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                    RefreshStatus();
                }
                finally { key = null; }
            }
        }

        private void SteamConnectionClicked()
        {
            string id = SelectedProfileId();
            Dictionary<string, object> profile = SelectedProfile();
            if (String.IsNullOrWhiteSpace(id) || profile == null) return;
            if (!GetBool(profile, "steam_connected"))
            {
                ConfigureSteamWebApi();
                return;
            }
            string warning = "Odłączenie Steam usunie zaszyfrowany klucz Web API dla tego profilu. " +
                "MoonWaker nie będzie mógł odświeżać pełnej biblioteki Steam. Lokalne manifesty nadal " +
                "potwierdzą zainstalowane gry, a ostatni snapshot może pozostać widoczny.\n\nKontynuować?";
            if (MessageBox.Show(this, warning, "Disconnect Steam", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) == DialogResult.Yes)
                RunPlatformAction("DisconnectSteam", id, "Steam został odłączony.");
        }

        private void EpicConnectionClicked()
        {
            string id = SelectedProfileId();
            Dictionary<string, object> profile = SelectedProfile();
            if (String.IsNullOrWhiteSpace(id) || profile == null) return;
            if (GetBool(profile, "epic_connected"))
            {
                string warning = "Odłączenie Epic usunie uwierzytelnienie Legendary. MoonWaker nie będzie " +
                    "odświeżać katalogu Epic ani uruchamiać, instalować lub odinstalowywać gier Epic do " +
                    "ponownego połączenia. Pliki gier nie zostaną usunięte.\n\nKontynuować?";
                if (MessageBox.Show(this, warning, "Disconnect Epic", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning) == DialogResult.Yes)
                    RunPlatformAction("DisconnectEpic", id, "Epic został odłączony od Legendary.");
                return;
            }
            string explanation = "Obsługa Epic działa przez Legendary, nie przez Epic Games Launcher. " +
                "Legendary utrzymuje własny katalog i stan instalacji; gry nie będą synchronizowane z " +
                "Epic Games Launcherem.\n\nLogowanie otworzy interaktywny proces Legendary. MoonWaker nie " +
                "przechwytuje hasła Epic. Kontynuować?";
            if (MessageBox.Show(this, explanation, "Connect Epic", MessageBoxButtons.YesNo,
                MessageBoxIcon.Information) == DialogResult.Yes)
                RunPlatformAction("ConnectEpic", id, "Epic został połączony przez Legendary.");
        }

        private async void PlayniteConnectionClicked()
        {
            string id = SelectedProfileId();
            Dictionary<string, object> profile = SelectedProfile();
            if (String.IsNullOrWhiteSpace(id) || profile == null) return;
            if (GetBool(profile, "playnite_connected"))
            {
                if (MessageBox.Show(this,
                        "Odłączyć opcjonalny katalog Playnite? Steam i Epic nadal będą działać bez zmian.",
                        "Disconnect Playnite", MessageBoxButtons.YesNo,
                        MessageBoxIcon.Warning) == DialogResult.Yes)
                    RunPlatformAction("DisconnectPlaynite", id, "Playnite został odłączony.");
                return;
            }
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Wybierz katalog instalacji Playnite z zainstalowanym SunshinePlaynite Connector.";
                dialog.ShowNewFolderButton = false;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    footer.Text = "Łączę opcjonalny katalog Playnite…";
                    Dictionary<string, object> result = await RunControlAsync(
                        "ConnectPlaynite", id, null, dialog.SelectedPath);
                    if (!IsOk(result)) throw new InvalidOperationException(
                        GetText(result, "error", "Nie udało się połączyć Playnite."));
                    processHost.RestartAll();
                    MessageBox.Show(this, "Playnite został połączony z profilem.",
                        "MoonWaker Host Control", MessageBoxButtons.OK,
                        MessageBoxIcon.Information);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "Playnite",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                finally { RefreshStatus(); }
            }
        }

        private async void RunPlatformAction(string action, string profile, string successMessage)
        {
            try
            {
                footer.Text = "Aktualizuję połączenie platformy…";
                Dictionary<string, object> result = await RunControlAsync(action, profile);
                if (!IsOk(result)) throw new InvalidOperationException(GetText(result, "error", "Operacja nie powiodła się."));
                if (action == "DisconnectPlaynite") processHost.RestartAll();
                MessageBox.Show(this, successMessage, "MoonWaker Host Control",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                RefreshStatus();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Control",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                RefreshStatus();
            }
        }

        private async void RunAction(string action, string profile)
        {
            try
            {
                footer.Text = "Wykonuję operację…";
                Dictionary<string, object> result = await RunControlAsync(action, profile);
                if (!IsOk(result)) throw new InvalidOperationException(GetText(result, "error", "Operacja nie powiodła się."));
                await Task.Delay(700);
                RefreshStatus();
            }
            catch (Exception ex) { MessageBox.Show(this, ex.Message, "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Error); RefreshStatus(); }
        }

        private async void PairGateway()
        {
            try
            {
                Dictionary<string, object> result = await RunControlAsync("PairGateway", null);
                if (!IsOk(result)) throw new InvalidOperationException(GetText(result, "error", "Nie udało się uruchomić parowania."));
                string code = GetText(result, "pairing_code", "");
                MessageBox.Show(this, "Kod parowania:\n\n" + code + "\n\nKod jest ważny przez 10 minut.",
                    "Parowanie MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
                RefreshStatus();
            }
            catch (Exception ex) { MessageBox.Show(this, ex.Message, "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        }

        private async void ExportDiagnostics()
        {
            try
            {
                footer.Text = "Eksportuję diagnostykę…";
                Dictionary<string, object> result = await RunControlAsync(
                    "ExportDiagnostics", null);
                if (!IsOk(result)) throw new InvalidOperationException(
                    GetText(result, "error", "Nie udało się wyeksportować diagnostyki."));
                MessageBox.Show(this, "Pakiet diagnostyczny zapisano w:\n\n" +
                    GetText(result, "path", ""), "MoonWaker Host Control",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Control",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally { RefreshStatus(); }
        }

        private void ClearDiscord()
        {
            string id = SelectedProfileId();
            if (String.IsNullOrWhiteSpace(id)) { MessageBox.Show(this, "Wybierz profil.", "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            DialogResult result = MessageBox.Show(this, "Usunąć token OAuth i lokalne dane Discorda dla profilu " + id + "?",
                "Usuń dane Discorda", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (result != DialogResult.Yes) return;
            DialogResult machine = MessageBox.Show(this,
                "Czy usunąć również wspólne dla komputera Client ID i Client Secret aplikacji Discord?\n\nWybierz Nie, jeśli inne profile nadal korzystają z Discorda.",
                "Wspólne dane aplikacji Discord", MessageBoxButtons.YesNoCancel, MessageBoxIcon.Warning);
            if (machine == DialogResult.Cancel) return;
            RunAction(machine == DialogResult.Yes ? "ClearDiscordMachine" : "ClearDiscord", id);
        }

        private async void ConfigureIntegrations()
        {
            string id = SelectedProfileId();
            Dictionary<string, object> profile = SelectedProfile();
            if (String.IsNullOrWhiteSpace(id) || profile == null) return;
            if (!GetBool(profile, "platform_controls_available"))
            {
                MessageBox.Show(this,
                    "Zaloguj się na konto Windows właściciela tego profilu, aby skonfigurować Discord.",
                    "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            using (IntegrationsDialog dialog = new IntegrationsDialog(id,
                GetBool(profile, "discord_configured")))
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                IntegrationSettings settings = dialog.Settings;
                try
                {
                    footer.Text = "Konfiguruję Discord…";
                    Dictionary<string, object> result = await RunControlAsync(
                        "ConfigureIntegrations", id, null, null, settings);
                    if (!IsOk(result)) throw new InvalidOperationException(GetText(result, "error",
                        "Nie udało się skonfigurować integracji."));
                    processHost.RestartAll();
                    MessageBox.Show(this,
                        "Integracje profilu zostały zapisane. Jeśli Discord wymaga autoryzacji OAuth, otwórz go i użyj „Napraw”.",
                        "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "Integracje MoonWaker",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                finally
                {
                    settings.ClearSecrets();
                    dialog.ClearSecrets();
                    RefreshStatus();
                }
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
                {
                    await Task.Run(delegate { process.WaitForExit(); });
                    if (mode == "add" && process.ExitCode == ProfileCreatedExitCode)
                        OpenVibepolloAccountPage();
                }
                RefreshStatus();
            }
            catch (System.ComponentModel.Win32Exception ex)
            {
                if (ex.NativeErrorCode != 1223)
                    MessageBox.Show(this, ex.Message, "MoonWaker Host Control",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                RefreshStatus();
            }
        }

        private void OpenVibepolloAccountPage()
        {
            try
            {
                ProcessStartInfo info = new ProcessStartInfo("https://127.0.0.1:47990");
                info.UseShellExecute = true;
                Process.Start(info);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Nie udało się otworzyć panelu Vibepollo: " + ex.Message,
                    "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async Task<Dictionary<string, object>> RunControlAsync(string action, string profile)
        {
            return await RunControlAsync(action, profile, null, null);
        }

        private async Task<Dictionary<string, object>> RunControlAsync(
            string action, string profile, string steamApiKey)
        {
            return await RunControlAsync(action, profile, steamApiKey, null);
        }

        private async Task<Dictionary<string, object>> RunControlAsync(
            string action, string profile, string steamApiKey,
            string playniteDirectory)
        {
            return await RunControlAsync(action, profile, steamApiKey,
                playniteDirectory, null);
        }

        private async Task<Dictionary<string, object>> RunControlAsync(
            string action, string profile, string steamApiKey,
            string playniteDirectory, IntegrationSettings integrations)
        {
            return await Task.Run(delegate
            {
                if (!File.Exists(script)) throw new FileNotFoundException("Brak komponentu sterującego.", script);
                string resultPath = Path.Combine(Path.GetTempPath(), "MoonWakerControl-" + Guid.NewGuid().ToString("N") + ".json");
                bool hasSteamApiKey = steamApiKey != null && action == "ConfigureSteamWebApi";
                bool hasIntegrationData = integrations != null && action == "ConfigureIntegrations";
                string steamDiagnosticPath = hasSteamApiKey ? SteamConfigurationDiagnosticPath() : null;
                string arguments = "-NoProfile -ExecutionPolicy Bypass -File " + Quote(script) + " -Action " + Quote(action) +
                    (String.IsNullOrWhiteSpace(profile) ? "" : " -ProfileId " + Quote(profile)) +
                    (hasSteamApiKey ? " -SteamWebApiKeyProtectedFromEnvironment" : "") +
                    (hasSteamApiKey ? " -SteamWebApiDiagnosticPath " + Quote(steamDiagnosticPath) : "") +
                    (hasIntegrationData ? " -IntegrationDataProtectedFromEnvironment" : "") +
                    (!String.IsNullOrWhiteSpace(playniteDirectory)
                        ? " -PlayniteDirectory " + Quote(playniteDirectory) : "") +
                    " -ResultPath " + Quote(resultPath);
                try
                {
                    if (hasSteamApiKey) WriteSteamConfigurationDiagnostic(steamDiagnosticPath, profile, "child_starting", 0);
                    ProcessStartInfo info = new ProcessStartInfo("powershell.exe", arguments);
                    info.UseShellExecute = false;
                    if (hasSteamApiKey)
                    {
                        info.EnvironmentVariables["MOONWAKER_STEAM_WEB_API_PROTECTED"] =
                            ProtectForCurrentUser(steamApiKey);
                    }
                    if (hasIntegrationData)
                    {
                        info.EnvironmentVariables["MOONWAKER_DISCORD_CONFIGURE"] =
                            integrations.ConfigureDiscord ? "1" : "0";
                        info.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_ID"] =
                            integrations.DiscordClientId ?? "";
                        info.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_SECRET_PROTECTED"] =
                            String.IsNullOrWhiteSpace(integrations.DiscordClientSecret) ? "" :
                            ProtectForCurrentUser(integrations.DiscordClientSecret);
                    }
                    info.CreateNoWindow = true;
                    info.RedirectStandardOutput = true;
                    info.RedirectStandardError = true;
                    info.StandardOutputEncoding = Encoding.Default;
                    info.StandardErrorEncoding = Encoding.Default;
                    using (Process process = Process.Start(info))
                    {
                        if (hasSteamApiKey) WriteSteamConfigurationDiagnostic(
                            steamDiagnosticPath, profile, "child_started", process.Id);
                        Task<string> stdout = process.StandardOutput.ReadToEndAsync();
                        Task<string> stderr = process.StandardError.ReadToEndAsync();
                        int timeout = action == "ConnectEpic" ? 900000 :
                            action == "ConfigureIntegrations" ? 120000 :
                            action == "ConnectPlaynite" ? 90000 :
                            action == "ExportDiagnostics" ? 120000 :
                            action == "CloseSession" ? 120000 :
                            action.EndsWith("Gateway", StringComparison.Ordinal) ? 60000 : 30000;
                        if (!process.WaitForExit(timeout))
                        {
                            if (hasSteamApiKey) WriteSteamConfigurationDiagnostic(
                                steamDiagnosticPath, profile, "child_timeout", process.Id);
                            try { process.Kill(); } catch { }
                            return Error("Operacja Host Control przekroczyła limit czasu. Szczegóły: " + steamDiagnosticPath);
                        }
                        process.WaitForExit();
                        if (hasSteamApiKey) WriteSteamConfigurationDiagnostic(
                            steamDiagnosticPath, profile, "child_exited", process.Id);
                        string output = "";
                        if (Task.WaitAll(new Task[] { stdout, stderr }, 2000))
                            output = stdout.Result;
                        if (File.Exists(resultPath)) output = File.ReadAllText(resultPath, Encoding.UTF8).TrimStart('\uFEFF');
                        if (String.IsNullOrWhiteSpace(output)) return Error("Brak odpowiedzi komponentu sterującego.");
                        return json.Deserialize<Dictionary<string, object>>(output.Trim());
                    }
                }
                catch (System.ComponentModel.Win32Exception ex) { return Error(ex.Message); }
                finally { try { File.Delete(resultPath); } catch { } }
            });
        }

        private static string ProtectForCurrentUser(string value)
        {
            byte[] plain = Encoding.Unicode.GetBytes(value);
            byte[] encrypted = null;
            try
            {
                encrypted = ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser);
                StringBuilder text = new StringBuilder(encrypted.Length * 2);
                foreach (byte item in encrypted) text.Append(item.ToString("x2"));
                return text.ToString();
            }
            finally
            {
                Array.Clear(plain, 0, plain.Length);
                if (encrypted != null) Array.Clear(encrypted, 0, encrypted.Length);
            }
        }

        private static string SteamConfigurationDiagnosticPath()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MoonWaker", "logs", "steam-web-api-configure.log");
        }

        private static void WriteSteamConfigurationDiagnostic(
            string path, string profile, string phase, int childPid)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                if (File.Exists(path) && new FileInfo(path).Length > 65536) File.WriteAllText(path, "");
                string line = DateTimeOffset.Now.ToString("o") + " pid=" + Process.GetCurrentProcess().Id +
                    " child_pid=" + childPid + " profile=" + profile +
                    " component=host-control phase=" + phase + Environment.NewLine;
                File.AppendAllText(path, line, Encoding.UTF8);
            }
            catch { }
        }

        private static Dictionary<string, object> Error(string message)
        {
            Dictionary<string, object> value = new Dictionary<string, object>();
            value["ok"] = false;
            value["error"] = message;
            return value;
        }

        private static Dictionary<string, object> AsDictionary(object value)
        {
            return value as Dictionary<string, object> ?? new Dictionary<string, object>();
        }

        private static bool IsOk(Dictionary<string, object> value) { return GetBool(value, "ok"); }
        private static bool GetBool(Dictionary<string, object> value, string key)
        {
            object found;
            if (!value.TryGetValue(key, out found) || found == null) return false;
            bool parsed;
            return found is bool ? (bool)found : Boolean.TryParse(found.ToString(), out parsed) && parsed;
        }

        private static string GetText(Dictionary<string, object> value, string key, string fallback)
        {
            object found;
            return value.TryGetValue(key, out found) && found != null ? found.ToString() : fallback;
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

    internal sealed class IntegrationSettings
    {
        internal bool ConfigureDiscord;
        internal string DiscordClientId;
        internal string DiscordClientSecret;

        internal void ClearSecrets()
        {
            DiscordClientSecret = null;
        }
    }

    internal sealed class IntegrationsDialog : Form
    {
        private readonly bool discordWasConfigured;
        private readonly CheckBox configureDiscord = new CheckBox();
        private readonly TextBox discordId = new TextBox();
        private readonly TextBox discordSecret = new TextBox();

        internal IntegrationSettings Settings
        {
            get
            {
                return new IntegrationSettings {
                    ConfigureDiscord = configureDiscord.Checked,
                    DiscordClientId = discordId.Text.Trim(),
                    DiscordClientSecret = discordSecret.Text
                };
            }
        }

        internal IntegrationsDialog(string profileId, bool discordConfigured)
        {
            discordWasConfigured = discordConfigured;
            Text = "Integracje MoonWaker";
            Icon = MoonWakerIcon.Create();
            ClientSize = new Size(720, 300);
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
            AddLabel("Profil: " + profileId + ". Sekrety są zapisywane dla aktualnego konta Windows.",
                28, 55, 650, 25, 9F, FontStyle.Regular, Color.FromArgb(164, 171, 193));

            configureDiscord.Text = discordConfigured
                ? "Discord Bridge — skonfigurowany (puste pola zachowają dane)"
                : "Skonfiguruj Discord Bridge";
            configureDiscord.SetBounds(28, 91, 650, 28);
            configureDiscord.ForeColor = Color.White;
            configureDiscord.Checked = true;
            configureDiscord.CheckedChanged += delegate { UpdateFields(); };
            Controls.Add(configureDiscord);
            AddLabel("Client ID", 30, 124, 280, 22, 9F, FontStyle.Regular, Color.White);
            AddLabel("Client Secret", 365, 124, 280, 22, 9F, FontStyle.Regular, Color.White);
            ConfigureBox(discordId, 30, 148, 315, false);
            ConfigureBox(discordSecret, 365, 148, 325, true);
            AddLabel("Przy pierwszej konfiguracji podaj oba pola. Discord uruchomi autoryzację OAuth przy starcie Bridge'a.",
                30, 184, 660, 34, 8.7F, FontStyle.Regular, Color.FromArgb(164, 171, 193));

            AddLabel("Vibepollo jest ustawieniem komputera: token zapisuje przycisk „Vibepollo…” w panelu Gateway.",
                30, 220, 660, 22, 8.7F, FontStyle.Regular, Color.FromArgb(164, 171, 193));

            Button save = new Button();
            save.Text = "Zapisz";
            save.SetBounds(468, 248, 102, 38);
            save.BackColor = Color.FromArgb(116, 100, 255);
            save.ForeColor = Color.White;
            save.FlatStyle = FlatStyle.Flat;
            save.FlatAppearance.BorderSize = 0;
            save.Click += SaveClicked;
            Controls.Add(save);
            Button cancel = new Button();
            cancel.Text = "Anuluj";
            cancel.DialogResult = DialogResult.Cancel;
            cancel.SetBounds(582, 248, 108, 38);
            cancel.BackColor = Color.FromArgb(28, 33, 45);
            cancel.ForeColor = Color.White;
            cancel.FlatStyle = FlatStyle.Flat;
            cancel.FlatAppearance.BorderColor = Color.FromArgb(77, 85, 109);
            Controls.Add(cancel);
            AcceptButton = save;
            CancelButton = cancel;
            FormClosed += delegate { if (DialogResult != DialogResult.OK) ClearSecrets(); };
            UpdateFields();
        }

        private void SaveClicked(object sender, EventArgs e)
        {
            if (!configureDiscord.Checked)
            {
                MessageBox.Show(this, "Zaznacz konfigurację Discorda.",
                    "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            if (configureDiscord.Checked)
            {
                bool hasId = !String.IsNullOrWhiteSpace(discordId.Text);
                bool hasSecret = !String.IsNullOrWhiteSpace(discordSecret.Text);
                if (hasId != hasSecret || (!discordWasConfigured && !hasId))
                {
                    MessageBox.Show(this, "Przy pierwszej konfiguracji Discorda podaj Client ID i Client Secret.",
                        "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                if (hasId && !System.Text.RegularExpressions.Regex.IsMatch(
                    discordId.Text.Trim(), "^[0-9]{17,20}$"))
                {
                    MessageBox.Show(this, "Discord Client ID musi zawierać od 17 do 20 cyfr.",
                        "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
            }
            DialogResult = DialogResult.OK;
            Close();
        }

        private void UpdateFields()
        {
            discordId.Enabled = configureDiscord.Checked;
            discordSecret.Enabled = configureDiscord.Checked;
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

        internal void ClearSecrets()
        {
            discordSecret.Clear();
        }
    }

    internal sealed class SteamApiKeyDialog : Form
    {
        private readonly TextBox keyBox = new TextBox();

        public string ApiKey { get { return keyBox.Text.Trim(); } }

        public SteamApiKeyDialog(string profileId)
        {
            Text = "Steam Web API key";
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
            title.Text = "Connect a Steam Web API key";
            title.Font = new Font("Segoe UI", 14F, FontStyle.Bold);
            title.SetBounds(24, 20, 440, 30);
            Controls.Add(title);

            Label profile = new Label();
            profile.Text = "Profile: " + profileId;
            profile.ForeColor = Color.FromArgb(164, 171, 193);
            profile.SetBounds(26, 54, 500, 24);
            Controls.Add(profile);

            Label prompt = new Label();
            prompt.Text = "32-character Steam Web API key";
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
            help.AccessibleName = "How to get a Steam Web API key";
            help.AccessibleDescription = "Shows key registration and secure storage instructions.";
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
            tip.SetToolTip(help, "How to get and store a Steam Web API key");

            Label storage = new Label();
            storage.Text = "The key is stored for this Windows user using DPAPI and is never returned by Gateway.";
            storage.ForeColor = Color.FromArgb(164, 171, 193);
            storage.SetBounds(26, 152, 560, 34);
            Controls.Add(storage);

            Button save = new Button();
            save.Text = "Save key";
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
                        "Enter exactly 32 hexadecimal characters.", "Steam Web API",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    keyBox.Focus();
                    return;
                }
                DialogResult = DialogResult.OK;
                Close();
            };
            Controls.Add(save);

            Button cancel = new Button();
            cancel.Text = "Cancel";
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
            "HOW TO GET A STEAM WEB API KEY\r\n\r\n" +
            "1. Open the official Steam registration page:\r\n" + RegistrationUrl + "\r\n\r\n" +
            "2. Sign in with the Steam account whose library MoonWaker should read.\r\n\r\n" +
            "3. Enter localhost in the Domain Name field for this local integration.\r\n\r\n" +
            "4. Review and accept the Steam Web API Terms of Use, then register the key.\r\n\r\n" +
            "5. Copy the generated 32-character key into the masked field in Host Control.\r\n\r\n" +
            "STORAGE AND SECURITY\r\n\r\n" +
            "• The key is stored in the selected MoonWaker profile as steam-web-api-key.dpapi.\r\n" +
            "• Windows DPAPI encrypts it for the Windows account that owns the profile.\r\n" +
            "• The key is not stored in config.json, command-line arguments, logs, audit files, or Gateway responses.\r\n" +
            "• MoonWaker sends it only to api.steampowered.com over HTTPS with normal certificate validation.\r\n" +
            "• Host Control never displays a previously stored key. Re-entering a key replaces it.\r\n" +
            "• Never paste the key into chat or share it. If it is exposed, revoke it on the Steam registration page and create a new one.";

        public SteamApiHelpForm()
        {
            Text = "Steam Web API key help";
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
            close.Text = "Close";
            close.DialogResult = DialogResult.OK;
            close.Size = new Size(100, 34);
            close.FlatStyle = FlatStyle.Flat;
            close.BackColor = Color.FromArgb(28, 33, 45);
            close.ForeColor = Color.White;
            buttons.Controls.Add(close);

            Button open = new Button();
            open.Text = "Open Steam key page";
            open.Size = new Size(180, 34);
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
