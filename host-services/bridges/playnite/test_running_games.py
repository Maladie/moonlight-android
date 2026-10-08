import ctypes
import json
import os
import time
import unittest
from unittest import mock

from GameProviderBridge import (
    FILETIME_UNIX_EPOCH, GAME_START_TIMEOUT, RUNNING_SCAN_DEADLINE, BridgeState,
    ProviderDiagnostics, WindowProbe, WindowReadinessWorker)
from GameOperations import GameOperationsService, SteamProvider
from OperationJournal import OperationJournal


def identity(pid=11, path=r"C:\Games\HK\game.exe", started=100):
    return {"process_id": pid, "process_path": path,
            "process_started_filetime": started, "user_sid": "own-sid",
            "session_id": 1, "visible_window": True}


class ProcessIdentityStatusTest(unittest.TestCase):
    def setUp(self):
        self.probe = object.__new__(WindowProbe)
        self.probe.kernel32 = mock.Mock()
        self.probe.advapi32 = None

    def test_open_process_distinguishes_missing_from_unavailable(self):
        self.probe.kernel32.OpenProcess.return_value = 0
        with mock.patch("GameProviderBridge.ctypes.get_last_error", return_value=87):
            self.assertEqual(("gone", None), self.probe.process_identity_status(44))
        with mock.patch("GameProviderBridge.ctypes.get_last_error", return_value=5):
            self.assertEqual(("unknown", None), self.probe.process_identity_status(44))

    def exit_code(self, code):
        self.probe.kernel32.GetExitCodeProcess.side_effect = \
            lambda _handle, value: setattr(value._obj, "value", code) is None

    def test_query_failure_of_a_live_process_is_unknown(self):
        self.probe.kernel32.OpenProcess.return_value = 1
        self.exit_code(WindowProbe.STILL_ACTIVE)
        self.probe.kernel32.QueryFullProcessImageNameW.return_value = False

        self.assertEqual(("unknown", None), self.probe.process_identity_status(44))
        self.probe.kernel32.CloseHandle.assert_called_once_with(1)

    def test_exited_process_is_gone_even_when_its_image_query_fails(self):
        # Lab S06 diag145c: the stopped child game (no longer running) was reported
        # "unknown", so the Gateway could never confirm the child stop.
        self.probe.kernel32.OpenProcess.return_value = 1
        self.exit_code(0)
        self.probe.kernel32.QueryFullProcessImageNameW.return_value = False
        self.probe.kernel32.GetProcessTimes.return_value = False

        self.assertEqual(("gone", None), self.probe.process_identity_status(44, include_owner=True))
        self.probe.kernel32.CloseHandle.assert_called_once_with(1)

    def test_exited_process_held_open_elsewhere_is_gone(self):
        self.probe.kernel32.OpenProcess.return_value = 1
        self.probe.kernel32.QueryFullProcessImageNameW.return_value = True
        self.probe.kernel32.GetProcessTimes.return_value = True
        for exit_code, expected in ((0, "gone"), (WindowProbe.STILL_ACTIVE, "alive")):
            self.probe.kernel32.GetExitCodeProcess.side_effect = \
                lambda _handle, value, code=exit_code: setattr(value._obj, "value", code) is None
            self.assertEqual(expected, self.probe.process_identity_status(44)[0])


