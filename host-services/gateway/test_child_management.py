import base64
import copy
import hashlib
import json
import tempfile
import unittest
from email.message import Message
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import wakeplay_gateway
from wakeplay_gateway import GatewayHandler, GatewayState, LoginBrokerClient, sha256_text


class FakeChildWriter:
    def __init__(self):
        self.requests = []
        self.response = None

    def child_profile_api(self, payload):
        self.requests.append(copy.deepcopy(payload))
        if self.response is not None:
            return self.response
        return {"result": {
            "ok": True,
            "parent_profile_id": payload.get("parent_profile_id", "parent"),
            "revision": int(payload.get("expected_revision", 0)) + 1,
            "idempotent": False,
            "cleanup_required": False,
        }}


class ChildManagementGatewayTest(unittest.TestCase):
    SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef"

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temporary.name) / "gateway.json"
        self.writer = FakeChildWriter()
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
                    "pin_verifier": self.pin_verifier(),
                },
            },
            "clients": [{
                "id": "tv",
                "token_sha256": sha256_text("token"),
                "profile_grants": {
                    "parent": ["use_profile", "manage_children"],
                },
            }],
        }), encoding="utf-8")
        self.state = GatewayState(
            self.config_path, None, broker_client=self.writer)
        self.state.child_profiles_api_enabled = True
        self.client = self.state.config["clients"][0]
        status, result = self.state.begin_child_management(
            self.client, "parent", "2468", self.SESSION_ID)
        self.assertEqual(200, status)
        self.authorization_id = result["authorization_id"]

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

    @staticmethod
    def draft(name="Łucja Żółć"):
        return {
            "name": name,
            "avatar_id": "star",
            "enabled": True,
            "schedule": {"weekdays": {
                day: {
                    "enabled": False,
                    "start_minute": 0,
                    "end_minute": 1440,
                    "daily_limit_seconds": 0,
                }
                for day in wakeplay_gateway.CHILD_PROFILE_WEEKDAYS
            }},
        }

    def management_args(self):
        return (self.client, "parent", self.SESSION_ID, self.authorization_id)

    def test_child_api_is_fail_closed_until_capability_is_enabled(self):
        self.state.child_profiles_api_enabled = False
        handler = object.__new__(GatewayHandler)
        handler.path = "/api/v1/profiles/children/list"
        handler.headers = Message()
        handler.headers["Authorization"] = "Bearer token"
        handler.headers["X-WakePlay-Profile"] = "parent"
        handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = self.SESSION_ID
        handler.server = SimpleNamespace(state=self.state)
        handler.client_address = ("192.0.2.1", 12345)
        responses = []
        handler.send_json = lambda status, body: responses.append((status, body))
        handler.read_json = mock.Mock(side_effect=AssertionError("fail-closed route read body"))
        handler.do_POST()
        self.assertEqual(403, responses[0][0])
        self.assertEqual("child_profiles_not_enforced", responses[0][1]["error"])
        handler.read_json.assert_not_called()

    def test_create_update_and_current_device_grant_use_writer_contract(self):
        status, result = self.state.create_child_profile(
            self.client, *self.management_args()[1:], "create-1", 0,
            self.draft(), grant_current_device=True)
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        create = self.writer.requests[-1]
        self.assertEqual("create", create["operation"])
        self.assertEqual("tv", create["grant_client_id"])
        self.assertTrue(create["grant_current_device"])
        self.assertEqual("Łucja Żółć", create["draft"]["name"])

        status, _result = self.state.update_child_profile(
            self.client, "parent", "child-1", self.SESSION_ID,
            self.authorization_id, "update-1", 0, self.draft("Renamed"))
        self.assertEqual(200, status)
        update = self.writer.requests[-1]
        self.assertEqual("update", update["operation"])
        self.assertNotIn("grant_client_id", update)
        self.assertNotIn("grant_current_device", update)

    def test_sharing_set_delegates_child_existence_to_atomic_writer(self):
        self.state.proxy_json = mock.Mock(return_value=(True, {
            "game_id": "steam:123",
            "canonical_game_id": "steam:123",
            "revision": "catalog-1",
        }))
        status, result = self.state.set_child_game_sharing(
            self.client, "parent", "steam:123", ["child-removed"], 0,
            "share-retry", self.SESSION_ID, self.authorization_id)
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        request = self.writer.requests[-1]
        self.assertEqual("sharing_set", request["operation"])
        self.assertEqual(["child-removed"], request["selected_child_ids"])

    def test_writer_request_reuse_and_stale_are_exact_conflicts(self):
        self.writer.response = {"result": {
            "ok": False, "reason": "request_id_reused"}}
        status, result = self.state.create_child_profile(
            self.client, "parent", self.SESSION_ID, self.authorization_id,
            "create-reused", 0, self.draft())
        self.assertEqual(409, status)
        self.assertEqual("request_id_reused", result["error"])

        self.writer.response = {"result": {
            "ok": False, "reason": "child_policy_revision_stale"}}
        status, result = self.state.delete_child_profile(
            self.client, "parent", "child-1", self.SESSION_ID,
            self.authorization_id, "delete-stale", 0)
        self.assertEqual(409, status)
        self.assertEqual("child_policy_revision_stale", result["error"])

    def test_child_profile_writer_wire_is_utf8_json(self):
        broker = LoginBrokerClient()
        captured = []

        def capture(operation, fields):
            captured.append((operation, fields))
            return {"fields": {
                "3": json.dumps({"ok": True, "name": "Łucja Żółć"},
                                 ensure_ascii=False)}}

        with mock.patch.object(broker, "request", side_effect=capture):
            result = broker.child_profile_api({
                "operation": "create", "draft": {"name": "Łucja Żółć"}})
        self.assertEqual(11, captured[0][0])
        self.assertIn("Łucja Żółć", captured[0][1][1])
        self.assertEqual("Łucja Żółć", result["result"]["name"])

    def test_child_profile_writer_decodes_numeric_broker_result_field(self):
        broker = LoginBrokerClient()

        def capture(_operation, _fields):
            return {"success": True, "state": "ready", "fields": {
                3: json.dumps({"ok": True, "revision": 7})}}

        with mock.patch.object(broker, "request", side_effect=capture):
            result = broker.child_profile_api({"operation": "sharing_set"})
        self.assertTrue(result["result"]["ok"])
        self.assertEqual(7, result["result"]["revision"])


if __name__ == "__main__":
    unittest.main()
