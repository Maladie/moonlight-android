"""An Epic (Legendary) game with a pending update: tiles, phase 0 and the tile's update action."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from GameOperations import EpicProvider, GameOperationsService, OperationJournal
from GameProviderBridge import BridgeState

GAME_ID = "epic:App"


class FakeProcess:
    def __init__(self, lines=()):
        self.stdout = list(lines)
        self.returncode = None

    def poll(self):
        return self.returncode

    def terminate(self):
        self.returncode = -1


class EpicUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        (root / "legendary.exe").write_bytes(b"")
        self.state_path = root / "state"
        self.state_path.mkdir()
        self.processes = []
        self.commands = []

        def run(arguments, **_kwargs):
            self.commands.append(arguments[1:])
            return SimpleNamespace(returncode=0, stdout="[]")

        def start(arguments, **_kwargs):
            process = FakeProcess(["[DLManager] INFO: = Progress: 42.00% (1/2), ETA: 00:01:00\n"])
            self.processes.append((arguments[1:], process))
            return process

        self.epic = EpicProvider(
            legendary_path=root / "legendary.exe", legendary_state_path=self.state_path,
            command_runner=run, process_runner=start)
        self.epic._resolve_legendary_app = lambda _game: "App"
        self.epic._installed_legendary = lambda _app: (True, {
            "installed": True, "provider": "epic", "install_directory": str(root),
            "executable": "Game.exe", "app_name": "App"})
        self.epic.launch = mock.Mock(return_value={"accepted": True, "dispatch": "legendary"})
        self.state = BridgeState(
            game_operations=GameOperationsService(OperationJournal(None), epic=self.epic),
            profile_id="profile", clock=lambda: 1_800_000_000.0, stop_timeout=0.05)
        self.state.nonsteam_launch_preparation = lambda _dispatch: {"ready": True}
        self.state.library_revision = "1"
        self.state.library = {GAME_ID: {
            "id": GAME_ID, "provider": "epic", "providerGameId": "App", "name": "Game",
            "installed": True}}
        self.versions(installed="1", latest="2")

    def versions(self, installed, latest):
        (self.state_path / "installed.json").write_text(json.dumps({"App": {
            "app_name": "App", "version": installed, "platform": "Windows"}}), encoding="utf-8")
        (self.state_path / "assets.json").write_text(json.dumps({"Windows": [
            {"app_name": "App", "build_version": latest}]}), encoding="utf-8")

    def update_process(self):
        arguments, process = self.processes[-1]
        self.assertEqual(["-y", "install", "App", "--update-only", "--skip-dlcs", "--skip-sdl"],
                         arguments)
        self.epic.process_registry.active(GAME_ID).collector.join(1)
        return process

    def start(self):
        result = self.state.start_game(GAME_ID)
        self.assertEqual("update", result["command"], result)
        self.assertIn(["list-installed", "--check-updates", "--json"], self.commands)
        return self.state.current["launchTaskId"]

    def test_version_against_latest_build_decides_what_is_an_update(self):
        self.assertEqual({"App"}, self.epic._pending_updates())
        self.versions(installed="2", latest="2")
        self.assertEqual(set(), self.epic._pending_updates())
        self.versions(installed="1", latest="")  # no asset known: no update
        self.assertEqual(set(), self.epic._pending_updates())

    def test_update_comes_before_launch_with_its_progress(self):
        task = self.start()
        process = self.update_process()
        self.state.advance_update(task)
        self.assertEqual({"phase": "downloading", "progress": 42},
                         self.state.observation_snapshot()["window"]["update"])
        self.epic.launch.assert_not_called()
        self.versions(installed="2", latest="2")
        process.returncode = 0
        self.state.advance_update(task)
        self.epic.launch.assert_called_once()
        self.assertEqual("game_starting", self.state.readiness["reason"])

    def test_failed_update_process_ends_the_start(self):
        task = self.start()
        self.update_process().returncode = 1
        self.state.advance_update(task)
        self.assertEqual("failed", self.state.current["state"])
        self.assertEqual("epic_update_failed", self.state.current["reason"])
        self.epic.launch.assert_not_called()

    def test_update_that_exits_but_stays_pending_is_incomplete(self):
        task = self.start()
        self.update_process().returncode = 0
        self.state.advance_update(task)
        self.assertEqual("epic_update_incomplete", self.state.current["reason"])

    def test_up_to_date_game_launches_without_an_update(self):
        self.versions(installed="2", latest="2")
        self.assertNotEqual("update", self.state.start_game(GAME_ID).get("command"))
        self.epic.launch.assert_called_once()
        self.assertEqual([], self.processes)

    def test_library_tiles_carry_the_update_and_the_card_action_runs_it(self):
        page = self.state.library_page("0", 10)["games"]
        self.assertEqual(("required", -1), (page[0]["updateState"], page[0]["updateProgress"]))
        result = self.state.install_game(GAME_ID)
        self.assertTrue(result["accepted"], result)
        process = self.update_process()
        page = self.state.library_page("0", 10)["games"]
        self.assertEqual(("downloading", 42), (page[0]["updateState"], page[0]["updateProgress"]))
        process.returncode = 0
        self.versions(installed="2", latest="2")
        self.assertNotIn("updateState", self.state.library_page("0", 10)["games"][0])


if __name__ == "__main__":
    unittest.main()
