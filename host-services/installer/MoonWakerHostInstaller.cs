using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Shapes;
using Microsoft.Win32;
using MoonWaker.HostControl;
using Path = System.IO.Path;

[assembly: AssemblyVersion("0.7.146.0")]
[assembly: AssemblyFileVersion("0.7.146.0")]
[assembly: AssemblyInformationalVersion("0.7.146+2026.10.17-launch-license-art")]

namespace MoonWaker.HostInstaller
{
    internal static class NativeMethods
    {
        [StructLayout(LayoutKind.Sequential)]
        internal struct SecurityAttributes
        {
            internal int Length;
            internal IntPtr SecurityDescriptor;
            [MarshalAs(UnmanagedType.Bool)] internal bool InheritHandle;
        }

        [DllImport("kernel32.dll", EntryPoint = "CreateDirectoryW", CharSet = CharSet.Unicode,
            SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CreateDirectory(string path,
            ref SecurityAttributes securityAttributes);

        internal const int MoveFileDelayUntilReboot = 4;

        [DllImport("kernel32.dll", EntryPoint = "MoveFileExW", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool MoveFileEx(string existing, string replacement, int flags);
    }

    // Starts a program through the signed-in user's Explorer (the desktop shell view's
    // IShellDispatch2.ShellExecute), so it runs with that user's standard token rather than
    // inheriting this elevated installer's. Must run on an STA thread.
    internal static class UnelevatedShell
    {
        [ComImport, Guid("9BA05972-F6A8-11CF-A442-00A0C90A8F39")]
        private class ShellWindows { }

        [ComImport, Guid("85CB6900-4D95-11CF-960C-0080C7F4EE85"),
         InterfaceType(ComInterfaceType.InterfaceIsDual)]
        private interface IShellWindows
        {
            // Slots before FindWindowSW: get_Count, Item, _NewEnum, Register, RegisterPending,
            // Revoke, OnNavigate, OnActivated. They are never called.
            void Slot1(); void Slot2(); void Slot3(); void Slot4();
            void Slot5(); void Slot6(); void Slot7(); void Slot8();
            [return: MarshalAs(UnmanagedType.IDispatch)]
            object FindWindowSW(ref object location, ref object locationRoot, int windowClass,
                out int window, int options);
        }

        [ComImport, Guid("6D5140C1-7436-11CE-8034-00AA006009FA"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IServiceProvider
        {
            [return: MarshalAs(UnmanagedType.IUnknown)]
            object QueryService(ref Guid service, ref Guid interfaceId);
        }

        [ComImport, Guid("000214E2-0000-0000-C000-000000000046"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IShellBrowser
        {
            // IOleWindow (2) and IShellBrowser methods before QueryActiveShellView (10).
            void Slot1(); void Slot2(); void Slot3(); void Slot4(); void Slot5(); void Slot6();
            void Slot7(); void Slot8(); void Slot9(); void Slot10(); void Slot11(); void Slot12();
            IShellView QueryActiveShellView();
        }

        [ComImport, Guid("000214E3-0000-0000-C000-000000000046"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IShellView
        {
            // IOleWindow (2) and IShellView methods before GetItemObject (10).
            void Slot1(); void Slot2(); void Slot3(); void Slot4(); void Slot5(); void Slot6();
            void Slot7(); void Slot8(); void Slot9(); void Slot10(); void Slot11(); void Slot12();
            [return: MarshalAs(UnmanagedType.IDispatch)]
            object GetItemObject(uint item, ref Guid interfaceId);
        }

        private const int SwcDesktop = 8;
        private const int SwfoNeedDispatch = 1;
        private const uint SvgioBackground = 0;

        internal static void Start(string file, string arguments, string directory)
        {
            object location = null, locationRoot = null;
            int window;
            object desktop = ((IShellWindows)new ShellWindows()).FindWindowSW(ref location,
                ref locationRoot, SwcDesktop, out window, SwfoNeedDispatch);
            if (desktop == null) throw new InvalidOperationException("Explorer desktop is not running.");
            Guid topLevelBrowser = new Guid("4C96BE40-915C-11CF-99D3-00AA004AE837");
            Guid shellBrowser = typeof(IShellBrowser).GUID;
            IShellBrowser browser = (IShellBrowser)((IServiceProvider)desktop).QueryService(
                ref topLevelBrowser, ref shellBrowser);
            Guid dispatch = new Guid("00020400-0000-0000-C000-000000000046");
            object folderView = browser.QueryActiveShellView().GetItemObject(SvgioBackground, ref dispatch);
            object shell = folderView.GetType().InvokeMember("Application",
                BindingFlags.GetProperty, null, folderView, null);
            shell.GetType().InvokeMember("ShellExecute", BindingFlags.InvokeMethod, null, shell,
                new object[] { file, arguments, directory, "open", 1 });
        }
    }

    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            bool uninstall = Array.IndexOf(args, "--uninstall") >= 0;
            if (uninstall && InstallerWindow.RelaunchOutsideInstallation()) return;
            InstallerWindow.ForgetStagedCopyAtReboot();
            new Application().Run(new InstallerWindow(uninstall));
        }
    }

    // The installer in Host Control's style (HostControlUi.cs): a step list on the left, cards
    // on the right and an action bar below. Every change of state renders the window anew.
    internal sealed class InstallerWindow : Window
    {
        private enum Page { Check, Ready, Remove, Progress }

        // A component the uninstall page offers. Patterns: the Apps & features names that
        // Uninstall-MoonWakerHostServices.ps1 removes for Key.
        private sealed class Part
        {
            internal string Key, English, Polish, EnglishNote, PolishNote;
            internal string[] Patterns;
            internal bool Locked, Selected, Present;
        }

        // Fixed, never chosen: the machine-wide Run entry starts code from here in every
        // account, so its parent must be one that ordinary users cannot rename or create in.
        private static readonly string InstallDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "MoonWaker");
        private const string UninstallKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\MoonWakerHost";
        private const string InstalledCopyName = "MoonWakerHostInstaller.exe";

        private readonly Ui ui;
        private readonly string payloadVersion;
        private readonly List<Part> parts = new List<Part>();
        private int language;
        private Page page;

        private bool checking = true;
        private WakeOnLanProbe wakeOnLan = new WakeOnLanProbe();
        private bool vibepolloInstalled;
        private string vibepolloVersion;
        private string vibepolloError = "";

        private bool removing;
        private bool running;
        private bool completed;
        private bool failed;
        private bool restartHostControl;
        private double progress;
        private string progressTitle = "";
        private string progressDetail = "";
        private readonly StringBuilder log = new StringBuilder();
        private bool showLog;

        internal InstallerWindow(bool uninstall)
        {
            payloadVersion = ReadPayloadVersion();
            language = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "pl" ? 1 : 0;
            ui = new Ui(new Palette(Palette.WindowsAppsUseLightTheme()));
            Width = 980;
            Height = 700;
            MinWidth = 860;
            MinHeight = 600;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = ui.P.Background;
            Foreground = ui.P.Text;
            FontFamily = Ui.Font;
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);
            SourceInitialized += delegate { TitleBar.Apply(this, !ui.P.Light); };
            Closing += delegate(object sender, CancelEventArgs e) { e.Cancel = running; };

            parts.Add(new Part { Key = "MoonWaker", English = "MoonWaker Host", Polish = "MoonWaker Host",
                EnglishNote = "Gateway, Login Broker, Host Control, profiles, pairing and stored credentials.",
                PolishNote = "Gateway, Login Broker, Host Control, profile, parowanie i zapisane poświadczenia.",
                Locked = true, Selected = true });
            parts.Add(new Part { Key = "Legendary", English = "Legendary (Epic Games)", Polish = "Legendary (Epic Games)",
                EnglishNote = "The program and every profile's Epic sign-in. Installed games stay on disk.",
                PolishNote = "Program i logowania Epic wszystkich profili. Zainstalowane gry zostają na dysku.",
                Locked = true, Selected = true });
            parts.Add(new Part { Key = "Vibepollo", English = "Vibepollo", Polish = "Vibepollo",
                EnglishNote = "Its own uninstaller removes it with Vibepollo Display Driver.",
                PolishNote = "Usuwany własnym deinstalatorem, razem z Vibepollo Display Driver.",
                Patterns = new[] { "*Vibepollo*" }, Selected = true });
            parts.Add(new Part { Key = "ViGEmBus", English = "ViGEmBus", Polish = "ViGEmBus",
                EnglishNote = "Virtual gamepad driver; other programs may use it.",
                PolishNote = "Sterownik wirtualnych gamepadów; mogą go używać inne programy.",
                Patterns = new[] { "*ViGEmBus*", "*Virtual Gamepad Emulation Bus*" } });
            parts.Add(new Part { Key = "Python", English = "Python 3.12", Polish = "Python 3.12",
                EnglishNote = "Gateway runtime; other programs may use it.",
                PolishNote = "Środowisko Gateway; mogą go używać inne programy.",
                Patterns = new[] { "Python 3.12.*" } });
            parts.Add(new Part { Key = "OpenSSL", English = "OpenSSL", Polish = "OpenSSL",
                EnglishNote = "Certificate tool of the Gateway; other programs may use it.",
                PolishNote = "Narzędzie certyfikatów Gateway; mogą go używać inne programy.",
                Patterns = new[] { "OpenSSL*" } });
            DetectParts();

            page = uninstall ? Page.Remove : Page.Check;
            Render();
            Loaded += async delegate { await RefreshPreflightAsync(); };
        }

        // An uninstall started from Apps & features runs the copy inside the installation,
        // which the removal deletes: that copy hands over to one in protected staging.
        internal static bool RelaunchOutsideInstallation()
        {
            string self = Path.GetFullPath(Assembly.GetExecutingAssembly().Location);
            if (!self.StartsWith(InstallDirectory.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase))
                return false;
            string copy = Path.Combine(CreateProtectedStagingDirectory(), InstalledCopyName);
            File.Copy(self, copy);
            using (Process.Start(new ProcessStartInfo(copy, "--uninstall") { UseShellExecute = false })) { }
            return true;
        }

        // The staged copy cannot delete itself while it runs; Windows removes it at restart.
        internal static void ForgetStagedCopyAtReboot()
        {
            string self = Path.GetFullPath(Assembly.GetExecutingAssembly().Location);
            string directory = Path.GetDirectoryName(self);
            string programData = Path.GetFullPath(Environment.GetFolderPath(
                Environment.SpecialFolder.CommonApplicationData)).TrimEnd('\\');
            if (!String.Equals(Path.GetDirectoryName(directory), programData, StringComparison.OrdinalIgnoreCase) ||
                !Path.GetFileName(directory).StartsWith("MoonWakerInstaller-", StringComparison.Ordinal)) return;
            NativeMethods.MoveFileEx(self, null, NativeMethods.MoveFileDelayUntilReboot);
            NativeMethods.MoveFileEx(directory, null, NativeMethods.MoveFileDelayUntilReboot);
        }

        // ---- Rendering -------------------------------------------------------------------

        private void Render()
        {
            Title = T("MoonWaker Host Installer ", "Instalator MoonWaker Host ") + payloadVersion;
            Grid root = new Grid { Background = ui.P.Background };
            root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(216) });
            root.ColumnDefinitions.Add(new ColumnDefinition());
            root.Children.Add(Sidebar());

            StackPanel content = new StackPanel { Margin = new Thickness(26, 18, 26, 14) };
            if (page == Page.Check) BuildCheck(content);
            else if (page == Page.Ready) BuildReady(content);
            else if (page == Page.Remove) BuildRemove(content);
            else BuildProgress(content);
            ScrollViewer scroll = new ScrollViewer {
                Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Focusable = false };
            scroll.Resources.Add(typeof(ScrollBar), ui.ScrollBarStyle());
            DockPanel right = new DockPanel();
            Grid.SetColumn(right, 1);
            Border actions = ActionBar();
            DockPanel.SetDock(actions, Dock.Bottom);
            right.Children.Add(actions);
            right.Children.Add(scroll);
            root.Children.Add(right);
            Content = root;
        }

        private Border Sidebar()
        {
            Palette p = ui.P;
            DockPanel panel = new DockPanel { LastChildFill = false };
            StackPanel brand = new StackPanel { Margin = new Thickness(12, 4, 12, 22) };
            brand.Children.Add(ui.Text("MOONWAKER", 17, p.Text, FontWeights.Bold));
            TextBlock sub = ui.Text(T("Host installer", "Instalator hosta"), 11, p.Muted, FontWeights.Normal);
            sub.Margin = new Thickness(0, 2, 0, 0);
            brand.Children.Add(sub);
            DockPanel.SetDock(brand, Dock.Top);
            panel.Children.Add(brand);

            string[] steps = removing || page == Page.Remove
                ? new[] { T("Components", "Składniki"), T("Removal", "Usuwanie") }
                : new[] { T("System check", "Sprawdzenie"), T("Installation", "Instalacja"), T("Done", "Gotowe") };
            int current = page == Page.Check || page == Page.Remove ? 0 : page == Page.Ready ? 1
                : removing ? 1 : 2;
            for (int index = 0; index < steps.Length; index++)
            {
                bool on = index == current;
                bool done = index < current || (index == current && completed);
                Grid line = new Grid { Height = 38 };
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                line.ColumnDefinitions.Add(new ColumnDefinition());
                FrameworkElement mark = done ? ui.Check(22) : (FrameworkElement)ui.Step(index + 1, on);
                mark.Margin = new Thickness(10, 0, 12, 0);
                line.Children.Add(mark);
                TextBlock label = ui.Text(steps[index], 13.5, on ? p.Text : p.Muted,
                    on ? FontWeights.SemiBold : FontWeights.Normal);
                Grid.SetColumn(label, 1);
                line.Children.Add(label);
                Border item = new Border {
                    Background = on ? p.Card : Brushes.Transparent, CornerRadius = new CornerRadius(10),
                    Margin = new Thickness(0, 0, 0, 2), Child = line };
                AutomationProperties.SetName(item, steps[index]);
                AutomationProperties.SetItemStatus(item, on ? T("current step", "bieżący krok") : "");
                if (on)
                {
                    Border marker = new Border {
                        Width = 4, Height = 18, CornerRadius = new CornerRadius(2), Background = p.Accent,
                        HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(-3, 0, 0, 0) };
                    line.Children.Add(marker);
                }
                DockPanel.SetDock(item, Dock.Top);
                panel.Children.Add(item);
            }

            StackPanel footer = new StackPanel { Margin = new Thickness(12, 10, 12, 0) };
            StackPanel languages = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 10) };
            foreach (int choice in new[] { 1, 0 })
            {
                int selected = choice;
                Button button = ui.Button(choice == 1 ? "Polski" : "English",
                    language == choice ? ButtonKind.Primary : ButtonKind.Normal, true, false,
                    delegate { language = selected; Render(); }, "language-" + choice);
                button.Margin = new Thickness(0, 0, 6, 0);
                languages.Children.Add(button);
            }
            footer.Children.Add(languages);
            footer.Children.Add(ui.Text(T("Version ", "Wersja ") + payloadVersion.TrimStart('v'),
                11, p.Muted, FontWeights.Normal));
            DockPanel.SetDock(footer, Dock.Bottom);
            panel.Children.Add(footer);
            return new Border { Background = p.Side, Padding = new Thickness(10, 16, 10, 12), Child = panel };
        }

        // The bar below the page: the state on the left, the page's actions on the right.
        private Border ActionBar()
        {
            Palette p = ui.P;
            Tone tone; string state;
            List<Button> buttons = new List<Button>();
            bool installed = SharedComponentsInstalled(InstallDirectory);
            if (page == Page.Check)
            {
                tone = checking ? Tone.Accent : Tone.Ok;
                state = checking ? T("Checking this PC…", "Sprawdzanie komputera…")
                    : T("Check finished", "Sprawdzenie zakończone");
                if (installed && !checking)
                    buttons.Add(ui.Button(T("Uninstall…", "Odinstaluj…"), ButtonKind.Danger, false, false,
                        delegate { ShowPage(Page.Remove); }, "uninstall"));
                Button next = ui.Button(T("Continue", "Dalej"), ButtonKind.Primary, false, false,
                    delegate { ShowPage(Page.Ready); }, "continue");
                next.IsEnabled = !checking;
                next.IsDefault = true;
                buttons.Add(next);
            }
            else if (page == Page.Ready)
            {
                tone = Tone.Off;
                state = InstallDirectory;
                buttons.Add(ui.Button(T("Back", "Wstecz"), ButtonKind.Normal, false, false,
                    delegate { ShowPage(Page.Check); }, "back"));
                Button install = ui.Button(!installed ? T("Install", "Zainstaluj")
                    : SharedComponentsNeedUpdate(InstallDirectory) ? T("Update", "Aktualizuj")
                    : T("Reinstall", "Zainstaluj ponownie"), ButtonKind.Primary, false, false,
                    async delegate { await InstallAsync(); }, "install");
                install.IsDefault = true;
                buttons.Add(install);
            }
            else if (page == Page.Remove)
            {
                tone = installed ? Tone.Warn : Tone.Off;
                state = installed ? T("Nothing is removed until you confirm.", "Nic nie zostanie usunięte bez potwierdzenia.")
                    : T("No MoonWaker installation was found.", "Nie znaleziono instalacji MoonWaker.");
                buttons.Add(ui.Button(T("Back", "Wstecz"), ButtonKind.Normal, false, false,
                    delegate { ShowPage(Page.Check); }, "back"));
                Button remove = ui.Button(T("Uninstall", "Odinstaluj"), ButtonKind.Danger, false, false,
                    async delegate { await UninstallAsync(); }, "confirm-uninstall");
                remove.IsEnabled = installed;
                buttons.Add(remove);
            }
            else
            {
                tone = running ? Tone.Accent : completed ? Tone.Ok : Tone.Error;
                state = running ? T("Working — keep this window open.", "Trwa praca — nie zamykaj tego okna.")
                    : completed ? T("Finished", "Zakończono") : T("Needs attention", "Wymaga uwagi");
                if (running)
                {
                    Button busy = ui.Button(removing ? T("Removing…", "Usuwanie…") : T("Installing…", "Instalowanie…"),
                        ButtonKind.Primary, false, false, delegate { }, "busy");
                    busy.IsEnabled = false;
                    buttons.Add(busy);
                }
                else if (failed)
                {
                    buttons.Add(ui.Button(T("Back", "Wstecz"), ButtonKind.Normal, false, false,
                        delegate { ShowPage(removing ? Page.Remove : Page.Ready); }, "back"));
                    buttons.Add(ui.Button(T("Retry", "Spróbuj ponownie"), ButtonKind.Primary, false, false,
                        async delegate { if (removing) await UninstallAsync(); else await InstallAsync(); }, "retry"));
                }
                else
                {
                    string control = Path.Combine(InstallDirectory, "control", "MoonWakerHostControl.exe");
                    if (!removing && File.Exists(control))
                        buttons.Add(ui.Button(T("Open Host Control", "Otwórz Host Control"), ButtonKind.Normal,
                            false, false, delegate { OpenHostControl(control); }, "open-host-control"));
                    Button close = ui.Button(T("Close", "Zamknij"), ButtonKind.Primary, false, false, Close, "close");
                    close.IsDefault = true;
                    buttons.Add(close);
                }
            }

            DockPanel row = new DockPanel { VerticalAlignment = VerticalAlignment.Center };
            StackPanel right = new StackPanel { Orientation = Orientation.Horizontal };
            foreach (Button button in buttons)
            {
                button.Margin = new Thickness(8, 0, 0, 0);
                right.Children.Add(button);
            }
            DockPanel.SetDock(right, Dock.Right);
            row.Children.Add(right);
            Ellipse dot = ui.Dot(tone);
            dot.Margin = new Thickness(0, 0, 10, 0);
            row.Children.Add(dot);
            TextBlock text = ui.Text(state, 12, p.Muted, FontWeights.Normal);
            text.TextTrimming = TextTrimming.CharacterEllipsis;
            text.TextWrapping = TextWrapping.NoWrap;
            AutomationProperties.SetLiveSetting(text, AutomationLiveSetting.Polite);
            row.Children.Add(text);
            return new Border {
                Background = p.Side, BorderBrush = p.Line, BorderThickness = new Thickness(0, 1, 0, 0),
                Padding = new Thickness(20, 12, 20, 12), Child = row };
        }

        private void Heading(Panel content, string title, string subtitle)
        {
            content.Children.Add(ui.Text(title, 24, ui.P.Text, FontWeights.SemiBold));
            TextBlock line = ui.Text(subtitle, 12.5, ui.P.Muted, FontWeights.Normal);
            line.Margin = new Thickness(0, 2, 0, 16);
            content.Children.Add(line);
        }

        // A small status card: a dot and a title over muted lines.
        private Border StatusCard(string title, Tone tone, string state, string detail)
        {
            StackPanel stack = new StackPanel();
            StackPanel head = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 8) };
            Ellipse dot = ui.Dot(tone);
            dot.Margin = new Thickness(0, 0, 9, 0);
            head.Children.Add(dot);
            head.Children.Add(ui.Bold(title, 13.5));
            stack.Children.Add(head);
            stack.Children.Add(ui.Text(state, 12.5, ui.P.Text, FontWeights.Normal));
            if (!String.IsNullOrEmpty(detail))
            {
                TextBlock muted = ui.Muted(detail);
                muted.Margin = new Thickness(0, 3, 0, 0);
                stack.Children.Add(muted);
            }
            Border card = ui.Card(stack);
            card.Margin = new Thickness(0, 0, 12, 12);
            return card;
        }

