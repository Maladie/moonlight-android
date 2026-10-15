using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.IO.Pipes;
using System.Management;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MoonWaker.HostControl;

namespace MoonWaker.HostConfigurator
{
    // The change channel and the elevated helper's change sets, without elevation: the channel on
    // real pipes in this process, validation and application on a gateway.json fixture in %TEMP%
    // with the Broker, provisioning, account list and Vibepollo replaced by stand-ins. With an
    // output directory it also renders the helper's window in both themes.
    internal static class ChangeTests
    {
        private static readonly string Self = WindowsIdentity.GetCurrent().User.Value;
        private const string SidA = "S-1-5-21-10-20-30-1001", SidB = "S-1-5-21-10-20-30-1002", SidC = "S-1-5-21-10-20-30-1003",
            SidLegacy = "S-1-5-21-10-20-30-1005";
        private static string root, config;
        private static int checks;

        [STAThread]
        private static int Main(string[] args)
        {
            string output = args.Length > 0 ? Path.GetFullPath(args[0]) : null;
            root = Path.Combine(Path.GetTempPath(), "MoonWakerChangeTests-" + Guid.NewGuid().ToString("N"));
            try
            {
                Directory.CreateDirectory(Path.Combine(root, "gateway"));
                config = Path.Combine(root, "gateway", "gateway.json");
                TestChannel();
                TestParse();
                TestPrepareAndApply();
                TestStaleAfterConfirmation();
                TestHandle();
                TestParentProcess();
                if (output != null) RenderWindows(output);
                Console.WriteLine("PASS change channel and helper change sets: " + checks + " checks" +
                    (output == null ? "." : "; helper windows rendered to " + output + "."));
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine("FAIL change channel and helper change sets: " + error);
                return 1;
            }
            finally
            {
                try { Directory.Delete(root, true); } catch { }
            }
        }

        // ---- Channel ----

        private static void TestChannel()
        {
            int self = Process.GetCurrentProcess().Id;
            SecurityIdentifier me = WindowsIdentity.GetCurrent().User;
            byte[] hello = Encoding.UTF8.GetBytes("{\"hello\":1}");

            // One exchange: the request arrives, the answer returns.
            string name = Pipe();
            bool called = false;
            Exception served = Serve(name, me, self, delegate(byte[] request, Func<bool> connected) {
                called = Encoding.UTF8.GetString(request) == "{\"hello\":1}";
                return Encoding.UTF8.GetBytes("{\"ok\":true}");
            }, 3000, 2000, delegate {
                byte[] answer = ChangeChannel.Exchange(name, self, delegate { return true; }, hello, 3000, 3000, 2000);
                Expect(Encoding.UTF8.GetString(answer) == "{\"ok\":true}", "the client reads the helper's answer");
            });
            Expect(served == null && called, "the helper reads one request and answers it");

            // Another account: the DACL names only the account that started the helper.
            name = Pipe();
            called = false;
            served = Serve(name, new SecurityIdentifier("S-1-5-21-1-2-3-4444"), self, delegate(byte[] request, Func<bool> connected) {
                called = true;
                return hello;
            }, 1500, 1000, delegate {
                Throws<UnauthorizedAccessException>(delegate {
                    ChangeChannel.Exchange(name, self, delegate { return true; }, hello, 1000, 1000, 1000);
                }, "another account cannot open the change channel");
            });
            Expect(served is TimeoutException && !called, "the helper gives up without a client of its account");

            // A group the account belongs to: the DACL admits it, the client's token user does not match.
            name = Pipe();
            called = false;
            served = Serve(name, new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null), self, delegate(byte[] request, Func<bool> connected) {
                called = true;
                return hello;
            }, 3000, 1000, delegate {
                Throws<Exception>(delegate {
                    ChangeChannel.Exchange(name, self, delegate { return true; }, hello, 3000, 2000, 1000);
                }, "a client admitted through a group gets no answer");
            });
            Expect(served is UnauthorizedAccessException && !called, "the helper checks the client's own account");

            // Another process of the account: the helper drops it unread.
            name = Pipe();
            served = Serve(name, me, 4, delegate(byte[] request, Func<bool> connected) { called = true; return hello; }, 3000, 1000, delegate {
                Throws<Exception>(delegate {
                    ChangeChannel.Exchange(name, self, delegate { return true; }, hello, 3000, 2000, 1000);
                }, "a client that is not the starting process gets no answer");
            });
            Expect(served is UnauthorizedAccessException && !called, "the helper refuses a client that did not start it");

            // A server that is not the started helper: Host Control sends nothing.
            name = Pipe();
            served = Serve(name, me, self, delegate(byte[] request, Func<bool> connected) { called = true; return hello; }, 3000, 1000, delegate {
                Throws<UnauthorizedAccessException>(delegate {
                    ChangeChannel.Exchange(name, 4, delegate { return true; }, hello, 3000, 2000, 1000);
                }, "Host Control refuses a server that is not the process it started");
            });
            Expect(served != null && !called, "nothing reaches the helper from a refused exchange");

            // Too large: refused from the length alone.
            name = Pipe();
            served = Serve(name, me, self, delegate(byte[] request, Func<bool> connected) { called = true; return hello; }, 3000, 1000, delegate {
                using (NamedPipeClientStream raw = Raw(name))
                {
                    int length = ChangeChannel.MaxRequestBytes + 1;
                    raw.Write(new byte[] { (byte)length, (byte)(length >> 8), (byte)(length >> 16), (byte)(length >> 24) }, 0, 4);
                    raw.Flush();
                    Thread.Sleep(300);
                }
            });
            Expect(served is InvalidDataException && !called, "a request over the size limit is refused");

