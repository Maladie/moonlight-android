import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import wakeplay_gateway
from wakeplay_gateway import GatewayState, sha256_text


class ChildSharingGatewayTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temporary.name) / "gateway.json"
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {
                "parent": {
                    "id": "parent",
                    "kind": "standard",
                    "name": "Parent",
                    "enabled": True,
                    "windows_account_sid": "S-1-5-21-1-2-3-1001",
                    "windows_account_name": "TESTPC\\Player",
                    "account_mapping_status": "resolved",
                    "game_provider_bridge": "http://127.0.0.1:8780",
                    "playnite_bridge": "http://127.0.0.1:8780",
                },
                "kid": {
                    "id": "kid",
                    "kind": "child",
                    "name": "Kid",
                    "display_name": "Kid",
                    "parent_profile_id": "parent",
                    "enabled": True,
                    "policy_revision": 0,
                    "allowed_game_keys": ["parent/steam:123"],
                },
                "other-kid": {
                    "id": "other-kid",
                    "kind": "child",
                    "name": "Other Kid",
                    "display_name": "Other Kid",
                    "parent_profile_id": "parent",
                    "enabled": False,
                    "policy_revision": 0,
                    "allowed_game_keys": [],
                },
            },
            "clients": [{
                "id": "tv",
                "token_sha256": sha256_text("token"),
                "profile_grants": {
                    "parent": ["use_profile", "manage_children"],
                    "kid": ["use_profile"],
                },
            }],
        }), encoding="utf-8")
        self.state = GatewayState(self.config_path, None)
        self.client = self.state.config["clients"][0]
        self.state.config["profiles"]["parent"]["pin_verifier"] = self.pin_verifier()
        self.session_id = "01234567-89ab-cdef-0123-456789abcdef"
        status, result = self.state.begin_child_management(
            self.client, "parent", "2468", self.session_id)
        self.assertEqual(200, status)
        self.authorization_id = result["authorization_id"]
        self.proxy_json_calls = []

        def proxy_json(name, path, body, timeout=8.0, profile_id=None):
            self.proxy_json_calls.append((name, path, body, profile_id))
            if path == "/library/resolve":
                return True, {
                    "game_id": body["game_id"],
                    "canonical_game_id": body["game_id"].lower()
                    if body["game_id"].startswith("STEAM:") else body["game_id"],
                    "revision": "catalog-1",
                }
            if path == "/library/list":
                return True, {
                    "games": [{"id": "steam:123", "name": "Allowed"}],
                    "next_cursor": "10",
                    "total": 1,
                    "revision": "catalog-1",
                    "categories": [], "plugins": [], "libraries": [], "providers": {},
                }
            if path == "/game/start":
                return True, {"accepted": False, "reason": "outside_schedule"}
            return True, {}

        self.proxy_json = mock.patch.object(
            self.state, "proxy_json", side_effect=proxy_json)
        self.proxy_json.start()

    def tearDown(self):
        self.proxy_json.stop()
        self.temporary.cleanup()

    @staticmethod
    def pin_verifier(pin="2468"):
        salt = b"0123456789abcdef"
        return {
            "version": 1,
            "algorithm": "pbkdf2-sha256",
            "iterations": 100_000,
            "salt": base64.b64encode(salt).decode("ascii"),
            "digest": base64.b64encode(
                hashlib.pbkdf2_hmac("sha256", pin.encode("ascii"), salt, 100_000)
            ).decode("ascii"),
        }

    def test_snapshot_and_writer_request_are_parent_scoped(self):
        snapshot = self.state.child_game_sharing_snapshot(
            self.client, "parent", "steam:123", self.session_id,
            self.authorization_id)
        self.assertEqual("parent/steam:123", snapshot["game_key"])
        self.assertEqual(0, snapshot["revision"])
        self.assertEqual({"kid", "other-kid"},
                         {child["id"] for child in snapshot["children"]})
        self.assertTrue(next(child for child in snapshot["children"]
                             if child["id"] == "kid")["granted"])
        self.assertFalse(next(child for child in snapshot["children"]
                              if child["id"] == "other-kid")["enabled"])

        request = self.state.prepare_child_game_sharing(
            self.client, "parent", "steam:123", ["other-kid"], 0,
            "share-1", self.session_id, self.authorization_id)
        self.assertEqual({"other-kid"}, set(request["child_profile_ids"]))
        self.assertEqual("parent/steam:123", request["game_key"])
        self.assertEqual("parent", next(call[3] for call in self.proxy_json_calls
                                         if call[1] == "/library/resolve"))

        with self.assertRaises(PermissionError):
            self.state.prepare_child_game_sharing(
                self.client, "parent", "steam:123", ["foreign-kid"], 0,
                "share-foreign", self.session_id, self.authorization_id)
        self.assertEqual(["parent/steam:123"],
                         self.state.config["profiles"]["kid"]["allowed_game_keys"])

    def test_child_library_posts_allowlist_and_binds_cursor_to_policy(self):
        status, result = self.state.child_playnite_library(
            self.client, "kid", "", 50)
        self.assertEqual(200, status)
        self.assertEqual("parent", self.proxy_json_calls[-1][3])
        self.assertEqual(["steam:123"],
                         self.proxy_json_calls[-1][2]["allowed_game_ids"])
        self.assertEqual("child:0:catalog-1:10",
                         result["library"]["next_cursor"])

        status, result = self.state.child_playnite_library(
            self.client, "kid", result["library"]["next_cursor"], 50)
        self.assertEqual(200, status)
        self.assertEqual("catalog-1",
                         self.proxy_json_calls[-1][2]["catalog_revision"])
        self.state.config["profiles"]["parent"][
            wakeplay_gateway.CHILD_PARENT_POLICY_REVISION_FIELD] = 1
        status, result = self.state.child_playnite_library(
            self.client, "kid", "child:0:catalog-1:10", 50)
        self.assertEqual(409, status)
        self.assertEqual("child_policy_revision_stale", result["error"])

    def test_unshared_projection_is_denied_and_state_event_data_is_filtered(self):
        self.state.proxy_bytes = mock.Mock(return_value=(200, b"image", "image/png"))
        self.state.child_playnite_artwork(
            self.client, "kid", "steam:123", "cover")
        self.state.proxy_bytes.assert_called_once()
        self.state.proxy_bytes.reset_mock()
        with self.assertRaises(PermissionError):
            self.state.child_playnite_artwork(
                self.client, "kid", "steam:999", "cover")
        self.state.proxy_bytes.assert_not_called()

        self.state.proxy = mock.Mock(return_value=(True, {
            "state": "running", "id": "steam:999", "title": "Parent secret",
            "processPath": "C:\\secret\\game.exe",
        }))
        status, current = self.state.child_playnite_state(
            self.client, "kid", "current")
        self.assertEqual(200, status)
        self.assertEqual("idle", current["current"]["state"])
        self.assertNotIn("steam:999", json.dumps(current))
        self.state.proxy.assert_called_once()

        self.state.proxy.reset_mock()
        self.state.proxy.return_value = (True, {
            "events": [
                {"sequence": 1, "event": "game-running",
                 "payload": {"id": "steam:123", "name": "Allowed"}},
                {"sequence": 2, "event": "game-running",
                 "payload": {"id": "steam:999", "name": "Parent secret"}},
                {"sequence": 3, "event": "library-updated",
                 "payload": {"revision": "catalog-1", "count": 999}},
                {"sequence": 4, "event": "bridge-connected",
                 "payload": {"secret": "hidden"}},
                {"sequence": 5, "event": "playnite-status",
                 "payload": {"name": "Parent secret"}},
            ]
        })
        status, result = self.state.child_playnite_events(
            self.client, "kid", 0, "transition-1")
        self.assertEqual(200, status)
        events = result["events"]["events"]
        self.assertEqual(["game-running", "library-updated", "bridge-connected"],
                         [event["event"] for event in events])
        self.assertEqual({"revision": "catalog-1"}, events[1]["payload"])
        self.assertEqual({}, events[2]["payload"])

    def test_child_start_keeps_provider_rejection(self):
        status, result = self.state.child_playnite_action(
            self.client, "kid", "game/start", {"game_id": "steam:123"})
        self.assertEqual(200, status)
        self.assertFalse(result["ok"])
        self.assertEqual("outside_schedule", result["result"]["reason"])

    def test_child_library_refresh_uses_parent_execution_profile(self):
        status, result = self.state.child_playnite_action(
            self.client, "kid", "library/refresh", {})
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        refresh = [call for call in self.proxy_json_calls
                   if call[1] == "/library/refresh"]
        self.assertEqual(1, len(refresh))
        self.assertEqual("parent", refresh[0][3])


if __name__ == "__main__":
    unittest.main()
