using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.ServiceProcess;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;

namespace MoonWaker.HostControl
{
    // Host Control's backend for the signed-in Windows account, free of UI types (the WPF
    // window and the tray call it). It reads what the Gateway, the installation and the
    // hosted processes publish and performs the account's own actions. Reading the status starts
    // no process and changes nothing; every action names the profile it acts on and checks that
    // this account owns it.
    internal sealed class HostBackend
    {
        internal const string GatewayServiceName = "MoonWakerGateway";
        // A profile id names a directory under <install>\profiles: never "." or "..".
        private static readonly Regex ProfileIdPattern = new Regex("^(?!\\.+$)[A-Za-z0-9._-]{1,64}$");
        private static readonly string[] DiscordScopes = {
            "rpc", "identify", "guilds", "rpc.voice.read", "rpc.voice.write" };

        internal readonly string InstallRoot;
        internal readonly string GatewayDirectory;
        private readonly string sid;
        private readonly Func<string> python;
        private readonly Action restartGateway;

        internal HostBackend(string installRoot)
            : this(installRoot, ProfileProcessHost.CurrentSid(),
                delegate { return ProfileProcessHost.GatewayPython("python.exe"); }, RestartGatewayService)
        {
        }

        // python: the installation's console Python (the Gateway service's --python).
        // restartGateway: restarts the MoonWakerGateway service; tests pass a stand-in.
        internal HostBackend(string installRoot, string sid, Func<string> python, Action restartGateway)
        {
            if (restartGateway == null) throw new ArgumentNullException("restartGateway");
            InstallRoot = Path.GetFullPath(installRoot).TrimEnd('\\');
            GatewayDirectory = Path.Combine(InstallRoot, "gateway");
            this.sid = sid ?? "";
            this.python = python;
            this.restartGateway = restartGateway;
        }

        private string GatewayFile(string name) { return Path.Combine(GatewayDirectory, name); }

        internal bool DiscordInstalled { get { return ProfileProcessHost.DiscordInstalled(InstallRoot); } }

        internal string LegendaryExecutable
        {
            get
            {
                string path = Path.Combine(InstallRoot, "tools", "legendary", "legendary.exe");
                return File.Exists(path) ? path : null;
            }
        }

        // ---- Status ----

        internal HostStatus ReadStatus()
        {
            byte[] raw = HostFiles.ReadBytes(GatewayFile("gateway.json"));
            Dictionary<string, object> registry = HostFiles.ParseJson(raw);
            Dictionary<string, object> version = HostFiles.ReadJson(Path.Combine(InstallRoot, "version.json"));
            HostStatus status = new HostStatus();
            // A change set names the revision its editor showed; the helper applies it only to that one.
            status.RegistryRevision = registry == null ? "" : RegistryRevision.Of(raw);
            status.InstalledVersion = HostFiles.Text(version, "version", "unknown");
            status.InstalledBuild = HostFiles.Text(version, "build", "unknown");
            status.LegendaryInstalled = LegendaryExecutable != null;
            status.DiscordInstalled = DiscordInstalled;
            status.Gateway = ReadGateway(registry, status);
            status.VibepolloTokenSavedAt = VibepolloTokenSavedAt();
            status.VibepolloTokenSaved = status.VibepolloTokenSavedAt != null;
            status.Profiles = new List<ProfileStatus>();
            foreach (KeyValuePair<string, Dictionary<string, object>> item in StandardProfiles(registry))
                status.Profiles.Add(ReadProfile(item.Key, item.Value, registry));
            return status;
        }

        // Paired TVs as gateway.json records them: display fields and grants only, never the
        // token digest. The Gateway's client-activity.json may hold a newer "last seen".
        private List<PairedClient> ReadClients(Dictionary<string, object> registry)
        {
            List<PairedClient> result = new List<PairedClient>();
            IList clients = registry == null ? null : HostFiles.Value(registry, "clients") as IList;
            if (clients == null) return result;
            Dictionary<string, object> activity = HostFiles.Value(
                HostFiles.ReadJson(GatewayFile("client-activity.json")), "clients") as Dictionary<string, object>;
            foreach (object item in clients)
            {
                Dictionary<string, object> record = item as Dictionary<string, object>;
                if (record == null) continue;
                PairedClient client = new PairedClient();
                client.Id = HostFiles.Text(record, "id", "");
                client.Name = HostFiles.Text(record, "name", "Android TV");
                client.PairedAt = HostFiles.Number(record, "paired_at");
                client.LastSeenAt = Math.Max(HostFiles.Number(record, "last_seen_at"),
                    HostFiles.Number(activity, client.Id));
                Dictionary<string, object> grants = HostFiles.Value(record, "profile_grants") as Dictionary<string, object>;
                if (grants != null)
                    foreach (KeyValuePair<string, object> grant in grants)
                    {
                        IList permissions = grant.Value as IList;
                        if (permissions == null) continue;
                        List<string> names = new List<string>();
                        foreach (object permission in permissions)
                            if (permission is string) names.Add((string)permission);
                        client.Grants[grant.Key] = names;
                    }
                result.Add(client);
            }
            return result;
        }

        private GatewayStatus ReadGateway(Dictionary<string, object> registry, HostStatus host)
        {
            GatewayStatus gateway = new GatewayStatus();
            gateway.Installed = registry != null;
            gateway.Port = (int)HostFiles.Number(registry, "listen_port");
            gateway.Clients = ReadClients(registry);
            long expires = HostFiles.Number(HostFiles.ReadJson(GatewayFile("pairing-code.json")), "expires_at");
            gateway.PairingExpiresAt = expires > DateTimeOffset.UtcNow.ToUnixTimeSeconds() ? expires : 0;

            // The service publishes its Python child; the child publishes its own identity.
            Dictionary<string, object> service = HostFiles.ReadJson(GatewayFile("gateway-service-state.json"));
            Dictionary<string, object> runtime = HostFiles.ReadJson(GatewayFile("gateway-runtime.json"));
            string serviceState = HostFiles.Text(service, "status", "");
            long processId = HostFiles.Number(service, "process_id");
            if (serviceState == "running" && processId > 0 &&
                HostFiles.Number(runtime, "pid") == processId && ProcessExists((int)processId))
            {
                gateway.State = "running";
                gateway.RuntimeVersion = HostFiles.Text(runtime, "version", "");
                gateway.RuntimeBuild = HostFiles.Text(runtime, "build", "");
                gateway.VersionMismatch = gateway.RuntimeVersion != host.InstalledVersion ||
                    gateway.RuntimeBuild != host.InstalledBuild;
            }
            // ponytail: a service that died without writing "stopped" still reads as starting or
            // recovering; Napraw restarts it either way.
            else if (serviceState == "starting" || serviceState == "running") gateway.State = "starting";
            else if (serviceState == "recovering") gateway.State = "recovering";
            else gateway.State = "stopped";
            if (gateway.State == "running") ReadPublishedStatus(gateway);
            return gateway;
        }

