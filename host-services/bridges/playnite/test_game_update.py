"""A start of a Steam game with a pending update: phase 0, no timeout, then the normal launch."""
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from GameOperations import GameOperationsService, OperationJournal, SteamProvider
from GameProviderBridge import BridgeState, GAME_START_TIMEOUT

APP = "10"


class UpdateStartTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "steamapps" / "common" / "Game").mkdir(parents=True)
        (self.root / "logs").mkdir()
        self.runner = mock.Mock()
        steam = SteamProvider(
            roots=[self.root], root_resolver=lambda: self.root,
            command_runner=self.runner,
            launch_preflight=lambda _provider: {"ready": True})
        steam.executable = lambda: self.root / "steam.exe"
        self.now = 1_800_000_000.0
        self.state = BridgeState(
            game_operations=GameOperationsService(OperationJournal(None), steam=steam),
            profile_id="profile", clock=lambda: self.now, stop_timeout=0.05)
        self.state.library_revision = "1"
        self.state.library = {"steam:10": {
            "id": "steam:10", "provider": "steam", "providerGameId": APP, "name": "Game"}}
        self.manifest(6, downloaded=25, total=100)

    def manifest(self, flags, downloaded=0, total=0, staged=0, to_stage=0):
        (self.root / "steamapps" / f"appmanifest_{APP}.acf").write_text(
            chr(10).join(["\"AppState\"", "{", "\"appid\" \"10\"", "\"installdir\" \"Game\"",
                       f'"StateFlags" "{flags}"',
                       f'"BytesDownloaded" "{downloaded}"',
                       f'"BytesToDownload" "{total}"',
                       f'"BytesStaged" "{staged}"',
                       f'"BytesToStage" "{to_stage}"', "}", ""]), encoding="utf-8")

    def start(self):
        result = self.state.start_game("steam:10")
        self.assertEqual("update", result["command"])
        return self.state.current["launchTaskId"]

    def launches(self):
        return [call for call in self.runner.call_args_list
                if "steam://launch/10/Dialog" in call.args[0]]

    def test_flags_decide_what_is_an_update(self):
        sample = self.state.game_operations.update_sample(dict(self.state.library["steam:10"]))
        self.assertEqual((True, True, 25), (sample["pending"], sample["installed"],
                                            sample["progress"]))
        self.manifest(4 | 64)  # AppRunning is no update
        sample = self.state.game_operations.update_sample(dict(self.state.library["steam:10"]))
        self.assertEqual((False, True, -1), (sample["pending"], sample["installed"],
                                             sample["progress"]))

    def test_update_comes_before_launch_and_never_times_out(self):
        task = self.start()
        self.assertEqual({"-silent", "+app_install", APP}, set(self.runner.call_args.args[0][1:]))
        self.assertEqual([], self.launches())
        self.now += GAME_START_TIMEOUT * 1000
        self.state.advance_update(task)
        self.assertEqual("starting", self.state.current["state"])
        self.assertTrue(self.state.current["updating"])
        observation = self.state.observation_snapshot()
        self.assertEqual("game_updating", observation["window"]["reason"])
        self.assertEqual({"phase": "downloading", "progress": 25},
                         observation["window"]["update"])
        self.manifest(1030, 0, 0)  # no bytes: indeterminate, not frozen
        self.state.advance_update(task)
        self.assertEqual({"phase": "downloading", "progress": -1},
                         self.state.observation_snapshot()["window"]["update"])

    def test_patching_follows_the_download_with_its_own_progress(self):
        task = self.start()
        self.manifest(1030, 100, 100, staged=300, to_stage=1200)
        self.state.advance_update(task)
        self.assertEqual({"phase": "patching", "progress": 25},
                         self.state.observation_snapshot()["window"]["update"])
        self.manifest(1030, 100, 100, staged=1200, to_stage=1200)  # committing
        self.state.advance_update(task)
        self.assertEqual({"phase": "patching", "progress": -1},
                         self.state.observation_snapshot()["window"]["update"])
        self.assertEqual([], self.launches())

    def test_cancel_proves_no_process_so_a_close_is_allowed(self):
        task = self.start()
        probe = mock.Mock()
        probe.scan_running_processes.return_value = ([], "complete")
        self.state.running_process_probe = probe
        self.state.refresh_running_games()
        observation = self.state.observation_snapshot()
        self.assertEqual("complete", observation["process"]["running_scan_status"])
        self.assertEqual("PENDING", observation["process"]["evidence"])
        self.assertEqual("starting", observation["game"]["state"])
        self.state.abandon_start("steam:10")
        self.manifest(4)
        self.state.advance_update(task)
        self.assertEqual("idle", self.state.current["state"])
        self.assertEqual([], self.launches())

    def test_hard_reset_ends_an_update_start_and_it_never_launches_later(self):
        task = self.start()
        probe = mock.Mock()
        probe.scan_running_processes.return_value = ([], "complete")
        self.state.running_process_probe = probe
        result = self.state.hard_reset_session()
        self.assertTrue(result["accepted"], result)
        self.assertEqual("idle", self.state.current["state"])
        self.manifest(4)
        self.state.advance_update(task)
        self.assertEqual([], self.launches())

    def test_library_tiles_carry_the_pending_update_and_it_can_be_started(self):
        page = self.state.library_page("0", 10)["games"]
        self.assertEqual(("required", 25), (page[0]["updateState"], page[0]["updateProgress"]) )
        self.manifest(1030, 100, 100, staged=300, to_stage=1200)
        self.state.game_operations.steam._update_states_cache = None
        page = self.state.library_page("0", 10)["games"]
        self.assertEqual(("patching", 25), (page[0]["updateState"], page[0]["updateProgress"]))
        self.state.library["steam:10"]["installed"] = True
        result = self.state.install_game("steam:10")
        self.assertTrue(result["accepted"], result)
        self.manifest(4)
        self.state.game_operations.steam._update_states_cache = None
        self.assertNotIn("updateState", self.state.library_page("0", 10)["games"][0])
        with self.assertRaises(ValueError):
            self.state.install_game("steam:10")

    def test_an_app_without_a_licence_is_no_update_and_launches_as_it_is(self):
        log = self.root / "logs" / "content_log.txt"
        failed = "Failed installing AppID 10 (No subscription)"
        log.write_text(failed + chr(10), encoding="utf-8")
        self.state.game_operations.steam._update_states_cache = None
        self.assertNotIn("updateState", self.state.library_page("0", 10)["games"][0])
        result = self.state.start_game("steam:10")
        self.assertNotEqual("update", result.get("command"))
        self.assertEqual(1, len(self.launches()))
        # a later state line of the app (bought, shared again) clears the failure
        log.write_text(failed + chr(10) + "AppID 10 state changed : Update Running," + chr(10),
                       encoding="utf-8")
        sample = self.state.game_operations.update_sample(
            dict(self.state.library["steam:10"]))
        self.assertTrue(sample["pending"])

    def test_launch_starts_only_when_the_update_is_done(self):
        task = self.start()
        self.state.advance_update(task)
        self.assertEqual([], self.launches())
        self.manifest(4)
        self.now += 5000
        self.state.advance_update(task)
        self.assertEqual(1, len(self.launches()))
        self.assertNotIn("updating", self.state.current)
        self.assertEqual(self.now, self.state.current["launchRequestedAt"])
        self.assertEqual("game_starting", self.state.readiness["reason"])
        self.assertNotIn("update", self.state.observation_snapshot()["window"])

    def test_cancel_ends_the_update_without_a_launch(self):
        task = self.start()
        self.state.stop_game("steam:10")
        self.manifest(4)
        self.state.advance_update(task)
        self.assertEqual([], self.launches())
        self.assertEqual("idle", self.state.current["state"])

    def test_log_evidence_ends_the_update_as_failed(self):
        task = self.start()
        log = self.root / "logs" / "content_log.txt"
        log.write_text("AppID 10 state changed : Update Running,Disk write failure,\n",
                       encoding="utf-8")
        self.state.advance_update(task)
        self.assertEqual("failed", self.state.current["state"])
        self.assertEqual("steam_update_disk_write_failure", self.state.current["reason"])

    def test_log_error_followed_by_recovery_is_no_evidence(self):
        task = self.start()
        (self.root / "logs" / "content_log.txt").write_text(
            "AppID 10 state changed : Disk write failure,\n"
            "AppID 10 state changed : Update Running,\n", encoding="utf-8")
        self.state.advance_update(task)
        self.assertEqual("starting", self.state.current["state"])

    def test_standing_manifest_resends_the_command_not_ends_the_update(self):
        task = self.start()
        self.state.advance_update(task)
        sent = self.runner.call_count
        original = SteamProvider.UPDATE_NUDGE_SECONDS
        with mock.patch.object(SteamProvider, "UPDATE_NUDGE_SECONDS", 0.0):
            self.state.advance_update(task)
        self.assertEqual(original, 120.0)
        self.assertEqual(sent + 1, self.runner.call_count)
        self.assertEqual("starting", self.state.current["state"])


if __name__ == "__main__":
    unittest.main()
