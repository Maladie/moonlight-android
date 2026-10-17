import threading
import time
import unittest

from GameProviderBridge import BridgeState


GAME_A = "840317c9-b9a4-4f72-be8e-807414e36a9b"
TOKEN_A = "a" * 64


class SessionObservationTest(unittest.TestCase):
    def setUp(self):
        self.wall = [1_700_000_000.0]
        self.state = BridgeState(clock=lambda: self.wall[0], profile_id="living-room")
        self.state.version_info = {"version": "0.7.118", "build": "2026.09.21"}
        self.state.connected = True
        self.state.library_revision = "catalog-1"
        self.state.library[GAME_A] = {"id": GAME_A, "providerCapabilities": {}}

    def publish_running_a(self):
        self.state.current = {"state": "running", "id": GAME_A,
                              "processId": 41}
        self.state.readiness = {"ready": True, "reason": "target_window_ready",
                                "target_kind": "game", "stable_samples": 3}
        self.state._running_games = [{"game_id": GAME_A, "process_id": 41,
                                      "process_token": TOKEN_A}]
        self.state._running_scan_status = "complete"
        self.state._running_scan_revision = "catalog-1"
        self.state._running_scan_at = time.monotonic()

    def test_schema_revision_and_stable_episode(self):
        self.publish_running_a()
        first = self.state.observation_snapshot()
        self.wall[0] += 1
        second = self.state.observation_snapshot()
        self.assertEqual(2, first["schema_version"])
        self.assertEqual("game-provider", first["producer"]["component"])
        self.assertEqual("living-room", first["producer"]["profile_id"])
        self.assertGreater(first["producer"]["process_id"], 0)
        self.assertEqual("0.7.118", first["producer"]["version"])
        self.assertEqual("2026.09.21", first["producer"]["build"])
        self.assertEqual(first["producer"]["boot_id"], second["producer"]["boot_id"])
        self.assertEqual(first["producer"]["revision"] + 1,
                         second["producer"]["revision"])
        self.assertEqual(first["state_episode"], second["state_episode"])
        self.assertEqual(first["state_since_unix_ms"],
                         second["state_since_unix_ms"])
        self.assertEqual("VERIFIED", second["process"]["evidence"])
        self.assertEqual(TOKEN_A, second["process"]["process_token"])
        self.assertTrue(second["window"]["ready"])

    def test_process_start_is_the_verified_game_process_wall_clock(self):
        def filetime(unix_ms):
            return (unix_ms + 11_644_473_600_000) * 10_000

        self.publish_running_a()
        # Without a start time of the process the fact is unknown, not invented.
        self.assertEqual(0, self.state.observation_snapshot()["process"]["started_unix_ms"])
        self.state.current["processStartedFiletime"] = filetime(1_699_999_000_123)
        first = self.state.observation_snapshot()
        self.assertEqual(1_699_999_000_123, first["process"]["started_unix_ms"])
        # The start is display data: it opens no new episode.
        self.wall[0] += 60
        self.assertEqual(first["state_episode"],
                         self.state.observation_snapshot()["state_episode"])
        # Another game in the same stream reports its own process start.
        game_b = "940317c9-b9a4-4f72-be8e-807414e36a9c"
        self.state.library[game_b] = {"id": game_b, "providerCapabilities": {}}
        self.state.current = {"state": "running", "id": game_b, "processId": 42,
                              "processStartedFiletime": filetime(1_699_999_900_000)}
        self.state._running_games = [{"game_id": game_b, "process_id": 42,
                                      "process_token": "b" * 64}]
        second = self.state.observation_snapshot()
        self.assertEqual((game_b, 1_699_999_900_000), (
            second["game"]["id"], second["process"]["started_unix_ms"]))
        # An unverified process has no start, and neither has an idle provider.
        self.state._running_games = []
        self.assertEqual(0, self.state.observation_snapshot()["process"]["started_unix_ms"])
        self.state.current = {"state": "idle"}
        self.assertEqual(0, self.state.observation_snapshot()["process"]["started_unix_ms"])

    def test_atomic_snapshot_never_mixes_game_and_window(self):
        self.publish_running_a()
        entered = threading.Event()
        captured = []
        with self.state.lock:
            worker = threading.Thread(target=lambda: (entered.set(), captured.append(
                self.state.observation_snapshot())))
            worker.start()
            self.assertTrue(entered.wait(1))
            self.state.current = {"state": "idle"}
            self.state.readiness = {"ready": False, "reason": "no_running_game",
                                    "target_kind": "playnite", "stable_samples": 0}
            self.state._running_games = []
        worker.join(1)
        self.assertFalse(worker.is_alive())
        self.assertEqual("ABSENT", captured[0]["game"]["evidence"])
        self.assertEqual("ABSENT", captured[0]["window"]["evidence"])
        self.assertFalse(captured[0]["window"]["ready"])

    def test_missing_process_proof_is_explicit_and_never_ready(self):
        self.publish_running_a()
        self.state._running_games = []
        observation = self.state.observation_snapshot()
        self.assertEqual("PENDING", observation["process"]["evidence"])
        self.assertEqual("PENDING", observation["window"]["evidence"])
        self.assertEqual(0, observation["process"]["process_id"])
        self.assertFalse(observation["window"]["ready"])


if __name__ == "__main__":
    unittest.main()
