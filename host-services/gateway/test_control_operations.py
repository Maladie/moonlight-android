import json
import tempfile
import threading
import unittest
from unittest.mock import patch
from control_snapshot_fixture import control_evidence
from provider_target_fixture import target_aware
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
        self.state.proxy = target_aware(lambda name, path, **_kwargs: (
            (True, {"game": {"state": "idle", "id": "", "evidence": "ABSENT"},
                    "process": {"evidence": "ABSENT", "running_games": [],
                                "running_scan_status": "complete"}})
            if name == "game_provider" and path == "/observation"
            else (False, {"error": "fixture_offline"})))

    def observe_exact_provider_game(self, game_id="steam:7"):
        token = "a" * 64
        self.state.control_state["stream"].update({
            "provider_game_id": game_id,
            "process_token": token,
            "process_id": 77,
        })
        self.state.proxy = target_aware(lambda name, path, **_kwargs: (
            (True, {"game": {"state": "running", "id": game_id,
                             "evidence": "VERIFIED"},
                    "process": {"evidence": "VERIFIED",
                                "running_scan_status": "complete",
                                "running_games": [{"game_id": game_id,
                                                   "process_id": 77,
                                                   "process_token": token}]}})
            if name == "game_provider" and path == "/observation"
            else (False, {"error": "fixture_offline"})))
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

    def test_full_quit_recovers_only_exact_retired_provider_stop(self):
        _status, launch = self.acquire(self.request("transport"))
        self.complete(launch)
        token = self.observe_exact_provider_game()
        _status, permit = self.acquire(self.request(
            "close", "full_quit", expected_generation=launch["generation"]))
        active = self.state.control_state["active"]
        active["effect_request_sha256"] = ControlOperationPolicy.effect_hash(
            "/game/stop-verified", {"force": False, "game_id": "steam:7",
                                     "expected_process_token": token})
        self.complete(permit, False)
        self.state.provider_control_operation.retire = lambda _active: (
            True, {"accepted": True, "command": "stop", "stopped_current": True,
                   "stopped_game_id": "steam:8"})
        body = {"operation_id": "close", "observed_sunshine_app_id": 7}
        status, wrong = self.state.control_operation_reconcile(
            self.client, body, "close")
        self.assertEqual(409, status)
        self.assertEqual("terminal_owner_required", wrong["reason"])
        self.assertEqual("uncertain", active["state"])

        self.state.provider_control_operation.retire = lambda _active: (
            True, {"accepted": True, "command": "stop", "stopped_current": True,
                   "stopped_game_id": "steam:7"})
        status, confirmed = self.state.control_operation_reconcile(
            self.client, body, "close")
        self.assertEqual(200, status)
        self.assertEqual("provider_stopped", confirmed["state"])
        self.assertEqual("uncertain", active["state"])
        self.assertEqual(200, self.complete(permit, True)[0])
        self.assertIsNone(self.state.control_state["stream"])

    def test_full_quit_lost_response_replays_only_exact_pending_permit(self):
        _status, launch = self.acquire(self.request("transport"))
        self.complete(launch)
        self.observe_exact_provider_game()
        body = self.request("close", "full_quit",
                            expected_generation=launch["generation"])
        status, permit = self.acquire(body)
        self.assertEqual(200, status)
        generation = self.state.control_state["generation"]

        status, replay = self.acquire(body, 0)
        self.assertEqual(200, status)
        self.assertEqual(permit, replay)
        self.assertEqual(generation, self.state.control_state["generation"])

        status, wrong = self.acquire({**body, "observed_sunshine_app_id": 0}, 0)
        self.assertEqual(409, status)
        self.assertEqual("operation_id_reused", wrong["reason"])
        status, wrong = self.acquire({**body, "sunshine_app_id": 8}, 0)
        self.assertEqual(409, status)
        self.assertEqual("operation_id_reused", wrong["reason"])
        self.assertEqual(200, self.complete(permit, True)[0])

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

    def test_force_close_trace_keeps_close_failure_and_authoritative_idle(self):
        calls = []
        def proxy(_name, path, **_kwargs):
            calls.append(path)
            if path == "/action/close-app":
                return False, {"status": 500}
            return True, {**control_evidence(), "clients": [], "apps": []}

        trace = {}
        self.assertTrue(StreamerControlOperation.force_close(proxy, "parent", trace))
        self.assertEqual(["/action/close-app", "/snapshot?force=1"], calls)
        self.assertEqual(0, trace["close_http_ok"])
        self.assertEqual(500, trace["close_http_status"])
        self.assertEqual(1, trace["snapshot_http_ok"])
        self.assertEqual(0, trace["snapshot_session_count"])

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

        trace = {}
        with patch("provider_control_operation.time.monotonic",
                   side_effect=lambda: clock[0]), \
                patch("provider_control_operation.time.sleep",
                      side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            closed, reason = StreamerControlOperation.close(proxy, "parent", stream, trace)
        self.assertFalse(closed)
        self.assertEqual("streamer_close_unconfirmed", reason)
        self.assertEqual(1, calls.count("/action/close-app"))
        self.assertGreater(calls.count("/snapshot?force=1"), 1)
        self.assertEqual("observe_idle", trace["close_stage"])
        self.assertEqual(reason, trace["reason"])
        self.assertEqual(1, trace["snapshot_session_count"])
        self.assertEqual(1, trace["close_http_ok"])

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

        trace = {}
        with patch("provider_control_operation.time.monotonic",
                   side_effect=lambda: clock[0]), \
                patch("provider_control_operation.time.sleep",
                      side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            closed, reason = StreamerControlOperation.close(proxy, "parent", stream, trace)
        self.assertTrue(closed)
        self.assertEqual("streamer_idle_after_close", reason)
        self.assertEqual("idle_confirmed", trace["close_stage"])
        self.assertEqual(0, trace["snapshot_session_count"])
        self.assertEqual((7, 7, 1), (trace["expected_app_id"], trace["observed_app_id"],
                                      trace["observed_client_matches"]))

    def test_streamer_close_identity_rejection_never_calls_effect(self):
        stream = {"vibepollo_client_uuid": VIBE_UUID, "sunshine_app_id": 7}
        active = {**control_evidence(1, True),
                  "clients": [{"uuid": VIBE_UUID, "connected": True}],
                  "apps": [{"app_id": 7, "running": True}]}
        for changes in (
                {"apps": [{"app_id": 99, "running": True}]},
                {"clients": [{"uuid": "foreign-client", "connected": True}]},
                {"clients": [{"uuid": VIBE_UUID, "connected": True},
                             {"uuid": "foreign-client", "connected": True}]},
                {"apps": None},
                {"session": {"active": True, "app_running": False, "count": 1}}):
            with self.subTest(changes=changes):
                calls, trace = [], {}
                def proxy(_name, path, **_kwargs):
                    calls.append(path)
                    return True, {**active, **changes, "private_payload": "Bearer secret"}
                closed, reason = StreamerControlOperation.close(proxy, "parent", stream, trace)
                self.assertEqual((False, "streamer_identity_unconfirmed"), (closed, reason))
                self.assertEqual(["/snapshot?force=1"], calls)
                self.assertEqual("identity_rejected", trace["close_stage"])
                self.assertNotIn("secret", json.dumps(trace))
                self.assertNotIn(VIBE_UUID, json.dumps(trace))

    def test_streamer_close_unavailable_observation_is_bounded_without_effect(self):
        calls, trace, clock = [], {}, [0.0]
        def proxy(_name, path, **_kwargs):
            calls.append(path)
            raise OSError("private transport payload")
        with patch("provider_control_operation.time.monotonic", side_effect=lambda: clock[0]), \
                patch("provider_control_operation.time.sleep",
                      side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            closed, reason = StreamerControlOperation.close(proxy, "parent", {
                "sunshine_app_id": 7, "vibepollo_client_uuid": VIBE_UUID}, trace)
        self.assertEqual((False, "streamer_snapshot_unavailable"), (closed, reason))
        self.assertNotIn("/action/close-app", calls)
        self.assertEqual("observe_authority", trace["close_stage"])
        self.assertEqual((0, 0), (trace["snapshot_http_ok"], trace["snapshot_control_fresh"]))
        self.assertGreaterEqual(clock[0], 12)
        self.assertNotIn("private", json.dumps(trace))

    def test_streamer_close_lost_or_failed_response_requires_fresh_idle(self):
        active = {**control_evidence(1, True),
                  "clients": [{"uuid": VIBE_UUID, "connected": True}],
                  "apps": [{"app_id": 7, "running": True}]}
        idle = {**control_evidence(), "clients": [], "apps": []}
        for lost in (False, True):
            with self.subTest(lost=lost):
                closed_effect, trace, calls = [False], {}, []
                def proxy(_name, path, **_kwargs):
                    calls.append(path)
                    if path == "/action/close-app":
                        closed_effect[0] = True
                        if lost:
                            raise OSError("response lost")
                        return False, {"status": 500, "private_payload": "secret"}
                    return True, idle if closed_effect[0] else active
                result = StreamerControlOperation.close(proxy, "parent", {
                    "sunshine_app_id": 7, "vibepollo_client_uuid": VIBE_UUID}, trace)
                self.assertEqual((True, "streamer_idle_after_close"), result)
                self.assertEqual(["/snapshot?force=1", "/action/close-app",
                                  "/snapshot?force=1"], calls)
                self.assertEqual(0 if lost else 500, trace["close_http_status"])
                self.assertEqual(0, trace["snapshot_session_count"])
                self.assertNotIn("secret", json.dumps(trace))

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
        self.state.proxy = target_aware(snapshot)
        status, result = self.acquire(self.request("new-parent", session="stream-new"), 0)
        self.assertEqual(200, status, result)
        self.assertGreater(result["generation"], launch["generation"])
        self.assertEqual(2, len(calls))

    def observe_paused_app(self, connected_count=0):
        """Sunshine after a client abort: the app stays paused (resumable), transport ended."""
        def proxy(name, path, **_kwargs):
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {**control_evidence(connected_count, app_running=True),
                              "clients": [{"uuid": VIBE_UUID,
                                           "connected": connected_count > 0}],
                              "apps": [{"app_id": 7, "running": True}]}
            if name == "game_provider" and path == "/observation":
                return True, {"game": {"state": "idle", "id": "", "evidence": "ABSENT"},
                              "process": {"evidence": "ABSENT", "running_games": [],
                                          "running_scan_status": "complete"}}
            return False, {"error": "fixture_offline"}
        self.state.proxy = target_aware(proxy)

    def test_aborted_transport_rebinds_to_same_owner_resume(self):
        # A1: launch permitted and completed, then the client aborts in CONNECTING_STREAM
        # without a stop; Sunshine ends the session but keeps the app paused.
        _status, a1 = self.acquire(self.request("a1-launch"))
        self.complete(a1)
        self.observe_paused_app()

        # A2: the same TV resumes the paused app on a new transport.
        status, a2 = self.acquire(self.request(
            "a2-resume", "resume", session="stream-2"), 0)
        self.assertEqual(200, status, a2)
        self.assertEqual("permitted", a2["state"])
        self.assertGreater(a2["generation"], a1["generation"])
        self.assertEqual(200, self.complete(a2)[0])
        self.assertEqual("stream-2", self.state.control_state["stream"]["stream_session_id"])

        # The dead transport's binding is fenced: a late stop for it cannot act.
        status, stale = self.acquire(self.request(
            "a1-late-stop", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual(409, status)
        self.assertEqual("unmanaged_stream", stale["reason"])

    def track_reads(self, during=None):
        """Record each proxy read and whether it ran under the Gateway lock."""
        reads = []
        proxy = self.state.proxy

        def tracked(name, path, **kwargs):
            reads.append((name, self.state.lock._is_owned()))
            if during is not None:
                during(name)
            return proxy(name, path, **kwargs)
        self.state.proxy = tracked
        return reads

    def test_acquire_reads_streamer_and_provider_without_the_lock(self):
        # Lab: lock-held-slow duration_ms=797 on a rebind acquire; every authenticated
        # request waited behind the streamer snapshot and the provider observation.
        a1 = self.launched_stream()
        self.observe_paused_app()
        reads = self.track_reads()
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            status, a2 = self.acquire(self.request("a2-resume", "resume", session="stream-2"))
        self.assertEqual(200, status, a2)
        self.assertEqual([("vibepollo", False), ("game_provider", False)], reads)
        events = [call.args[0] for call in record.call_args_list]
        self.assertIn("control-stream-transport-rebound", events)
        self.complete(a2)

        reads.clear()
        status, close = self.acquire(self.request(
            "a2-quit", "full_quit", session="stream-2", expected_generation=a2["generation"]))
        self.assertEqual(200, status, close)
        self.assertEqual([("game_provider", False)], reads)
        self.assertGreater(close["generation"], a1["generation"])

    def test_idle_reconcile_on_launch_reads_without_the_lock(self):
        _, launch = self.acquire(self.request("old-parent"))
        self.complete(launch)
        self.state.proxy = target_aware(lambda name, path, **_kwargs: (
            (True, {**control_evidence(), "clients": [], "apps": []})
            if name == "vibepollo" else
            (True, {"game": {"state": "idle", "id": "", "evidence": "ABSENT"},
                    "process": {"evidence": "ABSENT", "running_games": [],
                                "running_scan_status": "complete"}})))
        reads = self.track_reads()
        status, result = self.acquire(self.request("new-parent", session="stream-new"))
        self.assertEqual(200, status, result)
        self.assertEqual([("vibepollo", False), ("game_provider", False)], reads)
        self.assertEqual(launch["generation"],
                         self.state.control_state["idle_stream"]["generation"])

    def test_state_change_during_a_read_yields_no_rebind_or_permit(self):
        # Each read races a change the result was not taken against: one probe without
        # time left, else one re-probe, then the rebind fails closed.
        for change in ("generation", "stream"):
            for wait in (0, 20.0):
                with self.subTest(change=change, wait=wait):
                    label = "%s-%d" % (change, wait)
                    self.state.control_state.update({"active": None, "stream": None})
                    self.observe_paused_app()
                    _status, a1 = self.acquire(self.request("a1-" + label))
                    self.complete(a1)
                    bumps = []

                    def during(_name, change=change):
                        with self.state.lock:
                            if change == "generation":
                                bumps.append(1)
                                self.state.control_state["generation"] += 1
                            else:
                                self.state.control_state["stream"] = {
                                    **self.state.control_state["stream"],
                                    "stream_session_id": "swapped-%d" % len(bumps)}
                                bumps.append(0)
                    reads = self.track_reads(during)
                    generation = self.state.control_state["generation"]
                    with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
                        status, result = self.acquire(self.request(
                            "a2-" + label, "resume", session="stream-2"), wait)
                    self.assertEqual(409, status, result)
                    self.assertEqual("managed_stream_conflict", result["reason"])
                    events = [call.args[0] for call in record.call_args_list]
                    self.assertNotIn("control-operation-permitted", events)
                    self.assertNotIn("control-stream-transport-rebound", events)
                    self.assertIsNone(self.state.control_state["active"])
                    self.assertEqual([("vibepollo", False)] * (1 if wait == 0 else 2), reads)
                    # Only the injected change moved the generation, never the acquire.
                    self.assertEqual(generation + sum(bumps),
                                     self.state.control_state["generation"])

    def observe_partial_scan(self, connected_count=0, app_running=True, provider_ok=True,
                             scan="partial", game=None, running=(), process="PENDING",
                             target_status=None):
        """The Bridge while idle: game ABSENT, by default process PENDING and scan partial."""
        def proxy(name, path, **_kwargs):
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {**control_evidence(connected_count, app_running=app_running),
                              "clients": [{"uuid": VIBE_UUID,
                                           "connected": connected_count > 0}],
                              "apps": [{"app_id": 7, "running": app_running}]}
            if name == "game_provider" and path == "/observation" and provider_ok:
                return True, {"game": dict(game or {"state": "idle", "id": "",
                                                    "evidence": "ABSENT"}),
                              "process": {"evidence": process,
                                          "running_games": [dict(item) for item in running],
                                          "running_scan_status": scan}}
            return False, {"error": "fixture_offline"}
        self.state.proxy = target_aware(proxy, lambda: target_status)

    def assert_unconfirmed(self, generation):
        for body in (self.request("a1-resume", "resume"),
                     self.request("a1-quit", "full_quit", expected_generation=generation)):
            with self.subTest(intent=body["intent"]):
                status, result = self.acquire(body, 0)
                self.assertEqual(409, status)
                self.assertEqual("retained_process_unconfirmed", result["reason"])
        self.assertFalse(self.state._control_reconcile_idle())
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])

    def launched_stream(self, token=""):
        _status, launch = self.acquire(self.request("a1-launch"))
        self.complete(launch)
        if token:
            self.state.control_state["stream"].update({
                "provider_game_id": "steam:7", "process_token": token, "process_id": 77})
        return launch

    def test_tokenless_stream_rebinds_when_running_scan_complete_and_idle(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(scan="complete", process="ABSENT")
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            status, a2 = self.acquire(self.request(
                "a2-resume", "resume", session="stream-2"), 0)
        self.assertEqual(200, status, a2)
        self.assertEqual("permitted", a2["state"])
        self.assertGreater(a2["generation"], a1["generation"])
        events = [call.args[0] for call in record.call_args_list]
        self.assertIn("control-operation-permitted", events)
        self.assertIn("control-stream-transport-rebound", events)
        self.assertEqual(200, self.complete(a2)[0])
        stream = self.state.control_state["stream"]
        self.assertEqual(("stream-2", "", 0), (
            stream["stream_session_id"], stream["process_token"], stream["process_id"]))

    def test_tokenless_stream_full_quit_permitted_when_running_scan_complete_and_idle(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(scan="complete", process="ABSENT")
        status, close = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual(200, status, close)
        self.assertEqual("permitted", close["state"])
        self.assertEqual(("", ""), (close["provider_game_id"], close["process_token"]))
        self.assertEqual(200, self.complete(close)[0])
        self.assertIsNone(self.state.control_state["stream"])

    def test_tokenless_stream_unconfirmed_when_scan_partial(self):
        # Owner decision: idle needs a complete scan for every stream, with or without a
        # retained process identity (the HOST-GW1 follow-up relaxation is reverted).
        a1 = self.launched_stream()
        self.observe_partial_scan()
        status, result = self.acquire(self.request("a2-resume", "resume", session="stream-2"), 0)
        self.assertEqual(409, status)
        self.assertEqual("retained_process_unconfirmed", result["reason"])
        self.observe_partial_scan(app_running=False)
        self.assert_unconfirmed(a1["generation"])

    def test_token_bearing_stream_still_unconfirmed_when_scan_partial(self):
        a1 = self.launched_stream(token="a" * 64)
        self.observe_partial_scan(app_running=False)
        self.assert_unconfirmed(a1["generation"])

    def test_game_id_only_stream_still_unconfirmed_when_scan_partial(self):
        a1 = self.launched_stream()
        self.state.control_state["stream"]["provider_game_id"] = "steam:7"
        self.observe_partial_scan(app_running=False)
        self.assert_unconfirmed(a1["generation"])

    def test_tokenless_stream_unconfirmed_when_scan_unavailable(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(app_running=False, scan="unavailable")
        self.assert_unconfirmed(a1["generation"])

    def test_tokenless_stream_unconfirmed_when_game_starting(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(app_running=False, game={
            "state": "starting", "id": "", "evidence": "PENDING"})
        self.assert_unconfirmed(a1["generation"])

    def test_tokenless_stream_adopts_running_identity_when_scan_partial(self):
        self.launched_stream()
        token = "b" * 64
        self.observe_partial_scan(app_running=False, running=[
            {"game_id": "steam:7", "process_id": 77, "process_token": token}])
        self.assertFalse(self.state._control_reconcile_idle())
        status, resume = self.acquire(self.request("a1-resume", "resume"), 0)
        self.assertEqual(200, status, resume)
        self.assertEqual(("steam:7", token, 77), (
            resume["provider_game_id"], resume["process_token"], resume["process_id"]))

    def test_tokenless_stream_unconfirmed_when_provider_unavailable(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(app_running=False, provider_ok=False)
        self.assert_unconfirmed(a1["generation"])
        self.state.proxy = target_aware(lambda name, path, **_kwargs: (
            (True, {"game": {"state": "idle", "id": "", "evidence": "ABSENT"}})
            if name == "game_provider" else (False, {"error": "fixture_offline"})))
        status, result = self.acquire(self.request("a1-resume-malformed", "resume"), 0)
        self.assertEqual(409, status)
        self.assertEqual("retained_process_unconfirmed", result["reason"])

    def test_idle_reconcile_retires_tokenless_stream_when_scan_complete_and_streamer_idle(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(app_running=False, scan="complete", process="ABSENT")
        self.assertTrue(self.state._control_reconcile_idle())
        self.assertIsNone(self.state.control_state["stream"])
        self.assertEqual(a1["generation"],
                         self.state.control_state["idle_stream"]["generation"])

    def observe_launch(self, state, scan="complete", running=(), target_status=None):
        """The Bridge mid-launch or after a failed launch: game id kept, no game process."""
        self.observe_partial_scan(app_running=False, scan=scan, running=running,
                                  target_status=target_status, game={
                                      "state": state, "id": "steam:7",
                                      "evidence": "VERIFIED"})

    def close_permit(self, a1, intent="full_quit"):
        status, close = self.acquire(self.request(
            "a1-" + intent, intent, expected_generation=a1["generation"]), 0)
        self.assertEqual(200, status, close)
        self.assertEqual(("permitted", "", "", 0), (
            close["state"], close["provider_game_id"], close["process_token"],
            close["process_id"]))
        self.assertEqual(200, self.complete(close)[0])
        self.assertIsNone(self.state.control_state["stream"])

    def test_close_without_game_process_while_launch_starting(self):
        a1 = self.launched_stream()
        self.observe_launch("starting")
        self.close_permit(a1)

    def test_hard_reset_without_game_process_while_launch_starting(self):
        a1 = self.launched_stream()
        self.observe_launch("starting")
        self.close_permit(a1, "hard_reset")

    def test_close_without_game_process_after_failed_launch(self):
        a1 = self.launched_stream()
        self.observe_launch("failed")
        self.close_permit(a1)

    def test_close_after_failed_launch_when_retained_process_gone(self):
        a1 = self.launched_stream(token="a" * 64)
        self.observe_launch("failed")
        self.close_permit(a1)

    def test_launch_close_unconfirmed_when_retained_process_not_gone(self):
        a1 = self.launched_stream(token="a" * 64)
        self.observe_launch("failed", target_status="unknown")
        self.assert_unconfirmed(a1["generation"])

    def test_launch_close_unconfirmed_when_game_listed_without_token(self):
        a1 = self.launched_stream()
        self.observe_launch("failed", running=[{"game_id": "steam:7", "process_id": 77}])
        self.assert_unconfirmed(a1["generation"])

    def test_launch_close_unconfirmed_when_scan_partial(self):
        a1 = self.launched_stream()
        self.observe_launch("starting", scan="partial")
        self.assert_unconfirmed(a1["generation"])

    def test_launch_close_unconfirmed_when_scan_unavailable(self):
        a1 = self.launched_stream()
        self.observe_launch("failed", scan="unavailable")
        self.assert_unconfirmed(a1["generation"])

    def test_close_in_launch_phase_two_keeps_process_token(self):
        a1 = self.launched_stream()
        token = "b" * 64
        self.observe_launch("starting", running=[
            {"game_id": "steam:7", "process_id": 77, "process_token": token}])
        status, close = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual(200, status, close)
        self.assertEqual(("steam:7", token, 77), (
            close["provider_game_id"], close["process_token"], close["process_id"]))

    def test_child_launch_close_gains_no_other_intent(self):
        a1 = self.launched_stream()
        self.observe_launch("starting")
        self.state.config["profiles"]["kid"] = {
            "id": "kid", "kind": "child", "name": "Kid", "enabled": True,
            "parent_profile_id": "parent"}
        self.client["profile_grants"]["kid"] = ["use_profile"]
        self.state.authorize_child_profile(self.client, "kid")
        resume = self.request("kid-resume", "resume")
        status, result = self.state.control_operation_acquire(
            self.client, resume, resume["operation_id"], 0)
        self.assertEqual(409, status, result)
        close = self.request("kid-quit", "full_quit", expected_generation=a1["generation"])
        status, permit = self.state.control_operation_acquire(
            self.client, close, close["operation_id"], 0)
        self.assertEqual(200, status, permit)
        self.assertEqual(("kid", "", ""), (permit["actor_profile_id"],
                                           permit["provider_game_id"],
                                           permit["process_token"]))

    def test_rejected_close_leaves_no_active_operation(self):
        a1 = self.launched_stream(token="a" * 64)
        self.observe_partial_scan()
        status, result = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual(409, status)
        self.assertEqual("retained_process_unconfirmed", result["reason"])
        self.assertIsNone(self.state.control_state["active"])
        self.authorize()
        status, reconcile = self.state.control_operation_reconcile(
            self.client, {"operation_id": "a1-quit", "observed_sunshine_app_id": 7},
            "a1-quit")
        self.assertEqual(409, status)
        self.assertEqual("stale_operation", reconcile["reason"])
        stream = self.state.control_state["stream"]
        self.assertEqual(("stream-1", a1["generation"], "a" * 64), (
            stream["stream_session_id"], stream["generation"], stream["process_token"]))

    def test_live_or_foreign_or_unproven_stream_still_conflicts(self):
        for case in ("live_transport", "foreign_tv", "foreign_actor", "child_attached",
                     "snapshot_unavailable", "launch"):
            with self.subTest(case=case):
                self.state.control_state.update({"active": None, "stream": None})
                _status, owner = self.acquire(self.request("owner-" + case))
                self.complete(owner)
                if case == "foreign_tv":
                    self.state.control_state["stream"]["client_id"] = "other-tv"
                if case == "foreign_actor":
                    self.state.control_state["stream"]["actor_profile_id"] = "other-actor"
                if case == "child_attached":
                    self.state.control_state["stream"]["child_session_id"] = "child-1"
                if case == "snapshot_unavailable":
                    self.state.proxy = lambda *_a, **_k: (False, {"error": "offline"})
                else:
                    self.observe_paused_app(1 if case == "live_transport" else 0)
                intent = "launch" if case == "launch" else "resume"

                status, result = self.acquire(self.request(
                    "next-" + case, intent, session="stream-next", observed_app_id=7), 0)

                self.assertEqual(409, status)
                self.assertEqual("managed_stream_conflict", result["reason"])
                stream = self.state.control_state["stream"]
                self.assertEqual(("stream-1", owner["generation"]),
                                 (stream["stream_session_id"], stream["generation"]))


if __name__ == "__main__":
    unittest.main()
