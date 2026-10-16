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

    def manifest(self, flags, downloaded=0, total=0):
        (self.root / "steamapps" / f"appmanifest_{APP}.acf").write_text(
            '"AppState"\n{\n"appid" "10"\n"installdir" "Game"\n'
            f'"StateFlags" "{flags}"\n"BytesDownloaded" "{downloaded}"\n'
            f'"BytesToDownload" "{total}"\n}}\n', encoding="utf-8")

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
        self.assertEqual({"progress": 25}, observation["window"]["update"])
        self.manifest(1030, 0, 0)  # no bytes: indeterminate, not frozen
        self.state.advance_update(task)
        self.assertEqual({"progress": -1},
                         self.state.observation_snapshot()["window"]["update"])

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
