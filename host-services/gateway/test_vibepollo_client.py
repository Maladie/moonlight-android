import base64
import hashlib
import json
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import vibepollo_client
from vibepollo_client import (
    CLIENT_PERMISSIONS, IDENTITY_PREFIX, STREAM_APP_NAME, STREAM_APP_UUID, IdentityError,
    StreamConflict, VibepolloClient, VibepolloError, build_control_snapshot, local_base_url,
    read_settings,
)

OPENSSL = shutil.which("openssl")


def openssl(*args, cwd):
    # A missing openssl fails these tests; it never skips them.
    if OPENSSL is None:
        raise AssertionError("openssl must be on PATH to run the Vibepollo client tests")
    subprocess.run([OPENSSL, *args], cwd=cwd, check=True, capture_output=True, timeout=60)


def new_certificate(directory: Path, name: str, bits: int = 2048) -> tuple[Path, Path]:
    key, cert = directory / f"{name}.key", directory / f"{name}.pem"
    openssl("req", "-x509", "-newkey", f"rsa:{bits}", "-sha256", "-nodes", "-days", "2",
            "-subj", "/CN=Sunshine Gamestream Host", "-keyout", str(key), "-out", str(cert),
            cwd=directory)
    return key, cert


def probe(outcome="ok"):
    return {name: {"outcome": outcome, "elapsed_ms": 1} for name in vibepollo_client.CONTROL_PATHS}


def fresh_sources(**changes):
    sources = {
        "session": {"appRunning": False, "activeSessions": 0},
        "apps": {"current_app": "", "apps": [{"uuid": "a", "id": "7", "name": "Stream"}]},
        "clients": {"named_certs": [{"uuid": "c1", "name": "TV", "connected": False}]},
    }
    sources.update(changes)
    return sources


class ControlSnapshotTest(unittest.TestCase):
    def test_fresh_sources_are_authoritative_and_keep_collections(self):
        snapshot = build_control_snapshot(fresh_sources(
            apps={"current_app": "a", "apps": [{"uuid": "a", "id": "7", "name": "Stream"}]},
            session={"appRunning": True, "activeSessions": 1},
            clients={"named_certs": [{"uuid": "c1", "name": "TV", "connected": True}]}),
            probe())
        self.assertTrue(snapshot["control_fresh"])
        self.assertEqual([], snapshot["control_unavailable_sources"])
        self.assertEqual([{"uuid": "a", "app_id": 7, "name": "Stream", "running": True,
                           "playnite": False}], snapshot["apps"])
        self.assertEqual([{"uuid": "c1", "name": "TV", "connected": True}],
                         snapshot["clients"])
        self.assertEqual({"active": True, "count": 1, "app_running": True},
                         {key: snapshot["session"][key]
                          for key in ("active", "count", "app_running")})

    def test_missing_source_is_named_never_an_empty_collection(self):
        for source in ("session", "apps", "clients"):
            with self.subTest(source=source):
                snapshot = build_control_snapshot(fresh_sources(**{source: None}), probe())
                self.assertFalse(snapshot["control_fresh"])
                self.assertEqual([source], snapshot["control_unavailable_sources"])

    def test_non_list_collections_and_unknown_current_app_are_not_evidence(self):
        for source, value in (("session", {"appRunning": True, "activeSessions": None}),
                              ("clients", {"named_certs": {"uuid": "c1"}}),
                              ("clients", {"named_certs": [{"uuid": "c1", "name": "TV"}]}),
                              ("apps", {"current_app": "", "apps": {"uuid": "a"}})):
            with self.subTest(source=source, value=value):
                snapshot = build_control_snapshot(fresh_sources(**{source: value}), probe())
                self.assertEqual([source], snapshot["control_unavailable_sources"])
                self.assertEqual("schema_invalid",
                                 snapshot["control_source_probe"][source]["outcome"])
        snapshot = build_control_snapshot(fresh_sources(
            apps={"current_app": "missing", "apps": [{"uuid": "a", "name": "Stream"}]}), probe())
        self.assertEqual(["apps"], snapshot["control_unavailable_sources"])


    def test_running_app_id_tells_unknown_from_no_app(self):
        client = VibepolloClient(Path("unused-settings.json"), Path("unused-export"))
        apps = [{"uuid": "a", "id": "7", "name": "Stream"}, {"uuid": "b", "id": "x"}]
        for response, expected in (
                ({"current_app": "a", "apps": apps}, 7),
                ({"current_app": "", "apps": apps}, 0),
                ({"current_app": "b", "apps": apps}, None),          # no numeric id
                ({"current_app": "missing", "apps": apps}, None),    # not listed
                ({"current_app": None, "apps": apps}, None),
                ({"current_app": "", "apps": {"uuid": "a"}}, None)):
            with self.subTest(response=response),                     mock.patch.object(client, "call", return_value=response):
                self.assertEqual(expected, client.running_app_id())
        with mock.patch.object(client, "call", side_effect=VibepolloError("down")):
            self.assertIsNone(client.running_app_id())