        private void BuildCheck(Panel content)
        {
            Heading(content, T("Let's check this PC", "Sprawdźmy ten komputer"),
                T("MoonWaker checks the streaming host before making changes.",
                  "MoonWaker sprawdza host strumieniowania, zanim cokolwiek zmieni."));
            UniformGrid cards = new UniformGrid { Columns = 3, Margin = new Thickness(0, 0, -12, 0) };
            string waiting = T("Checking…", "Sprawdzanie…");

            Tone wolTone; string wolState, wolDetail;
            if (checking) { wolTone = Tone.Off; wolState = waiting; wolDetail = ""; }
            else if (!String.IsNullOrWhiteSpace(wakeOnLan.Error))
            { wolTone = Tone.Error; wolState = T("Check failed", "Sprawdzenie nie powiodło się"); wolDetail = wakeOnLan.Error; }
            else if (wakeOnLan.Armed)
            { wolTone = Tone.Ok; wolState = T("Enabled", "Włączony"); wolDetail = wakeOnLan.Adapters; }
            else if (wakeOnLan.Supported)
            {
                wolTone = Tone.Warn; wolState = T("Supported — enabled during installation", "Obsługiwany — zostanie włączony");
                wolDetail = wakeOnLan.Adapters;
            }
            else
            {
                wolTone = Tone.Warn; wolState = T("Not available to Windows", "Niedostępny dla Windows");
                wolDetail = T("Needs a firmware setting.", "Wymaga ustawienia w firmware.");
            }
            cards.Children.Add(StatusCard("Wake-on-LAN", wolTone, wolState, wolDetail));

            cards.Children.Add(checking ? StatusCard("Vibepollo", Tone.Off, waiting, "")
                : vibepolloInstalled
                ? StatusCard("Vibepollo", Tone.Ok, T("Installed", "Zainstalowany") +
                    (String.IsNullOrWhiteSpace(vibepolloVersion) ? "" : " " + vibepolloVersion),
                    T("Its settings stay as they are.", "Jego ustawienia pozostaną bez zmian."))
                : StatusCard("Vibepollo", Tone.Warn, T("Not installed", "Brak instalacji"),
                    String.IsNullOrEmpty(vibepolloError)
                        ? T("The official signed setup will be downloaded.", "Zostanie pobrany oficjalny podpisany instalator.")
                        : vibepolloError));

            bool installed = SharedComponentsInstalled(InstallDirectory);
            cards.Children.Add(!installed
                ? StatusCard("MoonWaker Host", Tone.Warn, T("Not installed", "Brak instalacji"),
                    T("All host components will be added.", "Zostaną dodane wszystkie komponenty hosta."))
                : SharedComponentsNeedUpdate(InstallDirectory)
                ? StatusCard("MoonWaker Host", Tone.Warn, T("Update available", "Dostępna aktualizacja"),
                    DisplayInstalledVersion(InstallDirectory) + " → " + payloadVersion)
                : StatusCard("MoonWaker Host", Tone.Ok, T("Up to date", "Aktualny") + " " + payloadVersion,
                    T("Profiles are managed in Host Control.", "Profilami zarządza Host Control.")));
            content.Children.Add(cards);

            if (!checking && (!String.IsNullOrWhiteSpace(wakeOnLan.Error) || !wakeOnLan.Supported))
            {
                Button recheck = ui.Button(T("Check again", "Sprawdź ponownie"), ButtonKind.Normal, true, false,
                    async delegate { await RefreshPreflightAsync(); }, "recheck");
                Border banner = ui.Banner(Tone.Warn, T("Wake-on-LAN needs attention.", "Wake-on-LAN wymaga uwagi."),
                    !String.IsNullOrWhiteSpace(wakeOnLan.Error)
                    ? T("Verify Wake-on-LAN in BIOS/UEFI and Device Manager, then check again.",
                        "Sprawdź Wake-on-LAN w BIOS/UEFI i Menedżerze urządzeń, a potem sprawdź ponownie.")
                    : T("Restart into BIOS/UEFI (usually Del, F2 or F10), enable “Wake on LAN”, “Power on by PCI-E” or “Resume by LAN”, use wired Ethernet and check again. You can also continue without it.",
                        "Uruchom BIOS/UEFI (zwykle Del, F2 lub F10), włącz „Wake on LAN”, „Power on by PCI-E” lub „Resume by LAN”, użyj przewodowego Ethernetu i sprawdź ponownie. Możesz też kontynuować bez tego."),
                    recheck);
                banner.Margin = new Thickness(0, 0, 0, 12);
                content.Children.Add(banner);
            }
            TextBlock caveat = ui.Muted(T(
                "Windows cannot read every vendor-specific BIOS/UEFI switch; the check uses the wired adapter capability reported by Windows.",
                "Windows nie odczyta każdego przełącznika BIOS/UEFI producenta; test korzysta z możliwości przewodowej karty zgłaszanej przez Windows."));
            caveat.Margin = new Thickness(2, 4, 0, 0);
            content.Children.Add(caveat);
        }

