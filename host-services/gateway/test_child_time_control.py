import json
import tempfile
import unittest
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
        self.current = {"id": "", "state": "idle", "running_games": []}
        self.proxy_calls = []
        self.bridge_calls = []
        self.graceful_end = False

        def proxy(name, path, timeout=2.5, profile_id=None):
            del timeout
            self.proxy_calls.append((name, path, profile_id))
            if name == "game_provider" and path == "/game/current":
                return True, {
                    **self.current,
                    "running_games": [dict(item) for item in
                                      self.current.get("running_games", [])],
                }
            return True, {}

        def proxy_json(name, path, body, timeout=8.0, profile_id=None):
            del timeout
            self.bridge_calls.append((name, path, dict(body), profile_id))
            if name != "game_provider":
                return True, {}
            if path == "/library/resolve":
                return True, {"game_id": "steam:123",
                              "canonical_game_id": "steam:123", "revision": "rev-1"}
            if path == "/child/session":
                if body["action"] == "end" and not self.graceful_end:
                    self.current = {"id": "", "state": "idle", "running_games": []}
                result = {"accepted": True,
                          "state": ("ending" if self.graceful_end else "stopped")
                          if body["action"] == "end"
                          else body["action"], "game_id": body["game_id"]}
                if body["action"] == "end" and self.graceful_end:
                    result["cleanup_required"] = True
                if body["action"] != "end":
                    result["session_id"] = body["session_id"]
                return True, result
            if path == "/game/start":
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

        self.state.proxy = proxy
        self.state.proxy_json = proxy_json

    def tearDown(self):
        self.temp_dir.cleanup()

    def session_id(self):
        return str(uuid4())

    def child(self):
        return self.state.config["profiles"]["kid"]

    def start_running(self):
        previous_binds = len([call for call in self.bridge_calls
                              if call[1] == "/child/session" and
                              call[2]["action"] == "bind"])
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id())
        self.assertEqual(200, status)
        self.assertEqual("running", body["phase"])
        self.assertEqual(previous_binds + 2, len([call for call in self.bridge_calls
                                                 if call[1] == "/child/session" and
                                                 call[2]["action"] == "bind"]))
        return body["session_id"]

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

    def test_parent_running_game_blocks_start_even_for_same_child_game(self):
        self.current = {
            "id": "steam:123", "state": "running",
            "running_games": [{"game_id": "steam:123", "process_id": 42,
                                "process_token": TOKEN}],
        }
        status, body = self.state.child_time_session_start(
            self.client, "kid", "steam:123", self.session_id())
        self.assertEqual(409, status)
        self.assertEqual("session_in_use", body["reason"])
        self.assertIsNone(self.state.child_time_session)

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

    def test_bridge_lifecycle_is_bind_renew_end_and_client_does_not_see_dto(self):
        session_id = self.start_running()
        first_bind = next(call for call in self.bridge_calls
                          if call[1] == "/child/session" and
                          call[2]["action"] == "bind")
        bridge_deadline = datetime.fromisoformat(
            first_bind[2]["deadline_utc"].replace("Z", "+00:00"))
        self.assertLessEqual((bridge_deadline - self.clock.wall).total_seconds(), 120)
        self.clock.advance(20)
        status, body = self.state.child_time_session_heartbeat(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertNotIn("bridge", body)
        self.assertEqual(
            ["bind", "bind", "renew"],
            [call[2]["action"] for call in self.bridge_calls
             if call[1] == "/child/session"])
        self.state.child_time_session_end(self.client, "kid", session_id)
        self.assertEqual("end", [call[2]["action"] for call in self.bridge_calls
                                  if call[1] == "/child/session"][-1])

    def test_bridge_end_acceptance_waits_for_confirmed_stop(self):
        session_id = self.start_running()
        self.graceful_end = True
        status, body = self.state.child_time_session_end(
            self.client, "kid", session_id)
        self.assertEqual(409, status)
        self.assertEqual("cleanup_required", body["reason"])
        self.assertEqual(session_id, self.state.child_time_session["session_id"])
        self.graceful_end = False
        status, body = self.state.child_time_session_end(
            self.client, "kid", session_id)
        self.assertEqual(200, status)
        self.assertEqual("ended", body["state"])

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
