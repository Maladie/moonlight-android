import base64
import hashlib
import json
import tempfile
import re
import threading
import time
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

    def _slow_events(self, recorder):
        return [call for call in recorder.record.call_args_list
                if call.args and call.args[0] == "observation-slow"]

    def test_slow_observation_records_every_phase_and_the_request_id(self):
        # Lab formal132/diag156a: an observation read that outlives the Android client's 5 s
        # timeout right after a game start; the Gateway now says where the time went.
        self.state.request_context.profile_id = "kid"
        self.state.request_context.actor_profile_id = "kid"
        self.state.request_context.execution_profile_id = "parent"
        self.state.request_context.request_id = "request-9"
        recorder = mock.Mock()
        blocker_ready = threading.Event()

        def slow_provider(*args, **kwargs):
            # Another request holds the Gateway lock while the provider call returns, so the
            # second lock acquisition is the slow phase.
            def hold():
                with self.state.lock:
                    blocker_ready.set()
                    time.sleep(0.3)
            threading.Thread(target=hold).start()
            blocker_ready.wait(2)
            return True, {"game": {"state": "idle"}}

        self.state.proxy = mock.Mock(side_effect=slow_provider)
        with mock.patch.object(wakeplay_gateway, "DIAGNOSTICS", recorder),                 mock.patch.object(wakeplay_gateway, "OBSERVATION_SLOW_MS", 200):
            status, _ = self.state.playnite_observation(1, "")
            self.assertEqual(200, status)
            slow = self._slow_events(recorder)
            self.assertEqual(1, len(slow))
            match = re.fullmatch(r"lock1-(\d+)-provider-(\d+)-lock2-(\d+)-inflight-1",
                                 slow[0].kwargs["reason"])
            self.assertIsNotNone(match)
            self.assertGreaterEqual(int(match.group(3)), 200)
            self.assertLess(int(match.group(1)), 200)
            self.assertEqual("request-9", slow[0].kwargs["request_id"])
            self.assertGreaterEqual(slow[0].kwargs["duration_ms"], 200)
            recorder.reset_mock()
            self.state.proxy = mock.Mock(return_value=(True, {"game": {"state": "idle"}}))
            with mock.patch.object(wakeplay_gateway, "OBSERVATION_SLOW_MS", 60_000):
                self.state.playnite_observation(2, "")
            self.assertFalse(self._slow_events(recorder))
        self.assertEqual(0, self.state.observation_inflight)

    def test_lock_reports_a_long_outermost_hold_and_its_site(self):
        recorder = mock.Mock()
        lock = wakeplay_gateway.TimedRLock()
        condition = threading.Condition(lock)
        with mock.patch.object(wakeplay_gateway, "DIAGNOSTICS", recorder),                 mock.patch.object(wakeplay_gateway, "LOCK_HELD_SLOW_MS", 50):
            with lock:
                with lock:  # a nested hold is part of the outermost one
                    time.sleep(0.08)
            held = [call for call in recorder.record.call_args_list
                    if call.args and call.args[0] == "lock-held-slow"]
            self.assertEqual(1, len(held))
            self.assertRegex(
                held[0].kwargs["reason"],
                r"^holder-test_lock_reports_a_long_outermost_hold_and_its_site-L[0-9]+$")
            self.assertGreaterEqual(held[0].kwargs["duration_ms"], 50)
            recorder.reset_mock()
            with lock:  # fast hold: silent
                pass
            self.assertFalse(recorder.record.called)
            # A Condition built on the lock still waits, notifies and stays balanced.
            with condition:
                self.assertFalse(condition.wait(0.01))
            self.assertFalse(lock._is_owned())
            self.assertEqual(0, lock._depth)

    def test_hold_before_a_condition_wait_is_reported_and_the_wait_is_not(self):
        recorder = mock.Mock()
        lock = wakeplay_gateway.TimedRLock()
        condition = threading.Condition(lock)
        with mock.patch.object(wakeplay_gateway, "DIAGNOSTICS", recorder),                 mock.patch.object(wakeplay_gateway, "LOCK_HELD_SLOW_MS", 50):
            with condition:
                time.sleep(0.08)          # work under the lock, then a wait
                condition.wait(0.2)       # the wait itself must not be counted
            held = [call for call in recorder.record.call_args_list
                    if call.args and call.args[0] == "lock-held-slow"]
            self.assertEqual(1, len(held))
            self.assertRegex(
                held[0].kwargs["reason"],
                r"^holder-test_hold_before_a_condition_wait_is_reported_and_the_wait_is_not"
                r"-L[0-9]+$")
            self.assertGreaterEqual(held[0].kwargs["duration_ms"], 50)
            self.assertLess(held[0].kwargs["duration_ms"], 200)
        self.assertEqual(0, lock._depth)

    def test_invalid_release_leaves_the_lock_bookkeeping_alone(self):
        lock = wakeplay_gateway.TimedRLock()
        with self.assertRaises(RuntimeError):
            lock.release()
        self.assertEqual(0, lock._depth)
        with self.assertRaises(RuntimeError):
            lock._release_save()
        self.assertEqual(0, lock._depth)
        with lock:
            self.assertEqual(1, lock._depth)
            other = []

            def foreign_release():
                try:
                    lock.release()
                except RuntimeError as error:
                    other.append(error)
            thread = threading.Thread(target=foreign_release)
            thread.start()
            thread.join()
            self.assertEqual(1, len(other))
            self.assertEqual(1, lock._depth)
        self.assertEqual(0, lock._depth)

    def _retained_stream(self):
        return {"client_id": "tv", "execution_profile_id": "parent", "generation": 4,
                "stream_session_id": "session-4", "actor_lease_epoch": 4}

    def _idle_reconcile_setup(self, probe):
        self.state.control_state["active"] = None
        self.state.control_state["stream"] = self._retained_stream()
        self.state.child_time_session = None
        self.state.proxy = mock.Mock(side_effect=probe)
        self.state._child_time_current_game = mock.Mock(return_value={"kind": "idle"})
        self.state._control_save_locked = mock.Mock()

    def test_housekeeping_reconciles_an_idle_stream_without_holding_the_lock(self):
        # Lab formal134/diag160a: the 5 s tick probed the streamer under the Gateway lock,
        # holding it for 0.5-1.5 s (up to the 8 s timeout) and stalling authentication and
        # observation reads.
        held = []

        def probe(*args, **kwargs):
            held.append(self.state.lock._is_owned())
            return True, {"snapshot": "idle"}

        self._idle_reconcile_setup(probe)
        with mock.patch.object(wakeplay_gateway.ControlOperationPolicy, "streamer_idle",
                               return_value=True):
            self.state.child_time_housekeeping_tick()
        self.assertEqual([False], held)
        self.assertIsNone(self.state.control_state["stream"])
        self.assertEqual("session-4", self.state.control_state["idle_stream"]["stream_session_id"])
        self.state._control_save_locked.assert_called_once()

    def test_reconcile_is_abandoned_when_an_operation_starts_during_the_probe(self):
        def probe(*args, **kwargs):
            with self.state.lock:
                self.state.control_state["active"] = {"operation_id": "control-1"}
            return True, {}

        self._idle_reconcile_setup(probe)
        with mock.patch.object(wakeplay_gateway.ControlOperationPolicy, "streamer_idle",
                               return_value=True):
            self.assertFalse(self.state._control_reconcile_idle())
        self.assertEqual("session-4", self.state.control_state["stream"]["stream_session_id"])
        self.state._control_save_locked.assert_not_called()

    def test_reconcile_is_abandoned_when_an_operation_starts_and_finishes_during_the_probe(self):
        # The permit is gone and the retained stream is equal again, but the operation advanced
        # a control counter, so the pre-operation idle reading must not retire the stream.
        def probe(*args, **kwargs):
            with self.state.lock:
                self.state.control_state["provider_sequence"] = int(
                    self.state.control_state.get("provider_sequence") or 0) + 1
                self.state.control_state["active"] = {"operation_id": "provider-1"}
                self.state.control_state["active"] = None
            return True, {}

        self._idle_reconcile_setup(probe)
        with mock.patch.object(wakeplay_gateway.ControlOperationPolicy, "streamer_idle",
                               return_value=True):
            self.assertFalse(self.state._control_reconcile_idle())
        self.assertEqual("session-4", self.state.control_state["stream"]["stream_session_id"])
        self.state._control_save_locked.assert_not_called()

    def test_reconcile_is_abandoned_when_a_stop_or_start_advanced_the_lease(self):
        def probe(*args, **kwargs):
            with self.state.lock:
                self.state.control_state["generation"] = int(
                    self.state.control_state.get("generation") or 0) + 1
                self.state.control_state["actor_lease_epoch"] = int(
                    self.state.control_state.get("actor_lease_epoch") or 0) + 1
            return True, {}

        self._idle_reconcile_setup(probe)
        with mock.patch.object(wakeplay_gateway.ControlOperationPolicy, "streamer_idle",
                               return_value=True):
            self.assertFalse(self.state._control_reconcile_idle())
        self.assertIsNotNone(self.state.control_state["stream"])
        self.state._control_save_locked.assert_not_called()

    def test_reconcile_is_abandoned_when_the_stream_is_replaced_during_the_probe(self):
        def probe(*args, **kwargs):
            with self.state.lock:
                self.state.control_state["stream"] = {
                    **self._retained_stream(), "generation": 5,
                    "stream_session_id": "session-5"}
            return True, {}

        self._idle_reconcile_setup(probe)
        with mock.patch.object(wakeplay_gateway.ControlOperationPolicy, "streamer_idle",
                               return_value=True):
            self.assertFalse(self.state._control_reconcile_idle())
        self.assertEqual("session-5", self.state.control_state["stream"]["stream_session_id"])
        self.state._control_save_locked.assert_not_called()

    def test_reconcile_keeps_a_stream_that_is_not_idle(self):
        self._idle_reconcile_setup(lambda *a, **k: (True, {}))
        with mock.patch.object(wakeplay_gateway.ControlOperationPolicy, "streamer_idle",
                               return_value=False):
            self.assertFalse(self.state._control_reconcile_idle())
        self.state._child_time_current_game.assert_not_called()
        self.assertIsNotNone(self.state.control_state["stream"])
        self.state._control_save_locked.assert_not_called()

    def test_observation_in_flight_count_survives_a_failing_read(self):
        self.state.request_context.actor_profile_id = "kid"
        self.state.request_context.execution_profile_id = "parent"
        self.state.proxy = mock.Mock(return_value=(True, {"game": {"state": "idle"}}))
        with mock.patch.object(self.state, "_observation_control_locked",
                               side_effect=ValueError("malformed control record")):
            with self.assertRaises(ValueError):
                self.state.playnite_observation(1, "")
        self.assertEqual(0, self.state.observation_inflight)
        self.state.proxy = mock.Mock(side_effect=RuntimeError("provider failed"))
        with self.assertRaises(RuntimeError):
            self.state.playnite_observation(2, "")
        self.assertEqual(0, self.state.observation_inflight)

    def test_unshared_artwork_is_denied_but_common_observation_stays_visible(self):
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
            "game": {"state": "running", "id": "steam:999"},
            "process": {"evidence": "PENDING", "process_id": 0,
                        "process_token": "", "running_games": []},
        }))
        self.state.request_context.profile_id = "kid"
        self.state.request_context.actor_profile_id = "kid"
        self.state.request_context.execution_profile_id = "parent"
        status, current = self.state.playnite_observation(1, "")
        self.assertEqual(200, status)
        self.assertEqual("running", current["game"]["state"])
        self.assertEqual("steam:999", current["game"]["id"])
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
        self.state.child_time_session = {
            "session_id": "01234567-89ab-cdef-0123-456789abcdef",
            "client_id": "tv", "actor_profile_id": "kid",
            "execution_profile_id": "parent", "game_id": "steam:123",
        }
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
