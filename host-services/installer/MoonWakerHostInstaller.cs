using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using Microsoft.Win32;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

[assembly: AssemblyVersion("0.7.62.0")]
[assembly: AssemblyFileVersion("0.7.62.0")]
[assembly: AssemblyInformationalVersion("0.7.62+2026.09.03")]

namespace MoonWaker.HostInstaller
{
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
        private readonly TextBox profileId = new TextBox();
        private readonly TextBox profileName = new TextBox();
        private readonly CheckBox discord = new CheckBox();
        private readonly Panel discordCard;
        private readonly Panel discordCredentials = new Panel();
        private readonly TextBox discordId = new TextBox();
        private readonly TextBox discordSecret = new TextBox();
        private readonly TextBox vibepolloUrl = new TextBox();
        private readonly TextBox vibepolloToken = new TextBox();
        private readonly CheckBox createVibepolloToken = new CheckBox();
        private readonly TextBox vibepolloAdmin = new TextBox();
        private readonly TextBox vibepolloPassword = new TextBox();
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
        private bool tokenChoiceInitialized;
        private bool discordChoiceInitialized;
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
            discordCard = BuildDiscordCard(pages[3]);
            BuildProgressPage();

            installPath.Text = DetectInstallDirectory();
            profileId.Text = "default";
            profileName.Text = Environment.UserName;
            vibepolloUrl.Text = "https://127.0.0.1:47990";
            installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());

            installPath.TextChanged += delegate {
                installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
                RefreshProfileDefaults();
                RefreshInstallationStatus();
            };
            profileId.TextChanged += delegate {
                RefreshProfileDefaults();
                RefreshInstallationStatus();
            };
            createVibepolloToken.CheckedChanged += delegate {
                UpdateTokenFields();
                RefreshInstallationStatus();
            };
            discord.CheckedChanged += delegate {
                UpdateDiscordFields();
                RefreshInstallationStatus();
            };
            FormClosed += delegate {
                discordSecret.Clear();
                vibepolloToken.Clear();
                vibepolloPassword.Clear();
            };

            ApplyLanguage(this);
            UpdateTokenFields();
            UpdateDiscordFields();
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
            Panel page = CreatePage(2, 670);
            AddPageHeading(page, "Set up Vibepollo", "Skonfiguruj Vibepollo",
                "MoonWaker can install the official signed release and create a limited API token.",
                "MoonWaker może zainstalować oficjalne podpisane wydanie i utworzyć token API z ograniczonymi uprawnieniami.");
            Panel statusCard = MakeCard(page, 112, 82, Color.FromArgb(255, 166, 76));
            vibepolloSetupStatus.SetBounds(24, 15, 650, 25);
            vibepolloSetupStatus.Font = new Font("Segoe UI", 10.5F, FontStyle.Bold);
            statusCard.Controls.Add(vibepolloSetupStatus);
            vibepolloSetupDetails.SetBounds(24, 45, 650, 24);
            vibepolloSetupDetails.ForeColor = Muted;
            statusCard.Controls.Add(vibepolloSetupDetails);
            Panel tokenCard = MakeCard(page, 208, 362, Accent);
            AddLocalizedLabel(tokenCard, "Secure API access", "Bezpieczny dostęp API",
                13F, FontStyle.Bold, 24, 16, 500, 28);
            ConfigureCheckBox(createVibepolloToken,
                "Create or renew a MoonWaker token automatically",
                "Utwórz lub odnów token MoonWaker automatycznie", 24, 52);
            tokenCard.Controls.Add(createVibepolloToken);
            AddLocalizedLabel(tokenCard, "Administrator username", "Login administratora",
                9F, FontStyle.Regular, 24, 93, 290, 22);
            AddLocalizedLabel(tokenCard, "Administrator password", "Hasło administratora",
                9F, FontStyle.Regular, 358, 93, 290, 22);
            ConfigureTextBox(vibepolloAdmin, 24, 117, 314, false);
            ConfigureTextBox(vibepolloPassword, 358, 117, 318, true);
            tokenCard.Controls.Add(vibepolloAdmin);
            tokenCard.Controls.Add(vibepolloPassword);
            Label credentialNote = AddLocalizedLabel(tokenCard,
                "For a new installation these become the Vibepollo Web UI credentials. Existing installations only use them to request the scoped token.",
                "Przy nowej instalacji będą to dane do panelu WWW Vibepollo. W istniejącej instalacji służą tylko do pobrania ograniczonego tokenu.",
                8.7F, FontStyle.Regular, 24, 157, 650, 44);
            credentialNote.ForeColor = Muted;
            AddLocalizedLabel(tokenCard, "Existing API token (alternative)",
                "Istniejący token API (alternatywa)", 9F, FontStyle.Regular,
                24, 213, 330, 22);
            ConfigureTextBox(vibepolloToken, 24, 237, 652, true);
            tokenCard.Controls.Add(vibepolloToken);
            Label tokenNote = AddLocalizedLabel(tokenCard,
                "Leave this empty to preserve a token already stored for the selected MoonWaker profile.",
                "Pozostaw puste, aby zachować token zapisany wcześniej dla wybranego profilu MoonWaker.",
                8.7F, FontStyle.Regular, 24, 275, 650, 25);
            tokenNote.ForeColor = Muted;
            AddLocalizedLabel(tokenCard, "Local API address", "Adres lokalnego API",
                9F, FontStyle.Regular, 24, 304, 260, 22);
            ConfigureTextBox(vibepolloUrl, 358, 300, 318, false);
            tokenCard.Controls.Add(vibepolloUrl);
            Label safety = AddLocalizedLabel(page,
                "Credentials and tokens are never written to the installation log. Stored Bridge tokens are protected with Windows DPAPI.",
                "Dane logowania i tokeny nigdy nie trafiają do dziennika instalacji. Zapisane tokeny Bridge chroni mechanizm Windows DPAPI.",
                8.8F, FontStyle.Regular, 34, 594, 680, 44);
            safety.ForeColor = Color.FromArgb(133, 143, 162);
        }

        private void BuildOptionsPage()
        {
            Panel page = CreatePage(3, 690);
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
            Panel profileCard = MakeCard(page, 334, 142, Color.FromArgb(68, 198, 142));
            AddLocalizedLabel(profileCard, "Windows profile", "Profil Windows",
                13F, FontStyle.Bold, 24, 14, 400, 28);
            AddLocalizedLabel(profileCard, "Profile ID", "Identyfikator profilu",
                9F, FontStyle.Regular, 24, 50, 270, 22);
            AddLocalizedLabel(profileCard, "Display name", "Nazwa wyświetlana",
                9F, FontStyle.Regular, 274, 50, 300, 22);
            ConfigureTextBox(profileId, 24, 74, 230, false);
            ConfigureTextBox(profileName, 274, 74, 402, false);
            profileCard.Controls.Add(profileId);
            profileCard.Controls.Add(profileName);
        }

        private Panel BuildDiscordCard(Panel page)
        {
            Panel card = MakeCard(page, 490, 176, Color.FromArgb(88, 101, 242));
            ConfigureCheckBox(discord, "Enable optional Discord integration",
                "Włącz opcjonalną integrację Discord", 24, 13);
            discord.Font = new Font("Segoe UI", 11.5F, FontStyle.Bold);
            card.Controls.Add(discord);
            discordCredentials.SetBounds(24, 52, 652, 105);
            Label info = AddLocalizedLabel(discordCredentials,
                "Leave both fields empty to reuse this computer's shared Discord application.",
                "Pozostaw oba pola puste, aby użyć wspólnej aplikacji Discord tego komputera.",
                8.8F, FontStyle.Regular, 0, 0, 650, 23);
            info.ForeColor = Muted;
            AddLabel(discordCredentials, "Client ID", 9F, FontStyle.Regular, 0, 30, 270, 22);
            AddLabel(discordCredentials, "Client Secret", 9F, FontStyle.Regular, 300, 30, 300, 22);
            ConfigureTextBox(discordId, 0, 53, 280, false);
            ConfigureTextBox(discordSecret, 300, 53, 352, true);
            discordCredentials.Controls.Add(discordId);
            discordCredentials.Controls.Add(discordSecret);
            card.Controls.Add(discordCredentials);
            return card;
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
                string error = ValidateVibepolloInput();
                if (error != null) { ShowWarning(error); return; }
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
                    T("Up to date — the selected Windows profile will be refreshed",
                      "Aktualne — wybrany profil Windows zostanie odświeżony"), Good);
            RefreshProfileDefaults();
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
            string script =
                "$ErrorActionPreference='SilentlyContinue';" +
                "$programmable=@(& powercfg.exe /devicequery wake_programmable|%{$_.Trim()}|?{$_});" +
                "$armed=@(& powercfg.exe /devicequery wake_armed|%{$_.Trim()}|?{$_});" +
                "$adapters=@(Get-NetAdapter -Physical|?{$_.HardwareInterface -and [int]$_.NdisPhysicalMedium -eq 14 -and $_.Status -ne 'Disabled'});" +
                "$found=$false;foreach($a in $adapters){$d=[string]$a.InterfaceDescription;" +
                "if($programmable -contains $d -or $programmable -contains [string]$a.Name){$found=$true;" +
                "$state=if($armed -contains $d -or $armed -contains [string]$a.Name){'armed'}else{'supported'};" +
                "$name=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$a.Name));" +
                "Write-Output ('MW_WOL|'+$state+'|'+$name)}};" +
                "if(-not $found){Write-Output 'MW_WOL|unavailable|'}";
            string encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            string output = RunHiddenProcess("powershell.exe",
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
            if (!PrepareHostControlUpdate()) return;
            installationCompleted = false;
            installationFailed = false;
            ShowPage(4);
            next.Enabled = false;
            back.Visible = false;
            progress.Value = 4;
            progressTitle.Text = T("Installing MoonWaker Host", "Instalowanie MoonWaker Host");
            progressDetail.Text = T(
                "Keep this window open. Windows may ask once for administrator approval.",
                "Pozostaw to okno otwarte. Windows może raz poprosić o zgodę administratora.");
            log.Text = T("Preparing the installation…\r\n", "Przygotowywanie instalacji…\r\n");
            try
            {
                string output = await Task.Run<string>(() => RunInstaller());
                log.AppendText(output);
                progress.Value = 100;
                progressTitle.Text = T("Host is ready", "Host jest gotowy");
                progressDetail.Text = T(
                    "MoonWaker components, Vibepollo access and the selected Windows profile are configured.",
                    "Komponenty MoonWaker, dostęp do Vibepollo i wybrany profil Windows są skonfigurowane.");
                installationCompleted = true;
                discordSecret.Clear(); vibepolloToken.Clear(); vibepolloPassword.Clear();
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

        private string RunInstaller()
        {
            string temporary = Path.Combine(Path.GetTempPath(),
                "moonwaker-host-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(temporary);
            try
            {
                ReportProgress(10, T("Unpacking verified host components…",
                    "Rozpakowywanie zweryfikowanych komponentów hosta…"));
                ExtractPayload(temporary);
                string package = Path.Combine(temporary, "host-services");
                string script = Path.Combine(package, "install", "Install-MoonWakerHostBundle.ps1");
                string hostScript = Path.Combine(package, "install", "Install-WakePlayHost.ps1");
                string machineWrapper = Path.Combine(package, "install", "Invoke-MoonWakerMachineInstall.ps1");
                string prerequisiteScript = Path.Combine(package, "install", "Prepare-MoonWakerHost.ps1");
                if (!File.Exists(script)) throw new InvalidOperationException(T(
                    "The embedded host package is incomplete.", "Osadzony pakiet hosta jest niekompletny."));
                bool ensureVibepollo = !vibepolloInstalled;
                bool enableWakeOnLan = wakeOnLan.Supported && !wakeOnLan.Armed;
                StringBuilder combinedOutput = new StringBuilder();
                if (installMachine.Checked || ensureVibepollo || enableWakeOnLan)
                {
                    ReportProgress(28, ensureVibepollo
                        ? T("Downloading and configuring Vibepollo…", "Pobieranie i konfigurowanie Vibepollo…")
                        : T("Configuring Windows host services…", "Konfigurowanie usług hosta Windows…"));
                    string credentialPath = ensureVibepollo
                        ? WriteProtectedVibepolloCredentials(temporary) : "";
                    combinedOutput.Append(RunMachineInstall(hostScript, machineWrapper,
                        prerequisiteScript, Path.GetFullPath(installPath.Text.Trim()), temporary,
                        credentialPath, installMachine.Checked, enableWakeOnLan, ensureVibepollo));
                }
                ReportProgress(62, T("Creating the MoonWaker integration profile…",
                    "Tworzenie profilu integracji MoonWaker…"));
                EnsureSharedDiscordAccess(temporary);
                List<string> args = new List<string>();
                args.Add("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(script));
                args.Add("-InstallDirectory " + Quote(Path.GetFullPath(installPath.Text.Trim())));
                args.Add("-ProfileId " + Quote(profileId.Text.Trim()));
                args.Add("-ProfileName " + Quote(profileName.Text.Trim()));
                args.Add("-ProfileOnly");
                if (installMachine.Checked) args.Add("-InitializeMachineData");
                if (!discord.Checked) args.Add("-SkipDiscord");
                ProcessStartInfo info = new ProcessStartInfo("powershell.exe", String.Join(" ", args.ToArray()));
                info.UseShellExecute = false; info.CreateNoWindow = true;
                info.RedirectStandardOutput = true; info.RedirectStandardError = true;
                info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
                info.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_ID"] =
                    discord.Checked ? discordId.Text.Trim() : "";
                info.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_SECRET"] =
                    discord.Checked ? discordSecret.Text : "";
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_URL"] = vibepolloUrl.Text.Trim();
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_TOKEN"] = vibepolloToken.Text;
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CREATE_TOKEN"] =
                    createVibepolloToken.Checked ? "1" : "0";
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_USERNAME"] = vibepolloAdmin.Text.Trim();
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD"] = vibepolloPassword.Text;
                using (Process process = Process.Start(info))
                {
                    Task<string> stdoutTask = process.StandardOutput.ReadToEndAsync();
                    Task<string> stderrTask = process.StandardError.ReadToEndAsync();
                    process.WaitForExit();
                    bool streamsClosed = Task.WaitAll(new Task[] { stdoutTask, stderrTask }, 2000);
                    string stdout = stdoutTask.IsCompleted ? stdoutTask.Result :
                        T("Profile services started in the background.\r\n",
                          "Usługi profilu uruchomiono w tle.\r\n");
                    string stderr = stderrTask.IsCompleted ? stderrTask.Result : "";
                    if (process.ExitCode != 0) throw new InvalidOperationException(
                        String.IsNullOrWhiteSpace(stderr) ? stdout : stderr);
                    combinedOutput.Append(stdout);
                    if (!streamsClosed) combinedOutput.Append(T(
                        "Installer log streams were detached after profile services started.\r\n",
                        "Strumienie dziennika odłączono po uruchomieniu usług profilu.\r\n"));
                }
                ReportProgress(92, T("Verifying Gateway and profile services…",
                    "Weryfikowanie Gatewaya i usług profilu…"));
                return combinedOutput.ToString();
            }
            finally { try { Directory.Delete(temporary, true); } catch { } }
        }

        private string WriteProtectedVibepolloCredentials(string temporary)
        {
            byte[] passwordBytes = Encoding.UTF8.GetBytes(vibepolloPassword.Text);
            byte[] protectedBytes = null;
            try
            {
                protectedBytes = ProtectedData.Protect(passwordBytes, null, DataProtectionScope.CurrentUser);
                string path = Path.Combine(temporary, "vibepollo-credentials.dpapi");
                File.WriteAllLines(path, new[] {
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(vibepolloAdmin.Text.Trim())),
                    Convert.ToBase64String(protectedBytes)
                }, Encoding.ASCII);
                return path;
            }
            finally
            {
                Array.Clear(passwordBytes, 0, passwordBytes.Length);
                if (protectedBytes != null) Array.Clear(protectedBytes, 0, protectedBytes.Length);
            }
        }

        private static string RunMachineInstall(string hostScript, string wrapper,
            string prerequisiteScript, string directory, string temporaryDirectory,
            string credentialPath, bool installHost, bool enableWakeOnLan, bool ensureVibepollo)
        {
            if (!File.Exists(wrapper)) throw new InvalidOperationException(
                "The machine installation module is missing.");
            string resultPath = Path.Combine(temporaryDirectory, "machine-install-result.txt");
            string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(wrapper) +
                " -HostInstallScript " + Quote(hostScript) +
                " -PrerequisiteScript " + Quote(prerequisiteScript) +
                " -InstallDirectory " + Quote(directory) +
                " -GatewayDirectory " + Quote(Path.Combine(directory, "gateway")) +
                " -ResultPath " + Quote(resultPath);
            if (!String.IsNullOrWhiteSpace(credentialPath))
                arguments += " -VibepolloCredentialPath " + Quote(credentialPath);
            if (!installHost) arguments += " -SkipMoonWakerHost";
            if (enableWakeOnLan) arguments += " -EnableWakeOnLan";
            if (ensureVibepollo) arguments += " -EnsureVibepollo";
            ProcessStartInfo info = new ProcessStartInfo("powershell.exe", arguments);
            info.UseShellExecute = true; info.Verb = "runas";
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

        private bool PrepareHostControlUpdate()
        {
            restartHostControl = false;
            if (!installMachine.Checked) return true;
            string executable = Path.GetFullPath(Path.Combine(
                installPath.Text.Trim(), "control", "MoonWakerHostControl.exe"));
            List<Process> running = new List<Process>();
            foreach (Process process in Process.GetProcessesByName("MoonWakerHostControl"))
            {
                try
                {
                    if (String.Equals(process.MainModule.FileName, executable,
                            StringComparison.OrdinalIgnoreCase)) running.Add(process);
                    else process.Dispose();
                }
                catch { process.Dispose(); }
            }
            if (running.Count == 0) return true;
            DialogResult answer = MessageBox.Show(this, T(
                "MoonWaker Host Control is running and must close for the update.\n\nClose it now and reopen it after installation?",
                "MoonWaker Host Control jest uruchomiony i musi zostać zamknięty na czas aktualizacji.\n\nZamknąć go teraz i uruchomić ponownie po instalacji?"),
                T("Update MoonWaker Host Control", "Aktualizacja MoonWaker Host Control"),
                MessageBoxButtons.YesNo, MessageBoxIcon.Information);
            if (answer != DialogResult.Yes)
            {
                foreach (Process process in running) process.Dispose();
                return false;
            }
            try
            {
                restartHostControl = true;
                foreach (Process process in running)
                {
                    using (process)
                    {
                        ProcessStartInfo killInfo = new ProcessStartInfo(
                            "taskkill.exe", "/PID " + process.Id + " /T /F");
                        killInfo.UseShellExecute = false; killInfo.CreateNoWindow = true;
                        using (Process killer = Process.Start(killInfo))
                        {
                            if (!killer.WaitForExit(10000))
                            {
                                try { killer.Kill(); } catch { }
                                throw new InvalidOperationException(T(
                                    "MoonWaker Host Control did not close in time.",
                                    "MoonWaker Host Control nie zamknął się na czas."));
                            }
                        }
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

        private void EnsureSharedDiscordAccess(string temporary)
        {
            if (installMachine.Checked || !discord.Checked ||
                    !String.IsNullOrWhiteSpace(discordId.Text) ||
                    !String.IsNullOrWhiteSpace(discordSecret.Text)) return;
            string directory = Path.GetFullPath(installPath.Text.Trim());
            string application = Path.Combine(directory, "machine-data", "discord-app.json");
            string secret = Path.Combine(directory, "machine-data", "discord-app-secret.dpapi");
            if (!File.Exists(application) || !File.Exists(secret)) throw new InvalidOperationException(T(
                "Shared Discord application data was not found. Enter Client ID and Client Secret, or disable Discord integration.",
                "Nie znaleziono wspólnych danych aplikacji Discord. Podaj Client ID i Client Secret albo wyłącz integrację Discord."));
            if (CanRead(application) && CanRead(secret)) return;
            string helper = Path.Combine(temporary, "host-services", "install",
                "Grant-MoonWakerMachineDiscordAccess.ps1");
            if (!File.Exists(helper)) throw new InvalidOperationException("The Discord access helper is missing.");
            string sid = WindowsIdentity.GetCurrent().User.Value;
            string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(helper) +
                " -InstallDirectory " + Quote(directory) + " -UserSid " + Quote(sid);
            ProcessStartInfo info = new ProcessStartInfo("powershell.exe", arguments);
            info.UseShellExecute = true; info.Verb = "runas";
            using (Process process = Process.Start(info))
            {
                process.WaitForExit();
                if (process.ExitCode != 0) throw new InvalidOperationException(T(
                    "Could not grant this profile access to shared Discord data.",
                    "Nie udało się przyznać temu profilowi dostępu do wspólnych danych Discord."));
            }
        }

        private static bool CanRead(string path)
        {
            try { using (File.Open(path, FileMode.Open, FileAccess.Read, FileShare.Read)) { return true; } }
            catch { return false; }
        }

        private string ValidateVibepolloInput()
        {
            Uri uri;
            if (!Uri.TryCreate(vibepolloUrl.Text.Trim(), UriKind.Absolute, out uri) ||
                uri.Scheme != Uri.UriSchemeHttps ||
                !(uri.Host == "127.0.0.1" || uri.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase)))
                return T("Vibepollo API must use HTTPS on 127.0.0.1 or localhost.",
                    "API Vibepollo musi używać HTTPS na 127.0.0.1 lub localhost.");
            bool profileHasToken = ProfileHasVibepolloToken();
            if (!vibepolloInstalled && !createVibepolloToken.Checked)
                return T("A new Vibepollo installation needs an administrator username and password.",
                    "Nowa instalacja Vibepollo wymaga loginu i hasła administratora.");
            if (createVibepolloToken.Checked &&
                    (String.IsNullOrWhiteSpace(vibepolloAdmin.Text) ||
                     String.IsNullOrWhiteSpace(vibepolloPassword.Text)))
                return T("Enter the Vibepollo administrator username and password to create the API token.",
                    "Podaj login i hasło administratora Vibepollo, aby utworzyć token API.");
            if (!createVibepolloToken.Checked && String.IsNullOrWhiteSpace(vibepolloToken.Text) &&
                    !profileHasToken)
                return T("Create a token automatically or enter an existing Vibepollo API token.",
                    "Utwórz token automatycznie albo podaj istniejący token API Vibepollo.");
            return null;
        }

        private string ValidateInput()
        {
            string vibepolloError = ValidateVibepolloInput();
            if (vibepolloError != null) return vibepolloError;
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
            if (!System.Text.RegularExpressions.Regex.IsMatch(
                    profileId.Text.Trim(), "^[A-Za-z0-9._-]{1,64}$"))
                return T("The profile ID may contain letters, digits, dots, underscores and hyphens.",
                    "Identyfikator profilu może zawierać litery, cyfry, kropki, podkreślenia i łączniki.");
            if (discord.Checked && (String.IsNullOrWhiteSpace(discordId.Text) !=
                    String.IsNullOrWhiteSpace(discordSecret.Text)))
                return T("Enter both Discord fields or leave both empty.",
                    "Podaj oba pola Discord albo pozostaw oba puste.");
            return null;
        }

        private void RefreshProfileDefaults()
        {
            bool hasToken = ProfileHasVibepolloToken();
            if (!tokenChoiceInitialized)
            {
                createVibepolloToken.Checked = !hasToken || !vibepolloInstalled;
                tokenChoiceInitialized = true;
            }
            if (!discordChoiceInitialized)
            {
                string root = installPath.Text.Trim();
                string id = profileId.Text.Trim();
                discord.Checked = !String.IsNullOrWhiteSpace(root) &&
                    !String.IsNullOrWhiteSpace(id) && File.Exists(Path.Combine(
                        root, "profiles", id, "discord", "discord_bridge_config.json"));
                discordChoiceInitialized = true;
            }
            UpdateTokenFields(); UpdateDiscordFields();
        }

        private bool ProfileHasVibepolloToken()
        {
            string root = installPath.Text.Trim();
            string id = profileId.Text.Trim();
            return !String.IsNullOrWhiteSpace(root) && !String.IsNullOrWhiteSpace(id) &&
                File.Exists(Path.Combine(root, "profiles", id, "vibepollo", "api_token.dpapi"));
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
                    "New installation. Shared host components, Gateway and this Windows profile will be created.",
                    "Nowa instalacja. Zostaną utworzone wspólne komponenty hosta, Gateway i ten profil Windows.");
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
                    "Shared components are up to date. The selected Windows profile will be installed or refreshed.",
                    "Wspólne komponenty są aktualne. Wybrany profil Windows zostanie zainstalowany lub odświeżony.");
            }
        }

        private void UpdateTokenFields()
        {
            vibepolloAdmin.Enabled = createVibepolloToken.Checked;
            vibepolloPassword.Enabled = createVibepolloToken.Checked;
            vibepolloToken.Enabled = !createVibepolloToken.Checked;
        }

        private void UpdateDiscordFields()
        {
            discordCredentials.Visible = discord.Checked;
            discordCredentials.Enabled = discord.Checked;
            if (discordCard != null)
            {
                discordCard.Height = discord.Checked ? 176 : 70;
                pages[3].AutoScrollMinSize = new Size(0, discord.Checked ? 690 : 590);
            }
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

        private static void ExtractPayload(string destination)
        {
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
            return @"C:\Tools\WakePlayHost";
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
