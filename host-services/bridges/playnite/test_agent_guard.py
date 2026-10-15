import ctypes
import json
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

import AgentGuard
import GameProviderBridge
from AgentGuard import (
    AUTHORITY_LOST_EXIT_CODE, AUTHORIZED, PENDING, PORT_IN_USE_EXIT_CODE,
    REGISTRY_UNREADABLE_EXIT_CODE, REVOKED, UNREADABLE, WATCHDOG_EXIT_CODE,
    AuthorityMonitor, Watchdog, lock_probe, profile_authority, read_registry, start_guard,
)

SID = "S-1-5-21-1-2-3-1001"


class ProfileAuthorityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.install = Path(self.temporary.name)
        self.profile = self.install / "profiles" / "default"
        (self.profile / "game-provider").mkdir(parents=True)
        (self.install / "gateway").mkdir()
        self.entry = {"enabled": True, "windows_account_sid": SID,
                      "profile_root": str(self.profile)}

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, entry):
        (self.install / "gateway" / "gateway.json").write_text(
            json.dumps({"profiles": {"default": entry}}), encoding="utf-8")

    def authority(self, sid=SID, profile=None):
        return profile_authority(self.install, profile or self.profile, sid)

    def test_authorized_profile(self):
        self.write(self.entry)
        self.assertEqual(AUTHORIZED, self.authority())
        self.assertEqual(AUTHORIZED, self.authority(sid=SID.lower()))

    def test_revocations(self):
        cases = [
            {**self.entry, "enabled": False},
            {key: value for key, value in self.entry.items() if key != "enabled"},
            {**self.entry, "deletion_tombstone": {"nonce": "x"}},
            {**self.entry, "kind": "child"},
            {**self.entry, "kind": "Child"},
            {**self.entry, "profile_root": str(self.install / "profiles" / "other")},
            {**self.entry, "profile_root": ""},
            {**self.entry, "windows_account_sid": "S-1-5-21-1-2-3-1002"},
            {key: value for key, value in self.entry.items() if key != "windows_account_sid"},
        ]
        for entry in cases:
            with self.subTest(entry=entry):
                self.write(entry)
                self.assertEqual(REVOKED, self.authority())
        self.write({**self.entry, "kind": "standard"})
        self.assertEqual(AUTHORIZED, self.authority())
        self.write(self.entry)
        self.assertEqual(REVOKED, self.authority(sid=""))
        misplaced = self.install / "elsewhere" / "default"
        self.assertEqual(REVOKED, self.authority(profile=misplaced))
        (self.install / "gateway" / "gateway.json").write_text('{"profiles": {}}', "utf-8")
        self.assertEqual(REVOKED, self.authority())

    def test_reparse_point_in_profile_path_revokes(self):
        target = self.install / "target"
        target.mkdir()
        link = self.install / "linked"
        result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)],
                                capture_output=True, check=False)
        if result.returncode != 0:
            self.skipTest("junction creation unavailable")
        (link / "default").mkdir()
        self.assertTrue(AgentGuard.has_reparse_point(link / "default"))
        self.assertFalse(AgentGuard.has_reparse_point(self.profile))

    def test_profiles_junction_revokes_authority(self):
        # Registry and Agent agree on <install>\profiles\default, but "profiles" redirects.
        install = self.install / "junctioned"
        (install / "gateway").mkdir(parents=True)
        target = self.install / "foreign-profiles"
        (target / "default" / "game-provider").mkdir(parents=True)
        result = subprocess.run(["cmd", "/c", "mklink", "/J", str(install / "profiles"),
                                 str(target)], capture_output=True, check=False)
        if result.returncode != 0:
            self.skipTest("junction creation unavailable")
        profile = install / "profiles" / "default"
        (install / "gateway" / "gateway.json").write_text(json.dumps({"profiles": {"default": {
            **self.entry, "profile_root": str(profile)}}}), encoding="utf-8")
        self.assertEqual(REVOKED, profile_authority(install, profile, SID))

    def test_unreadable_registry(self):
        self.assertEqual(UNREADABLE, self.authority())
        (self.install / "gateway" / "gateway.json").write_text("{", encoding="utf-8")
        self.assertEqual(UNREADABLE, self.authority())
        (self.install / "gateway" / "gateway.json").write_text("[]", encoding="utf-8")
        self.assertEqual(UNREADABLE, self.authority())

    def test_reader_does_not_block_atomic_replacement(self):
        path = self.install / "gateway" / "gateway.json"
        self.write(self.entry)
        replacement = path.with_suffix(".tmp")
        replace_file = ctypes.WinDLL("kernel32", use_last_error=True).ReplaceFileW
        # The Configurator replaces gateway.json with File.Replace (ReplaceFileW).
        replacement.write_text('{"profiles": {}}', encoding="utf-8")
        with open(path, "rb"):
            self.assertFalse(replace_file(str(path), str(replacement), None, 0, None, None))
        parse = json.loads
        replaced = []

        def replace_while_reading(text):
            # read_registry still holds its handle here.
            replaced.append(bool(replace_file(str(path), str(replacement), None, 0, None, None)))
            return parse(text)
        with mock.patch("json.loads", side_effect=replace_while_reading):
            self.assertEqual(["default"], list(read_registry(path)["profiles"]))
        self.assertEqual([True], replaced)
        self.assertEqual({"profiles": {}}, read_registry(path))

    def test_current_user_sid_matches_whoami(self):
        output = subprocess.run(["whoami", "/user", "/fo", "csv", "/nh"], capture_output=True,
                                text=True, check=True).stdout
        self.assertEqual(output.strip().split(",")[-1].strip('"'),
                         AgentGuard.current_user_sid())


