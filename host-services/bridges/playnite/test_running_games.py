import ctypes
import os
import time
import unittest
from unittest import mock

from GameProviderBridge import BridgeState, WindowProbe
from GameOperations import GameOperationsService, SteamProvider
from OperationJournal import OperationJournal


def identity(pid=11, path=r"C:\Games\HK\game.exe", started=100):
    return {"process_id": pid, "process_path": path,
            "process_started_filetime": started, "user_sid": "own-sid",
            "session_id": 1, "visible_window": True}


class RunningGamesTest(unittest.TestCase):
    def setUp(self):
        service = GameOperationsService(OperationJournal(None),
                                       steam=SteamProvider(root_resolver=lambda: None))
        self.state = BridgeState(game_operations=service, profile_id="profile")
        self.state.library_revision = "1"
        self.state.library = {
            "steam:1": {"id": "steam:1", "provider": "steam", "installDir": r"C:\Games\HK"},
            "epic:Cowbird": {"id": "epic:Cowbird", "provider": "epic", "installDir": r"C:\Games\Batman"},
        }
        self.hk = identity()
        self.batman = identity(22, r"C:\Games\Batman\Binaries\Batman.exe", 200)
        self.probe = mock.Mock()
        self.probe.scan_running_processes.return_value = ([self.hk, self.batman], "partial")
        self.probe.stop_verified_process.return_value = True
        self.probe.force_terminate_verified_process.return_value = True
        self.state.running_process_probe = self.probe
        self.state.current = {"state": "running", "id": "epic:Cowbird", "processId": 22,
                              "processPath": self.batman["process_path"],
                              "processStartedFiletime": 200}
        self.state.readiness = {"ready": True, "game_id": "epic:Cowbird"}

    def token(self, game_id="steam:1"):
        self.state.refresh_running_games()
        return next(game["process_token"] for game in self.state.current_snapshot()["running_games"]
                    if game["game_id"] == game_id)

    def test_guide_policy_uses_current_provider_not_other_running_games_or_id_prefix(self):
        self.state.refresh_running_games()
        self.assertFalse(self.state.current_snapshot()["host_guide_allowed"])
        self.state.current = {"state": "running", "id": "steam:1"}
        self.assertTrue(self.state.current_snapshot()["host_guide_allowed"])
        legacy_id = "11111111-2222-3333-4444-555555555555"
        self.state.library[legacy_id] = {"id": legacy_id, "source": "Steam"}
        self.state.current = {"state": "running", "id": legacy_id}
        self.assertTrue(self.state.current_snapshot()["host_guide_allowed"])
        self.state.library[legacy_id]["source"] = "Other"
        self.assertFalse(self.state.current_snapshot()["host_guide_allowed"])
        self.state.current = {"state": "running", "id": "steam:unknown"}
        self.assertFalse(self.state.current_snapshot()["host_guide_allowed"])
        self.state.current = {"state": "idle"}
        self.assertTrue(self.state.current_snapshot()["host_guide_allowed"])

    def test_two_games_partial_inventory_and_noncurrent_stop_preserve_session(self):
        token = self.token()
        snapshot = self.state.current_snapshot()
        self.assertEqual({"steam:1", "epic:Cowbird"},
                         {game["game_id"] for game in snapshot["running_games"]})
        self.assertEqual("partial", snapshot["running_scan_status"])
        self.assertNotIn("process_path", str(snapshot["running_games"]))
        self.assertNotIn("own-sid", str(snapshot))
        before = dict(self.state.current), dict(self.state.readiness)
        result = self.state._stop_verified_running_game("steam:1", token)
        self.assertTrue(result["accepted"])
        self.assertEqual("steam:1", result["stopped_game_id"])
        self.assertFalse(result["stopped_current"])
        self.assertEqual(before, (self.state.current, self.state.readiness))
        self.probe.stop_verified_process.assert_called_once()
        self.assertEqual(11, self.probe.stop_verified_process.call_args.args[0]["process_id"])

    def test_current_stop_clears_only_same_identity_and_never_newer_current(self):
        token = self.token("epic:Cowbird")
        result = self.state._stop_verified_running_game("epic:Cowbird", token)
        self.assertTrue(result["stopped_current"])
        self.assertEqual("idle", self.state.current["state"])
        newer = {"state": "running", "id": "steam:1", "processId": 99}
        self.probe.stop_verified_process.side_effect = lambda *_: (
            setattr(self.state, "current", newer) or True)
        result = self.state._stop_verified_running_game("epic:Cowbird", token)
        self.assertFalse(result["stopped_current"])
        self.assertEqual(newer, self.state.current)

    def test_old_token_reuse_profile_library_and_ambiguity_have_zero_effect(self):
        token = self.token()
        for changed in ({**self.hk, "process_started_filetime": 101},
                        {**self.hk, "process_path": r"C:\Games\HK\other.exe"},
                        {**self.hk, "user_sid": "foreign"}):
            self.probe.scan_running_processes.return_value = ([changed], "complete")
            self.assertFalse(self.state._stop_verified_running_game("steam:1", token)["accepted"])
        self.probe.scan_running_processes.return_value = ([self.hk, {**self.hk, "process_id": 33}], "complete")
        self.assertFalse(self.state._stop_verified_running_game("steam:1", token)["accepted"])
        self.probe.scan_running_processes.return_value = ([self.hk], "complete")
        self.state.profile_id = "other-profile"
        self.assertFalse(self.state._stop_verified_running_game("steam:1", token)["accepted"])
        self.probe.stop_verified_process.assert_not_called()

    def test_inventory_expiry_revision_change_and_helpers_fail_closed(self):
        self.token()
        self.state._running_scan_at = time.monotonic() - 11
        self.assertEqual([], self.state.current_snapshot()["running_games"])
        self.assertEqual("unavailable", self.state.current_snapshot()["running_scan_status"])
        self.state.refresh_running_games()
        self.state.library_revision = "2"
        self.assertEqual([], self.state.current_snapshot()["running_games"])
        self.probe.scan_running_processes.return_value = ([self.hk,
            identity(44, r"C:\Games\HK\UnityCrashHandler64.exe")], "partial")
        self.assertEqual(1, len(self.state._verified_running_games()[0]))
        self.probe.scan_running_processes.side_effect = lambda: (
            setattr(self.state, "library_revision", "3") or ([self.hk], "complete"))
        self.state.refresh_running_games()
        self.assertEqual([], self.state.current_snapshot()["running_games"])

    def test_shared_exe_native_without_connector_confirmation_is_not_claimed(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {guid: {"id": guid, "provider": "playnite",
                                     "playniteGameId": guid, "exe": self.hk["process_path"]}}
        self.probe.scan_running_processes.return_value = ([self.hk], "complete")
        self.assertEqual([], self.state._verified_running_games()[0])
        self.state.connected = True
        self.state.current = {"id": guid, "processId": 11, "processStartedFiletime": 100}
        self.state._native_reconciliation_confirmation = (guid, 11)
        self.assertEqual(1, len(self.state._verified_running_games()[0]))
        self.state.library["steam:2"] = {"id": "steam:2", "provider": "steam", "exe": self.hk["process_path"]}
        self.assertEqual([], self.state._verified_running_games()[0])

    def test_hollow_knight_to_contra_uses_fresh_connector_process_identity(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        emulator = identity(44, r"C:\Emulators\retroarch.exe", 400)
        self.state.library = {
            "steam:367520": {"id": "steam:367520", "provider": "steam",
                              "installDir": r"C:\Games\Hollow Knight",
                              "exe": r"C:\Games\Hollow Knight\hollow_knight.exe"},
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid,
                   "installDir": r"C:\Games\Contra", "exe": ""}}
        self.state.connected = True
        self.state.current = {"state": "idle"}
        hollow = identity(55, r"C:\Games\Hollow Knight\hollow_knight.exe", 300)
        self.probe.scan_running_processes.return_value = ([hollow, emulator], "complete")
        own = {"process_id": os.getpid(), "process_path": r"C:\Bridge\bridge.exe",
               "process_started_filetime": 500, "user_sid": "own-sid", "session_id": 1}
        self.probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(own) if pid == os.getpid() else dict(emulator))
        self.state.running_process_probe = self.probe
        self.state.handle_message({"type": "status", "status": {
            "name": "gameStarted", "id": guid, "processId": 44}})

        running, status, _revision = self.state._verified_running_games()

        self.assertEqual("complete", status)
        self.assertEqual({"steam:367520", guid},
                         {game["game_id"] for game in running})
        self.assertEqual(44, next(game["process_id"] for game in running
                                  if game["game_id"] == guid))

    def test_shared_playnite_emulator_rejects_foreign_process_identity(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        emulator = identity(44, r"C:\Emulators\retroarch.exe", 400)
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid,
                   "installDir": r"C:\Games\Contra", "exe": ""}}
        self.state.connected = True
        self.state.current = {"state": "running", "id": guid, "processId": 44,
                              "processStartedFiletime": 400}
        self.state._native_reconciliation_confirmation = (guid, 44)
        self.probe.scan_running_processes.return_value = ([emulator], "complete")
        own = {"process_id": os.getpid(), "process_path": r"C:\Bridge\bridge.exe",
               "process_started_filetime": 500, "user_sid": "own-sid", "session_id": 1}
        self.probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(own) if pid == os.getpid() else {**emulator, "user_sid": "foreign"})

        running, _status, _revision = self.state._verified_running_games()

        self.assertEqual([], running)

    def test_shared_playnite_emulator_rejects_stale_connector_confirmation(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        emulator = identity(44, r"C:\Emulators\retroarch.exe", 400)
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid,
                   "installDir": r"C:\Games\Contra", "exe": ""}}
        self.state.connected = True
        self.state.current = {"state": "running", "id": guid, "processId": 44,
                              "processStartedFiletime": 400}
        self.state._native_reconciliation_confirmation = (guid, 45)
        self.probe.scan_running_processes.return_value = ([emulator], "complete")
        own = {"process_id": os.getpid(), "process_path": r"C:\Bridge\bridge.exe",
               "process_started_filetime": 500, "user_sid": "own-sid", "session_id": 1}
        self.probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(own) if pid == os.getpid() else dict(emulator))

        running, _status, _revision = self.state._verified_running_games()

        self.assertEqual([], running)

    def test_playnite_process_replacement_invalidates_connector_confirmation(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.current = {"state": "running", "id": guid,
                              "provider": "playnite", "processId": 44,
                              "processPath": r"C:\Emulators\retroarch.exe"}
        self.state.readiness = {"target_kind": "game", "ready": True}
        self.state._native_reconciliation_confirmation = (guid, 44)

        self.state.apply_window_sample({
            "qualified": False, "reason": "target_not_fullscreen",
            "replacement_process": True, "process_id": 45,
            "process_path": r"C:\Emulators\retroarch.exe",
            "observed_game_id": guid,
        })

        self.assertIsNone(self.state._native_reconciliation_confirmation)

    def test_unconfirmed_stop_leaves_inventory_current_and_readiness(self):
        token = self.token()
        before = self.state.current_snapshot(), dict(self.state.readiness)
        self.probe.stop_verified_process.return_value = False
        self.assertFalse(self.state._stop_verified_running_game("steam:1", token)["accepted"])
        self.assertEqual(before, (self.state.current_snapshot(), self.state.readiness))
        with self.assertRaises(ValueError):
            self.state.stop_game("steam:1")  # legacy still cannot stop non-current

    def test_hard_reset_force_stops_all_verified_games_and_resets_bridge_state(self):
        result = self.state.hard_reset_session()

        self.assertTrue(result["accepted"])
        self.assertTrue(result["force"])
        self.assertEqual(2, result["stopped_count"])
        self.assertEqual({11, 22}, {
            call.args[0]["process_id"]
            for call in self.probe.force_terminate_verified_process.call_args_list
        })
        self.assertEqual("idle", self.state.current["state"])
        self.assertEqual("session_hard_reset", self.state.readiness["reason"])
        self.assertEqual([], self.state.current_snapshot()["running_games"])

    def test_hard_reset_fails_closed_when_process_inventory_is_unavailable(self):
        before = dict(self.state.current), dict(self.state.readiness)
        self.probe.scan_running_processes.return_value = ([], "unavailable")

        result = self.state.hard_reset_session()

        self.assertFalse(result["accepted"])
        self.assertEqual("running_game_inventory_unavailable", result["reason"])
        self.assertEqual(before, (self.state.current, self.state.readiness))
        self.probe.force_terminate_verified_process.assert_not_called()

    def test_hard_reset_fails_closed_when_active_game_has_no_verified_identity(self):
        before = dict(self.state.current), dict(self.state.readiness)
        self.probe.scan_running_processes.return_value = ([], "complete")
        self.probe.process_identity.return_value = None

        result = self.state.hard_reset_session()

        self.assertFalse(result["accepted"])
        self.assertEqual("running_game_identity_unconfirmed", result["reason"])
        self.assertEqual(before, (self.state.current, self.state.readiness))
        self.probe.force_terminate_verified_process.assert_not_called()

    def test_hard_reset_does_not_clear_active_game_for_unrelated_inventory(self):
        before = dict(self.state.current), dict(self.state.readiness)
        self.probe.scan_running_processes.return_value = ([self.hk], "complete")
        self.probe.process_identity.return_value = None

        result = self.state.hard_reset_session()

        self.assertFalse(result["accepted"])
        self.assertEqual("running_game_identity_unconfirmed", result["reason"])
        self.assertEqual(before, (self.state.current, self.state.readiness))
        self.probe.force_terminate_verified_process.assert_not_called()

    def test_hard_reset_recovers_exact_ambiguous_active_trace(self):
        trace = {
            "game_id": "playnite-game", "process_id": 33,
            "process_path": r"C:\Games\Native\game.exe",
            "process_started_filetime": 300,
        }
        self.state._active_game_trace = trace
        self.state.current = {
            "state": "ambiguous", "id": trace["game_id"],
            "reason": "active_game_connector_mismatch",
        }
        self.probe.scan_running_processes.return_value = ([], "partial")
        self.probe.process_identity.return_value = identity(
            33, trace["process_path"], 300)

        result = self.state.hard_reset_session()

        self.assertTrue(result["accepted"])
        self.assertEqual(1, result["stopped_count"])
        stopped = self.probe.force_terminate_verified_process.call_args.args[0]
        self.assertEqual(33, stopped["process_id"])
        self.assertEqual("idle", self.state.current["state"])


class NativeRunningGamesTest(unittest.TestCase):
    def setUp(self):
        self.probe = object.__new__(WindowProbe)
        self.probe.kernel32 = mock.Mock()
        self.probe.user32 = mock.Mock()
        self.probe.is_session_locked = mock.Mock(return_value=False)
        self.probe.uac_consent_pending = mock.Mock(return_value=False)
        self.expected = identity()
        self.probe.process_identity = mock.Mock(return_value=dict(self.expected))
        self.probe._matching_windows = mock.Mock(return_value=[1, 2])
        self.probe.user32.GetWindowThreadProcessId.side_effect = lambda hwnd, pid: setattr(pid._obj, "value", 11)
        self.probe.kernel32.WaitForSingleObject.return_value = 258

    def test_multiwindow_exit_after_first_close_is_success_without_second_close(self):
        self.probe.kernel32.WaitForSingleObject.side_effect = [258, 258, 258, 0]
        self.assertTrue(self.probe.stop_verified_process(self.expected, 1))
        self.probe.user32.PostMessageW.assert_called_once_with(1, self.probe.WM_CLOSE, 0, 0)

    def test_alive_without_window_exit_and_foreign_identity_never_claim_success(self):
        self.assertFalse(self.probe.stop_verified_process(self.expected, 0))
        self.probe.user32.PostMessageW.reset_mock()
        for change in ({"user_sid": "foreign"}, {"session_id": 2},
                       {"process_started_filetime": 101}, {"process_path": r"C:\Other.exe"}):
            self.probe.process_identity.side_effect = [self.expected, {**self.expected, **change}]
            self.assertFalse(self.probe.stop_verified_process(self.expected, 0))
        self.probe.user32.PostMessageW.assert_not_called()
        self.probe.uac_consent_pending.return_value = True
        self.assertFalse(self.probe.stop_verified_process(self.expected, 0))

    def test_force_termination_requires_exact_same_user_process_identity(self):
        self.probe.process_identity.side_effect = [self.expected, self.expected]
        self.probe.kernel32.WaitForSingleObject.side_effect = [258, 0]
        self.probe.kernel32.TerminateProcess.return_value = True

        self.assertTrue(self.probe.force_terminate_verified_process(self.expected))
        self.probe.kernel32.TerminateProcess.assert_called_once()

        self.probe.kernel32.TerminateProcess.reset_mock()
        self.probe.process_identity.side_effect = [self.expected, {
            **self.expected, "process_started_filetime": 101,
        }]
        self.assertFalse(self.probe.force_terminate_verified_process(self.expected))
        self.probe.kernel32.TerminateProcess.assert_not_called()

    def test_scan_budget_and_enumeration_error_never_publish_partial_uniqueness(self):
        self.probe.kernel32.CreateToolhelp32Snapshot.return_value = 1
        self.probe.kernel32.Process32FirstW.side_effect = lambda _, entry: (
            setattr(entry._obj, "th32ProcessID", 11) or True)
        self.probe.kernel32.Process32NextW.return_value = False
        with mock.patch("GameProviderBridge.time.monotonic", side_effect=[0, 1]):
            self.assertEqual(([], "unavailable"), self.probe.scan_running_processes())
        with mock.patch.object(ctypes, "get_last_error", return_value=5, create=True):
            self.assertEqual(([], "unavailable"), self.probe.scan_running_processes())
        for change in ({"user_sid": "foreign"}, {"session_id": 2}):
            self.probe.process_identity.side_effect = [self.expected, {**self.expected, **change}]
            with mock.patch.object(ctypes, "get_last_error", return_value=18, create=True):
                self.assertEqual(([], "complete"), self.probe.scan_running_processes())


if __name__ == "__main__":
    unittest.main()