        private void BuildReady(Panel content)
        {
            bool installed = SharedComponentsInstalled(InstallDirectory);
            bool update = SharedComponentsNeedUpdate(InstallDirectory);
            Heading(content, T("Ready to install", "Gotowe do instalacji"),
                T("Defaults are chosen for you; the installer only adds what is missing.",
                  "Ustawienia wybrano automatycznie; instalator dodaje tylko to, czego brakuje."));
            StackPanel list = new StackPanel();
            Border pill = !installed ? ui.Pill(T("New installation", "Nowa instalacja"), Tone.Accent)
                : update ? ui.Pill(DisplayInstalledVersion(InstallDirectory) + " → " + payloadVersion, Tone.Warn)
                : ui.Pill(T("Up to date", "Aktualny"), Tone.Ok);
            list.Children.Add(ui.Heading(T("What happens", "Co się stanie"), pill));
            int step = 0;
            list.Children.Add(ReadyItem(++step, false, "MoonWaker Host",
                T("Gateway, Login Broker, Host Control and Legendary in ", "Gateway, Login Broker, Host Control i Legendary w ") +
                InstallDirectory + T(". Profiles, certificates and pairing are kept.", ". Profile, certyfikaty i parowanie zostają zachowane.")));
            list.Children.Add(ReadyItem(++step, vibepolloInstalled, "Vibepollo", vibepolloInstalled
                ? T("Already installed; left unchanged.", "Już zainstalowany; bez zmian.")
                : T("Its signed setup opens: choose the folder and keep Vibepollo Display Driver selected.",
                    "Otworzy się jego podpisany instalator: wybierz katalog i pozostaw zaznaczony Vibepollo Display Driver.")));
            list.Children.Add(ReadyItem(++step, false, T("Drivers and runtimes", "Sterowniki i środowiska"),
                T("ViGEmBus 1.22.0 for virtual gamepads, Python 3.12 and OpenSSL — only if missing.",
                  "ViGEmBus 1.22.0 dla wirtualnych gamepadów, Python 3.12 i OpenSSL — tylko jeśli ich brakuje.")));
            list.Children.Add(ReadyItem(++step, wakeOnLan.Armed, "Wake-on-LAN",
                wakeOnLan.Armed ? T("Already enabled.", "Już włączony.")
                : wakeOnLan.Supported ? T("Windows enables it for the wired adapter.", "Windows włączy go dla karty przewodowej.")
                : T("Skipped — Windows does not offer it yet.", "Pominięty — Windows jeszcze go nie udostępnia."),
                !wakeOnLan.Supported));
            content.Children.Add(ui.Card(list));

            StackPanel next = new StackPanel();
            next.Children.Add(ui.Heading(T("Profiles in Host Control", "Profile w Host Control"), null));
            next.Children.Add(ui.Text(T(
                "Windows profiles, game libraries, devices and Remote Sign-in are set up in MoonWaker Host Control after installation — from the Start menu or the button at the end.",
                "Profile Windows, biblioteki gier, urządzenia i zdalne logowanie skonfigurujesz po instalacji w MoonWaker Host Control — z menu Start albo przyciskiem na końcu."),
                12.5, ui.P.Muted, FontWeights.Normal));
            Border card = ui.Card(next);
            card.Margin = new Thickness(0, 12, 0, 0);
            content.Children.Add(card);
        }

