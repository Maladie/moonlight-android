import base64
import copy
import hashlib
import io
import json
import struct
import tempfile
import time
import unittest
from email.message import Message
from types import SimpleNamespace
from pathlib import Path
from unittest import mock

import wakeplay_gateway
from wakeplay_gateway import GatewayState, LoginBrokerClient, sha256_text


class FakeBroker:
    """Minimal Broker seam for Gateway contract tests."""

    def __init__(self):
        self.profile = self.reply(True, "signed_out", fields={3: "ready", 4: "none"})
        self.child_begin_result = self.reply(
            True, "pending", fields={3: "0123456789abcdef0123456789abcdef"})
        self.legacy_begin_calls = []
        self.child_begin_calls = []

    @staticmethod
    def reply(success, state, reason="none", fields=None):
        return {"success": success, "state": state, "reason": reason,
                "fields": fields or {}}

    def profile_state(self, _profile):
        return self.profile

    def capability(self):
        return self.reply(True, "ready", fields={3: "1"})

    def begin(self, *args):
        self.legacy_begin_calls.append(args)
        raise AssertionError("child flow fell back to the legacy Broker operation")

    def begin_child(self, client_id, actor_profile_id, profile, request_id):
        self.child_begin_calls.append(
            (client_id, actor_profile_id, profile["id"], request_id))
        return self.child_begin_result


class ChildProfilesGatewayTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temporary.name) / "gateway.json"
        self.broker = FakeBroker()
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
                    "windows_account_sid": "S-1-5-21-1-2-3-1001",
                    "windows_account_name": "TESTPC\\Player",
                    "remote_sign_in_enabled": True,
                },
                "kid": {
                    "id": "kid",
                    "kind": "child",
                    "name": "Kid",
                    "display_name": "Kid",
                    "parent_profile_id": "living-room",
                    "enabled": True,
                    "policy_revision": 0,
                    "allowed_game_keys": [],
                },
            },
            "clients": [{
                "id": "android-tv",
                "token_sha256": sha256_text("token"),
                "profile_grants": {
                    "living-room": ["use_profile", "remote_sign_in"],
                },
            }],
        }), encoding="utf-8")
        self.state = GatewayState(
            self.config_path, None, broker_client=self.broker)
        self.client = self.state.config["clients"][0]

    def tearDown(self):
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

    def test_pairing_creates_grants_only_for_standard_profiles(self):
        self.state.pairing_code_hash = sha256_text("123456")
        self.state.pairing_expires_at = time.monotonic() + 60

        result = self.state.pair("192.0.2.1", "123456", "Child test TV")

        self.assertEqual(["living-room"],
                         [profile["id"] for profile in result["profiles"]])
        paired = next(client for client in self.state.config["clients"]
                      if client["id"] == result["client_id"])
        self.assertEqual({"living-room": ["use_profile"]},
                         paired["profile_grants"])
        self.assertNotIn("kid", result["profiles"])
        self.assertNotIn("kid", paired["profile_grants"])

    def test_child_only_grant_cannot_authorize_parent_or_default_alias(self):
        self.state.config["profiles"]["default"] = {
            "id": "default", "kind": "standard", "name": "Default",
            "enabled": True,
        }
        self.client["profile_grants"] = {"kid": ["use_profile"]}

        self.assertEqual("kid", self.state.authorize_child_profile(
            self.client, "kid"))
        self.assertEqual("kid", self.state.actor_profile_id)
        self.assertEqual("living-room", self.state.execution_profile_id)
        with self.assertRaises(PermissionError):
            self.state.authorize_profile(self.client, "living-room")
        with self.assertRaises(PermissionError):
            self.state.authorize_profile(self.client, "default")
        with self.assertRaises(PermissionError):
            self.state.select_profile("kid")

    def _identity_challenge(self):
        self.client["profile_grants"]["kid"] = ["use_profile"]
        actor = self.state.authorize_child_profile(self.client, "kid")
        status, result = self.state.vibepollo_identity_challenge(
            "192.0.2.1", self.client, actor, "identity-challenge-1")
        self.assertEqual(201, int(status))
        self.assertTrue(result["challenge"].startswith(
            "moonwaker-vibepollo-identity-v1\n"))
        return result

    def test_identity_bind_fills_parent_mapping_and_is_one_use(self):
        challenge = self._identity_challenge()
        self.state.proxy_json = mock.Mock(return_value=(True, {
            "ok": True, "client_uuid": "paired-client-uuid"}))
        result_status, result = self.state.vibepollo_identity_bind(
            "192.0.2.1", self.client, {
                "challenge_id": challenge["challenge_id"],
                "certificate_sha256": "a" * 64,
                "signature": base64.b64encode(b"proof").decode("ascii"),
            }, "kid", "identity-bind-1")
        self.assertEqual(200, int(result_status))
        self.assertEqual({"ok": True, "bound": True}, result)
        self.assertEqual(
            "paired-client-uuid",
            self.client["vibepollo_client_uuids"]["living-room"])
        replay_status, replay = self.state.vibepollo_identity_bind(
            "192.0.2.1", self.client, {
                "challenge_id": challenge["challenge_id"],
                "certificate_sha256": "a" * 64,
                "signature": base64.b64encode(b"proof").decode("ascii"),
            }, "kid", "identity-bind-2")
        self.assertEqual(410, int(replay_status))
        self.assertEqual("identity_challenge_expired", replay["reason"])

    def test_identity_bind_never_overwrites_existing_parent_mapping(self):
        challenge = self._identity_challenge()
        registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        registry["clients"][0]["vibepollo_client_uuids"] = {
            "living-room": "old-uuid"}
        self.config_path.write_text(json.dumps(registry), encoding="utf-8")
        self.state.config = registry
        self.state.proxy_json = mock.Mock(return_value=(True, {
            "ok": True, "client_uuid": "new-uuid"}))
        status, result = self.state.vibepollo_identity_bind(
            "192.0.2.1", self.client, {
                "challenge_id": challenge["challenge_id"],
                "certificate_sha256": "b" * 64,
                "signature": base64.b64encode(b"proof").decode("ascii"),
            }, "kid", "identity-bind-conflict")
        self.assertEqual(409, int(status))
        self.assertEqual("identity_binding_conflict", result["reason"])
        self.assertEqual(
            "old-uuid",
            self.state.config["clients"][0]["vibepollo_client_uuids"]["living-room"])

    def test_identity_http_routes_require_child_context_and_bind_parent(self):
        self.state.child_profiles_api_enabled = True
        self.client["profile_grants"]["kid"] = ["use_profile"]
        handler = object.__new__(wakeplay_gateway.GatewayHandler)
        handler.server = SimpleNamespace(state=self.state)
        handler.client_address = ("192.0.2.1", 12345)
        handler.headers = Message()
        handler.headers["Authorization"] = "Bearer token"
        handler.headers["X-WakePlay-Profile"] = "kid"
        handler.headers["X-MoonWaker-Capabilities"] = \
            wakeplay_gateway.CHILD_PROFILES_CAPABILITY
        responses = []

        def send_json(status, value):
            handler._response_status = int(status)
            responses.append((int(status), value))

        handler.send_json = send_json
        handler.path = wakeplay_gateway.API_PREFIX + "/vibepollo/identity/challenge"
        handler.headers["X-Request-Id"] = "http-identity-challenge"
        handler.rfile = io.BytesIO(b"{}")
        handler.do_POST()
        self.assertEqual(201, responses[-1][0])
        challenge = responses[-1][1]

        self.state.proxy_json = mock.Mock(return_value=(True, {
            "ok": True, "client_uuid": "http-paired-client"}))
        handler.path = wakeplay_gateway.API_PREFIX + "/vibepollo/identity/bind"
        handler.headers["X-Request-Id"] = "http-identity-bind"
        bind_body = json.dumps({
            "challenge_id": challenge["challenge_id"],
            "certificate_sha256": "c" * 64,
            "signature": base64.b64encode(b"proof").decode("ascii"),
        }).encode("utf-8")
        handler.headers["Content-Length"] = str(len(bind_body))
        handler.rfile = io.BytesIO(bind_body)
        handler.do_POST()
        self.assertEqual(200, responses[-1][0])
        self.assertEqual({"ok": True, "bound": True}, responses[-1][1])
        self.assertEqual(
            "http-paired-client",
            self.client["vibepollo_client_uuids"]["living-room"])

    def test_identity_http_rejects_missing_capability_grant_or_request_id(self):
        self.state.child_profiles_api_enabled = True

        def post(headers):
            handler = object.__new__(wakeplay_gateway.GatewayHandler)
            handler.server = SimpleNamespace(state=self.state)
            handler.client_address = ("192.0.2.1", 12345)
            handler.headers = Message()
            handler.headers["Authorization"] = "Bearer token"
            handler.headers["X-WakePlay-Profile"] = "kid"
            for key, value in headers.items():
                handler.headers[key] = value
            handler.path = wakeplay_gateway.API_PREFIX + \
                "/vibepollo/identity/challenge"
            handler.rfile = io.BytesIO(b"{}")
            handler.headers["Content-Length"] = "2"
            response = []
            handler.send_json = lambda status, value: response.append((int(status), value))
            handler.do_POST()
            return response[-1]

        cases = (
            ("capability", {"X-Request-Id": "missing-capability"},
             "child_profiles_capability_required"),
            ("grant", {
                "X-MoonWaker-Capabilities": wakeplay_gateway.CHILD_PROFILES_CAPABILITY,
                "X-Request-Id": "missing-grant",
            }, "not authorized"),
            ("request_id", {
                "X-MoonWaker-Capabilities": wakeplay_gateway.CHILD_PROFILES_CAPABILITY,
            }, "A valid X-Request-Id header is required."),
        )
        for label, headers, expected in cases:
            with self.subTest(label=label):
                self.client["profile_grants"].pop("kid", None)
                status, result = post(headers)
                self.assertEqual(403 if label != "request_id" else 400, status)
                self.assertIn(expected, str(result.get("error") or result.get("reason")))
                self.client["profile_grants"]["kid"] = ["use_profile"]

    def test_identity_bind_keeps_bridge_outage_retryable(self):
        challenge = self._identity_challenge()
        self.state.proxy_json = mock.Mock(return_value=(False, {
            "error": "Connection refused"}))
        status, result = self.state.vibepollo_identity_bind(
            "192.0.2.1", self.client, {
                "challenge_id": challenge["challenge_id"],
                "certificate_sha256": "d" * 64,
                "signature": base64.b64encode(b"proof").decode("ascii"),
            }, "kid", "identity-bind-outage")
        self.assertEqual(503, int(status))
        self.assertEqual("identity_binding_unavailable", result["reason"])

    def test_identity_bind_rejects_stale_context_and_expired_challenge(self):
        for label in ("client", "address", "actor", "execution"):
            with self.subTest(label=label):
                challenge = self._identity_challenge()
                bind_client = self.client
                bind_address = "192.0.2.1"
                bind_actor = "kid"
                if label == "client":
                    bind_client = copy.deepcopy(self.client)
                    bind_client["id"] = "another-client"
                elif label == "address":
                    bind_address = "192.0.2.2"
                elif label == "actor":
                    bind_actor = "another-child"
                elif label == "execution":
                    self.state.request_context.execution_profile_id = "another-parent"
                status, result = self.state.vibepollo_identity_bind(
                    bind_address, bind_client, {
                        "challenge_id": challenge["challenge_id"],
                        "certificate_sha256": "e" * 64,
                        "signature": base64.b64encode(b"proof").decode("ascii"),
                    }, bind_actor, "identity-bind-context")
                self.assertEqual(403, int(status))
                self.assertEqual("identity_proof_invalid", result["reason"])

        challenge = self._identity_challenge()
        self.state.vibepollo_identity_challenges[
            challenge["challenge_id"]]["expires_at"] = time.monotonic() - 1
        status, result = self.state.vibepollo_identity_bind(
            "192.0.2.1", self.client, {
                "challenge_id": challenge["challenge_id"],
                "certificate_sha256": "f" * 64,
                "signature": base64.b64encode(b"proof").decode("ascii"),
            }, "kid", "identity-bind-expired")
        self.assertEqual(410, int(status))
        self.assertEqual("identity_challenge_expired", result["reason"])

    def test_management_lease_is_parent_client_bound_and_expires(self):
        parent = self.state.config["profiles"]["living-room"]
        parent["pin_verifier"] = self.pin_verifier()
        other_parent = copy.deepcopy(parent)
        other_parent.update({
            "id": "other-parent", "name": "Other parent",
            "windows_account_sid": "S-1-5-21-1-2-3-1002",
            "owner_sid": "S-1-5-21-1-2-3-1002",
        })
        self.state.config["profiles"]["other-parent"] = other_parent
        self.client["profile_grants"]["living-room"] = [
            "use_profile", "manage_children"]
        self.client["profile_grants"]["other-parent"] = [
            "use_profile", "manage_children"]
        session_id = "01234567-89ab-cdef-0123-456789abcdef"

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100.0):
            status, result = self.state.begin_child_management(
                self.client, "living-room", "2468", session_id)
        self.assertEqual(200, status)
        authorization_id = result["authorization_id"]
        self.assertEqual({}, self.state.pin_unlock_leases)
        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=399.0):
            self.state.require_child_management(
                self.client, "living-room", session_id, authorization_id)

        wrong_client = {
            "id": "other-tv",
            "profile_grants": {"living-room": ["use_profile", "manage_children"]},
        }
        for label, client, parent_id in (
                ("wrong client", wrong_client, "living-room"),
                ("wrong parent", self.client, "other-parent")):
            with self.subTest(label=label):
                with self.assertRaises(PermissionError):
                    self.state.require_child_management(
                        client, parent_id, session_id, authorization_id)

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=400.0):
            with self.assertRaises(PermissionError):
                self.state.require_child_management(
                    self.client, "living-room", session_id, authorization_id)

    def test_child_broker_wire_operations_bind_actor_execution_client_request(self):
        client = LoginBrokerClient()
        execution = {
            "id": "living-room",
            "windows_account_sid": "S-1-5-21-1-2-3-1001",
            "windows_account_name": "TESTPC\\Player",
        }
        captured = []

        def capture(operation, fields):
            wire = LoginBrokerClient.encode_request(operation, fields)
            captured.append(wire)
            return {"success": True, "state": "ready", "reason": "none", "fields": {}}

        with mock.patch.object(client, "request", side_effect=capture):
            client.begin_child("android-tv", "kid", execution, "child-begin")
            client.child_attempt_state(
                "android-tv", "kid", execution, "child-request",
                "0123456789abcdef0123456789abcdef")
            client.cancel_child(
                "android-tv", "kid", execution, "child-request",
                "0123456789abcdef0123456789abcdef")
            client.switch_child("android-tv", "kid", execution, "child-switch")

        decoded = [self.decode_wire(item) for item in captured]
        self.assertEqual([
            (7, {1: "android-tv", 2: "kid", 3: "child-begin",
                 4: "living-room", 5: "S-1-5-21-1-2-3-1001", 6: "TESTPC\\Player"}),
            (8, {1: "android-tv", 2: "kid", 3: "child-request",
                 4: "0123456789abcdef0123456789abcdef", 5: "living-room",
                 6: "S-1-5-21-1-2-3-1001", 7: "TESTPC\\Player"}),
            (9, {1: "android-tv", 2: "kid", 3: "child-request",
                 4: "0123456789abcdef0123456789abcdef", 5: "living-room",
                 6: "S-1-5-21-1-2-3-1001", 7: "TESTPC\\Player"}),
            (10, {1: "android-tv", 2: "kid", 3: "child-switch",
                  4: "living-room", 5: "S-1-5-21-1-2-3-1001", 6: "TESTPC\\Player"}),
        ], decoded)

        self.client["profile_grants"]["kid"] = ["use_profile", "remote_sign_in"]
        self.broker.child_begin_result = self.broker.reply(
            False, "unsupported", "unsupported_operation")
        status, result = self.state.ensure_session(
            self.client, "kid", "old-broker-child")
        self.assertEqual(409, status)
        self.assertEqual("unsupported_operation", result["reason"])
        self.assertEqual(1, len(self.broker.child_begin_calls))
        self.assertEqual([], self.broker.legacy_begin_calls)

    @staticmethod
    def decode_wire(wire):
        if wire[:4] != b"MWLB" or wire[4] != 1 or wire[7] != 0:
            raise AssertionError("invalid MWLB wire header")
        offset = 8
        fields = {}
        for _ in range(wire[6]):
            key = wire[offset]
            length = struct.unpack("<I", wire[offset + 1:offset + 5])[0]
            offset += 5
            fields[key] = wire[offset:offset + length].decode("utf-8")
            offset += length
        if offset != len(wire):
            raise AssertionError("trailing bytes in MWLB wire message")
        return wire[5], fields


if __name__ == "__main__":
    unittest.main()