class MonitorTests(unittest.TestCase):
    def test_unreadable_streak_and_reset(self):
        states = iter([UNREADABLE] * 4 + [AUTHORIZED] + [UNREADABLE] * 5)
        monitor = AuthorityMonitor(lambda: next(states), limit=5)
        self.assertEqual([PENDING] * 4 + [AUTHORIZED] + [PENDING] * 4 + [UNREADABLE],
                         [monitor.poll() for _ in range(10)])

    def test_revocation_is_immediate(self):
        self.assertEqual(REVOKED, AuthorityMonitor(lambda: REVOKED).poll())

    def test_startup_wait_tolerates_streak(self):
        states = iter([UNREADABLE, UNREADABLE, AUTHORIZED])
        sleeps = []
        self.assertEqual(AUTHORIZED, AuthorityMonitor(lambda: next(states)).wait(sleeps.append))
        self.assertEqual(2, len(sleeps))
        self.assertEqual(UNREADABLE,
                         AuthorityMonitor(lambda: UNREADABLE, limit=3).wait(lambda _: None))


class WatchdogTests(unittest.TestCase):
    def test_second_consecutive_failure_is_a_hang(self):
        results = iter([False, True, False, False])
        watchdog = Watchdog({"loop": lambda: next(results)})
        self.assertEqual([None, None, None, "loop"], [watchdog.tick() for _ in range(4)])

    def test_raising_probe_counts_as_failure(self):
        def probe():
            raise RuntimeError("stuck")
        watchdog = Watchdog({"lock": probe})
        self.assertIsNone(watchdog.tick())
        self.assertEqual("lock", watchdog.tick())

    def test_lock_probe(self):
        lock = threading.RLock()
        self.assertTrue(lock_probe(lock, 0.05)())
        holder = threading.Event()
        release = threading.Event()

        def hold():
            with lock:
                holder.set()
                release.wait(5)
        thread = threading.Thread(target=hold)
        thread.start()
        holder.wait(5)
        try:
            self.assertFalse(lock_probe(lock, 0.05)())
        finally:
            release.set()
            thread.join()


class GuardLoopTests(unittest.TestCase):
    def run_guard(self, monitor, watchdog):
        exits = []
        finished = threading.Event()

        def on_exit(code, event, reason):
            exits.append((code, event, reason))
            finished.set()
        start_guard(monitor, watchdog, on_exit, sleep=lambda _: None)
        self.assertTrue(finished.wait(5))
        return exits[0]

    def test_authority_loss_exits_with_recognizable_code(self):
        self.assertEqual((AUTHORITY_LOST_EXIT_CODE, "agent.authority-lost", REVOKED),
                         self.run_guard(AuthorityMonitor(lambda: REVOKED), Watchdog({})))

    def test_unreadable_registry_is_not_a_revocation(self):
        self.assertNotEqual(AUTHORITY_LOST_EXIT_CODE, REGISTRY_UNREADABLE_EXIT_CODE)
        self.assertEqual((REGISTRY_UNREADABLE_EXIT_CODE, "agent.registry-unreadable", UNREADABLE),
                         self.run_guard(AuthorityMonitor(lambda: UNREADABLE), Watchdog({})))

    def test_hang_exits_with_watchdog_code(self):
        self.assertEqual((WATCHDOG_EXIT_CODE, "agent.watchdog-hang", "http_server"),
                         self.run_guard(AuthorityMonitor(lambda: AUTHORIZED),
                                        Watchdog({"http_server": lambda: False})))



class AgentExit(Exception):
    pass