class RunningScanFreshnessTest(unittest.TestCase):
    def test_catalog_update_marks_the_running_scan_outdated(self):
        # A library revision change leaves process evidence stale until the next scan; the
        # monitor rescans promptly instead of waiting out the 5 s interval (lab diag146b).
        state = BridgeState(game_operations=GameOperationsService(
            OperationJournal(None), steam=SteamProvider(root_resolver=lambda: None)))
        state.library_revision = "2"
        state._running_scan_revision = "1"
        self.assertTrue(state.running_scan_outdated())
        state._running_scan_revision = "2"
        self.assertFalse(state.running_scan_outdated())


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

    def test_scan_status_transitions_are_recorded_once_with_a_reason_category(self):
        recorder = mock.Mock()
        with mock.patch("GameProviderBridge.DIAGNOSTICS", recorder):
            self.state.refresh_running_games()
            self.state.refresh_running_games()
            self.probe.scan_running_processes.return_value = ([], "complete")
            self.state.refresh_running_games()
        self.assertEqual(
            [("unavailable", "partial", "process_unreadable"),
             ("partial", "complete", "verified")],
            [(call.kwargs["previous_state"], call.kwargs["status"], call.kwargs["reason"])
             for call in recorder.record.call_args_list])
        self.assertNotIn("process_path", str(recorder.record.call_args_list))

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
        self.assertEqual(1, len(self.state._verified_running_games(RUNNING_SCAN_DEADLINE)[0]))
        self.probe.scan_running_processes.side_effect = lambda _images, _catalog, _budget: (
            setattr(self.state, "library_revision", "3") or ([self.hk], "complete"))
        self.state.refresh_running_games()
        self.assertEqual([], self.state.current_snapshot()["running_games"])

    def test_shared_exe_native_without_connector_confirmation_is_not_claimed(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {guid: {"id": guid, "provider": "playnite",
                                     "playniteGameId": guid, "exe": self.hk["process_path"]}}
        self.probe.scan_running_processes.return_value = ([self.hk], "complete")
        self.assertEqual([], self.state._verified_running_games(RUNNING_SCAN_DEADLINE)[0])
        self.state.connected = True
        self.state.current = {"id": guid, "processId": 11, "processStartedFiletime": 100}
        self.state._native_reconciliation_confirmation = (guid, 11)
        self.assertEqual(1, len(self.state._verified_running_games(RUNNING_SCAN_DEADLINE)[0]))
        self.state.library["steam:2"] = {"id": "steam:2", "provider": "steam", "exe": self.hk["process_path"]}
        self.assertEqual([], self.state._verified_running_games(RUNNING_SCAN_DEADLINE)[0])

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

        running, status, _revision, _reason = self.state._verified_running_games(RUNNING_SCAN_DEADLINE)

        self.assertEqual("complete", status)
        self.assertEqual({"steam:367520", guid},
                         {game["game_id"] for game in running})
        self.assertEqual(44, next(game["process_id"] for game in running
                                  if game["game_id"] == guid))

    def test_unreadable_confirmed_emulator_makes_the_scan_partial(self):
        # An elevated emulator outside its ROM directory: its owner is unreadable, so the
        # idle-looking scan must not read "complete" while it may still run the game.
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {guid: {"id": guid, "provider": "playnite", "playniteGameId": guid,
                                     "installDir": r"C:\Games\Contra", "exe": ""}}
        self.state.connected = True
        self.state.current = {"id": guid, "state": "running", "processId": 44,
                              "processStartedFiletime": 400}
        self.state._native_reconciliation_confirmation = (guid, 44)
        self.probe.scan_running_processes.return_value = ([], "complete")
        own = {"process_id": os.getpid(), "process_path": r"C:\Bridge\bridge.exe",
               "process_started_filetime": 500, "user_sid": "own-sid", "session_id": 1}
        self.probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(own) if pid == os.getpid() else None)
        for probed, expected in ((("alive", identity(44, r"C:\Emu\retroarch.exe", 400)),
                                  ("partial", "process_unreadable")),
                                 (("unknown", None), ("partial", "process_unreadable")),
                                 (("gone", None), ("complete", "verified"))):
            with self.subTest(probed=probed[0]):
                self.probe.process_identity_status.return_value = probed
                running, status, _revision, reason = self.state._verified_running_games(RUNNING_SCAN_DEADLINE)
                self.assertEqual([], running)
                self.assertEqual(expected, (status, reason))

    def test_retained_target_status_is_exact_even_for_an_exe_less_emulator(self):
        # An exe-less emulator entry: once windowless it is never a catalog match, so only
        # the exact target status can tell whether the retained process ended.
        self.state.library["playnite:emu"] = {"id": "playnite:emu", "provider": "playnite",
                                              "installDir": r"C:\Roms", "exe": ""}
        emulator = identity(55, r"C:\Emulators\retroarch.exe", 500)
        token = self.state._process_token("playnite:emu", {
            **emulator, "process_path": self.state._normalized_process_path(
                emulator["process_path"])})
        # R010: removing the game from the catalog must not turn the live process into "reused".
        self.state.library.pop("playnite:emu")
        for probed, expected in (
                (("unknown", None), "unknown"),
                (("alive", emulator), "alive"),
                (("alive", {**emulator, "process_started_filetime": 501}), "reused"),
                (("gone", None), "gone")):
            with self.subTest(expected=expected):
                self.probe.process_identity_status.return_value = probed
                self.assertEqual(expected, self.state.target_process_status(55, token))
                self.probe.process_identity_status.assert_called_with(55, include_owner=True)
        # A token this provider boot never minted cannot be compared.
        self.probe.process_identity_status.return_value = ("alive", emulator)
        self.assertEqual("unknown", self.state.target_process_status(55, "c" * 64))
        self.assertEqual("unknown", self.state.target_process_status(56, token))

    def test_scan_receives_the_image_names_of_exact_game_executables(self):
        self.state.library["steam:2"] = {"id": "steam:2", "provider": "steam",
                                         "installDir": r"C:\Games\Celeste", "exe": "Celeste.exe"}
        self.state._verified_running_games(RUNNING_SCAN_DEADLINE)
        self.probe.scan_running_processes.assert_called_once_with({"celeste.exe"}, mock.ANY, RUNNING_SCAN_DEADLINE)
        catalog_path = self.probe.scan_running_processes.call_args.args[1]
        self.assertTrue(catalog_path(r"C:\Games\Celeste\Celeste.exe"))
        self.assertTrue(catalog_path(r"c:/games/hk/tools/helper.exe"))
        self.assertFalse(catalog_path(r"C:\Program Files\Admin Tool\tool.exe"))

    def test_emulator_stop_event_waits_for_exact_process_exit(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        emulator = identity(44, r"C:\Emulators\retroarch.exe", 400)
        own = identity(os.getpid(), r"C:\Bridge\bridge.exe", 500)
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid,
                   "installDir": r"C:\Emulators", "exe": ""}}
        self.state.connected = True
        self.state.current = {"state": "idle"}
        live = [True]
        self.probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(own) if pid == os.getpid() else dict(emulator) if live[0] else None)
        self.state._process_identity_status_action = lambda pid: (
            ("alive", dict(emulator)) if live[0] else ("gone", None))
        self.state.handle_message({"type": "status", "status": {
            "name": "gameStarted", "id": guid, "processId": 44}})

        self.state.handle_message({"type": "status", "status": {
            "name": "gameStopped", "id": guid}})

        self.assertEqual("running", self.state.current["state"])
        self.assertTrue(self.state.current["connectorStopPending"])
        live[0] = False
        self.probe.process_identity_status.return_value = ("gone", None)
        self.probe.scan_running_processes.return_value = ([], "complete")
        self.assertEqual("idle", self.state.current_snapshot()["state"])

    def test_emulator_stop_identity_failure_stays_pending(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid}}
        self.state.current = {"state": "stopping", "id": guid, "processId": 44,
                              "processPath": r"C:\Emulators\retroarch.exe",
                              "processStartedFiletime": 400}
        self.probe.process_identity.side_effect = OSError("transient")

        self.state.handle_message({"type": "status", "status": {
            "name": "gameStopped", "id": guid}})

        self.assertEqual("stopping", self.state.current["state"])
        self.assertTrue(self.state.current["connectorStopPending"])

    def test_identityless_emulator_crash_self_recovers_from_complete_empty_inventory(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid}}
        self.state.current = {"state": "running", "id": guid, "processId": 44}
        self.state._process_identity_status_action = mock.Mock(
            return_value=("gone", None))
        self.probe.scan_running_processes.return_value = ([], "complete")

        self.state.handle_message({"type": "status", "status": {
            "name": "gameStopped", "id": guid}})

        self.assertEqual("idle", self.state.current_snapshot()["state"])

    def test_identityless_delayed_emulator_handoff_stays_pending(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid}}
        self.state.current = {"state": "running", "id": guid, "processId": 44}
        self.state._process_identity_status_action = mock.Mock(
            return_value=("gone", None))
        self.probe.scan_running_processes.return_value = ([], "partial")

        self.state.handle_message({"type": "status", "status": {
            "name": "gameStopped", "id": guid}})

        self.assertEqual("running", self.state.current_snapshot()["state"])
        self.assertTrue(self.state.current["connectorStopPending"])

    def test_emulator_stop_missing_or_partial_identity_stays_pending(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid}}
        for observed in (None, {"process_path": r"C:\Emulators\retroarch.exe"}):
            self.state.current = {
                "state": "running", "id": guid, "processId": 44,
                "processPath": r"C:\Emulators\retroarch.exe",
                "processStartedFiletime": 400}
            self.state._process_identity_action = mock.Mock(return_value=observed)
            self.state._process_identity_status_action = None
            self.state.handle_message({"type": "status", "status": {
                "name": "gameStopped", "id": guid}})
            self.assertEqual("running", self.state.current["state"])
            self.assertTrue(self.state.current["connectorStopPending"])

    def test_emulator_stop_confirmed_pid_reuse_completes(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid}}
        self.state.current = {
            "state": "running", "id": guid, "processId": 44,
            "processPath": r"C:\Emulators\retroarch.exe",
            "processStartedFiletime": 400}
        replacement = identity(44, r"C:\Other\replacement.exe", 500)
        self.state._process_identity_status_action = mock.Mock(
            return_value=("alive", replacement))

        self.state.handle_message({"type": "status", "status": {
            "name": "gameStopped", "id": guid}})

        self.assertEqual("idle", self.state.current["state"])

    def test_emulator_stop_while_starting_reconciles_after_exact_process_exit(self):
        guid = "840317c9-b9a4-4f72-be8e-807414e36a9b"
        emulator = identity(44, r"C:\Emulators\retroarch.exe", 400)
        self.state.library = {
            guid: {"id": guid, "provider": "playnite", "playniteGameId": guid}}
        self.state.current = {
            "state": "starting", "id": guid, "processId": 44,
            "processPath": emulator["process_path"],
            "processStartedFiletime": emulator["process_started_filetime"]}
        live = [True]
        self.probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(emulator) if live[0] else None)
        self.state._process_identity_status_action = lambda pid: (
            ("alive", dict(emulator)) if live[0] else ("gone", None))

        self.state.handle_message({"type": "status", "status": {
            "name": "gameStopped", "id": guid}})
        self.assertEqual("starting", self.state.current["state"])
        self.assertTrue(self.state.current["connectorStopPending"])

        live[0] = False
        self.probe.process_identity_status.return_value = ("gone", None)
        self.probe.scan_running_processes.return_value = ([], "complete")
        self.assertEqual("idle", self.state.current_snapshot()["state"])

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

        running, _status, _revision, _reason = self.state._verified_running_games(RUNNING_SCAN_DEADLINE)

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

        running, _status, _revision, _reason = self.state._verified_running_games(RUNNING_SCAN_DEADLINE)

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

    def test_hard_reset_clears_orphan_stopping_state_without_game_identity(self):
        self.state.current = {"state": "stopping", "stopRequestedAt": self.state.clock()}
        self.state.readiness = {"ready": False, "reason": "game_stopping"}
        self.probe.scan_running_processes.return_value = ([], "complete")

        result = self.state.hard_reset_session()

        self.assertTrue(result["accepted"])
        self.assertEqual("idle", self.state.current["state"])
        self.assertEqual("session_hard_reset", self.state.readiness["reason"])

    def test_hard_reset_orphan_recovery_allows_the_next_launch(self):
        self.state.current = {"state": "stopping", "stopRequestedAt": self.state.clock()}
        self.state.readiness = {"ready": False, "reason": "game_stopping"}
        self.probe.scan_running_processes.return_value = ([], "complete")
        self.state._launch_provider = mock.Mock(return_value={"accepted": True})

        reset = self.state.hard_reset_session()
        launched = self.state.start_game("steam:1")

        self.assertTrue(reset["accepted"])
        self.assertTrue(launched["accepted"])
        self.assertEqual("starting", self.state.current["state"])
        self.assertEqual("steam:1", self.state.current["id"])
        self.state._launch_provider.assert_called_once()

    def test_hard_reset_keeps_orphan_state_when_empty_inventory_is_partial(self):
        self.state.current = {"state": "stopping", "stopRequestedAt": self.state.clock()}
        self.state.readiness = {"ready": False, "reason": "game_stopping"}
        self.probe.scan_running_processes.return_value = ([], "partial")

        result = self.state.hard_reset_session()

        self.assertFalse(result["accepted"])
        self.assertEqual("running_game_identity_unconfirmed", result["reason"])
        self.assertEqual("stopping", self.state.current["state"])

    def test_hard_reset_clears_connector_stop_when_recorded_pid_is_gone(self):
        self.state.current = {
            "state": "stopping", "id": "epic:Cowbird", "processId": 11156,
            "connectorStopPending": True,
            "stopRequestedAt": self.state.clock(),
        }
        self.state.readiness = {"ready": False, "reason": "game_stop_close_rejected"}
        self.probe.scan_running_processes.return_value = ([], "partial")
        self.state._process_identity_status_action = mock.Mock(
            return_value=("gone", None))

        result = self.state.hard_reset_session()

        self.assertTrue(result["accepted"])
        self.assertEqual("idle", self.state.current["state"])
        self.state._process_identity_status_action.assert_called_once_with(11156)

    def test_hard_reset_keeps_identityless_connector_stop_when_pid_is_not_proven_gone(self):
        self.probe.scan_running_processes.return_value = ([], "partial")
        for status in ("unknown", "alive"):
            with self.subTest(status=status):
                self.state.current = {
                    "state": "stopping", "id": "epic:Cowbird", "processId": 11156,
                    "connectorStopPending": True,
                    "stopRequestedAt": self.state.clock(),
                }
                self.state._process_identity_status_action = mock.Mock(
                    return_value=(status, None))

                result = self.state.hard_reset_session()

                self.assertFalse(result["accepted"])
                self.assertEqual("running_game_identity_unconfirmed", result["reason"])
                self.assertEqual("stopping", self.state.current["state"])

    def test_hard_reset_rejects_gone_candidate_with_unrelated_verified_game(self):
        self.state.current = {
            "state": "stopping", "id": "epic:Cowbird", "processId": 11156,
            "connectorStopPending": True,
        }
        self.probe.scan_running_processes.return_value = ([self.hk], "partial")
        self.state._process_identity_status_action = mock.Mock(
            return_value=("gone", None))

        result = self.state.hard_reset_session()

        self.assertFalse(result["accepted"])
        self.assertEqual("running_game_identity_unconfirmed", result["reason"])
        self.probe.force_terminate_verified_process.assert_not_called()

    def test_hard_reset_rejects_gone_running_nonpending_candidate(self):
        self.state.current = {
            "state": "running", "id": "epic:Cowbird", "processId": 11156,
        }
        self.probe.scan_running_processes.return_value = ([], "partial")
        self.state._process_identity_status_action = mock.Mock(
            return_value=("gone", None))

        result = self.state.hard_reset_session()

        self.assertFalse(result["accepted"])
        self.assertEqual("running_game_identity_unconfirmed", result["reason"])
        self.assertEqual("running", self.state.current["state"])
        self.probe.force_terminate_verified_process.assert_not_called()

    def test_connector_stop_with_gone_pid_waits_when_inventory_is_partial(self):
        self.state.current = {
            "state": "stopping", "id": "epic:Cowbird", "processId": 11156,
            "connectorStopPending": True,
            "stopRequestedAt": self.state.clock() - self.state.stop_timeout - 1,
        }
        self.state.readiness = {"ready": False, "reason": "game_stop_close_rejected"}
        self.state._process_identity_status_action = mock.Mock(
            return_value=("gone", None))
        self.probe.scan_running_processes.return_value = ([], "partial")

        snapshot = self.state.current_snapshot()

        self.assertEqual("stopping", snapshot["state"])

    def test_recent_identityless_connector_stop_waits_for_replacement_sample(self):
        self.state.current = {
            "state": "stopping", "id": "epic:Cowbird", "processId": 11156,
            "connectorStopPending": True,
            "stopRequestedAt": self.state.clock(),
        }
        self.state._process_identity_status_action = mock.Mock(
            return_value=("gone", None))

        snapshot = self.state.current_snapshot()

        self.assertEqual("stopping", snapshot["state"])

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
        self.probe.input_desktop_blocked = mock.Mock(return_value=False)
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
        with mock.patch("GameProviderBridge.time.monotonic", side_effect=[0, 3]):
            self.assertEqual(([], "unavailable"), self.probe.scan_running_processes(set(), lambda _path: True, RUNNING_SCAN_DEADLINE))
        with mock.patch.object(ctypes, "get_last_error", return_value=5, create=True):
            self.assertEqual(([], "unavailable"), self.probe.scan_running_processes(set(), lambda _path: True, RUNNING_SCAN_DEADLINE))
        for change in ({"user_sid": "foreign"}, {"session_id": 2}):
            self.probe.process_identity.side_effect = [self.expected, {**self.expected, **change}]
            with mock.patch.object(ctypes, "get_last_error", return_value=18, create=True):
                self.assertEqual(([], "complete"), self.probe.scan_running_processes(set(), lambda _path: True, RUNNING_SCAN_DEADLINE))