class SettingsTest(unittest.TestCase):
    def test_api_must_stay_on_local_https(self):
        self.assertEqual("https://127.0.0.1:47990", local_base_url("https://127.0.0.1:47990/"))
        self.assertEqual("https://localhost:443", local_base_url("https://localhost"))
        for value in ("http://127.0.0.1:47990", "https://192.168.1.2:47990",
                      "https://127.0.0.1:47990/api", "https://user@127.0.0.1:1",
                      "https://127.0.0.1:0", "https://127.0.0.1:99999"):
            with self.subTest(value=value), self.assertRaises(VibepolloError):
                local_base_url(value)

    def test_missing_or_empty_token_is_not_configured(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vibepollo-api.json"
            for content in (None, "{}", '{"token": ""}', '{"token": "a b"}', "not json"):
                if content is not None:
                    path.write_text(content, encoding="utf-8")
                with self.subTest(content=content), self.assertRaises(VibepolloError):
                    read_settings(path)
            path.write_text('{"token": "secret"}', encoding="utf-8")
            self.assertEqual(("https://127.0.0.1:47990", "secret"), read_settings(path))


class StubbedApiTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.client = VibepolloClient(self.root / "vibepollo-api.json", self.root / "exports",
                                      lambda: self.root / "config")
        self.calls = []
        self.client.call = self.call
        sleep = mock.patch("vibepollo_client.time.sleep")
        sleep.start()
        self.addCleanup(sleep.stop)

    def tearDown(self):
        self.temp.cleanup()

    def call(self, method, path, body=None, timeout=3.0, output=None):
        self.calls.append((method, path, json.loads(json.dumps(body))))
        return self.api(method, path, body)


class EnsureStreamTest(StubbedApiTest):
    def setUp(self):
        super().setUp()
        self.apps = []

    def api(self, method, path, body):
        self.assertEqual("/api/apps", path)
        if method == "POST":
            app = {key: value for key, value in body.items() if key != "index"}
            app.setdefault("id", "555")
            if body["index"] < 0:
                self.apps.append(app)
            else:
                self.apps[body["index"]] = app
            return {"status": True}
        return {"apps": [dict(app) for app in self.apps]}

    def test_absent_stream_is_created_command_free(self):
        self.apps = [{"name": "Desktop", "uuid": "d", "cmd": "explorer"}]
        result = self.client.ensure_stream()
        self.assertTrue(result["created"])
        self.assertEqual({"index": -1, "name": STREAM_APP_NAME, "uuid": STREAM_APP_UUID,
                          "moonwaker-managed": "stream", "auto-detach": True},
                         self.calls[1][2])
        self.assertEqual("explorer", self.apps[0]["cmd"])

    def test_ready_stream_is_left_alone_and_a_renamed_one_is_restored_in_place(self):
        ready = {"name": "MoonWaker  stream", "uuid": STREAM_APP_UUID, "id": "9",
                 "moonwaker-managed": "stream", "auto-detach": True}
        self.apps = [{"name": "Desktop", "uuid": "d"}, dict(ready)]
        self.assertEqual({"created": False, "updated": False, "app_id": 9, "index": 1},
                         {key: value for key, value in self.client.ensure_stream().items()
                          if key in {"created", "updated", "app_id", "index"}})
        self.assertEqual(1, len(self.calls))
        self.apps[1]["name"] = "Old"
        result = self.client.ensure_stream()
        self.assertEqual((False, True), (result["created"], result["updated"]))
        self.assertEqual(1, self.calls[2][2]["index"])
        self.assertEqual(STREAM_APP_NAME, self.apps[1]["name"])

    def test_foreign_holders_are_never_overwritten(self):
        cases = [
            [{"name": "MoonWaker Stream", "uuid": "x"}],
            [{"name": "Game", "uuid": STREAM_APP_UUID}],
            [{"name": STREAM_APP_NAME, "uuid": STREAM_APP_UUID, "moonwaker-managed": "stream",
              "prep-cmd": [{"do": "x"}]}],
            [{"name": "A", "moonwaker-managed": "stream"},
             {"name": "B", "moonwaker-managed": "stream"}],
        ]
        for apps in cases:
            with self.subTest(apps=apps):
                self.apps, self.calls = apps, []
                with self.assertRaises(StreamConflict):
                    self.client.ensure_stream()
                self.assertEqual(["GET"], [call[0] for call in self.calls])

    def test_keeper_records_each_outcome_once_and_retries(self):
        outcomes = iter([VibepolloError("down"), VibepolloError("down"),
                         {"ok": True}, StreamConflict("name_held_unmarked", "held"),
                         ValueError("bad index"), ValueError("bad index"),
                         StreamConflict("uuid_held_unmanaged", "held")])

        def ensure():
            value = next(outcomes)
            if isinstance(value, Exception):
                raise value
            return value

        self.client.ensure_stream = ensure
        records, waits = [], []

        class Stop:
            def wait(self, seconds):
                waits.append(seconds)
                return len(waits) == 7

        self.client.keep_stream_target(Stop(), lambda event, level, **fields: records.append(
            (level, fields["state"], fields["reason"],
             type(fields["error"]).__name__ if "error" in fields else "")))
        self.assertEqual([("WARN", "unavailable", "transport", ""), ("INFO", "ready", "", ""),
                          ("ERROR", "conflict", "name_held_unmarked", ""),
                          ("ERROR", "error", "ValueError", "ValueError"),
                          ("ERROR", "conflict", "uuid_held_unmanaged", "")], records)
        self.assertEqual([30.0, 30.0, 300.0, 300.0, 30.0, 30.0, 300.0], waits)


class PairTest(StubbedApiTest):
    def setUp(self):
        super().setUp()
        self.clients = [{"uuid": "old", "name": "TV"}]
        self.pin_attempts = 0
        self.persist = True

    def api(self, method, path, body):
        if path == "/api/clients/list":
            return {"named_certs": [dict(client) for client in self.clients]}
        if path == "/api/pin":
            self.pin_attempts += 1
            if self.pin_attempts < 3:
                return {"status": False}
            self.clients.append({"uuid": "new", "name": "tv", "perm": 0,
                                 "allow_client_commands": False, "do": [{"cmd": "x"}]})
            return {"status": True}
        if path == "/api/clients/update":
            if self.persist:
                self.clients[-1]["perm"] = body["perm"]
            return {"status": True}
        self.fail(path)

    def test_pairing_waits_for_pin_and_grants_exactly_moonwaker_permissions(self):
        self.assertEqual({"ok": True, "client_uuid": "new", "permissions": CLIENT_PERMISSIONS},
                         self.client.pair("1234", "TV"))
        update = next(call[2] for call in self.calls if call[1] == "/api/clients/update")
        self.assertEqual((CLIENT_PERMISSIONS, False, [{"cmd": "x"}], "TV"),
                         (update["perm"], update["allow_client_commands"], update["do"],
                          update["name"]))

    def test_unpersisted_permissions_fail_pairing(self):
        self.persist = False
        with self.assertRaisesRegex(VibepolloError, "did not persist"):
            self.client.pair("1234", "TV")


class IdentityTest(StubbedApiTest):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.key, cls.cert = new_certificate(Path(cls.keys.name), "client")
        cls.pem = cls.cert.read_text(encoding="ascii")
        cls.fingerprint = hashlib.sha256(vibepollo_client.pem_certificate(cls.pem)).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def setUp(self):
        super().setUp()
        (self.root / "config").mkdir()
        self.host_id = "HOST-1"
        self.live_clients = [{"uuid": "client-1"}]
        self.write_state("sunshine_state.json")

    def write_state(self, name, host_id="HOST-1", records=None):
        path = self.root / "config" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"root": {"uniqueid": host_id, "named_devices": records or [
            {"uuid": "client-1", "cert": self.pem}, {"uuid": "broken", "cert": "x"}]}}),
            encoding="utf-8")

    def api(self, method, path, body):
        if path == "/api/apps":
            return {"host_uuid": self.host_id, "apps": []}
        if path == "/api/clients/list":
            return {"named_certs": self.live_clients}
        self.fail(path)

    def sign(self, challenge: str) -> bytes:
        message = self.root / "challenge.txt"
        message.write_bytes(challenge.encode("utf-8"))
        signature = self.root / "challenge.sig"
        openssl("dgst", "-sha256", "-sign", str(self.key), "-out", str(signature),
                str(message), cwd=self.root)
        return signature.read_bytes()

    def reason(self, challenge, fingerprint, signature):
        with self.assertRaises(IdentityError) as raised:
            self.client.verify_identity(challenge, fingerprint, signature)
        return raised.exception.reason

    def test_signature_by_paired_certificate_names_its_client(self):
        challenge = IDENTITY_PREFIX + "nonce\ntv"
        signature = self.sign(challenge)
        self.assertEqual("client-1", self.client.verify_identity(
            challenge, self.fingerprint, signature))
        self.assertEqual("identity_proof_invalid",
                         self.reason(challenge + "x", self.fingerprint, signature))
        self.assertEqual("identity_proof_invalid", self.reason(
            "other\n" + challenge, self.fingerprint, signature))
        self.assertEqual("pairing_required", self.reason(challenge, "0" * 64, signature))
        self.live_clients = []
        self.assertEqual("pairing_required",
                         self.reason(challenge, self.fingerprint, signature))
        self.live_clients = [{"uuid": "client-1"}, {"uuid": "client-1"}]
        self.assertEqual("identity_ambiguous",
                         self.reason(challenge, self.fingerprint, signature))

    def test_state_must_belong_to_the_live_host_and_stay_trusted(self):
        challenge = IDENTITY_PREFIX + "nonce"
        signature = self.sign(challenge)
        self.host_id = "OTHER"
        self.assertEqual("identity_binding_unsupported",
                         self.reason(challenge, self.fingerprint, signature))
        self.host_id = "HOST-1"
        state = self.root / "config" / "sunshine_state.json"
        state.write_bytes(b" " * (vibepollo_client.MAX_LOCAL_FILE_BYTES + 1))
        self.assertEqual("identity_binding_unsupported",
                         self.reason(challenge, self.fingerprint, signature))
        # An explicit file_state is honored, but never outside the configuration directory.
        self.write_state("state/explicit.json")
        conf = self.root / "config" / "sunshine.conf"
        conf.write_text('# comment\nfile_state = "state/explicit.json" # trailing\n',
                        encoding="utf-8")
        self.assertEqual("client-1", self.client.verify_identity(
            challenge, self.fingerprint, signature))
        conf.write_text("file_state = ../outside.json\n", encoding="utf-8")
        self.assertEqual("identity_binding_unsupported",
                         self.reason(challenge, self.fingerprint, signature))

    def test_api_failure_is_unavailable_not_unsupported(self):
        challenge = IDENTITY_PREFIX + "nonce"
        signature = self.sign(challenge)

        def offline(*_args, **_kwargs):
            raise VibepolloError("timed out", kind="timeout")

        self.client.call = offline
        self.assertEqual("identity_binding_unavailable",
                         self.reason(challenge, self.fingerprint, signature))