        // skipped: nothing happens to it (a muted dot instead of a step number).
        private Border ReadyItem(int number, bool done, string title, string detail, bool skipped = false)
        {
            UIElement mark = done ? ui.Check(22) : skipped ? (UIElement)ui.Dot(Tone.Off) : ui.Step(number, true);
            return ui.Item(number == 1, mark,
                ui.Bold(title, 13), detail);
        }

        private void BuildRemove(Panel content)
        {
            Heading(content, T("Uninstall MoonWaker", "Odinstaluj MoonWaker"),
                T("Choose what leaves this computer. MoonWaker and Legendary always go together.",
                  "Wybierz, co usunąć z tego komputera. MoonWaker i Legendary są usuwane razem."));
            StackPanel list = new StackPanel();
            list.Children.Add(ui.Heading(T("Components", "Składniki"), ui.Muted(T("found on this computer", "wykryte na tym komputerze"))));
            bool first = true;
            foreach (Part part in parts)
            {
                if (!part.Locked && !part.Present) continue;
                Part current = part;
                string name = T(part.English, part.Polish);
                Button toggle = ui.Switch(part.Selected, name, part.Locked, false, delegate
                {
                    if (current.Locked) return;
                    current.Selected = !current.Selected;
                    Render();
                }, "remove-" + part.Key);
                if (part.Locked) toggle.ToolTip = T("Always removed with MoonWaker.", "Zawsze usuwany razem z MoonWaker.");
                list.Children.Add(ui.Item(first, null, ui.Bold(name, 13), T(part.EnglishNote, part.PolishNote), toggle));
                first = false;
            }
            content.Children.Add(ui.Card(list));
            Border warning = ui.Banner(Tone.Error, T("This cannot be undone.", "Tej operacji nie można cofnąć."),
                T("Paired TVs must pair again after a new installation.",
                  "Po nowej instalacji telewizory trzeba sparować ponownie."), null);
            warning.Margin = new Thickness(0, 12, 0, 0);
            content.Children.Add(warning);
        }

        private void BuildProgress(Panel content)
        {
            Heading(content, progressTitle, progressDetail);
            if (completed)
            {
                if (removing) BuildRemoved(content); else BuildInstalled(content);
                Button toggle = ui.Button((removing ? T("Removal log", "Dziennik usuwania")
                    : T("Installation log", "Dziennik instalacji")) + (showLog ? "  ▾" : "  ▸"),
                    ButtonKind.Normal, true, false, delegate { showLog = !showLog; Render(); }, "toggle-log");
                toggle.Margin = new Thickness(0, 12, 0, 0);
                content.Children.Add(toggle);
                if (!showLog) return;
            }
            StackPanel stack = new StackPanel();
            if (!completed) stack.Children.Add(ui.Bar(progress / 100.0));
            TextBlock caption = ui.Text(removing ? T("Removal log", "Dziennik usuwania")
                : T("Installation log", "Dziennik instalacji"), 12, ui.P.Muted, FontWeights.SemiBold);
            caption.Margin = new Thickness(0, completed ? 0 : 14, 0, 6);
            stack.Children.Add(caption);
            TextBox output = new TextBox {
                Text = log.ToString(), IsReadOnly = true, Height = 300, TextWrapping = TextWrapping.Wrap,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                FontFamily = new FontFamily("Cascadia Mono, Consolas"), BorderThickness = new Thickness(1) };
            ui.Input(output);
            output.FontSize = 12;
            output.Resources.Add(typeof(ScrollBar), ui.ScrollBarStyle());
            AutomationProperties.SetName(output, caption.Text);
            output.Loaded += delegate { output.ScrollToEnd(); };
            stack.Children.Add(output);
            Border card = ui.Card(stack);
            if (completed) card.Margin = new Thickness(0, 8, 0, 0);
            content.Children.Add(card);
        }