            // Too slow: a request that stops halfway.
            name = Pipe();
            Stopwatch clock = Stopwatch.StartNew();
            served = Serve(name, me, self, delegate(byte[] request, Func<bool> connected) { called = true; return hello; }, 3000, 500, delegate {
                using (NamedPipeClientStream raw = Raw(name))
                {
                    raw.Write(new byte[] { 50, 0, 0, 0, (byte)'{' }, 0, 5);
                    raw.Flush();
                    Thread.Sleep(1500);
                }
            });
            Expect(served is TimeoutException && !called && clock.ElapsedMilliseconds < 3000,
                "a request that does not arrive in time is refused");

            // One instance: a second server of the same name cannot be created.
            name = Pipe();
            served = Serve(name, me, self, delegate(byte[] request, Func<bool> connected) { return hello; }, 800, 500, delegate {
                Throws<System.ComponentModel.Win32Exception>(delegate {
                    ChangeChannel.Serve(name, me, self, delegate(byte[] request, Func<bool> connected) { return hello; }, 100, 100);
                }, "a second instance of the channel cannot be created");
            });
            Expect(served is TimeoutException, "the first instance still waits for its client");

            // Host Control stops waiting: the helper sees it; Host Control does not claim a failure.
            name = Pipe();
            bool waiting = false, stillWaiting = true;
            Serve(name, me, self, delegate(byte[] request, Func<bool> connected) {
                waiting = connected();
                Stopwatch wait = Stopwatch.StartNew();
                while (connected() && wait.ElapsedMilliseconds < 3000) Thread.Sleep(20);
                stillWaiting = connected();
                return hello;
            }, 3000, 1000, delegate {
                Throws<AnswerLostException>(delegate {
                    ChangeChannel.Exchange(name, self, delegate { return true; }, hello, 3000, 300, 1000);
                }, "a request without an answer in time has an unknown outcome");
            });
            Expect(waiting && !stillWaiting, "the helper sees whether Host Control still waits for the answer");

            // The helper ends after reading the request: the outcome is unknown, not a failure.
            name = Pipe();
            Serve(name, me, self, delegate(byte[] request, Func<bool> connected) {
                throw new InvalidOperationException("The helper stops.");
            }, 3000, 1000, delegate {
                Throws<AnswerLostException>(delegate {
                    ChangeChannel.Exchange(name, self, delegate { return true; }, hello, 3000, 3000, 1000);
                }, "a helper that ends without answering leaves the outcome unknown");
            });
        }

        private static string Pipe() { return ChangeChannel.PipeName(Guid.NewGuid()); }

        private static NamedPipeClientStream Raw(string name)
        {
            NamedPipeClientStream pipe = new NamedPipeClientStream(".", name, PipeAccessRights.ReadWrite,
                PipeOptions.None, TokenImpersonationLevel.Identification, HandleInheritability.None);
            pipe.Connect(3000);
            return pipe;
        }

        // Runs the server on its own thread while client runs here; returns what the server threw.
        private static Exception Serve(string name, SecurityIdentifier client, int clientPid,
            Func<byte[], Func<bool>, byte[]> answer,
            int connect, int transfer, Action run)
        {
            Exception error = null;
            Thread server = new Thread(delegate() {
                try { ChangeChannel.Serve(name, client, clientPid, answer, connect, transfer); }
                catch (Exception e) { error = e; }
            });
            server.Start();
            Thread.Sleep(100);
            run();
            server.Join();
            return error;
        }

        // ---- Parsing ----

        private static void TestParse()
        {
            string revision = new string('a', 64);
            ChangeSet set = ChangeSet.Parse(Request(revision,
                Edit("parent", SidA, "Mariusz", true), Pin("parent", SidA, "1234"),
                Grant("salon", "gosia", SidB, true, false, false), Change("client_remove", "client_id", "sypialnia")));
            Expect(set.Changes.Count == 4 && set.Changes[1].Pin == "1234" && set.Changes[2].UseProfile && !set.IsCompound,
                "a valid set of registry changes parses");
            Expect(ChangeSet.Parse(Request("", Change("vibepollo_token"))).IsCompound, "the Vibepollo token parses alone");

            Rejected(Encoding.UTF8.GetBytes("not json"), "a request that is not JSON");
            Rejected(Encoding.UTF8.GetBytes("{\"protocol\":\"other/1\",\"registry_revision\":\"\",\"changes\":[]}"),
                "another protocol");
            Rejected(Request(revision), "an empty set");
            Rejected(Request(revision, Change("format_disk")), "an unknown change");
            Dictionary<string, object> extra = Edit("parent", SidA, "Mariusz", true);
            extra["owner_sid"] = SidB;
            Rejected(Request(revision, extra), "an unknown field");
            Dictionary<string, object> missing = Edit("parent", SidA, "Mariusz", true);
            missing.Remove("enabled");
            Rejected(Request(revision, missing), "a missing field");
            Rejected(Request(revision, Edit("parent", "PC\\mariusz", "Mariusz", true)), "an account that is not a SID");
            Rejected(Request(revision, Edit("../parent", SidA, "Mariusz", true)), "an invalid profile id");
            Rejected(Request(revision, Edit("parent", SidA, "", true)), "an empty name");
            Rejected(Request(revision, Edit("parent", SidA, "Name\u0007", true)), "a name with a control character");
            Rejected(Request(revision, Pin("parent", SidA, "12a4")), "a PIN that is not four digits");
            Rejected(Request(revision, Change("remote_sign_in", "profile_id", "parent", "account_sid", SidA, "action", "enable_all")),
                "an unknown remote sign-in action");
            Dictionary<string, object> notBool = Grant("salon", "gosia", SidB, true, false, false);
            notBool["use_profile"] = "true";
            Rejected(Request(revision, notBool), "a grant that is not a boolean");
            Rejected(Request(revision, Pin("parent", SidA, "1234"), Change("pin_remove", "profile_id", "parent", "account_sid", SidA)),
                "two PIN changes of one profile");
            Rejected(Request(revision, Grant("salon", "gosia", SidB, true, false, false), Change("client_remove", "client_id", "salon")),
                "grants for a TV whose pairing is revoked");
            Rejected(Request(revision, Grant("salon", "gosia", SidB, true, false, false), Grant("sypialnia", "gosia", SidA, true, false, false)),
                "one profile's grants naming two accounts");
            Rejected(Request(revision, Change("profile_add", "account_sid", SidC, "display_name", "C"), Edit("parent", SidA, "M", true)),
                "a compound change with another change");
            Rejected(Request(revision, Change("vibepollo_token")), "the Vibepollo token with a registry revision");
            Rejected(Request("", Edit("parent", SidA, "Mariusz", true)), "a registry change without a revision");
            Rejected(Request(revision, ChildSave("parent", "", 1, Draft("Ania", 0, 1440, 60)),
                ChildSave("parent", "", 1, Draft("Staś", 0, 1440, 60))), "two child requests in one set");
            Rejected(Request(revision, ChildSave("parent", "", 1, Draft("Ania", 600, 500, 60))), "a child window that ends before it starts");
            Rejected(Request(revision, ChildSave("parent", "", 1, Draft("Ania", 0, 1440, 61))), "a limit of partial minutes");
            Dictionary<string, object> games = Draft("Ania", 0, 1440, 0);
            games["allowed_game_keys"] = new object[] { "parent/steam:1" };
            Rejected(Request(revision, ChildSave("parent", "", 1, games)), "a child's games in the profile editor");
            Dictionary<string, object> unknown = Draft("Ania", 0, 1440, 0);
            unknown["owner_sid"] = SidA;
            Rejected(Request(revision, ChildSave("parent", "", 1, unknown)), "an unknown key of a child draft");
            Dictionary<string, object> day = Draft("Ania", 0, 1440, 0);
            ((Dictionary<string, object>)((Dictionary<string, object>)((Dictionary<string, object>)day["schedule"])
                ["weekdays"])["mon"])["games"] = 1;
            Rejected(Request(revision, ChildSave("parent", "", 1, day)), "an unknown key of a child's day");
            Dictionary<string, object> schedule = Draft("Ania", 0, 1440, 0);
            ((Dictionary<string, object>)schedule["schedule"])["holidays"] = new object[0];
            Rejected(Request(revision, ChildSave("parent", "", 1, schedule)), "an unknown key of a child's schedule");
            foreach (string hidden in new[] { "Ania\u202e", "An\u2028ia", "An\u2029ia", "A\u200bnia", "An\u0085ia" })
            {
                Rejected(Request(revision, Edit("parent", SidA, hidden, true)), "a profile name with a hidden character");
                Rejected(Request(revision, ChildSave("parent", "", 1, Draft(hidden, 0, 1440, 0))),
                    "a child name with a hidden character");
            }
        }

