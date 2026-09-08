import json
import os
import tempfile
import time
import unittest
from datetime import datetime, timezone
from http import HTTPStatus
from pathlib import Path
from unittest import mock

import wakeplay_gateway
from test_child_library_integration import BridgeFixture
from wakeplay_gateway import GatewayState, sha256_text


GAME_ID = "steam:123"
SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef"


def schedule_entry(enabled, start_minute, end_minute, daily_limit_seconds):
    return {
        "enabled": enabled,
        "start_minute": start_minute,
        "end_minute": end_minute,
        "daily_limit_seconds": daily_limit_seconds,
    }


def canonical_schedule():
    return {
        "timezone": "UTC",
        "weekdays": {
            day: schedule_entry(day in {"mon", "tue"}, 18 * 60, 20 * 60, 3_600)
            for day in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        },
    }


class ChildTimeContractTest(unittest.TestCase):
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
                    "schedule": canonical_schedule(),
                },
            },
            "clients": [{
                "id": "tv",
                "token_sha256": sha256_text("token"),
                "profile_grants": {"kid": ["use_profile"]},
                "vibepollo_client_uuids": {"parent": "vibepollo-parent"},
            }],
        }), encoding="utf-8")
        self.state = GatewayState(self.config_path, None)
        self.client = self.state.config["clients"][0]
        self.monotonic = 100.0
        self.wall = datetime(2026, 9, 7, 19, 50, tzinfo=timezone.utc)
        self.state.child_time_clock = lambda: (self.monotonic, self.wall)
        self.proxy_json_calls = []
        self.proxy_calls = []

        def proxy_json(name, path, body, timeout=8.0, profile_id=None):
            self.proxy_json_calls.append((name, path, body, timeout, profile_id))
            if path == "/library/resolve":
                return True, {
                    "game_id": body["game_id"],
                    "canonical_game_id": str(body["game_id"]).lower(),
                    "revision": "catalog-1",
                }
            if path == "/child/session":
                return True, {
                    "accepted": True,
                    "session_id": body["session_id"],
                }
            if path == "/game/start":
                return True, {"accepted": True}
            return True, {}

        def proxy(name, path, timeout=3.0, profile_id=None):
            self.proxy_calls.append((name, path, timeout, profile_id))
            if path == "/game/current":
                return True, {"state": "idle", "id": "", "running_games": []}
            return True, {}

        self.proxy_json = mock.patch.object(
            self.state, "proxy_json", side_effect=proxy_json)
        self.proxy = mock.patch.object(self.state, "proxy", side_effect=proxy)
        self.proxy_json.start()
        self.proxy.start()

    def tearDown(self):
        self.proxy.stop()
        self.proxy_json.stop()
        self.temporary.cleanup()

    def set_wall(self, year, month, day, hour, minute):
        self.wall = datetime(year, month, day, hour, minute, tzinfo=timezone.utc)

    def test_eligibility_clips_active_day_to_1950_window_end(self):
        status, body = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)

        self.assertEqual(HTTPStatus.OK, status)
        self.assertEqual("mon", body["weekday"])
        self.assertEqual("2026-09-07T18:00:00+00:00", body["window_start"])
        self.assertEqual("2026-09-07T20:00:00+00:00", body["window_end"])
        self.assertEqual(3_600, body["remaining_daily_seconds"])
        self.assertEqual(600, body["playable_now_seconds"])

    def test_eligibility_returns_a_consistent_block_for_missing_grant(self):
        self.state.config["profiles"]["kid"]["allowed_game_keys"] = []

        status, body = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)

        self.assertEqual(HTTPStatus.OK, status)
        self.assertFalse(body["ok"])
        self.assertEqual("blocked", body["state"])
        self.assertEqual("game_not_shared", body["reason"])
        self.assertEqual(GAME_ID, body["game_id"])
        self.assertEqual(0, body["playable_now_seconds"])

    def test_eligibility_allows_common_replacement_but_start_still_requires_cleanup(self):
        self.state.child_time_session = {
            "actor_profile_id": "kid",
            "client_id": self.state.broker_client_id(self.client),
            "phase": "running",
        }
        status, own = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)
        self.assertEqual(HTTPStatus.OK, status)
        self.assertTrue(own["ok"])
        self.assertEqual("ready", own["state"])
        self.assertEqual(600, own["playable_now_seconds"])

        self.state.child_time_session["actor_profile_id"] = "another-child"
        status, foreign = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)
        self.assertEqual(HTTPStatus.OK, status)
        self.assertTrue(foreign["ok"])
        self.assertEqual("none", foreign["reason"])
        self.assertEqual(600, foreign["playable_now_seconds"])
        previous_client = self.state.child_time_session["client_id"]
        self.state.child_time_session["client_id"] = "another-client"
        _, occupied = self.state.child_time_eligibility(self.client, "kid", GAME_ID)
        self.assertFalse(occupied["ok"])
        self.assertEqual("session_in_use", occupied["reason"])
        self.state.child_time_session["client_id"] = previous_client

        self.state.child_time_session["actor_profile_id"] = "kid"
        self.state.child_time_session["phase"] = "cleanup_required"
        status, cleanup = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)
        self.assertEqual(HTTPStatus.OK, status)
        self.assertTrue(cleanup["ok"])
        self.assertEqual("none", cleanup["reason"])
        self.assertEqual(600, cleanup["playable_now_seconds"])
        status, start = self.state.child_time_session_start(
            self.client, "kid", GAME_ID, SESSION_ID)
        self.assertEqual(HTTPStatus.CONFLICT, status)
        self.assertEqual("session_in_use", start["reason"])

    def test_child_profile_projection_exposes_window_and_pairing_gate(self):
        self.state.profile_login_state = lambda _parent: {
            "session_state": "active", "remote_sign_in_state": "ready"}
        dto = self.state._child_profile_public_dto(
            self.client, "kid", self.state.config["profiles"]["kid"])
        self.assertEqual("2026-09-07T20:00:00+00:00", dto["window_end"])
        self.assertEqual(600, dto["playable_now_seconds"])
        self.assertEqual("none", dto["reason"])

        unpaired = dict(self.client)
        unpaired["vibepollo_client_uuids"] = {}
        dto = self.state._child_profile_public_dto(
            unpaired, "kid", self.state.config["profiles"]["kid"])
        self.assertEqual("pairing_required", dto["reason"])
        self.assertEqual(0, dto["playable_now_seconds"])

        self.state.child_time_session = {
            "actor_profile_id": "kid",
            "client_id": self.state.broker_client_id(self.client),
            "phase": "running",
        }
        dto = self.state._child_profile_public_dto(
            self.client, "kid", self.state.config["profiles"]["kid"])
        self.assertEqual("none", dto["reason"])
        self.assertEqual(600, dto["playable_now_seconds"])

        self.state.child_time_session["actor_profile_id"] = "another-child"
        dto = self.state._child_profile_public_dto(
            self.client, "kid", self.state.config["profiles"]["kid"])
        self.assertEqual("none", dto["reason"])
        self.assertEqual(600, dto["playable_now_seconds"])

    def test_day_usage_is_calendar_scoped_and_disabled_day_blocks(self):
        self.state.child_time_usage["days"] = {
            "kid": {"2026-09-07": 900},
        }
        self.set_wall(2026, 9, 7, 18, 30)
        status, monday = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)

        self.assertEqual(HTTPStatus.OK, status)
        self.assertEqual("2026-09-07", monday["day_key"])
        self.assertEqual(900, monday["used_seconds"])
        self.assertEqual(2_700, monday["remaining_daily_seconds"])
        self.assertEqual(2_700, monday["playable_now_seconds"])

        self.set_wall(2026, 9, 8, 18, 30)
        status, tuesday = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)

        self.assertEqual(HTTPStatus.OK, status)
        self.assertEqual("tue", tuesday["weekday"])
        self.assertEqual("2026-09-08", tuesday["day_key"])
        self.assertEqual(0, tuesday["used_seconds"])
        self.assertEqual(3_600, tuesday["remaining_daily_seconds"])
        self.assertEqual(3_600, tuesday["playable_now_seconds"])

        self.state.config["profiles"]["kid"]["schedule"]["weekdays"]["tue"] = \
            schedule_entry(False, 18 * 60, 20 * 60, 3_600)
        self.set_wall(2026, 9, 8, 19, 0)

        status, body = self.state.child_time_eligibility(
            self.client, "kid", GAME_ID)

        self.assertEqual(HTTPStatus.OK, status)
        self.assertEqual("tue", body["weekday"])
        self.assertEqual("blocked", body["state"])
        self.assertEqual("outside_schedule", body["reason"])
        self.assertEqual(0, body["playable_now_seconds"])

    def test_bridge_deadline_is_capped_when_daily_budget_exceeds_lease(self):
        self.state.config["profiles"]["kid"]["schedule"]["weekdays"]["mon"] = \
            schedule_entry(True, 18 * 60, 23 * 60, 4_500)
        self.set_wall(2026, 9, 7, 18, 0)

        status, body = self.state.child_time_session_start(
            self.client, "kid", GAME_ID, SESSION_ID, "child-start-1")

        self.assertEqual(HTTPStatus.ACCEPTED, status)
        self.assertEqual("launch_pending", body["phase"])
        bind = next(call for call in self.proxy_json_calls
                    if call[1] == "/child/session" and call[2]["action"] == "bind")
        deadline = datetime.fromisoformat(
            bind[2]["deadline_utc"].replace("Z", "+00:00"))
        self.assertGreaterEqual((deadline - self.wall).total_seconds(), 0)
        self.assertLessEqual(
            (deadline - self.wall).total_seconds(),
            120)
        self.assertEqual("parent", bind[4])

        self.state.child_time_session = None

    def test_start_without_game_grant_never_starts_provider_or_leaks_session(self):
        self.state.config["profiles"]["kid"]["allowed_game_keys"] = []

        with self.assertRaises(PermissionError) as denied:
            self.state.child_time_session_start(
                self.client, "kid", GAME_ID, SESSION_ID, "child-start-2")

        self.assertEqual("game_not_shared", str(denied.exception))
        self.assertFalse(any(path == "/game/start"
                             for _name, path, _body, _timeout, _profile
                             in self.proxy_json_calls))
        self.assertIsNone(self.state.child_time_session)


