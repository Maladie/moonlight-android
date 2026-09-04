using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace MoonWaker.WindowsLogin
{
    internal static class SelfTestProgram
    {
        private const string ProfileId = "living-room";
        private const string Sid = "S-1-5-21-100-200-300-1001";
        private const string OtherSid = "S-1-5-21-100-200-300-1002";
        private const string Account = "TESTPC\\Player";
        private const string Password = "home-test-password";

        private static int passed;

        private static int Main()
        {
            try
            {
                Run("management uses injected validation, secret and session backends", TestManagement);
                Run("attempts are bound, observable, idempotent and single-use", TestAttemptLifecycle);
                Run("provider failure deletes the credential and prevents retry", TestProviderFailure);
                Run("attempt expiry is terminal", TestExpiry);
                Run("MWLB/MWLR v1 framing matches the Configurator contract", TestWireProtocol);
                Run("session classification handles lock and another active user", TestSessionClassification);
                Run("pipe ACLs keep all three channels local and separated", TestPipeSecurity);
                Run("capability requires a registered compatible provider", TestCapability);
                Run("SID remains authoritative when account display metadata changes", TestSidIdentity);
                Console.WriteLine("PASS: " + passed + " Login Broker tests");
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine("FAIL: " + error.Message);
                return 1;
            }
        }

        private static void TestManagement()
        {
            Harness harness = new Harness();
            using (BrokerReply accounts = Management(harness.Core,
                BrokerCore.ManagementListAccounts))
            {
                AssertOk(accounts, "ready");
                string json = (string)accounts.Extra[3];
                Assert(json.Contains("\"sid\":\"" + Sid + "\"") &&
                    json.Contains("\"account_name\":\"TESTPC\\\\Player\""),
                    "account catalog did not return compact JSON");
            }
            Configure(harness);
            Assert(harness.Accounts.ValidationCount == 1, "configure did not validate LogonUser input");
            Assert(harness.Secrets.Contains(ProfileId), "configure did not store the credential");

            using (BrokerReply reply = Management(harness.Core, BrokerCore.ManagementTest,
                1, ProfileId, 2, Sid, 3, Account))
                AssertOk(reply, "ready");
            Assert(harness.Accounts.ValidationCount == 2, "test did not validate the stored credential");

            harness.Sessions.State = "locked";
            using (BrokerReply reply = Management(harness.Core, BrokerCore.ManagementSessionState,
                1, ProfileId, 2, Sid, 3, Account))
                AssertOk(reply, "locked");
            using (BrokerReply reply = Gateway(harness.Core, BrokerCore.GatewayProfileState,
                1, ProfileId, 2, Sid, 3, Account))
            {
                AssertOk(reply, "locked");
                Assert((string)reply.Extra[3] == "ready" &&
                    (string)reply.Extra[4] == "none", "gateway profile state lost credential readiness");
            }

            using (BrokerReply reply = Management(harness.Core, BrokerCore.ManagementCredentialState,
                1, ProfileId, 2, Sid, 3, Account))
                AssertOk(reply, "ready");

            using (BrokerReply reply = Management(harness.Core, BrokerCore.ManagementDelete,
                1, ProfileId, 2, Sid, 3, Account, 5, "nonce", 6, "1"))
                AssertOk(reply, "deleted");
            Assert(!harness.Secrets.Contains(ProfileId), "delete left the credential in the secret backend");
        }

        private static void TestAttemptLifecycle()
        {
            Harness harness = new Harness();
            Configure(harness);
            string attemptId;
            using (BrokerReply reply = Begin(harness, "request-one"))
            {
                AssertOk(reply, "pending");
                attemptId = (string)reply.Extra[3];
            }
            using (BrokerReply repeat = Begin(harness, "request-one"))
            {
                AssertOk(repeat, "pending");
                Assert((string)repeat.Extra[3] == attemptId, "duplicate begin created a second attempt");
            }
            Assert(harness.Signals == 1, "new pending attempt did not signal exactly once");

            using (BrokerReply observed = Provider(harness.Core, BrokerCore.ProviderObserve))
            {
                AssertOk(observed, "pending");
                Assert((string)observed.Extra[3] == attemptId, "provider observed a different attempt");
                Assert((string)observed.Extra[4] == "android-tv", "provider did not receive client binding");
                Assert((string)observed.Extra[5] == ProfileId, "provider did not receive profile binding");
                Assert((string)observed.Extra[6] == "request-one", "provider did not receive request binding");
            }

            char[] issued;
            BrokerReply acquired = Provider(harness.Core, BrokerCore.ProviderAcquire,
                1, attemptId, 2, "android-tv", 3, ProfileId, 4, "request-one");
            AssertOk(acquired, "credential_issued");
            issued = (char[])acquired.Extra[4];
            Assert(new string(issued) == Password, "provider received the wrong password");
            acquired.Dispose();
            Assert(AllZero(issued), "provider response did not clear its password buffer");

            using (BrokerReply duplicateAcquire = Provider(harness.Core, BrokerCore.ProviderAcquire,
                1, attemptId, 2, "android-tv", 3, ProfileId, 4, "request-one"))
                AssertFail(duplicateAcquire, "action_required", "credential_already_issued");

            using (BrokerReply wrongBinding = Gateway(harness.Core, BrokerCore.GatewayAttemptState,
                1, "android-tv", 2, ProfileId, 3, "wrong-request", 4, attemptId))
                AssertFail(wrongBinding, "action_required", "attempt_binding_mismatch");

            using (BrokerReply reported = Provider(harness.Core, BrokerCore.ProviderReport,
                1, attemptId, 2, "android-tv", 3, ProfileId, 4, "request-one", 5, "success"))
                AssertOk(reported, "completed");

            using (BrokerReply second = Begin(harness, "request-cancel"))
                attemptId = (string)second.Extra[3];
            using (BrokerReply cancelled = Gateway(harness.Core, BrokerCore.GatewayCancelAttempt,
                1, "android-tv", 2, ProfileId, 3, "request-cancel", 4, attemptId))
                AssertFail(cancelled, "action_required", "attempt_cancelled");
            using (BrokerReply repeatCancel = Gateway(harness.Core, BrokerCore.GatewayCancelAttempt,
                1, "android-tv", 2, ProfileId, 3, "request-cancel", 4, attemptId))
                AssertFail(repeatCancel, "action_required", "attempt_cancelled");
            using (BrokerReply idle = Provider(harness.Core, BrokerCore.ProviderObserve))
                AssertOk(idle, "idle");
        }

        private static void TestProviderFailure()
        {
            Harness harness = new Harness();
            Configure(harness);
            string attemptId;
            using (BrokerReply begin = Begin(harness, "request-failure"))
                attemptId = (string)begin.Extra[3];
            using (BrokerReply acquire = Provider(harness.Core, BrokerCore.ProviderAcquire,
                1, attemptId, 2, "android-tv", 3, ProfileId, 4, "request-failure"))
                AssertOk(acquire, "credential_issued");
            using (BrokerReply failed = Provider(harness.Core, BrokerCore.ProviderReport,
                1, attemptId, 2, "android-tv", 3, ProfileId, 4, "request-failure",
                5, "failure", 6, "logon_failed"))
                AssertFail(failed, "action_required", "logon_failed");
            Assert(!harness.Secrets.Contains(ProfileId), "failed provider report retained the credential");
            using (BrokerReply replay = Begin(harness, "request-failure"))
            {
                AssertFail(replay, "action_required", "logon_failed");
                Assert((string)replay.Extra[3] == attemptId,
                    "failed request replay did not return the same attempt");
            }
            Assert(harness.Signals == 1, "failed request replay submitted another attempt");
            using (BrokerReply retry = Begin(harness, "request-two"))
                AssertFail(retry, "action_required", "credential_missing");
            using (BrokerReply state = Gateway(harness.Core, BrokerCore.GatewayAttemptState,
                1, "android-tv", 2, ProfileId, 3, "request-failure", 4, attemptId))
                AssertFail(state, "action_required", "logon_failed");
        }

        private static void TestExpiry()
        {
            DateTime now = new DateTime(2026, 9, 4, 8, 0, 0, DateTimeKind.Utc);
            Harness harness = new Harness(delegate { return now; }, TimeSpan.FromSeconds(10));
            Configure(harness);
            string attemptId;
            using (BrokerReply begin = Begin(harness, "request-expiry"))
                attemptId = (string)begin.Extra[3];
            now = now.AddSeconds(11);
            using (BrokerReply state = Gateway(harness.Core, BrokerCore.GatewayAttemptState,
                1, "android-tv", 2, ProfileId, 3, "request-expiry", 4, attemptId))
                AssertFail(state, "action_required", "attempt_expired");
            using (BrokerReply observe = Provider(harness.Core, BrokerCore.ProviderObserve))
                AssertOk(observe, "idle");
        }

        private static void TestWireProtocol()
        {
            Dictionary<byte, string> fields = new Dictionary<byte, string> {
                { 1, ProfileId }, { 2, Sid }, { 3, Account }
            };
            byte[] wire = RequestBytes(BrokerCore.ManagementCredentialState, fields);
            using (MemoryStream stream = new MemoryStream(wire, false))
            using (PipeRequest request = PipeProtocol.ReadRequest(stream, 1000))
            {
                Assert(request.Operation == BrokerCore.ManagementCredentialState, "operation byte changed");
                Assert(request.Text(1, 64) == ProfileId, "field id changed");
                Assert(request.Text(2, 184) == Sid, "little-endian field length changed");
            }

            using (MemoryStream stream = new MemoryStream())
            using (BrokerReply reply = BrokerReply.Ok("ready"))
            {
                PipeProtocol.WriteReply(stream, reply, 1000);
                byte[] response = stream.ToArray();
                Assert(Encoding.ASCII.GetString(response, 0, 4) == "MWLR", "response magic changed");
                Assert(response[4] == 1 && response[5] == 0 && response[6] == 2 && response[7] == 0,
                    "response v1 header changed");
                Dictionary<byte, string> decoded = ResponseFields(response);
                Assert(decoded[1] == "ready" && decoded[2] == "none", "response fields changed");
            }
        }

        private static void TestSessionClassification()
        {
            ProfileIdentity identity = new ProfileIdentity(ProfileId, Sid, Account);
            Assert(WindowsSessionStateBackend.Classify(identity, new[] {
                new LoginSessionSnapshot { Sid = OtherSid,
                    AccountName = "TESTPC\\SomeoneElse", State = "active" }
            }) == "other_user_active", "another active user was not detected");
            Assert(WindowsSessionStateBackend.Classify(identity, new[] {
                new LoginSessionSnapshot { Sid = Sid,
                    AccountName = Account, State = "active", Locked = true }
            }) == "locked", "locked target was reported ready");
            Assert(WindowsSessionStateBackend.Classify(identity, new[] {
                new LoginSessionSnapshot { Sid = Sid, AccountName = "TESTPC\\OldName", State = "active" },
                new LoginSessionSnapshot { Sid = OtherSid,
                    AccountName = "TESTPC\\SomeoneElse", State = "active" }
            }) == "active", "ready target was hidden by another session");
            Assert(WindowsSessionStateBackend.Classify(identity, new[] {
                new LoginSessionSnapshot { Sid = Sid, AccountName = Account, State = "disconnected" }
            }) == "disconnected", "disconnected target state changed");
            Assert(WindowsSessionStateBackend.Classify(identity, new[] {
                new LoginSessionSnapshot { AccountName = Account, State = "active" }
            }) == "unknown", "an unresolved active session was matched by display name");
        }

        private static void TestPipeSecurity()
        {
            SecurityIdentifier system = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
            SecurityIdentifier network = new SecurityIdentifier(WellKnownSidType.NetworkSid, null);
            SecurityIdentifier admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
            SecurityIdentifier users = new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null);
            SecurityIdentifier gatewayService = new SecurityIdentifier(
                "S-1-5-80-100-200-300-400-500");
            PipeSecurity management = PipeSecurityFactory.Create(BrokerChannel.Management);
            PipeSecurity gateway = PipeSecurityFactory.Create(
                BrokerChannel.Gateway, gatewayService);
            PipeSecurity provider = PipeSecurityFactory.Create(BrokerChannel.Provider);
            Assert(HasRule(management, system, AccessControlType.Allow), "management excludes LocalSystem");
            Assert(HasRule(management, admins, AccessControlType.Allow), "management excludes administrators");
            Assert(HasRule(gateway, gatewayService, AccessControlType.Allow),
                "gateway excludes its restricted service identity");
            Assert(HasRule(gateway, gatewayService, AccessControlType.Allow,
                    PipeAccessRights.CreateNewInstance),
                "gateway client cannot open the pipe with GENERIC_WRITE");
            string pipeName = "MoonWakerLoginBroker.AclTest." + Guid.NewGuid().ToString("N");
            SecurityIdentifier currentUser = WindowsIdentity.GetCurrent().User;
            using (NamedPipeServerStream server = new NamedPipeServerStream(pipeName,
                PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.None,
                0, 0, PipeSecurityFactory.Create(BrokerChannel.Gateway, currentUser)))
            using (SafeFileHandle client = CreateFile(@"\\.\pipe\" + pipeName,
                0x80000000u | 0x40000000u, 0, IntPtr.Zero, 3, 0, IntPtr.Zero))
                Assert(!client.IsInvalid,
                    "a GENERIC_READ/GENERIC_WRITE client cannot open the gateway pipe");
            Assert(!HasRule(gateway, users, AccessControlType.Allow),
                "gateway grants every authenticated user access");
            bool missingGatewaySidRejected = false;
            try { PipeSecurityFactory.Create(BrokerChannel.Gateway, null); }
            catch (InvalidOperationException) { missingGatewaySidRejected = true; }
            Assert(missingGatewaySidRejected,
                "gateway pipe used a broad fallback when its service SID was unavailable");
            Assert(HasRule(provider, system, AccessControlType.Allow), "provider excludes LocalSystem");
            Assert(!HasRule(provider, users, AccessControlType.Allow) &&
                !HasRule(provider, admins, AccessControlType.Allow), "provider grants a user principal access");
            Assert(HasRule(management, network, AccessControlType.Deny) &&
                HasRule(provider, network, AccessControlType.Deny),
                "a privileged user pipe allows network logons");
            Assert(!HasRule(gateway, network, AccessControlType.Allow) &&
                !HasRule(gateway, network, AccessControlType.Deny),
                "gateway pipe does not rely solely on its service-SID allow-list");
            EventWaitHandleSecurity attemptEvent =
                BrokerServiceHost.CreateAttemptEventSecurity();
            Assert(HasEventRule(attemptEvent, system, AccessControlType.Allow),
                "attempt event excludes the LocalSystem provider");
            Assert(HasEventRule(attemptEvent, network, AccessControlType.Deny),
                "attempt event allows network logons");
        }

        private static void TestCapability()
        {
            Harness ready = new Harness();
            using (BrokerReply reply = Gateway(ready.Core, BrokerCore.GatewayCapability))
            {
                AssertOk(reply, "ready");
                Assert((string)reply.Extra[3] == "1", "ready provider was not advertised");
            }

            Harness missing = new Harness(delegate { return DateTime.UtcNow; },
                TimeSpan.FromMinutes(2), delegate { return false; });
            using (BrokerReply reply = Gateway(missing.Core, BrokerCore.GatewayCapability))
            {
                Assert(!reply.Success && reply.State == "unavailable" &&
                    reply.Reason == "provider_unavailable" && (string)reply.Extra[3] == "0",
                    "missing provider was advertised as available");
            }
        }

        private static void TestSidIdentity()
        {
            const string renamedAccount = "TESTPC\\RenamedPlayer";
            Harness harness = new Harness();
            Configure(harness);
            using (BrokerReply state = Management(harness.Core,
                BrokerCore.ManagementCredentialState,
                1, ProfileId, 2, Sid, 3, renamedAccount))
                AssertOk(state, "ready");

            string attemptId;
            using (BrokerReply begin = Gateway(harness.Core, BrokerCore.GatewayBeginAttempt,
                1, "android-tv", 2, ProfileId, 3, "request-renamed",
                4, Sid, 5, renamedAccount))
            {
                AssertOk(begin, "pending");
                attemptId = (string)begin.Extra[3];
            }
            using (BrokerReply acquired = Provider(harness.Core, BrokerCore.ProviderAcquire,
                1, attemptId, 2, "android-tv", 3, ProfileId, 4, "request-renamed"))
            {
                AssertOk(acquired, "credential_issued");
                Assert((string)acquired.Extra[3] == renamedAccount,
                    "provider received stale stored account metadata");
                Assert((string)acquired.Extra[5] == Sid,
                    "provider received a different authoritative SID");
            }
        }

        private static void Configure(Harness harness)
        {
            char[] password = Password.ToCharArray();
            try
            {
                using (BrokerReply reply = Management(harness.Core, BrokerCore.ManagementConfigure,
                    1, ProfileId, 2, Sid, 3, Account, 4, password))
                    AssertOk(reply, "ready");
            }
            finally { Array.Clear(password, 0, password.Length); }
        }

        private static BrokerReply Begin(Harness harness, string requestId)
        {
            return Gateway(harness.Core, BrokerCore.GatewayBeginAttempt,
                1, "android-tv", 2, ProfileId, 3, requestId, 4, Sid, 5, Account);
        }

        private static BrokerReply Management(BrokerCore core, byte operation, params object[] fields)
        {
            using (PipeRequest request = PipeRequest.FromText(operation, fields))
                return core.HandleManagement(request);
        }

        private static BrokerReply Gateway(BrokerCore core, byte operation, params object[] fields)
        {
            using (PipeRequest request = PipeRequest.FromText(operation, fields))
                return core.HandleGateway(request);
        }

        private static BrokerReply Provider(BrokerCore core, byte operation, params object[] fields)
        {
            using (PipeRequest request = PipeRequest.FromText(operation, fields))
                return core.HandleProvider(request);
        }

        private static byte[] RequestBytes(byte operation, Dictionary<byte, string> fields)
        {
            using (MemoryStream stream = new MemoryStream())
            {
                byte[] magic = Encoding.ASCII.GetBytes("MWLB");
                stream.Write(magic, 0, magic.Length);
                stream.WriteByte(1);
                stream.WriteByte(operation);
                stream.WriteByte((byte)fields.Count);
                stream.WriteByte(0);
                foreach (KeyValuePair<byte, string> field in fields)
                {
                    byte[] value = Encoding.UTF8.GetBytes(field.Value);
                    byte[] header = new byte[5];
                    header[0] = field.Key;
                    PipeProtocol.WriteInt32(header, 1, value.Length);
                    stream.Write(header, 0, header.Length);
                    stream.Write(value, 0, value.Length);
                }
                return stream.ToArray();
            }
        }

        private static Dictionary<byte, string> ResponseFields(byte[] response)
        {
            Dictionary<byte, string> result = new Dictionary<byte, string>();
            int cursor = 8;
            for (int index = 0; index < response[6]; index++)
            {
                byte key = response[cursor];
                int length = PipeProtocol.ReadInt32(response, cursor + 1);
                cursor += 5;
                result[key] = Encoding.UTF8.GetString(response, cursor, length);
                cursor += length;
            }
            Assert(cursor == response.Length, "response has trailing bytes");
            return result;
        }

        private static bool HasRule(PipeSecurity security, SecurityIdentifier identity,
            AccessControlType type)
        {
            AuthorizationRuleCollection rules = security.GetAccessRules(true, false,
                typeof(SecurityIdentifier));
            foreach (PipeAccessRule rule in rules)
                if (rule.AccessControlType == type && identity.Equals(rule.IdentityReference)) return true;
            return false;
        }

        private static bool HasRule(PipeSecurity security, SecurityIdentifier identity,
            AccessControlType type, PipeAccessRights requiredRights)
        {
            AuthorizationRuleCollection rules = security.GetAccessRules(true, false,
                typeof(SecurityIdentifier));
            foreach (PipeAccessRule rule in rules)
                if (rule.AccessControlType == type && identity.Equals(rule.IdentityReference) &&
                        (rule.PipeAccessRights & requiredRights) == requiredRights) return true;
            return false;
        }

        private static bool HasEventRule(EventWaitHandleSecurity security,
            SecurityIdentifier identity, AccessControlType type)
        {
            AuthorizationRuleCollection rules = security.GetAccessRules(true, false,
                typeof(SecurityIdentifier));
            foreach (EventWaitHandleAccessRule rule in rules)
                if (rule.AccessControlType == type && identity.Equals(rule.IdentityReference)) return true;
            return false;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFile(string fileName,
            uint desiredAccess, uint shareMode, IntPtr securityAttributes,
            uint creationDisposition, uint flagsAndAttributes, IntPtr templateFile);

        private static bool AllZero(char[] value)
        {
            foreach (char character in value) if (character != '\0') return false;
            return true;
        }

        private static void AssertOk(BrokerReply reply, string state)
        {
            Assert(reply.Success && reply.State == state,
                "expected success/" + state + ", got " + reply.Success + "/" + reply.State + "/" + reply.Reason);
        }

        private static void AssertFail(BrokerReply reply, string state, string reason)
        {
            Assert(!reply.Success && reply.State == state && reply.Reason == reason,
                "expected failure/" + state + "/" + reason + ", got " +
                reply.Success + "/" + reply.State + "/" + reply.Reason);
        }

        private static void Assert(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException(message);
        }

        private static void Run(string name, Action test)
        {
            test();
            passed++;
            Console.WriteLine("PASS: " + name);
        }

        private sealed class Harness
        {
            internal readonly FakeAccountValidator Accounts = new FakeAccountValidator();
            internal readonly FakeSecretStore Secrets = new FakeSecretStore();
            internal readonly FakeSessions Sessions = new FakeSessions();
            internal readonly BrokerCore Core;
            internal int Signals;

            internal Harness() : this(delegate { return DateTime.UtcNow; },
                TimeSpan.FromMinutes(2), delegate { return true; }) { }

            internal Harness(Func<DateTime> now, TimeSpan lifetime)
                : this(now, lifetime, delegate { return true; }) { }

            internal Harness(Func<DateTime> now, TimeSpan lifetime, Func<bool> providerReady)
            {
                Accounts.Expected = new ProfileIdentity(ProfileId, Sid, Account);
                Accounts.ExpectedPassword = Password;
                Core = new BrokerCore(Accounts, Secrets, Sessions,
                    new AttemptLedger(now, lifetime, 16, delegate { Signals++; }), providerReady);
            }
        }

        private sealed class FakeAccountValidator : IAccountValidator
        {
            internal ProfileIdentity Expected;
            internal string ExpectedPassword;
            internal int ValidationCount;

            public IList<LocalAccountRecord> ListSupported()
            {
                return new List<LocalAccountRecord> {
                    new LocalAccountRecord(Expected.Sid, Expected.AccountName)
                };
            }

            public void Validate(ProfileIdentity identity, char[] password)
            {
                Assert(identity.Same(Expected), "validator received a different identity");
                Assert(new string(password) == ExpectedPassword, "validator received a different password");
                ValidationCount++;
            }
        }

        private sealed class FakeSecretStore : ISecretStore
        {
            private readonly Dictionary<string, CredentialMaterial> values =
                new Dictionary<string, CredentialMaterial>(StringComparer.Ordinal);

            public void Put(ProfileIdentity identity, char[] password)
            {
                Delete(identity.ProfileId);
                values[identity.ProfileId] = new CredentialMaterial(identity, password);
            }

            public bool TryRead(string profileId, out CredentialMaterial credential)
            {
                CredentialMaterial stored;
                if (!values.TryGetValue(profileId, out stored))
                {
                    credential = null;
                    return false;
                }
                credential = new CredentialMaterial(stored.Identity, stored.Password);
                return true;
            }

            public void Delete(string profileId)
            {
                CredentialMaterial stored;
                if (!values.TryGetValue(profileId, out stored)) return;
                values.Remove(profileId);
                stored.Dispose();
            }

            internal bool Contains(string profileId)
            {
                return values.ContainsKey(profileId);
            }
        }

        private sealed class FakeSessions : ISessionStateBackend
        {
            internal string State = "signed_out";
            public string GetState(ProfileIdentity identity) { return State; }
        }
    }
}
