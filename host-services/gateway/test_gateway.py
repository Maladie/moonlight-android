import json
import os
import unittest
import urllib.parse
from pathlib import Path

from wakeplay_gateway import GatewayState, sha256_text


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

    def test_pair_stores_only_token_hash(self):
        state = GatewayState(self.config_path, "123456")
        result = state.pair("192.0.2.1", "123456", "Living room TV")

        stored = json.loads(self.config_path.read_text(encoding="utf-8"))["clients"][0]
        self.assertNotIn(result["token"], self.config_path.read_text(encoding="utf-8"))
        self.assertEqual(sha256_text(result["token"]), stored["token_sha256"])
        self.assertIsNotNone(state.client_for_token(result["token"]))

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

    def test_profile_selects_its_own_loopback_bridges(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
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

    def test_profile_summary_reports_health_without_bridge_addresses(self):
        state = GatewayState(self.config_path, None)
        state.config["profiles"]["basia"] = {
            "name": "Basia",
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

        summary = state.profiles_summary()

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

        self.assertEqual(201, status)
        self.assertTrue(result["ok"])
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
        self.assertEqual("playnite", requests[0][0])
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
        with self.assertRaises(ValueError):
            state.playnite_artwork("../../secret", "cover")
        with self.assertRaises(ValueError):
            state.playnite_artwork("840317c9-b9a4-4f72-be8e-807414e36a9b", "file")

    def test_playnite_start_accepts_only_a_guid_and_uses_profile_bridge(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body)) is None, {"accepted": True})

        status, result = state.playnite_action("game/start", {
            "game_id": "840317C9-B9A4-4F72-BE8E-807414E36A9B",
        })

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("playnite", "/game/start", {
            "game_id": "840317c9-b9a4-4f72-be8e-807414e36a9b",
        }), requests[0])
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
        self.assertEqual(("playnite", "/game/install", {
            "game_id": "840317c9-b9a4-4f72-be8e-807414e36a9b",
        }, 15.0), requests[0])

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
        self.assertEqual(("playnite", "/installation/focus", {
            "game_id": "840317c9-b9a4-4f72-be8e-807414e36a9b",
        }, 6.0), requests[0])

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
            ("playnite", "/library/refresh", {}, 5.0), requests[0])

    def test_playnite_stop_is_graceful_by_contract(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((path, body)) is None, {"accepted": True})

        status, result = state.playnite_action("game/stop", {})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("/game/stop", {"force": False}), requests[0])

    def test_playnite_focus_is_narrow_and_bodyless(self):
        state = GatewayState(self.config_path, None)
        requests = []
        state.proxy_json = lambda name, path, body, timeout=8.0: (
            requests.append((name, path, body, timeout)) is None, {"focused": True})

        status, result = state.playnite_action("game/focus", {})

        self.assertEqual(200, status)
        self.assertTrue(result["ok"])
        self.assertEqual(("playnite", "/game/focus", {}, 6.0), requests[0])

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


if __name__ == "__main__":
    unittest.main()
