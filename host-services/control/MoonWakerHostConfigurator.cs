using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Pipes;
using System.Management;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: System.Reflection.AssemblyVersion("0.7.78.0")]
[assembly: System.Reflection.AssemblyFileVersion("0.7.78.0")]
[assembly: System.Reflection.AssemblyInformationalVersion("0.7.78+2026.09.05")]

namespace MoonWaker.HostConfigurator
{
    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            if (!IsAdministrator())
            {
                MessageBox.Show("Konfigurator wymaga uprawnień administratora.",
                    "MoonWaker Host Configurator", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            string mode = Argument(args, "--mode") ?? "edit";
            string profileId = Argument(args, "--profile");
            try
            {
                GatewayRegistryStore store = GatewayRegistryStore.ForInstalledHost();
                Form form;
                if (mode == "add") form = new AddProfileForm(store);
                else
                {
                    if (!GatewayRegistryStore.IsValidProfileId(profileId))
                        throw new InvalidOperationException("Wybierz prawidłowy profil MoonWaker.");
                    if (mode == "edit") form = new EditProfileForm(store, profileId);
                    else if (mode == "remote-sign-in") form = new RemoteSignInForm(store, profileId);
                    else if (mode == "devices") form = new DeviceGrantsForm(store, profileId);
                    else if (mode == "remove") form = new RemoveProfileForm(store, profileId);
                    else throw new InvalidOperationException("Nieznany tryb konfiguratora.");
                }
                Application.Run(form);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static string Argument(string[] args, string name)
        {
            if (args == null) return null;
            for (int index = 0; index + 1 < args.Length; index++)
                if (String.Equals(args[index], name, StringComparison.OrdinalIgnoreCase))
                    return args[index + 1];
            return null;
        }

        private static bool IsAdministrator()
        {
            WindowsIdentity identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }

    internal static class Ui
    {
        internal static readonly Color Background = Color.FromArgb(17, 20, 28);
        internal static readonly Color Panel = Color.FromArgb(28, 33, 45);
        internal static readonly Color Accent = Color.FromArgb(116, 100, 255);
        internal static readonly Color Muted = Color.FromArgb(164, 171, 193);
        internal static readonly Color Danger = Color.FromArgb(255, 170, 170);
        internal static readonly Color Success = Color.FromArgb(129, 226, 169);

        internal static void Prepare(Form form, string title, int width, int height)
        {
            form.Text = title;
            form.ClientSize = new Size(width, height);
            form.StartPosition = FormStartPosition.CenterScreen;
            form.FormBorderStyle = FormBorderStyle.FixedDialog;
            form.MaximizeBox = false;
            form.MinimizeBox = false;
            form.BackColor = Background;
            form.ForeColor = Color.White;
            form.Font = new Font("Segoe UI", 9.5F);
            try { form.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        }

        internal static Label Label(Control parent, string text, int x, int y, int width, int height,
            float size, FontStyle style, Color color)
        {
            Label label = new Label();
            label.Text = text;
            label.SetBounds(x, y, width, height);
            label.Font = new Font("Segoe UI", size, style);
            label.ForeColor = color;
            parent.Controls.Add(label);
            return label;
        }

        internal static Button Button(Control parent, string text, int x, int y, int width,
            EventHandler clicked, bool primary)
        {
            Button button = new Button();
            button.Text = text;
            button.SetBounds(x, y, width, 38);
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderColor = Color.FromArgb(77, 85, 109);
            button.BackColor = primary ? Accent : Panel;
            button.ForeColor = Color.White;
            if (primary) button.FlatAppearance.BorderSize = 0;
            button.Click += clicked;
            parent.Controls.Add(button);
            return button;
        }

        internal static TextBox TextBox(Control parent, int x, int y, int width)
        {
            TextBox value = new TextBox();
            value.SetBounds(x, y, width, 30);
            value.BackColor = Panel;
            value.ForeColor = Color.White;
            value.BorderStyle = BorderStyle.FixedSingle;
            parent.Controls.Add(value);
            return value;
        }

        internal static string BrokerStatePolish(string state)
        {
            if (state == "ready") return "gotowe";
            if (state == "disabled") return "wyłączone";
            if (state == "action_required") return "wymaga działania";
            if (state == "unsupported") return "konto nieobsługiwane";
            if (state == "broker_unavailable" || state == "unavailable") return "usługa niedostępna";
            return "nieznany";
        }
    }

    internal sealed class LocalAccount
    {
        internal string Name;
        internal string QualifiedName;
        internal string Sid;
        public override string ToString() { return QualifiedName + "  —  " + Sid; }
    }

    internal static class LocalAccountEnumerator
    {
        private static readonly HashSet<string> SpecialNames = new HashSet<string>(
            new string[] { "Administrator", "Guest", "DefaultAccount", "WDAGUtilityAccount",
                "defaultuser0", "HomeGroupUser$" }, StringComparer.OrdinalIgnoreCase);

        internal static List<LocalAccount> EnumerateSupported()
        {
            List<LocalAccount> result = new List<LocalAccount>();
            string machine = Environment.MachineName;
            using (ManagementObjectSearcher searcher = new ManagementObjectSearcher(
                "SELECT Name,Domain,SID,Disabled,LocalAccount FROM Win32_UserAccount " +
                "WHERE LocalAccount=True AND Disabled=False"))
            using (ManagementObjectCollection accounts = searcher.Get())
            {
                foreach (ManagementObject value in accounts)
                {
                    string name = Convert.ToString(value["Name"]);
                    string domain = Convert.ToString(value["Domain"]);
                    string sid = Convert.ToString(value["SID"]);
                    if (String.IsNullOrWhiteSpace(name) || String.IsNullOrWhiteSpace(sid) ||
                        !String.Equals(domain, machine, StringComparison.OrdinalIgnoreCase) ||
                        SpecialNames.Contains(name) || name.EndsWith("$", StringComparison.Ordinal) ||
                        !IsNormalLocalSid(sid) || IsCloudIdentity(sid)) continue;
                    result.Add(new LocalAccount {
                        Name = name, QualifiedName = machine + "\\" + name, Sid = sid
                    });
                }
            }
            result.Sort(delegate(LocalAccount left, LocalAccount right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.Name, right.Name);
            });
            return result;
        }

        private static bool IsNormalLocalSid(string sid)
        {
            if (!System.Text.RegularExpressions.Regex.IsMatch(sid,
                "^S-1-5-21-[0-9]+-[0-9]+-[0-9]+-[0-9]+$")) return false;
            int separator = sid.LastIndexOf('-');
            int rid;
            return separator > 0 && Int32.TryParse(sid.Substring(separator + 1), out rid) && rid >= 1000;
        }

        private static bool IsCloudIdentity(string sid)
        {
            string path = @"SOFTWARE\Microsoft\IdentityStore\Cache\" + sid;
            try
            {
                using (RegistryKey key = Registry.LocalMachine.OpenSubKey(path, false))
                {
                    if (key == null) return false;
                    string provider = Convert.ToString(key.GetValue("IdentityProvider", ""));
                    if (String.IsNullOrWhiteSpace(provider))
                        provider = Convert.ToString(key.GetValue("ProviderName", ""));
                    return provider.IndexOf("Microsoft", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        provider.IndexOf("Azure", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        provider.IndexOf("Entra", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        provider.IndexOf("Cloud", StringComparison.OrdinalIgnoreCase) >= 0;
                }
            }
            catch { return true; }
        }
    }

    internal sealed class ProfileRecord
    {
        internal string Id;
        internal string DisplayName;
        internal string Sid;
        internal string AccountName;
        internal string Root;
        internal string DiscordEndpoint;
        internal string VibepolloEndpoint;
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
        internal int VibepolloPort { get { return GatewayRegistryStore.EndpointPort(VibepolloEndpoint); } }
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
    }

    internal sealed class DeviceGrant
    {
        internal bool UseProfile;
        internal bool RemoteSignIn;
    }

    internal sealed class GatewayRegistryStore
    {
        private const int SchemaVersion = 2;
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
                if (profile != null) result.Add(ToProfile(item.Key, profile));
            }
            result.Sort(delegate(ProfileRecord left, ProfileRecord right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.DisplayName, right.DisplayName);
            });
            return result;
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
                profile["owner_sid"] = account.Sid;
                profile["windows_account_sid"] = account.Sid;
                profile["owner"] = account.QualifiedName;
                profile["windows_account_name"] = account.QualifiedName;
                profile["enabled"] = false;
                profile["profile_root"] = root;
                profile["discord_bridge"] = "http://127.0.0.1:" + ports[0];
                profile["vibepollo_bridge"] = "http://127.0.0.1:" + ports[1];
                profile["game_provider_bridge"] = "http://127.0.0.1:" + ports[2];
                profile["playnite_bridge"] = "http://127.0.0.1:" + ports[2];
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
            MutateProfile(id, expectedSid, delegate(Dictionary<string, object> document,
                Dictionary<string, object> profile) { profile["enabled"] = true; });
        }

        internal void ProvisionAndFinalize(ProfileRecord expected, Action provision)
        {
            if (expected == null || provision == null) throw new ArgumentNullException();
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profile = Profile(document, expected.Id);
                EnsureProvisioningReservation(profile, expected);
                provision();
                document = ReadDocument();
                profile = Profile(document, expected.Id);
                EnsureProvisioningReservation(profile, expected);
                profile["enabled"] = true;
                WriteDocument(document);
            }
        }

        internal void UpdateProfile(string id, string expectedSid, string displayName, bool enabled)
        {
            ValidateDisplayName(displayName);
            MutateProfile(id, expectedSid, delegate(Dictionary<string, object> document,
                Dictionary<string, object> profile) {
                profile["name"] = displayName.Trim();
                profile["display_name"] = displayName.Trim();
                profile["enabled"] = enabled;
            });
        }

        internal void SetAppPin(string id, string expectedSid, string pin)
        {
            Dictionary<string, object> verifier = CreateAppPinVerifier(pin);
            MutateProfile(id, expectedSid, delegate(Dictionary<string, object> document,
                Dictionary<string, object> profile) { profile["pin_verifier"] = verifier; });
        }

        internal void RemoveAppPin(string id, string expectedSid)
        {
            MutateProfile(id, expectedSid, delegate(Dictionary<string, object> document,
                Dictionary<string, object> profile) { profile.Remove("pin_verifier"); });
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
            MutateProfile(id, expectedSid, delegate(Dictionary<string, object> document,
                Dictionary<string, object> profile) {
                profile["remote_sign_in_enabled"] = enabled;
                if (!enabled) RemoveRemoteSignInGrants(document, id);
            });
        }

        internal void UpdateGrants(string id, string expectedSid,
            Dictionary<string, DeviceGrant> requested)
        {
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profile = Profile(document, id);
                EnsureSid(profile, expectedSid);
                EnsureNotTombstoned(profile);
                bool remoteEnabled = BooleanValue(profile, "remote_sign_in_enabled");
                foreach (Dictionary<string, object> client in Clients(document))
                {
                    string clientId = Text(client, "id", "");
                    DeviceGrant grant;
                    if (String.IsNullOrWhiteSpace(clientId) || !requested.TryGetValue(clientId, out grant))
                        continue;
                    if (grant.RemoteSignIn && (!grant.UseProfile || !remoteEnabled))
                        throw new InvalidOperationException(
                            "Zdalny dostęp wymaga zwykłego dostępu i gotowych danych logowania profilu.");
                    Dictionary<string, object> grants = Dictionary(client, "profile_grants", true);
                    if (!grant.UseProfile) grants.Remove(id);
                    else grants[id] = grant.RemoteSignIn
                        ? new object[] { "use_profile", "remote_sign_in" }
                        : new object[] { "use_profile" };
                }
                WriteDocument(document);
            }
        }

        internal List<DeviceRecord> GetDevices(string profileId)
        {
            Dictionary<string, object> document = ReadDocument();
            Profile(document, profileId);
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
                    RemoteSignIn = permissions.Contains("remote_sign_in")
                });
            }
            result.Sort(delegate(DeviceRecord left, DeviceRecord right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(left.Name, right.Name);
            });
            return result;
        }

        internal void RemoveClient(string clientId)
        {
            if (String.IsNullOrWhiteSpace(clientId))
                throw new InvalidOperationException("Wybierz prawidłowe urządzenie.");
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
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
                WriteDocument(document);
            }
        }

        internal string PrepareRemoval(ProfileRecord expected)
        {
            if (expected == null) throw new ArgumentNullException("expected");
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profile = Profile(document, expected.Id);
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
                EnsureRemovalIdentity(profile, expected);
                EnsureDeletionTombstone(profile, expected);
                string expectedRoot = Path.GetFullPath(Path.Combine(ProfilesRoot, expected.Id));
                string actualRoot = String.IsNullOrWhiteSpace(expected.Root)
                    ? expectedRoot : Path.GetFullPath(expected.Root);
                if (!PathSafety.IsConclusiveAbsent(expectedRoot))
                    throw new InvalidOperationException(
                        "Katalog profilu nadal istnieje. Zamknij programy profilu i ponów usunięcie.");
                profiles.Remove(expected.Id);
                RemoveProfileGrants(document, expected.Id);
                WriteDocument(document);
            }
        }

        private void MutateProfile(string id, string expectedSid,
            Action<Dictionary<string, object>, Dictionary<string, object>> mutation)
        {
            using (RegistryFileLock.Acquire(RegistryLockPath, lockTimeoutMilliseconds))
            {
                Dictionary<string, object> document = ReadDocument();
                Dictionary<string, object> profile = Profile(document, id);
                EnsureSid(profile, expectedSid);
                EnsureNotTombstoned(profile);
                mutation(document, profile);
                WriteDocument(document);
            }
        }

        private Dictionary<string, object> ReadDocument()
        {
            Dictionary<string, object> document;
            try
            {
                document = json.Deserialize<Dictionary<string, object>>(
                    File.ReadAllText(ConfigPath, Encoding.UTF8));
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException("Rejestr profili Gateway jest uszkodzony.", ex);
            }
            object clients = document == null ? null : Value(document, "clients");
            if (document == null || IntValue(document, "schema_version") != SchemaVersion ||
                !(Value(document, "profiles") is Dictionary<string, object>) ||
                !(clients is IEnumerable) || clients is string ||
                clients is Dictionary<string, object>)
                throw new InvalidOperationException(
                    "Gateway musi najpierw uruchomić migrację rejestru do schematu 2.");
            return document;
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
                File.Replace(temporary, ConfigPath, null);
            }
            finally
            {
                Array.Clear(bytes, 0, bytes.Length);
                try { if (File.Exists(temporary)) File.Delete(temporary); } catch { }
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

        private static Dictionary<string, object> Profile(Dictionary<string, object> document, string id)
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

        private static ProfileRecord ToProfile(string id, Dictionary<string, object> profile)
        {
            string provider = Text(profile, "game_provider_bridge",
                Text(profile, "playnite_bridge", ""));
            Dictionary<string, object> tombstone = Value(profile,
                "deletion_tombstone") as Dictionary<string, object>;
            return new ProfileRecord {
                Id = id,
                DisplayName = Text(profile, "display_name", Text(profile, "name", id)),
                Sid = Text(profile, "windows_account_sid", Text(profile, "owner_sid", "")),
                AccountName = Text(profile, "windows_account_name", Text(profile, "owner", "")),
                Root = Text(profile, "profile_root", ""),
                DiscordEndpoint = Text(profile, "discord_bridge", ""),
                VibepolloEndpoint = Text(profile, "vibepollo_bridge", ""),
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

        private static IEnumerable<Dictionary<string, object>> Clients(Dictionary<string, object> document)
        {
            IEnumerable values = Value(document, "clients") as IEnumerable;
            if (values == null) yield break;
            foreach (object value in values)
            {
                Dictionary<string, object> client = value as Dictionary<string, object>;
                if (client != null) yield return client;
            }
        }

        private static HashSet<string> Permissions(Dictionary<string, object> client, string profileId)
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
                if (text == "use_profile" || text == "remote_sign_in") result.Add(text);
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
                if (permissions != null && !(permissionsValue is string))
                    foreach (object permission in permissions)
                        if (Convert.ToString(permission) == "use_profile") use = true;
                if (use) grants[id] = new object[] { "use_profile" };
                else grants.Remove(id);
            }
        }

        private static void EnsureSidUnused(Dictionary<string, object> profiles, string sid, string exceptId)
        {
            foreach (KeyValuePair<string, object> item in profiles)
            {
                if (String.Equals(item.Key, exceptId, StringComparison.Ordinal)) continue;
                Dictionary<string, object> profile = item.Value as Dictionary<string, object>;
                if (profile != null && SameSid(Text(profile, "windows_account_sid",
                    Text(profile, "owner_sid", "")), sid))
                    throw new InvalidOperationException(
                        "To konto Windows ma już profil MoonWaker. Jedno konto może mieć tylko jeden profil.");
            }
        }

        private static void EnsureSid(Dictionary<string, object> profile, string expectedSid)
        {
            string current = Text(profile, "windows_account_sid", Text(profile, "owner_sid", ""));
            if (!SameSid(current, expectedSid))
                throw new InvalidOperationException(
                    "Mapowanie SID profilu zmieniło się. Usuń i utwórz profil ponownie.");
        }

        private static void EnsureRemovalIdentity(Dictionary<string, object> profile,
            ProfileRecord expected)
        {
            string id = Text(profile, "id", expected.Id);
            string sid = Text(profile, "windows_account_sid", Text(profile, "owner_sid", ""));
            string account = Text(profile, "windows_account_name", Text(profile, "owner", ""));
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

        private static bool IsSid(string value)
        {
            return !String.IsNullOrWhiteSpace(value) &&
                System.Text.RegularExpressions.Regex.IsMatch(value, "^S-[0-9]+(?:-[0-9]+){2,15}$");
        }

        private static void ValidateDisplayName(string value)
        {
            string trimmed = value == null ? "" : value.Trim();
            if (trimmed.Length == 0 || trimmed.Length > 80 ||
                System.Text.RegularExpressions.Regex.IsMatch(trimmed, "[\\x00-\\x1f\\x7f]"))
                throw new InvalidOperationException("Nazwa profilu musi mieć od 1 do 80 znaków drukowalnych.");
        }

        private static HashSet<int> UsedPorts(Dictionary<string, object> profiles)
        {
            HashSet<int> result = new HashSet<int>();
            foreach (object value in profiles.Values)
            {
                Dictionary<string, object> profile = value as Dictionary<string, object>;
                if (profile == null) continue;
                foreach (string name in new string[] { "discord_bridge", "vibepollo_bridge",
                    "game_provider_bridge", "playnite_bridge" })
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
                int[] ports = new int[] { 8765 + (100 * slot), 8775 + (100 * slot),
                    8780 + (100 * slot) };
                if (!used.Contains(ports[0]) && !used.Contains(ports[1]) && !used.Contains(ports[2]))
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

        private static string Text(Dictionary<string, object> source, string key, string fallback)
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
        internal static void Provision(GatewayRegistryStore store, ProfileRecord profile)
        {
            string script = TrustedInstalledFile(store, "install", "Install-WakePlayProfile.ps1");
            string hostControl = TrustedInstalledFile(store, "control", "MoonWakerHostControl.exe");
            List<string> args = new List<string>();
            args.Add("-NoProfile");
            args.Add("-ExecutionPolicy");
            args.Add("Bypass");
            args.Add("-File");
            args.Add(script);
            args.Add("-ProfileId"); args.Add(profile.Id);
            args.Add("-ProfileName"); args.Add(profile.DisplayName);
            args.Add("-OwnerSid"); args.Add(profile.Sid);
            args.Add("-OwnerName"); args.Add(profile.AccountName);
            args.Add("-InstallRoot"); args.Add(store.ProfilesRoot);
            args.Add("-GatewayConfigPath"); args.Add(store.ConfigPath);
            args.Add("-GatewayDirectory"); args.Add(store.GatewayDirectory);
            args.Add("-HostControlExecutable"); args.Add(hostControl);
            args.Add("-DiscordPort"); args.Add(profile.DiscordPort.ToString());
            args.Add("-VibepolloPort"); args.Add(profile.VibepolloPort.ToString());
            args.Add("-GameProviderPort"); args.Add(profile.GameProviderPort.ToString());
            args.Add("-SkipPlaynite");
            args.Add("-SkipGatewayRegistration");
            args.Add("-MachineProvisioning");
            args.Add("-SkipStart");
            Run(TrustedSystemExecutable("WindowsPowerShell", "v1.0", "powershell.exe"),
                JoinArguments(args), 120000, false);
        }

        internal static void RemoveArtifacts(GatewayRegistryStore store, ProfileRecord profile)
        {
            if (String.IsNullOrWhiteSpace(profile.DeletionNonce))
                throw new InvalidOperationException("Brak trwałego znacznika usuwania profilu.");
            string expected = Path.GetFullPath(Path.Combine(store.ProfilesRoot, profile.Id));
            string actual = String.IsNullOrWhiteSpace(profile.Root)
                ? expected : Path.GetFullPath(profile.Root);
            string taskScheduler = TrustedSystemExecutable("schtasks.exe");
            foreach (string taskName in new string[] {
                "Wake & Play Discord Bridge (" + profile.Id + ")",
                "Wake & Play Vibepollo Bridge (" + profile.Id + ")",
                "Wake & Play Game Provider Bridge (" + profile.Id + ")",
                "Wake & Play Playnite Bridge (" + profile.Id + ")",
                "MoonWaker Profile Bridge (" + profile.Id + ")" })
            {
                if (TaskExists(taskScheduler, taskName))
                    Run(taskScheduler, JoinArguments(new List<string> {
                        "/Delete", "/TN", taskName, "/F" }), 15000, false);
                if (TaskExists(taskScheduler, taskName))
                    throw new InvalidOperationException(
                        "Nie udało się potwierdzić usunięcia zadania startowego profilu.");
            }
            bool exactRoot = String.Equals(expected.TrimEnd('\\'), actual.TrimEnd('\\'),
                StringComparison.OrdinalIgnoreCase);
            if (!exactRoot) return;
            if (File.Exists(actual))
                throw new InvalidOperationException("Ścieżka danych profilu jest plikiem.");
            if (Directory.Exists(actual)) Directory.Delete(actual, true);
        }

        private static RegistryKey OpenMachineRegistry(bool writable)
        {
            RegistryView view = Environment.Is64BitOperatingSystem
                ? RegistryView.Registry64 : RegistryView.Registry32;
            return RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view);
        }

        private static bool HasTrustedProfileRootProvenance(ProfileRecord profile, string root)
        {
            if (!HasValidSid(profile.Sid)) return false;
            try
            {
                using (RegistryKey machine = OpenMachineRegistry(false))
                using (RegistryKey parent = machine.OpenSubKey(
                    @"SOFTWARE\MoonWaker\ProfileRootProvenance", false))
                {
                    if (parent == null || !TrustedRegistryKey(parent)) return false;
                    using (RegistryKey marker = parent.OpenSubKey(profile.Id, false))
                    {
                    if (marker == null || !TrustedRegistryKey(marker)) return false;
                    RegistrySecurity security = marker.GetAccessControl(
                        AccessControlSections.Access | AccessControlSections.Owner);
                    string markerOwner = security.GetOwner(typeof(SecurityIdentifier)).Value;
                    if (!security.AreAccessRulesProtected ||
                        (markerOwner != "S-1-5-18" && markerOwner != "S-1-5-32-544")) return false;
                    bool system = false, administrators = false;
                    foreach (RegistryAccessRule rule in security.GetAccessRules(
                        true, false, typeof(SecurityIdentifier)))
                    {
                        string sid = rule.IdentityReference.Value;
                        if (rule.IsInherited || rule.AccessControlType != AccessControlType.Allow ||
                            (rule.RegistryRights & RegistryRights.FullControl) != RegistryRights.FullControl ||
                            (sid != "S-1-5-18" && sid != "S-1-5-32-544")) return false;
                        if (sid == "S-1-5-18") system = true;
                        if (sid == "S-1-5-32-544") administrators = true;
                    }
                    return system && administrators &&
                        Convert.ToInt32(marker.GetValue("Version", 0)) == 1 &&
                        String.Equals(Convert.ToString(marker.GetValue("ProfileId", "")),
                            profile.Id, StringComparison.Ordinal) &&
                        String.Equals(Convert.ToString(marker.GetValue("ProfileRoot", "")),
                            Path.GetFullPath(root), StringComparison.OrdinalIgnoreCase) &&
                        String.Equals(Convert.ToString(marker.GetValue("OwnerSid", "")),
                            profile.Sid, StringComparison.OrdinalIgnoreCase);
                    }
                }
            }
            catch { return false; }
        }

        private static bool TrustedRegistryKey(RegistryKey key)
        {
            RegistrySecurity security = key.GetAccessControl(
                AccessControlSections.Access | AccessControlSections.Owner);
            string owner = security.GetOwner(typeof(SecurityIdentifier)).Value;
            if (!security.AreAccessRulesProtected ||
                (owner != "S-1-5-18" && owner != "S-1-5-32-544")) return false;
            bool system = false, administrators = false;
            foreach (RegistryAccessRule rule in security.GetAccessRules(
                true, false, typeof(SecurityIdentifier)))
            {
                string sid = rule.IdentityReference.Value;
                if (rule.IsInherited || rule.AccessControlType != AccessControlType.Allow ||
                    (sid != "S-1-5-18" && sid != "S-1-5-32-544") ||
                    (rule.RegistryRights & RegistryRights.FullControl) != RegistryRights.FullControl)
                    return false;
                if (sid == "S-1-5-18") system = true;
                if (sid == "S-1-5-32-544") administrators = true;
            }
            return system && administrators;
        }

        private static void RemoveProfileRootProvenance(ProfileRecord profile, string root)
        {
            if (!HasTrustedProfileRootProvenance(profile, root))
                throw new InvalidOperationException(
                    "Znacznik pochodzenia katalogu profilu zmienił się; profil pozostaje oznaczony do usunięcia.");
            using (RegistryKey machine = OpenMachineRegistry(true))
            using (RegistryKey parent = machine.OpenSubKey(
                @"SOFTWARE\MoonWaker\ProfileRootProvenance", true))
            {
                if (parent == null) throw new InvalidOperationException(
                    "Brak chronionego rejestru pochodzenia katalogów profili.");
                parent.DeleteSubKey(profile.Id, false);
            }
        }

        private static void RequireNoRunningOwnerProcesses(ProfileRecord profile, string profileRoot)
        {
            string rootPrefix = profileRoot.TrimEnd('\\') + "\\";
            string[] markers = new string[] { "MoonWakerProfileBridge.ps1", "DiscordBridge.ps1",
                "VibepolloBridge.ps1", "GameProviderBridge.py", "PlayniteBridge.py" };
            using (ManagementObjectSearcher searcher = new ManagementObjectSearcher(
                "SELECT ProcessId,CommandLine FROM Win32_Process"))
            using (ManagementObjectCollection processes = searcher.Get())
            {
                foreach (ManagementObject process in processes)
                {
                    string commandLine = Convert.ToString(process["CommandLine"]);
                    int processId = Convert.ToInt32(process["ProcessId"]);
                    if (processId <= 4) continue;
                    bool matchesRoot = commandLine.IndexOf(rootPrefix,
                        StringComparison.OrdinalIgnoreCase) >= 0;
                    string escapedId = System.Text.RegularExpressions.Regex.Escape(profile.Id);
                    bool matchesProfileId = System.Text.RegularExpressions.Regex.IsMatch(commandLine,
                        "(?:^|\\s)-ProfileId\\s+(?:\"" + escapedId + "\"|" + escapedId +
                        ")(?:\\s|$)", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
                    bool known = false;
                    foreach (string marker in markers)
                        if (commandLine.IndexOf(marker, StringComparison.OrdinalIgnoreCase) >= 0)
                        { known = true; break; }
                    if (!HasValidSid(profile.Sid))
                    {
                        if (known && (matchesRoot || matchesProfileId))
                            throw new InvalidOperationException(
                                "Starszy profil nie ma SID i nadal działa. Zatrzymaj go lub wyloguj konto, a potem ponów usunięcie.");
                        continue;
                    }
                    ManagementBaseObject input = null;
                    InvokeMethodOptions options = null;
                    ManagementBaseObject owner = process.InvokeMethod("GetOwnerSid", input, options);
                    if (owner == null || (owner.Properties["ReturnValue"] != null &&
                        Convert.ToUInt32(owner["ReturnValue"]) != 0))
                        throw new InvalidOperationException(
                            "Nie można potwierdzić, że konto profilu jest wylogowane. Ponów próbę.");
                    string ownerSid = owner == null ? "" : Convert.ToString(owner["Sid"]);
                    if (String.Equals(ownerSid, profile.Sid, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidOperationException(
                            "Konto Windows profilu nadal ma uruchomione procesy. Wyloguj je i ponów usunięcie; MoonWaker nie wyloguje go automatycznie.");
                }
            }
        }

        private static string TrustedInstalledFile(GatewayRegistryStore store, string directory,
            string name)
        {
            string root = Path.GetFullPath(store.InstallRoot).TrimEnd('\\');
            string control = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(
                Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            DirectoryInfo verifiedRoot = Directory.GetParent(control);
            if (verifiedRoot == null || !String.Equals(root,
                    verifiedRoot.FullName.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                throw new UnauthorizedAccessException(
                    "Rejestr Gateway nie należy do chronionej instalacji konfiguratora.");
            string candidate = Path.GetFullPath(Path.Combine(root, directory, name));
            if (!candidate.StartsWith(root + Path.DirectorySeparatorChar,
                StringComparison.OrdinalIgnoreCase) || !File.Exists(candidate))
                throw new FileNotFoundException("Brak chronionego składnika instalacji MoonWaker.", candidate);
            EnsureOrdinaryPath(candidate);
            return candidate;
        }

        private static string TrustedSystemExecutable(params string[] relativeParts)
        {
            string system = Environment.SystemDirectory;
            if (Environment.Is64BitOperatingSystem && !Environment.Is64BitProcess)
            {
                DirectoryInfo parent = Directory.GetParent(system);
                if (parent == null) throw new InvalidOperationException(
                    "Nie można ustalić chronionego katalogu systemu Windows.");
                system = Path.Combine(parent.FullName, "Sysnative");
            }
            string executable = system;
            foreach (string part in relativeParts) executable = Path.Combine(executable, part);
            executable = Path.GetFullPath(executable);
            if (!File.Exists(executable))
                throw new FileNotFoundException("Brak zaufanego narzędzia systemu Windows.", executable);
            EnsureOrdinaryPath(executable);
            return executable;
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
                string actual = String.IsNullOrWhiteSpace(profile.Root)
                    ? expected : Path.GetFullPath(profile.Root);
                string failedStaging = Path.Combine(store.ProfilesRoot,
                    ".moonwaker-stage-" + profile.Id);
                string retired = Path.Combine(store.ProfilesRoot, ".moonwaker-delete-" +
                    profile.Id + "-" + profile.DeletionNonce);
                bool present = Directory.Exists(actual) || File.Exists(actual) ||
                    Directory.Exists(failedStaging) || File.Exists(failedStaging) ||
                    Directory.Exists(retired) || File.Exists(retired);
                return !String.Equals(expected.TrimEnd('\\'), actual.TrimEnd('\\'),
                    StringComparison.OrdinalIgnoreCase);
            }
            catch { return true; }
        }

        private static void EnsureOrdinaryPath(string path)
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

        private static FileSystemSecurity RemovalSecurity(bool directory)
        {
            FileSystemSecurity security = directory
                ? (FileSystemSecurity)new DirectorySecurity() : new FileSecurity();
            security.SetAccessRuleProtection(true, false);
            security.SetOwner(new SecurityIdentifier(
                WellKnownSidType.BuiltinAdministratorsSid, null));
            InheritanceFlags inheritance = directory
                ? InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit
                : InheritanceFlags.None;
            foreach (SecurityIdentifier sid in new SecurityIdentifier[] {
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null) })
                security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                    inheritance, PropagationFlags.None, AccessControlType.Allow));
            return security;
        }

        private static DirectorySecurity ProfilesRootSecurity()
        {
            DirectorySecurity security = new DirectorySecurity();
            security.SetAccessRuleProtection(true, false);
            security.SetOwner(new SecurityIdentifier(
                WellKnownSidType.BuiltinAdministratorsSid, null));
            foreach (SecurityIdentifier sid in new SecurityIdentifier[] {
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null) })
                security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                    InheritanceFlags.None, PropagationFlags.None, AccessControlType.Allow));
            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null),
                FileSystemRights.ReadAndExecute, InheritanceFlags.None,
                PropagationFlags.None, AccessControlType.Allow));
            return security;
        }

        private static void ProtectProfilesRootForManagement(string profilesRoot)
        {
            profilesRoot = Path.GetFullPath(profilesRoot);
            EnsureOrdinaryPath(profilesRoot);
            DirectoryInfo root = new DirectoryInfo(profilesRoot);
            DirectorySecurity security = ProfilesRootSecurity();
            if (root.Exists) Directory.SetAccessControl(profilesRoot, security);
            else root.Create(security);
            EnsureOrdinaryPath(profilesRoot);
            DirectorySecurity applied = Directory.GetAccessControl(profilesRoot,
                AccessControlSections.Access | AccessControlSections.Owner);
            string owner = applied.GetOwner(typeof(SecurityIdentifier)).Value;
            if (!applied.AreAccessRulesProtected ||
                (owner != "S-1-5-18" && owner != "S-1-5-32-544"))
                throw new InvalidOperationException(
                    "Nie udało się ochronić katalogu profili przed zmianami innych użytkowników.");
        }

        private static void ProtectTreeForRemoval(string profileRoot)
        {
            EnsureOrdinaryPath(profileRoot);
            Directory.SetAccessControl(profileRoot, (DirectorySecurity)RemovalSecurity(true));
            Stack<DirectoryInfo> pending = new Stack<DirectoryInfo>();
            pending.Push(new DirectoryInfo(profileRoot));
            while (pending.Count != 0)
            {
                foreach (FileSystemInfo item in pending.Pop().GetFileSystemInfos())
                {
                    if ((item.Attributes & FileAttributes.ReparsePoint) != 0)
                        throw new InvalidOperationException(
                            "Katalog profilu zawiera dowiązanie; usunięcie zostało zatrzymane.");
                    DirectoryInfo directory = item as DirectoryInfo;
                    if (directory != null)
                    {
                        Directory.SetAccessControl(directory.FullName,
                            (DirectorySecurity)RemovalSecurity(true));
                        pending.Push(directory);
                    }
                    else File.SetAccessControl(item.FullName, (FileSecurity)RemovalSecurity(false));
                }
            }
        }

        private static int Run(string fileName, string arguments, int timeoutMilliseconds,
            bool ignoreExitCode)
        {
            ProcessStartInfo info = new ProcessStartInfo(fileName, arguments);
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            using (Process process = Process.Start(info))
            {
                System.Threading.Tasks.Task<string> output = process.StandardOutput.ReadToEndAsync();
                System.Threading.Tasks.Task<string> error = process.StandardError.ReadToEndAsync();
                if (!process.WaitForExit(timeoutMilliseconds))
                {
                    try { process.Kill(); } catch { }
                    throw new TimeoutException("Konfiguracja profilu przekroczyła limit czasu.");
                }
                process.WaitForExit();
                if (!ignoreExitCode && process.ExitCode != 0)
                {
                    System.Threading.Tasks.Task.WaitAll(new System.Threading.Tasks.Task[] {
                        output, error }, 2000);
                    string message = String.IsNullOrWhiteSpace(error.Result) ? output.Result : error.Result;
                    throw new InvalidOperationException(String.IsNullOrWhiteSpace(message)
                        ? "Instalator profilu zakończył się błędem." : message.Trim());
                }
                return process.ExitCode;
            }
        }

        private static bool TaskExists(string taskScheduler, string taskName)
        {
            ProcessStartInfo info = new ProcessStartInfo(taskScheduler, "/Query /FO CSV /NH");
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            using (Process process = Process.Start(info))
            {
                string output = process.StandardOutput.ReadToEnd();
                string error = process.StandardError.ReadToEnd();
                if (!process.WaitForExit(15000))
                    throw new TimeoutException("Nie można potwierdzić stanu zadań profilu.");
                if (process.ExitCode != 0)
                    throw new InvalidOperationException(String.IsNullOrWhiteSpace(error)
                        ? "Nie można potwierdzić stanu Harmonogramu zadań."
                        : error.Trim());
                foreach (string line in output.Split(new string[] { "\r\n", "\n" },
                    StringSplitOptions.RemoveEmptyEntries))
                {
                    if (!line.StartsWith("\"", StringComparison.Ordinal)) continue;
                    int end = line.IndexOf("\",", 1, StringComparison.Ordinal);
                    if (end < 0) continue;
                    string scheduledName = line.Substring(1, end - 1).Replace("\"\"", "\"");
                    if (String.Equals(scheduledName.TrimStart('\\'), taskName,
                            StringComparison.OrdinalIgnoreCase)) return true;
                }
                return false;
            }
        }

        internal static string JoinArguments(IEnumerable<string> values)
        {
            StringBuilder result = new StringBuilder();
            foreach (string value in values)
            {
                if (result.Length > 0) result.Append(' ');
                result.Append(QuoteArgument(value ?? ""));
            }
            return result.ToString();
        }

        private static string QuoteArgument(string value)
        {
            if (value.Length > 0 && value.IndexOfAny(new char[] { ' ', '\t', '\n', '\v', '"' }) < 0)
                return value;
            StringBuilder result = new StringBuilder("\"");
            int slashes = 0;
            foreach (char character in value)
            {
                if (character == '\\') { slashes++; continue; }
                if (character == '"')
                {
                    result.Append('\\', (slashes * 2) + 1);
                    result.Append('"');
                    slashes = 0;
                    continue;
                }
                if (slashes > 0) { result.Append('\\', slashes); slashes = 0; }
                result.Append(character);
            }
            if (slashes > 0) result.Append('\\', slashes * 2);
            result.Append('"');
            return result.ToString();
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

        internal BrokerResult ListSupportedLocalAccounts()
        {
            return Send(BrokerOperation.ListSupportedLocalAccounts,
                new Dictionary<byte, object>());
        }

        internal BrokerResult Configure(ProfileRecord profile, char[] password)
        {
            Dictionary<byte, object> fields = BasicFields(profile);
            fields[4] = password;
            return Send(BrokerOperation.ConfigureProfileCredential, fields);
        }

        internal BrokerResult Test(ProfileRecord profile)
        {
            return Send(BrokerOperation.TestProfileCredential, BasicFields(profile));
        }

        internal BrokerResult Delete(ProfileRecord profile)
        {
            Dictionary<byte, object> fields = BasicFields(profile);
            // The durable removal nonce/generation makes retries the same
            // idempotent deletion operation for the future SYSTEM Broker peer.
            fields[5] = profile.DeletionNonce;
            fields[6] = profile.DeletionGeneration.ToString(
                System.Globalization.CultureInfo.InvariantCulture);
            return Send(BrokerOperation.DeleteProfileCredential, fields);
        }

        internal BrokerResult CredentialState(ProfileRecord profile)
        {
            return Send(BrokerOperation.GetProfileCredentialState, BasicFields(profile));
        }

        internal BrokerResult SessionState(ProfileRecord profile)
        {
            return Send(BrokerOperation.GetProfileSessionState, BasicFields(profile));
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

    internal sealed class AddProfileForm : Form
    {
        private readonly GatewayRegistryStore store;
        private readonly ComboBox accounts = new ComboBox();
        private readonly TextBox displayName;
        private readonly Label status;
        private readonly Button create;

        internal AddProfileForm(GatewayRegistryStore store)
        {
            this.store = store;
            Ui.Prepare(this, "Dodaj profil MoonWaker", 720, 410);
            Ui.Label(this, "Dodaj profil", 28, 22, 600, 38, 18F, FontStyle.Bold, Color.White);
            Ui.Label(this, "Wybierz obsługiwane lokalne konto Windows. SID zostanie przypisany na stałe.",
                30, 64, 650, 38, 9.5F, FontStyle.Regular, Ui.Muted);
            Ui.Label(this, "Konto Windows", 30, 112, 200, 24, 9.5F, FontStyle.Bold, Color.White);
            accounts.SetBounds(30, 138, 650, 30);
            accounts.DropDownStyle = ComboBoxStyle.DropDownList;
            accounts.BackColor = Ui.Panel;
            accounts.ForeColor = Color.White;
            accounts.SelectedIndexChanged += delegate {
                LocalAccount selected = accounts.SelectedItem as LocalAccount;
                if (selected != null && String.IsNullOrWhiteSpace(displayName.Text))
                    displayName.Text = selected.Name;
            };
            Controls.Add(accounts);
            Ui.Label(this, "Nazwa profilu MoonWaker", 30, 188, 260, 24, 9.5F,
                FontStyle.Bold, Color.White);
            displayName = Ui.TextBox(this, 30, 214, 650);
            displayName.MaxLength = 80;
            status = Ui.Label(this, "", 30, 258, 650, 50, 9F, FontStyle.Regular, Ui.Muted);
            create = Ui.Button(this, "Utwórz profil", 436, 338, 142, CreateClicked, true);
            Button cancel = Ui.Button(this, "Anuluj", 588, 338, 92,
                delegate { Close(); }, false);
            CancelButton = cancel;
            Shown += delegate { LoadAccounts(); };
        }

        private void LoadAccounts()
        {
            try
            {
                HashSet<string> used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach (ProfileRecord profile in store.GetProfiles())
                    if (!String.IsNullOrWhiteSpace(profile.Sid)) used.Add(profile.Sid);
                foreach (LocalAccount account in LocalAccountEnumerator.EnumerateSupported())
                    if (!used.Contains(account.Sid)) accounts.Items.Add(account);
                if (accounts.Items.Count > 0) accounts.SelectedIndex = 0;
                else
                {
                    status.Text = "Brak wolnego, obsługiwanego lokalnego konta Windows.";
                    status.ForeColor = Ui.Danger;
                    create.Enabled = false;
                }
            }
            catch (Exception ex)
            {
                status.Text = ex.Message;
                status.ForeColor = Ui.Danger;
                create.Enabled = false;
            }
        }

        private async void CreateClicked(object sender, EventArgs e)
        {
            LocalAccount account = accounts.SelectedItem as LocalAccount;
            if (account == null) return;
            create.Enabled = false;
            ProfileRecord reservation = null;
            bool finalized = false;
            try
            {
                status.Text = "Rezerwuję profil i instaluję Bridge…";
                status.ForeColor = Ui.Muted;
                reservation = store.ReserveProfile(account, displayName.Text);
                await System.Threading.Tasks.Task.Run(delegate {
                    store.ProvisionAndFinalize(reservation, delegate {
                        ProfileProvisioner.Provision(store, reservation);
                    });
                });
                finalized = true;
                BrokerResult brokerState = new LoginBrokerClient().CredentialState(reservation);
                DialogResult configureRemote = brokerState.State == "broker_unavailable" ||
                    brokerState.State == "unavailable"
                    ? MessageBox.Show(this,
                        "Profil został utworzony dla " + reservation.AccountName +
                        ". Zdalne logowanie będzie dostępne po zainstalowaniu usługi Login Broker. " +
                        "Discord i Vibepollo skonfigurujesz przyciskiem Integracje w Host Control.",
                        "MoonWaker Host Configurator", MessageBoxButtons.OK,
                        MessageBoxIcon.Information)
                    : MessageBox.Show(this,
                        "Profil został utworzony dla " + reservation.AccountName +
                        ". Zdalne logowanie pozostaje wyłączone, dopóki nie zapiszesz hasła w Login Broker.\n\n" +
                        "Skonfigurować je teraz? Discord i Vibepollo ustawisz później przyciskiem Integracje w Host Control.",
                        "MoonWaker Host Configurator", MessageBoxButtons.YesNo,
                        MessageBoxIcon.Information);
                if (configureRemote == DialogResult.Yes)
                {
                    using (RemoteSignInForm remote = new RemoteSignInForm(store, reservation.Id))
                        remote.ShowDialog(this);
                    ProfileRecord current = store.GetProfile(reservation.Id);
                    if (current.RemoteSignInEnabled && MessageBox.Show(this,
                        "Dane logowania są gotowe. Wybrać urządzenia uprawnione do zdalnego logowania?",
                        "MoonWaker Host Configurator", MessageBoxButtons.YesNo,
                        MessageBoxIcon.Warning) == DialogResult.Yes)
                        using (DeviceGrantsForm devices = new DeviceGrantsForm(store, reservation.Id))
                            devices.ShowDialog(this);
                }
                Close();
            }
            catch (Exception ex)
            {
                string message = ex.Message;
                if (reservation != null && !finalized)
                {
                    try
                    {
                        store.PrepareRemoval(reservation);
                        message += " Profil pozostawiono wyłączony i oznaczony do jawnego usunięcia; " +
                            "usuń go w Host Control, a następnie ponów dodawanie.";
                    }
                    catch (Exception tombstoneError)
                    {
                        message += " Nie udało się utrwalić znacznika naprawy: " +
                            tombstoneError.Message;
                    }
                }
                status.Text = message;
                status.ForeColor = Ui.Danger;
                create.Enabled = true;
            }
        }
    }

    internal sealed class EditProfileForm : Form
    {
        private readonly GatewayRegistryStore store;
        private readonly ProfileRecord profile;
        private readonly TextBox displayName;
        private readonly CheckBox enabled = new CheckBox();

        internal EditProfileForm(GatewayRegistryStore store, string profileId)
        {
            this.store = store;
            profile = store.GetProfile(profileId);
            profile.EnsureMutable();
            Ui.Prepare(this, "Edytuj profil MoonWaker", 720, 430);
            Ui.Label(this, "Edytuj profil", 28, 22, 600, 38, 18F, FontStyle.Bold, Color.White);
            Ui.Label(this, "Konto Windows", 30, 78, 200, 22, 9F, FontStyle.Bold, Color.White);
            Ui.Label(this, profile.AccountName, 230, 78, 450, 22, 9F, FontStyle.Regular, Ui.Muted);
            Ui.Label(this, "SID (niezmienny)", 30, 108, 200, 22, 9F, FontStyle.Bold, Color.White);
            Ui.Label(this, profile.Sid, 230, 108, 450, 22, 9F, FontStyle.Regular, Ui.Muted);
            Ui.Label(this, "Nazwa profilu", 30, 154, 200, 22, 9F, FontStyle.Bold, Color.White);
            displayName = Ui.TextBox(this, 230, 150, 450);
            displayName.Text = profile.DisplayName;
            displayName.MaxLength = 80;
            enabled.Text = "Profil włączony";
            enabled.Checked = profile.Enabled;
            enabled.SetBounds(230, 198, 260, 28);
            enabled.ForeColor = Color.White;
            Controls.Add(enabled);
            Ui.Label(this, "Zmiana konta Windows wymaga usunięcia i ponownego utworzenia profilu.",
                30, 244, 650, 38, 9F, FontStyle.Regular, Ui.Muted);
            Ui.Button(this, "Zdalne logowanie…", 30, 294, 190,
                delegate { using (RemoteSignInForm form = new RemoteSignInForm(store, profile.Id))
                    form.ShowDialog(this); }, false);
            Ui.Button(this, "Urządzenia i dostęp…", 230, 294, 190,
                delegate { using (DeviceGrantsForm form = new DeviceGrantsForm(store, profile.Id))
                    form.ShowDialog(this); }, false);
            Ui.Button(this, "PIN aplikacji…", 430, 294, 150,
                delegate { using (AppPinForm form = new AppPinForm(store, profile.Id))
                    form.ShowDialog(this); }, false);
            Ui.Button(this, "Zapisz", 488, 370, 92, SaveClicked, true);
            Button cancel = Ui.Button(this, "Anuluj", 588, 370, 92,
                delegate { Close(); }, false);
            CancelButton = cancel;
        }

        private void SaveClicked(object sender, EventArgs e)
        {
            try
            {
                store.UpdateProfile(profile.Id, profile.Sid, displayName.Text, enabled.Checked);
                MessageBox.Show(this, "Zmiany zapisano. Gateway wczyta je automatycznie.",
                    "MoonWaker Host Configurator", MessageBoxButtons.OK, MessageBoxIcon.Information);
                Close();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }

    internal sealed class AppPinForm : Form
    {
        private readonly GatewayRegistryStore store;
        private readonly ProfileRecord profile;
        private readonly TextBox pin;
        private readonly TextBox confirmation;
        private readonly Button remove;

        internal AppPinForm(GatewayRegistryStore store, string profileId)
        {
            this.store = store;
            profile = store.GetProfile(profileId);
            profile.EnsureMutable();
            Ui.Prepare(this, "PIN aplikacji MoonWaker", 620, 360);
            Ui.Label(this, "PIN aplikacji MoonWaker", 28, 22, 560, 38,
                18F, FontStyle.Bold, Color.White);
            Ui.Label(this,
                "To czterocyfrowy PIN aplikacji MoonWaker, nie hasło Windows.",
                30, 68, 550, 30, 9.5F, FontStyle.Regular, Ui.Muted);
            Ui.Label(this, profile.AppPinRequired
                ? "Wpisz nowy PIN, aby zastąpić obecny." : "Ustaw PIN dla tego profilu.",
                30, 104, 550, 24, 9F, FontStyle.Regular, Ui.Muted);
            Ui.Label(this, "Nowy PIN", 30, 144, 180, 22, 9F,
                FontStyle.Bold, Color.White);
            pin = Ui.TextBox(this, 210, 140, 180);
            pin.MaxLength = 4;
            pin.UseSystemPasswordChar = true;
            Ui.Label(this, "Powtórz PIN", 30, 184, 180, 22, 9F,
                FontStyle.Bold, Color.White);
            confirmation = Ui.TextBox(this, 210, 180, 180);
            confirmation.MaxLength = 4;
            confirmation.UseSystemPasswordChar = true;
            Ui.Button(this, profile.AppPinRequired ? "Zmień PIN" : "Ustaw PIN",
                330, 284, 110, SaveClicked, true);
            remove = Ui.Button(this, "Usuń PIN", 202, 284, 118, RemoveClicked, false);
            remove.Enabled = profile.AppPinRequired;
            Button cancel = Ui.Button(this, "Anuluj", 450, 284, 110,
                delegate { ClearAndClose(); }, false);
            CancelButton = cancel;
        }

        private void SaveClicked(object sender, EventArgs e)
        {
            string value = pin.Text;
            string repeated = confirmation.Text;
            try
            {
                if (!String.Equals(value, repeated, StringComparison.Ordinal))
                    throw new InvalidOperationException("Wpisane PIN-y aplikacji nie są identyczne.");
                store.SetAppPin(profile.Id, profile.Sid, value);
                MessageBox.Show(this, "PIN aplikacji MoonWaker zapisano.",
                    "MoonWaker Host Configurator", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                ClearAndClose();
            }
            catch (Exception ex)
            {
                pin.Clear();
                confirmation.Clear();
                MessageBox.Show(this, ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                value = null;
                repeated = null;
            }
        }

        private void RemoveClicked(object sender, EventArgs e)
        {
            if (MessageBox.Show(this, "Usunąć PIN aplikacji MoonWaker dla tego profilu?",
                "MoonWaker Host Configurator", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes) return;
            try
            {
                store.RemoveAppPin(profile.Id, profile.Sid);
                MessageBox.Show(this, "PIN aplikacji MoonWaker usunięto.",
                    "MoonWaker Host Configurator", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                ClearAndClose();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void ClearAndClose()
        {
            pin.Clear();
            confirmation.Clear();
            Close();
        }
    }

    internal sealed class RemoteSignInForm : Form
    {
        private readonly GatewayRegistryStore store;
        private ProfileRecord profile;
        private readonly LoginBrokerClient broker = new LoginBrokerClient();
        private readonly Label credentialState;
        private readonly Label sessionState;
        private readonly TextBox password;
        private readonly TextBox confirmation;
        private readonly Button configure;
        private readonly Button test;

        internal RemoteSignInForm(GatewayRegistryStore store, string profileId)
        {
            this.store = store;
            profile = store.GetProfile(profileId);
            profile.EnsureMutable();
            Ui.Prepare(this, "Zdalne logowanie Windows", 760, 550);
            Ui.Label(this, "Zdalne logowanie Windows", 28, 20, 680, 38, 18F,
                FontStyle.Bold, Color.White);
            Ui.Label(this, profile.DisplayName + "  •  " + profile.AccountName, 30, 62, 690, 24,
                9.5F, FontStyle.Regular, Ui.Muted);
            Label warning = Ui.Label(this,
                "UPRZYWILEJOWANE: zapisane dane pozwalają zatwierdzonemu urządzeniu zalogować lub odblokować to konto.",
                30, 100, 690, 46, 9.5F, FontStyle.Bold, Ui.Danger);
            warning.AutoEllipsis = true;
            Ui.Label(this, "Dane logowania:", 30, 158, 160, 24, 9F, FontStyle.Bold, Color.White);
            credentialState = Ui.Label(this, "sprawdzanie…", 190, 158, 300, 24, 9F,
                FontStyle.Regular, Ui.Muted);
            Ui.Label(this, "Sesja Windows:", 30, 188, 160, 24, 9F, FontStyle.Bold, Color.White);
            sessionState = Ui.Label(this, "sprawdzanie…", 190, 188, 300, 24, 9F,
                FontStyle.Regular, Ui.Muted);
            Ui.Label(this, "Nowe hasło Windows", 30, 236, 300, 22, 9F, FontStyle.Bold, Color.White);
            password = Ui.TextBox(this, 30, 260, 700);
            password.UseSystemPasswordChar = true;
            password.MaxLength = 256;
            Ui.Label(this, "Powtórz hasło", 30, 304, 300, 22, 9F, FontStyle.Bold, Color.White);
            confirmation = Ui.TextBox(this, 30, 328, 700);
            confirmation.UseSystemPasswordChar = true;
            confirmation.MaxLength = 256;
            configure = Ui.Button(this, "Zapisz / zmień", 30, 390, 160,
                ConfigureClicked, true);
            test = Ui.Button(this, "Sprawdź zapisane", 200, 390, 170,
                TestClicked, false);
            Button disable = Ui.Button(this, "Wyłącz i usuń dane", 380, 390, 190,
                DisableClicked, false);
            disable.ForeColor = Ui.Danger;
            Button close = Ui.Button(this, "Zamknij", 630, 490, 100,
                delegate { ClearPasswordControls(); Close(); }, false);
            bool supported = profile.MappingStatus == "resolved" &&
                !String.IsNullOrWhiteSpace(profile.Sid);
            password.Enabled = supported;
            confirmation.Enabled = supported;
            configure.Enabled = supported;
            test.Enabled = supported;
            CancelButton = close;
            FormClosed += delegate { ClearPasswordControls(); };
            Shown += delegate { RefreshStates(); };
        }

        private void RefreshStates()
        {
            profile = store.GetProfile(profile.Id);
            if (profile.MappingStatus != "resolved" || String.IsNullOrWhiteSpace(profile.Sid))
            {
                credentialState.Text = "konto nieobsługiwane / mapowanie wymaga naprawy";
                credentialState.ForeColor = Ui.Danger;
                sessionState.Text = "nieznany";
                return;
            }
            BrokerResult credential = broker.CredentialState(profile);
            bool brokerAvailable = credential.State != "broker_unavailable" &&
                credential.State != "unavailable";
            password.Enabled = brokerAvailable;
            confirmation.Enabled = brokerAvailable;
            configure.Enabled = brokerAvailable;
            test.Enabled = brokerAvailable;
            if (!brokerAvailable)
            {
                credentialState.Text = "Login Broker niedostępny — zdalne logowanie pojawi się po instalacji usługi";
                credentialState.ForeColor = Ui.Muted;
                sessionState.Text = "niedostępna";
                sessionState.ForeColor = Ui.Muted;
                return;
            }
            BrokerResult session = broker.SessionState(profile);
            string state = profile.RemoteSignInEnabled ? credential.State : "disabled";
            credentialState.Text = Ui.BrokerStatePolish(state);
            credentialState.ForeColor = state == "ready" ? Ui.Success :
                (state == "disabled" ? Ui.Muted : Ui.Danger);
            sessionState.Text = String.IsNullOrWhiteSpace(session.State) ? "unknown" : session.State;
            sessionState.ForeColor = session.Success ? Ui.Muted : Ui.Danger;
        }

        private void ConfigureClicked(object sender, EventArgs e)
        {
            char[] first = password.Text.ToCharArray();
            char[] second = confirmation.Text.ToCharArray();
            ClearPasswordControls();
            try
            {
                if (first.Length == 0 || !Same(first, second))
                    throw new InvalidOperationException("Hasła są puste albo różnią się.");
                BrokerResult result = broker.Configure(profile, first);
                if (!result.Success)
                {
                    // No client grant exists yet. If delivery was ambiguous, keep the
                    // policy bit set so removal/Disable retries cleanup with Broker.
                    if (result.RequestMayHaveReachedBroker)
                        store.SetRemoteSignInEnabled(profile.Id, profile.Sid, true);
                    throw new InvalidOperationException(result.State == "broker_unavailable"
                        ? (result.RequestMayHaveReachedBroker
                            ? "Login Broker nie potwierdził wyniku. Nie nadano żadnego grantu; po uruchomieniu usługi użyj opcji Wyłącz, aby potwierdzić usunięcie danych."
                            : "Login Broker jest niedostępny. Hasło nie zostało zapisane.")
                        : "Login Broker odrzucił dane: " + Ui.BrokerStatePolish(result.State) + ".");
                }
                if (result.State != "ready")
                    throw new InvalidOperationException("Login Broker nie potwierdził gotowości danych logowania.");
                store.SetRemoteSignInEnabled(profile.Id, profile.Sid, true);
                MessageBox.Show(this,
                    "Hasło zostało zweryfikowane i przekazane bezpośrednio do Login Broker. " +
                    "Nadaj uprawnienie urządzeniom osobno.",
                    "MoonWaker Host Configurator", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                Array.Clear(first, 0, first.Length);
                Array.Clear(second, 0, second.Length);
                RefreshStates();
            }
        }

        private void TestClicked(object sender, EventArgs e)
        {
            ClearPasswordControls();
            BrokerResult result = broker.Test(profile);
            MessageBox.Show(this, result.Success && result.State == "ready"
                ? "Login Broker ponownie zweryfikował zapisane dane."
                : (result.State == "broker_unavailable"
                    ? "Login Broker jest niedostępny. Nie wykonano testu."
                    : "Dane wymagają działania w Host Control."),
                "MoonWaker Host Configurator", MessageBoxButtons.OK,
                result.Success ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            RefreshStates();
        }

        private void DisableClicked(object sender, EventArgs e)
        {
            ClearPasswordControls();
            if (MessageBox.Show(this,
                "Wyłączyć zdalne logowanie, cofnąć uprawnienia remote_sign_in i usunąć dane z Login Broker?",
                "Zdalne logowanie Windows", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes) return;
            store.SetRemoteSignInEnabled(profile.Id, profile.Sid, false);
            BrokerResult result = broker.Delete(profile);
            if (!result.Success)
            {
                // Grants stay revoked. The policy bit records that Broker cleanup
                // still has to be retried; it cannot authorize a device by itself.
                store.SetRemoteSignInEnabled(profile.Id, profile.Sid, true);
                MessageBox.Show(this,
                    "Granty zdalnego logowania zostały cofnięte, ale Login Broker nie potwierdził usunięcia sekretu. " +
                    "Uruchom usługę i ponów operację.", "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            else
                MessageBox.Show(this, "Zdalne logowanie wyłączono, a dane usunięto z Login Broker.",
                    "MoonWaker Host Configurator", MessageBoxButtons.OK, MessageBoxIcon.Information);
            RefreshStates();
        }

        private void ClearPasswordControls()
        {
            password.Clear();
            confirmation.Clear();
        }

        private static bool Same(char[] left, char[] right)
        {
            int different = left.Length ^ right.Length;
            int count = Math.Min(left.Length, right.Length);
            for (int index = 0; index < count; index++) different |= left[index] ^ right[index];
            return different == 0;
        }
    }

    internal sealed class DeviceGrantsForm : Form
    {
        private readonly GatewayRegistryStore store;
        private readonly ProfileRecord profile;
        private readonly DataGridView grid = new DataGridView();
        private bool loading;

        internal DeviceGrantsForm(GatewayRegistryStore store, string profileId)
        {
            this.store = store;
            profile = store.GetProfile(profileId);
            profile.EnsureMutable();
            Ui.Prepare(this, "Urządzenia i dostęp", 940, 570);
            Ui.Label(this, "Urządzenia i dostęp", 24, 18, 700, 38, 18F, FontStyle.Bold, Color.White);
            Ui.Label(this, "Profil: " + profile.DisplayName, 26, 58, 500, 24, 9.5F,
                FontStyle.Regular, Ui.Muted);
            Ui.Label(this,
                "Zdalne logowanie jest uprawnieniem podwyższonego ryzyka. Wymaga zwykłego dostępu i osobnego potwierdzenia.",
                26, 88, 880, 34, 9F, FontStyle.Bold, Ui.Danger);
            grid.SetBounds(24, 130, 892, 360);
            grid.AllowUserToAddRows = false;
            grid.AllowUserToDeleteRows = false;
            grid.MultiSelect = false;
            grid.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
            grid.RowHeadersVisible = false;
            grid.BackgroundColor = Ui.Panel;
            grid.ForeColor = Color.Black;
            grid.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
            grid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Urządzenie", ReadOnly = true });
            grid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Bezpieczne ID", ReadOnly = true });
            grid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Sparowano", ReadOnly = true });
            grid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Ostatnio widziane", ReadOnly = true });
            grid.Columns.Add(new DataGridViewCheckBoxColumn { HeaderText = "Dostęp do profilu" });
            grid.Columns.Add(new DataGridViewCheckBoxColumn { HeaderText = "ZDALNE LOGOWANIE" });
            grid.CurrentCellDirtyStateChanged += delegate {
                if (grid.IsCurrentCellDirty) grid.CommitEdit(DataGridViewDataErrorContexts.Commit);
            };
            grid.CellValueChanged += CellValueChanged;
            Controls.Add(grid);
            Button remove = Ui.Button(this, "Usuń urządzenie", 24, 512, 150,
                RemoveClicked, false);
            remove.ForeColor = Ui.Danger;
            Ui.Button(this, "Zapisz uprawnienia", 666, 512, 160, SaveClicked, true);
            Button close = Ui.Button(this, "Anuluj", 834, 512, 82,
                delegate { Close(); }, false);
            CancelButton = close;
            Shown += delegate { LoadDevices(); };
        }

        private void RemoveClicked(object sender, EventArgs e)
        {
            DataGridViewRow row = grid.CurrentRow;
            string id = row == null ? null : row.Tag as string;
            if (String.IsNullOrWhiteSpace(id))
            {
                MessageBox.Show(this, "Wybierz urządzenie do usunięcia.", "MoonWaker",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            string name = Convert.ToString(row.Cells[0].Value);
            if (MessageBox.Show(this,
                "Usunąć parowanie urządzenia „" + name + "”? Utraci ono dostęp do wszystkich profili " +
                "i będzie wymagało ponownego sparowania.", "Usuń urządzenie",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            try
            {
                store.RemoveClient(id);
                LoadDevices();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void LoadDevices()
        {
            loading = true;
            try
            {
                grid.Rows.Clear();
                foreach (DeviceRecord device in store.GetDevices(profile.Id))
                {
                    int index = grid.Rows.Add(device.Name, device.SafeId, FormatTime(device.PairedAt),
                        FormatTime(device.LastSeenAt), device.UseProfile, device.RemoteSignIn);
                    grid.Rows[index].Tag = device.Id;
                }
            }
            finally { loading = false; }
        }

        private void CellValueChanged(object sender, DataGridViewCellEventArgs e)
        {
            if (loading || e.RowIndex < 0 || e.ColumnIndex < 4) return;
            DataGridViewRow row = grid.Rows[e.RowIndex];
            if (e.ColumnIndex == 4 && !Convert.ToBoolean(row.Cells[4].Value))
            {
                loading = true;
                row.Cells[5].Value = false;
                loading = false;
            }
            if (e.ColumnIndex == 5 && Convert.ToBoolean(row.Cells[5].Value))
            {
                string name = Convert.ToString(row.Cells[0].Value);
                DialogResult confirmed = MessageBox.Show(this,
                    "Nadać urządzeniu „" + name + "” uprzywilejowane prawo do zdalnego zalogowania lub " +
                    "odblokowania konta " + profile.AccountName + "?",
                    "Potwierdź zdalne logowanie", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                loading = true;
                if (confirmed == DialogResult.Yes) row.Cells[4].Value = true;
                else row.Cells[5].Value = false;
                loading = false;
            }
        }

        private void SaveClicked(object sender, EventArgs e)
        {
            try
            {
                Dictionary<string, DeviceGrant> grants = new Dictionary<string, DeviceGrant>();
                foreach (DataGridViewRow row in grid.Rows)
                {
                    string id = row.Tag as string;
                    if (String.IsNullOrWhiteSpace(id)) continue;
                    grants[id] = new DeviceGrant {
                        UseProfile = Convert.ToBoolean(row.Cells[4].Value),
                        RemoteSignIn = Convert.ToBoolean(row.Cells[5].Value)
                    };
                }
                store.UpdateGrants(profile.Id, profile.Sid, grants);
                MessageBox.Show(this, "Uprawnienia zapisano. Gateway zastosuje je przy następnym żądaniu.",
                    "MoonWaker Host Configurator", MessageBoxButtons.OK, MessageBoxIcon.Information);
                Close();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static string FormatTime(long unix)
        {
            if (unix <= 0) return "nigdy";
            try { return DateTimeOffset.FromUnixTimeSeconds(unix).ToLocalTime().ToString("g"); }
            catch { return "nieznany"; }
        }
    }

    internal sealed class RemoveProfileForm : Form
    {
        private readonly GatewayRegistryStore store;
        private readonly ProfileRecord profile;
        private readonly Button remove;

        internal RemoveProfileForm(GatewayRegistryStore store, string profileId)
        {
            this.store = store;
            profile = store.GetProfile(profileId);
            bool offline = ProfileProvisioner.RequiresOfflineCleanup(store, profile);
            bool unresolved = !ProfileProvisioner.HasAuthoritativeOwner(profile);
            Ui.Prepare(this, "Usuń profil MoonWaker", 700, 390);
            Ui.Label(this, "Usuń profil MoonWaker", 28, 22, 620, 38, 18F, FontStyle.Bold, Color.White);
            Label scope = Ui.Label(this,
                offline
                    ? "Ta operacja bezpiecznie wyłączy profil, cofnie granty, usunie sekret Login Broker i dokładne " +
                      "zadania startowe. Katalog profilu pozostanie nienaruszony do ręcznego sprzątania offline. " +
                      "Konto Windows ani jego pliki użytkownika nie zostaną zmienione."
                    : "Ta operacja usuwa wyłącznie dane profilu MoonWaker, jego Bridge, autostart, granty urządzeń " +
                      "i sekret Login Broker. Konto Windows „" + profile.AccountName + "” oraz jego pliki użytkownika " +
                      "nie zostaną usunięte ani zmienione.",
                30, 78, 640, 112, 10F, FontStyle.Regular, Ui.Muted);
            scope.AutoEllipsis = true;
            Ui.Label(this, "Profil: " + profile.DisplayName + "  •  " + profile.Id,
                30, 210, 640, 28, 10F, FontStyle.Bold, Ui.Danger);
            if (offline)
                Ui.Label(this,
                    "Brak zaufanego znacznika utworzenia: MoonWaker nie przejdzie ani nie usunie tego katalogu online. " +
                    (unresolved
                        ? "Po oznaczeniu możesz od razu dodać nowy profil SID; stary katalog sprząta administrator offline."
                        : "Po sprzątaniu offline ponów usunięcie, aby zwolnić SID przed utworzeniem nowego profilu."),
                    30, 250, 620, 52, 9.5F, FontStyle.Bold, Ui.Danger);
            else if (String.Equals(profile.Id, "default", StringComparison.OrdinalIgnoreCase))
                Ui.Label(this,
                    "To profil zgodności. Usunięcie dotyczy wyłącznie MoonWaker i nigdy nie zmieni konta Windows.",
                    30, 250, 620, 52, 9.5F, FontStyle.Bold, Ui.Danger);
            remove = Ui.Button(this, offline ? "Wyłącz; sprzątanie offline" : "Usuń dane profilu",
                386, 330, 200, RemoveClicked, false);
            remove.ForeColor = Ui.Danger;
            Button cancel = Ui.Button(this, "Anuluj", 596, 330, 74,
                delegate { Close(); }, false);
            CancelButton = cancel;
        }

        private async void RemoveClicked(object sender, EventArgs e)
        {
            bool offline = ProfileProvisioner.RequiresOfflineCleanup(store, profile);
            bool unresolved = !ProfileProvisioner.HasAuthoritativeOwner(profile);
            bool compatibility = String.Equals(profile.Id, "default", StringComparison.OrdinalIgnoreCase);
            bool legacy = compatibility || offline;
            if (offline && MessageBox.Show(this,
                "Nie można potwierdzić bezpiecznego pochodzenia tego katalogu. Profil zostanie trwale wyłączony, granty, " +
                "sekret i zadania startowe zostaną cofnięte, ale katalog pozostanie do ręcznego sprzątania offline. " +
                (unresolved
                    ? "Możesz od razu utworzyć nowy profil z SID. Kontynuować?"
                    : "Po sprzątaniu offline ponów usunięcie, aby zwolnić SID. Kontynuować?"),
                "Wyłącz profil; sprzątanie offline", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes) return;
            if (!offline && compatibility && MessageBox.Show(this,
                "To profil zgodności. Usunięta zostanie tylko konfiguracja i dane MoonWaker; konto Windows " +
                "pozostanie bez zmian. Kontynuować?",
                "Usuń profil zgodności", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes) return;
            if (MessageBox.Show(this,
                (offline
                    ? "Trwale wyłączyć profil MoonWaker „" + profile.DisplayName +
                      "” i pozostawić jego katalog do sprzątania offline? Konto Windows pozostanie bez zmian."
                    : "Usunąć tylko profil MoonWaker „" + profile.DisplayName +
                      "”? Konto Windows pozostanie bez zmian."),
                "Ostateczne potwierdzenie", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes) return;
            remove.Enabled = false;
            try
            {
                // A false policy bit means the profile was never configured or its
                // Broker secret was already deleted. This lets a fresh Slice 2
                // profile be removed while Broker is not installed yet.
                if (profile.RemoteSignInEnabled)
                {
                    BrokerResult brokerResult = new LoginBrokerClient().Delete(profile);
                    if (!brokerResult.Success)
                        throw new InvalidOperationException(
                            "Login Broker nie potwierdził usunięcia sekretu. " +
                            "uruchom usługę i ponów usunięcie.");
                }
                store.PrepareRemoval(profile);
                await System.Threading.Tasks.Task.Run(delegate {
                    ProfileProvisioner.RemoveArtifacts(store, profile);
                });
                store.RemoveProfile(profile);
                MessageBox.Show(this,
                    "Profil MoonWaker usunięto. Konto Windows nie zostało zmienione." +
                    (legacy ? " Teraz utwórz nowy profil w Host Control." : ""),
                    "MoonWaker Host Configurator", MessageBoxButtons.OK, MessageBoxIcon.Information);
                Close();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "MoonWaker Host Configurator",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                remove.Enabled = true;
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
                    "{\"schema_version\":2,\"unknown_top\":{\"keep\":true},\"profiles\":{" +
                    "\"profile-a\":{\"id\":\"profile-a\",\"name\":\"A\",\"display_name\":\"A\"," +
                    "\"owner_sid\":\"" + sidA + "\",\"windows_account_sid\":\"" + sidA + "\"," +
                    "\"owner\":\"PC\\\\A\",\"windows_account_name\":\"PC\\\\A\",\"enabled\":true," +
                    "\"profile_root\":\"C:\\\\MoonWaker\\\\profiles\\\\profile-a\"," +
                    "\"discord_bridge\":\"http://127.0.0.1:8765\"," +
                    "\"vibepollo_bridge\":\"http://127.0.0.1:8775\"," +
                    "\"game_provider_bridge\":\"http://127.0.0.1:8780\"," +
                    "\"playnite_bridge\":\"http://127.0.0.1:8780\"," +
                    "\"remote_sign_in_enabled\":false,\"account_mapping_status\":\"resolved\"," +
                    "\"unknown_profile\":17},\"default\":{\"id\":\"default\",\"name\":\"Legacy\"," +
                    "\"display_name\":\"Legacy\",\"owner_sid\":\"\",\"windows_account_sid\":\"\"," +
                    "\"owner\":\"\",\"windows_account_name\":\"\",\"enabled\":true," +
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
                Assert(raw.Contains("unknown_top") && raw.Contains("unknown_profile") &&
                    raw.Contains("unknown_client") && raw.Contains("SECRET_HASH") &&
                    raw.Contains("C:\\\\MoonWaker\\\\profiles\\\\profile-a"),
                    "Targeted mutation did not preserve unrelated registry data.");
                ProfileRecord edited = store.GetProfile("profile-a");
                Assert(edited.DisplayName == "Renamed" && edited.Sid == sidA && !edited.Enabled,
                    "Profile edit did not preserve immutable SID.");
                store.SetAppPin("profile-a", sidA, "4826");
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
                store.SetRemoteSignInEnabled("profile-a", sidA, true);
                store.UpdateGrants("profile-a", sidA,
                    new Dictionary<string, DeviceGrant> { {
                        devices[0].Id, new DeviceGrant { UseProfile = true, RemoteSignIn = true }
                    } });
                devices = store.GetDevices("profile-a");
                Assert(devices[0].UseProfile && devices[0].RemoteSignIn,
                    "Explicit profile grants were not persisted.");
                store.SetRemoteSignInEnabled("profile-a", sidA, false);
                devices = store.GetDevices("profile-a");
                Assert(devices[0].UseProfile && !devices[0].RemoteSignIn,
                    "Disabling remote sign-in did not revoke only the privileged grant.");
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
            }
            finally
            {
                if (Directory.Exists(root)) Directory.Delete(root, true);
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
    }
}