        private static void Rejected(byte[] request, string what)
        {
            Throws<Exception>(delegate { ChangeSet.Parse(request); }, "parsing refuses " + what);
        }

        // ---- Validation and application on a fixture ----

        private static void TestPrepareAndApply()
        {
            Fixture();
            FakeBroker broker = new FakeBroker();
            List<string> provisioned = new List<string>();
            ChangeEffects effects = Effects(broker, provisioned);
            GatewayRegistryStore store = new GatewayRegistryStore(config, 500);
            ChangeApplier applier = new ChangeApplier(store, effects);

            // Stale: a change decided on another revision is refused before anything is written.
            string before = File.ReadAllText(config);
            Throws<StaleRegistryException>(delegate {
                applier.Prepare(ChangeSet.Parse(Request(new string('0', 64), Edit("parent", SidA, "X", true))));
            }, "a set built on an older revision is refused");

            // Every rule of the old forms, now in the helper.
            RejectedByHelper(applier, Edit("parent", SidB, "X", true), "a profile edit naming another account");
            RejectedByHelper(applier, Edit("ania", SidA, "X", true), "a profile edit of a child");
            RejectedByHelper(applier, Grant("salon", "gosia", SidB, true, true, false), "remote sign-in before the profile's password");
            RejectedByHelper(applier, Grant("salon", "gosia", SidB, false, true, false), "remote sign-in without use");
            RejectedByHelper(applier, Grant("salon", "gosia", SidB, true, false, true), "child management without the parent's PIN");
            RejectedByHelper(applier, Grant("salon", "ania", SidA, true, false, true), "child management by a child");
            RejectedByHelper(applier, Grant("salon", "ania", SidB, true, false, false), "a child's grant naming another account");
            RejectedByHelper(applier, Grant("gone", "gosia", SidB, true, false, false), "a grant for a TV that is not paired");
            RejectedByHelper(applier, Change("client_remove", "client_id", "gone"), "revoking a TV that is not paired");
            RejectedByHelper(applier, Change("profile_add", "account_sid", "S-1-5-21-10-20-30-1999", "display_name", "X"),
                "an account that is not a supported local account");
            RejectedByHelper(applier, Change("profile_add", "account_sid", SidB, "display_name", "X"), "a second profile for an account");
            RejectedByHelper(applier, ChildSave("gosia", "", 0, Draft("Kid", 0, 1440, 0)), "a child of a parent without a PIN");
            RejectedByHelper(applier, ChildSave("parent", "", 0, Draft("Kid", 0, 1440, 0)), "a child request on an older child revision");
            RejectedByHelper(applier, ChildSave("parent", "stas", 1, Draft("Kid", 0, 1440, 0)), "a child that is not the parent's");
            RejectedByHelper(applier, Change("remote_sign_in", "profile_id", "legacy", "account_sid", SidLegacy, "action", "set_password"),
                "remote sign-in for an unresolved account");
            RejectedByHelper(applier, Change("profile_remove", "profile_id", "parent", "account_sid", SidB), "removing with another account");
            broker.Available = false;
            RejectedByHelper(applier, Change("remote_sign_in", "profile_id", "parent", "account_sid", SidA, "action", "set_password"),
                "a password while Login Broker is unavailable");
            broker.Available = true;
            Expect(File.ReadAllText(config) == before && provisioned.Count == 0, "refused sets changed nothing");

            // Registry changes: one transaction, a summary without the PIN.
            ChangePlan plan = Plan(applier, Edit("parent", SidA, "Mariusz K", true), Pin("gosia", SidB, "4826"),
                Grant("salon", "parent", SidA, true, true, true), Grant("sypialnia", "ania", SidA, true, false, false),
                Change("client_remove", "client_id", "kuchnia"));
            string summary = String.Join("\n", plan.Summary.ToArray()) + String.Join("\n", plan.Warnings.ToArray());
            Expect(!summary.Contains("4826") && summary.Contains("Mariusz K") && summary.Contains("nowy PIN aplikacji") &&
                summary.Contains("zdalne logowanie: tak") && summary.Contains("Cofnij parowanie TV „Kuchnia”") &&
                summary.Contains("uprzywilejowane prawo"), "the helper's summary names every change and no PIN:\n" + summary);
            Expect(File.ReadAllText(config) == before, "preparing writes nothing");
            applier.Apply(plan);
            Dictionary<string, object> document = Read();
            Dictionary<string, object> parent = Entry(document, "parent"), gosia = Entry(document, "gosia");
            Expect((string)parent["display_name"] == "Mariusz K" && gosia.ContainsKey("pin_verifier") &&
                !File.ReadAllText(config).Contains("4826"), "the profile edit and the PIN are saved");
            Expect(Grants(document, "salon", "parent") == "use_profile,remote_sign_in,manage_children" &&
                Grants(document, "sypialnia", "ania") == "use_profile" && Client(document, "kuchnia") == null,
                "the grants and the revoked pairing are saved");
            Expect(Directory.GetFiles(Path.Combine(root, "gateway"), "*.tmp").Length == 0, "no temporary file is left");

            // Names from TVs and older registries: no line break or bidi control reaches the window.
            Dictionary<string, object> hostile = Read();
            Client(hostile, "salon")["name"] = "Salon\n\u202eVT\u2028\u2029\u2066x\u0085" + new string('y', 200);
            Entry(hostile, "parent")["display_name"] = "Mar\riusz\u200f\u202e";
            File.WriteAllText(config, new JavaScriptSerializer().Serialize(hostile), new UTF8Encoding(false));
            plan = Plan(applier, Grant("salon", "parent", SidA, false, false, false), Pin("parent", SidA, "1234"));
            foreach (string line in plan.Summary.Concat(plan.Warnings))
                foreach (char character in line)
                {
                    System.Globalization.UnicodeCategory category = Char.GetUnicodeCategory(character);
                    Expect(category != System.Globalization.UnicodeCategory.Control &&
                        category != System.Globalization.UnicodeCategory.Format &&
                        category != System.Globalization.UnicodeCategory.LineSeparator &&
                        category != System.Globalization.UnicodeCategory.ParagraphSeparator,
                        "a summary line holds no control, bidi or separator character: " + line);
                }
            summary = String.Join("\n", plan.Summary.ToArray());
            Expect(summary.Contains("„SalonVTx") && summary.Contains("Mariusz") && !summary.Contains(new string('y', 64)),
                "hidden characters are dropped and a long name is cut:\n" + summary);
            Fixture();
            Expect(Plan(applier, ChildSave("parent", "", 1, Draft("Kid", 0, 1440, 0))).Summary[0].Contains("awatar „Niebieski”"),
                "a new child's summary names its avatar");
            Expect(Plan(applier, ChildSave("parent", "ania", 1, Draft("Ania", 0, 1440, 0))).Summary[0]
                .Contains("awatar „Zielony” → „Niebieski”"), "a child's summary names a changed avatar");
            Dictionary<string, object> same = Draft("Ania", 0, 1440, 0);
            same["avatar_id"] = "green";
            Expect(!Plan(applier, ChildSave("parent", "ania", 1, same)).Summary[0].Contains("awatar"),
                "an unchanged avatar is not reported");
            applier.Apply(Plan(applier, Edit("parent", SidA, "Mariusz K", true), Pin("gosia", SidB, "4826"),
                Grant("salon", "parent", SidA, true, true, true), Grant("sypialnia", "ania", SidA, true, false, false),
                Change("client_remove", "client_id", "kuchnia")));

            // A child: created, edited, removed through its own revision.
            int revision = (int)Entry(Read(), "parent")["children_policy_revision"];
            applier.Apply(Plan(applier, ChildSave("parent", "", revision, Draft("Staś", 480, 1200, 3600))));
            document = Read();
            string created = null;
            foreach (KeyValuePair<string, object> item in (Dictionary<string, object>)document["profiles"])
                if (((Dictionary<string, object>)item.Value).ContainsKey("kind") &&
                    (string)((Dictionary<string, object>)item.Value)["kind"] == "child" &&
                    (string)((Dictionary<string, object>)item.Value)["name"] == "Staś") created = item.Key;
            Expect(created != null && (int)Entry(document, "parent")["children_policy_revision"] == revision + 1,
                "a new child is created and the parent's revision advances");
            applier.Apply(Plan(applier, ChildSave("parent", created, revision + 1, Draft("Staś K", 480, 1200, 1800))));
            Expect((string)Entry(Read(), created)["name"] == "Staś K", "a child is edited");
            applier.Apply(Plan(applier, Change("child_remove", "profile_id", "parent", "child_profile_id", created,
                "expected_revision", revision + 2)));
            Expect(!((Dictionary<string, object>)Read()["profiles"]).ContainsKey(created), "a child is removed");

            // Remote sign-in: the password goes only to the Broker; disable that Broker does not confirm.
            plan = Plan(applier, Change("remote_sign_in", "profile_id", "gosia", "account_sid", SidB, "action", "set_password"));
            Expect(plan.NeedsPassword && plan.Summary.Exists(line => line.Contains("Dane logowania")),
                "setting a password asks the helper's window for it");
            plan.Password = "Tajne hasło 1".ToCharArray();
            applier.Apply(plan);
            Expect(broker.Password == "Tajne hasło 1" && (bool)Entry(Read(), "gosia")["remote_sign_in_enabled"] &&
                !File.ReadAllText(config).Contains("Tajne"), "the password reaches the Broker and only the policy bit is saved");
            Expect(applier.Apply(Plan(applier, Change("remote_sign_in", "profile_id", "gosia", "account_sid", SidB, "action", "test")))
                .Contains("zweryfikował"), "the saved credential is tested");
            applier.Apply(Plan(applier, Grant("salon", "gosia", SidB, true, true, false)));
            broker.DeleteFails = true;
            Throws<PartialChangeException>(delegate {
                applier.Apply(Plan(applier, Change("remote_sign_in", "profile_id", "gosia", "account_sid", SidB, "action", "disable")));
            }, "a disable the Broker does not confirm is partial");
            Expect((bool)Entry(Read(), "gosia")["remote_sign_in_enabled"] && Grants(Read(), "salon", "gosia") == "use_profile",
                "the grants stay revoked and the policy bit records the pending cleanup");
            broker.DeleteFails = false;
            applier.Apply(Plan(applier, Change("remote_sign_in", "profile_id", "gosia", "account_sid", SidB, "action", "disable")));
            Expect(!(bool)Entry(Read(), "gosia")["remote_sign_in_enabled"], "remote sign-in is disabled");

            // A new profile: reserved, provisioned, enabled; a failed provisioning leaves a tombstone.
            string message = applier.Apply(Plan(applier, Change("profile_add", "account_sid", SidC, "display_name", "Celina")));
            Dictionary<string, object> added = null;
            foreach (object value in ((Dictionary<string, object>)Read()["profiles"]).Values)
                if (((Dictionary<string, object>)value).ContainsKey("windows_account_sid") &&
                    (string)((Dictionary<string, object>)value)["windows_account_sid"] == SidC) added = (Dictionary<string, object>)value;
            Expect(added != null && (bool)added["enabled"] && provisioned.Count == 1 && message.Contains("PC\\celina"),
                "a profile is added for the chosen account");
            string addedId = (string)added["id"];
            effects.Accounts = delegate { return new List<LocalAccount> { new LocalAccount {
                Name = "dawid", QualifiedName = "PC\\dawid", Sid = "S-1-5-21-10-20-30-1004" } }; };
            effects.Provision = delegate { throw new InvalidOperationException("Brak miejsca."); };
            Throws<InvalidOperationException>(delegate {
                applier.Apply(Plan(applier, Change("profile_add", "account_sid", "S-1-5-21-10-20-30-1004", "display_name", "Dawid")));
            }, "a failed provisioning fails the change");
            Expect(File.ReadAllText(config).Contains("deletion_tombstone"), "a failed add leaves its reservation tombstoned");

            // Removal: the Broker secret first, then the tombstone, the files and the entry.
            effects.RemoveArtifacts = delegate(GatewayRegistryStore unused, ProfileRecord profile) { provisioned.Remove(profile.Id); };
            ChangePlan removal = Plan(applier, Change("profile_remove", "profile_id", addedId, "account_sid", SidC));
            Expect(removal.Danger && removal.ConfirmLabel == "Usuń dane profilu", "a removal is confirmed as a danger");
            applier.Apply(removal);
            Expect(!((Dictionary<string, object>)Read()["profiles"]).ContainsKey(addedId) && provisioned.Count == 0,
                "the profile is removed");

            // Vibepollo: the window's input reaches the saver; missing files make it partial.
            ChangePlan token = applier.Prepare(ChangeSet.Parse(Request("", Change("vibepollo_token"))));
            Expect(token.NeedsVibepollo && token.Vibepollo.Url == VibepolloMachineApi.DefaultBaseUrl, "the token asks for its input");
            token.Vibepollo.Username = "admin";
            token.Vibepollo.Password = "secret".ToCharArray();
            Expect(applier.Apply(token).StartsWith("Token zapisany"), "the token is saved");
            effects.SaveVibepolloToken = delegate { return new List<string> { @"C:\Vibepollo\config\sunshine_state.json" }; };
            Throws<PartialChangeException>(delegate { applier.Apply(token); }, "missing Vibepollo files make the save partial");
            token.Vibepollo.Password = new char[0];
            Throws<InvalidOperationException>(delegate { VibepolloMachineApi.SaveToken(store, token.Vibepollo); },
                "a token request without the administrator's password is refused");
        }

