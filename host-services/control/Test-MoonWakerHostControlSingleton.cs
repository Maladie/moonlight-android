using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;

namespace MoonWaker.HostControl
{
    internal static class SingletonTests
    {
        private const int WorkerCount = 4;
        private const int GateTimeoutMilliseconds = 15000;
        private const int WorkerTimeoutMilliseconds = 25000;

        private static int Main(string[] args)
        {
            try
            {
                if (args != null && args.Length > 0 && args[0] == "--worker")
                    return RunWorker(args);

                TestCompetingProcessesAndActivation();
                Console.WriteLine("PASS Host Control singleton: one owner, tray suppression, repeat activation.");
                return 0;
            }
            catch (Exception exception)
            {
                Console.Error.WriteLine("FAIL Host Control singleton: " + exception.Message);
                return 1;
            }
        }

        private static void TestCompetingProcessesAndActivation()
        {
            string id = Guid.NewGuid().ToString("N");
            string prefix = @"Local\MoonWakerHostControlTest." + id;
            string gateName = prefix + ".Gate";
            string releaseName = prefix + ".Release";
            string callbackName = prefix + ".Callback";
            string root = Path.Combine(Path.GetTempPath(), "MWHostControlTest-" + id);
            Directory.CreateDirectory(root);

            EventWaitHandle gate = new EventWaitHandle(false, EventResetMode.ManualReset, gateName);
            EventWaitHandle release = new EventWaitHandle(false, EventResetMode.ManualReset, releaseName);
            EventWaitHandle callback = new EventWaitHandle(false, EventResetMode.ManualReset, callbackName);
            List<EventWaitHandle> readyEvents = new List<EventWaitHandle>();
            List<Process> workers = new List<Process>();
            bool released = false;
            try
            {
                string executable = Process.GetCurrentProcess().MainModule.FileName;
                for (int index = 0; index < WorkerCount; index++)
                {
                    string readyName = prefix + ".Ready." + index;
                    string rolePath = Path.Combine(root, "role-" + index + ".txt");
                    string finalPath = Path.Combine(root, "final-" + index + ".txt");
                    EventWaitHandle ready = new EventWaitHandle(false,
                        EventResetMode.ManualReset, readyName);
                    readyEvents.Add(ready);

                    ProcessStartInfo startInfo = new ProcessStartInfo();
                    startInfo.FileName = executable;
                    startInfo.Arguments = String.Join(" ", new string[] {
                        "--worker", Quote(prefix), Quote(readyName), Quote(gateName),
                        Quote(releaseName), Quote(callbackName), Quote(rolePath), Quote(finalPath)
                    });
                    startInfo.UseShellExecute = false;
                    startInfo.CreateNoWindow = true;
                    Process worker = Process.Start(startInfo);
                    if (worker == null) throw new InvalidOperationException("Could not start a headless test worker.");
                    workers.Add(worker);
                }

                WaitHandle[] readyHandles = readyEvents.ToArray();
                if (!WaitHandle.WaitAll(readyHandles, GateTimeoutMilliseconds))
                    throw new TimeoutException("Competing workers did not reach the startup barrier.");
                gate.Set();

                int ownerIndex = -1;
                for (int index = 0; index < WorkerCount; index++)
                {
                    string rolePath = Path.Combine(root, "role-" + index + ".txt");
                    string role = WaitForFileText(rolePath, WorkerTimeoutMilliseconds);
                    if (role == "PRIMARY")
                    {
                        if (ownerIndex >= 0) throw new InvalidOperationException("More than one competing process claimed the singleton.");
                        ownerIndex = index;
                    }
                    else if (role != "DUPLICATE")
                    {
                        throw new InvalidOperationException("A competing process returned an invalid role.");
                    }
                }
                if (ownerIndex < 0) throw new InvalidOperationException("No competing process claimed the singleton.");

                for (int index = 0; index < WorkerCount; index++)
                {
                    if (index == ownerIndex) continue;
                    if (!workers[index].WaitForExit(WorkerTimeoutMilliseconds) || workers[index].ExitCode != 0)
                        throw new InvalidOperationException("A duplicate worker did not exit cleanly.");
                }

                using (HostControlInstance duplicate = new HostControlInstance(prefix))
                {
                    if (duplicate.IsPrimary) throw new InvalidOperationException("The parent unexpectedly acquired the singleton.");
                    if (duplicate.ShouldRun(true)) throw new InvalidOperationException("A --tray duplicate was allowed to start another UI.");
                    if (callback.WaitOne(250)) throw new InvalidOperationException("A --tray duplicate requested window activation.");

                    if (duplicate.ShouldRun(false)) throw new InvalidOperationException("An ordinary duplicate was allowed to start another UI.");
                    if (!callback.WaitOne(5000)) throw new TimeoutException("An ordinary shortcut did not reach the active owner's activation callback.");
                    callback.Reset();

                    if (duplicate.ShouldRun(false)) throw new InvalidOperationException("A repeated ordinary duplicate was allowed to start another UI.");
                    if (!callback.WaitOne(5000)) throw new TimeoutException("A repeated shortcut did not activate the existing owner.");
                }

                release.Set();
                released = true;
                if (!workers[ownerIndex].WaitForExit(WorkerTimeoutMilliseconds) || workers[ownerIndex].ExitCode != 0)
                    throw new InvalidOperationException("The primary worker did not exit cleanly after release.");
                string finalStatus = WaitForFileText(Path.Combine(root, "final-" + ownerIndex + ".txt"),
                    GateTimeoutMilliseconds);
                if (finalStatus != "PRIMARY:2")
                    throw new InvalidOperationException("The primary activation callback count was not exactly two.");
            }
            finally
            {
                if (!released)
                {
                    gate.Set();
                    release.Set();
                }
                foreach (Process worker in workers)
                {
                    try
                    {
                        if (!worker.HasExited)
                        {
                            worker.Kill();
                            worker.WaitForExit(5000);
                        }
                    }
                    catch { }
                    worker.Dispose();
                }
                foreach (EventWaitHandle ready in readyEvents) ready.Dispose();
                callback.Dispose();
                release.Dispose();
                gate.Dispose();
                try { DeleteTestDirectory(root); }
                catch { }
            }
        }