        // gateway-status.json, which the running Gateway rewrites at least every 5 s: whether
        // Vibepollo's API answers it and its stream record. Missing, unreadable or older than
        // 30 s (the Gateway hangs): unknown, nothing guessed.
        private void ReadPublishedStatus(GatewayStatus gateway)
        {
            Dictionary<string, object> published = HostFiles.ReadJson(GatewayFile("gateway-status.json"), 64 * 1024);
            long updated = HostFiles.Number(published, "updated_at");
            if (Math.Abs(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - updated) > 30) return;
            gateway.StatusKnown = true;
            object online = HostFiles.Value(published, "vibepollo_api_online");
            if (online is bool) gateway.VibepolloOnline = (bool)online;
            Dictionary<string, object> session = HostFiles.Value(published, "session") as Dictionary<string, object>;
            if (session == null) return;
            gateway.Session = new ActiveSession {
                ActorProfileId = HostFiles.Text(session, "actor_profile_id", ""),
                ActorProfileName = HostFiles.Text(session, "actor_profile_name", ""),
                ExecutionProfileId = HostFiles.Text(session, "execution_profile_id", ""),
                ExecutionProfileName = HostFiles.Text(session, "execution_profile_name", ""),
                ClientName = HostFiles.Text(session, "client_name", ""),
                GameId = HostFiles.Text(session, "game_id", ""),
                StreamStartedUnixMs = HostFiles.Number(session, "stream_started_unix_ms") };
        }

        // The token file is readable only by Administrators and the service; its size and time
        // are not. The installer creates it as "{}", the Configurator writes the token into it.
        private DateTime? VibepolloTokenSavedAt()
        {
            try
            {
                FileInfo file = new FileInfo(GatewayFile("vibepollo-api.json"));
                return file.Exists && file.Length > 2 ? file.LastWriteTime : (DateTime?)null;
            }
            catch { return null; }
        }

        private ProfileStatus ReadProfile(string id, Dictionary<string, object> entry,
            Dictionary<string, object> registry)
        {
            ProfileStatus profile = new ProfileStatus();
            profile.Id = id;
            profile.Name = HostFiles.Text(entry, "name", id);
            profile.Owner = HostFiles.Text(entry, "windows_account_name", "");
            profile.Enabled = HostFiles.Flag(entry, "enabled", false);
            profile.PinRequired = HostFiles.Value(entry, "pin_verifier") != null;
            profile.RemoteSignInEnabled = HostFiles.Flag(entry, "remote_sign_in_enabled", false);
            profile.DeletionPending = HostFiles.Value(entry, "deletion_tombstone") != null;
            profile.CurrentUser = OwnedBy(entry, sid);
            profile.Sid = HostFiles.Text(entry, "windows_account_sid", "");
            profile.Root = HostFiles.Text(entry, "profile_root", "");
            profile.AccountResolved = profile.Sid.Length > 0 &&
                HostFiles.Text(entry, "account_mapping_status", "") == "resolved";
            profile.ChildrenRevision = (int)HostFiles.Number(entry, "children_policy_revision");
            profile.Children = new List<ChildStatus>();
            foreach (KeyValuePair<string, object> item in (Dictionary<string, object>)registry["profiles"])
            {
                Dictionary<string, object> child = item.Value as Dictionary<string, object>;
                if (child != null && IsChild(child) && HostFiles.Text(child, "parent_profile_id", "") == id)
                    profile.Children.Add(ReadChild(item.Key, child));
            }
            profile.Children.Sort(delegate(ChildStatus left, ChildStatus right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.Name, right.Name);
            });
            // Each account's tray hosts its own children; their answers are their health.
            // /health answers without the Agent's state lock, so a busy Agent still reads online.
            profile.Agent = LoopbackHealth.Probe(HostFiles.Text(entry, "game_provider_bridge", ""), "/health");
            profile.Discord = LoopbackHealth.Probe(HostFiles.Text(entry, "discord_bridge", ""), "/health");
            // Another account's profile directory is not readable from this one.
            string root = profile.CurrentUser ? ProfileRoot(id, entry) : null;
            if (root != null)
            {
                profile.SteamConnected = SteamConnected(root);
                profile.EpicConnected = EpicConnected(root);
                profile.PlayniteConnected = PlayniteConnected(root);
                profile.DiscordConfigured = DiscordConfigured(root);
                // The Client ID is the Discord application's public identifier, not a secret.
                profile.DiscordClientId = HostFiles.Text(HostFiles.ReadJson(
                    Path.Combine(root, "discord", "discord_bridge_config.json")), "client_id", "");
            }
            return profile;
        }

        private static readonly string[] Weekdays = { "mon", "tue", "wed", "thu", "fri", "sat", "sun" };

        // A child's record as its editor shows it; a missing day reads as the Configurator's default.
        private static ChildStatus ReadChild(string id, Dictionary<string, object> entry)
        {
            ChildStatus child = new ChildStatus {
                Id = id, Name = HostFiles.Text(entry, "display_name", HostFiles.Text(entry, "name", id)),
                AvatarId = HostFiles.Text(entry, "avatar_id", ""), Enabled = HostFiles.Flag(entry, "enabled", false) };
            IList games = HostFiles.Value(entry, "allowed_game_keys") as IList;
            child.SharedGames = games == null ? 0 : games.Count;
            Dictionary<string, object> weekdays = HostFiles.Value(
                HostFiles.Value(entry, "schedule") as Dictionary<string, object>, "weekdays") as Dictionary<string, object>;
            foreach (string name in Weekdays)
            {
                Dictionary<string, object> day = HostFiles.Value(weekdays, name) as Dictionary<string, object>;
                child.Days.Add(new ChildDay {
                    Enabled = HostFiles.Flag(day, "enabled", false),
                    StartMinute = day != null && day.ContainsKey("start_minute") ? (int)HostFiles.Number(day, "start_minute") : 0,
                    EndMinute = day != null && day.ContainsKey("end_minute") ? (int)HostFiles.Number(day, "end_minute") : 1440,
                    DailyLimitSeconds = (int)HostFiles.Number(day, "daily_limit_seconds") });
            }
            return child;
        }

        private static bool ProcessExists(int processId)
        {
            try { using (Process.GetProcessById(processId)) return true; }
            catch { return false; }
        }

        private static IEnumerable<KeyValuePair<string, Dictionary<string, object>>> StandardProfiles(
            Dictionary<string, object> registry)
        {
            Dictionary<string, object> profiles = registry == null ? null :
                HostFiles.Value(registry, "profiles") as Dictionary<string, object>;
            if (profiles == null) yield break;
            foreach (KeyValuePair<string, object> item in profiles)
            {
                Dictionary<string, object> entry = item.Value as Dictionary<string, object>;
                if (entry != null && !IsChild(entry))
                    yield return new KeyValuePair<string, Dictionary<string, object>>(item.Key, entry);
            }
        }

