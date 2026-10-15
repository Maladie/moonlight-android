using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace MoonWaker.HostControl
{
    // Host Control's window: five pages over HostBackend, notices on top of a page and a status
    // bar. It holds no business rule of its own: every fact comes from HostBackend.ReadStatus,
    // every action is a HostBackend call or the elevated Configurator. Closing only hides it;
    // TrayHost owns the process.
    internal sealed class HostControlWindow : Window
    {
        internal const int Overview = 0, Profiles = 1, Devices = 2, Integrations = 3, Diagnostics = 4;
        internal static readonly string[] PageNames = { "Przegląd", "Profile", "Urządzenia", "Integracje", "Diagnostyka" };
        private static readonly string[] PageIcons = {
            "M3 9L12 2L21 9V20A2 2 0 0 1 19 22H5A2 2 0 0 1 3 20Z M9 22V12H15V22",
            "M17 21V19A4 4 0 0 0 13 15H5A4 4 0 0 0 1 19V21 M13 7A4 4 0 1 1 5 7A4 4 0 1 1 13 7 " +
                "M23 21V19A4 4 0 0 0 20 15.13 M16 3.13A4 4 0 0 1 16 10.88",
            "M4 3H20A2 2 0 0 1 22 5V15A2 2 0 0 1 20 17H4A2 2 0 0 1 2 15V5A2 2 0 0 1 4 3Z M8 21H16 M12 17V21",
            "M10 13A5 5 0 0 0 17.54 13.54L20.54 10.54A5 5 0 0 0 13.47 3.47L11.75 5.18 " +
                "M14 11A5 5 0 0 0 6.46 10.46L3.46 13.46A5 5 0 0 0 10.53 20.53L12.24 18.82",
            "M22 12H18L15 21L9 3L6 12H2" };
        private const int PairingLifetimeSeconds = 600;

        private sealed class Notice
        {
            internal Tone Tone;
            internal string Title, Text, ActionLabel;
            internal Action Action;
        }

        private readonly HostBackend backend;
        private readonly ProfileProcessHost processes;
        private readonly bool? forcedLight;
        private readonly Dictionary<int, Notice> notices = new Dictionary<int, Notice>();
        private readonly DispatcherTimer refreshTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        private readonly DispatcherTimer secondTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        private readonly string account = Environment.UserDomainName + "\\" + Environment.UserName;
        private Ui ui;
        private HostStatus status;
        private string microphone;
        private int page;
        private string selectedProfile;
        private string pairingCode;
        private string statusMessage = "Odczytuję stan…";
        private Tone statusTone = Tone.Off;
        private DateTime statusMessageUntil;
        private bool busy, refreshing, closingSession, exiting;
        private string renderedSignature;
        private ScrollViewer scroll;
        private TextBlock pairingCountdown;
        private TextBlock sessionDuration;
        private Grid pairingBar;
        private TextBlock statusText;
        private System.Windows.Shapes.Ellipse statusDot;
        private ProgressBar statusProgress;

        // Shows a tray balloon when the window is hidden: text and System.Windows.Forms.ToolTipIcon.
        internal Action<string, System.Windows.Forms.ToolTipIcon> Balloon;

        internal HostControlWindow(HostBackend backend, ProfileProcessHost processes, bool? light)
        {
            this.backend = backend;
            this.processes = processes;
            forcedLight = light;
            ui = new Ui(new Palette(light ?? Palette.WindowsAppsUseLightTheme()));
            Title = "MoonWaker Host Control";
            Width = 1100;
            Height = 760;
            MinWidth = 940;
            MinHeight = 600;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            FontFamily = Ui.Font;
            FontSize = 13;
            UseLayoutRounding = true;
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);
            Render();

            refreshTimer.Tick += delegate { Refresh(); };
            secondTimer.Tick += delegate { Tick(); };
            SourceInitialized += delegate { TitleBar.Apply(this, !ui.P.Light); };
            IsVisibleChanged += delegate
            {
                if (IsVisible)
                {
                    refreshTimer.Start();
                    secondTimer.Start();
                    Refresh();
                    ProbeMicrophone();
                }
                else
                {
                    refreshTimer.Stop();
                    secondTimer.Stop();
                }
            };
            StateChanged += delegate { if (WindowState == WindowState.Minimized) Hide(); };
            PreviewKeyDown += OnKey;
            if (light == null)
                SystemEvents.UserPreferenceChanged += delegate(object sender, UserPreferenceChangedEventArgs e)
                {
                    if (e.Category == UserPreferenceCategory.General)
                        Dispatcher.BeginInvoke((Action)FollowWindowsTheme);
                };
        }

        internal void ShowControl()
        {
            ShowInTaskbar = true;
            Show();
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
        }

        // The tray ends the process: the window then really closes.
        internal void Exit()
        {
            exiting = true;
            Close();
        }

        protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
        {
            // The tray hosts the profile's Agent, so a user close only hides the window.
            if (!exiting)
            {
                e.Cancel = true;
                Hide();
            }
            base.OnClosing(e);
        }

        private void OnKey(object sender, KeyEventArgs e)
        {
            if (Keyboard.Modifiers == ModifierKeys.Control && e.Key >= Key.D1 && e.Key <= Key.D5)
            {
                ShowPage(e.Key - Key.D1);
                e.Handled = true;
            }
            else if (e.Key == Key.F5)
            {
                Refresh();
                ProbeMicrophone();
                e.Handled = true;
            }
            else if (e.Key == Key.Escape)
            {
                Hide();
                e.Handled = true;
            }
        }

        private void FollowWindowsTheme()
        {
            bool light = Palette.WindowsAppsUseLightTheme();
            if (light == ui.P.Light) return;
            ui = new Ui(new Palette(light));
            TitleBar.Apply(this, !light);
            Render();
        }

        internal void ShowPage(int index)
        {
            if (index < 0 || index >= PageNames.Length) return;
            page = index;
            Render();
            if (scroll != null) scroll.ScrollToTop();
        }

        // ---- State ----

        private async void Refresh()
        {
            if (refreshing) return;
            refreshing = true;
            try
            {
                HostStatus fresh = await Task.Run(delegate { return backend.ReadStatus(); });
                Apply(fresh);
            }
            catch (Exception error)
            {
                SetStatus("Stan niedostępny: " + error.Message, Tone.Error);
            }
            finally { refreshing = false; }
        }

        // A status read re-renders only when something shown changed: no lost click, no lost
        // focus every two seconds.
        internal void Apply(HostStatus fresh)
        {
            status = fresh;
            if (Signature() != renderedSignature) Render();
            if (!busy && DateTime.UtcNow >= statusMessageUntil)
                ShowStatus("Stan odświeżony " + DateTime.Now.ToString("HH:mm:ss"), Tone.Ok);
        }

        // The microphone worker's probe starts a process: when the window opens and on F5.
        private async void ProbeMicrophone()
        {
            string reason = await Task.Run(delegate { return backend.ProbeMicrophone(); });
            ApplyMicrophone(reason);
        }

        internal void ApplyMicrophone(string reason)
        {
            microphone = reason;
            if (Signature() != renderedSignature) Render();
        }

        private string Signature()
        {
            StringBuilder text = new StringBuilder();
            Describe(status, text);
            text.Append(microphone).Append('|');
            ProfileStatus own = Own();
            if (own != null) text.Append(processes.Describe(own.Id, false)).Append(processes.Describe(own.Id, true));
            return text.ToString();
        }

        // Every field of the status, recursively.
        private static void Describe(object value, StringBuilder text)
        {
            if (value == null) { text.Append('~'); return; }
            if (value is string || value is DateTime || value.GetType().IsPrimitive)
            {
                text.Append(value).Append('|');
                return;
            }
            IEnumerable items = value as IEnumerable;
            if (items != null)
            {
                text.Append('[');
                foreach (object item in items) Describe(item, text);
                text.Append(']');
                return;
            }
            foreach (FieldInfo field in value.GetType().GetFields(
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic))
                Describe(field.GetValue(value), text);
        }

        private ProfileStatus Own()
        {
            return status == null ? null : status.Profiles.Find(delegate(ProfileStatus item) { return item.CurrentUser; });
        }

        private ProfileStatus Selected()
        {
            if (status == null || status.Profiles.Count == 0) return null;
            ProfileStatus chosen = status.Profiles.Find(delegate(ProfileStatus item) { return item.Id == selectedProfile; });
            return chosen ?? Own() ?? status.Profiles[0];
        }

        // Null while the Agent answers; otherwise what stands in its way.
        private string AgentProblem(ProfileStatus own)
        {
            string hosted = processes.Describe(own.Id, false);
            if (hosted != null) return hosted;
            if (own.Agent == "online") return null;
            return own.Agent == "error" ? "Odpowiada błędem" : own.Agent == "disabled" ? "Profil nie ma skonfigurowanego Agenta"
                : "Nie odpowiada";
        }

        private string DiscordState(ProfileStatus own)
        {
            string hosted = processes.Describe(own.Id, true);
            if (hosted != null) return hosted;
            return own.Discord == "online" ? "Działa" : own.Discord == "error" ? "Odpowiada błędem"
                : own.Discord == "disabled" ? "Discord nie jest skonfigurowany dla profilu" : "Nie działa";
        }

        private bool GatewayRunning { get { return status != null && status.Gateway.State == "running"; } }

        private long PairingSecondsLeft
        {
            get
            {
                if (status == null || status.Gateway.PairingExpiresAt == 0) return 0;
                return Math.Max(0, status.Gateway.PairingExpiresAt - DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            }
        }

        // Diagnostics problems: the page's banner names the first, the navigation counts them.
        private List<KeyValuePair<Tone, string>> Problems()
        {
            List<KeyValuePair<Tone, string>> problems = new List<KeyValuePair<Tone, string>>();
            if (status == null) return problems;
            if (status.Gateway.Installed && !GatewayRunning)
                problems.Add(new KeyValuePair<Tone, string>(Tone.Error, "Gateway nie działa."));
            else if (status.Gateway.VersionMismatch)
                problems.Add(new KeyValuePair<Tone, string>(Tone.Warn, "Działająca wersja usług różni się od zainstalowanej."));
            ProfileStatus own = Own();
            if (own != null && AgentProblem(own) != null)
                problems.Add(new KeyValuePair<Tone, string>(Tone.Error, "Agent tego konta: " + AgentProblem(own) + "."));
            if (own != null && status.DiscordInstalled && own.DiscordConfigured && own.Discord != "online")
                problems.Add(new KeyValuePair<Tone, string>(Tone.Error, "Discord: " + DiscordState(own) + "."));
            if (microphone != null && microphone != "ready")
                problems.Add(new KeyValuePair<Tone, string>(Tone.Warn, "Mikrofon na TV nie jest gotowy."));
            if (!status.LegendaryInstalled)
                problems.Add(new KeyValuePair<Tone, string>(Tone.Warn, "Brak Legendary."));
            // The banner names the first: errors before warnings.
            List<KeyValuePair<Tone, string>> sorted = problems.FindAll(item => item.Key == Tone.Error);
            sorted.AddRange(problems.FindAll(item => item.Key != Tone.Error));
            return sorted;
        }

        // ---- Layout ----

        private void Render()
        {
            Palette p = ui.P;
            IInputElement focused = Keyboard.FocusedElement;
            string focusId = focused is Visual && IsAncestorOf((Visual)focused)
                ? AutomationProperties.GetAutomationId((DependencyObject)focused) : null;
            double offset = scroll == null ? 0 : scroll.VerticalOffset;
            renderedSignature = Signature();
            pairingCountdown = null;
            pairingBar = null;
            sessionDuration = null;

            Background = p.Background;
            Grid root = new Grid { Background = p.Background };
            root.RowDefinitions.Add(new RowDefinition());
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(30) });
            Grid middle = new Grid();
            middle.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(216) });
            middle.ColumnDefinitions.Add(new ColumnDefinition());
            root.Children.Add(middle);
            middle.Children.Add(Navigation());

            StackPanel content = new StackPanel { Margin = new Thickness(26, 18, 26, 14) };
            if (page == Overview) BuildOverview(content);
            else if (page == Profiles) BuildProfiles(content);
            else if (page == Devices) BuildDevices(content);
            else if (page == Integrations) BuildIntegrations(content);
            else BuildDiagnostics(content);
            scroll = new ScrollViewer {
                Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Focusable = false };
            scroll.Resources.Add(typeof(System.Windows.Controls.Primitives.ScrollBar), ui.ScrollBarStyle());
            Grid.SetColumn(scroll, 1);
            middle.Children.Add(scroll);
            scroll.ScrollToVerticalOffset(offset);

            Border bar = StatusBar();
            Grid.SetRow(bar, 1);
            root.Children.Add(bar);
            Content = root;
            ShowStatus(statusMessage, statusTone);

            if (focusId != null)
                Dispatcher.BeginInvoke(DispatcherPriority.Loaded, (Action)delegate
                {
                    UIElement target = FindById(root, focusId);
                    if (target != null) target.Focus();
                });
        }

        private static UIElement FindById(DependencyObject parent, string id)
        {
            foreach (object child in LogicalTreeHelper.GetChildren(parent))
            {
                DependencyObject element = child as DependencyObject;
                if (element == null) continue;
                if (element is Button && AutomationProperties.GetAutomationId(element) == id) return (UIElement)element;
                UIElement found = FindById(element, id);
                if (found != null) return found;
            }
            return null;
        }

        private Border StatusBar()
        {
            Palette p = ui.P;
            statusDot = ui.Dot(Tone.Off);
            statusDot.Margin = new Thickness(0, 0, 10, 0);
            statusText = ui.Text("", 11.5, p.Muted, FontWeights.Normal);
            statusText.TextTrimming = TextTrimming.CharacterEllipsis;
            statusText.TextWrapping = TextWrapping.NoWrap;
            statusProgress = new ProgressBar {
                Width = 180, Height = 6, IsIndeterminate = true, Foreground = p.Accent, Background = p.Card2,
                BorderThickness = new Thickness(0), Visibility = Visibility.Collapsed, Margin = new Thickness(10, 0, 0, 0) };
            AutomationProperties.SetName(statusProgress, "Trwa operacja");
            DockPanel row = new DockPanel { VerticalAlignment = VerticalAlignment.Center };
            row.Children.Add(statusDot);
            DockPanel.SetDock(statusProgress, Dock.Right);
            row.Children.Add(statusProgress);
            row.Children.Add(statusText);
            Border bar = new Border {
                Background = p.Side, BorderBrush = p.Line, BorderThickness = new Thickness(0, 1, 0, 0),
                Padding = new Thickness(16, 0, 16, 0), Child = row };
            AutomationProperties.SetLiveSetting(statusText, AutomationLiveSetting.Polite);
            return bar;
        }

        // A message shown for 8 s before the refresh time takes its place again.
        private void SetStatus(string text, Tone tone)
        {
            statusMessageUntil = DateTime.UtcNow.AddSeconds(8);
            ShowStatus(text, tone);
        }

        private void ShowStatus(string text, Tone tone)
        {
            statusMessage = text;
            statusTone = tone;
            if (statusText == null) return;
            statusText.Text = text;
            statusDot.Fill = ui.P.Strong(busy ? Tone.Accent : tone);
            statusProgress.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        }

        private Border Navigation()
        {
            Palette p = ui.P;
            DockPanel panel = new DockPanel { LastChildFill = false };
            StackPanel brand = new StackPanel { Margin = new Thickness(12, 4, 12, 18) };
            TextBlock name = ui.Text("MOONWAKER", 17, p.Text, FontWeights.Bold);
            brand.Children.Add(name);
            TextBlock sub = ui.Text("Host Control", 11, p.Muted, FontWeights.Normal);
            sub.Margin = new Thickness(0, 2, 0, 0);
            brand.Children.Add(sub);
            DockPanel.SetDock(brand, Dock.Top);
            panel.Children.Add(brand);

            int problems = Problems().Count;
            for (int index = 0; index < PageNames.Length; index++)
            {
                int target = index;
                bool on = index == page;
                Grid line = new Grid { Height = 38 };
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                line.ColumnDefinitions.Add(new ColumnDefinition());
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                FrameworkElement icon = ui.Icon(PageIcons[index], on ? p.Text : p.Muted, 18);
                icon.Margin = new Thickness(12, 0, 12, 0);
                line.Children.Add(icon);
                TextBlock label = ui.Text(PageNames[index], 13.5, on ? p.Text : p.Muted,
                    on ? FontWeights.SemiBold : FontWeights.Normal);
                Grid.SetColumn(label, 1);
                line.Children.Add(label);
                if (index == Diagnostics && problems > 0)
                {
                    TextBlock count = ui.Text(problems.ToString(), 11, Palette.Brush("#1B1B22"), FontWeights.Bold);
                    count.HorizontalAlignment = HorizontalAlignment.Center;
                    Border badge = new Border {
                        MinWidth = 18, Height = 18, CornerRadius = new CornerRadius(9), Background = p.Warn,
                        Margin = new Thickness(0, 0, 12, 0), Padding = new Thickness(5, 0, 5, 0), Child = count };
                    Grid.SetColumn(badge, 2);
                    line.Children.Add(badge);
                }
                if (on)
                {
                    Border marker = new Border {
                        Width = 4, Height = 18, CornerRadius = new CornerRadius(2), Background = p.Accent,
                        HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(-3, 0, 0, 0) };
                    line.Children.Add(marker);
                }
                Button item = ui.Tile(line, on ? p.Card : Brushes.Transparent, Brushes.Transparent,
                    PageNames[index], delegate { ShowPage(target); });
                item.Margin = new Thickness(0, 0, 0, 2);
                item.ToolTip = PageNames[index] + " (Ctrl+" + (index + 1) + ")";
                AutomationProperties.SetItemStatus(item, on ? "bieżąca strona" : "");
                DockPanel.SetDock(item, Dock.Top);
                panel.Children.Add(item);
            }

            TextBlock footer = ui.Text("Wersja " + (status == null ? "?" : status.InstalledVersion) +
                "\nKonto: " + account, 11, p.Muted, FontWeights.Normal);
            footer.LineHeight = 16.5;
            footer.Margin = new Thickness(12, 10, 12, 0);
            DockPanel.SetDock(footer, Dock.Bottom);
            panel.Children.Add(footer);
            KeyboardNavigation.SetDirectionalNavigation(panel, KeyboardNavigationMode.Cycle);
            return new Border { Background = p.Side, Padding = new Thickness(10, 16, 10, 10), Child = panel };
        }

        private void Add(StackPanel content, UIElement element)
        {
            if (content.Children.Count > 0)
            {
                FrameworkElement framed = (FrameworkElement)element;
                framed.Margin = new Thickness(framed.Margin.Left, 12, framed.Margin.Right, framed.Margin.Bottom);
            }
            content.Children.Add(element);
        }

        private void Header(StackPanel content, string title, string subtitle, UIElement right)
        {
            DockPanel header = new DockPanel();
            if (right != null)
            {
                DockPanel.SetDock(right, Dock.Right);
                header.Children.Add(right);
            }
            StackPanel text = new StackPanel();
            text.Children.Add(ui.Text(title, 24, ui.P.Text, FontWeights.SemiBold));
            TextBlock sub = ui.Text(subtitle, 12.5, ui.P.Muted, FontWeights.Normal);
            sub.Margin = new Thickness(0, 2, 0, 0);
            text.Children.Add(sub);
            header.Children.Add(text);
            Add(content, header);
            Notice notice;
            if (notices.TryGetValue(page, out notice))
            {
                int origin = page;
                Action act = notice.Action ?? delegate { notices.Remove(origin); Render(); };
                Add(content, ui.Banner(notice.Tone, notice.Title, notice.Text,
                    ui.Button(notice.ActionLabel ?? "Zamknij", notice.Action == null ? ButtonKind.Normal : ButtonKind.Primary,
                        true, false, act, "notice")));
            }
        }

        private Grid Columns(params UIElement[] cells)
        {
            Grid grid = new Grid();
            for (int index = 0; index < cells.Length; index++)
            {
                grid.ColumnDefinitions.Add(new ColumnDefinition());
                FrameworkElement cell = (FrameworkElement)cells[index];
                cell.Margin = new Thickness(index == 0 ? 0 : 6, 0, index == cells.Length - 1 ? 0 : 6, 0);
                Grid.SetColumn(cell, index);
                grid.Children.Add(cell);
            }
            return grid;
        }

        private static StackPanel Row(params UIElement[] items)
        {
            StackPanel row = new StackPanel { Orientation = Orientation.Horizontal };
            foreach (UIElement item in items)
            {
                if (item == null) continue;
                if (row.Children.Count > 0) ((FrameworkElement)item).Margin = new Thickness(8, 0, 0, 0);
                row.Children.Add(item);
            }
            return row;
        }

        // ---- Przegląd ----

        private void BuildOverview(StackPanel content)
        {
            Header(content, "Przegląd", "Stan tego komputera i szybkie akcje", null);
            if (status == null) { Add(content, ui.Card(ui.Muted("Odczytuję stan…"))); return; }
            ProfileStatus own = Own();
            GatewayStatus gateway = status.Gateway;

            UIElement gatewayCard;
            string paired = "Sparowane TV: " + gateway.Clients.Count;
            if (!gateway.Installed)
                gatewayCard = Stat("Gateway komputera", Tone.Error, "Nie zainstalowano", "Zainstaluj MoonWaker Host", null);
            else if (GatewayRunning && !gateway.VersionMismatch)
                gatewayCard = Stat("Gateway komputera", Tone.Ok, "Działa", paired, null);
            else
                gatewayCard = Stat("Gateway komputera", gateway.State == "stopped" ? Tone.Error : Tone.Warn,
                    GatewayRunning ? "Działa, inna wersja" : gateway.State == "stopped" ? "Zatrzymany"
                        : gateway.State == "recovering" ? "Ponowny start" : "Uruchamianie",
                    paired, RepairButton(true));

            UIElement vibepolloCard;
            if (!status.VibepolloTokenSaved)
                vibepolloCard = Stat("Vibepollo", Tone.Warn, "Brak tokenu", "Streamy nie wystartują",
                    ui.Button("Pobierz token", ButtonKind.Normal, true, true,
                        delegate { LaunchConfigurator("vibepollo", null); }, "vibepollo-token"));
            else if (gateway.VibepolloOnline == true)
                vibepolloCard = Stat("Vibepollo", Tone.Ok, "Połączone", "Token komputera ważny", null);
            else if (gateway.VibepolloOnline == false)
                vibepolloCard = Stat("Vibepollo", Tone.Error, "Brak połączenia",
                    "Sprawdź, czy Vibepollo działa, albo odnów token",
                    ui.Button("Odnów token", ButtonKind.Normal, true, true,
                        delegate { LaunchConfigurator("vibepollo", null); }, "vibepollo-token"));
            else
                vibepolloCard = Stat("Vibepollo", Tone.Off, "Stan nieznany", "Gateway nie podaje stanu", null);

            UIElement agentCard;
            if (own == null)
                agentCard = Stat("Agent tego konta", Tone.Warn, "Brak profilu", "To konto nie ma profilu MoonWaker",
                    ui.Button("Dodaj profil", ButtonKind.Normal, true, true, delegate { LaunchConfigurator("add", null); }));
            else if (AgentProblem(own) == null)
                agentCard = Stat("Agent tego konta", Tone.Ok, "Działa", "Profil: " + own.Name, null);
            else
                agentCard = Stat("Agent tego konta", Tone.Error, AgentProblem(own), "Profil: " + own.Name, RepairButton(true));

            List<string> remote = new List<string>();
            foreach (ProfileStatus profile in status.Profiles) if (profile.RemoteSignInEnabled) remote.Add(profile.Name);
            UIElement remoteCard = remote.Count > 0
                ? Stat("Zdalne logowanie", Tone.Ok, "Włączone", "Profile: " + String.Join(", ", remote.ToArray()), null)
                : Stat("Zdalne logowanie", Tone.Warn, "Wyłączone", "Żaden profil go nie używa",
                    ui.Button("Włącz w profilu", ButtonKind.Normal, true, false, delegate { ShowPage(Profiles); }));
            Add(content, Columns(gatewayCard, vibepolloCard, agentCard, remoteCard));

            Add(content, Columns(SessionCard(), PairingCard()));

            // First configuration: shown while any step is not met, so it comes back when one
            // stops being met. An unknown Vibepollo state does not bring it back.
            bool[] done = {
                status.VibepolloTokenSaved && gateway.VibepolloOnline != false, gateway.Clients.Count > 0, own != null,
                own != null && (own.SteamConnected || own.EpicConnected || own.PlayniteConnected) };
            int ready = 0;
            foreach (bool step in done) if (step) ready++;
            if (ready == done.Length) return;
            StackPanel setup = new StackPanel();
            setup.Children.Add(ui.Heading("Pierwsza konfiguracja", ui.Muted(ready + " z " + done.Length + " gotowe")));
            string[] names = { "Vibepollo połączone", "TV sparowany", "Profil utworzony", "Biblioteka gier połączona" };
            string[] notes = { null, null, null, " — Steam, Epic lub Playnite" };
            int current = Array.IndexOf(done, false);
            for (int index = 0; index < done.Length; index++)
            {
                UIElement action = null;
                if (index == current)
                {
                    if (index == 0) action = ui.Button(status.VibepolloTokenSaved ? "Odnów token" : "Pobierz token",
                        ButtonKind.Primary, true, true,
                        delegate { LaunchConfigurator("vibepollo", null); }, "setup-vibepollo");
                    else if (index == 1) action = ui.Button("Sparuj TV", ButtonKind.Primary, true, false, Pair, "setup-pair");
                    else if (index == 2) action = ui.Button("Dodaj profil", ButtonKind.Primary, true, true,
                        delegate { LaunchConfigurator("add", null); }, "setup-profile");
                    else action = ui.Button("Połącz bibliotekę", ButtonKind.Primary, true, false,
                        delegate { ShowPage(Profiles); }, "setup-library");
                }
                TextBlock title = ui.Titled(names[index], notes[index]);
                title.FontWeight = FontWeights.Normal;
                if (done[index]) title.Foreground = ui.P.Muted;
                UIElement mark = done[index] ? (UIElement)ui.Check(22) : ui.Step(index + 1, index == current);
                Border item = ui.Item(index == 0, mark, title, null, action);
                item.MinHeight = 38;
                item.Padding = new Thickness(0, 3, 0, 3);
                setup.Children.Add(item);
            }
            Add(content, ui.Card(setup));
        }

        private Border Stat(string title, Tone tone, string state, string detail, UIElement action)
        {
            Grid card = new Grid();
            card.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            card.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            card.RowDefinitions.Add(new RowDefinition());
            System.Windows.Shapes.Ellipse dot = ui.Dot(tone);
            dot.Margin = new Thickness(0, 0, 8, 0);
            card.Children.Add(Row(dot, ui.Bold(title, 13)));
            TextBlock text = ui.Muted(state + "\n" + detail);
            text.LineHeight = 16;
            text.Margin = new Thickness(0, 5, 0, 0);
            Grid.SetRow(text, 1);
            card.Children.Add(text);
            if (action != null)
            {
                ((FrameworkElement)action).VerticalAlignment = VerticalAlignment.Bottom;
                ((FrameworkElement)action).Margin = new Thickness(0, 6, 0, 0);
                Grid.SetRow(action, 2);
                card.Children.Add(action);
            }
            Border border = ui.Card(card);
            border.Padding = new Thickness(14, 13, 14, 13);
            border.MinHeight = 108;
            AutomationProperties.SetName(border, title + ": " + state);
            return border;
        }

        // The Gateway's stream record as gateway-status.json publishes it. The close is offered
        // in every state: the Gateway also closes a stream it holds no record of.
        private Border SessionCard()
        {
            StackPanel card = new StackPanel();
            ActiveSession session = status.Gateway.Session;
            if (session != null && session.StreamStartedUnixMs > 0)
            {
                sessionDuration = ui.Muted("");
                ShowDuration();
            }
            card.Children.Add(ui.Heading("Aktywna sesja", sessionDuration));
            if (session != null)
            {
                string profile = "Profil " + session.ActorProfileName + (session.ActorProfileId == session.ExecutionProfileId
                    ? "" : " (konto " + session.ExecutionProfileName + ")");
                List<string> detail = new List<string> { profile };
                if (session.ClientName.Length > 0) detail.Add(session.ClientName);
                string source = GameSource(session.GameId);
                if (source != null) detail.Add(source);
                StackPanel text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                // The Gateway knows the game's library, not its name.
                text.Children.Add(ui.Bold(session.GameId.Length == 0 ? "Stream bez gry" : "Gra w toku", 15));
                TextBlock line = ui.Muted(String.Join("  •  ", detail.ToArray()));
                line.Margin = new Thickness(0, 2, 0, 0);
                text.Children.Add(line);
                Border avatar = ui.Avatar(session.ActorProfileName, Math.Max(0,
                    status.Profiles.FindIndex(item => item.Id == session.ExecutionProfileId)), 44);
                avatar.Margin = new Thickness(0, 0, 4, 0);
                card.Children.Add(Row(avatar, text));
            }
            else
                card.Children.Add(ui.Muted(status.Gateway.StatusKnown ? "Gateway nie prowadzi teraz sesji streamu."
                    : "Stan sesji nieznany: Gateway go nie podaje."));
            Button close = ui.Button(closingSession ? "Zamykam…" : "Zamknij sesję", ButtonKind.Danger, false, false,
                CloseSession, "close-session");
            close.IsEnabled = !closingSession;
            TextBlock hint = ui.Muted("Zamyka grę i kończy stream.\nSkrót: Ctrl+Alt+Shift+End");
            hint.Margin = new Thickness(10, 0, 0, 0);
            hint.MaxWidth = 260;
            StackPanel row = Row(close, hint);
            row.Margin = new Thickness(0, 14, 0, 0);
            card.Children.Add(row);
            return ui.Card(card);
        }

        private static string GameSource(string gameId)
        {
            if (gameId.StartsWith("steam:")) return "Steam";
            if (gameId.StartsWith("epic:")) return "Epic";
            return gameId.Length > 0 ? "Playnite" : null;
        }

        private void ShowDuration()
        {
            long seconds = Math.Max(0, (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() -
                status.Gateway.Session.StreamStartedUnixMs) / 1000);
            sessionDuration.Text = "trwa " + (seconds / 3600) + ":" + (seconds / 60 % 60).ToString("00") + ":" +
                (seconds % 60).ToString("00");
        }

        private Border PairingCard()
        {
            StackPanel card = new StackPanel();
            long left = PairingSecondsLeft;
            card.Children.Add(ui.Heading("Sparuj TV", left > 0 ? ui.Muted("oczekuje na TV") : null));
            if (left > 0 && pairingCode != null)
            {
                TextBlock code = ui.Text(pairingCode.Substring(0, 3) + " " + pairingCode.Substring(3), 38, ui.P.Text,
                    FontWeights.SemiBold);
                code.Margin = new Thickness(0, 0, 18, 0);
                code.TextWrapping = TextWrapping.NoWrap;
                code.Typography.NumeralAlignment = FontNumeralAlignment.Tabular;
                AutomationProperties.SetName(code, "Kod parowania " + String.Join(" ", pairingCode.ToCharArray()));
                StackPanel countdown = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                pairingCountdown = ui.Muted("");
                pairingCountdown.Margin = new Thickness(0, 0, 0, 6);
                countdown.Children.Add(pairingCountdown);
                pairingBar = ui.Bar(1);
                countdown.Children.Add(pairingBar);
                DockPanel line = new DockPanel();
                line.Children.Add(code);
                line.Children.Add(countdown);
                card.Children.Add(line);
                ShowCountdown(left);
                TextBlock hint = ui.Muted("Na telewizorze wybierz Dodaj komputer i wpisz kod.");
                hint.Margin = new Thickness(0, 10, 0, 0);
                card.Children.Add(hint);
                StackPanel buttons = Row(ui.Button("Nowy kod", ButtonKind.Normal, true, false, Pair, "pair"),
                    ui.Button("Anuluj", ButtonKind.Normal, true, false, CancelPairing, "pair-cancel"));
                buttons.Margin = new Thickness(0, 8, 0, 0);
                card.Children.Add(buttons);
            }
            else
            {
                card.Children.Add(ui.Muted(left > 0
                    ? "Kod parowania jest jeszcze ważny, ale to okno go nie zna. Utwórz nowy kod; poprzedni przestanie działać."
                    : "Na telewizorze wybierz Dodaj komputer, a tutaj utwórz kod ważny 10 minut."));
                Button pair = ui.Button(left > 0 ? "Nowy kod" : "Utwórz kod", ButtonKind.Primary, false, false, Pair, "pair");
                StackPanel buttons = left > 0
                    ? Row(pair, ui.Button("Anuluj", ButtonKind.Normal, false, false, CancelPairing, "pair-cancel"))
                    : Row(pair);
                buttons.Margin = new Thickness(0, 14, 0, 0);
                card.Children.Add(buttons);
            }
            return ui.Card(card);
        }

        // Once a second: the session's duration, the pairing countdown, and a render when the
        // code expires.
        private void Tick()
        {
            if (sessionDuration != null) ShowDuration();
            if (pairingCountdown == null) return;
            long left = PairingSecondsLeft;
            if (left == 0)
            {
                pairingCode = null;
                Render();
                return;
            }
            ShowCountdown(left);
        }

        private void ShowCountdown(long left)
        {
            pairingCountdown.Inlines.Clear();
            pairingCountdown.Inlines.Add(new System.Windows.Documents.Run("Kod wygasa za "));
            pairingCountdown.Inlines.Add(new System.Windows.Documents.Run((left / 60) + ":" + (left % 60).ToString("00")) {
                FontWeight = FontWeights.SemiBold, Foreground = ui.P.Text });
            Ui.Fill(pairingBar, (double)left / PairingLifetimeSeconds);
        }

        // ---- Profile ----

        private void BuildProfiles(StackPanel content)
        {
            Header(content, "Profile", "Profil MoonWaker to konto Windows używane z telewizora", null);
            if (status == null) return;
            StackPanel list = new StackPanel { Width = 318 };
            ProfileStatus selected = Selected();
            foreach (ProfileStatus profile in status.Profiles)
            {
                ProfileStatus item = profile;
                bool on = selected != null && profile.Id == selected.Id;
                StackPanel text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                text.Children.Add(ui.Bold(profile.Name, 13.5));
                text.Children.Add(ui.Muted(profile.Owner + "  •  dzieci: " + profile.Children.Count));
                StackPanel pills = Row(ui.Pill("PIN " + (profile.PinRequired ? "wł." : "wył."), profile.PinRequired ? Tone.Ok : Tone.Off),
                    ui.Pill("Zdalne logowanie " + (profile.RemoteSignInEnabled ? "wł." : "wył."),
                        profile.RemoteSignInEnabled ? Tone.Ok : Tone.Off));
                pills.Margin = new Thickness(0, 6, 0, 0);
                text.Children.Add(pills);
                Border avatar = ui.Avatar(profile.Name, status.Profiles.IndexOf(profile), 44);
                avatar.Margin = new Thickness(0, 0, 4, 0);
                DockPanel line = new DockPanel { Margin = new Thickness(13, 11, 13, 11) };
                line.Children.Add(avatar);
                line.Children.Add(text);
                Button card = ui.Tile(line, on ? ui.P.AccentSoft : ui.P.Card, on ? ui.P.Accent : ui.P.Line,
                    "Profil " + profile.Name, delegate { selectedProfile = item.Id; Render(); });
                card.Margin = new Thickness(0, list.Children.Count == 0 ? 0 : 10, 0, 0);
                list.Children.Add(card);
            }
            Button add = ui.Button("Dodaj profil", ButtonKind.Normal, false, true, delegate { LaunchConfigurator("add", null); });
            add.Margin = new Thickness(0, list.Children.Count == 0 ? 0 : 10, 0, 0);
            list.Children.Add(add);

            DockPanel row = new DockPanel();
            row.Children.Add(list);
            Border detail = ui.Card(selected == null
                ? (UIElement)ui.Muted("Nie ma jeszcze profili. Profil łączy konto Windows tego komputera z telewizorem.")
                : ProfileDetail(selected));
            detail.Margin = new Thickness(12, 0, 0, 0);
            detail.Padding = new Thickness(18, 14, 18, 14);
            row.Children.Add(detail);
            Add(content, row);
        }

        private UIElement ProfileDetail(ProfileStatus profile)
        {
            StackPanel panel = new StackPanel();
            DockPanel head = new DockPanel { Margin = new Thickness(0, 0, 0, 4) };
            Border avatar = ui.Avatar(profile.Name, status.Profiles.IndexOf(profile), 44);
            avatar.Margin = new Thickness(0, 0, 14, 0);
            head.Children.Add(avatar);
            Button edit = ui.Button("Edytuj profil", ButtonKind.Normal, true, true,
                delegate { LaunchConfigurator("edit", profile.Id); });
            DockPanel.SetDock(edit, Dock.Right);
            head.Children.Add(edit);
            StackPanel who = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            StackPanel nameLine = Row(ui.Bold(profile.Name, 17),
                profile.DeletionPending ? ui.Pill("Usuwanie w toku", Tone.Warn) : null,
                !profile.Enabled ? ui.Pill("Wyłączony", Tone.Off) : null);
            who.Children.Add(nameLine);
            who.Children.Add(ui.Muted("Konto Windows: " + profile.Owner));
            head.Children.Add(who);
            panel.Children.Add(head);

            TextBlock libraries = ui.Bold("Biblioteki", 14);
            libraries.Margin = new Thickness(0, 8, 0, 0);
            panel.Children.Add(libraries);
            if (!profile.CurrentUser)
                panel.Children.Add(ui.Item(true, null, ui.Text("Biblioteki tego profilu łączy się z jego konta Windows."),
                    "Zaloguj się na " + profile.Owner + ", aby połączyć Steam, Epic lub Playnite."));
            else
            {
                string id = profile.Id;
                panel.Children.Add(ui.Item(true, null, ui.Bold("Steam", 13),
                    profile.SteamConnected ? "Klucz Web API zapisany dla tego konta" : "Brak klucza Steam Web API",
                    ui.Pill(profile.SteamConnected ? "Połączone" : "Niepołączone", profile.SteamConnected ? Tone.Ok : Tone.Off),
                    ui.Button(profile.SteamConnected ? "Odłącz" : "Połącz", ButtonKind.Normal, true, false,
                        delegate { Steam(profile); }, "steam")));
                UIElement epicAction = !status.LegendaryInstalled ? null : ui.Button(
                    profile.EpicConnected ? "Odłącz" : "Zaloguj w Legendary",
                    profile.EpicConnected ? ButtonKind.Normal : ButtonKind.Primary, true, false, delegate { Epic(profile); }, "epic");
                panel.Children.Add(ui.Item(false, null, ui.Bold("Epic przez Legendary", 13),
                    !status.LegendaryInstalled ? "Brak Legendary — zaktualizuj MoonWaker Host bieżącym instalatorem"
                        : profile.EpicConnected ? "Konto Epic zalogowane w Legendary" : "Legendary nie jest zalogowane",
                    ui.Pill(!status.LegendaryInstalled ? "Brak Legendary" : profile.EpicConnected ? "Połączone" : "Niepołączone",
                        !status.LegendaryInstalled ? Tone.Warn : profile.EpicConnected ? Tone.Ok : Tone.Off),
                    epicAction));
                panel.Children.Add(ui.Item(false, null, ui.Titled("Playnite", " (opcjonalnie)"),
                    profile.PlayniteConnected ? "Agent korzysta z Playnite tego konta" : "Nie wybrano katalogu Playnite",
                    ui.Pill(profile.PlayniteConnected ? "Połączone" : "Niepołączone", profile.PlayniteConnected ? Tone.Ok : Tone.Off),
                    ui.Button(profile.PlayniteConnected ? "Odłącz" : "Połącz", ButtonKind.Normal, true, false,
                        delegate { Playnite(profile); }, "playnite")));
            }

            TextBlock access = ui.Bold("Dostęp", 14);
            access.Margin = new Thickness(0, 12, 0, 0);
            panel.Children.Add(access);
            panel.Children.Add(ui.Item(true, null, ui.Bold("PIN aplikacji", 13),
                profile.PinRequired ? "Wymagany przy wyborze profilu na TV" : "Profil otwiera się na TV bez PIN-u",
                ui.Pill(profile.PinRequired ? "Włączony" : "Wyłączony", profile.PinRequired ? Tone.Ok : Tone.Off),
                ui.Button("Zmień PIN", ButtonKind.Normal, true, true, delegate { LaunchConfigurator("edit", profile.Id); }, "pin")));
            panel.Children.Add(ui.Item(false, null, ui.Bold("Zdalne logowanie", 13),
                profile.RemoteSignInEnabled ? "TV może zalogować to konto Windows"
                    : "TV nie zaloguje tego konta Windows",
                ui.Pill(profile.RemoteSignInEnabled ? "Włączone" : "Wyłączone", profile.RemoteSignInEnabled ? Tone.Ok : Tone.Off),
                ui.Button("Zmień", ButtonKind.Normal, true, true,
                    delegate { LaunchConfigurator("remote-sign-in", profile.Id); }, "remote-sign-in")));
            panel.Children.Add(ui.Item(false, null, ui.Bold("Profile dzieci", 13),
                (profile.Children.Count == 0 ? "Brak profili dzieci" : String.Join(", ", profile.Children.ToArray())) +
                "  •  limity czasu i biblioteka gier",
                ui.Button("Zarządzaj", ButtonKind.Normal, true, true, delegate { LaunchConfigurator("children", profile.Id); })));
            panel.Children.Add(ui.Item(false, null, ui.Bold("Usuń profil", 13), "Usuwa profil MoonWaker, konto Windows zostaje",
                ui.Button("Usuń profil", ButtonKind.Danger, true, true, delegate { LaunchConfigurator("remove", profile.Id); }, "remove")));
            return panel;
        }

        // ---- Urządzenia ----

        private void BuildDevices(StackPanel content)
        {
            Header(content, "Urządzenia", "Sparowane telewizory i ich uprawnienia do profili",
                ui.Button("Sparuj nowy TV", ButtonKind.Primary, false, false, Pair));
            if (status == null) return;
            ProfileStatus manager = Own() ?? (status.Profiles.Count > 0 ? status.Profiles[0] : null);
            if (status.Gateway.Clients.Count == 0)
                Add(content, ui.Card(ui.Muted("Nie ma sparowanych telewizorów. Użyj „Sparuj nowy TV” i wpisz kod na telewizorze.")));
            foreach (PairedClient client in status.Gateway.Clients)
            {
                StackPanel card = new StackPanel();
                DockPanel head = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
                if (manager != null)
                {
                    Button revoke = ui.Button("Cofnij parowanie", ButtonKind.Danger, true, true,
                        delegate { LaunchConfigurator("devices", manager.Id); }, "revoke-" + client.Id);
                    DockPanel.SetDock(revoke, Dock.Right);
                    head.Children.Add(revoke);
                }
                StackPanel title = new StackPanel();
                title.Children.Add(ui.Bold(client.Name, 15));
                List<string> meta = new List<string>();
                if (client.PairedAt > 0) meta.Add("sparowano " + Local(client.PairedAt).ToString("dd.MM.yyyy"));
                if (client.LastSeenAt > 0) meta.Add("widziany " + Seen(client.LastSeenAt));
                title.Children.Add(ui.Muted(String.Join("  •  ", meta.ToArray())));
                head.Children.Add(title);
                card.Children.Add(head);

                Grid table = new Grid();
                table.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(46, GridUnitType.Star) });
                table.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(27, GridUnitType.Star) });
                table.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(27, GridUnitType.Star) });
                AddCell(table, 0, 0, Small("Profil"), false);
                AddCell(table, 0, 1, Small("Korzystanie z profilu"), false);
                AddCell(table, 0, 2, Small("Zdalne logowanie"), false);
                int line = 1;
                foreach (ProfileStatus profile in status.Profiles)
                {
                    List<string> grants;
                    if (!client.Grants.TryGetValue(profile.Id, out grants)) grants = new List<string>();
                    Border avatar = ui.Avatar(profile.Name, status.Profiles.IndexOf(profile), 30);
                    avatar.Margin = new Thickness(0, 0, 10, 0);
                    AddCell(table, line, 0, Row(avatar, ui.Bold(profile.Name, 13)), true);
                    AddCell(table, line, 1, ui.Switch(grants.Contains("use_profile"), profile.Name + ", korzystanie"), true);
                    AddCell(table, line, 2, ui.Switch(grants.Contains("remote_sign_in"), profile.Name + ", zdalne logowanie"), true);
                    line++;
                }
                card.Children.Add(table);
                Add(content, ui.Card(card));
            }
            if (status.Profiles.Count == 0) return;
            StackPanel change = new StackPanel();
            change.Children.Add(ui.Heading("Zmiana uprawnień", null));
            change.Children.Add(ui.Muted("Uprawnienia i usuwanie telewizorów zapisuje chroniony Configurator, osobno dla każdego profilu."));
            WrapPanel buttons = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
            foreach (ProfileStatus profile in status.Profiles)
            {
                string id = profile.Id;
                Button button = ui.Button("Uprawnienia: " + profile.Name, ButtonKind.Normal, true, true,
                    delegate { LaunchConfigurator("devices", id); });
                button.Margin = new Thickness(0, 0, 8, 6);
                buttons.Children.Add(button);
            }
            change.Children.Add(buttons);
            Add(content, ui.Card(change));
        }

        private TextBlock Small(string text) { return ui.Text(text, 11.5, ui.P.Muted, FontWeights.Normal); }

        private void AddCell(Grid table, int row, int column, UIElement element, bool line)
        {
            while (table.RowDefinitions.Count <= row) table.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            if (column > 0) ((FrameworkElement)element).HorizontalAlignment = HorizontalAlignment.Center;
            Border cell = new Border {
                BorderBrush = ui.P.Line, BorderThickness = new Thickness(0, line ? 1 : 0, 0, 0),
                Padding = new Thickness(0, line ? 6 : 4, 0, line ? 6 : 4), Child = element };
            Grid.SetRow(cell, row);
            Grid.SetColumn(cell, column);
            table.Children.Add(cell);
        }

        private static DateTime Local(long unix) { return DateTimeOffset.FromUnixTimeSeconds(unix).LocalDateTime; }

        private static string Seen(long unix)
        {
            DateTime when = Local(unix);
            if (when.Date == DateTime.Today) return "dziś " + when.ToString("HH:mm");
            if (when.Date == DateTime.Today.AddDays(-1)) return "wczoraj " + when.ToString("HH:mm");
            return when.ToString("dd.MM.yyyy HH:mm");
        }

        // ---- Integracje ----

        private void BuildIntegrations(StackPanel content)
        {
            Header(content, "Integracje", "Ustawienia całego komputera", null);
            if (status == null) return;
            StackPanel vibepollo = new StackPanel();
            bool? online = status.Gateway.VibepolloOnline;
            vibepollo.Children.Add(ui.Heading("Vibepollo", null, !status.VibepolloTokenSaved ? ui.Pill("Brak tokenu", Tone.Warn)
                : online == true ? ui.Pill("Połączone", Tone.Ok) : online == false ? ui.Pill("Brak połączenia", Tone.Error)
                : ui.Pill("Stan nieznany", Tone.Off)));
            TextBlock about = ui.Text("MoonWaker używa Vibepollo do streamu obrazu. Jeden token obsługuje cały komputer i wszystkie profile.",
                13, ui.P.Muted, FontWeights.Normal);
            about.MaxWidth = 640;
            about.HorizontalAlignment = HorizontalAlignment.Left;
            about.Margin = new Thickness(0, 0, 0, 10);
            vibepollo.Children.Add(about);
            vibepollo.Children.Add(ui.KeyValue(true, "Token komputera", ui.Text(!status.VibepolloTokenSaved ? "brak"
                : online == true ? "zapisany, ważny" : "zapisany")));
            vibepollo.Children.Add(ui.KeyValue(false, "Ostatni zapis", ui.Text(status.VibepolloTokenSavedAt == null ? "—"
                : status.VibepolloTokenSavedAt.Value.ToString("dd.MM.yyyy HH:mm"))));
            Button token = ui.Button("Pobierz / odnów token", ButtonKind.Primary, false, true,
                delegate { LaunchConfigurator("vibepollo", null); }, "vibepollo-token");
            token.Margin = new Thickness(0, 10, 0, 0);
            vibepollo.Children.Add(token);
            Add(content, ui.Card(vibepollo));

            if (!status.DiscordInstalled) return;
            ProfileStatus own = Own();
            StackPanel discord = new StackPanel();
            discord.Children.Add(ui.Heading("Discord", null, own == null ? null : own.DiscordConfigured
                ? ui.Pill("Skonfigurowany", Tone.Ok) : ui.Pill("Nieskonfigurowany", Tone.Off)));
            TextBlock purpose = ui.Text(own == null ? "Czat głosowy i wiadomości na TV. To konto Windows nie ma profilu MoonWaker."
                : "Czat głosowy i wiadomości na TV dla profilu " + own.Name + ".", 13, ui.P.Muted, FontWeights.Normal);
            purpose.Margin = new Thickness(0, 0, 0, 10);
            discord.Children.Add(purpose);
            if (own != null)
            {
                string id = own.DiscordClientId;
                discord.Children.Add(ui.KeyValue(true, "Dane aplikacji (Client ID)", ui.Text(id.Length < 8 ? (id.Length == 0 ? "—" : id)
                    : id.Substring(0, 4) + " •••• •••• " + id.Substring(id.Length - 4), 12, ui.P.Text, FontWeights.Normal)));
                discord.Children.Add(ui.KeyValue(false, "Client Secret", ui.Text(own.DiscordConfigured ? "zapisany" : "brak")));
                if (own.DiscordConfigured)
                    discord.Children.Add(ui.KeyValue(false, "Stan", ui.Text(DiscordState(own))));
                StackPanel actions = own.DiscordConfigured
                    ? Row(ui.Button("Zmień dane aplikacji", ButtonKind.Normal, false, false, delegate { Discord(own); }),
                        ui.Button("Odłącz", ButtonKind.Danger, false, false, delegate { DisconnectDiscord(own); }, "discord-disconnect"))
                    : Row(ui.Button("Połącz Discord", ButtonKind.Primary, false, false, delegate { Discord(own); }));
                actions.Margin = new Thickness(0, 10, 0, 0);
                discord.Children.Add(actions);
            }
            Add(content, ui.Card(discord));
        }

        // ---- Diagnostyka ----

        private void BuildDiagnostics(StackPanel content)
        {
            Header(content, "Diagnostyka", "Wersje, komponenty i narzędzia naprawy",
                ui.Button("Eksportuj diagnostykę", ButtonKind.Normal, false, false, Export));
            if (status == null) return;
            List<KeyValuePair<Tone, string>> problems = Problems();
            bool repairInBanner = !notices.ContainsKey(Diagnostics) && problems.Count > 0;
            if (repairInBanner)
                Add(content, ui.Banner(problems[0].Key, problems[0].Value, problems[0].Value.StartsWith("Działająca")
                    ? "Usługi korzystają jeszcze ze starej wersji." : null, RepairButton(false)));

            StackPanel versions = new StackPanel();
            versions.Children.Add(ui.Heading("Wersje", null));
            versions.Children.Add(ui.KeyValue(true, "Zainstalowana", ui.Bold(status.InstalledVersion, 13)));
            UIElement running;
            if (!GatewayRunning) running = ui.Text("—");
            else if (status.Gateway.VersionMismatch)
            {
                TextBlock version = ui.Text(status.Gateway.RuntimeVersion + (status.Gateway.RuntimeVersion == status.InstalledVersion
                    ? " (" + status.Gateway.RuntimeBuild + ")" : ""), 13, ui.P.Warn, FontWeights.SemiBold);
                running = Row(version, ui.Pill("różnica", Tone.Warn));
            }
            else running = ui.Bold(status.Gateway.RuntimeVersion, 13);
            versions.Children.Add(ui.KeyValue(false, "Działająca", running));
            Add(content, ui.Card(versions));

            StackPanel components = new StackPanel();
            components.Children.Add(ui.Heading("Komponenty", null));
            Tone mic = microphone == null ? Tone.Off : microphone == "ready" ? Tone.Ok
                : microphone == "worker_missing" ? Tone.Error : Tone.Warn;
            components.Children.Add(Component(true, mic, "Mikrofon na TV",
                microphone == null ? "Sprawdzam urządzenie wejściowe…"
                    : microphone == "ready" ? "Urządzenie wejściowe wykryte, worker działa"
                    : microphone == "worker_missing" ? "Brak workera mikrofonu — zainstaluj ponownie MoonWaker Host"
                    : "Nie znaleziono jednoznacznego urządzenia wejściowego Steam",
                microphone == null ? "Sprawdzam" : microphone == "ready" ? "Działa" : "Problem"));
            components.Children.Add(Component(false, status.LegendaryInstalled ? Tone.Ok : Tone.Warn, "Legendary (Epic)",
                status.LegendaryInstalled ? "Zainstalowany z MoonWaker Host" : "Zaktualizuj MoonWaker Host bieżącym instalatorem",
                status.LegendaryInstalled ? "Zainstalowany" : "Brak"));
            // Gateway and this account's Agent: the two halves of a TV request, one row.
            ProfileStatus own = Own();
            string agentProblem = own == null ? null : AgentProblem(own);
            string serviceProblem = !status.Gateway.Installed ? "Gateway nie jest zainstalowany"
                : !GatewayRunning ? "Gateway: " + (status.Gateway.State == "stopped" ? "zatrzymany" : "uruchamia się") : null;
            string pair = serviceProblem ?? (agentProblem == null ? null : "Agent: " + agentProblem);
            components.Children.Add(Component(false, pair == null ? Tone.Ok : serviceProblem != null &&
                    status.Gateway.State != "stopped" && status.Gateway.Installed ? Tone.Warn : Tone.Error,
                own == null ? "Gateway" : "Gateway i Agent tego konta",
                pair ?? (own == null ? "Odpowiada poprawnie; to konto nie ma profilu" : "Odpowiadają poprawnie"),
                pair == null ? "Działa" : "Problem"));
            if (own != null && status.DiscordInstalled && own.DiscordConfigured)
                components.Children.Add(Component(false, own.Discord == "online" ? Tone.Ok : Tone.Error, "Discord Bridge",
                    DiscordState(own), own.Discord == "online" ? "Działa" : "Problem"));
            Add(content, ui.Card(components));

            StackPanel repair = new StackPanel();
            repair.Children.Add(ui.Heading("Napraw", null));
            repair.Children.Add(ui.Text("Jedno „Napraw” sprawdza usługi i Agenta tego konta i restartuje to, co nie działa. Nie zmienia ustawień ani profili.",
                13, ui.P.Muted, FontWeights.Normal));
            if (!repairInBanner)
            {
                Button button = RepairButton(false);
                button.Margin = new Thickness(0, 10, 0, 0);
                repair.Children.Add(button);
            }
            Add(content, ui.Card(repair));
        }

        private Border Component(bool first, Tone tone, string title, string detail, string state)
        {
            return ui.Item(first, ui.Dot(tone), ui.Bold(title, 13), detail, ui.Pill(state, tone));
        }

        // The one "Napraw" of Host Control: one button wherever it is offered.
        private Button RepairButton(bool small)
        {
            return ui.Button("Napraw", ButtonKind.Primary, small, false, Repair, "repair");
        }

        // ---- Actions ----

        // Runs work off the UI thread with progress in the status bar; a failure becomes the
        // notice of the page it started on.
        private async void Run(string progress, Func<string> work, string failure, bool restartProcesses)
        {
            if (busy) { SetStatus("Poczekaj na koniec bieżącej operacji.", Tone.Warn); return; }
            int origin = page;
            busy = true;
            SetStatus(progress, Tone.Accent);
            try
            {
                string done = await Task.Run(work);
                if (restartProcesses) processes.RestartAll();
                notices.Remove(origin);
                busy = false;
                SetStatus(done, Tone.Ok);
            }
            catch (Exception error)
            {
                Fail(origin, failure, error.Message);
            }
            finally { busy = false; }
            Render();
            Refresh();
        }

        internal void ReportUnexpected(Exception error)
        {
            busy = false;
            try { SetStatus("Błąd okna: " + error.Message, Tone.Error); } catch (Exception) { }
        }

        internal void Fail(int origin, string title, string message)
        {
            notices[origin] = new Notice { Tone = Tone.Error, Title = title, Text = message };
            busy = false;
            SetStatus(title, Tone.Error);
            Render();
        }

        private void Steam(ProfileStatus profile)
        {
            string id = profile.Id;
            if (profile.SteamConnected)
            {
                if (!HostDialog.Confirm(this, ui, "Odłączyć Steam?",
                        "Odłączenie usunie zaszyfrowany klucz Web API tego profilu. MoonWaker nie będzie odświeżać pełnej " +
                        "biblioteki Steam; lokalne manifesty nadal potwierdzą zainstalowane gry.", "Odłącz", true)) return;
                Run("Odłączam Steam…", delegate { backend.RemoveSteamWebApiKey(id); return "Steam odłączony."; },
                    "Steam: nie udało się odłączyć.", false);
                return;
            }
            string key = HostDialog.SteamKey(this, ui, profile.Name);
            if (key == null) return;
            Run("Zapisuję klucz Steam…", delegate
            {
                backend.SaveSteamWebApiKey(id, key);
                return "Klucz Steam zapisany i zabezpieczony przez Windows DPAPI.";
            }, "Steam: nie udało się połączyć.", false);
        }

        private void Epic(ProfileStatus profile)
        {
            string id = profile.Id;
            if (profile.EpicConnected)
            {
                if (!HostDialog.Confirm(this, ui, "Odłączyć Epic?",
                        "Odłączenie usunie uwierzytelnienie Legendary. MoonWaker nie będzie odświeżać katalogu Epic ani " +
                        "uruchamiać gier Epic do ponownego połączenia. Pliki gier zostaną.", "Odłącz", true)) return;
                Run("Odłączam Epic…", delegate { backend.DisconnectEpic(id); return "Epic odłączony od Legendary."; },
                    "Epic: nie udało się odłączyć.", false);
                return;
            }
            if (!HostDialog.Confirm(this, ui, "Zaloguj w Legendary",
                    "Epic działa przez Legendary, nie przez Epic Games Launcher; gry nie są z nim synchronizowane. " +
                    "Otworzy się okno Legendary i strona logowania Epic. MoonWaker nie widzi hasła Epic.", "Zaloguj", false)) return;
            Run("Logowanie w Legendary — dokończ je w oknie Legendary…",
                delegate { backend.ConnectEpic(id); return "Epic połączony przez Legendary."; },
                "Epic: nie udało się połączyć.", false);
        }

        private void Playnite(ProfileStatus profile)
        {
            string id = profile.Id;
            if (profile.PlayniteConnected)
            {
                if (!HostDialog.Confirm(this, ui, "Odłączyć Playnite?",
                        "Steam i Epic będą działać bez zmian.", "Odłącz", true)) return;
                Run("Odłączam Playnite…", delegate { backend.DisconnectPlaynite(id); return "Playnite odłączony."; },
                    "Playnite: nie udało się odłączyć.", true);
                return;
            }
            string directory;
            using (System.Windows.Forms.FolderBrowserDialog dialog = new System.Windows.Forms.FolderBrowserDialog())
            {
                dialog.Description = "Wybierz katalog instalacji Playnite z zainstalowanym SunshinePlaynite Connector.";
                dialog.ShowNewFolderButton = false;
                if (dialog.ShowDialog(new Win32Owner(this)) != System.Windows.Forms.DialogResult.OK) return;
                directory = dialog.SelectedPath;
            }
            Run("Łączę Playnite…", delegate
            {
                backend.ConnectPlaynite(id, directory);
                return "Playnite połączony. Uruchom ponownie Playnite, aby wczytał łatkę MoonWaker.";
            }, "Playnite: nie udało się połączyć.", true);
        }

        private sealed class Win32Owner : System.Windows.Forms.IWin32Window
        {
            private readonly IntPtr handle;
            internal Win32Owner(Window window) { handle = new WindowInteropHelper(window).Handle; }
            public IntPtr Handle { get { return handle; } }
        }

        private void Discord(ProfileStatus profile)
        {
            string id = profile.Id, clientId, secret;
            if (!HostDialog.Discord(this, ui, profile.Name, profile.DiscordConfigured, out clientId, out secret)) return;
            Run("Zapisuję dane Discorda…", delegate
            {
                backend.ConfigureDiscord(id, clientId, secret);
                return "Dane Discorda zapisane. Autoryzację OAuth zatwierdzisz w Discordzie przy pierwszym użyciu z TV.";
            }, "Discord: nie udało się zapisać danych.", true);
        }

        private void DisconnectDiscord(ProfileStatus profile)
        {
            string id = profile.Id;
            if (!HostDialog.Confirm(this, ui, "Odłączyć Discord?",
                    "Usunie token OAuth i Client Secret Discorda profilu " + profile.Name + ". Discord tego profilu przestanie działać na TV.",
                    "Odłącz", true)) return;
            Run("Odłączam Discord…", delegate { backend.DisconnectDiscord(id); return "Discord odłączony od profilu."; },
                "Discord: nie udało się odłączyć.", true);
        }

        private void Repair()
        {
            Run("Naprawiam…", delegate
            {
                RepairResult result = backend.Repair(processes);
                string restarted = result.Restarted.Count == 0 ? "" :
                    "Uruchomiono ponownie: " + String.Join(", ", result.Restarted.ToArray()) + ".";
                if (result.Problem != null) throw new InvalidOperationException((restarted + " " + result.Problem).Trim());
                return restarted.Length > 0 ? restarted : "Gateway i procesy profilu tego konta działają; nie było czego naprawiać.";
            }, "Napraw nie dokończył naprawy.", false);
        }

        private async void Export()
        {
            if (busy) return;
            busy = true;
            SetStatus("Eksportuję diagnostykę…", Tone.Accent);
            try
            {
                DiagnosticsExport export = await Task.Run(delegate { return backend.ExportDiagnostics(null); });
                busy = false;
                string path = export.Path;
                notices[Diagnostics] = new Notice {
                    Tone = Tone.Accent, Title = "Pakiet diagnostyczny zapisany:", Text = path, ActionLabel = "Pokaż w folderze",
                    Action = delegate
                    {
                        notices.Remove(Diagnostics);
                        Render();
                        try { Process.Start("explorer.exe", "/select,\"" + path + "\""); } catch { }
                    } };
                SetStatus("Pakiet diagnostyczny zapisany (" + export.Files + " plików).", Tone.Ok);
                Render();
            }
            catch (Exception error) { Fail(Diagnostics, "Eksport diagnostyki nie powiódł się.", error.Message); }
            finally { busy = false; }
        }

        // A code for "Dodaj komputer" on the TV; the Przegląd page shows it with its countdown.
        private async void Pair()
        {
            if (busy) return;
            busy = true;
            try
            {
                string code = null;
                HostStatus fresh = await Task.Run(delegate
                {
                    code = backend.CreatePairingCode();
                    return backend.ReadStatus();
                });
                busy = false;
                ShowPairingCode(code);
                Apply(fresh);
                SetStatus("Kod parowania jest ważny 10 minut.", Tone.Ok);
            }
            catch (Exception error) { Fail(Overview, "Nie udało się utworzyć kodu parowania.", error.Message); }
            finally { busy = false; }
        }

        private async void CancelPairing()
        {
            if (busy) return;
            busy = true;
            try
            {
                HostStatus fresh = await Task.Run(delegate
                {
                    backend.CancelPairingCode();
                    return backend.ReadStatus();
                });
                busy = false;
                pairingCode = null;
                status = fresh;
                Render();
                SetStatus("Kod parowania anulowany.", Tone.Ok);
            }
            catch (Exception error) { Fail(Overview, "Nie udało się anulować kodu parowania.", error.Message); }
            finally { busy = false; }
        }

        internal void ShowPairingCode(string code)
        {
            pairingCode = code;
            page = Overview;
            notices.Remove(Overview);
            Render();
        }

        // The tray menu, the global hotkey and its secure-desktop pipe all end the session the
        // same way: the Gateway closes the stream and the game on its own record.
        internal async void CloseSession()
        {
            if (closingSession) return;
            closingSession = true;
            Render();
            SetStatus("Zamykam sesję…", Tone.Accent);
            try
            {
                CloseSessionResult result = await Task.Run(delegate { return backend.CloseSession(25); });
                string text = CloseSessionMessage(result.Reason);
                if (!result.Ok) throw new InvalidOperationException(text);
                // The stream is closed, but the game may still run: a warning, not a success.
                bool unconfirmed = result.Reason == "stream_closed_game_unconfirmed";
                if (unconfirmed) notices[Overview] = new Notice { Tone = Tone.Warn, Title = "Sesja zamknięta częściowo.", Text = text };
                else notices.Remove(Overview);
                SetStatus(text, unconfirmed ? Tone.Warn : Tone.Ok);
                Notify(text, unconfirmed ? System.Windows.Forms.ToolTipIcon.Warning : System.Windows.Forms.ToolTipIcon.Info);
            }
            catch (Exception error)
            {
                notices[Overview] = new Notice { Tone = Tone.Error, Title = "Nie zamknięto sesji.", Text = error.Message };
                SetStatus(error.Message, Tone.Error);
                Notify(error.Message, System.Windows.Forms.ToolTipIcon.Error);
            }
            finally { closingSession = false; }
            Render();
        }

        private void Notify(string text, System.Windows.Forms.ToolTipIcon icon)
        {
            if (!IsVisible && Balloon != null) Balloon(text, icon);
        }

        internal static string CloseSessionMessage(string reason)
        {
            switch (reason)
            {
                case "stream_and_game_closed": return "Zamknięto stream i grę.";
                case "stream_closed": return "Zamknięto stream.";
                case "game_closed": return "Zamknięto grę.";
                case "stream_closed_game_unconfirmed":
                    return "Zamknięto stream. Nie udało się potwierdzić procesu gry — gra mogła pozostać uruchomiona na hoście.";
                case "session_closed": return "Zamknięto sesję dziecka: grę i stream.";
                case "nothing_to_close": return "Nie ma sesji do zamknięcia.";
                case "operation_in_progress":
                    return "Trwa inna operacja na hoście (start albo zamykanie z TV). Spróbuj ponownie za chwilę.";
                case "session_on_other_profile":
                    return "Sesja działa na innym profilu Windows. Zamknij ją z tamtego konta.";
                case "game_state_unverified":
                    return "Nie udało się potwierdzić procesu gry, a MoonWaker nie prowadzi streamu, więc niczego nie zamknięto.";
                case "game_stop_unconfirmed":
                    return "Gra nie potwierdziła zamknięcia. Stream pozostał otwarty.";
                case "streamer_identity_unconfirmed":
                    return "W streamerze działa aplikacja albo klient spoza tej sesji MoonWaker. Niczego nie zamknięto: gra i stream pozostały.";
                case "game_closed_stream_unconfirmed":
                    return "Zamknięto grę, ale streamer nie potwierdził zamknięcia streamu — stream mógł pozostać otwarty.";
                case "streamer_close_unconfirmed":
                    return "Streamer nie potwierdził zamknięcia streamu.";
                case "streamer_snapshot_unavailable":
                    return "Nie udało się odczytać stanu streamera. Niczego nie zamknięto.";
                case "child_cleanup_required":
                    return "Nie udało się dokończyć zamykania sesji dziecka. Spróbuj ponownie.";
                case "gateway_not_responding":
                    return "Gateway nie odpowiada. Sprawdź, czy usługa MoonWaker Gateway działa. Sesja nie została zamknięta.";
                case "result_timeout":
                    return "Gateway przyjął żądanie, ale nie potwierdził zamknięcia sesji.";
                case "profile_ambiguous":
                    return "Nie można jednoznacznie ustalić profilu tego konta Windows.";
                case "registry_unreadable":
                    return "Nie można odczytać rejestru Gatewaya (gateway.json), więc sesja nie została zamknięta. Spróbuj ponownie; jeśli błąd się powtarza, zainstaluj ponownie MoonWaker Host.";
                case "channel_missing":
                    return "Ten Gateway nie obsługuje zamykania sesji z hosta. Zainstaluj ponownie MoonWaker Host.";
                default: return "Gateway odmówił zamknięcia sesji (" + reason + ").";
            }
        }

        // Profiles, devices, remote sign-in, child profiles and the Vibepollo token are written
        // by the elevated Configurator, behind UAC; this process passes no secret to it.
        private async void LaunchConfigurator(string mode, string profileId)
        {
            int origin = page;
            string executable = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "MoonWakerHostConfigurator.exe");
            if (!File.Exists(executable))
            {
                Fail(origin, "Brak konfiguratora hosta.", "Zaktualizuj instalację MoonWaker Host.");
                return;
            }
            if (!String.IsNullOrWhiteSpace(profileId) &&
                !System.Text.RegularExpressions.Regex.IsMatch(profileId, "^[A-Za-z0-9._-]{1,64}$"))
            {
                Fail(origin, "Identyfikator profilu jest nieprawidłowy.", profileId);
                return;
            }
            try
            {
                SetStatus("Otwieram chroniony konfigurator…", Tone.Accent);
                string arguments = "--mode " + Quote(mode) +
                    (String.IsNullOrWhiteSpace(profileId) ? "" : " --profile " + Quote(profileId));
                ProcessStartInfo info = new ProcessStartInfo(executable, arguments);
                info.UseShellExecute = true;
                info.Verb = "runas";
                info.WorkingDirectory = AppDomain.CurrentDomain.BaseDirectory;
                using (Process process = Process.Start(info))
                    await Task.Run(delegate { process.WaitForExit(); });
                SetStatus("Konfigurator zamknięty.", Tone.Ok);
            }
            catch (System.ComponentModel.Win32Exception error)
            {
                if (error.NativeErrorCode == 1223) SetStatus("Anulowano zgodę administratora.", Tone.Off);
                else Fail(origin, "Nie udało się otworzyć konfiguratora.", error.Message);
            }
            Refresh();
        }

        private static string Quote(string value) { return "\"" + value.Replace("\"", "\\\"") + "\""; }
    }
}
