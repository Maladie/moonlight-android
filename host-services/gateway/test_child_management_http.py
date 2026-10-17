import unittest
from unittest import mock

import wakeplay_gateway


class ChildManagementHttpTest(unittest.TestCase):
    @staticmethod
    def pin_verifier(pin="2468"):
        from test_child_management import ChildManagementGatewayTest
        return ChildManagementGatewayTest.pin_verifier(pin)

    @staticmethod
    def draft(name="Łucja Żółć"):
        from test_child_management import ChildManagementGatewayTest
        return ChildManagementGatewayTest.draft(name)

    def setUp(self):
        from test_child_management import ChildManagementGatewayTest
        self.SESSION_ID = ChildManagementGatewayTest.SESSION_ID
        ChildManagementGatewayTest.setUp(self)

    def tearDown(self):
        from test_child_management import ChildManagementGatewayTest
        ChildManagementGatewayTest.tearDown(self)

    def post(self, path, body, profile_id="parent"):
        from test_gateway import GatewayStateTest
        handler, responses = GatewayStateTest.request_handler(
            self.state, path, "token", profile_id)
        handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = self.SESSION_ID
        handler.headers["X-Request-Id"] = str(body.get("request_id") or "http-request")
        reader = mock.Mock(return_value=body)
        handler.read_json = reader
        handler.do_POST()
        self.assertEqual(1, len(responses))
        return responses[0][0], responses[0][1], reader

    def sharing_body(self):
        return {
            "parent_profile_id": "parent",
            "game_id": "steam:source",
            "selected_child_ids": ["kid-a", "kid-b"],
            "expected_revision": 0,
            "request_id": "share-http-1",
            "authorization_id": self.authorization_id,
        }

    def resolver_result(self):
        return True, {
            "game_id": "steam:source",
            "canonical_game_id": "steam:canonical",
            "revision": "catalog-7",
        }

    def test_capability_disabled_fail_closes_all_management_routes(self):
        self.state.child_profiles_api_enabled = False
        routes = {
            "authorize": {"parent_profile_id": "parent", "pin": "2468"},
            "list": {"parent_profile_id": "parent",
                     "authorization_id": self.authorization_id},
            "create": {"parent_profile_id": "parent",
                       "authorization_id": self.authorization_id,
                       "request_id": "create-http-1", "expected_revision": 0,
                       "draft": self.draft()},
            "sharing/set": self.sharing_body(),
        }
        for action, body in routes.items():
            with self.subTest(action=action):
                status, result, reader = self.post(
                    "/api/v1/profiles/children/" + action, body)
                self.assertEqual(403, status)
                self.assertEqual("child_profiles_not_enforced", result["error"])
                reader.assert_not_called()

    def test_authorize_post_then_list_uses_fresh_pin_session_and_management_grant(self):
        status, authorized, _reader = self.post(
            "/api/v1/profiles/children/authorize",
            {"parent_profile_id": "parent", "pin": "2468"})
        self.assertEqual(200, status)
        self.assertTrue(authorized["authorization_id"])

        status, result, _reader = self.post(
            "/api/v1/profiles/children/list", {
                "parent_profile_id": "parent",
                "authorization_id": authorized["authorization_id"],
            })
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual("parent", result["parent_profile_id"])
        self.assertEqual([], result["children"])

    def test_child_actor_header_cannot_manage_parent_body(self):
        self.state.config["profiles"]["kid"] = {
            "id": "kid", "kind": "child", "name": "Kid",
            "parent_profile_id": "parent", "enabled": True,
            "allowed_game_keys": [], "policy_revision": 0,
        }
        self.client["profile_grants"]["kid"] = ["use_profile"]

        status, result, _reader = self.post(
            "/api/v1/profiles/children/list", {
                "parent_profile_id": "parent",
                "authorization_id": self.authorization_id,
            }, profile_id="kid")
        self.assertEqual(403, status)
        self.assertEqual("parent_profile_context_mismatch", result["error"])
        self.assertEqual([], self.writer.requests)

    def test_sharing_set_dispatches_resolved_key_and_returns_writer_result(self):
        self.state.proxy_json = mock.Mock(return_value=self.resolver_result())
        self.writer.response = {"result": {
            "ok": True, "source": "writer", "revision": 9,
            "parent_profile_id": "parent",
            "game_key": "parent/steam:canonical",
            "selected_child_ids": ["kid-a", "kid-b"],
        }}

        status, result, _reader = self.post(
            "/api/v1/profiles/children/sharing/set", self.sharing_body())
        self.assertEqual(200, status)
        self.assertEqual("writer", result["source"])
        self.assertEqual(9, result["revision"])
        self.state.proxy_json.assert_called_once()
        request = self.writer.requests[-1]
        self.assertEqual("sharing_set", request["operation"])
        self.assertEqual("parent/steam:canonical", request["game_key"])
        self.assertEqual(["kid-a", "kid-b"], request["selected_child_ids"])
        self.assertEqual(["parent/steam:canonical"],
                         request["parent_catalog_game_keys"])
        self.assertEqual("share-http-1", request["request_id"])

    def test_sharing_writer_failure_is_not_reported_as_success(self):
        self.state.proxy_json = mock.Mock(return_value=self.resolver_result())
        self.writer.response = {"success": False,
                                "state": "broker_unavailable",
                                "reason": "writer_down"}

        status, result, _reader = self.post(
            "/api/v1/profiles/children/sharing/set", self.sharing_body())
        self.assertEqual(503, status)
        self.assertFalse(result["ok"])
        self.assertEqual("writer_down", result["error"])
        self.assertEqual(1, len(self.writer.requests))

    def test_create_grant_uses_authenticated_client_id(self):
        body = {
            "parent_profile_id": "parent",
            "authorization_id": self.authorization_id,
            "request_id": "create-http-1",
            "expected_revision": 0,
            "draft": self.draft(),
            "grant_current_device": True,
            "grant_client_id": "attacker-supplied-id",
        }
        status, result, _reader = self.post(
            "/api/v1/profiles/children/create", body)
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        request = self.writer.requests[-1]
        self.assertEqual("create", request["operation"])
        self.assertTrue(request["grant_current_device"])
        self.assertEqual("tv", request["grant_client_id"])


if __name__ == "__main__":
    unittest.main()