        private static bool IsChild(Dictionary<string, object> entry)
        {
            return String.Equals(HostFiles.Text(entry, "kind", ""), "child", StringComparison.OrdinalIgnoreCase);
        }

        private static bool OwnedBy(Dictionary<string, object> entry, string sid)
        {
            string owner = HostFiles.Text(entry, "windows_account_sid", "");
            return owner.Length > 0 && String.Equals(owner, sid, StringComparison.OrdinalIgnoreCase);
        }

        // Exactly <install>\profiles\<id>, as registered, with no reparse point on the way; or null.
        private string ProfileRoot(string id, Dictionary<string, object> entry)
        {
            if (id == null || !ProfileIdPattern.IsMatch(id)) return null;
            try
            {
                string expected = Path.GetFullPath(Path.Combine(InstallRoot, "profiles", id)).TrimEnd('\\');
                string registered = HostFiles.Text(entry, "profile_root", "");
                if (registered.Length == 0 || !String.Equals(Path.GetFullPath(registered).TrimEnd('\\'),
                        expected, StringComparison.OrdinalIgnoreCase)) return null;
                for (DirectoryInfo cursor = new DirectoryInfo(expected); cursor != null; cursor = cursor.Parent)
                    if (cursor.Exists && (cursor.Attributes & FileAttributes.ReparsePoint) != 0) return null;
                return expected;
            }
            catch { return null; }
        }

        private static string SteamKeyPath(string root)
        {
            return Path.Combine(root, "game-provider", "steam-web-api-key.dpapi");
        }

        private static bool SteamConnected(string root)
        {
            try
            {
                string path = SteamKeyPath(root);
                return File.Exists(path) && Regex.IsMatch(
                    Dpapi.Unprotect(File.ReadAllText(path, Encoding.ASCII).Trim()), "^[A-Fa-f0-9]{32}$");
            }
            catch { return false; }
        }

        private string LegendaryState(string root) { return Path.Combine(root, "state", "legendary"); }

        // Legendary keeps the signed-in Epic account in user.json of its configuration
        // directory; "legendary status" reports exactly its displayName.
        // ponytail: reads Legendary's file instead of starting it on every refresh.
        private bool EpicConnected(string root)
        {
            if (LegendaryExecutable == null) return false;
            Dictionary<string, object> user = HostFiles.ReadJson(Path.Combine(LegendaryState(root), "user.json"));
            return HostFiles.Text(user, "displayName", "").Trim().Length > 0;
        }

        private static bool PlayniteConnected(string root)
        {
            Dictionary<string, object> config = HostFiles.ReadJson(Path.Combine(root, "game-provider", "config.json"));
            return HostFiles.Flag(config, "playnite_enabled", false) &&
                File.Exists(HostFiles.Text(config, "playnite_desktop_executable", "")) &&
                File.Exists(HostFiles.Text(config, "playnite_fullscreen_executable", ""));
        }

        private static bool DiscordConfigured(string root)
        {
            return File.Exists(Path.Combine(root, "discord", "discord_bridge_config.json")) &&
                File.Exists(Path.Combine(root, "discord", "client_secret.dpapi"));
        }

        // Runs the microphone worker's probe, so only on request, never in a status refresh.
        internal string ProbeMicrophone()
        {
            Dictionary<string, object> registry = HostFiles.ReadJson(GatewayFile("gateway.json"));
            string name = HostFiles.Text(registry, "microphone_worker", "MoonWakerMicrophoneWorker.exe");
            string worker = Path.IsPathRooted(name) ? name : Path.Combine(GatewayDirectory, name);
            if (!File.Exists(worker)) return "worker_missing";
            ProcessStartInfo info = new ProcessStartInfo(worker, "--probe");
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            try
            {
                using (Process process = Process.Start(info))
                {
                    if (process.WaitForExit(3000) && process.ExitCode == 0) return "ready";
                    if (!process.HasExited) { try { process.Kill(); } catch { } }
                }
            }
            catch { }
            return "steam_endpoint_missing_or_ambiguous_or_unsupported";
        }

        // ---- Account actions ----

        private sealed class OwnedProfile
        {
            internal string Id;
            internal Dictionary<string, object> Entry;
            internal string Root;
        }

        private OwnedProfile RequireOwnedProfile(string id, string purpose)
        {
            if (String.IsNullOrWhiteSpace(id)) throw new InvalidOperationException("Wybierz profil.");
            Dictionary<string, object> registry = HostFiles.ReadJson(GatewayFile("gateway.json"));
            if (registry == null) throw new InvalidOperationException("Gateway MoonWaker nie jest zainstalowany.");
            Dictionary<string, object> profiles = HostFiles.Value(registry, "profiles") as Dictionary<string, object>;
            object value;
            Dictionary<string, object> entry = profiles != null && profiles.TryGetValue(id, out value)
                ? value as Dictionary<string, object> : null;
            if (entry == null) throw new InvalidOperationException("Nieznany profil „" + id + "”.");
            if (IsChild(entry))
                throw new InvalidOperationException("Profilami dzieci zarządza menedżer profili dzieci.");
            string root = ProfileRoot(id, entry);
            if (!OwnedBy(entry, sid) || root == null)
                throw new InvalidOperationException("Zaloguj się na konto Windows właściciela profilu „" +
                    id + "”, aby " + purpose + ".");
            return new OwnedProfile { Id = id, Entry = entry, Root = root };
        }

        // A six-digit code for "Dodaj komputer" on the TV, valid for 10 minutes. The installer
        // grants local accounts in-place writes to pairing-code.json, which holds only the
        // code's SHA-256; the Gateway reads it on every pairing attempt.
        internal string CreatePairingCode()
        {
            if (!File.Exists(GatewayFile("gateway.json")))
                throw new InvalidOperationException("Gateway MoonWaker nie jest zainstalowany.");
            byte[] random = new byte[4];
            using (RandomNumberGenerator generator = RandomNumberGenerator.Create()) generator.GetBytes(random);
            string code = (100000 + BitConverter.ToUInt32(random, 0) % 900000).ToString();
            string digest;
            using (SHA256 sha = SHA256.Create())
                digest = Hex(sha.ComputeHash(Encoding.UTF8.GetBytes(code)));
            HostFiles.OverwriteInPlace(GatewayFile("pairing-code.json"), HostFiles.Serialize(
                new Dictionary<string, object> {
                    { "code_sha256", digest },
                    { "expires_at", DateTimeOffset.UtcNow.AddMinutes(10).ToUnixTimeSeconds() } }),
                "Brak kanału parowania Gatewaya. Zainstaluj ponownie MoonWaker Host.");
            return code;
        }

