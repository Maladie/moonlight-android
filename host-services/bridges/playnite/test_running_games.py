import ctypes
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

    def test_unconfirmed_stop_leaves_inventory_current_and_readiness(self):
        token = self.token()
        before = self.state.current_snapshot(), dict(self.state.readiness)
        self.probe.stop_verified_process.return_value = False
        self.assertFalse(self.state._stop_verified_running_game("steam:1", token)["accepted"])
        self.assertEqual(before, (self.state.current_snapshot(), self.state.readiness))
        with self.assertRaises(ValueError):
            self.state.stop_game("steam:1")  # legacy still cannot stop non-current


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
