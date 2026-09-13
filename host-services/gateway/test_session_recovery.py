"""A/B/C through the real Gateway and Bridge HTTP dispatch, with fake processes."""

import os
import copy
import threading
import unittest
from control_snapshot_fixture import control_evidence
from pathlib import Path
from unittest import mock
from uuid import UUID

import test_child_time_control as child_fixture
from test_child_time_control import VIBE_UUID
from test_child_library_integration import BridgeFixture, BridgeState
from wakeplay_gateway import GatewayState


class SessionRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = child_fixture.ChildTimeControlTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.gateway = self.fixture.state
        self.client = self.fixture.client
        self.client["profile_grants"]["parent"] = ["use_profile"]
        self.gateway.config["profiles"]["kid"]["allowed_game_keys"].append("parent/steam:456")
        second_child = copy.deepcopy(self.gateway.config["profiles"]["kid"])
        second_child.update(id="kid-b", name="Kid B", display_name="Kid B")
        self.gateway.config["profiles"]["kid-b"] = second_child
        self.client["profile_grants"]["kid-b"] = ["use_profile"]
        self.bridge = BridgeFixture(Path(self.fixture.temp_dir.name) / "provider")
        service = self.bridge.state.game_operations
        self.bridge.state = BridgeState(game_operations=service, profile_id="parent",
            active_game_path=Path(self.fixture.temp_dir.name) / "active-game.json",
            clock=lambda: self.fixture.clock.wall.timestamp(), stop_timeout=.01)
        self.processes = {}
        own = {"process_id": os.getpid(), "process_path": r"C:\Bridge\bridge.exe",
               "process_started_filetime": 1, "user_sid": "own", "session_id": 1}
        probe = mock.Mock()
        probe.process_identity.side_effect = lambda pid, **kw: (
            own if pid == os.getpid() else self.processes.get(pid))
        probe.scan_running_processes.side_effect = lambda: (list(self.processes.values()), "complete")
        probe.force_terminate_verified_process.side_effect = lambda value: bool(
            self.processes.pop(value["process_id"], None))
        self.bridge.state.running_process_probe = probe
        probe.stop_verified_process.side_effect = lambda value, _timeout: bool(
            self.processes.pop(value["process_id"], None))
        self.bridge.state.set_reconciliation_actions(
            lambda pid: own if pid == os.getpid() else self.processes.get(pid),
            lambda path: [value for value in self.processes.values()
                          if value["process_path"].casefold() == path.casefold()],
            lambda pid: (("alive", own) if pid == os.getpid() else
                         ("alive", self.processes[pid]) if pid in self.processes else
                         ("gone", None)),
        )
        self.bridge.state.graceful_close = lambda pid: bool(self.processes.pop(pid, None))
        service.launch = mock.Mock(return_value={"accepted": True, "command": "launch"})
        self.bridge.set_library([{ "id": "steam:" + app, "provider": "steam",
            "providerGameId": app, "name": app, "installDir": "C:\\Games\\" + app,
            "exe": "game.exe"} for app in ("123", "456")])
        self.stream_active = False
        self.closes = []
        self.serial = 0
        self.wire(self.gateway)

    def wire(self, gateway):
        gateway.child_profiles_api_enabled = True
        gateway.proxy_json = self.bridge.proxy_json
        gateway.child_time_clock = self.fixture.clock
        gateway.child_terminal_operation.clock = lambda: self.fixture.clock.monotonic
        gateway.child_terminal_operation.wait = self.fixture.clock.advance
        def proxy(name, path, **kwargs):
            if name == "vibepollo":
                if path == "/snapshot?force=1":
                    return True, {**control_evidence(int(self.stream_active), self.stream_active),
                                  "clients": [{"uuid": VIBE_UUID, "connected": self.stream_active}],
                                  "apps": [{"app_id": 7, "running": self.stream_active}]}
                if path == "/action/close-app":
                    self.closes.append(dict(gateway.control_state["active"]))
                    self.stream_active = False
                    return False, {"ok": False, "error": "HTTP 500 after actual close"}
                self.fail("Unexpected streamer endpoint " + path)
            return self.bridge.proxy(name, path, **kwargs)
        gateway.proxy = proxy

    def acquire(self, actor, intent, child_id="", stream=None):
        self.serial += 1
        self.gateway.authorize_profile(self.client, actor, allow_child=True)
        stream = stream or self.gateway.control_state.get("stream") or {}
        op = "scenario-" + str(self.serial)
        return self.gateway.control_operation_acquire(self.client, {
            "operation_id": op, "intent": intent, "stream_session_id": stream.get("stream_session_id", op),
            "sunshine_app_id": 7, "expected_generation": stream.get("generation", 0),
            "observed_sunshine_app_id": 7 if self.stream_active else 0,
            "child_session_id": child_id}, op, 0)

    def start_child(self, game="123", retained=False):
        self.serial += 1
        child_id = str(UUID(int=self.serial))
        self.gateway.authorize_profile(self.client, "kid", allow_child=True)
        status, result = self.gateway.child_time_session_start(
            self.client, "kid", "steam:" + game, child_id, "allowance-" + str(self.serial))
        self.assertEqual(202, status, result)
        status, permit = self.acquire("kid", "reauthorize" if retained else "launch", child_id)
        self.assertEqual(200, status, permit)
        if not retained:
            self.stream_active = True
            status, result = self.gateway.control_operation_complete(self.client, {
                "operation_id": permit["operation_id"], "generation": permit["generation"],
                "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True}, permit["operation_id"])
            self.assertEqual(200, status, result)
        self.gateway.request_context.request_id = "game-start-" + child_id
        status, result = self.gateway.child_playnite_action(
            self.client, "kid", "game/start", {"game_id": "steam:" + game})
        self.assertEqual(200, status, result)
        pid = 5000 + self.serial
        identity = {"process_id": pid, "process_path": "C:\\Games\\" + game + "\\game.exe",
                    "process_started_filetime": 100 + self.serial, "user_sid": "own",
                    "session_id": 1, "visible_window": True}
        self.processes[pid] = identity
        self.bridge.state.current.update(state="running", processId=pid,
            processPath=identity["process_path"], processStartedFiletime=identity["process_started_filetime"])
        self.bridge.state.refresh_running_games()
        status, result = self.gateway.child_time_session_heartbeat(self.client, "kid", child_id)
        self.assertEqual(200, status, result)
        self.assertEqual(pid, self.gateway.child_time_session["process_id"])
        return child_id

    def test_allowed_game_switch_uses_common_stop_and_reuses_retained_stream(self):
        first = self.start_child()
        status, result = self.gateway.child_time_session_end(self.client, "kid", first, "client_closed")
        self.assertEqual(200, status, result)
        self.assertEqual(1, len(self.closes))
        second = self.start_child("456")
        old = dict(self.gateway.control_state["stream"])
        self.gateway.request_context.request_id = "carousel-switch-stop"
        status, result = self.gateway.child_playnite_action(
            self.client, "kid", "game/stop-verified", {
                "game_id": "steam:456",
                "expected_process_token": self.gateway.child_time_session["process_token"]})
        self.assertEqual(200, status, result)
        status, result = self.gateway.child_time_session_end(
            self.client, "kid", second, "game_stopped")
        self.assertEqual(200, status, result)
        third = self.start_child("123", retained=True)
        self.assertEqual(old["stream_session_id"],
                         self.gateway.control_state["stream"]["stream_session_id"])
        self.assertEqual(third, self.gateway.control_state["stream"]["child_session_id"])
        self.assertGreater(self.gateway.control_state["stream"]["generation"], old["generation"])
        self.assertEqual(1, len(self.closes))

    def test_same_allowed_running_game_is_adopted_without_provider_restart(self):
        self.gateway.authorize_profile(self.client, "parent")
        status, parent = self.acquire("parent", "launch")
        self.assertEqual(200, status, parent)
        self.stream_active = True
        status, result = self.gateway.control_operation_complete(self.client, {
            "operation_id": parent["operation_id"], "generation": parent["generation"],
            "actor_lease_epoch": parent["actor_lease_epoch"], "succeeded": True,
        }, parent["operation_id"])
        self.assertEqual(200, status, result)
        identity = {"process_id": 7001, "process_path": r"C:\Games\123\game.exe",
                    "process_started_filetime": 701, "user_sid": "own",
                    "session_id": 1, "visible_window": True}
        self.processes[identity["process_id"]] = identity
        self.bridge.state.current = {
            "state": "running", "id": "steam:123", "processId": identity["process_id"],
            "processPath": identity["process_path"],
            "processStartedFiletime": identity["process_started_filetime"],
        }
        self.bridge.state.refresh_running_games()

        child_id = str(UUID(int=8001))
        self.gateway.authorize_profile(self.client, "kid", allow_child=True)
        status, pending = self.gateway.child_time_session_start(
            self.client, "kid", "steam:123", child_id, "adopt-same-game")
        self.assertEqual(202, status, pending)
        original_token = self.gateway.child_time_session["process_token"]
        self.assertRegex(original_token, r"^[0-9a-f]{64}$")
        self.assertEqual("kid", self.gateway.control_state["stream"]["actor_profile_id"])
        self.assertEqual(child_id,
                         self.gateway.control_state["stream"]["child_session_id"])
        self.assertEqual(pending["control_generation"],
                         self.gateway.control_state["stream"]["generation"])
        self.assertGreater(pending["control_generation"], parent["generation"])
        status, adopted = self.acquire("kid", "reauthorize", child_id)

        self.assertEqual(200, status, adopted)
        self.assertEqual("succeeded", adopted["state"])
        self.assertEqual(child_id, self.gateway.control_state["stream"]["child_session_id"])
        self.assertEqual(original_token,
                         self.gateway.control_state["stream"]["process_token"])
        status, bootstrap = self.acquire("kid", "reconnect", child_id)
        self.assertEqual(200, status, bootstrap)
        self.assertEqual("permitted", bootstrap["state"])
        status, completed = self.gateway.control_operation_complete(self.client, {
            "operation_id": bootstrap["operation_id"],
            "generation": bootstrap["generation"],
            "actor_lease_epoch": bootstrap["actor_lease_epoch"],
            "succeeded": True,
        }, bootstrap["operation_id"])
        self.assertEqual(200, status, completed)
        self.assertEqual(original_token,
                         self.gateway.control_state["stream"]["process_token"])
        self.assertEqual(7001, self.bridge.state.current["processId"])
        self.bridge.state.game_operations.launch.assert_not_called()

        replacement = {**identity, "process_started_filetime": 702}
        self.processes[identity["process_id"]] = replacement
        self.bridge.state.current["processStartedFiletime"] = 702
        self.bridge.state.refresh_running_games()
        status, rejected = self.acquire("kid", "reauthorize", child_id)
        self.assertEqual(409, status, rejected)
        self.assertEqual("retained_process_unconfirmed", rejected["reason"])
        self.assertEqual(original_token,
                         self.gateway.control_state["stream"]["process_token"])

    def test_parent_child_and_child_to_child_handoff_move_one_actor_and_time_attachment(self):
        first = self.start_child()
        first_stream = dict(self.gateway.control_state["stream"])
        status, released = self.gateway.child_time_session_end(
            self.client, "kid", first, "actor_replaced", "parent")
        self.assertEqual(200, status, released)
        self.assertEqual("parent", self.gateway.control_state["stream"]["actor_profile_id"])
        self.assertEqual("", self.gateway.control_state["stream"]["child_session_id"])
        self.assertEqual(released["control_generation"],
                         self.gateway.control_state["stream"]["generation"])
        status, parent = self.acquire("parent", "reauthorize")
        self.assertEqual(200, status, parent)
        self.assertEqual("parent", self.gateway.control_state["stream"]["actor_profile_id"])
        self.assertGreater(parent["generation"], first_stream["generation"])

        child_a = str(UUID(int=8101))
        self.gateway.authorize_profile(self.client, "kid", allow_child=True)
        status, _ = self.gateway.child_time_session_start(
            self.client, "kid", "steam:123", child_a, "handoff-child-a")
        self.assertEqual(202, status)
        status, actor_a = self.acquire("kid", "reauthorize", child_a)
        self.assertEqual(200, status, actor_a)

        child_b = str(UUID(int=8102))
        self.gateway.authorize_profile(self.client, "kid-b", allow_child=True)
        status, _ = self.gateway.child_time_session_start(
            self.client, "kid-b", "steam:123", child_b, "handoff-child-b")
        self.assertEqual(202, status)
        self.assertEqual("kid-b", self.gateway.control_state["stream"]["actor_profile_id"])
        self.assertEqual(child_b, self.gateway.control_state["stream"]["child_session_id"])
        self.assertGreater(self.gateway.control_state["stream"]["generation"],
                           actor_a["generation"])
        status, actor_b = self.acquire("kid-b", "reauthorize", child_b)

        self.assertEqual(200, status, actor_b)
        self.assertEqual("kid-b", self.gateway.control_state["stream"]["actor_profile_id"])
        self.assertEqual(child_b, self.gateway.child_time_session["session_id"])
        self.assertEqual(child_b, self.gateway.control_state["stream"]["child_session_id"])
        self.assertGreater(actor_b["generation"], actor_a["generation"])
        self.assertGreater(actor_b["actor_lease_epoch"], actor_a["actor_lease_epoch"])
        self.assertEqual("actor_replaced", self.gateway.child_time_completed[child_a]["reason"])

    def test_child_suspend_is_fenced_against_concurrent_actor_handoff(self):
        child_a = self.start_child()
        self.gateway.authorize_profile(self.client, "kid-b", allow_child=True)
        effect_entered = threading.Event()
        allow_effect = threading.Event()
        suspend_results = []
        handoff_results = []

        def blocked_suspend(_body, _request_id):
            effect_entered.set()
            self.assertTrue(allow_effect.wait(1.0))
            return 202, {"ok": True, "accepted": True,
                         "suspend_id": "suspend-a", "session": {}}

        self.gateway.suspend_session = blocked_suspend
        suspend = threading.Thread(target=lambda: suspend_results.append(
            self.gateway.child_suspend_session(self.client, "kid", {
                "suspend_id": "suspend-a", "sunshine_app_id": 7,
                "playnite_game_id": "steam:123", "title": "Allowed",
            }, "suspend-a")))
        child_b = str(UUID(int=8202))
        handoff = threading.Thread(target=lambda: handoff_results.append(
            self.gateway.child_time_session_start(
                self.client, "kid-b", "steam:123", child_b, "handoff-after-suspend")))
        suspend.start()
        self.assertTrue(effect_entered.wait(1.0))
        handoff.start()
        handoff.join(0.05)
        self.assertTrue(handoff.is_alive())
        allow_effect.set()
        suspend.join(1.0)
        handoff.join(1.0)

        self.assertFalse(suspend.is_alive())
        self.assertFalse(handoff.is_alive())
        self.assertEqual(202, suspend_results[0][0], suspend_results)
        self.assertTrue(suspend_results[0][1]["child_time_released"])
        self.assertEqual(202, handoff_results[0][0], handoff_results)
        self.assertEqual("suspended",
                         self.gateway.child_time_completed[child_a]["reason"])
        self.assertEqual(child_b, self.gateway.child_time_session["session_id"])
        self.assertEqual("kid-b", self.gateway.control_state["stream"]["actor_profile_id"])
        self.assertEqual(child_b,
                         self.gateway.control_state["stream"]["child_session_id"])

    def test_uncertain_terminal_retry_retires_old_process_and_never_stops_replacement(self):
        child_id = self.start_child()
        old_session = self.gateway.child_time_session
        old_token = old_session["process_token"]
        original = self.gateway.proxy_json

        def lose_stop_response(name, path, body, **kwargs):
            ok, result = original(name, path, body, **kwargs)
            if path == "/game/stop-verified":
                raise TimeoutError("response lost after exact process stopped")
            return ok, result

        self.gateway.proxy_json = lose_stop_response
        status, first = self.gateway.child_time_session_end(
            self.client, "kid", child_id, "client_closed")
        self.assertEqual(409, status, first)
        self.assertTrue(first["cleanup_required"])
        self.assertEqual("uncertain", self.gateway.control_state["active"]["state"])

        replacement = {
            "process_id": 9901, "process_path": r"C:\Games\123\game.exe",
            "process_started_filetime": 9901, "user_sid": "own",
            "session_id": 1, "visible_window": True,
        }
        self.processes[replacement["process_id"]] = replacement
        self.bridge.state.current.update(
            state="running", id="steam:123", processId=replacement["process_id"],
            processPath=replacement["process_path"],
            processStartedFiletime=replacement["process_started_filetime"])
        self.bridge.state.refresh_running_games()
        self.gateway.proxy_json = original

        status, retried = self.gateway.child_time_session_status(
            self.client, "kid", child_id)
        self.assertEqual(200, status, retried)
        self.assertFalse(retried["cleanup_required"])
        self.assertIsNone(self.gateway.control_state["active"])
        self.assertIsNone(self.gateway.control_state["stream"])
        self.assertIsNone(self.gateway.child_time_session)
        self.assertIn(replacement["process_id"], self.processes)
        self.assertNotEqual(old_token, self.gateway._child_time_process_token(
            self.bridge.state.current_snapshot()["running_games"][0]))

    def test_b_lost_provider_stop_restart_parent_cleanup_retires_exact_operation(self):
        self._lost_stop_parent_cleanup("hard_reset")

    def test_b_full_quit_also_recovers_lost_provider_stop_after_restart(self):
        self._lost_stop_parent_cleanup("full_quit")

    def _lost_stop_parent_cleanup(self, intent):
        child_id = self.start_child("456")
        stream = dict(self.gateway.control_state["stream"])
        original = self.bridge.proxy_json
        delayed = []
        def lose(name, path, body, **kwargs):
            if path == "/game/stop-verified":
                delayed.append((name, path, dict(body), kwargs))
                raise TimeoutError("not delivered yet")
            return original(name, path, body, **kwargs)
        self.gateway.proxy_json = lose
        self.gateway.request_context.request_id = "lost-provider-stop"
        status, _ = self.gateway.child_playnite_action(
            self.client, "kid", "game/stop-verified", {
                "game_id": "steam:456",
                "expected_process_token": self.gateway.child_time_session["process_token"]})
        self.assertNotEqual(200, status)
        uncertain = dict(self.gateway.control_state["active"])
        self.assertEqual("uncertain", uncertain["state"])
        self.gateway = GatewayState(self.fixture.config_path, None)
        self.gateway.config["clients"][0]["profile_grants"]["parent"] = ["use_profile"]
        self.client = self.gateway.config["clients"][0]
        self.wire(self.gateway)
        status, result = self.acquire("parent", intent, stream=stream)
        self.assertEqual(200, status, result)
        self.assertEqual("succeeded", result["state"])
        self.assertIsNone(self.gateway.control_state["active"])
        self.assertIsNone(self.gateway.control_state["stream"])
        self.assertIsNone(self.gateway.child_time_session)
        self.assertEqual("failed", self.gateway.control_state["completed"][uncertain["operation_id"]]["state"])
        # Delayed old RPC arrives after the barrier/new parent generation.
        status, parent = self.acquire("parent", "launch")
        self.assertEqual(200, status, parent)
        self.gateway.control_operation_complete(self.client, {
            "operation_id": parent["operation_id"], "generation": parent["generation"],
            "actor_lease_epoch": parent["actor_lease_epoch"], "succeeded": True}, parent["operation_id"])
        effects = len(self.closes)
        name, path, body, kwargs = delayed[0]
        ok, rejected = original(name, path, body, **kwargs)
        self.assertFalse(ok, rejected)
        self.assertEqual(effects, len(self.closes))
        self.assertEqual(parent["generation"], self.gateway.control_state["stream"]["generation"])

    def test_c_late_child_cleanup_cannot_touch_reauthorized_parent(self):
        child_id = self.start_child()
        child = self.gateway.child_time_session
        status, _ = self.gateway.child_time_session_end(self.client, "kid", child_id, "game_stopped")
        self.assertEqual(200, status)
        status, parent = self.acquire("parent", "reauthorize")
        self.assertEqual(200, status, parent)
        calls = len(self.bridge.calls)
        status, result = self.gateway._child_time_finish(self.client, child, "daily_limit_reached", self.fixture.clock.wall)
        self.assertEqual(409, status, result)
        self.assertEqual(calls, len(self.bridge.calls))
        self.assertEqual([], self.closes)
        self.assertEqual("parent", self.gateway.control_state["stream"]["actor_profile_id"])

    def test_cancelled_child_handoff_compensation_closes_only_its_exact_allowance(self):
        child_id = self.start_child()
        status, result = self.gateway.child_time_session_end(self.client, "kid", child_id, "cancelled")
        self.assertEqual(200, status, result)
        self.assertEqual([], self.closes)
        self.assertIsNotNone(self.gateway.control_state["stream"])
        self.assertIsNone(self.gateway.child_time_session)

    def test_late_parent_handoff_compensation_rejects_newer_retained_generation(self):
        child_id = self.start_child()
        self.gateway.child_time_session_end(self.client, "kid", child_id, "game_stopped")
        status, first = self.acquire("parent", "reauthorize")
        self.assertEqual(200, status, first)
        old_stream = dict(self.gateway.control_state["stream"])
        status, newer = self.acquire("parent", "reauthorize")
        self.assertEqual(200, status, newer)
        status, result = self.acquire("parent", "full_quit", stream=old_stream)
        self.assertEqual(409, status, result)
        self.assertEqual([], self.closes)
        self.assertEqual(newer["generation"], self.gateway.control_state["stream"]["generation"])


if __name__ == "__main__":
    unittest.main()