        // "Anuluj": the Gateway reads the file on every pairing attempt, so the emptied file ends
        // the code at once.
        internal void CancelPairingCode()
        {
            HostFiles.OverwriteInPlace(GatewayFile("pairing-code.json"), "{}",
                "Brak kanału parowania Gatewaya. Zainstaluj ponownie MoonWaker Host.");
        }

        // Saves a change set through the elevated helper: "MoonWakerHostConfigurator.exe --apply
        // --pipe <GUID> --client <this account's SID>" through UAC (ShellExecuteEx with runas;
        // Process keeps the handle of SEE_MASK_NOCLOSEPROCESS, so the helper's id cannot be
        // reused meanwhile), then one exchange over the pipe the helper creates. The helper
        // validates the set, shows it and asks for any password in its own window. Blocks until
        // it answers: call it off the UI thread. owner: the window the UAC prompt belongs to.
        internal ChangeAnswer ApplyChanges(string registryRevision, List<Dictionary<string, object>> changes, IntPtr owner)
        {
            string control = Path.Combine(InstallRoot, "control");
            string executable = Path.Combine(control, "MoonWakerHostConfigurator.exe");
            if (!File.Exists(executable))
                throw new InvalidOperationException("Brak pomocnika administratora. Zaktualizuj instalację MoonWaker Host.");
            if (!Regex.IsMatch(sid, "^S-1-[0-9-]+$")) throw new InvalidOperationException("Nie można ustalić konta Windows.");
            Guid channel = Guid.NewGuid();
            byte[] request = new UTF8Encoding(false).GetBytes(HostFiles.Serialize(new Dictionary<string, object> {
                { "protocol", ChangeChannel.Protocol }, { "registry_revision", registryRevision ?? "" },
                { "changes", changes } }));
            try
            {
                ProcessStartInfo info = new ProcessStartInfo(executable,
                    "--apply --pipe " + channel.ToString("D") + " --client " + sid);
                info.UseShellExecute = true;
                info.Verb = "runas";
                info.WorkingDirectory = control;
                info.ErrorDialogParentHandle = owner;
                Process helper;
                try { helper = Process.Start(info); }
                catch (System.ComponentModel.Win32Exception error)
                {
                    if (error.NativeErrorCode == 1223) return new ChangeAnswer("cancelled", "Anulowano zgodę administratora.");
                    throw;
                }
                using (helper)
                {
                    byte[] answer;
                    try
                    {
                        answer = ChangeChannel.Exchange(ChangeChannel.PipeName(channel), helper.Id,
                            delegate { return !helper.HasExited; }, request);
                    }
                    catch (AnswerLostException error) { return new ChangeAnswer("unknown", error.Message); }
                    Dictionary<string, object> reply = HostFiles.ParseJson(answer);
                    Array.Clear(answer, 0, answer.Length);
                    string outcome = HostFiles.Text(reply, "outcome", "");
                    if (HostFiles.Text(reply, "protocol", "") != ChangeChannel.Protocol ||
                        Array.IndexOf(new[] { "applied", "partial", "rejected", "failed", "cancelled" }, outcome) < 0)
                        throw new InvalidOperationException("Pomocnik administratora odpowiedział nieczytelnie.");
                    return new ChangeAnswer(outcome, HostFiles.Text(reply, "message", ""));
                }
            }
            finally { Array.Clear(request, 0, request.Length); }
        }

        // Ends this account's session through the Gateway, which owns the stream record and
        // closes the stream and the game itself (TRANSITION_CONTRACT.md, "Close session from Host
        // Control"). The request file is the only local channel; no Bridge is called. Blocks for
        // up to ~105 s: call it off the UI thread.
        internal CloseSessionResult CloseSession(int acceptSeconds)
        {
            Dictionary<string, object> registry = HostFiles.ReadJson(GatewayFile("gateway.json"));
            if (registry == null) return new CloseSessionResult("unavailable", "registry_unreadable");
            // One standard profile per Windows account (the Configurator's rule); the
            // runtime-status hint never selects the target.
            string profileId = AgentProfileSelector.Select(registry, sid);
            if (profileId == null) return new CloseSessionResult("unavailable", "profile_ambiguous");
            string requestPath = GatewayFile("host-close-request.json");
            string answerPath = GatewayFile("host-close-result.json");
            if (!File.Exists(requestPath)) return new CloseSessionResult("unavailable", "channel_missing");
            string requestId = Guid.NewGuid().ToString("N");
            // In place: local accounts may read and write this file, not delete or replace it.
            HostFiles.OverwriteInPlace(requestPath, HostFiles.Serialize(new Dictionary<string, object> {
                { "request_id", requestId },
                { "expires_at", DateTimeOffset.UtcNow.AddSeconds(45).ToUnixTimeSeconds() },
                { "profile_id", profileId } }), "host-close-request.json");
            bool accepted = false, withdrawn = false;
            // A busy Gateway (a close in flight) takes a while; the request's own expires_at
            // (45 s) bounds the wait anyway.
            DateTime acceptDeadline = DateTime.UtcNow.AddSeconds(acceptSeconds);
            DateTime deadline = DateTime.UtcNow.AddSeconds(105);
            while (DateTime.UtcNow < deadline)
            {
                Thread.Sleep(250);
                Dictionary<string, object> answer = HostFiles.ReadJson(answerPath);
                string state = "", reason = "";
                if (HostFiles.Text(answer, "request_id", "") == requestId)
                {
                    state = HostFiles.Text(answer, "state", "");
                    reason = HostFiles.Text(answer, "reason", "");
                }
                if (state == "completed" || state == "refused") return new CloseSessionResult(state, reason);
                if (state == "accepted") accepted = true;
                else if (!accepted && withdrawn)
                    return new CloseSessionResult("unavailable", "gateway_not_responding");
                else if (!accepted && DateTime.UtcNow >= acceptDeadline)
                {
                    // Nothing took the request up. Withdraw it, so a Gateway that starts later
                    // does not close a session the user was told is still open. The Gateway may
                    // have read it just before: wait one of its polls (1 s) and read its answer
                    // once more before saying that it does not respond.
                    HostFiles.OverwriteInPlace(requestPath, "{}", "host-close-request.json");
                    withdrawn = true;
                    Thread.Sleep(1000);
                }
            }
            return new CloseSessionResult("accepted", "result_timeout");
        }

        internal void SaveSteamWebApiKey(string profileId, string key)
        {
            OwnedProfile profile = RequireOwnedProfile(profileId, "skonfigurować Steam");
            if (key == null || !Regex.IsMatch(key, "^[A-Fa-f0-9]{32}$"))
                throw new InvalidOperationException("Klucz Steam Web API musi mieć dokładnie 32 znaki szesnastkowe.");
            string path = SteamKeyPath(profile.Root);
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.WriteAllText(path, Dpapi.Protect(key) + "\r\n", Encoding.ASCII);
            if (!SteamConnected(profile.Root))
            {
                File.Delete(path);
                throw new InvalidOperationException("Windows DPAPI nie potwierdził zapisanego klucza Steam Web API.");
            }
        }