class FakeVibepollo(BaseHTTPRequestHandler):
    delay = 0.0
    failing = set()
    seen = []

    def log_message(self, *_args):
        pass

    export = "zip"

    def do_GET(self):
        FakeVibepollo.seen.append((self.path, self.headers.get("Authorization")))
        if self.path == "/api/logs/export" and FakeVibepollo.export != "zip":
            # "truncated" promises more than it sends; "trickle" never finishes, but each
            # byte arrives well within any single socket timeout.
            self.send_response(200)
            self.send_header("Content-Length", "1000")
            self.end_headers()
            for _ in range(10 if FakeVibepollo.export == "truncated" else 40):
                self.wfile.write(b"x")
                self.wfile.flush()
                if FakeVibepollo.export == "trickle":
                    time.sleep(0.1)
            return
        time.sleep(FakeVibepollo.delay)
        status = 503 if self.path in FakeVibepollo.failing else 200
        body = json.dumps({"path": self.path}).encode() if status == 200 else b"private error"
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass  # a client that rejects the certificate aborts the handshake


class PinnedTlsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.server_key, cls.server_cert = new_certificate(cls.root, "server")
        _key, cls.other_cert = new_certificate(cls.root, "other")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cls.server_cert, cls.server_key)
        cls.server = QuietServer(("127.0.0.1", 0), FakeVibepollo)
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.temp.cleanup()

    def setUp(self):
        FakeVibepollo.delay, FakeVibepollo.failing, FakeVibepollo.seen = 0.0, set(), []
        FakeVibepollo.export = "zip"
        self.config = self.root / "config"
        (self.config / "credentials").mkdir(parents=True, exist_ok=True)
        self.pin(self.server_cert)
        settings = self.root / "vibepollo-api.json"
        settings.write_text(json.dumps({
            "base_url": f"https://127.0.0.1:{self.server.server_port}",
            "token": "private-test-token"}), encoding="utf-8")
        self.client = VibepolloClient(settings, self.root / "exports", lambda: self.config)

    def pin(self, certificate):
        shutil.copyfile(certificate, self.config / "credentials" / "cacert.pem")

    def test_request_is_sent_only_to_the_pinned_certificate(self):
        self.assertEqual({"path": "/api/metadata"}, self.client.call("GET", "/api/metadata"))
        self.assertEqual([("/api/metadata", "Bearer private-test-token")], FakeVibepollo.seen)
        self.assertTrue(self.client.api_online())

        self.pin(self.other_cert)
        fresh = VibepolloClient(self.client.settings_path, self.root / "exports",
                                lambda: self.config)
        with self.assertRaisesRegex(VibepolloError, "certificate_mismatch"):
            fresh.call("GET", "/api/metadata")
        self.assertEqual(1, len(FakeVibepollo.seen))
        (self.config / "credentials" / "cacert.pem").unlink()
        with self.assertRaisesRegex(VibepolloError, "certificate_unavailable"):
            fresh.call("GET", "/api/metadata")
        self.assertEqual(1, len(FakeVibepollo.seen))

    def test_control_sources_are_parallel_bounded_and_independent(self):
        FakeVibepollo.delay = 0.4
        FakeVibepollo.failing = {"/api/apps"}
        started = time.monotonic()
        ok, snapshot = self.client.control_snapshot(timeout=8.0)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertTrue(ok)
        self.assertFalse(snapshot["control_fresh"])
        self.assertIn("apps", snapshot["control_unavailable_sources"])
        self.assertEqual("http_503", snapshot["control_source_probe"]["apps"]["outcome"])
        self.assertNotIn("private", json.dumps(snapshot))
        self.assertEqual(set(vibepollo_client.CONTROL_PATHS.values()),
                         {path for path, _auth in FakeVibepollo.seen})

    def test_slow_sources_time_out_within_one_source_budget(self):
        FakeVibepollo.delay = 1.0
        started = time.monotonic()
        ok, snapshot = self.client.control_snapshot(timeout=8.0)
        self.assertLess(time.monotonic() - started,
                        vibepollo_client.CONTROL_SOURCE_TIMEOUT_SECONDS + 0.3)
        self.assertFalse(ok)
        self.assertEqual({"timeout"}, {row["outcome"] for row in
                                       snapshot["control_source_probe"].values()})

    def exports(self):
        return sorted(path.name for path in (self.root / "exports").glob("*"))

    def test_export_keeps_only_the_latest_and_never_a_partial_file(self):
        names = iter(["Vibepollo_Logs_1.zip", "Vibepollo_Logs_2.zip", "Vibepollo_Logs_3.zip",
                      "Vibepollo_Logs_4.zip", "Vibepollo_Logs_5.zip"])
        with mock.patch("vibepollo_client.time.strftime", lambda _format: next(names)):
            self.client.action("export-logs")
            self.client.action("export-logs")
            self.assertEqual(["Vibepollo_Logs_2.zip"], self.exports())
            FakeVibepollo.export = "truncated"
            with self.assertRaises(VibepolloError) as raised:
                self.client.action("export-logs")
            self.assertEqual("transport", raised.exception.kind)
            self.assertEqual(["Vibepollo_Logs_2.zip"], self.exports())
            # The whole export shares one deadline, however steadily bytes arrive.
            FakeVibepollo.export = "trickle"
            started = time.monotonic()
            with mock.patch("vibepollo_client.EXPORT_TIMEOUT_SECONDS", 0.5), \
                    self.assertRaises(VibepolloError) as raised:
                self.client.action("export-logs")
            self.assertLess(time.monotonic() - started, 1.5)
            self.assertEqual("timeout", raised.exception.kind)
            self.assertEqual(["Vibepollo_Logs_2.zip"], self.exports())
            # A local write failure is not evidence that Vibepollo is unavailable.
            FakeVibepollo.export = "zip"
            self.client.unavailable_until = 0.0
            with mock.patch("vibepollo_client.open", side_effect=PermissionError("denied"),
                            create=True), self.assertRaises(VibepolloError) as raised:
                self.client.action("export-logs")
            self.assertEqual("local", raised.exception.kind)
            self.assertEqual(0.0, self.client.unavailable_until)
            self.assertEqual(["Vibepollo_Logs_2.zip"], self.exports())

    def test_unreachable_api_backs_off(self):
        self.client.settings_path.write_text(json.dumps({
            "base_url": "https://127.0.0.1:1", "token": "t"}), encoding="utf-8")
        self.assertFalse(self.client.api_online())
        self.client.call = lambda *_args, **_kwargs: self.fail("no call during backoff")
        self.assertFalse(self.client.api_online())


