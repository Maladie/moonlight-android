using System;
using System.Diagnostics;
using System.IO;

namespace MoonWaker.GatewayService
{
    internal static class SelfTestProgram
    {
        private static int Main()
        {
            string root = Path.Combine(Path.GetTempPath(),
                "MoonWakerGatewayServiceTest-" + Guid.NewGuid().ToString("N"));
            try
            {
                Directory.CreateDirectory(root);
                string python = Path.Combine(root, "python.exe");
                File.WriteAllText(python, "test");
                File.WriteAllText(Path.Combine(root, "wakeplay_gateway.py"), "# test");
                File.WriteAllText(Path.Combine(root, "gateway.json"), "{}");
                GatewayServiceSettings settings = GatewayServiceSettings.Parse(new[] {
                    "--gateway-dir", root, "--python", python
                });
                ProcessStartInfo start = settings.CreateStartInfo();
                Assert(start.FileName == Path.GetFullPath(python), "Python path changed.");
                Assert(start.WorkingDirectory == Path.GetFullPath(root), "Working directory changed.");
                Assert(start.Arguments.Contains("wakeplay_gateway.py") &&
                    start.Arguments.Contains("--config") &&
                    start.Arguments.Contains("--registry-lock") &&
                    !start.Arguments.Contains("--pairing-code"), "Gateway arguments are incomplete.");
                Assert(!start.UseShellExecute && start.CreateNoWindow,
                    "Service child would require an interactive desktop.");
                Console.WriteLine("PASS: Gateway service settings and pre-logon command");
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine("FAIL: " + error.Message);
                return 1;
            }
            finally
            {
                if (Directory.Exists(root)) Directory.Delete(root, true);
            }
        }

        private static void Assert(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException(message);
        }
    }
}