        internal void RemoveSteamWebApiKey(string profileId)
        {
            HostFiles.Delete(SteamKeyPath(RequireOwnedProfile(profileId, "odłączyć Steam").Root));
        }

        // Interactive: Legendary opens its own console window and the Epic sign-in page.
        // MoonWaker never sees the Epic password. Blocks until Legendary exits (at most 15 min).
        internal void ConnectEpic(string profileId)
        {
            OwnedProfile profile = RequireOwnedProfile(profileId, "połączyć Epic");
            string executable = RequireLegendary();
            Directory.CreateDirectory(LegendaryState(profile.Root));
            ProcessStartInfo info = LegendaryStartInfo(executable, profile.Root, "auth");
            using (Process process = Process.Start(info))
            {
                if (!process.WaitForExit(15 * 60 * 1000))
                {
                    try { process.Kill(); } catch { }
                    throw new InvalidOperationException("Logowanie Legendary przekroczyło limit czasu.");
                }
                if (process.ExitCode != 0)
                    throw new InvalidOperationException("Logowanie Legendary zakończyło się kodem " + process.ExitCode + ".");
            }
            if (!EpicConnected(profile.Root))
                throw new InvalidOperationException("Legendary nie potwierdził połączenia z Epic.");
        }

        internal void DisconnectEpic(string profileId)
        {
            OwnedProfile profile = RequireOwnedProfile(profileId, "odłączyć Epic");
            ProcessStartInfo info = LegendaryStartInfo(RequireLegendary(), profile.Root, "auth --delete");
            info.CreateNoWindow = true;
            if (RunHidden(info, 60000) != 0)
                throw new InvalidOperationException("Legendary nie usunął uwierzytelnienia Epic.");
            if (EpicConnected(profile.Root))
                throw new InvalidOperationException("Legendary nadal zgłasza aktywne połączenie z Epic.");
        }

        private string RequireLegendary()
        {
            string executable = LegendaryExecutable;
            if (executable == null) throw new InvalidOperationException(
                "Brak Legendary. Zaktualizuj MoonWaker Host bieżącym instalatorem.");
            return executable;
        }

        private ProcessStartInfo LegendaryStartInfo(string executable, string root, string arguments)
        {
            ProcessStartInfo info = new ProcessStartInfo(executable, arguments);
            info.UseShellExecute = false;
            info.WorkingDirectory = Path.GetDirectoryName(executable);
            info.EnvironmentVariables["LEGENDARY_CONFIG_PATH"] = LegendaryState(root);
            return info;
        }

        // Points the Agent at a Playnite installation with the SunshinePlaynite Connector and
        // patches that connector for MoonWaker's launcher snapshots. The caller restarts the Agent.
        internal void ConnectPlaynite(string profileId, string directory)
        {
            OwnedProfile profile = RequireOwnedProfile(profileId, "zmienić Playnite");
            Dictionary<string, object> config = ReadProviderConfig(profile.Root);
            if (String.IsNullOrWhiteSpace(directory))
                throw new InvalidOperationException("Wybierz katalog instalacji Playnite.");
            string resolved = Path.GetFullPath(directory);
            string desktop = Path.Combine(resolved, "Playnite.DesktopApp.exe");
            string fullscreen = Path.Combine(resolved, "Playnite.FullscreenApp.exe");
            string connector = Path.Combine(resolved, "Extensions", "SunshinePlaynite", "SunshinePlaynite.psm1");
            if (!File.Exists(desktop) || !File.Exists(fullscreen) || !File.Exists(connector))
                throw new InvalidOperationException(
                    "Wybrany katalog nie zawiera Playnite z SunshinePlaynite Connector.");
            string patcher = Path.Combine(InstallRoot, "bridge-source", "playnite", "PatchPlayniteConnector.py");
            if (!File.Exists(patcher))
                throw new InvalidOperationException("Brak zainstalowanej łatki konektora Playnite.");
            string interpreter = python();
            ProcessStartInfo check = new ProcessStartInfo(interpreter, Quote(patcher) + " " + Quote(connector));
            check.CreateNoWindow = true;
            if (RunHidden(check, 60000) != 0)
                throw new InvalidOperationException("Zainstalowany konektor Playnite nie jest zgodny z łatką MoonWaker. Przeinstaluj rozszerzenie SunshinePlaynite w Playnite i połącz ponownie.");
            ProcessStartInfo apply = new ProcessStartInfo(interpreter,
                Quote(patcher) + " " + Quote(connector) + " --apply");
            apply.CreateNoWindow = true;
            if (RunHidden(apply, 60000) != 0)
                throw new InvalidOperationException("Nie udało się nałożyć łatki na konektor Playnite.");
            config["playnite_enabled"] = true;
            config["playnite_desktop_executable"] = desktop;
            config["playnite_fullscreen_executable"] = fullscreen;
            HostFiles.WriteJson(ProviderConfigPath(profile.Root), config);
        }

        internal void DisconnectPlaynite(string profileId)
        {
            OwnedProfile profile = RequireOwnedProfile(profileId, "zmienić Playnite");
            Dictionary<string, object> config = ReadProviderConfig(profile.Root);
            config["playnite_enabled"] = false;
            config["playnite_desktop_executable"] = "";
            config["playnite_fullscreen_executable"] = "";
            HostFiles.WriteJson(ProviderConfigPath(profile.Root), config);
        }

        private static string ProviderConfigPath(string root)
        {
            return Path.Combine(root, "game-provider", "config.json");
        }

        private static Dictionary<string, object> ReadProviderConfig(string root)
        {
            Dictionary<string, object> config = HostFiles.ReadJson(ProviderConfigPath(root));
            if (config == null) throw new InvalidOperationException(
                "Brak konfiguracji Agenta profilu. Zaktualizuj MoonWaker Host.");
            return config;
        }