class ChildTimeBridgeIntegrationTest(unittest.TestCase):
    """Exercise the real Bridge HTTP handler through the Gateway proxy seams."""

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
                    "allowed_game_keys": ["living-room/steam:123"],
                    "schedule": canonical_schedule(),
                },
            },
            "clients": [{
                "id": "tv",
                "token_sha256": sha256_text("child-time-integration-token"),
                "profile_grants": {"kid": ["use_profile"]},
                "vibepollo_client_uuids": {
                    "living-room": "22222222-2222-4222-8222-222222222222",
                },
            }],
        }), encoding="utf-8")
        self.gateway = GatewayState(self.config_path, None)
        self.client = self.gateway.config["clients"][0]
        self.monotonic = 100.0
        self.wall = datetime(2026, 9, 7, 19, 50, tzinfo=timezone.utc)
        self.gateway.child_time_clock = lambda: (self.monotonic, self.wall)

        self.bridge = BridgeFixture(self.root / "bridge")
        self.bridge.set_library([{
            "id": GAME_ID,
            "name": "Child game",
            "provider": "steam",
            "providerGameId": "123",
            "installDir": r"C:\Games\Child",
            "exe": "game.exe",
        }], revision="rev-1")
        self.bridge.state.clock = lambda: self.wall.timestamp()
        self.gateway.proxy = self.bridge.proxy
        self.gateway.proxy_json = self.bridge.proxy_json
        self.gateway.proxy_bytes = self.bridge.proxy_bytes
        self.launch = mock.patch.object(
            self.bridge.state.game_operations, "launch",
            return_value={"accepted": True, "command": "launch", "provider": "steam"})
        self.launch.start()

    def tearDown(self):
        self.launch.stop()
        self.temporary.cleanup()

    def test_child_time_session_round_trip_uses_real_bridge_contract(self):
        status, pending = self.gateway.child_time_session_start(
            self.client, "kid", GAME_ID, SESSION_ID, "child-time-start")

        self.assertEqual(HTTPStatus.ACCEPTED, status)
        self.assertEqual("launch_pending", pending["phase"])
        bridge_session_calls = [
            call for call in self.bridge.calls
            if call[0] == "json" and call[2] == "/child/session"
        ]
        self.assertEqual(1, len(bridge_session_calls))
        bind = bridge_session_calls[0][3]
        self.assertEqual("bind", bind["action"])
        self.assertEqual(SESSION_ID, bind["session_id"])
        self.assertEqual("kid", bind["actor_profile_id"])
        self.assertEqual("living-room", bind["execution_profile_id"])
        self.assertEqual(GAME_ID, bind["game_id"])
        self.assertEqual(
            "22222222-2222-4222-8222-222222222222",
            bind["vibepollo_client_uuid"])
        self.assertEqual("", bind["process_token"])
        paths = [call[2] for call in self.bridge.calls if call[0] == "json"]
        self.assertLess(paths.index("/child/session"), paths.index("/game/start"))

        process_id = 4242
        process_started = 133700000000000000
        process_path = r"C:\Games\Child\game.exe"
        child_identity = {
            "process_id": process_id,
            "process_path": process_path,
            "process_started_filetime": process_started,
            "user_sid": "test-sid",
            "session_id": 1,
            "visible_window": True,
        }
        own_identity = {
            "process_id": os.getpid(),
            "process_path": r"C:\Bridge\bridge.exe",
            "process_started_filetime": 1,
            "user_sid": "test-sid",
            "session_id": 1,
        }
        probe = mock.Mock()
        probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(child_identity) if pid == process_id else
            dict(own_identity) if pid == os.getpid() else None)
        probe.scan_running_processes.return_value = (
            [dict(child_identity)], "complete")
        self.bridge.state.running_process_probe = probe
        with self.bridge.state.lock:
            self.bridge.state.current = {
                "state": "running", "id": GAME_ID,
                "processId": process_id, "processPath": process_path,
                "processStartedFiletime": process_started,
            }
        self.bridge.state.refresh_running_games()
        self.assertEqual(1, len(self.bridge.state._running_games))
        process_token = self.bridge.state._running_games[0]["process_token"]
        self.assertRegex(process_token, r"^[0-9a-f]{64}$")

        self.monotonic = 110.0
        self.wall = datetime(2026, 9, 7, 19, 50, 10, tzinfo=timezone.utc)
        status, running = self.gateway.child_time_session_heartbeat(
            self.client, "kid", SESSION_ID)

        self.assertEqual(HTTPStatus.OK, status)
        self.assertEqual("running", running["phase"])
        bridge_session_calls = [
            call for call in self.bridge.calls
            if call[0] == "json" and call[2] == "/child/session"
        ]
        self.assertEqual(["bind", "bind"], [
            call[3]["action"] for call in bridge_session_calls])
        running_bind = bridge_session_calls[-1][3]
        self.assertEqual(process_token, running_bind["process_token"])
        self.assertEqual(SESSION_ID, running_bind["session_id"])

        self.monotonic = 120.0
        self.wall = datetime(2026, 9, 7, 19, 50, 20, tzinfo=timezone.utc)
        status, renewed = self.gateway.child_time_session_heartbeat(
            self.client, "kid", SESSION_ID)

        self.assertEqual(HTTPStatus.OK, status)
        self.assertEqual("running", renewed["phase"])
        bridge_session_calls = [
            call for call in self.bridge.calls
            if call[0] == "json" and call[2] == "/child/session"
        ]
        self.assertEqual(["bind", "bind", "renew"], [
            call[3]["action"] for call in bridge_session_calls])
        renew = bridge_session_calls[-1][3]
        self.assertEqual(SESSION_ID, renew["session_id"])
        self.assertEqual("kid", renew["actor_profile_id"])
        self.assertEqual("living-room", renew["execution_profile_id"])
        self.assertEqual(GAME_ID, renew["game_id"])
        self.assertEqual(process_token, renew["process_token"])


        graceful = []

        def close_verified_process(pid):
            graceful.append(pid)
            return True

        self.bridge.state.graceful_close = close_verified_process
        self.monotonic = 125.0
        self.wall = datetime(2026, 9, 7, 19, 50, 25, tzinfo=timezone.utc)
        initial_status, ended = self.gateway.child_time_session_end(
            self.client, "kid", SESSION_ID)

        self.assertEqual(SESSION_ID, ended["session_id"])
        self.assertEqual(HTTPStatus.CONFLICT, initial_status)
        self.assertEqual("cleanup_required", ended["state"])
        self.assertTrue(ended["cleanup_required"])
        self.assertEqual(1, len([
            call for call in self.bridge.calls
            if call[0] == "json" and call[2] == "/child/session"
            and call[3]["action"] == "end"
        ]))
        self.assertEqual([process_id], graceful)

        # Graceful close is asynchronous.  Let the Bridge observe that the
        # exact process disappeared after its grace period, then run its real
        # cleanup path before retrying the Gateway end request.
        self.monotonic = 136.0
        self.wall = datetime(2026, 9, 7, 19, 50, 36, tzinfo=timezone.utc)
        self.bridge.state.current = {"state": "idle"}
        self.bridge.state._running_games = []
        self.bridge.state._running_scan_status = "complete"
        self.bridge.state._running_scan_revision = self.bridge.state.library_revision
        self.bridge.state._running_scan_at = time.monotonic()
        probe.process_identity.side_effect = lambda pid, include_owner=False: (
            dict(own_identity) if pid == os.getpid() else None)
        probe.scan_running_processes.return_value = ([], "complete")
        stopped = self.bridge.state.enforce_child_session()
        self.assertTrue(stopped["accepted"])
        self.assertEqual("stopped", stopped["state"])
        self.assertFalse(stopped["cleanup_required"])
        self.assertIsNone(self.bridge.state.child_session)

        status, finished = self.gateway.child_time_session_end(
            self.client, "kid", SESSION_ID)
        self.assertEqual(HTTPStatus.OK, status, repr(finished))
        self.assertEqual("ended", finished["state"])
        self.assertEqual(SESSION_ID, finished["session_id"])
        self.assertIsNone(self.gateway.child_time_session)

        # A new launch receives a fresh allowance on the same reusable stream.
        next_id = "33333333-3333-4333-8333-333333333333"
        status, restarted = self.gateway.child_time_session_start(
            self.client, "kid", GAME_ID, next_id, "next-launch")
        self.assertEqual(HTTPStatus.ACCEPTED, status, repr(restarted))
        self.assertEqual(next_id, restarted["session_id"])
        self.assertEqual("launch_pending", restarted["phase"])
        self.gateway.child_time_session_end(self.client, "kid", SESSION_ID)
        self.assertEqual(next_id, self.gateway.child_time_session["session_id"])

        end_calls = [
            call for call in self.bridge.calls
            if call[0] == "json" and call[2] == "/child/session"
            and call[3]["action"] == "end"
        ]
        self.assertEqual(2, len(end_calls))
        self.assertTrue(all(call[3]["session_id"] == SESSION_ID
                             for call in end_calls))


if __name__ == "__main__":
    unittest.main()
