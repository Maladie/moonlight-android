import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

from GameProviderBridge import BridgeState
from GameOperations import GameOperationsService, SteamProvider
from OperationJournal import OperationJournal


class PlaytimeTest(unittest.TestCase):
    def make_state(self, path, clock=None):
        service = GameOperationsService(OperationJournal(None),
                                       steam=SteamProvider(root_resolver=lambda: None))
        return BridgeState(cache_path=path, game_operations=service, clock=clock)

    def test_epic_time_survives_refresh_restart_and_playnite_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "library-cache.json"
            state = self.make_state(path)
            state.clock = lambda: 1700000000
            epic = {"id": "epic:Batman", "provider": "epic", "playtimeMinutes": 10}
            state.library = {epic["id"]: dict(epic)}
            state.library_revision = "1"
            running = [{"game_id": epic["id"], "process_id": 22, "process_token": "a"}]
            state._verified_running_games = lambda: (running, "complete", state.library_revision)
            with mock.patch("GameProviderBridge.time.monotonic") as clock:
                for second in range(100, 166, 5):
                    clock.return_value = second
                    state.refresh_running_games()
                self.assertEqual(665, state.library[epic["id"]]["playtimeSeconds"])
                expected_last_played = datetime.fromtimestamp(
                    1700000000, timezone.utc).isoformat()
                self.assertEqual(expected_last_played,
                                 state.library[epic["id"]]["lastPlayed"])
                self.assertEqual(expected_last_played,
                                 state.epic_last_played[epic["id"]])
                self.assertEqual("complete", state.current_snapshot()["running_scan_status"])
                # No Playnite connection; a new provider snapshot contains no usage.
                state.game_operations.aggregate_catalog = mock.Mock(return_value={
                    "library": {epic["id"]: {"id": epic["id"], "provider": "epic"}}})
                state._refresh_catalog()
                self.assertEqual(665, state.library[epic["id"]]["playtimeSeconds"])
                self.assertEqual(expected_last_played,
                                 state.library[epic["id"]]["lastPlayed"])
                running.clear()
                clock.return_value = 170
                state.refresh_running_games()
            restored = self.make_state(path)
            self.assertEqual(665, restored.library[epic["id"]]["playtimeSeconds"])
            self.assertEqual(expected_last_played,
                             restored.library[epic["id"]]["lastPlayed"])
            self.assertEqual(expected_last_played,
                             restored.epic_last_played[epic["id"]])
            self.assertEqual({}, restored._epic_playtime_samples)
            self.assertFalse(restored.connected)

    def test_only_same_verified_epic_process_accrues_contiguous_time(self):
        state = self.make_state(None)
        state.library = {"epic:Batman": {"provider": "epic", "playtimeMinutes": 1},
                         "steam:10": {"provider": "steam", "playtimeMinutes": 120}}
        running = [{"game_id": "epic:Batman", "process_id": 22, "process_token": "a"},
                   {"game_id": "steam:10", "process_id": 11, "process_token": "b"}]
        state._verified_running_games = lambda: (running, "partial", state.library_revision)
        with mock.patch("GameProviderBridge.time.monotonic") as clock:
            for second in (100, 105, 500, 505):  # sleep/long verification gap is not billed
                clock.return_value = second
                state.refresh_running_games()
            self.assertEqual(70, state.library["epic:Batman"]["playtimeSeconds"])
            running[0]["process_token"] = "reused-pid"
            clock.return_value = 510
            state.refresh_running_games()
            self.assertEqual(70, state.library["epic:Batman"]["playtimeSeconds"])
            state._verified_running_games = mock.Mock(side_effect=OSError("probe unavailable"))
            state.refresh_running_games()
            state._verified_running_games = lambda: (running, "complete", state.library_revision)
            clock.return_value = 515
            state.refresh_running_games()
            self.assertEqual(70, state.library["epic:Batman"]["playtimeSeconds"])
        self.assertEqual(120, state.library["steam:10"]["playtimeMinutes"])
        self.assertNotIn("steam:10", state.epic_playtime_seconds)


if __name__ == "__main__":
    unittest.main()
