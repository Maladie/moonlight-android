import json
import tempfile
import unittest
from datetime import datetime, timezone
from email.message import Message
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import wakeplay_gateway
from wakeplay_gateway import GatewayHandler, GatewayState, sha256_text


SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef"
VIBE_UUID = SESSION_ID
PROCESS_TOKEN = "a" * 64


class ChildSessionsHttpTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temporary.name) / "gateway.json"
        weekdays = {
            day: {"enabled": True, "start_minute": 0,
                  "end_minute": 1440, "daily_limit_seconds": 3600}
            for day in wakeplay_gateway.CHILD_PROFILE_WEEKDAYS
        }
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem", "private_key": "key.pem",
            "game_provider_bridge": "http://127.0.0.1:8780",
            "profiles": {
                "parent": {
                    "id": "parent", "kind": "standard", "name": "Parent",
                    "enabled": True,
                    "windows_account_sid": "S-1-5-21-1-2-3-1001",
                    "owner_sid": "S-1-5-21-1-2-3-1001",
                    "windows_account_name": "TESTPC\\Parent",
                    "owner": "TESTPC\\Parent",
                    "account_mapping_status": "resolved",
                },
                "kid": {
                    "id": "kid", "kind": "child", "name": "Kid",
                    "enabled": True, "parent_profile_id": "parent",
                    "allowed_game_keys": ["parent/steam:123"],
                    "policy_revision": 1,
                    "schedule": {"timezone": "UTC", "weekdays": weekdays},
                },
            },
            "clients": [{
                "id": "tv-1", "token_sha256": sha256_text("child-token"),
                "profile_grants": {
                    "parent": ["use_profile"], "kid": ["use_profile"]},
                "vibepollo_client_uuids": {"parent": VIBE_UUID},
            }],
        }), encoding="utf-8")
        self.state = GatewayState(self.config_path, None)
        self.state.child_profiles_api_enabled = True
        self.client = self.state.client_for_token("child-token")
        self.clock = [0.0, datetime(2026, 1, 3, 12, 0, tzinfo=timezone.utc)]
        self.state.child_time_clock = lambda: tuple(self.clock)
        self.current = {"id": "", "state": "idle", "running_games": []}
        self.bridge_calls = []

        def proxy(name, path, timeout=2.5, profile_id=None):
            del timeout
            if name == "game_provider" and path == "/game/current":
                return True, {**self.current,
                              "running_games": [dict(item) for item in
                                                 self.current.get("running_games", [])]}
            if path == "/health":
                return True, {"connector_connected": True}
            return True, {}

        def proxy_json(name, path, body, timeout=8.0, profile_id=None):
            del timeout, profile_id
            self.bridge_calls.append((name, path, dict(body)))
            if name != "game_provider":
                return True, {}
            if path == "/library/resolve":
                return True, {"game_id": "steam:123",
                              "canonical_game_id": "steam:123",
                              "revision": "catalog-1"}
            if path == "/library/list":
                return True, {"games": [{"id": "steam:123", "name": "Allowed"}],
                              "next_cursor": "", "revision": "catalog-1"}
            if path == "/child/session":
                action = body["action"]
                if action == "end":
                    self.current = {"id": "", "state": "idle", "running_games": []}
                    return True, {"accepted": True, "state": "stopped",
                                  "session_id": body["session_id"],
                                  "cleanup_required": False}
                return True, {"accepted": True, "state": action,
                              "session_id": body["session_id"]}
            if path == "/game/start":
                self.current = {
                    "id": "steam:123", "state": "running",
                    "running_games": [{"game_id": "steam:123",
                                        "process_id": 42,
                                        "process_token": PROCESS_TOKEN}],
                }
                return True, {"accepted": True, "state": "starting"}
            return True, {}

        self.state.proxy = proxy
        self.state.proxy_json = proxy_json
        self.state.login_broker.profile_state = mock.Mock(return_value={
            "success": True, "state": "active", "fields": {3: "ready", 4: "none"}})
        self.state.login_broker.capability = mock.Mock(return_value={
            "success": True, "fields": {3: "1"}})

    def tearDown(self):
        self.temporary.cleanup()

    def handler(self, path, body=None, profile="parent", capability=False,
                profile_session=True):
        handler = object.__new__(GatewayHandler)
        handler.path = path
        handler.headers = Message()
        handler.headers["Authorization"] = "Bearer child-token"
        handler.headers["X-WakePlay-Profile"] = profile
        if capability:
            handler.headers["X-MoonWaker-Capabilities"] = \
                wakeplay_gateway.CHILD_PROFILES_CAPABILITY
        if profile_session:
            handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = SESSION_ID
        handler.headers["X-Request-Id"] = "http-request"
        handler.server = SimpleNamespace(state=self.state)
        handler.client_address = ("192.0.2.1", 12345)
        responses = []
        handler.send_json = lambda status, value: responses.append((int(status), value))
        handler.read_json = lambda: {} if body is None else dict(body)
        return handler, responses

    def post(self, path, body, **kwargs):
        handler, responses = self.handler(path, body, **kwargs)
        handler.do_POST()
        self.assertEqual(1, len(responses))
        return responses[0]

    def get(self, path, **kwargs):
        handler, responses = self.handler(path, **kwargs)
        handler.do_GET()
        self.assertEqual(1, len(responses))
        return responses[0]

    def test_old_client_does_not_see_child_and_capability_is_fail_closed(self):
        status, result = self.get("/api/v1/profiles", profile="parent")
        self.assertEqual(200, status)
        self.assertNotIn("kid", [item["id"] for item in result["profiles"]])

        status, result = self.get("/api/v1/game/current", profile="kid")
        self.assertEqual(403, status)
        self.assertEqual("child_profiles_capability_required", result["error"])

    def test_negotiated_profiles_project_child_without_parent_identity(self):
        status, result = self.get("/api/v1/profiles", profile="parent",
                                  capability=True)
        self.assertEqual(200, status)
        child = next(item for item in result["profiles"] if item["id"] == "kid")
        self.assertEqual("child", child["kind"])
        self.assertEqual("parent", child["parent_profile_id"])
        self.assertEqual("parent", child["execution_profile_id"])
        self.assertFalse(child["permissions"]["manage_children"])
        self.assertNotIn("discord_bridge_online", child)
        self.assertNotIn("virtualhere_available", child)

    def test_child_library_uses_child_projection_before_bridge_paging(self):
        status, result = self.get("/api/v1/library?limit=50", profile="kid",
                                  capability=True)
        self.assertEqual(200, status)
        library_call = next(call for call in self.bridge_calls
                             if call[1] == "/library/list")
        self.assertEqual(["steam:123"], library_call[2]["allowed_game_ids"])
        self.assertEqual("steam:123", result["library"]["games"][0]["id"])

    def test_session_route_requires_profile_session_and_exact_body(self):
        handler, responses = self.handler(
            "/api/v1/child/session/start",
            {"game_id": "steam:123", "session_id": SESSION_ID},
            profile="kid", capability=True, profile_session=False)
        handler.do_POST()
        self.assertEqual(400, responses[0][0])
        self.assertEqual(0, len(self.bridge_calls))

        status, result = self.post(
            "/api/v1/child/session/start",
            {"game_id": "steam:123", "session_id": SESSION_ID,
             "request_id": "start-1", "unexpected": True},
            profile="kid", capability=True)
        self.assertEqual(400, status)
        self.assertIn("Invalid child session request", result["error"])

    def test_legacy_child_start_needs_an_active_time_session(self):
        status, result = self.post(
            "/api/v1/game/start", {"game_id": "steam:123"},
            profile="kid", capability=True)
        self.assertEqual(403, status)
        self.assertEqual("child_session_required", result["error"])

    def test_child_library_refresh_uses_child_authorization(self):
        status, result = self.post(
            "/api/v1/library/refresh", {}, profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual("library/refresh", result["action"])
        self.assertEqual(1, len([call for call in self.bridge_calls
                                 if call[1] == "/library/refresh"]))

    def test_public_start_and_end_after_revoke_use_the_bound_session(self):
        status, result = self.post(
            "/api/v1/child/session/start",
            {"game_id": "steam:123", "session_id": SESSION_ID,
             "request_id": "start-1"}, profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual("running", result["phase"])

        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        registry["profiles"]["kid"]["enabled"] = False
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")
        status, result = self.post(
            "/api/v1/child/session/end",
            {"session_id": SESSION_ID}, profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual("ended", result["phase"])
        self.assertEqual("kid", result["actor_profile_id"])
        self.assertEqual("parent", result["execution_profile_id"])
        self.assertIsNone(self.state.child_time_session)

    def test_child_uses_common_focus_and_stop_without_ending_transport(self):
        self.post("/api/v1/child/session/start",
                  {"game_id": "steam:123", "session_id": SESSION_ID, "request_id": "start-actions"},
                  profile="kid", capability=True)
        for action, body in [("focus", {}), ("stop", {"game_id": "steam:123"})]:
            handler, responses = self.handler("/api/v1/game/" + action, body,
                                              profile="kid", capability=True)
            handler.headers.replace_header("X-Request-Id", "game-" + action)
            handler.do_POST()
            self.assertEqual(200, responses[0][0], responses)
            self.assertTrue(responses[0][1]["ok"])
        actions = [path for _, path, _ in self.bridge_calls]
        self.assertIn("/game/focus", actions)
        self.assertIn("/game/stop", actions)
        self.assertNotIn("/action/disconnect", actions)
        status, _ = self.post("/api/v1/game/stop", {"game_id": "steam:999"},
                              profile="kid", capability=True)
        self.assertEqual(403, status)

    def test_child_only_grant_can_list_start_delete_end_and_replay(self):
        # A child device must not need the parent's use_profile grant to run
        # against the host-resolved parent execution identity.
        self.client["profile_grants"] = {"kid": ["use_profile"]}
        status, result = self.get(
            "/api/v1/profiles", profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual("kid", next(
            item["id"] for item in result["profiles"] if item["id"] == "kid"))

        status, result = self.post(
            "/api/v1/child/session/start",
            {"game_id": "steam:123", "session_id": SESSION_ID,
             "request_id": "child-only-start"},
            profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual("parent", result["execution_profile_id"])
        self.assertEqual("running", result["phase"])

        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        del registry["profiles"]["kid"]
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")
        status, result = self.post(
            "/api/v1/child/session/end", {"session_id": SESSION_ID},
            profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual("ended", result["phase"])

        status, result = self.post(
            "/api/v1/child/session/end", {"session_id": SESSION_ID},
            profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual("ended", result["phase"])

    def test_completed_status_confirms_cleanup_and_preserves_actor_binding(self):
        self.post("/api/v1/child/session/start",
                  {"game_id": "steam:123", "session_id": SESSION_ID,
                   "request_id": "start-status"}, profile="kid", capability=True)
        self.post("/api/v1/child/session/end", {"session_id": SESSION_ID},
                  profile="kid", capability=True)
        status, result = self.post("/api/v1/child/session/status",
                                   {"session_id": SESSION_ID},
                                   profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual("ended", result["phase"])
        self.assertFalse(result["cleanup_required"])
        self.assertEqual(SESSION_ID, result["session_id"])
        status, _result = self.post("/api/v1/child/session/status",
                                    {"session_id": SESSION_ID},
                                    profile="parent", capability=True)
        self.assertEqual(403, status)

    def test_child_sleep_requires_grant_and_capability_and_is_idempotent(self):
        with mock.patch.object(self.state, "sleep_host", return_value=(200, {"ok": True})) as sleep:
            status, _ = self.post("/api/v1/system/sleep", {}, profile="kid", capability=False)
            self.assertEqual(403, status)
            sleep.assert_not_called()
            for _ in range(2):
                status, _ = self.post("/api/v1/system/sleep", {}, profile="kid", capability=True)
                self.assertEqual(200, status)
            sleep.assert_called_once()
            self.client["profile_grants"]["kid"] = []
            status, _ = self.post("/api/v1/system/sleep", {}, profile="kid", capability=True)
            self.assertEqual(403, status)
            sleep.assert_called_once()

    def test_child_destructive_routes_are_rejected(self):
        for path in ("/api/v1/game/install", "/api/v1/game/uninstall",
                     "/api/v1/session/hard-reset", "/api/v1/discord/home",
                     "/api/v1/virtualhere/action"):
            with self.subTest(path=path):
                status, result = self.post(path, {}, profile="kid", capability=True)
                self.assertEqual(403, status)
                self.assertEqual("child_action_not_allowed", result["error"])


if __name__ == "__main__":
    unittest.main()