        // Discord application data of this account's profile; both fields together, or neither to
        // keep a configuration that already exists. A new Client ID drops the old OAuth token.
        internal void ConfigureDiscord(string profileId, string clientId, string clientSecret)
        {
            OwnedProfile profile = RequireOwnedProfile(profileId, "skonfigurować Discord");
            if (!DiscordInstalled)
                throw new InvalidOperationException("Discord nie jest zainstalowany na tym komputerze.");
            bool hasId = !String.IsNullOrWhiteSpace(clientId);
            if (hasId != !String.IsNullOrWhiteSpace(clientSecret))
                throw new InvalidOperationException("Podaj jednocześnie Discord Client ID i Client Secret.");
            string directory = Path.Combine(profile.Root, "discord");
            string configPath = Path.Combine(directory, "discord_bridge_config.json");
            if (!hasId)
            {
                if (!DiscordConfigured(profile.Root))
                    throw new InvalidOperationException("Podaj dane aplikacji Discord przy pierwszej konfiguracji.");
                return;
            }
            clientId = clientId.Trim();
            if (!Regex.IsMatch(clientId, "^[0-9]{17,20}$"))
                throw new InvalidOperationException("Discord Client ID musi zawierać od 17 do 20 cyfr.");
            int port = LoopbackHealth.Port(HostFiles.Text(profile.Entry, "discord_bridge", ""));
            if (port == 0) throw new InvalidOperationException("Discord nie jest skonfigurowany dla profilu.");
            Directory.CreateDirectory(directory);
            string previousId = HostFiles.Text(HostFiles.ReadJson(configPath), "client_id", "");
            HostFiles.WriteJson(configPath, new Dictionary<string, object> {
                { "client_id", clientId }, { "port", port }, { "redirect_uri", "" },
                { "scopes", DiscordScopes } });
            File.WriteAllText(Path.Combine(directory, "client_secret.dpapi"),
                Dpapi.Protect(clientSecret) + "\r\n", Encoding.ASCII);
            if (previousId.Length > 0 && previousId != clientId)
                HostFiles.Delete(Path.Combine(directory, "oauth_token.dpapi"));
        }

        // "Odłącz": removes the profile's Discord secrets; the tray then stops its Discord Bridge.
        internal void DisconnectDiscord(string profileId)
        {
            OwnedProfile profile = RequireOwnedProfile(profileId, "odłączyć Discord");
            foreach (string name in new[] { "oauth_token.dpapi", "client_secret.dpapi" })
                HostFiles.Delete(Path.Combine(profile.Root, "discord", name));
        }

        // "Napraw": restarts what does not work - the Gateway service when it is not running or
        // runs another version than the installed one, and this account's hosted processes when
        // the Agent, or a configured Discord Bridge, does not answer. Changes no setting and no
        // profile. A tray with an elevated token and an Agent blocked after losing its authority
        // are not restarted (that would only lift the block): the result names the cause.
        internal RepairResult Repair(ProfileProcessHost processes)
        {
            HostStatus status = ReadStatus();
            RepairResult result = new RepairResult();
            if (status.Gateway.Installed &&
                (status.Gateway.State != "running" || status.Gateway.VersionMismatch))
            {
                restartGateway();
                result.Restarted.Add("Gateway");
            }
            ProfileStatus own = status.Profiles.Find(delegate(ProfileStatus item) { return item.CurrentUser; });
            if (own == null) return result;
            string hosted = processes.Describe(own.Id, false);
            bool discordDown = status.DiscordInstalled && own.DiscordConfigured &&
                (own.Discord == "offline" || own.Discord == "error");
            if (hosted == ProfileProcessHost.ElevatedStatus)
                result.Problem = "Host Control działa jako administrator — uruchom go zwykłym kontem.";
            else if (hosted == ProfileProcessHost.AuthorityLostStatus)
                result.Problem = "Profil nie ma uprawnień na tym koncie — sprawdź profil w Configuratorze.";
            else if (hosted != null || own.Agent != "online" || discordDown)
            {
                processes.RestartAll();
                result.Restarted.Add("procesy profilu");
            }
            return result;
        }

