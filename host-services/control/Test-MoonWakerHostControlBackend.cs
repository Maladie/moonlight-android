using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace MoonWaker.HostControl
{
    // Headless checks of Host Control's backend against a temporary installation: status,
    // pairing, Steam, Discord, Epic, Playnite, diagnostics export and the close-session channel.
    // Touches no service, task or registry and no network beyond loopback; Legendary, Python
    // and the microphone worker are copies of this executable.
    internal static class BackendTests
    {
        private const string ForeignSid = "S-1-5-21-100-200-300-4999";
        private static readonly string Sid = WindowsIdentity.GetCurrent().User.Value;
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
        private static string root;
        private static string gateway;
        private static string profile;
        private static int agentPort;
        private static int closedPort;
        private static volatile string agentReply = "online";
        private static readonly List<string> agentPaths = new List<string>();
        private static int gatewayRestarts;

        private static int Main(string[] args)
        {
            // Stand-ins started by the backend (or the process host) always get arguments; each
            // records its call beside its executable, so a status read that starts one shows.
            if (args.Length > 0)
                File.AppendAllText(Process.GetCurrentProcess().MainModule.FileName + ".calls",
                    String.Join(" ", args) + "\n");
            if (args.Length > 0 && args[0] == "exit78") return ChildRestartPolicy.AuthorityLostExitCode;
            if (args.Length > 0 && args[0] == "auth")
            {
                string user = Path.Combine(Environment.GetEnvironmentVariable("LEGENDARY_CONFIG_PATH"), "user.json");
                if (args.Length > 1 && args[1] == "--delete" && File.Exists(user)) File.Delete(user);
                return 0;
            }
            if (args.Length > 1 && args[0].EndsWith("PatchPlayniteConnector.py", StringComparison.Ordinal))
            {
                File.AppendAllText(args[1] + ".calls", (args.Length > 2 ? args[2] : "check") + "\n");
                return 0;
            }
            if (args.Length > 0) return 0;

            root = Path.Combine(Path.GetTempPath(), "MoonWakerHostControlBackend-" + Guid.NewGuid().ToString("N"));
            TcpListener agent = new TcpListener(IPAddress.Loopback, 0);
            try
            {
                agent.Start();
                agentPort = ((IPEndPoint)agent.LocalEndpoint).Port;
                TcpListener closed = new TcpListener(IPAddress.Loopback, 0);
                closed.Start();
                closedPort = ((IPEndPoint)closed.LocalEndpoint).Port;
                closed.Stop();
                Thread server = new Thread(delegate() { Serve(agent); });
                server.IsBackground = true;
                server.Start();
                CreateInstallation();

                TestStatus();
                TestProbe();
                TestPairing();
                TestSteam();
                TestDpapiCompatibility();
                TestReparseProfileRoot();
                TestDiscord();
                TestEpic();
                TestPlaynite();
                TestMicrophone();
                TestStatusStartsNothing();
                TestRepair();
                TestExport();
                TestCloseSession();
                Expect(gatewayRestarts == 0, "a Gateway restart outside the repair checks");
                Console.WriteLine("PASS Host Control backend: status, probe, pairing, Steam, DPAPI, reparse, " +
                    "Discord, Epic, Playnite, microphone, repair, export, close session.");
                return 0;
            }
            catch (Exception exception)
            {
                Console.Error.WriteLine("FAIL Host Control backend: " + exception.Message);
                return 1;
            }
            finally
            {
                agent.Stop();
                try { Directory.Delete(root, true); } catch { }
            }
        }

        private static HostBackend Backend()
        {
            // Never the real MoonWakerGateway service: a counter stands in for its restart.
            return new HostBackend(root, Sid, delegate { return Path.Combine(root, "python", "python.exe"); },
                delegate { Interlocked.Increment(ref gatewayRestarts); });
        }

        private static void CreateInstallation()
        {
            gateway = Path.Combine(root, "gateway");
            profile = Path.Combine(root, "profiles", "default");
            Directory.CreateDirectory(gateway);
            Directory.CreateDirectory(Path.Combine(profile, "game-provider"));
            Directory.CreateDirectory(Path.Combine(root, "profiles", "foreign"));
            Write(Path.Combine(root, "version.json"), "{\"version\":\"test\",\"build\":\"b1\",\"protocol_version\":1}");
            WriteRegistry(ForeignSid);
            foreach (string channel in new[] { "pairing-code.json", "host-close-request.json", "vibepollo-api.json" })
                Write(Path.Combine(gateway, channel), "{}");
            Write(Path.Combine(profile, "game-provider", "config.json"),
                "{\"listen_port\":8780,\"playnite_enabled\":false,\"keep\":\"yes\"}");
        }

        private static void WriteRegistry(string foreignSid)
        {
            WriteRegistry(foreignSid, closedPort);
        }

        private static void WriteRegistry(string foreignSid, int discordPort)
        {
            Write(Path.Combine(gateway, "gateway.json"), Json.Serialize(new Dictionary<string, object> {
                { "listen_port", 8785 },
                { "microphone_worker", "MoonWakerMicrophoneWorker.exe" },
                { "clients", new object[] { new Dictionary<string, object> {
                    { "id", "tv" }, { "token_sha256", "f00d" }, { "paired_at", 100 }, { "last_seen_at", 150 },
                    { "profile_grants", new Dictionary<string, object> {
                        { "default", new object[] { "use_profile", "remote_sign_in" } } } } } } },
                { "profiles", new Dictionary<string, object> {
                    { "default", new Dictionary<string, object> {
                        { "name", "Diagnostic test" }, { "owner", "PC\\test" },
                        { "windows_account_sid", Sid }, { "profile_root", profile },
                        { "pin_verifier", new Dictionary<string, object> { { "version", 1 } } },
                        { "game_provider_bridge", "http://127.0.0.1:" + agentPort },
                        { "discord_bridge", "http://127.0.0.1:" + discordPort } } },
                    // Account names are display only: the same name with another SID is foreign.
                    { "foreign", new Dictionary<string, object> {
                        { "name", "Foreign" }, { "owner", "PC\\test" },
                        { "windows_account_sid", foreignSid },
                        { "profile_root", Path.Combine(root, "profiles", "foreign") } } },
                    { "child", new Dictionary<string, object> {
                        { "kind", "child" }, { "name", "Child" }, { "parent_profile_id", "default" } } } } } }));
        }

        private static void TestStatus()
        {
            HostBackend backend = Backend();
            string before = Snapshot(root);
            HostStatus status = backend.ReadStatus();
            Expect(Snapshot(root) == before, "a status read changed files");
            Expect(status.InstalledVersion == "test" && status.InstalledBuild == "b1", "installed version");
            Expect(status.Profiles.Count == 2 && status.Profiles.TrueForAll(delegate(ProfileStatus item) {
                return item.Id != "child"; }), "child profiles are not Windows accounts");
            ProfileStatus own = Find(status, "default");
            ProfileStatus foreign = Find(status, "foreign");
            Expect(own.CurrentUser && !foreign.CurrentUser, "profiles correlate with their Windows SID");
            Expect(own.PinRequired && !foreign.PinRequired && own.Enabled, "PIN and enabled state");
            Expect(own.Agent == "online" && own.Discord == "offline", "agent online, Discord port closed");
            Expect(foreign.Agent == "disabled" && foreign.Discord == "disabled", "missing endpoints are disabled");
            lock (agentPaths) Expect(agentPaths.TrueForAll(delegate(string path) {
                return path == "/health"; }) && agentPaths.Count > 0, "the Agent is probed on /health only");
            agentReply = "error";
            Expect(Find(backend.ReadStatus(), "default").Agent == "error", "an Agent answering ok:false is an error");
            agentReply = "text";
            Expect(Find(backend.ReadStatus(), "default").Agent == "online", "a plain ok answer is online");
            agentReply = "online";

            Expect(status.Gateway.Installed && status.Gateway.Port == 8785 && status.Gateway.Clients.Count == 1 &&
                status.Gateway.Clients[0].Id == "tv" && status.Gateway.Clients[0].Name == "Android TV" &&
                own.Children.Count == 1 && own.Children[0] == "Child" && foreign.Children.Count == 0,
                "Gateway registry facts");
            PairedClient tv = status.Gateway.Clients[0];
            Expect(tv.PairedAt == 100 && tv.LastSeenAt == 150 && tv.Grants.Count == 1 &&
                String.Join(",", tv.Grants["default"].ToArray()) == "use_profile,remote_sign_in",
                "paired TV dates and grants");
            Write(Path.Combine(gateway, "client-activity.json"), "{\"schema_version\":1,\"clients\":{\"tv\":200}}");
            Expect(backend.ReadStatus().Gateway.Clients[0].LastSeenAt == 200, "the Gateway's newer activity");
            File.Delete(Path.Combine(gateway, "client-activity.json"));
            Expect(status.Gateway.State == "stopped" && !status.VibepolloTokenSaved, "nothing published yet");
            int self = Process.GetCurrentProcess().Id;
            Write(Path.Combine(gateway, "gateway-service-state.json"),
                "{\"status\":\"running\",\"restart_count\":0,\"process_id\":" + self + "}");
            Write(Path.Combine(gateway, "gateway-runtime.json"),
                "{\"version\":\"test\",\"build\":\"b1\",\"pid\":" + self + ",\"started_at\":1}");
            Write(Path.Combine(gateway, "vibepollo-api.json"), "{\"base_url\":\"https://127.0.0.1:47990\",\"token\":\"t\"}");
            status = backend.ReadStatus();
            Expect(status.Gateway.State == "running" && !status.Gateway.VersionMismatch &&
                status.VibepolloTokenSaved && status.VibepolloTokenSavedAt != null,
                "a running Gateway of the installed version");
            Write(Path.Combine(gateway, "gateway-runtime.json"),
                "{\"version\":\"test\",\"build\":\"old\",\"pid\":" + self + ",\"started_at\":1}");
            Expect(backend.ReadStatus().Gateway.VersionMismatch, "a different running build is reported");
            Write(Path.Combine(gateway, "gateway-runtime.json"), "{\"version\":\"test\",\"build\":\"b1\",\"pid\":1}");
            Expect(backend.ReadStatus().Gateway.State == "starting", "the child has not published itself yet");
            Write(Path.Combine(gateway, "gateway-service-state.json"), "{\"status\":\"recovering\",\"process_id\":0}");
            Expect(backend.ReadStatus().Gateway.State == "recovering", "a recovering service");
            Write(Path.Combine(gateway, "gateway-runtime.json"),
                "{\"version\":\"test\",\"build\":\"b1\",\"pid\":" + self + ",\"started_at\":1}");
            Write(Path.Combine(gateway, "gateway-service-state.json"),
                "{\"status\":\"running\",\"restart_count\":0,\"process_id\":" + self + "}");
        }

        private static void TestPairing()
        {
            HostBackend backend = Backend();
            string code = backend.CreatePairingCode();
            Expect(System.Text.RegularExpressions.Regex.IsMatch(code, "^[1-9][0-9]{5}$"), "a six-digit code");
            Dictionary<string, object> stored = Read(Path.Combine(gateway, "pairing-code.json"));
            string digest;
            using (System.Security.Cryptography.SHA256 sha = System.Security.Cryptography.SHA256.Create())
                digest = HostBackend.Hex(sha.ComputeHash(Encoding.UTF8.GetBytes(code)));
            long lifetime = Convert.ToInt64(stored["expires_at"]) - DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            Expect(stored.Count == 2 && (string)stored["code_sha256"] == digest && lifetime > 590 && lifetime <= 600,
                "only the code's SHA-256 with a 10-minute expiry is stored");
            long seconds = backend.ReadStatus().Gateway.PairingExpiresAt - DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            Expect(seconds > 590 && seconds <= 600, "pairing countdown");
            File.Delete(Path.Combine(gateway, "pairing-code.json"));
            Throws(delegate { backend.CreatePairingCode(); }, "Brak kanału parowania", "the channel is never created");
            Expect(!File.Exists(Path.Combine(gateway, "pairing-code.json")), "no pairing file was created");
            Write(Path.Combine(gateway, "pairing-code.json"), "{}");
        }

        private static void TestSteam()
        {
            HostBackend backend = Backend();
            string key = new string('a', 32);
            Throws(delegate { backend.SaveSteamWebApiKey("default", "xyz"); }, "32 znaki", "an invalid key");
            Throws(delegate { backend.SaveSteamWebApiKey("foreign", key); }, "Zaloguj się", "a foreign profile");
            Throws(delegate { backend.SaveSteamWebApiKey("child", key); }, "dzieci", "a child profile");
            Throws(delegate { backend.SaveSteamWebApiKey("missing", key); }, "Nieznany profil", "an unknown profile");
            backend.SaveSteamWebApiKey("default", key);
            string path = Path.Combine(profile, "game-provider", "steam-web-api-key.dpapi");
            string stored = File.ReadAllText(path, Encoding.ASCII);
            Expect(!stored.Contains(key) && Dpapi.Unprotect(stored.Trim()) == key && stored.EndsWith("\r\n"),
                "the key is stored as CurrentUser DPAPI hex");
            Expect(Find(backend.ReadStatus(), "default").SteamConnected, "Steam connected");
            backend.RemoveSteamWebApiKey("default");
            Expect(!File.Exists(path) && !Find(backend.ReadStatus(), "default").SteamConnected, "Steam disconnected");
        }

        private static void TestDiscord()
        {
            HostBackend backend = Backend();
            string id = "123456789012345678", secret = Guid.NewGuid().ToString("N");
            Throws(delegate { backend.ConfigureDiscord("default", id, secret); },
                "Discord nie jest zainstalowany", "Discord is optional");
            Directory.CreateDirectory(Path.Combine(root, "bridge-source", "discord"));
            Write(Path.Combine(root, "bridge-source", "discord", "DiscordBridge.ps1"), "");
            Throws(delegate { backend.ConfigureDiscord("default", "", ""); }, "pierwszej konfiguracji",
                "first configuration needs both fields");
            Throws(delegate { backend.ConfigureDiscord("default", id, ""); }, "jednocześnie", "both fields");
            Throws(delegate { backend.ConfigureDiscord("default", "12", secret); }, "17 do 20", "Client ID digits");
            backend.ConfigureDiscord("default", id, secret);
            string directory = Path.Combine(profile, "discord");
            Dictionary<string, object> config = Read(Path.Combine(directory, "discord_bridge_config.json"));
            Expect((string)config["client_id"] == id && Convert.ToInt32(config["port"]) == closedPort &&
                ((System.Collections.ICollection)config["scopes"]).Count == 5 && (string)config["redirect_uri"] == "",
                "Discord configuration keeps the profile's endpoint port");
            Expect(Dpapi.Unprotect(File.ReadAllText(Path.Combine(directory, "client_secret.dpapi")).Trim()) == secret,
                "the client secret is stored with CurrentUser DPAPI");
            Expect(Find(backend.ReadStatus(), "default").DiscordConfigured &&
                Find(backend.ReadStatus(), "default").DiscordClientId == id, "Discord configured");
            Write(Path.Combine(directory, "oauth_token.dpapi"), "00");
            backend.ConfigureDiscord("default", "", "");
            Expect(File.Exists(Path.Combine(directory, "oauth_token.dpapi")), "empty fields keep the configuration");
            backend.ConfigureDiscord("default", "987654321098765432", secret);
            Expect(!File.Exists(Path.Combine(directory, "oauth_token.dpapi")), "a new Client ID drops the OAuth token");
            Write(Path.Combine(directory, "oauth_token.dpapi"), "00");
            backend.DisconnectDiscord("default");
            Expect(!File.Exists(Path.Combine(directory, "oauth_token.dpapi")) &&
                !File.Exists(Path.Combine(directory, "client_secret.dpapi")) &&
                !Find(backend.ReadStatus(), "default").DiscordConfigured, "Odłącz removes the Discord secrets");
            Throws(delegate { backend.DisconnectDiscord("foreign"); }, "Zaloguj się", "a foreign Discord");
        }

        private static void TestEpic()
        {
            HostBackend backend = Backend();
            string state = Path.Combine(profile, "state", "legendary");
            Directory.CreateDirectory(state);
            Write(Path.Combine(state, "user.json"), "{\"displayName\":\"epic-user\"}");
            Expect(!Find(backend.ReadStatus(), "default").EpicConnected, "no Legendary, no Epic");
            Throws(delegate { backend.DisconnectEpic("default"); }, "Brak Legendary", "Legendary is required");
            string legendary = Path.Combine(root, "tools", "legendary", "legendary.exe");
            Directory.CreateDirectory(Path.GetDirectoryName(legendary));
            File.Copy(Process.GetCurrentProcess().MainModule.FileName, legendary);
            HostStatus status = backend.ReadStatus();
            Expect(status.LegendaryInstalled && Find(status, "default").EpicConnected, "Legendary's signed-in account");
            Write(Path.Combine(state, "user.json"), "{\"displayName\":\"\"}");
            Expect(!Find(backend.ReadStatus(), "default").EpicConnected, "an empty account is not connected");
            Write(Path.Combine(state, "user.json"), "{\"displayName\":\"epic-user\"}");
            backend.DisconnectEpic("default");
            Expect(!File.Exists(Path.Combine(state, "user.json")) && !Find(backend.ReadStatus(), "default").EpicConnected,
                "auth --delete runs in the profile's Legendary state");
        }

        private static void TestPlaynite()
        {
            HostBackend backend = Backend();
            string python = Path.Combine(root, "python", "python.exe");
            Directory.CreateDirectory(Path.GetDirectoryName(python));
            File.Copy(Process.GetCurrentProcess().MainModule.FileName, python);
            Directory.CreateDirectory(Path.Combine(root, "bridge-source", "playnite"));
            Write(Path.Combine(root, "bridge-source", "playnite", "PatchPlayniteConnector.py"), "");
            string playnite = Path.Combine(root, "Playnite dir");
            string connector = Path.Combine(playnite, "Extensions", "SunshinePlaynite", "SunshinePlaynite.psm1");
            Directory.CreateDirectory(Path.GetDirectoryName(connector));
            Write(Path.Combine(playnite, "Playnite.DesktopApp.exe"), "");
            Write(Path.Combine(playnite, "Playnite.FullscreenApp.exe"), "");
            Throws(delegate { backend.ConnectPlaynite("default", playnite); }, "SunshinePlaynite",
                "Playnite without the connector");
            Write(connector, "");
            backend.ConnectPlaynite("default", playnite);
            Expect(File.ReadAllText(connector + ".calls") == "check\n--apply\n", "the connector is checked, then patched");
            Dictionary<string, object> config = Read(Path.Combine(profile, "game-provider", "config.json"));
            Expect((bool)config["playnite_enabled"] && (string)config["keep"] == "yes" &&
                (string)config["playnite_desktop_executable"] == Path.Combine(playnite, "Playnite.DesktopApp.exe"),
                "the Agent configuration points at Playnite and keeps other settings");
            Expect(Find(backend.ReadStatus(), "default").PlayniteConnected, "Playnite connected");
            backend.DisconnectPlaynite("default");
            config = Read(Path.Combine(profile, "game-provider", "config.json"));
            Expect(!(bool)config["playnite_enabled"] && (string)config["playnite_fullscreen_executable"] == "" &&
                !Find(backend.ReadStatus(), "default").PlayniteConnected, "Playnite disconnected");
        }

        private static void TestMicrophone()
        {
            HostBackend backend = Backend();
            Expect(backend.ProbeMicrophone() == "worker_missing", "no worker");
            File.Copy(Process.GetCurrentProcess().MainModule.FileName,
                Path.Combine(gateway, "MoonWakerMicrophoneWorker.exe"));
            Expect(backend.ProbeMicrophone() == "ready", "the worker's probe");
        }

        // Every stand-in is installed now: a status read must still start none of them.
        private static void TestStatusStartsNothing()
        {
            string before = Snapshot(root);
            Backend().ReadStatus();
            Expect(Snapshot(root) == before, "a status read started a process or changed files");
        }

        // Napraw restarts the Gateway (a counter here) only when it does not run the installed
        // version, and this account's processes only when the Agent or a configured Discord
        // Bridge does not answer; an elevated tray or a blocked Agent get a reason instead.
        private static void TestRepair()
        {
            HostBackend backend = Backend();
            string runtime = Path.Combine(gateway, "gateway-runtime.json");
            string service = Path.Combine(gateway, "gateway-service-state.json");
            string runtimeText = File.ReadAllText(runtime), serviceText = File.ReadAllText(service);
            int agentStarts = 0;
            string self = Process.GetCurrentProcess().MainModule.FileName;
            using (ProfileProcessHost processes = new ProfileProcessHost(root, Sid, false,
                delegate { agentStarts++; return new ProcessStartInfo(self, "exit78"); },
                delegate { throw new InvalidOperationException("not started"); }))
            {
                gatewayRestarts = 0;
                ExpectRepair(backend.Repair(processes), "", null, "nothing to repair");
                Expect(gatewayRestarts == 0, "a running Gateway of the installed version is not restarted");

                Write(runtime, runtimeText.Replace("\"b1\"", "\"old\""));
                ExpectRepair(backend.Repair(processes), "Gateway", null, "another running version");
                Write(runtime, runtimeText);
                Write(service, "{\"status\":\"stopped\",\"process_id\":0}");
                ExpectRepair(backend.Repair(processes), "Gateway", null, "a stopped Gateway");
                Write(service, serviceText);
                Expect(gatewayRestarts == 2, "the Gateway restarts: " + gatewayRestarts);

                agentReply = "error";
                ExpectRepair(backend.Repair(processes), "procesy profilu", null, "a failing Agent");
                agentReply = "online";

                // A configured Discord Bridge that does not answer is restarted with the Agent.
                backend.ConfigureDiscord("default", "123456789012345678", Guid.NewGuid().ToString("N"));
                ExpectRepair(backend.Repair(processes), "procesy profilu", null, "Discord does not answer");
                WriteRegistry(ForeignSid, agentPort);
                ExpectRepair(backend.Repair(processes), "", null, "Discord answers");
                WriteRegistry(ForeignSid);
                backend.DisconnectDiscord("default");
                Expect(gatewayRestarts == 2, "no Gateway restart for the profile's processes");

                // Authority lost: Napraw neither restarts nor lifts the block.
                for (int attempt = 0; attempt < 100 &&
                    processes.Describe("default", false) != ProfileProcessHost.AuthorityLostStatus; attempt++)
                {
                    processes.Tick();
                    Thread.Sleep(100);
                }
                Expect(processes.Describe("default", false) == ProfileProcessHost.AuthorityLostStatus &&
                    agentStarts == 1, "the Agent's authority-lost exit blocks it");
                agentReply = "error";
                ExpectRepair(backend.Repair(processes), "", "nie ma uprawnień", "a blocked Agent");
                agentReply = "online";
                for (int tick = 0; tick < 3; tick++) { processes.Tick(); Thread.Sleep(50); }
                Expect(processes.Describe("default", false) == ProfileProcessHost.AuthorityLostStatus &&
                    agentStarts == 1, "Napraw lifted the authority block");
            }
            using (ProfileProcessHost elevated = new ProfileProcessHost(root, Sid, true,
                delegate { throw new InvalidOperationException("not started"); },
                delegate { throw new InvalidOperationException("not started"); }))
            {
                elevated.Tick();
                agentReply = "error";
                ExpectRepair(backend.Repair(elevated), "", "administrator", "an elevated tray");
                agentReply = "online";
            }
            Expect(gatewayRestarts == 2, "no Gateway restart for the profile's processes");
            gatewayRestarts = 0;
        }

        private static void ExpectRepair(RepairResult result, string restarted, string problem, string name)
        {
            string actual = String.Join(",", result.Restarted.ToArray());
            Expect(actual == restarted && (problem == null ? result.Problem == null :
                result.Problem != null && result.Problem.Contains(problem)),
                name + ": " + actual + " / " + result.Problem);
        }

        // Loopback only, one deadline, one size limit.
        private static void TestProbe()
        {
            int before;
            lock (agentPaths) before = agentPaths.Count;
            Expect(LoopbackHealth.Probe("http://192.0.2.10:" + agentPort, "/health") == "offline" &&
                LoopbackHealth.Probe("http://127.0.0.2:" + agentPort, "/health") == "offline" &&
                LoopbackHealth.Probe("https://127.0.0.1:" + agentPort, "/health") == "offline",
                "an endpoint off 127.0.0.1/localhost http is not probed");
            lock (agentPaths) Expect(agentPaths.Count == before, "a non-loopback endpoint was probed");
            Expect(LoopbackHealth.Probe("http://localhost:" + agentPort, "/health") == "online", "localhost");

            agentReply = "big";
            Expect(LoopbackHealth.Probe("http://127.0.0.1:" + agentPort, "/health") == "error",
                "an answer over 64 KB is an error");
            foreach (string reply in new[] { "slow", "late" })
            {
                agentReply = reply;
                string answer = null;
                Stopwatch clock = Stopwatch.StartNew();
                Thread probe = new Thread(delegate() {
                    answer = LoopbackHealth.Probe("http://127.0.0.1:" + agentPort, "/health"); });
                probe.IsBackground = true;
                probe.Start();
                Expect(probe.Join(4000) && clock.ElapsedMilliseconds < LoopbackHealth.DeadlineMilliseconds + 800 &&
                    answer == "offline", "a " + reply + " answer ends at the deadline: " + answer + " after " +
                    clock.ElapsedMilliseconds + " ms");
            }
            agentReply = "online";
        }

        // Steam's key and Discord's secret are read by PowerShell's ConvertTo-SecureString.
        private static void TestDpapiCompatibility()
        {
            string secret = "MoonWaker-" + Guid.NewGuid().ToString("N");
            ProcessStartInfo info = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory,
                "WindowsPowerShell", "v1.0", "powershell.exe"),
                "-NoProfile -NonInteractive -Command \"$s = ConvertTo-SecureString -String '" + Dpapi.Protect(secret) +
                "'; [Runtime.InteropServices.Marshal]::PtrToStringBSTR(" +
                "[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)); ConvertFrom-SecureString " +
                "-SecureString (ConvertTo-SecureString -String '" + secret + "' -AsPlainText -Force)\"");
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            info.RedirectStandardOutput = true;
            string[] lines;
            using (Process process = Process.Start(info))
            {
                lines = process.StandardOutput.ReadToEnd().Trim().Split(new[] { "\r\n", "\n" },
                    StringSplitOptions.RemoveEmptyEntries);
                Expect(process.WaitForExit(30000) && process.ExitCode == 0, "PowerShell DPAPI check");
            }
            Expect(lines.Length == 2 && lines[0] == secret, "ConvertTo-SecureString reads the backend's DPAPI hex");
            Expect(Dpapi.Unprotect(lines[1]) == secret, "the backend reads ConvertFrom-SecureString's hex");
        }

        // A profile directory reached through a junction is not this account's profile root.
        private static void TestReparseProfileRoot()
        {
            string target = Path.Combine(root, "elsewhere");
            string link = Path.Combine(root, "profiles", "linked");
            Directory.CreateDirectory(Path.Combine(target, "game-provider"));
            ProcessStartInfo junction = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"),
                "/c mklink /J \"" + link + "\" \"" + target + "\"");
            junction.UseShellExecute = false;
            junction.CreateNoWindow = true;
            junction.RedirectStandardOutput = true;
            using (Process process = Process.Start(junction))
            {
                process.StandardOutput.ReadToEnd();
                Expect(process.WaitForExit(10000) && process.ExitCode == 0, "mklink /J");
            }
            Dictionary<string, object> registry = Read(Path.Combine(gateway, "gateway.json"));
            ((Dictionary<string, object>)registry["profiles"])["linked"] = new Dictionary<string, object> {
                { "name", "Linked" }, { "windows_account_sid", Sid }, { "profile_root", link } };
            Write(Path.Combine(gateway, "gateway.json"), Json.Serialize(registry));
            try
            {
                Throws(delegate { Backend().SaveSteamWebApiKey("linked", new string('b', 32)); }, "Zaloguj się",
                    "a junction profile root");
                Expect(!File.Exists(Path.Combine(target, "game-provider", "steam-web-api-key.dpapi")),
                    "nothing was written through the junction");
            }
            finally
            {
                WriteRegistry(ForeignSid);
                Directory.Delete(link);
            }
        }

        private static void TestExport()
        {
            HostBackend backend = Backend();
            Directory.CreateDirectory(Path.Combine(gateway, "logs"));
            Directory.CreateDirectory(Path.Combine(profile, "game-provider", "logs"));
            Write(Path.Combine(gateway, "gateway-supervisor.jsonl"), "{\"event\":\"supervisor.started\"}");
            Write(Path.Combine(gateway, "gateway-supervisor.jsonl.1"), "{\"event\":\"supervisor.stopped\"}");
            Write(Path.Combine(gateway, "logs", "gateway-diagnostics.jsonl"), "{\"event\":\"request.failed\"}");
            Write(Path.Combine(gateway, "logs", "gateway-diagnostics.jsonl.1"), "{\"event\":\"request.started\"}");
            Write(Path.Combine(profile, "game-provider", "logs", "provider-diagnostics.jsonl"), "{\"event\":\"x\"}");
            Write(Path.Combine(profile, "game-provider", "logs", "provider-diagnostics.jsonl.3"), "{\"event\":\"y\"}");
            Write(Path.Combine(gateway, "gateway-supervisor.jsonl.secret"), "SECRET-MATERIAL");
            Write(Path.Combine(profile, "game-provider", "logs", "provider-diagnostics.jsonl.bak"), "SECRET-MATERIAL");
            Write(Path.Combine(profile, "private-config.json"), "SECRET-MATERIAL");
            // Another account's logs, and a registered id ".." that would name the installation.
            string foreignLogs = Path.Combine(root, "profiles", "foreign", "game-provider", "logs");
            string installLogs = Path.Combine(root, "game-provider", "logs");
            Directory.CreateDirectory(foreignLogs);
            Directory.CreateDirectory(installLogs);
            Write(Path.Combine(foreignLogs, "provider-diagnostics.jsonl"), "SECRET-MATERIAL");
            Write(Path.Combine(installLogs, "provider-diagnostics.jsonl"), "SECRET-MATERIAL");
            Dictionary<string, object> registry = Read(Path.Combine(gateway, "gateway.json"));
            ((Dictionary<string, object>)registry["profiles"])[".."] = new Dictionary<string, object> {
                { "name", "Up" }, { "windows_account_sid", Sid }, { "profile_root", root } };
            Write(Path.Combine(gateway, "gateway.json"), Json.Serialize(registry));
            DiagnosticsExport export = backend.ExportDiagnostics(Path.Combine(root, "exports"));
            WriteRegistry(ForeignSid);
            Expect(export.Files == 6 && File.Exists(export.Path), "the export archive");
            using (ZipArchive archive = ZipFile.OpenRead(export.Path))
            {
                List<string> names = new List<string>();
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    names.Add(entry.FullName.Replace('\\', '/'));
                    using (StreamReader reader = new StreamReader(entry.Open()))
                        Expect(!reader.ReadToEnd().Contains("SECRET-MATERIAL"), "secret material in the export");
                }
                names.Sort(StringComparer.Ordinal);
                Expect(String.Join(",", names.ToArray()) ==
                    "gateway/service/gateway-diagnostics.jsonl,gateway/service/gateway-diagnostics.jsonl.1," +
                    "gateway/supervisor/gateway-supervisor.jsonl,gateway/supervisor/gateway-supervisor.jsonl.1," +
                    "manifest.json,profiles/default/provider/provider-diagnostics.jsonl," +
                    "profiles/default/provider/provider-diagnostics.jsonl.3", "export entries: " + String.Join(",", names.ToArray()));
                using (StreamReader reader = new StreamReader(archive.GetEntry("manifest.json").Open()))
                {
                    Dictionary<string, object> manifest = Json.Deserialize<Dictionary<string, object>>(reader.ReadToEnd());
                    Expect(manifest.Count == 6 && Convert.ToInt32(manifest["schema_version"]) == 1 &&
                        Convert.ToInt32(manifest["file_count"]) == 6 && (string)manifest["version"] == "test" &&
                        (string)manifest["build"] == "b1", "the export manifest");
                }
            }


        }

        // A stand-in for the Gateway answers the request file the way the Gateway does.
        private static void TestCloseSession()
        {
            HostBackend backend = Backend();
            string request = Path.Combine(gateway, "host-close-request.json");
            string answer = Path.Combine(gateway, "host-close-result.json");
            foreach (string[] item in new[] {
                new[] { "completed", "stream_and_game_closed", "" },
                new[] { "completed", "stream_closed_game_unconfirmed", "" },
                new[] { "refused", "operation_in_progress", "" },
                new[] { "completed", "stream_closed", "late" } })
            {
                Write(request, "{}");
                if (File.Exists(answer)) File.Delete(answer);
                string seen = null;
                string[] responseCase = item;
                Thread responder = new Thread(delegate() { seen = Respond(request, answer, responseCase); });
                responder.IsBackground = true;
                responder.Start();
                long requestedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                CloseSessionResult result = backend.CloseSession(item[2] == "late" ? 2 : 25);
                Expect(responder.Join(10000), "the responder finished");
                Dictionary<string, object> sent = Json.Deserialize<Dictionary<string, object>>(seen);
                List<string> keys = new List<string>(sent.Keys);
                keys.Sort(StringComparer.Ordinal);
                long lifetime = Convert.ToInt64(sent["expires_at"]) - requestedAt;
                Expect(result.State == item[0] && result.Reason == item[1] && result.Ok == (item[0] == "completed") &&
                    String.Join(",", keys.ToArray()) == "expires_at,profile_id,request_id" &&
                    System.Text.RegularExpressions.Regex.IsMatch((string)sent["request_id"], "^[0-9a-f]{32}$") &&
                    (string)sent["profile_id"] == "default" && lifetime >= 1 && lifetime <= 60,
                    "close session " + String.Join("/", item) + ": " + result.State + "/" + result.Reason);
            }

            // Nobody takes the request up: reported, and the request is withdrawn.
            Write(request, "{}");
            File.Delete(answer);
            CloseSessionResult silent = backend.CloseSession(1);
            Expect(!silent.Ok && silent.Reason == "gateway_not_responding" && File.ReadAllText(request).Trim() == "{}",
                "a silent Gateway: " + silent.Reason);

            // A second profile of this account: no target, nothing written.
            WriteRegistry(Sid);
            CloseSessionResult ambiguous = backend.CloseSession(1);
            Expect(ambiguous.Reason == "profile_ambiguous" && File.ReadAllText(request).Trim() == "{}",
                "ambiguous profile: " + ambiguous.Reason);
            WriteRegistry(ForeignSid);

            // An unreadable registry is its own reason, not an ambiguous profile.
            string registryText = File.ReadAllText(Path.Combine(gateway, "gateway.json"));
            Write(Path.Combine(gateway, "gateway.json"), "{not json");
            CloseSessionResult unreadable = backend.CloseSession(1);
            Expect(unreadable.Reason == "registry_unreadable" && File.ReadAllText(request).Trim() == "{}",
                "unreadable registry: " + unreadable.Reason);
            Write(Path.Combine(gateway, "gateway.json"), registryText);

            // Only the installer creates the channel.
            File.Delete(request);
            CloseSessionResult missing = backend.CloseSession(1);
            Expect(missing.Reason == "channel_missing" && !File.Exists(request), "missing channel: " + missing.Reason);
        }

        // Never throws: a failed check may delete the fixture while it still runs.
        private static string Respond(string request, string answer, string[] item)
        {
            try { return RespondOnce(request, answer, item); }
            catch { return null; }
        }

        private static string RespondOnce(string request, string answer, string[] item)
        {
            DateTime deadline = DateTime.UtcNow.AddSeconds(20);
            while (DateTime.UtcNow < deadline)
            {
                Thread.Sleep(100);
                string raw;
                raw = TryRead(request);
                if (!raw.Contains("request_id")) continue;
                string id;
                try { id = (string)Json.Deserialize<Dictionary<string, object>>(raw)["request_id"]; }
                catch { continue; }
                if (item[2] == "late")
                    // A busy Gateway that read the request answers only after it was withdrawn.
                    while (DateTime.UtcNow < deadline && TryRead(request).Trim() != "{}") Thread.Sleep(100);
                Write(request, "{}");
                foreach (string state in new[] { "accepted", item[0] })
                {
                    Write(answer, Json.Serialize(new Dictionary<string, object> {
                        { "request_id", id }, { "state", state },
                        { "reason", state == "accepted" ? "none" : item[1] } }));
                    Thread.Sleep(400);
                }
                return raw;
            }
            return null;
        }


        private static string TryRead(string path)
        {
            try { return File.ReadAllText(path); }
            catch (IOException) { return ""; }
        }

        private static void Serve(TcpListener listener)
        {
            while (true)
            {
                TcpClient client;
                try { client = listener.AcceptTcpClient(); } catch { return; }
                using (client)
                using (NetworkStream stream = client.GetStream())
                {
                    try
                    {
                        StreamReader reader = new StreamReader(stream, Encoding.ASCII);
                        string line = reader.ReadLine() ?? "";
                        while (!String.IsNullOrEmpty(reader.ReadLine())) { }
                        string[] parts = line.Split(' ');
                        lock (agentPaths) agentPaths.Add(parts.Length > 1 ? parts[1] : "");
                        if (agentReply == "slow")
                        {
                            // "ok" and then a byte every 100 ms: no read times out, the body never ends.
                            byte[] head = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                                "Content-Length: 100000\r\nConnection: close\r\n\r\nok");
                            stream.Write(head, 0, head.Length);
                            for (int sent = 0; sent < 100000 && agentReply == "slow"; sent++)
                            {
                                stream.Write(new[] { (byte)'x' }, 0, 1);
                                Thread.Sleep(100);
                            }
                            continue;
                        }
                        if (agentReply == "late")
                        {
                            // Headers just inside the deadline, then the body stalls.
                            Thread.Sleep(1300);
                            byte[] head = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                                "Content-Length: 100\r\nConnection: close\r\n\r\nok");
                            stream.Write(head, 0, head.Length);
                            for (int wait = 0; wait < 50 && agentReply == "late"; wait++) Thread.Sleep(100);
                            continue;
                        }
                        if (agentReply == "big")
                        {
                            // Valid within its first 64 KB ("ok..."), too long as a whole.
                            string large = "ok" + new string('x', LoopbackHealth.MaxResponseBytes);
                            byte[] whole = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                                "Content-Length: " + large.Length + "\r\nConnection: close\r\n\r\n" + large);
                            stream.Write(whole, 0, whole.Length);
                            continue;
                        }
                        string body = agentReply == "online" ? "{\"ok\":true,\"schema_version\":1}"
                            : agentReply == "error" ? "{\"ok\":false}" : "ok";
                        byte[] response = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: " +
                            (agentReply == "text" ? "text/plain" : "application/json") + "\r\nContent-Length: " +
                            body.Length + "\r\nConnection: close\r\n\r\n" + body);
                        stream.Write(response, 0, response.Length);
                    }
                    catch { }
                }
            }
        }

        private static ProfileStatus Find(HostStatus status, string id)
        {
            ProfileStatus found = status.Profiles.Find(delegate(ProfileStatus item) { return item.Id == id; });
            if (found == null) throw new InvalidOperationException("profile " + id + " missing");
            return found;
        }

        private static string Snapshot(string directory)
        {
            StringBuilder value = new StringBuilder();
            foreach (string path in Directory.GetFileSystemEntries(directory, "*", SearchOption.AllDirectories))
            {
                FileInfo file = new FileInfo(path);
                value.Append(path).Append('|').Append(file.Exists ? file.Length + "|" + file.LastWriteTimeUtc.Ticks : "d")
                    .Append('\n');
            }
            return value.ToString();
        }

        private static Dictionary<string, object> Read(string path)
        {
            return Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(path));
        }

        private static void Write(string path, string text)
        {
            File.WriteAllText(path, text, new UTF8Encoding(false));
        }

        private static void Throws(Action action, string message, string name)
        {
            try { action(); }
            catch (InvalidOperationException error)
            {
                Expect(error.Message.Contains(message), name + ": " + error.Message);
                return;
            }
            throw new InvalidOperationException(name + ": no error");
        }

        private static void Expect(bool condition, string name)
        {
            if (!condition) throw new InvalidOperationException(name);
        }
    }
}
