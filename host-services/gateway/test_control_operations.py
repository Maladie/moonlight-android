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
from control_snapshot_fixture import route_vibepollo_through_proxy, routed

route_vibepollo_through_proxy(wakeplay_gateway.GatewayState)


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
        self.state = GatewayState(self.config_path)
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
        restarted = GatewayState(self.config_path)
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
        self.assertEqual("stream_generation_superseded", result["reason"])
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
        self.observe_provider_idle()  # a fresh child start needs the provider's proof

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

        restarted = GatewayState(self.config_path)
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

    def test_end_game_abandons_start_without_process_under_provider_permit(self):
        _status, launch = self.acquire(self.request("transport"))
        self.complete(launch)
        calls = []
        self.state.proxy_json = lambda name, path, body, **kwargs: (
            calls.append((name, path, body)) or
            (True, {"accepted": True, "command": "abandon-start"}))
        self.authorize()

        status, result = self.state.playnite_action(
            "game/abandon-start", {"game_id": "steam:7"}, self.client,
            "parent", "provider-abandon")

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("game_provider", "/game/abandon-start"), calls[0][:2])
        self.assertEqual("steam:7", calls[0][2]["game_id"])
        self.assertEqual(launch["generation"],
                         calls[0][2]["control_operation"]["generation"])

        self.state.proxy_json = lambda name, path, body, **kwargs: (
            True, {"accepted": False, "command": "abandon-start",
                   "reason": "start_not_abandonable"})
        status, refused = self.state.playnite_action(
            "game/abandon-start", {"game_id": "steam:7"}, self.client,
            "parent", "provider-abandon-2")
        self.assertEqual(200, status)
        self.assertFalse(refused["ok"])
        self.assertEqual("start_not_abandonable", refused["error"])

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

        restarted = GatewayState(self.config_path)
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
        self.assertTrue(StreamerControlOperation.force_close(routed(proxy), trace))
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
            closed, reason = StreamerControlOperation.close(routed(proxy), stream, trace)
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
            closed, reason = StreamerControlOperation.close(routed(proxy), stream, trace)
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
                closed, reason = StreamerControlOperation.close(routed(proxy), stream, trace)
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
            closed, reason = StreamerControlOperation.close(routed(proxy), {
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
                result = StreamerControlOperation.close(routed(proxy), {
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
            closed, reason = StreamerControlOperation.close(routed(proxy), stream)

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
                            {"control_unavailable_sources": ["clients"]},
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
        self.assertEqual("stream_generation_superseded", stale["reason"])

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

    def close_permit(self, a1, intent="full_quit", abandons=False):
        """abandons: answer the Bridge's abandon-start from a fixture (no loopback request)
        and require that exactly this start was abandoned."""
        abandoned = []
        if abandons:
            self.state.proxy_json = lambda name, path, body, **kwargs: (
                abandoned.append((name, path, body["game_id"], kwargs["profile_id"]))
                or (True, {"accepted": True}))
        status, close = self.acquire(self.request(
            "a1-" + intent, intent, expected_generation=a1["generation"]), 0)
        self.assertEqual(200, status, close)
        self.assertEqual(("permitted", "", "", 0), (
            close["state"], close["provider_game_id"], close["process_token"],
            close["process_id"]))
        self.assertEqual(200, self.complete(close)[0])
        self.assertIsNone(self.state.control_state["stream"])
        if abandons:
            self.assertEqual(
                [("game_provider", "/game/abandon-start", "steam:7", "parent")], abandoned)

    def test_close_without_game_process_while_launch_starting(self):
        a1 = self.launched_stream()
        self.observe_launch("starting")
        self.close_permit(a1, abandons=True)

    def test_hard_reset_without_game_process_while_launch_starting(self):
        a1 = self.launched_stream()
        self.observe_launch("starting")
        self.close_permit(a1, "hard_reset", abandons=True)

    def test_close_without_game_process_after_failed_launch(self):
        a1 = self.launched_stream()
        self.observe_launch("failed")
        self.close_permit(a1, abandons=True)

    def test_close_after_failed_launch_abandons_that_start_in_the_bridge(self):
        a1 = self.launched_stream()
        self.observe_launch("failed")
        calls = []

        def control_lock_free():
            # Another thread can take the control lock: the slow Bridge call blocks nobody.
            free = []
            probe = threading.Thread(target=lambda: free.append(
                self.state.control_condition.acquire(timeout=1.0)
                and self.state.control_condition.release() is None))
            probe.start()
            probe.join()
            return free == [True]

        self.state.proxy_json = lambda name, path, body, **kwargs: (
            calls.append((name, path, body, kwargs, control_lock_free()))
            or (True, {"accepted": True}))
        status, close = self.acquire(self.request(
            "a1-full_quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual(200, status, close)
        self.assertEqual([], calls)  # Nothing is abandoned before the close succeeds.
        self.assertEqual(200, self.complete(close)[0])
        self.assertEqual(1, len(calls))
        name, path, body, kwargs, lock_free = calls[0]
        self.assertTrue(lock_free)
        self.assertEqual(("game_provider", "/game/abandon-start", "steam:7", "parent"),
                         (name, path, body["game_id"], kwargs["profile_id"]))
        self.assertEqual((close["operation_id"], close["generation"]), (
            body["control_operation"]["operation_id"],
            body["control_operation"]["generation"]))
        self.assertIsNone(self.state.control_state["stream"])

    def test_close_succeeds_when_the_bridge_refuses_to_abandon(self):
        a1 = self.launched_stream()
        self.observe_launch("starting")
        self.state.proxy_json = lambda *_args, **_kwargs: (
            True, {"accepted": False, "reason": "start_not_abandonable"})
        self.close_permit(a1)

    def test_close_with_a_verified_process_abandons_nothing(self):
        a1 = self.launched_stream()
        self.observe_launch("starting", running=[
            {"game_id": "steam:7", "process_id": 77, "process_token": "b" * 64}])
        self.state.proxy_json = lambda *_args, **_kwargs: self.fail("no abandon")
        status, close = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual(200, status, close)
        self.assertEqual(200, self.complete(close)[0])

    def test_close_after_failed_launch_when_retained_process_gone(self):
        a1 = self.launched_stream(token="a" * 64)
        self.observe_launch("failed")
        self.close_permit(a1, abandons=True)

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

    # --- D-1: the Gateway ends a stale client start itself -------------------------------

    def uncertain_start(self, operation_id="stale-launch"):
        status, permit = self.acquire(self.request(operation_id))
        self.assertEqual(200, status, permit)
        self.assertEqual((409, "effect_result_uncertain"), (
            self.complete(permit, succeeded=False)[0],
            self.state.control_state["active"]["reason"]))
        return permit

    def age_holder(self, seconds=1000.0):
        self.state.control_state["active"]["holder_monotonic"] -= seconds

    def test_uncertain_start_with_streamer_proof_fails_and_the_next_play_is_permitted(self):
        stale = self.uncertain_start()
        self.age_holder()
        self.observe_partial_scan(app_running=False)
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            status, play = self.acquire(self.request("next-play", session="stream-2"), 0)
        self.assertEqual((200, "permitted"), (status, play["state"]), play)
        self.assertGreater(play["generation"], stale["generation"])
        resolved = [call for call in record.call_args_list
                    if call.args[0] == "control-start-auto-resolved"]
        self.assertEqual([("stale-launch", "effect_result_uncertain")], [
            (call.kwargs["operation_id"], call.kwargs["reason"]) for call in resolved])
        receipt = self.state.control_state["completed"]["stale-launch"]
        self.assertEqual(("failed", "start_effect_absent"),
                         (receipt["state"], receipt["reason"]))
        self.assertNotIn("holder_monotonic", receipt)
        self.assertLessEqual(receipt["permitted_at"], receipt["state_changed_at"])
        # The previous holder's late terminal write reads its receipt and changes nothing.
        status, late = self.complete(stale)
        self.assertEqual((200, False, "failed", "start_effect_absent"),
                         (status, late["ok"], late["state"], late["reason"]))
        self.assertEqual("next-play", self.state.control_state["active"]["operation_id"])
        self.assertIsNone(self.state.control_state["stream"])
        self.assertEqual(200, self.complete(play)[0])
        self.assertEqual("stream-2", self.state.control_state["stream"]["stream_session_id"])

    def test_stale_start_is_kept_without_grace_or_proof(self):
        cases = {
            "in_grace": (False, lambda: self.observe_partial_scan(app_running=False)),
            "own_effect_in_grace": (False, self.observe_paused_app),
            "foreign_effect_in_grace": (False, lambda: self.observe_streamer((9,))),
            # The session count names a client the list lacks: not authoritative.
            "snapshot_incoherent": (True, lambda: setattr(
                self.state, "proxy", lambda name, path, **_k: (True, {
                    **control_evidence(1, app_running=True), "clients": [],
                    "apps": [{"app_id": 7, "running": True}]})
                if name == "vibepollo" else (False, {"error": "fixture_offline"}))),
            "snapshot_unavailable": (True, lambda: None),
        }
        for case, (aged, observe) in cases.items():
            with self.subTest(case=case):
                self.state.control_state.update({"active": None, "stream": None})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                self.uncertain_start("stale-" + case)
                if aged:
                    self.age_holder()
                observe()
                self.assertFalse(self.state._control_resolve_stale_start())
                status, result = self.acquire(self.request(
                    "next-" + case, session="stream-2"), 0)
                self.assertEqual((409, "uncertain", "effect_result_uncertain"),
                                 (status, result["state"], result["reason"]))
                self.assertEqual("stale-" + case,
                                 self.state.control_state["active"]["operation_id"])

    def test_permitted_start_still_launching_is_not_released(self):
        # The streamer shows nothing yet while the holder is inside its launch request.
        _status, launching = self.acquire(self.request("launching"))
        self.observe_partial_scan(app_running=False)
        self.age_holder(wakeplay_gateway.CONTROL_START_REPORTED_GRACE_SECONDS + 1)
        self.assertFalse(self.state._control_resolve_stale_start())
        status, result = self.acquire(self.request("impatient", session="stream-2"), 0)
        self.assertEqual((409, "terminal_result_timeout"), (status, result["reason"]))
        # A waiter's timeout is not the holder acting: the silent grace still runs.
        self.assertFalse(self.state._control_resolve_stale_start())
        self.assertEqual(200, self.complete(launching)[0])
        self.assertEqual("stream-1", self.state.control_state["stream"]["stream_session_id"])

    def test_abandoned_permitted_start_is_released_after_the_silent_grace(self):
        self.acquire(self.request("client-died"))
        self.observe_partial_scan(app_running=False)
        self.age_holder(wakeplay_gateway.CONTROL_START_SILENT_GRACE_SECONDS + 1)
        self.assertIsNone(self.state.child_time_housekeeping_tick())
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual("start_effect_absent",
                         self.state.control_state["completed"]["client-died"]["reason"])

    def test_next_play_waits_out_a_grace_that_ends_within_the_request(self):
        self.uncertain_start()
        self.observe_partial_scan(app_running=False)
        with patch.object(wakeplay_gateway, "CONTROL_START_REPORTED_GRACE_SECONDS", 0.2):
            status, play = self.acquire(self.request("next-play", session="stream-2"))
        self.assertEqual((200, "permitted"), (status, play["state"]), play)

    def test_stale_start_proof_is_discarded_when_the_holder_reports_meanwhile(self):
        _status, permit = self.acquire(self.request("slow-launch"))
        self.age_holder()
        key = self.state._control_stale_start_locked()[0]
        self.complete(permit, succeeded=False)  # The holder acted during the probe.
        self.assertFalse(self.state._control_resolve_stale_start_locked(key, "absent"))
        self.assertFalse(self.state._control_resolve_stale_start_locked(key, "present"))
        self.assertEqual("uncertain", self.state.control_state["active"]["state"])

    def test_host_owned_and_stop_operations_are_never_stale_starts(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(scan="complete", process="ABSENT")
        _status, close = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.complete(close, succeeded=False)
        self.observe_partial_scan(app_running=False, scan="complete", process="ABSENT")
        self.assertIsNone(self.state._control_stale_start_locked())
        self.assertFalse(self.state._control_resolve_stale_start())
        self.assertEqual("a1-quit", self.state.control_state["active"]["operation_id"])

    def test_restarted_gateway_resolves_the_persisted_start_after_proof(self):
        self.acquire(self.request("before-restart"))
        restarted = GatewayState(self.config_path)
        active = restarted.control_state["active"]
        self.assertEqual(("uncertain", "gateway_restarted_before_terminal_result"),
                         (active["state"], active["reason"]))

        def idle(name, path, **_kwargs):
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {**control_evidence(0, app_running=False),
                              "clients": [], "apps": []}
            return False, {"error": "fixture_offline"}

        restarted.proxy = idle
        self.assertIsNone(restarted.child_time_housekeeping_tick())  # Grace runs anew.
        self.assertEqual("before-restart", restarted.control_state["active"]["operation_id"])
        active["holder_monotonic"] -= wakeplay_gateway.CONTROL_START_SILENT_GRACE_SECONDS + 1
        restarted.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
        restarted.child_time_housekeeping_tick()
        self.assertIsNotNone(restarted.control_state["active"])  # No proof, no release.
        restarted.proxy = idle
        restarted.child_time_housekeeping_tick()
        self.assertIsNone(restarted.control_state["active"])
        self.assertEqual("failed",
                         restarted.control_state["completed"]["before-restart"]["state"])

    # --- lab fix: a start whose own effect is on the streamer is adopted, not kept --------

    def adopted_start(self, operation_id="client-died"):
        """The client died after its launch reached the streamer: the application runs,
        nobody is connected, the permit was never completed."""
        _status, permit = self.acquire(self.request(operation_id))
        self.observe_paused_app()
        self.age_holder(wakeplay_gateway.CONTROL_START_SILENT_GRACE_SECONDS + 1)
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            self.assertTrue(self.state._control_resolve_stale_start())
        return permit, record

    def test_stale_start_with_its_own_effect_is_adopted_as_the_holders_stream(self):
        permit, record = self.adopted_start()
        self.assertEqual(
            [("control-start-auto-adopted", "WARN"), ("control-operation-terminal",)],
            [call.args for call in record.call_args_list])
        adopted = record.call_args_list[0].kwargs
        self.assertEqual(("client-died", "permitted", "none", "present", "parent"), (
            adopted["operation_id"], adopted["state"], adopted["reason"],
            adopted["effect"], adopted["actor_profile_id"]))
        receipt = self.state.control_state["completed"]["client-died"]
        self.assertEqual(("succeeded", "start_effect_present"),
                         (receipt["state"], receipt["reason"]))
        self.assertIsNone(self.state.control_state["active"])
        record_left = dict(self.state.control_state["stream"])
        self.assertGreater(record_left["stream_started_unix_ms"], 0)
        # The holder's late terminal write, either way, reads the receipt and changes nothing.
        for succeeded in (True, False):
            status, late = self.complete(permit, succeeded=succeeded)
            self.assertEqual((200, True, "succeeded", "start_effect_present",
                              permit["generation"]),
                             (status, late["ok"], late["state"], late["reason"],
                              late["generation"]))
            self.assertEqual(record_left, self.state.control_state["stream"])
        # The record is the one the holder's own complete(true) leaves.
        self.state.control_state.update({"active": None, "stream": None, "completed": {}})
        _status, plain = self.acquire(self.request("client-died"))
        self.complete(plain)
        self.assertEqual(
            {**self.state.control_state["stream"], "generation": permit["generation"],
             "actor_lease_epoch": permit["actor_lease_epoch"],
             "stream_started_unix_ms": record_left["stream_started_unix_ms"]}, record_left)

    def test_owner_connects_to_the_adopted_stream_and_others_are_refused_as_before(self):
        permit, _record = self.adopted_start()
        # Another TV meets a managed stream, as for any retained one.
        other = dict(self.client, id="tv-2")
        self.state.authorize_profile(other, "parent")
        body = self.request("other-play", session="stream-9", observed_app_id=7)
        status, refused = self.state.control_operation_acquire(other, body, "other-play", 0)
        self.assertEqual((409, "managed_stream_conflict"), (status, refused["reason"]))
        # The owner's next Play resumes it on a new transport (rebind) and may close it.
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            status, resume = self.acquire(self.request(
                "next-play", "resume", session="stream-2"), 0)
        self.assertEqual((200, "permitted"), (status, resume["state"]), resume)
        self.assertIn("control-stream-transport-rebound",
                      [call.args[0] for call in record.call_args_list])
        self.assertEqual(200, self.complete(resume)[0])
        stream = self.state.control_state["stream"]
        self.assertEqual(("stream-2", resume["generation"]),
                         (stream["stream_session_id"], stream["generation"]))
        self.assertGreater(resume["generation"], permit["generation"])

    def test_next_play_adopts_the_stale_start_within_its_acquire(self):
        permit = self.uncertain_start()
        self.observe_paused_app()
        with patch.object(wakeplay_gateway, "CONTROL_START_REPORTED_GRACE_SECONDS", 0.2):
            # Before the grace is over nothing changes, whatever the streamer shows.
            self.assertFalse(self.state._control_resolve_stale_start())
            self.assertEqual("uncertain", self.state.control_state["active"]["state"])
            status, resume = self.acquire(self.request(
                "next-play", "resume", session="stream-2"))
        self.assertEqual((200, "permitted"), (status, resume["state"]), resume)
        self.assertEqual("succeeded",
                         self.state.control_state["completed"]["stale-launch"]["state"])
        self.assertGreater(resume["generation"], permit["generation"])

    def test_stale_start_beside_a_foreign_effect_fails_and_records_nothing(self):
        other = "89abcdef-0123-4567-89ab-cdef01234567"
        cases = {"foreign_app": ((9,), ()), "foreign_app_beside_own": ((7, 9), ()),
                 "foreign_client": ((7,), (other,)),
                 "foreign_client_beside_own": ((7,), (VIBE_UUID, other)),
                 "transport_without_app": ((), (VIBE_UUID,))}
        for case, (apps, clients) in cases.items():
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                operation_id = "stale-" + case.replace("_", "-")
                _status, permit = self.acquire(self.request(operation_id))
                self.observe_streamer(apps, clients)
                self.assertFalse(self.state._control_resolve_stale_start())  # In grace.
                self.age_holder(wakeplay_gateway.CONTROL_START_SILENT_GRACE_SECONDS + 1)
                with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
                    self.assertTrue(self.state._control_resolve_stale_start())
                self.assertEqual(("control-start-auto-resolved", "foreign"), (
                    record.call_args_list[0].args[0],
                    record.call_args_list[0].kwargs["effect"]))
                receipt = self.state.control_state["completed"][operation_id]
                self.assertEqual(("failed", "start_effect_foreign"),
                                 (receipt["state"], receipt["reason"]))
                self.assertEqual((None, None), (self.state.control_state["active"],
                                                self.state.control_state["stream"]))
                # A late success is not accepted: what runs is not this holder's.
                status, late = self.complete(permit)
                self.assertEqual((200, False, "failed", "start_effect_foreign"),
                                 (status, late["ok"], late["state"], late["reason"]))
                self.assertIsNone(self.state.control_state["stream"])
                status, close = self.acquire(self.request(
                    "close-" + case.replace("_", "-"), "full_quit",
                    expected_generation=permit["generation"]), 0)
                self.assertEqual((409, "managed_stream_unavailable"),
                                 (status, close["reason"]))

    def test_restarted_gateway_adopts_the_persisted_start_after_its_grace(self):
        _status, permit = self.acquire(self.request("before-restart"))
        restarted = GatewayState(self.config_path)
        active = restarted.control_state["active"]
        self.assertEqual("gateway_restarted_before_terminal_result", active["reason"])
        self.state = restarted
        self.client = restarted.client_for_token("token")
        self.observe_paused_app()
        self.assertIsNone(restarted.child_time_housekeeping_tick())  # Grace runs anew.
        self.assertEqual("before-restart", restarted.control_state["active"]["operation_id"])
        active["holder_monotonic"] -= wakeplay_gateway.CONTROL_START_SILENT_GRACE_SECONDS + 1
        self.assertIsNone(restarted.child_time_housekeeping_tick())
        self.assertIsNone(restarted.control_state["active"])
        stream = restarted.control_state["stream"]
        self.assertEqual(("tv", "parent", "stream-1", 7, permit["generation"]), (
            stream["client_id"], stream["actor_profile_id"], stream["stream_session_id"],
            stream["sunshine_app_id"], stream["generation"]))
        status, resume = self.acquire(self.request(
            "next-play", "resume", session="stream-2"), 0)
        self.assertEqual((200, "permitted"), (status, resume["state"]), resume)

    def test_unused_adopted_stream_is_retired_by_the_idle_reconcile(self):
        permit, _record = self.adopted_start()
        # While the streamer keeps the application the record stays the handle of it.
        self.assertIsNone(self.state.child_time_housekeeping_tick())
        self.assertEqual(permit["generation"], self.state.control_state["stream"]["generation"])
        self.observe_partial_scan(app_running=False, scan="complete", process="ABSENT")
        self.assertIsNone(self.state.child_time_housekeeping_tick())
        self.assertIsNone(self.state.control_state["stream"])
        self.assertEqual(permit["generation"],
                         self.state.control_state["idle_stream"]["generation"])
        status, play = self.acquire(self.request("next-play", session="stream-2"), 0)
        self.assertEqual((200, "permitted"), (status, play["state"]), play)

    def test_unused_adopted_stream_abandons_its_game_start_when_it_ends(self):
        permit, _record = self.adopted_start()
        self.observe_launch("starting")
        calls = self.record_abandons()
        self.assertIsNone(self.state.child_time_housekeeping_tick())
        self.assertIsNone(self.state.control_state["stream"])
        self.assert_abandoned(calls, permit["generation"] + 1)

    def test_adopted_child_start_leaves_the_record_its_own_complete_would(self):
        child_session_id = "01234567-89ab-cdef-0123-456789abcdef"
        self.state.config["profiles"]["kid"] = {
            "id": "kid", "kind": "child", "name": "Kid", "enabled": True,
            "parent_profile_id": "parent"}
        self.client["profile_grants"]["kid"] = ["use_profile"]
        session = {"session_id": child_session_id, "client_id": "tv",
                   "actor_profile_id": "kid", "execution_profile_id": "parent"}

        def child_start(operation_id):
            self.observe_provider_idle()  # a fresh child start needs the provider's proof
            self.state.child_time_session = session
            self.state.authorize_child_profile(self.client, "kid")
            body = {**self.request(operation_id), "child_session_id": child_session_id}
            status, permit = self.state.control_operation_acquire(
                self.client, body, operation_id, 0)
            self.assertEqual(200, status, permit)
            return permit

        for case in ("session_live", "session_ended"):
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                permit = child_start("kid-" + case.replace("_", "-"))
                self.observe_paused_app()
                self.age_holder(wakeplay_gateway.CONTROL_START_SILENT_GRACE_SECONDS + 1)
                if case == "session_ended":
                    self.state.child_time_session = None
                self.assertTrue(self.state._control_resolve_stale_start())
                adopted = dict(self.state.control_state["stream"])
                self.assertEqual(("kid", "parent", child_session_id), (
                    adopted["actor_profile_id"], adopted["execution_profile_id"],
                    adopted["child_session_id"]))
                # Exactly what the child's own complete(true) leaves in the same situation.
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                plain = child_start("kid-plain-" + case.replace("_", "-"))
                if case == "session_ended":
                    self.state.child_time_session = None
                self.state.authorize_child_profile(self.client, "kid")
                self.assertEqual(200, self.state.control_operation_complete(self.client, {
                    "operation_id": plain["operation_id"],
                    "generation": plain["generation"],
                    "actor_lease_epoch": plain["actor_lease_epoch"],
                    "succeeded": True}, plain["operation_id"])[0])
                ordinary = self.state.control_state["stream"]
                self.assertEqual(
                    {**ordinary, "generation": permit["generation"],
                     "actor_lease_epoch": permit["actor_lease_epoch"],
                     "stream_session_id": adopted["stream_session_id"],
                     "stream_started_unix_ms": adopted["stream_started_unix_ms"]}, adopted)
        # Adoption grants the child nothing: without a live session it still may only close.
        self.state.authorize_child_profile(self.client, "kid")
        body = {**self.request("kid-again", "resume", session="stream-2"),
                "child_session_id": child_session_id}
        status, refused = self.state.control_operation_acquire(
            self.client, body, "kid-again", 0)
        self.assertEqual((409, "child_session_unmanaged"), (status, refused["reason"]))

    # --- D-1: an ended stream abandons its game start ------------------------------------

    def record_abandons(self, result=(True, {"accepted": True})):
        calls = []

        def control_lock_free():
            free = []
            probe = threading.Thread(target=lambda: free.append(
                self.state.control_condition.acquire(timeout=1.0)
                and self.state.control_condition.release() is None))
            probe.start()
            probe.join()
            return free == [True]

        self.state.proxy_json = lambda name, path, body, **kwargs: (
            calls.append((name, path, body, kwargs.get("profile_id"),
                          control_lock_free())) or result)
        return calls

    def assert_abandoned(self, calls, generation):
        self.assertEqual(1, len(calls), calls)
        name, path, body, profile, lock_free = calls[0]
        self.assertTrue(lock_free)
        self.assertEqual(("game_provider", "/game/abandon-start", "steam:7", "parent"),
                         (name, path, body["game_id"], profile))
        binding = body["control_operation"]
        self.assertEqual((f"stream-idle-{generation}", generation, "stream-1", "parent"), (
            binding["operation_id"], binding["generation"],
            binding["stream_session_id"], binding["execution_profile_id"]))

    def test_idle_reconcile_abandons_the_start_of_a_stream_the_streamer_closed(self):
        for state in ("starting", "failed"):
            with self.subTest(state=state):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                a1 = self.launched_stream()
                self.observe_launch(state)
                calls = self.record_abandons()
                self.assertIsNone(self.state.child_time_housekeeping_tick())
                self.assertIsNone(self.state.control_state["stream"])
                self.assertEqual(a1["generation"],
                                 self.state.control_state["idle_stream"]["generation"])
                # Newer than the start it ends, older than anything permitted after it.
                self.assert_abandoned(calls, a1["generation"] + 1)
                self.assertEqual(a1["generation"] + 1,
                                 self.state.control_state["generation"])
                self.assertEqual(a1["actor_lease_epoch"] + 1,
                                 self.state.control_state["actor_lease_epoch"])
                self.assertFalse(self.state._control_reconcile_idle())
                self.assertEqual(1, len(calls))

    def test_idle_reconcile_keeps_a_launch_handed_over_to_a_process_or_a_live_stream(self):
        process = [{"game_id": "steam:7", "process_id": 77, "process_token": "b" * 64}]
        cases = {
            "game_process": lambda: self.observe_launch("starting", running=process),
            "scan_partial": lambda: self.observe_launch("starting", scan="partial"),
            "app_running": lambda: self.observe_partial_scan(
                app_running=True, scan="complete",
                game={"state": "starting", "id": "steam:7", "evidence": "VERIFIED"}),
        }
        for case, observe in cases.items():
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                a1 = self.launched_stream()
                observe()
                self.state.proxy_json = lambda *_args, **_kwargs: self.fail("no abandon")
                self.assertFalse(self.state._control_reconcile_idle())
                self.assertEqual(a1["generation"],
                                 self.state.control_state["stream"]["generation"])

    def test_next_launch_retires_the_ended_stream_and_abandons_its_start(self):
        a1 = self.launched_stream()
        self.observe_launch("starting")
        calls = self.record_abandons((True, {"accepted": False,
                                             "reason": "start_not_abandonable"}))
        status, play = self.acquire(self.request("next-play", session="stream-2"), 0)
        self.assertEqual((200, "permitted"), (status, play["state"]), play)
        self.assert_abandoned(calls, a1["generation"] + 1)
        self.assertEqual(a1["generation"] + 2, play["generation"])

    def test_resolved_reconnect_then_retires_its_stream_and_abandons_the_start(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(scan="complete", process="ABSENT")
        status, reconnect = self.acquire(self.request("a1-reconnect", "reconnect"), 0)
        self.assertEqual(200, status, reconnect)
        self.complete(reconnect, succeeded=False)
        self.age_holder()
        self.observe_launch("starting")
        calls = self.record_abandons()
        self.assertIsNone(self.state.child_time_housekeeping_tick())
        self.assertEqual("start_effect_absent",
                         self.state.control_state["completed"]["a1-reconnect"]["reason"])
        self.assertIsNone(self.state.control_state["stream"])
        self.assert_abandoned(calls, reconnect["generation"] + 1)
        self.assertGreater(reconnect["generation"], a1["generation"])

    def test_unreachable_bridge_does_not_fail_the_idle_reconcile(self):
        self.launched_stream()
        self.observe_launch("starting")

        def offline(*_args, **_kwargs):
            raise OSError("bridge offline")
        self.state.proxy_json = offline
        with patch.object(wakeplay_gateway, "CONTROL_ABANDON_RETRY_SECONDS", 0.0):
            self.assertTrue(self.state._control_reconcile_idle())
        self.assertEqual([], self.state.control_abandons)

    def test_abandon_is_retried_once_only_when_the_bridge_does_not_answer(self):
        for result, attempts in (((False, {"error": "timed out"}), 2),
                                 ((False, {"error": "Stale", "status": 400}), 1),
                                 ((True, {"accepted": False}), 1)):
            with self.subTest(result=result):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.launched_stream()
                self.observe_launch("starting")
                calls = self.record_abandons(result)
                with patch.object(wakeplay_gateway.time, "sleep") as sleep:
                    self.assertTrue(self.state._control_reconcile_idle())
                self.assertEqual(attempts, len(calls))
                self.assertTrue(all(call[4] for call in calls))  # Outside the lock.
                self.assertEqual(
                    [wakeplay_gateway.CONTROL_ABANDON_RETRY_SECONDS] * (attempts - 1),
                    [call.args[0] for call in sleep.call_args_list])

    # --- D-1: a repeated full quit joins the first ---------------------------------------

    def open_quit(self):
        a1 = self.launched_stream()
        self.observe_partial_scan(scan="complete", process="ABSENT")
        status, first = self.acquire(self.request(
            "quit-1", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual((200, "permitted"), (status, first["state"]), first)
        return a1, first

    def joining_quit(self, a1):
        results = []
        thread = threading.Thread(target=lambda: results.append(self.acquire(self.request(
            "quit-2", "full_quit", expected_generation=a1["generation"]), 5.0)))
        thread.start()
        thread.join(0.3)
        self.assertTrue(thread.is_alive())
        self.assertEqual("permitted", self.state.control_state["active"]["state"])
        return thread, results

    def test_second_full_quit_of_the_same_owner_joins_the_first(self):
        a1, first = self.open_quit()
        thread, results = self.joining_quit(a1)
        status, closed = self.complete(first)
        thread.join(5.0)
        self.assertEqual((200, "succeeded"), (status, closed["state"]))
        (status, joined), = results
        self.assertEqual(200, status, joined)
        self.assertEqual({**closed, "operation_id": "quit-2"}, joined)
        self.assertIsNone(self.state.control_state["stream"])
        # The joined receipt is idempotent for the second caller's own terminal write.
        status, replay = self.complete(joined)
        self.assertEqual((200, "succeeded"), (status, replay["state"]))

    def test_joined_full_quit_returns_the_first_failure_and_keeps_the_stream(self):
        a1, first = self.open_quit()
        thread, results = self.joining_quit(a1)
        self.authorize()
        self.state.control_operation_complete(self.client, {
            "operation_id": "quit-1", "generation": first["generation"],
            "actor_lease_epoch": first["actor_lease_epoch"], "succeeded": False,
            "effect_result": {"outcome": "definitive_failure"}}, "quit-1")
        thread.join(5.0)
        (status, joined), = results
        # The status a replay of quit-2 reads from its receipt: 200 with state=failed.
        self.assertEqual((200, False, "failed", "effect_rejected", "quit-2"), (
            status, joined["ok"], joined["state"], joined["reason"],
            joined["operation_id"]))
        self.assertEqual((200, joined), self.acquire(self.request(
            "quit-2", "full_quit", expected_generation=a1["generation"]), 0))
        self.assertEqual("stream-1", self.state.control_state["stream"]["stream_session_id"])
        self.assertIsNone(self.state.control_state["active"])

    def test_joined_full_quit_times_out_without_marking_the_first_uncertain(self):
        a1, first = self.open_quit()
        for wait in (0, 0.2):
            status, result = self.acquire(self.request(
                "quit-2", "full_quit", expected_generation=a1["generation"]), wait)
            self.assertEqual((409, {"ok": False, "state": "blocked",
                                    "reason": "operation_in_progress"}), (status, result))
        active = self.state.control_state["active"]
        self.assertEqual(("quit-1", "permitted"), (active["operation_id"], active["state"]))
        self.assertEqual(200, self.complete(first)[0])

    def test_full_quit_of_another_stream_does_not_join(self):
        a1, _first = self.open_quit()
        status, result = self.acquire(self.request(
            "quit-other", "full_quit", session="stream-other",
            expected_generation=a1["generation"]), 0)
        self.assertEqual((409, "terminal_result_timeout"), (status, result["reason"]))
        self.assertEqual("uncertain", self.state.control_state["active"]["state"])

    # --- D-1 review: late effect, grace boundary, join signature ------------------------

    def resolved_start(self):
        stale = self.uncertain_start()
        self.age_holder()
        self.observe_partial_scan(app_running=False)
        self.assertTrue(self.state._control_resolve_stale_start())
        return stale

    def test_late_success_of_a_released_start_is_accepted_while_nothing_was_granted(self):
        stale = self.resolved_start()
        self.assertEqual((200, "failed"), (lambda status, result: (
            status, result["state"]))(*self.complete(stale, succeeded=False)))
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            status, late = self.complete(stale)
        self.assertEqual((200, True, "succeeded", "none"),
                         (status, late["ok"], late["state"], late["reason"]))
        self.assertEqual(["control-start-late-effect-accepted", "control-operation-terminal"],
                         [call.args[0] for call in record.call_args_list])
        released = dict(self.state.control_state["stream"])
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual(stale["generation"], self.state.control_state["generation"])
        self.assertEqual((200, late), self.complete(stale))
        # The record is the one an ordinary successful start leaves.
        self.state.control_state.update({"active": None, "stream": None, "completed": {}})
        _status, plain = self.acquire(self.request("stale-launch"))
        self.complete(plain)
        ordinary = self.state.control_state["stream"]
        self.assertEqual(
            {**ordinary, "generation": stale["generation"],
             "actor_lease_epoch": stale["actor_lease_epoch"],
             "stream_started_unix_ms": released["stream_started_unix_ms"]}, released)
        self.assertGreater(released["stream_started_unix_ms"], 0)
        # The owner can close it.
        self.observe_partial_scan(scan="complete", process="ABSENT")
        self.close_permit(plain)

    def test_late_success_of_a_released_start_stays_failed_after_a_newer_grant(self):
        stale = self.resolved_start()
        _status, play = self.acquire(self.request("next-play", session="stream-2"), 0)
        self.complete(play)
        self.observe_partial_scan(scan="complete", process="ABSENT")
        status, close = self.acquire(self.request(
            "next-quit", "full_quit", session="stream-2",
            expected_generation=play["generation"]), 0)
        self.assertEqual(200, status, close)
        self.complete(close)
        self.assertEqual((None, None), (self.state.control_state["active"],
                                        self.state.control_state["stream"]))
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            status, late = self.complete(stale)
        self.assertEqual((200, False, "failed", "start_effect_absent"),
                         (status, late["ok"], late["state"], late["reason"]))
        self.assertIsNone(self.state.control_state["stream"])
        (warning,) = record.call_args_list
        self.assertEqual(("control-start-late-effect", "WARN"), warning.args)
        self.assertEqual((stale["generation"], close["generation"]), (
            warning.kwargs["generation"], warning.kwargs["current_generation"]))

    def test_late_success_does_not_touch_a_newer_operation_in_flight(self):
        stale = self.resolved_start()
        _status, play = self.acquire(self.request("next-play", session="stream-2"), 0)
        before = dict(self.state.control_state["active"])
        with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
            self.assertEqual("failed", self.complete(stale)[1]["state"])
        self.assertEqual("next-play", record.call_args.kwargs["active_operation_id"])
        self.assertEqual(before, self.state.control_state["active"])
        self.assertIsNone(self.state.control_state["stream"])
        # Another holder identity never reaches the receipt.
        self.assertEqual(409, self.complete({**stale, "generation": play["generation"]})[0])

    def waited(self):
        """Record the acquire's waits; each one lets the whole grace pass."""
        waits = []
        return waits, patch.object(
            self.state.control_condition, "wait",
            side_effect=lambda timeout=None: (waits.append(timeout), self.age_holder())[0])

    def test_only_the_next_start_waits_out_a_grace_and_only_with_the_proof_reserve(self):
        grace = wakeplay_gateway.CONTROL_START_REPORTED_GRACE_SECONDS
        reserve = wakeplay_gateway.CONTROL_START_PROOF_RESERVE_SECONDS
        self.uncertain_start()
        self.observe_partial_scan(app_running=False)
        waits, waiting = self.waited()
        with waiting:
            # 20 s request, 10 s reserve: a grace with 11 s left is not waited for.
            self.age_holder(grace - (20.0 - reserve) - 1.0)
            status, result = self.acquire(self.request("too-early", session="stream-2"))
            self.assertEqual((409, "effect_result_uncertain"), (status, result["reason"]))
            # With 9 s left a close is still answered at once; the next Play waits.
            self.age_holder(2.0)
            status, result = self.acquire(self.request("close", "full_quit"))
            self.assertEqual((409, "uncertain"), (status, result["state"]))
            self.assertEqual([], waits)
            started = wakeplay_gateway.time.monotonic()
            status, play = self.acquire(self.request("next-play", session="stream-2"))
        self.assertEqual((200, "permitted"), (status, play["state"]), play)
        (waited,) = waits
        self.assertTrue(20.0 - reserve - 2.0 < waited < 20.0 - reserve, waited)
        # Worst case: this wait, then the 8 s proof read, inside the client's 30 s.
        self.assertLess(waited + 8.0, 20.0)
        self.assertLess(wakeplay_gateway.time.monotonic() - started, 5.0)

    def test_start_over_a_retained_stream_never_waits_out_a_grace(self):
        self.launched_stream()
        self.observe_partial_scan(scan="complete", process="ABSENT")
        _status, reconnect = self.acquire(self.request("a1-reconnect", "reconnect"), 0)
        self.complete(reconnect, succeeded=False)
        self.age_holder(wakeplay_gateway.CONTROL_START_REPORTED_GRACE_SECONDS - 1.0)
        waits, waiting = self.waited()
        with waiting:
            status, result = self.acquire(self.request("next-play", session="stream-2"))
        self.assertEqual((409, "effect_result_uncertain", []),
                         (status, result["reason"], waits))

    def test_holder_repeating_its_uncertain_report_restarts_the_grace(self):
        stale = self.uncertain_start()
        self.age_holder()
        self.observe_partial_scan(app_running=False)
        self.assertEqual(409, self.complete(stale, succeeded=False)[0])
        self.assertGreater(self.state._control_stale_start_locked()[2],
                           wakeplay_gateway.CONTROL_START_REPORTED_GRACE_SECONDS - 1.0)
        self.assertFalse(self.state._control_resolve_stale_start())
        self.assertEqual("stale-launch", self.state.control_state["active"]["operation_id"])
        self.age_holder()
        self.assertTrue(self.state._control_resolve_stale_start())

    def test_full_quit_joins_only_the_same_owner_stream_and_generation(self):
        child_session_id = "01234567-89ab-cdef-0123-456789abcdef"

        def as_kid(_body):
            self.state.config["profiles"]["kid"] = {
                "id": "kid", "kind": "child", "name": "Kid", "enabled": True,
                "parent_profile_id": "parent"}
            self.client["profile_grants"]["kid"] = ["use_profile"]
            self.state.authorize_child_profile(self.client, "kid")

        def with_child_session(body):
            self.state.child_time_session = {
                "session_id": child_session_id, "client_id": "tv",
                "actor_profile_id": "parent", "execution_profile_id": "parent"}
            body["child_session_id"] = child_session_id

        def other_generation(body):
            body["expected_generation"] += 1

        for case, change in (("actor", as_kid), ("child_session", with_child_session),
                             ("expected_generation", other_generation)):
            with self.subTest(case=case):
                self.state.child_time_session = None
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                a1, first = self.open_quit()
                body = self.request("quit-2", "full_quit",
                                    expected_generation=a1["generation"])
                change(body)
                status, result = self.state.control_operation_acquire(
                    self.client, body, "quit-2", 0)
                # A joined request reads `operation_in_progress` and leaves the first alone.
                self.assertEqual((409, "terminal_result_timeout"), (status, result["reason"]))
                self.assertEqual("uncertain", self.state.control_state["active"]["state"])
                self.assertNotIn("quit-2", self.state.control_state["completed"])
                self.authorize()
                self.assertEqual(200, self.complete(first)[0])

    def test_full_quit_does_not_join_an_uncertain_first(self):
        a1, first = self.open_quit()
        self.assertEqual(409, self.complete(first, succeeded=False)[0])
        waits, waiting = self.waited()
        with waiting:
            status, result = self.acquire(self.request(
                "quit-2", "full_quit", expected_generation=a1["generation"]), 5.0)
        self.assertEqual((409, "uncertain", "effect_result_uncertain", []),
                         (status, result["state"], result["reason"], waits))
        self.assertNotIn("quit-2", self.state.control_state["completed"])
        self.assertEqual(200, self.complete(first)[0])
        self.assertEqual(409, self.acquire(self.request(
            "quit-2", "full_quit", expected_generation=a1["generation"]), 0)[0])

    # ---- D-2: abandon, close refusal reasons, Retry on a closed application ----

    def abandon(self, operation_id, child_session_id="", **generation):
        self.authorize()
        return self.state.control_operation_abandon(
            self.client, {"operation_id": operation_id,
                          "child_session_id": child_session_id, **generation}, operation_id)

    def test_abandon_without_effect_releases_the_permit_and_is_idempotent(self):
        _status, permit = self.acquire(self.request("cancelled"))
        self.observe_partial_scan(app_running=False)
        for _attempt in range(2):
            status, result = self.abandon("cancelled")
            self.assertEqual((200, True, False, "failed", "start_abandoned"), (
                status, result["released"], result["ok"], result["state"], result["reason"]))
        self.assertIsNone(self.state.control_state["active"])
        self.assertIsNone(self.state.control_state["stream"])
        # The released holder's late success is not the absent-effect receipt: it stands.
        self.assertEqual((200, "failed"), (
            self.complete(permit)[0], self.complete(permit)[1]["state"]))
        self.assertIsNone(self.state.control_state["stream"])
        status, play = self.acquire(self.request("next-play", session="stream-2"), 0)
        self.assertEqual((200, "permitted"), (status, play["state"]), play)

    def test_abandoned_start_is_a_new_attempt_under_the_same_operation_id(self):
        self.acquire(self.request("retry"))
        self.observe_partial_scan(app_running=False)
        self.assertTrue(self.abandon("retry")[1]["released"])
        status, again = self.acquire(self.request("retry"), 0)
        self.assertEqual((200, "permitted"), (status, again["state"]), again)
        self.assertEqual(200, self.complete(again)[0])
        self.assertEqual(again["generation"],
                         self.state.control_state["stream"]["generation"])

    def test_abandon_with_an_effect_records_the_stream_for_an_ordinary_close(self):
        for case, observe in {
                "app_running": lambda: self.observe_partial_scan(
                    app_running=True, scan="complete", process="ABSENT"),
                "transport_live": lambda: self.observe_partial_scan(
                    connected_count=1, app_running=True, scan="complete",
                    process="ABSENT")}.items():
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                operation_id = "started-" + case
                _status, permit = self.acquire(self.request(operation_id))
                observe()
                for _attempt in range(2):
                    status, result = self.abandon(operation_id)
                    self.assertEqual(
                        (200, False, "succeeded", "start_effect_present",
                         permit["generation"]),
                        (status, result["released"], result["state"], result["reason"],
                         result["generation"]))
                self.assertEqual(permit["generation"],
                                 self.state.control_state["stream"]["generation"])
                status, close = self.acquire(self.request(
                    "close-" + case, "full_quit",
                    expected_generation=result["generation"]), 0)
                self.assertEqual((200, "permitted"), (status, close["state"]), close)
                self.complete(close)

    def test_abandon_without_proof_keeps_the_permit_until_the_host_proves_it(self):
        self.acquire(self.request("unproven"))
        status, result = self.abandon("unproven")
        self.assertEqual((409, False, "uncertain", "start_abandoned"), (
            status, result["released"], result["state"], result["reason"]))
        self.assertEqual("unproven", self.state.control_state["active"]["operation_id"])
        status, blocked = self.acquire(self.request("next-play", session="stream-2"), 0)
        self.assertEqual((409, "start_abandoned"), (status, blocked["reason"]))
        # No grace is left: the first authoritative proof releases it.
        self.observe_partial_scan(app_running=False)
        self.assertTrue(self.state._control_resolve_stale_start())
        self.assertTrue(self.abandon("unproven")[1]["released"])
        self.assertEqual(200, self.acquire(self.request("next-play", session="stream-2"), 0)[0])

    def test_abandon_of_a_resume_keeps_the_retained_stream_record(self):
        a1 = self.launched_stream()
        self.observe_paused_app()
        _status, resume = self.acquire(self.request(
            "a1-resume", "resume", expected_generation=a1["generation"]), 0)
        self.assertEqual("permitted", resume["state"], resume)
        reads = self.track_reads()
        status, result = self.abandon("a1-resume")
        self.assertEqual((200, True, "start_abandoned"),
                         (status, result["released"], result["reason"]))
        self.assertEqual([], reads)
        self.assertEqual(a1["generation"], self.state.control_state["stream"]["generation"])
        status, close = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual((200, "permitted"), (status, close["state"]), close)

    def test_abandon_refuses_unknown_foreign_and_non_start_operations_alike(self):
        refused = (409, {"ok": False, "state": "blocked", "reason": "stale_operation"})
        self.assertEqual(refused, self.abandon("never-acquired"))
        self.acquire(self.request("foreign"))
        self.observe_partial_scan(app_running=False)
        for key, value in (("client_id", "other-tv"), ("actor_profile_id", "other"),
                           ("execution_profile_id", "other"),
                           ("child_session_id", "01234567-89ab-cdef-0123-456789abcdef")):
            with self.subTest(key=key):
                active = self.state.control_state["active"]
                original = active[key]
                active[key] = value
                self.assertEqual(refused, self.abandon("foreign"))
                self.assertEqual("permitted", active["state"])
                active[key] = original
        with self.assertRaises(ValueError):
            self.state.control_operation_abandon(
                self.client, {"operation_id": "foreign"}, "another-request")
        self.assertTrue(self.abandon("foreign")[1]["released"])
        # A foreign receipt is as unknown as a foreign permit.
        self.state.control_state["completed"]["foreign"]["client_id"] = "other-tv"
        self.assertEqual(refused, self.abandon("foreign"))
        a1 = self.launched_stream()
        self.observe_partial_scan(scan="complete", process="ABSENT")
        _status, close = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=a1["generation"]), 0)
        self.assertEqual("permitted", close["state"], close)
        self.assertEqual(refused, self.abandon("a1-quit"))
        self.assertEqual("a1-quit", self.state.control_state["active"]["operation_id"])

    def test_each_close_refusal_names_its_reason(self):
        self.observe_partial_scan(scan="complete", process="ABSENT")

        def close(operation_id, generation, **changes):
            body = {**self.request(operation_id, "full_quit",
                                   expected_generation=generation), **changes}
            status, result = self.acquire(body, 0)
            return status, result["reason"]

        self.assertEqual((409, "managed_stream_unavailable"), close("no-record", 1))
        a1 = self.launched_stream()
        generation = a1["generation"]
        stream = self.state.control_state["stream"]
        for key in ("client_id", "execution_profile_id"):
            with self.subTest(owner=key):
                original, stream[key] = stream[key], "other"
                self.assertEqual((409, "stream_owner_mismatch"),
                                 close("foreign-" + key, generation))
                stream[key] = original
        self.assertEqual((409, "stream_owner_mismatch"),
                         close("other-session", generation, stream_session_id="stream-9"))
        self.assertEqual((409, "stream_owner_mismatch"), close("future", generation + 5))
        self.assertEqual((409, "stream_generation_superseded"),
                         close("superseded", generation - 1))
        self.assertEqual((409, "unmanaged_stream"),
                         close("foreign-app", generation, observed_sunshine_app_id=99))
        self.assertEqual(generation, stream["generation"])
        self.assertIsNone(self.state.control_state["active"])
        status, result = self.acquire(self.request(
            "a1-quit", "full_quit", expected_generation=generation), 0)
        self.assertEqual((200, "permitted"), (status, result["state"]), result)

    def observe_closed_app_with_running_game(self):
        """The streamer closed the application; the provider still runs the game."""
        self.observe_partial_scan(
            app_running=False, scan="complete", process="VERIFIED",
            game={"state": "running", "id": "steam:7", "evidence": "VERIFIED"},
            running=[{"game_id": "steam:7", "process_id": 77, "process_token": "a" * 64}])

    def test_retry_on_a_closed_application_starts_fresh_and_replaces_the_record(self):
        for intent in ("reconnect", "launch"):
            with self.subTest(intent=intent):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                a1 = self.launched_stream(token="a" * 64)
                self.observe_closed_app_with_running_game()
                # The provider is not idle, so the idle reconcile keeps the record.
                self.assertFalse(self.state._control_reconcile_idle())
                with patch.object(wakeplay_gateway.DIAGNOSTICS, "record") as record:
                    status, retry = self.acquire(self.request(
                        "retry-" + intent, intent, observed_app_id=0,
                        expected_generation=a1["generation"]), 0)
                self.assertEqual((200, "permitted"), (status, retry["state"]), retry)
                self.assertIn("control-stream-closed-restart",
                              [call.args[0] for call in record.call_args_list])
                self.assertGreater(retry["generation"], a1["generation"])
                # Until the new start succeeds the record stays the close handle.
                self.assertEqual(a1["generation"],
                                 self.state.control_state["stream"]["generation"])
                self.assertEqual(200, self.complete(retry)[0])
                stream = self.state.control_state["stream"]
                # No game process identity is carried over without the Bridge's proof.
                self.assertEqual((retry["generation"], False), (
                    stream["generation"], bool(stream["provider_game_id"]
                                               or stream["process_token"]
                                               or stream["process_id"])))
                # The dead generation's late close finds its newer one and does nothing.
                status, late = self.acquire(self.request(
                    "late-" + intent, "full_quit",
                    expected_generation=a1["generation"]), 0)
                self.assertEqual((409, "stream_generation_superseded"),
                                 (status, late["reason"]))

    def observation(self, ordinal=1, actor="parent", execution="parent", provider=None):
        self.state.request_context.actor_profile_id = actor
        self.state.request_context.execution_profile_id = execution
        self.state.proxy = lambda *_a, **_k: (True, dict(provider or {
            "game": {"state": "idle", "id": "", "evidence": "ABSENT"},
            "process": {"evidence": "ABSENT", "process_id": 0, "process_token": "",
                        "started_unix_ms": 0}}))
        try:
            return self.state.playnite_observation(ordinal, "")
        finally:
            self.authorize()

    def test_stream_start_time_is_set_once_and_told_only_to_its_owner(self):
        self.assertEqual(0, self.observation()[1]["correlation"]["stream_started_unix_ms"])
        before = int(wakeplay_gateway.time.time() * 1000)
        _status, permit = self.acquire(self.request("a1-launch"))
        # A permitted start is not a stream yet.
        self.assertEqual(0, self.observation(2)[1]["correlation"]["stream_started_unix_ms"])
        self.complete(permit)
        started = self.state.control_state["stream"]["stream_started_unix_ms"]
        self.assertGreaterEqual(started, before)
        self.assertLessEqual(started, int(wakeplay_gateway.time.time() * 1000))
        self.assertEqual(started, self.observation(3)[1]["correlation"][
            "stream_started_unix_ms"])
        # Another actor or execution profile reads the stream identity but not its start.
        for actor, execution in (("kid", "parent"), ("other", "other")):
            correlation = self.observation(4, actor, execution)[1]["correlation"]
            self.assertEqual(("stream-1", 0), (
                correlation["stream_session_id"], correlation["stream_started_unix_ms"]))

    def test_stream_start_time_survives_reconnect_replacement_retry_and_restart(self):
        self.launched_stream()
        self.state.control_state["stream"]["stream_started_unix_ms"] = 1_234
        self.state._control_save_locked()
        generation = self.state.control_state["stream"]["generation"]
        # Reconnect on a new transport (the application stays paused on the host).
        self.observe_paused_app()
        status, resume = self.acquire(self.request(
            "a2-resume", "resume", session="stream-2"), 0)
        self.assertEqual(200, status, resume)
        self.complete(resume)
        self.assertEqual(("stream-2", 1_234), (
            self.state.control_state["stream"]["stream_session_id"],
            self.state.control_state["stream"]["stream_started_unix_ms"]))
        # Replacement by the same actor.
        status, replacement = self.acquire(self.request(
            "a3-replacement", "replacement", session="stream-3",
            expected_generation=resume["generation"]), 0)
        self.assertEqual(200, status, replacement)
        self.complete(replacement)
        self.assertEqual(("stream-3", 1_234), (
            self.state.control_state["stream"]["stream_session_id"],
            self.state.control_state["stream"]["stream_started_unix_ms"]))
        self.assertGreater(replacement["generation"], generation)
        # Retry after a lost transport: a fresh start on the retained record.
        self.observe_closed_app_with_running_game()
        status, retry = self.acquire(self.request(
            "a4-retry", "launch", session="stream-3", observed_app_id=0,
            expected_generation=replacement["generation"]), 0)
        self.assertEqual((200, "permitted"), (status, retry["state"]), retry)
        self.complete(retry)
        self.assertEqual((retry["generation"], 1_234), (
            self.state.control_state["stream"]["generation"],
            self.state.control_state["stream"]["stream_started_unix_ms"]))
        # A Gateway restart keeps it with the stream record.
        restarted = GatewayState(self.config_path)
        self.assertEqual(1_234, restarted.control_state["stream"]["stream_started_unix_ms"])

    def test_stream_start_time_ends_with_the_stream(self):
        launch = self.launched_stream()
        self.state.control_state["stream"]["stream_started_unix_ms"] = 1_234
        self.observe_provider_idle()
        status, stop = self.acquire(self.request(
            "a1-stop", "full_quit", expected_generation=launch["generation"]), 0)
        self.assertEqual(200, status, stop)
        self.complete(stop)
        self.assertIsNone(self.state.control_state["stream"])
        self.assertEqual(0, self.observation()[1]["correlation"]["stream_started_unix_ms"])
        _status, again = self.acquire(self.request("a2-launch", session="stream-2"))
        self.complete(again)
        self.assertGreater(self.state.control_state["stream"]["stream_started_unix_ms"], 1_234)

    def test_another_actor_taking_the_stream_over_starts_its_own_time(self):
        _status, parent = self.acquire(self.request("parent-stream"))
        self.complete(parent)
        self.state.control_state["stream"]["stream_started_unix_ms"] = 1_234
        self.state.config["profiles"]["kid"] = {
            "id": "kid", "kind": "child", "enabled": True,
            "parent_profile_id": "parent"}
        self.client["profile_grants"]["kid"] = ["use_profile"]
        child_session_id = "01234567-89ab-cdef-0123-456789abcdef"
        self.state.child_time_session = {
            "session_id": child_session_id, "client_id": "tv",
            "actor_profile_id": "kid", "execution_profile_id": "parent"}
        self.state.authorize_child_profile(self.client, "kid")
        self.observe_provider_idle()  # a fresh child start needs the provider's proof
        status, replacement = self.state.control_operation_acquire(self.client, {
            "operation_id": "child-replacement", "intent": "replacement",
            "stream_session_id": "child-stream", "sunshine_app_id": 7,
            "expected_generation": parent["generation"],
            "observed_sunshine_app_id": 7,
            "child_session_id": child_session_id,
        }, "child-replacement", 0)
        self.assertEqual(200, status, replacement)
        self.state.authorize_child_profile(self.client, "kid")
        status, _done = self.state.control_operation_complete(self.client, {
            "operation_id": "child-replacement",
            "generation": replacement["generation"],
            "actor_lease_epoch": replacement["actor_lease_epoch"],
            "succeeded": True}, "child-replacement")
        self.assertEqual(200, status)
        stream = self.state.control_state["stream"]
        self.assertEqual("kid", stream["actor_profile_id"])
        self.assertGreater(stream["stream_started_unix_ms"], 1_234)

    def test_changed_control_correlation_reports_no_process_start(self):
        self.launched_stream()

        def racing(*_a, **_k):
            self.state.control_state["stream"]["generation"] += 1
            return True, {"game": {"state": "running", "id": "steam:7",
                                   "evidence": "VERIFIED"},
                          "process": {"evidence": "VERIFIED", "process_id": 77,
                                      "process_token": "a" * 64,
                                      "started_unix_ms": 1_700_000_000_000}}
        self.state.proxy = racing
        status, observation = self.state.playnite_observation(1, "")
        self.assertEqual(200, status)
        self.assertEqual("control_correlation_changed", observation["reason"])
        self.assertEqual(0, observation["process"]["started_unix_ms"])

    def test_retry_keeps_the_record_without_proof_generation_or_ownership(self):
        cases = {
            "app_running": (lambda: self.observe_paused_app(), {}, {}),
            "transport_live": (lambda: self.observe_partial_scan(
                connected_count=1, app_running=False), {}, {}),
            "snapshot_unavailable": (lambda: None, {}, {}),
            "stale_generation": (self.observe_closed_app_with_running_game,
                                 {"expected_generation": 0}, {}),
            "other_client": (self.observe_closed_app_with_running_game, {},
                             {"client_id": "other-tv"}),
            "other_actor": (self.observe_closed_app_with_running_game, {},
                            {"actor_profile_id": "other"}),
            "other_app": (self.observe_closed_app_with_running_game, {},
                          {"sunshine_app_id": 9}),
        }
        for case, (observe, request_changes, stream_changes) in cases.items():
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                a1 = self.launched_stream(token="a" * 64)
                self.state.control_state["stream"].update(stream_changes)
                kept = dict(self.state.control_state["stream"])
                observe()
                status, result = self.acquire({**self.request(
                    "retry-" + case, "reconnect", observed_app_id=0,
                    expected_generation=a1["generation"]), **request_changes}, 0)
                self.assertEqual(409, status, result)
                self.assertIn(result["reason"], {"unmanaged_stream", "managed_stream_conflict"})
                self.assertEqual(kept, self.state.control_state["stream"])
                self.assertIsNone(self.state.control_state["active"])

    def test_retry_on_a_closed_application_waits_for_a_close_under_a_child_session(self):
        a1 = self.launched_stream(token="a" * 64)
        self.observe_closed_app_with_running_game()
        self.state.child_time_session = {
            "session_id": "01234567-89ab-cdef-0123-456789abcdef", "client_id": "tv",
            "actor_profile_id": "kid", "execution_profile_id": "parent"}
        status, result = self.acquire(self.request(
            "retry", "reconnect", observed_app_id=0,
            expected_generation=a1["generation"]), 0)
        self.assertEqual((409, "unmanaged_stream"), (status, result["reason"]))
        self.assertEqual(a1["generation"], self.state.control_state["stream"]["generation"])

    # ---- D-2 review: whose effect, kept close handle, same-ID retries, restart ----

    def observe_streamer(self, apps=(), clients=()):
        """A coherent authoritative snapshot: running app IDs and connected client UUIDs."""
        def proxy(name, path, **_kwargs):
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {
                    **control_evidence(len(clients), app_running=bool(apps)),
                    "clients": [{"uuid": uuid, "connected": True} for uuid in clients]
                    + [{"uuid": "ffffffff-89ab-cdef-0123-456789abcdef", "connected": False}],
                    "apps": [{"app_id": app_id, "running": True} for app_id in apps]
                    + [{"app_id": 3, "running": False}]}
            return False, {"error": "fixture_offline"}
        self.state.proxy = target_aware(proxy)

    def test_abandon_records_only_this_starts_own_effect(self):
        other = "89abcdef-0123-4567-89ab-cdef01234567"
        cases = {
            "own_app": ((7,), (), True),
            "own_app_and_client": ((7,), (VIBE_UUID.upper(),), True),
            "foreign_app": ((9,), (), False),
            "foreign_app_beside_own": ((7, 9), (), False),
            "foreign_client": ((7,), (other,), False),
            "foreign_client_beside_own": ((7,), (VIBE_UUID, other), False),
            "foreign_app_own_client": ((9,), (VIBE_UUID,), False),
            "transport_without_app": ((), (VIBE_UUID,), False),
        }
        for case, (apps, clients, own) in cases.items():
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                operation_id = "abandoned-" + case.replace("_", "-")
                _status, permit = self.acquire(self.request(operation_id))
                self.observe_streamer(apps, clients)
                status, result = self.abandon(operation_id)
                if own:
                    self.assertEqual(
                        (200, False, "succeeded", "start_effect_present"),
                        (status, result["released"], result["state"], result["reason"]))
                    self.assertEqual(permit["generation"],
                                     self.state.control_state["stream"]["generation"])
                    continue
                # Not this start's: the permit ends and nobody is made its owner.
                self.assertEqual((200, True, "failed", "start_abandoned"), (
                    status, result["released"], result["state"], result["reason"]))
                self.assertIsNone(self.state.control_state["stream"])
                self.assertIsNone(self.state.control_state["active"])
                status, close = self.acquire(self.request(
                    "close-" + case.replace("_", "-"), "full_quit",
                    expected_generation=permit["generation"]), 0)
                self.assertEqual((409, "managed_stream_unavailable"),
                                 (status, close["reason"]))
                if apps and apps != (7,):
                    status, play = self.acquire(self.request(
                        "play-" + case.replace("_", "-"), observed_app_id=apps[-1]), 0)
                    self.assertEqual((409, "unmanaged_stream"), (status, play["reason"]))

    def test_start_effect_needs_a_coherent_snapshot(self):
        operation = {"sunshine_app_id": 7, "vibepollo_client_uuid": VIBE_UUID}
        effect = ControlOperationPolicy.streamer_start_effect
        own = {**control_evidence(1, app_running=True),
               "clients": [{"uuid": VIBE_UUID, "connected": True}],
               "apps": [{"app_id": 7, "running": True}]}
        self.assertEqual("present", effect(own, operation))
        self.assertEqual("foreign", effect(own, {**operation, "vibepollo_client_uuid": ""}))
        self.assertEqual("foreign", effect(own, {**operation, "sunshine_app_id": 0}))
        self.assertEqual("absent", effect(
            {**control_evidence(0), "clients": [], "apps": []}, operation))
        for broken in ({"control_fresh": False}, {"clients": None}, {"apps": None},
                       {"clients": [{"uuid": VIBE_UUID, "connected": "yes"}]},
                       {"apps": [{"app_id": 7, "running": 1}]},
                       {"clients": []},  # The session count names a client the list lacks.
                       {"apps": []}):    # The session runs an app the list lacks.
            with self.subTest(broken=broken):
                self.assertEqual("unknown", effect({**own, **broken}, operation))

    def retry_on_closed_application(self):
        a1 = self.launched_stream(token="a" * 64)
        self.observe_closed_app_with_running_game()
        status, retry = self.acquire(self.request(
            "retry", "reconnect", observed_app_id=0,
            expected_generation=a1["generation"]), 0)
        self.assertEqual((200, "permitted"), (status, retry["state"]), retry)
        self.assertGreater(retry["generation"], a1["generation"])
        return a1, retry

    def test_failed_retry_on_a_closed_application_keeps_the_record_as_the_close_handle(self):
        def rejected(retry):
            self.authorize()
            status, result = self.state.control_operation_complete(self.client, {
                "operation_id": "retry", "generation": retry["generation"],
                "actor_lease_epoch": retry["actor_lease_epoch"], "succeeded": False,
                "effect_result": {"outcome": "definitive_failure"}}, "retry")
            self.assertEqual((200, "failed", "effect_rejected"),
                             (status, result["state"], result["reason"]))

        def abandoned(retry):
            reads = self.track_reads()
            status, result = self.abandon("retry", generation=retry["generation"])
            self.assertEqual((200, True, "start_abandoned"),
                             (status, result["released"], result["reason"]))
            self.assertEqual([], reads)  # The record answers; no streamer proof is needed.
            self.observe_closed_app_with_running_game()

        def silent(retry):
            self.assertEqual(409, self.complete(retry, succeeded=False)[0])
            # The provider still runs the game, the streamer is idle: the host ends the
            # permit, and the record of the running game stays.
            self.age_holder()
            self.assertTrue(self.state._control_resolve_stale_start())

        for end in (rejected, abandoned, silent):
            with self.subTest(end=end.__name__):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                a1, retry = self.retry_on_closed_application()
                kept = dict(self.state.control_state["stream"])
                self.assertEqual((a1["generation"], "a" * 64),
                                 (kept["generation"], kept["process_token"]))
                end(retry)
                self.assertIsNone(self.state.control_state["active"])
                self.assertEqual(kept, self.state.control_state["stream"])
                # "Close session" with the old generation still finds the session and closes
                # it on the Bridge's proof of the running game.
                status, close = self.acquire(self.request(
                    "close-" + end.__name__, "full_quit", observed_app_id=0,
                    expected_generation=a1["generation"]), 0)
                self.assertEqual((200, "permitted"), (status, close["state"]), close)
                self.assertEqual(("steam:7", "a" * 64, 77), (
                    close["provider_game_id"], close["process_token"], close["process_id"]))
                self.assertEqual(200, self.complete(close)[0])
                self.assertIsNone(self.state.control_state["stream"])

    def test_retry_waiting_on_its_own_cancelled_attempt_is_the_new_attempt(self):
        _status, first = self.acquire(self.request("cancel-retry"))
        self.observe_partial_scan(app_running=False)
        entered, result = threading.Event(), []

        def retry():
            entered.set()
            result.append(self.acquire(self.request("cancel-retry"), 5.0))

        thread = threading.Thread(target=retry)
        thread.start()
        self.assertTrue(entered.wait(1.0))
        thread.join(0.2)
        self.assertEqual([], result)  # It waits for the attempt in flight.
        self.assertTrue(self.abandon("cancel-retry", generation=first["generation"])[1][
            "released"])
        thread.join(5.0)
        status, again = result[0]
        self.assertEqual((200, "permitted"), (status, again["state"]), again)
        self.assertGreater(again["generation"], first["generation"])
        self.assertNotIn("cancel-retry", self.state.control_state["completed"])
        self.assertEqual((200, "succeeded"), (
            self.complete(again)[0], self.state.control_state["completed"][
                "cancel-retry"]["state"]))
        self.assertEqual(again["generation"],
                         self.state.control_state["stream"]["generation"])

    def test_abandon_with_a_known_generation_must_name_the_permits(self):
        refused = (409, {"ok": False, "state": "blocked", "reason": "stale_operation"})
        _status, old = self.acquire(self.request("same-id"))
        self.observe_partial_scan(app_running=False)
        self.assertEqual(refused, self.abandon("same-id", generation=old["generation"] + 1))
        self.assertEqual("permitted", self.state.control_state["active"]["state"])
        self.assertTrue(self.abandon("same-id", generation=old["generation"])[1]["released"])
        _status, new = self.acquire(self.request("same-id"), 0)
        # The old attempt's abandon, repeated by HTTP, does not release the new permit.
        self.assertEqual(refused, self.abandon("same-id", generation=old["generation"]))
        self.assertEqual(("same-id", "permitted", new["generation"]), tuple(
            self.state.control_state["active"][key]
            for key in ("operation_id", "state", "generation")))
        self.assertTrue(self.abandon("same-id", generation=new["generation"])[1]["released"])
        # The receipt replays to its own generation only.
        self.assertEqual(refused, self.abandon("same-id", generation=old["generation"]))
        self.assertTrue(self.abandon("same-id", generation=new["generation"])[1]["released"])
        self.assertTrue(self.abandon("same-id")[1]["released"])
        for invalid in (-1, True, "1", 1.0, None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.abandon("same-id", generation=invalid)

    def test_restarted_gateway_keeps_an_abandoned_start_without_grace(self):
        self.acquire(self.request("abandoned-before-restart"))
        self.assertEqual(409, self.abandon("abandoned-before-restart")[0])
        restarted = GatewayState(self.config_path)
        active = restarted.control_state["active"]
        self.assertEqual(("uncertain", "start_abandoned"), (active["state"], active["reason"]))
        self.assertLessEqual(restarted._control_stale_start_locked()[2], 0)

        def idle(name, path, **_kwargs):
            if name == "vibepollo" and path == "/snapshot?force=1":
                return True, {**control_evidence(0, app_running=False),
                              "clients": [], "apps": []}
            return False, {"error": "fixture_offline"}

        restarted.proxy = idle
        restarted.child_time_housekeeping_tick()  # The first proof ends it: no grace anew.
        self.assertIsNone(restarted.control_state["active"])
        self.assertEqual("start_effect_absent", restarted.control_state["completed"][
            "abandoned-before-restart"]["reason"])

    def test_retry_on_its_own_unproven_abandoned_start_is_proven_within_the_acquire(self):
        for retry_id in ("abandoned-unproven", "another-play"):
            with self.subTest(retry_id=retry_id):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                _status, first = self.acquire(self.request("abandoned-unproven"))
                self.assertEqual(409, self.abandon("abandoned-unproven")[0])
                # Still no proof: the permit holds the host.
                status, blocked = self.acquire(self.request(retry_id), 0)
                self.assertEqual((409, "uncertain", "start_abandoned"),
                                 (status, blocked["state"], blocked["reason"]))
                # The proof is read by this acquire, not by a later housekeeping tick.
                self.observe_partial_scan(app_running=False)
                status, again = self.acquire(self.request(retry_id), 0)
                self.assertEqual((200, "permitted"), (status, again["state"]), again)
                self.assertGreater(again["generation"], first["generation"])
                status, finished = self.complete(again)
                self.assertEqual((200, "succeeded"), (status, finished["state"]))
                self.assertEqual(again["generation"],
                                 self.state.control_state["stream"]["generation"])

    def test_a_reported_uncertain_start_is_not_proven_for_its_own_retry(self):
        _status, permit = self.acquire(self.request("reported"))
        self.assertEqual(409, self.complete(permit, succeeded=False)[0])
        self.observe_partial_scan(app_running=False)
        reads = self.track_reads()
        status, retry = self.acquire(self.request("reported"), 0)
        self.assertEqual((409, "effect_result_uncertain"), (status, retry["reason"]))
        self.assertEqual([], reads)

    def test_abandon_of_a_reported_uncertain_start(self):
        for case, observe, expected in (
                ("absent", lambda: self.observe_partial_scan(app_running=False),
                 (200, "failed", "start_abandoned", True)),
                ("own-effect", lambda: self.observe_streamer((7,), (VIBE_UUID,)),
                 (200, "succeeded", "start_effect_present", False)),
                ("unproven", lambda: None, (409, "uncertain", "start_abandoned", False))):
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                _status, permit = self.acquire(self.request("reported-" + case))
                status, reported = self.complete(permit, succeeded=False)
                self.assertEqual((409, "effect_result_uncertain"),
                                 (status, reported["reason"]))
                observe()
                status, result = self.abandon("reported-" + case,
                                              generation=permit["generation"])
                self.assertEqual(expected, (status, result["state"], result["reason"],
                                            result["released"]))
                if case == "unproven":
                    # The holder's declaration costs the permit its 15 s grace.
                    self.assertLessEqual(self.state._control_stale_start_locked()[2], 0)

    def test_late_success_after_an_unresolved_or_host_resolved_abandon_is_accepted(self):
        for case in ("unresolved", "host-resolved"):
            with self.subTest(case=case):
                self.state.control_state.update(
                    {"active": None, "stream": None, "completed": {}})
                self.state.proxy = lambda *_a, **_k: (False, {"error": "fixture_offline"})
                _status, permit = self.acquire(self.request("late-" + case))
                status, result = self.abandon("late-" + case)
                self.assertEqual((409, "start_abandoned"), (status, result["reason"]))
                if case == "host-resolved":
                    self.observe_partial_scan(app_running=False)
                    self.assertTrue(self.state._control_resolve_stale_start())
                    self.assertEqual("start_effect_absent", self.state.control_state[
                        "completed"]["late-" + case]["reason"])
                # The start was sent after all: nothing was granted since, so it is recorded.
                status, late = self.complete(permit)
                self.assertEqual((200, "succeeded"), (status, late["state"]), late)
                self.assertEqual(permit["generation"],
                                 self.state.control_state["stream"]["generation"])

    def test_abandon_is_a_control_operation_route_with_its_own_body(self):
        source = Path(wakeplay_gateway.__file__).read_text(encoding="utf-8")
        self.assertIn('"abandon": {"operation_id", "child_session_id", "generation"},',
                      source)
        self.assertIn('f"{API_PREFIX}/control/operation/abandon",', source)
        self.assertEqual("control_operations_v2",
                         wakeplay_gateway.CONTROL_OPERATIONS_CAPABILITY)
        version = json.loads((Path(wakeplay_gateway.__file__).parents[1]
                              / "version.json").read_text(encoding="utf-8-sig"))
        self.assertEqual(7, version["protocol_version"])

if __name__ == "__main__":
    unittest.main()