        private static void RejectedByHelper(ChangeApplier applier, Dictionary<string, object> change, string what)
        {
            bool vibepollo = (string)change["type"] == "vibepollo_token";
            Throws<InvalidOperationException>(delegate {
                applier.Prepare(ChangeSet.Parse(Request(vibepollo ? "" : Revision(), change)));
            }, "the helper refuses " + what);
        }

        private static ChangePlan Plan(ChangeApplier applier, params Dictionary<string, object>[] changes)
        {
            return applier.Prepare(ChangeSet.Parse(Request(Revision(), changes)));
        }

        // ---- The registry changes while the helper's window is open ----

        private static void TestStaleAfterConfirmation()
        {
            Fixture();
            FakeBroker broker = new FakeBroker();
            List<string> provisioned = new List<string>();
            ChangeEffects effects = Effects(broker, provisioned);
            ChangeApplier applier = new ChangeApplier(new GatewayRegistryStore(config, 500), effects);
            Stale(applier.Prepare(ChangeSet.Parse(Request(Revision(), Edit("parent", SidA, "Nowa", true),
                Grant("salon", "parent", SidA, true, false, false)))), applier, "a set of registry changes");
            Stale(applier.Prepare(ChangeSet.Parse(Request(Revision(), ChildSave("parent", "ania", 1,
                Draft("Ania", 0, 1440, 0))))), applier, "a child's change");
            ChangePlan password = applier.Prepare(ChangeSet.Parse(Request(Revision(),
                Change("remote_sign_in", "profile_id", "parent", "account_sid", SidA, "action", "set_password"))));
            password.Password = "Tajne".ToCharArray();
            Stale(password, applier, "a remote sign-in password");
            Stale(applier.Prepare(ChangeSet.Parse(Request(Revision(),
                Change("remote_sign_in", "profile_id", "parent", "account_sid", SidA, "action", "disable")))),
                applier, "disabling remote sign-in");
            Stale(applier.Prepare(ChangeSet.Parse(Request(Revision(),
                Change("profile_remove", "profile_id", "gosia", "account_sid", SidB)))), applier, "a profile removal");
            Stale(applier.Prepare(ChangeSet.Parse(Request(Revision(),
                Change("profile_add", "account_sid", SidC, "display_name", "Celina")))), applier, "a new profile");
            Expect(broker.Password == null && broker.Calls.Count(call => call != BrokerOperation.GetProfileCredentialState &&
                call != BrokerOperation.GetProfileSessionState) == 0 && provisioned.Count == 0,
                "a stale set reaches neither Login Broker nor provisioning");
        }

