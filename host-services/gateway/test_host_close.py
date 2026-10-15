import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import wakeplay_gateway
from control_operation_policy import ControlOperationPolicy
from control_snapshot_fixture import control_evidence
from provider_target_fixture import target_aware
from wakeplay_gateway import GatewayState, sha256_text
from vibepollo_client import STREAM_APP_UUID
from control_snapshot_fixture import route_vibepollo_through_proxy

route_vibepollo_through_proxy(wakeplay_gateway.GatewayState)

VIBE_UUID = "01234567-89ab-cdef-0123-456789abcdef"
TOKEN = "a" * 64
REQUEST = "0123456789abcdef0123456789abcdef"
FOREIGN_UUID = "99999999-9999-4999-8999-999999999999"


class HostCloseTest(unittest.TestCase):
    """Host Control's "Zamknij sesję": the request file, its validation and the host operation."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temp_dir.name) / "gateway.json"
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "profiles": {
                "parent": {"id": "parent", "kind": "standard", "name": "Parent",
                           "enabled": True},
                "other": {"id": "other", "kind": "standard", "name": "Other",
                          "enabled": True},
                "kid": {"id": "kid", "kind": "child", "name": "Kid", "enabled": True,
                        "parent_profile_id": "parent"},
            },
            "clients": [{"id": "tv", "token_sha256": sha256_text("token"),
                         "profile_grants": {"parent": ["use_profile"]},
                         "vibepollo_client_uuids": {"parent": VIBE_UUID}}],
        }), encoding="utf-8")
        self.request_path = self.config_path.with_name("host-close-request.json")
        self.result_path = self.config_path.with_name("host-close-result.json")
        self.request_path.write_text("{}", encoding="utf-8")
        self.game = None        # the provider's verified running game, or None when idle
        self.running_app = ""   # UUID of the application the streamer runs
        self.connected = False  # the TV's transport
        self.attributed = True  # the snapshot names the TV's UUID for its transport
        self.close_ignored = False  # the streamer accepts close-app and keeps running
        self.observation = None  # None: derived from self.game; False: the Bridge is down
        self.stop_refused = False
        self.calls = []
        self.stops = []
        self.abandons = []
        self.restart()

    def tearDown(self):
        self.temp_dir.cleanup()

    def restart(self):
        self.state = GatewayState(self.config_path)
        self.state.proxy = target_aware(self.proxy)
        self.state.proxy_json = self.proxy_json
        self.client = self.state.client_for_token("token")

    def proxy(self, name, path, timeout=2.5, profile_id=None):
        del timeout
        self.calls.append((name, path, profile_id))
        if name == "game_provider" and path == "/observation":
            if self.observation is not None:
                return bool(self.observation), json.loads(json.dumps(self.observation or {}))
            running = [dict(self.game)] if self.game else []
            return True, {
                "game": {"state": "running" if running else "idle",
                         "id": self.game["game_id"] if running else "",
                         "evidence": "VERIFIED" if running else "ABSENT"},
                "process": {"evidence": "VERIFIED" if running else "ABSENT",
                            "running_games": running, "running_scan_status": "complete"}}
        if name == "vibepollo" and path == "/snapshot?force=1":
            return True, {
                **control_evidence(int(self.connected), bool(self.running_app)),
                "clients": [{"uuid": VIBE_UUID,
                             "connected": self.connected and self.attributed}],
                "apps": [{"app_id": app_id, "uuid": uuid, "running": uuid == self.running_app}
                         for app_id, uuid in ((7, STREAM_APP_UUID),
                                              (99, FOREIGN_UUID))]}
        if name == "vibepollo" and path == "/action/close-app":
            if not self.close_ignored:
                self.running_app, self.connected = "", False
            return True, {"ok": True}
        return False, {"error": "fixture_offline"}

    def proxy_json(self, name, path, body, timeout=8.0, profile_id=None):
        del timeout
        self.calls.append((name, path, profile_id))
        if name == "game_provider" and path == "/game/stop-verified":
            self.stops.append(dict(body))
            if self.game and not self.stop_refused \
                    and body.get("expected_process_token") == self.game["process_token"]:
                game_id, self.game = self.game["game_id"], None
                return True, {"ok": True, "accepted": True, "command": "stop",
                              "stopped_game_id": game_id, "stopped_current": True}
            return True, {"ok": True, "accepted": False,
                          "reason": "running_game_identity_changed"}
        if name == "game_provider" and path == "/game/abandon-start":
            self.abandons.append(dict(body))
            return True, {"ok": True, "accepted": True, "command": "abandon-start"}
        return False, {"error": "fixture_offline"}

    def run_game(self):
        self.game = {"game_id": "steam:7", "process_id": 77, "process_token": TOKEN}

    def acquire(self, operation_id, intent="launch", expected_generation=0, observed=0):
        self.state.authorize_profile(self.client, "parent")
        return self.state.control_operation_acquire(self.client, {
            "operation_id": operation_id, "intent": intent, "stream_session_id": "stream-1",
            "sunshine_app_id": 7, "expected_generation": expected_generation,
            "observed_sunshine_app_id": observed, "child_session_id": ""}, operation_id)

    def complete(self, permit):
        self.state.authorize_profile(self.client, "parent")
        return self.state.control_operation_complete(self.client, {
            "operation_id": permit["operation_id"], "generation": permit["generation"],
            "actor_lease_epoch": permit["actor_lease_epoch"], "succeeded": True},
            permit["operation_id"])

    def start_stream(self, with_game=True, operation_id="start-1"):
        status, permit = self.acquire(operation_id)
        self.assertEqual(200, status)
        self.assertEqual(200, self.complete(permit)[0])
        self.running_app, self.connected = STREAM_APP_UUID, True
        if with_game:
            self.run_game()
            self.state.control_state["stream"].update({
                "provider_game_id": "steam:7", "process_token": TOKEN, "process_id": 77})
        return self.state.control_state["stream"]["generation"]

    def write_request(self, request_id=REQUEST, ttl=30, profile_id="parent"):
        # Windows PowerShell 5.1 writes UTF-8 with a byte order mark.
        self.request_path.write_text(json.dumps({
            "request_id": request_id, "expires_at": int(time.time()) + ttl,
            "profile_id": profile_id}), encoding="utf-8-sig")

    def poll(self):
        # The Gateway polls on its housekeeping thread, which has no request context.
        thread = threading.Thread(target=self.state.host_close_poll)
        thread.start()
        thread.join(30.0)
        self.assertFalse(thread.is_alive())

    def idle_tick(self, age=0.0):
        """One housekeeping tick whose idle probe is due, with the idle wait aged."""
        self.state.host_idle_next_probe = 0.0
        watch = self.state.host_idle_watch
        if watch is not None and age:
            self.state.host_idle_watch = (watch[0], watch[1] - age)
        thread = threading.Thread(target=self.state.child_time_housekeeping_tick)
        thread.start()
        thread.join(30.0)
        self.assertFalse(thread.is_alive())

    def abandon_stream(self, with_game=False):
        """A record whose client went away: the streamer keeps the application paused."""
        generation = self.start_stream(with_game=with_game)
        self.connected = False
        return generation

    def result(self):
        return json.loads(self.result_path.read_text(encoding="utf-8")) \
            if self.result_path.exists() else None

    def closes(self):
        return [call for call in self.calls if call[1] == "/action/close-app"]

    def assert_result(self, state, reason, request_id=REQUEST):
        result = self.result()
        self.assertEqual({"request_id", "state", "reason", "updated_at"}, set(result))
        self.assertEqual((request_id, state, reason),
                         (result["request_id"], result["state"], result["reason"]))

    def test_request_closes_the_stream_and_its_verified_game(self):
        generation = self.start_stream()
        self.write_request()
        self.poll()

        self.assert_result("completed", "stream_and_game_closed")
        self.assertEqual("{}", self.request_path.read_text(encoding="utf-8"))
        self.assertIsNone(self.game)
        self.assertEqual("", self.running_app)
        self.assertIsNone(self.state.control_state["stream"])
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual(generation + 1, self.state.control_state["generation"])
        self.assertEqual(1, len(self.stops))
        self.assertEqual((False, "steam:7", TOKEN), tuple(self.stops[0][key] for key in (
            "force", "game_id", "expected_process_token")))
        binding = self.stops[0]["control_operation"]
        self.assertEqual(("host-close:" + REQUEST, generation + 1, "parent", "stream-1"),
                         tuple(binding[key] for key in (
                             "operation_id", "generation", "execution_profile_id",
                             "stream_session_id")))
        # The game stops before the streamer closes, as in the client's full quit.
        paths = [call[1] for call in self.calls]
        self.assertLess(paths.index("/game/stop-verified"), paths.index("/action/close-app"))
        receipt = self.state.control_state["completed"]["host-close:" + REQUEST]
        self.assertEqual(("full_quit", "succeeded", "tv"),
                         (receipt["intent"], receipt["state"], receipt["client_id"]))

    def test_client_with_the_old_generation_finds_no_managed_stream_afterwards(self):
        generation = self.start_stream()
        self.write_request()
        self.poll()
        self.assert_result("completed", "stream_and_game_closed")

        status, late = self.acquire("late-close", "full_quit", generation, 0)
        self.assertEqual(409, status)
        self.assertEqual("managed_stream_unavailable", late["reason"])
        self.assertEqual(409, self.acquire("late-resume", "resume", generation, 0)[0])
        self.assertEqual(1, len(self.closes()))

    def test_stream_without_a_game_closes_only_the_stream(self):
        self.start_stream(with_game=False)
        self.write_request()
        self.poll()

        self.assert_result("completed", "stream_closed")
        self.assertEqual([], self.stops)
        self.assertEqual(1, len(self.closes()))
        self.assertIsNone(self.state.control_state["stream"])

    def test_game_without_a_stream_record_is_stopped_and_a_foreign_app_is_left(self):
        self.run_game()
        self.running_app = FOREIGN_UUID
        self.write_request()
        self.poll()

        self.assert_result("completed", "game_closed")
        self.assertIsNone(self.game)
        self.assertEqual(FOREIGN_UUID, self.running_app)
        self.assertEqual([], self.closes())
        binding = self.stops[0]["control_operation"]
        self.assertEqual(("host-close:" + REQUEST, 1, 1, "parent", "parent", "host-close:1"),
                         tuple(binding[key] for key in (
                             "operation_id", "generation", "actor_lease_epoch",
                             "actor_profile_id", "execution_profile_id", "stream_session_id")))
        self.assertEqual(TOKEN, self.stops[0]["expected_process_token"])
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual("succeeded", self.state.control_state["completed"][
            "host-close:" + REQUEST]["state"])

    def test_own_streamer_app_without_a_record_is_closed_with_the_game(self):
        self.run_game()
        self.running_app = STREAM_APP_UUID
        self.write_request()
        self.poll()

        self.assert_result("completed", "stream_and_game_closed")
        self.assertEqual("", self.running_app)
        self.assertIsNone(self.game)

    def test_nothing_to_close_changes_nothing(self):
        self.running_app = FOREIGN_UUID
        self.write_request()
        self.poll()

        self.assert_result("completed", "nothing_to_close")
        self.assertEqual([], self.stops)
        self.assertEqual([], self.closes())
        self.assertEqual(0, int(self.state.control_state.get("generation") or 0))

    def test_unverified_game_is_refused_and_left_running(self):
        self.game = {"game_id": "steam:7", "process_id": 77}  # no verified process token
        self.write_request()
        self.poll()

        self.assert_result("refused", "game_state_unverified")
        self.assertEqual([], self.stops)
        self.assertEqual([], self.closes())

    def test_unconfirmed_game_closes_the_stream_alone_and_withdraws_the_record(self):
        for case, observation in (("bridge_down", False), ("other_process", None)):
            with self.subTest(case=case):
                self.calls.clear()
                generation = self.start_stream(operation_id="start-" + case)
                self.observation = observation
                if observation is None:  # the provider runs a process the record does not name
                    self.game = {"game_id": "steam:7", "process_id": 78,
                                 "process_token": "b" * 64}
                request_id = sha256_text(case)[:32]
                self.write_request(request_id)
                self.poll()

                self.assert_result("completed", "stream_closed_game_unconfirmed", request_id)
                self.assertEqual([], self.stops)
                self.assertIsNotNone(self.game)
                self.assertEqual(1, len(self.closes()))
                self.assertEqual("", self.running_app)
                self.assertIsNone(self.state.control_state["stream"])
                self.assertIsNone(self.state.control_state["active"])
                receipt = self.state.control_state["completed"]["host-close:" + request_id]
                self.assertEqual(("full_quit", "succeeded", True, True, ""), tuple(
                    receipt.get(key) for key in ("intent", "state", "host_held",
                                                 "game_unconfirmed", "provider_game_id")))
                # The TV's old generation closes nothing more.
                self.observation = None
                status, late = self.acquire("late-" + case, "full_quit", generation, 0)
                self.assertEqual((409, "managed_stream_unavailable"), (status, late["reason"]))
                self.game = None

    def test_unconfirmed_game_never_closes_a_foreign_streamer_application(self):
        generation = self.start_stream()
        self.observation = False
        self.running_app = FOREIGN_UUID
        self.write_request()
        self.poll()

        self.assert_result("refused", "streamer_identity_unconfirmed")
        self.assertEqual([], self.closes())
        self.assertEqual(FOREIGN_UUID, self.running_app)
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])

    def test_a_client_close_still_needs_the_confirmed_game(self):
        generation = self.start_stream()
        self.observation = False
        status, refused = self.acquire("owner-close", "full_quit", generation, 7)

        self.assertEqual((409, "retained_process_unconfirmed"), (status, refused["reason"]))
        self.assertIsNone(self.state.control_state["active"])
        self.assertIsNotNone(self.state.control_state["stream"])

    def test_unconfirmed_close_abandons_the_start_the_provider_still_shows(self):
        self.start_stream(with_game=False)
        self.observation = {
            "game": {"state": "starting", "id": "steam:9", "evidence": "VERIFIED"},
            "process": {"evidence": "PENDING", "running_games": [],
                        "running_scan_status": "partial"}}
        self.write_request()
        self.poll()

        self.assert_result("completed", "stream_closed_game_unconfirmed")
        self.assertIsNone(self.state.control_state["stream"])
        self.assertEqual([], self.stops)
        self.assertEqual(1, len(self.abandons))
        self.assertEqual("steam:9", self.abandons[0]["game_id"])
        self.assertEqual("host-close:" + REQUEST,
                         self.abandons[0]["control_operation"]["operation_id"])

    def test_unverified_game_without_a_record_closes_only_the_own_stream(self):
        self.game = {"game_id": "steam:7", "process_id": 77}  # no verified process token
        self.running_app = STREAM_APP_UUID
        self.write_request()
        self.poll()

        self.assert_result("completed", "stream_closed_game_unconfirmed")
        self.assertEqual([], self.stops)
        self.assertEqual(1, len(self.closes()))
        self.assertEqual("", self.running_app)
        self.assertIsNotNone(self.game)
        receipt = self.state.control_state["completed"]["host-close:" + REQUEST]
        self.assertEqual(("host_close", "succeeded", True),
                         (receipt["intent"], receipt["state"], receipt["host_held"]))
        # A start the provider shows without a process is dropped; the Bridge decides.
        self.assertEqual(["steam:7"], [item["game_id"] for item in self.abandons])

    def test_game_that_does_not_stop_leaves_the_streamer_and_says_so(self):
        self.run_game()
        self.running_app = STREAM_APP_UUID
        self.stop_refused = True
        self.write_request()
        self.poll()

        self.assert_result("refused", "game_stop_unconfirmed")
        self.assertEqual(1, len(self.stops))
        self.assertEqual([], self.closes())
        self.assertEqual(STREAM_APP_UUID, self.running_app)
        self.assertIsNotNone(self.game)
        self.assertEqual("failed", self.state.control_state["completed"][
            "host-close:" + REQUEST]["state"])

    def test_streamer_running_another_app_keeps_the_record_and_ends_the_permit(self):
        generation = self.start_stream()
        self.running_app = FOREIGN_UUID
        self.write_request()
        self.poll()

        self.assert_result("refused", "streamer_identity_unconfirmed")
        self.assertEqual([], self.closes())
        self.assertEqual(FOREIGN_UUID, self.running_app)
        # Decided before the game stops: a refused close leaves the game too.
        self.assertEqual([], self.stops)
        self.assertIsNotNone(self.game)
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])
        # The owner can still close its record afterwards.
        self.running_app = STREAM_APP_UUID
        status, permit = self.acquire("owner-close", "full_quit", generation, 7)
        self.assertEqual((200, "permitted"), (status, permit["state"]))

    def test_unattributed_session_of_the_own_app_is_closed_by_the_host(self):
        # Lab: the TV streams (session count 1) but no client is marked connected.
        self.start_stream()
        self.attributed = False
        self.write_request()
        self.poll()

        self.assert_result("completed", "stream_and_game_closed")
        self.assertIsNone(self.game)
        self.assertEqual("", self.running_app)
        self.assertIsNone(self.state.control_state["stream"])

    def test_streamer_that_does_not_close_after_the_game_says_both(self):
        self.start_stream()
        self.close_ignored = True
        self.write_request()
        self.poll()

        self.assert_result("refused", "game_closed_stream_unconfirmed")
        self.assertIsNone(self.game)
        self.assertEqual(STREAM_APP_UUID, self.running_app)
        self.assertIsNotNone(self.state.control_state["stream"])
        self.assertEqual("failed", self.state.control_state["completed"][
            "host-close:" + REQUEST]["state"])

    def test_host_rule_ignores_missing_attribution_but_not_a_foreign_app_or_client(self):
        stream = {"sunshine_app_id": 7, "vibepollo_client_uuid": VIBE_UUID}

        def snapshot(count, clients, app_id=7):
            return {**control_evidence(count, True), "clients": clients,
                    "apps": [{"app_id": item, "running": item == app_id}
                             for item in (7, 99)]}

        host = ControlOperationPolicy.host_streamer_close_allowed
        client = ControlOperationPolicy.streamer_close_allowed
        lab = snapshot(1, [{"uuid": VIBE_UUID, "connected": False}])
        self.assertTrue(host(lab, stream))
        self.assertFalse(client(lab, stream))
        own = snapshot(1, [{"uuid": VIBE_UUID, "connected": True}])
        self.assertTrue(host(own, stream))
        self.assertTrue(client(own, stream))
        for case, value in (
                ("foreign_app", snapshot(1, [{"uuid": VIBE_UUID, "connected": False}], 99)),
                ("foreign_client", snapshot(1, [{"uuid": FOREIGN_UUID, "connected": True}])),
                ("more_clients_than_sessions", snapshot(0, [
                    {"uuid": VIBE_UUID, "connected": True}])),
                ("not_authoritative", {**lab, "control_fresh": False}),
                ("malformed_clients", {**lab, "clients": [{"uuid": VIBE_UUID}]})):
            with self.subTest(case=case):
                self.assertFalse(host(value, stream))
                self.assertFalse(client(value, stream))

    def test_expired_and_overlong_requests_are_refused(self):
        self.start_stream()
        for request_id, ttl in (("1" * 32, -1), ("2" * 32, 0), ("3" * 32, 61)):
            # One frozen wall clock for the request and the Gateway: a second boundary
            # between writing and reading would otherwise make 61 s fit the 60 s limit.
            with mock.patch.object(time, "time", return_value=float(int(time.time()))):
                self.write_request(request_id, ttl)
                self.poll()
            self.assert_result("refused", "request_expired", request_id)
            self.assertEqual("{}", self.request_path.read_text(encoding="utf-8"))
        self.assertEqual([], self.stops)
        self.assertEqual([], self.closes())
        self.assertIsNotNone(self.state.control_state["stream"])

    def test_replayed_request_is_not_executed_again(self):
        self.start_stream()
        self.write_request(ttl=30)
        replay = self.request_path.read_bytes()
        self.poll()
        self.assert_result("completed", "stream_and_game_closed")
        self.start_stream(operation_id="start-2")
        self.request_path.write_bytes(replay)
        self.poll()
        self.poll()

        self.assert_result("completed", "stream_and_game_closed")
        self.assertEqual("{}", self.request_path.read_text(encoding="utf-8"))
        self.assertEqual(1, len(self.stops))
        self.assertEqual(1, len(self.closes()))
        self.assertIsNotNone(self.state.control_state["stream"])
        self.assertIsNotNone(self.game)

    def test_corrupt_oversized_and_misshapen_files_execute_nothing(self):
        self.start_stream()
        valid = {"request_id": REQUEST, "expires_at": int(time.time()) + 30,
                 "profile_id": "parent"}
        for raw in (b"not json", b"\xff\xfe\x00", b"[1, 2]", b'"text"',
                    json.dumps({**valid, "padding": "x" * 2000}).encode(),
                    json.dumps({**valid, "command": "calc.exe"}).encode(),
                    json.dumps({**valid, "request_id": "../../x"}).encode(),
                    json.dumps({**valid, "request_id": REQUEST.upper()}).encode(),
                    json.dumps({**valid, "expires_at": True}).encode(),
                    json.dumps({**valid, "expires_at": "soon"}).encode(),
                    json.dumps({**valid, "profile_id": "..\\\\other"}).encode(),
                    json.dumps({key: valid[key] for key in ("request_id", "expires_at")})
                    .encode()):
            with self.subTest(raw=raw[:24]):
                self.assertIsNone(GatewayState._host_close_parse(raw))
                self.request_path.write_bytes(raw)
                self.poll()
                # A first read may be a write in flight; the same content twice is garbage.
                self.assertEqual(raw, self.request_path.read_bytes())
                self.poll()
                self.assertEqual(b"{}", self.request_path.read_bytes())
        self.assertIsNone(self.result())
        self.assertEqual([], self.stops)
        self.assertEqual([], self.closes())
        self.assertIsNone(self.state.control_state["active"])

    def test_partial_write_is_not_consumed_before_the_request_is_complete(self):
        self.start_stream()
        self.write_request()
        whole = self.request_path.read_bytes()
        self.request_path.write_bytes(whole[:20])
        self.poll()
        self.request_path.write_bytes(whole)
        self.poll()

        self.assert_result("completed", "stream_and_game_closed")

    def test_unknown_child_and_other_profiles_are_refused(self):
        self.start_stream()
        for request_id, profile_id, reason in (
                ("1" * 32, "missing", "unknown_profile"),
                ("2" * 32, "kid", "unknown_profile"),
                ("3" * 32, "other", "session_on_other_profile")):
            self.write_request(request_id, profile_id=profile_id)
            self.poll()
            self.assert_result("refused", reason, request_id)
        self.assertEqual([], self.stops)
        self.assertEqual([], self.closes())
        self.assertIsNotNone(self.state.control_state["stream"])

    def test_client_operation_in_flight_is_waited_out_never_superseded(self):
        status, permit = self.acquire("start-1")
        self.assertEqual(200, status)
        self.write_request()
        self.poll()
        self.poll()

        self.assert_result("accepted", "none")
        active = self.state.control_state["active"]
        self.assertEqual(("start-1", "permitted"), (active["operation_id"], active["state"]))
        self.assertEqual([], self.closes())
        self.assertEqual(200, self.complete(permit)[0])
        self.running_app, self.connected = STREAM_APP_UUID, True
        self.poll()

        self.assert_result("completed", "stream_closed")
        self.assertIsNone(self.state.control_state["stream"])
        self.assertEqual("succeeded", self.state.control_state["completed"]["start-1"]["state"])

    def test_request_expiring_behind_a_client_operation_is_refused(self):
        status, _permit = self.acquire("start-1")
        self.assertEqual(200, status)
        self.write_request()
        self.poll()
        self.assert_result("accepted", "none")
        self.state.host_close_pending["expires_at"] = int(time.time()) - 1
        self.poll()

        self.assert_result("refused", "operation_in_progress")
        self.assertIsNone(self.state.host_close_pending)
        active = self.state.control_state["active"]
        self.assertEqual(("start-1", "permitted"), (active["operation_id"], active["state"]))
        self.assertEqual([], self.closes())

    def test_host_permit_left_by_a_gateway_restart_ends_and_frees_the_host(self):
        generation = self.start_stream()
        self.state.authorize_profile(self.client, "parent")
        status, permit = self.state.control_operation_acquire(self.client, {
            "operation_id": "host-close:" + REQUEST, "intent": "full_quit",
            "stream_session_id": "stream-1", "sunshine_app_id": 7,
            "expected_generation": generation, "observed_sunshine_app_id": 0,
            "child_session_id": ""}, "host-close:" + REQUEST, host_held=True)
        self.assertEqual((200, "permitted"), (status, permit["state"]))
        self.restart()

        self.assertIsNone(self.state.control_state["active"])
        receipt = self.state.control_state["completed"]["host-close:" + REQUEST]
        self.assertEqual(("failed", "gateway_restarted"), (receipt["state"], receipt["reason"]))
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])
        self.write_request("f" * 32)
        self.poll()
        self.assert_result("completed", "stream_and_game_closed", "f" * 32)

    def test_a_permit_is_the_hosts_by_its_mark_not_by_its_operation_id(self):
        generation = self.start_stream()
        # A permit a client holds stays its own across a restart, whatever its ID looks like.
        status, permit = self.acquire("host-close:" + REQUEST, "full_quit", generation, 7)
        self.assertEqual((200, "permitted"), (status, permit["state"]))
        self.assertNotIn("host_held", self.state.control_state["active"])
        self.restart()

        active = self.state.control_state["active"]
        self.assertEqual(("host-close:" + REQUEST, "uncertain"),
                         (active["operation_id"], active["state"]))
        self.assertNotIn("host-close:" + REQUEST, self.state.control_state["completed"])

    def test_stream_nobody_uses_is_closed_after_the_idle_wait(self):
        generation = self.abandon_stream()
        self.idle_tick()
        self.assertIsNotNone(self.state.host_idle_watch)
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS - 5)
        self.assertEqual([], self.closes())
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])

        self.idle_tick(10)
        self.assertEqual(1, len(self.closes()))
        self.assertEqual("", self.running_app)
        self.assertIsNone(self.state.control_state["stream"])
        self.assertIsNone(self.state.control_state["active"])
        self.assertIsNone(self.state.host_idle_watch)
        receipt = self.state.control_state["completed"][f"host-idle:{generation}"]
        self.assertEqual(("full_quit", "succeeded", True, "tv"), tuple(
            receipt.get(key) for key in ("intent", "state", "host_held", "client_id")))
        self.assertEqual([], self.stops)
        status, late = self.acquire("late-resume", "resume", generation, 0)
        self.assertEqual(409, status)

    def test_idle_wait_never_closes_a_used_stream(self):
        age = wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS + 5
        for case in ("game", "client", "operation", "child_session"):
            with self.subTest(case=case):
                self.calls.clear()
                generation = self.start_stream(with_game=False,
                                               operation_id="start-" + case)
                self.connected = False
                self.idle_tick()
                self.assertIsNotNone(self.state.host_idle_watch)
                permit = None
                if case == "game":  # the paused session keeps its game for a resume
                    self.run_game()
                elif case == "client":
                    self.connected = True
                elif case == "operation":
                    status, permit = self.acquire("close-" + case, "full_quit", generation, 7)
                    self.assertEqual(200, status)
                else:
                    self.state.child_time_session = {"session_id": "s"}
                if case == "child_session":
                    self.state.host_idle_next_probe = 0.0
                    self.state.host_idle_watch = (self.state.host_idle_watch[0],
                                                  self.state.host_idle_watch[1] - age)
                    self.state._host_idle_close()
                    self.state.child_time_session = None
                else:
                    self.idle_tick(age)

                self.assertEqual([], self.closes())
                self.assertEqual([], self.stops)
                self.assertIsNone(self.state.host_idle_watch)
                self.assertEqual(generation, self.state.control_state["stream"]["generation"])
                # Clear the record for the next case.
                self.game, self.connected = None, True
                if permit is None:
                    status, permit = self.acquire("close-" + case, "full_quit", generation, 7)
                    self.assertEqual(200, status)
                self.running_app, self.connected = "", False
                self.assertEqual(200, self.complete(permit)[0])
                self.assertIsNone(self.state.control_state["stream"])

    def test_idle_wait_starts_again_when_the_client_comes_back(self):
        generation = self.abandon_stream()
        self.idle_tick()
        first = self.state.host_idle_watch[1]
        self.connected = True
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS - 5)
        self.assertIsNone(self.state.host_idle_watch)
        self.connected = False
        self.idle_tick()
        self.assertGreaterEqual(self.state.host_idle_watch[1], first)
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS - 5)

        self.assertEqual([], self.closes())
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])
        # A control operation on the record restarts the wait as well: its counters moved.
        status, permit = self.acquire("resume-1", "resume", generation, 7)
        self.assertEqual(200, status, permit)
        self.assertEqual(200, self.complete(permit)[0])
        self.idle_tick(10)
        self.assertEqual([], self.closes())
        self.assertIsNotNone(self.state.control_state["stream"])

    def test_unreadable_sample_neither_starts_nor_ends_the_idle_wait(self):
        self.abandon_stream()
        self.observation = False
        self.idle_tick()
        self.assertIsNone(self.state.host_idle_watch)
        self.observation = None
        self.idle_tick()
        watch = self.state.host_idle_watch
        self.assertIsNotNone(watch)
        self.observation = False
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS + 5)

        self.assertEqual(watch[0], self.state.host_idle_watch[0])
        self.assertEqual([], self.closes())

    def test_idle_probe_is_sampled_between_ticks(self):
        self.abandon_stream()
        self.idle_tick()
        snapshots = len(self.calls)
        self.state._host_idle_close()
        self.assertEqual(snapshots, len(self.calls))

    def test_idle_wait_starts_again_after_a_gateway_restart(self):
        generation = self.abandon_stream()
        self.idle_tick()
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS - 5)
        self.restart()

        self.assertIsNone(self.state.host_idle_watch)
        self.idle_tick(10)
        self.assertEqual([], self.closes())
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS + 5)
        self.assertEqual(1, len(self.closes()))
        self.assertIsNone(self.state.control_state["stream"])

    def test_idle_close_leaves_a_game_that_started_meanwhile(self):
        generation = self.abandon_stream()
        self.idle_tick()
        # The sample still reads idle; under the permit the provider shows a game.
        self.state._host_idle_observation = lambda stream: True
        self.run_game()
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS + 5)

        self.assertEqual([], self.closes())
        self.assertEqual([], self.stops)
        self.assertIsNotNone(self.game)
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])
        receipt = self.state.control_state["completed"][f"host-idle:{generation}"]
        self.assertEqual("failed", receipt["state"])

    def test_status_of_a_record_without_a_bound_game_keeps_its_types(self):
        # An adopted start: the record has no provider game yet (None in the record).
        self.start_stream(with_game=False)
        self.assertIsNone(self.state.control_state["stream"].get("provider_game_id"))
        self.state.authorize_profile(self.client, "parent")
        status, body = self.state.control_operation_status(self.client)

        self.assertEqual((200, "owned"), (status, body["state"]))
        self.assertEqual(("", "", "", 0), tuple(body[key] for key in (
            "provider_game_id", "process_token", "child_session_id", "process_id")))
        self.assertEqual(("stream-1", 7, "parent"), tuple(body[key] for key in (
            "stream_session_id", "sunshine_app_id", "actor_profile_id")))
        self.assertNotIn(None, body.values())

    def test_idle_close_keeps_the_strict_rule_for_an_unattributed_session(self):
        generation = self.abandon_stream()
        self.idle_tick()
        # A client connects after the idle sample; the streamer does not attribute it yet.
        self.state._host_idle_observation = lambda stream: True
        self.connected, self.attributed = True, False
        self.idle_tick(wakeplay_gateway.HOST_IDLE_STREAM_CLOSE_SECONDS + 5)

        self.assertEqual([], self.closes())
        self.assertEqual(STREAM_APP_UUID, self.running_app)
        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])
        receipt = self.state.control_state["completed"][f"host-idle:{generation}"]
        self.assertEqual("failed", receipt["state"])

    def test_unreadable_streamer_without_a_record_closes_nothing(self):
        for case, snapshot in (("http_error", (False, {"error": "offline"})),
                               ("not_authoritative", None)):
            with self.subTest(case=case):
                self.calls.clear()
                self.stops.clear()
                self.run_game()
                self.running_app = STREAM_APP_UUID
                inner = self.state.proxy

                def proxy(name, path, *args, value=snapshot, **kwargs):
                    if name == "vibepollo" and path == "/snapshot?force=1":
                        if value is not None:
                            return value
                        ok, observed = inner(name, path, *args, **kwargs)
                        return ok, {**observed, "control_fresh": False}
                    return inner(name, path, *args, **kwargs)
                self.state.proxy = proxy
                request_id = sha256_text(case)[:32]
                self.write_request(request_id)
                self.poll()
                self.state.proxy = inner

                self.assert_result("refused", "streamer_snapshot_unavailable", request_id)
                self.assertEqual([], self.stops)
                self.assertEqual([], self.closes())
                self.assertIsNotNone(self.game)
                self.assertEqual(STREAM_APP_UUID, self.running_app)
                self.assertIsNone(self.state.control_state["active"])
                receipt = self.state.control_state["completed"]["host-close:" + request_id]
                self.assertEqual(("failed", "streamer_snapshot_unavailable"),
                                 (receipt["state"], receipt["reason"]))

    def test_host_idle_permit_left_by_a_gateway_restart_ends(self):
        generation = self.abandon_stream()
        self.state.authorize_profile(self.client, "parent")
        operation_id = f"host-idle:{generation}"
        status, permit = self.state.control_operation_acquire(self.client, {
            "operation_id": operation_id, "intent": "full_quit",
            "stream_session_id": "stream-1", "sunshine_app_id": 7,
            "expected_generation": generation, "observed_sunshine_app_id": 0,
            "child_session_id": ""}, operation_id, host_held=True)
        self.assertEqual((200, "permitted"), (status, permit["state"]))
        self.restart()

        self.assertIsNone(self.state.control_state["active"])
        self.assertEqual("gateway_restarted",
                         self.state.control_state["completed"][operation_id]["reason"])
        self.assertEqual(generation, self.state.control_state["stream"]["generation"])

    def test_only_the_reserved_application_alone_counts_as_moonwakers(self):
        def snapshot(*running):
            return {**control_evidence(0, bool(running)), "clients": [],
                    "apps": [{"uuid": uuid, "running": uuid in running}
                             for uuid in (STREAM_APP_UUID, FOREIGN_UUID)]}

        own = ControlOperationPolicy.streamer_runs_only
        self.assertTrue(own(snapshot(STREAM_APP_UUID), STREAM_APP_UUID))
        self.assertFalse(own(snapshot(), STREAM_APP_UUID))
        self.assertFalse(own(snapshot(FOREIGN_UUID), STREAM_APP_UUID))
        self.assertFalse(own(snapshot(STREAM_APP_UUID, FOREIGN_UUID),
                             STREAM_APP_UUID))
        self.assertFalse(own({**snapshot(STREAM_APP_UUID), "control_fresh": False},
                             STREAM_APP_UUID))
        self.assertFalse(own({**snapshot(STREAM_APP_UUID), "apps": None},
                             STREAM_APP_UUID))
        self.assertFalse(own(None, STREAM_APP_UUID))


if __name__ == "__main__":
    unittest.main()
