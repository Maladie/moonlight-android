using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using Microsoft.Win32;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

[assembly: AssemblyVersion("0.7.78.0")]
[assembly: AssemblyFileVersion("0.7.78.0")]
[assembly: AssemblyInformationalVersion("0.7.78+2026.09.05")]

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
    }

    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
        }
    }

    internal sealed class InstallerForm : Form
    {
        private static readonly Color Background = Color.FromArgb(12, 15, 22);
        private static readonly Color Surface = Color.FromArgb(22, 27, 38);
        private static readonly Color SurfaceRaised = Color.FromArgb(29, 35, 49);
        private static readonly Color Muted = Color.FromArgb(174, 183, 201);
        private static readonly Color Accent = Color.FromArgb(120, 103, 255);
        private static readonly Color Good = Color.FromArgb(91, 214, 151);
        private static readonly Color Warning = Color.FromArgb(255, 190, 94);
        private static readonly Color Bad = Color.FromArgb(255, 112, 124);

        private readonly Panel[] pages = new Panel[5];
        private readonly Label[] stepLabels = new Label[5];
        private readonly Panel pageHost = new Panel();
        private readonly Panel navigation = new Panel();
        private readonly Button back = new Button();
        private readonly Button next = new Button();
        private readonly Button uninstall = new Button();

        private readonly Label wolStatus = new Label();
        private readonly Label wolDetails = new Label();
        private readonly Panel firmwarePanel = new Panel();
        private readonly Label firmwareInstructions = new Label();
        private readonly Button recheck = new Button();
        private readonly Label vibepolloDetectionStatus = new Label();
        private readonly Label vibepolloDetectionDetails = new Label();
        private readonly Label vibepolloSetupStatus = new Label();
        private readonly Label vibepolloSetupDetails = new Label();
        private readonly Label moonWakerDetectionStatus = new Label();
        private Panel vibepolloDetectionCard;
        private Panel moonWakerDetectionCard;
        private Label systemCaveat;

        private readonly TextBox installPath = new TextBox();
        private readonly CheckBox installMachine = new CheckBox();
        private readonly Label installationStatus = new Label();

        private readonly ProgressBar progress = new ProgressBar();
        private readonly Label progressTitle = new Label();
        private readonly Label progressDetail = new Label();
        private readonly RichTextBox log = new RichTextBox();
        private readonly Button install = new Button();

        private int language;
        private int currentPage;
        private bool restartHostControl;
        private bool vibepolloInstalled;
        private string vibepolloVersion;
        private WakeOnLanProbe wakeOnLan = new WakeOnLanProbe();
        private bool installationCompleted;
        private bool installationFailed;
        private readonly string payloadVersion;

        internal InstallerForm()
        {
            payloadVersion = ReadPayloadVersion();
            Text = "MoonWaker Host Installer " + payloadVersion;
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(1100, 830);
            MinimumSize = new Size(1000, 760);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Background;
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);
            DoubleBuffered = true;
            try { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location); } catch { }

            Controls.Add(BuildSidebar());
            Panel main = new Panel { Dock = DockStyle.Fill, BackColor = Background };
            Controls.Add(main);
            main.BringToFront();

            navigation.Dock = DockStyle.Bottom;
            navigation.Height = 76;
            navigation.BackColor = Color.FromArgb(17, 21, 30);
            navigation.Padding = new Padding(24, 16, 24, 16);
            main.Controls.Add(navigation);

            ConfigureNavigationButton(back, 442, "Back", "Wstecz");
            back.Click += delegate { if (currentPage > 1) ShowPage(currentPage - 1); };
            navigation.Controls.Add(back);
            ConfigureNavigationButton(next, 600, "Continue", "Dalej");
            next.BackColor = Accent;
            next.FlatAppearance.BorderColor = Accent;
            next.Click += async delegate { await ContinueAsync(); };
            navigation.Controls.Add(next);
            ConfigureNavigationButton(uninstall, 24, "Uninstall", "Odinstaluj");
            uninstall.Anchor = AnchorStyles.Top | AnchorStyles.Left;
            uninstall.BackColor = Color.FromArgb(92, 38, 47);
            uninstall.FlatAppearance.BorderColor = Bad;
            uninstall.Click += async delegate { await UninstallAsync(); };
            navigation.Controls.Add(uninstall);
            navigation.Resize += delegate {
                next.Left = navigation.ClientSize.Width - next.Width - 24;
                back.Left = next.Left - back.Width - 12;
            };

            pageHost.Dock = DockStyle.Fill;
            pageHost.BackColor = Background;
            main.Controls.Add(pageHost);
            pageHost.BringToFront();

            BuildLanguagePage();
            BuildSystemPage();
            BuildVibepolloPage();
            BuildOptionsPage();
            BuildProgressPage();

            installPath.Text = DetectInstallDirectory();
            installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());

            installPath.TextChanged += delegate {
                installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
                RefreshInstallationStatus();
            };
            ApplyLanguage(this);
            RefreshInstallationStatus();
            ShowPage(0);
        }

        private Panel BuildSidebar()
        {
            Panel sidebar = new Panel { Dock = DockStyle.Left, Width = 220,
                BackColor = Color.FromArgb(18, 22, 32) };
            PictureBox brandIcon = new PictureBox { Left = 24, Top = 28,
                Width = 42, Height = 42, SizeMode = PictureBoxSizeMode.Zoom };
            if (Icon != null) brandIcon.Image = Icon.ToBitmap();
            sidebar.Controls.Add(brandIcon);
            AddLabel(sidebar, "MOONWAKER", 13F, FontStyle.Bold, 77, 29, 138, 25);
            Label host = AddLabel(sidebar, "HOST SETUP", 8.5F, FontStyle.Bold, 79, 52, 120, 20);
            host.ForeColor = Color.FromArgb(169, 157, 255);
            string[,] titles = {
                { "Language", "Język" }, { "System check", "Sprawdzenie systemu" },
                { "Vibepollo", "Vibepollo" }, { "MoonWaker", "MoonWaker" },
                { "Installation", "Instalacja" }
            };
            for (int i = 0; i < stepLabels.Length; i++)
            {
                Label number = AddLabel(sidebar, "0" + (i + 1), 9F, FontStyle.Bold,
                    27, 130 + i * 66, 30, 24);
                number.ForeColor = Color.FromArgb(113, 122, 142);
                stepLabels[i] = AddLocalizedLabel(sidebar, titles[i, 0], titles[i, 1],
                    10.5F, FontStyle.Regular, 68, 128 + i * 66, 138, 38);
            }
            Label version = AddLabel(sidebar, payloadVersion, 9F, FontStyle.Bold,
                26, 660, 160, 24);
            version.Anchor = AnchorStyles.Left | AnchorStyles.Bottom;
            version.ForeColor = Muted;
            return sidebar;
        }

        private void BuildLanguagePage()
        {
            Panel page = CreatePage(0, 620);
            AddLabel(page, "Welcome / Witaj", 26F, FontStyle.Bold, 32, 45, 690, 44);
            Label intro = AddLabel(page,
                "Choose the installer language / Wybierz język instalatora",
                11F, FontStyle.Regular, 34, 94, 680, 30);
            intro.ForeColor = Muted;
            Panel englishCard = MakeCard(page, 150, 120, Color.FromArgb(75, 151, 255));
            AddLabel(englishCard, "English", 17F, FontStyle.Bold, 28, 22, 300, 34);
            Label enDetail = AddLabel(englishCard, "Continue in English", 10F,
                FontStyle.Regular, 29, 60, 300, 24);
            enDetail.ForeColor = Muted;
            Button english = MakeButton("Select", 525, 34, 150, 46);
            english.Click += async delegate { await SelectLanguageAsync(0); };
            englishCard.Controls.Add(english);
            Panel polishCard = MakeCard(page, 288, 120, Color.FromArgb(231, 78, 99));
            AddLabel(polishCard, "Polski", 17F, FontStyle.Bold, 28, 22, 300, 34);
            Label plDetail = AddLabel(polishCard, "Kontynuuj po polsku", 10F,
                FontStyle.Regular, 29, 60, 300, 24);
            plDetail.ForeColor = Muted;
            Button polish = MakeButton("Wybierz", 525, 34, 150, 46);
            polish.Click += async delegate { await SelectLanguageAsync(1); };
            polishCard.Controls.Add(polish);
            Label note = AddLabel(page,
                "You can restart the installer to choose another language.  •  Język można zmienić po ponownym uruchomieniu.",
                9F, FontStyle.Regular, 34, 444, 680, 44);
            note.ForeColor = Color.FromArgb(132, 142, 161);
        }

        private void BuildSystemPage()
        {
            Panel page = CreatePage(1, 745);
            AddPageHeading(page, "Let's check this PC", "Sprawdźmy ten komputer",
                "MoonWaker checks the streaming host before making changes.",
                "MoonWaker sprawdzi host strumieniowania przed wprowadzeniem zmian.");
            Panel wolCard = MakeCard(page, 112, 112, Color.FromArgb(68, 198, 142));
            AddLocalizedLabel(wolCard, "Wake-on-LAN", "Wake-on-LAN", 13F,
                FontStyle.Bold, 24, 16, 300, 28);
            wolStatus.SetBounds(24, 48, 650, 25);
            wolStatus.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            wolCard.Controls.Add(wolStatus);
            wolDetails.SetBounds(24, 76, 650, 25);
            wolDetails.ForeColor = Muted;
            wolCard.Controls.Add(wolDetails);
            firmwarePanel.SetBounds(28, 238, 704, 178);
            firmwarePanel.BackColor = Color.FromArgb(49, 39, 25);
            firmwarePanel.Controls.Add(new Panel { Dock = DockStyle.Left, Width = 4, BackColor = Warning });
            AddLocalizedLabel(firmwarePanel,
                "Wake-on-LAN needs attention", "Wake-on-LAN wymaga uwagi",
                12F, FontStyle.Bold, 20, 14, 470, 28);
            firmwareInstructions.SetBounds(20, 47, 652, 91);
            firmwareInstructions.ForeColor = Color.FromArgb(235, 220, 191);
            firmwareInstructions.Font = new Font("Segoe UI", 9.2F);
            firmwarePanel.Controls.Add(firmwareInstructions);
            ConfigureNavigationButton(recheck, 520, "Check again", "Sprawdź ponownie");
            recheck.SetBounds(520, 136, 160, 32);
            recheck.Click += async delegate { await RefreshPreflightAsync(); };
            firmwarePanel.Controls.Add(recheck);
            page.Controls.Add(firmwarePanel);
            vibepolloDetectionCard = MakeCard(page, 430, 104, Color.FromArgb(255, 166, 76));
            AddLabel(vibepolloDetectionCard, "Vibepollo", 13F, FontStyle.Bold, 24, 14, 300, 28);
            vibepolloDetectionStatus.SetBounds(24, 45, 650, 24);
            vibepolloDetectionStatus.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            vibepolloDetectionCard.Controls.Add(vibepolloDetectionStatus);
            vibepolloDetectionDetails.SetBounds(24, 72, 650, 22);
            vibepolloDetectionDetails.ForeColor = Muted;
            vibepolloDetectionCard.Controls.Add(vibepolloDetectionDetails);
            moonWakerDetectionCard = MakeCard(page, 548, 100, Accent);
            AddLabel(moonWakerDetectionCard, "MoonWaker Host", 13F, FontStyle.Bold, 24, 14, 300, 28);
            moonWakerDetectionStatus.SetBounds(24, 47, 650, 40);
            moonWakerDetectionStatus.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            moonWakerDetectionCard.Controls.Add(moonWakerDetectionStatus);
            systemCaveat = AddLocalizedLabel(page,
                "Windows cannot read every vendor-specific BIOS/UEFI switch. The check above uses the wired adapter capability reported by Windows.",
                "Windows nie potrafi odczytać każdego przełącznika BIOS/UEFI producenta. Powyższy test korzysta z możliwości przewodowej karty zgłoszonej przez Windows.",
                8.8F, FontStyle.Regular, 32, 670, 685, 52);
            systemCaveat.ForeColor = Color.FromArgb(133, 143, 162);
            firmwarePanel.Visible = false;
            UpdateSystemPageLayout();
        }

        private void BuildVibepolloPage()
        {
            Panel page = CreatePage(2, 520);
            AddPageHeading(page, "Set up Vibepollo", "Skonfiguruj Vibepollo",
                "MoonWaker installs Vibepollo here; profile access is configured after the host installation.",
                "MoonWaker instaluje tutaj Vibepollo; dostęp profilu skonfigurujesz po instalacji hosta.");
            Panel statusCard = MakeCard(page, 112, 82, Color.FromArgb(255, 166, 76));
            vibepolloSetupStatus.SetBounds(24, 15, 650, 25);
            vibepolloSetupStatus.Font = new Font("Segoe UI", 10.5F, FontStyle.Bold);
            statusCard.Controls.Add(vibepolloSetupStatus);
            vibepolloSetupDetails.SetBounds(24, 45, 650, 24);
            vibepolloSetupDetails.ForeColor = Muted;
            statusCard.Controls.Add(vibepolloSetupDetails);
            Panel setupCard = MakeCard(page, 208, 156, Accent);
            AddLocalizedLabel(setupCard, "No password is collected here", "Tutaj nie podajesz hasła",
                13F, FontStyle.Bold, 24, 16, 500, 28);
            Label setupNote = AddLocalizedLabel(setupCard,
                "After installation, create a profile in Host Control and choose Integrations. You can paste a Vibepollo token or let MoonWaker request one, and configure Discord there too.",
                "Po instalacji utwórz profil w Host Control i wybierz Integracje. Możesz wkleić token Vibepollo albo pobrać go automatycznie; tam skonfigurujesz też Discorda.",
                9F, FontStyle.Regular, 24, 58, 650, 70);
            setupNote.ForeColor = Muted;
            Label safety = AddLocalizedLabel(page,
                "The MoonWaker installer never receives or logs a Vibepollo password.",
                "Instalator MoonWaker nigdy nie otrzymuje ani nie zapisuje hasła Vibepollo.",
                8.8F, FontStyle.Regular, 34, 388, 680, 44);
            safety.ForeColor = Color.FromArgb(133, 143, 162);
        }

        private void BuildOptionsPage()
        {
            Panel page = CreatePage(3, 520);
            AddPageHeading(page, "Ready to install", "Gotowe do instalacji",
                "Defaults are selected automatically. Change only what you need.",
                "Ustawienia domyślne wybrano automatycznie. Zmień tylko to, czego potrzebujesz.");
            Panel locationCard = MakeCard(page, 112, 208, Color.FromArgb(75, 151, 255));
            AddLocalizedLabel(locationCard, "Host installation", "Instalacja hosta",
                13F, FontStyle.Bold, 24, 15, 400, 28);
            AddLocalizedLabel(locationCard, "Installation folder", "Katalog instalacji",
                9F, FontStyle.Regular, 24, 51, 260, 22);
            ConfigureTextBox(installPath, 24, 74, 492, false);
            locationCard.Controls.Add(installPath);
            Button browse = MakeLocalizedButton("Browse…", "Wybierz…", 534, 74, 142, 34);
            browse.Click += delegate { BrowseInstallDirectory(); };
            locationCard.Controls.Add(browse);
            installationStatus.SetBounds(24, 119, 652, 75);
            installationStatus.ForeColor = Muted;
            locationCard.Controls.Add(installationStatus);
            Panel profileCard = MakeCard(page, 334, 154, Color.FromArgb(68, 198, 142));
            AddLocalizedLabel(profileCard, "Profiles in Host Control", "Profile w Host Control",
                13F, FontStyle.Bold, 24, 14, 400, 28);
            Label info = AddLocalizedLabel(profileCard,
                "This installer installs the shared machine components and Gateway. Add Windows profiles, integrations and Remote Sign-in permissions in Host Control.",
                "Ten instalator instaluje wspólne komponenty komputera i Gateway. Profile Windows, integracje i uprawnienia zdalnego logowania dodasz w Host Control.",
                8.8F, FontStyle.Regular, 24, 48, 650, 42);
            info.ForeColor = Muted;
            AddLocalizedLabel(profileCard,
                "After installation, open MoonWaker Host Control from the Windows Start menu.",
                "Po instalacji otwórz MoonWaker Host Control z menu Start systemu Windows.",
                8.6F, FontStyle.Bold, 24, 96, 650, 42);
        }

        private void BuildProgressPage()
        {
            Panel page = CreatePage(4, 650);
            progressTitle.SetBounds(34, 58, 680, 44);
            progressTitle.Font = new Font("Segoe UI", 25F, FontStyle.Bold);
            page.Controls.Add(progressTitle);
            progressDetail.SetBounds(36, 108, 675, 54);
            progressDetail.Font = new Font("Segoe UI", 10.5F);
            progressDetail.ForeColor = Muted;
            page.Controls.Add(progressDetail);
            Panel progressCard = MakeCard(page, 188, 318, Accent);
            progress.SetBounds(26, 27, 648, 12);
            progress.Style = ProgressBarStyle.Continuous;
            progress.Maximum = 100;
            progressCard.Controls.Add(progress);
            AddLocalizedLabel(progressCard, "Installation log", "Dziennik instalacji",
                9F, FontStyle.Bold, 26, 58, 300, 22);
            log.SetBounds(26, 86, 648, 202);
            log.ReadOnly = true;
            log.BackColor = Color.FromArgb(10, 13, 19);
            log.ForeColor = Color.FromArgb(204, 211, 224);
            log.BorderStyle = BorderStyle.FixedSingle;
            log.Font = new Font("Consolas", 9F);
            log.WordWrap = true;
            log.ScrollBars = RichTextBoxScrollBars.Vertical;
            progressCard.Controls.Add(log);
            install.Visible = false;
        }

        private Panel CreatePage(int index, int contentHeight)
        {
            Panel page = new Panel { Dock = DockStyle.Fill, BackColor = Background,
                AutoScroll = true, AutoScrollMinSize = new Size(0, contentHeight), Visible = false };
            pages[index] = page;
            pageHost.Controls.Add(page);
            return page;
        }

        private void AddPageHeading(Panel page, string englishTitle, string polishTitle,
                                    string englishSubtitle, string polishSubtitle)
        {
            AddLocalizedLabel(page, englishTitle, polishTitle, 24F, FontStyle.Bold,
                32, 34, 690, 43);
            Label subtitle = AddLocalizedLabel(page, englishSubtitle, polishSubtitle,
                10.5F, FontStyle.Regular, 34, 78, 685, 30);
            subtitle.ForeColor = Muted;
        }

        private async Task SelectLanguageAsync(int selectedLanguage)
        {
            SetLanguage(selectedLanguage);
            await RefreshPreflightAsync();
        }

        private void SetLanguage(int selectedLanguage)
        {
            language = selectedLanguage == 1 ? 1 : 0;
            Text = T("MoonWaker Host Installer ", "Instalator MoonWaker Host ") + payloadVersion;
            ApplyLanguage(this);
            RefreshInstallationStatus();
            ShowPage(1);
        }

        private async Task ContinueAsync()
        {
            if (currentPage == 1) { ShowPage(2); return; }
            if (currentPage == 2)
            {
                ShowPage(3); return;
            }
            if (currentPage == 3)
            {
                string error = ValidateInput();
                if (error != null) { ShowWarning(error); return; }
                await InstallAsync(); return;
            }
            if (currentPage == 4)
            {
                if (installationCompleted) { Close(); return; }
                if (installationFailed) await InstallAsync();
            }
        }

        private void ShowPage(int index)
        {
            currentPage = index;
            for (int i = 0; i < pages.Length; i++)
            {
                pages[i].Visible = i == index;
                stepLabels[i].ForeColor = i == index ? Color.White : Color.FromArgb(121, 130, 149);
                stepLabels[i].Font = new Font("Segoe UI", 10.5F,
                    i == index ? FontStyle.Bold : FontStyle.Regular);
            }
            navigation.Visible = index != 0;
            back.Visible = index > 1 && index < 4;
            next.Visible = index > 0;
            uninstall.Visible = index == 3 && SharedComponentsInstalled(installPath.Text.Trim());
            next.Enabled = true;
            next.Text = index == 3 ? T("Install", "Zainstaluj") : T("Continue", "Dalej");
            if (index == 4)
            {
                back.Visible = installationFailed;
                next.Enabled = installationCompleted || installationFailed;
                next.Text = installationCompleted ? T("Finish", "Zakończ") :
                    (installationFailed ? T("Retry", "Spróbuj ponownie") :
                    T("Installing…", "Instalowanie…"));
            }
            pages[index].BringToFront();
        }

        private async Task RefreshPreflightAsync()
        {
            recheck.Enabled = false;
            next.Enabled = false;
            SetStatus(wolStatus, T("Checking Windows…", "Sprawdzanie Windows…"), Muted);
            SetStatus(vibepolloDetectionStatus, T("Checking installation…", "Sprawdzanie instalacji…"), Muted);
            SetStatus(vibepolloSetupStatus, T("Checking installation…", "Sprawdzanie instalacji…"), Muted);
            vibepolloDetectionDetails.Text = "";
            vibepolloSetupDetails.Text = "";
            firmwarePanel.Visible = false;
            UpdateSystemPageLayout();
            Task<WakeOnLanProbe> wakeTask = Task.Run<WakeOnLanProbe>(() => ProbeWakeOnLan());
            Task<string> vibepolloTask = Task.Run<string>(() => DetectVibepolloVersion());
            try { wakeOnLan = await wakeTask; }
            catch (Exception error)
            {
                wakeOnLan = new WakeOnLanProbe { Error = error.Message };
            }
            try
            {
                vibepolloVersion = await vibepolloTask;
                vibepolloInstalled = vibepolloVersion != null;
            }
            catch (Exception error)
            {
                vibepolloInstalled = false;
                vibepolloVersion = null;
                vibepolloDetectionDetails.Text = error.Message;
            }
            ApplyPreflightResults();
            recheck.Enabled = true;
            next.Enabled = true;
        }

        private void ApplyPreflightResults()
        {
            if (!String.IsNullOrWhiteSpace(wakeOnLan.Error))
            {
                SetStatus(wolStatus, T("Check failed", "Sprawdzenie nie powiodło się"), Bad);
                wolDetails.Text = wakeOnLan.Error;
                firmwareInstructions.Text = T(
                    "Windows could not complete the adapter check. Verify Wake-on-LAN in BIOS/UEFI and Device Manager, then select Check again.",
                    "Windows nie ukończył sprawdzania karty. Sprawdź Wake-on-LAN w BIOS/UEFI i Menedżerze urządzeń, a następnie wybierz Sprawdź ponownie.");
                firmwarePanel.Visible = true;
            }
            else if (wakeOnLan.Armed)
            {
                SetStatus(wolStatus, T("Ready — Wake-on-LAN is enabled",
                    "Gotowe — Wake-on-LAN jest włączony"), Good);
                wolDetails.Text = wakeOnLan.Adapters;
                firmwarePanel.Visible = false;
            }
            else if (wakeOnLan.Supported)
            {
                SetStatus(wolStatus, T("Supported — Windows will enable it during installation",
                    "Obsługiwane — Windows włączy je podczas instalacji"), Warning);
                wolDetails.Text = wakeOnLan.Adapters;
                firmwarePanel.Visible = false;
            }
            else
            {
                SetStatus(wolStatus, T("Not available to Windows", "Niedostępne dla Windows"), Warning);
                wolDetails.Text = T("Enable the firmware setting, then return and check again.",
                    "Włącz ustawienie firmware, następnie wróć i sprawdź ponownie.");
                firmwareInstructions.Text = T(
                    "1. Restart the PC and open BIOS/UEFI (usually Del, F2 or F10).\n2. Enable “Wake on LAN”, “Power on by PCI-E” or “Resume by LAN”. Save changes.\n3. Use wired Ethernet, start Windows and select Check again.",
                    "1. Uruchom ponownie komputer i otwórz BIOS/UEFI (zwykle Del, F2 lub F10).\n2. Włącz „Wake on LAN”, „Power on by PCI-E” lub „Resume by LAN”. Zapisz zmiany.\n3. Użyj przewodowego Ethernetu, uruchom Windows i wybierz Sprawdź ponownie.");
                firmwarePanel.Visible = true;
            }
            UpdateSystemPageLayout();
            RefreshVibepolloStatus();
            bool sharedInstalled = SharedComponentsInstalled(installPath.Text.Trim());
            bool updateRequired = SharedComponentsNeedUpdate(installPath.Text.Trim());
            if (!sharedInstalled)
                SetStatus(moonWakerDetectionStatus,
                    T("Not installed — all host components will be added",
                      "Brak instalacji — wszystkie komponenty hosta zostaną dodane"), Warning);
            else if (updateRequired)
                SetStatus(moonWakerDetectionStatus,
                    T("Update available — existing profiles and secrets will be preserved",
                      "Dostępna aktualizacja — profile i sekrety zostaną zachowane"), Warning);
            else
                SetStatus(moonWakerDetectionStatus,
                    T("Up to date — manage Windows profiles in Host Control",
                      "Aktualne — profilami Windows zarządza Host Control"), Good);
            RefreshInstallationStatus();
        }

        private void UpdateSystemPageLayout()
        {
            bool showFirmware = firmwarePanel.Visible;
            vibepolloDetectionCard.Top = showFirmware ? 430 : 238;
            moonWakerDetectionCard.Top = showFirmware ? 548 : 356;
            systemCaveat.Top = showFirmware ? 670 : 478;
            pages[1].AutoScrollMinSize = new Size(0, showFirmware ? 745 : 565);
        }

        private void RefreshVibepolloStatus()
        {
            if (vibepolloInstalled)
            {
                SetStatus(vibepolloDetectionStatus,
                    T("Installed", "Zainstalowany") +
                    (String.IsNullOrWhiteSpace(vibepolloVersion) ? "" : "  •  " + vibepolloVersion), Good);
                SetStatus(vibepolloSetupStatus,
                    T("Installed", "Zainstalowany") +
                    (String.IsNullOrWhiteSpace(vibepolloVersion) ? "" : "  •  " + vibepolloVersion), Good);
                vibepolloDetectionDetails.Text = T(
                    "MoonWaker will use the existing installation and will not replace its settings.",
                    "MoonWaker użyje istniejącej instalacji i nie zastąpi jej ustawień.");
                vibepolloSetupDetails.Text = vibepolloDetectionDetails.Text;
            }
            else
            {
                SetStatus(vibepolloDetectionStatus,
                    T("Not installed — the official signed MSI will be downloaded",
                      "Brak instalacji — zostanie pobrany oficjalny podpisany MSI"), Warning);
                SetStatus(vibepolloSetupStatus,
                    T("Not installed — the official signed MSI will be downloaded",
                      "Brak instalacji — zostanie pobrany oficjalny podpisany MSI"), Warning);
                vibepolloDetectionDetails.Text = T(
                    "The latest stable release is downloaded directly from Nonary/Vibepollo on GitHub.",
                    "Najnowsze stabilne wydanie zostanie pobrane bezpośrednio z Nonary/Vibepollo na GitHub.");
                vibepolloSetupDetails.Text = vibepolloDetectionDetails.Text;
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
                                string display = Convert.ToString(entry.GetValue("DisplayName"));
                                if (display.IndexOf("Vibepollo", StringComparison.OrdinalIgnoreCase) < 0) continue;
                                return Convert.ToString(entry.GetValue("DisplayVersion")) ?? "";
                            }
                        }
                    }
                }
                catch { }
            }
            string executable = Path.Combine(Environment.GetFolderPath(
                Environment.SpecialFolder.ProgramFiles), "Vibepollo", "sunshine.exe");
            return File.Exists(executable) ? "" : null;
        }

        private async Task InstallAsync()
        {
            string validation = ValidateInput();
            if (validation != null) { ShowWarning(validation); return; }
            if (!PrepareHostControlUpdate(installMachine.Checked, true)) return;
            installationCompleted = false;
            installationFailed = false;
            ShowPage(4);
            next.Enabled = false;
            back.Visible = false;
            progress.Value = 4;
            progressTitle.Text = T("Installing MoonWaker Host", "Instalowanie MoonWaker Host");
            progressDetail.Text = T(
                "Keep this administrator window open while the machine components and Gateway are installed.",
                "Pozostaw to okno administratora otwarte podczas instalowania komponentów komputera i Gateway.");
            log.Text = T("Preparing the installation…\r\n", "Przygotowywanie instalacji…\r\n");
            try
            {
                string output = await Task.Run<string>(() => RunInstaller());
                log.AppendText(output);
                progress.Value = 100;
                progressTitle.Text = T("Host files are installed", "Pliki hosta są zainstalowane");
                progressDetail.Text = T(
                    "Gateway and Login Broker are running. Open Host Control from Start to add profiles and integrations.",
                    "Gateway i Login Broker działają. Otwórz Host Control z menu Start, aby dodać profile i integracje.");
                installationCompleted = true;
            }
            catch (Exception error)
            {
                log.AppendText("\r\n" + T("ERROR: ", "BŁĄD: ") + error.Message);
                progressTitle.Text = T("Installation needs attention", "Instalacja wymaga uwagi");
                progressDetail.Text = T("Review the message below, correct the problem and retry.",
                    "Przeczytaj komunikat poniżej, popraw problem i spróbuj ponownie.");
                installationFailed = true;
            }
            finally { RestartHostControlIfNeeded(); ShowPage(4); }
        }

        private async Task UninstallAsync()
        {
            string directory = Path.GetFullPath(installPath.Text.Trim());
            if (!SharedComponentsInstalled(directory))
            {
                ShowWarning(T("No MoonWaker installation was found in this folder.",
                    "W tym katalogu nie znaleziono instalacji MoonWaker."));
                return;
            }
            DialogResult answer = MessageBox.Show(this, T(
                "Remove MoonWaker services, profiles, pairing data and stored credentials from this computer?\n\nThis cannot be undone.",
                "Usunąć z tego komputera usługi MoonWaker, profile, dane parowania i zapisane poświadczenia?\n\nTej operacji nie można cofnąć."),
                T("Uninstall MoonWaker", "Odinstaluj MoonWaker"),
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (answer != DialogResult.Yes || !PrepareHostControlUpdate(true, false)) return;

            installationCompleted = false;
            installationFailed = false;
            ShowPage(4);
            next.Enabled = false;
            back.Visible = false;
            progress.Value = 10;
            progressTitle.Text = T("Uninstalling MoonWaker Host", "Odinstalowywanie MoonWaker Host");
            progressDetail.Text = T("Stopping services and removing MoonWaker data…",
                "Zatrzymywanie usług i usuwanie danych MoonWaker…");
            log.Text = progressDetail.Text + "\r\n";
            try
            {
                string output = await Task.Run<string>(() => RunUninstaller(directory));
                log.AppendText(output);
                progress.Value = 100;
                progressTitle.Text = T("MoonWaker was removed", "MoonWaker został usunięty");
                progressDetail.Text = T("Services, profiles, credentials and installation files were removed.",
                    "Usunięto usługi, profile, poświadczenia i pliki instalacji.");
                installationCompleted = true;
            }
            catch (Exception error)
            {
                log.AppendText("\r\n" + T("ERROR: ", "BŁĄD: ") + error.Message);
                progressTitle.Text = T("Uninstallation needs attention", "Deinstalacja wymaga uwagi");
                progressDetail.Text = T("Review the message below and retry.",
                    "Przeczytaj komunikat poniżej i spróbuj ponownie.");
                installationFailed = true;
            }
            finally { ShowPage(4); }
        }

        private string RunInstaller()
        {
            string temporary = CreateProtectedStagingDirectory();
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
                bool ensureVibepollo = !vibepolloInstalled;
                bool enableWakeOnLan = wakeOnLan.Supported && !wakeOnLan.Armed;
                StringBuilder combinedOutput = new StringBuilder();
                if (installMachine.Checked || ensureVibepollo || enableWakeOnLan)
                {
                    ReportProgress(28, ensureVibepollo
                        ? T("Downloading and configuring Vibepollo…", "Pobieranie i konfigurowanie Vibepollo…")
                        : T("Configuring Windows host services…", "Konfigurowanie usług hosta Windows…"));
                    combinedOutput.Append(RunMachineInstall(hostScript, machineWrapper,
                        prerequisiteScript, Path.GetFullPath(installPath.Text.Trim()), temporary,
                        installMachine.Checked, enableWakeOnLan, ensureVibepollo));
                }
                if (combinedOutput.Length == 0) combinedOutput.AppendLine(T(
                    "Machine host components are already up to date.",
                    "Komponenty hosta komputera są już aktualne."));
                ReportProgress(92, T("Machine services are installed and starting; open Host Control to pair or manage profiles.",
                    "Usługi komputera są zainstalowane i uruchamiane; otwórz Host Control, aby sparować urządzenie lub zarządzać profilami."));
                return combinedOutput.ToString();
            }
            finally { DeleteProtectedStagingDirectory(temporary); }
        }

        private string RunUninstaller(string directory)
        {
            string temporary = CreateProtectedStagingDirectory();
            try
            {
                ExtractPayload(temporary);
                string script = Path.Combine(temporary, "host-services", "install",
                    "Uninstall-MoonWakerHostServices.ps1");
                if (!File.Exists(script)) throw new InvalidOperationException(T(
                    "The embedded uninstaller is missing.", "Brakuje osadzonego deinstalatora."));
                string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " +
                    Quote(script) + " -InstallDirectory " + Quote(directory) +
                    " -PurgeCredentials -RemoveFiles";
                ProcessStartInfo info = new ProcessStartInfo(WindowsPowerShellPath(), arguments);
                info.UseShellExecute = false; info.CreateNoWindow = true;
                info.RedirectStandardOutput = true; info.RedirectStandardError = true;
                info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
                info.EnvironmentVariables["PSModulePath"] = TrustedPowerShellModulePath();
                using (Process process = Process.Start(info))
                {
                    string stdout = process.StandardOutput.ReadToEnd();
                    string stderr = process.StandardError.ReadToEnd();
                    process.WaitForExit();
                    if (process.ExitCode != 0) throw new InvalidOperationException(
                        String.IsNullOrWhiteSpace(stderr) ? stdout : stderr);
                    return stdout;
                }
            }
            finally { DeleteProtectedStagingDirectory(temporary); }
        }

        private static string RunMachineInstall(string hostScript, string wrapper,
            string prerequisiteScript, string directory, string temporaryDirectory,
            bool installHost, bool enableWakeOnLan, bool ensureVibepollo)
        {
            if (!File.Exists(wrapper)) throw new InvalidOperationException(
                "The machine installation module is missing.");
            string resultPath = Path.Combine(temporaryDirectory, "machine-install-result.txt");
            string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(wrapper) +
                " -HostInstallScript " + Quote(hostScript) +
                " -PrerequisiteScript " + Quote(prerequisiteScript) +
                " -InstallDirectory " + Quote(directory) +
                " -GatewayDirectory " + Quote(Path.Combine(directory, "gateway")) +
                " -ProtectedStagingDirectory " + Quote(temporaryDirectory) +
                " -ResultPath " + Quote(resultPath);
            if (!installHost) arguments += " -SkipMoonWakerHost";
            if (enableWakeOnLan) arguments += " -EnableWakeOnLan";
            if (ensureVibepollo) arguments += " -EnsureVibepollo";
            ProcessStartInfo info = new ProcessStartInfo(WindowsPowerShellPath(), arguments);
            info.UseShellExecute = false; info.CreateNoWindow = true;
            info.EnvironmentVariables["PSModulePath"] = TrustedPowerShellModulePath();
            using (Process process = Process.Start(info))
            {
                process.WaitForExit();
                string details = File.Exists(resultPath) ? File.ReadAllText(resultPath).Trim() : "";
                if (process.ExitCode != 0) throw new InvalidOperationException(
                    "Machine preparation failed (" + process.ExitCode + ")." +
                    (String.IsNullOrWhiteSpace(details) ? "" : "\r\n\r\n" + details));
                return String.IsNullOrWhiteSpace(details) ? "" : details + "\r\n";
            }
        }

        private void ReportProgress(int value, string detail)
        {
            if (IsDisposed) return;
            BeginInvoke(new Action(delegate {
                progress.Value = Math.Max(progress.Minimum, Math.Min(progress.Maximum, value));
                progressDetail.Text = detail;
                log.AppendText(detail + "\r\n");
            }));
        }

        private bool PrepareHostControlUpdate(bool required, bool reopen)
        {
            restartHostControl = false;
            if (!required) return true;
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
            DialogResult answer = MessageBox.Show(this, reopen ? T(
                "MoonWaker Host Control must close for the update.\n\nClose it now and reopen it after installation?",
                "MoonWaker Host Control musi zostać zamknięty na czas aktualizacji.\n\nZamknąć go teraz i uruchomić ponownie po instalacji?") : T(
                "MoonWaker Host Control and Host Configurator must close before uninstallation.\n\nClose them now?",
                "MoonWaker Host Control i Host Configurator muszą zostać zamknięte przed deinstalacją.\n\nZamknąć je teraz?"),
                "MoonWaker Host Control", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
            if (answer != DialogResult.Yes)
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
                MessageBox.Show(this, error.Message, "MoonWaker",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return false;
            }
        }

        private void RestartHostControlIfNeeded()
        {
            if (!restartHostControl) return;
            restartHostControl = false;
            string executable = Path.GetFullPath(Path.Combine(
                installPath.Text.Trim(), "control", "MoonWakerHostControl.exe"));
            if (!File.Exists(executable)) return;
            try { Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true }); }
            catch (Exception error)
            {
                log.AppendText("\r\n" + T("Could not restart Host Control: ",
                    "Nie udało się ponownie uruchomić Host Control: ") + error.Message);
            }
        }

        private string ValidateInput()
        {
            string path = installPath.Text.Trim();
            if (String.IsNullOrWhiteSpace(path) || !Path.IsPathRooted(path))
                return T("Choose an absolute installation path.", "Wybierz bezwzględną ścieżkę instalacji.");
            string root = Path.GetPathRoot(Path.GetFullPath(path));
            if (String.IsNullOrWhiteSpace(root) || !Directory.Exists(root))
                return T("The selected drive is unavailable.", "Wybrany dysk jest niedostępny.");
            if (!installMachine.Checked && !File.Exists(Path.Combine(
                    Path.GetFullPath(path), "gateway", "gateway.json")))
                return T("Install shared MoonWaker components first or select an existing installation.",
                    "Najpierw zainstaluj wspólne komponenty MoonWaker albo wybierz istniejącą instalację.");
            return null;
        }

        private void RefreshInstallationStatus()
        {
            string root = installPath.Text.Trim();
            bool sharedInstalled = SharedComponentsInstalled(root);
            bool updateRequired = SharedComponentsNeedUpdate(root);
            if (!sharedInstalled)
            {
                installationStatus.ForeColor = Warning;
                installationStatus.Text = T(
                    "New installation. Shared host components and Gateway will be installed. Profiles are added in Host Control.",
                    "Nowa instalacja. Zostaną zainstalowane wspólne komponenty hosta i Gateway. Profile dodaje się w Host Control.");
            }
            else if (updateRequired)
            {
                installationStatus.ForeColor = Warning;
                installationStatus.Text = T("Update ", "Aktualizacja ") +
                    DisplayInstalledVersion(root) + " → " + payloadVersion + T(
                    ". Existing profiles, certificates, pairing and encrypted tokens will be preserved.",
                    ". Istniejące profile, certyfikaty, parowanie i zaszyfrowane tokeny zostaną zachowane.");
            }
            else
            {
                installationStatus.ForeColor = Good;
                installationStatus.Text = T(
                    "Shared components are up to date. Open Host Control to add or manage Windows profiles.",
                    "Wspólne komponenty są aktualne. Profile Windows dodasz lub zmienisz w Host Control.");
            }
            uninstall.Visible = currentPage == 3 && sharedInstalled;
        }

        private void BrowseInstallDirectory()
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = T("Choose the MoonWaker Host installation folder",
                    "Wybierz katalog instalacji MoonWaker Host");
                if (Directory.Exists(installPath.Text)) dialog.SelectedPath = installPath.Text;
                if (dialog.ShowDialog(this) == DialogResult.OK) installPath.Text = dialog.SelectedPath;
            }
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

        private static string DetectInstallDirectory()
        {
            string configured = Environment.GetEnvironmentVariable("MOONWAKER_INSTALL_DIRECTORY");
            if (!String.IsNullOrWhiteSpace(configured)) return configured;
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "MoonWaker");
        }

        private bool SharedComponentsNeedUpdate(string root)
        {
            if (!SharedComponentsInstalled(root)) return true;
            try
            {
                string directory = Path.GetFullPath(root);
                if (!File.Exists(Path.Combine(directory, "tools", "legendary", "legendary.exe"))) return true;
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

        private void ShowWarning(string message)
        {
            MessageBox.Show(this, message, "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }

        private void SetStatus(Label label, string text, Color color)
        {
            label.Text = "●  " + text;
            label.ForeColor = color;
        }

        private string T(string english, string polish)
        {
            return language == 1 ? polish : english;
        }

        private TControl Localize<TControl>(TControl control, string english, string polish)
            where TControl : Control
        {
            control.Tag = new[] { english, polish };
            control.Text = T(english, polish);
            return control;
        }

        private void ApplyLanguage(Control root)
        {
            string[] translations = root.Tag as string[];
            if (translations != null && translations.Length == 2) root.Text = translations[language];
            foreach (Control child in root.Controls) ApplyLanguage(child);
        }

        private void ConfigureTextBox(TextBox box, int left, int top, int width, bool password)
        {
            box.SetBounds(left, top, width, 34);
            box.BorderStyle = BorderStyle.FixedSingle;
            box.BackColor = Color.FromArgb(32, 38, 52);
            box.ForeColor = Color.White;
            box.UseSystemPasswordChar = password;
        }

        private void ConfigureCheckBox(CheckBox box, string english, string polish, int left, int top)
        {
            Localize(box, english, polish);
            box.SetBounds(left, top, 650, 30);
            box.ForeColor = Color.White;
            box.FlatStyle = FlatStyle.Flat;
            box.FlatAppearance.BorderColor = Accent;
        }

        private Panel MakeCard(Control parent, int top, int height, Color accentColor)
        {
            Panel card = new Panel { Left = 28, Top = top, Width = 704, Height = height,
                BackColor = Surface, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            card.Controls.Add(new Panel { Dock = DockStyle.Left, Width = 4, BackColor = accentColor });
            parent.Controls.Add(card);
            return card;
        }

        private Button MakeButton(string text, int left, int top, int width, int height)
        {
            Button button = new Button { Text = text, FlatStyle = FlatStyle.Flat,
                BackColor = SurfaceRaised, ForeColor = Color.White, Cursor = Cursors.Hand };
            button.FlatAppearance.BorderColor = Color.FromArgb(108, 116, 140);
            button.FlatAppearance.MouseOverBackColor = Color.FromArgb(54, 63, 84);
            button.SetBounds(left, top, width, height);
            return button;
        }

        private Button MakeLocalizedButton(string english, string polish, int left,
                                           int top, int width, int height)
        {
            return Localize(MakeButton(T(english, polish), left, top, width, height), english, polish);
        }

        private void ConfigureNavigationButton(Button button, int left, string english, string polish)
        {
            Localize(button, english, polish);
            button.SetBounds(left, 16, 146, 42);
            button.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            button.FlatStyle = FlatStyle.Flat;
            button.BackColor = SurfaceRaised;
            button.ForeColor = Color.White;
            button.Cursor = Cursors.Hand;
            button.FlatAppearance.BorderColor = Color.FromArgb(91, 101, 124);
            button.FlatAppearance.MouseOverBackColor = Color.FromArgb(56, 65, 87);
        }

        private Label AddLocalizedLabel(Control parent, string english, string polish,
            float size, FontStyle style, int left, int top, int width, int height)
        {
            return Localize(AddLabel(parent, T(english, polish), size, style,
                left, top, width, height), english, polish);
        }

        private Label AddLabel(Control parent, string text, float size, FontStyle style,
                               int left, int top, int width, int height)
        {
            Label label = new Label { Text = text, Font = new Font("Segoe UI", size, style),
                ForeColor = Color.White, AutoEllipsis = false, UseMnemonic = false };
            label.SetBounds(left, top, width, height);
            parent.Controls.Add(label);
            return label;
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
