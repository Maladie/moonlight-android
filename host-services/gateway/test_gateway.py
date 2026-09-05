import io
import contextlib
import json
import os
import re
import tempfile
import time
import unittest
import urllib.parse
from email.message import Message
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import wakeplay_gateway
from wakeplay_gateway import (
    GatewayDiagnostics, GatewayHandler, GatewayState, diagnostic_route, sha256_text,
)


class GatewayStateTest(unittest.TestCase):
    def setUp(self):
        self.config_path = Path(__file__).parent / ".gateway-test-runtime.json"
        self.config_path.write_text(json.dumps({
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "clients": [],
        }), encoding="utf-8")

    def tearDown(self):
        self.config_path.unlink(missing_ok=True)
        self.config_path.with_name("pairing-code.json").unlink(missing_ok=True)
        self.config_path.with_name("runtime-status.json").unlink(missing_ok=True)
        self.config_path.with_name("gateway-runtime.json").unlink(missing_ok=True)
        self.config_path.with_name("client-activity.json").unlink(missing_ok=True)
        self.config_path.with_suffix(self.config_path.suffix + ".lock").unlink(missing_ok=True)

    @staticmethod
    def request_handler(state, path, token, profile_id=None):
        handler = object.__new__(GatewayHandler)
        handler.path = path
        handler.headers = Message()
        handler.headers["Authorization"] = f"Bearer {token}"
        if profile_id is not None:
            handler.headers["X-WakePlay-Profile"] = profile_id
        handler.server = SimpleNamespace(state=state)
        handler.client_address = ("192.0.2.1", 12345)
        responses = []

        def send_json(status, body):
            handler._response_status = int(status)
            responses.append((int(status), body))

        handler.send_json = send_json
        handler.read_json = lambda: {}
        return handler, responses

    def pin_state(self):
        salt = b"0123456789abcdef"
        verifier = {
            "version": 1,
            "algorithm": "pbkdf2-sha256",
            "iterations": 100_000,
            "salt": wakeplay_gateway.base64.b64encode(salt).decode("ascii"),
            "digest": wakeplay_gateway.base64.b64encode(
                wakeplay_gateway.hashlib.pbkdf2_hmac(
                    "sha256", b"2468", salt, 100_000)).decode("ascii"),
        }
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {
                "protected": {"id": "protected", "name": "Protected",
                              "enabled": True, "pin_verifier": verifier},
                "open": {"id": "open", "name": "Open", "enabled": True},
            },
            "clients": [{
                "id": "client-1", "token_sha256": sha256_text("pin-token"),
                "profile_grants": {
                    "protected": ["use_profile"], "open": ["use_profile"]},
            }],
        }), encoding="utf-8")
        state = GatewayState(self.config_path, None)
        return state, state.client_for_token("pin-token")

    def test_profile_pin_summary_exposes_only_requirement(self):
        state, client = self.pin_state()
        state.discord_status = lambda: {
            "bridge_online": False, "rpc_connected": False,
            "authenticated": False, "error": ""}
        state.proxy = lambda *_args, **_kwargs: (False, {})

        summary = state.profiles_summary(client)
        serialized = json.dumps(summary)

        protected = next(profile for profile in summary["profiles"]
                         if profile["id"] == "protected")
        self.assertTrue(protected["pin_required"])
        self.assertNotIn("pin_verifier", serialized)
        self.assertNotIn("digest", serialized)
        self.assertNotIn("2468", serialized)

    def test_pin_verifier_uses_constant_time_comparison(self):
        state, client = self.pin_state()
        verifier = state.config["profiles"]["protected"]["pin_verifier"]
        with mock.patch.object(wakeplay_gateway.secrets, "compare_digest",
                               wraps=wakeplay_gateway.secrets.compare_digest) as compare:
            self.assertTrue(wakeplay_gateway.verify_profile_pin("2468", verifier))
            self.assertFalse(wakeplay_gateway.verify_profile_pin("1357", verifier))
        self.assertEqual(2, compare.call_count)

        status, result = state.verify_pin(client, "protected", "2468")
        self.assertEqual(200, status)
        self.assertTrue(result["unlocked"])

    def test_pin_rate_limit_is_scoped_to_client_and_profile(self):
        state, client = self.pin_state()
        other = {"id": "client-2"}
        state.config["profiles"]["second"] = {
            "id": "second", "enabled": True,
            "pin_verifier": state.config["profiles"]["protected"]["pin_verifier"],
        }
        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100.0):
            self.assertEqual(403, state.verify_pin(client, "protected", "1357")[0])
            self.assertEqual(429, state.verify_pin(client, "protected", "1357")[0])
            self.assertEqual(403, state.verify_pin(other, "protected", "1357")[0])
            self.assertEqual(403, state.verify_pin(client, "second", "1357")[0])
        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=101.0):
            status, result = state.verify_pin(client, "protected", "1357")
            self.assertEqual(403, status)
            self.assertEqual(2, result["retry_after_seconds"])

    def test_protected_routes_require_current_unlock_lease(self):
        state, _client = self.pin_state()
        state.playnite_health = mock.Mock(return_value=(200, {"ok": True}))

        handler, responses = self.request_handler(
            state, "/api/v1/playnite/health", "pin-token", "protected")
        handler.do_GET()
        self.assertEqual(403, responses[0][0])
        state.playnite_health.assert_not_called()

        handler, responses = self.request_handler(
            state, "/api/v1/playnite/health", "pin-token", "open")
        handler.do_GET()
        self.assertEqual(200, responses[0][0])

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100.0):
            handler, responses = self.request_handler(
                state, "/api/v1/profiles/pin/verify", "pin-token", "protected")
            handler.read_json = lambda: {"pin": "2468"}
            handler.do_POST()
            self.assertEqual(200, responses[0][0])

            handler, responses = self.request_handler(
                state, "/api/v1/playnite/health", "pin-token", "protected")
            handler.do_GET()
            self.assertEqual(200, responses[0][0])

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=401.0):
            handler, responses = self.request_handler(
                state, "/api/v1/playnite/health", "pin-token", "protected")
            handler.do_GET()
            self.assertEqual(403, responses[0][0])

    def test_legacy_unlock_lease_expires_after_five_minutes(self):
        state, client = self.pin_state()
        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100.0):
            status, result = state.verify_pin(client, "protected", "2468")
        self.assertEqual(200, status)
        self.assertEqual(300, result["expires_in_seconds"])
        self.assertEqual(400.0, state.pin_unlock_leases[("client-1", "protected")][0])

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=399.0):
            state.require_profile_unlock(client, "protected")
        self.assertEqual(400.0, state.pin_unlock_leases[("client-1", "protected")][0])

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=400.0):
            with self.assertRaises(PermissionError):
                state.require_profile_unlock(client, "protected")

    def test_profile_session_unlock_survives_idle_and_replaces_prior_session(self):
        state, client = self.pin_state()
        session_a = "01234567-89ab-cdef-0123-456789abcdef"
        session_b = "fedcba98-7654-3210-fedc-ba9876543210"
        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100.0):
            status, result = state.verify_pin(client, "protected", "2468", session_a)
        self.assertEqual(200, status)
        self.assertTrue(result["session_scoped"])
        self.assertNotIn("expires_in_seconds", result)

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100000.0):
            state.require_profile_unlock(client, "protected", session_a.upper())
            with self.assertRaises(PermissionError):
                state.require_profile_unlock(client, "protected")

            status, _result = state.verify_pin(client, "protected", "2468", session_b)
            self.assertEqual(200, status)
            with self.assertRaises(PermissionError):
                state.require_profile_unlock(client, "protected", session_a)
            state.require_profile_unlock(client, "protected", session_b)

    def test_profile_session_unlock_is_invalidated_by_verifier_change(self):
        state, client = self.pin_state()
        session_id = "01234567-89ab-cdef-0123-456789abcdef"
        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100.0):
            status, _result = state.verify_pin(client, "protected", "2468", session_id)
        self.assertEqual(200, status)
        state.config["profiles"]["protected"]["pin_verifier"]["digest"] = (
            wakeplay_gateway.base64.b64encode(
                wakeplay_gateway.hashlib.pbkdf2_hmac(
                    "sha256", b"2468", b"fedcba9876543210", 100_000)).decode("ascii"))
        with self.assertRaises(PermissionError):
            state.require_profile_unlock(client, "protected", session_id)
        self.assertNotIn(("client-1", "protected"), state.pin_unlock_leases)

    def test_profile_session_header_requires_a_canonical_uuid(self):
        state, _client = self.pin_state()
        with self.assertRaises(ValueError):
            state.verify_pin(
                {"id": "client-1"}, "protected", "2468", "short-session")

    def test_handler_propagates_profile_session_to_protected_routes(self):
        state, _client = self.pin_state()
        state.playnite_health = mock.Mock(return_value=(200, {"ok": True}))
        session_a = "01234567-89ab-cdef-0123-456789abcdef"
        session_b = "fedcba98-7654-3210-fedc-ba9876543210"
        second_profile = dict(state.config["profiles"]["protected"])
        second_profile["id"] = "protected-other"
        state.config["profiles"]["protected-other"] = second_profile
        state.config["clients"][0]["profile_grants"]["protected-other"] = ["use_profile"]
        state.config["clients"].append({
            "id": "client-2",
            "token_sha256": sha256_text("other-token"),
            "profile_grants": {"protected": ["use_profile"]},
        })
        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100.0):
            handler, responses = self.request_handler(
                state, "/api/v1/profiles/pin/verify", "pin-token", "protected")
            handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = session_a
            handler.read_json = lambda: {"pin": "2468"}
            handler.do_POST()
            self.assertEqual(200, responses[0][0])

        with mock.patch.object(wakeplay_gateway.time, "monotonic", return_value=100000.0):
            for session_id, expected_status in (
                    (session_a, 200), (session_b, 403), (None, 403)):
                handler, responses = self.request_handler(
                    state, "/api/v1/playnite/health", "pin-token", "protected")
                if session_id is not None:
                    handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = session_id
                handler.do_GET()
                self.assertEqual(expected_status, responses[0][0])

            handler, responses = self.request_handler(
                state, "/api/v1/playnite/health", "pin-token", "protected")
            handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = session_a
            handler.do_GET()
            self.assertEqual(200, responses[0][0])

            handler, responses = self.request_handler(
                state, "/api/v1/playnite/health", "other-token", "protected")
            handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = session_a
            handler.do_GET()
            self.assertEqual(403, responses[0][0])

            handler, responses = self.request_handler(
                state, "/api/v1/playnite/health", "pin-token", "protected-other")
            handler.headers[wakeplay_gateway.PROFILE_SESSION_HEADER] = session_a
            handler.do_GET()
            self.assertEqual(403, responses[0][0])

    def test_pin_verify_requires_authenticated_use_profile_grant(self):
        state, _client = self.pin_state()
        state.config["clients"][0]["profile_grants"] = {"open": ["use_profile"]}
        handler, responses = self.request_handler(
            state, "/api/v1/profiles/pin/verify", "pin-token", "protected")
        handler.read_json = lambda: {"pin": "2468"}

        handler.do_POST()

        self.assertEqual(403, responses[0][0])
        self.assertEqual({}, state.pin_unlock_leases)

    def test_gateway_installer_packages_microphone_worker(self):
        installer = (Path(__file__).parent / "Install-WakePlayGateway.ps1").read_text(
            encoding="utf-8-sig")
        files = re.search(r"\$files\s*=\s*@\((.*?)\)", installer, re.DOTALL)
        self.assertIsNotNone(files)
        self.assertIn('"MoonWakerMicrophoneWorker.exe"', files.group(1))

    def test_gateway_installer_packages_discord_audio_worker(self):
        installer = (Path(__file__).parent / "Install-WakePlayGateway.ps1").read_text(
            encoding="utf-8-sig")
        files = re.search(r"\$files\s*=\s*@\((.*?)\)", installer, re.DOTALL)
        self.assertIsNotNone(files)
        self.assertIn('"MoonWakerDiscordAudioWorker.exe"', files.group(1))

    def test_pair_stores_only_token_hash(self):
        state = GatewayState(self.config_path, "123456")
        result = state.pair("192.0.2.1", "123456", "Living room TV")

        stored = json.loads(self.config_path.read_text(encoding="utf-8"))["clients"][0]
        self.assertNotIn(result["token"], self.config_path.read_text(encoding="utf-8"))
        self.assertEqual(sha256_text(result["token"]), stored["token_sha256"])
        self.assertEqual(stored["paired_at"], stored["last_seen_at"])
        self.assertEqual({"default": ["use_profile"]}, stored["profile_grants"])
        self.assertNotIn("remote_sign_in", stored["profile_grants"]["default"])
        self.assertEqual("default", result["suggested_profile_id"])
        self.assertEqual(["default"], [profile["id"] for profile in result["profiles"]])
        self.assertIsNotNone(state.client_for_token(result["token"]))

    def test_default_profile_alias_selects_only_granted_profile(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"] = {
            "basia": {"id": "basia", "name": "Basia", "enabled": True},
        }
        client = {"profile_grants": {"basia": ["use_profile"]}}

        self.assertEqual("basia", state.authorize_profile(client, "default"))

    def test_pair_merges_into_current_registry_without_losing_profile_edits(self):
        state = GatewayState(self.config_path, "123456")
        externally_edited = json.loads(self.config_path.read_text(encoding="utf-8"))
        externally_edited["profiles"]["added"] = {
            "id": "added", "name": "Added", "enabled": True,
        }
        externally_edited["profiles"]["default"]["display_name"] = "Renamed"
        self.config_path.write_text(json.dumps(externally_edited), encoding="utf-8")

        result = state.pair("192.0.2.1", "123456", "Living room TV")

        persisted = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.assertEqual("Renamed", persisted["profiles"]["default"]["display_name"])
        self.assertIn("added", persisted["profiles"])
        self.assertEqual(
            {"added": ["use_profile"], "default": ["use_profile"]},
            persisted["clients"][0]["profile_grants"])
        self.assertEqual("", result["suggested_profile_id"])
        self.assertEqual(["added", "default"],
                         [profile["id"] for profile in result["profiles"]])
        self.assertIsNotNone(state.client_for_token(result["token"]))

    def test_legacy_schema_migrates_profiles_and_clients_without_remote_grants(self):
        token_hash = sha256_text("legacy-token")
        self.config_path.write_text(json.dumps({
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {
                "basia": {
                    "name": "Basia",
                    "owner": "HOST\\Basia",
                    "profile_root": "C:\\MoonWaker\\Basia",
                    "discord_bridge": "http://127.0.0.1:8865",
                    "vibepollo_bridge": "http://127.0.0.1:8875",
                    "playnite_bridge": "http://127.0.0.1:8880",
                    "integration_token": "preserved-token",
                    "remote_sign_in_enabled": True,
                },
                "manual": {
                    "name": "Manual",
                    "owner": "HOST\\Missing",
                    "remote_sign_in_enabled": True,
                },
                "disabled": {
                    "name": "Disabled",
                    "enabled": False,
                },
            },
            "clients": [{
                "id": "legacy-client",
                "name": "TV",
                "token_sha256": token_hash,
                "paired_at": 123,
                "profile_grants": {
                    "basia": ["use_profile", "remote_sign_in"],
                },
            }],
        }), encoding="utf-8")
        self.config_path.with_suffix(self.config_path.suffix + ".lock").write_bytes(b"0")

        with mock.patch.object(
                wakeplay_gateway, "resolve_windows_account_sid",
                side_effect=lambda name: (
                    "S-1-5-21-1-2-3-1001" if name == "HOST\\Basia" else "")):
            state = GatewayState(self.config_path, None)

        saved = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.assertEqual(wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
                         saved["schema_version"])
        basia = saved["profiles"]["basia"]
        self.assertEqual("basia", basia["id"])
        self.assertEqual("Basia", basia["display_name"])
        self.assertEqual("S-1-5-21-1-2-3-1001", basia["windows_account_sid"])
        self.assertEqual(basia["windows_account_sid"], basia["owner_sid"])
        self.assertEqual("HOST\\Basia", basia["windows_account_name"])
        self.assertEqual("C:\\MoonWaker\\Basia", basia["profile_root"])
        self.assertEqual("http://127.0.0.1:8880", basia["game_provider_bridge"])
        self.assertEqual("preserved-token", basia["integration_token"])
        self.assertFalse(basia["remote_sign_in_enabled"])
        manual = saved["profiles"]["manual"]
        self.assertEqual("action_required", manual["account_mapping_status"])
        self.assertFalse(manual["remote_sign_in_enabled"])
        self.assertIn("manual", saved["profiles"])
        client = saved["clients"][0]
        self.assertEqual(token_hash, client["token_sha256"])
        self.assertEqual(
            {"basia": ["use_profile"], "default": ["use_profile"],
             "disabled": ["use_profile"],
             "manual": ["use_profile"]},
            client["profile_grants"])
        for permissions in client["profile_grants"].values():
            self.assertNotIn("remote_sign_in", permissions)
        with self.assertRaises(PermissionError):
            state.authorize_profile(client, "basia", "remote_sign_in")

    def test_current_schema_missing_or_malformed_grants_fail_closed(self):
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {"default": {"id": "default", "enabled": True}},
            "clients": [
                {"id": "missing", "token_sha256": sha256_text("missing")},
                {"id": "malformed", "token_sha256": sha256_text("malformed"),
                 "profile_grants": ["use_profile"]},
            ],
        }), encoding="utf-8")

        state = GatewayState(self.config_path, None)

        self.assertEqual({}, state.config["clients"][0]["profile_grants"])
        self.assertEqual({}, state.config["clients"][1]["profile_grants"])

    def test_current_schema_does_not_resurrect_removed_compatibility_profile(self):
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {},
            "clients": [],
        }), encoding="utf-8")

        state = GatewayState(self.config_path, None)

        self.assertEqual({}, state.config["profiles"])
        self.assertEqual({}, json.loads(
            self.config_path.read_text(encoding="utf-8"))["profiles"])

    def test_tombstoned_profile_stays_disabled_and_cannot_be_used_or_regranted(self):
        token = "existing-client-token"
        tombstone = {"nonce": "deletion-nonce", "generation": 3,
                     "created_at": 1234}
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {"pending": {
                "id": "pending", "name": "Pending", "enabled": True,
                "owner_sid": "S-1-5-21-1-2-3-1001",
                "windows_account_sid": "S-1-5-21-1-2-3-1001",
                "remote_sign_in_enabled": True,
                "deletion_tombstone": tombstone,
            }},
            "clients": [{
                "id": "existing", "token_sha256": sha256_text(token),
                "profile_grants": {
                    "pending": ["use_profile", "remote_sign_in"],
                },
            }],
        }), encoding="utf-8")

        state = GatewayState(self.config_path, "123456")
        pending = state.config["profiles"]["pending"]
        self.assertFalse(pending["enabled"])
        self.assertFalse(pending["remote_sign_in_enabled"])
        self.assertEqual(tombstone, pending["deletion_tombstone"])
        client = state.client_for_token(token)
        with self.assertRaises(PermissionError):
            state.authorize_profile(client, "pending", "use_profile")
        with self.assertRaises(PermissionError):
            state.select_profile("pending")
        self.assertEqual([], state.profiles_summary(client)["profiles"])

        paired = state.pair("192.0.2.2", "123456", "New TV")
        new_client = state.client_for_token(paired["token"])
        self.assertNotIn("pending", new_client["profile_grants"])
        persisted = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.assertEqual(tombstone,
                         persisted["profiles"]["pending"]["deletion_tombstone"])

    def test_corrupt_optional_client_activity_does_not_block_startup(self):
        self.config_path.with_name("client-activity.json").write_text(json.dumps({
            "schema_version": 1,
            "clients": {"client-1": "not-a-timestamp"},
        }), encoding="utf-8")
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {"default": {"id": "default", "enabled": True}},
            "clients": [{"id": "client-1", "last_seen_at": 123,
                         "profile_grants": {}}],
        }), encoding="utf-8")

        state = GatewayState(self.config_path, None)

        self.assertEqual(123, state.config["clients"][0]["last_seen_at"])

    def test_migration_never_replaces_registry_changed_while_starting(self):
        replacement = {
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "replacement-cert.pem",
            "private_key": "replacement-key.pem",
            "profiles": {"default": {"id": "default", "enabled": True}},
            "clients": [],
        }

        @contextlib.contextmanager
        def concurrent_edit(_state):
            self.config_path.write_text(json.dumps(replacement), encoding="utf-8")
            yield

        with mock.patch.object(GatewayState, "registry_update_lock", concurrent_edit):
            with self.assertRaisesRegex(RuntimeError, "changed during migration"):
                GatewayState(self.config_path, None)

        self.assertEqual(
            "replacement-cert.pem",
            json.loads(self.config_path.read_text(encoding="utf-8"))["certificate"])

    def test_live_gateway_refuses_to_mutate_newer_registry_schema(self):
        state = GatewayState(self.config_path, "123456")
        newer = json.loads(self.config_path.read_text(encoding="utf-8"))
        newer["schema_version"] = wakeplay_gateway.GATEWAY_SCHEMA_VERSION + 1
        self.config_path.write_text(json.dumps(newer), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "schema changed"):
            state.pair("192.0.2.1", "123456", "TV")

        self.assertEqual(
            wakeplay_gateway.GATEWAY_SCHEMA_VERSION + 1,
            json.loads(self.config_path.read_text(encoding="utf-8"))["schema_version"])

    def test_live_registry_reload_applies_grant_revocation_before_next_request(self):
        token = "revoked-token"
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": {"default": {"id": "default", "enabled": True}},
            "clients": [{
                "id": "client-1",
                "token_sha256": sha256_text(token),
                "profile_grants": {"default": ["use_profile", "remote_sign_in"]},
            }],
        }), encoding="utf-8")
        state = GatewayState(self.config_path, None)
        self.assertIsNotNone(state.client_for_token(token))

        edited = json.loads(self.config_path.read_text(encoding="utf-8"))
        edited["clients"][0]["profile_grants"] = {"default": ["use_profile"]}
        self.config_path.write_text(json.dumps(edited), encoding="utf-8")
        state.registry_modified_ns = -1

        client = state.client_for_token(token)
        self.assertIsNotNone(client)
        with self.assertRaises(PermissionError):
            state.authorize_profile(client, "default", "remote_sign_in")

    def test_client_last_seen_write_is_throttled(self):
        state = GatewayState(self.config_path, None)
        token = "known-token"
        state.config["clients"].append({
            "id": "client-1",
            "token_sha256": sha256_text(token),
            "last_seen_at": 1000,
            "profile_grants": {"default": ["use_profile"]},
        })
        state.save()
        externally_edited = json.loads(self.config_path.read_text(encoding="utf-8"))
        externally_edited["profiles"]["default"]["display_name"] = "Renamed elsewhere"
        externally_edited["clients"][0]["profile_grants"] = {}
        self.config_path.write_text(json.dumps(externally_edited), encoding="utf-8")

        with mock.patch.object(wakeplay_gateway.time, "time", return_value=1299):
            self.assertIsNotNone(state.client_for_token(token))
        with mock.patch.object(wakeplay_gateway.time, "time", return_value=1300):
            self.assertIsNotNone(state.client_for_token(token))
        with mock.patch.object(wakeplay_gateway.time, "time", return_value=1301):
            self.assertIsNotNone(state.client_for_token(token))

        self.assertEqual(1300, state.config["clients"][0]["last_seen_at"])
        persisted = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.assertEqual(1000, persisted["clients"][0]["last_seen_at"])
        self.assertEqual({}, persisted["clients"][0]["profile_grants"])
        self.assertEqual("Renamed elsewhere",
                         persisted["profiles"]["default"]["display_name"])
        activity = json.loads(
            self.config_path.with_name("client-activity.json").read_text(encoding="utf-8"))
        self.assertEqual(1300, activity["clients"]["client-1"])

        restarted = GatewayState(self.config_path, None)
        self.assertEqual(1300, restarted.config["clients"][0]["last_seen_at"])

    def test_invalid_pairing_code_is_rejected(self):
        state = GatewayState(self.config_path, "123456")
        with self.assertRaises(PermissionError):
            state.pair("192.0.2.1", "000000", "TV")

    def test_pair_issues_short_lived_stream_pair_ticket(self):
        state = GatewayState(self.config_path, "123456")
        result = state.pair("192.0.2.1", "123456", "TV")

        self.assertTrue(result["stream_pair_ticket"])
        self.assertGreater(result["stream_pair_expires_seconds"], 0)
        self.assertNotIn(result["stream_pair_ticket"],
                         self.config_path.read_text(encoding="utf-8"))

    def test_stream_pair_ticket_is_bound_to_gateway_client_and_consumed(self):
        state = GatewayState(self.config_path, "123456")
        pairing = state.pair("192.0.2.1", "123456", "TV")
        client = state.client_for_token(pairing["token"])
        calls = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            calls.append((name, path, body, timeout)) or
            (True, {"client_uuid": "paired-client", "permissions": 0x07001F00}))

        status, result = state.vibepollo_pair_client(
            "192.0.2.1", client, pairing["stream_pair_ticket"],
            {"pin": "1234", "name": "MoonWaker TV"})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("vibepollo", "/pair",
                          {"pin": "1234", "name": "MoonWaker TV"}, 13.0), calls[0])
        with self.assertRaises(PermissionError):
            state.vibepollo_pair_client(
                "192.0.2.1", client, pairing["stream_pair_ticket"],
                {"pin": "1234", "name": "MoonWaker TV"})

    def test_stream_pair_ticket_rejects_another_address(self):
        state = GatewayState(self.config_path, "123456")
        pairing = state.pair("192.0.2.1", "123456", "TV")
        client = state.client_for_token(pairing["token"])
        with self.assertRaises(PermissionError):
            state.vibepollo_pair_client(
                "192.0.2.2", client, pairing["stream_pair_ticket"],
                {"pin": "1234", "name": "MoonWaker TV"})

    def test_runtime_report_identifies_the_running_gateway_build(self):
        state = GatewayState(self.config_path, None)
        state.write_runtime_info()

        runtime = json.loads(state.gateway_runtime_path.read_text(encoding="utf-8"))
        self.assertEqual(os.getpid(), runtime["pid"])
        self.assertIn("version", runtime)
        self.assertIn("source_sha256", runtime)

    def test_running_gateway_accepts_locally_activated_pairing_code(self):
        state = GatewayState(self.config_path, None)
        state.pairing_control_path.write_text(json.dumps({
            "code_sha256": sha256_text("654321"),
            "expires_at": int(__import__("time").time()) + 600,
        }), encoding="utf-8")

        self.assertTrue(state.pairing_active())
        result = state.pair("192.0.2.1", "654321", "TV")
        self.assertIsNotNone(state.client_for_token(result["token"]))

    def test_bridge_url_must_remain_on_loopback(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["default"]["vibepollo_bridge"] = "http://192.0.2.2:8775"
        with self.assertRaises(ValueError):
            state.bridge_url("vibepollo", "/health")

    def test_microphone_probe_is_fail_closed_and_uses_worker_probe(self):
        state = GatewayState(self.config_path, None)
        self.assertFalse(state.probe_microphone())
        self.assertEqual(
            {"available": False, "reason": "worker_missing"},
            state.microphone_status())
        worker = self.config_path.with_name("worker.exe")
        worker.write_bytes(b"test")
        self.addCleanup(worker.unlink)
        state.config["microphone_worker"] = worker.name
        with mock.patch.object(wakeplay_gateway.subprocess, "run") as run:
            run.return_value.returncode = 0
            self.assertTrue(state.probe_microphone())
            self.assertEqual("ready", state.microphone_status()["reason"])
            run.return_value.returncode = 1
            self.assertEqual(
                "steam_endpoint_missing_or_ambiguous_or_unsupported",
                state.microphone_status()["reason"])
        self.assertEqual([str(worker), "--probe"], run.call_args.args[0])

    def test_microphone_capability_exposes_only_safe_status(self):
        state = GatewayState(self.config_path, None)
        with mock.patch.object(state, "proxy", return_value=(False, {})), \
                mock.patch.object(state, "microphone_status", return_value={
                    "available": False,
                    "reason": "steam_endpoint_missing_or_ambiguous_or_unsupported",
                }):
            microphone = state.capabilities()["capabilities"]["microphone"]
        self.assertEqual(False, microphone["available"])
        self.assertEqual(
            "steam_endpoint_missing_or_ambiguous_or_unsupported",
            microphone["reason"])
        self.assertNotIn("error", microphone)
        self.assertNotIn("path", microphone)

    def test_discord_audio_status_uses_private_target_and_exposes_safe_reason(self):
        state = GatewayState(self.config_path, None)
        worker = self.config_path.with_name("discord-audio-worker.exe")
        worker.write_bytes(b"test")
        self.addCleanup(worker.unlink)
        state.config["discord_audio_worker"] = worker.name
        state.proxy = mock.Mock(return_value=(True, {
            "ready": True, "reason": "ready", "pid": 1234,
        }))
        with mock.patch.object(wakeplay_gateway.subprocess, "run") as run:
            run.return_value.returncode = 0
            self.assertEqual({"available": True, "reason": "ready"},
                             state.discord_audio_status())
        self.assertEqual([str(worker), "--probe", "1234"], run.call_args.args[0])
        state.proxy.assert_called_with("discord", "/audio-capture-target", timeout=2.0)

        state.proxy.return_value = (True, {
            "ready": False, "reason": "discord_process_missing", "pid": 0,
        })
        status = state.discord_audio_status()
        self.assertEqual({"available": False, "reason": "discord_process_missing"}, status)
        self.assertNotIn("pid", status)

    def test_discord_audio_stream_frames_and_always_releases_worker(self):
        handler = object.__new__(GatewayHandler)
        handler.headers = Message()
        handler.headers["X-Request-Id"] = "request-1"
        handler.wfile = io.BytesIO()
        handler.close_connection = False
        headers = []
        handler.send_response = lambda status: headers.append(("status", int(status)))
        handler.send_header = lambda name, value: headers.append((name, value))
        handler.end_headers = lambda: None
        state = SimpleNamespace(
            lock=__import__("threading").RLock(), discord_audio_streams={},
            discord_audio_worker=lambda: self.config_path,
            discord_audio_target=lambda: ("ready", 1234),
        )
        handler.server = SimpleNamespace(state=state)
        frame = b"x" * wakeplay_gateway.DISCORD_AUDIO_FRAME_BYTES
        process = mock.Mock()
        process.stdout = io.BytesIO(frame)
        process.poll.return_value = None
        with mock.patch.object(wakeplay_gateway.subprocess, "Popen", return_value=process):
            handler.discord_audio_stream("default")

        self.assertEqual({}, state.discord_audio_streams)
        self.assertIn(("Content-Type", wakeplay_gateway.DISCORD_AUDIO_CONTENT_TYPE), headers)
        self.assertIn(("Cache-Control", "no-store"), headers)
        self.assertIn(("X-Content-Type-Options", "nosniff"), headers)
        self.assertEqual(
            f"{len(frame):X}\r\n".encode("ascii") + frame + b"\r\n0\r\n\r\n",
            handler.wfile.getvalue())
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=2.0)

    def test_discord_audio_stream_rejects_second_profile_session(self):
        handler = object.__new__(GatewayHandler)
        handler.headers = Message()
        handler.headers["X-Request-Id"] = "request-2"
        state = SimpleNamespace(
            lock=__import__("threading").RLock(),
            discord_audio_streams={"default": "request-1"},
            discord_audio_worker=lambda: self.config_path,
            discord_audio_target=lambda: ("ready", 1234),
        )
        handler.server = SimpleNamespace(state=state)
        responses = []
        handler.send_json = lambda status, body: responses.append((status, body))

        handler.discord_audio_stream("default")

        self.assertEqual(409, responses[0][0])
        self.assertEqual("request-1", state.discord_audio_streams["default"])

    def test_discord_audio_silence_heartbeat_disconnect_releases_session(self):
        class ClosedClient:
            def write(self, _data):
                raise BrokenPipeError("client closed")
            def flush(self):
                pass

        handler = object.__new__(GatewayHandler)
        handler.headers = Message()
        handler.headers["X-Request-Id"] = "request-1"
        handler.wfile = ClosedClient()
        handler.close_connection = False
        handler.send_response = lambda _status: None
        handler.send_header = lambda _name, _value: None
        handler.end_headers = lambda: None
        state = SimpleNamespace(
            lock=__import__("threading").RLock(), discord_audio_streams={},
            discord_audio_worker=lambda: self.config_path,
            discord_audio_target=lambda: ("ready", 1234),
        )
        handler.server = SimpleNamespace(state=state)
        process = mock.Mock()
        process.stdout = io.BytesIO(b"\0" * wakeplay_gateway.DISCORD_AUDIO_FRAME_BYTES)
        process.poll.return_value = None
        with mock.patch.object(wakeplay_gateway.subprocess, "Popen", return_value=process):
            handler.discord_audio_stream("default")

        self.assertTrue(handler.close_connection)
        self.assertEqual({}, state.discord_audio_streams)
        process.kill.assert_called_once_with()

    def test_network_download_streams_safe_default_with_identity_and_no_store(self):
        class CountingClient:
            def __init__(self):
                self.bytes = 0
                self.all_zero = True
            def write(self, data):
                self.bytes += len(data)
                self.all_zero = self.all_zero and not any(data)
            def flush(self):
                pass

        handler = object.__new__(GatewayHandler)
        handler.wfile = CountingClient()
        handler.close_connection = False
        headers = []
        handler.send_response = lambda status: headers.append(("status", int(status)))
        handler.send_header = lambda name, value: headers.append((name, value))
        handler.end_headers = lambda: None
        state = SimpleNamespace(
            lock=__import__("threading").RLock(), network_downloads=set())
        handler.server = SimpleNamespace(state=state)

        handler.network_download(None, "default", "client-1")

        self.assertEqual(wakeplay_gateway.NETWORK_DOWNLOAD_DEFAULT_BYTES,
                         handler.wfile.bytes)
        self.assertTrue(handler.wfile.all_zero)
        self.assertEqual(set(), state.network_downloads)
        self.assertIn(("Content-Encoding", "identity"), headers)
        self.assertIn(("Cache-Control", "no-store"), headers)
        self.assertIn(("Content-Length", str(
            wakeplay_gateway.NETWORK_DOWNLOAD_DEFAULT_BYTES)), headers)

    def test_network_download_route_requires_gateway_authentication(self):
        handler = object.__new__(GatewayHandler)
        handler.path = "/api/v1/diagnostics/network/download?size=1024"
        handler.headers = Message()
        handler.server = SimpleNamespace(state=SimpleNamespace())
        responses = []
        handler.send_json = lambda status, body: responses.append((int(status), body))

        handler._do_GET()

        self.assertEqual(401, responses[0][0])

    def test_network_download_rejects_oversize_and_concurrent_client_profile(self):
        handler = object.__new__(GatewayHandler)
        state = SimpleNamespace(
            lock=__import__("threading").RLock(), network_downloads=set())
        handler.server = SimpleNamespace(state=state)

        with self.assertRaisesRegex(ValueError, "512 MiB"):
            handler.network_download(
                str(wakeplay_gateway.NETWORK_DOWNLOAD_MAX_BYTES + 1),
                "default", "client-1")

        state.network_downloads.add(("default", "client-1"))
        responses = []
        handler.send_json = lambda status, body: responses.append((int(status), body))
        handler.network_download("1", "default", "client-1")

        self.assertEqual(409, responses[0][0])
        self.assertEqual({("default", "client-1")}, state.network_downloads)

    def test_network_download_disconnect_releases_client_profile_slot(self):
        class ClosedClient:
            def write(self, _data):
                raise BrokenPipeError("client closed")
            def flush(self):
                pass

        handler = object.__new__(GatewayHandler)
        handler.wfile = ClosedClient()
        handler.close_connection = False
        handler.send_response = lambda _status: None
        handler.send_header = lambda _name, _value: None
        handler.end_headers = lambda: None
        state = SimpleNamespace(
            lock=__import__("threading").RLock(), network_downloads=set())
        handler.server = SimpleNamespace(state=state)

        handler.network_download("1024", "default", "client-1")

        self.assertTrue(handler.close_connection)
        self.assertEqual(set(), state.network_downloads)

    def test_microphone_chunk_parser_frames_and_bounds_input(self):
        handler = object.__new__(GatewayHandler)
        handler.connection = SimpleNamespace(settimeout=lambda _value: None)
        frame = b"x" * wakeplay_gateway.MICROPHONE_FRAME_BYTES
        handler.rfile = io.BytesIO(b"780\r\n" + frame + b"\r\n0\r\n\r\nX")
        output = io.BytesIO()
        handler.read_microphone_chunks(output)
        self.assertEqual(frame, output.getvalue())
        self.assertEqual(b"X", handler.rfile.read())

        handler.rfile = io.BytesIO(b"2001\r\n")
        with self.assertRaisesRegex(ValueError, "too large"):
            handler.read_microphone_chunks(io.BytesIO())

        handler.rfile = io.BytesIO(b"1\r\nx\r\n0\r\n\r\n")
        with self.assertRaisesRegex(ValueError, "Partial"):
            handler.read_microphone_chunks(io.BytesIO())

    def test_microphone_stream_rejects_second_profile_session_before_body(self):
        handler = object.__new__(GatewayHandler)
        handler.headers = Message()
        handler.headers["Transfer-Encoding"] = "chunked"
        handler.headers["Content-Type"] = (
            "application/vnd.moonwaker.microphone-pcm;"
            "format=s16le;rate=48000;channels=1")
        handler.headers["X-Request-Id"] = "request-1"
        handler.headers["X-Microphone-Session-Id"] = "session-2"
        state = SimpleNamespace(
            lock=__import__("threading").RLock(),
            microphone_streams={"default": "session-1"},
            microphone_worker=lambda: self.config_path,
        )
        handler.server = SimpleNamespace(state=state)
        responses = []
        handler.send_json = lambda status, body: responses.append((status, body))

        handler.microphone_stream("default")

        self.assertEqual(409, responses[0][0])
        self.assertEqual("session-1", state.microphone_streams["default"])

    def test_microphone_stream_parser_failure_always_releases_session_and_worker(self):
        handler = object.__new__(GatewayHandler)
        handler.headers = Message()
        handler.headers["Transfer-Encoding"] = "chunked"
        handler.headers["Content-Type"] = (
            "application/vnd.moonwaker.microphone-pcm;"
            "format=s16le;rate=48000;channels=1")
        handler.headers["X-Request-Id"] = "request-1"
        handler.headers["X-Microphone-Session-Id"] = "session-1"
        handler.connection = SimpleNamespace(settimeout=lambda _value: None)
        handler.rfile = io.BytesIO(b"invalid\r\n")
        state = SimpleNamespace(
            lock=__import__("threading").RLock(), microphone_streams={},
            microphone_worker=lambda: self.config_path,
        )
        handler.server = SimpleNamespace(state=state)

        process = mock.Mock()
        process.stdin = io.BytesIO()
        process.poll.return_value = None
        with mock.patch.object(wakeplay_gateway.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(ValueError, "chunk size"):
                handler.microphone_stream("default")

        self.assertEqual({}, state.microphone_streams)
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=2.0)

    def test_sleep_host_schedules_native_action_without_waiting(self):
        state = GatewayState(self.config_path, None)
        scheduled = []
        state._schedule_system_sleep = lambda: scheduled.append(True)

        status, result = state.sleep_host()

        if os.name == "nt":
            self.assertEqual(202, status)
            self.assertTrue(result["accepted"])
            self.assertEqual([True], scheduled)
        else:
            self.assertEqual(501, status)
            self.assertEqual([], scheduled)

    def test_suspend_session_validates_and_delays_sleep_for_stream_shutdown(self):
        state = GatewayState(self.config_path, None)
        scheduled = []
        state._schedule_system_sleep = lambda delay=0.75, force=False: scheduled.append((delay, force))
        suspend_id = "4f31dce8-8743-4a18-aed4-0abf09e589d1"

        status, result = state.suspend_session({
            "suspend_id": suspend_id,
            "sunshine_app_id": 42,
            "playnite_game_id": "ABC-123",
            "title": "Hollow Knight",
        }, suspend_id)

        if os.name == "nt":
            self.assertEqual(202, status)
            self.assertTrue(result["accepted"])
            self.assertEqual(suspend_id, result["suspend_id"])
            self.assertEqual("abc-123", result["session"]["playnite_game_id"])
            self.assertEqual([(2.5, True)], scheduled)
        else:
            self.assertEqual(501, status)
            self.assertEqual([], scheduled)

    def test_suspend_session_rejects_missing_sunshine_application(self):
        state = GatewayState(self.config_path, None)
        if os.name != "nt":
            self.skipTest("Windows-only validation path")
        state._schedule_system_sleep = lambda delay=0.75, force=False: self.fail("must not sleep")
        suspend_id = "4f31dce8-8743-4a18-aed4-0abf09e589d1"

        status, result = state.suspend_session({
            "suspend_id": suspend_id, "playnite_game_id": "abc"
        }, suspend_id)

        self.assertEqual(400, status)
        self.assertFalse(result["ok"])

    def test_suspend_session_rejects_mismatched_suspend_id(self):
        state = GatewayState(self.config_path, None)
        status, result = state.suspend_session({
            "suspend_id": "request-a", "sunshine_app_id": 42
        }, "request-b")

        self.assertEqual(400, status)
        self.assertFalse(result["ok"])

    def test_suspend_idempotency_reuses_typed_acceptance(self):
        state = GatewayState(self.config_path, None)
        calls = []
        operation = lambda: (calls.append(True) or (202, {
            "accepted": True, "suspend_id": "request-a"
        }))

        first = state.idempotent("session-suspend:request-a", operation)
        second = state.idempotent("session-suspend:request-a", operation)

        self.assertEqual(first, second)
        self.assertEqual([True], calls)
    def test_profile_selects_its_own_loopback_bridges(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
            "enabled": True,
            "discord_bridge": "http://127.0.0.1:8865",
            "vibepollo_bridge": "http://localhost:8875",
            "playnite_bridge": "http://127.0.0.1:8880",
        }

        self.assertEqual("basia", state.select_profile("basia"))
        self.assertEqual(
            "http://127.0.0.1:8865/health",
            state.bridge_url("discord", "/health"),
        )
        self.assertEqual(
            "http://127.0.0.1:8880/library/list",
            state.bridge_url("playnite", "/library/list"),
        )
        self.assertEqual("basia", state.capabilities()["gateway"]["integration_profile_id"])

    def test_authenticated_profile_use_is_persisted_for_host_control(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
            "enabled": True,
            "discord_bridge": "http://127.0.0.1:8865",
            "vibepollo_bridge": "http://127.0.0.1:8875",
            "playnite_bridge": "http://127.0.0.1:8880",
        }

        state.select_profile("basia", record_use=True)

        runtime = json.loads(state.runtime_status_path.read_text(encoding="utf-8"))
        self.assertEqual("basia", runtime["profile_id"])
        self.assertGreater(runtime["updated_at"], 0)

    def test_unknown_or_invalid_profile_is_rejected(self):
        state = GatewayState(self.config_path, None)
        with self.assertRaises(ValueError):
            state.select_profile("missing")
        with self.assertRaises(ValueError):
            state.select_profile("../default")

    def test_profile_listing_is_filtered_to_client_use_grants(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
            "name": "Basia",
            "enabled": True,
            "discord_bridge": "http://127.0.0.1:8865",
            "vibepollo_bridge": "http://127.0.0.1:8875",
            "game_provider_bridge": "http://127.0.0.1:8880",
        }
        token = "filtered-token"
        state.config["clients"].append({
            "id": "client-1",
            "token_sha256": sha256_text(token),
            "last_seen_at": int(time.time()),
            "profile_grants": {"default": ["use_profile"]},
        })
        state.discord_status = lambda: {
            "bridge_online": False, "rpc_connected": False,
            "authenticated": False, "error": "",
        }
        state.proxy = lambda *_args, **_kwargs: (False, {})
        handler, responses = self.request_handler(
            state, "/api/v1/profiles", token)

        handler.do_GET()

        self.assertEqual(200, responses[0][0])
        self.assertEqual(["default"], [
            profile["id"] for profile in responses[0][1]["profiles"]])

    def test_manual_unauthorized_profile_header_is_forbidden(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
            "name": "Basia", "enabled": True,
        }
        token = "limited-token"
        state.config["clients"].append({
            "id": "client-1",
            "token_sha256": sha256_text(token),
            "last_seen_at": int(time.time()),
            "profile_grants": {"default": ["use_profile"]},
        })
        state.playnite_health = mock.Mock(return_value=(200, {"ok": True}))
        handler, responses = self.request_handler(
            state, "/api/v1/playnite/health", token, "basia")

        handler.do_GET()

        self.assertEqual(403, responses[0][0])
        state.playnite_health.assert_not_called()

    def test_authorized_profile_header_reaches_profile_route(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
            "name": "Basia", "enabled": True,
        }
        token = "authorized-token"
        state.config["clients"].append({
            "id": "client-1",
            "token_sha256": sha256_text(token),
            "last_seen_at": int(time.time()),
            "profile_grants": {"basia": ["use_profile"]},
        })
        state.playnite_health = mock.Mock(return_value=(200, {"ok": True}))
        handler, responses = self.request_handler(
            state, "/api/v1/playnite/health", token, "basia")

        handler.do_GET()

        self.assertEqual((200, {"ok": True}), responses[0])
        self.assertEqual("basia", state.last_runtime_profile)
        state.playnite_health.assert_called_once_with()

    def test_missing_or_non_boolean_profile_enablement_fails_closed(self):
        token = "malformed-profile-token"
        malformed_profiles = {
            "missing": {"id": "missing", "name": "Missing"},
            "null": {"id": "null", "name": "Null", "enabled": None},
            "string": {"id": "string", "name": "String", "enabled": "true"},
            "integer": {"id": "integer", "name": "Integer", "enabled": 1},
            "false": {"id": "false", "name": "False", "enabled": False},
        }
        grants = {profile_id: ["use_profile"] for profile_id in malformed_profiles}
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem",
            "private_key": "key.pem",
            "profiles": malformed_profiles,
            "clients": [{
                "id": "client-1",
                "token_sha256": sha256_text(token),
                "last_seen_at": int(time.time()),
                "profile_grants": grants,
            }],
        }), encoding="utf-8")

        state = GatewayState(self.config_path, None)
        self.assertTrue(all(profile["enabled"] is False
                            for profile in state.config["profiles"].values()))

        # A live schema-2 edit is loaded without migration normalization, so
        # request-time checks must independently reject malformed values.
        live_registry = json.loads(self.config_path.read_text(encoding="utf-8"))
        live_registry["profiles"] = malformed_profiles
        self.config_path.write_text(json.dumps(live_registry), encoding="utf-8")
        state.registry_modified_ns = -1
        client = state.client_for_token(token)
        self.assertIsNotNone(client)

        state.discord_status = mock.Mock(
            side_effect=AssertionError("disabled profile Bridge was probed"))
        state.proxy = mock.Mock(
            side_effect=AssertionError("disabled profile Bridge was probed"))
        self.assertEqual([], state.profiles_summary(client)["profiles"])
        state.discord_status.assert_not_called()
        state.proxy.assert_not_called()

        state.playnite_health = mock.Mock(return_value=(200, {"ok": True}))
        for profile_id in malformed_profiles:
            handler, responses = self.request_handler(
                state, "/api/v1/playnite/health", token, profile_id)
            handler.do_GET()
            self.assertEqual(403, responses[0][0], profile_id)
            with self.assertRaises(PermissionError, msg=profile_id):
                state.select_profile(profile_id)
        state.playnite_health.assert_not_called()

    def test_host_wide_sleep_does_not_select_arbitrary_profile(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {"name": "Basia", "enabled": True}
        token = "host-token"
        state.config["clients"].append({
            "id": "client-1",
            "token_sha256": sha256_text(token),
            "last_seen_at": int(time.time()),
            "profile_grants": {"default": ["use_profile"]},
        })
        state.authorize_profile = mock.Mock(
            side_effect=AssertionError("host-wide route selected a profile"))
        state.sleep_host = mock.Mock(return_value=(202, {"ok": True}))
        handler, responses = self.request_handler(
            state, "/api/v1/system/sleep", token, "../invalid")
        handler.headers["X-Request-Id"] = "sleep-request"

        handler.do_POST()

        self.assertEqual((202, {"ok": True}), responses[0])
        state.authorize_profile.assert_not_called()
        state.sleep_host.assert_called_once_with()

    def test_profile_deletion_removes_all_client_grants(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {"name": "Basia"}
        state.config["clients"] = [
            {"profile_grants": {
                "default": ["use_profile"],
                "basia": ["use_profile", "remote_sign_in"],
            }},
            {"profile_grants": {"basia": ["use_profile"]}},
        ]
        state.save()

        self.assertTrue(state.delete_profile("basia"))

        saved = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.assertNotIn("basia", saved["profiles"])
        self.assertEqual({"default": ["use_profile"]},
                         saved["clients"][0]["profile_grants"])
        self.assertEqual({}, saved["clients"][1]["profile_grants"])

    def test_profile_summary_reports_health_without_bridge_addresses(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
            "name": "Basia",
            "enabled": True,
            "discord_bridge": "http://127.0.0.1:8865",
            "vibepollo_bridge": "http://127.0.0.1:8875",
        }
        state.discord_status = lambda: {
            "bridge_online": True,
            "rpc_connected": state.profile_id == "basia",
            "authenticated": state.profile_id == "basia",
            "error": "",
        }
        state.proxy = lambda name, path, timeout=2.5: (
            True, {"installed": state.profile_id == "basia"})

        summary = state.profiles_summary({
            "profile_grants": {
                "default": ["use_profile"],
                "basia": ["use_profile"],
            },
        })

        self.assertEqual("basia", summary["suggested_profile_id"])
        self.assertEqual("default", state.profile_id)
        basia = next(item for item in summary["profiles"] if item["id"] == "basia")
        self.assertEqual("Basia", basia["name"])
        self.assertTrue(basia["discord_rpc_connected"])
        self.assertTrue(basia["virtualhere_available"])
        self.assertNotIn("discord_bridge", basia)
        self.assertNotIn("vibepollo_bridge", basia)

    def test_idempotent_action_runs_once(self):
        state = GatewayState(self.config_path, None)
        calls = []

        def operation():
            calls.append(True)
            return 200, {"ok": True}

        first = state.idempotent("request-1", operation)
        second = state.idempotent("request-1", operation)
        self.assertEqual(first, second)
        self.assertEqual(1, len(calls))

    def test_vibepollo_app_creation_forwards_only_validated_fields(self):
        state = GatewayState(self.config_path, None)
        calls = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            calls.append((name, path, body, timeout)) or
            (True, {"created": True, "uuid": "generated", "name": body["name"]}))

        status, result = state.vibepollo_ensure_app({
            "playnite_game_id": "11223344-5566-7788-99aa-bbccddeeff00",
            "name": "  Żółw Ninja  ",
            "ignored": "must not cross the loopback boundary",
        })

        self.assertEqual(202, status)
        self.assertTrue(result["ok"])
        self.assertEqual("preparing", result["state"])
        for _ in range(50):
            if calls:
                break
            time.sleep(0.01)
        self.assertEqual(1, len(calls))
        self.assertEqual("vibepollo", calls[0][0])
        self.assertEqual("/apps/ensure", calls[0][1])
        self.assertEqual({
            "playnite_game_id": "11223344-5566-7788-99aa-bbccddeeff00",
            "name": "Żółw Ninja",
        }, calls[0][2])

    def test_vibepollo_app_creation_rejects_invalid_input(self):
        state = GatewayState(self.config_path, None)
        with self.assertRaises(ValueError):
            state.vibepollo_ensure_app({"playnite_game_id": "../bad", "name": "Game"})
        with self.assertRaises(ValueError):
            state.vibepollo_ensure_app({
                "playnite_game_id": "11223344-5566-7788-99aa-bbccddeeff00",
                "name": "bad\nname",
            })

    def test_library_exposes_gateway_owned_vibepollo_preparation(self):
        state = GatewayState(self.config_path, None)
        game_id = "11223344-5566-7788-99aa-bbccddeeff00"
        state.vibepollo_app_operations["default:" + game_id] = {
            "state": "preparing", "playnite_game_id": game_id,
        }
        state.proxy = lambda name, path, timeout=2.5: (True, {
            "games": [{"id": game_id, "name": "Game"}],
        })

        status, result = state.playnite_library("", 50)

        self.assertEqual(200, status)
        self.assertEqual("preparing",
                         result["library"]["games"][0]["vibepollo_state"])

    def test_capabilities_advertise_vibepollo_app_management(self):
        state = GatewayState(self.config_path, None)
        state.proxy = lambda name, path, timeout=2.5: (True, {"ok": True})

        capabilities = state.capabilities()["capabilities"]

        self.assertTrue(capabilities["vibepollo_apps"]["available"])

    def test_discord_status_normalizes_bridge_health(self):
        state = GatewayState(self.config_path, None)
        state.proxy = lambda name, path, timeout=2.5: (True, {
            "message": "ok\tconnected=True\tauthenticated=True\tpipe=discord-ipc-0\terror="
        })

        status = state.discord_status()

        self.assertTrue(status["bridge_online"])
        self.assertTrue(status["rpc_connected"])
        self.assertTrue(status["authenticated"])
        self.assertEqual("discord-ipc-0", status["pipe"])

    def test_discord_channels_rejects_invalid_guild_id(self):
        state = GatewayState(self.config_path, None)
        state.discord_ready = lambda: (True, "")
        with self.assertRaises(ValueError):
            state.discord_channels("../../shutdown")

    def test_discord_ready_preserves_bridge_profile_conflict(self):
        state = GatewayState(self.config_path, None)
        conflict = (
            "Discord RPC is owned by another Windows profile. Fully exit Discord "
            "in the other profile, then start it in this Bridge profile."
        )
        state.proxy = lambda name, path, timeout=2.5: (True, {
            "message": "ok\tconnected=False\tauthenticated=False\tpipe=\terror=" + conflict
        })

        ready, error = state.discord_ready()

        self.assertFalse(ready)
        self.assertEqual(conflict, error)

    def test_discord_join_uses_allowlisted_encoded_query(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.discord_ready = lambda: (True, "")
        state.proxy = lambda name, path, timeout=2.5: (requests.append((name, path)) is None, {"message": "ok"})

        status, result = state.discord_action("join", {
            "channel_id": "123456789012345678",
            "guild_id": "987654321098765432",
            "guild_name": "A guild & friends",
            "channel_name": "Games / voice",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual("discord", requests[0][0])
        parsed = urllib.parse.urlsplit(requests[0][1])
        self.assertEqual("/join-advanced", parsed.path)
        query = urllib.parse.parse_qs(parsed.query)
        self.assertEqual(["A guild & friends"], query["guild_name"])
        self.assertEqual(["Games / voice"], query["channel_name"])

    def test_discord_home_returns_conflict_before_calling_rpc(self):
        state = GatewayState(self.config_path, None)
        state.discord_ready = lambda: (False, "Discord client is not running in the Bridge user session.")
        state.proxy = lambda *args, **kwargs: self.fail("RPC proxy must not run while Discord is unavailable")

        status, result = state.discord_home()

        self.assertEqual(409, status)
        self.assertFalse(result["ok"])
        self.assertIn("not running", result["error"])

    def test_start_discord_is_a_separate_allowlisted_action(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy = lambda name, path, timeout=2.5: (requests.append((name, path)) is None, {"message": "ok"})

        status, result = state.discord_action("start", {})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("discord", "/start-discord"), requests[0])

    def test_virtualhere_action_encodes_allowlisted_device_address(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy = lambda name, path, timeout=2.5: (requests.append((name, path)) is None, {"message": "ok"})

        status, result = state.virtualhere_action("use", {"address": "gaming-pc.114"})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual("discord", requests[0][0])
        parsed = urllib.parse.urlsplit(requests[0][1])
        self.assertEqual("/virtualhere-action", parsed.path)
        self.assertEqual(["use"], urllib.parse.parse_qs(parsed.query)["action"])
        self.assertEqual(["gaming-pc.114"], urllib.parse.parse_qs(parsed.query)["address"])

    def test_virtualhere_action_rejects_untrusted_address(self):
        state = GatewayState(self.config_path, None)
        with self.assertRaises(ValueError):
            state.virtualhere_action("stop", {"address": "../../shutdown"})

    def test_discord_participant_volume_is_strictly_allowlisted(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.discord_ready = lambda: (True, "")
        state.proxy = lambda name, path, timeout=2.5: (requests.append(path) is None, {"message": "ok"})

        status, result = state.discord_action("user-volume", {
            "user_id": "123456789012345678",
            "delta": -10,
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        parsed = urllib.parse.urlsplit(requests[0])
        self.assertEqual("/user-volume", parsed.path)
        self.assertEqual(["-10"], urllib.parse.parse_qs(parsed.query)["delta"])
        with self.assertRaises(ValueError):
            state.discord_action("user-volume", {
                "user_id": "123456789012345678", "delta": 1000})
        status, result = state.discord_action("user-volume", {
            "user_id": "123456789012345678", "volume": 140})
        self.assertEqual(200, status)
        self.assertEqual(["140"], urllib.parse.parse_qs(
            urllib.parse.urlsplit(requests[-1]).query)["value"])

    def test_audio_device_selection_is_allowlisted_and_encoded(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.discord_ready = lambda: (True, "")
        state.proxy = lambda name, path, timeout=2.5: (requests.append(path) is None, {"message": "ok"})

        status, result = state.audio_action("select", {
            "scope": "discord",
            "kind": "input",
            "device_id": "{0.0.1.00000000}.{1234-abcd}",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        parsed = urllib.parse.urlsplit(requests[0])
        self.assertEqual("/select-device", parsed.path)
        self.assertEqual(["input"], urllib.parse.parse_qs(parsed.query)["kind"])
        with self.assertRaises(ValueError):
            state.audio_action("select", {
                "scope": "system", "kind": "output", "device_id": "../../shutdown"})

    def test_playnite_library_is_paged_and_allowlisted(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy = lambda name, path, timeout=2.5: (
            requests.append((name, path)) is None,
            {"games": [{"id": "840317c9-b9a4-4f72-be8e-807414e36a9b"}],
             "next_cursor": "page:2"},
        )

        status, result = state.playnite_library("page:1", 40)

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual("game_provider", requests[0][0])
        parsed = urllib.parse.urlsplit(requests[0][1])
        self.assertEqual("/library/list", parsed.path)
        self.assertEqual(["page:1"], urllib.parse.parse_qs(parsed.query)["cursor"])
        self.assertEqual(["40"], urllib.parse.parse_qs(parsed.query)["limit"])
        with self.assertRaises(ValueError):
            state.playnite_library("../../secrets", 40)
        with self.assertRaises(ValueError):
            state.playnite_library("", 500)

    def test_playnite_artwork_uses_allowlisted_game_and_kind(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_bytes = lambda name, path, timeout=8.0: (
            requests.append((name, path)) or (200, b"image", "image/png"))
        status, body, content_type = state.playnite_artwork(
            "840317c9-b9a4-4f72-be8e-807414e36a9b", "cover")
        self.assertEqual((200, b"image", "image/png"), (status, body, content_type))
        parsed = urllib.parse.urlsplit(requests[0][1])
        self.assertEqual("/artwork", parsed.path)
        self.assertEqual(["cover"], urllib.parse.parse_qs(parsed.query)["kind"])
        state.playnite_artwork("steam:367520", "hero")
        hero = urllib.parse.urlsplit(requests[1][1])
        self.assertEqual(["hero"], urllib.parse.parse_qs(hero.query)["kind"])
        with self.assertRaises(ValueError):
            state.playnite_artwork("../../secret", "cover")
        with self.assertRaises(ValueError):
            state.playnite_artwork("840317c9-b9a4-4f72-be8e-807414e36a9b", "file")

    def test_playnite_start_accepts_only_a_guid_and_uses_profile_bridge(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None, {"accepted": True})

        status, result = state.playnite_action("game/start", {
            "game_id": "840317C9-B9A4-4F72-BE8E-807414E36A9B",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("game_provider", "/game/start", {
            "game_id": "840317c9-b9a4-4f72-be8e-807414e36a9b",
        }, 22.0), requests[0])
        with self.assertRaises(ValueError):
            state.playnite_action("game/start", {"game_id": "../../cmd.exe"})

    def test_playnite_install_is_forwarded_without_waiting_for_completion(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None, {"accepted": True})

        status, result = state.playnite_action("game/install", {
            "game_id": "840317C9-B9A4-4F72-BE8E-807414E36A9B",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("game_provider", "/game/install", {
            "game_id": "840317c9-b9a4-4f72-be8e-807414e36a9b",
        }, 15.0), requests[0])

    def test_playnite_terminal_operation_failure_is_not_successful_gateway_response(self):
        state = GatewayState(self.config_path, None)
        state.proxy_json = lambda _name, _path, _body, timeout=8.0: (True, {
            "accepted": False, "requires_attention": False,
            "operation_state": "failed",
            "reason": "epic_egl_export_verification_failed",
        })

        for action in ("game/install", "game/uninstall"):
            with self.subTest(action=action):
                status, result = state.playnite_action(action, {
                    "game_id": "840317C9-B9A4-4F72-BE8E-807414E36A9B",
                })

                self.assertEqual(200, status)
                self.assertFalse(result["ok"])
                self.assertEqual("epic_egl_export_verification_failed", result["error"])
                self.assertFalse(result["result"]["requires_attention"])

    def test_rejected_start_is_normalized_to_outer_failure(self):
        state = GatewayState(self.config_path, None)
        state.proxy_json = lambda _name, _path, _body, timeout=8.0: (True, {
            "accepted": False, "reason": "playnite_busy"})

        status, result = state.playnite_action("game/start", {
            "game_id": "840317C9-B9A4-4F72-BE8E-807414E36A9B",
        })

        self.assertEqual(200, status)
        self.assertFalse(result["ok"])
        self.assertEqual("playnite_busy", result["error"])
        self.assertEqual("playnite_busy", result["result"]["reason"])

    def test_provider_neutral_actions_accept_strict_record_ids(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body)) is None, {"accepted": True})

        for game_id in ("steam:289070", "epic:CelesteApp"):
            with self.subTest(game_id=game_id):
                status, result = state.playnite_action(
                    "game/start", {"game_id": game_id})
                self.assertEqual(200, status)
                self.assertTrue(result["ok"])

        self.assertEqual(["steam:289070", "epic:CelesteApp"],
                         [request[2]["game_id"] for request in requests])

    def test_playnite_installation_focus_is_scoped_to_the_requested_game(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None, {"focused": True})

        status, result = state.playnite_action("game/install/focus", {
            "game_id": "840317C9-B9A4-4F72-BE8E-807414E36A9B",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("game_provider", "/installation/focus", {
            "game_id": "840317c9-b9a4-4f72-be8e-807414e36a9b",
        }, 6.0), requests[0])

    def test_playnite_installation_verification_is_forwarded(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None,
            {"requires_attention": False, "status": "installing"})

        status, result = state.playnite_action("game/install/verify", {
            "game_id": "840317C9-B9A4-4F72-BE8E-807414E36A9B",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("game_provider", "/installation/verify", {
            "game_id": "840317c9-b9a4-4f72-be8e-807414e36a9b",
        }, 8.0), requests[0])

    def test_playnite_library_refresh_is_forwarded_as_non_blocking_action(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None,
            {"accepted": True, "previous_revision": "17"})

        status, result = state.playnite_action("library/refresh", {})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(
            ("game_provider", "/library/refresh", {}, 5.0), requests[0])

    def test_playnite_stop_is_graceful_by_contract(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((path, body, timeout)) is None, {"accepted": True})

        status, result = state.playnite_action("game/stop", {})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("/game/stop", {"force": False}, 25.0), requests[0])

    def test_session_hard_reset_is_explicit_and_closes_provider_before_stream(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None,
            {"accepted": True, "stopped_count": 2})
        state.proxy = lambda name, path, timeout=2.5: (
            requests.append((name, path, timeout)) is None, {"ok": True})

        status, result = state.hard_reset_session({"force": True})

        self.assertEqual(200, status)
        self.assertTrue(result["accepted"])
        self.assertEqual(2, result["stopped_game_count"])
        self.assertEqual(("game_provider", "/session/hard-reset",
                          {"force": True}, 12.0), requests[0])
        self.assertEqual(("vibepollo", "/action/close-app", 8.0), requests[1])
        with self.assertRaises(ValueError):
            state.hard_reset_session({})

    def test_provider_neutral_stop_preserves_exact_game_identity(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((path, body)) is None, {"accepted": True})

        status, result = state.playnite_action("game/stop", {
            "game_id": "epic:CelesteApp",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("/game/stop", {
            "force": False, "game_id": "epic:CelesteApp",
        }), requests[0])

    def test_rejected_stop_is_normalized_to_outer_failure(self):
        state = GatewayState(self.config_path, None)
        state.proxy_json = lambda _name, _path, _body, timeout=8.0: (True, {
            "accepted": False, "reason": "another_game_running"})

        status, result = state.playnite_action("game/stop", {
            "game_id": "steam:289070",
        })

        self.assertEqual(200, status)
        self.assertFalse(result["ok"])
        self.assertEqual("another_game_running", result["error"])

    def test_verified_stop_has_named_route_required_token_and_no_legacy_fallback(self):
        state = GatewayState(self.config_path, None)
        state.proxy_json = mock.Mock(return_value=(True, {
            "accepted": True, "stopped_game_id": "epic:Cowbird", "stopped_current": False}))
        payload = {"game_id": "epic:Cowbird", "expected_process_token": "a" * 64}
        status, result = state.playnite_action("game/stop-verified", payload)
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        state.proxy_json.assert_called_once_with(
            "game_provider", "/game/stop-verified", {"force": False, **payload}, timeout=25.0)
        for bad in (None, "", "A" * 64, "a" * 63):
            with self.assertRaises(ValueError):
                state.playnite_action("game/stop-verified", {**payload, "expected_process_token": bad})
        with self.assertRaises(ValueError):
            state.playnite_action("game/stop-verified", {"expected_process_token": "a" * 64})
        state.proxy_json.reset_mock()
        state.proxy_json.return_value = (False, {"error": "Endpoint not found"})
        status, result = state.playnite_action("game/stop-verified", payload)
        self.assertFalse(result["ok"])
        self.assertEqual(1, state.proxy_json.call_count)
        self.assertEqual("/game/stop-verified", state.proxy_json.call_args.args[1])

    def test_playnite_focus_is_narrow_and_bodyless(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None, {"focused": True})

        status, result = state.playnite_action("game/focus", {})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("game_provider", "/game/focus", {}, 6.0), requests[0])

    def test_playnite_events_sequence_is_allowlisted(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy = lambda name, path, timeout=2.5: (
            requests.append((name, path, timeout)) is None,
            {"events": [{"sequence": 12, "event": "game-stopped"}]},
        )
        status, result = state.playnite_events("11", "host:game:42:1000:1")
        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual("host:game:42:1000:1", result["transition_id"])
        self.assertEqual("/events?after=11", requests[0][1])
        with self.assertRaises(ValueError):
            state.playnite_events("../../logs")
        with self.assertRaises(ValueError):
            state.playnite_events("11", "../../wrong")


class GatewayDiagnosticsTest(unittest.TestCase):
    @staticmethod
    def read_entries(log_dir):
        entries = []
        for path in log_dir.glob("gateway-diagnostics.jsonl*"):
            entries.extend(json.loads(line) for line in
                           path.read_text(encoding="utf-8").splitlines())
        return entries

    def test_writer_schema_allowlist_and_safe_exception_frames(self):
        with tempfile.TemporaryDirectory() as temporary:
            log_dir = Path(temporary) / "logs"
            diagnostics = GatewayDiagnostics()
            diagnostics.start(log_dir)
            diagnostics.record(
                "request.failed", level="ERROR", method="POST",
                route="/api/v1/game/{id}", request_id="req:42",
                profile_id="living-room", status="failed", http_status=500,
                duration_ms=12, reason="Bearer secret", Authorization="token")
            try:
                raise RuntimeError("Bearer secret from C:/private/config.json")
            except RuntimeError as error:
                diagnostics.record("request.failed", level="ERROR", error=error,
                                   include_frames=True, route="https://host/api?token=secret",
                                   request_id="Bearer secret")
            diagnostics.close()

            entries = self.read_entries(log_dir)
            first = entries[0]
            self.assertEqual(1, first["v"])
            self.assertEqual("host.gateway", first["component"])
            self.assertEqual("req:42", first["request_id"])
            self.assertEqual("/api/v1/game/{id}", first["route"])
            self.assertTrue(first["ts"].endswith("Z"))
            self.assertNotIn("reason", first)
            self.assertNotIn("Authorization", first)
            serialized = json.dumps(entries)
            self.assertNotIn("Bearer secret", serialized)
            self.assertNotIn("C:/private", serialized)
            self.assertNotIn("route", entries[1])
            self.assertNotIn("request_id", entries[1])
            self.assertEqual("test_gateway.py", entries[1]["frames"][-1]["file"])
            self.assertLessEqual(len(entries[1]["frames"]), 16)

    def test_route_sanitizer_removes_query_and_rejects_unsafe_targets(self):
        self.assertEqual("/api/v1/library", diagnostic_route(
            "/api/v1/library?cursor=secret#fragment"))
        self.assertEqual("", diagnostic_route("https://host/api/v1/library?token=secret"))
        self.assertEqual("", diagnostic_route("/api/v1/bad path"))

    def test_writer_rotates_and_fails_open(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log_dir = root / "logs"
            log_dir.mkdir()
            old = time.time() - wakeplay_gateway.DIAGNOSTIC_RETENTION_SECONDS - 1
            for suffix in ("", ".1", ".secret", ".bak"):
                path = log_dir / f"gateway-diagnostics.jsonl{suffix}"
                path.write_text("old", encoding="utf-8")
                os.utime(path, (old, old))
            retention = GatewayDiagnostics()
            retention.start(log_dir)
            retention.close()
            self.assertFalse((log_dir / "gateway-diagnostics.jsonl").exists())
            self.assertFalse((log_dir / "gateway-diagnostics.jsonl.1").exists())
            self.assertTrue((log_dir / "gateway-diagnostics.jsonl.secret").exists())
            self.assertTrue((log_dir / "gateway-diagnostics.jsonl.bak").exists())
            (log_dir / "gateway-diagnostics.jsonl.secret").unlink()
            (log_dir / "gateway-diagnostics.jsonl.bak").unlink()

            with mock.patch.object(wakeplay_gateway, "DIAGNOSTIC_MAX_BYTES", 1024):
                diagnostics = GatewayDiagnostics()
                diagnostics.start(log_dir)
                for index in range(80):
                    diagnostics.record(
                        "request.completed", method="POST", route="/api/v1/game/start",
                        request_id=f"request-{index}", profile_id="p" * 256,
                        status="completed", http_status=200, duration_ms=index)
                diagnostics.close()
            files = list((root / "logs").glob("gateway-diagnostics.jsonl*"))
            self.assertGreater(len(files), 1)
            self.assertLessEqual(len(files), 10)

            unavailable = root / "not-a-directory"
            unavailable.write_text("occupied", encoding="utf-8")
            diagnostics = GatewayDiagnostics()
            diagnostics.start(unavailable / "logs")
            diagnostics.record("request.completed", status="completed")
            diagnostics.close()
            self.assertGreater(diagnostics.dropped, 0)

    def test_request_envelope_emits_expected_outcomes_and_clears_context(self):
        state = SimpleNamespace(request_context=__import__("threading").local())
        handler = object.__new__(GatewayHandler)
        handler.server = SimpleNamespace(state=state)
        handler.path = "/api/v1/game/start?token=secret"
        handler.headers = Message()
        handler.headers["X-Request-Id"] = "  request:42  "
        recorder = mock.Mock()
        with mock.patch.object(wakeplay_gateway, "DIAGNOSTICS", recorder):
            handler._begin_diagnostics("POST")
            state.request_context.profile_id = "living-room"
            handler._response_status = 202
            handler._finish_diagnostics()

            self.assertEqual(["request.started", "request.completed"],
                             [call.args[0] for call in recorder.record.call_args_list])
            self.assertEqual("request:42",
                             recorder.record.call_args_list[0].kwargs["request_id"])
            completed = recorder.record.call_args_list[-1].kwargs
            self.assertEqual("/api/v1/game/start", completed["route"])
            self.assertEqual("request:42", completed["request_id"])
            self.assertEqual("living-room", completed["profile_id"])
            self.assertFalse(hasattr(state.request_context, "request_id"))
            self.assertFalse(hasattr(state.request_context, "profile_id"))

            recorder.reset_mock()
            handler._begin_diagnostics("GET")
            handler._response_status = 404
            handler._finish_diagnostics()
            self.assertEqual(["request.failed"],
                             [call.args[0] for call in recorder.record.call_args_list])

            recorder.reset_mock()
            handler._begin_diagnostics("POST")
            handler._finish_diagnostics()
            self.assertEqual("request.failed", recorder.record.call_args_list[-1].args[0])
            self.assertIsNone(
                recorder.record.call_args_list[-1].kwargs["http_status"])

    def test_loopback_proxy_propagates_only_valid_request_id(self):
        with tempfile.TemporaryDirectory() as temporary:
            config_path = Path(temporary) / "gateway.json"
            config_path.write_text(json.dumps({
                "certificate": "cert.pem", "private_key": "key.pem", "clients": [],
            }), encoding="utf-8")
            state = GatewayState(config_path, None)
            captured = []

            class Response:
                headers = Message()
                headers["Content-Type"] = "application/json"

                def __enter__(self):
                    return self

                def __exit__(self, *_args):
                    return False

                def read(self, _limit):
                    return b"{}"

            def open_request(request, timeout):
                captured.append((request, timeout))
                return Response()

            with mock.patch.object(wakeplay_gateway.urllib.request, "urlopen",
                                   side_effect=open_request):
                for request_id in ("request:42", "Bearer secret", "", None):
                    state.request_context.request_id = request_id
                    state.proxy_json("playnite", "/health", {})

            headers = [dict((key.lower(), value) for key, value in
                            request.header_items()) for request, _timeout in captured]
            self.assertEqual("request:42", headers[0]["x-request-id"])
            self.assertNotIn("x-request-id", headers[1])
            self.assertNotIn("x-request-id", headers[2])
            self.assertNotIn("x-request-id", headers[3])


class LoginBrokerGatewayTest(unittest.TestCase):
    class FakeBroker:
        def __init__(self):
            self.profile = self.reply(True, "signed_out", fields={3: "ready", 4: "none"})
            self.begin_result = self.reply(
                True, "pending", fields={3: "0123456789abcdef0123456789abcdef"})
            self.attempt = self.reply(True, "completed")
            self.cancel_result = self.reply(
                False, "action_required", "attempt_cancelled",
                {3: "0123456789abcdef0123456789abcdef"})
            self.switch_result = self.reply(
                True, "pending", fields={3: "0123456789abcdef0123456789abcdef"})
            self.begin_calls = []
            self.cancel_calls = []
            self.switch_calls = []

        @staticmethod
        def reply(success, state, reason="none", fields=None):
            return {"success": success, "state": state, "reason": reason,
                    "fields": fields or {}}

        def profile_state(self, _profile):
            return self.profile

        def capability(self):
            return self.reply(True, "ready", fields={3: "1"})

        def begin(self, client_id, profile, request_id):
            self.begin_calls.append((client_id, profile["id"], request_id))
            return self.begin_result

        def attempt_state(self, _client_id, _profile_id, _request_id, _attempt_id):
            return self.attempt

        def cancel(self, _client_id, _profile_id, _request_id, _attempt_id):
            self.cancel_calls.append((_request_id, _attempt_id))
            return self.cancel_result

        def switch_session(self, client_id, profile, request_id):
            self.switch_calls.append((client_id, profile["id"], request_id))
            return self.switch_result

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temporary.name) / "gateway.json"
        self.config_path.write_text(json.dumps({
            "schema_version": wakeplay_gateway.GATEWAY_SCHEMA_VERSION,
            "certificate": "cert.pem", "private_key": "key.pem",
            "profiles": {"living-room": {
                "id": "living-room", "name": "Living room", "enabled": True,
                "windows_account_sid": "S-1-5-21-1-2-3-1001",
                "windows_account_name": "TESTPC\\Player",
                "remote_sign_in_enabled": True,
            }},
            "clients": [{
                "id": "android-tv", "token_sha256": sha256_text("token"),
                "profile_grants": {
                    "living-room": ["use_profile", "remote_sign_in"],
                },
            }],
        }), encoding="utf-8")
        self.broker = self.FakeBroker()
        self.state = GatewayState(
            self.config_path, None, broker_client=self.broker)
        self.client = self.state.config["clients"][0]

    def tearDown(self):
        self.temporary.cleanup()

    def test_binary_gateway_client_matches_mwl_v1(self):
        encoded = wakeplay_gateway.LoginBrokerClient.encode_request(
            4, {1: "living-room", 2: "S-1-5-21-1"})
        self.assertEqual(b"MWLB\x01\x04\x02\x00", encoded[:8])
        self.assertEqual(1, encoded[8])
        self.assertEqual(len("living-room"), int.from_bytes(encoded[9:13], "little"))

        response = bytearray(b"MWLR\x01\x00\x03\x00")
        for key, value in ((1, "locked"), (2, "none"), (3, "ready")):
            raw = value.encode("utf-8")
            response.extend(bytes((key,)) + len(raw).to_bytes(4, "little") + raw)
        decoded = wakeplay_gateway.LoginBrokerClient.decode_response(io.BytesIO(response))
        self.assertTrue(decoded["success"])
        self.assertEqual("locked", decoded["state"])
        self.assertEqual("ready", decoded["fields"][3])

    def test_ensure_begins_bound_attempt_only_when_target_needs_login(self):
        status, result = self.state.ensure_session(
            self.client, "living-room", "request-one")
        self.assertEqual(202, status)
        self.assertEqual("pending", result["state"])
        self.assertEqual("0123456789abcdef0123456789abcdef", result["attempt_id"])
        self.assertEqual(
            [("android-tv", "living-room", "request-one")], self.broker.begin_calls)

        self.broker.profile = self.broker.reply(
            True, "active", fields={3: "ready", 4: "none"})
        status, result = self.state.ensure_session(
            self.client, "living-room", "request-two")
        self.assertEqual(200, status)
        self.assertEqual("ready", result["state"])
        self.assertEqual(1, len(self.broker.begin_calls))

    def test_switch_maps_pending_and_missing_credential_attention(self):
        status, result = self.state.switch_session(
            self.client, "living-room", "request-switch")
        self.assertEqual(202, status)
        self.assertEqual("pending", result["state"])
        self.assertEqual("0123456789abcdef0123456789abcdef", result["attempt_id"])
        self.assertEqual(
            [("android-tv", "living-room", "request-switch")],
            self.broker.switch_calls)

        self.broker.switch_result = self.broker.reply(
            False, "attention_required", "credential_missing",
            {3: "fedcba9876543210fedcba9876543210"})
        status, result = self.state.switch_session(
            self.client, "living-room", "request-no-credential")
        self.assertEqual(409, status)
        self.assertEqual("attention_required", result["state"])
        self.assertEqual("credential_missing", result["reason"])

    def test_switch_route_requires_remote_sign_in_grant(self):
        self.client["profile_grants"]["living-room"] = ["use_profile"]
        handler, responses = GatewayStateTest.request_handler(
            self.state, "/api/v1/system/session/switch", "token", "living-room")
        handler.headers["X-Request-Id"] = "request-switch-route"
        handler.do_POST()
        self.assertEqual(403, responses[0][0])
        self.assertEqual([], self.broker.switch_calls)

    def test_other_user_or_missing_credential_requires_action(self):
        self.broker.profile = self.broker.reply(
            True, "other_user_active", fields={3: "ready", 4: "none"})
        status, result = self.state.ensure_session(
            self.client, "living-room", "request-one")
        self.assertEqual(409, status)
        self.assertEqual("other_user_active", result["reason"])

        self.broker.profile = self.broker.reply(
            True, "signed_out", fields={3: "action_required", 4: "credential_missing"})
        self.broker.begin_result = self.broker.reply(
            False, "action_required", "credential_missing")
        status, result = self.state.ensure_session(
            self.client, "living-room", "request-two")
        self.assertEqual(409, status)
        self.assertEqual("credential_missing", result["reason"])
        self.assertEqual(
            [("android-tv", "living-room", "request-two")], self.broker.begin_calls)

    def test_failed_request_replay_returns_same_terminal_broker_result(self):
        attempt_id = "0123456789abcdef0123456789abcdef"
        self.broker.profile = self.broker.reply(
            True, "signed_out", fields={3: "action_required", 4: "credential_missing"})
        self.broker.begin_result = self.broker.reply(
            False, "action_required", "logon_failed", {3: attempt_id})
        status, result = self.state.ensure_session(
            self.client, "living-room", "same-failed-request")
        self.assertEqual(409, status)
        self.assertEqual("logon_failed", result["reason"])
        self.assertEqual(attempt_id, result["attempt_id"])
        self.assertEqual([("android-tv", "living-room", "same-failed-request")],
                         self.broker.begin_calls)

    def test_active_use_only_profile_succeeds_but_signed_out_requires_remote_grant(self):
        self.client["profile_grants"]["living-room"] = ["use_profile"]
        self.broker.profile = self.broker.reply(
            True, "active", fields={3: "ready", 4: "none"})
        status, result = self.state.ensure_session(
            self.client, "living-room", "request-active")
        self.assertEqual(200, status)
        self.assertEqual("ready", result["state"])

        self.broker.profile = self.broker.reply(
            True, "signed_out", fields={3: "ready", 4: "none"})
        with self.assertRaises(PermissionError):
            self.state.ensure_session(
                self.client, "living-room", "request-signed-out")

    def test_other_active_user_is_reported_before_remote_grant_check(self):
        self.client["profile_grants"]["living-room"] = ["use_profile"]
        self.broker.profile = self.broker.reply(
            True, "other_user_active",
            fields={3: "action_required", 4: "credential_missing"})
        status, result = self.state.ensure_session(
            self.client, "living-room", "request-other-user")
        self.assertEqual(409, status)
        self.assertEqual("other_user_active", result["session_state"])
        self.assertEqual([], self.broker.begin_calls)

    def test_session_ensure_route_checks_remote_grant_only_when_login_is_needed(self):
        self.client["profile_grants"]["living-room"] = ["use_profile"]
        self.broker.profile = self.broker.reply(
            True, "active", fields={3: "ready", 4: "none"})
        handler, responses = GatewayStateTest.request_handler(
            self.state, "/api/v1/system/session/ensure", "token", "living-room")
        handler.headers["X-Request-Id"] = "request-active-route"
        handler.do_POST()
        self.assertEqual(200, responses[0][0])
        self.assertEqual("ready", responses[0][1]["state"])

        self.broker.profile = self.broker.reply(
            True, "signed_out", fields={3: "ready", 4: "none"})
        handler, responses = GatewayStateTest.request_handler(
            self.state, "/api/v1/system/session/ensure", "token", "living-room")
        handler.headers["X-Request-Id"] = "request-signed-out-route"
        handler.do_POST()
        self.assertEqual(403, responses[0][0])

    def test_non_success_session_route_keeps_coarse_reason_in_error(self):
        self.broker.profile = self.broker.reply(
            True, "signed_out", fields={3: "action_required", 4: "credential_missing"})
        self.broker.begin_result = self.broker.reply(
            False, "action_required", "credential_missing")
        handler, responses = GatewayStateTest.request_handler(
            self.state, "/api/v1/system/session/ensure", "token", "living-room")
        handler.headers["X-Request-Id"] = "request-missing-credential"
        handler.do_POST()
        self.assertEqual(409, responses[0][0])
        self.assertEqual("credential_missing", responses[0][1]["error"])
        self.assertEqual("credential_missing", responses[0][1]["reason"])

    def test_session_ensure_route_rejects_all_body_fields(self):
        handler, responses = GatewayStateTest.request_handler(
            self.state, "/api/v1/system/session/ensure", "token", "living-room")
        handler.headers["X-Request-Id"] = "request-with-password"
        handler.read_json = lambda: {"password": "must-not-be-accepted"}
        handler.do_POST()
        self.assertEqual(400, responses[0][0])
        self.assertEqual("invalid_request_body", responses[0][1]["error"])
        self.assertNotIn("must-not-be-accepted", json.dumps(responses[0][1]))
        self.assertEqual([], self.broker.begin_calls)

    def test_cancel_route_rejects_header_body_request_mismatch(self):
        attempt_id = "0123456789abcdef0123456789abcdef"
        handler, responses = GatewayStateTest.request_handler(
            self.state, "/api/v1/system/session/cancel", "token", "living-room")
        handler.headers["X-Request-Id"] = "original-request"
        handler.read_json = lambda: {
            "attempt_id": attempt_id, "request_id": "different-request"}
        handler.do_POST()
        self.assertEqual(400, responses[0][0])
        self.assertEqual("request_id_mismatch", responses[0][1]["error"])
        self.assertEqual([], self.broker.cancel_calls)

    def test_completed_attempt_waits_for_interactive_session_ready(self):
        attempt_id = "0123456789abcdef0123456789abcdef"
        status, result = self.state.session_attempt_status(
            self.client, "living-room", "request-one", attempt_id)
        self.assertEqual(202, status)
        self.assertEqual("session_starting", result["state"])

        self.broker.profile = self.broker.reply(
            True, "active", fields={3: "ready", 4: "none"})
        status, result = self.state.session_attempt_status(
            self.client, "living-room", "request-one", attempt_id)
        self.assertEqual(200, status)
        self.assertEqual("ready", result["state"])

    def test_cancel_and_profile_summary_expose_coarse_states(self):
        attempt_id = "0123456789abcdef0123456789abcdef"
        status, result = self.state.cancel_session_attempt(
            self.client, "living-room", "request-one", attempt_id)
        self.assertEqual(200, status)
        self.assertEqual("cancelled", result["state"])

        self.broker.profile = self.broker.reply(
            True, "locked", fields={3: "ready", 4: "none"})
        self.state.discord_status = mock.Mock(return_value={
            "bridge_online": False, "rpc_connected": False, "authenticated": False})
        self.state.proxy = mock.Mock(return_value=(False, {}))
        summary = self.state.profiles_summary(self.client)["profiles"][0]
        self.assertEqual("locked", summary["session_state"])
        self.assertEqual("ready", summary["remote_sign_in_state"])
        self.assertTrue(summary["permissions"]["remote_sign_in"])
        capability = self.state.remote_windows_sign_in_capability()
        self.assertTrue(capability["available"])
        self.assertEqual(1, capability["protocol_version"])


if __name__ == "__main__":
    unittest.main()
