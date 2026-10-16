using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using MoonWaker.HostControl;

// The elevated helper's half of "UAC przy Zapisz": a change set from the unelevated Host Control
// is untrusted. It is parsed strictly, validated in full against the registry revision Host
// Control showed (by running every change on a copy of that registry), shown in the helper's
// own window without secrets, and applied only after the user confirms there.
namespace MoonWaker.HostConfigurator
{
    internal sealed class Change
    {
        internal string Type;
        internal string ProfileId = "", AccountSid = "", ClientId = "", ChildProfileId = "";
        internal string DisplayName = "", Action = "", Pin = "";
        internal bool Enabled, UseProfile, RemoteSignIn, ManageChildren;
        internal int ExpectedRevision;
        // Normalized by GatewayRegistryStore.NormalizeChildDraft.
        internal Dictionary<string, object> Draft;
    }

    internal sealed class ChangeSet
    {
        internal const int MaxChanges = 256;
        private static readonly Dictionary<string, string[]> Fields = new Dictionary<string, string[]> {
            { "profile_add", new[] { "account_sid", "display_name" } },
            { "profile_edit", new[] { "profile_id", "account_sid", "display_name", "enabled" } },
            { "pin_set", new[] { "profile_id", "account_sid", "pin" } },
            { "pin_remove", new[] { "profile_id", "account_sid" } },
            { "remote_sign_in", new[] { "profile_id", "account_sid", "action" } },
            { "profile_remove", new[] { "profile_id", "account_sid" } },
            { "device_grant", new[] { "client_id", "profile_id", "account_sid", "use_profile",
                "remote_sign_in", "manage_children" } },
            { "client_remove", new[] { "client_id" } },
            { "child_save", new[] { "profile_id", "child_profile_id", "expected_revision", "draft" } },
            { "child_remove", new[] { "profile_id", "child_profile_id", "expected_revision" } },
            { "vibepollo_token", new string[0] } };
        // Several steps beyond one registry write (UAC, Broker, files, Vibepollo).
        internal static readonly HashSet<string> Compound = new HashSet<string> {
            "profile_add", "profile_remove", "remote_sign_in", "vibepollo_token" };
        // A set of its own: compound changes, and child requests with their own revision.
        private static readonly HashSet<string> Alone = new HashSet<string> {
            "profile_add", "profile_remove", "remote_sign_in", "vibepollo_token", "child_save", "child_remove" };

        internal string RegistryRevision = "";
        internal readonly List<Change> Changes = new List<Change>();

        internal bool IsCompound { get { return Compound.Contains(Changes[0].Type); } }

        // Throws InvalidDataException or InvalidOperationException with the reason for the user.
        internal static ChangeSet Parse(byte[] raw)
        {
            Dictionary<string, object> message;
            try
            {
                JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = ChangeChannel.MaxRequestBytes };
                message = json.DeserializeObject(new UTF8Encoding(false, true).GetString(raw)) as Dictionary<string, object>;
            }
            catch (Exception) { message = null; }
            if (message == null) throw new InvalidDataException("Zestaw zmian nie jest prawidłowym obiektem JSON.");
            Keys(message, "protocol", "registry_revision", "changes");
            if (Text(message, "protocol") != ChangeChannel.Protocol)
                throw new InvalidDataException("Nieznany protokół zestawu zmian.");
            ChangeSet set = new ChangeSet { RegistryRevision = Text(message, "registry_revision") };
            object[] changes = message["changes"] as object[];
            if (changes == null || changes.Length == 0 || changes.Length > MaxChanges)
                throw new InvalidDataException("Zestaw zmian musi zawierać od 1 do " + MaxChanges + " zmian.");
            foreach (object item in changes) set.Changes.Add(ParseChange(item as Dictionary<string, object>));

