using System;
using System.IO;
using System.IO.Pipes;
using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace MoonWaker.HotUpdater
{
    internal static class SelfTestProgram
    {
        private static int Main()
        {
            if (Array.IndexOf(Environment.GetCommandLineArgs(), "--flood-stderr") >= 0)
            {
                Console.Error.Write(new string('x', 2 * 1024 * 1024));
                return 0;
            }
            if (Array.IndexOf(Environment.GetCommandLineArgs(), "--hang") >= 0)
            {
                System.Threading.Thread.Sleep(30000);
                return 0;
            }
            int dummyIndex = Array.IndexOf(Environment.GetCommandLineArgs(), "--dummy-http");
            if (dummyIndex >= 0)
            {
                int port = Int32.Parse(Environment.GetCommandLineArgs()[dummyIndex + 1]);
                TcpListener listener = new TcpListener(IPAddress.Loopback, port);
                listener.Start();
                using (TcpClient client = listener.AcceptTcpClient())
                using (NetworkStream stream = client.GetStream())
                {
                    byte[] request = new byte[4096];
                    stream.Read(request, 0, request.Length);
                    byte[] response = Encoding.ASCII.GetBytes(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");
                    stream.Write(response, 0, response.Length);
                }
                listener.Stop();
                return 0;
            }
            try
            {
                SecurityIdentifier pinned = new SecurityIdentifier("S-1-5-21-1-2-3-1001");
                PipeSecurity security = UpdateServer.CreatePipeSecurity(pinned);
                Assert(HasRule(security, WellKnownSidType.NetworkSid,
                    AccessControlType.Deny), "Network clients are not denied.");
                Assert(HasRule(security, pinned, AccessControlType.Allow),
                    "Pinned local developer cannot submit updates.");
                Assert(!HasRule(security, WellKnownSidType.AuthenticatedUserSid,
                    AccessControlType.Allow), "Unpinned authenticated users can submit updates.");
                Assert(UpdateServer.MaximumPackageBytes == 64L * 1024L * 1024L,
                    "Package transfer is not bounded.");
                string root = Path.Combine(Path.GetTempPath(), "MoonWakerUpdaterSettings-" +
                    Guid.NewGuid().ToString("N"));
                try
                {
                    Directory.CreateDirectory(Path.Combine(root, "updater"));
                    File.WriteAllText(Path.Combine(root, "version.json"), "{}");
                    File.WriteAllText(Path.Combine(root, "updater",
                        "Apply-MoonWakerHotUpdate.ps1"), "# test");
                    UpdaterSettings settings = UpdaterSettings.Parse(new[] { "--host-root", root,
                        "--client-sid", pinned.Value });
                    Assert(settings.HostRoot == Path.GetFullPath(root), "Host root changed.");
                    Assert(settings.ApplyScript.StartsWith(settings.HostRoot +
                        Path.DirectorySeparatorChar), "Apply script escaped the host root.");
                }
                finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
                ProcessStartInfo flood = new ProcessStartInfo {
                    FileName = Environment.GetCommandLineArgs()[0],
                    Arguments = "--flood-stderr", UseShellExecute = false,
                    RedirectStandardOutput = true, RedirectStandardError = true,
                    CreateNoWindow = true
                };
                Stopwatch elapsed = Stopwatch.StartNew();
                BoundedProcessResult floodResult = BoundedProcess.Run(flood, 10000, null, null);
                Assert(floodResult.ExitCode == 0 &&
                    floodResult.Error.Length == BoundedProcess.MaximumCapturedCharacters,
                    "Stderr flood was not drained and bounded.");
                Assert(elapsed.ElapsedMilliseconds < 10000, "Stderr flood deadlocked.");
                ProcessStartInfo hang = new ProcessStartInfo {
                    FileName = Environment.GetCommandLineArgs()[0], Arguments = "--hang",
                    UseShellExecute = false, RedirectStandardOutput = true,
                    RedirectStandardError = true, CreateNoWindow = true
                };
                elapsed.Restart();
                bool timedOut = false;
                try { BoundedProcess.Run(hang, 200, null, null); }
                catch (System.TimeoutException) { timedOut = true; }
                Assert(timedOut && elapsed.ElapsedMilliseconds < 5000,
                    "Apply timeout did not kill and reap its child.");
                Console.WriteLine("PASS: local pipe ACL and package bound");
                return 0;
            }
            catch (Exception error) { Console.Error.WriteLine("FAIL: " + error.Message); return 1; }
        }

        private static bool HasRule(PipeSecurity security, WellKnownSidType type,
            AccessControlType access)
        {
            return HasRule(security, new SecurityIdentifier(type, null), access);
        }

        private static bool HasRule(PipeSecurity security, SecurityIdentifier sid,
            AccessControlType access)
        {
            foreach (PipeAccessRule rule in security.GetAccessRules(true, false,
                typeof(SecurityIdentifier)))
                if (rule.IdentityReference.Equals(sid) && rule.AccessControlType == access)
                    return true;
            return false;
        }

        private static void Assert(bool condition, string message)
        { if (!condition) throw new InvalidOperationException(message); }
    }
}