        private static void DeleteTestDirectory(string path)
        {
            string expectedParent = Path.GetFullPath(Path.GetTempPath()).TrimEnd(
                Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            string resolvedPath = Path.GetFullPath(path);
            DirectoryInfo parent = Directory.GetParent(resolvedPath);
            string resolvedParent = parent.FullName.TrimEnd(
                Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (!String.Equals(expectedParent, resolvedParent, StringComparison.OrdinalIgnoreCase) ||
                !Path.GetFileName(resolvedPath).StartsWith("MWHostControlTest-", StringComparison.Ordinal))
                throw new InvalidOperationException("Refusing to recursively remove an unexpected test directory.");
            Directory.Delete(resolvedPath, true);
        }

        private static int RunWorker(string[] args)
        {
            if (args.Length != 8) throw new ArgumentException("Worker arguments are incomplete.");
            string prefix = args[1];
            EventWaitHandle ready = EventWaitHandle.OpenExisting(args[2]);
            EventWaitHandle gate = EventWaitHandle.OpenExisting(args[3]);
            EventWaitHandle release = EventWaitHandle.OpenExisting(args[4]);
            EventWaitHandle callback = EventWaitHandle.OpenExisting(args[5]);
            try
            {
                ready.Set();
                if (!gate.WaitOne(GateTimeoutMilliseconds))
                    throw new TimeoutException("Worker startup barrier was not released.");

                using (HostControlInstance instance = new HostControlInstance(prefix))
                {
                    if (!instance.IsPrimary)
                    {
                        File.WriteAllText(args[6], "DUPLICATE");
                        return 0;
                    }

                    int activationCount = 0;
                    using (HostControlActivationListener listener = new HostControlActivationListener(
                        instance.ActivationEvent,
                        delegate
                        {
                            Interlocked.Increment(ref activationCount);
                            callback.Set();
                        }))
                    {
                        File.WriteAllText(args[6], "PRIMARY");
                        if (!release.WaitOne(WorkerTimeoutMilliseconds))
                            throw new TimeoutException("Primary worker release was not signaled.");
                        File.WriteAllText(args[7], "PRIMARY:" + activationCount);
                    }
                }
                return 0;
            }
            finally
            {
                callback.Dispose();
                release.Dispose();
                gate.Dispose();
                ready.Dispose();
            }
        }

        private static string WaitForFileText(string path, int timeoutMilliseconds)
        {
            Stopwatch timer = Stopwatch.StartNew();
            while (timer.ElapsedMilliseconds < timeoutMilliseconds)
            {
                if (File.Exists(path))
                {
                    try { return File.ReadAllText(path); }
                    catch (IOException) { }
                }
                Thread.Sleep(10);
            }
            throw new TimeoutException("A headless test worker did not publish its result.");
        }

        private static string Quote(string value)
        {
            StringBuilder result = new StringBuilder();
            result.Append('"');
            int backslashes = 0;
            foreach (char character in value)
            {
                if (character == '\\')
                {
                    backslashes++;
                    continue;
                }
                if (character == '"')
                {
                    result.Append('\\', backslashes * 2 + 1);
                    result.Append('"');
                }
                else
                {
                    result.Append('\\', backslashes);
                    result.Append(character);
                }
                backslashes = 0;
            }
            result.Append('\\', backslashes * 2);
            result.Append('"');
            return result.ToString();
        }
    }
}
