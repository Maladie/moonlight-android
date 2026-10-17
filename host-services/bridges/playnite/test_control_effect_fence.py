import tempfile
import threading
import unittest
from pathlib import Path

from ControlEffectFence import ControlEffectFence


class ControlEffectFenceTest(unittest.TestCase):
    def setUp(self):
        self.binding = {"operation_id": "provider-old", "generation": 11,
            "actor_lease_epoch": 11, "effect_sequence": 67, "actor_profile_id": "kid",
            "execution_profile_id": "parent", "stream_session_id": "stream-1",
            "child_session_id": "11111111-1111-4111-8111-111111111111"}

    def test_retirement_fences_delayed_effect_across_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "control-operation.json"
            fence = ControlEffectFence(path, threading.RLock())
            result = fence.retire(self.binding)
            self.assertTrue(result["accepted"])
            restored = ControlEffectFence(path, threading.RLock())
            effects = []
            with self.assertRaisesRegex(ValueError, "Retired"):
                restored.run(self.binding, lambda: effects.append("old"))
            newer = {**self.binding, "generation": 12, "actor_lease_epoch": 12,
                     "effect_sequence": 1, "operation_id": "parent-start",
                     "actor_profile_id": "parent", "child_session_id": ""}
            restored.run(newer, lambda: effects.append("parent"))
            self.assertEqual(["parent"], effects)

    def test_condition_wait_cannot_release_provider_effect_barrier(self):
        lock = threading.RLock()
        condition = threading.Condition(lock)
        fence = ControlEffectFence(None, lock)
        entered = threading.Event()
        release = threading.Event()
        effects = []
        def effect():
            entered.set()
            while not release.is_set():
                condition.wait()
            effects.append("finished")
            return {"accepted": False, "reason": "game_stop_timeout"}
        worker = threading.Thread(target=lambda: fence.run(self.binding, effect))
        worker.start()
        try:
            self.assertTrue(entered.wait(1))
            # State lock is available, but the effect itself is still in flight.
            with lock:
                result = fence.retire(self.binding)
                self.assertFalse(result["accepted"])
                self.assertEqual("in_progress", result["state"])
                release.set()
                condition.notify_all()
        finally:
            with lock:
                release.set()
                condition.notify_all()
            worker.join(1)
        self.assertFalse(worker.is_alive())
        self.assertEqual(["finished"], effects)
        receipt = fence.retire(self.binding)
        self.assertEqual("game_stop_timeout", receipt["result"]["reason"])

    def test_failed_durable_write_never_executes_effect(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "directory-is-not-a-file"
            path.mkdir()
            fence = ControlEffectFence(path, threading.RLock())
            effects = []
            with self.assertRaises(OSError):
                fence.run(self.binding, lambda: effects.append("unsafe"))
            self.assertEqual([], effects)

    def test_hard_reset_barrier_rejects_a_delayed_old_effect(self):
        fence = ControlEffectFence(None, threading.RLock())
        effects = []
        fence.run(self.binding, lambda: effects.append("old-before-reset"))
        reset = {
            **self.binding,
            "generation": 12,
            "actor_lease_epoch": 12,
            "effect_sequence": 0,
            "operation_id": "hard-reset:12:12",
            "stream_session_id": "hard-reset:12",
            "child_session_id": "",
            "actor_profile_id": "parent",
        }

        fence.run(reset, lambda: effects.append("reset"))
        with self.assertRaisesRegex(ValueError, "Stale"):
            fence.run(self.binding, lambda: effects.append("delayed-old"))

        self.assertEqual(["old-before-reset", "reset"], effects)


if __name__ == "__main__":
    unittest.main()