        private void BuildInstalled(Panel content)
        {
            FrameworkElement mark = ui.Check(44);
            mark.Margin = new Thickness(0, 0, 16, 0);
            StackPanel text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(ui.Bold(T("Gateway and Login Broker are running.", "Gateway i Login Broker działają."), 14));
            TextBlock note = ui.Muted(T("Open Host Control to add profiles, pair a TV and connect game libraries.",
                "Otwórz Host Control, aby dodać profile, sparować telewizor i połączyć biblioteki gier."));
            note.Margin = new Thickness(0, 3, 0, 0);
            text.Children.Add(note);
            DockPanel hero = new DockPanel();
            hero.Children.Add(mark);
            hero.Children.Add(text);
            Border heroCard = ui.Card(hero);
            heroCard.Margin = new Thickness(0, 0, 0, 12);
            content.Children.Add(heroCard);

            StackPanel list = new StackPanel();
            list.Children.Add(ui.Heading(T("What next", "Co dalej"), null));
            list.Children.Add(ui.Item(true, ui.Step(1, true), ui.Bold(T("Open Host Control", "Otwórz Host Control"), 13),
                T("With the button below or from Start → MoonWaker.", "Z przycisku poniżej lub z menu Start → MoonWaker.")));
            list.Children.Add(ui.Item(false, ui.Step(2, true), ui.Bold(T("Add a profile and pair the TV", "Dodaj profil i sparuj TV"), 13),
                T("On the TV choose Add computer and type the code from Host Control.",
                  "Na telewizorze wybierz Dodaj komputer i wpisz kod z Host Control.")));
            list.Children.Add(ui.Item(false, ui.Step(3, true), ui.Bold(T("Uninstalling", "Odinstalowanie"), 13),
                T("Windows Settings → Apps → MoonWaker Host, or this installer.",
                  "W Ustawieniach Windows → Aplikacje → MoonWaker Host albo z tego instalatora.")));
            content.Children.Add(ui.Card(list));
        }

        // What the removal achieved, read back from this computer rather than assumed.
        private void BuildRemoved(Panel content)
        {
            StackPanel list = new StackPanel();
            list.Children.Add(ui.Heading(T("Result", "Wynik"), null));
            List<string> kept = new List<string>();
            bool first = true;
            foreach (Part part in parts)
            {
                string name = T(part.English, part.Polish);
                if (!part.Selected) { if (part.Present) kept.Add(name); continue; }
                bool gone = !part.Present;
                if (!gone && part.Patterns == null) continue;
                string detail = gone
                    ? (part.Key == "Legendary" ? T("Program and Epic sign-ins removed; games stay on disk.",
                        "Program i logowania Epic usunięte; gry zostały na dysku.") : T("Removed.", "Usunięty."))
                    : T("Still installed — remove it in Windows Settings → Apps.",
                        "Nadal zainstalowany — usuń go w Ustawieniach Windows → Aplikacje.");
                list.Children.Add(ui.Item(first, gone ? ui.Check(22) : (UIElement)ui.Dot(Tone.Warn), ui.Bold(name, 13), detail));
                first = false;
            }
            if (kept.Count > 0)
                list.Children.Add(ui.Item(first, ui.Dot(Tone.Off), ui.Bold(String.Join(", ", kept.ToArray()), 13),
                    T("Kept — not chosen for removal.", "Pozostawione — nie wybrano ich do usunięcia.")));
            content.Children.Add(ui.Card(list));
        }

        private void ShowPage(Page target)
        {
            if (target == Page.Remove) DetectParts();
            page = target;
            Render();
        }

        // ---- Flows ------------------------------------------------------------------------

        private async Task RefreshPreflightAsync()
        {
            checking = true;
            Render();
            Task<WakeOnLanProbe> wakeTask = Task.Run<WakeOnLanProbe>(() => ProbeWakeOnLan());
            Task<string> vibepolloTask = Task.Run<string>(() => DetectVibepolloVersion());
            try { wakeOnLan = await wakeTask; }
            catch (Exception error) { wakeOnLan = new WakeOnLanProbe { Error = error.Message }; }
            try
            {
                vibepolloVersion = await vibepolloTask;
                vibepolloInstalled = vibepolloVersion != null;
                vibepolloError = "";
            }
            catch (Exception error)
            {
                vibepolloInstalled = false;
                vibepolloVersion = null;
                vibepolloError = error.Message;
            }
            checking = false;
            Render();
        }

        private void StartRun(bool removal, string title, string detail)
        {
            removing = removal;
            running = true;
            completed = false;
            failed = false;
            progress = 4;
            progressTitle = title;
            progressDetail = detail;
            log.Clear();
            showLog = false;
            page = Page.Progress;
            Render();
        }

        private void FinishRun(Exception error, string title, string detail)
        {
            running = false;
            if (error == null)
            {
                completed = true;
                progress = 100;
                progressTitle = title;
                progressDetail = detail;
            }
            else
            {
                failed = true;
                log.AppendLine().Append(T("ERROR: ", "BŁĄD: ")).AppendLine(error.Message);
                progressTitle = removing ? T("Uninstallation needs attention", "Deinstalacja wymaga uwagi")
                    : T("Installation needs attention", "Instalacja wymaga uwagi");
                progressDetail = T("Review the log below, correct the problem and retry.",
                    "Przeczytaj dziennik poniżej, popraw problem i spróbuj ponownie.");
            }
            Render();
        }

        private async Task InstallAsync()
        {
            if (!PrepareHostControlUpdate(true)) return;
            StartRun(false, T("Installing MoonWaker Host", "Instalowanie MoonWaker Host"),
                T("Keep this window open while the machine components and Gateway are installed.",
                  "Pozostaw to okno otwarte podczas instalowania komponentów komputera i Gateway."));
            log.AppendLine(T("Preparing the installation…", "Przygotowywanie instalacji…"));
            bool ensureVibepollo = !vibepolloInstalled;
            bool enableWakeOnLan = wakeOnLan.Supported && !wakeOnLan.Armed;
            Exception failure = null;
            try
            {
                log.Append(await Task.Run<string>(() => RunInstaller(ensureVibepollo, enableWakeOnLan)));
                RegisterUninstallEntry();
            }
            catch (Exception error) { failure = error; }
            RestartHostControlIfNeeded();
            FinishRun(failure, T("MoonWaker Host is installed", "MoonWaker Host jest zainstalowany"),
                T("Version ", "Wersja ") + payloadVersion.TrimStart('v') + T(" in ", " w ") + InstallDirectory);
        }

        private async Task UninstallAsync()
        {
            if (!SharedComponentsInstalled(InstallDirectory)) return;
            List<string> components = new List<string>();
            List<string> names = new List<string>();
            foreach (Part part in parts)
            {
                if (!part.Present || !part.Selected) continue;
                names.Add(T(part.English, part.Polish));
                if (part.Patterns != null) components.Add(part.Key);
            }
            if (!HostDialog.Confirm(this, ui, T("Uninstall MoonWaker?", "Odinstalować MoonWaker?"),
                    T("To be removed: ", "Zostaną usunięte: ") + String.Join(", ", names.ToArray()) + ". " +
                    T("Host Control and Host Configurator will close.", "Host Control i Host Configurator zostaną zamknięte."),
                    T("Uninstall", "Odinstaluj"), true, false, T("Cancel", "Anuluj"))) return;
            if (!PrepareHostControlUpdate(false)) return;
            StartRun(true, T("Uninstalling MoonWaker Host", "Odinstalowywanie MoonWaker Host"),
                components.Contains("Vibepollo")
                ? T("Stopping services and removing MoonWaker. Follow Vibepollo's uninstaller if it opens.",
                    "Zatrzymywanie usług i usuwanie MoonWaker. Jeśli otworzy się deinstalator Vibepollo, postępuj według niego.")
                : T("Stopping services and removing MoonWaker data…", "Zatrzymywanie usług i usuwanie danych MoonWaker…"));
            log.AppendLine(progressDetail);
            progress = 10;
            Exception failure = null;
            try
            {
                log.Append(await Task.Run<string>(() => RunUninstaller(components)));
                Registry.LocalMachine.DeleteSubKeyTree(UninstallKey, false);
            }
            catch (Exception error) { failure = error; }
            DetectParts();
            FinishRun(failure, T("MoonWaker was removed", "MoonWaker został usunięty"),
                T("Services, profiles, Legendary, credentials and installation files were removed.",
                  "Usunięto usługi, profile, Legendary, poświadczenia i pliki instalacji."));
        }

        private void ReportProgress(int value, string detail)
        {
            Dispatcher.BeginInvoke(new Action(delegate {
                progress = Math.Max(progress, value);
                progressDetail = detail;
                log.AppendLine(detail);
                Render();
            }));
        }

        private void OpenHostControl(string executable)
        {
            // Host Control runs with the signed-in user's standard token, not this elevated one.
            try { UnelevatedShell.Start(executable, "", Path.GetDirectoryName(executable)); }
            catch (Exception error)
            {
                log.AppendLine(T("Could not open Host Control: ", "Nie udało się otworzyć Host Control: ") + error.Message);
                Render();
            }
        }

