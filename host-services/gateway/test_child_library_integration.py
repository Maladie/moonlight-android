import json
import sys
import tempfile
import time
import unittest
from collections import deque
from email.message import Message
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import wakeplay_gateway
from wakeplay_gateway import GatewayState, sha256_text


BRIDGE_DIR = Path(__file__).resolve().parents[1] / "bridges" / "playnite"
if str(BRIDGE_DIR) not in sys.path:
    sys.path.insert(0, str(BRIDGE_DIR))

from GameProviderBridge import (  # noqa: E402
    BridgeState,
    GameOperationsService,
    GameProviderHandler,
    OperationJournal,
    SteamProvider,
)


RAW_ALLOWED = "840317c9-b9a4-4f72-be8e-807414e36a9b"
RAW_SECOND = "9d7a2e4f-3c61-4bb5-9a08-2b6f1d7e4c30"
RAW_FOREIGN = "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f"


def canonical(raw_id):
    return "playnite:" + raw_id


def game_key(raw_id):
    return "living-room/" + canonical(raw_id)


class BridgeFixture:
    """Inject the real BridgeState HTTP handler without opening a socket."""

    def __init__(self, root):
        service = GameOperationsService(
            OperationJournal(None), steam=SteamProvider(roots=[]))
        self.state = BridgeState(
            cache_path=Path(root) / "library-cache.json",
            game_operations=service,
            profile_id="living-room")
        self.calls = []

    def set_library(self, records, revision="rev-1"):
        with self.state.lock:
            self.state.library = {record["id"]: dict(record) for record in records}
            self.state.library_revision = revision

    def _invoke(self, method, path, body=None):
        handler = object.__new__(GameProviderHandler)
        handler.server = SimpleNamespace(state=self.state)
        handler.path = path
        handler.headers = Message()
        handler.read_json = mock.Mock(return_value=dict(body or {}))
        responses = []
        handler.send_json = lambda status, value: responses.append(
            ("json", int(status), value))
        handler.send_binary = lambda status, value, content_type: responses.append(
            ("binary", int(status), value, content_type))
        handler._begin_diagnostics = mock.Mock()
        handler._finish_diagnostics = mock.Mock()
        if method == "POST":
            handler.do_POST()
        else:
            handler.do_GET()
        if len(responses) != 1:
            raise AssertionError(f"Bridge handler returned {len(responses)} responses")
        return responses[0]

    def proxy_json(self, name, path, body, timeout=8.0, profile_id=None):
        self.calls.append(("json", name, path, dict(body), profile_id))
        response = self._invoke("POST", path, body)
        return response[1] < 400, response[2]

    def proxy(self, name, path, timeout=2.5, profile_id=None):
        self.calls.append(("get", name, path, profile_id))
        response = self._invoke("GET", path)
        return response[1] < 400, response[2]

    def proxy_bytes(self, name, path, timeout=8.0, profile_id=None):
        self.calls.append(("bytes", name, path, profile_id))
        response = self._invoke("GET", path)
        if response[0] == "binary":
            return response[1], response[2], response[3]
        return response[1], b"", "application/octet-stream"


class ChildLibraryIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.config_path = self.root / "gateway.json"
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {
                "living-room": {
                    "id": "living-room",
                    "kind": "standard",
                    "name": "Living room",
                    "enabled": True,
                    "children_policy_revision": 0,
                },
                "kid": {
                    "id": "kid",
                    "kind": "child",
                    "name": "Kid",
                    "parent_profile_id": "living-room",
                    "enabled": True,
                    "policy_revision": 0,
                    "allowed_game_keys": [],
                },
            },
            "clients": [{
                "id": "android-tv",
                "token_sha256": sha256_text("child-library-token"),
                "profile_grants": {"kid": ["use_profile"]},
            }],
        }), encoding="utf-8")
        self.gateway = GatewayState(self.config_path, None)
        self.client = self.gateway.config["clients"][0]
        self.bridge = BridgeFixture(self.root / "bridge")
        self.gateway.proxy = self.bridge.proxy
        self.gateway.proxy_json = self.bridge.proxy_json
        self.gateway.proxy_bytes = self.bridge.proxy_bytes
        self.child = self.gateway.config["profiles"]["kid"]

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def record(raw_id, name, **extra):
        return {
            "id": raw_id,
            "name": name,
            "provider": "playnite",
            "providerGameId": raw_id,
            "playniteGameId": raw_id,
            **extra,
        }

    def test_library_maps_raw_guid_grants_and_keeps_page_boundary(self):
        self.bridge.set_library([
            self.record(RAW_ALLOWED, "Alpha allowed"),
            self.record(RAW_SECOND, "Beta allowed"),
            self.record(RAW_FOREIGN, "Zeta foreign"),
        ])
        self.child["allowed_game_keys"] = [
            game_key(RAW_SECOND), game_key(RAW_ALLOWED)]

        status, first = self.gateway.playnite_library(
            "", 1, self.client, "kid")
        self.assertEqual(200, status)
        self.assertEqual(["Alpha allowed"], [
            game["name"] for game in first["library"]["games"]])
        self.assertEqual("child:0:rev-1:1", first["library"]["next_cursor"])
        first_request = next(call for call in self.bridge.calls
                             if call[0] == "json" and call[2] == "/library/list")
        self.assertEqual({
            "allowed_game_ids": [canonical(RAW_ALLOWED), canonical(RAW_SECOND)],
            "cursor": "0", "limit": 1,
        }, first_request[3])
        self.assertEqual("living-room", first_request[4])

        status, second = self.gateway.playnite_library(
            first["library"]["next_cursor"], 1, self.client, "kid")
        self.assertEqual(200, status)
        self.assertEqual(["Beta allowed"], [
            game["name"] for game in second["library"]["games"]])
        self.assertEqual("", second["library"]["next_cursor"])
        second_request = [call for call in self.bridge.calls
                          if call[0] == "json" and call[2] == "/library/list"][-1]
        self.assertEqual({
            "allowed_game_ids": [canonical(RAW_ALLOWED), canonical(RAW_SECOND)],
            "cursor": "1", "limit": 1, "catalog_revision": "rev-1",
        }, second_request[3])

        self.child["allowed_game_keys"] = []
        self.bridge.calls.clear()
        status, empty = self.gateway.playnite_library(
            "", 50, self.client, "kid")
        self.assertEqual(200, status)
        self.assertEqual([], empty["library"]["games"])
        self.assertEqual("", empty["library"]["next_cursor"])
        empty_request = next(call for call in self.bridge.calls
                             if call[0] == "json" and call[2] == "/library/list")
        self.assertEqual([], empty_request[3]["allowed_game_ids"])
        self.assertNotIn(RAW_FOREIGN, json.dumps(empty))

    def test_library_rejects_stale_policy_or_catalog_without_unfiltered_fallback(self):
        self.bridge.set_library([
            self.record(RAW_ALLOWED, "Alpha allowed"),
            self.record(RAW_SECOND, "Beta allowed"),
            self.record(RAW_FOREIGN, "Zeta foreign"),
        ])
        self.child["allowed_game_keys"] = [game_key(RAW_ALLOWED), game_key(RAW_SECOND)]

        status, page = self.gateway.playnite_library(
            "", 1, self.client, "kid")
        self.assertEqual(200, status)
        cursor = page["library"]["next_cursor"]

        self.bridge.calls.clear()
        self.gateway.config["profiles"]["living-room"][
            "children_policy_revision"] = 1
        status, result = self.gateway.playnite_library(
            cursor, 1, self.client, "kid")
        self.assertEqual(409, status)
        self.assertEqual("child_policy_revision_stale", result["error"])
        self.assertEqual([], self.bridge.calls)

        self.gateway.config["profiles"]["living-room"][
            "children_policy_revision"] = 0
        status, page = self.gateway.playnite_library(
            "", 1, self.client, "kid")
        self.assertEqual(200, status)
        cursor = page["library"]["next_cursor"]
        self.bridge.state.library_revision = "rev-2"
        self.bridge.calls.clear()
        status, result = self.gateway.playnite_library(
            cursor, 1, self.client, "kid")
        self.assertEqual(409, status)
        self.assertEqual("child_catalog_revision_stale", result["error"])
        self.assertEqual(1, len(self.bridge.calls))
        self.assertEqual("/library/list", self.bridge.calls[0][2])
        self.assertEqual("rev-1", self.bridge.calls[0][3]["catalog_revision"])
        self.assertEqual([
            canonical(RAW_ALLOWED), canonical(RAW_SECOND)],
            self.bridge.calls[0][3]["allowed_game_ids"])
        self.assertNotIn("Zeta foreign", json.dumps(result))

    def test_current_and_events_preserve_payload_shape_and_filter_foreign_games(self):
        self.bridge.set_library([
            self.record(RAW_ALLOWED, "Alpha allowed"),
            self.record(RAW_FOREIGN, "Zeta foreign"),
        ])
        self.child["allowed_game_keys"] = [game_key(RAW_ALLOWED)]
        with self.bridge.state.lock:
            self.bridge.state.current = {
                "id": RAW_ALLOWED, "state": "running"}
            self.bridge.state._running_games = [
                {"game_id": RAW_ALLOWED, "process_id": 10,
                 "process_token": "a" * 64},
                {"game_id": RAW_FOREIGN, "process_id": 11,
                 "process_token": "b" * 64},
            ]
            self.bridge.state._running_scan_revision = "rev-1"
            self.bridge.state._running_scan_at = time.monotonic()
            self.bridge.state.events = deque([
                {"sequence": 1, "event": "game-running",
                 "payload": {"id": RAW_ALLOWED, "state": "running"}},
                {"sequence": 2, "event": "game-running",
                 "payload": {"id": RAW_FOREIGN, "state": "running"}},
                {"sequence": 3, "event": "bridge-connected",
                 "payload": {"connector": "private-detail"}},
                {"sequence": 4, "event": "library-updated",
                 "payload": {"revision": "rev-1", "count": 2}},
            ])

        status, current = self.gateway.playnite_state(
            "current", self.client, "kid")
        self.assertEqual(200, status)
        self.assertEqual(RAW_ALLOWED, current["current"]["id"])
        self.assertEqual([RAW_ALLOWED], [
            game["game_id"] for game in current["current"]["running_games"]])
        self.assertNotIn(RAW_FOREIGN, json.dumps(current))

        status, events_result = self.gateway.playnite_events(
            0, "events-1", self.client, "kid")
        self.assertEqual(200, status)
        events = events_result["events"]["events"]
        self.assertEqual(3, len(events))
        self.assertTrue(all(isinstance(event.get("payload"), dict)
                            for event in events))
        self.assertIn(RAW_ALLOWED, json.dumps(events))
        self.assertNotIn(RAW_FOREIGN, json.dumps(events))
        self.assertEqual({}, next(event["payload"] for event in events
                                   if event["event"] == "bridge-connected"))
        self.assertEqual({"revision": "rev-1"}, next(
            event["payload"] for event in events
            if event["event"] == "library-updated"))

    def test_artwork_and_start_resolve_raw_guid_to_canonical_identity(self):
        artwork_path = self.root / "allowed-cover.png"
        artwork_path.write_bytes(b"\x89PNG\r\n\x1a\nchild-cover")
        self.bridge.set_library([
            self.record(RAW_ALLOWED, "Alpha allowed", boxArtPath=str(artwork_path)),
            self.record(RAW_FOREIGN, "Zeta foreign"),
        ])
        self.child["allowed_game_keys"] = [game_key(RAW_ALLOWED)]

        status, body, content_type = self.gateway.playnite_artwork(
            RAW_ALLOWED.upper(), "cover", self.client, "kid")
        self.assertEqual(200, status)
        self.assertEqual(b"\x89PNG\r\n\x1a\nchild-cover", body)
        self.assertEqual("image/png", content_type)
        artwork_call = [call for call in self.bridge.calls
                        if call[0] == "bytes"][-1]
        self.assertIn("game_id=playnite%3A" + RAW_ALLOWED, artwork_call[2])

        with self.assertRaises(PermissionError):
            self.gateway.playnite_artwork(
                RAW_FOREIGN, "cover", self.client, "kid")
        self.assertEqual(1, len([call for call in self.bridge.calls
                                 if call[0] == "bytes"]))

        self.bridge.state.start_game = mock.Mock(return_value={
            "accepted": True, "command": "launch"})
        status, result = self.gateway.playnite_action(
            "game/start", {"game_id": RAW_ALLOWED.upper()},
            self.client, "kid")
        self.assertEqual(200, status)
        self.assertTrue(result["result"]["accepted"])
        self.bridge.state.start_game.assert_called_once_with(canonical(RAW_ALLOWED))
        start_request = [call for call in self.bridge.calls
                         if call[0] == "json" and call[2] == "/game/start"][-1]
        self.assertEqual({"game_id": canonical(RAW_ALLOWED)}, start_request[3])

        with self.assertRaises(PermissionError):
            self.gateway.playnite_action(
                "game/start", {"game_id": RAW_FOREIGN},
                self.client, "kid")
        self.bridge.state.start_game.assert_called_once()


if __name__ == "__main__":
    unittest.main()