        // The registry changes after the window was shown: applying the plan is refused, nothing written.
        private static void Stale(ChangePlan plan, ChangeApplier applier, string what)
        {
            File.AppendAllText(config, " ");
            string changed = File.ReadAllText(config);
            Throws<StaleRegistryException>(delegate { applier.Apply(plan); },
                what + " is refused when the registry changed after the confirmation window");
            Expect(File.ReadAllText(config) == changed, what + ": a stale apply writes nothing");
        }

        // ---- One exchange end to end: channel, helper, window decision ----

        private static void TestHandle()
        {
            Fixture();
            GatewayRegistryStore store = new GatewayRegistryStore(config, 500);
            ChangeEffects effects = Effects(new FakeBroker(), new List<string>());
            Func<ChangeApplier> open = delegate { return new ChangeApplier(store, effects); };
            string before = File.ReadAllText(config);
            Expect(Outcome(ApplyRequest.Handle(Encoding.UTF8.GetBytes("{]"), open, Never, Always)) == "rejected",
                "a malformed message is rejected without the window");
            Expect(Outcome(ApplyRequest.Handle(Request(Revision(), Edit("parent", SidA, "Nowa", true)), open,
                delegate { return false; }, Always)) == "cancelled" && File.ReadAllText(config) == before,
                "a set the window does not confirm changes nothing");
            Expect(Outcome(ApplyRequest.Handle(Request(Revision(), Edit("parent", SidA, "Nowa", true)), open,
                delegate { return true; }, delegate { return false; })) == "cancelled" && File.ReadAllText(config) == before,
                "a set confirmed after Host Control stopped waiting changes nothing");

            int self = Process.GetCurrentProcess().Id;
            string name = Pipe();
            string answer = null;
            Exception served = Serve(name, WindowsIdentity.GetCurrent().User, self, delegate(byte[] request, Func<bool> connected) {
                return ApplyRequest.Handle(request, open, delegate { return true; }, connected);
            }, 3000, 2000, delegate {
                answer = Encoding.UTF8.GetString(ChangeChannel.Exchange(name, self, delegate { return true; },
                    Request(Revision(), Edit("parent", SidA, "Przez kanał", true)), 3000, 5000, 2000));
            });
            Expect(served == null && answer.Contains("\"outcome\":\"applied\"") &&
                (string)Entry(Read(), "parent")["display_name"] == "Przez kanał", "a confirmed set is applied over the channel");
        }

