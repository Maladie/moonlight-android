import io
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


if __name__ == "__main__":
    unittest.main()
