using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

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

        internal InstallerForm()
        {
            Text = "MoonWaker Host Installer";
            ClientSize = new Size(900, 760);
            MinimumSize = new Size(820, 680);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(17, 20, 28);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5F);

            Panel content = new Panel { Dock = DockStyle.Fill, AutoScroll = true };
            Controls.Add(content);
            int y = 22;
            AddLabel(content, "MOONWAKER HOST", 28F, FontStyle.Bold, 28, y, 760, 42); y += 58;
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
            installMachine.Checked = !File.Exists(Path.Combine(installPath.Text.Trim(), "gateway", "gateway.json"));
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
            ConfigureCheckBox(playnite, "Playnite Bridge", 530, y); playnite.Checked = true; y += 48;
            content.Controls.Add(discord); content.Controls.Add(vibepollo); content.Controls.Add(playnite);

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
            createVibepolloToken.CheckedChanged += delegate { UpdateTokenFields(); }; y += 38;
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
            installPath.TextChanged += delegate { RefreshInstallationStatus(); };
            profileId.TextChanged += delegate { RefreshInstallationStatus(); };
            installMachine.CheckedChanged += delegate { RefreshInstallationStatus(); };
            UpdateTokenFields();
            RefreshInstallationStatus();
        }

        private async Task InstallAsync()
        {
            string validation = ValidateInput();
            if (validation != null) { MessageBox.Show(this, validation, "MoonWaker", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }
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
                install.Enabled = true;
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
                    RunMachineInstall(hostScript, Path.GetFullPath(installPath.Text.Trim()));
                }
                List<string> args = new List<string>();
                args.Add("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(script));
                args.Add("-InstallDirectory " + Quote(Path.GetFullPath(installPath.Text.Trim())));
                args.Add("-ProfileId " + Quote(profileId.Text.Trim()));
                args.Add("-ProfileName " + Quote(profileName.Text.Trim()));
                args.Add("-ProfileOnly");
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
                    Task.WaitAll(stdoutTask, stderrTask);
                    string stdout = stdoutTask.Result;
                    string stderr = stderrTask.Result;
                    if (process.ExitCode != 0) throw new InvalidOperationException(
                        String.IsNullOrWhiteSpace(stderr) ? stdout : stderr);
                    return stdout;
                }
            }
            finally { try { Directory.Delete(temporary, true); } catch { } }
        }

        private static void RunMachineInstall(string script, string directory)
        {
            if (!File.Exists(script)) throw new InvalidOperationException("Brak skryptu instalacji komponentów wspólnych.");
            string arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + Quote(script) +
                " -InstallDirectory " + Quote(directory) +
                " -GatewayDirectory " + Quote(Path.Combine(directory, "gateway")) + " -SkipStart";
            ProcessStartInfo info = new ProcessStartInfo("powershell.exe", arguments);
            info.UseShellExecute = true;
            info.Verb = "runas";
            using (Process process = Process.Start(info))
            {
                process.WaitForExit();
                if (process.ExitCode != 0) throw new InvalidOperationException(
                    "Instalacja komponentów wspólnych zakończyła się błędem (" + process.ExitCode + ").");
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

        private string ValidateInput()
        {
            string path = installPath.Text.Trim();
            if (String.IsNullOrWhiteSpace(path) || !Path.IsPathRooted(path)) return "Wybierz bezwzględną ścieżkę instalacji.";
            string root = Path.GetPathRoot(Path.GetFullPath(path));
            if (String.IsNullOrWhiteSpace(root) || !Directory.Exists(root)) return "Wybrany dysk jest obecnie niedostępny.";
            if (!installMachine.Checked && !File.Exists(Path.Combine(Path.GetFullPath(path), "gateway", "gateway.json")))
                return "Najpierw zainstaluj komponenty wspólne albo wybierz istniejącą instalację MoonWaker.";
            if (!System.Text.RegularExpressions.Regex.IsMatch(profileId.Text.Trim(), "^[A-Za-z0-9._-]{1,64}$")) return "Nieprawidłowy identyfikator profilu.";
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
            string id = profileId.Text.Trim();
            bool profileHasVibepollo = sharedInstalled && !String.IsNullOrWhiteSpace(id) &&
                File.Exists(Path.Combine(root, "profiles", id, "vibepollo", "api_token.dpapi"));
            if (sharedInstalled)
            {
                installationStatus.ForeColor = Color.FromArgb(129, 226, 169);
                installationStatus.Text = installMachine.Checked
                    ? "Wykryto istniejącą instalację. Aktualizacja komponentów wspólnych jest opcjonalna; nie usuwa innych profili."
                    : "Komponenty wspólne są już zainstalowane. Dodasz/zaaktualizujesz tylko bieżący profil Windows.";
                if (profileHasVibepollo)
                    installationStatus.Text += " Token Vibepollo dla tego profilu zostanie zachowany, jeśli pole tokena pozostanie puste.";
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
        private static string FindPlaynite()
        {
            string[] candidates = {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Playnite"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Playnite"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Playnite") };
            foreach (string candidate in candidates)
                if (File.Exists(Path.Combine(candidate, "Playnite.FullscreenApp.exe"))) return candidate;
            return "";
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
        private void AddLabel(Control parent, string text, float size, FontStyle style, int left, int top, int width, int height)
        {
            Label label = new Label { Text = text, Font = new Font("Segoe UI", size, style), ForeColor = Color.White, AutoEllipsis = true };
            label.SetBounds(left, top, width, height); parent.Controls.Add(label);
        }
    }
}