            bool registry = set.Changes.Exists(delegate(Change change) { return change.Type != "vibepollo_token"; });
            if (registry ? !Regex.IsMatch(set.RegistryRevision, "^[0-9a-f]{64}$") : set.RegistryRevision.Length != 0)
                throw new InvalidDataException("Zestaw zmian ma nieprawidłową rewizję rejestru.");
            if (set.Changes.Count > 1 && set.Changes.Exists(delegate(Change change) { return Alone.Contains(change.Type); }))
                throw new InvalidDataException("Ta zmiana musi być zapisywana osobno.");
            HashSet<string> targets = new HashSet<string>(StringComparer.Ordinal);
            HashSet<string> removedClients = new HashSet<string>(StringComparer.Ordinal);
            Dictionary<string, string> grantSids = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (Change change in set.Changes)
            {
                if (change.Type == "client_remove") removedClients.Add(change.ClientId);
                if (change.Type == "device_grant")
                {
                    string sid;
                    if (grantSids.TryGetValue(change.ProfileId, out sid) &&
                        !String.Equals(sid, change.AccountSid, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("Uprawnienia jednego profilu wskazują różne konta Windows.");
                    grantSids[change.ProfileId] = change.AccountSid;
                }
                string target = change.Type == "device_grant" ? "grant\n" + change.ClientId + "\n" + change.ProfileId
                    : change.Type == "client_remove" ? "client\n" + change.ClientId
                    : change.Type.StartsWith("pin_") ? "pin\n" + change.ProfileId
                    : change.Type + "\n" + change.ProfileId;
                if (!targets.Add(target)) throw new InvalidDataException("Zestaw zmian zmienia to samo dwa razy.");
            }
            foreach (Change change in set.Changes)
                if (change.Type == "device_grant" && removedClients.Contains(change.ClientId))
                    throw new InvalidDataException("Zestaw zmian nadaje uprawnienia urządzeniu, którego parowanie cofa.");
            return set;
        }

        private static Change ParseChange(Dictionary<string, object> item)
        {
            if (item == null) throw new InvalidDataException("Zmiana nie jest obiektem JSON.");
            string type = Text(item, "type");
            string[] fields;
            if (!Fields.TryGetValue(type, out fields)) throw new InvalidDataException("Nieznany rodzaj zmiany.");
            List<string> expected = new List<string>(fields) { "type" };
            Keys(item, expected.ToArray());
            Change change = new Change { Type = type };
            foreach (string field in fields)
            {
                switch (field)
                {
                    case "profile_id":
                        change.ProfileId = Text(item, field);
                        if (!GatewayRegistryStore.IsValidProfileId(change.ProfileId))
                            throw new InvalidDataException("Zmiana wskazuje nieprawidłowy profil.");
                        break;
                    case "account_sid":
                        change.AccountSid = Text(item, field);
                        // A profile from before SID mapping can only be removed.
                        if (!GatewayRegistryStore.IsSid(change.AccountSid) &&
                            !(type == "profile_remove" && change.AccountSid.Length == 0))
                            throw new InvalidDataException("Zmiana wskazuje nieprawidłowe konto Windows.");
                        break;
                    case "display_name":
                        change.DisplayName = Text(item, field);
                        GatewayRegistryStore.ValidateDisplayName(change.DisplayName);
                        change.DisplayName = change.DisplayName.Trim();
                        break;
                    case "pin":
                        change.Pin = Text(item, field);
                        if (!Regex.IsMatch(change.Pin, "^[0-9]{4}$"))
                            throw new InvalidDataException("PIN aplikacji MoonWaker musi mieć dokładnie cztery cyfry.");
                        break;
                    case "action":
                        change.Action = Text(item, field);
                        if (change.Action != "set_password" && change.Action != "test" && change.Action != "disable")
                            throw new InvalidDataException("Nieznana operacja zdalnego logowania.");
                        break;
                    case "client_id":
                        change.ClientId = Text(item, field);
                        if (change.ClientId.Length == 0 || change.ClientId.Length > 128 ||
                            Regex.IsMatch(change.ClientId, "[\\x00-\\x1f\\x7f]"))
                            throw new InvalidDataException("Zmiana wskazuje nieprawidłowe urządzenie.");
                        break;
                    case "child_profile_id":
                        change.ChildProfileId = Text(item, field);
                        if (!(type == "child_save" && change.ChildProfileId.Length == 0) &&
                            !GatewayRegistryStore.IsValidProfileId(change.ChildProfileId))
                            throw new InvalidDataException("Zmiana wskazuje nieprawidłowy profil dziecka.");
                        break;
                    case "expected_revision":
                        if (!(item[field] is int) || (int)item[field] < 0)
                            throw new InvalidDataException("Zmiana ma nieprawidłową rewizję zasad dzieci.");
                        change.ExpectedRevision = (int)item[field];
                        break;
                    case "draft":
                        change.Draft = GatewayRegistryStore.NormalizeChildDraft(StrictDraft(item[field]));
                        break;
                    default:
                        if (!(item[field] is bool)) throw new InvalidDataException("Zmiana ma nieprawidłową wartość " + field + ".");
                        bool value = (bool)item[field];
                        if (field == "enabled") change.Enabled = value;
                        else if (field == "use_profile") change.UseProfile = value;
                        else if (field == "remote_sign_in") change.RemoteSignIn = value;
                        else change.ManageChildren = value;
                        break;
                }
            }
            return change;
        }

        // The child editor's draft has exactly these keys at every level; the values are
        // checked by NormalizeChildDraft.
        private static Dictionary<string, object> StrictDraft(object value)
        {
            Dictionary<string, object> draft = Object(value);
            Keys(draft, "name", "avatar_id", "enabled", "schedule");
            Dictionary<string, object> schedule = Object(draft["schedule"]);
            Keys(schedule, "weekdays");
            Dictionary<string, object> weekdays = Object(schedule["weekdays"]);
            Keys(weekdays, ChangeApplier.Days);
            foreach (string day in ChangeApplier.Days)
                Keys(Object(weekdays[day]), "enabled", "start_minute", "end_minute", "daily_limit_seconds");
            return draft;
        }

        private static Dictionary<string, object> Object(object value)
        {
            Dictionary<string, object> result = value as Dictionary<string, object>;
            if (result == null) throw new InvalidDataException("Profil dziecka ma nieprawidłową strukturę.");
            return result;
        }

        // Exactly these keys: an unknown field is an error, not something to ignore.
        private static void Keys(Dictionary<string, object> item, params string[] names)
        {
            if (item.Count != names.Length) throw new InvalidDataException("Zmiana ma nieoczekiwane pola.");
            foreach (string name in names)
                if (!item.ContainsKey(name)) throw new InvalidDataException("Zmianie brakuje pola " + name + ".");
        }

        private static string Text(Dictionary<string, object> item, string key)
        {
            object value;
            if (!item.TryGetValue(key, out value) || !(value is string))
                throw new InvalidDataException("Pole " + key + " musi być tekstem.");
            return (string)value;
        }
    }

    // A change that saved something and needs attention for the rest.
    internal sealed class PartialChangeException : InvalidOperationException
    {
        internal PartialChangeException(string message) : base(message) { }
    }

    // What the confirmation window shows, and what only it asks for.
    internal sealed class ChangePlan
    {
        internal readonly ChangeSet Set;
        internal readonly List<string> Summary = new List<string>();
        internal readonly List<string> Warnings = new List<string>();
        internal string ConfirmLabel = "Zastosuj zmiany";
        internal bool Danger;
        // The account whose Host Control asked; shown to the administrator.
        internal string Requester = "";
        internal bool NeedsPassword;
        internal char[] Password = new char[0];
        internal bool NeedsVibepollo;
        internal bool VibepolloTokenSaved;
        internal readonly VibepolloInput Vibepollo = new VibepolloInput();
        // One request id for the child request Prepare tries and Apply makes.
        internal readonly string ChildRequestId = "host-control-" + Guid.NewGuid().ToString("N");

        internal ChangePlan(ChangeSet set) { Set = set; }

        internal void Clear()
        {
            Array.Clear(Password, 0, Password.Length);
            Vibepollo.Clear();
            foreach (Change change in Set.Changes) change.Pin = "";
        }
    }

