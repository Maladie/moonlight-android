import json
import tempfile
import unittest
from control_snapshot_fixture import control_evidence
from provider_target_fixture import target_aware
from datetime import datetime, timezone
from email.message import Message
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import wakeplay_gateway
from wakeplay_gateway import GatewayHandler, GatewayState, sha256_text
from control_snapshot_fixture import route_vibepollo_through_proxy

route_vibepollo_through_proxy(wakeplay_gateway.GatewayState)


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
            }, {
                "id": "tv-2", "token_sha256": sha256_text("other-token"),
                "profile_grants": {"kid": ["use_profile"]},
                "vibepollo_client_uuids": {"parent": VIBE_UUID},
            }],
        }), encoding="utf-8")
        self.state = GatewayState(self.config_path)
        self.state.child_profiles_api_enabled = True
        self.client = self.state.client_for_token("child-token")
        self.clock = [0.0, datetime(2026, 1, 3, 12, 0, tzinfo=timezone.utc)]
        self.state.child_time_clock = lambda: tuple(self.clock)
        self.current = {"id": "", "state": "idle", "running_games": []}
        self.streamer_active = False
        self.bridge_calls = []
        self.observation_calls = []

        def proxy(name, path, timeout=2.5, profile_id=None):
            del timeout
            if name == "game_provider" and path == "/observation":
                self.observation_calls.append((path, profile_id))
                game_id = self.current.get("id", "")
                running = [dict(item) for item in self.current.get("running_games", [])]
                exact = running[0] if game_id and running else {}
                return True, {
                    "schema_version": 2,
                    "producer": {"boot_id": "producer-boot", "revision": 7},
                    "observed_at_unix_ms": 1_700_000_000_000,
                    "fresh_for_ms": 5_000,
                    "state_episode": "producer-boot:1",
                    "state_since_unix_ms": 1_700_000_000_000,
                    "provider": {"state": self.current.get("state", "idle"),
                                 "connector_evidence": "VERIFIED",
                                 "reason": "none"},
                    "game": {"evidence": "VERIFIED" if game_id else "ABSENT",
                             "id": game_id,
                             "state": self.current.get("state", "idle")},
                    "process": {"evidence": "VERIFIED" if exact else "ABSENT",
                                "process_id": exact.get("process_id", 0),
                                "process_token": exact.get("process_token", ""),
                                "running_games": running,
                                "running_scan_status": "complete"},
                    "window": {"evidence": "ABSENT", "ready": False,
                               "target_kind": "playnite", "stable_samples": 0,
                               "reason": "no_running_game"},
                    "capabilities": {"host_guide_allowed": True,
                                     "requires_connector": False,
                                     "start_before_stream": False},
                }
            if path == "/health":
                return True, {"connector_connected": True}
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {**control_evidence(int(self.streamer_active), self.streamer_active),
                              "clients": [{"uuid": VIBE_UUID,
                                           "connected": self.streamer_active}],
                              "apps": [{"app_id": 7,
                                        "running": self.streamer_active}]}
            if name == "vibepollo" and path == "/action/close-app":
                self.streamer_active = False
                return True, {"ok": True}
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
            if path == "/game/start":
                self.streamer_active = True
                self.current = {
                    "id": "steam:123", "state": "running",
                    "running_games": [{"game_id": "steam:123",
                                        "process_id": 42,
                                        "process_token": PROCESS_TOKEN}],
                }
                return True, {"accepted": True, "state": "starting"}
            if path == "/game/stop-verified":
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True, "state": "stopped"}
            if path == "/session/hard-reset":
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True, "stopped_count": 1}
            return True, {}

        self.state.proxy = target_aware(proxy)
        self.state.proxy_json = proxy_json
        self.state.login_broker.profile_state = mock.Mock(return_value={
            "success": True, "state": "active", "fields": {3: "ready", 4: "none"}})
        self.state.login_broker.capability = mock.Mock(return_value={
            "success": True, "fields": {3: "1"}})

    def tearDown(self):
        self.temporary.cleanup()

    def handler(self, path, body=None, profile="parent", capability=False,
                profile_session=True, request_id="http-request",
                token="child-token"):
        handler = object.__new__(GatewayHandler)
        handler.path = path
        handler.headers = Message()
        handler.headers["Authorization"] = "Bearer " + token
        handler.headers["X-WakePlay-Profile"] = profile
        if capability:
            handler.headers["X-MoonWaker-Capabilities"] = \
                wakeplay_gateway.CHILD_PROFILES_CAPABILITY
        if profile_session:
            handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = SESSION_ID
        handler.headers["X-Request-Id"] = request_id
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

    def test_parent_and_child_use_the_same_atomic_observation_without_health(self):
        for profile, capability, expected_execution in (
                ("parent", False, "parent"), ("kid", True, "parent")):
            status, body = self.get(
                "/api/v1/session/observation?request_ordinal=9&transition_id=t-9",
                profile=profile, capability=capability)
            self.assertEqual(200, status)
            self.assertTrue(body["ok"])
            self.assertEqual(profile, body["scope"]["actor_profile_id"])
            self.assertEqual(expected_execution,
                             body["scope"]["execution_profile_id"])
            self.assertEqual(9, body["correlation"]["request_ordinal"])
            self.assertEqual("t-9", body["correlation"]["caller_transition_id"])
        self.assertEqual([("/observation", "parent"),
                          ("/observation", "parent")], self.observation_calls)

    def test_control_change_across_provider_proxy_fails_closed(self):
        original = self.state.proxy

        def racing_proxy(name, path, timeout=2.5, profile_id=None):
            result = original(name, path, timeout, profile_id)
            if name == "game_provider" and path == "/observation":
                with self.state.lock:
                    self.state.control_state["generation"] += 1
            return result

        self.state.proxy = racing_proxy
        status, body = self.get(
            "/api/v1/session/observation?request_ordinal=10&transition_id=t-10",
            profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertFalse(body["ok"])
        self.assertEqual("control_correlation_changed", body["reason"])
        self.assertEqual("UNAVAILABLE", body["game"]["evidence"])

    def establish_child_transport_and_start_game(self):
        session = self.state.child_time_session
        self.state.authorize_child_profile(self.client, "kid")
        operation_id = "child-http-transport"
        status, permit = self.state.control_operation_acquire(self.client, {
            "operation_id": operation_id, "intent": "launch",
            "stream_session_id": SESSION_ID, "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": SESSION_ID,
        }, operation_id)
        self.assertEqual(200, status)
        self.state.control_operation_complete(self.client, {
            "operation_id": operation_id, "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
        }, operation_id)
        handler, responses = self.handler("/api/v1/game/start",
                                          {"game_id": "steam:123"},
                                          profile="kid", capability=True)
        handler.headers.replace_header("X-Request-Id", "game-start-setup")
        handler.do_POST()
        self.assertEqual(200, responses[0][0])
        status, _ = self.state.child_time_session_heartbeat(
            self.client, "kid", SESSION_ID)
        self.assertEqual(200, status)

    def test_old_client_does_not_see_child_and_capability_is_fail_closed(self):
        status, result = self.get("/api/v1/profiles", profile="parent")
        self.assertEqual(200, status)
        self.assertNotIn("kid", [item["id"] for item in result["profiles"]])

        status, result = self.get(
            "/api/v1/session/observation?request_ordinal=1", profile="kid")
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

    def test_child_capabilities_negotiate_control_without_parent_health_or_effects(self):
        with mock.patch.object(self.state, "proxy") as proxy, \
                mock.patch.object(self.state, "proxy_json") as proxy_json:
            for _ in range(3):
                status, result = self.get("/api/v1/capabilities", profile="kid",
                                          capability=True)
                self.assertEqual(200, status)
                self.assertEqual({"gateway", "capabilities"}, set(result))
                self.assertEqual({"online": True, "api_version": 1}, result["gateway"])
                self.assertEqual({"child_profiles_v1", "control_operations_v2"},
                                 set(result["capabilities"]))
                self.assertTrue(result["capabilities"]["child_profiles_v1"]["available"])
                self.assertTrue(result["capabilities"]["control_operations_v2"]["available"])
            proxy.assert_not_called()
            proxy_json.assert_not_called()
            self.assertIsNone(self.state.child_time_session)
            self.assertFalse(self.state.control_state.get("stream"))
            status, result = self.get("/api/v1/game-provider/health", profile="kid",
                                      capability=True)
            self.assertEqual(404, status)
            proxy.assert_not_called()
            proxy_json.assert_not_called()

    def test_child_uses_the_common_bounded_network_calibration_download(self):
        handler, responses = self.handler(
            "/api/v1/diagnostics/network/download?size=1024",
            profile="kid", capability=True)
        calls = []
        handler.network_download = lambda size, profile, client_id: calls.append(
            (size, profile, client_id))

        handler.do_GET()

        self.assertEqual([], responses)
        self.assertEqual([("1024", "host", "tv-1")], calls)

    def test_child_capabilities_preserve_negotiation_and_authorization_gates(self):
        status, result = self.get("/api/v1/capabilities", profile="kid")
        self.assertEqual(403, status)
        self.assertEqual("child_profiles_capability_required", result["error"])

        self.state.child_profiles_api_enabled = False
        status, result = self.get("/api/v1/capabilities", profile="kid", capability=True)
        self.assertEqual(403, status)
        self.assertEqual("child_profiles_not_enforced", result["error"])
        self.state.child_profiles_api_enabled = True

        self.client["profile_grants"]["kid"] = []
        status, _ = self.get("/api/v1/capabilities", profile="kid", capability=True)
        self.assertEqual(403, status)

        handler, responses = self.handler("/api/v1/capabilities", profile="kid",
                                           capability=True)
        handler.headers.replace_header("Authorization", "Bearer invalid-token")
        handler.do_GET()
        self.assertEqual(401, responses[0][0])
        self.assertEqual([], self.bridge_calls)

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
        status, result = self.get("/api/v1/capabilities", profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertTrue(result["capabilities"]["control_operations_v2"]["available"])
        status, result = self.post("/api/v1/control/operation/acquire", {
            "operation_id": "http-request", "intent": "launch",
            "stream_session_id": SESSION_ID, "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": SESSION_ID,
        }, profile="kid", capability=True)
        self.assertEqual(409, status)
        self.assertEqual("child_session_unmanaged", result["reason"])
        status, result = self.post(
            "/api/v1/game/start", {"game_id": "steam:123"},
            profile="kid", capability=True)
        self.assertEqual(403, status)
        self.assertEqual("child_session_required", result["error"])
        self.assertIsNone(self.state.child_time_session)
        self.assertFalse(self.state.control_state.get("stream"))
        self.assertEqual([], self.bridge_calls)

    def test_definitive_control_failure_is_accepted_over_http_and_next_play_can_acquire(self):
        status, permit = self.post("/api/v1/control/operation/acquire", {
            "operation_id": "http-request", "intent": "launch",
            "stream_session_id": "stream-http-1", "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": "",
        })
        self.assertEqual(200, status)

        status, failed = self.post("/api/v1/control/operation/complete", {
            "operation_id": "http-request", "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": False,
            "effect_result": {"outcome": "definitive_failure"},
        })
        self.assertEqual(200, status)
        self.assertEqual("failed", failed["state"])
        self.assertEqual("effect_rejected", failed["reason"])

        status, next_play = self.post("/api/v1/control/operation/acquire", {
            "operation_id": "http-next-play", "intent": "launch",
            "stream_session_id": "stream-http-2", "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": "",
        }, request_id="http-next-play")
        self.assertEqual(200, status)
        self.assertEqual("permitted", next_play["state"])

    def test_client_cannot_name_an_operation_the_gateway_holds_itself(self):
        for prefix in wakeplay_gateway.HOST_OPERATION_ID_PREFIXES:
            operation_id = prefix + "0123456789abcdef0123456789abcdef"
            for action, body in (
                    ("acquire", {"operation_id": operation_id, "intent": "launch",
                                 "stream_session_id": "stream-http-1", "sunshine_app_id": 7,
                                 "expected_generation": 0, "observed_sunshine_app_id": 0,
                                 "child_session_id": ""}),
                    ("complete", {"operation_id": operation_id, "generation": 1,
                                  "actor_lease_epoch": 1, "succeeded": True}),
                    ("abandon", {"operation_id": operation_id, "child_session_id": "",
                                 "generation": 0}),
                    ("reconcile", {"operation_id": operation_id,
                                   "observed_sunshine_app_id": 0})):
                with self.subTest(prefix=prefix, action=action):
                    status, result = self.post(
                        "/api/v1/control/operation/" + action, body,
                        request_id=operation_id)
                    self.assertEqual(400, status, result)
        self.assertIsNone(self.state.control_state.get("active"))
        self.assertEqual({}, self.state.control_state.get("completed") or {})
        self.assertEqual(0, int(self.state.control_state.get("generation") or 0))

    # ---- abandon: a child hands back its own start only, and adopts nothing foreign ----

    def child_pending_launch(self, operation_id):
        if self.state.child_time_session is None:
            status, _result = self.post("/api/v1/child/session/start", {
                "game_id": "steam:123", "session_id": SESSION_ID,
                "request_id": "abandon-session-start"}, profile="kid", capability=True)
            self.assertEqual(202, status)
        status, permit = self.post("/api/v1/control/operation/acquire", {
            "operation_id": operation_id, "intent": "launch",
            "stream_session_id": "child-stream", "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": SESSION_ID,
        }, profile="kid", capability=True, request_id=operation_id)
        self.assertEqual(200, status, permit)
        return permit

    def child_abandon(self, operation_id, child_session_id=SESSION_ID, profile="kid",
                      **body):
        return self.post("/api/v1/control/operation/abandon", {
            "operation_id": operation_id, "child_session_id": child_session_id, **body},
            profile=profile, capability=profile == "kid", request_id=operation_id)

    def observe_streamer(self, apps=(), clients=()):
        original = self.state.proxy

        def proxy(name, path, timeout=2.5, profile_id=None):
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {
                    **control_evidence(len(clients), bool(apps)),
                    "clients": [{"uuid": uuid, "connected": True} for uuid in clients],
                    "apps": [{"app_id": app_id, "running": True} for app_id in apps]}
            return original(name, path, timeout, profile_id)

        self.state.proxy = proxy
        self.addCleanup(setattr, self.state, "proxy", original)

    def test_child_abandon_needs_its_own_actor_and_session_binding(self):
        permit = self.child_pending_launch("child-abandon")
        refused = (409, {"ok": False, "state": "blocked", "reason": "stale_operation"})
        self.assertEqual(refused, self.child_abandon("child-abandon", ""))
        self.assertEqual(refused, self.child_abandon(
            "child-abandon", "11111111-89ab-cdef-0123-456789abcdef"))
        # The parent whose Windows profile executes it is another actor.
        self.assertEqual(refused, self.child_abandon("child-abandon", profile="parent"))
        self.assertEqual(refused, self.child_abandon("child-abandon", "", profile="parent"))
        self.assertEqual(refused, self.child_abandon(
            "child-abandon", generation=permit["generation"] + 1))
        for invalid in ({"child_session_id": "not-a-session"}, {"unexpected": True},
                        {"generation": "1"}):
            with self.subTest(invalid=invalid):
                status, _result = self.child_abandon("child-abandon", **{
                    "child_session_id": SESSION_ID, **invalid})
                self.assertEqual(400, status)
        self.assertEqual(("child-abandon", "permitted"), (
            self.state.control_state["active"]["operation_id"],
            self.state.control_state["active"]["state"]))
        status, result = self.child_abandon(
            "child-abandon", generation=permit["generation"])
        self.assertEqual((200, True, "failed", "start_abandoned"), (
            status, result["released"], result["state"], result["reason"]))
        self.assertIsNone(self.state.control_state["active"])
        self.assertFalse(self.state.control_state.get("stream"))

    def test_child_abandon_never_adopts_a_stream_it_did_not_start(self):
        other = "89abcdef-0123-4567-89ab-cdef01234567"
        for case, apps, clients in (("foreign-app", (9,), ()),
                                    ("foreign-client", (7,), (other,)),
                                    ("foreign-client-beside-own", (7,), (VIBE_UUID, other)),
                                    ("host-app-own-client", (9,), (VIBE_UUID,))):
            with self.subTest(case=case):
                self.child_pending_launch("child-" + case)
                self.observe_streamer(apps, clients)
                status, result = self.child_abandon("child-" + case)
                self.assertEqual((200, True, "failed", "start_abandoned"), (
                    status, result["released"], result["state"], result["reason"]))
                # No record: the child owns nothing it could later close.
                self.assertFalse(self.state.control_state.get("stream"))
                status, close = self.post("/api/v1/control/operation/acquire", {
                    "operation_id": "close-" + case, "intent": "full_quit",
                    "stream_session_id": "child-stream", "sunshine_app_id": 7,
                    "expected_generation": result["generation"],
                    "observed_sunshine_app_id": apps[0], "child_session_id": "",
                }, profile="kid", capability=True, request_id="close-" + case)
                self.assertEqual((409, "managed_stream_unavailable"),
                                 (status, close["reason"]))
                self.doCleanups()

    def test_child_abandon_records_its_own_started_stream_for_its_close(self):
        permit = self.child_pending_launch("child-own")
        self.observe_streamer((7,), (VIBE_UUID,))
        status, result = self.child_abandon("child-own")
        self.assertEqual((200, False, "succeeded", "start_effect_present"), (
            status, result["released"], result["state"], result["reason"]))
        stream = self.state.control_state["stream"]
        self.assertEqual(("kid", "parent", SESSION_ID, permit["generation"]), (
            stream["actor_profile_id"], stream["execution_profile_id"],
            stream["child_session_id"], stream["generation"]))

    def test_child_abandon_after_its_session_ended_releases_or_leaves_only_a_close(self):
        for case, own_effect in (("released", False), ("own-effect", True)):
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.child_time_completed.clear()
                self.child_pending_launch("child-ended-" + case)
                self.state.child_time_session = None  # The session ended meanwhile.
                refused = (409, {"ok": False, "state": "blocked",
                                 "reason": "stale_operation"})
                self.assertEqual(refused, self.child_abandon("child-ended-" + case, ""))
                if own_effect:
                    self.observe_streamer((7,), (VIBE_UUID,))
                status, result = self.child_abandon("child-ended-" + case)
                self.assertEqual(200, status, result)
                self.assertEqual(not own_effect, result["released"])
                self.assertIsNone(self.state.control_state["active"])
                self.assertEqual(own_effect, bool(self.state.control_state.get("stream")))
                # Without a session the child may still only close: no new start is granted.
                status, play = self.post("/api/v1/control/operation/acquire", {
                    "operation_id": "play-" + case, "intent": "launch",
                    "stream_session_id": "child-stream-2", "sunshine_app_id": 7,
                    "expected_generation": 0, "observed_sunshine_app_id": 0,
                    "child_session_id": SESSION_ID,
                }, profile="kid", capability=True, request_id="play-" + case)
                self.assertEqual((409, "child_session_unmanaged"), (status, play["reason"]))
                if own_effect:
                    status, close = self.post("/api/v1/control/operation/acquire", {
                        "operation_id": "close-" + case, "intent": "full_quit",
                        "stream_session_id": "child-stream", "sunshine_app_id": 7,
                        "expected_generation": result["generation"],
                        "observed_sunshine_app_id": 7, "child_session_id": "",
                    }, profile="kid", capability=True, request_id="close-" + case)
                    self.assertEqual((200, "permitted"), (status, close["state"]), close)
                self.doCleanups()

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
        self.assertEqual(202, status)
        self.assertEqual("launch_pending", result["phase"])
        self.establish_child_transport_and_start_game()

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
        self.establish_child_transport_and_start_game()
        for action, body in [("focus", {}), ("stop-verified", {
                "game_id": "steam:123", "expected_process_token": PROCESS_TOKEN})]:
            handler, responses = self.handler("/api/v1/game/" + action, body,
                                              profile="kid", capability=True)
            handler.headers.replace_header("X-Request-Id", "game-" + action)
            handler.do_POST()
            self.assertEqual(200, responses[0][0], responses)
            self.assertTrue(responses[0][1]["ok"])
        actions = [path for _, path, _ in self.bridge_calls]
        self.assertIn("/game/focus", actions)
        self.assertIn("/game/stop-verified", actions)
        self.assertNotIn("/action/disconnect", actions)
        status, _ = self.post("/api/v1/game/stop", {"game_id": "steam:999"},
                              profile="kid", capability=True)
        self.assertEqual(403, status)
        status, _ = self.post("/api/v1/game/stop", {"game_id": "steam:123"},
                              profile="kid", capability=True)
        self.assertEqual(403, status)

    def test_observation_replaces_old_snapshot_and_health_routes(self):
        status, result = self.get(
            "/api/v1/session/observation?request_ordinal=1",
            profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual(1, result["correlation"]["request_ordinal"])

        for path in ("/api/v1/game/current", "/api/v1/window/readiness",
                     "/api/v1/game-provider/health"):
            with self.subTest(path=path):
                status, _ = self.get(path, profile="kid", capability=True)
                self.assertEqual(404, status)
            status, _ = self.get(path, profile="parent", capability=True)
            self.assertEqual(404, status)

    def test_child_cannot_inherit_parent_management_discord_or_sharing(self):
        before = list(self.bridge_calls)
        for path in ("/api/v1/discord/status", "/api/v1/discord/home"):
            with self.subTest(path=path):
                if path.endswith("/status"):
                    status, _ = self.get(path, profile="kid", capability=True)
                    self.assertEqual(404, status)
                else:
                    status, _ = self.post(path, {}, profile="kid", capability=True)
                    self.assertEqual(403, status)

        child_mutations = (
            ("/api/v1/profiles/children/create", {
                "parent_profile_id": "kid", "authorization_id": "parent-lease",
                "request_id": "child-create", "expected_revision": 1, "draft": {}}),
            ("/api/v1/profiles/children/update", {
                "parent_profile_id": "kid", "child_profile_id": "kid",
                "authorization_id": "parent-lease", "request_id": "child-update",
                "expected_revision": 1, "draft": {}}),
            ("/api/v1/profiles/children/sharing/set", {
                "parent_profile_id": "kid", "game_id": "steam:123",
                "selected_child_ids": ["kid"], "authorization_id": "parent-lease",
                "request_id": "child-share", "expected_revision": 1}),
        )
        for path, body in child_mutations:
            with self.subTest(path=path):
                status, _ = self.post(path, body, profile="kid", capability=True)
                self.assertEqual(403, status)
        self.assertEqual(before, self.bridge_calls)

    def test_child_only_grant_can_list_start_delete_end_and_replay(self):
        # A child device must not need the parent's use_profile grant to run
        # against the host-resolved parent execution identity.
        self.client["profile_grants"] = {"kid": ["use_profile"]}
        status, result = self.get(
            "/api/v1/capabilities", profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertTrue(result["capabilities"]["control_operations_v2"]["available"])
        status, result = self.get(
            "/api/v1/profiles", profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertEqual(["kid"], [item["id"] for item in result["profiles"]])
        self.assertEqual("kid", next(
            item["id"] for item in result["profiles"] if item["id"] == "kid"))

        status, result = self.post(
            "/api/v1/child/session/eligibility", {"game_id": "steam:123"},
            profile="kid", capability=True)
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual("none", result["reason"])

        status, result = self.post(
            "/api/v1/child/session/start",
            {"game_id": "steam:123", "session_id": SESSION_ID,
             "request_id": "child-only-start"},
            profile="kid", capability=True)
        self.assertEqual(202, status)
        self.assertEqual("parent", result["execution_profile_id"])
        self.assertEqual("launch_pending", result["phase"])
        self.establish_child_transport_and_start_game()

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

    def test_revoked_child_exact_hard_reset_fences_pending_launch_and_unblocks_next_play(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "pending-hard-reset-start"}, profile="kid", capability=True)
        self.state.authorize_child_profile(self.client, "kid")
        operation_id = "pending-child-launch"
        status, permit = self.state.control_operation_acquire(self.client, {
            "operation_id": operation_id, "intent": "launch",
            "stream_session_id": "pending-child-stream", "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": SESSION_ID,
        }, operation_id)
        self.assertEqual(200, status)

        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        registry["profiles"]["kid"]["enabled"] = False
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")
        status, result = self.post(
            "/api/v1/session/hard-reset",
            {"force": True, "session_id": SESSION_ID}, profile="kid",
            capability=True, request_id="pending-child-hard-reset")

        self.assertEqual(200, status, result)
        self.assertTrue(result["accepted"])
        self.assertIsNone(self.state.control_state["active"])
        self.assertIsNone(self.state.child_time_session)
        self.state.request_context.profile_id = "kid"
        self.state.request_context.actor_profile_id = "kid"
        self.state.request_context.execution_profile_id = "parent"
        status, delayed = self.state.control_operation_complete(self.client, {
            "operation_id": operation_id, "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
        }, operation_id)
        self.assertEqual(200, status)
        self.assertEqual("failed", delayed["state"])
        self.assertEqual("superseded_by_hard_reset", delayed["reason"])

        self.state.authorize_profile(self.client, "parent")
        status, next_play = self.state.control_operation_acquire(self.client, {
            "operation_id": "next-parent-launch", "intent": "launch",
            "stream_session_id": "next-parent-stream", "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": "",
        }, "next-parent-launch", 0)
        self.assertEqual(200, status, next_play)

    def test_delayed_exact_child_reset_is_noop_after_a_newer_session_started(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "old-child-start"}, profile="kid", capability=True)
        old = self.state.child_time_session
        self.state.child_time_completed[SESSION_ID] = {
            "reason": "user_terminated", "game_id": "steam:123",
            "actor_profile_id": "kid", "execution_profile_id": "parent",
            "client_id": "tv-1",
        }
        newer_id = "11234567-89ab-cdef-0123-456789abcdef"
        newer = {**old, "session_id": newer_id}
        self.state.child_time_session = newer
        self.state.child_time_usage["active_session"] = newer
        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        del registry["profiles"]["kid"]
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")
        effects_before = len([call for call in self.bridge_calls
                              if call[1] == "/session/hard-reset"])

        status, result = self.post(
            "/api/v1/session/hard-reset",
            {"force": True, "session_id": SESSION_ID}, profile="kid",
            capability=True, request_id="delayed-old-child-reset")

        self.assertEqual(200, status, result)
        self.assertEqual("child_session_already_ended", result["reason"])
        self.assertIs(newer, self.state.child_time_session)
        effects_after = len([call for call in self.bridge_calls
                             if call[1] == "/session/hard-reset"])
        self.assertEqual(effects_before, effects_after)

    def test_completed_child_session_id_cannot_be_reused(self):
        self.state.child_time_completed[SESSION_ID] = {
            "reason": "user_terminated", "game_id": "steam:123",
            "actor_profile_id": "kid", "execution_profile_id": "parent",
            "client_id": "tv-1",
        }

        status, result = self.post(
            "/api/v1/child/session/start", {
                "game_id": "steam:123", "session_id": SESSION_ID,
                "request_id": "reused-child-session-start"},
            profile="kid", capability=True)

        self.assertEqual(409, status)
        self.assertEqual("session_id_reused", result["reason"])
        self.assertIsNone(self.state.child_time_session)

    def test_partial_hard_reset_persists_consumed_child_session_id_across_restart(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "child-before-failed-reset"},
            profile="kid", capability=True)
        self.state.proxy_json = lambda *_args, **_kwargs: (
            False, {"error": "provider unavailable"})

        status, result = self.post(
            "/api/v1/session/hard-reset", {"force": True}, profile="kid",
            capability=True, request_id="failed-child-hard-reset")

        self.assertEqual(200, status)
        self.assertTrue(result["accepted"])
        self.assertTrue(result["recovered"])
        self.assertFalse(result["cleanup_confirmed"])
        restarted = GatewayState(self.config_path)
        restarted.child_profiles_api_enabled = True
        restarted.child_time_clock = lambda: tuple(self.clock)
        restarted.proxy = self.state.proxy
        restarted.proxy_json = lambda name, path, body, **_kwargs: (
            (True, {"game_id": "steam:123", "canonical_game_id": "steam:123",
                    "revision": "catalog-1"}) if path == "/library/resolve" else
            (True, {}))
        client = restarted.client_for_token("child-token")

        status, reused = restarted.child_time_session_start(
            client, "kid", "steam:123", SESSION_ID, "reuse-after-restart")

        self.assertEqual(409, status)
        self.assertEqual("session_id_reused", reused["reason"])
        self.assertIsNone(restarted.child_time_session)

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

    def test_child_suspend_uses_parent_execution_and_releases_only_time_attachment(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "start-suspend",
        }, profile="kid", capability=True)
        self.establish_child_transport_and_start_game()
        stream = dict(self.state.control_state["stream"])
        accepted = {
            "ok": True, "accepted": True, "suspend_id": "child-suspend",
            "session": {"sunshine_app_id": 7,
                        "playnite_game_id": "steam:123", "title": "Allowed"},
        }
        with mock.patch.object(self.state, "suspend_session",
                               return_value=(202, accepted)) as suspend:
            status, result = self.post("/api/v1/system/suspend-session", {
                "suspend_id": "child-suspend", "sunshine_app_id": 7,
                "playnite_game_id": "steam:123", "title": "Allowed",
            }, profile="kid", capability=True, request_id="child-suspend")
        self.assertEqual(202, status, result)
        self.assertTrue(result["child_time_released"])
        self.assertEqual(SESSION_ID, result["child_session_id"])
        suspend.assert_called_once()
        self.assertIsNone(self.state.child_time_session)
        self.assertEqual(stream, self.state.control_state["stream"])
        self.assertEqual("running", self.current["state"])

    def test_child_suspend_without_a_game_id_has_no_host_effect(self):
        # R011 P2: a missing or empty game id must not bypass the session game binding.
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "start-suspend",
        }, profile="kid", capability=True)
        self.establish_child_transport_and_start_game()
        session = self.state.child_time_session
        for serial, game in enumerate(({}, {"playnite_game_id": ""},
                                       {"playnite_game_id": "   "})):
            with mock.patch.object(self.state, "suspend_session") as suspend:
                status, result = self.post("/api/v1/system/suspend-session", {
                    "suspend_id": "child-suspend", "sunshine_app_id": 7,
                    "title": "Allowed", **game,
                }, profile="kid", capability=True,
                    request_id="child-suspend-empty-%d" % serial)
            self.assertEqual(403, status, result)
            suspend.assert_not_called()
            self.assertIs(session, self.state.child_time_session)

    def test_child_install_uninstall_match_parent_capabilities(self):
        for action in ("install", "uninstall"):
            status, result = self.post(
                "/api/v1/game/" + action, {"game_id": "steam:123"},
                profile="kid", capability=True, request_id="child-" + action)
            self.assertEqual(200, status, result)
            self.assertTrue(result["ok"])
        self.assertEqual(["/game/install", "/game/uninstall"], [
            path for name, path, _body in self.bridge_calls
            if name == "game_provider" and path in {"/game/install", "/game/uninstall"}
        ])

    def test_child_discord_and_unrelated_destructive_routes_are_rejected(self):
        for path in ("/api/v1/discord/home", "/api/v1/vibepollo/action/restart"):
            with self.subTest(path=path):
                status, result = self.post(path, {}, profile="kid", capability=True)
                self.assertEqual(403, status)
                self.assertEqual("child_action_not_allowed", result["error"])

    def test_child_hard_reset_uses_the_global_escape_hatch_without_owning_session(self):
        expected = {"ok": True, "accepted": True}
        with mock.patch.object(self.state, "hard_reset_session",
                               return_value=(200, expected)) as reset:
            status, result = self.post(
                "/api/v1/session/hard-reset", {"force": True}, profile="kid",
                capability=True, request_id="child-global-hard-reset")

        self.assertEqual(200, status)
        self.assertEqual(expected, result)
        reset.assert_called_once_with({"force": True}, "tv-1", "parent")

    def test_revoked_child_can_hard_reset_only_its_exact_session(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "revoked-hard-reset-start"}, profile="kid", capability=True)
        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        del registry["profiles"]["kid"]
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")

        status, result = self.post(
            "/api/v1/session/hard-reset",
            {"force": True, "session_id": SESSION_ID}, profile="kid",
            capability=True, request_id="revoked-child-hard-reset")

        self.assertEqual(200, status)
        self.assertTrue(result["accepted"])

    def test_disabled_child_can_hard_reset_its_exact_persisted_session(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "disabled-hard-reset-start"}, profile="kid", capability=True)
        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        registry["profiles"]["kid"]["enabled"] = False
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")

        status, result = self.post(
            "/api/v1/session/hard-reset",
            {"force": True, "session_id": SESSION_ID}, profile="kid",
            capability=True, request_id="disabled-child-hard-reset")

        self.assertEqual(200, status)
        self.assertTrue(result["accepted"])

    def test_child_without_current_grant_can_hard_reset_its_exact_session(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "ungranted-hard-reset-start"}, profile="kid", capability=True)
        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        registry["clients"][0]["profile_grants"]["kid"] = []
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")

        status, result = self.post(
            "/api/v1/session/hard-reset",
            {"force": True, "session_id": SESSION_ID}, profile="kid",
            capability=True, request_id="ungranted-child-hard-reset")

        self.assertEqual(200, status)
        self.assertTrue(result["accepted"])

    def test_child_global_hard_reset_is_scoped_to_the_requesting_tv(self):
        self.post("/api/v1/child/session/start", {
            "game_id": "steam:123", "session_id": SESSION_ID,
            "request_id": "wrong-owner-reset-start"}, profile="kid", capability=True)
        def reset(_body, client_id, execution_id):
            if client_id != "tv-1" or execution_id != "parent":
                return 409, {"ok": False, "accepted": False,
                             "reason": "hard_reset_requester_conflict"}
            return 200, {"ok": True, "accepted": True}

        with mock.patch.object(self.state, "hard_reset_session", side_effect=reset):
            status, result = self.post(
                "/api/v1/session/hard-reset",
                {"force": True}, profile="kid", capability=True,
                request_id="other-tv-reset", token="other-token")
            self.assertEqual(409, status, result)
            self.assertEqual("hard_reset_requester_conflict", result["reason"])

            other = self.state.client_for_token("other-token")
            other["profile_grants"]["kid"] = []
            status, _result = self.post(
                "/api/v1/session/hard-reset", {"force": True}, profile="kid",
                capability=True, request_id="ungranted-reset", token="other-token")
            self.assertEqual(400, status)
        self.assertIsNotNone(self.state.child_time_session)

    def test_child_global_hard_reset_is_idempotent_per_request(self):
        expected = {"ok": True, "accepted": True}
        with mock.patch.object(self.state, "hard_reset_session",
                               return_value=(200, expected)) as reset:
            for _index in range(2):
                status, result = self.post(
                    "/api/v1/session/hard-reset", {"force": True}, profile="kid",
                    capability=True, request_id="same-hard-reset-request")
                self.assertEqual(200, status)
                self.assertEqual(expected, result)
        reset.assert_called_once_with({"force": True}, "tv-1", "parent")

    def test_parent_hard_reset_idempotency_is_scoped_per_tv(self):
        self.state.client_for_token("other-token")["profile_grants"]["parent"] = [
            "use_profile"]
        expected = {"ok": True, "accepted": True}
        with mock.patch.object(self.state, "hard_reset_session",
                               return_value=(200, expected)) as reset:
            for token in ("child-token", "other-token"):
                status, result = self.post(
                    "/api/v1/session/hard-reset", {"force": True},
                    profile="parent", request_id="same-parent-reset", token=token)
                self.assertEqual(200, status)
                self.assertEqual(expected, result)
        self.assertEqual(2, reset.call_count)

    def test_parent_hard_reset_reaches_recovery_escape_hatch(self):
        expected = {"ok": True, "accepted": True}
        with mock.patch.object(self.state, "hard_reset_session",
                               return_value=(200, expected)) as reset:
            status, result = self.post(
                "/api/v1/session/hard-reset", {"force": True}, profile="parent")

        self.assertEqual(200, status)
        self.assertEqual(expected, result)
        reset.assert_called_once_with({"force": True})


if __name__ == "__main__":
    unittest.main()
