using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using Microsoft.Win32;
using System.Reflection;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

[assembly: AssemblyVersion("0.7.60.0")]
[assembly: AssemblyFileVersion("0.7.60.0")]
[assembly: AssemblyInformationalVersion("0.7.60+2026.09.02")]

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
        private readonly TextBox installPath = new TextBox();
        private readonly TextBox profileId = new TextBox();
        private readonly TextBox profileName = new TextBox();
        private readonly CheckBox discord = new CheckBox();
        private readonly Panel discordCard;
        private readonly Panel discordCredentials;
        private readonly TextBox discordId = new TextBox();
        private readonly TextBox discordSecret = new TextBox();
        private readonly TextBox vibepolloUrl = new TextBox();
        private readonly TextBox vibepolloToken = new TextBox();
        private readonly CheckBox createVibepolloToken = new CheckBox();
        private readonly TextBox vibepolloAdmin = new TextBox();
        private readonly TextBox vibepolloPassword = new TextBox();
        private readonly CheckBox installMachine = new CheckBox();
        private readonly Label installationStatus = new Label();
        private readonly Button install = new Button();
        private readonly RichTextBox log = new RichTextBox();
        private bool restartHostControl;
        private readonly string payloadVersion;

        internal InstallerForm()
        {
            payloadVersion = ReadPayloadVersion();
            Text = "MoonWaker Host Installer " + payloadVersion;
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(980, 780);
            MinimumSize = new Size(990, 700);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(13, 16, 23);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);
            DoubleBuffered = true;
            try { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            FlowLayoutPanel content = new FlowLayoutPanel {
                Dock = DockStyle.Fill,
                AutoScroll = true,
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                Padding = new Padding(24, 22, 24, 28),
                BackColor = BackColor
            };
            Controls.Add(content);

            Panel header = MakeCard(112, Color.FromArgb(116, 100, 255));
            header.BackColor = Color.FromArgb(24, 29, 44);
            AddLabel(header, "MOONWAKER HOST", 27F, FontStyle.Bold, 24, 18, 620, 44);
            Label version = AddLabel(header, payloadVersion, 11F, FontStyle.Bold, 720, 26, 150, 28);
            version.TextAlign = ContentAlignment.MiddleRight;
            version.ForeColor = Color.FromArgb(180, 171, 255);
            Label intro = AddLabel(header,
                "Konfiguracja hosta, profilu Windows i integracji MoonWaker.",
                10F, FontStyle.Regular, 26, 70, 830, 26);
            intro.ForeColor = Color.FromArgb(188, 195, 210);
            content.Controls.Add(header);

            Panel locationCard = MakeCard(276, Color.FromArgb(75, 151, 255));
            AddLabel(locationCard, "Instalacja na hoście", 14F, FontStyle.Bold, 24, 17, 500, 30);
            Label locationInfo = AddLabel(locationCard,
                "Komponenty wspólne i profile zostaną zapisane w jednym katalogu.",
                9F, FontStyle.Regular, 24, 48, 850, 24);
            locationInfo.ForeColor = Color.FromArgb(178, 186, 202);
            AddLabel(locationCard, "Katalog instalacji", 9F, FontStyle.Regular, 24, 78, 280, 22);
            ConfigureTextBox(installPath, 24, 100, 680, false);
            installPath.Text = DetectInstallDirectory();
            locationCard.Controls.Add(installPath);
            Button browse = MakeButton("Wybierz…", 720, 100, 156, 36);
            browse.Click += delegate { BrowseInstallDirectory(); };
            locationCard.Controls.Add(browse);
            ConfigureCheckBox(installMachine,
                "Zainstaluj lub zaktualizuj komponenty wspólne (wymaga administratora)",
                24, 144);
            installMachine.Width = 850;
            installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
            locationCard.Controls.Add(installMachine);
            installationStatus.SetBounds(24, 176, 852, 86);
            installationStatus.ForeColor = Color.FromArgb(188, 195, 210);
            installationStatus.AutoEllipsis = false;
            installationStatus.UseMnemonic = false;
            locationCard.Controls.Add(installationStatus);
            content.Controls.Add(locationCard);

            Panel profileCard = MakeCard(140, Color.FromArgb(68, 198, 142));
            AddLabel(profileCard, "Profil integracji", 14F, FontStyle.Bold, 24, 17, 500, 30);
            AddLabel(profileCard, "Identyfikator", 9F, FontStyle.Regular, 24, 57, 260, 22);
            AddLabel(profileCard, "Nazwa profilu", 9F, FontStyle.Regular, 324, 57, 300, 22);
            ConfigureTextBox(profileId, 24, 79, 280, false); profileId.Text = "default";
            ConfigureTextBox(profileName, 324, 79, 552, false); profileName.Text = Environment.UserName;
            profileCard.Controls.Add(profileId); profileCard.Controls.Add(profileName);
            content.Controls.Add(profileCard);

            discordCard = MakeCard(168, Color.FromArgb(88, 101, 242));
            ConfigureCheckBox(discord, "Discord Bridge (opcjonalnie)", 24, 16);
            discord.Font = new Font("Segoe UI", 13F, FontStyle.Bold);
            discord.Width = 620;
            discord.Checked = true;
            discordCard.Controls.Add(discord);
            discordCredentials = new Panel();
            discordCredentials.SetBounds(24, 55, 852, 100);
            Label discordInfo = AddLabel(discordCredentials,
                "Pola możesz pozostawić puste, aby użyć wspólnej aplikacji Discord tego komputera.",
                9F, FontStyle.Regular, 0, 0, 850, 24);
            discordInfo.ForeColor = Color.FromArgb(178, 186, 202);
            AddLabel(discordCredentials, "Client ID", 9F, FontStyle.Regular, 0, 29, 280, 22);
            AddLabel(discordCredentials, "Client Secret", 9F, FontStyle.Regular, 320, 29, 300, 22);
            ConfigureTextBox(discordId, 0, 51, 300, false);
            ConfigureTextBox(discordSecret, 320, 51, 532, true);
            discordCredentials.Controls.Add(discordId);
            discordCredentials.Controls.Add(discordSecret);
            discordCard.Controls.Add(discordCredentials);
            content.Controls.Add(discordCard);

            Panel vibepolloCard = MakeCard(250, Color.FromArgb(255, 166, 76));
            AddLabel(vibepolloCard, "Vibepollo", 14F, FontStyle.Bold, 24, 17, 500, 30);
            Label vibepolloInfo = AddLabel(vibepolloCard,
                "Wymagane połączenie z lokalnym API Sunshine/Vibepollo.",
                9F, FontStyle.Regular, 24, 48, 850, 24);
            vibepolloInfo.ForeColor = Color.FromArgb(178, 186, 202);
            AddLabel(vibepolloCard, "API URL", 9F, FontStyle.Regular, 24, 77, 280, 22);
            AddLabel(vibepolloCard, "Istniejący token", 9F, FontStyle.Regular, 424, 77, 300, 22);
            ConfigureTextBox(vibepolloUrl, 24, 99, 380, false); vibepolloUrl.Text = "https://127.0.0.1:47990";
            ConfigureTextBox(vibepolloToken, 424, 99, 452, true);
            vibepolloCard.Controls.Add(vibepolloUrl); vibepolloCard.Controls.Add(vibepolloToken);
            ConfigureCheckBox(createVibepolloToken, "Utwórz lub odnów token automatycznie", 24, 141);
            vibepolloCard.Controls.Add(createVibepolloToken);
            createVibepolloToken.CheckedChanged += delegate {
                UpdateTokenFields();
                RefreshInstallationStatus();
            };
            AddLabel(vibepolloCard, "Login administratora", 9F, FontStyle.Regular, 24, 177, 300, 22);
            AddLabel(vibepolloCard, "Hasło", 9F, FontStyle.Regular, 424, 177, 200, 22);
            ConfigureTextBox(vibepolloAdmin, 24, 199, 380, false);
            ConfigureTextBox(vibepolloPassword, 424, 199, 452, true);
            vibepolloCard.Controls.Add(vibepolloAdmin); vibepolloCard.Controls.Add(vibepolloPassword);
            content.Controls.Add(vibepolloCard);

            Panel actionCard = MakeCard(300, Color.FromArgb(116, 100, 255));
            install = MakeButton("Zainstaluj / aktualizuj", 24, 20, 270, 44);
            install.BackColor = Color.FromArgb(116, 100, 255);
            install.Click += async delegate { await InstallAsync(); };
            actionCard.Controls.Add(install);
            AddLabel(actionCard, "Przebieg instalacji", 9F, FontStyle.Bold, 24, 78, 300, 22);
            log.SetBounds(24, 104, 852, 172);
            log.ReadOnly = true; log.BackColor = Color.FromArgb(11, 14, 20);
            log.ForeColor = Color.FromArgb(204, 210, 222);
            log.BorderStyle = BorderStyle.FixedSingle;
            log.Font = new Font("Consolas", 9F);
            log.WordWrap = false;
            log.ScrollBars = RichTextBoxScrollBars.Both;
            actionCard.Controls.Add(log);
            content.Controls.Add(actionCard);
            AcceptButton = install;

            installPath.TextChanged += delegate {
                installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
                RefreshInstallationStatus();
            };
            profileId.TextChanged += delegate { RefreshInstallationStatus(); };
            installMachine.CheckedChanged += delegate { RefreshInstallationStatus(); };
            discord.CheckedChanged += delegate {
                UpdateDiscordFields();
                RefreshInstallationStatus();
            };
            installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
            UpdateDiscordFields();
            UpdateTokenFields();
            RefreshInstallationStatus();
        }

        private async Task InstallAsync()
        {
            string validation = ValidateInput();
            if (validation != null) { MessageBox.Show(this, validation, "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }
            if (!PrepareHostControlUpdate()) return;
            install.Enabled = false;
            log.Text = "Przygotowywanie instalacji…\r\n";
            try
            {
                string output = await Task.Run<string>(() => RunInstaller());
                log.AppendText(output);
                MessageBox.Show(this, "Instalacja zakończona. Wszystkie komponenty znajdują się w:\n" +
                    Path.GetFullPath(installPath.Text.Trim()), "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception error)
            {
                log.AppendText("\r\nBŁĄD: " + error.Message);
                MessageBox.Show(this, error.Message, "Instalacja nie powiodła się", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                discordSecret.Clear(); vibepolloToken.Clear(); vibepolloPassword.Clear();
                RestartHostControlIfNeeded();
                install.Enabled = true;
            }
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
                            StringComparison.OrdinalIgnoreCase))
                        running.Add(process);
                    else
                        process.Dispose();
                }
                catch { process.Dispose(); }
            }
            if (running.Count == 0) return true;

            DialogResult answer = MessageBox.Show(this,
                "MoonWaker Host Control jest uruchomiony i blokuje aktualizację.\n\n" +
                "Zamknąć go automatycznie i uruchomić ponownie po instalacji?",
                "Aktualizacja MoonWaker Host Control",
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
                        killInfo.UseShellExecute = false;
                        killInfo.CreateNoWindow = true;
                        using (Process killer = Process.Start(killInfo))
                        {
                            if (!killer.WaitForExit(10000))
                            {
                                try { killer.Kill(); } catch { }
                                throw new InvalidOperationException(
                                    "Nie udało się zamknąć drzewa procesów MoonWaker Host Control.");
                            }
                        }
                        if (!process.WaitForExit(5000))
                            throw new InvalidOperationException(
                                "Nie udało się zamknąć MoonWaker Host Control.");
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
            try
            {
                Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true });
            }
            catch (Exception error)
            {
                log.AppendText("\r\nNie udało się ponownie uruchomić Host Control: " +
                    error.Message);
            }
        }

        private string RunInstaller()
        {
            string temporary = Path.Combine(Path.GetTempPath(), "moonwaker-host-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(temporary);
            try
            {
                ExtractPayload(temporary);
                string script = Path.Combine(temporary, "host-services", "install", "Install-MoonWakerHostBundle.ps1");
                if (!File.Exists(script)) throw new InvalidOperationException("Pakiet instalacyjny jest niekompletny.");
                if (installMachine.Checked)
                {
                    string hostScript = Path.Combine(temporary, "host-services", "install", "Install-WakePlayHost.ps1");
                    string machineWrapper = Path.Combine(temporary, "host-services", "install",
                            "Invoke-MoonWakerMachineInstall.ps1");
                    RunMachineInstall(hostScript, machineWrapper,
                            Path.GetFullPath(installPath.Text.Trim()), temporary);
                }
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
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CREATE_TOKEN"] = createVibepolloToken.Checked ? "1" : "0";
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_USERNAME"] = vibepolloAdmin.Text.Trim();
                info.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD"] = vibepolloPassword.Text;
                using (Process process = Process.Start(info))
                {
                    Task<string> stdoutTask = process.StandardOutput.ReadToEndAsync();
                    Task<string> stderrTask = process.StandardError.ReadToEndAsync();
                    process.WaitForExit();
                    // Profile supervisors intentionally stay alive. Older builds
                    // inherited these pipes and made ReadToEndAsync wait forever
                    // after the installer script itself had already exited.
                    bool streamsClosed = Task.WaitAll(new Task[] { stdoutTask, stderrTask }, 2000);
                    string stdout = stdoutTask.IsCompleted ? stdoutTask.Result :
                        "Profile services started in the background.\r\n";
                    string stderr = stderrTask.IsCompleted ? stderrTask.Result : "";
                    if (process.ExitCode != 0) throw new InvalidOperationException(
                        String.IsNullOrWhiteSpace(stderr) ? stdout : stderr);
                    return stdout + (streamsClosed ? "" :
                        "Installer log streams were detached after the profile services started.\r\n");
                }
            }
            finally { try { Directory.Delete(temporary, true); } catch { } }
        }

        private static void RunMachineInstall(string script, string wrapper, string directory,
                                              string temporaryDirectory)
        {
            if (!File.Exists(script)) throw new InvalidOperationException("Brak skryptu instalacji komponentów wspólnych.");
            if (!File.Exists(wrapper)) throw new InvalidOperationException("Brak modułu diagnostycznego instalacji komponentów wspólnych.");
            string resultPath = Path.Combine(temporaryDirectory, "machine-install-result.txt");
            string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(wrapper) +
                " -HostInstallScript " + Quote(script) +
                " -InstallDirectory " + Quote(directory) +
                " -GatewayDirectory " + Quote(Path.Combine(directory, "gateway")) +
                " -ResultPath " + Quote(resultPath);
            ProcessStartInfo info = new ProcessStartInfo("powershell.exe", arguments);
            info.UseShellExecute = true;
            info.Verb = "runas";
            using (Process process = Process.Start(info))
            {
                process.WaitForExit();
                if (process.ExitCode != 0)
                {
                    string details = File.Exists(resultPath) ? File.ReadAllText(resultPath).Trim() : "";
                    throw new InvalidOperationException("Instalacja komponentów wspólnych zakończyła się błędem (" +
                        process.ExitCode + ")." + (String.IsNullOrWhiteSpace(details) ? "" : "\r\n\r\n" + details));
                }
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
            if (!File.Exists(application) || !File.Exists(secret)) {
                throw new InvalidOperationException("Nie znaleziono wspólnych danych aplikacji Discord. " +
                    "Podaj własne Client ID i Client Secret albo najpierw zaktualizuj komponenty wspólne.");
            }
            if (CanRead(application) && CanRead(secret)) return;
            string helper = Path.Combine(temporary, "host-services", "install",
                    "Grant-MoonWakerMachineDiscordAccess.ps1");
            if (!File.Exists(helper)) throw new InvalidOperationException(
                    "Pakiet instalacyjny nie zawiera modułu dostępu do wspólnych danych Discord.");
            string sid = WindowsIdentity.GetCurrent().User.Value;
            RunDiscordAccessGrant(helper, directory, sid);
            if (!CanRead(application) || !CanRead(secret)) throw new InvalidOperationException(
                    "Nie udało się przyznać temu profilowi dostępu do wspólnych danych Discord.");
        }

        private static bool CanRead(string path)
        {
            try { using (File.Open(path, FileMode.Open, FileAccess.Read, FileShare.Read)) { return true; } }
            catch { return false; }
        }

        private static void RunDiscordAccessGrant(string script, string directory, string sid)
        {
            string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(script) +
                " -InstallDirectory " + Quote(directory) + " -UserSid " + Quote(sid);
            ProcessStartInfo info = new ProcessStartInfo("powershell.exe", arguments);
            info.UseShellExecute = true;
            info.Verb = "runas";
            using (Process process = Process.Start(info))
            {
                process.WaitForExit();
                if (process.ExitCode != 0) throw new InvalidOperationException(
                    "Nie udało się udostępnić wspólnych danych aplikacji Discord bieżącemu profilowi (" +
                    process.ExitCode + ").");
            }
        }

        private static void ExtractPayload(string destination)
        {
            Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("MoonWaker.HostServices.zip");
            if (resource == null) throw new InvalidOperationException("Brak osadzonego pakietu hosta.");
            string root = Path.GetFullPath(destination) + Path.DirectorySeparatorChar;
            using (resource)
            using (ZipArchive archive = new ZipArchive(resource, ZipArchiveMode.Read))
            {
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    string target = Path.GetFullPath(Path.Combine(destination, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                    if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Nieprawidłowa ścieżka w pakiecie.");
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
                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("MoonWaker.Version.json"))
                using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
                {
                    string text = reader.ReadToEnd();
                    System.Text.RegularExpressions.Match match = System.Text.RegularExpressions.Regex.Match(
                        text, "\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                    if (match.Success) return "v" + match.Groups[1].Value;
                }
            }
            catch { }
            return "v?";
        }

        private string ValidateInput()
        {
            string path = installPath.Text.Trim();
            if (String.IsNullOrWhiteSpace(path) || !Path.IsPathRooted(path)) return "Wybierz bezwzględną ścieżkę instalacji.";
            string root = Path.GetPathRoot(Path.GetFullPath(path));
            if (String.IsNullOrWhiteSpace(root) || !Directory.Exists(root)) return "Wybrany dysk jest obecnie niedostępny.";
            if (!installMachine.Checked && !File.Exists(Path.Combine(Path.GetFullPath(path), "gateway", "gateway.json")))
                return "Najpierw zainstaluj komponenty wspólne albo wybierz istniejącą instalację MoonWaker.";
            if (!installMachine.Checked && SharedComponentsNeedUpdate(path))
                return "Wykryta wersja komponentów wspólnych (" +
                    DisplayInstalledVersion(path) + ") nie odpowiada wersji tego instalatora (" +
                    payloadVersion + "). Zaznacz aktualizację komponentów wspólnych i uruchom instalację ponownie.";
            if (!System.Text.RegularExpressions.Regex.IsMatch(profileId.Text.Trim(), "^[A-Za-z0-9._-]{1,64}$")) return "Nieprawidłowy identyfikator profilu.";
            if (discord.Checked && (String.IsNullOrWhiteSpace(discordId.Text) !=
                    String.IsNullOrWhiteSpace(discordSecret.Text))) return
                "Podaj oba pola Discorda albo pozostaw oba puste, aby użyć wspólnej aplikacji komputera.";
            if (createVibepolloToken.Checked && (String.IsNullOrWhiteSpace(vibepolloAdmin.Text) || String.IsNullOrWhiteSpace(vibepolloPassword.Text)))
                return "Do automatycznego utworzenia tokena podaj login i hasło administratora Vibepollo.";
            return null;
        }

        private static string Quote(string value) { return "\"" + value.Replace("\"", "\\\"") + "\""; }
        private void BrowseInstallDirectory() { BrowseDirectory(installPath); }
        private void BrowseDirectory(TextBox target)
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Wybierz katalog instalacji MoonWaker Host";
                if (Directory.Exists(target.Text)) dialog.SelectedPath = target.Text;
                if (dialog.ShowDialog(this) == DialogResult.OK) target.Text = dialog.SelectedPath;
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
            discordCard.Height = discord.Checked ? 168 : 70;
            if (discordCard.Parent != null) discordCard.Parent.PerformLayout();
        }

        private void RefreshInstallationStatus()
        {
            string root = installPath.Text.Trim();
            bool sharedInstalled = !String.IsNullOrWhiteSpace(root) &&
                File.Exists(Path.Combine(root, "gateway", "gateway.json")) &&
                File.Exists(Path.Combine(root, "control", "MoonWakerHostControl.exe"));
            bool updateRequired = SharedComponentsNeedUpdate(root);
            string id = profileId.Text.Trim();
            bool profileHasVibepollo = sharedInstalled && !String.IsNullOrWhiteSpace(id) &&
                File.Exists(Path.Combine(root, "profiles", id, "vibepollo", "api_token.dpapi"));
            if (sharedInstalled)
            {
                installationStatus.ForeColor = updateRequired
                    ? Color.FromArgb(255, 210, 140) : Color.FromArgb(129, 226, 169);
                if (updateRequired)
                    installationStatus.Text = "Wykryto komponenty wspólne " + DisplayInstalledVersion(root) +
                        "; instalator zawiera " + payloadVersion + ". Aktualizacja komponentów wspólnych jest wymagana i nie usuwa profili.";
                else
                    installationStatus.Text = installMachine.Checked
                        ? "Wykryto aktualną instalację. Ponowna aktualizacja komponentów wspólnych nie usuwa innych profili."
                        : "Komponenty wspólne są aktualne. Dodasz/zaaktualizujesz tylko bieżący profil Windows.";
                if (!installMachine.Checked && discord.Checked &&
                        String.IsNullOrWhiteSpace(discordId.Text) &&
                        String.IsNullOrWhiteSpace(discordSecret.Text)) {
                    installationStatus.Text += " Discord użyje wspólnej aplikacji tego komputera.";
                }
                if (profileHasVibepollo)
                {
                    installationStatus.Text += " Token Vibepollo dla tego profilu zostanie zachowany, jeśli pole tokena pozostanie puste.";
                    if (updateRequired && !createVibepolloToken.Checked)
                        installationStatus.Text += " Aby włączyć automatyczne parowanie klienta i nadawanie uprawnień do gier, zaznacz „Utwórz/odnów token automatycznie” i podaj dane administratora Vibepollo.";
                }
            }
            else
            {
                installationStatus.ForeColor = Color.FromArgb(255, 210, 140);
                installationStatus.Text = "Nie wykryto kompletnej instalacji wspólnej. Zaznacz instalację komponentów wspólnych.";
            }
        }
        private static string DetectInstallDirectory()
        {
            string configured = Environment.GetEnvironmentVariable("MOONWAKER_INSTALL_DIRECTORY");
            if (!String.IsNullOrWhiteSpace(configured)) return configured;
            return @"C:\Tools\WakePlayHost";
        }

        private bool SharedComponentsNeedUpdate(string root)
        {
            if (String.IsNullOrWhiteSpace(root)) return true;
            try
            {
                string directory = Path.GetFullPath(root);
                if (!File.Exists(Path.Combine(directory, "gateway", "gateway.json")) ||
                        !File.Exists(Path.Combine(directory, "control", "MoonWakerHostControl.exe"))) return true;
                if (!File.Exists(Path.Combine(directory, "tools", "legendary", "legendary.exe"))) return true;
                return !String.Equals(ReadInstalledVersion(directory), payloadVersion,
                           StringComparison.OrdinalIgnoreCase) ||
                    !String.Equals(ReadInstalledVersion(Path.Combine(directory, "gateway")), payloadVersion,
                        StringComparison.OrdinalIgnoreCase);
            }
            catch { return true; }
        }

        private static string ReadInstalledVersion(string root)
        {
            try
            {
                string versionFile = Path.Combine(Path.GetFullPath(root), "version.json");
                if (!File.Exists(versionFile)) return "";
                string text = File.ReadAllText(versionFile, Encoding.UTF8);
                System.Text.RegularExpressions.Match match = System.Text.RegularExpressions.Regex.Match(
                    text, "\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                return match.Success ? "v" + match.Groups[1].Value : "";
            }
            catch { return ""; }
        }

        private static string DisplayInstalledVersion(string root)
        {
            string version = ReadInstalledVersion(root);
            return String.IsNullOrWhiteSpace(version) ? "wersja nieznana" : version;
        }

        private void ConfigureTextBox(TextBox box, int left, int top, int width, bool password)
        {
            box.SetBounds(left, top, width, 34); box.BorderStyle = BorderStyle.FixedSingle;
            box.BackColor = Color.FromArgb(28, 33, 45); box.ForeColor = Color.White;
            box.UseSystemPasswordChar = password;
        }
        private void ConfigureCheckBox(CheckBox box, string text, int left, int top)
        {
            box.Text = text; box.SetBounds(left, top, 600, 30); box.Checked = true;
            box.ForeColor = Color.White;
            box.FlatStyle = FlatStyle.Flat;
            box.FlatAppearance.BorderColor = Color.FromArgb(116, 100, 255);
        }
        private Panel MakeCard(int height, Color accentColor)
        {
            Panel card = new Panel {
                Width = 900,
                Height = height,
                BackColor = Color.FromArgb(22, 27, 37),
                Margin = new Padding(0, 0, 0, 14)
            };
            Panel accent = new Panel {
                Dock = DockStyle.Left,
                Width = 4,
                BackColor = accentColor
            };
            card.Controls.Add(accent);
            return card;
        }
        private Button MakeButton(string text, int left, int top, int width, int height)
        {
            Button button = new Button { Text = text, FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(55, 67, 89), ForeColor = Color.White,
                Cursor = Cursors.Hand };
            button.FlatAppearance.BorderColor = Color.FromArgb(130, 117, 255);
            button.FlatAppearance.MouseOverBackColor = Color.FromArgb(78, 89, 118);
            button.SetBounds(left, top, width, height); return button;
        }
        private Label AddLabel(Control parent, string text, float size, FontStyle style, int left, int top, int width, int height)
        {
            Label label = new Label { Text = text, Font = new Font("Segoe UI", size, style),
                ForeColor = Color.White, AutoEllipsis = false, UseMnemonic = false };
            label.SetBounds(left, top, width, height); parent.Controls.Add(label);
            return label;
        }
    }
}
