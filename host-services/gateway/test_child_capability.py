import json
import tempfile
import unittest
from pathlib import Path

import wakeplay_gateway


_MISSING = object()


class ChildCapabilityConfigTest(unittest.TestCase):
    def make_state(self, configured=_MISSING):
        temporary = tempfile.TemporaryDirectory()
        config_path = Path(temporary.name) / "gateway.json"
        config = {
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "profiles": {
                "parent": {
                    "id": "parent",
                    "kind": "standard",
                    "name": "Parent",
                    "enabled": True,
                    "owner": "TESTPC\\Parent",
                    "windows_account_name": "TESTPC\\Parent",
                    "owner_sid": "S-1-5-21-1-2-3-1001",
                    "windows_account_sid": "S-1-5-21-1-2-3-1001",
                },
            },
            "clients": [],
        }
        if configured is not _MISSING:
            config["child_profiles_enabled"] = configured
        config_path.write_text(json.dumps(config), encoding="utf-8")
        return temporary, wakeplay_gateway.GatewayState(config_path, None)

    def test_missing_and_false_keep_child_api_disabled(self):
        for configured in (_MISSING, False):
            with self.subTest(configured=configured):
                temporary, state = self.make_state(configured)
                try:
                    self.assertIs(state.child_profiles_api_enabled, False)
                finally:
                    temporary.cleanup()

    def test_literal_true_enables_child_api(self):
        temporary, state = self.make_state(True)
        try:
            self.assertIs(state.child_profiles_api_enabled, True)
        finally:
            temporary.cleanup()

    def test_non_boolean_values_fail_closed(self):
        for configured in (None, 0, 1, "true", [], {}):
            with self.subTest(configured=configured):
                temporary, state = self.make_state(configured)
                try:
                    self.assertIs(state.child_profiles_api_enabled, False)
                finally:
                    temporary.cleanup()

    def test_change_requires_new_gateway_state(self):
        temporary, state = self.make_state(False)
        try:
            config_path = Path(temporary.name) / "gateway.json"
            config = json.loads(config_path.read_text(encoding="utf-8"))
            config["child_profiles_enabled"] = True
            config_path.write_text(json.dumps(config), encoding="utf-8")
            self.assertIs(state.child_profiles_api_enabled, False)
            restarted = wakeplay_gateway.GatewayState(config_path, None)
            self.assertIs(restarted.child_profiles_api_enabled, True)
        finally:
            temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