    // What the helper does beyond the registry; tests replace them.
    internal sealed class ChangeEffects
    {
        internal Func<List<LocalAccount>> Accounts = LocalAccountEnumerator.EnumerateSupported;
        internal Action<GatewayRegistryStore, ProfileRecord> Provision = ProfileProvisioner.Provision;
        internal Action<GatewayRegistryStore, ProfileRecord> RemoveArtifacts = ProfileProvisioner.RemoveArtifacts;
        internal LoginBrokerClient Broker = new LoginBrokerClient();
        internal Func<GatewayRegistryStore, VibepolloInput, List<string>> SaveVibepolloToken = VibepolloMachineApi.SaveToken;
    }

    internal sealed class ChangeApplier
    {
        internal static readonly string[] Days = { "mon", "tue", "wed", "thu", "fri", "sat", "sun" };
        private static readonly string[] DayNames = { "pon", "wt", "śr", "czw", "pt", "sob", "nd" };
        private readonly GatewayRegistryStore store;
        private readonly ChangeEffects effects;

        internal ChangeApplier(GatewayRegistryStore store, ChangeEffects effects)
        {
            this.store = store;
            this.effects = effects;
        }

        // Validates the whole set against the revision Host Control showed and describes it.
        // Registry changes run on a copy of that revision, by the same code Apply runs.
        internal ChangePlan Prepare(ChangeSet set)
        {
            ChangePlan plan = new ChangePlan(set);
            Change first = set.Changes[0];
            if (first.Type == "vibepollo_token")
            {
                Dictionary<string, object> current = VibepolloMachineApi.ReadSettings(store);
                object url, token;
                if (current.TryGetValue("base_url", out url) && !String.IsNullOrWhiteSpace(Convert.ToString(url)))
                    plan.Vibepollo.Url = Convert.ToString(url);
                plan.VibepolloTokenSaved = current.TryGetValue("token", out token) &&
                    !String.IsNullOrWhiteSpace(Convert.ToString(token));
                plan.NeedsVibepollo = true;
                plan.ConfirmLabel = "Zapisz token";
                plan.Summary.Add("Pobierz lub odnów token Vibepollo tego komputera. " + (plan.VibepolloTokenSaved
                    ? "Gateway ma zapisany token; zapis zastąpi go nowym." : "Gateway nie ma jeszcze tokenu Vibepollo."));
                return plan;
            }
            Dictionary<string, object> document = store.Snapshot(set.RegistryRevision);
            foreach (Change change in set.Changes) Describe(document, change, plan);
            // Describe has found the account to add and the profile to remove.
            if (first.Type == "profile_add")
                GatewayRegistryStore.EnsureSidUnused(
                    (Dictionary<string, object>)document["profiles"], first.AccountSid, null);
            else if (first.Type == "remote_sign_in") PrepareRemote(document, first, plan);
            else if (!set.IsCompound)
            {
                Dictionary<string, Dictionary<string, object>> placeholders = new Dictionary<string, Dictionary<string, object>>();
                foreach (Change change in set.Changes)
                    if (change.Type == "pin_set") placeholders[change.ProfileId] = new Dictionary<string, object>();
                ApplyRegistry(document, set, placeholders, plan.ChildRequestId);
            }
            // Names come from the registry and from TVs: nothing they hold may break or reorder a line.
            for (int index = 0; index < plan.Summary.Count; index++)
                plan.Summary[index] = DisplayText.Shown(plan.Summary[index], 2000);
            for (int index = 0; index < plan.Warnings.Count; index++)
                plan.Warnings[index] = DisplayText.Shown(plan.Warnings[index], 2000);
            return plan;
        }

        internal string Apply(ChangePlan plan)
        {
            ChangeSet set = plan.Set;
            Change first = set.Changes[0];
            if (first.Type == "vibepollo_token") return SaveVibepollo(plan);
            if (first.Type == "profile_add") return AddProfile(set, first);
            if (first.Type == "profile_remove") return RemoveProfile(set, first);
            if (first.Type == "remote_sign_in") return RemoteSignIn(set, first, plan);
            // PBKDF2 outside the registry lock.
            Dictionary<string, Dictionary<string, object>> verifiers = new Dictionary<string, Dictionary<string, object>>();
            foreach (Change change in set.Changes)
                if (change.Type == "pin_set")
                    verifiers[change.ProfileId] = GatewayRegistryStore.CreateAppPinVerifier(change.Pin);
            store.Transaction(set.RegistryRevision, delegate(Dictionary<string, object> document) {
                ApplyRegistry(document, set, verifiers, plan.ChildRequestId);
                return true;
            });
            return Done(set);
        }

