using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using Microsoft.Win32;
using MoonWaker.HostControl;

[assembly: System.Reflection.AssemblyVersion("0.7.146.0")]
[assembly: System.Reflection.AssemblyFileVersion("0.7.146.0")]
[assembly: System.Reflection.AssemblyInformationalVersion("0.7.146+2026.10.17-launch-license-art")]
// WPF follows the per-monitor DPI of the manifest only for a .NET Framework 4.6.2+ target.
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8")]

namespace MoonWaker.HostConfigurator
{
    // The elevated helper. "--apply --pipe <GUID>": Host Control's change set over the change
    // channel, confirmed in its own window (ConfiguratorChanges.cs). "--mode child-api": the
    // Login Broker's child-profile requests. "--mode vibepollo-access": the installer's grant of
    // Vibepollo's files to the Gateway service.
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            string mode = Argument(args, "--mode");
            if (String.Equals(mode, "child-api", StringComparison.OrdinalIgnoreCase))
                return RunChildApi();
            if (String.Equals(mode, "vibepollo-access", StringComparison.OrdinalIgnoreCase))
                return RunVibepolloAccess();
            if (mode == null && args != null && args.Length == 5 && args[0] == "--apply")
                return RunApply(Argument(args, "--pipe"), Argument(args, "--client"));
            return 2;
        }

        // Serves one change set for the Host Control that started this process through UAC:
        // only the account it names may open the pipe, and the channel accepts only the process
        // that started this one, running as that account (a false claim reaches nobody).
        private static int RunApply(string pipe, string clientSid)
        {
            Guid id;
            SecurityIdentifier client;
            try { client = new SecurityIdentifier(clientSid ?? ""); }
            catch (ArgumentException) { return 2; }
            if (!Guid.TryParseExact(pipe ?? "", "D", out id) || !client.IsAccountSid() || !IsAdministrator()) return 2;
            try
            {
                ChangeChannel.Serve(ChangeChannel.PipeName(id), client, ParentProcess.Id(),
                    delegate(byte[] request, Func<bool> connected) {
                    return ApplyRequest.Handle(request, delegate {
                        return new ChangeApplier(GatewayRegistryStore.ForInstalledHost(), new ChangeEffects());
                    }, delegate(ChangePlan plan) {
                        try { plan.Requester = client.Translate(typeof(NTAccount)).Value; }
                        catch (IdentityNotMappedException) { plan.Requester = client.Value; }
                        return ConfirmWindow.Ask(plan);
                    }, connected);
                });
                return 0;
            }
            catch (Exception) { return 1; }
        }

        private static int RunChildApi()
        {
            JavaScriptSerializer serializer = new JavaScriptSerializer();
            serializer.MaxJsonLength = 4 * 1024 * 1024;
            Dictionary<string, object> result;
            using (StreamReader input = new StreamReader(
                Console.OpenStandardInput(), new UTF8Encoding(false)))
            {
                try
                {
                    if (!IsAdministrator()) result = ChildApiFailure("administrator_required");
                    else
                    {
                        string raw = input.ReadToEnd();
                        if (String.IsNullOrWhiteSpace(raw) || raw.Length > 4 * 1024 * 1024)
                            throw new InvalidOperationException("invalid_child_profile_request");
                        Dictionary<string, object> request =
                            serializer.Deserialize<Dictionary<string, object>>(raw);
                        result = GatewayRegistryStore.ForInstalledHost()
                            .ApplyChildProfileRequest(request);
                    }
                }
                catch (Exception error)
                {
                    result = ChildApiFailure(ChildApiReason(error));
                }
            }
            // The Broker launches this mode with redirected standard streams and
            // no console handle. Setting Console.InputEncoding/OutputEncoding
            // therefore throws IOException before the request can be read.
            using (StreamWriter output = new StreamWriter(
                Console.OpenStandardOutput(), new UTF8Encoding(false)))
            {
                output.WriteLine(serializer.Serialize(result));
            }
            return 0;
        }

        // The installer's half of the Gateway's Vibepollo read access; every token save repeats
        // it. Standard output lists missing files (exit 0), or why Vibepollo or its
        // configuration cannot be used yet (exit 2), or why the grant failed (exit 1).
        private static int RunVibepolloAccess()
        {
            List<string> lines = new List<string>();
            int code = 0;
            try
            {
                if (!IsAdministrator()) throw new UnauthorizedAccessException("administrator_required");
                lines.AddRange(VibepolloMachineApi.GrantGatewayReadAccess());
            }
            catch (InvalidOperationException error) { lines.Add(error.Message); code = 2; }
            catch (Exception error) { lines.Add(error.GetType().Name + ": " + error.Message); code = 1; }
            using (StreamWriter output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)))
                foreach (string line in lines) output.WriteLine(line);
            return code;
        }

        private static Dictionary<string, object> ChildApiFailure(string reason)
        {
            return new Dictionary<string, object> {
                { "ok", false }, { "reason", reason }, { "error", reason }
            };
        }

        private static string ChildApiReason(Exception error)
        {
            string message = error == null ? "" : error.Message;
            foreach (string known in new string[] {
                "child_policy_revision_stale", "request_id_reused",
                "administrator_required", "invalid_child_profile_request",
                "child_profile_not_owned", "game_not_in_parent_catalog",
                "invalid_child_game_key" })
                if (message.IndexOf(known, StringComparison.OrdinalIgnoreCase) >= 0)
                    return known;
            return "child_profile_write_failed";
        }

        private static string Argument(string[] args, string name)
        {
            if (args == null) return null;
            for (int index = 0; index + 1 < args.Length; index++)
                if (String.Equals(args[index], name, StringComparison.OrdinalIgnoreCase))
                    return args[index + 1];
            return null;
        }

        internal static bool IsAdministrator()
        {
            WindowsIdentity identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }

    internal sealed class ProfileRecord
    {
        internal string Id;
        internal string Kind;
        internal string ParentProfileId;
        internal string AvatarId;
        internal int PolicyRevision;
        internal List<string> AllowedGameKeys;
        internal string DisplayName;
        internal string Sid;
        internal string AccountName;
        internal string Root;
        internal string DiscordEndpoint;
        internal string GameProviderEndpoint;
        internal bool Enabled;
        internal bool AppPinRequired;
        internal bool RemoteSignInEnabled;
        internal string MappingStatus;
        internal string DeletionNonce;
        internal long DeletionGeneration;
        internal string ReservationNonce;

        internal void EnsureMutable()
        {
            if (!String.IsNullOrWhiteSpace(DeletionNonce))
                throw new InvalidOperationException(
                    "Profil oczekuje na usunięcie. Dokończ usuwanie albo utwórz profil ponownie.");
        }

        internal int DiscordPort { get { return GatewayRegistryStore.EndpointPort(DiscordEndpoint); } }
        internal int GameProviderPort { get { return GatewayRegistryStore.EndpointPort(GameProviderEndpoint); } }
    }

    internal sealed class DeviceRecord
    {
        internal string Id;
        internal string SafeId;
        internal string Name;
        internal long PairedAt;
        internal long LastSeenAt;
        internal bool UseProfile;
        internal bool RemoteSignIn;
        internal bool ManageChildren;
    }

    internal sealed class DeviceGrant
    {
        internal bool UseProfile;
        internal bool RemoteSignIn;
        internal bool ManageChildren;
    }

    internal sealed class ChildGameGrantRecord
    {
        internal string Id;
        internal string DisplayName;
        internal bool Enabled;
        internal bool Granted;
        internal int PolicyRevision;
    }

    internal sealed class ChildGameInvalidation
    {
        internal string ChildProfileId;
        internal string GameKey;
        internal int PolicyRevision;
    }

    internal sealed class ChildGameSharingSnapshot
    {
        internal string ParentProfileId;
        internal string GameKey;
        internal int Revision;
        internal List<ChildGameGrantRecord> Children;
    }

    internal sealed class ChildGameSharingResult
    {
        internal string ParentProfileId;
        internal string GameKey;
        internal int Revision;
        internal List<string> ChildProfileIds;
        internal List<ChildGameInvalidation> Invalidations;
        internal bool Idempotent;
    }

    internal sealed class GatewayRegistryStore
    {
        private const int SchemaVersion = 3;
        private const string ChildParentPolicyRevisionField = "children_policy_revision";
        private const string ChildSharingRequestsField = "child_sharing_requests";
        private const int MaxChildSharingRequests = 32;
        private const string ChildProfileRequestsField = "child_profile_requests";
        private const int MaxChildProfileRequests = 32;
        private static readonly string[] ChildWeekdays = new string[] {
            "mon", "tue", "wed", "thu", "fri", "sat", "sun" };
        private const string ChildGameKeyPattern =
            "^[A-Za-z0-9._-]{1,64}/[a-z][a-z0-9_-]{1,31}:[A-Za-z0-9._-]{1,128}$";
        private const string GameRecordIdPattern =
            "^[a-z][a-z0-9_-]{1,31}:[A-Za-z0-9._-]{1,128}$";
        private const string RequestIdPattern =
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$";
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();
        private readonly int lockTimeoutMilliseconds;
        internal readonly string ConfigPath;
        internal readonly string GatewayDirectory;
        internal readonly string InstallRoot;
        internal readonly string ProfilesRoot;
        internal readonly string RegistryLockPath;
        internal GatewayRegistryStore(string configPath, int lockTimeoutMilliseconds)
        {
            ConfigPath = Path.GetFullPath(configPath);
            GatewayDirectory = Path.GetDirectoryName(ConfigPath);
            InstallRoot = Directory.GetParent(GatewayDirectory).FullName;
            ProfilesRoot = Path.Combine(InstallRoot, "profiles");
            RegistryLockPath = ConfigPath + ".lock";
            this.lockTimeoutMilliseconds = lockTimeoutMilliseconds;
            json.MaxJsonLength = 4 * 1024 * 1024;
            if (!File.Exists(ConfigPath))
                throw new FileNotFoundException("Najpierw zainstaluj Gateway MoonWaker.", ConfigPath);
        }

        internal static GatewayRegistryStore ForInstalledHost()
        {
            string baseDirectory = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            DirectoryInfo control = new DirectoryInfo(baseDirectory);
            if (control.Parent == null) throw new InvalidOperationException(
                "Nie można ustalić chronionego katalogu instalacji.");
            string candidate = Path.Combine(control.Parent.FullName, "gateway", "gateway.json");
            return new GatewayRegistryStore(candidate, 10000);
        }

        internal static bool IsValidProfileId(string value)
        {
            return !String.IsNullOrWhiteSpace(value) && value.Length <= 64 &&
                System.Text.RegularExpressions.Regex.IsMatch(value, "^[A-Za-z0-9._-]+$");
        }

        internal static string NewProfileId()
        {
            return "p-" + Guid.NewGuid().ToString("N");
        }

        internal ProfileRecord GetProfile(string id)
        {
            Dictionary<string, object> document = ReadDocument();
            Dictionary<string, object> profile = Profile(document, id);
            return ToProfile(id, profile);
        }

        internal List<ProfileRecord> GetProfiles()
        {
            Dictionary<string, object> document = ReadDocument();
            Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
            List<ProfileRecord> result = new List<ProfileRecord>();
            foreach (KeyValuePair<string, object> item in profiles)
            {
                Dictionary<string, object> profile = item.Value as Dictionary<string, object>;
                if (profile != null && !String.Equals(Text(profile, "kind", "standard"),
                        "child", StringComparison.OrdinalIgnoreCase))
                    result.Add(ToProfile(item.Key, profile));
            }
            result.Sort(delegate(ProfileRecord left, ProfileRecord right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.DisplayName, right.DisplayName);
            });
            return result;
        }

        internal List<ProfileRecord> GetChildren(string parentProfileId)
        {
            if (!IsValidProfileId(parentProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil rodzica.");
            Dictionary<string, object> document = ReadDocument();
            List<ProfileRecord> result = new List<ProfileRecord>();
            foreach (KeyValuePair<string, object> item in
                Dictionary(document, "profiles", false))
            {
                Dictionary<string, object> profile = item.Value as Dictionary<string, object>;
                if (profile != null && String.Equals(Text(profile, "kind", "standard"),
                        "child", StringComparison.OrdinalIgnoreCase) &&
                    String.Equals(Text(profile, "parent_profile_id", ""), parentProfileId,
                        StringComparison.Ordinal))
                    result.Add(ToProfile(item.Key, profile));
            }
            result.Sort(delegate(ProfileRecord left, ProfileRecord right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.DisplayName, right.DisplayName);
            });
            return result;
        }

        internal int GetChildPolicyRevision(string parentProfileId)
        {
            if (!IsValidProfileId(parentProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil rodzica.");
            Dictionary<string, object> document = ReadDocument();
            object value;
            Dictionary<string, object> parent;
            if (!Dictionary(document, "profiles", false).TryGetValue(parentProfileId,
                    out value) || (parent = value as Dictionary<string, object>) == null ||
                !String.Equals(Text(parent, "kind", "standard"), "standard",
                    StringComparison.OrdinalIgnoreCase))
                return 0;
            return ChildPolicyRevision(parent);
        }

        // Local administrator cleanup is allowed to remove an orphan record even
        // when its former parent is gone or disabled.  It never removes Windows
        // identity or profile data, and it intentionally does not cascade.
        internal void RemoveChildProfileLocal(string childProfileId)
        {
            Transaction(null, delegate(Dictionary<string, object> document) {
                RemoveChildProfileLocalIn(document, childProfileId);
                return true;
            });
        }

        internal static void RemoveChildProfileLocalIn(Dictionary<string, object> document,
            string childProfileId)
        {
            if (!IsValidProfileId(childProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil dziecka.");
            Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
            object value;
            Dictionary<string, object> child;
            if (!profiles.TryGetValue(childProfileId, out value) ||
                (child = value as Dictionary<string, object>) == null ||
                !IsOwnedChild(child, Text(child, "parent_profile_id", "")))
                throw new InvalidOperationException("Profil dziecka nie istnieje.");
            string parentProfileId = Text(child, "parent_profile_id", "");
            profiles.Remove(childProfileId);
            RemoveProfileGrants(document, childProfileId);
            object parentValue;
            Dictionary<string, object> parent;
            if (profiles.TryGetValue(parentProfileId, out parentValue) &&
                (parent = parentValue as Dictionary<string, object>) != null &&
                String.Equals(Text(parent, "kind", "standard"), "standard",
                    StringComparison.OrdinalIgnoreCase))
                parent[ChildParentPolicyRevisionField] = ChildPolicyRevision(parent) + 1;
        }

        // The orphan or disabled-parent cleanup a live parent never takes: the parent is
        // missing, not a standard profile, or disabled (also while its removal is pending).
        internal static bool ChildNeedsLocalCleanup(Dictionary<string, object> document,
            string parentProfileId)
        {
            object value;
            Dictionary<string, object> parent;
            return !Dictionary(document, "profiles", false).TryGetValue(parentProfileId, out value) ||
                (parent = value as Dictionary<string, object>) == null ||
                !String.Equals(Text(parent, "kind", "standard"), "standard", StringComparison.OrdinalIgnoreCase) ||
                !BooleanValue(parent, "enabled");
        }

        internal ChildGameSharingSnapshot GetChildGameSharing(string parentProfileId,
            string gameKey)
        {
            if (!IsValidProfileId(parentProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil rodzica.");
            Dictionary<string, object> document = ReadDocument();
            Dictionary<string, object> parent = Profile(document, parentProfileId);
            EnsureChildPolicyParent(parent);
            string canonicalKey = NormalizeChildGameKey(parentProfileId, gameKey);
            List<ChildGameGrantRecord> children = new List<ChildGameGrantRecord>();
            foreach (KeyValuePair<string, object> item in
                Dictionary(document, "profiles", false))
            {
                Dictionary<string, object> child = item.Value as Dictionary<string, object>;
                if (!IsOwnedChild(child, parentProfileId)) continue;
                children.Add(new ChildGameGrantRecord {
                    Id = item.Key,
                    DisplayName = Text(child, "display_name", Text(child, "name", item.Key)),
                    Enabled = BooleanValue(child, "enabled") &&
                        Value(child, "deletion_tombstone") == null,
                    Granted = ContainsGameKey(child, canonicalKey),
                    PolicyRevision = ChildPolicyRevision(child)
                });
            }
            children.Sort(delegate(ChildGameGrantRecord left,
                ChildGameGrantRecord right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(
                    left.DisplayName, right.DisplayName);
            });
            return new ChildGameSharingSnapshot {
                ParentProfileId = parentProfileId,
                GameKey = canonicalKey,
                Revision = ChildPolicyRevision(parent),
                Children = children
            };
        }

        internal ChildGameSharingResult UpdateChildGameSharing(string parentProfileId,
            string gameKey, IEnumerable<string> selectedChildProfileIds,
            int expectedRevision, string requestId,
            IEnumerable<string> parentCatalogGameKeys)
        {
            if (!IsValidProfileId(parentProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil rodzica.");
            if (!System.Text.RegularExpressions.Regex.IsMatch(
                    requestId ?? "", RequestIdPattern))
                throw new InvalidOperationException("Nieprawidłowy identyfikator żądania udostępniania.");
            if (expectedRevision < 0)
                throw new InvalidOperationException("Nieprawidłowa rewizja zasad dzieci.");
            if (selectedChildProfileIds == null || parentCatalogGameKeys == null)
                throw new InvalidOperationException("Brak pełnego wyboru udostępniania gry.");

            List<string> selected = new List<string>();
            HashSet<string> selectedSet = new HashSet<string>(StringComparer.Ordinal);
            foreach (string value in selectedChildProfileIds)
            {
                string childId = value == null ? "" : value.Trim();
                if (!IsValidProfileId(childId) || !selectedSet.Add(childId))
                    throw new InvalidOperationException("invalid_child_profile_request");
                selected.Add(childId);
            }
            HashSet<string> catalog = new HashSet<string>(StringComparer.Ordinal);
            foreach (string value in parentCatalogGameKeys)
            {
                string normalized = value == null ? "" : value.Trim();
                if (normalized.Length > 0) catalog.Add(normalized);
            }

            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
                Dictionary<string, object> parent = Profile(document, parentProfileId);
                EnsureChildPolicyParent(parent);
                string canonicalKey = NormalizeChildGameKey(parentProfileId, gameKey);
                if (!catalog.Contains(canonicalKey))
                    throw new InvalidOperationException("game_not_in_parent_catalog");
                int currentRevision = ChildPolicyRevision(parent);
                Dictionary<string, object> requests = ChildSharingRequests(parent, true);
                string fingerprint = SharingFingerprint(canonicalKey, expectedRevision, selected);
                object priorValue;
                if (requests.TryGetValue(requestId, out priorValue))
                {
                    Dictionary<string, object> prior = priorValue as Dictionary<string, object>;
                    if (prior == null || !String.Equals(Text(prior, "fingerprint", ""),
                        fingerprint, StringComparison.Ordinal))
                        throw new InvalidOperationException("request_id_reused");
                    return SharingResultFromRecord(parentProfileId, canonicalKey,
                        prior, true);
                }
                if (expectedRevision != currentRevision)
                    throw new InvalidOperationException("child_policy_revision_stale");

                Dictionary<string, Dictionary<string, object>> ownChildren =
                    new Dictionary<string, Dictionary<string, object>>(StringComparer.Ordinal);
                foreach (KeyValuePair<string, object> item in profiles)
                {
                    Dictionary<string, object> child = item.Value as Dictionary<string, object>;
                    if (IsOwnedChild(child, parentProfileId)) ownChildren[item.Key] = child;
                }
                foreach (string childId in selected)
                    if (!ownChildren.ContainsKey(childId))
                        throw new InvalidOperationException(
                            "child_profile_not_owned");

                List<ChildGameInvalidation> invalidations =
                    new List<ChildGameInvalidation>();
                bool changed = false;
                foreach (KeyValuePair<string, Dictionary<string, object>> item in ownChildren)
                {
                    bool shouldGrant = selectedSet.Contains(item.Key);
                    Dictionary<string, object> child = item.Value;
                    bool currentlyGranted = ContainsGameKey(child, canonicalKey);
                    if (currentlyGranted == shouldGrant) continue;
                    changed = true;
                    List<object> keys = ChildGameKeys(child);
                    if (shouldGrant) keys.Add(canonicalKey);
                    else
                    {
                        for (int index = keys.Count - 1; index >= 0; index--)
                            if (String.Equals(Convert.ToString(keys[index]), canonicalKey,
                                StringComparison.Ordinal)) keys.RemoveAt(index);
                    }
                    child["allowed_game_keys"] = keys.ToArray();
                    int childRevision = ChildPolicyRevision(child) + 1;
                    child["policy_revision"] = childRevision;
                    if (!shouldGrant)
                    {
                        invalidations.Add(new ChildGameInvalidation {
                            ChildProfileId = item.Key,
                            GameKey = canonicalKey,
                            PolicyRevision = childRevision
                        });
                    }
                }
                int revision = currentRevision;
                if (changed)
                {
                    revision++;
                    parent[ChildParentPolicyRevisionField] = revision;
                }
                Dictionary<string, object> record = SharingRequestRecord(
                    fingerprint, revision, selected, invalidations);
                requests[requestId] = record;
                TrimChildSharingRequests(requests);
                WriteDocument(document);
                return SharingResultFromRecord(parentProfileId, canonicalKey,
                    record, false);
            }
        }

        internal ProfileRecord CreateChildProfile(string parentProfileId,
            string displayName, string avatarId)
        {
            ValidateDisplayName(displayName);
            if (!IsValidProfileId(parentProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil rodzica.");
            if (avatarId == null) avatarId = "";
            if (avatarId.Length > 128 ||
                System.Text.RegularExpressions.Regex.IsMatch(avatarId, "[\\x00-\\x1f\\x7f]"))
                throw new InvalidOperationException("Nieprawidłowy identyfikator awatara.");
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
                Dictionary<string, object> parent = Profile(document, parentProfileId);
                EnsureStandardProfile(parent);
                if (!BooleanValue(parent, "enabled") || Value(parent, "deletion_tombstone") != null)
                    throw new InvalidOperationException("Rodzic profilu dziecka jest wyłączony.");
                if (Value(parent, "pin_verifier") == null)
                    throw new InvalidOperationException(
                        "Rodzic profilu dziecka musi mieć ustawiony PIN aplikacji.");
                if (!IsSid(Text(parent, "windows_account_sid", "")) ||
                    String.IsNullOrWhiteSpace(Text(parent, "windows_account_name", "")) ||
                    !String.Equals(Text(parent, "account_mapping_status", ""),
                        "resolved", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException(
                        "Rodzic profilu dziecka musi mieć rozpoznane konto Windows.");
                string id;
                do { id = NewProfileId(); } while (profiles.ContainsKey(id));
                Dictionary<string, object> child = new Dictionary<string, object>();
                child["id"] = id;
                child["kind"] = "child";
                child["name"] = displayName.Trim();
                child["display_name"] = displayName.Trim();
                child["avatar_id"] = avatarId;
                child["parent_profile_id"] = parentProfileId;
                child["enabled"] = true;
                child["policy_revision"] = 0;
                child["allowed_game_keys"] = new object[0];
                child["schedule"] = DefaultChildSchedule();
                profiles[id] = child;
                parent[ChildParentPolicyRevisionField] =
                    ChildPolicyRevision(parent) + 1;
                WriteDocument(document);
                return ToProfile(id, child);
            }
        }

        // The Gateway sends this narrow DTO through the Broker.  Keep the
        // registry mutation here so every caller uses the same lock, revision
        // check, and idempotency record.
        internal Dictionary<string, object> ApplyChildProfileRequest(
            Dictionary<string, object> request)
        {
            if (request != null && Text(request, "operation", "").Trim().ToLowerInvariant() == "sharing_set")
                return ApplyChildGameSharingRequest(request);
            Dictionary<string, object> result = null;
            Transaction(null, delegate(Dictionary<string, object> document) {
                result = ApplyChildProfileRequestIn(document, request);
                // A replayed request changes nothing.
                return !Convert.ToBoolean(result["idempotent"]);
            });
            return result;
        }

        internal static Dictionary<string, object> ApplyChildProfileRequestIn(
            Dictionary<string, object> document, Dictionary<string, object> request)
        {
            if (request == null) throw new InvalidOperationException("Brak żądania profilu dziecka.");
            string operation = Text(request, "operation", "").Trim().ToLowerInvariant();
            if (operation != "create" && operation != "update" && operation != "delete")
                throw new InvalidOperationException("Nieznana operacja profilu dziecka.");
            string parentProfileId = Text(request, "parent_profile_id", "").Trim();
            if (!IsValidProfileId(parentProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil rodzica.");
            string requestId = Text(request, "request_id", "").Trim();
            if (!System.Text.RegularExpressions.Regex.IsMatch(requestId, RequestIdPattern))
                throw new InvalidOperationException("Nieprawidłowy identyfikator żądania profilu dziecka.");
            int expectedRevision = RequiredNonNegativeInt(request, "expected_revision");
            string childProfileId = Text(request, "child_profile_id", "").Trim();
            if (operation != "create" && !IsValidProfileId(childProfileId))
                throw new InvalidOperationException("Wybierz prawidłowy profil dziecka.");
            Dictionary<string, object> draft = null;
            if (operation != "delete")
                draft = NormalizeChildDraft(Value(request, "draft") as Dictionary<string, object>);
            bool grantCurrentDevice = false;
            string grantClientId = "";
            if (operation == "create")
            {
                object grantValue = Value(request, "grant_current_device");
                if (grantValue != null && !(grantValue is bool))
                    throw new InvalidOperationException(
                        "Żądanie profilu dziecka ma nieprawidłowy grant urządzenia.");
                grantCurrentDevice = grantValue is bool && (bool)grantValue;
                grantClientId = Text(request, "grant_client_id", "").Trim();
                if (grantCurrentDevice && String.IsNullOrWhiteSpace(grantClientId))
                    throw new InvalidOperationException(
                        "Żądanie profilu dziecka nie wskazuje urządzenia.");
                if (!grantCurrentDevice && grantClientId.Length > 0)
                    throw new InvalidOperationException(
                        "Żądanie profilu dziecka ma nieprawidłowy grant urządzenia.");
            }
            string fingerprint = ChildProfileFingerprint(operation, parentProfileId,
                childProfileId, expectedRevision, draft, grantCurrentDevice, grantClientId);

            Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
            Dictionary<string, object> parent = Profile(document, parentProfileId);
            EnsureChildPolicyParent(parent);
            if (Value(parent, "pin_verifier") == null)
                throw new InvalidOperationException(
                    "Rodzic profilu dziecka musi mieć ustawiony PIN aplikacji.");
            int currentRevision = ChildPolicyRevision(parent);
            Dictionary<string, object> requests = ChildProfileRequests(parent, true);
            object priorValue;
            if (requests.TryGetValue(requestId, out priorValue))
            {
                Dictionary<string, object> prior = priorValue as Dictionary<string, object>;
                if (prior == null || !String.Equals(Text(prior, "fingerprint", ""),
                    fingerprint, StringComparison.Ordinal))
                    throw new InvalidOperationException("request_id_reused");
                Dictionary<string, object> priorResult = Value(prior, "result") as
                    Dictionary<string, object>;
                if (priorResult == null)
                    throw new InvalidOperationException("Rejestr ma nieprawidłowy wynik żądania profilu dziecka.");
                Dictionary<string, object> replay =
                    new Dictionary<string, object>(priorResult);
                replay["idempotent"] = true;
                return replay;
            }
            if (expectedRevision != currentRevision)
                throw new InvalidOperationException("child_policy_revision_stale");

            Dictionary<string, object> result;
            if (operation == "create")
            {
                string id;
                do { id = NewProfileId(); } while (profiles.ContainsKey(id));
                Dictionary<string, object> child = new Dictionary<string, object> {
                    { "id", id }, { "kind", "child" },
                    { "name", draft["name"] }, { "display_name", draft["name"] },
                    { "avatar_id", draft["avatar_id"] },
                    { "parent_profile_id", parentProfileId },
                    { "enabled", draft["enabled"] }, { "policy_revision", 0 },
                    { "allowed_game_keys", new object[0] },
                    { "schedule", draft["schedule"] }
                };
                profiles[id] = child;
                if (grantCurrentDevice)
                    GrantChildUseProfile(document, grantClientId, id);
                int revision = currentRevision + 1;
                parent[ChildParentPolicyRevisionField] = revision;
                result = ChildProfileMutationResult(parentProfileId, revision,
                    ChildProfileDto(child), false, false);
            }
            else
            {
                object childValue;
                Dictionary<string, object> child;
                if (!profiles.TryGetValue(childProfileId, out childValue) ||
                    (child = childValue as Dictionary<string, object>) == null ||
                    !IsOwnedChild(child, parentProfileId))
                    throw new InvalidOperationException("child_profile_not_owned");
                if (operation == "delete")
                {
                    profiles.Remove(childProfileId);
                    RemoveProfileGrants(document, childProfileId);
                    int revision = currentRevision + 1;
                    parent[ChildParentPolicyRevisionField] = revision;
                    result = ChildProfileMutationResult(parentProfileId, revision,
                        null, false, true);
                    result["child_profile_id"] = childProfileId;
                    result["affected_child_profile_ids"] = new object[] { childProfileId };
                }
                else
                {
                    bool scheduleChanged = !String.Equals(ScheduleFingerprint(
                            StoredChildSchedule(child)),
                            ScheduleFingerprint((Dictionary<string, object>)draft["schedule"]),
                            StringComparison.Ordinal);
                    bool enabledChanged = !BooleanEquals(child, "enabled",
                        (bool)draft["enabled"]);
                    bool changed = !String.Equals(Text(child, "name",
                            Text(child, "display_name", "")),
                            Convert.ToString(draft["name"]), StringComparison.Ordinal) ||
                        !String.Equals(Text(child, "display_name", ""),
                            Convert.ToString(draft["name"]), StringComparison.Ordinal) ||
                        !String.Equals(Text(child, "avatar_id", ""),
                            Convert.ToString(draft["avatar_id"]), StringComparison.Ordinal) ||
                        enabledChanged || scheduleChanged;
                    bool policyChanged = enabledChanged ||
                        ChildScheduleShrank(StoredChildSchedule(child),
                            (Dictionary<string, object>)draft["schedule"]);
                    if (changed)
                    {
                        child["name"] = draft["name"];
                        child["display_name"] = draft["name"];
                        child["avatar_id"] = draft["avatar_id"];
                        child["enabled"] = draft["enabled"];
                        child["schedule"] = draft["schedule"];
                        child["policy_revision"] = ChildPolicyRevision(child) + 1;
                        currentRevision++;
                        parent[ChildParentPolicyRevisionField] = currentRevision;
                    }
                    result = ChildProfileMutationResult(parentProfileId, currentRevision,
                        ChildProfileDto(child), false, policyChanged);
                    if (policyChanged)
                        result["affected_child_profile_ids"] = new object[] { childProfileId };
                }
            }
            requests[requestId] = new Dictionary<string, object> {
                { "fingerprint", fingerprint }, { "result", result }
            };
            TrimChildProfileRequests(requests);
            return result;
        }

        private Dictionary<string, object> ApplyChildGameSharingRequest(
            Dictionary<string, object> request)
        {
            string parentProfileId = Text(request, "parent_profile_id", "").Trim();
            string gameKey = Text(request, "game_key", "").Trim();
            string requestId = Text(request, "request_id", "").Trim();
            int expectedRevision = RequiredNonNegativeInt(request, "expected_revision");
            List<string> selected = RequiredStringList(request, "selected_child_ids");
            List<string> catalog = RequiredStringList(request, "parent_catalog_game_keys");
            ChildGameSharingResult result = UpdateChildGameSharing(
                parentProfileId, gameKey, selected, expectedRevision, requestId, catalog);
            return ChildGameSharingMutationResult(result);
        }

        private static List<string> RequiredStringList(
            Dictionary<string, object> source, string key)
        {
            IEnumerable values = Value(source, key) as IEnumerable;
            if (values == null || values is string)
                throw new InvalidOperationException("Żądanie zawiera niepełną listę.");
            List<string> result = new List<string>();
            foreach (object value in values)
            {
                string text = value as string;
                if (text == null) throw new InvalidOperationException(
                    "Żądanie zawiera nieprawidłową listę.");
                result.Add(text);
            }
            return result;
        }

        private static Dictionary<string, object> ChildGameSharingMutationResult(
            ChildGameSharingResult value)
        {
            List<object> selected = new List<object>();
            foreach (string childId in value.ChildProfileIds) selected.Add(childId);
            List<object> invalidations = new List<object>();
            List<object> affected = new List<object>();
            HashSet<string> affectedIds = new HashSet<string>(StringComparer.Ordinal);
            foreach (ChildGameInvalidation invalidation in value.Invalidations)
            {
                invalidations.Add(new Dictionary<string, object> {
                    { "child_profile_id", invalidation.ChildProfileId },
                    { "game_key", invalidation.GameKey },
                    { "policy_revision", invalidation.PolicyRevision }
                });
                if (affectedIds.Add(invalidation.ChildProfileId))
                    affected.Add(invalidation.ChildProfileId);
            }
            Dictionary<string, object> result = new Dictionary<string, object> {
                { "ok", true }, { "parent_profile_id", value.ParentProfileId },
                { "game_key", value.GameKey }, { "revision", value.Revision },
                { "selected_child_ids", selected.ToArray() },
                { "invalidations", invalidations.ToArray() },
                { "idempotent", value.Idempotent },
                { "cleanup_required", affected.Count > 0 }
            };
            if (affected.Count > 0)
                result["affected_child_profile_ids"] = affected.ToArray();
            return result;
        }

        private static void GrantChildUseProfile(Dictionary<string, object> document,
            string clientId, string childProfileId)
        {
            if (String.IsNullOrWhiteSpace(clientId) || clientId.Length > 128 ||
                System.Text.RegularExpressions.Regex.IsMatch(clientId, "[\\x00-\\x1f\\x7f]"))
                throw new InvalidOperationException("Żądanie wskazuje nieprawidłowe urządzenie.");
            foreach (Dictionary<string, object> client in Clients(document))
            {
                if (!String.Equals(Text(client, "id", ""), clientId,
                    StringComparison.Ordinal)) continue;
                Dictionary<string, object> grants = Dictionary(client, "profile_grants", true);
                grants[childProfileId] = new object[] { "use_profile" };
                return;
            }
            throw new InvalidOperationException("Wskazane urządzenie nie jest sparowane.");
        }

        internal ProfileRecord ReserveProfile(LocalAccount account, string displayName)
        {
            ValidateDisplayName(displayName);
            if (account == null || !IsSid(account.Sid) || String.IsNullOrWhiteSpace(account.QualifiedName))
                throw new InvalidOperationException("Wybierz obsługiwane lokalne konto Windows.");
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
                EnsureSidUnused(profiles, account.Sid, null);
                HashSet<int> used = UsedPorts(profiles);
                int[] ports = AllocatePorts(used);
                string id;
                do { id = NewProfileId(); } while (profiles.ContainsKey(id));
                string root = Path.GetFullPath(Path.Combine(ProfilesRoot, id));
                Dictionary<string, object> profile = new Dictionary<string, object>();
                profile["id"] = id;
                profile["name"] = displayName.Trim();
                profile["display_name"] = displayName.Trim();
                profile["windows_account_sid"] = account.Sid;
                profile["windows_account_name"] = account.QualifiedName;
                profile["enabled"] = false;
                profile["profile_root"] = root;
                profile["discord_bridge"] = "http://127.0.0.1:" + ports[0];
                profile["game_provider_bridge"] = "http://127.0.0.1:" + ports[1];
                profile["remote_sign_in_enabled"] = false;
                profile["account_mapping_status"] = "resolved";
                profile["reservation_nonce"] = Guid.NewGuid().ToString("N");
                profiles[id] = profile;
                WriteDocument(document);
                return ToProfile(id, profile);
            }
        }

        internal void FinalizeProfile(string id, string expectedSid)
        {
            Transaction(null, delegate(Dictionary<string, object> document) {
                MutateProfileIn(document, id, expectedSid, delegate(Dictionary<string, object> profile) {
                    profile["enabled"] = true;
                });
                return true;
            });
        }

        internal void ProvisionAndFinalize(ProfileRecord expected, Action provision)
        {
            if (expected == null || provision == null) throw new ArgumentNullException();
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profile = Profile(document, expected.Id);
                EnsureStandardProfile(profile);
                EnsureProvisioningReservation(profile, expected);
                provision();
                document = ReadDocument();
                profile = Profile(document, expected.Id);
                EnsureStandardProfile(profile);
                EnsureProvisioningReservation(profile, expected);
                profile["enabled"] = true;
                WriteDocument(document);
            }
        }

        internal void UpdateProfile(string id, string expectedSid, string displayName, bool enabled)
        {
            Transaction(null, delegate(Dictionary<string, object> document) {
                EditProfileIn(document, id, expectedSid, displayName, enabled);
                return true;
            });
        }

        internal static void EditProfileIn(Dictionary<string, object> document, string id,
            string expectedSid, string displayName, bool enabled)
        {
            ValidateDisplayName(displayName);
            MutateProfileIn(document, id, expectedSid, delegate(Dictionary<string, object> profile) {
                profile["name"] = displayName.Trim();
                profile["display_name"] = displayName.Trim();
                profile["enabled"] = enabled;
            });
        }

        internal void SetAppPin(string id, string expectedSid, string pin)
        {
            Dictionary<string, object> verifier = CreateAppPinVerifier(pin);
            Transaction(null, delegate(Dictionary<string, object> document) {
                SetPinVerifierIn(document, id, expectedSid, verifier);
                return true;
            });
        }

        internal static void SetPinVerifierIn(Dictionary<string, object> document, string id,
            string expectedSid, Dictionary<string, object> verifier)
        {
            MutateProfileIn(document, id, expectedSid, delegate(Dictionary<string, object> profile) {
                profile["pin_verifier"] = verifier;
            });
        }

        internal void RemoveAppPin(string id, string expectedSid)
        {
            Transaction(null, delegate(Dictionary<string, object> document) {
                RemovePinIn(document, id, expectedSid);
                return true;
            });
        }

        internal static void RemovePinIn(Dictionary<string, object> document, string id, string expectedSid)
        {
            MutateProfileIn(document, id, expectedSid, delegate(Dictionary<string, object> profile) {
                profile.Remove("pin_verifier");
            });
        }

        internal static Dictionary<string, object> CreateAppPinVerifier(string pin)
        {
            if (String.IsNullOrEmpty(pin) ||
                !System.Text.RegularExpressions.Regex.IsMatch(pin, "^[0-9]{4}$"))
                throw new InvalidOperationException("PIN aplikacji MoonWaker musi mieć dokładnie cztery cyfry.");
            byte[] salt = new byte[16];
            byte[] digest = null;
            using (RandomNumberGenerator random = RandomNumberGenerator.Create())
                random.GetBytes(salt);
            try
            {
                using (Rfc2898DeriveBytes derive = new Rfc2898DeriveBytes(
                    pin, salt, 120000, HashAlgorithmName.SHA256))
                    digest = derive.GetBytes(32);
                return new Dictionary<string, object> {
                    { "version", 1 },
                    { "algorithm", "pbkdf2-sha256" },
                    { "iterations", 120000 },
                    { "salt", Convert.ToBase64String(salt) },
                    { "digest", Convert.ToBase64String(digest) }
                };
            }
            finally
            {
                Array.Clear(salt, 0, salt.Length);
                if (digest != null) Array.Clear(digest, 0, digest.Length);
            }
        }

        internal void SetRemoteSignInEnabled(string id, string expectedSid, bool enabled)
        {
            Transaction(null, delegate(Dictionary<string, object> document) {
                MutateProfileIn(document, id, expectedSid, delegate(Dictionary<string, object> profile) {
                    profile["remote_sign_in_enabled"] = enabled;
                    if (!enabled) RemoveRemoteSignInGrants(document, id);
                });
                return true;
            });
        }

        internal void UpdateGrants(string id, string expectedSid,
            Dictionary<string, DeviceGrant> requested)
        {
            Transaction(null, delegate(Dictionary<string, object> document) {
                UpdateGrantsIn(document, id, expectedSid, requested);
                return true;
            });
        }

        // Clients are those of the document: the helper's Prepare refuses a TV that is not paired.
        internal static void UpdateGrantsIn(Dictionary<string, object> document, string id,
            string expectedSid, Dictionary<string, DeviceGrant> requested)
        {
            Dictionary<string, object> profile = Profile(document, id);
            bool child = IsChildProfile(profile);
            Dictionary<string, object> policyParent = profile;
            if (child)
            {
                policyParent = EnsureChildGrantParent(document, profile, true);
                EnsureNotTombstoned(profile);
                EnsureSid(policyParent, expectedSid);
            }
            else
            {
                EnsureStandardProfile(profile);
                EnsureSid(profile, expectedSid);
                EnsureNotTombstoned(profile);
            }
            bool remoteEnabled = BooleanValue(policyParent, "remote_sign_in_enabled");
            foreach (Dictionary<string, object> client in Clients(document))
            {
                string clientId = Text(client, "id", "");
                DeviceGrant grant;
                if (String.IsNullOrWhiteSpace(clientId) || !requested.TryGetValue(clientId, out grant))
                    continue;
                if (grant.RemoteSignIn && (!grant.UseProfile || !remoteEnabled))
                    throw new InvalidOperationException(
                        "Zdalny dostęp wymaga zwykłego dostępu i gotowych danych logowania profilu.");
                if (grant.ManageChildren && !grant.UseProfile)
                    throw new InvalidOperationException(
                        "Zarządzanie dziećmi wymaga zwykłego dostępu do profilu rodzica.");
                if (child && grant.ManageChildren)
                    throw new InvalidOperationException(
                        "Profil dziecka nie może zarządzać profilami dzieci.");
                if (!child && grant.ManageChildren &&
                    Value(profile, "pin_verifier") == null)
                    throw new InvalidOperationException(
                        "Zarządzanie dziećmi wymaga ustawionego PIN-u aplikacji rodzica.");
                Dictionary<string, object> grants = Dictionary(client, "profile_grants", true);
                if (!grant.UseProfile) grants.Remove(id);
                else
                {
                    List<object> permissions = new List<object> { "use_profile" };
                    if (grant.RemoteSignIn) permissions.Add("remote_sign_in");
                    if (grant.ManageChildren) permissions.Add("manage_children");
                    grants[id] = permissions.ToArray();
                }
            }
        }

        internal List<DeviceRecord> GetDevices(string profileId)
        {
            Dictionary<string, object> document = ReadDocument();
            Dictionary<string, object> selected = Profile(document, profileId);
            bool child = IsChildProfile(selected);
            if (child) EnsureChildGrantParent(document, selected, false);
            else EnsureStandardProfile(selected);
            Dictionary<string, long> activity = ReadActivity();
            List<DeviceRecord> result = new List<DeviceRecord>();
            foreach (Dictionary<string, object> client in Clients(document))
            {
                string id = Text(client, "id", "");
                if (String.IsNullOrWhiteSpace(id)) continue;
                long paired = LongValue(client, "paired_at");
                long seen = Math.Max(LongValue(client, "last_seen_at"), paired);
                long separate;
                if (activity.TryGetValue(id, out separate)) seen = Math.Max(seen, separate);
                HashSet<string> permissions = Permissions(client, profileId);
                result.Add(new DeviceRecord {
                    Id = id,
                    SafeId = SafeClientId(id),
                    Name = Text(client, "name", "Urządzenie"),
                    PairedAt = paired,
                    LastSeenAt = seen,
                    UseProfile = permissions.Contains("use_profile"),
                    RemoteSignIn = permissions.Contains("remote_sign_in"),
                    ManageChildren = !child && permissions.Contains("manage_children")
                });
            }
            result.Sort(delegate(DeviceRecord left, DeviceRecord right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.Name, right.Name);
            });
            return result;
        }

        internal void RemoveClient(string clientId)
        {
            Transaction(null, delegate(Dictionary<string, object> document) {
                RemoveClientIn(document, clientId);
                return true;
            });
        }

        internal static void RemoveClientIn(Dictionary<string, object> document, string clientId)
        {
            if (String.IsNullOrWhiteSpace(clientId))
                throw new InvalidOperationException("Wybierz prawidłowe urządzenie.");
            IList clients = Value(document, "clients") as IList;
            bool removed = false;
            for (int index = clients.Count - 1; index >= 0; index--)
            {
                Dictionary<string, object> client = clients[index]
                    as Dictionary<string, object>;
                if (client != null && String.Equals(Text(client, "id", ""),
                    clientId, StringComparison.Ordinal))
                {
                    clients.RemoveAt(index);
                    removed = true;
                }
            }
            if (!removed) throw new InvalidOperationException(
                "Urządzenie nie jest już sparowane. Odśwież listę.");
        }

        internal string PrepareRemoval(ProfileRecord expected)
        {
            if (expected == null) throw new ArgumentNullException("expected");
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profile = Profile(document, expected.Id);
                EnsureStandardProfile(profile);
                EnsureRemovalIdentity(profile, expected);
                Dictionary<string, object> tombstone = Value(profile,
                    "deletion_tombstone") as Dictionary<string, object>;
                string nonce = tombstone == null ? "" : Text(tombstone, "nonce", "");
                long generation = tombstone == null ? 0 : LongValue(tombstone, "generation");
                if (String.IsNullOrWhiteSpace(nonce))
                {
                    if (!String.IsNullOrWhiteSpace(expected.DeletionNonce))
                        throw new InvalidOperationException(
                            "Znacznik usuwania profilu zmienił się. Odśwież Host Control.");
                    nonce = Guid.NewGuid().ToString("N");
                    generation = Math.Max(1, expected.DeletionGeneration + 1);
                    tombstone = new Dictionary<string, object>();
                    tombstone["nonce"] = nonce;
                    tombstone["generation"] = generation;
                    tombstone["created_at"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                    tombstone["profile_id"] = expected.Id;
                    tombstone["windows_account_sid"] = expected.Sid ?? "";
                    tombstone["windows_account_name"] = expected.AccountName ?? "";
                    tombstone["profile_root"] = expected.Root ?? "";
                    profile["deletion_tombstone"] = tombstone;
                }
                else if (!String.Equals(nonce, expected.DeletionNonce,
                    StringComparison.Ordinal) || generation != expected.DeletionGeneration)
                    throw new InvalidOperationException(
                        "Profil ma inny znacznik usuwania. Odśwież Host Control.");
                EnsureTombstoneSnapshot(tombstone, expected);
                profile["enabled"] = false;
                profile["remote_sign_in_enabled"] = false;
                RemoveProfileGrants(document, expected.Id);
                WriteDocument(document);
                expected.DeletionNonce = nonce;
                expected.DeletionGeneration = generation;
                return nonce;
            }
        }

        internal void RemoveProfile(ProfileRecord expected)
        {
            if (expected == null) throw new ArgumentNullException("expected");
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
                Dictionary<string, object> profile = Profile(document, expected.Id);
                EnsureStandardProfile(profile);
                EnsureRemovalIdentity(profile, expected);
                EnsureDeletionTombstone(profile, expected);
                string expectedRoot = Path.GetFullPath(Path.Combine(ProfilesRoot, expected.Id));
                if (!PathSafety.IsConclusiveAbsent(expectedRoot))
                    throw new InvalidOperationException(
                        "Katalog profilu nadal istnieje. Zamknij programy profilu i ponów usunięcie.");
                profiles.Remove(expected.Id);
                RemoveProfileGrants(document, expected.Id);
                WriteDocument(document);
            }
        }

        // A standard profile of the expected account that is not being removed.
        internal static void MutateProfileIn(Dictionary<string, object> document, string id,
            string expectedSid, Action<Dictionary<string, object>> mutation)
        {
            Dictionary<string, object> profile = Profile(document, id);
            EnsureStandardProfile(profile);
            EnsureSid(profile, expectedSid);
            EnsureNotTombstoned(profile);
            mutation(profile);
        }

        // One locked read-modify-write of the registry. expectedRevision, unless null, is the
        // revision the change was decided on (RegistryRevision); change returns whether to write.
        internal void Transaction(string expectedRevision, Func<Dictionary<string, object>, bool> change)
        {
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument(expectedRevision);
                if (change(document)) WriteDocument(document);
            }
        }

        // The registry read under its lock, still at expectedRevision; nothing is written.
        internal Dictionary<string, object> Snapshot(string expectedRevision)
        {
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
                return ReadDocument(expectedRevision);
        }

        private Dictionary<string, object> ReadDocument()
        {
            return ReadDocument(null);
        }

        private Dictionary<string, object> ReadDocument(string expectedRevision)
        {
            Dictionary<string, object> document;
            byte[] content;
            try { content = File.ReadAllBytes(ConfigPath); }
            catch (Exception ex)
            {
                throw new InvalidOperationException("Rejestr profili Gateway jest uszkodzony.", ex);
            }
            if (expectedRevision != null && RegistryRevision.Of(content) != expectedRevision)
                throw new StaleRegistryException();
            try
            {
                document = json.Deserialize<Dictionary<string, object>>(
                    Encoding.UTF8.GetString(content).TrimStart('﻿'));
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException("Rejestr profili Gateway jest uszkodzony.", ex);
            }
            object clients = document == null ? null : Value(document, "clients");
            if (document != null && IntValue(document, "schema_version") == 2)
                UpgradeSchema2(document);
            if (document == null || IntValue(document, "schema_version") != SchemaVersion ||
                !(Value(document, "profiles") is Dictionary<string, object>) ||
                !(clients is IEnumerable) || clients is string ||
                clients is Dictionary<string, object>)
                throw new InvalidOperationException(
                    "Gateway musi najpierw uruchomić migrację rejestru do schematu 3.");
            ValidateProfileRegistry(Dictionary(document, "profiles", false));
            return document;
        }

        private static void UpgradeSchema2(Dictionary<string, object> document)
        {
            Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
            foreach (KeyValuePair<string, object> item in profiles)
            {
                Dictionary<string, object> profile = item.Value as Dictionary<string, object>;
                if (profile == null) throw new InvalidOperationException(
                    "Rejestr Gateway ma nieprawidłową strukturę profili.");
                if (!profile.ContainsKey("id")) profile["id"] = item.Key;
                if (!profile.ContainsKey("kind")) profile["kind"] = "standard";
                if (String.Equals(Text(profile, "kind", "standard"), "child",
                        StringComparison.OrdinalIgnoreCase) &&
                    !profile.ContainsKey("schedule"))
                    profile["schedule"] = DefaultChildSchedule();
            }
            // 2 -> 3 is a representation upgrade only.  It never adds a
            // profile, resolves a SID, or changes grants.
            document["schema_version"] = SchemaVersion;
        }

        private static Dictionary<string, object> DefaultChildSchedule()
        {
            Dictionary<string, object> weekdays = new Dictionary<string, object>();
            foreach (string day in ChildWeekdays)
                weekdays[day] = new Dictionary<string, object> {
                    { "enabled", false }, { "start_minute", 0 },
                    { "end_minute", 1440 }, { "daily_limit_seconds", 0 }
                };
            return new Dictionary<string, object> {
                { "weekdays", weekdays }
            };
        }

        private static void ValidateProfileRegistry(Dictionary<string, object> profiles)
        {
            foreach (KeyValuePair<string, object> item in profiles)
            {
                Dictionary<string, object> profile = item.Value as Dictionary<string, object>;
                if (profile == null || !IsValidProfileId(item.Key) ||
                    !String.Equals(Text(profile, "id", item.Key), item.Key,
                        StringComparison.Ordinal))
                    throw new InvalidOperationException("Rejestr Gateway ma nieprawidłową tożsamość profilu.");
                string kind = Text(profile, "kind", "standard");
                if (String.Equals(kind, "standard", StringComparison.OrdinalIgnoreCase))
                {
                    if (!String.IsNullOrWhiteSpace(Text(profile, "parent_profile_id", "")))
                        throw new InvalidOperationException("Standardowy profil nie może mieć rodzica.");
                    if (profile.ContainsKey(ChildParentPolicyRevisionField) &&
                        !IsNonNegativeInt(Value(profile, ChildParentPolicyRevisionField)))
                        throw new InvalidOperationException(
                            "Rejestr ma nieprawidłową rewizję zasad dzieci.");
                    if (profile.ContainsKey(ChildSharingRequestsField) &&
                        !(Value(profile, ChildSharingRequestsField) is Dictionary<string, object>))
                        throw new InvalidOperationException(
                            "Rejestr ma nieprawidłowe żądania udostępniania.");
                    if (profile.ContainsKey(ChildProfileRequestsField) &&
                        !(Value(profile, ChildProfileRequestsField) is Dictionary<string, object>))
                        throw new InvalidOperationException(
                            "Rejestr ma nieprawidłowe żądania profili dzieci.");
                    continue;
                }
                if (!String.Equals(kind, "child", StringComparison.OrdinalIgnoreCase) ||
                    !(Value(profile, "id") is string) ||
                    !String.Equals(Text(profile, "id", ""), item.Key,
                        StringComparison.Ordinal) ||
                    !IsValidProfileId(Text(profile, "parent_profile_id", "")) ||
                    !(Value(profile, "enabled") is bool) ||
                    !(Value(profile, "allowed_game_keys") is IEnumerable) ||
                    Value(profile, "allowed_game_keys") is string)
                    throw new InvalidOperationException("Rejestr Gateway ma nieprawidłowy profil dziecka.");
                int policyRevision = IntValue(profile, "policy_revision");
                if (policyRevision < 0)
                    throw new InvalidOperationException("Profil dziecka ma nieprawidłową wersję zasad.");
                if (profile.ContainsKey("schedule") &&
                    !(Value(profile, "schedule") is Dictionary<string, object>))
                    throw new InvalidOperationException("Profil dziecka ma nieprawidłowy harmonogram.");
                StoredChildSchedule(profile);
                string parentProfileId = Text(profile, "parent_profile_id", "");
                foreach (object gameKey in (IEnumerable)Value(profile, "allowed_game_keys"))
                    if (!(gameKey is string) ||
                        !System.Text.RegularExpressions.Regex.IsMatch((string)gameKey,
                            ChildGameKeyPattern) ||
                        !((string)gameKey).StartsWith(parentProfileId + "/",
                            StringComparison.Ordinal))
                        throw new InvalidOperationException("Profil dziecka ma nieprawidłową listę gier.");
                if (profile.ContainsKey("ratings"))
                    throw new InvalidOperationException("Profil dziecka nie może zawierać ocen gier.");
                Dictionary<string, object> parent;
                object parentValue;
                if (profiles.TryGetValue(parentProfileId, out parentValue))
                {
                    parent = parentValue as Dictionary<string, object>;
                    if (parent == null ||
                        !String.Equals(Text(parent, "kind", "standard"), "standard",
                            StringComparison.OrdinalIgnoreCase))
                        throw new InvalidOperationException("Profil dziecka wskazuje profil bez konta Windows.");
                }
                foreach (string forbidden in new string[] {
                    "windows_account_sid", "windows_account_name",
                    "profile_root", "discord_bridge",
                    "game_provider_bridge", "integration_token",
                    "pin_verifier", "account_mapping_status", "reservation_nonce",
                    "remote_sign_in_enabled",
                    "ratings", "minimum_age", "age_rating", "pegi", "esrb" })
                    if (profile.ContainsKey(forbidden))
                        throw new InvalidOperationException("Profil dziecka zawiera dane wykonywania.");
            }
        }

        private void WriteDocument(Dictionary<string, object> document)
        {
            string temporary = ConfigPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
            byte[] bytes = new UTF8Encoding(false).GetBytes(json.Serialize(document) + Environment.NewLine);
            try
            {
                using (FileStream stream = new FileStream(temporary, FileMode.CreateNew,
                    FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
                {
                    stream.Write(bytes, 0, bytes.Length);
                    stream.Flush(true);
                }
                ReplaceRegistryFile(temporary, ConfigPath);
            }
            finally
            {
                Array.Clear(bytes, 0, bytes.Length);
                try { if (File.Exists(temporary)) File.Delete(temporary); } catch { }
            }
        }

        // Antivirus or indexing briefly opens the registry without delete sharing, and File.Replace
        // then fails with a sharing violation (lab: setting the parent app PIN). The registry lock
        // is held by the caller, so waiting a bounded time cannot interleave another writer.
        internal static void ReplaceRegistryFile(string source, string destination)
        {
            const int sharingViolation = unchecked((int)0x80070020);
            for (int attempt = 1; ; attempt++)
            {
                try
                {
                    File.Replace(source, destination, null);
                    return;
                }
                catch (IOException error)
                {
                    if (Marshal.GetHRForException(error) != sharingViolation || attempt >= 30) throw;
                }
                Thread.Sleep(100);
            }
        }

        private Dictionary<string, long> ReadActivity()
        {
            Dictionary<string, long> result = new Dictionary<string, long>(StringComparer.Ordinal);
            string path = Path.Combine(GatewayDirectory, "client-activity.json");
            try
            {
                Dictionary<string, object> document;
                document = json.Deserialize<Dictionary<string, object>>(
                    File.ReadAllText(path, Encoding.UTF8));
                Dictionary<string, object> clients = Dictionary(document, "clients", false);
                foreach (KeyValuePair<string, object> item in clients)
                {
                    long seen;
                    if (Int64.TryParse(Convert.ToString(item.Value), out seen)) result[item.Key] = seen;
                }
            }
            catch { }
            return result;
        }

        internal static Dictionary<string, object> Profile(Dictionary<string, object> document, string id)
        {
            if (!IsValidProfileId(id)) throw new InvalidOperationException("Nieprawidłowy profil.");
            Dictionary<string, object> profiles = Dictionary(document, "profiles", false);
            object value;
            Dictionary<string, object> profile;
            if (!profiles.TryGetValue(id, out value) ||
                (profile = value as Dictionary<string, object>) == null)
                throw new InvalidOperationException("Profil nie istnieje lub został usunięty.");
            return profile;
        }

        private static bool IsOwnedChild(Dictionary<string, object> profile,
            string parentProfileId)
        {
            return profile != null &&
                String.Equals(Text(profile, "kind", "standard"), "child",
                    StringComparison.OrdinalIgnoreCase) &&
                String.Equals(Text(profile, "parent_profile_id", ""),
                    parentProfileId, StringComparison.Ordinal);
        }

        internal static bool IsChildProfile(Dictionary<string, object> profile)
        {
            return profile != null && String.Equals(Text(profile, "kind", "standard"),
                "child", StringComparison.OrdinalIgnoreCase);
        }

        private static Dictionary<string, object> EnsureChildGrantParent(
            Dictionary<string, object> document, Dictionary<string, object> child,
            bool requireActive)
        {
            string parentProfileId = Text(child, "parent_profile_id", "").Trim();
            if (!IsValidProfileId(parentProfileId))
                throw new InvalidOperationException(
                    "Profil dziecka nie ma prawidłowego rodzica.");
            Dictionary<string, object> parent = Profile(document, parentProfileId);
            EnsureStandardProfile(parent);
            if (requireActive) EnsureChildPolicyParent(parent);
            return parent;
        }

        private static void EnsureChildPolicyParent(Dictionary<string, object> parent)
        {
            EnsureStandardProfile(parent);
            if (!BooleanValue(parent, "enabled") ||
                Value(parent, "deletion_tombstone") != null)
                throw new InvalidOperationException("Rodzic profilu dziecka jest wyłączony.");
            if (!IsSid(Text(parent, "windows_account_sid", "")) ||
                String.IsNullOrWhiteSpace(Text(parent, "windows_account_name", "")) ||
                !String.Equals(Text(parent, "account_mapping_status", ""),
                    "resolved", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "Rodzic profilu dziecka musi mieć rozpoznane konto Windows.");
        }

        internal static int ChildPolicyRevision(Dictionary<string, object> profile)
        {
            object value = Value(profile, ChildParentPolicyRevisionField);
            if (value == null && String.Equals(Text(profile, "kind", "standard"),
                    "child", StringComparison.OrdinalIgnoreCase))
                value = Value(profile, "policy_revision");
            if (value == null) return 0;
            int revision;
            if (!Int32.TryParse(Convert.ToString(value), out revision) || revision < 0)
                throw new InvalidOperationException("Rejestr ma nieprawidłową rewizję zasad dzieci.");
            return revision;
        }

        private static string NormalizeChildGameKey(string parentProfileId, string gameKey)
        {
            string normalized = gameKey == null ? "" : gameKey.Trim();
            if (!System.Text.RegularExpressions.Regex.IsMatch(normalized, ChildGameKeyPattern) ||
                !normalized.StartsWith(parentProfileId + "/", StringComparison.Ordinal))
                throw new InvalidOperationException("invalid_child_game_key");
            string canonical = normalized.Substring(parentProfileId.Length + 1);
            if (!System.Text.RegularExpressions.Regex.IsMatch(canonical, GameRecordIdPattern))
                throw new InvalidOperationException("invalid_child_game_key");
            return normalized;
        }

        private static List<object> ChildGameKeys(Dictionary<string, object> child)
        {
            List<object> result = new List<object>();
            IEnumerable values = Value(child, "allowed_game_keys") as IEnumerable;
            if (values == null || values is string)
                throw new InvalidOperationException("Profil dziecka ma nieprawidłową listę gier.");
            HashSet<string> seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (object value in values)
            {
                string text = value as string;
                if (text != null && seen.Add(text)) result.Add(text);
            }
            return result;
        }

        private static bool ContainsGameKey(Dictionary<string, object> child,
            string gameKey)
        {
            foreach (object value in ChildGameKeys(child))
                if (String.Equals(Convert.ToString(value), gameKey,
                    StringComparison.Ordinal)) return true;
            return false;
        }

        private static Dictionary<string, object> ChildSharingRequests(
            Dictionary<string, object> parent, bool create)
        {
            object value = Value(parent, ChildSharingRequestsField);
            Dictionary<string, object> result = value as Dictionary<string, object>;
            if (result == null && create)
            {
                result = new Dictionary<string, object>(StringComparer.Ordinal);
                parent[ChildSharingRequestsField] = result;
            }
            if (result == null)
                throw new InvalidOperationException("Rejestr ma nieprawidłowe żądania udostępniania.");
            return result;
        }

        private static Dictionary<string, object> ChildProfileRequests(
            Dictionary<string, object> parent, bool create)
        {
            object value = Value(parent, ChildProfileRequestsField);
            Dictionary<string, object> result = value as Dictionary<string, object>;
            if (result == null && create)
            {
                result = new Dictionary<string, object>(StringComparer.Ordinal);
                parent[ChildProfileRequestsField] = result;
            }
            if (result == null)
                throw new InvalidOperationException(
                    "Rejestr ma nieprawidłowe żądania profili dzieci.");
            return result;
        }

        private static void TrimChildProfileRequests(Dictionary<string, object> requests)
        {
            while (requests.Count > MaxChildProfileRequests)
            {
                string oldest = null;
                foreach (string key in requests.Keys) { oldest = key; break; }
                if (oldest == null) break;
                requests.Remove(oldest);
            }
        }

        private static int RequiredNonNegativeInt(Dictionary<string, object> source,
            string key)
        {
            object value = Value(source, key);
            if (value is bool || value == null)
                throw new InvalidOperationException("Rejestr ma nieprawidłową wartość " + key + ".");
            int result;
            if (value is int) result = (int)value;
            else if (value is long && (long)value <= Int32.MaxValue) result = (int)(long)value;
            else if (!Int32.TryParse(Convert.ToString(value), out result))
                throw new InvalidOperationException("Rejestr ma nieprawidłową wartość " + key + ".");
            if (result < 0)
                throw new InvalidOperationException("Rejestr ma nieprawidłową wartość " + key + ".");
            return result;
        }

        private static bool BooleanEquals(Dictionary<string, object> source,
            string key, bool expected)
        {
            object value = Value(source, key);
            return value is bool && (bool)value == expected;
        }

        internal static Dictionary<string, object> NormalizeChildDraft(
            Dictionary<string, object> source)
        {
            if (source == null)
                throw new InvalidOperationException("Brak danych profilu dziecka.");
            string name = Text(source, "name", "").Trim();
            ValidateDisplayName(name);
            string avatar = Text(source, "avatar_id", "").Trim();
            if (avatar.Length > 128 ||
                System.Text.RegularExpressions.Regex.IsMatch(avatar, "[\\x00-\\x1f\\x7f]"))
                throw new InvalidOperationException("Nieprawidłowy identyfikator awatara.");
            object enabledValue = Value(source, "enabled");
            if (!(enabledValue is bool))
                throw new InvalidOperationException("Profil dziecka ma nieprawidłowy stan aktywności.");
            object allowedValue = Value(source, "allowed_game_keys");
            if (allowedValue != null)
            {
                IEnumerable allowed = allowedValue as IEnumerable;
                if (allowed == null || allowedValue is string)
                    throw new InvalidOperationException("Granty gier dzieci są zarządzane osobno.");
                foreach (object value in allowed)
                    throw new InvalidOperationException("Granty gier dzieci są zarządzane osobno.");
            }
            Dictionary<string, object> schedule = NormalizeChildSchedule(
                Value(source, "schedule") as Dictionary<string, object>);
            return new Dictionary<string, object> {
                { "name", name }, { "avatar_id", avatar },
                { "enabled", (bool)enabledValue }, { "schedule", schedule }
            };
        }

        private static Dictionary<string, object> NormalizeChildSchedule(
            Dictionary<string, object> source)
        {
            if (source == null)
                throw new InvalidOperationException("Profil dziecka ma nieprawidłowy harmonogram.");
            Dictionary<string, object> weekdays = Value(source, "weekdays") as
                Dictionary<string, object>;
            if (weekdays == null)
                throw new InvalidOperationException("Profil dziecka ma nieprawidłowy harmonogram.");
            Dictionary<string, object> result = new Dictionary<string, object>();
            foreach (string day in ChildWeekdays)
            {
                Dictionary<string, object> entry = Value(weekdays, day) as
                    Dictionary<string, object>;
                if (entry == null)
                    throw new InvalidOperationException("Profil dziecka ma nieprawidłowy harmonogram.");
                object enabled = Value(entry, "enabled");
                if (!(enabled is bool))
                    throw new InvalidOperationException("Profil dziecka ma nieprawidłowy harmonogram.");
                int start = RequiredNonNegativeInt(entry, "start_minute");
                int end = RequiredNonNegativeInt(entry, "end_minute");
                int limit = RequiredNonNegativeInt(entry, "daily_limit_seconds");
                if (start >= 1440 || end < 1 || end > 1440 || start >= end ||
                    limit > 24 * 60 * 60 || limit % 60 != 0)
                    throw new InvalidOperationException("Profil dziecka ma nieprawidłowy harmonogram.");
                result[day] = new Dictionary<string, object> {
                    { "enabled", (bool)enabled }, { "start_minute", start },
                    { "end_minute", end }, { "daily_limit_seconds", limit }
                };
            }
            return new Dictionary<string, object> { { "weekdays", result } };
        }

        private static Dictionary<string, object> StoredChildSchedule(
            Dictionary<string, object> profile)
        {
            Dictionary<string, object> source = Value(profile, "schedule") as
                Dictionary<string, object>;
            Dictionary<string, object> weekdays = source == null ? null :
                Value(source, "weekdays") as Dictionary<string, object>;
            Dictionary<string, object> result = new Dictionary<string, object>();
            foreach (string day in ChildWeekdays)
            {
                Dictionary<string, object> entry = weekdays == null ? null :
                    Value(weekdays, day) as Dictionary<string, object>;
                bool enabled = entry != null && BooleanValue(entry, "enabled");
                int start = OptionalNonNegativeInt(entry, "start_minute", 0);
                int end = OptionalNonNegativeInt(entry, "end_minute", 1440);
                int limit = OptionalNonNegativeInt(entry, "daily_limit_seconds", 0);
                if (start >= 1440 || end < 1 || end > 1440 || start >= end ||
                    limit > 24 * 60 * 60 || limit % 60 != 0)
                    throw new InvalidOperationException("Profil dziecka ma nieprawidłowy harmonogram.");
                result[day] = new Dictionary<string, object> {
                    { "enabled", enabled }, { "start_minute", start },
                    { "end_minute", end }, { "daily_limit_seconds", limit }
                };
            }
            return new Dictionary<string, object> { { "weekdays", result } };
        }

        private static int OptionalNonNegativeInt(Dictionary<string, object> source,
            string key, int fallback)
        {
            if (source == null || Value(source, key) == null) return fallback;
            return RequiredNonNegativeInt(source, key);
        }

        private static string ScheduleFingerprint(Dictionary<string, object> schedule)
        {
            Dictionary<string, object> weekdays = Value(schedule, "weekdays") as
                Dictionary<string, object>;
            StringBuilder result = new StringBuilder();
            foreach (string day in ChildWeekdays)
            {
                Dictionary<string, object> entry = weekdays == null ? null :
                    Value(weekdays, day) as Dictionary<string, object>;
                result.Append(day).Append(':')
                    .Append(entry != null && BooleanValue(entry, "enabled") ? '1' : '0')
                    .Append(':').Append(OptionalNonNegativeInt(entry, "start_minute", 0))
                    .Append(':').Append(OptionalNonNegativeInt(entry, "end_minute", 1440))
                    .Append(':').Append(OptionalNonNegativeInt(entry,
                        "daily_limit_seconds", 0)).Append(';');
            }
            return result.ToString();
        }

        private static string ChildProfileFingerprint(string operation,
            string parentProfileId, string childProfileId, int expectedRevision,
            Dictionary<string, object> draft, bool grantCurrentDevice,
            string grantClientId)
        {
            StringBuilder result = new StringBuilder();
            result.Append(operation).Append('\n').Append(parentProfileId).Append('\n')
                .Append(childProfileId).Append('\n').Append(expectedRevision)
                .Append('\n').Append(grantCurrentDevice ? '1' : '0')
                .Append('\n').Append(grantClientId ?? "");
            if (draft != null)
                result.Append('\n').Append(Convert.ToString(draft["name"]))
                    .Append('\n').Append(Convert.ToString(draft["avatar_id"]))
                    .Append('\n').Append((bool)draft["enabled"] ? '1' : '0')
                    .Append('\n').Append(ScheduleFingerprint(
                        (Dictionary<string, object>)draft["schedule"]));
            return result.ToString();
        }

        private static bool ChildScheduleShrank(
            Dictionary<string, object> oldSchedule,
            Dictionary<string, object> newSchedule)
        {
            Dictionary<string, object> oldDays = Value(oldSchedule, "weekdays") as
                Dictionary<string, object>;
            Dictionary<string, object> newDays = Value(newSchedule, "weekdays") as
                Dictionary<string, object>;
            foreach (string day in ChildWeekdays)
            {
                Dictionary<string, object> oldEntry = oldDays == null ? null :
                    Value(oldDays, day) as Dictionary<string, object>;
                Dictionary<string, object> newEntry = newDays == null ? null :
                    Value(newDays, day) as Dictionary<string, object>;
                bool oldEnabled = oldEntry != null && BooleanValue(oldEntry, "enabled");
                bool newEnabled = newEntry != null && BooleanValue(newEntry, "enabled");
                if (oldEnabled && !newEnabled) return true;
                if (!oldEnabled || !newEnabled) continue;
                if (OptionalNonNegativeInt(newEntry, "start_minute", 0) >
                        OptionalNonNegativeInt(oldEntry, "start_minute", 0) ||
                    OptionalNonNegativeInt(newEntry, "end_minute", 1440) <
                        OptionalNonNegativeInt(oldEntry, "end_minute", 1440) ||
                    OptionalNonNegativeInt(newEntry, "daily_limit_seconds", 0) <
                        OptionalNonNegativeInt(oldEntry, "daily_limit_seconds", 0))
                    return true;
            }
            return false;
        }

        private static Dictionary<string, object> ChildProfileMutationResult(
            string parentProfileId, int revision,
            Dictionary<string, object> child, bool idempotent,
            bool cleanupRequired)
        {
            Dictionary<string, object> result = new Dictionary<string, object> {
                { "ok", true }, { "parent_profile_id", parentProfileId },
                { "revision", revision }, { "idempotent", idempotent },
                { "cleanup_required", cleanupRequired }
            };
            if (child != null) result["child"] = child;
            return result;
        }

        private static string SharingFingerprint(string gameKey, int expectedRevision,
            List<string> selected)
        {
            return expectedRevision.ToString() + "\n" + gameKey + "\n" +
                String.Join("\n", selected.ToArray());
        }

        private static Dictionary<string, object> SharingRequestRecord(string fingerprint,
            int revision, List<string> selected,
            List<ChildGameInvalidation> invalidations)
        {
            List<object> selectedValues = new List<object>();
            foreach (string value in selected) selectedValues.Add(value);
            List<object> invalidationValues = new List<object>();
            foreach (ChildGameInvalidation value in invalidations)
                invalidationValues.Add(new Dictionary<string, object> {
                    { "child_profile_id", value.ChildProfileId },
                    { "game_key", value.GameKey },
                    { "policy_revision", value.PolicyRevision }
                });
            return new Dictionary<string, object> {
                { "fingerprint", fingerprint },
                { "revision", revision },
                { "child_profile_ids", selectedValues.ToArray() },
                { "invalidations", invalidationValues.ToArray() }
            };
        }

        private static ChildGameSharingResult SharingResultFromRecord(
            string parentProfileId, string gameKey,
            Dictionary<string, object> record, bool idempotent)
        {
            List<string> selected = new List<string>();
            IEnumerable selectedValues = Value(record, "child_profile_ids") as IEnumerable;
            if (selectedValues != null && !(selectedValues is string))
                foreach (object value in selectedValues)
                    if (value is string) selected.Add((string)value);
            List<ChildGameInvalidation> invalidations =
                new List<ChildGameInvalidation>();
            IEnumerable invalidationValues = Value(record, "invalidations") as IEnumerable;
            if (invalidationValues != null && !(invalidationValues is string))
                foreach (object raw in invalidationValues)
                {
                    Dictionary<string, object> value = raw as Dictionary<string, object>;
                    if (value == null) continue;
                    invalidations.Add(new ChildGameInvalidation {
                        ChildProfileId = Text(value, "child_profile_id", ""),
                        GameKey = Text(value, "game_key", gameKey),
                        PolicyRevision = IntValue(value, "policy_revision")
                    });
                }
            return new ChildGameSharingResult {
                ParentProfileId = parentProfileId,
                GameKey = gameKey,
                Revision = IntValue(record, "revision"),
                ChildProfileIds = selected,
                Invalidations = invalidations,
                Idempotent = idempotent
            };
        }

        private static void TrimChildSharingRequests(Dictionary<string, object> requests)
        {
            while (requests.Count > MaxChildSharingRequests)
            {
                string oldest = null;
                foreach (string key in requests.Keys) { oldest = key; break; }
                if (oldest == null) break;
                requests.Remove(oldest);
            }
        }

        internal static ProfileRecord ToProfile(string id, Dictionary<string, object> profile)
        {
            string provider = Text(profile, "game_provider_bridge", "");
            List<string> allowedGameKeys = new List<string>();
            IEnumerable allowed = Value(profile, "allowed_game_keys") as IEnumerable;
            if (allowed != null && !(allowed is string))
                foreach (object value in allowed)
                    if (value is string) allowedGameKeys.Add((string)value);
            Dictionary<string, object> tombstone = Value(profile,
                "deletion_tombstone") as Dictionary<string, object>;
            return new ProfileRecord {
                Id = id,
                Kind = Text(profile, "kind", "standard"),
                ParentProfileId = Text(profile, "parent_profile_id", ""),
                AvatarId = Text(profile, "avatar_id", ""),
                PolicyRevision = IntValue(profile, "policy_revision"),
                AllowedGameKeys = allowedGameKeys,
                DisplayName = Text(profile, "display_name", Text(profile, "name", id)),
                Sid = Text(profile, "windows_account_sid", ""),
                AccountName = Text(profile, "windows_account_name", ""),
                Root = Text(profile, "profile_root", ""),
                DiscordEndpoint = Text(profile, "discord_bridge", ""),
                GameProviderEndpoint = provider,
                Enabled = BooleanValue(profile, "enabled"),
                AppPinRequired = Value(profile, "pin_verifier") != null,
                RemoteSignInEnabled = BooleanValue(profile, "remote_sign_in_enabled"),
                MappingStatus = Text(profile, "account_mapping_status", "action_required"),
                DeletionNonce = tombstone == null ? "" : Text(tombstone, "nonce", ""),
                DeletionGeneration = tombstone == null ? 0 : LongValue(tombstone, "generation"),
                ReservationNonce = Text(profile, "reservation_nonce", "")
            };
        }

        private static Dictionary<string, object> ChildProfileDto(
            Dictionary<string, object> profile)
        {
            string id = Text(profile, "id", "");
            return new Dictionary<string, object> {
                { "id", id },
                { "name", Text(profile, "display_name", Text(profile, "name", id)) },
                { "avatar_id", Text(profile, "avatar_id", "") },
                { "enabled", BooleanValue(profile, "enabled") },
                { "parent_profile_id", Text(profile, "parent_profile_id", "") },
                { "policy_revision", ChildPolicyRevision(profile) },
                { "schedule", StoredChildSchedule(profile) }
            };
        }

        private static void EnsureProvisioningReservation(Dictionary<string, object> profile,
            ProfileRecord expected)
        {
            EnsureSid(profile, expected.Sid);
            EnsureNotTombstoned(profile);
            object enabled = Value(profile, "enabled");
            if (!(enabled is bool) || (bool)enabled ||
                String.IsNullOrWhiteSpace(expected.ReservationNonce) ||
                !String.Equals(Text(profile, "reservation_nonce", ""), expected.ReservationNonce,
                    StringComparison.Ordinal) ||
                !String.Equals(Path.GetFullPath(Text(profile, "profile_root", "")).TrimEnd('\\'),
                    Path.GetFullPath(expected.Root).TrimEnd('\\'),
                    StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "Rezerwacja profilu zmieniła się lub została usunięta podczas instalacji.");
        }

        private static void EnsureStandardProfile(Dictionary<string, object> profile)
        {
            if (String.Equals(Text(profile, "kind", "standard"), "child",
                    StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "Profil dziecka nie ma konta Windows ani katalogu wykonywania.");
        }

        internal static IEnumerable<Dictionary<string, object>> Clients(Dictionary<string, object> document)
        {
            IEnumerable values = Value(document, "clients") as IEnumerable;
            if (values == null) yield break;
            foreach (object value in values)
            {
                Dictionary<string, object> client = value as Dictionary<string, object>;
                if (client != null) yield return client;
            }
        }

        internal static HashSet<string> Permissions(Dictionary<string, object> client, string profileId)
        {
            HashSet<string> result = new HashSet<string>(StringComparer.Ordinal);
            object grantsValue = Value(client, "profile_grants");
            Dictionary<string, object> grants = grantsValue as Dictionary<string, object>;
            object permissionsValue;
            if (grants == null || !grants.TryGetValue(profileId, out permissionsValue)) return result;
            IEnumerable permissions = permissionsValue as IEnumerable;
            if (permissions == null || permissionsValue is string) return result;
            foreach (object permission in permissions)
            {
                string text = permission as string;
                if (text == "use_profile" || text == "remote_sign_in" ||
                    text == "manage_children") result.Add(text);
            }
            return result;
        }

        private static void RemoveProfileGrants(Dictionary<string, object> document, string id)
        {
            foreach (Dictionary<string, object> client in Clients(document))
            {
                Dictionary<string, object> grants = Value(client, "profile_grants") as Dictionary<string, object>;
                if (grants != null) grants.Remove(id);
            }
        }

        private static void RemoveRemoteSignInGrants(Dictionary<string, object> document, string id)
        {
            foreach (Dictionary<string, object> client in Clients(document))
            {
                Dictionary<string, object> grants = Value(client, "profile_grants") as Dictionary<string, object>;
                object permissionsValue;
                if (grants == null || !grants.TryGetValue(id, out permissionsValue)) continue;
                IEnumerable permissions = permissionsValue as IEnumerable;
                bool use = false;
                bool manageChildren = false;
                if (permissions != null && !(permissionsValue is string))
                    foreach (object permission in permissions)
                    {
                        if (Convert.ToString(permission) == "use_profile") use = true;
                        if (Convert.ToString(permission) == "manage_children") manageChildren = true;
                    }
                if (use)
                {
                    List<object> kept = new List<object> { "use_profile" };
                    if (manageChildren) kept.Add("manage_children");
                    grants[id] = kept.ToArray();
                }
                else grants.Remove(id);
            }
        }

        internal static void EnsureSidUnused(Dictionary<string, object> profiles, string sid, string exceptId)
        {
            foreach (KeyValuePair<string, object> item in profiles)
            {
                if (String.Equals(item.Key, exceptId, StringComparison.Ordinal)) continue;
                Dictionary<string, object> profile = item.Value as Dictionary<string, object>;
                if (profile != null && SameSid(Text(profile, "windows_account_sid", ""), sid))
                    throw new InvalidOperationException(
                        "To konto Windows ma już profil MoonWaker. Jedno konto może mieć tylko jeden profil.");
            }
        }

        private static void EnsureSid(Dictionary<string, object> profile, string expectedSid)
        {
            string current = Text(profile, "windows_account_sid", "");
            if (!SameSid(current, expectedSid))
                throw new InvalidOperationException(
                    "Mapowanie SID profilu zmieniło się. Usuń i utwórz profil ponownie.");
        }

        private static void EnsureRemovalIdentity(Dictionary<string, object> profile,
            ProfileRecord expected)
        {
            string id = Text(profile, "id", expected.Id);
            string sid = Text(profile, "windows_account_sid", "");
            string account = Text(profile, "windows_account_name", "");
            string root = Text(profile, "profile_root", "");
            if (!String.Equals(id, expected.Id, StringComparison.Ordinal) ||
                !String.Equals(sid, expected.Sid ?? "", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(account, expected.AccountName ?? "", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(root, expected.Root ?? "", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "Tożsamość profilu zmieniła się podczas usuwania. Odśwież Host Control i spróbuj ponownie.");
        }

        private static void EnsureNotTombstoned(Dictionary<string, object> profile)
        {
            if (Value(profile, "deletion_tombstone") != null)
                throw new InvalidOperationException(
                    "Profil oczekuje na bezpieczne usunięcie i nie może być zmieniany ani ponownie włączony.");
        }

        private static void EnsureDeletionTombstone(Dictionary<string, object> profile,
            ProfileRecord expected)
        {
            Dictionary<string, object> tombstone = Value(profile,
                "deletion_tombstone") as Dictionary<string, object>;
            string nonce = tombstone == null ? "" : Text(tombstone, "nonce", "");
            long generation = tombstone == null ? 0 : LongValue(tombstone, "generation");
            if (String.IsNullOrWhiteSpace(expected.DeletionNonce) ||
                !String.Equals(nonce, expected.DeletionNonce, StringComparison.Ordinal) ||
                generation != expected.DeletionGeneration)
                throw new InvalidOperationException(
                    "Brak zgodnego trwałego znacznika usuwania profilu. Odśwież Host Control.");
            EnsureTombstoneSnapshot(tombstone, expected);
        }

        private static void EnsureTombstoneSnapshot(Dictionary<string, object> tombstone,
            ProfileRecord expected)
        {
            if (tombstone == null ||
                !String.Equals(Text(tombstone, "profile_id", ""), expected.Id,
                    StringComparison.Ordinal) ||
                !String.Equals(Text(tombstone, "windows_account_sid", ""),
                    expected.Sid ?? "", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(Text(tombstone, "windows_account_name", ""),
                    expected.AccountName ?? "", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(Text(tombstone, "profile_root", ""),
                    expected.Root ?? "", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "Trwały znacznik usuwania nie pasuje do zapisanej tożsamości i katalogu profilu.");
        }

        private static bool SameSid(string left, string right)
        {
            return IsSid(left) && String.Equals(left, right, StringComparison.OrdinalIgnoreCase);
        }

        internal static bool IsSid(string value)
        {
            return !String.IsNullOrWhiteSpace(value) &&
                System.Text.RegularExpressions.Regex.IsMatch(value, "^S-[0-9]+(?:-[0-9]+){2,15}$");
        }

        internal static void ValidateDisplayName(string value)
        {
            string trimmed = value == null ? "" : value.Trim();
            if (trimmed.Length == 0 || trimmed.Length > 80 || DisplayText.HasHidden(trimmed))
                throw new InvalidOperationException("Nazwa profilu musi mieć od 1 do 80 znaków drukowalnych.");
        }

        private static HashSet<int> UsedPorts(Dictionary<string, object> profiles)
        {
            HashSet<int> result = new HashSet<int>();
            foreach (object value in profiles.Values)
            {
                Dictionary<string, object> profile = value as Dictionary<string, object>;
                if (profile == null) continue;
                foreach (string name in new string[] { "discord_bridge", "game_provider_bridge" })
                {
                    int port = EndpointPort(Text(profile, name, ""));
                    if (port > 0) result.Add(port);
                }
            }
            try
            {
                foreach (System.Net.IPEndPoint endpoint in
                    IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners())
                    if (endpoint.Address.Equals(System.Net.IPAddress.Loopback) ||
                        endpoint.Address.Equals(System.Net.IPAddress.IPv6Loopback)) result.Add(endpoint.Port);
            }
            catch { }
            return result;
        }

        private static int[] AllocatePorts(HashSet<int> used)
        {
            for (int slot = 0; slot <= 50; slot++)
            {
                int[] ports = new int[] { 8765 + (100 * slot), 8780 + (100 * slot) };
                if (!used.Contains(ports[0]) && !used.Contains(ports[1]))
                    return ports;
            }
            throw new InvalidOperationException("Brak wolnego zestawu portów Bridge.");
        }

        internal static int EndpointPort(string endpoint)
        {
            Uri uri;
            if (!Uri.TryCreate(endpoint, UriKind.Absolute, out uri) ||
                uri.Scheme != Uri.UriSchemeHttp ||
                !(uri.Host == "127.0.0.1" || uri.Host == "localhost") ||
                uri.Port < 1024 || uri.Port > 65535) return 0;
            return uri.Port;
        }

        private static string SafeClientId(string id)
        {
            if (id.Length <= 12) return id;
            return id.Substring(0, 8) + "…" + id.Substring(id.Length - 4);
        }

        private static object Value(Dictionary<string, object> source, string key)
        {
            object value;
            return source != null && source.TryGetValue(key, out value) ? value : null;
        }

        private static Dictionary<string, object> Dictionary(Dictionary<string, object> source,
            string key, bool create)
        {
            object value = Value(source, key);
            Dictionary<string, object> result = value as Dictionary<string, object>;
            if (result == null && create)
            {
                result = new Dictionary<string, object>();
                source[key] = result;
            }
            if (result == null) throw new InvalidOperationException("Rejestr Gateway ma nieprawidłową strukturę.");
            return result;
        }

        internal static string Text(Dictionary<string, object> source, string key, string fallback)
        {
            object value = Value(source, key);
            return value == null ? fallback : Convert.ToString(value);
        }

        private static bool BooleanValue(Dictionary<string, object> source, string key)
        {
            object value = Value(source, key);
            return value is bool && (bool)value;
        }

        private static int IntValue(Dictionary<string, object> source, string key)
        {
            int result;
            return Int32.TryParse(Convert.ToString(Value(source, key)), out result) ? result : 0;
        }

        private static long LongValue(Dictionary<string, object> source, string key)
        {
            long result;
            return Int64.TryParse(Convert.ToString(Value(source, key)), out result) ? result : 0;
        }

        private static bool IsNonNegativeInt(object value)
        {
            if (value is bool || value == null) return false;
            int parsed;
            return Int32.TryParse(Convert.ToString(value), out parsed) && parsed >= 0;
        }
    }

    // The registry changed after Host Control showed it: nothing was applied.
    internal sealed class StaleRegistryException : InvalidOperationException
    {
        internal StaleRegistryException() : base(
            "Ustawienia zmieniły się, odkąd Host Control je pokazał. Nic nie zapisano — sprawdź zmiany i zapisz ponownie.") { }
    }

    internal sealed class RegistryFileLock : IDisposable
    {
        private FileStream stream;
        private RegistryFileLock(FileStream stream) { this.stream = stream; }

        internal static RegistryFileLock Acquire(string path, int timeoutMilliseconds)
        {
            Stopwatch clock = Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < timeoutMilliseconds)
            {
                FileStream candidate = null;
                try
                {
                    candidate = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite,
                        FileShare.ReadWrite);
                    if (candidate.Length < 1)
                    {
                        candidate.WriteByte(0);
                        candidate.Flush(true);
                        candidate.Position = 0;
                    }
                    candidate.Lock(0, 1);
                    return new RegistryFileLock(candidate);
                }
                catch (IOException)
                {
                    if (candidate != null) candidate.Dispose();
                    Thread.Sleep(50);
                }
            }
            throw new TimeoutException("Rejestr Gateway jest zajęty. Spróbuj ponownie.");
        }

        public void Dispose()
        {
            if (stream == null) return;
            try { stream.Unlock(0, 1); }
            finally { stream.Dispose(); stream = null; }
        }
    }

    internal static class PathSafety
    {
        private const uint InvalidFileAttributes = 0xFFFFFFFF;
        private const int ErrorFileNotFound = 2;
        private const int ErrorPathNotFound = 3;

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFileAttributes(string fileName);

        internal static bool IsConclusiveAbsent(string path)
        {
            if (String.IsNullOrWhiteSpace(path)) return false;
            string full = Path.GetFullPath(path);
            string root = Path.GetPathRoot(full);
            if (String.IsNullOrWhiteSpace(root) || String.Equals(full.TrimEnd('\\'),
                    root.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) return false;
            string current = root;
            string remainder = full.Substring(root.Length);
            foreach (string part in remainder.Split(new char[] { '\\', '/' },
                StringSplitOptions.RemoveEmptyEntries))
            {
                current = Path.Combine(current, part);
                uint attributes = GetFileAttributes(current);
                if (attributes == InvalidFileAttributes)
                {
                    int error = Marshal.GetLastWin32Error();
                    if (error == ErrorFileNotFound || error == ErrorPathNotFound) return true;
                    throw new System.ComponentModel.Win32Exception(error,
                        "Nie można bezpiecznie potwierdzić braku katalogu profilu.");
                }
                if ((((FileAttributes)attributes) & FileAttributes.ReparsePoint) != 0)
                    return false;
            }
            return false;
        }
    }

    internal static class ProfileProvisioner
    {
        // The data directory of a profile ReserveProfile has just registered (with its ports).
        internal static void Provision(GatewayRegistryStore store, ProfileRecord profile)
        {
            RequireSupportedAccount(profile.Sid, profile.AccountName);
            new ProfileProvisioning {
                ProfilesRoot = store.ProfilesRoot,
                BridgeSource = TrustedInstalledDirectory(store, "bridge-source"),
                ProfileId = profile.Id,
                OwnerSid = profile.Sid,
                DiscordPort = profile.DiscordPort,
                GameProviderPort = profile.GameProviderPort
            }.Run();
        }

        // The account was chosen from this list a moment ago; it must still be an enabled,
        // supported local account with the same name.
        private static void RequireSupportedAccount(string sid, string qualifiedName)
        {
            foreach (LocalAccount account in LocalAccountEnumerator.EnumerateSupported())
                if (String.Equals(account.Sid, sid, StringComparison.OrdinalIgnoreCase) &&
                    String.Equals(account.QualifiedName, qualifiedName, StringComparison.OrdinalIgnoreCase))
                    return;
            throw new InvalidOperationException(
                "Wybrane konto Windows nie jest już włączonym, obsługiwanym kontem lokalnym albo zmieniło nazwę.");
        }

        internal static void RemoveArtifacts(GatewayRegistryStore store, ProfileRecord profile)
        {
            RemoveArtifacts(store, profile, TimeSpan.FromSeconds(10));
        }

        // The tombstone PrepareRemoval wrote ends the profile's Agent (authority lost) and, with
        // it, its Discord Bridge; the owner's tray kills them within a second or two. Until both
        // stop answering on loopback and their files are released, deletion is retried.
        internal static void RemoveArtifacts(GatewayRegistryStore store, ProfileRecord profile, TimeSpan wait)
        {
            if (String.IsNullOrWhiteSpace(profile.DeletionNonce))
                throw new InvalidOperationException("Brak trwałego znacznika usuwania profilu.");
            if (RequiresOfflineCleanup(store, profile)) return;
            string actual = Path.GetFullPath(profile.Root);
            if (File.Exists(actual))
                throw new InvalidOperationException("Ścieżka danych profilu jest plikiem.");
            Stopwatch clock = Stopwatch.StartNew();
            for (int delay = 100; ; delay = Math.Min(delay * 2, 1000))
            {
                if (!LoopbackAnswers(profile.GameProviderPort) && !LoopbackAnswers(profile.DiscordPort))
                {
                    try
                    {
                        if (Directory.Exists(actual)) Directory.Delete(actual, true);
                        return;
                    }
                    catch (IOException) { }
                    catch (UnauthorizedAccessException) { }
                }
                if (clock.Elapsed >= wait)
                    throw new InvalidOperationException(
                        "Procesy profilu nadal działają albo trzymają jego pliki, więc katalog danych nie " +
                        "został usunięty. Profil pozostaje oznaczony do usunięcia — ponów usuwanie za chwilę.");
                Thread.Sleep(delay);
            }
        }

        private static bool LoopbackAnswers(int port)
        {
            if (port == 0) return false;
            using (System.Net.Sockets.TcpClient client = new System.Net.Sockets.TcpClient())
            {
                try { return client.ConnectAsync(System.Net.IPAddress.Loopback, port).Wait(250) && client.Connected; }
                catch { return false; }
            }
        }

        private static string TrustedInstalledDirectory(GatewayRegistryStore store, string name)
        {
            string root = Path.GetFullPath(store.InstallRoot).TrimEnd('\\');
            string control = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(
                Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            DirectoryInfo verifiedRoot = Directory.GetParent(control);
            if (verifiedRoot == null || !String.Equals(root,
                    verifiedRoot.FullName.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                throw new UnauthorizedAccessException(
                    "Rejestr Gateway nie należy do chronionej instalacji konfiguratora.");
            string candidate = Path.Combine(root, name);
            if (!Directory.Exists(candidate))
                throw new DirectoryNotFoundException("Brak chronionego składnika instalacji MoonWaker: " + candidate);
            EnsureOrdinaryPath(candidate);
            return candidate;
        }

        private static bool HasValidSid(string value)
        {
            return !String.IsNullOrWhiteSpace(value) &&
                System.Text.RegularExpressions.Regex.IsMatch(value,
                    "^S-[0-9]+(?:-[0-9]+){2,15}$");
        }

        internal static bool HasAuthoritativeOwner(ProfileRecord profile)
        {
            return profile != null && HasValidSid(profile.Sid);
        }

        internal static bool RequiresOfflineCleanup(GatewayRegistryStore store, ProfileRecord profile)
        {
            if (store == null || profile == null) return true;
            try
            {
                string expected = Path.GetFullPath(Path.Combine(store.ProfilesRoot, profile.Id));
                string actual = Path.GetFullPath(profile.Root);
                return !String.Equals(expected.TrimEnd('\\'), actual.TrimEnd('\\'),
                    StringComparison.OrdinalIgnoreCase);
            }
            catch { return true; }
        }

        // No existing component of the path is a reparse point.
        internal static void EnsureOrdinaryPath(string path)
        {
            FileSystemInfo cursor = File.Exists(path)
                ? (FileSystemInfo)new FileInfo(path) : new DirectoryInfo(path);
            while (cursor != null)
            {
                if (cursor.Exists && (cursor.Attributes & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidOperationException(
                        "Ścieżka MoonWaker zawiera dowiązanie; operacja została zatrzymana.");
                DirectoryInfo parent = cursor is DirectoryInfo
                    ? ((DirectoryInfo)cursor).Parent : ((FileInfo)cursor).Directory;
                cursor = parent;
            }
        }
    }

    // Creates one new profile's data directory: <profiles>\<id> with the Agent's and, when
    // Discord is installed, Discord Bridge's configuration seeded from bridge-source's examples
    // and the profile's ports. Then the whole tree gets a protected ACL owned by Administrators:
    // SYSTEM and Administrators full, the owner account Modify; the root last, so the account
    // cannot change the tree during the walk. The profile's registry entry is not touched here.
    // An input-only command: the elevated Configurator runs it with what it has validated.
    internal sealed class ProfileProvisioning
    {
        private const string SystemSid = "S-1-5-18";
        private const string AdministratorsSid = "S-1-5-32-544";
        internal string ProfilesRoot;
        internal string BridgeSource;
        internal string ProfileId;
        internal string OwnerSid;
        internal int DiscordPort;
        internal int GameProviderPort;

        internal void Run()
        {
            ProtectTree(CreateLayout());
        }

        internal string CreateLayout()
        {
            if (!GatewayRegistryStore.IsValidProfileId(ProfileId))
                throw new InvalidOperationException("Nieprawidłowy identyfikator profilu.");
            new SecurityIdentifier(OwnerSid);
            bool discord = File.Exists(Path.Combine(BridgeSource, "discord", "DiscordBridge.ps1"));
            if (!ValidPort(GameProviderPort) || (discord && !ValidPort(DiscordPort)))
                throw new InvalidOperationException("Porty Bridge'y muszą mieścić się w zakresie 1024–65535.");
            if (discord && DiscordPort == GameProviderPort)
                throw new InvalidOperationException("Discord Bridge i Agent muszą używać różnych portów.");
            string profilesRoot = Path.GetFullPath(ProfilesRoot).TrimEnd('\\');
            if (File.Exists(profilesRoot))
                throw new InvalidOperationException("Katalog profili jest plikiem; tworzenie profilu zatrzymane.");
            string root = Path.Combine(profilesRoot, ProfileId);
            ProfileProvisioner.EnsureOrdinaryPath(root);
            if (Directory.Exists(root) || File.Exists(root))
                throw new InvalidOperationException(
                    "Zarezerwowany katalog profilu już istnieje. Usuń nieudany profil jawnie i ponów próbę.");
            Directory.CreateDirectory(root);

            if (discord)
            {
                Dictionary<string, object> config = Seed(root, "discord", "discord",
                    "discord_bridge_config.example.json");
                config["port"] = DiscordPort;
                Write(Path.Combine(root, "discord", "discord_bridge_config.json"), config);
            }
            Dictionary<string, object> provider = Seed(root, "playnite", "game-provider", "config.example.json");
            provider["listen_port"] = GameProviderPort;
            provider["epic_legendary_enabled"] = true;
            provider["playnite_enabled"] = false;
            if (!provider.ContainsKey("legendary_path")) provider["legendary_path"] = "";
            Write(Path.Combine(root, "game-provider", "config.json"), provider);
            return root;
        }

        private static bool ValidPort(int port) { return port >= 1024 && port <= 65535; }

        private Dictionary<string, object> Seed(string root, string source, string destination, string example)
        {
            Directory.CreateDirectory(Path.Combine(root, destination));
            return new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(
                File.ReadAllText(Path.Combine(BridgeSource, source, example), Encoding.UTF8));
        }

        private static void Write(string path, Dictionary<string, object> value)
        {
            File.WriteAllText(path, new JavaScriptSerializer().Serialize(value), new UTF8Encoding(false));
        }

        internal void ProtectTree(string root)
        {
            ProfileProvisioner.EnsureOrdinaryPath(root);
            if (!Directory.Exists(root))
                throw new InvalidOperationException("Nowy katalog profilu zniknął podczas tworzenia.");
            Stack<DirectoryInfo> pending = new Stack<DirectoryInfo>();
            pending.Push(new DirectoryInfo(root));
            while (pending.Count != 0)
            {
                foreach (FileSystemInfo item in pending.Pop().GetFileSystemInfos())
                {
                    if ((item.Attributes & FileAttributes.ReparsePoint) != 0)
                        throw new InvalidOperationException(
                            "Katalog profilu zawiera dowiązanie; tworzenie profilu zatrzymane.");
                    DirectoryInfo directory = item as DirectoryInfo;
                    if (directory != null)
                    {
                        Directory.SetAccessControl(directory.FullName, (DirectorySecurity)Security(true));
                        RequireTrustedOwner(Directory.GetAccessControl(directory.FullName));
                        pending.Push(directory);
                    }
                    else
                    {
                        File.SetAccessControl(item.FullName, (FileSecurity)Security(false));
                        RequireTrustedOwner(File.GetAccessControl(item.FullName));
                    }
                }
            }
            Directory.SetAccessControl(root, (DirectorySecurity)Security(true));
            RequireTrustedOwner(Directory.GetAccessControl(root));
        }

        internal FileSystemSecurity Security(bool directory)
        {
            FileSystemSecurity security = directory
                ? (FileSystemSecurity)new DirectorySecurity() : new FileSecurity();
            security.SetAccessRuleProtection(true, false);
            security.SetOwner(new SecurityIdentifier(AdministratorsSid));
            InheritanceFlags inheritance = directory
                ? InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit : InheritanceFlags.None;
            security.SetAccessRule(new FileSystemAccessRule(new SecurityIdentifier(SystemSid),
                FileSystemRights.FullControl, inheritance, PropagationFlags.None, AccessControlType.Allow));
            security.SetAccessRule(new FileSystemAccessRule(new SecurityIdentifier(AdministratorsSid),
                FileSystemRights.FullControl, inheritance, PropagationFlags.None, AccessControlType.Allow));
            security.SetAccessRule(new FileSystemAccessRule(new SecurityIdentifier(OwnerSid),
                FileSystemRights.Modify, inheritance, PropagationFlags.None, AccessControlType.Allow));
            return security;
        }

        private static void RequireTrustedOwner(FileSystemSecurity security)
        {
            string owner = security.GetOwner(typeof(SecurityIdentifier)).Value;
            if (owner != SystemSid && owner != AdministratorsSid)
                throw new InvalidOperationException("Element katalogu profilu ma niezaufanego właściciela ACL.");
        }
    }

    internal enum BrokerOperation : byte
    {
        ListSupportedLocalAccounts = 1,
        ConfigureProfileCredential = 2,
        TestProfileCredential = 3,
        DeleteProfileCredential = 4,
        GetProfileCredentialState = 5,
        GetProfileSessionState = 6
    }

    internal sealed class BrokerResult
    {
        internal bool Success;
        internal string State;
        internal string Reason;
        internal bool RequestMayHaveReachedBroker;
        internal static BrokerResult Unavailable(bool requestMayHaveReachedBroker = false)
        {
            return new BrokerResult { Success = false, State = "broker_unavailable",
                Reason = "broker_unavailable",
                RequestMayHaveReachedBroker = requestMayHaveReachedBroker };
        }
    }

    internal sealed class LoginBrokerClient
    {
        internal const string ManagementPipeName = "MoonWakerLoginBroker.Management.v1";
        internal const byte ProtocolVersion = 1;
        private const int TimeoutMilliseconds = 500;
        private const int MaximumFieldBytes = 4096;
        private const int MaximumMessageBytes = 32768;
        private static readonly byte[] RequestMagic = Encoding.ASCII.GetBytes("MWLB");
        private static readonly byte[] ResponseMagic = Encoding.ASCII.GetBytes("MWLR");
        private readonly Func<BrokerOperation, Dictionary<byte, object>, BrokerResult> transport;

        // transport: the Broker's management pipe; tests pass a stand-in.
        internal LoginBrokerClient(Func<BrokerOperation, Dictionary<byte, object>, BrokerResult> transport = null)
        {
            this.transport = transport ?? new Func<BrokerOperation, Dictionary<byte, object>, BrokerResult>(Send);
        }

        internal static string StatePolish(string state)
        {
            if (state == "ready") return "gotowe";
            if (state == "disabled") return "wyłączone";
            if (state == "action_required") return "wymaga działania";
            if (state == "unsupported") return "konto nieobsługiwane";
            if (state == "broker_unavailable" || state == "unavailable") return "usługa niedostępna";
            return "nieznany";
        }

        internal static bool Unavailable(BrokerResult result)
        {
            return result.State == "broker_unavailable" || result.State == "unavailable";
        }

        internal BrokerResult Configure(ProfileRecord profile, char[] password)
        {
            Dictionary<byte, object> fields = BasicFields(profile);
            fields[4] = password;
            return transport(BrokerOperation.ConfigureProfileCredential, fields);
        }

        internal BrokerResult Test(ProfileRecord profile)
        {
            return transport(BrokerOperation.TestProfileCredential, BasicFields(profile));
        }

        internal BrokerResult Delete(ProfileRecord profile)
        {
            Dictionary<byte, object> fields = BasicFields(profile);
            // The durable removal nonce/generation makes retries the same
            // idempotent deletion operation for the future SYSTEM Broker peer.
            fields[5] = profile.DeletionNonce;
            fields[6] = profile.DeletionGeneration.ToString(
                System.Globalization.CultureInfo.InvariantCulture);
            return transport(BrokerOperation.DeleteProfileCredential, fields);
        }

        internal BrokerResult CredentialState(ProfileRecord profile)
        {
            return transport(BrokerOperation.GetProfileCredentialState, BasicFields(profile));
        }

        internal BrokerResult SessionState(ProfileRecord profile)
        {
            return transport(BrokerOperation.GetProfileSessionState, BasicFields(profile));
        }

        private static Dictionary<byte, object> BasicFields(ProfileRecord profile)
        {
            return new Dictionary<byte, object> {
                { 1, profile.Id }, { 2, profile.Sid }, { 3, profile.AccountName }
            };
        }

        private BrokerResult Send(BrokerOperation operation, Dictionary<byte, object> fields)
        {
            bool requestStarted = false;
            try
            {
                using (NamedPipeClientStream pipe = new NamedPipeClientStream(".", ManagementPipeName,
                    PipeDirection.InOut, PipeOptions.Asynchronous,
                    TokenImpersonationLevel.Identification))
                {
                    pipe.Connect(TimeoutMilliseconds);
                    if (!IsLocalSystemServer(pipe)) return BrokerResult.Unavailable(false);
                    Stopwatch deadline = Stopwatch.StartNew();
                    requestStarted = true;
                    Write(pipe, RequestMagic, deadline);
                    Write(pipe, new byte[] { ProtocolVersion, (byte)operation,
                        checked((byte)fields.Count), 0 }, deadline);
                    int total = 8;
                    foreach (KeyValuePair<byte, object> field in fields)
                    {
                        char[] secret = field.Value as char[];
                        byte[] value = secret == null
                            ? Encoding.UTF8.GetBytes(Convert.ToString(field.Value))
                            : EncodeSecret(secret);
                        try
                        {
                            if (value.Length > MaximumFieldBytes || total + 5 + value.Length > MaximumMessageBytes)
                                throw new InvalidOperationException("Pole IPC jest zbyt duże.");
                            byte[] header = new byte[5];
                            header[0] = field.Key;
                            WriteInt32(header, 1, value.Length);
                            Write(pipe, header, deadline);
                            Write(pipe, value, deadline);
                            total += 5 + value.Length;
                        }
                        finally { Array.Clear(value, 0, value.Length); }
                    }
                    pipe.Flush();
                    return ReadResponse(pipe, deadline);
                }
            }
            catch
            {
                return BrokerResult.Unavailable(requestStarted);
            }
        }

        private static BrokerResult ReadResponse(NamedPipeClientStream pipe, Stopwatch deadline)
        {
            byte[] header = Read(pipe, 8, deadline);
            if (!Equal(header, 0, ResponseMagic) || header[4] != ProtocolVersion || header[6] > 8)
                return BrokerResult.Unavailable(true);
            byte status = header[5];
            int fieldCount = header[6];
            int total = 8;
            Dictionary<byte, string> fields = new Dictionary<byte, string>();
            for (int index = 0; index < fieldCount; index++)
            {
                byte[] fieldHeader = Read(pipe, 5, deadline);
                int length = ReadInt32(fieldHeader, 1);
                if (length < 0 || length > MaximumFieldBytes || total + 5 + length > MaximumMessageBytes)
                    return BrokerResult.Unavailable(true);
                byte[] value = Read(pipe, length, deadline);
                try { fields[fieldHeader[0]] = Encoding.UTF8.GetString(value); }
                finally { Array.Clear(value, 0, value.Length); }
                total += 5 + length;
            }
            string state;
            string reason;
            fields.TryGetValue(1, out state);
            fields.TryGetValue(2, out reason);
            return new BrokerResult {
                Success = status == 0,
                State = String.IsNullOrWhiteSpace(state) ? (status == 0 ? "ready" : "action_required") : state,
                Reason = String.IsNullOrWhiteSpace(reason) ? (status == 0 ? "none" : "action_required") : reason
            };
        }

        private static byte[] EncodeSecret(char[] value)
        {
            int count = Encoding.UTF8.GetByteCount(value, 0, value.Length);
            if (count > MaximumFieldBytes) throw new InvalidOperationException("Hasło jest zbyt długie.");
            byte[] bytes = new byte[count];
            Encoding.UTF8.GetBytes(value, 0, value.Length, bytes, 0);
            return bytes;
        }

        private static void Write(NamedPipeClientStream pipe, byte[] value, Stopwatch deadline)
        {
            IAsyncResult pending = pipe.BeginWrite(value, 0, value.Length, null, null);
            int remaining = TimeoutMilliseconds - (int)deadline.ElapsedMilliseconds;
            if (remaining <= 0 || !pending.AsyncWaitHandle.WaitOne(remaining))
                throw new TimeoutException();
            pipe.EndWrite(pending);
        }

        private static byte[] Read(NamedPipeClientStream pipe, int count, Stopwatch deadline)
        {
            byte[] result = new byte[count];
            int offset = 0;
            while (offset < count)
            {
                IAsyncResult pending = pipe.BeginRead(result, offset, count - offset, null, null);
                int remaining = TimeoutMilliseconds - (int)deadline.ElapsedMilliseconds;
                if (remaining <= 0 || !pending.AsyncWaitHandle.WaitOne(remaining))
                    throw new TimeoutException();
                int read = pipe.EndRead(pending);
                if (read <= 0) throw new EndOfStreamException();
                offset += read;
            }
            return result;
        }

        private static bool Equal(byte[] value, int offset, byte[] expected)
        {
            if (value.Length - offset < expected.Length) return false;
            for (int index = 0; index < expected.Length; index++)
                if (value[offset + index] != expected[index]) return false;
            return true;
        }

        private static void WriteInt32(byte[] value, int offset, int number)
        {
            value[offset] = (byte)number;
            value[offset + 1] = (byte)(number >> 8);
            value[offset + 2] = (byte)(number >> 16);
            value[offset + 3] = (byte)(number >> 24);
        }

        private static int ReadInt32(byte[] value, int offset)
        {
            return value[offset] | (value[offset + 1] << 8) |
                (value[offset + 2] << 16) | (value[offset + 3] << 24);
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetNamedPipeServerProcessId(
            Microsoft.Win32.SafeHandles.SafePipeHandle pipe, out uint serverProcessId);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint desiredAccess, bool inheritHandle, uint processId);
        [DllImport("advapi32.dll", SetLastError = true)]
        private static extern bool OpenProcessToken(IntPtr process, uint desiredAccess, out IntPtr token);
        [DllImport("kernel32.dll")]
        private static extern bool CloseHandle(IntPtr handle);

        private static bool IsLocalSystemServer(NamedPipeClientStream pipe)
        {
            uint processId;
            if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out processId)) return false;
            IntPtr process = OpenProcess(0x1000, false, processId);
            if (process == IntPtr.Zero) return false;
            IntPtr token = IntPtr.Zero;
            try
            {
                if (!OpenProcessToken(process, 0x0008, out token)) return false;
                using (WindowsIdentity identity = new WindowsIdentity(token))
                    return identity.User != null && identity.User.IsWellKnown(WellKnownSidType.LocalSystemSid);
            }
            finally
            {
                if (token != IntPtr.Zero) CloseHandle(token);
                CloseHandle(process);
            }
        }
    }

    internal static class SelfTestProgram
    {
        private static void Main(string[] args)
        {
            string root = Path.Combine(Path.GetTempPath(),
                "MoonWakerConfiguratorTest-" + Guid.NewGuid().ToString("N"));
            try
            {
                string gateway = Path.Combine(root, "gateway");
                Directory.CreateDirectory(gateway);
                string config = Path.Combine(gateway, "gateway.json");
                File.WriteAllBytes(config + ".lock", new byte[] { 0 });
                string sidA = "S-1-5-21-100-200-300-1001";
                string sidB = "S-1-5-21-100-200-300-1002";
                string legacyRecordedRoot = Path.Combine(root, "legacy-profile-location", "default");
                File.WriteAllText(config,
                    "{\"schema_version\":2,\"child_profiles_enabled\":true,\"unknown_top\":{\"keep\":true},\"profiles\":{" +
                    "\"profile-a\":{\"id\":\"profile-a\",\"name\":\"A\",\"display_name\":\"A\"," +
                    "\"windows_account_sid\":\"" + sidA + "\"," +
                    "\"windows_account_name\":\"PC\\\\A\",\"enabled\":true," +
                    "\"profile_root\":\"C:\\\\MoonWaker\\\\profiles\\\\profile-a\"," +
                    "\"discord_bridge\":\"http://127.0.0.1:8765\"," +
                    "\"game_provider_bridge\":\"http://127.0.0.1:8780\"," +
                    "\"remote_sign_in_enabled\":false,\"account_mapping_status\":\"resolved\"," +
                    "\"unknown_profile\":17},\"default\":{\"id\":\"default\",\"name\":\"Legacy\"," +
                    "\"display_name\":\"Legacy\",\"windows_account_sid\":\"\"," +
                    "\"windows_account_name\":\"\",\"enabled\":true," +
                    "\"profile_root\":\"" + legacyRecordedRoot.Replace("\\", "\\\\") +
                    "\",\"remote_sign_in_enabled\":false," +
                    "\"account_mapping_status\":\"action_required\"}},\"clients\":[{" +
                    "\"id\":\"client-abcdefghijklmnop\",\"name\":\"TV\",\"token_sha256\":\"SECRET_HASH\"," +
                    "\"paired_at\":100,\"last_seen_at\":120,\"unknown_client\":\"keep\"," +
                    "\"profile_grants\":{\"profile-a\":[\"use_profile\"]," +
                    "\"default\":[\"use_profile\"]}}]}",
                    new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(gateway, "client-activity.json"),
                    "{\"schema_version\":1,\"clients\":{\"client-abcdefghijklmnop\":200}}",
                    new UTF8Encoding(false));
                GatewayRegistryStore store = new GatewayRegistryStore(config, 250);

                string generatedA = GatewayRegistryStore.NewProfileId();
                string generatedB = GatewayRegistryStore.NewProfileId();
                Assert(GatewayRegistryStore.IsValidProfileId(generatedA) && generatedA != generatedB,
                    "Generated profile IDs are not stable opaque values.");
                store.UpdateProfile("profile-a", sidA, "Renamed", false);
                string raw = File.ReadAllText(config);
                Dictionary<string, object> roundTrip = jsonDocument(raw);
                Assert(roundTrip.ContainsKey("child_profiles_enabled") &&
                    roundTrip["child_profiles_enabled"] is bool &&
                    (bool)roundTrip["child_profiles_enabled"] &&
                    raw.Contains("unknown_top") && raw.Contains("unknown_profile") &&
                    raw.Contains("unknown_client") && raw.Contains("SECRET_HASH") &&
                    raw.Contains("C:\\\\MoonWaker\\\\profiles\\\\profile-a"),
                    "Targeted mutation did not preserve unrelated registry data.");
                ProfileRecord edited = store.GetProfile("profile-a");
                Assert(edited.DisplayName == "Renamed" && edited.Sid == sidA && !edited.Enabled,
                    "Profile edit did not preserve immutable SID.");
                store.UpdateProfile("profile-a", sidA, "Renamed", true);
                int childrenBeforePin = store.GetChildren("profile-a").Count;
                bool childWithoutPinBlocked = false;
                try { store.CreateChildProfile("profile-a", "No Pin", ""); }
                catch (InvalidOperationException) { childWithoutPinBlocked = true; }
                Assert(childWithoutPinBlocked && store.GetChildren("profile-a").Count == childrenBeforePin,
                    "A parent without an app PIN accepted a child or changed the registry.");
                bool managementWithoutPinBlocked = false;
                try
                {
                    store.UpdateGrants("profile-a", sidA,
                        new Dictionary<string, DeviceGrant> { {
                            "client-abcdefghijklmnop",
                            new DeviceGrant { UseProfile = true,
                                ManageChildren = true }
                        } });
                }
                catch (InvalidOperationException error)
                {
                    managementWithoutPinBlocked = error.Message.IndexOf(
                        "PIN-u aplikacji rodzica", StringComparison.OrdinalIgnoreCase) >= 0;
                }
                Assert(managementWithoutPinBlocked,
                    "A parent without an app PIN accepted child-management access.");
                store.SetAppPin("profile-a", sidA, "4826");
                // A scanner-like reader without delete sharing must only delay the replacement.
                FileStream scanner = new FileStream(config, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                ThreadPool.QueueUserWorkItem(delegate { Thread.Sleep(400); scanner.Dispose(); });
                store.UpdateProfile("profile-a", sidA, "Scanned", true);
                Assert(store.GetProfile("profile-a").DisplayName == "Scanned",
                    "A briefly held registry file lost the profile write.");
                bool heldTooLongFails = false;
                using (new FileStream(config, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                {
                    try { store.UpdateProfile("profile-a", sidA, "Lost", true); }
                    catch (IOException) { heldTooLongFails = true; }
                }
                Assert(heldTooLongFails && store.GetProfile("profile-a").DisplayName == "Scanned",
                    "A registry held past the retry bound was not reported or was partially written.");
                store.UpdateProfile("profile-a", sidA, "Renamed", true);
                ProfileRecord child = store.CreateChildProfile("profile-a", "Kid", "avatar-1");
                Assert(child.Kind == "child" && child.ParentProfileId == "profile-a" &&
                    child.AllowedGameKeys.Count == 0 && child.Sid == "" && child.Root == "" &&
                    child.DiscordEndpoint == "" &&
                    child.GameProviderEndpoint == "" && !child.AppPinRequired,
                    "Child profile inherited executable identity or policy data.");
                Assert(store.GetProfiles().Find(delegate(ProfileRecord value) {
                    return value.Id == child.Id;
                }) == null && store.GetChildren("profile-a").Count == 1,
                    "Child profile leaked into the standard profile list.");
                Dictionary<string, object> childRaw = dictionaryValue(
                    dictionaryValue(jsonDocument(File.ReadAllText(config)), "profiles"), child.Id);
                foreach (string forbidden in new string[] { "windows_account_sid", "profile_root",
                    "discord_bridge", "game_provider_bridge",
                    "pin_verifier", "reservation_nonce" })
                    Assert(!childRaw.ContainsKey(forbidden), "Child profile cloned " + forbidden + ".");
                Dictionary<string, object> weekdays = dictionaryValue(
                    dictionaryValue(childRaw, "schedule"), "weekdays");
                foreach (string day in new string[] {
                    "mon", "tue", "wed", "thu", "fri", "sat", "sun" })
                    Assert(!Convert.ToBoolean(dictionaryValue(weekdays, day)["enabled"]),
                        "Child profile did not start with a disabled " + day + " schedule.");
                Assert(!File.ReadAllText(config).Contains("\"" + child.Id + "\":[\"use_profile\"]"),
                    "Child profile received an implicit client grant.");
                store.UpdateProfile("profile-a", sidA, "Renamed", false);
                bool childCreateBlocked = false;
                try { store.CreateChildProfile("profile-a", "Blocked", ""); }
                catch (InvalidOperationException) { childCreateBlocked = true; }
                Assert(childCreateBlocked, "A disabled parent accepted a new child.");
                store.UpdateProfile("profile-a", sidA, "Renamed", true);
                store.SetAppPin("profile-a", sidA, "4826");
                ProfileRecord secondChild = store.CreateChildProfile("profile-a", "Kid 2", "avatar-2");
                string sharedGameKey = "profile-a/steam:123";
                ChildGameSharingSnapshot sharing = store.GetChildGameSharing(
                    "profile-a", sharedGameKey);
                Assert(sharing.Revision == 2 && sharing.Children.Count == 2 &&
                    !sharing.Children[0].Granted && !sharing.Children[1].Granted,
                    "Child sharing snapshot did not include all own children or revision.");
                ChildGameSharingResult grantedOne = store.UpdateChildGameSharing(
                    "profile-a", sharedGameKey, new string[] { child.Id },
                    sharing.Revision, "share-one", new string[] { sharedGameKey });
                Assert(!grantedOne.Idempotent && grantedOne.Revision == 3 &&
                    grantedOne.Invalidations.Count == 0 &&
                    store.GetChildGameSharing("profile-a", sharedGameKey).Children.Find(
                        delegate(ChildGameGrantRecord value) { return value.Id == child.Id; }).Granted,
                    "Granting one own child was not atomic or did not advance revision.");
                ChildGameSharingResult retryGrant = store.UpdateChildGameSharing(
                    "profile-a", sharedGameKey, new string[] { child.Id }, 2,
                    "share-one", new string[] { sharedGameKey });
                Assert(retryGrant.Idempotent && retryGrant.Revision == grantedOne.Revision,
                    "Retrying an identical child sharing request was not idempotent.");
                ChildGameSharingResult grantedAll = store.UpdateChildGameSharing(
                    "profile-a", sharedGameKey,
                    new string[] { child.Id, secondChild.Id }, grantedOne.Revision,
                    "share-all", new string[] { sharedGameKey });
                Assert(grantedAll.Revision == 4 &&
                    store.GetChildGameSharing("profile-a", sharedGameKey).Children.Find(
                        delegate(ChildGameGrantRecord value) { return value.Id == secondChild.Id; }).Granted,
                    "Granting all own children did not preserve the full selection.");
                ChildGameSharingResult revokedOne = store.UpdateChildGameSharing(
                    "profile-a", sharedGameKey, new string[] { secondChild.Id },
                    grantedAll.Revision, "revoke-one", new string[] { sharedGameKey });
                Assert(revokedOne.Revision == 5 && revokedOne.Invalidations.Count == 1 &&
                    revokedOne.Invalidations[0].ChildProfileId == child.Id &&
                    revokedOne.Invalidations[0].GameKey == sharedGameKey,
                    "Revoking one child did not emit its exact invalidation.");
                ChildGameSharingResult revokedAll = store.UpdateChildGameSharing(
                    "profile-a", sharedGameKey, new string[0], revokedOne.Revision,
                    "revoke-all", new string[] { sharedGameKey });
                Assert(revokedAll.Revision == 6 && revokedAll.Invalidations.Count == 1 &&
                    revokedAll.Invalidations[0].ChildProfileId == secondChild.Id,
                    "Revoking all children did not clear the own selection atomically.");
                int stableSharingRevision = revokedAll.Revision;
                bool foreignDenied = false;
                try
                {
                    store.UpdateChildGameSharing("profile-a", sharedGameKey,
                        new string[] { "foreign-child" }, stableSharingRevision,
                        "foreign-child-request", new string[] { sharedGameKey });
                }
                catch (InvalidOperationException) { foreignDenied = true; }
                bool outsideCatalogDenied = false;
                try
                {
                    store.UpdateChildGameSharing("profile-a", sharedGameKey,
                        new string[] { child.Id }, stableSharingRevision,
                        "outside-catalog-request", new string[] { "profile-a/steam:999" });
                }
                catch (InvalidOperationException) { outsideCatalogDenied = true; }
                bool staleDenied = false;
                try
                {
                    store.UpdateChildGameSharing("profile-a", sharedGameKey,
                        new string[] { child.Id }, stableSharingRevision - 1,
                        "stale-request", new string[] { sharedGameKey });
                }
                catch (InvalidOperationException) { staleDenied = true; }
                ChildGameSharingSnapshot afterRejected = store.GetChildGameSharing(
                    "profile-a", sharedGameKey);
                Assert(foreignDenied && outsideCatalogDenied && staleDenied &&
                    afterRejected.Revision == stableSharingRevision &&
                    !afterRejected.Children.Find(delegate(ChildGameGrantRecord value) {
                        return value.Id == child.Id;
                    }).Granted,
                    "Rejected child sharing requests changed the registry.");
                Dictionary<string, object> childMutationWeekdays =
                    new Dictionary<string, object>();
                foreach (string day in new string[] {
                    "mon", "tue", "wed", "thu", "fri", "sat", "sun" })
                    childMutationWeekdays[day] = new Dictionary<string, object> {
                        { "enabled", false }, { "start_minute", 0 },
                        { "end_minute", 1440 }, { "daily_limit_seconds", 0 }
                    };
                Dictionary<string, object> childMutationDraft =
                    new Dictionary<string, object> {
                        { "name", "Retry child" }, { "avatar_id", "" },
                        { "enabled", true }, { "schedule",
                            new Dictionary<string, object> {
                                { "weekdays", childMutationWeekdays } } }
                    };
                int mutationRevision = store.GetChildPolicyRevision("profile-a");
                string beforeRejectedApply = File.ReadAllText(config);
                bool foreignApplyDenied = false;
                try
                {
                    store.ApplyChildProfileRequest(new Dictionary<string, object> {
                        { "operation", "update" },
                        { "parent_profile_id", "profile-a" },
                        { "child_profile_id", "foreign-child" },
                        { "request_id", "apply-foreign-child" },
                        { "expected_revision", mutationRevision },
                        { "draft", childMutationDraft }
                    });
                }
                catch (InvalidOperationException error)
                {
                    foreignApplyDenied = error.Message == "child_profile_not_owned";
                }
                bool staleApplyDenied = false;
                try
                {
                    store.ApplyChildProfileRequest(new Dictionary<string, object> {
                        { "operation", "update" },
                        { "parent_profile_id", "profile-a" },
                        { "child_profile_id", child.Id },
                        { "request_id", "apply-stale-child" },
                        { "expected_revision", mutationRevision - 1 },
                        { "draft", childMutationDraft }
                    });
                }
                catch (InvalidOperationException error)
                {
                    staleApplyDenied = error.Message == "child_policy_revision_stale";
                }
                Assert(foreignApplyDenied && staleApplyDenied &&
                    mutationRevision == store.GetChildPolicyRevision("profile-a") &&
                    beforeRejectedApply == File.ReadAllText(config),
                    "Rejected child profile mutations changed the registry.");
                Dictionary<string, object> createChildRequest =
                    new Dictionary<string, object> {
                        { "operation", "create" },
                        { "parent_profile_id", "profile-a" },
                        { "child_profile_id", "" },
                        { "request_id", "create-child-retry" },
                        { "expected_revision", mutationRevision },
                        { "draft", childMutationDraft },
                        { "grant_current_device", true },
                        { "grant_client_id", "client-abcdefghijklmnop" }
                    };
                Dictionary<string, object> createdChildResult =
                    store.ApplyChildProfileRequest(createChildRequest);
                string createdChildId = Convert.ToString(dictionaryValue(
                    createdChildResult, "child")["id"]);
                List<DeviceRecord> createdChildDevices = store.GetDevices(createdChildId);
                Dictionary<string, object> createdChildDocument = jsonDocument(
                    File.ReadAllText(config));
                Dictionary<string, object> createdClient = null;
                foreach (Dictionary<string, object> clientRecord in ClientsForTest(
                    createdChildDocument))
                    if (Convert.ToString(clientRecord["id"]) ==
                        "client-abcdefghijklmnop") createdClient = clientRecord;
                IList childPermissions = null;
                if (createdClient != null)
                {
                    Dictionary<string, object> rawGrants = dictionaryValue(
                        createdClient, "profile_grants");
                    object grantValue;
                    if (rawGrants.TryGetValue(createdChildId, out grantValue))
                        childPermissions = grantValue as IList;
                }
                Assert(createdChildDevices.Count == 1 && createdChildDevices[0].UseProfile &&
                    !createdChildDevices[0].RemoteSignIn &&
                    !createdChildDevices[0].ManageChildren && createdClient != null &&
                    childPermissions != null && childPermissions.Count == 1 &&
                    Convert.ToString(childPermissions[0]) == "use_profile",
                    "Current-device child grant was not written to the child profile.");
                IList parentPermissionsBeforeChildEdit = null;
                if (createdClient != null)
                {
                    Dictionary<string, object> rawGrants = dictionaryValue(
                        createdClient, "profile_grants");
                    object parentGrantValue;
                    if (rawGrants.TryGetValue("profile-a", out parentGrantValue))
                        parentPermissionsBeforeChildEdit = parentGrantValue as IList;
                }
                store.UpdateGrants(createdChildId, sidA,
                    new Dictionary<string, DeviceGrant> { {
                        "client-abcdefghijklmnop",
                        new DeviceGrant { UseProfile = true }
                    } });
                bool childRemoteDenied = false;
                try
                {
                    store.UpdateGrants(createdChildId, sidA,
                        new Dictionary<string, DeviceGrant> { {
                            "client-abcdefghijklmnop",
                            new DeviceGrant { UseProfile = true, RemoteSignIn = true }
                        } });
                }
                catch (InvalidOperationException) { childRemoteDenied = true; }
                Dictionary<string, object> afterChildGrantDocument = jsonDocument(
                    File.ReadAllText(config));
                Dictionary<string, object> afterChildGrantClient = null;
                foreach (Dictionary<string, object> clientRecord in ClientsForTest(
                    afterChildGrantDocument))
                    if (Convert.ToString(clientRecord["id"]) ==
                        "client-abcdefghijklmnop") afterChildGrantClient = clientRecord;
                IList afterChildParentPermissions = null;
                if (afterChildGrantClient != null)
                {
                    Dictionary<string, object> rawGrants = dictionaryValue(
                        afterChildGrantClient, "profile_grants");
                    object parentGrantValue;
                    if (rawGrants.TryGetValue("profile-a", out parentGrantValue))
                        afterChildParentPermissions = parentGrantValue as IList;
                }
                Assert(childRemoteDenied && parentPermissionsBeforeChildEdit != null &&
                    afterChildParentPermissions != null &&
                    parentPermissionsBeforeChildEdit.Count == afterChildParentPermissions.Count &&
                    Convert.ToString(parentPermissionsBeforeChildEdit[0]) ==
                        Convert.ToString(afterChildParentPermissions[0]),
                    "Child device grants changed the parent grant or bypassed parent remote policy.");
                Dictionary<string, object> retriedChildResult =
                    store.ApplyChildProfileRequest(createChildRequest);
                Assert(createdChildId == Convert.ToString(dictionaryValue(
                        retriedChildResult, "child")["id"]) &&
                    Convert.ToBoolean(retriedChildResult["idempotent"]),
                    "Retrying an identical child create did not replay its result.");
                childMutationDraft["name"] = "Different child";
                bool createRequestReuseDenied = false;
                try { store.ApplyChildProfileRequest(createChildRequest); }
                catch (InvalidOperationException error) {
                    createRequestReuseDenied = error.Message == "request_id_reused";
                }
                Assert(createRequestReuseDenied,
                    "Reusing a child create request ID with a new payload was accepted.");
                Dictionary<string, object> deleteChildRequest =
                    new Dictionary<string, object> {
                        { "operation", "delete" },
                        { "parent_profile_id", "profile-a" },
                        { "child_profile_id", createdChildId },
                        { "request_id", "delete-child-retry" },
                        { "expected_revision", Convert.ToInt32(
                            createdChildResult["revision"]) }
                    };
                Dictionary<string, object> deletedChildResult =
                    store.ApplyChildProfileRequest(deleteChildRequest);
                Dictionary<string, object> retriedDeleteResult =
                    store.ApplyChildProfileRequest(deleteChildRequest);
                Assert(Convert.ToBoolean(deletedChildResult["ok"]) &&
                    Convert.ToBoolean(retriedDeleteResult["idempotent"]) &&
                    store.GetChildren("profile-a").Find(delegate(ProfileRecord value) {
                        return value.Id == createdChildId;
                    }) == null,
                    "Retrying a child delete was not idempotent or left the child present.");
                ProfileRecord parentBeforeOrphanCleanup = store.GetProfile("profile-a");
                Dictionary<string, object> orphanDocument = jsonDocument(
                    File.ReadAllText(config));
                Dictionary<string, object> orphanProfiles = dictionaryValue(
                    orphanDocument, "profiles");
                orphanProfiles["orphan-child"] = new Dictionary<string, object> {
                    { "id", "orphan-child" }, { "kind", "child" },
                    { "name", "Orphan" }, { "display_name", "Orphan" },
                    { "avatar_id", "" }, { "parent_profile_id", "missing-parent" },
                    { "enabled", false }, { "policy_revision", 3 },
                    { "allowed_game_keys", new object[0] },
                    { "schedule", childMutationDraft["schedule"] }
                };
                orphanDocument["child_time_usage"] = new Dictionary<string, object> {
                    { "keep", "usage-marker" } };
                File.WriteAllText(config, new JavaScriptSerializer().Serialize(
                    orphanDocument), new UTF8Encoding(false));
                store.RemoveChildProfileLocal("orphan-child");
                Dictionary<string, object> afterOrphanDocument = jsonDocument(
                    File.ReadAllText(config));
                Dictionary<string, object> afterOrphanParent = dictionaryValue(
                    dictionaryValue(afterOrphanDocument, "profiles"), "profile-a");
                Assert(!dictionaryValue(afterOrphanDocument, "profiles").ContainsKey("orphan-child") &&
                    Convert.ToString(afterOrphanParent["windows_account_sid"]) == parentBeforeOrphanCleanup.Sid &&
                    Convert.ToString(afterOrphanParent["profile_root"]) == parentBeforeOrphanCleanup.Root &&
                    Convert.ToString(dictionaryValue(afterOrphanDocument,
                        "child_time_usage")["keep"]) == "usage-marker",
                    "Orphan cleanup changed the parent Windows identity or child usage data.");
                raw = File.ReadAllText(config);
                Dictionary<string, object> withPin = jsonDocument(raw);
                Dictionary<string, object> pinProfile = dictionaryValue(
                    dictionaryValue(withPin, "profiles"), "profile-a");
                Dictionary<string, object> verifier = dictionaryValue(pinProfile, "pin_verifier");
                Assert(!raw.Contains("4826") && store.GetProfile("profile-a").AppPinRequired &&
                    Convert.ToInt32(verifier["version"]) == 1 &&
                    Convert.ToString(verifier["algorithm"]) == "pbkdf2-sha256" &&
                    Convert.FromBase64String(Convert.ToString(verifier["salt"])).Length == 16 &&
                    Convert.FromBase64String(Convert.ToString(verifier["digest"])).Length == 32,
                    "App PIN was not stored as a versioned salted verifier.");
                store.RemoveAppPin("profile-a", sidA);
                Assert(!store.GetProfile("profile-a").AppPinRequired &&
                    !File.ReadAllText(config).Contains("pin_verifier"),
                    "Removing the app PIN left verifier material in the profile.");
                bool immutable = false;
                try { store.UpdateProfile("profile-a", sidB, "Bad", true); }
                catch (InvalidOperationException) { immutable = true; }
                Assert(immutable, "SID mismatch was accepted.");

                ProfileRecord reserved = store.ReserveProfile(new LocalAccount {
                    Name = "B", QualifiedName = "PC\\B", Sid = sidB }, "Profile B");
                Assert(!reserved.RemoteSignInEnabled && !reserved.Enabled &&
                    GatewayRegistryStore.IsValidProfileId(reserved.Id),
                    "New profile did not fail closed.");
                List<DeviceRecord> newDevices = store.GetDevices(reserved.Id);
                Assert(newDevices.Count == 1 && !newDevices[0].UseProfile &&
                    !newDevices[0].RemoteSignIn, "New profile received an implicit client grant.");
                ManualResetEvent provisioningEntered = new ManualResetEvent(false);
                ManualResetEvent finishProvisioning = new ManualResetEvent(false);
                Exception provisioningFailure = null;
                Thread provisioningThread = new Thread(delegate() {
                    try
                    {
                        store.ProvisionAndFinalize(reserved, delegate() {
                            provisioningEntered.Set();
                            finishProvisioning.WaitOne(2000);
                        });
                    }
                    catch (Exception ex) { provisioningFailure = ex; }
                });
                provisioningThread.Start();
                Assert(provisioningEntered.WaitOne(1000), "Provisioning race test did not enter its locked phase.");
                bool concurrentRemoveBlocked = false;
                try { store.PrepareRemoval(reserved); }
                catch (TimeoutException) { concurrentRemoveBlocked = true; }
                finally { finishProvisioning.Set(); }
                provisioningThread.Join(2000);
                Assert(concurrentRemoveBlocked && provisioningFailure == null &&
                    store.GetProfile(reserved.Id).Enabled,
                    "Concurrent removal crossed the reservation task/publication lock.");
                provisioningEntered.Dispose();
                finishProvisioning.Dispose();
                bool duplicate = false;
                try { store.ReserveProfile(new LocalAccount {
                    Name = "B2", QualifiedName = "PC\\B2", Sid = sidB }, "Duplicate"); }
                catch (InvalidOperationException) { duplicate = true; }
                Assert(duplicate, "A second profile for the same SID was accepted.");

                ProfileRecord competingRemoval = store.GetProfile(reserved.Id);
                string reservationNonce = store.PrepareRemoval(reserved);
                bool competingPrepareDenied = false;
                try { store.PrepareRemoval(competingRemoval); }
                catch (InvalidOperationException) { competingPrepareDenied = true; }
                Assert(competingPrepareDenied,
                    "A stale concurrent removal snapshot adopted another operation's tombstone.");
                ProfileRecord pendingReservation = store.GetProfile(reserved.Id);
                Assert(!String.IsNullOrWhiteSpace(reservationNonce) &&
                    reservationNonce == pendingReservation.DeletionNonce &&
                    pendingReservation.DeletionGeneration > 0 && !pendingReservation.Enabled,
                    "A failed add reservation did not become a durable disabled tombstone.");
                int blockedMutations = 0;
                try { store.FinalizeProfile(reserved.Id, sidB); }
                catch (InvalidOperationException) { blockedMutations++; }
                try { store.UpdateProfile(reserved.Id, sidB, "Re-enabled", true); }
                catch (InvalidOperationException) { blockedMutations++; }
                try { store.SetRemoteSignInEnabled(reserved.Id, sidB, true); }
                catch (InvalidOperationException) { blockedMutations++; }
                try
                {
                    store.UpdateGrants(reserved.Id, sidB,
                        new Dictionary<string, DeviceGrant> { {
                            "client-abcdefghijklmnop",
                            new DeviceGrant { UseProfile = true, RemoteSignIn = false }
                        } });
                }
                catch (InvalidOperationException) { blockedMutations++; }
                Assert(blockedMutations == 4,
                    "A tombstoned profile accepted a finalize, edit, remote, or grant mutation.");
                pendingReservation.DeletionNonce = "stale-nonce";
                bool staleTombstoneDenied = false;
                try { store.RemoveProfile(pendingReservation); }
                catch (InvalidOperationException) { staleTombstoneDenied = true; }
                Assert(staleTombstoneDenied,
                    "Removal accepted a stale deletion tombstone nonce.");
                pendingReservation = store.GetProfile(reserved.Id);
                long reservationGeneration = pendingReservation.DeletionGeneration;
                Assert(store.PrepareRemoval(pendingReservation) == reservationNonce &&
                    pendingReservation.DeletionGeneration == reservationGeneration,
                    "Retrying removal replaced its durable tombstone generation or nonce.");
                store.RemoveProfile(pendingReservation);

                List<DeviceRecord> devices = store.GetDevices("profile-a");
                Assert(devices.Count == 1 && devices[0].LastSeenAt == 200 &&
                    devices[0].SafeId != devices[0].Id,
                    "Activity merge or safe client projection failed.");

                bool remoteDeniedByDefault = false;
                try
                {
                    store.UpdateGrants("profile-a", sidA,
                        new Dictionary<string, DeviceGrant> { {
                            devices[0].Id, new DeviceGrant { UseProfile = true, RemoteSignIn = true }
                        } });
                }
                catch (InvalidOperationException) { remoteDeniedByDefault = true; }
                Assert(remoteDeniedByDefault,
                    "A remote sign-in grant was accepted before profile credential enablement.");
                store.SetAppPin("profile-a", sidA, "4826");
                store.SetRemoteSignInEnabled("profile-a", sidA, true);
                store.UpdateGrants("profile-a", sidA,
                    new Dictionary<string, DeviceGrant> { {
                        devices[0].Id, new DeviceGrant {
                            UseProfile = true, RemoteSignIn = true, ManageChildren = true }
                    } });
                devices = store.GetDevices("profile-a");
                Assert(devices[0].UseProfile && devices[0].RemoteSignIn && devices[0].ManageChildren,
                    "Explicit profile grants were not persisted.");
                store.SetRemoteSignInEnabled("profile-a", sidA, false);
                devices = store.GetDevices("profile-a");
                Assert(devices[0].UseProfile && !devices[0].RemoteSignIn && devices[0].ManageChildren,
                    "Disabling remote sign-in dropped the unrelated child-management grant.");
                bool lockBlocked = false;
                using (RegistryFileLock held = RegistryFileLock.Acquire(store.RegistryLockPath, 250))
                {
                    try { store.UpdateProfile("profile-a", sidA, "Blocked", true); }
                    catch (TimeoutException) { lockBlocked = true; }
                }
                Assert(lockBlocked, "Gateway byte lock was not honored.");
                store.RemoveClient(devices[0].Id);
                Assert(store.GetDevices("profile-a").Count == 0 &&
                    !File.ReadAllText(config).Contains("SECRET_HASH"),
                    "Removing a paired device did not revoke its registry record.");
                Assert(Directory.GetFiles(gateway, "*.tmp").Length == 0,
                    "Atomic writer left a temporary file.");
                Console.WriteLine("MoonWaker Host Configurator registry tests passed.");
                TestProfileProvisioning(root);
                Console.WriteLine("MoonWaker Host Configurator profile provisioning tests passed.");
                TestRemoveArtifacts(store);
                Console.WriteLine("MoonWaker Host Configurator profile removal tests passed.");
            }
            finally
            {
                if (Directory.Exists(root)) Directory.Delete(root, true);
            }
        }

        // Removal waits for the profile's Agent (a loopback listener here) to stop answering and
        // for its files to be released; past the limit the directory stays and the error says so.
        private static void TestRemoveArtifacts(GatewayRegistryStore store)
        {
            System.Net.Sockets.TcpListener agent = new System.Net.Sockets.TcpListener(
                System.Net.IPAddress.Loopback, 0);
            agent.Start();
            int port = ((System.Net.IPEndPoint)agent.LocalEndpoint).Port;
            string directory = Path.Combine(store.ProfilesRoot, "removed");
            Directory.CreateDirectory(Path.Combine(directory, "game-provider", "logs"));
            string log = Path.Combine(directory, "game-provider", "logs", "provider-diagnostics.jsonl");
            ProfileRecord profile = new ProfileRecord {
                Id = "removed", Root = directory, DeletionNonce = "nonce",
                GameProviderEndpoint = "http://127.0.0.1:" + port, DiscordEndpoint = "" };
            File.WriteAllText(log, "{}");
            FileStream held = null;
            try
            {
                bool refused = false;
                try { ProfileProvisioner.RemoveArtifacts(store, profile, TimeSpan.FromSeconds(1)); }
                catch (InvalidOperationException error) { refused = error.Message.Contains("oznaczony do usunięcia"); }
                Assert(refused && File.Exists(log), "Removal did not wait for a running Agent.");
                agent.Stop();
                held = new FileStream(log, FileMode.Open, FileAccess.Write, FileShare.None);
                refused = false;
                try { ProfileProvisioner.RemoveArtifacts(store, profile, TimeSpan.FromSeconds(1)); }
                catch (InvalidOperationException) { refused = true; }
                Assert(refused && File.Exists(log), "Removal did not wait for files still in use.");
                // Released while removal waits: it finishes.
                FileStream release = held;
                Thread releaser = new Thread(delegate() { Thread.Sleep(700); release.Dispose(); });
                releaser.Start();
                Stopwatch clock = Stopwatch.StartNew();
                ProfileProvisioner.RemoveArtifacts(store, profile);
                releaser.Join();
                Assert(!Directory.Exists(directory) && clock.ElapsedMilliseconds >= 600 &&
                    clock.ElapsedMilliseconds < 5000, "Removal did not retry until the files were released.");
            }
            finally
            {
                if (held != null) held.Dispose();
                agent.Stop();
            }
        }

        // The data directory of a new profile under a temporary installation. The ACL walk needs
        // an elevated token (owner Administrators); without one only its descriptor is checked.
        private static void TestProfileProvisioning(string root)
        {
            string source = Path.Combine(root, "bridge-source");
            Directory.CreateDirectory(Path.Combine(source, "playnite"));
            File.WriteAllText(Path.Combine(source, "playnite", "config.example.json"),
                "{\"listen_host\":\"127.0.0.1\",\"listen_port\":8780,\"epic_legendary_enabled\":false," +
                "\"playnite_enabled\":true,\"streamed_display\":\"\"}");
            string sid = WindowsIdentity.GetCurrent().User.Value;
            ProfileProvisioning provisioning = new ProfileProvisioning {
                ProfilesRoot = Path.Combine(root, "profiles"), BridgeSource = source,
                ProfileId = "p-one", OwnerSid = sid, DiscordPort = 8800, GameProviderPort = 8801 };
            string profile = provisioning.CreateLayout();
            Assert(profile == Path.Combine(root, "profiles", "p-one"), "Profile root is not <profiles>\\<id>.");
            Dictionary<string, object> provider = jsonDocument(File.ReadAllText(
                Path.Combine(profile, "game-provider", "config.json")));
            Assert(Convert.ToInt32(provider["listen_port"]) == 8801 &&
                (bool)provider["epic_legendary_enabled"] && !(bool)provider["playnite_enabled"] &&
                (string)provider["legendary_path"] == "" && (string)provider["listen_host"] == "127.0.0.1",
                "The Agent configuration was not seeded from its example with the profile's port.");
            Assert(!Directory.Exists(Path.Combine(profile, "discord")),
                "A profile got Discord data although Discord is not installed.");
            Assert(Directory.GetFiles(profile, "*", SearchOption.AllDirectories).Length == 1,
                "Provisioning copied code or other files into the profile.");
            bool refused = false;
            try { provisioning.CreateLayout(); }
            catch (InvalidOperationException) { refused = true; }
            Assert(refused, "Provisioning reused an existing profile root.");

            Directory.CreateDirectory(Path.Combine(source, "discord"));
            File.WriteAllText(Path.Combine(source, "discord", "DiscordBridge.ps1"), "");
            File.WriteAllText(Path.Combine(source, "discord", "discord_bridge_config.example.json"),
                "{\"client_id\":\"\",\"port\":8765,\"scopes\":[\"rpc\"]}");
            provisioning.ProfileId = "p-two";
            provisioning.GameProviderPort = 8800;
            refused = false;
            try { provisioning.CreateLayout(); }
            catch (InvalidOperationException) { refused = true; }
            Assert(refused && !Directory.Exists(Path.Combine(root, "profiles", "p-two")),
                "Discord and the Agent were given one port.");
            provisioning.GameProviderPort = 1000;
            refused = false;
            try { provisioning.CreateLayout(); }
            catch (InvalidOperationException) { refused = true; }
            Assert(refused, "A port below 1024 was accepted.");
            provisioning.GameProviderPort = 8802;
            profile = provisioning.CreateLayout();
            Dictionary<string, object> discord = jsonDocument(File.ReadAllText(
                Path.Combine(profile, "discord", "discord_bridge_config.json")));
            Assert(Convert.ToInt32(discord["port"]) == 8800 && discord.ContainsKey("scopes"),
                "Discord configuration was not seeded with the profile's port.");

            DirectorySecurity security = (DirectorySecurity)provisioning.Security(true);
            Assert(security.AreAccessRulesProtected &&
                security.GetOwner(typeof(SecurityIdentifier)).Value == "S-1-5-32-544",
                "The profile ACL is not protected or not owned by Administrators.");
            Dictionary<string, FileSystemRights> rights = new Dictionary<string, FileSystemRights>();
            foreach (FileSystemAccessRule rule in security.GetAccessRules(true, false, typeof(SecurityIdentifier)))
            {
                Assert(rule.AccessControlType == AccessControlType.Allow && rule.InheritanceFlags ==
                    (InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit),
                    "A profile directory rule is not an inherited allow rule.");
                rights[rule.IdentityReference.Value] = rule.FileSystemRights;
            }
            Assert(rights.Count == 3 && rights["S-1-5-18"] == FileSystemRights.FullControl &&
                rights["S-1-5-32-544"] == FileSystemRights.FullControl &&
                (rights[sid] & FileSystemRights.Modify) == FileSystemRights.Modify &&
                (rights[sid] & (FileSystemRights.ChangePermissions | FileSystemRights.TakeOwnership)) == 0,
                "The profile ACL is not SYSTEM/Administrators full and owner Modify.");
            if (new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator))
            {
                provisioning.ProtectTree(profile);
                foreach (string path in Directory.GetFileSystemEntries(profile, "*", SearchOption.AllDirectories))
                    Assert(File.GetAccessControl(path).AreAccessRulesProtected &&
                        File.GetAccessControl(path).GetOwner(typeof(SecurityIdentifier)).Value == "S-1-5-32-544",
                        "A profile element kept its inherited ACL: " + path);
            }
        }

        private static void Assert(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException(message);
        }

        private static Dictionary<string, object> jsonDocument(string value)
        {
            return new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(value);
        }

        private static Dictionary<string, object> dictionaryValue(
            Dictionary<string, object> source, string key)
        {
            return (Dictionary<string, object>)source[key];
        }

        private static IEnumerable<Dictionary<string, object>> ClientsForTest(
            Dictionary<string, object> document)
        {
            IList values = document["clients"] as IList;
            if (values == null) yield break;
            foreach (object value in values)
            {
                Dictionary<string, object> client = value as Dictionary<string, object>;
                if (client != null) yield return client;
            }
        }
    }

    // The computer's one Vibepollo token for the Gateway. Only this elevated process writes
    // gateway\vibepollo-api.json (in place, keeping the installer's ACL: Administrators, SYSTEM
    // and read for the Gateway service). Every call pins the certificate Vibepollo keeps in its
    // configuration directory, the same rule the Gateway applies.
    internal static class VibepolloMachineApi
    {
        internal const string DefaultBaseUrl = "https://127.0.0.1:47990";
        private const int MaxLocalFileBytes = 1024 * 1024;

        internal static string LocalBaseUrl(string value)
        {
            Uri uri;
            if (!Uri.TryCreate((value ?? "").Trim(), UriKind.Absolute, out uri) ||
                uri.Scheme != Uri.UriSchemeHttps || !String.IsNullOrEmpty(uri.UserInfo) ||
                !(uri.Host == "127.0.0.1" || uri.Host == "localhost") ||
                uri.AbsolutePath != "/" || !String.IsNullOrEmpty(uri.Query) ||
                !String.IsNullOrEmpty(uri.Fragment))
                throw new InvalidOperationException("API Vibepollo musi używać HTTPS na 127.0.0.1 lub localhost.");
            return "https://" + uri.Host + ":" + uri.Port;
        }

        internal static string SettingsPath(GatewayRegistryStore store)
        {
            return Path.Combine(store.GatewayDirectory, "vibepollo-api.json");
        }

        internal static Dictionary<string, object> ReadSettings(GatewayRegistryStore store)
        {
            try
            {
                return new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(
                    File.ReadAllText(SettingsPath(store))) ?? new Dictionary<string, object>();
            }
            catch { return new Dictionary<string, object>(); }
        }

        internal static void WriteSettings(GatewayRegistryStore store, string baseUrl, string token)
        {
            string path = SettingsPath(store);
            if (!File.Exists(path))
                throw new InvalidOperationException(
                    "Brak chronionego pliku tokenu Vibepollo. Uruchom ponownie instalator MoonWaker Host.");
            byte[] content = new UTF8Encoding(false).GetBytes(new JavaScriptSerializer().Serialize(
                new Dictionary<string, object> { { "base_url", baseUrl }, { "token", token } }));
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Write, FileShare.Read))
            {
                stream.SetLength(0);
                stream.Write(content, 0, content.Length);
            }
        }

        private static bool IsReparsePoint(string path)
        {
            return (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;
        }

        internal static string ConfigDirectory()
        {
            Dictionary<string, string> roots = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (RegistryView view in new RegistryView[] { RegistryView.Registry64, RegistryView.Registry32 })
            {
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                using (RegistryKey uninstall = machine.OpenSubKey(
                    @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall", false))
                {
                    if (uninstall == null) continue;
                    foreach (string name in uninstall.GetSubKeyNames())
                    {
                        using (RegistryKey entry = uninstall.OpenSubKey(name, false))
                        {
                            if (entry == null) continue;
                            string display = Convert.ToString(entry.GetValue("DisplayName", ""));
                            string location = Convert.ToString(entry.GetValue("InstallLocation", "")).Trim();
                            if (display.IndexOf("vibepollo", StringComparison.OrdinalIgnoreCase) < 0 ||
                                location.Length == 0) continue;
                            try
                            {
                                string root = Path.GetFullPath(location).TrimEnd('\\', '/');
                                if (Directory.Exists(root) && !IsReparsePoint(root)) roots[root] = root;
                            }
                            catch { }
                        }
                    }
                }
            }
            if (roots.Count != 1)
                throw new InvalidOperationException("Nie znaleziono jednoznacznej instalacji Vibepollo.");
            string config = Path.Combine(new List<string>(roots.Keys)[0], "config");
            if (!Directory.Exists(config) || IsReparsePoint(config))
                throw new InvalidOperationException("Katalog konfiguracji Vibepollo jest niedostępny.");
            return config;
        }

        private static byte[] ReadLocalFile(string path)
        {
            FileInfo info = new FileInfo(path);
            if (!info.Exists || IsReparsePoint(path) || info.Length > MaxLocalFileBytes)
                throw new InvalidOperationException("Plik Vibepollo jest niedostępny: " + path);
            return File.ReadAllBytes(path);
        }

        internal static string ConfiguredFile(string configDirectory, string key, string fallback)
        {
            string value = fallback;
            string conf = Path.Combine(configDirectory, "sunshine.conf");
            if (File.Exists(conf))
            {
                List<string> values = new List<string>();
                foreach (string rawLine in Encoding.UTF8.GetString(ReadLocalFile(conf))
                    .TrimStart('﻿').Split('\r', '\n'))
                {
                    string line = System.Text.RegularExpressions.Regex.Replace(rawLine, "#.*$", "").Trim();
                    System.Text.RegularExpressions.Match match = System.Text.RegularExpressions.Regex.Match(
                        line, "^" + System.Text.RegularExpressions.Regex.Escape(key) + @"\s*=\s*(.*?)\s*$");
                    if (!match.Success) continue;
                    string item = match.Groups[1].Value.Trim();
                    if (item.Length >= 2 && item[0] == item[item.Length - 1] && (item[0] == '"' || item[0] == '\''))
                        item = item.Substring(1, item.Length - 2);
                    if (item.Length > 0 && !values.Contains(item)) values.Add(item);
                }
                if (values.Count > 1)
                    throw new InvalidOperationException("sunshine.conf podaje kilka wartości " + key + ".");
                if (values.Count == 1) value = values[0];
            }
            string path = Path.GetFullPath(Path.IsPathRooted(value) ? value : Path.Combine(configDirectory, value));
            if (!path.StartsWith(configDirectory.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(key + " wskazuje plik poza konfiguracją Vibepollo.");
            return path;
        }

        // Read access for the Gateway service to the files it reads from Vibepollo's
        // configuration, found by the rules vibepollo_client.py applies: sunshine.conf, the
        // state file and the HTTPS certificate it names, all inside the configuration
        // directory and free of reparse points. The directories down to each file get
        // traverse only, never listing: credentials also holds Vibepollo's private key.
        // Returns the files that do not exist yet.
        internal static List<string> GrantGatewayReadAccess()
        {
            string config = ConfigDirectory();
            SecurityIdentifier gateway = (SecurityIdentifier)new NTAccount(@"NT SERVICE\MoonWakerGateway")
                .Translate(typeof(SecurityIdentifier));
            List<string> missing = new List<string>();
            foreach (string file in new string[] {
                Path.Combine(config, "sunshine.conf"),
                ConfiguredFile(config, "file_state", "sunshine_state.json"),
                ConfiguredFile(config, "cert", "credentials/cacert.pem") })
            {
                if (!File.Exists(file)) { missing.Add(file); continue; }
                List<string> directories = new List<string>();
                for (string directory = Path.GetDirectoryName(file);
                    !String.Equals(directory, config, StringComparison.OrdinalIgnoreCase);
                    directory = Path.GetDirectoryName(directory))
                {
                    if (IsReparsePoint(directory))
                        throw new InvalidOperationException("Katalog Vibepollo jest punktem reparse: " + directory);
                    directories.Add(directory);
                }
                directories.Add(config);
                if (IsReparsePoint(file))
                    throw new InvalidOperationException("Plik Vibepollo jest punktem reparse: " + file);
                foreach (string directory in directories)
                {
                    DirectorySecurity security = Directory.GetAccessControl(directory, AccessControlSections.Access);
                    security.AddAccessRule(new FileSystemAccessRule(gateway,
                        FileSystemRights.Traverse | FileSystemRights.ReadAttributes, AccessControlType.Allow));
                    Directory.SetAccessControl(directory, security);
                }
                FileSecurity fileSecurity = File.GetAccessControl(file, AccessControlSections.Access);
                fileSecurity.AddAccessRule(new FileSystemAccessRule(gateway, FileSystemRights.Read,
                    AccessControlType.Allow));
                File.SetAccessControl(file, fileSecurity);
            }
            return missing;
        }

        internal static string PinnedCertificateSha256()
        {
            string configDirectory = ConfigDirectory();
            string pem = Encoding.ASCII.GetString(ReadLocalFile(
                ConfiguredFile(configDirectory, "cert", "credentials/cacert.pem")));
            System.Text.RegularExpressions.Match match = System.Text.RegularExpressions.Regex.Match(pem,
                @"-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----");
            if (!match.Success) throw new InvalidOperationException("Certyfikat Vibepollo jest nieczytelny.");
            byte[] der = Convert.FromBase64String(
                System.Text.RegularExpressions.Regex.Replace(match.Groups[1].Value, @"\s", ""));
            return Sha256Hex(der);
        }

        private static string Sha256Hex(byte[] value)
        {
            using (SHA256 sha = SHA256.Create())
                return BitConverter.ToString(sha.ComputeHash(value)).Replace("-", "").ToLowerInvariant();
        }

        internal static string Request(string baseUrl, string method, string path, string authorization,
            object body, string pinnedSha256)
        {
            System.Net.ServicePointManager.SecurityProtocol = System.Net.SecurityProtocolType.Tls12;
            System.Net.HttpWebRequest request = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(baseUrl + path);
            request.Method = method;
            request.Timeout = 15000;
            request.ReadWriteTimeout = 15000;
            request.Accept = "application/json";
            request.Headers[System.Net.HttpRequestHeader.Authorization] = authorization;
            // Authentication is this pin: the self-signed certificate names no host and chains to no CA.
            request.ServerCertificateValidationCallback = delegate(object sender,
                System.Security.Cryptography.X509Certificates.X509Certificate certificate,
                System.Security.Cryptography.X509Certificates.X509Chain chain,
                System.Net.Security.SslPolicyErrors errors)
            {
                return certificate != null && Sha256Hex(certificate.GetRawCertData()) == pinnedSha256;
            };
            try
            {
                // The connection, and so the certificate check, starts here when there is a body.
                if (body != null)
                {
                    byte[] content = new UTF8Encoding(false).GetBytes(new JavaScriptSerializer().Serialize(body));
                    request.ContentType = "application/json";
                    request.ContentLength = content.Length;
                    using (Stream stream = request.GetRequestStream()) stream.Write(content, 0, content.Length);
                }
                using (System.Net.WebResponse response = request.GetResponse())
                using (StreamReader reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
                    return reader.ReadToEnd();
            }
            catch (System.Net.WebException error)
            {
                System.Net.HttpWebResponse response = error.Response as System.Net.HttpWebResponse;
                if (response != null)
                    throw new InvalidOperationException("Vibepollo odrzuciło żądanie " + path +
                        " (HTTP " + (int)response.StatusCode + ").");
                if (error.Status == System.Net.WebExceptionStatus.TrustFailure)
                    throw new InvalidOperationException(
                        "Certyfikat Vibepollo nie zgadza się z plikiem w jego konfiguracji.");
                throw new InvalidOperationException("API Vibepollo nie odpowiada: " + error.Message);
            }
        }

        internal static string CreateToken(string baseUrl, string username, string password, string pin)
        {
            Dictionary<string, object> scopes = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(
                File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "vibepollo-token-scopes.json")));
            string basic = "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(username + ":" + password));
            string raw = Request(baseUrl, "POST", "/api/token", basic,
                new Dictionary<string, object> { { "scopes", scopes["scopes"] } }, pin);
            string token = null;
            try
            {
                Dictionary<string, object> document =
                    new JavaScriptSerializer().DeserializeObject(raw) as Dictionary<string, object>;
                if (document != null && document.ContainsKey("token")) token = document["token"] as string;
            }
            catch (ArgumentException) { }
            if (String.IsNullOrWhiteSpace(token)) throw new InvalidOperationException("Vibepollo nie zwróciło tokenu API.");
            return token.Trim();
        }

        // "Pobierz / odnów token": a token from /api/token with the panel's administrator login
        // (or a pasted one), checked with /api/apps and written for the Gateway; every save also
        // repairs the Gateway's read access (e.g. after Vibepollo regenerated its certificate).
        // Returns the Vibepollo files that do not exist yet.
        internal static List<string> SaveToken(GatewayRegistryStore store, VibepolloInput input)
        {
            string problem = input.Problem();
            if (problem != null) throw new InvalidOperationException(problem);
            string baseUrl = LocalBaseUrl(input.Url);
            string pin = PinnedCertificateSha256();
            string value = input.Automatic
                ? CreateToken(baseUrl, input.Username.Trim(), new string(input.Password), pin)
                : new string(input.Token).Trim();
            Request(baseUrl, "GET", "/api/apps", "Bearer " + value, null, pin);
            WriteSettings(store, baseUrl, value);
            return GrantGatewayReadAccess();
        }
    }

    // What "Pobierz / odnów token" asks for. It is typed only in the helper's elevated window:
    // the panel's administrator login is never in Host Control or in the change channel.
    internal sealed class VibepolloInput
    {
        internal bool Automatic = true;
        internal string Url = VibepolloMachineApi.DefaultBaseUrl;
        internal string Username = "";
        internal char[] Password = new char[0];
        internal char[] Token = new char[0];

        // Null when the input can be used.
        internal string Problem()
        {
            try { VibepolloMachineApi.LocalBaseUrl(Url); }
            catch (InvalidOperationException error) { return error.Message; }
            if (Automatic && (Username.Trim().Length == 0 || Password.Length == 0))
                return "Podaj login i hasło administratora Vibepollo albo wklej istniejący token.";
            if (!Automatic && !System.Text.RegularExpressions.Regex.IsMatch(
                    new string(Token).Trim(), "^[\\x21-\\x7e]{1,4096}$"))
                return "Wklej token API Vibepollo albo włącz jego automatyczne pobranie.";
            return null;
        }

        internal void Clear()
        {
            Array.Clear(Password, 0, Password.Length);
            Array.Clear(Token, 0, Token.Length);
        }
    }
}
