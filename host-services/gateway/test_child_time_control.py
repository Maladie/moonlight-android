import json
import tempfile
import threading
import unittest
from control_snapshot_fixture import control_evidence
from provider_target_fixture import target_aware
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import wakeplay_gateway
from wakeplay_gateway import GatewayState, sha256_text

TOKEN = "a" * 64
VIBE_UUID = "01234567-89ab-cdef-0123-456789abcdef"


class FakeClock:
    def __init__(self, wall):
        self.monotonic = 0.0
        self.wall = wall

    def __call__(self):
        return self.monotonic, self.wall

    def advance(self, seconds, wall_seconds=None):
        self.monotonic += seconds
        self.wall += timedelta(seconds=seconds if wall_seconds is None else wall_seconds)


class ChildTimeControlTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temp_dir.name) / "gateway.json"
        weekdays = {
            day: {"enabled": True, "start_minute": 8 * 60,
                  "end_minute": 20 * 60, "daily_limit_seconds": 75 * 60}
            for day in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        }
        sid = "S-1-5-21-1-2-3-1001"
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "game_provider_bridge": "http://127.0.0.1:8780",
            "vibepollo_bridge": "http://127.0.0.1:8775",
            "profiles": {
                "parent": {
                    "id": "parent", "kind": "standard", "name": "Parent",
                    "enabled": True, "windows_account_sid": sid,
                    "owner_sid": sid, "windows_account_name": "PARENT",
                    "owner": "PARENT", "profile_root": "C:/Users/PARENT",
                },
                "kid": {
                    "id": "kid", "kind": "child", "name": "Kid",
                    "enabled": True, "parent_profile_id": "parent",
                    "allowed_game_keys": ["parent/steam:123"],
                    "policy_revision": 4,
                    "schedule": {"timezone": "UTC", "weekdays": weekdays},
                },
            },
            "clients": [{
                "id": "tv-1", "token_sha256": sha256_text("child-token"),
                "profile_grants": {"kid": ["use_profile"]},
                "vibepollo_client_uuids": {"parent": VIBE_UUID},
            }],
        }), encoding="utf-8")
        self.state = GatewayState(self.config_path, None)
        self.client = self.state.client_for_token("child-token")
        self.assertIsNotNone(self.client)
        self.clock = FakeClock(datetime(2026, 1, 3, 8, 0, tzinfo=timezone.utc))
        self.state.child_time_clock = self.clock
        self.state.child_terminal_operation.clock = lambda: self.clock.monotonic
        self.state.child_terminal_operation.wait = self.clock.advance
        self.current = {"id": "", "state": "idle", "running_games": []}
        self.proxy_calls = []
        self.bridge_calls = []
        self.streamer_active = False

        def proxy(name, path, timeout=2.5, profile_id=None):
            del timeout
            self.proxy_calls.append((name, path, profile_id))
            if name == "game_provider" and path == "/observation":
                game_id = str(self.current.get("id") or "")
                state = str(self.current.get("state") or "unknown")
                running = [dict(item) for item in
                           self.current.get("running_games", [])]
                scan_status = str(self.current.get(
                    "running_scan_status", "complete"))
                game_evidence = "VERIFIED" if game_id else (
                    "ABSENT" if state == "idle" else "PENDING")
                process_evidence = "VERIFIED" if running else (
                    "ABSENT" if game_evidence == "ABSENT" and
                    scan_status == "complete" else "PENDING")
                return True, {
                    "game": {"state": state, "id": game_id,
                             "evidence": game_evidence},
                    "process": {
                        "evidence": process_evidence,
                        "running_games": running,
                        "running_scan_status": scan_status,
                    },
                }
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
            del timeout
            self.bridge_calls.append((name, path, dict(body), profile_id))
            if name != "game_provider":
                return True, {}
            if path == "/library/resolve":
                return True, {"game_id": "steam:123",
                              "canonical_game_id": "steam:123", "revision": "rev-1"}
            if path == "/game/start":
                self.streamer_active = True
                self.current = {
                    "id": "steam:123", "state": "running",
                    "processId": 42,
                    "running_games": [{"game_id": "steam:123", "process_id": 42,
                                        "process_token": TOKEN}],
                }
                return True, {"accepted": True, "state": "starting"}
            if path == "/game/stop-verified":
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True}
            return True, {}

        self.state.proxy = target_aware(proxy)
        self.state.proxy_json = proxy_json

    def tearDown(self):
        self.temp_dir.cleanup()

    def session_id(self):
        return str(uuid4())

    def child(self):
        return self.state.config["profiles"]["kid"]

    def test_current_game_requires_explicit_absence_before_idle(self):
        observations = [
            {},
            {"game": {"state": "starting", "id": "", "evidence": "PENDING"},
             "process": {"evidence": "PENDING", "running_games": [],
                         "running_scan_status": "complete"}},
            {"game": {"state": "unknown", "id": "", "evidence": "UNAVAILABLE"},
             "process": {"evidence": "UNAVAILABLE", "running_games": [],
                         "running_scan_status": "unavailable"}},
        ]
        for observation in observations:
            with self.subTest(observation=observation):
                self.state.proxy = lambda *args, value=observation, **kwargs: (
                    True, value)
                self.assertEqual(
                    "unavailable",
                    self.state._child_time_current_game("parent")["kind"])

        self.state.proxy = lambda *args, **kwargs: (True, {
            "game": {"state": "idle", "id": "", "evidence": "ABSENT"},
            "process": {"evidence": "ABSENT", "running_games": [],
                        "running_scan_status": "complete"},
        })
        self.assertEqual("idle", self.state._child_time_current_game("parent")["kind"])

    def test_retained_target_retires_only_on_exact_gone_or_reused_status(self):
        # An exe-less emulator that turned windowless is absent from a complete scan while
        # it still runs; only the provider's exact target status may retire its token.
        token = "b" * 64
        target = {"process_id": 55, "process_token": token}
        paths = []

        def idle_scan(name, path, **_kwargs):
            paths.append(path)
            return True, {"game": {"state": "idle", "id": "", "evidence": "ABSENT"},
                          "process": {"evidence": "ABSENT", "running_games": [],
                                      "running_scan_status": "complete"}}

        for status, retired in (("unknown", False), ("alive", False),
                                ("gone", True), ("reused", True)):
            with self.subTest(status=status):
                self.state.proxy = target_aware(idle_scan, lambda value=status: value)
                current = self.state._child_time_current_game("parent", target)
                self.assertEqual("idle" if retired else "unavailable", current["kind"])
                self.assertEqual(retired, self.state._child_time_process_absent(current, token))
                self.assertFalse(self.state._child_time_process_absent(current, "c" * 64))

        self.state.proxy = idle_scan
        current = self.state._child_time_current_game("parent", target)
        self.assertIn("target_process_id=55", paths[-1])
        self.assertIn("target_process_token=" + token, paths[-1])
        self.assertEqual("unavailable", current["kind"])
        self.assertFalse(self.state._child_time_process_absent(current, token))
        untargeted = self.state._child_time_current_game("parent")
        self.assertEqual("idle", untargeted["kind"])
        self.assertFalse(self.state._child_time_process_absent(untargeted, token))

    def start_running(self):
        previous_binds = len([call for call in self.bridge_calls
                              if call[1] == "/child/session" and
                              call[2]["action"] == "bind"])
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id())
        self.assertEqual(202, status)
        session = self.state.child_time_session
        operation_id = "child-transport-" + body["session_id"]
        status, permit = self.state._child_time_control_call(session, lambda:
            self.state.control_operation_acquire(self.client, {
                "operation_id": operation_id, "intent": "launch",
                "stream_session_id": "stream-" + body["session_id"],
                "sunshine_app_id": 7,
                "expected_generation": 0, "observed_sunshine_app_id": 0,
                "child_session_id": body["session_id"],
            }, operation_id))
        self.assertEqual(200, status)
        self.state._child_time_control_call(session, lambda:
            self.state.control_operation_complete(self.client, {
                "operation_id": operation_id, "generation": permit["generation"],
                "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
            }, operation_id))
        self.state.request_context.request_id = "provider-" + body["session_id"]
        status, _ = self.state._child_time_control_call(session, lambda:
            self.state.child_playnite_action(
                self.client, "kid", "game/start", {"game_id": "steam:123"}))
        self.assertEqual(200, status)
        status, body = self.state.child_time_session_heartbeat(
            self.client, "kid", body["session_id"])
        self.assertEqual(200, status)
        self.assertEqual("running", body["phase"])
        self.assertEqual(previous_binds, len([call for call in self.bridge_calls
                                             if call[1] == "/child/session" and
                                             call[2]["action"] == "bind"]))
        return body["session_id"]

    def test_replacement_releases_only_time_attachment(self):
        session_id = self.start_running()
        generation = self.state.control_state["stream"]["generation"]
        status, result = self.state.child_time_session_end(
            self.client, "kid", session_id, "replacement")
        self.assertEqual(200, status, result)
        end = [call[2] for call in self.bridge_calls
               if call[1] == "/child/session" and call[2]["action"] == "end"]
        self.assertEqual([], end)
        self.assertIsNone(self.state.child_time_session)
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])

    def test_housekeeping_during_game_start_does_not_expire_pending_session(self):
        proxy_json = self.state.proxy_json

        def start_with_housekeeping(name, path, body, **kwargs):
            if path == "/game/start":
                self.clock.advance(5)
                status, pending = self.state.child_time_housekeeping_tick()
                self.assertEqual(202, status)
                self.assertEqual("launch_pending", pending["state"])
                self.assertEqual(0, pending["used_seconds"])
            return proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = start_with_housekeeping
        session_id = self.start_running()
        self.assertEqual(session_id, self.state.child_time_session["session_id"])

    def test_pending_launch_still_stops_on_timeout_foreign_game_or_schedule_end(self):
        proxy_json = self.state.proxy_json
        self.state.proxy_json = lambda name, path, body, **kwargs: (
            (True, {"accepted": True}) if path == "/game/start"
            else proxy_json(name, path, body, **kwargs))
        for scenario in ("timeout", "foreign_game", "schedule_end"):
            with self.subTest(scenario=scenario):
                self.current = {"id": "", "state": "idle", "running_games": []}
                self.clock.wall = datetime(2026, 1, 3, 8, 0, tzinfo=timezone.utc)
                session_id = self.session_id()
                status, _body = self.state.child_time_session_start(
                    self.client, "kid", "steam:123", session_id)
                self.assertEqual(202, status)
                if scenario == "timeout":
                    self.clock.advance(wakeplay_gateway.CHILD_TIME_CLIENT_GRACE_SECONDS)
                elif scenario == "foreign_game":
                    self.current = {"id": "steam:999", "state": "running"}
                else:
                    self.clock.wall = self.clock.wall.replace(hour=20)
                _status, body = self.state.child_time_session_heartbeat(
                    self.client, "kid", session_id)
                self.assertEqual("outside_schedule" if scenario == "schedule_end"
                                 else "child_session_expired", body["reason"])
                self.assertIsNone(self.state.child_time_session)

    def test_disconnected_client_keeps_game_and_host_enforces_budget_and_schedule(self):
        for limit in ("budget", "schedule"):
            with self.subTest(limit=limit):
                self.clock.wall = datetime(2026, 1, 3, 19, 50, tzinfo=timezone.utc)
                self.state.child_time_usage["days"] = {}
                self.child()["schedule"]["weekdays"]["sat"]["daily_limit_seconds"] = (
                    300 if limit == "budget" else 4500)
                session_id = self.start_running()
                for _ in range(5):
                    self.clock.advance(30)
                    status, body = self.state.child_time_housekeeping_tick()
                    self.assertEqual(200, status)
                    self.assertEqual("running", body["state"])
                self.assertEqual(session_id, self.state.child_time_session["session_id"])
                self.assertEqual(150, body["used_seconds"])
                self.assertEqual(0, self.state.child_time_session["last_heartbeat_monotonic"]
                                 - self.state.child_time_session["started_monotonic"])
                for _ in range(5 if limit == "budget" else 15):
                    self.clock.advance(30)
                    status, body = self.state.child_time_housekeeping_tick()
                self.assertIsNone(self.state.child_time_session)
                self.assertEqual("daily_limit_reached" if limit == "budget"
                                 else "outside_schedule", body["reason"])

    def test_android_absent_expiry_closes_exact_managed_stream_under_same_lease(self):
        session_id = self.start_running()
        self.child()["schedule"]["weekdays"]["sat"]["daily_limit_seconds"] = 1
        self.clock.advance(2)
        status, body = self.state.child_time_housekeeping_tick()

        self.assertEqual(200, status)
        self.assertEqual("ended", body["state"])
        self.assertIsNone(self.state.control_state["stream"])
        self.assertIn(("vibepollo", "/action/close-app", "parent"), self.proxy_calls)

    def assert_replacement_closes_stream_without_stopping_replacement(self, game_id):
        self.start_running()
        replacement_token = "b" * 64
        stops_before = len([call for call in self.bridge_calls
                            if call[1] == "/game/stop-verified"])
        self.current = {
            "id": game_id, "state": "running", "running_scan_status": "complete",
            "running_games": [{"game_id": game_id, "process_id": 84,
                               "process_token": replacement_token}],
        }

        status, body = self.state.child_time_housekeeping_tick()

        self.assertEqual(200, status, body)
        self.assertEqual("ended", body["state"])
        self.assertEqual("child_session_expired", body["reason"])
        self.assertIsNone(self.state.child_time_session)
        self.assertIsNone(self.state.control_state["stream"])
        self.assertFalse(self.streamer_active)
        self.assertEqual(stops_before, len([call for call in self.bridge_calls
                                           if call[1] == "/game/stop-verified"]))
        self.assertEqual(replacement_token,
                         self.current["running_games"][0]["process_token"])

    def test_housekeeping_same_game_replacement_closes_only_shared_stream(self):
        self.assert_replacement_closes_stream_without_stopping_replacement("steam:123")

    def test_housekeeping_different_game_replacement_closes_only_shared_stream(self):
        self.assert_replacement_closes_stream_without_stopping_replacement("steam:999")

    def test_terminal_cleanup_uses_exact_streamer_app_after_android_disconnect(self):
        session_id = self.start_running()
        original_proxy = self.state.proxy
        streamer_active = True

        def proxy(name, path, **kwargs):
            nonlocal streamer_active
            self.proxy_calls.append((name, path, kwargs.get("profile_id")))
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {**control_evidence(0, streamer_active), "clients": [],
                              "apps": [{"app_id": 7, "running": streamer_active}]}
            if name == "vibepollo" and path == "/action/close-app":
                streamer_active = False
                return True, {"ok": True}
            return original_proxy(name, path, **kwargs)

        self.state.proxy = proxy
        status, body = self.state.child_time_session_end(
            self.client, "kid", session_id, "client_closed")

        self.assertEqual(200, status)
        self.assertFalse(body["cleanup_required"])
        self.assertIsNone(self.state.control_state["stream"])
        self.assertIn(("vibepollo", "/action/close-app", "parent"), self.proxy_calls)

    def test_game_stopped_ends_allowance_but_retains_managed_stream(self):
        session_id = self.start_running()
        generation = self.state.control_state["stream"]["generation"]
        self.proxy_calls.clear()

        status, body = self.state.child_time_session_end(
            self.client, "kid", session_id, "game_stopped")

        self.assertEqual(200, status)
        self.assertEqual("game_stopped", body["reason"])
        self.assertIsNotNone(self.state.control_state["stream"])
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])
        self.assertNotIn(("vibepollo", "/action/close-app", "parent"), self.proxy_calls)

    def test_verified_stop_allows_immediate_different_game_on_retained_stream(self):
        session_id = self.start_running()
        before = dict(self.state.control_state["stream"])
        original_proxy_json = self.state.proxy_json

        def proxy_json(name, path, body, **kwargs):
            if path == "/library/resolve":
                game_id = str(body.get("game_id") or "")
                return True, {"game_id": game_id,
                              "canonical_game_id": game_id, "revision": "rev-2"}
            if path == "/game/stop-verified":
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True, "command": "stop", "force": False,
                              "stopped_game_id": "steam:123",
                              "stopped_current": True}
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = proxy_json
        self.state.request_context.request_id = "verified-switch-stop"
        status, stopped = self.state.child_playnite_action(
            self.client, "kid", "game/stop-verified", {
                "game_id": "steam:123", "expected_process_token": TOKEN})

        self.assertEqual(200, status, stopped)
        retained = self.state.control_state["stream"]
        self.assertEqual(before["stream_session_id"], retained["stream_session_id"])
        self.assertEqual(before["sunshine_app_id"], retained["sunshine_app_id"])
        self.assertEqual(before["generation"], retained["generation"])
        self.assertEqual(before["actor_lease_epoch"], retained["actor_lease_epoch"])
        self.assertEqual("", retained["provider_game_id"])
        self.assertEqual("", retained["process_token"])
        self.assertEqual(0, retained["process_id"])

        status, ended = self.state.child_time_session_end(
            self.client, "kid", session_id, "game_stopped")
        self.assertEqual(200, status, ended)
        self.child()["allowed_game_keys"].append("parent/steam:456")
        next_id = self.session_id()
        status, started = self.state.child_time_session_start(
            self.client, "kid", "steam:456", next_id, "retained-switch-start")

        self.assertEqual(202, status, started)
        self.assertEqual(next_id, self.state.child_time_session["session_id"])
        self.assertEqual(before["stream_session_id"],
                         self.state.control_state["stream"]["stream_session_id"])
        self.assertNotIn(("vibepollo", "/action/close-app", "parent"), self.proxy_calls)

    def test_restart_repairs_persisted_exact_verified_stop_receipt(self):
        self.start_running()
        before = dict(self.state.control_state["stream"])
        original_proxy_json = self.state.proxy_json

        def proxy_json(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True, "command": "stop", "force": False,
                              "stopped_game_id": "steam:123",
                              "stopped_current": True}
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = proxy_json
        self.state.request_context.request_id = "persisted-switch-stop"
        status, stopped = self.state.child_playnite_action(
            self.client, "kid", "game/stop-verified", {
                "game_id": "steam:123", "expected_process_token": TOKEN})
        self.assertEqual(200, status, stopped)

        # Recreate the already-deployed pre-fix state: the exact successful
        # receipt persisted, but its stopped process identity remained attached.
        with self.state.lock:
            self.state.control_state["stream"].update({
                "provider_game_id": before["provider_game_id"],
                "process_token": before["process_token"],
                "process_id": before["process_id"],
            })
            self.state.child_time_session = None
            self.state.child_time_usage["active_session"] = None
            self.state._control_save_locked()

        restarted = GatewayState(self.config_path, None)
        retained = restarted.control_state["stream"]
        self.assertEqual(before["stream_session_id"], retained["stream_session_id"])
        self.assertEqual(before["generation"], retained["generation"])
        self.assertEqual(before["actor_lease_epoch"], retained["actor_lease_epoch"])
        self.assertEqual("", retained["provider_game_id"])
        self.assertEqual("", retained["process_token"])
        self.assertEqual(0, retained["process_id"])

    def test_verified_stop_receipt_cannot_retire_replacement_process(self):
        self.start_running()
        stream = self.state.control_state["stream"]
        receipt = {
            **{key: stream.get(key) for key in (
                "client_id", "actor_profile_id", "execution_profile_id",
                "stream_session_id", "sunshine_app_id", "generation",
                "actor_lease_epoch", "child_session_id")},
            "state": "succeeded", "intent": "provider_transition",
            "direction": "transition",
            "effect_request_sha256": wakeplay_gateway.ControlOperationPolicy.effect_hash(
                "/game/stop-verified", {
                    "game_id": "steam:123", "expected_process_token": TOKEN}),
            "effect_result": {
                "accepted": True, "command": "stop", "stopped_current": True,
                "stopped_game_id": "steam:123",
            },
        }
        replacement_token = "b" * 64
        stream.update(process_token=replacement_token, process_id=84)

        with self.state.lock:
            retired = self.state._control_reconcile_verified_provider_stop_locked(receipt)

        self.assertFalse(retired)
        self.assertEqual(replacement_token, stream["process_token"])
        self.assertEqual(84, stream["process_id"])

    def test_uncertain_child_cleanup_is_retried_by_host_without_android(self):
        session_id = self.start_running()
        original_proxy = self.state.proxy
        ambiguous = True

        def proxy(name, path, **kwargs):
            if ambiguous and name == "vibepollo" and path == "/snapshot?force=1":
                clients = [{"uuid": VIBE_UUID, "connected": True}]
                clients.append({"uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                "connected": True})
                return True, {**control_evidence(len(clients), True), "clients": clients, "apps": [{"app_id": 7, "running": True}]}
            return original_proxy(name, path, **kwargs)

        self.state.proxy = proxy
        status, first = self.state.child_time_session_end(
            self.client, "kid", session_id, "time_expired")
        self.assertEqual(409, status)
        self.assertTrue(first["cleanup_required"])
        uncertain = dict(self.state.control_state["active"])
        self.assertEqual("uncertain", uncertain["state"])

        ambiguous = False
        status, second = self.state.child_time_housekeeping_tick()
        self.assertEqual(200, status)
        self.assertFalse(second["cleanup_required"])
        self.assertIsNone(self.state.child_time_session)
        completed = self.state.control_state["completed"][uncertain["operation_id"]]
        self.assertEqual(uncertain["generation"], completed["generation"])
        self.assertEqual(uncertain["actor_lease_epoch"], completed["actor_lease_epoch"])
        self.assertEqual("time_expired", completed["child_terminal_result"]["reason"])
        self.assertIsNone(self.state.control_state["stream"])

    def test_async_provider_grace_keeps_exact_permit_until_stream_close(self):
        session_id = self.start_running()
        status, result = self.state.child_time_session_end(
            self.client, "kid", session_id, "client_closed")
        self.assertEqual(200, status)
        self.assertFalse(result["cleanup_required"])
        self.assertEqual(1, len([call for call in self.bridge_calls
                                if call[1] == "/game/stop-verified"]))
        self.assertFalse(any(call[1] == "/child/session"
                             for call in self.bridge_calls))
        self.assertIsNone(self.state.control_state["active"])
        self.assertIsNone(self.state.control_state["stream"])
        self.start_running()  # New explicit Play is usable immediately.

    def test_game_stop_release_never_calls_child_provider_lifecycle(self):
        session_id = self.start_running()
        status, result = self.state.child_time_session_end(
            self.client, "kid", session_id, "game_stopped")
        self.assertEqual(200, status)
        self.assertEqual("game_stopped", result["reason"])
        self.assertIsNone(self.state.child_time_session)
        self.assertFalse(any(call[1] == "/child/session" and
                             call[2].get("action") == "end"
                             for call in self.bridge_calls))
        self.assertIsNotNone(self.state.control_state["stream"])
        self.assertNotIn(("vibepollo", "/action/close-app", "parent"), self.proxy_calls)

    def test_game_stop_release_has_no_dependency_on_removed_bridge_route(self):
        session_id = self.start_running()
        original = self.state.proxy_json
        self.state.proxy_json = lambda name, path, body, **kwargs: (
            (False, {"error": "offline"}) if path == "/child/session"
            else original(name, path, body, **kwargs))
        status, result = self.state.child_time_session_end(
            self.client, "kid", session_id, "game_stopped")
        self.assertEqual(200, status, result)
        self.assertIsNone(self.state.control_state["active"])
        self.assertIsNotNone(self.state.control_state["stream"])

    def test_released_time_attachment_stays_released_after_restart(self):
        session_id = self.start_running()
        status, result = self.state.child_time_session_end(
            self.client, "kid", session_id, "game_stopped")
        self.assertEqual(200, status, result)

        restarted = GatewayState(self.config_path, None)
        self.assertIsNone(restarted.child_time_session)
        self.assertIsNone(restarted.control_state["active"])
        self.assertIsNotNone(restarted.control_state["stream"])

    def test_lost_close_response_restart_reconciles_idle_without_second_close(self):
        session_id = self.start_running()
        original_proxy = self.state.proxy

        def lost_close(name, path, **kwargs):
            if path == "/action/close-app":
                raise TimeoutError("response lost")
            return original_proxy(name, path, **kwargs)

        self.state.proxy = lost_close
        status, _ = self.state.child_time_session_end(
            self.client, "kid", session_id, "client_closed")
        self.assertEqual(409, status)
        permit = dict(self.state.control_state["active"])
        restarted = GatewayState(self.config_path, None)
        restarted.child_time_clock = self.clock
        restarted.proxy_json = self.state.proxy_json
        calls = []

        def idle(name, path, **kwargs):
            calls.append(path)
            self.assertNotEqual("/action/close-app", path)
            if name == "game_provider" and path == "/observation":
                return True, {
                    "game": {"state": "idle", "id": "", "evidence": "ABSENT"},
                    "process": {"evidence": "ABSENT", "running_games": [],
                                "running_scan_status": "complete"},
                }
            return True, {**control_evidence(), "clients": [], "apps": []}

        restarted.proxy = target_aware(idle)
        status, result = restarted.child_time_session_status(self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertFalse(result["cleanup_required"])
        self.assertIsNone(restarted.control_state["active"])
        self.assertIsNone(restarted.control_state["stream"])
        self.assertEqual("succeeded", restarted.control_state["completed"][
            permit["operation_id"]]["state"])
        # A second restart loses only the response cache, not the terminal fact.
        after_completion = GatewayState(self.config_path, None)
        status, replay = after_completion.child_time_session_status(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual("ended", replay["phase"])
        self.assertEqual("steam:123", replay["game_id"])
        self.assertFalse(replay["cleanup_required"])
        after_completion.child_time_clock = self.clock
        after_completion.proxy = original_proxy
        after_completion.proxy_json = self.state.proxy_json
        next_session_id = "11234567-89ab-cdef-0123-456789abcdef"
        status, _ = after_completion.child_time_session_start(
            self.client, "kid", "steam:123", next_session_id, "new-request")
        self.assertEqual(202, status)
        new_generation = GatewayState(self.config_path, None)
        self.assertIsNotNone(new_generation.child_time_session)
        self.assertEqual(next_session_id,
                         new_generation.child_time_session["session_id"])
        self.assertEqual("new-request", new_generation.child_time_session["request_id"])

    def test_lost_child_terminal_response_restarts_same_exact_operation_once(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        first_calls = []
        original_proxy_json = self.state.proxy_json

        def lost_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                first_calls.append(dict(body))
                raise TimeoutError("response lost")
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = lost_response
        status, first = self.state._child_time_finish(
            self.client, session, "client_closed", self.clock.wall)
        self.assertEqual(409, status, first)
        active = dict(self.state.control_state["active"])
        self.assertEqual("uncertain", active["state"])
        self.assertEqual("child-terminal:" + session_id, active["operation_id"])

        restarted = GatewayState(self.config_path, None)
        restarted.child_time_clock = self.clock
        restarted.child_terminal_operation.clock = lambda: self.clock.monotonic
        restarted.child_terminal_operation.wait = self.clock.advance
        restarted.proxy = self.state.proxy
        restart_calls = []

        def retry_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                restart_calls.append(dict(body))
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True}
            return original_proxy_json(name, path, body, **kwargs)

        restarted.proxy_json = retry_response
        status, result = restarted.child_time_session_status(
            self.client, "kid", session_id)
        self.assertEqual(200, status, result)
        self.assertFalse(result["cleanup_required"])
        self.assertEqual([active["operation_id"]], [
            restarted.control_state["completed"][active["operation_id"]]["operation_id"]])
        self.assertEqual(1, len(first_calls))
        self.assertEqual(1, len(restart_calls))
        self.assertEqual(active["operation_id"], restart_calls[0][
            "control_operation"]["operation_id"])
        self.assertEqual(TOKEN, restart_calls[0]["expected_process_token"])
        self.assertIsNone(restarted.control_state["active"])
        self.assertIsNone(restarted.control_state["stream"])

    def test_exact_persisted_child_terminal_orphan_recovers_before_next_start(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        original_proxy_json = self.state.proxy_json

        def lost_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                raise TimeoutError("response lost")
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = lost_response
        status, first = self.state._child_time_finish(
            self.client, session, "client_closed", self.clock.wall)
        self.assertEqual(409, status, first)
        operation_id = "child-terminal:" + session_id
        with self.state.lock:
            self.assertEqual(operation_id,
                             self.state.control_state["active"]["operation_id"])
            self.state.child_time_session = None
            self.state.child_time_usage["active_session"] = None
            self.state._control_save_locked()

        restarted = GatewayState(self.config_path, None)
        restarted.child_time_clock = self.clock
        restarted.child_terminal_operation.clock = lambda: self.clock.monotonic
        restarted.child_terminal_operation.wait = self.clock.advance
        restarted.proxy = self.state.proxy
        retry_calls = []

        def retry_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                retry_calls.append(dict(body))
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True}
            return original_proxy_json(name, path, body, **kwargs)

        restarted.proxy_json = retry_response
        next_id = self.session_id()
        status, started = restarted.child_time_session_start(
            self.client, "kid", "steam:123", next_id, "after-exact-orphan")
        self.assertEqual(202, status, started)
        self.assertEqual(next_id, started["session_id"])
        self.assertEqual(1, len(retry_calls))
        self.assertEqual(operation_id, retry_calls[0]["control_operation"][
            "operation_id"])
        self.assertEqual(TOKEN, retry_calls[0]["expected_process_token"])
        self.assertEqual("succeeded", restarted.control_state["completed"][
            operation_id]["state"])
        self.assertIsNone(restarted.control_state["active"])
        self.assertIsNotNone(restarted.child_time_session)
        self.assertEqual(next_id, restarted.child_time_session["session_id"])

    def test_persisted_orphan_missing_effect_hash_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active.pop("effect_request_sha256", None))

    def test_persisted_user_terminated_orphan_replays_forced_stop(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        original_proxy_json = self.state.proxy_json

        def lost_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                self.assertTrue(body["force"])
                raise TimeoutError("response lost")
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = lost_response
        status, first = self.state._child_time_finish(
            self.client, session, "user_terminated", self.clock.wall, force=True)
        self.assertEqual(409, status, first)
        operation_id = "child-terminal:" + session_id
        with self.state.lock:
            self.assertEqual(operation_id,
                             self.state.control_state["active"]["operation_id"])
            self.state.child_time_session = None
            self.state.child_time_usage["active_session"] = None
            self.state._control_save_locked()

        restarted = GatewayState(self.config_path, None)
        restarted.child_time_clock = self.clock
        restarted.child_terminal_operation.clock = lambda: self.clock.monotonic
        restarted.child_terminal_operation.wait = self.clock.advance
        restarted.proxy = self.state.proxy
        retry_calls = []

        def retry_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                retry_calls.append(dict(body))
                self.assertTrue(body["force"])
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True}
            return original_proxy_json(name, path, body, **kwargs)

        restarted.proxy_json = retry_response
        next_id = self.session_id()
        status, started = restarted.child_time_session_start(
            self.client, "kid", "steam:123", next_id, "after-forced-orphan")
        self.assertEqual(202, status, started)
        self.assertEqual(1, len(retry_calls))
        self.assertTrue(retry_calls[0]["force"])
        self.assertEqual(operation_id, retry_calls[0]["control_operation"][
            "operation_id"])
        self.assertEqual("succeeded", restarted.control_state["completed"][
            operation_id]["state"])
        self.assertEqual(next_id, restarted.child_time_session["session_id"])

    def test_parent_full_quit_recovers_exact_persisted_child_terminal_orphan(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        original_proxy_json = self.state.proxy_json

        def lost_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                raise TimeoutError("response lost")
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = lost_response
        status, _body = self.state._child_time_finish(
            self.client, session, "client_closed", self.clock.wall)
        self.assertEqual(409, status)
        with self.state.lock:
            stream = dict(self.state.control_state["stream"])
            self.state.child_time_session = None
            self.state.child_time_usage["active_session"] = None
            self.state._control_save_locked()

        restarted = GatewayState(self.config_path, None)
        restarted.child_time_clock = self.clock
        restarted.child_terminal_operation.clock = lambda: self.clock.monotonic
        restarted.child_terminal_operation.wait = self.clock.advance
        restarted.proxy = self.state.proxy

        def retry_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True}
            return original_proxy_json(name, path, body, **kwargs)

        restarted.proxy_json = retry_response
        self.client["profile_grants"]["parent"] = ["use_profile"]
        restarted.authorize_profile(self.client, "parent")
        request = {
            "operation_id": "orphan-parent-close", "intent": "full_quit",
            "stream_session_id": stream["stream_session_id"],
            "sunshine_app_id": stream["sunshine_app_id"],
            "expected_generation": stream["generation"],
            "observed_sunshine_app_id": stream["sunshine_app_id"],
            "child_session_id": "",
        }
        status, result = restarted.control_operation_acquire(
            self.client, request, request["operation_id"])
        self.assertEqual(200, status, result)
        self.assertEqual("succeeded", result["state"])
        self.assertEqual("succeeded", restarted.control_state["completed"][
            "child-terminal:" + session_id]["state"])
        self.assertIsNone(restarted.control_state["active"])
        self.assertIsNone(restarted.control_state["stream"])

    def _persist_terminal_orphan_for_negative(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        original_proxy_json = self.state.proxy_json

        def lost_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                raise TimeoutError("response lost")
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = lost_response
        status, _body = self.state._child_time_finish(
            self.client, session, "client_closed", self.clock.wall)
        self.assertEqual(409, status)
        with self.state.lock:
            active = self.state.control_state["active"]
            self.state.child_time_session = None
            self.state.child_time_usage["active_session"] = None
            self.state._control_save_locked()
        return session_id, active, original_proxy_json

    def _assert_persisted_orphan_rejected(self, mutate=None, replacement=False):
        session_id, active, original_proxy_json = self._persist_terminal_orphan_for_negative()
        if mutate is not None:
            with self.state.lock:
                mutate(active)
                self.state._control_save_locked()
        if replacement:
            self.current = {
                "id": "steam:123", "state": "running",
                "running_games": [{"game_id": "steam:123", "process_id": 84,
                                    "process_token": "b" * 64}],
            }
        restarted = GatewayState(self.config_path, None)
        restarted.child_time_clock = self.clock
        restarted.child_terminal_operation.clock = lambda: self.clock.monotonic
        restarted.child_terminal_operation.wait = self.clock.advance
        restarted.proxy = self.state.proxy
        calls = []

        def reject_effect(name, path, body, **kwargs):
            calls.append((name, path, dict(body)))
            return original_proxy_json(name, path, body, **kwargs)

        restarted.proxy_json = reject_effect
        status, result = restarted.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id(),
            "orphan-negative")
        self.assertEqual(409, status, result)
        self.assertEqual("child_cleanup_required", result["reason"])
        self.assertFalse(any(path == "/game/stop-verified" for _, path, _ in calls))
        self.assertIsNone(restarted.child_time_session)
        self.assertIsNotNone(restarted.control_state["active"])

    def test_persisted_orphan_stale_operation_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active.update(operation_id="child-terminal:00000000-0000-0000-0000-000000000000"))

    def test_persisted_orphan_wrong_token_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active.update(process_token="b" * 64))

    def test_persisted_orphan_new_generation_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active.update(generation=int(active["generation"]) + 1))

    def test_persisted_orphan_new_actor_epoch_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active.update(actor_lease_epoch=int(
                active["actor_lease_epoch"]) + 1))

    def test_persisted_orphan_replacement_identity_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(replacement=True)

    def test_persisted_orphan_incomplete_evidence_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active.pop("child_terminal_result"))

    def test_persisted_orphan_wrong_effect_hash_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active.update(effect_request_sha256="0" * 64))

    def test_persisted_orphan_missing_terminal_reason_is_not_recovered(self):
        self._assert_persisted_orphan_rejected(
            lambda active: active["child_terminal_result"].pop("reason"))

    def test_parent_close_recovers_exact_child_cleanup_and_replays_receipt(self):
        session_id = self.start_running()
        stream = dict(self.state.control_state["stream"])
        original = self.state.proxy_json
        attempts = 0

        def transient(*args, **kwargs):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise TimeoutError("provider unavailable")
            return original(*args, **kwargs)

        self.state.proxy_json = transient
        self.client["profile_grants"]["parent"] = ["use_profile"]
        self.state.authorize_profile(self.client, "parent")
        request = {"operation_id": "parent-close", "intent": "full_quit",
                   "stream_session_id": stream["stream_session_id"], "sunshine_app_id": 7,
                   "expected_generation": stream["generation"],
                   "observed_sunshine_app_id": 7, "child_session_id": ""}
        status, result = self.state.control_operation_acquire(
            self.client, request, "parent-close", 0)
        self.assertEqual(200, status)
        self.assertEqual("succeeded", result["state"])
        self.assertEqual("parent", result["actor_profile_id"])
        terminal = self.state.control_state["completed"][
            "child-terminal:" + session_id]
        self.assertEqual(result["generation"], terminal["generation"])
        self.assertEqual(result["actor_lease_epoch"],
                         terminal["actor_lease_epoch"])
        self.assertGreater(terminal["generation"], stream["generation"])
        self.assertGreater(terminal["actor_lease_epoch"],
                           stream["actor_lease_epoch"])
        self.assertEqual(stream["process_token"], terminal["process_token"])
        self.assertEqual(stream["process_id"], terminal["process_id"])
        calls = len(self.proxy_calls)
        self.assertEqual((status, result), self.state.control_operation_acquire(
            self.client, request, "parent-close", 0))
        self.assertEqual(calls, len(self.proxy_calls))
        self.assertIsNone(self.state.child_time_session)
        self.assertIsNone(self.state.control_state["active"])

    def test_parent_close_aliases_exact_child_terminal_that_just_completed(self):
        session_id = self.start_running()
        stream = dict(self.state.control_state["stream"])
        status, child_result = self.state.child_time_session_end(
            self.client, "kid", session_id, "time_expired")
        self.assertEqual(200, status, child_result)
        self.client["profile_grants"]["parent"] = ["use_profile"]
        self.state.authorize_profile(self.client, "parent")
        request = {"operation_id": "late-parent-close", "intent": "full_quit",
                   "stream_session_id": stream["stream_session_id"],
                   "sunshine_app_id": stream["sunshine_app_id"],
                   "expected_generation": stream["generation"],
                   "observed_sunshine_app_id": stream["sunshine_app_id"],
                   "child_session_id": ""}

        status, result = self.state.control_operation_acquire(
            self.client, request, request["operation_id"], 0)

        self.assertEqual(200, status, result)
        self.assertEqual("succeeded", result["state"])
        self.assertEqual(self.state.control_state["completed"][
            "child-terminal:" + session_id]["generation"], result["generation"])
        self.assertIsNone(self.state.control_state["stream"])

    def test_parent_close_does_not_alias_child_terminal_from_foreign_actor_epoch(self):
        session_id = self.start_running()
        stream = dict(self.state.control_state["stream"])
        status, child_result = self.state.child_time_session_end(
            self.client, "kid", session_id, "time_expired")
        self.assertEqual(200, status, child_result)
        self.state.control_state["actor_lease_epoch"] += 1
        self.client["profile_grants"]["parent"] = ["use_profile"]
        self.state.authorize_profile(self.client, "parent")
        request = {"operation_id": "foreign-epoch-parent-close", "intent": "full_quit",
                   "stream_session_id": stream["stream_session_id"],
                   "sunshine_app_id": stream["sunshine_app_id"],
                   "expected_generation": stream["generation"],
                   "observed_sunshine_app_id": stream["sunshine_app_id"],
                   "child_session_id": ""}

        status, result = self.state.control_operation_acquire(
            self.client, request, request["operation_id"], 0)

        self.assertEqual(409, status, result)
        self.assertNotIn(request["operation_id"], self.state.control_state["completed"])

    def test_parent_recovery_and_old_child_cleanup_cannot_touch_a_new_stream(self):
        session_id = self.start_running()
        child = self.state.child_time_session
        stream = dict(self.state.control_state["stream"])
        self.client["profile_grants"]["parent"] = ["use_profile"]
        self.state.authorize_profile(self.client, "parent")
        request = {"operation_id": "parent-close-stale", "intent": "full_quit",
                   "stream_session_id": "foreign-stream", "sunshine_app_id": 7,
                   "expected_generation": stream["generation"],
                   "observed_sunshine_app_id": 7, "child_session_id": ""}
        calls = len(self.bridge_calls)
        status, _ = self.state.control_operation_acquire(
            self.client, request, request["operation_id"], 0)
        self.assertEqual(409, status)
        self.assertEqual(calls, len(self.bridge_calls))
        self.state.child_time_session_end(self.client, "kid", session_id, "client_closed")
        self.state.authorize_profile(self.client, "parent")
        start = {**request, "operation_id": "new-parent", "intent": "launch",
                 "expected_generation": 0, "observed_sunshine_app_id": 0}
        status, permit = self.state.control_operation_acquire(
            self.client, start, "new-parent", 0)
        self.assertEqual(200, status)
        self.state.control_operation_complete(self.client, {
            "operation_id": "new-parent", "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True},
            "new-parent")
        calls = (len(self.bridge_calls), len(self.proxy_calls))
        status, _ = self.state._child_time_finish(
            self.client, child, "client_closed", self.clock.wall)
        self.assertEqual(409, status)
        self.assertEqual(calls, (len(self.bridge_calls), len(self.proxy_calls)))
        self.assertEqual("parent", self.state.control_state["stream"]["actor_profile_id"])

    def test_thirty_second_heartbeat_charges_the_entire_monotonic_delta(self):
        session_id = self.start_running()
        self.clock.advance(30)
        status, body = self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual(30, body["used_seconds"])
        self.clock.advance(30)
        status, body = self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual(60, body["used_seconds"])

    def test_overlapping_end_and_housekeeping_join_one_common_cleanup(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        stream = dict(self.state.control_state["stream"])
        original_proxy_json = self.state.proxy_json
        entered = threading.Event()
        release = threading.Event()
        end_calls = []
        results = []

        def proxy_json(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                end_calls.append(dict(body))
                entered.set()
                self.assertTrue(release.wait(1.0))
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = proxy_json
        self.child()["schedule"]["weekdays"]["sat"]["daily_limit_seconds"] = 1
        self.clock.advance(2)
        first = threading.Thread(target=lambda: results.append(
            self.state.child_time_session_end(
                self.client, "kid", session_id, "time_expired")))
        second = threading.Thread(target=lambda: results.append(
            self.state.child_time_housekeeping_tick()))
        first.start()
        self.assertTrue(entered.wait(1.0))
        second.start()

        status, blocked = self.state._child_time_control_call(session, lambda:
            self.state.control_operation_acquire(self.client, {
                "operation_id": "next-play", "intent": "reconnect",
                "stream_session_id": stream["stream_session_id"],
                "sunshine_app_id": stream["sunshine_app_id"],
                "expected_generation": stream["generation"],
                "observed_sunshine_app_id": stream["sunshine_app_id"],
                "child_session_id": session_id,
            }, "next-play", 0))
        self.assertEqual(409, status)
        self.assertEqual("uncertain", blocked["state"])
        self.assertEqual(1, len(end_calls))
        self.assertFalse(results)
        release.set()
        first.join(1.0)
        second.join(1.0)

        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual(2, len(results))
        self.assertEqual(results[0], results[1])
        self.assertEqual(1, len(end_calls))

    def test_full_close_joins_inflight_child_terminal_and_returns_its_receipt(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        stream = dict(self.state.control_state["stream"])
        original_proxy_json = self.state.proxy_json
        entered = threading.Event()
        release = threading.Event()
        stop_calls = []
        child_results = []
        parent_results = []

        def proxy_json(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                stop_calls.append(dict(body))
                entered.set()
                self.assertTrue(release.wait(1.0))
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = proxy_json
        child = threading.Thread(target=lambda: child_results.append(
            self.state.child_time_session_end(
                self.client, "kid", session_id, "time_expired")))
        child.start()
        self.assertTrue(entered.wait(1.0))
        self.client["profile_grants"]["parent"] = ["use_profile"]
        self.state.authorize_profile(self.client, "parent")
        request = {"operation_id": "joined-parent-close", "intent": "full_quit",
                   "stream_session_id": stream["stream_session_id"],
                   "sunshine_app_id": stream["sunshine_app_id"],
                   "expected_generation": stream["generation"],
                   "observed_sunshine_app_id": stream["sunshine_app_id"],
                   "child_session_id": ""}
        def parent_close():
            self.state.authorize_profile(self.client, "parent")
            parent_results.append(self.state.control_operation_acquire(
                self.client, request, request["operation_id"], 0))

        parent = threading.Thread(target=parent_close)
        parent.start()
        self.assertEqual([], parent_results)
        self.assertIs(session, self.state.child_time_session)
        release.set()
        child.join(1.0)
        parent.join(1.0)

        self.assertFalse(child.is_alive())
        self.assertFalse(parent.is_alive())
        self.assertEqual(200, child_results[0][0])
        self.assertEqual(200, parent_results[0][0])
        self.assertEqual("succeeded", parent_results[0][1]["state"])
        self.assertEqual(1, len(stop_calls))
        self.assertEqual(1, len([call for call in self.proxy_calls
                                if call[0] == "vibepollo"
                                and call[1] == "/action/close-app"]))
        self.assertIsNone(self.state.child_time_session)
        self.assertIsNone(self.state.control_state["stream"])
        self.assertFalse(self.state.child_terminal_operation.in_progress(session))
        status, started = self.state.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id(), "after-joined-close")
        self.assertEqual(202, status, started)

    def test_new_start_replaces_same_execution_time_attachment_immediately(self):
        previous_id = self.start_running()
        new_session_id = self.session_id()
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", new_session_id)
        self.assertEqual(202, status, body)
        self.assertEqual(new_session_id,
                         self.state.child_time_session["session_id"])
        self.assertEqual("actor_replaced",
                         self.state.child_time_completed[previous_id]["reason"])
        self.assertFalse(any(call[1] == "/child/session" and
                             call[2].get("action") == "end"
                             for call in self.bridge_calls))

    def test_new_start_policy_failure_keeps_previous_actor_metered(self):
        previous_id = self.start_running()
        previous_stream = dict(self.state.control_state["stream"])
        policy_snapshot = self.state._child_time_policy_snapshot
        snapshots = 0

        def policy_changes_after_precheck(*args, **kwargs):
            nonlocal snapshots
            snapshots += 1
            result = policy_snapshot(*args, **kwargs)
            if snapshots == 2:
                return {**result, "playable_now_seconds": 0,
                        "reason": "outside_schedule"}
            return result

        self.state._child_time_policy_snapshot = policy_changes_after_precheck
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id())
        self.assertEqual(409, status, body)
        self.assertEqual("outside_schedule", body["reason"])
        self.assertEqual(previous_id, self.state.child_time_session["session_id"])
        self.assertEqual(previous_stream, self.state.control_state["stream"])
        self.assertNotIn(previous_id, self.state.child_time_completed)

    def test_terminal_handoff_is_not_observed_as_idle(self):
        operation = self.state.child_terminal_operation
        session = {}
        game_entered = threading.Event()
        release_game = threading.Event()
        terminal_entered = threading.Event()
        release_terminal = threading.Event()
        wait_results = []

        def game_effect():
            game_entered.set()
            self.assertTrue(release_game.wait(1.0))
            return "game"

        def terminal_effect():
            terminal_entered.set()
            self.assertTrue(release_terminal.wait(1.0))
            return "terminal"

        game = threading.Thread(target=lambda: operation.run(
            session, False, game_effect))
        terminal = threading.Thread(target=lambda: operation.run(
            session, True, terminal_effect))
        waiter = threading.Thread(target=lambda: wait_results.append(
            operation.wait_until_idle(session, 1.0)))

        game.start()
        self.assertTrue(game_entered.wait(1.0))
        terminal.start()
        with operation._condition:
            self.assertTrue(operation._condition.wait_for(
                lambda: operation._terminal_handoff is session, 1.0))
        waiter.start()
        release_game.set()
        self.assertTrue(terminal_entered.wait(1.0))
        self.assertEqual([], wait_results)
        release_terminal.set()
        game.join(1.0)
        terminal.join(1.0)
        waiter.join(1.0)

        self.assertFalse(game.is_alive())
        self.assertFalse(terminal.is_alive())
        self.assertFalse(waiter.is_alive())
        self.assertEqual([True], wait_results)

    def test_replayed_launch_pending_start_remains_accepted(self):
        session_id = self.session_id()
        status, first = self.state.child_time_session_start(
            self.client, "kid", "steam:123", session_id, "request-1")
        status2, replay = self.state.child_time_session_start(
            self.client, "kid", "steam:123", session_id, "request-1")

        self.assertEqual(202, status, first)
        self.assertEqual(202, status2, replay)
        self.assertTrue(replay["ok"])
        self.assertEqual("none", replay["reason"])
        self.assertEqual("launch_pending", replay["state"])
        self.assertEqual(session_id, replay["session_id"])

    def test_new_start_joins_terminal_cleanup_before_creating_attachment(self):
        previous_id = self.start_running()
        previous = self.state.child_time_session
        next_id = self.session_id()
        original_proxy_json = self.state.proxy_json
        entered = threading.Event()
        release = threading.Event()
        terminal_results = []
        start_results = []

        def proxy_json(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                entered.set()
                self.assertTrue(release.wait(1.0))
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = proxy_json
        terminal = threading.Thread(target=lambda: terminal_results.append(
            self.state.child_time_session_end(
                self.client, "kid", previous_id, "client_closed")))
        terminal.start()
        self.assertTrue(entered.wait(1.0))
        start = threading.Thread(target=lambda: start_results.append(
            self.state.child_time_session_start(
                self.client, "kid", "steam:123", next_id, "next-request")))
        start.start()
        self.assertEqual([], start_results)
        self.assertIs(previous, self.state.child_time_session)
        release.set()
        terminal.join(1.0)
        start.join(1.0)

        self.assertFalse(terminal.is_alive())
        self.assertFalse(start.is_alive())
        self.assertEqual(200, terminal_results[0][0])
        self.assertEqual(202, start_results[0][0])
        self.assertEqual(next_id, self.state.child_time_session["session_id"])
        self.assertNotIn(previous_id, [self.state.child_time_session["session_id"]])
        self.assertFalse(any(call[1] == "/child/session" and
                             call[2].get("action") in {"bind", "renew", "end"}
                             for call in self.bridge_calls))

    def test_terminal_reservation_blocks_new_attachment_before_provider_snapshot(self):
        previous_id = self.start_running()
        previous = self.state.child_time_session
        next_id = self.session_id()
        original_current = self.state._child_time_current_game
        entered = threading.Event()
        release = threading.Event()
        terminal_results = []
        start_results = []

        def blocked_current(execution_profile_id):
            entered.set()
            self.assertTrue(release.wait(1.0))
            self.state._child_time_current_game = original_current
            return original_current(execution_profile_id)

        self.state._child_time_current_game = blocked_current
        terminal = threading.Thread(target=lambda: terminal_results.append(
            self.state.child_time_session_end(
                self.client, "kid", previous_id, "client_closed")))
        terminal.start()
        self.assertTrue(entered.wait(1.0))
        start = threading.Thread(target=lambda: start_results.append(
            self.state.child_time_session_start(
                self.client, "kid", "steam:123", next_id, "reserved-next")))
        start.start()

        self.assertEqual([], start_results)
        self.assertIs(previous, self.state.child_time_session)
        release.set()
        terminal.join(1.0)
        start.join(1.0)

        self.assertFalse(terminal.is_alive())
        self.assertFalse(start.is_alive())
        self.assertEqual(200, terminal_results[0][0])
        self.assertEqual(202, start_results[0][0])
        self.assertEqual(next_id, self.state.child_time_session["session_id"])

    def test_terminal_reservation_wins_the_late_start_publication_race(self):
        previous_id = self.start_running()
        previous = self.state.child_time_session
        next_id = self.session_id()
        original_current = self.state._child_time_current_game
        original_proxy_json = self.state.proxy_json
        start_before_publish = threading.Event()
        release_start = threading.Event()
        terminal_effect = threading.Event()
        release_terminal = threading.Event()
        start_results = []
        terminal_results = []

        def current(execution_profile_id, target=None):
            if threading.current_thread().name == "late-start":
                start_before_publish.set()
                self.assertTrue(release_start.wait(1.0))
            return original_current(execution_profile_id, target)

        def proxy_json(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                terminal_effect.set()
                self.assertTrue(release_terminal.wait(1.0))
            return original_proxy_json(name, path, body, **kwargs)

        self.state._child_time_current_game = current
        self.state.proxy_json = proxy_json
        start = threading.Thread(name="late-start", target=lambda: start_results.append(
            self.state.child_time_session_start(
                self.client, "kid", "steam:123", next_id, "late-publication")))
        start.start()
        self.assertTrue(start_before_publish.wait(1.0))
        terminal = threading.Thread(target=lambda: terminal_results.append(
            self.state.child_time_session_end(
                self.client, "kid", previous_id, "client_closed")))
        terminal.start()
        self.assertTrue(terminal_effect.wait(1.0))
        release_start.set()
        start.join(1.0)

        self.assertFalse(start.is_alive())
        self.assertEqual(409, start_results[0][0])
        self.assertEqual("child_cleanup_in_progress", start_results[0][1]["reason"])
        self.assertIs(previous, self.state.child_time_session)
        release_terminal.set()
        terminal.join(1.0)
        self.assertFalse(terminal.is_alive())
        self.assertEqual(200, terminal_results[0][0])

        status, retried = self.state.child_time_session_start(
            self.client, "kid", "steam:123", next_id, "late-publication")
        self.assertEqual(202, status, retried)
        self.assertEqual(next_id, self.state.child_time_session["session_id"])

    def test_terminal_reservation_rejects_late_process_binding(self):
        session_id = self.session_id()
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", session_id, "pending-bind")
        self.assertEqual(202, status, body)
        session = self.state.child_time_session
        operation_id = "pending-bind-stream"
        status, permit = self.state._child_time_control_call(session, lambda:
            self.state.control_operation_acquire(self.client, {
                "operation_id": operation_id, "intent": "launch",
                "stream_session_id": "stream-" + session_id,
                "sunshine_app_id": 7, "expected_generation": 0,
                "observed_sunshine_app_id": 0, "child_session_id": session_id,
            }, operation_id))
        self.assertEqual(200, status, permit)
        self.state._child_time_control_call(session, lambda:
            self.state.control_operation_complete(self.client, {
                "operation_id": operation_id, "generation": permit["generation"],
                "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
            }, operation_id))
        session.update(process_token=TOKEN, process_id=42)

        with self.state.lock:
            self.assertTrue(self.state.child_terminal_operation.reserve_terminal(session))
            self.assertFalse(self.state._control_bind_process_identity_locked(session))
            stream = self.state.control_state["stream"]
            self.assertFalse(stream.get("process_token"))
            self.assertEqual(0, int(stream.get("process_id") or 0))

    def test_terminal_close_reservation_blocks_all_nonterminal_releases(self):
        session_id = self.start_running()
        session = self.state.child_time_session
        with self.state.lock:
            self.assertTrue(self.state.child_terminal_operation.reserve_terminal(session))

        for reason in ("cancelled", "game_stopped", "replacement", "actor_replaced"):
            with self.subTest(reason=reason):
                status, result = self.state.child_time_session_end(
                    self.client, "kid", session_id, reason)
                self.assertEqual(409, status, result)
                self.assertEqual("child_cleanup_in_progress", result["reason"])
                self.assertIs(session, self.state.child_time_session)
        self.assertFalse(any(call[1] == "/child/session" and
                             call[2].get("action") == "end"
                             for call in self.bridge_calls))

    def test_tokenless_launch_pending_full_close_adopts_exact_permit_identity(self):
        session_id = self.session_id()
        status, pending = self.state.child_time_session_start(
            self.client, "kid", "steam:123", session_id, "tokenless-full-close")
        self.assertEqual(202, status, pending)
        session = self.state.child_time_session

        operation_id = "tokenless-transport-" + session_id
        self.state.authorize_child_profile(self.client, "kid")
        status, permit = self.state.control_operation_acquire(self.client, {
            "operation_id": operation_id, "intent": "launch",
            "stream_session_id": "stream-" + session_id,
            "sunshine_app_id": 7, "expected_generation": 0,
            "observed_sunshine_app_id": 0, "child_session_id": session_id,
        }, operation_id)
        self.assertEqual(200, status, permit)
        status, _completed = self.state.control_operation_complete(self.client, {
            "operation_id": operation_id, "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
        }, operation_id)
        self.assertEqual(200, status)
        self.assertEqual("launch_pending", session["phase"])
        self.assertEqual("", self.state._child_time_process_token(session))
        self.assertEqual("", self.state._child_time_process_token(
            self.state.control_state["stream"]))

        self.streamer_active = True
        self.current = {
            "id": "steam:123", "state": "running",
            "running_games": [{"game_id": "steam:123", "process_id": 42,
                                "process_token": TOKEN}],
        }

        status, ended = self.state.child_time_session_end(
            self.client, "kid", session_id, "client_closed")
        self.assertEqual(200, status, ended)
        self.assertFalse(ended["cleanup_required"])
        self.assertEqual(TOKEN, next(call[2]["expected_process_token"]
                                     for call in self.bridge_calls
                                     if call[1] == "/game/stop-verified"))
        self.assertIsNone(self.state.child_time_session)
        self.assertIsNone(self.state.control_state["active"])
        self.assertIsNone(self.state.control_state["stream"])

        next_id = self.session_id()
        status, next_start = self.state.child_time_session_start(
            self.client, "kid", "steam:123", next_id, "after-tokenless-full-close")
        self.assertEqual(202, status, next_start)
        self.assertEqual(next_id, next_start["session_id"])

    def test_tokenless_adoption_survives_uncertain_retry_with_same_identity(self):
        session_id = self.session_id()
        status, pending = self.state.child_time_session_start(
            self.client, "kid", "steam:123", session_id, "tokenless-retry")
        self.assertEqual(202, status, pending)
        session = self.state.child_time_session
        operation_id = "tokenless-retry-transport-" + session_id
        self.state.authorize_child_profile(self.client, "kid")
        status, permit = self.state.control_operation_acquire(self.client, {
            "operation_id": operation_id, "intent": "launch",
            "stream_session_id": "stream-" + session_id,
            "sunshine_app_id": 7, "expected_generation": 0,
            "observed_sunshine_app_id": 0, "child_session_id": session_id,
        }, operation_id)
        self.assertEqual(200, status, permit)
        self.state.control_operation_complete(self.client, {
            "operation_id": operation_id, "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
        }, operation_id)
        self.streamer_active = True
        self.current = {
            "id": "steam:123", "state": "running",
            "running_games": [{"game_id": "steam:123", "process_id": 42,
                                "process_token": TOKEN}],
        }
        original_proxy_json = self.state.proxy_json
        first_calls = []

        def lost_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                first_calls.append(dict(body))
                raise TimeoutError("response lost")
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = lost_response
        status, first = self.state._child_time_finish(
            self.client, session, "client_closed", self.clock.wall)
        self.assertEqual(409, status, first)
        active = self.state.control_state["active"]
        self.assertEqual(TOKEN, session["process_token"])
        self.assertEqual(42, session["process_id"])
        self.assertEqual(TOKEN, active["process_token"])
        self.assertEqual(42, active["process_id"])

        retry_calls = []

        def retry_response(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                retry_calls.append(dict(body))
                self.current = {"id": "", "state": "idle", "running_games": []}
                return True, {"accepted": True}
            return original_proxy_json(name, path, body, **kwargs)

        self.state.proxy_json = retry_response
        status, result = self.state._child_time_finish(
            self.client, session, "client_closed", self.clock.wall)
        self.assertEqual(200, status, result)
        self.assertEqual(1, len(first_calls))
        self.assertEqual(1, len(retry_calls))
        self.assertEqual("child-terminal:" + session_id,
                         retry_calls[0]["control_operation"]["operation_id"])
        self.assertEqual(TOKEN, retry_calls[0]["expected_process_token"])

    def test_launch_pending_idle_cleanup_reuses_exact_uncertain_permit_then_starts(self):
        session_id = self.session_id()
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", session_id, "launch-pending")
        self.assertEqual(202, status, body)
        session = self.state.child_time_session
        operation_id = "child-transport-" + session_id
        status, permit = self.state._child_time_control_call(session, lambda:
            self.state.control_operation_acquire(self.client, {
                "operation_id": operation_id, "intent": "launch",
                "stream_session_id": "stream-" + session_id,
                "sunshine_app_id": 7, "expected_generation": 0,
                "observed_sunshine_app_id": 0, "child_session_id": session_id,
            }, operation_id))
        self.assertEqual(200, status, permit)
        self.state._child_time_control_call(session, lambda:
            self.state.control_operation_complete(self.client, {
                "operation_id": operation_id, "generation": permit["generation"],
                "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
            }, operation_id))
        self.streamer_active = True
        original_close = wakeplay_gateway.StreamerControlOperation.close
        attempts = []

        def transient_close(proxy, profile_id, stream):
            del proxy, profile_id, stream
            attempts.append(True)
            return (False, "streamer_close_unconfirmed") if len(attempts) == 1 \
                else (True, "streamer_idle_after_close")

        wakeplay_gateway.StreamerControlOperation.close = staticmethod(transient_close)
        try:
            status, first = self.state.child_time_session_end(
                self.client, "kid", session_id, "client_closed")
            self.assertEqual(409, status, first)
            active = self.state.control_state["active"]
            self.assertTrue(active["provider_idle_confirmed"])
            self.assertEqual("", active["provider_game_id"])
            self.assertEqual("", active["process_token"])
            self.assertEqual(0, active["process_id"])

            next_id = self.session_id()
            status, started = self.state.child_time_session_start(
                self.client, "kid", "steam:123", next_id, "after-idle-cleanup")
        finally:
            wakeplay_gateway.StreamerControlOperation.close = original_close

        self.assertEqual(202, status, started)
        self.assertEqual(2, len(attempts))
        self.assertEqual(next_id, self.state.child_time_session["session_id"])
        self.assertIsNone(self.state.control_state["active"])

    def test_late_release_from_previous_actor_cannot_touch_new_attachment(self):
        previous_id = self.start_running()
        previous = self.state.child_time_session
        status, replacement = self.state.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id())
        self.assertEqual(202, status, replacement)
        current_id = self.state.child_time_session["session_id"]

        status, result = self.state._child_time_release_lease(
            self.client, previous, "game_stopped", self.clock.wall)

        self.assertEqual(409, status, result)
        self.assertEqual(current_id, self.state.child_time_session["session_id"])
        self.assertEqual("actor_replaced",
                         self.state.child_time_completed[previous_id]["reason"])

    def test_ambiguous_stream_observation_blocks_global_child_cleanup(self):
        session_id = self.start_running()
        original_proxy = self.state.proxy

        def proxy(name, path, **kwargs):
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {"clients": [
                    {"uuid": VIBE_UUID, "connected": True},
                    {"uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                     "connected": True},
                ]}
            return original_proxy(name, path, **kwargs)

        self.state.proxy = proxy
        self.child()["schedule"]["weekdays"]["sat"]["daily_limit_seconds"] = 1
        self.clock.advance(2)
        _status, body = self.state.child_time_housekeeping_tick()

        self.assertTrue(body["cleanup_required"])
        self.assertIsNotNone(self.state.control_state["stream"])
        self.assertNotIn(("vibepollo", "/action/close-app", "parent"), self.proxy_calls)

    def test_daily_budget_uses_monotonic_time_and_pauses_do_not_tick(self):
        session_id = self.start_running()

        for _index in range(18):
            self.clock.advance(100)
            status, _body = self.state.child_time_session_heartbeat(
                self.client, "kid", session_id)
            self.assertEqual(200, status)
        used = self.state.child_time_usage["days"]["kid"]["2026-01-03"]
        self.assertEqual(1800, used)

        # A wall-clock-only break does not consume the daily budget.
        self.clock.advance(0, wall_seconds=60 * 60)
        self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        self.assertEqual(1800,
                         self.state.child_time_usage["days"]["kid"]["2026-01-03"])

        for _index in range(27):
            self.clock.advance(100)
            self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        status, _body = self.state.child_time_session_end(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual(75 * 60,
                         self.state.child_time_usage["days"]["kid"]["2026-01-03"])

    def test_window_caps_playable_time_at_1950(self):
        self.clock.wall = datetime(2026, 1, 3, 19, 50, tzinfo=timezone.utc)
        status, body = self.state.child_time_eligibility(
            self.client, "kid", "steam:123")
        self.assertEqual(200, status)
        self.assertEqual("ready", body["state"])
        self.assertEqual(600, body["playable_now_seconds"])
        self.assertNotIn("bridge", body)

    def test_usage_splits_at_midnight_for_a_single_day_window(self):
        weekdays = {
            day: {"enabled": True, "start_minute": 23 * 60,
                  "end_minute": 24 * 60, "daily_limit_seconds": 7200}
            for day in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        }
        self.child()["schedule"]["weekdays"] = weekdays
        self.clock.wall = datetime(2026, 1, 3, 23, 59, tzinfo=timezone.utc)
        session_id = self.start_running()
        self.clock.advance(120)
        self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        self.assertEqual(30, self.state.child_time_usage["days"]["kid"]["2026-01-03"])
        self.assertEqual(30, self.state.child_time_usage["days"]["kid"]["2026-01-04"])
        self.state.child_time_session_end(self.client, "kid", session_id)

    def test_dst_transition_counts_real_elapsed_seconds_without_tzdata(self):
        weekdays = {
            day: {"enabled": day == "sun", "start_minute": 60,
                  "end_minute": 5 * 60, "daily_limit_seconds": 7200}
            for day in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        }
        self.child()["schedule"] = {"timezone": "UTC", "weekdays": weekdays}
        self.clock.wall = datetime(2026, 3, 29, 1, 50,
                                   tzinfo=timezone(timedelta(hours=1)))
        session_id = self.start_running()

        # The local clock jumps from 01:50+01 to 03:10+02, while UTC and
        # monotonic time advance by exactly twenty minutes.
        self.clock.monotonic += 1200
        second = datetime(2026, 3, 29, 3, 10,
                          tzinfo=timezone(timedelta(hours=2)))
        with self.state.lock:
            charged, rolled_back = self.state._child_time_accrue_locked(
                self.state.child_time_session, self.clock.monotonic, second,
                self.state.child_time_session["last_playable_seconds"])
        self.assertFalse(rolled_back)
        self.assertEqual(1200, charged)
        policy = self.state._child_time_policy_snapshot(
            "kid", self.child(), second)
        self.assertEqual("2026-03-29", policy["day_key"])
        self.assertEqual(1200, policy["used_seconds"])
        self.assertEqual(6000, policy["remaining_daily_seconds"])
        self.assertEqual(6000, policy["playable_now_seconds"])
        self.assertEqual(1200, self.state.child_time_usage["days"]["kid"][
            "2026-03-29"])

    def test_overlapping_heartbeat_does_not_treat_old_request_time_as_clock_rollback(self):
        session_id = self.start_running()
        self.clock.advance(10)
        read_current = self.state._child_time_current_game
        def overlapping_read(parent_id):
            self.state._child_time_current_game = read_current
            self.clock.advance(2)
            status, body = self.state.child_time_session_heartbeat(
                self.client, "kid", session_id)
            self.assertEqual(200, status)
            self.assertEqual("running", body["state"])
            return read_current(parent_id)
        self.state._child_time_current_game = overlapping_read
        status, body = self.state.child_time_session_heartbeat(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual("running", body["state"])
        self.assertEqual(12, body["used_seconds"])
        self.assertFalse(self.state.child_time_session.get("clock_rollback", False))
        self.assertFalse(any(call[2].get("action") == "end" for call in self.bridge_calls))

    def test_wall_clock_rollback_stops_without_extra_usage(self):
        session_id = self.start_running()
        self.clock.advance(100)
        self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        self.clock.monotonic += 100
        self.clock.wall = datetime(2026, 1, 3, 7, 59, 30, tzinfo=timezone.utc)
        status, body = self.state.child_time_session_heartbeat(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual("policy_unavailable", body["reason"])
        self.assertEqual(100, self.state.child_time_usage["days"]["kid"]["2026-01-03"])

    def test_parent_running_shared_game_can_receive_child_time_attachment(self):
        self.client["profile_grants"]["parent"] = ["use_profile"]
        self.state.authorize_profile(self.client, "parent")
        operation_id = "parent-managed-stream"
        status, permit = self.state.control_operation_acquire(self.client, {
            "operation_id": operation_id, "intent": "launch",
            "stream_session_id": "parent-stream", "sunshine_app_id": 7,
            "expected_generation": 0, "observed_sunshine_app_id": 0,
            "child_session_id": "",
        }, operation_id)
        self.assertEqual(200, status, permit)
        status, completed = self.state.control_operation_complete(self.client, {
            "operation_id": operation_id, "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True,
        }, operation_id)
        self.assertEqual(200, status, completed)
        self.streamer_active = True
        self.current = {
            "id": "steam:123", "state": "running",
            "running_games": [{"game_id": "steam:123", "process_id": 42,
                                "process_token": TOKEN}],
        }
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id())
        self.assertEqual(202, status, body)
        self.assertEqual("running", body["state"])
        self.assertEqual("steam:123", self.state.child_time_session["game_id"])
        self.assertEqual(TOKEN, self.state.child_time_session["process_token"])
        self.assertEqual(TOKEN, self.state.control_state["stream"]["process_token"])

    def test_unshared_start_is_rejected_before_pending_or_bridge_bind(self):
        self.child()["allowed_game_keys"] = []
        with self.assertRaises(PermissionError):
            self.state.child_time_session_start(
                self.client, "kid", "steam:123", self.session_id())
        self.assertIsNone(self.state.child_time_session)
        self.assertEqual([], [call for call in self.bridge_calls
                              if call[1] == "/child/session"])

    def test_only_one_child_device_can_own_the_active_generation(self):
        session_id = self.start_running()
        second = {
            "id": "tv-2", "profile_grants": {"kid": ["use_profile"]},
            "vibepollo_client_uuids": {"parent": "fedcba98-7654-3210-fedc-ba9876543210"},
        }
        status, body = self.state.child_time_session_start(
            second, "kid", "steam:123", self.session_id())
        self.assertEqual(409, status)
        self.assertEqual("session_in_use", body["reason"])
        self.assertEqual(session_id, self.state.child_time_session["session_id"])
        self.state.child_time_session_end(self.client, "kid", session_id)

    def test_usage_and_active_session_survive_gateway_restart(self):
        session_id = self.start_running()
        self.clock.advance(100)
        self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        self.state.child_time_shutdown()
        restarted = GatewayState(self.config_path, None)
        self.assertEqual(100,
                         restarted.child_time_usage["days"]["kid"]["2026-01-03"])
        self.assertEqual(session_id, restarted.child_time_session["session_id"])
        restarted.child_time_shutdown()

    def test_stale_end_does_not_close_a_new_generation(self):
        first = self.start_running()
        self.state.child_time_session_end(self.client, "kid", first)
        second = self.start_running()
        status, body = self.state.child_time_session_end(
            self.client, "kid", first)
        self.assertEqual(200, status)
        self.assertEqual("ended", body["state"])
        self.assertEqual(second, self.state.child_time_session["session_id"])
        self.state.child_time_session_end(self.client, "kid", second)

    def test_time_accounting_has_no_parallel_child_provider_lifecycle(self):
        session_id = self.start_running()
        self.clock.advance(20)
        status, body = self.state.child_time_session_heartbeat(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertNotIn("bridge", body)
        self.assertFalse(any(call[1] == "/child/session"
                             for call in self.bridge_calls))

    def test_terminal_time_expiry_uses_common_verified_game_stop(self):
        session_id = self.start_running()
        status, body = self.state.child_time_session_end(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual("ended", body["state"])
        self.assertTrue(any(call[1] == "/game/stop-verified"
                            for call in self.bridge_calls))
        self.assertFalse(any(call[1] == "/child/session"
                             for call in self.bridge_calls))

    def test_revoke_ends_only_matching_child_game_and_preserves_usage(self):
        session_id = self.start_running()
        self.clock.advance(100)
        self.state.child_time_session_heartbeat(self.client, "kid", session_id)
        status, body = self.state.child_time_revoke("kid", "parent/steam:123")
        self.assertEqual(200, status)
        self.assertEqual("game_access_revoked", body["reason"])
        self.assertEqual(100,
                         self.state.child_time_usage["days"]["kid"]["2026-01-03"])
        self.assertIsNone(self.state.child_time_session)


if __name__ == "__main__":
    unittest.main()
