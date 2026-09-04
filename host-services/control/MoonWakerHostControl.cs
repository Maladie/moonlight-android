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
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyVersion("0.7.59.0")]
[assembly: AssemblyFileVersion("0.7.59.0")]
[assembly: AssemblyInformationalVersion("0.7.59+2026.09.01")]

namespace MoonWaker.HostControl
{
    internal static class NativeMethods
    {
        [DllImport("user32.dll", SetLastError = true)]
        internal static extern bool RegisterHotKey(IntPtr window, int id, uint modifiers, uint key);

        [DllImport("user32.dll", SetLastError = true)]
        internal static extern bool UnregisterHotKey(IntPtr window, int id);
    }

    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            bool startInTray = args != null && Array.IndexOf(args, "--tray") >= 0;
            Application.Run(new ControlForm(startInTray));
        }
    }

    internal sealed class ControlForm : Form
    {
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
        private readonly bool startInTray;
        private bool refreshing;
        private bool exiting;
        private bool streamClosePending;
        private bool legendaryInstalled;
        private readonly string hostVersion;
        private const int StreamHotkeyId = 0x4D57;
        private const int WmHotkey = 0x0312;
        private const uint StreamHotkeyModifiers = 0x0001 | 0x0002 | 0x0004 | 0x4000;
        private const uint StreamHotkeyKey = 0x23;

        public ControlForm(bool startInTray)
        {
            this.startInTray = startInTray;
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
            AddActionButton(gatewayPanel, "Napraw wszystko", 430, 74,
                delegate { RunAction("RecoverAll", null); }, 250);
            AddActionButton(gatewayPanel, "Eksportuj diagnostykę", 688, 74,
                delegate { ExportDiagnostics(); }, 256);
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
            Label profileHint = MakeLabel("Jeden nadzorca Bridge na każde konto Windows", 9F, FontStyle.Regular, muted);
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
            profiles.Columns.Add("Profil", 142);
            profiles.Columns.Add("Użytkownik", 154);
            profiles.Columns.Add("Nadzorca / PID", 126);
            profiles.Columns.Add("Discord / PID", 116);
            profiles.Columns.Add("Vibepollo / PID", 124);
            profiles.Columns.Add("Provider / PID", 116);
            profiles.Columns.Add("Logowanie", 88);
            profiles.SelectedIndexChanged += delegate { UpdateConnectionControls(); };
            profilePanel.Controls.Add(profiles);

            Button addProfile = AddActionButton(profilePanel, "Dodaj profil", 24, 232,
                delegate { LaunchConfigurator("add", null); }, 140);
            addProfile.BackColor = accent;
            addProfile.FlatAppearance.BorderSize = 0;
            AddActionButton(profilePanel, "Edytuj profil", 174, 232,
                delegate { LaunchSelectedConfigurator("edit"); }, 140);
            AddActionButton(profilePanel, "Zdalne logowanie", 324, 232,
                delegate { LaunchSelectedConfigurator("remote-sign-in"); }, 180);
            AddActionButton(profilePanel, "Urządzenia i dostęp", 514, 232,
                delegate { LaunchSelectedConfigurator("devices"); }, 190);
            Button remove = AddActionButton(profilePanel, "Usuń profil", 714, 232,
                delegate { LaunchSelectedConfigurator("remove"); }, 140);
            remove.ForeColor = Color.FromArgb(255, 180, 180);
            AddActionButton(profilePanel, "Uruchom Bridge", 24, 282,
                delegate { RunProfileAction("StartProfile"); }, 140);
            AddActionButton(profilePanel, "Zatrzymaj", 174, 282,
                delegate { RunProfileAction("StopProfile"); }, 120);
            AddActionButton(profilePanel, "Restart", 304, 282,
                delegate { RunProfileAction("RestartProfile"); }, 120);
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
            streamHotkeyPipe = new StreamHotkeyPipe(this, CloseStreamFromHotkey);
            timer.Interval = 5000;
            timer.Tick += delegate { RefreshStatus(); };
            Shown += delegate
            {
                RefreshStatus();
                timer.Start();
                if (this.startInTray) BeginInvoke((MethodInvoker)HideToTray);
            };
            Resize += delegate { if (WindowState == FormWindowState.Minimized) HideToTray(); };
        }

        private void ConfigureTrayIcon()
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Otwórz MoonWaker Host Control", null, delegate { ShowControl(); });
            menu.Items.Add("Odśwież status", null, delegate { RefreshStatus(); });
            menu.Items.Add("Zamknij stream  (Ctrl+Alt+Shift+End)", null,
                delegate { CloseStreamFromHotkey(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Zakończ", null, delegate
            {
                exiting = true;
                trayIcon.Visible = false;
                Close();
            });
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

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!exiting && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                HideToTray();
                return;
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
                CloseStreamFromHotkey();
            base.WndProc(ref message);
        }

        private async void CloseStreamFromHotkey()
        {
            if (streamClosePending) return;
            streamClosePending = true;
            footer.Text = "Zamykam stream…";
            try
            {
                Dictionary<string, object> result = await RunControlAsync("CloseStream", null);
                if (!IsOk(result)) throw new InvalidOperationException(
                    GetText(result, "error", "Nie udało się zamknąć streamu."));
                footer.Text = "Wysłano polecenie zamknięcia streamu.";
                trayIcon.ShowBalloonTip(3000, "MoonWaker",
                    "Wysłano polecenie zamknięcia streamu.", ToolTipIcon.Info);
            }
            catch (Exception ex)
            {
                footer.Text = ex.Message;
                trayIcon.ShowBalloonTip(5000, "MoonWaker — błąd", ex.Message,
                    ToolTipIcon.Error);
            }
            finally { streamClosePending = false; }
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
                    row.SubItems.Add(ProcessStatus(profile, "supervisor"));
                    row.SubItems.Add(ProcessStatus(profile, "discord"));
                    row.SubItems.Add(ProcessStatus(profile, "vibepollo"));
                    row.SubItems.Add(ProcessStatus(profile, "game_provider"));
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
            if (value == "manually_stopped") return "ZATRZYMANY RĘCZNIE";
            if (value == "disabled") return "WYŁ.";
            if (value == "unavailable") return "INNE KONTO";
            return "OFFLINE";
        }

        private static string ProcessStatus(Dictionary<string, object> profile, string name)
        {
            string status = StatusLabel(GetText(profile, name, "stopped"));
            string pid = GetText(profile, name + "_pid", "");
            return String.IsNullOrWhiteSpace(pid) || pid == "0" ? status : status + "  #" + pid;
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

        private void RunProfileAction(string action)
        {
            string id = SelectedProfileId();
            if (String.IsNullOrWhiteSpace(id)) { MessageBox.Show(this, "Wybierz profil.", "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            RunAction(action, id);
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
                    "Zaloguj się na konto Windows właściciela tego profilu, aby skonfigurować Discord i Vibepollo.",
                    "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            using (IntegrationsDialog dialog = new IntegrationsDialog(id,
                GetBool(profile, "discord_configured"), GetBool(profile, "vibepollo_configured")))
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                IntegrationSettings settings = dialog.Settings;
                try
                {
                    footer.Text = "Konfiguruję Discord i Vibepollo…";
                    Dictionary<string, object> result = await RunControlAsync(
                        "ConfigureIntegrations", id, null, null, settings);
                    if (!IsOk(result)) throw new InvalidOperationException(GetText(result, "error",
                        "Nie udało się skonfigurować integracji."));
                    MessageBox.Show(this,
                        "Integracje profilu zostały zapisane. Jeśli Discord wymaga autoryzacji OAuth, otwórz go i uruchom Bridge ponownie.",
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
                        info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CONFIGURE"] =
                            integrations.ConfigureVibepollo ? "1" : "0";
                        info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_URL"] =
                            integrations.VibepolloUrl ?? "";
                        info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_TOKEN_PROTECTED"] =
                            String.IsNullOrWhiteSpace(integrations.VibepolloToken) ? "" :
                            ProtectForCurrentUser(integrations.VibepolloToken);
                        info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CREATE_TOKEN"] =
                            integrations.CreateVibepolloToken ? "1" : "0";
                        info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_USERNAME"] =
                            integrations.VibepolloAdmin ?? "";
                        info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD_PROTECTED"] =
                            String.IsNullOrWhiteSpace(integrations.VibepolloPassword) ? "" :
                            ProtectForCurrentUser(integrations.VibepolloPassword);
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
                            action == "RecoverAll" ? 90000 :
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
        internal bool ConfigureVibepollo;
        internal bool CreateVibepolloToken;
        internal string VibepolloUrl;
        internal string VibepolloToken;
        internal string VibepolloAdmin;
        internal string VibepolloPassword;

        internal void ClearSecrets()
        {
            DiscordClientSecret = null;
            VibepolloToken = null;
            VibepolloPassword = null;
        }
    }

    internal sealed class IntegrationsDialog : Form
    {
        private readonly bool discordWasConfigured;
        private readonly bool vibepolloWasConfigured;
        private readonly CheckBox configureDiscord = new CheckBox();
        private readonly TextBox discordId = new TextBox();
        private readonly TextBox discordSecret = new TextBox();
        private readonly CheckBox configureVibepollo = new CheckBox();
        private readonly CheckBox createVibepolloToken = new CheckBox();
        private readonly TextBox vibepolloAdmin = new TextBox();
        private readonly TextBox vibepolloPassword = new TextBox();
        private readonly TextBox vibepolloToken = new TextBox();
        private readonly TextBox vibepolloUrl = new TextBox();

        internal IntegrationSettings Settings
        {
            get
            {
                return new IntegrationSettings {
                    ConfigureDiscord = configureDiscord.Checked,
                    DiscordClientId = discordId.Text.Trim(),
                    DiscordClientSecret = discordSecret.Text,
                    ConfigureVibepollo = configureVibepollo.Checked,
                    CreateVibepolloToken = createVibepolloToken.Checked,
                    VibepolloUrl = vibepolloUrl.Text.Trim(),
                    VibepolloToken = vibepolloToken.Text,
                    VibepolloAdmin = vibepolloAdmin.Text.Trim(),
                    VibepolloPassword = vibepolloPassword.Text
                };
            }
        }

        internal IntegrationsDialog(string profileId, bool discordConfigured,
            bool vibepolloConfigured)
        {
            discordWasConfigured = discordConfigured;
            vibepolloWasConfigured = vibepolloConfigured;
            Text = "Integracje MoonWaker";
            Icon = MoonWakerIcon.Create();
            ClientSize = new Size(720, 620);
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            BackColor = Color.FromArgb(17, 20, 28);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);
            AutoScaleMode = AutoScaleMode.Dpi;

            AddLabel("Discord i Vibepollo", 26, 18, 620, 34, 16F, FontStyle.Bold, Color.White);
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

            Panel divider = new Panel();
            divider.SetBounds(28, 224, 662, 1);
            divider.BackColor = Color.FromArgb(62, 69, 88);
            Controls.Add(divider);

            configureVibepollo.Text = vibepolloConfigured
                ? "Vibepollo — skonfigurowane (puste pola zachowają zapisane dane)"
                : "Skonfiguruj Vibepollo";
            configureVibepollo.SetBounds(28, 241, 650, 28);
            configureVibepollo.ForeColor = Color.White;
            configureVibepollo.Checked = true;
            configureVibepollo.CheckedChanged += delegate { UpdateFields(); };
            Controls.Add(configureVibepollo);

            createVibepolloToken.Text = "Pobierz / odnów token automatycznie";
            createVibepolloToken.SetBounds(30, 276, 390, 26);
            createVibepolloToken.ForeColor = Color.White;
            createVibepolloToken.Checked = !vibepolloConfigured;
            createVibepolloToken.CheckedChanged += delegate { UpdateFields(); };
            Controls.Add(createVibepolloToken);
            AddLabel("Login administratora Vibepollo", 30, 309, 300, 22, 9F,
                FontStyle.Regular, Color.White);
            AddLabel("Hasło administratora", 365, 309, 300, 22, 9F,
                FontStyle.Regular, Color.White);
            ConfigureBox(vibepolloAdmin, 30, 333, 315, false);
            ConfigureBox(vibepolloPassword, 365, 333, 325, true);
            AddLabel("Istniejący token API (alternatywa)", 30, 374, 350, 22, 9F,
                FontStyle.Regular, Color.White);
            ConfigureBox(vibepolloToken, 30, 398, 660, true);
            AddLabel("Adres lokalnego API Vibepollo", 30, 439, 350, 22, 9F,
                FontStyle.Regular, Color.White);
            ConfigureBox(vibepolloUrl, 30, 463, 660, false);
            vibepolloUrl.Text = vibepolloConfigured ? "" : "https://127.0.0.1:47990";
            AddLabel("Automatyczne pobranie używa loginu i hasła tylko do lokalnego wywołania /api/token. Możesz zamiast tego wkleić token.",
                30, 501, 660, 38, 8.7F, FontStyle.Regular, Color.FromArgb(164, 171, 193));

            Button save = new Button();
            save.Text = "Zapisz";
            save.SetBounds(468, 562, 102, 38);
            save.BackColor = Color.FromArgb(116, 100, 255);
            save.ForeColor = Color.White;
            save.FlatStyle = FlatStyle.Flat;
            save.FlatAppearance.BorderSize = 0;
            save.Click += SaveClicked;
            Controls.Add(save);
            Button cancel = new Button();
            cancel.Text = "Anuluj";
            cancel.DialogResult = DialogResult.Cancel;
            cancel.SetBounds(582, 562, 108, 38);
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
            if (!configureDiscord.Checked && !configureVibepollo.Checked)
            {
                MessageBox.Show(this, "Wybierz przynajmniej jedną integrację.",
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
            if (configureVibepollo.Checked)
            {
                string vibepolloUrlText = vibepolloUrl.Text.Trim();
                Uri uri;
                if (!(vibepolloWasConfigured && String.IsNullOrWhiteSpace(vibepolloUrlText)) &&
                    (!Uri.TryCreate(vibepolloUrlText, UriKind.Absolute, out uri) ||
                     uri.Scheme != Uri.UriSchemeHttps ||
                     !(uri.Host == "127.0.0.1" || uri.Host.Equals("localhost",
                         StringComparison.OrdinalIgnoreCase))))
                {
                    MessageBox.Show(this, "API Vibepollo musi używać HTTPS na 127.0.0.1 lub localhost.",
                        "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                if (createVibepolloToken.Checked &&
                    (String.IsNullOrWhiteSpace(vibepolloAdmin.Text) ||
                     String.IsNullOrWhiteSpace(vibepolloPassword.Text)))
                {
                    MessageBox.Show(this, "Podaj login i hasło administratora Vibepollo albo wybierz tryb istniejącego tokenu.",
                        "Integracje MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                if (!createVibepolloToken.Checked &&
                    String.IsNullOrWhiteSpace(vibepolloToken.Text) && !vibepolloWasConfigured)
                {
                    MessageBox.Show(this, "Wklej token API Vibepollo albo włącz jego automatyczne pobranie.",
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
            createVibepolloToken.Enabled = configureVibepollo.Checked;
            vibepolloUrl.Enabled = configureVibepollo.Checked;
            vibepolloAdmin.Enabled = configureVibepollo.Checked && createVibepolloToken.Checked;
            vibepolloPassword.Enabled = configureVibepollo.Checked && createVibepolloToken.Checked;
            vibepolloToken.Enabled = configureVibepollo.Checked && !createVibepolloToken.Checked;
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
            vibepolloPassword.Clear();
            vibepolloToken.Clear();
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