        private static bool Never(ChangePlan plan) { throw new InvalidOperationException("The window must not open."); }

        private static bool Always() { return true; }

        private static string Outcome(byte[] answer)
        {
            return (string)new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(
                Encoding.UTF8.GetString(answer))["outcome"];
        }

        // ---- The process that started the helper ----

        private static void TestParentProcess()
        {
            int parentId = 0;
            using (ManagementObjectSearcher searcher = new ManagementObjectSearcher(
                "SELECT ParentProcessId FROM Win32_Process WHERE ProcessId=" + Process.GetCurrentProcess().Id))
                foreach (ManagementObject item in searcher.Get()) parentId = Convert.ToInt32(item["ParentProcessId"]);
            Expect(ParentProcess.Id() == parentId && parentId > 0, "the helper's client is the process that started it");
        }

        // ---- The helper's window ----

        private static void RenderWindows(string output)
        {
            Directory.CreateDirectory(output);
            Fixture();
            GatewayRegistryStore store = new GatewayRegistryStore(config, 500);
            ChangeApplier applier = new ChangeApplier(store, Effects(new FakeBroker(), new List<string>()));
            foreach (bool light in new[] { false, true })
            {
                string theme = light ? "jasny" : "ciemny";
                ChangePlan grants = Plan(applier, Grant("salon", "parent", SidA, true, true, true),
                    Grant("sypialnia", "parent", SidA, true, false, false), Pin("gosia", SidB, "4826"));
                grants.Requester = "MARIUSZ-PC\\mariusz";
                string text = Render(ConfirmWindow.Create(grants, light), output, "07-pomocnik-zmiany-" + theme);
                Expect(text.Contains("Zatwierdź zmiany MoonWaker") && text.Contains("MARIUSZ-PC\\mariusz") &&
                    !text.Contains("4826"), "the helper's window shows the set without the PIN");
                ChangePlan password = Plan(applier, Change("remote_sign_in", "profile_id", "parent", "account_sid", SidA,
                    "action", "set_password"));
                text = Render(ConfirmWindow.Create(password, light), output, "07-pomocnik-haslo-" + theme);
                Expect(text.Contains("Nowe hasło Windows") && text.Contains("UPRZYWILEJOWANE"), "the password is asked in the helper");
                ChangePlan token = applier.Prepare(ChangeSet.Parse(Request("", Change("vibepollo_token"))));
                text = Render(ConfirmWindow.Create(token, light), output, "07-pomocnik-vibepollo-" + theme);
                Expect(text.Contains("Login administratora Vibepollo"), "Vibepollo's login is asked in the helper");
                HostDialog removal = ConfirmWindow.Create(
                    Plan(applier, Change("profile_remove", "profile_id", "gosia", "account_sid", SidB)), light);
                Expect(DefaultButton(removal) == "Anuluj", "Enter cancels a profile removal");
                Render(removal, output, "07-pomocnik-usun-profil-" + theme);
                HostDialog remote = ConfirmWindow.Create(Plan(applier, Grant("salon", "parent", SidA, true, true, false)), light);
                Expect(DefaultButton(remote) == "Anuluj", "Enter cancels a remote sign-in grant");
                text = Render(remote, output, "07-pomocnik-zdalne-" + theme);
                Expect(text.Contains("uprzywilejowane prawo"), "the grant's warning is shown");
                HostDialog pin = ConfirmWindow.Create(Plan(applier, Pin("gosia", SidB, "4826")), light);
                Expect(DefaultButton(pin) == "Zastosuj zmiany", "Enter confirms a change without a warning");
                pin.Close();
            }
        }