        // The installer's service DACL lets interactive users start, stop and query the Gateway
        // service (Install-WakePlayGateway.ps1, sc sdset ...IU): no elevation.
        private static void RestartGatewayService()
        {
            try
            {
                using (ServiceController service = new ServiceController(GatewayServiceName))
                {
                    if (service.Status != ServiceControllerStatus.Stopped)
                    {
                        if (service.Status != ServiceControllerStatus.StopPending) service.Stop();
                        service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(30));
                    }
                    service.Start();
                    service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                }
            }
            catch (Exception error)
            {
                throw new InvalidOperationException("Nie udało się ponownie uruchomić usługi Gateway: " +
                    error.Message, error);
            }
        }

        // A zip of the Gateway's and this account's profiles' diagnostic logs (rotated series
        // only, never configuration or secrets) on the Desktop (else Documents, else %TEMP%).
        internal DiagnosticsExport ExportDiagnostics(string outputDirectory)
        {
            if (String.IsNullOrWhiteSpace(outputDirectory))
                foreach (string candidate in new[] {
                    Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                    Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), Path.GetTempPath() })
                    if (!String.IsNullOrWhiteSpace(candidate) && Directory.Exists(candidate))
                    {
                        outputDirectory = candidate;
                        break;
                    }
            outputDirectory = Path.GetFullPath(outputDirectory);
            Directory.CreateDirectory(outputDirectory);

            string temporary = Path.GetFullPath(Path.GetTempPath()).TrimEnd('\\');
            string staging = Path.Combine(temporary, "MoonWaker-Diagnostics-" + Guid.NewGuid().ToString("N"));
            try
            {
                Directory.CreateDirectory(staging);
                int count = CopySeries(Path.Combine(GatewayDirectory, "logs"), "gateway-diagnostics.jsonl",
                    Path.Combine(staging, "gateway", "service"));
                Dictionary<string, object> registry = HostFiles.ReadJson(GatewayFile("gateway.json"));
                Dictionary<string, object> profiles = registry == null ? null :
                    HostFiles.Value(registry, "profiles") as Dictionary<string, object>;
                if (profiles != null)
                    foreach (KeyValuePair<string, object> item in profiles)
                    {
                        // Only this account's profiles; ProfileRoot validates the id it names.
                        Dictionary<string, object> entry = item.Value as Dictionary<string, object>;
                        string root = entry == null || !OwnedBy(entry, sid) ? null : ProfileRoot(item.Key, entry);
                        if (root == null) continue;
                        count += CopySeries(Path.Combine(root, "game-provider", "logs"),
                            "provider-diagnostics.jsonl",
                            Path.Combine(staging, "profiles", item.Key, "provider"));
                    }
                Dictionary<string, object> version = HostFiles.ReadJson(Path.Combine(InstallRoot, "version.json"));
                File.WriteAllText(Path.Combine(staging, "manifest.json"), HostFiles.Serialize(
                    new Dictionary<string, object> {
                        { "schema_version", 1 },
                        { "created_utc", DateTime.UtcNow.ToString("o") },
                        { "version", SafeName(HostFiles.Text(version, "version", "")) },
                        { "build", SafeName(HostFiles.Text(version, "build", "")) },
                        { "protocol_version", HostFiles.Number(version, "protocol_version") },
                        { "file_count", count } }), new UTF8Encoding(false));
                string archive = Path.Combine(outputDirectory, "MoonWaker-Diagnostics-" +
                    DateTime.UtcNow.ToString("yyyyMMdd-HHmmss-fff") + "-" +
                    Guid.NewGuid().ToString("N").Substring(0, 8) + ".zip");
                ZipFile.CreateFromDirectory(staging, archive, CompressionLevel.Optimal, false);
                return new DiagnosticsExport { Path = archive, Files = count };
            }
            finally
            {
                try { Directory.Delete(staging, true); } catch { }
            }
        }

        private static string SafeName(string value)
        {
            return Regex.IsMatch(value, "^[A-Za-z0-9._+-]{1,128}$") ? value : "unknown";
        }

        // <base> and its rotations <base>.<n>; no reparse points; read beside a live writer.
        private static int CopySeries(string source, string baseName, string destination)
        {
            FileInfo[] files;
            try { files = new DirectoryInfo(source).GetFiles(); }
            catch { return 0; }
            Array.Sort(files, delegate(FileInfo left, FileInfo right) {
                return String.CompareOrdinal(left.Name, right.Name); });
            Regex pattern = new Regex("^" + Regex.Escape(baseName) + "(?:\\.[0-9]+)?$");
            int copied = 0;
            foreach (FileInfo file in files)
            {
                if (!pattern.IsMatch(file.Name) || (file.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                string target = Path.Combine(destination, file.Name);
                try
                {
                    Directory.CreateDirectory(destination);
                    using (FileStream input = new FileStream(file.FullName, FileMode.Open, FileAccess.Read,
                        FileShare.ReadWrite | FileShare.Delete))
                    using (FileStream output = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.None))
                        input.CopyTo(output);
                    copied++;
                }
                catch { try { File.Delete(target); } catch { } }
            }
            return copied;
        }

        // Runs a hidden helper to completion; its output is read and dropped.
        private static int RunHidden(ProcessStartInfo info, int timeoutMilliseconds)
        {
            info.UseShellExecute = false;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            using (Process process = Process.Start(info))
            {
                process.OutputDataReceived += delegate { };
                process.ErrorDataReceived += delegate { };
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                if (!process.WaitForExit(timeoutMilliseconds))
                {
                    try { process.Kill(); } catch { }
                    throw new System.TimeoutException("Operacja Host Control przekroczyła limit czasu.");
                }
                process.WaitForExit();
                return process.ExitCode;
            }
        }

        private static string Quote(string value) { return "\"" + value.Replace("\"", "\\\"") + "\""; }

        internal static string Hex(byte[] value)
        {
            StringBuilder text = new StringBuilder(value.Length * 2);
            foreach (byte item in value) text.Append(item.ToString("x2"));
            return text.ToString();
        }
    }

    internal sealed class HostStatus
    {
        internal string InstalledVersion;
        internal string InstalledBuild;
        internal bool LegendaryInstalled;
        internal bool DiscordInstalled;
        internal bool VibepolloTokenSaved;
        internal DateTime? VibepolloTokenSavedAt;
        internal GatewayStatus Gateway;
        internal List<ProfileStatus> Profiles;
        // The SHA-256 of the gateway.json this status was read from; "" without one.
        internal string RegistryRevision = "";
    }

    internal sealed class GatewayStatus
    {
        internal bool Installed;
        // "running", "starting", "recovering" or "stopped".
        internal string State;
        internal int Port;
        internal List<PairedClient> Clients = new List<PairedClient>();
        // Unix seconds when the pairing code expires; 0 when none is valid.
        internal long PairingExpiresAt;
        internal string RuntimeVersion = "";
        internal string RuntimeBuild = "";
        internal bool VersionMismatch;
        // From gateway-status.json while the Gateway runs and keeps it fresh. Unknown: not
        // StatusKnown, VibepolloOnline null.
        internal bool StatusKnown;
        internal bool? VibepolloOnline;
        // The Gateway's stream record; null when there is none or StatusKnown is false.
        internal ActiveSession Session;
    }

    internal sealed class ActiveSession
    {
        internal string ActorProfileId, ActorProfileName, ExecutionProfileId, ExecutionProfileName;
        internal string ClientName;
        // "steam:…", "epic:…", a Playnite id or "" (a stream without a game).
        internal string GameId;
        // Host clock, Unix milliseconds; 0 when unknown.
        internal long StreamStartedUnixMs;
    }

    internal sealed class PairedClient
    {
        internal string Id;
        internal string Name;
        // Unix seconds; 0 when unknown.
        internal long PairedAt;
        internal long LastSeenAt;
        // Profile id -> "use_profile", "remote_sign_in", "manage_children".
        internal Dictionary<string, List<string>> Grants = new Dictionary<string, List<string>>();
    }

    internal sealed class ProfileStatus
    {
        internal string Id;
        internal string Name;
        internal string Owner;
        internal bool Enabled;
        internal bool CurrentUser;
        internal bool PinRequired;
        internal bool RemoteSignInEnabled;
        internal bool DeletionPending;
        // The Windows account (SID) a change to this profile names; "" before SID mapping.
        internal string Sid = "";
        internal string Root = "";
        internal bool AccountResolved;
        // children_policy_revision: a child change names the revision its editor showed.
        internal int ChildrenRevision;
        // "disabled" (no endpoint), "online", "error" (answered, not ok) or "offline".
        internal string Agent;
        internal string Discord;
        internal List<ChildStatus> Children;
        // Known only for this account's own profile.
        internal bool SteamConnected;
        internal bool EpicConnected;
        internal bool PlayniteConnected;
        internal bool DiscordConfigured;
        internal string DiscordClientId = "";
    }

    internal sealed class ChildStatus
    {
        internal string Id, Name, AvatarId;
        internal bool Enabled;
        // The games the parent shared on the TV (the child's allowed_game_keys).
        internal int SharedGames;
        // Monday first.
        internal List<ChildDay> Days = new List<ChildDay>();
    }

    internal sealed class ChildDay
    {
        internal bool Enabled;
        internal int StartMinute, EndMinute = 1440, DailyLimitSeconds;
    }

    // The elevated helper's answer to a change set: "applied", "partial", "rejected", "failed"
    // or "cancelled" (in its window or at the UAC prompt), with the message for the user; or
    // "unknown" when the set reached the helper and no answer came back.
    internal sealed class ChangeAnswer
    {
        internal readonly string Outcome, Message;
        internal ChangeAnswer(string outcome, string message) { Outcome = outcome; Message = message; }
    }

    internal sealed class CloseSessionResult
    {
        internal readonly string State;
        internal readonly string Reason;
        internal CloseSessionResult(string state, string reason) { State = state; Reason = reason; }
        internal bool Ok { get { return State == "completed"; } }
    }

    internal sealed class RepairResult
    {
        internal readonly List<string> Restarted = new List<string>();
        // Why this account's processes were not restarted; null when nothing stood in the way.
        internal string Problem;
    }

    internal sealed class DiagnosticsExport
    {
        internal string Path;
        internal int Files;
    }

    internal static class LoopbackHealth
    {
        internal const int MaxResponseBytes = 64 * 1024;
        internal const int DeadlineMilliseconds = 1500;

        // The Configurator's endpoint rule: http on 127.0.0.1 or localhost, a port >= 1024.
        internal static int Port(string endpoint)
        {
            Uri uri;
            return Uri.TryCreate((endpoint ?? "").Trim(), UriKind.Absolute, out uri) &&
                uri.Scheme == Uri.UriSchemeHttp && (uri.Host == "127.0.0.1" || uri.Host == "localhost") &&
                uri.Port >= 1024 && uri.Port <= 65535 ? uri.Port : 0;
        }

        // A Bridge answers on loopback only; anything else in the registry is not probed.
        internal static string Probe(string endpoint, string path)
        {
            if (String.IsNullOrWhiteSpace(endpoint)) return "disabled";
            int port = Port(endpoint);
            if (port == 0) return "offline";
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + port + path);
            request.Proxy = null;
            request.Timeout = DeadlineMilliseconds;
            request.ReadWriteTimeout = DeadlineMilliseconds;
            request.AllowAutoRedirect = false;
            // One deadline for connecting, the headers and the body, however slowly it trickles
            // or late it stalls: the abort fails whatever read is still waiting.
            using (new Timer(delegate { request.Abort(); }, null, DeadlineMilliseconds, Timeout.Infinite))
            try
            {
                using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                using (Stream stream = response.GetResponseStream())
                {
                    byte[] buffer = new byte[MaxResponseBytes];
                    int length = 0, read;
                    while (length < buffer.Length && (read = stream.Read(buffer, length, buffer.Length - length)) > 0)
                        length += read;
                    if (length == buffer.Length && stream.ReadByte() >= 0) return "error";
                    string body = Encoding.UTF8.GetString(buffer, 0, length);
                    try
                    {
                        Dictionary<string, object> value = new JavaScriptSerializer()
                            .DeserializeObject(body) as Dictionary<string, object>;
                        if (value != null)
                            return HostFiles.Value(value, "ok") is bool && !(bool)value["ok"] ? "error" : "online";
                    }
                    catch { }
                    return body.TrimStart().StartsWith("ok", StringComparison.OrdinalIgnoreCase) ? "online" : "error";
                }
            }
            catch { return "offline"; }
        }
    }

    internal static class Dpapi
    {
        // Hex of CurrentUser DPAPI over UTF-16 text, the format ConvertTo-SecureString reads
        // (Discord Bridge, the Agent's Steam key reader).
        internal static string Protect(string value)
        {
            byte[] plain = Encoding.Unicode.GetBytes(value);
            byte[] encrypted = null;
            try
            {
                encrypted = ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser);
                return HostBackend.Hex(encrypted);
            }
            finally
            {
                Array.Clear(plain, 0, plain.Length);
                if (encrypted != null) Array.Clear(encrypted, 0, encrypted.Length);
            }
        }

        internal static string Unprotect(string hex)
        {
            if (hex == null || !Regex.IsMatch(hex, "^(?:[A-Fa-f0-9]{2})+$"))
                throw new FormatException("The DPAPI value is not hexadecimal.");
            byte[] encrypted = new byte[hex.Length / 2];
            for (int index = 0; index < encrypted.Length; index++)
                encrypted[index] = Convert.ToByte(hex.Substring(index * 2, 2), 16);
            byte[] plain = null;
            try
            {
                plain = ProtectedData.Unprotect(encrypted, null, DataProtectionScope.CurrentUser);
                return Encoding.Unicode.GetString(plain);
            }
            finally
            {
                Array.Clear(encrypted, 0, encrypted.Length);
                if (plain != null) Array.Clear(plain, 0, plain.Length);
            }
        }
    }

    internal static class HostFiles
    {
        private const int MaxJsonBytes = 4 * 1024 * 1024;

        // Delete sharing: a reader never blocks the Gateway's or the Configurator's atomic
        // replacement of the file. Null when it is missing, unreadable or not a JSON object.
        internal static Dictionary<string, object> ReadJson(string path, int maxBytes = MaxJsonBytes)
        {
            return ParseJson(ReadBytes(path, maxBytes));
        }

        // The file's bytes, or null when it is missing, unreadable or larger than maxBytes.
        internal static byte[] ReadBytes(string path, int maxBytes = MaxJsonBytes)
        {
            try
            {
                using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete))
                {
                    if (stream.Length > maxBytes) return null;
                    byte[] content = new byte[stream.Length];
                    int offset = 0, read;
                    while (offset < content.Length && (read = stream.Read(content, offset, content.Length - offset)) > 0)
                        offset += read;
                    return offset == content.Length ? content : null;
                }
            }
            catch { return null; }
        }

        internal static Dictionary<string, object> ParseJson(byte[] content)
        {
            if (content == null) return null;
            try { return Serializer().Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(content).TrimStart('﻿')); }
            catch { return null; }
        }

        internal static string Serialize(object value) { return Serializer().Serialize(value); }

        // Files in a profile directory, which the account itself owns.
        internal static void WriteJson(string path, object value)
        {
            File.WriteAllText(path, Serialize(value), new UTF8Encoding(false));
        }

        // A Gateway channel file the installer created: local accounts may overwrite it, not
        // create or replace it.
        internal static void OverwriteInPlace(string path, string content, string missing)
        {
            if (!File.Exists(path)) throw new InvalidOperationException(missing);
            byte[] bytes = new UTF8Encoding(false).GetBytes(content);
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Write, FileShare.Read))
            {
                stream.SetLength(0);
                stream.Write(bytes, 0, bytes.Length);
            }
        }

        internal static void Delete(string path)
        {
            if (File.Exists(path)) File.Delete(path);
        }

        private static JavaScriptSerializer Serializer()
        {
            JavaScriptSerializer json = new JavaScriptSerializer();
            json.MaxJsonLength = MaxJsonBytes;
            return json;
        }

        internal static object Value(Dictionary<string, object> source, string key)
        {
            object value;
            return source != null && source.TryGetValue(key, out value) ? value : null;
        }

        internal static string Text(Dictionary<string, object> source, string key, string fallback)
        {
            object value = Value(source, key);
            return value == null ? fallback : Convert.ToString(value);
        }

        internal static long Number(Dictionary<string, object> source, string key)
        {
            object value = Value(source, key);
            if (value == null || value is bool) return 0;
            try { return Convert.ToInt64(value); }
            catch { return 0; }
        }

        internal static bool Flag(Dictionary<string, object> source, string key, bool fallback)
        {
            object value = Value(source, key);
            return value is bool ? (bool)value : fallback;
        }
    }
}