class AgentMainTests(unittest.TestCase):
    """main(): startup authority, bind before startup effects, guard started."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        install = Path(self.temporary.name)
        self.data = install / "profiles" / "default" / "game-provider"
        self.data.mkdir(parents=True)
        self.config = {"listen_host": "127.0.0.1", "listen_port": 0, "playnite_enabled": True,
                       "playnite_desktop_executable": "Playnite.DesktopApp.exe"}
        self.effects = mock.MagicMock()
        self.guard = []
        self.server = None

    def tearDown(self):
        self.temporary.cleanup()

    def run_main(self, authority):
        (self.data / "config.json").write_text(json.dumps(self.config), encoding="utf-8")

        def exit_agent(code, event, reason):
            raise AgentExit(code, event)

        def start_guard(monitor, watchdog, on_exit):
            self.guard.append((monitor.poll(), sorted(watchdog.probes),
                               on_exit is GameProviderBridge.exit_agent))

        def serve(server, **_):
            self.server = server
        bridge = GameProviderBridge
        argv = ["GameProviderBridge.py", "--config", str(self.data / "config.json")]
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(AgentGuard, "POLL_SECONDS", 0), \
                mock.patch.object(bridge.DIAGNOSTICS, "start"), \
                mock.patch.object(bridge.DIAGNOSTICS, "close"), \
                mock.patch.object(bridge, "current_user_sid", return_value=SID), \
                mock.patch.object(bridge, "profile_authority", side_effect=authority), \
                mock.patch.object(bridge, "exit_agent", side_effect=exit_agent), \
                mock.patch.object(bridge, "start_guard", side_effect=start_guard), \
                mock.patch.object(bridge, "ensure_playnite_desktop", self.effects.playnite), \
                mock.patch.object(bridge, "WindowsPipeClient", self.effects.pipe), \
                mock.patch.object(bridge, "WindowReadinessWorker", self.effects.readiness), \
                mock.patch.object(bridge, "OperationJournal",
                                  side_effect=bridge.OperationJournal) as journal, \
                mock.patch.object(bridge.BridgeState, "_schedule_catalog_refresh_locked"), \
                mock.patch.object(bridge.GameProviderServer, "serve_forever", autospec=True,
                                  side_effect=serve):
            self.journal = journal
            bridge.main()

    def assert_no_startup_effects(self):
        self.assertEqual([], self.effects.mock_calls)
        self.journal.assert_not_called()
        self.assertEqual([], self.guard)

    def test_revoked_at_start_exits_before_any_effect(self):
        with self.assertRaises(AgentExit) as raised:
            self.run_main(lambda *_: REVOKED)
        self.assertEqual((AUTHORITY_LOST_EXIT_CODE, "agent.authority-lost"), raised.exception.args)
        self.assert_no_startup_effects()

    def test_unreadable_at_start_exits_with_its_own_code(self):
        calls = []
        with self.assertRaises(AgentExit) as raised:
            self.run_main(lambda *_: calls.append(1) or UNREADABLE)
        self.assertEqual(REGISTRY_UNREADABLE_EXIT_CODE, raised.exception.args[0])
        self.assertEqual(AgentGuard.UNREADABLE_LIMIT, len(calls))
        self.assert_no_startup_effects()

    def test_second_agent_on_a_served_port_exits_without_effects(self):
        first = GameProviderBridge.GameProviderServer(("127.0.0.1", 0), None)
        try:
            self.config["listen_port"] = first.server_port
            with self.assertRaises(AgentExit) as raised:
                self.run_main(lambda *_: AUTHORIZED)
        finally:
            first.server_close()
        self.assertEqual(PORT_IN_USE_EXIT_CODE, raised.exception.args[0])
        self.assert_no_startup_effects()

    def test_authorized_agent_starts_its_guard_and_serves(self):
        self.run_main(lambda install, profile, sid: AUTHORIZED if (
            profile.name, sid) == ("default", SID) else REVOKED)
        self.assertEqual(1, len(self.guard))
        self.assertEqual((AUTHORIZED, ["http_server", "state_lock"], True), self.guard[0])
        self.effects.playnite.assert_called_once_with("Playnite.DesktopApp.exe")
        self.assertIsNotNone(self.server.state)


class ExclusivePortTests(unittest.TestCase):
    def test_served_port_cannot_be_shared_or_taken_over(self):
        server = GameProviderBridge.GameProviderServer(("127.0.0.1", 0), None)
        try:
            with self.assertRaises(OSError):
                GameProviderBridge.GameProviderServer(("127.0.0.1", server.server_port), None)
            intruder = socket.socket()
            try:
                intruder.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                with self.assertRaises(OSError):
                    intruder.bind(("127.0.0.1", server.server_port))
            finally:
                intruder.close()
        finally:
            server.server_close()


if __name__ == "__main__":
    unittest.main()