        // The text of the button Enter presses.
        private static string DefaultButton(DependencyObject parent)
        {
            foreach (object child in LogicalTreeHelper.GetChildren(parent))
            {
                Button button = child as Button;
                if (button != null && button.IsDefault)
                {
                    StringBuilder text = new StringBuilder();
                    Collect(button, text);
                    return text.ToString().Trim();
                }
                DependencyObject element = child as DependencyObject;
                string found = element == null ? null : DefaultButton(element);
                if (found != null) return found;
            }
            return null;
        }

        private static string Render(Window dialog, string output, string name)
        {
            UIElement content = (UIElement)dialog.Content;
            dialog.Content = null;
            Border frame = new Border { Background = dialog.Background, Child = content, Width = dialog.Width };
            TextOptions.SetTextFormattingMode(frame, TextFormattingMode.Display);
            frame.Measure(new Size(dialog.Width, Double.PositiveInfinity));
            frame.Arrange(new Rect(frame.DesiredSize));
            frame.UpdateLayout();
            RenderTargetBitmap bitmap = new RenderTargetBitmap((int)Math.Ceiling(frame.ActualWidth),
                (int)Math.Ceiling(frame.ActualHeight), 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(frame);
            PngBitmapEncoder png = new PngBitmapEncoder();
            png.Frames.Add(BitmapFrame.Create(bitmap));
            using (FileStream file = File.Create(Path.Combine(output, name + ".png"))) png.Save(file);
            StringBuilder text = new StringBuilder(dialog.Title + "\n");
            Collect(frame, text);
            dialog.Close();
            return text.ToString();
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

        // ---- Fixture ----

        private sealed class FakeBroker
        {
            internal bool Available = true, DeleteFails;
            internal string Password;
            internal readonly List<BrokerOperation> Calls = new List<BrokerOperation>();

            internal BrokerResult Send(BrokerOperation operation, Dictionary<byte, object> fields)
            {
                Calls.Add(operation);
                if (!Available) return BrokerResult.Unavailable(false);
                if (operation == BrokerOperation.ConfigureProfileCredential) Password = new string((char[])fields[4]);
                if (operation == BrokerOperation.DeleteProfileCredential && DeleteFails) return BrokerResult.Unavailable(true);
                return new BrokerResult { Success = true, State = operation == BrokerOperation.GetProfileSessionState
                    ? "signed_out" : "ready", Reason = "none" };
            }
        }

        private static ChangeEffects Effects(FakeBroker broker, List<string> provisioned)
        {
            return new ChangeEffects {
                Accounts = delegate { return new List<LocalAccount> {
                    new LocalAccount { Name = "gosia", QualifiedName = "PC\\gosia", Sid = SidB },
                    new LocalAccount { Name = "celina", QualifiedName = "PC\\celina", Sid = SidC } }; },
                Provision = delegate(GatewayRegistryStore store, ProfileRecord profile) { provisioned.Add(profile.Id); },
                RemoveArtifacts = delegate { },
                Broker = new LoginBrokerClient(broker.Send),
                SaveVibepolloToken = delegate(GatewayRegistryStore store, VibepolloInput input) {
                    if (input.Problem() != null) throw new InvalidOperationException(input.Problem());
                    return new List<string>();
                } };
        }

        private static void Fixture()
        {
            string profiles = Path.Combine(root, "profiles");
            Dictionary<string, object> weekdays = new Dictionary<string, object>();
            foreach (string day in new[] { "mon", "tue", "wed", "thu", "fri", "sat", "sun" })
                weekdays[day] = new Dictionary<string, object> {
                    { "enabled", false }, { "start_minute", 0 }, { "end_minute", 1440 }, { "daily_limit_seconds", 0 } };
            Dictionary<string, object> document = new Dictionary<string, object> {
                { "schema_version", 3 }, { "listen_port", 8785 },
                { "profiles", new Dictionary<string, object> {
                    { "parent", Standard("parent", "Mariusz", SidA, "PC\\mariusz", Path.Combine(profiles, "parent"), true, true) },
                    { "gosia", Standard("gosia", "Gosia", SidB, "PC\\gosia", Path.Combine(profiles, "gosia"), false, false) },
                    { "legacy", new Dictionary<string, object> {
                        { "id", "legacy" }, { "name", "Stary" }, { "display_name", "Stary" }, { "windows_account_sid", SidLegacy },
                        { "enabled", true }, { "profile_root", Path.Combine(profiles, "legacy") },
                        { "account_mapping_status", "action_required" } } },
                    { "ania", new Dictionary<string, object> {
                        { "id", "ania" }, { "kind", "child" }, { "name", "Ania" }, { "display_name", "Ania" },
                        { "avatar_id", "green" }, { "parent_profile_id", "parent" }, { "enabled", true },
                        { "policy_revision", 0 }, { "allowed_game_keys", new object[0] },
                        { "schedule", new Dictionary<string, object> { { "weekdays", weekdays } } } } },
                    { "stas", new Dictionary<string, object> {
                        { "id", "stas" }, { "kind", "child" }, { "name", "Staś" }, { "display_name", "Staś" },
                        { "avatar_id", "" }, { "parent_profile_id", "gosia" }, { "enabled", true },
                        { "policy_revision", 0 }, { "allowed_game_keys", new object[0] },
                        { "schedule", new Dictionary<string, object> { { "weekdays", weekdays } } } } } } },
                { "clients", new object[] {
                    Tv("salon", "Salon TV", new Dictionary<string, object> {
                        { "parent", new object[] { "use_profile" } }, { "gosia", new object[] { "use_profile" } } }),
                    Tv("sypialnia", "Sypialnia", new Dictionary<string, object>()),
                    Tv("kuchnia", "Kuchnia", new Dictionary<string, object> { { "parent", new object[] { "use_profile" } } }) } } };
            File.WriteAllText(config, new JavaScriptSerializer().Serialize(document), new UTF8Encoding(false));
            File.WriteAllBytes(config + ".lock", new byte[] { 0 });
            File.WriteAllText(Path.Combine(root, "gateway", "vibepollo-api.json"), "{}");
        }

        private static Dictionary<string, object> Standard(string id, string name, string sid, string account, string profileRoot,
            bool pin, bool remote)
        {
            Dictionary<string, object> profile = new Dictionary<string, object> {
                { "id", id }, { "kind", "standard" }, { "name", name }, { "display_name", name },
                { "owner_sid", sid }, { "windows_account_sid", sid }, { "owner", account }, { "windows_account_name", account },
                { "enabled", true }, { "profile_root", profileRoot }, { "remote_sign_in_enabled", remote },
                { "account_mapping_status", "resolved" }, { "children_policy_revision", 1 },
                { "game_provider_bridge", "http://127.0.0.1:1" } };
            if (pin) profile["pin_verifier"] = GatewayRegistryStore.CreateAppPinVerifier("1111");
            return profile;
        }

        private static Dictionary<string, object> Tv(string id, string name, Dictionary<string, object> grants)
        {
            return new Dictionary<string, object> {
                { "id", id }, { "name", name }, { "token_sha256", "00" }, { "paired_at", 100 }, { "profile_grants", grants } };
        }

        private static string Revision() { return RegistryRevision.Of(File.ReadAllBytes(config)); }

        private static Dictionary<string, object> Read()
        {
            return new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(File.ReadAllText(config));
        }

        private static Dictionary<string, object> Entry(Dictionary<string, object> document, string id)
        {
            return (Dictionary<string, object>)((Dictionary<string, object>)document["profiles"])[id];
        }

        private static Dictionary<string, object> Client(Dictionary<string, object> document, string id)
        {
            foreach (object value in (IEnumerable)document["clients"])
                if ((string)((Dictionary<string, object>)value)["id"] == id) return (Dictionary<string, object>)value;
            return null;
        }

        private static string Grants(Dictionary<string, object> document, string client, string profile)
        {
            Dictionary<string, object> grants = (Dictionary<string, object>)Client(document, client)["profile_grants"];
            object value;
            if (!grants.TryGetValue(profile, out value)) return "";
            List<string> names = new List<string>();
            foreach (object name in (IEnumerable)value) names.Add((string)name);
            return String.Join(",", names.ToArray());
        }

        // ---- Requests ----

        private static byte[] Request(string revision, params Dictionary<string, object>[] changes)
        {
            return new UTF8Encoding(false).GetBytes(new JavaScriptSerializer().Serialize(new Dictionary<string, object> {
                { "protocol", ChangeChannel.Protocol }, { "registry_revision", revision }, { "changes", changes } }));
        }

        private static Dictionary<string, object> Change(string type, params object[] fields)
        {
            Dictionary<string, object> change = new Dictionary<string, object> { { "type", type } };
            for (int index = 0; index + 1 < fields.Length; index += 2) change[(string)fields[index]] = fields[index + 1];
            return change;
        }

        private static Dictionary<string, object> Edit(string id, string sid, string name, bool enabled)
        {
            return Change("profile_edit", "profile_id", id, "account_sid", sid, "display_name", name, "enabled", enabled);
        }

        private static Dictionary<string, object> Pin(string id, string sid, string pin)
        {
            return Change("pin_set", "profile_id", id, "account_sid", sid, "pin", pin);
        }

        private static Dictionary<string, object> Grant(string client, string profile, string sid, bool use, bool remote, bool manage)
        {
            return Change("device_grant", "client_id", client, "profile_id", profile, "account_sid", sid,
                "use_profile", use, "remote_sign_in", remote, "manage_children", manage);
        }

        private static Dictionary<string, object> ChildSave(string parent, string child, int revision, Dictionary<string, object> draft)
        {
            return Change("child_save", "profile_id", parent, "child_profile_id", child, "expected_revision", revision, "draft", draft);
        }

        private static Dictionary<string, object> Draft(string name, int start, int end, int limitSeconds)
        {
            Dictionary<string, object> weekdays = new Dictionary<string, object>();
            foreach (string day in new[] { "mon", "tue", "wed", "thu", "fri", "sat", "sun" })
                weekdays[day] = new Dictionary<string, object> {
                    { "enabled", day == "sat" }, { "start_minute", start }, { "end_minute", end },
                    { "daily_limit_seconds", limitSeconds } };
            return new Dictionary<string, object> {
                { "name", name }, { "avatar_id", "blue" }, { "enabled", true },
                { "schedule", new Dictionary<string, object> { { "weekdays", weekdays } } } };
        }

        // ---- Assertions ----

        private static void Throws<T>(Action action, string what) where T : Exception
        {
            try { action(); }
            catch (T) { checks++; return; }
            catch (Exception other) { throw new InvalidOperationException("Expectation failed: " + what + " (threw " + other + ")"); }
            throw new InvalidOperationException("Expectation failed: " + what + " (nothing thrown)");
        }

        private static void Expect(bool condition, string what)
        {
            if (!condition) throw new InvalidOperationException("Expectation failed: " + what);
            checks++;
        }
    }
}