def filetime(unix_seconds):
    return int(unix_seconds * 10_000_000) + FILETIME_UNIX_EPOCH


class StartReadinessTest(unittest.TestCase):
    """Two-phase start: process within GAME_START_TIMEOUT, then the window without a limit."""

    def setUp(self):
        self.now = 1_800_000_000.0
        service = GameOperationsService(OperationJournal(None),
                                       steam=SteamProvider(root_resolver=lambda: None))
        self.state = BridgeState(game_operations=service, profile_id="profile",
                                 clock=lambda: self.now)
        self.state.library_revision = "1"
        self.state.library = {
            "steam:1": {"id": "steam:1", "provider": "steam", "installDir": r"C:\Games\HK"},
            "steam:2": {"id": "steam:2", "provider": "steam", "installDir": r"C:\Games\Other"},
        }
        self.probe = mock.Mock()
        self.probe.scan_running_processes.return_value = ([], "complete")
        self.probe.process_identity_status.return_value = ("unknown", None)
        self.state.running_process_probe = self.probe
        self.requested_at = self.now
        self.state.current = {"state": "starting", "id": "steam:1",
                              "installDir": r"C:\Games\HK",
                              "launchRequestedAt": self.requested_at}
        self.state.readiness = {"ready": False, "reason": "game_starting",
                                "target_kind": "game", "stable_samples": 0}
        self.game = identity(11, started=filetime(self.requested_at + 5))

    def scan(self, *games, status="complete"):
        self.probe.scan_running_processes.return_value = (list(games), status)
        self.state.refresh_running_games()

    def no_window(self):
        self.state.apply_window_sample({"qualified": False, "reason": "waiting_for_game_window"})

    def test_phase_one_fails_after_the_limit_without_a_process(self):
        self.now += GAME_START_TIMEOUT - 1
        self.no_window()
        self.assertEqual("starting", self.state.current["state"])
        self.assertEqual("game_starting", self.state.readiness["reason"])
        self.now += 1
        self.no_window()
        self.assertEqual("failed", self.state.current["state"])
        self.assertEqual("game_start_timeout", self.state.current["reason"])
        self.assertEqual(self.requested_at, self.state.current["launchRequestedAt"])
        self.assertEqual("game-launch-failed", self.state.events[-1]["event"])

    def test_verified_process_has_no_window_limit_and_carries_its_token(self):
        self.scan(self.game)
        self.assertEqual(11, self.state.current["processId"])
        self.assertEqual("waiting_for_game_window", self.state.readiness["reason"])
        for elapsed in (GAME_START_TIMEOUT + 1, 3600):
            self.now = self.requested_at + elapsed
            self.no_window()
            self.scan(self.game)
            self.assertEqual("starting", self.state.current["state"])
        self.assertEqual("waiting_for_game_window", self.state.readiness["reason"])
        observation = self.state.observation_snapshot()
        self.assertEqual("starting", observation["game"]["state"])
        self.assertEqual("VERIFIED", observation["process"]["evidence"])
        self.assertEqual(11, observation["process"]["process_id"])
        self.assertRegex(observation["process"]["process_token"], "^[0-9a-f]{64}$")
        self.assertFalse(observation["window"]["ready"])
        self.assertEqual("PENDING", observation["window"]["evidence"])

    def test_exit_in_phase_two_fails_only_on_proven_absence(self):
        self.scan(self.game)
        self.state.apply_window_sample({"qualified": False, "reason": "game_process_exited",
                                        "process_id": 11})
        self.assertEqual("starting", self.state.current["state"])
        self.scan(status="partial")
        self.scan()  # liveness unknown
        self.probe.process_identity_status.return_value = (
            "alive", {**self.game, "visible_window": False})
        self.scan()  # alive without a visible window: still the same process
        self.assertEqual("starting", self.state.current["state"])
        self.probe.process_identity_status.return_value = ("gone", None)
        self.scan()
        self.assertEqual("failed", self.state.current["state"])
        self.assertEqual("game_exited_during_start", self.state.current["reason"])
        self.assertEqual("game_exited_during_start", self.state.readiness["reason"])
        self.assertEqual({"id": "steam:1", "reason": "game_exited_during_start"},
                         self.state.events[-1]["payload"])

    def test_verified_successor_replaces_an_exited_launcher_process(self):
        self.scan(self.game)
        successor = identity(12, r"C:\Games\HK\bin\hk.exe", filetime(self.requested_at + 9))
        self.probe.process_identity_status.return_value = ("gone", None)
        self.scan(successor)
        self.assertEqual("starting", self.state.current["state"])
        self.assertEqual(12, self.state.current["processId"])

    def test_late_window_after_200_seconds_makes_the_game_running(self):
        self.scan(self.game)
        self.now += 200
        self.state.apply_window_sample({"qualified": False, "reason": "waiting_for_game_window",
                                        "process_id": 12, "replacement_process": True})
        self.assertEqual("starting", self.state.current["state"])
        self.state.apply_window_sample({"qualified": False, "reason": "target_not_fullscreen",
                                        "process_id": 11})
        self.assertEqual("running", self.state.current["state"])
        self.assertEqual(11, self.state.current["processId"])
        self.assertFalse(self.state.readiness["ready"])

    def test_failed_start_adopts_a_late_process_of_the_same_request(self):
        self.now += GAME_START_TIMEOUT
        self.no_window()
        self.assertEqual("failed", self.state.current["state"])
        self.scan(identity(11, started=filetime(self.requested_at + 95)))
        self.assertEqual("starting", self.state.current["state"])
        self.assertNotIn("reason", self.state.current)
        self.assertEqual("waiting_for_game_window", self.state.readiness["reason"])
        self.assertEqual("VERIFIED", self.state.observation_snapshot()["process"]["evidence"])
        self.state.apply_window_sample({"qualified": False, "reason": "target_not_foreground",
                                        "process_id": 11})
        self.assertEqual("running", self.state.current["state"])

    def test_no_adoption_of_another_game_or_an_earlier_request(self):
        self.now += GAME_START_TIMEOUT
        self.no_window()
        other_game = identity(21, r"C:\Games\Other\other.exe", filetime(self.requested_at + 95))
        earlier = identity(11, started=filetime(self.requested_at - 30))
        self.scan(other_game, earlier)
        self.assertEqual("failed", self.state.current["state"])
        self.assertNotIn("processId", self.state.current)
        # A new request for the same game never adopts the previous request's process.
        self.state.current = {"state": "starting", "id": "steam:1",
                              "installDir": r"C:\Games\HK",
                              "launchRequestedAt": self.now}
        self.scan(identity(11, started=filetime(self.requested_at + 5)))
        self.assertNotIn("processId", self.state.current)
        self.assertEqual("PENDING", self.state.observation_snapshot()["process"]["evidence"])

    def test_probe_reason_changes_are_recorded_once_without_titles(self):
        samples = iter([
            {"qualified": False, "reason": "waiting_for_game_window",
             "probe_window_count": 0, "probe_windows": []},
            {"qualified": False, "reason": "waiting_for_game_window",
             "probe_window_count": 0, "probe_windows": []},
            {"qualified": False, "reason": "waiting_for_game_window", "probe_window_count": 5,
             "probe_windows": ["pid-11:160x28:visible:minimized:uncloaked:DISPLAY1",
                               "Secret Title", "a", "b", "c"]},
        ])
        probe = mock.Mock()
        probe.sample.side_effect = lambda *_args: dict(next(samples))
        resolver = mock.Mock()
        resolver.resolve.return_value = None
        self.state.installation_probes = mock.Mock(return_value=[])
        self.state.refresh_running_games = mock.Mock()
        sleeps = iter([None, None, StopIteration()])

        def sleep(_seconds):
            value = next(sleeps)
            if value is not None:
                raise value

        recorder = mock.Mock()
        with mock.patch("GameProviderBridge.DIAGNOSTICS", recorder), \
                mock.patch("GameProviderBridge.time.sleep", side_effect=sleep):
            with self.assertRaises(StopIteration):
                WindowReadinessWorker(self.state, probe, resolver).run()
        calls = [call for call in recorder.record.call_args_list
                 if call.args[0] == "window-probe.reason-changed"]
        self.assertEqual(1, len(calls))
        self.assertEqual({"game_id": "steam:1", "state": "starting",
                          "reason": "waiting_for_game_window", "previous_reason": "none",
                          "window_count": 0, "windows": []}, calls[0].kwargs)

        diagnostics = ProviderDiagnostics()
        diagnostics.logger = mock.Mock()
        diagnostics.record("window-probe.reason-changed", reason="target_not_fullscreen",
                           window_count=5, windows=[
                               "pid-11:160x28:visible:minimized:uncloaked:DISPLAY1",
                               "Secret Title", "a", "b", "c"])
        entry = json.loads(diagnostics.logger.info.call_args.args[0])
        self.assertEqual(5, entry["window_count"])
        self.assertEqual(["pid-11:160x28:visible:minimized:uncloaked:DISPLAY1", "a", "b"],
                         entry["windows"])

    def test_failed_start_with_kept_install_dir_skips_window_enumeration(self):
        self.state.current = {"state": "failed", "id": "steam:1",
                              "installDir": r"C:\Games\HK",
                              "launchRequestedAt": self.requested_at}
        window_probe = mock.Mock()
        resolver = mock.Mock()
        resolver.resolve.return_value = None
        self.state.installation_probes = mock.Mock(return_value=[])
        self.state.refresh_running_games = mock.Mock()
        sleeps = iter([None, StopIteration()])

        def sleep(_seconds):
            value = next(sleeps)
            if value is not None:
                raise value

        with mock.patch("GameProviderBridge.time.sleep", side_effect=sleep):
            with self.assertRaises(StopIteration):
                WindowReadinessWorker(self.state, window_probe, resolver).run()
        window_probe.sample.assert_not_called()
        # Adoption stays with the running scan.
        self.state.refresh_running_games.assert_called()

    def test_probe_describes_hidden_minimized_and_small_game_windows(self):
        class User32:
            windows = {1: (False, (0, 0, 1920, 1080)), 2: (True, (-32000, -32000, -31840, -31972)),
                       3: (True, (0, 0, 320, 200)), 4: (True, (0, 0, 1920, 1080))}

            @staticmethod
            def GetForegroundWindow():
                return 0

            @classmethod
            def IsWindowVisible(cls, hwnd):
                return cls.windows[hwnd][0]

            @staticmethod
            def GetWindowThreadProcessId(hwnd, process_id):
                process_id._obj.value = 11 if hwnd != 4 else 99
                return 1

            @classmethod
            def GetWindowRect(cls, hwnd, rect):
                (rect._obj.left, rect._obj.top,
                 rect._obj.right, rect._obj.bottom) = cls.windows[hwnd][1]
                return True

            @classmethod
            def EnumWindows(cls, callback, _value):
                for hwnd in cls.windows:
                    callback(hwnd, 0)
                return True

        probe = object.__new__(WindowProbe)
        probe.user32 = User32()
        probe.dwmapi = None
        probe.kernel32 = object()
        probe.input_desktop_blocked = mock.Mock(return_value=False)
        probe._process_tree = mock.Mock(return_value={11})
        probe._process_path = mock.Mock(side_effect=lambda pid: (
            r"C:\Games\HK\game.exe" if pid == 11 else r"C:\Apps\other.exe"))
        probe._process_image = mock.Mock(return_value="")
        probe._window_title = mock.Mock(return_value="Hollow Knight")
        probe._window_class = mock.Mock(return_value="UnityWndClass")
        probe._monitor_details = mock.Mock(return_value=(r"\\.\DISPLAY1", [0, 0, 1920, 1080]))

        sample = probe.sample("game", 11, r"\\.\DISPLAY1", r"C:\Games\HK")

        self.assertEqual("waiting_for_game_window", sample["reason"])
        self.assertEqual(3, sample["probe_window_count"])
        self.assertEqual([
            "pid-11:160x28:visible:minimized:uncloaked:DISPLAY1",
            "pid-11:320x200:visible:normal:uncloaked:DISPLAY1",
            "pid-11:1920x1080:hidden:normal:uncloaked:DISPLAY1",
        ], sample["probe_windows"])
        self.assertNotIn("Hollow", str(sample["probe_windows"]))


if __name__ == "__main__":
    unittest.main()
