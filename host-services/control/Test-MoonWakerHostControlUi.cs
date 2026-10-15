using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MoonWaker.HostControl
{
    // Smoke test of the WPF window: builds it on a temporary installation, renders every page
    // in both themes without showing anything on screen, and checks what the pages say. With
    // an output directory it also writes each render as PNG. Touches no service, registry value
    // or network beyond a loopback stand-in for the Agent.
    internal static class UiTests
    {
        private static readonly string Sid = WindowsIdentity.GetCurrent().User.Value;
        private const double Width = 1100, Height = 688;
        private static string root;

        [STAThread]
        private static int Main(string[] args)
        {
            string output = args.Length > 0 ? Path.GetFullPath(args[0]) : null;
            root = Path.Combine(Path.GetTempPath(), "MoonWakerHostControlUi-" + Guid.NewGuid().ToString("N"));
            TcpListener agent = new TcpListener(IPAddress.Loopback, 0);
            try
            {
                agent.Start();
                Thread server = new Thread(delegate() { Serve(agent); });
                server.IsBackground = true;
                server.Start();
                CreateInstallation(((IPEndPoint)agent.LocalEndpoint).Port);
                if (output != null) Directory.CreateDirectory(output);
                HostBackend backend = new HostBackend(root, Sid, delegate { return Path.Combine(root, "python.exe"); },
                    delegate { throw new InvalidOperationException("The UI test never restarts the Gateway."); });
                using (ProfileProcessHost processes = new ProfileProcessHost(root, Sid, false, null, null))
                    foreach (bool light in new[] { false, true })
                        RenderTheme(backend, processes, light, output);
                Console.WriteLine("PASS Host Control UI: 12 page states and 7 editors x 2 themes built and rendered" +
                    (output == null ? "." : " to " + output + "."));
                return 0;
            }
            catch (Exception exception)
            {
                Console.Error.WriteLine("FAIL Host Control UI: " + exception);
                return 1;
            }
            finally
            {
                agent.Stop();
                try { Directory.Delete(root, true); } catch { }
            }
        }

        private static void RenderTheme(HostBackend backend, ProfileProcessHost processes, bool light, string output)
        {
            string theme = light ? "jasny" : "ciemny";
            string steamKey = Path.Combine(root, "profiles", "mariusz", "game-provider", "steam-web-api-key.dpapi");
            HostControlWindow window = new HostControlWindow(backend, processes, light);
            Expect(((Panel)window.Content).Background is SolidColorBrush &&
                ((SolidColorBrush)((Panel)window.Content).Background).Color ==
                (Color)ColorConverter.ConvertFromString(light ? "#F3F3F8" : "#1B1B22"), "the " + theme + " palette");

            // No library yet: the first configuration shows its last step, then a pairing code.
            if (File.Exists(steamKey)) File.Delete(steamKey);
            window.ApplyMicrophone("ready");
            window.Apply(backend.ReadStatus());
            window.ShowPairingCode(backend.CreatePairingCode());
            window.Apply(backend.ReadStatus());
            string text = Render(window, HostControlWindow.Overview, "01-przeglad-konfiguracja-" + theme, output);
            Shows(text, "overview with first configuration", "Pierwsza konfiguracja", "3 z 4 gotowe",
                "Vibepollo połączone", "Połącz bibliotekę", "Kod wygasa za", "Anuluj");
            Expect(!text.Contains("Ukryj kod"), "the pairing code is cancelled, not hidden");
            // A TV paired: the Gateway emptied the file and the window follows it.
            Write(Path.Combine(root, "gateway", "pairing-code.json"), "{}");
            window.Apply(backend.ReadStatus());
            text = Render(window, HostControlWindow.Overview, null, null);
            Expect(!text.Contains("Kod wygasa za") && text.Contains("Utwórz kod"), "a consumed pairing code is no longer shown");

            // A library connected: the first configuration is gone.
            File.WriteAllText(steamKey, Dpapi.Protect(new string('a', 32)) + "\r\n", Encoding.ASCII);
            window.Apply(backend.ReadStatus());
            text = Render(window, HostControlWindow.Overview, "01-przeglad-" + theme, output);
            Expect(!text.Contains("Pierwsza konfiguracja"), "the first configuration disappears once complete");
            Shows(text, "overview", "Gateway komputera", "Sparowane TV: 2", "Połączone", "Token komputera ważny",
                "Profil: Mariusz", "Aktywna sesja", "trwa 1:24:", "Gra w toku", "Profil Mariusz  •  Salon TV  •  Steam",
                "Zamknij sesję");

            // Vibepollo not answering and no stream record; then no status file at all.
            WriteGatewayStatus(false, false);
            window.Apply(backend.ReadStatus());
            text = Render(window, HostControlWindow.Overview, "01-przeglad-bez-sesji-" + theme, output);
            Shows(text, "overview without a session", "Brak połączenia", "Sprawdź, czy Vibepollo działa", "Odnów token",
                "Gateway nie prowadzi teraz sesji streamu.", "Zamknij sesję", "Vibepollo połączone");
            File.Delete(Path.Combine(root, "gateway", "gateway-status.json"));
            window.Apply(backend.ReadStatus());
            text = Render(window, HostControlWindow.Overview, "01-przeglad-stan-nieznany-" + theme, output);
            Shows(text, "overview with an unknown state", "Stan nieznany", "Stan sesji nieznany");
            Expect(!text.Contains("Pierwsza konfiguracja"), "an unknown Vibepollo state keeps the first configuration done");
            text = Render(window, HostControlWindow.Integrations, "04-integracje-stan-nieznany-" + theme, output);
            Shows(text, "integrations with an unknown state", "Stan nieznany", "zapisany");
            WriteGatewayStatus(true, true);
            window.Apply(backend.ReadStatus());

            window.Fail(HostControlWindow.Profiles, "Epic: nie udało się połączyć.",
                "Legendary nie jest zalogowane na koncie Mariusz.");
            text = Render(window, HostControlWindow.Profiles, "02-profile-" + theme, output);
            Shows(text, "profiles", "Epic: nie udało się połączyć.", "Gosia", "Ania, Staś", "Zaloguj w Legendary",
                "Klucz Web API zapisany dla tego konta", "Usuń profil");

            text = Render(window, HostControlWindow.Devices, "03-urzadzenia-" + theme, output);
            Shows(text, "devices", "Salon TV", "sparowano 02.10.2026", "Sypialnia", "Korzystanie z profilu",
                "Zarządzanie dziećmi", "Ania", "Cofnij parowanie");
            Expect(!text.Contains("Niezapisan"), "no pending bar before a switch is flipped");
            Expect(Name(window, "revoke-salon") == "Cofnij parowanie (wymaga zgody administratora)",
                "revoking a pairing carries the UAC shield");
            // A flipped switch is pending until saved together or discarded; flipped back, it is gone.
            window.Toggle("salon", "mariusz", HostControlWindow.RemoteColumn);
            text = Render(window, HostControlWindow.Devices, "03-urzadzenia-zmiana-" + theme, output);
            Shows(text, "devices with a pending change", "Niezapisana zmiana: ",
                "Salon TV: zdalne logowanie profilu Mariusz — wyłącz.", "Odrzuć", "Zapisz zmiany");
            Expect(Name(window, "grant-salon-mariusz-remote") == "Mariusz, zdalne logowanie: nie (niezapisana zmiana)" &&
                Name(window, "grant-salon-mariusz-use") == "Mariusz, korzystanie: tak",
                "the flipped switch shows its new state, marked as unsaved");
            Expect(BottomOf(window, "grants-save") <= Height - 30, "the pending bar stays above the status bar");
            window.Toggle("sypialnia", "ania", HostControlWindow.UseColumn);
            text = Render(window, HostControlWindow.Devices, null, null);
            Shows(text, "devices with two pending changes", "Niezapisane zmiany (2): ", "Sypialnia: korzystanie profilu Ania — włącz");
            window.Toggle("sypialnia", "ania", HostControlWindow.UseColumn);
            text = Render(window, HostControlWindow.Devices, null, null);
            Expect(text.Contains("Niezapisana zmiana: ") && !text.Contains("Sypialnia: korzystanie"),
                "a switch flipped back is no longer pending");

            // Editors: built without elevation; their "Zapisz" carries the UAC shield.
            HostStatus status = backend.ReadStatus();
            ProfileStatus mariusz = status.Profiles.Find(item => item.Id == "mariusz");
            ProfileStatus gosia = status.Profiles.Find(item => item.Id == "gosia");
            HostControlWindow.Editor editor = window.EditProfileEditor(mariusz);
            Dictionary<string, object> change = editor.Changes()[0];
            Expect((string)change["type"] == "profile_edit" && (string)change["account_sid"] == Sid &&
                (string)change["display_name"] == "Mariusz", "the profile editor names the profile's account");
            Expect(editor.Revision == status.RegistryRevision && editor.Revision.Length == 64,
                "an editor keeps the registry revision its data came from");
            text = RenderDialog(editor.Dialog, "06-edytuj-profil-" + theme, output);
            Shows(text, "profile editor", "Edytuj profil", "Nazwa profilu", "Profil włączony", "Zapisz");
            text = RenderDialog(window.PinEditor(mariusz).Dialog, "06-pin-" + theme, output);
            Shows(text, "PIN editor", "PIN aplikacji", "Powtórz PIN", "Zmień PIN", "Usuń PIN");
            text = RenderDialog(window.RemoteEditor(mariusz).Dialog, "06-zdalne-logowanie-" + theme, output);
            Shows(text, "remote sign-in editor", "UPRZYWILEJOWANE", "Zmień hasło Windows", "Sprawdź zapisane dane",
                "Wyłącz i usuń dane z Login Brokera", "Dalej");
            Expect(!text.Contains("Nowe hasło"), "Host Control never asks for the Windows password");
            text = RenderDialog(window.ChildrenList(mariusz, delegate { }), "06-dzieci-" + theme, output);
            Shows(text, "children", "Profile dzieci", "Ania", "Staś", "udostępnione gry: 2", "granie w 1 dzień tygodnia",
                "Dodaj dziecko");
            text = RenderDialog(window.ChildEditor(mariusz, mariusz.Children.Find(item => item.Id == "ania"),
                status.RegistryRevision).Dialog,
                "06-dziecko-" + theme, output);
            Shows(text, "child editor", "Profil dziecka Ania", "Zielony", "Profil aktywny", "Sobota", "Limit (min)",
                "Po upływie czasu gra zostanie zamknięta");
            List<LocalAccount> accounts = new List<LocalAccount> {
                new LocalAccount { Name = "basia", QualifiedName = "MARIUSZ-PC\\basia", Sid = "S-1-5-21-1-2-3-1003" },
                new LocalAccount { Name = "gry", QualifiedName = "MARIUSZ-PC\\gry", Sid = "S-1-5-21-1-2-3-1004" } };
            editor = window.AddProfileEditor(accounts);
            change = editor.Changes()[0];
            Expect((string)change["type"] == "profile_add" && (string)change["account_sid"] == accounts[0].Sid &&
                (string)change["display_name"] == "basia", "a new profile names the chosen account");
            text = RenderDialog(editor.Dialog, "06-dodaj-profil-" + theme, output);
            Shows(text, "add profile", "Konto Windows", "MARIUSZ-PC\\gry", "Utwórz profil");
            text = RenderDialog(window.RemoveProfileEditor(gosia).Dialog, "06-usun-profil-" + theme, output);
            Shows(text, "remove profile", "Usunąć profil „Gosia”?", "MARIUSZ-PC\\gosia", "Usuń profil");

            text = Render(window, HostControlWindow.Integrations, "04-integracje-" + theme, output);
            Shows(text, "integrations", "Vibepollo", "Połączone", "zapisany, ważny", "Pobierz / odnów token",
                "1204 •••• •••• 7731", "Skonfigurowany");

            text = Render(window, HostControlWindow.Diagnostics, "05-diagnostyka-" + theme, output);
            Shows(text, "diagnostics", "Discord: ", "0.7.133", "różnica", "Napraw",
                "Mikrofon na TV", "Legendary (Epic)");
            window.Exit();
        }

        private static string Render(HostControlWindow window, int page, string name, string output)
        {
            window.ShowPage(page);
            FrameworkElement content = (FrameworkElement)window.Content;
            content.Measure(new Size(Width, Height));
            content.Arrange(new Rect(0, 0, Width, Height));
            content.UpdateLayout();
            if (output != null)
            {
                RenderTargetBitmap bitmap = new RenderTargetBitmap((int)Width, (int)Height, 96, 96, PixelFormats.Pbgra32);
                bitmap.Render(content);
                PngBitmapEncoder png = new PngBitmapEncoder();
                png.Frames.Add(BitmapFrame.Create(bitmap));
                using (FileStream file = File.Create(Path.Combine(output, name + ".png"))) png.Save(file);
            }
            StringBuilder text = new StringBuilder();
            Collect(content, text);
            return text.ToString();
        }

        // A dialog's content on its background at its width, as PNG; returns its text.
        private static string RenderDialog(Window dialog, string name, string output)
        {
            UIElement content = (UIElement)dialog.Content;
            dialog.Content = null;
            Border frame = new Border { Background = dialog.Background, Child = content, Width = dialog.Width };
            TextOptions.SetTextFormattingMode(frame, TextFormattingMode.Display);
            frame.Measure(new Size(dialog.Width, Double.PositiveInfinity));
            frame.Arrange(new Rect(frame.DesiredSize));
            frame.UpdateLayout();
            if (output != null)
            {
                RenderTargetBitmap bitmap = new RenderTargetBitmap((int)Math.Ceiling(frame.ActualWidth),
                    (int)Math.Ceiling(frame.ActualHeight), 96, 96, PixelFormats.Pbgra32);
                bitmap.Render(frame);
                PngBitmapEncoder png = new PngBitmapEncoder();
                png.Frames.Add(BitmapFrame.Create(bitmap));
                using (FileStream file = File.Create(Path.Combine(output, name + ".png"))) png.Save(file);
            }
            StringBuilder text = new StringBuilder();
            Collect(frame, text);
            dialog.Close();
            return text.ToString();
        }

        private static FrameworkElement Find(DependencyObject parent, string id)
        {
            foreach (object child in LogicalTreeHelper.GetChildren(parent))
            {
                FrameworkElement element = child as FrameworkElement;
                if (element == null) continue;
                if (System.Windows.Automation.AutomationProperties.GetAutomationId(element) == id) return element;
                FrameworkElement found = Find(element, id);
                if (found != null) return found;
            }
            return null;
        }

        private static string Name(HostControlWindow window, string id)
        {
            FrameworkElement element = Find((DependencyObject)window.Content, id);
            return element == null ? null : System.Windows.Automation.AutomationProperties.GetName(element);
        }

        // Where the element ends in the rendered window, from its top.
        private static double BottomOf(HostControlWindow window, string id)
        {
            FrameworkElement element = Find((DependencyObject)window.Content, id);
            return element.TransformToAncestor((Visual)window.Content).Transform(new Point(0, element.ActualHeight)).Y;
        }

        private static void Collect(DependencyObject parent, StringBuilder text)
        {
            foreach (object child in LogicalTreeHelper.GetChildren(parent))
            {
                TextBlock block = child as TextBlock;
                if (block != null)
                    text.Append(new System.Windows.Documents.TextRange(block.ContentStart, block.ContentEnd).Text).Append('\n');
                ContentControl control = child as ContentControl;
                if (control != null && control.Content is string) text.Append((string)control.Content).Append('\n');
                DependencyObject element = child as DependencyObject;
                if (element != null) Collect(element, text);
            }
        }

        private static void CreateInstallation(int agentPort)
        {
            string gateway = Path.Combine(root, "gateway");
            string profile = Path.Combine(root, "profiles", "mariusz");
            Directory.CreateDirectory(gateway);
            Directory.CreateDirectory(Path.Combine(profile, "game-provider"));
            Directory.CreateDirectory(Path.Combine(profile, "discord"));
            Directory.CreateDirectory(Path.Combine(root, "tools", "legendary"));
            Directory.CreateDirectory(Path.Combine(root, "bridge-source", "discord"));
            File.WriteAllText(Path.Combine(root, "tools", "legendary", "legendary.exe"), "");
            File.WriteAllText(Path.Combine(root, "bridge-source", "discord", "DiscordBridge.ps1"), "");
            Write(Path.Combine(root, "version.json"), "{\"version\":\"0.7.134\",\"build\":\"b2\"}");
            int self = Process.GetCurrentProcess().Id;
            Write(Path.Combine(gateway, "gateway-service-state.json"), "{\"status\":\"running\",\"process_id\":" + self + "}");
            Write(Path.Combine(gateway, "gateway-runtime.json"),
                "{\"version\":\"0.7.133\",\"build\":\"b1\",\"pid\":" + self + "}");
            Write(Path.Combine(gateway, "vibepollo-api.json"), "{\"token\":\"t\"}");
            Write(Path.Combine(gateway, "pairing-code.json"), "{}");
            Write(Path.Combine(gateway, "host-close-request.json"), "{}");
            WriteGatewayStatus(true, true);
            Write(Path.Combine(profile, "game-provider", "config.json"), "{\"playnite_enabled\":false}");
            Write(Path.Combine(profile, "discord", "discord_bridge_config.json"), "{\"client_id\":\"120412345678907731\"}");
            File.WriteAllText(Path.Combine(profile, "discord", "client_secret.dpapi"), Dpapi.Protect("secret") + "\r\n", Encoding.ASCII);
            long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            Write(Path.Combine(gateway, "gateway.json"), new JavaScriptSerializer().Serialize(new Dictionary<string, object> {
                { "listen_port", 8785 },
                { "clients", new object[] {
                    Client("salon", "Salon TV", new DateTime(2026, 10, 2, 19, 0, 0), now - 60, true, true, true, false),
                    Client("sypialnia", "Sypialnia", new DateTime(2026, 10, 11, 20, 0, 0),
                        new DateTimeOffset(DateTime.Today.AddDays(-1).AddHours(22).AddMinutes(5)).ToUnixTimeSeconds(),
                        true, false, false, false) } },
                { "profiles", new Dictionary<string, object> {
                    { "mariusz", new Dictionary<string, object> {
                        { "name", "Mariusz" }, { "owner", "MARIUSZ-PC\\mariusz" }, { "windows_account_sid", Sid },
                        { "account_mapping_status", "resolved" }, { "children_policy_revision", 4 },
                        { "profile_root", profile }, { "enabled", true }, { "remote_sign_in_enabled", true },
                        { "pin_verifier", new Dictionary<string, object> { { "version", 1 } } },
                        { "game_provider_bridge", "http://127.0.0.1:" + agentPort },
                        { "discord_bridge", "http://127.0.0.1:1" } } },
                    { "gosia", new Dictionary<string, object> {
                        { "name", "Gosia" }, { "owner", "MARIUSZ-PC\\gosia" }, { "windows_account_sid", "S-1-5-21-1-2-3-1002" },
                        { "profile_root", Path.Combine(root, "profiles", "gosia") }, { "enabled", true } } },
                    { "ania", new Dictionary<string, object> {
                        { "kind", "child" }, { "name", "Ania" }, { "parent_profile_id", "mariusz" }, { "enabled", true },
                        { "avatar_id", "green" }, { "allowed_game_keys", new object[] { "mariusz/steam:1", "mariusz/epic:2" } },
                        { "schedule", new Dictionary<string, object> { { "weekdays", new Dictionary<string, object> {
                            { "sat", new Dictionary<string, object> { { "enabled", true }, { "start_minute", 600 },
                                { "end_minute", 1080 }, { "daily_limit_seconds", 7200 } } } } } } } } },
                    { "stas", new Dictionary<string, object> {
                        { "kind", "child" }, { "name", "Staś" }, { "parent_profile_id", "mariusz" } } } } } }));
        }

        // The Gateway's published status: Vibepollo answering or not, Mariusz streaming a Steam
        // game to the living room for 1:24:07, or no stream record.
        private static void WriteGatewayStatus(bool online, bool session)
        {
            long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            Dictionary<string, object> status = new Dictionary<string, object> {
                { "vibepollo_api_online", online }, { "vibepollo_checked_at", now }, { "updated_at", now } };
            if (session)
                status["session"] = new Dictionary<string, object> {
                    { "actor_profile_id", "mariusz" }, { "actor_profile_name", "Mariusz" },
                    { "execution_profile_id", "mariusz" }, { "execution_profile_name", "Mariusz" },
                    { "client_name", "Salon TV" }, { "game_id", "steam:1091500" },
                    { "stream_started_unix_ms", (now - 5047) * 1000 } };
            Write(Path.Combine(root, "gateway", "gateway-status.json"), new JavaScriptSerializer().Serialize(status));
        }

        private static Dictionary<string, object> Client(string id, string name, DateTime paired, long seen,
            bool mariuszUse, bool mariuszRemote, bool gosiaUse, bool gosiaRemote)
        {
            Dictionary<string, object> grants = new Dictionary<string, object>();
            grants["mariusz"] = Permissions(mariuszUse, mariuszRemote);
            grants["gosia"] = Permissions(gosiaUse, gosiaRemote);
            return new Dictionary<string, object> {
                { "id", id }, { "name", name }, { "token_sha256", "00" },
                { "paired_at", new DateTimeOffset(paired).ToUnixTimeSeconds() }, { "last_seen_at", seen },
                { "profile_grants", grants } };
        }

        private static object[] Permissions(bool use, bool remote)
        {
            List<object> list = new List<object>();
            if (use) list.Add("use_profile");
            if (remote) list.Add("remote_sign_in");
            return list.ToArray();
        }

        private static void Serve(TcpListener listener)
        {
            byte[] reply = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}");
            while (true)
            {
                try
                {
                    using (TcpClient client = listener.AcceptTcpClient())
                    using (NetworkStream stream = client.GetStream())
                    {
                        stream.Read(new byte[4096], 0, 4096);
                        stream.Write(reply, 0, reply.Length);
                    }
                }
                catch { return; }
            }
        }

        private static void Write(string path, string text)
        {
            File.WriteAllText(path, text, new UTF8Encoding(false));
        }

        // Every needle must be on the page: the failure names the missing ones and the page text.
        private static void Shows(string text, string what, params string[] needles)
        {
            List<string> missing = new List<string>();
            foreach (string needle in needles) if (!text.Contains(needle)) missing.Add(needle);
            if (missing.Count > 0)
                throw new InvalidOperationException(what + " lacks: " + String.Join(" | ", missing.ToArray()) +
                    "\n--- page text ---\n" + text);
        }

        private static void Expect(bool condition, string what)
        {
            if (!condition) throw new InvalidOperationException("Expectation failed: " + what);
        }
    }
}