        // Apps & features lists MoonWaker Host; its Uninstall starts the copy kept in the
        // installation with --uninstall.
        private static void RegisterUninstallEntry()
        {
            string copy = Path.Combine(InstallDirectory, InstalledCopyName);
            string self = Path.GetFullPath(Assembly.GetExecutingAssembly().Location);
            if (!String.Equals(self, copy, StringComparison.OrdinalIgnoreCase)) File.Copy(self, copy, true);
            using (RegistryKey key = Registry.LocalMachine.CreateSubKey(UninstallKey))
            {
                key.SetValue("DisplayName", "MoonWaker Host");
                key.SetValue("DisplayIcon", Path.Combine(InstallDirectory, "control", "MoonWakerHostControl.exe"));
                key.SetValue("Publisher", "MoonWaker");
                key.SetValue("InstallLocation", InstallDirectory);
                key.SetValue("UninstallString", Quote(copy) + " --uninstall");
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
        }

        private void DetectParts()
        {
            List<string> names = InstalledProductNames();
            foreach (Part part in parts)
            {
                if (part.Patterns == null) { part.Present = SharedComponentsInstalled(InstallDirectory); continue; }
                part.Present = false;
                foreach (string pattern in part.Patterns)
                {
                    Regex like = new Regex("^" + Regex.Escape(pattern).Replace("\\*", ".*") + "$",
                        RegexOptions.IgnoreCase);
                    foreach (string name in names) if (like.IsMatch(name)) part.Present = true;
                }
            }
        }

        // Visible Apps & features entries that can be uninstalled, as the uninstall script sees them.
        private static List<string> InstalledProductNames()
        {
            List<string> names = new List<string>();
            foreach (RegistryView view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
            {
                try
                {
                    using (RegistryKey root = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                    using (RegistryKey uninstall = root.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"))
                    {
                        if (uninstall == null) continue;
                        foreach (string subKey in uninstall.GetSubKeyNames())
                        {
                            using (RegistryKey entry = uninstall.OpenSubKey(subKey))
                            {
                                if (entry == null || Convert.ToString(entry.GetValue("UninstallString")) == "" ||
                                    Convert.ToString(entry.GetValue("SystemComponent")) == "1") continue;
                                string display = Convert.ToString(entry.GetValue("DisplayName"));
                                if (display != "") names.Add(display);
                            }
                        }
                    }
                }
                catch { }
            }
            return names;
        }

        private string RunInstaller(bool ensureVibepollo, bool enableWakeOnLan)
        {
            string temporary = CreateProtectedStagingDirectory();
            Exception installError = null;
            try
            {
                ReportProgress(10, T("Unpacking verified host components…",
                    "Rozpakowywanie zweryfikowanych komponentów hosta…"));
                ExtractPayload(temporary);
                string package = Path.Combine(temporary, "host-services");
                string hostScript = Path.Combine(package, "install", "Install-WakePlayHost.ps1");
                string machineWrapper = Path.Combine(package, "install", "Invoke-MoonWakerMachineInstall.ps1");
                string prerequisiteScript = Path.Combine(package, "install", "Prepare-MoonWakerHost.ps1");
                if (!File.Exists(hostScript) || !File.Exists(machineWrapper) ||
                    !File.Exists(prerequisiteScript)) throw new InvalidOperationException(T(
                    "The embedded host package is incomplete.", "Osadzony pakiet hosta jest niekompletny."));
                ReportProgress(28, ensureVibepollo
                    ? T("Installing components; Vibepollo's setup opens when it is needed…",
                        "Instalowanie składników; instalator Vibepollo otworzy się, gdy będzie potrzebny…")
                    : T("Checking required components and drivers…",
                        "Sprawdzanie wymaganych składników i sterowników…"));
                string output = RunMachineInstall(hostScript, machineWrapper, prerequisiteScript, temporary,
                    enableWakeOnLan, ensureVibepollo, true, true, true);
                ReportProgress(92, T("Machine services are installed and starting.",
                    "Usługi komputera są zainstalowane i uruchamiane."));
                return output;
            }
            catch (Exception error) { installError = error; throw; }
            finally
            {
                try { DeleteProtectedStagingDirectory(temporary); }
                catch { if (installError == null) throw; }
            }
        }

        private string RunUninstaller(List<string> components)
        {
            string temporary = CreateProtectedStagingDirectory();
            Exception uninstallError = null;
            try
            {
                ExtractPayload(temporary);
                string script = Path.Combine(temporary, "host-services", "install",
                    "Uninstall-MoonWakerHostServices.ps1");
                if (!File.Exists(script)) throw new InvalidOperationException(T(
                    "The embedded uninstaller is missing.", "Brakuje osadzonego deinstalatora."));
                ReportProgress(25, T("Removing MoonWaker services and files…", "Usuwanie usług i plików MoonWaker…"));
                string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " +
                    Quote(script) + " -PurgeCredentials -RemoveFiles";
                if (components.Count > 0) arguments += " -RemoveComponents " + String.Join(",", components.ToArray());
                ProcessStartInfo info = new ProcessStartInfo(WindowsPowerShellPath(), arguments);
                info.UseShellExecute = false; info.CreateNoWindow = true;
                info.RedirectStandardOutput = true; info.RedirectStandardError = true;
                info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
                info.EnvironmentVariables["PSModulePath"] = TrustedPowerShellModulePath();
                using (Process process = Process.Start(info))
                {
                    Task<string> stdout = process.StandardOutput.ReadToEndAsync();
                    Task<string> stderr = process.StandardError.ReadToEndAsync();
                    process.WaitForExit();
                    Task.WaitAll(new Task[] { stdout, stderr });
                    if (process.ExitCode != 0) throw new InvalidOperationException(
                        String.IsNullOrWhiteSpace(stderr.Result) ? stdout.Result : stderr.Result);
                    return stdout.Result;
                }
            }
            catch (Exception error) { uninstallError = error; throw; }
            finally
            {
                try { DeleteProtectedStagingDirectory(temporary); }
                catch { if (uninstallError == null) throw; }
            }
        }

        private static string RunMachineInstall(string hostScript, string wrapper,
            string prerequisiteScript, string temporaryDirectory,
            bool enableWakeOnLan, bool ensureVibepollo, bool ensureViGEmBus,
            bool ensurePython, bool ensureOpenSsl)
        {
            if (!File.Exists(wrapper)) throw new InvalidOperationException(
                "The machine installation module is missing.");
            string resultPath = Path.Combine(temporaryDirectory, "machine-install-result.txt");
            string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(wrapper) +
                " -HostInstallScript " + Quote(hostScript) +
                " -PrerequisiteScript " + Quote(prerequisiteScript) +
                " -ProtectedStagingDirectory " + Quote(temporaryDirectory) +
                " -ResultPath " + Quote(resultPath);
            if (enableWakeOnLan) arguments += " -EnableWakeOnLan";
            if (ensureVibepollo) arguments += " -EnsureVibepollo";
            if (ensureViGEmBus) arguments += " -EnsureViGEmBus";
            if (ensurePython) arguments += " -EnsurePython";
            if (ensureOpenSsl) arguments += " -EnsureOpenSsl";
            ProcessStartInfo info = new ProcessStartInfo(WindowsPowerShellPath(), arguments);
            info.UseShellExecute = false; info.CreateNoWindow = true;
            info.RedirectStandardOutput = true; info.RedirectStandardError = true;
            info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
            info.EnvironmentVariables["PSModulePath"] = TrustedPowerShellModulePath();
            using (Process process = Process.Start(info))
            {
                Task<string> stdout = process.StandardOutput.ReadToEndAsync();
                Task<string> stderr = process.StandardError.ReadToEndAsync();
                process.WaitForExit();
                Task.WaitAll(new Task[] { stdout, stderr });
                string details = File.Exists(resultPath) ? File.ReadAllText(resultPath).Trim() : "";
                if (String.IsNullOrWhiteSpace(details) && !String.IsNullOrWhiteSpace(stderr.Result))
                    details = stderr.Result.Trim();
                if (String.IsNullOrWhiteSpace(details) && !String.IsNullOrWhiteSpace(stdout.Result))
                    details = stdout.Result.Trim();
                if (process.ExitCode != 0) throw new InvalidOperationException(
                    "Machine preparation failed (" + process.ExitCode + ")." +
                    (String.IsNullOrWhiteSpace(details) ? "" : "\r\n\r\n" + details));
                return String.IsNullOrWhiteSpace(details) ? "" : details + "\r\n";
            }
        }

        // Host Control and the Configurator hold installed files: they close first. An update
        // asks and starts the tray again afterwards; an uninstallation said so in its confirmation.
        private bool PrepareHostControlUpdate(bool reopen)
        {
            restartHostControl = false;
            List<Process> running = new List<Process>();
            bool hostControlRunning = false;
            foreach (string name in new[] { "MoonWakerHostControl", "MoonWakerHostConfigurator" })
            {
                foreach (Process process in Process.GetProcessesByName(name))
                {
                    running.Add(process);
                    if (name == "MoonWakerHostControl") hostControlRunning = true;
                }
            }
            if (running.Count == 0) return true;
            bool agreed = !reopen || HostDialog.Confirm(this, ui, "MoonWaker Host Control", T(
                "Host Control must close for the update. It reopens after installation.",
                "Host Control musi zostać zamknięty na czas aktualizacji. Uruchomi się ponownie po instalacji."),
                T("Close and continue", "Zamknij i kontynuuj"), false, false, T("Cancel", "Anuluj"));
            if (!agreed)
            {
                foreach (Process process in running) process.Dispose();
                return false;
            }
            try
            {
                restartHostControl = reopen && hostControlRunning;
                foreach (Process process in running)
                {
                    using (process)
                    {
                        if (process.CloseMainWindow() && process.WaitForExit(3000)) continue;
                        process.Kill();
                        if (!process.WaitForExit(5000)) throw new InvalidOperationException(T(
                            "MoonWaker Host Control did not close in time.",
                            "MoonWaker Host Control nie zamknął się na czas."));
                    }
                }
                return true;
            }
            catch (Exception error)
            {
                RestartHostControlIfNeeded();
                MessageBox.Show(this, error.Message, "MoonWaker", MessageBoxButton.OK, MessageBoxImage.Error);
                return false;
            }
        }

        private void RestartHostControlIfNeeded()
        {
            if (!restartHostControl) return;
            restartHostControl = false;
            string executable = Path.Combine(InstallDirectory, "control", "MoonWakerHostControl.exe");
            if (!File.Exists(executable)) return;
            // The tray hosts profile processes only with the user's standard token.
            try { UnelevatedShell.Start(executable, "--tray", Path.GetDirectoryName(executable)); }
            catch (Exception error)
            {
                log.AppendLine(T("Could not restart Host Control: ",
                    "Nie udało się ponownie uruchomić Host Control: ") + error.Message);
            }
        }

        private static WakeOnLanProbe ProbeWakeOnLan()
        {
            string powerCfg = WindowsSystemExecutable("powercfg.exe").Replace("'", "''");
            string script =
                "$ErrorActionPreference='SilentlyContinue';" +
                "$moduleRoot=Join-Path $PSHOME 'Modules';$env:PSModulePath=$moduleRoot;" +
                "Import-Module -Name (Join-Path $moduleRoot 'NetAdapter\\NetAdapter.psd1') -Force -ErrorAction Stop;" +
                "$powercfg='" + powerCfg + "';" +
                "$programmable=@(& $powercfg /devicequery wake_programmable|%{$_.Trim()}|?{$_});" +
                "$armed=@(& $powercfg /devicequery wake_armed|%{$_.Trim()}|?{$_});" +
                "$adapters=@(NetAdapter\\Get-NetAdapter -Physical|?{$_.HardwareInterface -and [int]$_.NdisPhysicalMedium -eq 14 -and $_.Status -ne 'Disabled'});" +
                "$found=$false;foreach($a in $adapters){$d=[string]$a.InterfaceDescription;" +
                "if($programmable -contains $d -or $programmable -contains [string]$a.Name){$found=$true;" +
                "$state=if($armed -contains $d -or $armed -contains [string]$a.Name){'armed'}else{'supported'};" +
                "$name=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$a.Name));" +
                "Write-Output ('MW_WOL|'+$state+'|'+$name)}};" +
                "if(-not $found){Write-Output 'MW_WOL|unavailable|'}";
            string encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            string output = RunHiddenProcess(WindowsPowerShellPath(),
                "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded, 15000);
            WakeOnLanProbe result = new WakeOnLanProbe();
            List<string> names = new List<string>();
            foreach (string raw in output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
            {
                string line = raw.Trim();
                if (!line.StartsWith("MW_WOL|", StringComparison.Ordinal)) continue;
                string[] fields = line.Split('|');
                if (fields.Length < 3) continue;
                if (fields[1] == "armed") { result.Supported = true; result.Armed = true; }
                else if (fields[1] == "supported") result.Supported = true;
                if (!String.IsNullOrWhiteSpace(fields[2]))
                {
                    try { names.Add(Encoding.UTF8.GetString(Convert.FromBase64String(fields[2]))); }
                    catch { }
                }
            }
            result.Adapters = String.Join(", ", names.ToArray());
            return result;
        }

        private static string DetectVibepolloVersion()
        {
            RegistryView[] views = Environment.Is64BitOperatingSystem
                ? new[] { RegistryView.Registry64, RegistryView.Registry32 }
                : new[] { RegistryView.Registry32 };
            foreach (RegistryView view in views)
            {
                try
                {
                    using (RegistryKey root = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                    using (RegistryKey uninstall = root.OpenSubKey(
                        @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"))
                    {
                        if (uninstall == null) continue;
                        foreach (string name in uninstall.GetSubKeyNames())
                        {
                            using (RegistryKey entry = uninstall.OpenSubKey(name))
                            {
                                if (entry == null) continue;
                                string display = Convert.ToString(entry.GetValue("DisplayName"));
                                if (display.IndexOf("Vibepollo", StringComparison.OrdinalIgnoreCase) < 0) continue;
                                string location = Convert.ToString(entry.GetValue("InstallLocation"));
                                if (String.IsNullOrWhiteSpace(location)) continue;
                                string executable;
                                try { executable = Path.Combine(Path.GetFullPath(location), "sunshine.exe"); }
                                catch { continue; }
                                if (!File.Exists(executable)) continue;
                                return Convert.ToString(entry.GetValue("DisplayVersion")) ?? "";
                            }
                        }
                    }
                }
                catch { }
            }
            foreach (Environment.SpecialFolder folder in new[] {
                Environment.SpecialFolder.ProgramFiles, Environment.SpecialFolder.ProgramFilesX86 })
            {
                string root = Environment.GetFolderPath(folder);
                if (String.IsNullOrWhiteSpace(root)) continue;
                string executable = Path.Combine(root, "Vibepollo", "sunshine.exe");
                if (File.Exists(executable)) return "";
            }
            return null;
        }

        private static string RunHiddenProcess(string fileName, string arguments, int timeoutMilliseconds)
        {
            ProcessStartInfo info = new ProcessStartInfo(fileName, arguments);
            info.UseShellExecute = false; info.CreateNoWindow = true;
            if (String.Equals(Path.GetFullPath(fileName), WindowsPowerShellPath(),
                    StringComparison.OrdinalIgnoreCase))
                info.EnvironmentVariables["PSModulePath"] = TrustedPowerShellModulePath();
            info.RedirectStandardOutput = true; info.RedirectStandardError = true;
            using (Process process = Process.Start(info))
            {
                Task<string> stdout = process.StandardOutput.ReadToEndAsync();
                Task<string> stderr = process.StandardError.ReadToEndAsync();
                if (!process.WaitForExit(timeoutMilliseconds))
                {
                    try { process.Kill(); } catch { }
                    throw new TimeoutException("The Windows capability check timed out.");
                }
                Task.WaitAll(new Task[] { stdout, stderr }, 2000);
                if (process.ExitCode != 0) throw new InvalidOperationException(
                    stderr.IsCompleted ? stderr.Result : "The Windows capability check failed.");
                return stdout.IsCompleted ? stdout.Result : "";
            }
        }

        private static string WindowsPowerShellPath()
        {
            return WindowsSystemExecutable("WindowsPowerShell", "v1.0", "powershell.exe");
        }

        private static string TrustedPowerShellModulePath()
        {
            return Path.Combine(Path.GetDirectoryName(WindowsPowerShellPath()), "Modules");
        }

        private static string WindowsSystemExecutable(params string[] relativeParts)
        {
            string windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            string system = Environment.Is64BitOperatingSystem && !Environment.Is64BitProcess
                ? Path.Combine(windows, "Sysnative") : Path.Combine(windows, "System32");
            string executable = system;
            foreach (string part in relativeParts) executable = Path.Combine(executable, part);
            executable = Path.GetFullPath(executable);
            if (!File.Exists(executable))
                throw new FileNotFoundException("Trusted Windows executable was not found.", executable);
            return executable;
        }

        private static DirectorySecurity ProtectedStagingSecurity()
        {
            DirectorySecurity security = new DirectorySecurity();
            security.SetAccessRuleProtection(true, false);
            security.SetOwner(new SecurityIdentifier(
                WellKnownSidType.BuiltinAdministratorsSid, null));
            InheritanceFlags inheritance = InheritanceFlags.ContainerInherit |
                InheritanceFlags.ObjectInherit;
            foreach (WellKnownSidType sidType in new[] {
                WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
            {
                security.AddAccessRule(new FileSystemAccessRule(
                    new SecurityIdentifier(sidType, null), FileSystemRights.FullControl,
                    inheritance, PropagationFlags.None, AccessControlType.Allow));
            }
            return security;
        }

        private static string CreateProtectedStagingDirectory()
        {
            const int ErrorFileExists = 80;
            const int ErrorAlreadyExists = 183;
            string parent = Path.GetFullPath(Environment.GetFolderPath(
                Environment.SpecialFolder.CommonApplicationData)).TrimEnd(
                    Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            byte[] descriptor = ProtectedStagingSecurity().GetSecurityDescriptorBinaryForm();
            GCHandle pinned = GCHandle.Alloc(descriptor, GCHandleType.Pinned);
            try
            {
                NativeMethods.SecurityAttributes attributes = new NativeMethods.SecurityAttributes {
                    Length = Marshal.SizeOf(typeof(NativeMethods.SecurityAttributes)),
                    SecurityDescriptor = pinned.AddrOfPinnedObject(), InheritHandle = false
                };
                for (int attempt = 0; attempt < 8; attempt++)
                {
                    string path = Path.Combine(parent,
                        "MoonWakerInstaller-" + Guid.NewGuid().ToString("N"));
                    if (NativeMethods.CreateDirectory(path, ref attributes))
                        return ValidateProtectedStagingDirectory(path);
                    int error = Marshal.GetLastWin32Error();
                    if (error != ErrorFileExists && error != ErrorAlreadyExists)
                        throw new Win32Exception(error,
                            "Could not create the protected installer staging directory.");
                }
            }
            finally { pinned.Free(); Array.Clear(descriptor, 0, descriptor.Length); }
            throw new IOException("Could not allocate a unique installer staging directory.");
        }

        private static string ValidateProtectedStagingDirectory(string path)
        {
            string parent = Path.GetFullPath(Environment.GetFolderPath(
                Environment.SpecialFolder.CommonApplicationData)).TrimEnd(
                    Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            string full = Path.GetFullPath(path).TrimEnd(
                Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            string leaf = Path.GetFileName(full);
            Guid id;
            if (!String.Equals(Path.GetDirectoryName(full), parent,
                    StringComparison.OrdinalIgnoreCase) ||
                !leaf.StartsWith("MoonWakerInstaller-", StringComparison.Ordinal) ||
                !Guid.TryParseExact(leaf.Substring("MoonWakerInstaller-".Length), "N", out id))
                throw new InvalidDataException("Invalid installer staging path.");
            DirectoryInfo directory = new DirectoryInfo(full);
            if (!directory.Exists || (directory.Attributes & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Installer staging must be an ordinary directory.");
            DirectorySecurity security = directory.GetAccessControl(
                AccessControlSections.Access | AccessControlSections.Owner);
            if (!security.AreAccessRulesProtected)
                throw new UnauthorizedAccessException("Installer staging ACL inheritance is enabled.");
            SecurityIdentifier owner = (SecurityIdentifier)security.GetOwner(
                typeof(SecurityIdentifier));
            if (owner.Value != new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null).Value &&
                owner.Value != new SecurityIdentifier(
                    WellKnownSidType.BuiltinAdministratorsSid, null).Value)
                throw new UnauthorizedAccessException("Installer staging has an untrusted owner.");
            HashSet<string> allowed = new HashSet<string>(StringComparer.OrdinalIgnoreCase) {
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null).Value,
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null).Value
            };
            foreach (FileSystemAccessRule rule in security.GetAccessRules(
                true, false, typeof(SecurityIdentifier)))
            {
                SecurityIdentifier sid = (SecurityIdentifier)rule.IdentityReference;
                if (rule.AccessControlType != AccessControlType.Allow ||
                    !allowed.Contains(sid.Value) ||
                    (rule.FileSystemRights & FileSystemRights.FullControl) != FileSystemRights.FullControl)
                    throw new UnauthorizedAccessException(
                        "Installer staging grants access outside Administrators and SYSTEM.");
                allowed.Remove(sid.Value);
            }
            if (allowed.Count != 0)
                throw new UnauthorizedAccessException("Installer staging ACL is incomplete.");
            return full;
        }

        private static void DeleteProtectedStagingDirectory(string path)
        {
            string root = ValidateProtectedStagingDirectory(path);
            Stack<string> pending = new Stack<string>();
            pending.Push(root);
            while (pending.Count > 0)
            {
                foreach (string entry in Directory.GetFileSystemEntries(pending.Pop()))
                {
                    FileAttributes attributes = File.GetAttributes(entry);
                    if ((attributes & FileAttributes.ReparsePoint) != 0)
                        throw new InvalidDataException(
                            "Refusing to recursively delete a reparse point from installer staging.");
                    if ((attributes & FileAttributes.Directory) != 0) pending.Push(entry);
                }
            }
            Directory.Delete(root, true);
        }

        private static void ExtractPayload(string destination)
        {
            destination = ValidateProtectedStagingDirectory(destination);
            Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream(
                "MoonWaker.HostServices.zip");
            if (resource == null) throw new InvalidOperationException("Missing embedded host package.");
            string root = Path.GetFullPath(destination) + Path.DirectorySeparatorChar;
            using (resource)
            using (ZipArchive archive = new ZipArchive(resource, ZipArchiveMode.Read))
            {
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    string target = Path.GetFullPath(Path.Combine(destination,
                        entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                    if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("Invalid path in embedded host package.");
                    if (String.IsNullOrEmpty(entry.Name)) { Directory.CreateDirectory(target); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(target));
                    entry.ExtractToFile(target, true);
                }
            }
        }

        private static string ReadPayloadVersion()
        {
            try
            {
                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(
                    "MoonWaker.Version.json"))
                using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
                {
                    string text = reader.ReadToEnd();
                    System.Text.RegularExpressions.Match match =
                        System.Text.RegularExpressions.Regex.Match(text,
                            "\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                    if (match.Success) return "v" + match.Groups[1].Value;
                }
            }
            catch { }
            return "v?";
        }

        private bool SharedComponentsNeedUpdate(string root)
        {
            if (!SharedComponentsInstalled(root)) return true;
            try
            {
                string directory = Path.GetFullPath(root);
                foreach (string required in new[] {
                    Path.Combine("gateway", "MoonWakerGatewayService.exe"),
                    Path.Combine("windows-login", "login-broker", "MoonWakerLoginBroker.exe"),
                    Path.Combine("windows-login", "credential-provider", "MoonWakerCredentialProvider.dll"),
                    Path.Combine("control", "MoonWakerHostControl.exe"),
                    Path.Combine("control", "MoonWakerHostConfigurator.exe"),
                    Path.Combine("tools", "legendary", "legendary.exe") })
                {
                    if (!File.Exists(Path.Combine(directory, required))) return true;
                }
                return !String.Equals(ReadInstalledVersion(directory), payloadVersion,
                           StringComparison.OrdinalIgnoreCase) ||
                    !String.Equals(ReadInstalledVersion(Path.Combine(directory, "gateway")),
                        payloadVersion, StringComparison.OrdinalIgnoreCase);
            }
            catch { return true; }
        }

        private static bool SharedComponentsInstalled(string root)
        {
            if (String.IsNullOrWhiteSpace(root)) return false;
            try
            {
                string directory = Path.GetFullPath(root);
                return File.Exists(Path.Combine(directory, "gateway", "gateway.json")) &&
                    File.Exists(Path.Combine(directory, "control", "MoonWakerHostControl.exe"));
            }
            catch { return false; }
        }

        private static string ReadInstalledVersion(string root)
        {
            try
            {
                string versionFile = Path.Combine(Path.GetFullPath(root), "version.json");
                if (!File.Exists(versionFile)) return "";
                string text = File.ReadAllText(versionFile, Encoding.UTF8);
                System.Text.RegularExpressions.Match match =
                    System.Text.RegularExpressions.Regex.Match(text,
                        "\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                return match.Success ? "v" + match.Groups[1].Value : "";
            }
            catch { return ""; }
        }

        private string DisplayInstalledVersion(string root)
        {
            string version = ReadInstalledVersion(root);
            return String.IsNullOrWhiteSpace(version)
                ? T("unknown version", "nieznana wersja") : version;
        }

        private static string Quote(string value)
        {
            return "\"" + value.Replace("\"", "\\\"") + "\"";
        }

        private string T(string english, string polish)
        {
            return language == 1 ? polish : english;
        }

        private sealed class WakeOnLanProbe
        {
            internal bool Supported;
            internal bool Armed;
            internal string Adapters = "";
            internal string Error = "";
        }
    }
}
