import tempfile
import unittest
from pathlib import Path

from OperationJournal import OperationJournal


class OperationJournalTest(unittest.TestCase):
    def test_operation_survives_restart_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "operations.sqlite3"
            journal = OperationJournal(path)
            first = journal.begin("game", "install", "steam", "FEZ")
            second = journal.begin("game", "install", "steam", "FEZ")
            self.assertEqual(first["requested_at"], second["requested_at"])

            journal.update("game", "attention_required", detail="launcher_prompt",
                           window_handle=42, window_title="Steam", launcher="steam.exe")
            restored = OperationJournal(path).get("game")
            self.assertEqual("attention_required", restored["state"])
            self.assertEqual(42, restored["window_handle"])

            OperationJournal(path).update("game", "completed")
            self.assertEqual([], OperationJournal(path).active())


if __name__ == "__main__":
    unittest.main()