        // Every registry change of the set on one document, in one locked transaction.
        private static void ApplyRegistry(Dictionary<string, object> document, ChangeSet set,
            Dictionary<string, Dictionary<string, object>> verifiers, string childRequestId)
        {
            Dictionary<string, Dictionary<string, DeviceGrant>> grants =
                new Dictionary<string, Dictionary<string, DeviceGrant>>(StringComparer.Ordinal);
            Dictionary<string, string> grantSids = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (Change change in set.Changes)
            {
                switch (change.Type)
                {
                    case "profile_edit":
                        GatewayRegistryStore.EditProfileIn(document, change.ProfileId, change.AccountSid,
                            change.DisplayName, change.Enabled);
                        break;
                    case "pin_set":
                        GatewayRegistryStore.SetPinVerifierIn(document, change.ProfileId, change.AccountSid,
                            verifiers[change.ProfileId]);
                        break;
                    case "pin_remove":
                        GatewayRegistryStore.RemovePinIn(document, change.ProfileId, change.AccountSid);
                        break;
                    case "client_remove":
                        GatewayRegistryStore.RemoveClientIn(document, change.ClientId);
                        break;
                    case "device_grant":
                        Dictionary<string, DeviceGrant> requested;
                        if (!grants.TryGetValue(change.ProfileId, out requested))
                            grants[change.ProfileId] = requested = new Dictionary<string, DeviceGrant>(StringComparer.Ordinal);
                        requested[change.ClientId] = new DeviceGrant {
                            UseProfile = change.UseProfile, RemoteSignIn = change.RemoteSignIn,
                            ManageChildren = change.ManageChildren };
                        grantSids[change.ProfileId] = change.AccountSid;
                        break;
                    case "child_remove":
                        if (GatewayRegistryStore.ChildNeedsLocalCleanup(document, change.ProfileId))
                        {
                            // The old parent is gone or disabled: the record goes without its revision.
                            Dictionary<string, object> child = GatewayRegistryStore.Profile(document, change.ChildProfileId);
                            if (!GatewayRegistryStore.IsChildProfile(child) || GatewayRegistryStore.Text(child,
                                    "parent_profile_id", "") != change.ProfileId)
                                throw new InvalidOperationException("child_profile_not_owned");
                            GatewayRegistryStore.RemoveChildProfileLocalIn(document, change.ChildProfileId);
                        }
                        else GatewayRegistryStore.ApplyChildProfileRequestIn(document, ChildRequest(change, childRequestId));
                        break;
                    case "child_save":
                        GatewayRegistryStore.ApplyChildProfileRequestIn(document, ChildRequest(change, childRequestId));
                        break;
                    default:
                        throw new InvalidOperationException("Zmiana " + change.Type + " nie jest zmianą rejestru.");
                }
            }
            foreach (KeyValuePair<string, Dictionary<string, DeviceGrant>> item in grants)
                GatewayRegistryStore.UpdateGrantsIn(document, item.Key, grantSids[item.Key], item.Value);
        }

        private static Dictionary<string, object> ChildRequest(Change change, string requestId)
        {
            Dictionary<string, object> request = new Dictionary<string, object> {
                { "operation", change.Type == "child_remove" ? "delete"
                    : change.ChildProfileId.Length == 0 ? "create" : "update" },
                { "parent_profile_id", change.ProfileId }, { "child_profile_id", change.ChildProfileId },
                { "request_id", requestId }, { "expected_revision", change.ExpectedRevision },
                { "grant_current_device", false }, { "grant_client_id", "" } };
            if (change.Draft != null) request["draft"] = change.Draft;
            return request;
        }

        private static string Done(ChangeSet set)
        {
            string type = set.Changes[0].Type;
            if (set.Changes.Exists(delegate(Change change) { return change.Type == "client_remove"; }))
                return set.Changes.Count == 1 ? "Parowanie TV cofnięto. Urządzenie musi zostać sparowane ponownie."
                    : "Zmiany urządzeń zapisano. Gateway zastosuje je przy następnym żądaniu.";
            if (set.Changes.TrueForAll(delegate(Change change) { return change.Type == type; }))
            {
                if (type == "device_grant") return "Uprawnienia zapisano. Gateway zastosuje je przy następnym żądaniu.";
                if (type == "pin_set") return "PIN aplikacji MoonWaker zapisano.";
                if (type == "pin_remove") return "PIN aplikacji MoonWaker usunięto.";
                if (type == "child_save") return "Profil dziecka zapisano.";
                if (type == "child_remove") return "Profil dziecka usunięto.";
            }
            return "Zmiany zapisano. Gateway wczyta je automatycznie.";
        }

        // ---- Compound changes ----

        private LocalAccount FindAccount(string sid)
        {
            foreach (LocalAccount account in effects.Accounts())
                if (String.Equals(account.Sid, sid, StringComparison.OrdinalIgnoreCase)) return account;
            throw new InvalidOperationException(
                "Wybrane konto Windows nie jest włączonym, obsługiwanym kontem lokalnym tego komputera.");
        }

        private string AddProfile(ChangeSet set, Change change)
        {
            LocalAccount account = FindAccount(change.AccountSid);
            // Each later step keeps the store's own checks (SID free, reservation nonce).
            store.Snapshot(set.RegistryRevision);
            ProfileRecord reservation = store.ReserveProfile(account, change.DisplayName);
            try
            {
                store.ProvisionAndFinalize(reservation, delegate { effects.Provision(store, reservation); });
            }
            catch (Exception error)
            {
                string message = error.Message;
                try
                {
                    store.PrepareRemoval(reservation);
                    message += " Profil pozostawiono wyłączony i oznaczony do jawnego usunięcia; " +
                        "usuń go w Host Control, a następnie ponów dodawanie.";
                }
                catch (Exception tombstoneError)
                {
                    message += " Nie udało się utrwalić znacznika naprawy: " + tombstoneError.Message;
                }
                throw new InvalidOperationException(message, error);
            }
            return LoginBrokerClient.Unavailable(effects.Broker.CredentialState(reservation))
                ? "Profil został utworzony dla " + reservation.AccountName +
                    ". Zdalne logowanie będzie dostępne po zainstalowaniu usługi Login Broker."
                : "Profil został utworzony dla " + reservation.AccountName +
                    ". Zdalne logowanie pozostaje wyłączone, dopóki nie zapiszesz hasła Windows w Login Brokerze " +
                    "(Zdalne logowanie → Zmień).";
        }

