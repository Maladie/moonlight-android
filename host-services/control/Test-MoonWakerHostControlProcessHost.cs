using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Web.Script.Serialization;

namespace MoonWaker.HostControl
{
    // Headless checks of the tray's process host: restart policy, profile selection,
    // Python source, the hosting decisions of Tick, and the kill-on-close job. Touches no
    // service, task or registry; every hosted process is a sleeping copy of this executable.
    internal static class ProcessHostTests
    {
        private const string Sid = "S-1-5-21-10-20-30-1001";

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool TerminateProcess(IntPtr process, uint exitCode);

        private static int Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "--sleep")
            {
                Thread.Sleep(60000);
                return 0;
            }
            if (args.Length > 1 && args[0] == "--spawn-grandchild")
            {
                using (Process grandchild = Process.Start(SleepStartInfo()))
                    File.WriteAllText(args[1] + ".tmp", grandchild.Id.ToString());
                File.Move(args[1] + ".tmp", args[1]);
                Thread.Sleep(60000);
                return 0;
            }
            if (args.Length > 1 && args[0] == "--job-owner")
            {
                // Exit without disposing the job: Windows closes the handle as the tray dies.
                ChildProcessJob job = new ChildProcessJob();
                ProcessStartInfo info = SleepStartInfo();
                info.Arguments = "--spawn-grandchild \"" + args[1] + ".grandchild\"";
                Process child = job.Start(info);
                for (int wait = 0; wait < 200 && !File.Exists(args[1] + ".grandchild"); wait++)
                    Thread.Sleep(50);
                File.WriteAllText(args[1], child.Id.ToString());
                return 0;
            }
            try
            {
                TestBackoff();
                TestAuthorityBlock();
                TestProfileSelection();
                TestPythonFromServiceImage();
                TestKillOnOwnerExit();
                TestHostingDecisions();
                Console.WriteLine("PASS Host Control process host: backoff, authority block, profile, Python, job, hosting.");
                return 0;
            }
            catch (Exception exception)
            {
                Console.Error.WriteLine("FAIL Host Control process host: " + exception.Message);
                return 1;
            }
        }

        private static void TestBackoff()
        {
            int[] expected = { 0, 2, 4, 8, 16, 30, 30 };
            for (int failures = 0; failures < expected.Length; failures++)
                Expect(ChildRestartPolicy.Delay(failures).TotalSeconds == expected[failures],
                    "delay after " + failures + " failures");
            ChildRestartPolicy policy = new ChildRestartPolicy();
            TimeSpan quick = TimeSpan.FromSeconds(3);
            Expect(policy.OnExit(1, quick, "a").TotalSeconds == 2, "first crash waits 2 s");
            Expect(policy.OnExit(1, quick, "a").TotalSeconds == 4, "second crash doubles");
            Expect(policy.OnExit(70, quick, "a").TotalSeconds == 8, "watchdog exit doubles");
            Expect(policy.OnExit(74, quick, "a").TotalSeconds == 16 && !policy.Blocked,
                "an unreadable registry is a plain backoff");
            Expect(policy.OnExit(75, quick, "a").TotalSeconds == 30 && !policy.Blocked,
                "a busy port is a plain backoff");
            Expect(policy.OnExit(1, ChildRestartPolicy.StableRun, "a").TotalSeconds == 2,
                "a stable run resets the backoff");
            Expect(!policy.Blocked && policy.MayStart("a"), "a crash never blocks a restart");
        }

        private static void TestAuthorityBlock()
        {
            ChildRestartPolicy policy = new ChildRestartPolicy();
            policy.OnExit(1, TimeSpan.Zero, "a");
            Expect(policy.OnExit(ChildRestartPolicy.AuthorityLostExitCode, TimeSpan.Zero, "a") ==
                TimeSpan.Zero && policy.Blocked, "authority loss blocks");
            Expect(!policy.MayStart("a") && policy.Blocked, "unchanged registry keeps the block");
            Expect(policy.MayStart("b") && !policy.Blocked, "a changed registry entry allows a start");
            Expect(policy.OnExit(1, TimeSpan.Zero, "b").TotalSeconds == 2, "block reset the backoff");
            policy.OnExit(ChildRestartPolicy.AuthorityLostExitCode, TimeSpan.Zero, "unreadable");
            Expect(policy.MayStart("b"), "a readable registry ends a block from an unreadable one");
            policy.OnExit(ChildRestartPolicy.AuthorityLostExitCode, TimeSpan.Zero, "b");
            policy.Reset();
            Expect(policy.MayStart("b"), "an explicit repair clears the block");
        }

        private static void TestProfileSelection()
        {
            string json = "{\"profiles\":{" +
                "\"kid\":{\"kind\":\"child\",\"windows_account_sid\":\"" + Sid + "\"}," +
                "\"other\":{\"windows_account_sid\":\"S-1-5-21-10-20-30-1002\"}," +
                "\"parent\":{\"windows_account_sid\":\"" + Sid.ToLowerInvariant() + "\",\"enabled\":false}}}";
            Dictionary<string, object> registry = Parse(json);
            Expect(AgentProfileSelector.Select(registry, Sid) == "parent",
                "own standard profile is selected regardless of its enabled state");
            Expect(AgentProfileSelector.Select(registry, "S-1-5-21-10-20-30-1003") == null,
                "an account without a profile hosts nothing");
            Expect(AgentProfileSelector.Select(registry, "") == null, "empty SID hosts nothing");
            Expect(AgentProfileSelector.Select(Parse("{\"profiles\":{" +
                "\"a\":{\"windows_account_sid\":\"" + Sid + "\"}," +
                "\"b\":{\"windows_account_sid\":\"" + Sid + "\"}}}"), Sid) == null,
                "two profiles of one account are ambiguous");
            Expect(AgentProfileSelector.Select(Parse("{\"profiles\":{\"a b\":{\"windows_account_sid\":\"" +
                Sid + "\"}}}"), Sid) == null, "an unsafe profile id is refused");
            Expect(AgentProfileSelector.Select(Parse("{\"profiles\":[]}"), Sid) == null,
                "a malformed registry hosts nothing");
        }

        private static void TestPythonFromServiceImage()
        {
            Expect(ProfileProcessHost.PythonFromServiceImage(
                "\"C:\\Program Files\\MoonWaker\\gateway\\MoonWakerGatewayService.exe\" --gateway-dir " +
                "\"C:\\Program Files\\MoonWaker\\gateway\" --python \"C:\\Program Files\\Python312\\python.exe\"") ==
                "C:\\Program Files\\Python312\\python.exe", "quoted --python");
            Expect(ProfileProcessHost.PythonFromServiceImage(
                "service.exe --python C:\\Python312\\python.exe") == "C:\\Python312\\python.exe",
                "plain --python");
            Expect(ProfileProcessHost.PythonFromServiceImage("service.exe --gateway-dir x") == null,
                "missing --python");
            Expect(ProfileProcessHost.PythonFromServiceImage("service.exe --python python.exe") == null,
                "relative --python");
            Expect(ProfileProcessHost.PythonFromServiceImage(null) == null, "missing service");
        }

        private static void TestKillOnOwnerExit()
        {
            string pidPath = Path.Combine(Path.GetTempPath(),
                "MoonWakerProcessHostTest-" + Guid.NewGuid().ToString("N") + ".txt");
            Process grandchild = null;
            try
            {
                ProcessStartInfo owner = SleepStartInfo();
                owner.Arguments = "--job-owner \"" + pidPath + "\"";
                using (Process process = Process.Start(owner))
                    Expect(process.WaitForExit(15000) && process.ExitCode == 0, "job owner exits");
                Expect(File.Exists(pidPath + ".grandchild"), "the hosted child started a grandchild");
                try { grandchild = Process.GetProcessById(Int32.Parse(File.ReadAllText(pidPath + ".grandchild"))); }
                catch (ArgumentException) { }
                WaitForExit(Int32.Parse(File.ReadAllText(pidPath)), "the hosted child dies with its tray");
                Expect(grandchild != null && !grandchild.WaitForExit(1000),
                    "a game the child started survives the tray");
            }
            finally
            {
                if (grandchild != null)
                {
                    try { grandchild.Kill(); } catch { }
                    grandchild.Dispose();
                }
                File.Delete(pidPath);
                File.Delete(pidPath + ".grandchild");
            }
        }

        // Tick against a temporary installation whose Agent and Discord are sleeping copies.
        private static void TestHostingDecisions()
        {
            string root = Path.Combine(Path.GetTempPath(),
                "MoonWakerProcessHostTick-" + Guid.NewGuid().ToString("N"));
            try
            {
                Directory.CreateDirectory(Path.Combine(root, "gateway"));
                foreach (string id in new[] { "p", "q" })
                {
                    string discordData = Path.Combine(root, "profiles", id, "discord");
                    Directory.CreateDirectory(discordData);
                    File.WriteAllText(Path.Combine(discordData, "discord_bridge_config.json"), "{}");
                    File.WriteAllText(Path.Combine(discordData, "client_secret.dpapi"), "00");
                }
                WriteRegistry(root, "p", "x");

                using (ProfileProcessHost host = NewHost(root, true))
                {
                    host.Tick();
                    Expect(host.ProfileId == "p" && host.ProcessId(false) == 0 && host.ProcessId(true) == 0,
                        "an elevated tray hosts nothing");
                    Expect(host.Describe("p", false) == ProfileProcessHost.ElevatedStatus,
                        "an elevated tray says why");
                }

                // Without -WithDiscord the installation has no Discord Bridge: a configured
                // profile still runs only its Agent.
                using (ProfileProcessHost host = NewHost(root, false))
                {
                    WaitUntil(delegate { host.Tick(); return host.ProcessId(false) != 0; },
                        "the Agent runs without Discord installed");
                    host.Tick();
                    Expect(host.ProcessId(true) == 0, "Discord is not started when it is not installed");
                }
                string discordSource = Path.Combine(root, "bridge-source", "discord");
                Directory.CreateDirectory(discordSource);
                File.WriteAllText(Path.Combine(discordSource, "DiscordBridge.ps1"), "");

                using (ProfileProcessHost host = NewHost(root, false))
                {
                    RunBoth(host, "both children run for the account's profile");
                    // The Agent exits with the unreadable-registry code: backoff, no block,
                    // and Discord stops with it.
                    int discordId = host.ProcessId(true);
                    Terminate(host.ProcessId(false), 74);
                    host.Tick();
                    WaitForExit(discordId, "Discord stops when the Agent is not running");
                    host.Tick();
                    Expect(host.ProcessId(false) == 0 && host.ProcessId(true) == 0,
                        "nothing restarts during the backoff");
                    Expect(host.Describe("p", false).StartsWith("RESTART ZA "),
                        "an unreadable registry is a backoff, not a block");

                    // Repair: old processes are killed and new ones start only after they died.
                    host.RestartAll();
                    RunBoth(host, "repair ends the backoff");
                    int agentId = host.ProcessId(false);
                    discordId = host.ProcessId(true);
                    host.RestartAll();
                    Expect(Alive(agentId) && Alive(discordId), "repair on the UI thread does not stop or wait");
                    host.Tick();
                    Expect(host.ProcessId(false) == agentId && host.ProcessId(true) == discordId,
                        "no second start before the stopped process is gone");
                    WaitForExit(agentId, "repair kills the Agent");
                    WaitForExit(discordId, "repair kills Discord");
                    RunBoth(host, "repair restarts both children");

                    // Authority lost: no restart, Discord stops, until the entry changes.
                    discordId = host.ProcessId(true);
                    Terminate(host.ProcessId(false), (uint)ChildRestartPolicy.AuthorityLostExitCode);
                    host.Tick();
                    WaitForExit(discordId, "Discord stops when the Agent loses authority");
                    host.Tick();
                    Expect(host.ProcessId(false) == 0 && host.ProcessId(true) == 0 &&
                        host.Describe("p", false) == ProfileProcessHost.AuthorityLostStatus, "authority loss blocks");
                    WriteRegistry(root, "p", "y");
                    RunBoth(host, "a changed registry entry ends the block");

                    // The account's profile changes: the old children go first.
                    agentId = host.ProcessId(false);
                    discordId = host.ProcessId(true);
                    WriteRegistry(root, "q", "y");
                    host.Tick();
                    Expect(host.ProfileId == "q" && host.ProcessId(false) == agentId,
                        "a profile change stops the old Agent before a new start");
                    WaitForExit(agentId, "a profile change kills the old Agent");
                    WaitForExit(discordId, "a profile change kills the old Discord");
                    RunBoth(host, "the new profile's children start");
                    Expect(host.Describe("p", false) == null, "the old profile is no longer hosted");
                }
            }
            finally
            {
                try { Directory.Delete(root, true); } catch { }
            }
        }

        private static ProfileProcessHost NewHost(string root, bool elevated)
        {
            return new ProfileProcessHost(root, Sid, elevated,
                delegate { return SleepStartInfo(); }, delegate { return SleepStartInfo(); });
        }

        private static void WriteRegistry(string root, string id, string marker)
        {
            File.WriteAllText(Path.Combine(root, "gateway", "gateway.json"), "{\"profiles\":{\"" + id +
                "\":{\"windows_account_sid\":\"" + Sid + "\",\"discord_bridge\":\"http://127.0.0.1:1/\"," +
                "\"marker\":\"" + marker + "\"}}}");
        }

        // Ticks until both children run (a start may wait for a stopped process to exit).
        private static void RunBoth(ProfileProcessHost host, string name)
        {
            WaitUntil(delegate
            {
                host.Tick();
                return host.ProcessId(false) != 0 && host.ProcessId(true) != 0 &&
                    host.Describe(host.ProfileId, false) == null &&
                    host.Describe(host.ProfileId, true) == null;
            }, name);
        }

        private static void Terminate(int id, uint exitCode)
        {
            using (Process process = Process.GetProcessById(id))
            {
                Expect(TerminateProcess(process.Handle, exitCode), "terminate " + id);
                Expect(process.WaitForExit(5000), "terminated " + id);
            }
        }

        private static bool Alive(int id)
        {
            try { using (Process process = Process.GetProcessById(id)) return !process.HasExited; }
            catch (ArgumentException) { return false; }
        }

        private static void WaitForExit(int id, string name)
        {
            Process process;
            try { process = Process.GetProcessById(id); }
            catch (ArgumentException) { return; }
            using (process)
            {
                bool exited = process.WaitForExit(5000);
                if (!exited) { try { process.Kill(); } catch { } }
                Expect(exited, name);
            }
        }

        private static void WaitUntil(Func<bool> condition, string name)
        {
            for (int attempt = 0; attempt < 100; attempt++)
            {
                if (condition()) return;
                Thread.Sleep(100);
            }
            throw new InvalidOperationException(name);
        }

        private static ProcessStartInfo SleepStartInfo()
        {
            ProcessStartInfo info = new ProcessStartInfo(
                Process.GetCurrentProcess().MainModule.FileName, "--sleep");
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            return info;
        }

        private static Dictionary<string, object> Parse(string json)
        {
            return new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
        }

        private static void Expect(bool condition, string name)
        {
            if (!condition) throw new InvalidOperationException(name);
        }
    }
}
