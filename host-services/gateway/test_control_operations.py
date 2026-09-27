import json
import tempfile
import threading
import unittest
from unittest.mock import patch
from control_snapshot_fixture import control_evidence
from pathlib import Path

import wakeplay_gateway
from control_operation_policy import ControlOperationPolicy
from provider_control_operation import StreamerControlOperation
from wakeplay_gateway import GatewayState, sha256_text


VIBE_UUID = "01234567-89ab-cdef-0123-456789abcdef"


class ControlOperationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temp_dir.name) / "gateway.json"
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "profiles": {"parent": {"id": "parent", "kind": "standard",
                                      "name": "Parent", "enabled": True}},
            "clients": [{"id": "tv", "token_sha256": sha256_text("token"),
                         "profile_grants": {"parent": ["use_profile"]},
                         "vibepollo_client_uuids": {"parent": VIBE_UUID}}],
        }), encoding="utf-8")
        self.state = GatewayState(self.config_path, None)
        self.state.proxy = lambda *_args, **_kwargs: (False, {"error": "fixture_offline"})
        self.client = self.state.client_for_token("token")
        self.authorize()

    def tearDown(self):
        self.temp_dir.cleanup()

    def authorize(self):
        self.state.authorize_profile(self.client, "parent")

    @staticmethod
    def request(operation_id, intent="launch", session="stream-1", app_id=7,
                expected_generation=0, observed_app_id=None):
        if observed_app_id is None:
            observed_app_id = app_id if intent in {
                "resume", "reconnect", "replacement", "full_quit",
                "child_terminal", "hard_reset"} else 0
        return {"operation_id": operation_id, "intent": intent,
                "stream_session_id": session, "sunshine_app_id": app_id,
                "expected_generation": expected_generation,
                "observed_sunshine_app_id": observed_app_id,
                "child_session_id": ""}

    def acquire(self, body, wait=20.0):
        self.authorize()
        return self.state.control_operation_acquire(
            self.client, body, body["operation_id"], wait)

    def complete(self, permit, succeeded=True):
        self.authorize()
        body = {"operation_id": permit["operation_id"],
                "generation": permit["generation"],
                "actor_lease_epoch": permit["actor_lease_epoch"],
                "succeeded": succeeded}
        return self.state.control_operation_complete(
            self.client, body, permit["operation_id"])

    def observe_provider_idle(self):
        self.state.proxy = lambda name, path, **_kwargs: (
            (True, {"game": {"state": "idle", "id": "", "evidence": "ABSENT"},
                    "process": {"evidence": "ABSENT", "running_games": [],
                                "running_scan_status": "complete"}})
            if name == "game_provider" and path == "/observation"
            else (False, {"error": "fixture_offline"}))

    def observe_exact_provider_game(self, game_id="steam:7"):
        token = "a" * 64
        self.state.control_state["stream"].update({
            "provider_game_id": game_id,
            "process_token": token,
            "process_id": 77,
        })
        self.state.proxy = lambda name, path, **_kwargs: (
            (True, {"game": {"state": "running", "id": game_id,
                             "evidence": "VERIFIED"},
                    "process": {"evidence": "VERIFIED",
                                "running_scan_status": "complete",
                                "running_games": [{"game_id": game_id,
                                                   "process_id": 77,
                                                   "process_token": token}]}})
            if name == "game_provider" and path == "/observation"
            else (False, {"error": "fixture_offline"}))
        return token

    def test_second_start_waits_for_terminal_result(self):
        status, first = self.acquire(self.request("op-1"))
        self.assertEqual(200, status)
        entered = threading.Event()
        finished = threading.Event()
        result = []

        def second():
            entered.set()
            result.append(self.acquire(self.request("op-2", session="stream-2")))
            finished.set()

        thread = threading.Thread(target=second)
        thread.start()
        self.assertTrue(entered.wait(1.0))
        self.assertFalse(finished.is_set())
        self.complete(first)
        self.assertTrue(finished.wait(1.0))
        thread.join()
        self.assertEqual(409, result[0][0])
        self.assertEqual("managed_stream_conflict", result[0][1]["reason"])

    def test_retry_cannot_reauthorize_or_reconcile_a_stalled_effect(self):
        status, first = self.acquire(self.request("stalled"))
        self.assertEqual(200, status)
        status, next_play = self.acquire(self.request(
            "next", session="stream-2", observed_app_id=0), 0)
        self.assertEqual(409, status)
        self.assertEqual("terminal_result_timeout", next_play["reason"])
        status, retry = self.acquire(self.request("stalled"), 0)
        self.assertEqual(409, status)
        self.assertEqual("terminal_result_timeout", retry["reason"])
        self.authorize()
        status, reconcile = self.state.control_operation_reconcile(
            self.client, {"operation_id": "stalled",
                          "observed_sunshine_app_id": 7}, "stalled")
        self.assertEqual(409, status)
        self.assertEqual("terminal_owner_required", reconcile["reason"])

        self.complete(first)
        status, after_terminal = self.acquire(self.request(
            "next", session="stream-2", observed_app_id=0), 0)
        self.assertEqual(409, status)
        self.assertEqual("managed_stream_conflict", after_terminal["reason"])

    def test_lost_response_survives_restart_and_reconciles_same_operation(self):
        _status, permit = self.acquire(self.request("op-lost"))
        restarted = GatewayState(self.config_path, None)
        client = restarted.client_for_token("token")
        restarted.authorize_profile(client, "parent")
        status, blocked = restarted.control_operation_acquire(
            client, self.request("op-new", session="stream-2"), "op-new", 0)
        self.assertEqual(409, status)
        self.assertEqual("uncertain", blocked["state"])
        status, reconciled = restarted.control_operation_reconcile(
            client, {"operation_id": permit["operation_id"],
                     "observed_sunshine_app_id": 7}, permit["operation_id"])
        self.assertEqual(409, status)
        self.assertEqual("terminal_owner_required", reconciled["reason"])
        status, terminal = restarted.control_operation_complete(client, {
            "operation_id": permit["operation_id"],
            "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"],
            "succeeded": True,
        }, permit["operation_id"])
        self.assertEqual(200, status)
        self.assertEqual("succeeded", terminal["state"])
        restarted.authorize_profile(client, "parent")
        status, replay = restarted.control_operation_complete(client, {
            "operation_id": permit["operation_id"],
            "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"],
            "succeeded": True,
        }, permit["operation_id"])
        self.assertEqual(200, status)
        self.assertEqual(reconciled["generation"], replay["generation"])

    def test_stale_generation_cannot_stop_new_stream(self):
        _status, start = self.acquire(self.request("start-1"))
        self.complete(start)
        stale = self.request("stop-stale", "full_quit",
                             expected_generation=start["generation"] - 1)
        status, result = self.acquire(stale, 0)
        self.assertEqual(409, status)
        self.assertEqual("unmanaged_stream", result["reason"])
        self.assertEqual(start["generation"],
                         self.state.control_state["stream"]["generation"])

    def test_failed_effect_is_sticky_uncertain_until_observed(self):
        _status, permit = self.acquire(self.request("op-failed"))
        status, failed = self.complete(permit, False)
        self.assertEqual(409, status)
        self.assertEqual("uncertain", failed["state"])
        status, blocked = self.acquire(self.request("op-new", session="stream-2"), 0)
        self.assertEqual(409, status)
        self.assertEqual("uncertain", blocked["state"])

    def test_definitive_launch_failure_releases_fence_for_next_play(self):
        _status, permit = self.acquire(self.request("op-rejected"))
        status, failed = self.state.control_operation_complete(self.client, {
            "operation_id": permit["operation_id"],
            "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"],
            "succeeded": False,
            "effect_result": {"outcome": "definitive_failure"},
        }, permit["operation_id"])
        self.assertEqual(200, status)
        self.assertEqual("failed", failed["state"])
        self.assertEqual("effect_rejected", failed["reason"])
        self.assertIsNone(self.state.control_state["active"])

        status, next_play = self.acquire(self.request(
            "op-next", session="stream-2", observed_app_id=0), 0)
        self.assertEqual(200, status)
        self.assertEqual("permitted", next_play["state"])

    def test_operation_id_reuse_cannot_change_binding(self):
        _status, permit = self.acquire(self.request("same"))
        self.complete(permit)
        status, replay = self.acquire(self.request("same", observed_app_id=7), 0)
        self.assertEqual(200, status)
        self.assertEqual("succeeded", replay["state"])
        status, result = self.acquire(self.request("same", app_id=8), 0)
        self.assertEqual(409, status)
        self.assertEqual("operation_id_reused", result["reason"])

    def test_immediate_start_waits_for_exact_stop_terminal(self):
        _status, start = self.acquire(self.request("start-old"))
        self.complete(start)
        self.observe_provider_idle()
        _status, stop = self.acquire(self.request(
            "stop-old", "full_quit", expected_generation=start["generation"]))
        finished = threading.Event()
        result = []

        def next_start():
            result.append(self.acquire(self.request(
                "start-next", session="stream-2", observed_app_id=7)))
            finished.set()

        thread = threading.Thread(target=next_start)
        thread.start()
        self.assertFalse(finished.wait(0.05))
        self.complete(stop)
        self.assertTrue(finished.wait(1.0))
        thread.join()
        self.assertEqual(200, result[0][0])
        self.assertEqual("permitted", result[0][1]["state"])

    def test_unmanaged_observed_stream_is_rejected_before_permit(self):
        status, result = self.acquire(self.request(
            "unmanaged", "replacement", observed_app_id=99), 0)

        self.assertEqual(409, status)
        self.assertEqual("unmanaged_stream", result["reason"])

    def test_child_replacement_can_reuse_parent_stream_on_same_tv_and_execution(self):
        _status, parent = self.acquire(self.request("parent-stream"))
        self.complete(parent)
        self.state.config["profiles"]["kid"] = {
            "id": "kid", "kind": "child", "enabled": True,
            "parent_profile_id": "parent"}
        self.client["profile_grants"]["kid"] = ["use_profile"]
        child_session_id = "01234567-89ab-cdef-0123-456789abcdef"
        self.state.child_time_session = {
            "session_id": child_session_id, "client_id": "tv",
            "actor_profile_id": "kid", "execution_profile_id": "parent"}
        self.state.authorize_child_profile(self.client, "kid")

        status, replacement = self.state.control_operation_acquire(self.client, {
            "operation_id": "child-replacement", "intent": "replacement",
            "stream_session_id": "child-stream", "sunshine_app_id": 7,
            "expected_generation": parent["generation"],
            "observed_sunshine_app_id": 7,
            "child_session_id": child_session_id,
        }, "child-replacement", 0)

        self.assertEqual(200, status, replacement)
        self.assertEqual("permitted", replacement["state"])

    def test_child_replacement_rejects_other_tv_or_execution_owner(self):
        for owner_client, owner_execution in (("other-tv", "parent"),
                                              ("tv", "other-parent")):
            with self.subTest(owner_client=owner_client,
                              owner_execution=owner_execution):
                self.state.control_state.update({
                    "active": None, "generation": 4, "actor_lease_epoch": 4,
                    "stream": {
                        "client_id": owner_client, "actor_profile_id": "parent",
                        "execution_profile_id": owner_execution,
                        "stream_session_id": "parent-stream", "sunshine_app_id": 7,
                        "generation": 4, "actor_lease_epoch": 4,
                        "child_session_id": "",
                    },
                })
                self.state.config["profiles"]["kid"] = {
                    "id": "kid", "kind": "child", "enabled": True,
                    "parent_profile_id": "parent"}
                self.client["profile_grants"]["kid"] = ["use_profile"]
                child_session_id = "01234567-89ab-cdef-0123-456789abcdef"
                self.state.child_time_session = {
                    "session_id": child_session_id, "client_id": "tv",
                    "actor_profile_id": "kid", "execution_profile_id": "parent"}
                self.state.authorize_child_profile(self.client, "kid")
                operation_id = "replacement-" + owner_client + owner_execution

                status, result = self.state.control_operation_acquire(self.client, {
                    "operation_id": operation_id, "intent": "replacement",
                    "stream_session_id": "child-stream", "sunshine_app_id": 7,
                    "expected_generation": 4, "observed_sunshine_app_id": 7,
                    "child_session_id": child_session_id,
                }, operation_id, 0)

                self.assertEqual(409, status)
                self.assertEqual("managed_stream_conflict", result["reason"])

    def test_hard_reset_blocks_new_launch_until_stream_close_finishes(self):
        _status, parent = self.acquire(self.request("parent-before-reset"))
        self.complete(parent)
        provider_entered = threading.Event()
        release_provider = threading.Event()
        launch_finished = threading.Event()
        reset_result = []
        launch_result = []

        def provider(*_args, **_kwargs):
            provider_entered.set()
            release_provider.wait(2.0)
            return True, {"accepted": True, "stopped_count": 1}

        def launch_after_reset():
            launch_result.append(self.acquire(self.request(
                "after-reset", session="stream-after-reset", observed_app_id=0)))
            launch_finished.set()

        self.state.proxy_json = provider
        self.state.proxy = lambda _name, path, **_kwargs: (
            True, {**control_evidence(), "clients": [], "apps": []}
            if path == "/snapshot?force=1" else {"ok": True})
        reset = threading.Thread(target=lambda: reset_result.append(
            self.state.hard_reset_session({"force": True}, "tv", "parent")))
        reset.start()
        self.assertTrue(provider_entered.wait(1.0))
        launch = threading.Thread(target=launch_after_reset)
        launch.start()
        self.assertFalse(launch_finished.wait(0.05))
        self.assertEqual("hard_reset", self.state.control_state["active"]["intent"])

        release_provider.set()
        reset.join(2.0)
        launch.join(2.0)
        self.assertEqual(200, reset_result[0][0])
        self.assertEqual(200, launch_result[0][0])
        self.assertEqual("permitted", launch_result[0][1]["state"])

    def test_partial_hard_reset_reports_recovery_and_releases_control_barrier(self):
        _status, parent = self.acquire(self.request("parent-before-failed-reset"))
        self.complete(parent)
        self.state.proxy_json = lambda *_args, **_kwargs: (_ for _ in ()).throw(
            OSError("provider unavailable"))
        streamer_calls = []

        def streamer(_name, path, **_kwargs):
            streamer_calls.append(path)
            if path == "/snapshot?force=1":
                return True, {**control_evidence(), "clients": [], "apps": []}
            return True, {"ok": True}

        self.state.proxy = streamer

        status, result = self.state.hard_reset_session(
            {"force": True}, "tv", "parent")

        self.assertEqual(200, status)
        self.assertTrue(result["accepted"])
        self.assertTrue(result["recovered"])
        self.assertFalse(result["cleanup_confirmed"])
        self.assertTrue(result["session_close_requested"])
        self.assertEqual(["/action/close-app", "/snapshot?force=1"], streamer_calls)
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual("succeeded", self.state.control_state["completed"][
            "hard-reset:2:2"]["state"])

    def test_second_hard_reset_cannot_supersede_inflight_reset(self):
        _status, parent = self.acquire(self.request("parent-before-two-resets"))
        self.complete(parent)
        provider_entered = threading.Event()
        release_provider = threading.Event()
        provider_calls = []
        first_result = []

        def provider(*_args, **_kwargs):
            provider_calls.append("hard-reset")
            provider_entered.set()
            release_provider.wait(2.0)
            return True, {"accepted": True, "stopped_count": 1}

        self.state.proxy_json = provider
        self.state.proxy = lambda _name, path, **_kwargs: (
            True, {**control_evidence(), "clients": [], "apps": []}
            if path == "/snapshot?force=1" else {"ok": True})
        first = threading.Thread(target=lambda: first_result.append(
            self.state.hard_reset_session({"force": True}, "tv", "parent")))
        first.start()
        self.assertTrue(provider_entered.wait(1.0))

        status, second = self.state.hard_reset_session(
            {"force": True}, "tv", "parent")

        self.assertEqual(409, status)
        self.assertEqual("hard_reset_in_progress", second["reason"])
        self.assertEqual(1, len(provider_calls))
        release_provider.set()
        first.join(2.0)
        self.assertEqual(200, first_result[0][0])
        status, launch = self.acquire(self.request(
            "after-two-resets", session="stream-after-two-resets",
            observed_app_id=0))
        self.assertEqual(200, status, launch)
        self.assertEqual("permitted", launch["state"])

    def test_gateway_restart_retries_persisted_hard_reset_and_unblocks_play(self):
        _status, parent = self.acquire(self.request("parent-before-reset-crash"))
        self.complete(parent)
        self.state.proxy_json = lambda *_args, **_kwargs: (_ for _ in ()).throw(
            SystemExit("simulated process termination"))
        with self.assertRaises(SystemExit):
            self.state.hard_reset_session({"force": True}, "tv", "parent")
        persisted = dict(self.state.control_state["active"])

        restarted = GatewayState(self.config_path, None)
        self.assertEqual("uncertain", restarted.control_state["active"]["state"])
        restarted.proxy_json = lambda *_args, **_kwargs: (
            True, {"accepted": True, "stopped_count": 1})
        restarted.proxy = lambda _name, path, **_kwargs: (
            True, {**control_evidence(), "clients": [], "apps": []}
            if path == "/snapshot?force=1" else {"ok": True})

        status, recovered = restarted.hard_reset_session(
            {"force": True}, "tv", "parent")

        self.assertEqual(200, status, recovered)
        self.assertTrue(recovered["accepted"])
        self.assertIsNone(restarted.control_state["active"])
        terminal = restarted.control_state["completed"][persisted["operation_id"]]
        self.assertEqual(persisted["generation"], terminal["generation"])
        self.assertEqual(persisted["actor_lease_epoch"],
                         terminal["actor_lease_epoch"])
        client = restarted.client_for_token("token")
        restarted.authorize_profile(client, "parent")
        status, launch = restarted.control_operation_acquire(client, self.request(
            "after-reset-crash", session="stream-after-reset-crash",
            observed_app_id=0), "after-reset-crash", 0)
        self.assertEqual(200, status, launch)
        self.assertEqual("permitted", launch["state"])

    def test_provider_effect_carries_exact_generation_and_actor_epoch(self):
        _status, launch = self.acquire(self.request("transport"))
        self.complete(launch)
        calls = []
        self.state.proxy_json = lambda name, path, body, **kwargs: (
            calls.append((name, path, body)) or (True, {"accepted": True}))
        self.authorize()

        status, result = self.state.playnite_action(
            "game/start", {"game_id": "steam:7"}, self.client,
            "parent", "provider-start")

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        binding = calls[0][2]["control_operation"]
        self.assertEqual(binding["generation"], launch["generation"])
        self.assertEqual(binding["actor_lease_epoch"], launch["actor_lease_epoch"])
        self.assertGreater(binding["effect_sequence"], 0)
        self.assertEqual("parent", binding["actor_profile_id"])
        self.assertEqual("parent", binding["execution_profile_id"])
        self.assertEqual("stream-1", binding["stream_session_id"])

    def test_completed_provider_request_replays_saved_result_without_effect(self):
        _status, launch = self.acquire(self.request("transport"))
        self.complete(launch)
        calls = []
        self.state.proxy_json = lambda name, path, body, **kwargs: (
            calls.append((name, path, body)) or
            (True, {"accepted": True, "command": "launch"}))
        self.authorize()

        first = self.state.playnite_action(
            "game/start", {"game_id": "steam:7"}, self.client,
            "parent", "provider-replay")
        replay = self.state.playnite_action(
            "game/start", {"game_id": "steam:7"}, self.client,
            "parent", "provider-replay")

        self.assertEqual(first, replay)
        self.assertEqual(1, len(calls))

        restarted = GatewayState(self.config_path, None)
        restarted_client = restarted.client_for_token("token")
        restarted.authorize_profile(restarted_client, "parent")
        restarted.proxy_json = lambda *_args, **_kwargs: self.fail(
            "completed provider replay dispatched after restart")
        self.assertEqual(first, restarted.playnite_action(
            "game/start", {"game_id": "steam:7"}, restarted_client,
            "parent", "provider-replay"))
        status, changed = restarted.playnite_action(
            "game/start", {"game_id": "steam:8"}, restarted_client,
            "parent", "provider-replay")
        self.assertEqual(409, status)
        self.assertEqual("control_operation_required", changed["error"])

    def test_provider_stop_can_join_exact_outer_terminal_operation(self):
        _status, launch = self.acquire(self.request("transport"))
        self.complete(launch)
        token = self.observe_exact_provider_game()
        _status, terminal = self.acquire(self.request(
            "terminal", "full_quit", expected_generation=launch["generation"]))
        calls = []
        self.state.proxy_json = lambda name, path, body, **kwargs: (
            calls.append(body) or (True, {"accepted": True}))
        self.authorize()
        status, result = self.state.playnite_action("game/stop-verified", {
            "game_id": "steam:7",
            "expected_process_token": token,
            "control_operation": {
                "operation_id": terminal["operation_id"],
                "generation": terminal["generation"],
                "actor_lease_epoch": terminal["actor_lease_epoch"],
            },
        }, self.client, "parent", "provider-stop")
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(terminal["generation"],
                         calls[0]["control_operation"]["generation"])
        self.assertEqual("permitted", self.state.control_state["active"]["state"])

    def test_singleton_vibepollo_snapshot_preserves_idle_and_exact_close(self):
        stream = {"vibepollo_client_uuid": VIBE_UUID, "sunshine_app_id": 7}
        self.assertTrue(ControlOperationPolicy.streamer_idle({
            **control_evidence(),
            "clients": {"uuid": VIBE_UUID, "connected": False},
            "apps": {"app_id": 7, "running": False},
        }))
        self.assertTrue(ControlOperationPolicy.streamer_close_allowed({
            **control_evidence(1, True),
            "clients": {"uuid": VIBE_UUID, "connected": True},
            "apps": {"app_id": 7, "running": True},
        }, stream))

    def test_unavailable_or_contradictory_snapshot_is_not_control_evidence(self):
        stream = {"vibepollo_client_uuid": VIBE_UUID, "sunshine_app_id": 7}
        idle = {**control_evidence(), "clients": [], "apps": []}
        self.assertFalse(ControlOperationPolicy.streamer_idle({**idle, "control_fresh": False}))
        for session in ({"active": True, "count": 1, "app_running": False},
                        {"active": False, "count": 0, "app_running": True}, {}):
            self.assertFalse(ControlOperationPolicy.streamer_idle({**idle, "session": session}))
        owned = {**control_evidence(1, True), "clients": [{"uuid": VIBE_UUID, "connected": True}],
                 "apps": [{"app_id": 7, "running": True}]}
        self.assertFalse(ControlOperationPolicy.streamer_close_allowed(
            {**owned, "control_fresh": False}, stream))
        self.assertFalse(ControlOperationPolicy.streamer_close_allowed(
            {**owned, "apps": [{"app_id": 99, "running": True}]}, stream))
        self.assertFalse(ControlOperationPolicy.streamer_close_allowed(
            {**owned, "apps": None}, stream))

    def test_streamer_close_ack_requires_physical_idle_postcondition(self):
        stream = {"vibepollo_client_uuid": VIBE_UUID, "sunshine_app_id": 7}
        active = {**control_evidence(1, True),
                  "clients": [{"uuid": VIBE_UUID, "connected": True}],
                  "apps": [{"app_id": 7, "running": True}]}
        calls = []
        clock = [0.0]

        def proxy(_name, path, **_kwargs):
            calls.append(path)
            if path == "/action/close-app":
                return True, {"ok": True}
            return True, active

        with patch("provider_control_operation.time.monotonic",
                   side_effect=lambda: clock[0]), \
                patch("provider_control_operation.time.sleep",
                      side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            closed, reason = StreamerControlOperation.close(proxy, "parent", stream)
        self.assertFalse(closed)
        self.assertEqual("streamer_close_unconfirmed", reason)
        self.assertEqual(1, calls.count("/action/close-app"))
        self.assertGreater(calls.count("/snapshot?force=1"), 1)

    def test_streamer_close_ack_waits_until_physical_idle(self):
        stream = {"vibepollo_client_uuid": VIBE_UUID, "sunshine_app_id": 7}
        active = {**control_evidence(1, True),
                  "clients": [{"uuid": VIBE_UUID, "connected": True}],
                  "apps": [{"app_id": 7, "running": True}]}
        idle = {**control_evidence(), "clients": [], "apps": []}
        snapshots = iter((active, active, idle))
        clock = [0.0]

        def proxy(_name, path, **_kwargs):
            if path == "/action/close-app":
                return True, {"ok": True}
            return True, next(snapshots)

        with patch("provider_control_operation.time.monotonic",
                   side_effect=lambda: clock[0]), \
                patch("provider_control_operation.time.sleep",
                      side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            closed, reason = StreamerControlOperation.close(proxy, "parent", stream)
        self.assertTrue(closed)
        self.assertEqual("streamer_idle_after_close", reason)

    def test_streamer_close_retries_unavailable_control_snapshot_before_effect(self):
        stream = {"vibepollo_client_uuid": VIBE_UUID, "sunshine_app_id": 7}
        unavailable = {**control_evidence(1, True), "control_fresh": False,
                       "control_unavailable_sources": ["session"],
                       "clients": [], "apps": []}
        active = {**control_evidence(1, True),
                  "clients": [{"uuid": VIBE_UUID, "connected": True}],
                  "apps": [{"app_id": 7, "running": True}]}
        idle = {**control_evidence(), "clients": [], "apps": []}
        snapshots = iter((unavailable, active, idle))
        calls = []
        clock = [0.0]

        def proxy(_name, path, **_kwargs):
            calls.append(path)
            return (True, {"ok": True}) if path == "/action/close-app" \
                else (True, next(snapshots))

        with patch("provider_control_operation.time.monotonic",
                   side_effect=lambda: clock[0]), \
                patch("provider_control_operation.time.sleep",
                      side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            closed, reason = StreamerControlOperation.close(proxy, "parent", stream)

        self.assertTrue(closed)
        self.assertEqual("streamer_idle_after_close", reason)
        self.assertEqual(1, calls.count("/action/close-app"))

    def test_missing_stale_or_contradictory_authority_never_allows_idle_or_close(self):
        stream = {"vibepollo_client_uuid": VIBE_UUID, "sunshine_app_id": 7}
        idle = {**control_evidence(), "clients": [], "apps": []}
        owned = {**control_evidence(1, True),
                 "clients": [{"uuid": VIBE_UUID, "connected": True}],
                 "apps": [{"app_id": 7, "running": True}]}
        for policy, valid in ((ControlOperationPolicy.streamer_idle, idle),
                              (lambda value: ControlOperationPolicy.streamer_close_allowed(value, stream), owned)):
            self.assertTrue(policy(valid))
            for missing in ("control_fresh", "control_unavailable_sources", "session", "clients", "apps"):
                incomplete = dict(valid)
                incomplete.pop(missing)
                with self.subTest(missing=missing, snapshot=valid):
                    self.assertFalse(policy(incomplete))
            for changes in ({"control_fresh": False}, {"control_fresh": "true"},
                            {"control_unavailable_sources": ["rtsp"]},
                            {"control_unavailable_sources": None}, {"session": {}},
                            {"session": {"active": False, "count": True, "app_running": False}},
                            {"session": {"active": False, "count": 1, "app_running": True}},
                            {"session": {"active": True, "count": 0, "app_running": True}}):
                with self.subTest(changes=changes):
                    self.assertFalse(policy({**valid, **changes}))
        self.assertFalse(ControlOperationPolicy.streamer_idle({"clients": [], "apps": []}))
        self.assertFalse(ControlOperationPolicy.streamer_close_allowed({
            "clients": owned["clients"], "apps": owned["apps"]}, stream))
        for session in ({"active": True, "count": 2, "app_running": True},
                        {"active": True, "count": 1, "app_running": False},
                        {"active": False, "count": 0, "app_running": True}):
            self.assertFalse(ControlOperationPolicy.streamer_close_allowed({**owned, "session": session}, stream))

    def test_definitive_provider_rejection_does_not_poison_next_operation(self):
        _, launch = self.acquire(self.request("transport"))
        self.complete(launch)
        token = self.observe_exact_provider_game()
        self.state.proxy_json = lambda *args, **kwargs: (
            False, {"accepted": False, "reason": "game_stop_timeout"})
        status, result = self.state.playnite_action("game/stop-verified", {
            "game_id": "steam:7", "expected_process_token": token},
            self.client, "parent", "rejected-stop")
        self.assertNotEqual(200, status)
        self.assertIsNone(self.state.control_state["active"])
        self.state.proxy_json = lambda *args, **kwargs: (True, {"accepted": True})
        status, result = self.state.playnite_action("game/stop-verified", {
            "game_id": "steam:7", "expected_process_token": token},
            self.client, "parent", "retry-stop")
        self.assertEqual(200, status, result)

    def test_server_owned_status_rejects_foreign_client(self):
        _, launch = self.acquire(self.request("owned"))
        self.complete(launch)
        status, owned = self.state.control_operation_status(self.client)
        self.assertEqual(200, status)
        self.assertEqual(launch["generation"], owned["generation"])
        foreign = {**self.client, "id": "another-tv"}
        status, result = self.state.control_operation_status(foreign)
        self.assertEqual(409, status)
        self.assertEqual("stream_owner_mismatch", result["reason"])

    def test_child_on_same_tv_can_close_parent_stream(self):
        _, launch = self.acquire(self.request("parent-stream"))
        self.complete(launch)
        self.observe_provider_idle()
        self.state.config["profiles"]["kid"] = {
            "id": "kid", "kind": "child", "name": "Kid", "enabled": True,
            "parent_profile_id": "parent",
        }
        self.client["profile_grants"]["kid"] = ["use_profile"]
        self.state.authorize_child_profile(self.client, "kid")

        status, owned = self.state.control_operation_status(self.client)
        self.assertEqual(200, status)
        close = self.request("kid-closes-parent", "full_quit",
                             expected_generation=owned["generation"])
        status, permit = self.state.control_operation_acquire(
            self.client, close, close["operation_id"], 0)
        self.assertEqual(200, status)
        self.assertEqual("kid", permit["actor_profile_id"])
        status, result = self.state.control_operation_complete(self.client, {
            "operation_id": permit["operation_id"],
            "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"],
            "succeeded": True,
        }, permit["operation_id"])
        self.assertEqual(200, status)
        self.assertEqual("succeeded", result["state"])
        self.assertIsNone(self.state.control_state["stream"])

    def test_completed_owned_idle_recovers_parent_launch_without_close(self):
        _, launch = self.acquire(self.request("old-parent"))
        self.complete(launch)
        calls = []
        def snapshot(name, path, **kwargs):
            calls.append((name, path))
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {**control_evidence(), "clients": [], "apps": []}
            if name == "game_provider" and path == "/observation":
                return True, {"game": {"state": "idle", "id": "",
                                       "evidence": "ABSENT"},
                              "process": {"evidence": "ABSENT",
                                          "running_games": [],
                                          "running_scan_status": "complete"}}
            self.fail("Idle reconciliation attempted an effect")
        self.state.proxy = snapshot
        status, result = self.acquire(self.request("new-parent", session="stream-new"), 0)
        self.assertEqual(200, status, result)
        self.assertGreater(result["generation"], launch["generation"])
        self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()