        private ProfileRecord RemovalTarget(Dictionary<string, object> document, Change change)
        {
            Dictionary<string, object> entry = GatewayRegistryStore.Profile(document, change.ProfileId);
            if (GatewayRegistryStore.IsChildProfile(entry))
                throw new InvalidOperationException("Profil dziecka usuwa się w profilu jego rodzica.");
            ProfileRecord profile = GatewayRegistryStore.ToProfile(change.ProfileId, entry);
            if (!String.Equals(profile.Sid, change.AccountSid, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "Tożsamość profilu zmieniła się. Odśwież Host Control i spróbuj ponownie.");
            return profile;
        }

        private string RemoveProfile(ChangeSet set, Change change)
        {
            ProfileRecord profile = RemovalTarget(store.Snapshot(set.RegistryRevision), change);
            bool offline = ProfileProvisioner.RequiresOfflineCleanup(store, profile);
            // A false policy bit means the profile was never configured or its Broker secret was
            // already deleted, so a profile can be removed while Broker is not installed yet.
            if (profile.RemoteSignInEnabled && !effects.Broker.Delete(profile).Success)
                throw new InvalidOperationException(
                    "Login Broker nie potwierdził usunięcia sekretu. Uruchom usługę i ponów usunięcie.");
            store.PrepareRemoval(profile);
            effects.RemoveArtifacts(store, profile);
            store.RemoveProfile(profile);
            return "Profil MoonWaker usunięto. Konto Windows nie zostało zmienione." +
                (offline ? " Teraz utwórz nowy profil w Host Control." : "");
        }

        private ProfileRecord RemoteProfile(Dictionary<string, object> document, Change change)
        {
            GatewayRegistryStore.MutateProfileIn(document, change.ProfileId, change.AccountSid,
                delegate(Dictionary<string, object> profile) { });
            ProfileRecord record = GatewayRegistryStore.ToProfile(change.ProfileId,
                GatewayRegistryStore.Profile(document, change.ProfileId));
            if (change.Action != "disable" &&
                (record.MappingStatus != "resolved" || String.IsNullOrWhiteSpace(record.Sid)))
                throw new InvalidOperationException("Konto nieobsługiwane albo mapowanie konta wymaga naprawy.");
            return record;
        }

        private void PrepareRemote(Dictionary<string, object> document, Change change, ChangePlan plan)
        {
            ProfileRecord profile = RemoteProfile(document, change);
            BrokerResult credential = effects.Broker.CredentialState(profile);
            if (change.Action != "disable" && LoginBrokerClient.Unavailable(credential))
                throw new InvalidOperationException(
                    "Login Broker niedostępny — zdalne logowanie pojawi się po instalacji usługi.");
            BrokerResult session = effects.Broker.SessionState(profile);
            plan.Warnings.Add("UPRZYWILEJOWANE: zapisane dane pozwalają zatwierdzonemu urządzeniu zalogować " +
                "lub odblokować to konto.");
            plan.Summary.Add("Dane logowania: " + LoginBrokerClient.StatePolish(
                profile.RemoteSignInEnabled ? credential.State : "disabled") + ".  Sesja Windows: " +
                (String.IsNullOrWhiteSpace(session.State) ? "nieznana" : session.State) + ".");
            plan.NeedsPassword = change.Action == "set_password";
            plan.ConfirmLabel = change.Action == "set_password" ? "Zapisz hasło"
                : change.Action == "test" ? "Sprawdź" : "Wyłącz i usuń dane";
            plan.Danger = change.Action == "disable";
        }

        private string RemoteSignIn(ChangeSet set, Change change, ChangePlan plan)
        {
            ProfileRecord profile = RemoteProfile(store.Snapshot(set.RegistryRevision), change);
            LoginBrokerClient broker = effects.Broker;
            if (change.Action == "test")
            {
                BrokerResult tested = broker.Test(profile);
                if (tested.Success && tested.State == "ready") return "Login Broker ponownie zweryfikował zapisane dane.";
                throw new InvalidOperationException(tested.State == "broker_unavailable"
                    ? "Login Broker jest niedostępny. Nie wykonano testu." : "Dane wymagają działania w Host Control.");
            }
            if (change.Action == "disable")
            {
                store.SetRemoteSignInEnabled(profile.Id, profile.Sid, false);
                if (!broker.Delete(profile).Success)
                {
                    // Grants stay revoked. The policy bit records that Broker cleanup still has
                    // to be retried; it cannot authorize a device by itself.
                    store.SetRemoteSignInEnabled(profile.Id, profile.Sid, true);
                    throw new PartialChangeException(
                        "Granty zdalnego logowania zostały cofnięte, ale Login Broker nie potwierdził usunięcia " +
                        "sekretu. Uruchom usługę i ponów operację.");
                }
                return "Zdalne logowanie wyłączono, a dane usunięto z Login Broker.";
            }
            if (plan.Password.Length == 0) throw new InvalidOperationException("Hasła są puste albo różnią się.");
            BrokerResult result = broker.Configure(profile, plan.Password);
            if (!result.Success)
            {
                // No client grant exists yet. If delivery was ambiguous, keep the policy bit set
                // so removal or Disable retries cleanup with Broker.
                if (result.RequestMayHaveReachedBroker) store.SetRemoteSignInEnabled(profile.Id, profile.Sid, true);
                throw new InvalidOperationException(result.State == "broker_unavailable"
                    ? (result.RequestMayHaveReachedBroker
                        ? "Login Broker nie potwierdził wyniku. Nie nadano żadnego grantu; po uruchomieniu usługi " +
                            "użyj opcji Wyłącz, aby potwierdzić usunięcie danych."
                        : "Login Broker jest niedostępny. Hasło nie zostało zapisane.")
                    : "Login Broker odrzucił dane: " + LoginBrokerClient.StatePolish(result.State) + ".");
            }
            if (result.State != "ready")
                throw new InvalidOperationException("Login Broker nie potwierdził gotowości danych logowania.");
            store.SetRemoteSignInEnabled(profile.Id, profile.Sid, true);
            return "Hasło zostało zweryfikowane i przekazane bezpośrednio do Login Broker. " +
                "Nadaj uprawnienie urządzeniom osobno (Urządzenia).";
        }

        private string SaveVibepollo(ChangePlan plan)
        {
            List<string> missing = effects.SaveVibepolloToken(store, plan.Vibepollo);
            if (missing.Count == 0) return "Token zapisany. Gateway używa go od następnego wywołania Vibepollo.";
            throw new PartialChangeException("Token zapisany, ale brakuje pliku Vibepollo (" +
                String.Join(", ", missing.ConvertAll<string>(Path.GetFileName).ToArray()) +
                "). Uruchom Vibepollo i zapisz ponownie.");
        }

        // ---- What the window shows: the registry before the change, never a secret ----

        private void Describe(Dictionary<string, object> document, Change change, ChangePlan plan)
        {
            switch (change.Type)
            {
                case "profile_add":
                    LocalAccount account = FindAccount(change.AccountSid);
                    plan.Summary.Add("Nowy profil „" + Shown(change.DisplayName) + "” dla konta Windows " +
                        Shown(account.QualifiedName) + ". SID konta zostanie przypisany na stałe.");
                    plan.ConfirmLabel = "Utwórz profil";
                    break;
                case "profile_edit":
                    plan.Summary.Add("Profil „" + Name(document, change.ProfileId) + "”: nazwa „" +
                        Shown(change.DisplayName) + "”, profil " + (change.Enabled ? "włączony." : "wyłączony."));
                    break;
                case "pin_set":
                    plan.Summary.Add("Profil „" + Name(document, change.ProfileId) + "”: nowy PIN aplikacji.");
                    break;
                case "pin_remove":
                    plan.Summary.Add("Profil „" + Name(document, change.ProfileId) + "”: usuń PIN aplikacji.");
                    break;
                case "remote_sign_in":
                    ProfileRecord remote = GatewayRegistryStore.ToProfile(change.ProfileId,
                        GatewayRegistryStore.Profile(document, change.ProfileId));
                    string who = "Profil „" + Shown(remote.DisplayName) + "” (konto " + Shown(remote.AccountName) + "): ";
                    plan.Summary.Add(who + (change.Action == "set_password"
                        ? "zapisz lub zmień hasło Windows do zdalnego logowania. Hasło wpisujesz tylko tutaj; " +
                            "trafia bezpośrednio do Login Brokera."
                        : change.Action == "test" ? "sprawdź zapisane dane zdalnego logowania."
                        : "wyłącz zdalne logowanie, cofnij uprawnienia zdalnego logowania urządzeń i usuń dane z Login Brokera."));
                    break;
                case "profile_remove":
                    DescribeRemoval(document, change, plan);
                    break;
                case "device_grant":
                    DescribeGrant(document, change, plan);
                    break;
                case "client_remove":
                    plan.Summary.Add("Cofnij parowanie TV „" + ClientName(document, change.ClientId) +
                        "”: utraci dostęp do wszystkich profili i będzie wymagało ponownego sparowania.");
                    plan.ConfirmLabel = "Cofnij parowanie";
                    plan.Danger = true;
                    break;
                case "child_save":
                    string parent = Name(document, change.ProfileId);
                    string name = Shown(Convert.ToString(change.Draft["name"]));
                    string avatar = ChildAvatars.Name(Convert.ToString(change.Draft["avatar_id"]));
                    string before = change.ChildProfileId.Length == 0 ? null : ChildAvatars.Name(GatewayRegistryStore.Text(
                        GatewayRegistryStore.Profile(document, change.ChildProfileId), "avatar_id", ""));
                    plan.Summary.Add((before == null
                            ? "Nowy profil dziecka „" + name + "”, awatar „" + avatar + "”"
                            : "Profil dziecka „" + Name(document, change.ChildProfileId) + "” → „" + name + "”" +
                                (before == avatar ? "" : ", awatar „" + before + "” → „" + avatar + "”")) +
                        " (rodzic „" + parent + "”), " + ((bool)change.Draft["enabled"] ? "aktywny" : "wyłączony") +
                        ". " + Schedule((Dictionary<string, object>)change.Draft["schedule"]));
                    break;
                case "child_remove":
                    plan.Summary.Add("Usuń profil dziecka „" + Name(document, change.ChildProfileId) + "”" +
                        (GatewayRegistryStore.ChildNeedsLocalCleanup(document, change.ProfileId)
                            ? "." : " (rodzic „" + Name(document, change.ProfileId) + "”)."));
                    plan.ConfirmLabel = "Usuń profil dziecka";
                    plan.Danger = true;
                    break;
            }
        }

        private void DescribeRemoval(Dictionary<string, object> document, Change change, ChangePlan plan)
        {
            ProfileRecord profile = RemovalTarget(document, change);
            string name = Shown(profile.DisplayName);
            bool offline = ProfileProvisioner.RequiresOfflineCleanup(store, profile);
            bool unresolved = !ProfileProvisioner.HasAuthoritativeOwner(profile);
            plan.Summary.Add(offline
                ? "Profil „" + name + "” zostanie trwale wyłączony, jego granty i sekret Login Brokera " +
                    "cofnięte, a katalog pozostanie do ręcznego sprzątania offline. Konto Windows ani jego pliki " +
                    "użytkownika nie zostaną zmienione."
                : "Usunięte zostaną wyłącznie dane profilu MoonWaker „" + name + "” (" + profile.Id +
                    "), jego granty urządzeń i sekret Login Brokera. Konto Windows „" + Shown(profile.AccountName) +
                    "” oraz jego pliki użytkownika nie zostaną usunięte ani zmienione.");
            if (offline)
                plan.Warnings.Add("Brak zaufanego znacznika utworzenia: MoonWaker nie przejdzie ani nie usunie tego " +
                    "katalogu online. " + (unresolved
                        ? "Po oznaczeniu możesz od razu dodać nowy profil z tym SID; stary katalog sprząta administrator offline."
                        : "Po sprzątaniu offline ponów usunięcie, aby zwolnić SID przed utworzeniem nowego profilu."));
            plan.ConfirmLabel = offline ? "Wyłącz; sprzątanie offline" : "Usuń dane profilu";
            plan.Danger = true;
        }

        private static void DescribeGrant(Dictionary<string, object> document, Change change, ChangePlan plan)
        {
            Dictionary<string, object> client = Client(document, change.ClientId);
            HashSet<string> now = GatewayRegistryStore.Permissions(client, change.ProfileId);
            ProfileRecord profile = GatewayRegistryStore.ToProfile(change.ProfileId,
                GatewayRegistryStore.Profile(document, change.ProfileId));
            List<string> parts = new List<string>();
            if (now.Contains("use_profile") != change.UseProfile)
                parts.Add("korzystanie z profilu: " + (change.UseProfile ? "tak" : "nie"));
            if (now.Contains("remote_sign_in") != change.RemoteSignIn)
                parts.Add("zdalne logowanie: " + (change.RemoteSignIn ? "tak" : "nie"));
            if (now.Contains("manage_children") != change.ManageChildren)
                parts.Add("zarządzanie dziećmi: " + (change.ManageChildren ? "tak" : "nie"));
            string tv = Shown(GatewayRegistryStore.Text(client, "name", "Urządzenie"));
            plan.Summary.Add("„" + tv + "”, profil „" + Shown(profile.DisplayName) + "”: " +
                (parts.Count == 0 ? "bez zmian" : String.Join(", ", parts.ToArray())) + ".");
            if (change.RemoteSignIn && !now.Contains("remote_sign_in"))
            {
                string account = GatewayRegistryStore.IsChildProfile(GatewayRegistryStore.Profile(document, change.ProfileId))
                    ? GatewayRegistryStore.ToProfile(profile.ParentProfileId,
                        GatewayRegistryStore.Profile(document, profile.ParentProfileId)).AccountName
                    : profile.AccountName;
                plan.Warnings.Add("„" + tv + "” dostanie uprzywilejowane prawo do zdalnego zalogowania lub " +
                    "odblokowania konta " + Shown(account) + ".");
            }
            plan.ConfirmLabel = "Zapisz zmiany";
        }

        private static string Name(Dictionary<string, object> document, string profileId)
        {
            return Shown(GatewayRegistryStore.ToProfile(profileId, GatewayRegistryStore.Profile(document, profileId)).DisplayName);
        }

        // A name inside a summary line: without hidden characters, bounded.
        private static string Shown(string name) { return DisplayText.Shown(name, 64); }

        private static Dictionary<string, object> Client(Dictionary<string, object> document, string clientId)
        {
            foreach (Dictionary<string, object> client in GatewayRegistryStore.Clients(document))
                if (GatewayRegistryStore.Text(client, "id", "") == clientId) return client;
            throw new InvalidOperationException("Urządzenie nie jest już sparowane. Odśwież listę.");
        }

        private static string ClientName(Dictionary<string, object> document, string clientId)
        {
            return Shown(GatewayRegistryStore.Text(Client(document, clientId), "name", "Urządzenie"));
        }

        private static string Schedule(Dictionary<string, object> schedule)
        {
            Dictionary<string, object> weekdays = (Dictionary<string, object>)schedule["weekdays"];
            List<string> days = new List<string>();
            for (int index = 0; index < Days.Length; index++)
            {
                Dictionary<string, object> day = (Dictionary<string, object>)weekdays[Days[index]];
                if (!(bool)day["enabled"]) continue;
                int limit = (int)day["daily_limit_seconds"] / 60;
                days.Add(DayNames[index] + " " + Clock((int)day["start_minute"]) + "–" + Clock((int)day["end_minute"]) +
                    (limit > 0 ? " (limit " + limit + " min)" : ""));
            }
            return days.Count == 0 ? "Granie wyłączone we wszystkie dni." : "Granie: " + String.Join(", ", days.ToArray()) + ".";
        }

        private static string Clock(int minutes)
        {
            return (minutes / 60).ToString("00") + ":" + (minutes % 60).ToString("00");
        }
    }

