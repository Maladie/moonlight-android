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
                Console.WriteLine("PASS Host Control UI: 9 page states x 2 themes built and rendered" +
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
            Shows(text, "devices", "Salon TV", "sparowano 02.10.2026", "Sypialnia", "Uprawnienia: Gosia");

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

        private static void Collect(DependencyObject parent, StringBuilder text)
        {
            foreach (object child in LogicalTreeHelper.GetChildren(parent))
            {
                TextBlock block = child as TextBlock;
                if (block != null)
                    text.Append(new System.Windows.Documents.TextRange(block.ContentStart, block.ContentEnd).Text).Append('\n');
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
                        { "profile_root", profile }, { "enabled", true }, { "remote_sign_in_enabled", true },
                        { "pin_verifier", new Dictionary<string, object> { { "version", 1 } } },
                        { "game_provider_bridge", "http://127.0.0.1:" + agentPort },
                        { "discord_bridge", "http://127.0.0.1:1" } } },
                    { "gosia", new Dictionary<string, object> {
                        { "name", "Gosia" }, { "owner", "MARIUSZ-PC\\gosia" }, { "windows_account_sid", "S-1-5-21-1-2-3-1002" },
                        { "profile_root", Path.Combine(root, "profiles", "gosia") }, { "enabled", true } } },
                    { "ania", new Dictionary<string, object> {
                        { "kind", "child" }, { "name", "Ania" }, { "parent_profile_id", "mariusz" } } },
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
