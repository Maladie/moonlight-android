using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyVersion("0.6.8.0")]
[assembly: AssemblyFileVersion("0.6.8.0")]
[assembly: AssemblyInformationalVersion("0.6.8+2026.08.11")]

namespace MoonWaker.HostControl
{
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
        private readonly ListView profiles = new ListView();
        private readonly Label footer = new Label();
        private readonly Timer timer = new Timer();
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();
        private readonly string script;
        private static readonly Icon moonWakerIcon = MoonWakerIcon.Create();
        private readonly NotifyIcon trayIcon = new NotifyIcon();
        private readonly bool startInTray;
        private bool refreshing;
        private bool exiting;
        private readonly string hostVersion;

        public ControlForm(bool startInTray)
        {
            this.startInTray = startInTray;
            script = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Invoke-MoonWakerHostControl.ps1");
            hostVersion = ReadHostVersion();
            Text = "MoonWaker Host Control " + hostVersion;
            Icon = moonWakerIcon;
            ClientSize = new Size(1040, 708);
            MinimumSize = new Size(940, 640);
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
            AddActionButton(gatewayPanel, "Napraw wszystko", 430, 74, delegate { RunAction("RecoverAll", null); }, 514);
            activeProfile.SetBounds(430, 122, 510, 25);
            activeProfile.ForeColor = muted;
            gatewayPanel.Controls.Add(activeProfile);

            Panel profilePanel = NewPanel(34, 284, 972, 346);
            Controls.Add(profilePanel);
            Label profileTitle = MakeLabel("Profile i Bridge'e", 16F, FontStyle.Bold, Color.White);
            profileTitle.SetBounds(24, 16, 400, 32);
            profilePanel.Controls.Add(profileTitle);
            Label profileHint = MakeLabel("Jeden nadzorca Bridge na każde konto Windows", 9F, FontStyle.Regular, muted);
            profileHint.SetBounds(26, 48, 500, 24);
            profilePanel.Controls.Add(profileHint);

            profiles.SetBounds(24, 82, 924, 180);
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
            profiles.Columns.Add("Playnite / PID", 116);
            profiles.Columns.Add("Używany", 88);
            profilePanel.Controls.Add(profiles);

            AddActionButton(profilePanel, "Uruchom Bridge", 24, 282, delegate { RunProfileAction("StartProfile"); }, 140);
            AddActionButton(profilePanel, "Zatrzymaj", 174, 282, delegate { RunProfileAction("StopProfile"); }, 140);
            AddActionButton(profilePanel, "Restart", 324, 282, delegate { RunProfileAction("RestartProfile"); }, 140);
            AddActionButton(profilePanel, "Usuń dane Discorda", 474, 282, delegate { ClearDiscord(); }, 180);
            Button remove = AddActionButton(profilePanel, "Usuń profil", 664, 282, delegate { RemoveProfile(); }, 130);
            remove.ForeColor = Color.FromArgb(255, 180, 180);
            AddActionButton(profilePanel, "Odśwież", 804, 282, delegate { RefreshStatus(); }, 140);

            footer.SetBounds(36, 646, 968, 36);
            footer.ForeColor = muted;
            Controls.Add(footer);

            ConfigureTrayIcon();
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
            trayIcon.Visible = false;
            trayIcon.Dispose();
            base.OnFormClosing(e);
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
                Dictionary<string, object> result = await RunControlAsync("Status", null, false);
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
            gatewayDetails.Text = "Port " + GetText(gateway, "port", "—") + "  •  sparowane urządzenia: " +
                GetText(gateway, "paired_clients", "0") + "\n" + versionLine;
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
                    row.SubItems.Add(ProcessStatus(profile, "playnite"));
                    row.SubItems.Add(GetBool(profile, "last_used") ? "AKTYWNY" : "");
                    profiles.Items.Add(row);
                    if (id == selected) row.Selected = true;
                }
            }
            profiles.EndUpdate();
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

        private string SelectedProfileId()
        {
            if (profiles.SelectedItems.Count == 0) return null;
            return profiles.SelectedItems[0].Name;
        }

        private void RunProfileAction(string action)
        {
            string id = SelectedProfileId();
            if (String.IsNullOrWhiteSpace(id)) { MessageBox.Show(this, "Wybierz profil.", "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            RunAction(action, id);
        }

        private async void RunAction(string action, string profile)
        {
            try
            {
                footer.Text = "Wykonuję operację…";
                Dictionary<string, object> result = await RunControlAsync(action, profile, false);
                if (!IsOk(result))
                {
                    DialogResult elevate = MessageBox.Show(this, GetText(result, "error", "Operacja nie powiodła się.") +
                        "\n\nSpróbować z uprawnieniami administratora?", "MoonWaker Host Control",
                        MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                    if (elevate == DialogResult.Yes) result = await RunControlAsync(action, profile, true);
                }
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
                Dictionary<string, object> result = await RunControlAsync("PairGateway", null, false);
                if (!IsOk(result)) result = await RunControlAsync("PairGateway", null, true);
                if (!IsOk(result)) throw new InvalidOperationException(GetText(result, "error", "Nie udało się uruchomić parowania."));
                string code = GetText(result, "pairing_code", "");
                MessageBox.Show(this, "Kod parowania:\n\n" + code + "\n\nKod jest ważny przez 10 minut.",
                    "Parowanie MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
                RefreshStatus();
            }
            catch (Exception ex) { MessageBox.Show(this, ex.Message, "MoonWaker Host Control", MessageBoxButtons.OK, MessageBoxIcon.Error); }
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

        private void RemoveProfile()
        {
            string id = SelectedProfileId();
            if (String.IsNullOrWhiteSpace(id)) { MessageBox.Show(this, "Wybierz profil.", "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            if (MessageBox.Show(this, "Usunąć profil " + id + "?\n\nBridge zostanie zatrzymany, autostart wyrejestrowany, a tokeny i konfiguracja profilu trwale usunięte.",
                "Usuń profil MoonWaker", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes) RunAction("RemoveProfile", id);
        }

        private async Task<Dictionary<string, object>> RunControlAsync(string action, string profile, bool elevated)
        {
            return await Task.Run(delegate
            {
                if (!File.Exists(script)) throw new FileNotFoundException("Brak komponentu sterującego.", script);
                string resultPath = Path.Combine(Path.GetTempPath(), "MoonWakerControl-" + Guid.NewGuid().ToString("N") + ".json");
                string arguments = "-NoProfile -ExecutionPolicy Bypass -File " + Quote(script) + " -Action " + Quote(action) +
                    (String.IsNullOrWhiteSpace(profile) ? "" : " -ProfileId " + Quote(profile)) + " -ResultPath " + Quote(resultPath);
                try
                {
                    ProcessStartInfo info = new ProcessStartInfo("powershell.exe", arguments);
                    info.UseShellExecute = elevated;
                    if (elevated) info.Verb = "runas";
                    else
                    {
                        info.CreateNoWindow = true;
                        info.RedirectStandardOutput = true;
                        info.RedirectStandardError = true;
                        info.StandardOutputEncoding = Encoding.Default;
                        info.StandardErrorEncoding = Encoding.Default;
                    }
                    using (Process process = Process.Start(info))
                    {
                        string output = elevated ? "" : process.StandardOutput.ReadToEnd();
                        process.WaitForExit();
                        if (File.Exists(resultPath)) output = File.ReadAllText(resultPath, Encoding.UTF8).TrimStart('\uFEFF');
                        if (String.IsNullOrWhiteSpace(output)) return Error("Brak odpowiedzi komponentu sterującego.");
                        return json.Deserialize<Dictionary<string, object>>(output.Trim());
                    }
                }
                catch (System.ComponentModel.Win32Exception ex) { return Error(ex.NativeErrorCode == 1223 ? "Anulowano prośbę o uprawnienia administratora." : ex.Message); }
                finally { try { File.Delete(resultPath); } catch { } }
            });
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