    // One exchange of the change channel: the request in, the answer out. "rejected": the set
    // is malformed or does not hold for the registry; "cancelled": the window was closed, or
    // Host Control stopped waiting (connected) before the user confirmed; "applied", "partial"
    // or "failed": what happened after the user confirmed.
    internal static class ApplyRequest
    {
        internal static byte[] Handle(byte[] raw, Func<ChangeApplier> open, Func<ChangePlan, bool> confirm,
            Func<bool> connected)
        {
            ChangePlan plan = null;
            try
            {
                ChangeApplier applier;
                try
                {
                    ChangeSet set = ChangeSet.Parse(raw);
                    applier = open();
                    plan = applier.Prepare(set);
                }
                catch (Exception error) { return Answer("rejected", error.Message); }
                bool confirmed;
                try { confirmed = confirm(plan); }
                catch (Exception error) { return Answer("failed", "Okno potwierdzenia nie działa: " + error.Message); }
                if (!confirmed) return Answer("cancelled", "Zmiany odrzucono w oknie administratora.");
                // Nobody would learn the outcome: apply nothing.
                if (!connected()) return Answer("cancelled", "Host Control przestał czekać na wynik. Nic nie zapisano.");
                try { return Answer("applied", applier.Apply(plan)); }
                catch (PartialChangeException error) { return Answer("partial", error.Message); }
                catch (Exception error) { return Answer("failed", error.Message); }
            }
            finally { if (plan != null) plan.Clear(); }
        }

