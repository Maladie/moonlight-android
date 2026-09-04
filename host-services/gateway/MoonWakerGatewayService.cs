using System;
using System.Diagnostics;
using System.IO;
using System.ServiceProcess;
using System.Threading;

namespace MoonWaker.GatewayService
{
    internal sealed class GatewayServiceSettings
    {
        internal readonly string GatewayDirectory;
        internal readonly string PythonPath;
        internal readonly string ScriptPath;
        internal readonly string ConfigPath;
        internal readonly string RegistryLockPath;

        private GatewayServiceSettings(string gatewayDirectory, string pythonPath)
        {
            GatewayDirectory = gatewayDirectory;
            PythonPath = pythonPath;
            ScriptPath = Path.Combine(gatewayDirectory, "wakeplay_gateway.py");
            ConfigPath = Path.Combine(gatewayDirectory, "gateway.json");
            RegistryLockPath = ConfigPath + ".lock";
        }

        internal static GatewayServiceSettings Parse(string[] args)
        {
            string directory = AppDomain.CurrentDomain.BaseDirectory;
            string python = "python.exe";
            for (int index = 0; args != null && index < args.Length; index++)
            {
                if (args[index] == "--gateway-dir" && index + 1 < args.Length)
                    directory = args[++index];
                else if (args[index] == "--python" && index + 1 < args.Length)
                    python = args[++index];
            }
            directory = Path.GetFullPath(directory).TrimEnd(Path.DirectorySeparatorChar);
            python = Path.GetFullPath(python);
            GatewayServiceSettings result = new GatewayServiceSettings(directory, python);
            if (directory.IndexOf('"') >= 0 || python.IndexOf('"') >= 0 ||
                !File.Exists(python) || !File.Exists(result.ScriptPath) ||
                !File.Exists(result.ConfigPath))
                throw new InvalidOperationException(
                    "Gateway service paths are incomplete or invalid.");
            return result;
        }

        internal ProcessStartInfo CreateStartInfo()
        {
            return new ProcessStartInfo {
                FileName = PythonPath,
                Arguments = Quote(ScriptPath) + " --config " + Quote(ConfigPath) +
                    " --registry-lock " + Quote(RegistryLockPath),
                WorkingDirectory = GatewayDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
        }

        private static string Quote(string value)
        {
            return "\"" + value + "\"";
        }
    }

    internal sealed class GatewayProcessSupervisor : IDisposable
    {
        private readonly object gate = new object();
        private readonly GatewayServiceSettings settings;
        private readonly ManualResetEvent stopping = new ManualResetEvent(false);
        private Thread thread;
        private Process child;

        internal GatewayProcessSupervisor(GatewayServiceSettings settings)
        {
            this.settings = settings;
        }

        internal void Start()
        {
            if (thread != null) throw new InvalidOperationException("Gateway supervisor already started.");
            thread = new Thread(Run);
            thread.IsBackground = true;
            thread.Name = "MoonWaker Gateway process supervisor";
            thread.Start();
        }

        internal void Stop()
        {
            stopping.Set();
            Process current;
            lock (gate) current = child;
            if (current != null)
            {
                try { if (!current.HasExited) current.Kill(); }
                catch { }
            }
            Thread worker = thread;
            if (worker != null) worker.Join(10000);
            thread = null;
        }

        private void Run()
        {
            int restartCount = 0;
            while (!stopping.WaitOne(0))
            {
                Process process = null;
                try
                {
                    WriteState("starting", restartCount, 0);
                    process = new Process { StartInfo = settings.CreateStartInfo() };
                    if (!process.Start()) throw new InvalidOperationException("Python did not start.");
                    lock (gate) child = process;
                    WriteState("running", restartCount, process.Id);
                    while (!stopping.WaitOne(250) && !process.HasExited) { }
                    if (stopping.WaitOne(0) && !process.HasExited) process.Kill();
                    process.WaitForExit();
                }
                catch (Exception error)
                {
                    WriteState("recovering", restartCount, 0, error.GetType().Name);
                }
                finally
                {
                    lock (gate) if (Object.ReferenceEquals(child, process)) child = null;
                    if (process != null) process.Dispose();
                }
                if (stopping.WaitOne(0)) break;
                restartCount = Math.Min(restartCount + 1, 8);
                WriteState("recovering", restartCount, 0);
                stopping.WaitOne(Math.Min(30000, Math.Max(2000, restartCount * 2000)));
            }
            WriteState("stopped", 0, 0);
        }

        private void WriteState(string status, int restartCount, int processId,
            string errorType = "")
        {
            try
            {
                string value = "{\"status\":\"" + status + "\",\"restart_count\":" +
                    restartCount + ",\"process_id\":" + processId +
                    ",\"updated_at\":" + DateTimeOffset.UtcNow.ToUnixTimeSeconds() +
                    (errorType.Length == 0 ? "" : ",\"error_type\":\"" + errorType + "\"") + "}";
                string path = Path.Combine(settings.GatewayDirectory, "gateway-service-state.json");
                string temporary = path + ".tmp";
                File.WriteAllText(temporary, value);
                if (File.Exists(path)) File.Replace(temporary, path, null);
                else File.Move(temporary, path);
            }
            catch { }
        }

        public void Dispose()
        {
            Stop();
            stopping.Dispose();
        }
    }

    internal sealed class MoonWakerGatewayService : ServiceBase
    {
        private readonly GatewayServiceSettings settings;
        private GatewayProcessSupervisor supervisor;

        internal MoonWakerGatewayService(GatewayServiceSettings settings)
        {
            this.settings = settings;
            ServiceName = "MoonWakerGateway";
            CanStop = true;
            CanShutdown = true;
            AutoLog = false;
        }

        protected override void OnStart(string[] args)
        {
            supervisor = new GatewayProcessSupervisor(settings);
            supervisor.Start();
        }

        protected override void OnStop()
        {
            if (supervisor == null) return;
            supervisor.Dispose();
            supervisor = null;
        }

        protected override void OnShutdown()
        {
            OnStop();
            base.OnShutdown();
        }
    }

    internal static class Program
    {
        private static void Main(string[] args)
        {
            GatewayServiceSettings settings = GatewayServiceSettings.Parse(args);
            if (args != null && Array.IndexOf(args, "--console") >= 0)
            {
                using (GatewayProcessSupervisor supervisor =
                    new GatewayProcessSupervisor(settings))
                using (ManualResetEvent stopped = new ManualResetEvent(false))
                {
                    Console.CancelKeyPress += delegate(object sender, ConsoleCancelEventArgs e) {
                        e.Cancel = true;
                        stopped.Set();
                    };
                    supervisor.Start();
                    Console.WriteLine("MoonWaker Gateway service host is running. Press Ctrl+C to stop.");
                    stopped.WaitOne();
                }
                return;
            }
            ServiceBase.Run(new MoonWakerGatewayService(settings));
        }
    }
}