def _tlv(tag: int, content: bytes) -> bytes:
    size = len(content)
    if size < 128:
        return bytes([tag, size]) + content
    length = size.to_bytes((size.bit_length() + 7) // 8, "big")
    return bytes([tag, 0x80 | len(length)]) + length + content


def _der_integer(value: int) -> bytes:
    return _tlv(0x02, value.to_bytes(value.bit_length() // 8 + 1, "big"))


def with_public_key(certificate: bytes, modulus: int, exponent: int) -> bytes:
    """`certificate` with its subject public key replaced (its own signature is not checked)."""
    der, children = vibepollo_client._der, vibepollo_client._der_children
    start, end = der(certificate, 0, 0x30)
    tbs_start, tbs_end = der(certificate, start, 0x30)
    fields = children(certificate, tbs_start, tbs_end)
    _tag, spki_start, spki_end = fields[6]
    algorithm = children(certificate, *der(certificate, spki_start, 0x30))[0]
    spki = _tlv(0x30, certificate[algorithm[1]:algorithm[2]] + _tlv(0x03, b"\x00" + _tlv(
        0x30, _der_integer(modulus) + _der_integer(exponent))))
    tbs = certificate[tbs_start:spki_start] + spki + certificate[spki_end:tbs_end]
    return _tlv(0x30, _tlv(0x30, tbs) + certificate[tbs_end:end])


class RsaTest(unittest.TestCase):
    """Signatures over hand-made encoded messages (raw RSA, no padding) must verify only
    when the message is exactly EMSA-PKCS1-v1_5 of SHA-256 under an acceptable key."""

    message = IDENTITY_PREFIX.encode() + b"nonce"

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.key, cert = new_certificate(cls.root, "client")
        cls.small_key, small_cert = new_certificate(cls.root, "small", bits=1024)
        cls.cert = vibepollo_client.pem_certificate(cert.read_text(encoding="ascii"))
        cls.small_cert = vibepollo_client.pem_certificate(small_cert.read_text(encoding="ascii"))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def raw_sign(self, key: Path, encoded: bytes) -> bytes:
        """encoded^d mod n. OpenSSL 3 refuses an unpadded `-sign` of more than a digest, and
        an unpadded `-decrypt` is the same raw private-key operation."""
        (self.root / "em.bin").write_bytes(encoded)
        openssl("pkeyutl", "-decrypt", "-inkey", str(key), "-pkeyopt", "rsa_padding_mode:none",
                "-in", "em.bin", "-out", "sig.bin", cwd=self.root)
        return (self.root / "sig.bin").read_bytes()

    def valid(self, certificate: bytes, signature: bytes) -> bool:
        return vibepollo_client.rsa_sha256_signature_valid(certificate, self.message, signature)

    @staticmethod
    def encoded(size: int, digest_info: bytes, kind: bytes = b"\x01", fill: bytes = b"\xff",
                garbage: int = 0) -> bytes:
        padding = size - len(digest_info) - 3 - garbage
        return b"\x00" + kind + fill * padding + b"\x00" + digest_info + b"\x00" * garbage

    def test_only_exact_pkcs1_v1_5_encoding_verifies(self):
        modulus, _exponent = vibepollo_client.rsa_public_key(self.cert)
        size = (modulus.bit_length() + 7) // 8
        digest = hashlib.sha256(self.message).digest()
        digest_info = vibepollo_client.SHA256_DIGEST_INFO + digest
        no_null = bytes.fromhex("302f300b0609608648016503040201" "0420") + digest
        good = self.raw_sign(self.key, self.encoded(size, digest_info))
        self.assertTrue(self.valid(self.cert, good))
        forged = {
            "garbage after the hash": self.encoded(size, digest_info, garbage=8),
            "padding other than FF": self.encoded(size, digest_info, fill=b"\xfe"),
            "DigestInfo without NULL": self.encoded(size, no_null),
            "block type 02": self.encoded(size, digest_info, kind=b"\x02"),
        }
        for name, encoded in forged.items():
            with self.subTest(name):
                self.assertEqual(size, len(encoded))
                self.assertFalse(self.valid(self.cert, self.raw_sign(self.key, encoded)))
        value = int.from_bytes(good, "big")
        for name, signature in {
                "short": good[1:], "long": b"\x00" + good,
                "s + n": (value + modulus).to_bytes(size + 1, "big")}.items():
            with self.subTest(name):
                self.assertFalse(self.valid(self.cert, signature))

    def test_unacceptable_keys_are_rejected(self):
        modulus, _exponent = vibepollo_client.rsa_public_key(self.cert)
        size = (modulus.bit_length() + 7) // 8
        digest_info = vibepollo_client.SHA256_DIGEST_INFO + hashlib.sha256(self.message).digest()
        encoded = self.encoded(size, digest_info)
        # With e = 1 the encoded message is its own signature.
        for exponent in (1, 2, 65536, (1 << 32) + 1):
            with self.subTest(exponent=exponent):
                self.assertFalse(self.valid(with_public_key(self.cert, modulus, exponent),
                                            encoded))
        # A correctly encoded signature by a 1024-bit key.
        signature = self.raw_sign(self.small_key, self.encoded(128, digest_info))
        self.assertFalse(self.valid(self.small_cert, signature))
        with self.assertRaises(ValueError):
            vibepollo_client.rsa_public_key(self.small_cert)

    def test_malformed_certificates_and_signatures_fail_closed(self):
        self.assertFalse(vibepollo_client.rsa_sha256_signature_valid(b"\x30\x00", b"m", b"s"))
        self.assertFalse(vibepollo_client.rsa_sha256_signature_valid(
            base64.b64decode("MAMCAQE="), b"m", b"s"))


if __name__ == "__main__":
    unittest.main()