        private static byte[] Answer(string outcome, string message)
        {
            if (message.Length > 4000) message = message.Substring(0, 4000);
            return new UTF8Encoding(false).GetBytes(new JavaScriptSerializer().Serialize(new Dictionary<string, object> {
                { "protocol", ChangeChannel.Protocol }, { "outcome", outcome }, { "message", message } }));
        }
    }

    // The helper's own window: the change set as the registry will see it, the inputs only it
    // asks for (a Windows password, Vibepollo's administrator login), and the confirmation.
    internal static class ConfirmWindow
    {
        internal static bool Ask(ChangePlan plan)
        {
            HostDialog dialog = Create(plan, null);
            dialog.Topmost = true;
            return dialog.ShowDialog() == true;
        }

        internal static HostDialog Create(ChangePlan plan, bool? light)
        {
            Ui ui = new Ui(new Palette(light ?? Palette.WindowsAppsUseLightTheme()));
            StackPanel body = new StackPanel();
            StackPanel lines = new StackPanel();
            foreach (string line in plan.Summary) lines.Children.Add(Bullet(ui, line, ui.P.Text));
            foreach (string line in plan.Warnings) lines.Children.Add(Bullet(ui, line, ui.P.Error));
            body.Children.Add(new ScrollViewer {
                Content = lines, MaxHeight = 320, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });

            PasswordBox first = null, second = null, adminPassword = null, token = null;
            TextBox login = null, url = null;
            CheckBox automatic = null;
            if (plan.NeedsPassword)
            {
                first = Secret(ui, body, "Nowe hasło Windows", 256);
                second = Secret(ui, body, "Powtórz hasło", 256);
            }
            if (plan.NeedsVibepollo)
            {
                automatic = new CheckBox {
                    Content = "Pobierz / odnów token automatycznie", IsChecked = true, Foreground = ui.P.Text,
                    Margin = new Thickness(0, 12, 0, 0) };
                body.Children.Add(automatic);
                login = new TextBox();
                Field(ui, body, "Login administratora Vibepollo", login);
                adminPassword = Secret(ui, body, "Hasło administratora", 1024);
                token = Secret(ui, body, "Istniejący token API (alternatywa)", 4096);
                url = new TextBox { Text = plan.Vibepollo.Url };
                Field(ui, body, "Adres lokalnego API Vibepollo", url);
                TextBlock note = ui.Muted("Login i hasło służą tylko do lokalnego wywołania /api/token i nie są zapisywane. " +
                    "Certyfikat Vibepollo jest sprawdzany z plikiem w jego katalogu konfiguracji.");
                note.Margin = new Thickness(0, 10, 0, 0);
                body.Children.Add(note);
                RoutedEventHandler follow = delegate {
                    bool on = automatic.IsChecked == true;
                    login.IsEnabled = adminPassword.IsEnabled = on;
                    token.IsEnabled = !on;
                };
                automatic.Checked += follow;
                automatic.Unchecked += follow;
                follow(null, null);
            }

            HostDialog dialog = new HostDialog(null, ui, "Zatwierdź zmiany MoonWaker",
                "Host Control" + (plan.Requester.Length == 0 ? "" : " konta " + plan.Requester) +
                " prosi o zapis poniższych zmian. Sprawdź je, zanim je zastosujesz.",
                body, plan.ConfirmLabel, plan.Danger || plan.Warnings.Count > 0 ? ButtonKind.Danger : ButtonKind.Primary, delegate
                {
                    if (plan.NeedsPassword)
                    {
                        char[] one = Chars(first), two = Chars(second);
                        first.Clear();
                        second.Clear();
                        bool same = one.Length > 0 && Same(one, two);
                        Array.Clear(two, 0, two.Length);
                        if (!same)
                        {
                            Array.Clear(one, 0, one.Length);
                            return "Hasła są puste albo różnią się.";
                        }
                        plan.Password = one;
                    }
                    if (plan.NeedsVibepollo)
                    {
                        plan.Vibepollo.Clear();
                        plan.Vibepollo.Automatic = automatic.IsChecked == true;
                        plan.Vibepollo.Username = login.Text;
                        plan.Vibepollo.Url = url.Text;
                        plan.Vibepollo.Password = Chars(adminPassword);
                        plan.Vibepollo.Token = Chars(token);
                        string problem = plan.Vibepollo.Problem();
                        if (problem != null) return problem;
                    }
                    return null;
                }, 560);
            dialog.Closed += delegate
            {
                foreach (PasswordBox box in new[] { first, second, adminPassword, token })
                    if (box != null) box.Clear();
            };
            return dialog;
        }

