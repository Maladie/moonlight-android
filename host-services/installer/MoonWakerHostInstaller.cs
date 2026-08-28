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

[assembly: AssemblyVersion("0.7.35.0")]
[assembly: AssemblyFileVersion("0.7.35.0")]
[assembly: AssemblyInformationalVersion("0.7.35+2026.08.28")]

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
        private readonly CheckBox vibepollo = new CheckBox();
        private readonly CheckBox playnite = new CheckBox();
        private readonly TextBox discordId = new TextBox();
        private readonly TextBox discordSecret = new TextBox();
        private readonly TextBox vibepolloUrl = new TextBox();
        private readonly TextBox vibepolloToken = new TextBox();
        private readonly CheckBox createVibepolloToken = new CheckBox();
        private readonly TextBox vibepolloAdmin = new TextBox();
        private readonly TextBox vibepolloPassword = new TextBox();
        private readonly TextBox playnitePath = new TextBox();
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
            ClientSize = new Size(900, 760);
            MinimumSize = new Size(820, 680);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(17, 20, 28);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);
            try { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            Panel content = new Panel { Dock = DockStyle.Fill, AutoScroll = true };
            Controls.Add(content);
            int y = 22;
            AddLabel(content, "MOONWAKER HOST  " + payloadVersion, 28F, FontStyle.Bold, 28, y, 760, 42); y += 58;
            AddLabel(content, "Wszystkie komponenty zostaną zainstalowane w jednym wybranym katalogu.",
                10F, FontStyle.Regular, 30, y, 810, 28); y += 42;

            AddLabel(content, "Katalog instalacji", 10F, FontStyle.Bold, 30, y, 300, 24); y += 24;
            ConfigureTextBox(installPath, 30, y, 680, false);
            installPath.Text = DetectInstallDirectory();
            content.Controls.Add(installPath);
            Button browse = MakeButton("Wybierz…", 725, y, 125, 34);
            browse.Click += delegate { BrowseInstallDirectory(); };
            content.Controls.Add(browse); y += 54;

            ConfigureCheckBox(installMachine, "Zainstaluj/aktualizuj komponenty wspólne (wymaga administratora)", 30, y);
            installMachine.Width = 780;
            installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
            content.Controls.Add(installMachine); y += 45;
            installationStatus.SetBounds(30, y, 810, 42);
            installationStatus.ForeColor = Color.Gainsboro;
            installationStatus.AutoEllipsis = true;
            content.Controls.Add(installationStatus); y += 52;

            AddLabel(content, "Profil integracji", 14F, FontStyle.Bold, 30, y, 500, 30); y += 38;
            AddLabel(content, "Identyfikator", 9F, FontStyle.Regular, 30, y, 180, 22);
            AddLabel(content, "Nazwa", 9F, FontStyle.Regular, 330, y, 180, 22); y += 22;
            ConfigureTextBox(profileId, 30, y, 270, false); profileId.Text = "default";
            ConfigureTextBox(profileName, 330, y, 380, false); profileName.Text = Environment.UserName;
            content.Controls.Add(profileId); content.Controls.Add(profileName); y += 55;

            AddLabel(content, "Komponenty", 14F, FontStyle.Bold, 30, y, 500, 30); y += 35;
            ConfigureCheckBox(discord, "Discord Bridge", 30, y); discord.Checked = true;
            ConfigureCheckBox(vibepollo, "Vibepollo Bridge", 270, y); vibepollo.Checked = true;
            ConfigureCheckBox(playnite, "Game Provider Bridge", 530, y); playnite.Checked = true; y += 48;
            content.Controls.Add(discord); content.Controls.Add(vibepollo); content.Controls.Add(playnite);
            Label legendaryInfo = AddLabel(content,
                "Legendary jest zawsze instalowane dla obsługi Epic. Konto podłączysz później w Host Control.",
                9F, FontStyle.Regular, 30, y, 810, 38);
            legendaryInfo.ForeColor = Color.Gainsboro;
            y += 38;

            AddLabel(content, "Discord Client ID", 9F, FontStyle.Regular, 30, y, 260, 22);
            AddLabel(content, "Discord Client Secret", 9F, FontStyle.Regular, 330, y, 300, 22); y += 22;
            ConfigureTextBox(discordId, 30, y, 270, false);
            ConfigureTextBox(discordSecret, 330, y, 380, true);
            content.Controls.Add(discordId); content.Controls.Add(discordSecret); y += 55;

            AddLabel(content, "Vibepollo API URL", 9F, FontStyle.Regular, 30, y, 280, 22);
            AddLabel(content, "Istniejący token (opcjonalnie)", 9F, FontStyle.Regular, 430, y, 300, 22); y += 22;
            ConfigureTextBox(vibepolloUrl, 30, y, 370, false); vibepolloUrl.Text = "https://127.0.0.1:47990";
            ConfigureTextBox(vibepolloToken, 430, y, 280, true);
            content.Controls.Add(vibepolloUrl); content.Controls.Add(vibepolloToken); y += 48;
            ConfigureCheckBox(createVibepolloToken, "Utwórz/odnów token automatycznie", 30, y);
            content.Controls.Add(createVibepolloToken);
            createVibepolloToken.CheckedChanged += delegate {
                UpdateTokenFields();
                RefreshInstallationStatus();
            }; y += 38;
            AddLabel(content, "Login administratora Vibepollo", 9F, FontStyle.Regular, 30, y, 310, 22);
            AddLabel(content, "Hasło", 9F, FontStyle.Regular, 430, y, 200, 22); y += 22;
            ConfigureTextBox(vibepolloAdmin, 30, y, 370, false);
            ConfigureTextBox(vibepolloPassword, 430, y, 280, true);
            content.Controls.Add(vibepolloAdmin); content.Controls.Add(vibepolloPassword); y += 55;

            AddLabel(content, "Katalog Playnite", 9F, FontStyle.Regular, 30, y, 280, 22); y += 22;
            ConfigureTextBox(playnitePath, 30, y, 680, false); playnitePath.Text = FindPlaynite();
            content.Controls.Add(playnitePath);
            Button browsePlaynite = MakeButton("Wybierz…", 725, y, 125, 34);
            browsePlaynite.Click += delegate { BrowseDirectory(playnitePath); };
            content.Controls.Add(browsePlaynite); y += 58;

            install = MakeButton("Zainstaluj / aktualizuj", 30, y, 250, 42);
            install.Click += async delegate { await InstallAsync(); };
            content.Controls.Add(install); y += 58;
            log.SetBounds(30, y, 820, 140);
            log.ReadOnly = true; log.BackColor = Color.FromArgb(12, 15, 21); log.ForeColor = Color.Gainsboro;
            log.BorderStyle = BorderStyle.FixedSingle;
            content.Controls.Add(log); y += 165;
            content.AutoScrollMinSize = new Size(0, y);
            installPath.TextChanged += delegate {
                installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
                RefreshInstallationStatus();
            };
            profileId.TextChanged += delegate { RefreshInstallationStatus(); };
            installMachine.CheckedChanged += delegate { RefreshInstallationStatus(); };
            discord.CheckedChanged += delegate { RefreshInstallationStatus(); };
            installMachine.Checked = SharedComponentsNeedUpdate(installPath.Text.Trim());
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
                if (!String.IsNullOrWhiteSpace(playnitePath.Text)) args.Add("-PlayniteDirectory " + Quote(playnitePath.Text.Trim()));
                if (!discord.Checked) args.Add("-SkipDiscord");
                if (!vibepollo.Checked) args.Add("-SkipVibepollo");
                if (!playnite.Checked) args.Add("-SkipPlaynite");

                ProcessStartInfo info = new ProcessStartInfo("powershell.exe", String.Join(" ", args.ToArray()));
                info.UseShellExecute = false; info.CreateNoWindow = true;
                info.RedirectStandardOutput = true; info.RedirectStandardError = true;
                info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
                info.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_ID"] = discordId.Text.Trim();
                info.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_SECRET"] = discordSecret.Text;
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
            if (playnite.Checked && String.IsNullOrWhiteSpace(playnitePath.Text)) return "Wskaż katalog Playnite.";
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

        private static string FindPlaynite()
        {
            string configured = Environment.GetEnvironmentVariable("MOONWAKER_PLAYNITE_DIRECTORY");
            string found = ValidPlayniteDirectory(configured);
            if (found != null) return found;
            string[] candidates = {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Playnite"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Playnite"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Playnite") };
            foreach (string candidate in candidates)
            {
                found = ValidPlayniteDirectory(candidate);
                if (found != null) return found;
            }
            foreach (string candidate in RegistryPlayniteDirectories())
            {
                found = ValidPlayniteDirectory(candidate);
                if (found != null) return found;
            }
            foreach (DriveInfo drive in DriveInfo.GetDrives())
            {
                try
                {
                    if (!drive.IsReady || drive.DriveType != DriveType.Fixed) continue;
                    string[] locations = {
                        Path.Combine(drive.RootDirectory.FullName, "Playnite"),
                        Path.Combine(drive.RootDirectory.FullName, "Games", "Playnite"),
                        Path.Combine(drive.RootDirectory.FullName, "Gry", "Playnite"),
                        Path.Combine(drive.RootDirectory.FullName, "Program Files", "Playnite"),
                        Path.Combine(drive.RootDirectory.FullName, "Program Files (x86)", "Playnite") };
                    foreach (string candidate in locations)
                    {
                        found = ValidPlayniteDirectory(candidate);
                        if (found != null) return found;
                    }
                }
                catch { }
            }
            return "";
        }

        private static string ValidPlayniteDirectory(string candidate)
        {
            try
            {
                if (String.IsNullOrWhiteSpace(candidate)) return null;
                string directory = Path.GetFullPath(candidate.Trim().Trim('"'));
                return File.Exists(Path.Combine(directory, "Playnite.FullscreenApp.exe")) ? directory : null;
            }
            catch { return null; }
        }

        private static IEnumerable<string> RegistryPlayniteDirectories()
        {
            List<string> results = new List<string>();
            RegistryView[] views = { RegistryView.Registry64, RegistryView.Registry32 };
            RegistryHive[] hives = { RegistryHive.CurrentUser, RegistryHive.LocalMachine };
            foreach (RegistryHive hive in hives)
            foreach (RegistryView view in views)
            {
                RegistryKey baseKey = null;
                RegistryKey uninstall = null;
                try
                {
                    baseKey = RegistryKey.OpenBaseKey(hive, view);
                    uninstall = baseKey.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall");
                    if (uninstall == null) continue;
                    foreach (string keyName in uninstall.GetSubKeyNames())
                    using (RegistryKey entry = uninstall.OpenSubKey(keyName))
                    {
                        if (entry == null) continue;
                        string displayName = entry.GetValue("DisplayName") as string;
                        if (String.IsNullOrWhiteSpace(displayName) ||
                                displayName.IndexOf("Playnite", StringComparison.OrdinalIgnoreCase) < 0) continue;
                        string installLocation = entry.GetValue("InstallLocation") as string;
                        if (!String.IsNullOrWhiteSpace(installLocation)) results.Add(installLocation);
                        string displayIcon = entry.GetValue("DisplayIcon") as string;
                        if (!String.IsNullOrWhiteSpace(displayIcon))
                            results.Add(Path.GetDirectoryName(displayIcon.Trim().Trim('"').Split(',')[0]));
                    }
                }
                catch { }
                finally { if (uninstall != null) uninstall.Dispose(); if (baseKey != null) baseKey.Dispose(); }
            }
            return results;
        }
        private void ConfigureTextBox(TextBox box, int left, int top, int width, bool password)
        {
            box.SetBounds(left, top, width, 34); box.BorderStyle = BorderStyle.FixedSingle;
            box.BackColor = Color.FromArgb(28, 33, 45); box.ForeColor = Color.White;
            box.UseSystemPasswordChar = password;
        }
        private void ConfigureCheckBox(CheckBox box, string text, int left, int top)
        {
            box.Text = text; box.SetBounds(left, top, 240, 28); box.Checked = true;
            box.ForeColor = Color.White;
        }
        private Button MakeButton(string text, int left, int top, int width, int height)
        {
            Button button = new Button { Text = text, FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(68, 88, 120), ForeColor = Color.White };
            button.FlatAppearance.BorderColor = Color.FromArgb(116, 100, 255); button.SetBounds(left, top, width, height); return button;
        }
        private Label AddLabel(Control parent, string text, float size, FontStyle style, int left, int top, int width, int height)
        {
            Label label = new Label { Text = text, Font = new Font("Segoe UI", size, style), ForeColor = Color.White, AutoEllipsis = true };
            label.SetBounds(left, top, width, height); parent.Controls.Add(label);
            return label;
        }
    }
}