        private static TextBlock Bullet(Ui ui, string text, System.Windows.Media.Brush brush)
        {
            TextBlock line = ui.Text("•  " + text, 13, brush, FontWeights.Normal);
            line.Margin = new Thickness(0, 0, 0, 6);
            return line;
        }

        private static void Field(Ui ui, StackPanel body, string label, Control input)
        {
            TextBlock caption = ui.Text(label, 13, ui.P.Text, FontWeights.Normal);
            caption.Margin = new Thickness(0, 10, 0, 4);
            body.Children.Add(caption);
            ui.Input(input);
            System.Windows.Automation.AutomationProperties.SetName(input, label);
            body.Children.Add(input);
        }

        private static PasswordBox Secret(Ui ui, StackPanel body, string label, int length)
        {
            PasswordBox box = new PasswordBox { MaxLength = length };
            Field(ui, body, label, box);
            return box;
        }

        private static char[] Chars(PasswordBox box)
        {
            using (SecureString secure = box.SecurePassword)
            {
                IntPtr pointer = Marshal.SecureStringToGlobalAllocUnicode(secure);
                try
                {
                    char[] result = new char[secure.Length];
                    Marshal.Copy(pointer, result, 0, result.Length);
                    return result;
                }
                finally { Marshal.ZeroFreeGlobalAllocUnicode(pointer); }
            }
        }

        private static bool Same(char[] left, char[] right)
        {
            int different = left.Length ^ right.Length;
            int count = Math.Min(left.Length, right.Length);
            for (int index = 0; index < count; index++) different |= left[index] ^ right[index];
            return different == 0;
        }
    }

    // The process that started this one. Through UAC that is the Host Control that asked for
    // elevation (consent makes the caller the parent): the only process the change channel
    // accepts. Its id only: opening another account's process may be refused to an
    // over-the-shoulder administrator, and the channel checks the client's token itself.
    internal static class ParentProcess
    {
        internal static int Id()
        {
            ProcessBasicInformation information = new ProcessBasicInformation();
            int length;
            int status;
            using (Process self = Process.GetCurrentProcess())
                status = NtQueryInformationProcess(self.Handle, 0, ref information, Marshal.SizeOf(information), out length);
            if (status != 0) throw new InvalidOperationException("Nie można ustalić procesu, który uruchomił pomocnika.");
            return (int)information.InheritedFromUniqueProcessId.ToInt64();
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ProcessBasicInformation
        {
            internal IntPtr ExitStatus;
            internal IntPtr PebBaseAddress;
            internal IntPtr AffinityMask;
            internal IntPtr BasePriority;
            internal IntPtr UniqueProcessId;
            internal IntPtr InheritedFromUniqueProcessId;
        }

        [DllImport("ntdll.dll")]
        private static extern int NtQueryInformationProcess(IntPtr process, int informationClass,
            ref ProcessBasicInformation information, int length, out int returnLength);
    }
}
