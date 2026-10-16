"""The Gateway's in-process client of this computer's Vibepollo (Sunshine) HTTPS API.

Vibepollo is one per machine, so is its token: `vibepollo-api.json` beside gateway.json,
written only by the elevated Host Configurator and readable only by Administrators, SYSTEM
and the Gateway service. Every connection pins the certificate Vibepollo keeps in its own
configuration directory: the handshake accepts the self-signed certificate (it names no host
and chains to no CA), and nothing, not even the token, is sent before the presented
certificate's SHA-256 equals that file's.
"""

from __future__ import annotations

import base64
import contextlib
import hashlib
import hmac
import http.client
import itertools
import json
import os
import re
import ssl
import stat
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable

DEFAULT_BASE_URL = "https://127.0.0.1:47990"
STREAM_APP_NAME = "MoonWaker Stream"
STREAM_APP_UUID = "6d6f6f6e-7761-4b65-9273-747265616d00"
# List, view, launch and every input device; never clipboard, file transfer or server commands.
CLIENT_PERMISSIONS = 0x07001F00
# The authoritative sources of a control decision, read in parallel.
CONTROL_PATHS = {
    "session": "/api/session/status",
    "rtsp": "/api/rtsp/sessions",
    "webrtc": "/api/webrtc/sessions",
    "apps": "/api/apps",
    "clients": "/api/clients/list",
}
# Total budget of one source read (connection, handshake, request and body). The sources
# are read in parallel, so a snapshot takes about as long as its slowest source.
CONTROL_SOURCE_TIMEOUT_SECONDS = 0.6
REQUEST_TIMEOUT_SECONDS = 3.0
EXPORT_TIMEOUT_SECONDS = 20.0
# A failed connection makes `api_online` answer False without a new attempt for this long.
UNAVAILABLE_BACKOFF_SECONDS = 5.0
# A successful call this recent answers `api_online` without a new request.
ONLINE_EVIDENCE_SECONDS = 30.0
PIN_RELOAD_SECONDS = 60.0
STREAM_RETRY_SECONDS = 30.0
STREAM_RECHECK_SECONDS = 300.0
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_EXPORT_BYTES = 256 * 1024 * 1024
MAX_LOCAL_FILE_BYTES = 1024 * 1024
TOKEN_PATTERN = re.compile(r"^[\x21-\x7e]{1,4096}$")
FINGERPRINT_PATTERN = re.compile(r"^[0-9a-f]{64}$")
IDENTITY_PREFIX = "moonwaker-vibepollo-identity-v1\n"
PEM_CERTIFICATE = re.compile(
    r"-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----")
RSA_ENCRYPTION_OID = bytes.fromhex("2a864886f70d010101")
SHA256_DIGEST_INFO = bytes.fromhex("3031300d060960864801650304020105000420")
STREAM_COMMAND_FIELDS = ("cmd", "prep-cmd", "state-cmd", "detached", "playnite-id",
                         "playnite_id", "playnite-managed", "playnite-source")


class VibepolloError(Exception):
    """A failed Vibepollo call. `kind`: transport, timeout, http, schema, config or local
    (a file on this computer, never evidence about the API's availability)."""

    def __init__(self, message: str, kind: str = "transport", status: int = 0) -> None:
        super().__init__(message)
        self.kind = kind
        self.status = status


class StreamConflict(VibepolloError):
    """A foreign or hand-edited application holds MoonWaker Stream's name or UUID; `reason`
    is the diagnostic token naming which."""

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(message, kind="schema")
        self.reason = reason


class IdentityError(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _first(value: Any, *names: str, default: Any = None) -> Any:
    if isinstance(value, dict):
        for name in names:
            if value.get(name) is not None:
                return value[name]
    return default


def _items(value: Any) -> list[Any]:
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def local_base_url(value: Any) -> str:
    """The canonical URL of a Vibepollo API on this computer's loopback."""
    parts = urllib.parse.urlsplit(str(value or "").strip())
    try:
        port = parts.port
    except ValueError:
        port = 0
    if (parts.scheme != "https" or parts.hostname not in {"127.0.0.1", "localhost"} or
            parts.username or parts.password or parts.path not in {"", "/"} or
            parts.query or parts.fragment or port == 0):
        raise VibepolloError("The Vibepollo API must use HTTPS on 127.0.0.1 or localhost.",
                             kind="config")
    return f"https://{parts.hostname}:{port or 443}"


def read_settings(path: Path) -> tuple[str, str]:
    try:
        document = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        document = None
    token = document.get("token") if isinstance(document, dict) else None
    if not isinstance(token, str) or not TOKEN_PATTERN.fullmatch(token):
        raise VibepolloError("vibepollo_not_configured", kind="config")
    return local_base_url(document.get("base_url") or DEFAULT_BASE_URL), token


def _read_local_file(path: Path) -> bytes:
    """A small regular file that is not a reparse point."""
    info = os.lstat(path)
    if (not stat.S_ISREG(info.st_mode) or info.st_size > MAX_LOCAL_FILE_BYTES or
            getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT):
        raise OSError("not a small regular file")
    return path.read_bytes()


def _local(operation: Callable[..., Any], *args: Any) -> Any:
    """`operation` on this computer's file system; its failure is not an API failure."""
    try:
        return operation(*args)
    except OSError as error:
        raise VibepolloError(f"{type(error).__name__}: {error}", kind="local") from None


def _inside(directory: Path, path: Path) -> bool:
    prefix = os.path.normcase(str(directory)).rstrip("\\/") + os.sep
    return os.path.normcase(str(path)).startswith(prefix)


def vibepollo_config_directory() -> Path:
    """`<install>\\config` of the single Vibepollo the uninstall registry names."""
    import winreg
    roots: dict[str, Path] = {}
    for view in (winreg.KEY_WOW64_64KEY, winreg.KEY_WOW64_32KEY):
        try:
            uninstall = winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall", 0,
                winreg.KEY_READ | view)
        except OSError:
            continue
        with uninstall:
            for index in itertools.count():
                try:
                    name = winreg.EnumKey(uninstall, index)
                except OSError:
                    break
                try:
                    with winreg.OpenKey(uninstall, name) as entry:
                        display = str(winreg.QueryValueEx(entry, "DisplayName")[0])
                        location = str(winreg.QueryValueEx(entry, "InstallLocation")[0]).strip()
                except OSError:
                    continue
                if "vibepollo" not in display.casefold() or not location:
                    continue
                root = Path(os.path.abspath(location.rstrip("\\/")))
                try:
                    attributes = getattr(os.lstat(root), "st_file_attributes", 0)
                except OSError:
                    continue
                if not attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT:
                    roots[os.path.normcase(str(root))] = root
    if len(roots) != 1:
        raise VibepolloError("Vibepollo installation is missing or ambiguous.", kind="config")
    config = next(iter(roots.values())) / "config"
    try:
        info = os.lstat(config)
    except OSError:
        raise VibepolloError("Vibepollo configuration directory is missing.",
                             kind="config") from None
    if not stat.S_ISDIR(info.st_mode) or \
            getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
        raise VibepolloError("Vibepollo configuration directory is unsafe.", kind="config")
    return config


def configured_file(config_directory: Path, key: str, default: str) -> Path:
    """A file Vibepollo's `sunshine.conf` names by `key`, inside its configuration directory."""
    value = default
    conf = config_directory / "sunshine.conf"
    if os.path.lexists(conf):
        values: list[str] = []
        pattern = re.compile(r"^" + re.escape(key) + r"\s*=\s*(.*?)\s*$")
        for line in _read_local_file(conf).decode("utf-8-sig", "replace").splitlines():
            match = pattern.match(re.sub(r"#.*$", "", line).strip())
            if not match:
                continue
            item = match.group(1).strip()
            if len(item) >= 2 and item[0] == item[-1] and item[0] in "\"'":
                item = item[1:-1]
            if item and item not in values:
                values.append(item)
        if len(values) > 1:
            raise OSError(f"sunshine.conf names several {key} values")
        if values:
            value = values[0]
    path = Path(os.path.abspath(value if os.path.isabs(value) else config_directory / value))
    if not _inside(config_directory, path):
        raise OSError(f"{key} is outside Vibepollo's configuration directory")
    return path


def pem_certificate(text: str) -> bytes:
    match = PEM_CERTIFICATE.search(text)
    if not match:
        raise ValueError("no PEM certificate")
    der = base64.b64decode(re.sub(r"\s", "", match.group(1)), validate=True)
    if not 64 <= len(der) <= 64 * 1024:
        raise ValueError("invalid certificate size")
    return der


def certificate_fingerprint(config_directory: Path) -> str:
    """SHA-256 of the certificate Vibepollo serves its HTTPS API with."""
    try:
        path = configured_file(config_directory, "cert", "credentials/cacert.pem")
        return hashlib.sha256(pem_certificate(
            _read_local_file(path).decode("ascii", "replace"))).hexdigest()
    except (OSError, ValueError):
        raise VibepolloError("vibepollo_certificate_unavailable", kind="config") from None


def _der(data: bytes, offset: int, tag: int) -> tuple[int, int]:
    """(content start, content end) of the DER element at `offset`, which must be `tag`."""
    if offset + 2 > len(data) or data[offset] != tag:
        raise ValueError("unexpected DER element")
    length = data[offset + 1]
    offset += 2
    if length & 0x80:
        count = length & 0x7F
        if not 1 <= count <= 4 or offset + count > len(data):
            raise ValueError("invalid DER length")
        length = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    if offset + length > len(data):
        raise ValueError("truncated DER element")
    return offset, offset + length


def _der_children(data: bytes, start: int, end: int) -> list[tuple[int, int, int]]:
    """(tag, element start, element end) of each element between start and end."""
    children = []
    while start < end:
        _content, element_end = _der(data, start, data[start])
        children.append((data[start], start, element_end))
        start = element_end
    return children


def rsa_public_key(certificate: bytes) -> tuple[int, int]:
    """(modulus, exponent) of an X.509 certificate's RSA subject public key."""
    start, end = _der(certificate, 0, 0x30)
    start, end = _der(certificate, start, 0x30)  # tbsCertificate
    fields = _der_children(certificate, start, end)
    if fields and fields[0][0] == 0xA0:  # explicit version
        fields = fields[1:]
    _tag, spki_start, _spki_end = fields[5]  # serial, signature, issuer, validity, subject
    start, end = _der(certificate, spki_start, 0x30)
    algorithm, key = _der_children(certificate, start, end)[:2]
    start, _end = _der(certificate, algorithm[1], 0x30)
    oid_start, oid_end = _der(certificate, start, 0x06)
    if certificate[oid_start:oid_end] != RSA_ENCRYPTION_OID:
        raise ValueError("not an RSA key")
    start, end = _der(certificate, key[1], 0x03)
    if start >= end or certificate[start] != 0:
        raise ValueError("invalid key bit string")
    start, end = _der(certificate, start + 1, 0x30)
    modulus_start, modulus_end = _der(certificate, start, 0x02)
    exponent_start, exponent_end = _der(certificate, modulus_end, 0x02)
    modulus = int.from_bytes(certificate[modulus_start:modulus_end], "big")
    exponent = int.from_bytes(certificate[exponent_start:exponent_end], "big")
    if not 2048 <= modulus.bit_length() <= 8192 or not 3 <= exponent < 1 << 32 or \
            exponent % 2 == 0:
        raise ValueError("unacceptable RSA key parameters")
    return modulus, exponent


def rsa_sha256_signature_valid(certificate: bytes, message: bytes, signature: bytes) -> bool:
    """RSASSA-PKCS1-v1_5 with SHA-256 by the certificate's key."""
    try:
        modulus, exponent = rsa_public_key(certificate)
    except (ValueError, IndexError):
        return False
    size = (modulus.bit_length() + 7) // 8
    digest_info = SHA256_DIGEST_INFO + hashlib.sha256(message).digest()
    value = int.from_bytes(signature, "big")
    if len(signature) != size or size < len(digest_info) + 11 or value >= modulus:
        return False
    expected = b"\x00\x01" + b"\xff" * (size - len(digest_info) - 3) + b"\x00" + digest_info
    return hmac.compare_digest(pow(value, exponent, modulus).to_bytes(size, "big"), expected)


def read_identity_state(path: Path) -> tuple[str, list[tuple[str, str, bytes]]]:
    """(host unique ID, [(client UUID, certificate SHA-256, certificate)]) of Vibepollo's
    paired clients."""
    try:
        document = json.loads(_read_local_file(path).decode("utf-8-sig"))
    except (OSError, ValueError):
        raise IdentityError("identity_binding_unsupported") from None
    root = document.get("root") if isinstance(document, dict) else None
    host_id = str(_first(root, "uniqueid", default="")).strip()
    records = []
    for raw in _items(_first(root, "named_devices")):
        uuid = str(_first(raw, "uuid", default="")).strip()
        try:
            certificate = pem_certificate(str(_first(raw, "cert", default="")))
        except ValueError:
            continue
        if uuid:
            records.append((uuid, hashlib.sha256(certificate).hexdigest(), certificate))
    if not host_id or not records:
        raise IdentityError("identity_binding_unsupported")
    return host_id, records


def _normalized_name(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip()).casefold()


def stream_app_command_free(app: dict[str, Any]) -> bool:
    for field in STREAM_COMMAND_FIELDS:
        value = app.get(field)
        if value is None or (isinstance(value, str) and not value.strip()) or \
                (isinstance(value, (list, dict)) and not value):
            continue
        return False
    return True


def _stream_app_ready(app: dict[str, Any]) -> bool:
    return (_normalized_name(app.get("name")) == _normalized_name(STREAM_APP_NAME) and
            str(_first(app, "uuid", default="")) == STREAM_APP_UUID and
            bool(_first(app, "auto-detach", default=False)))


def _normalize_apps(response: Any) -> list[dict[str, Any]]:
    current = str(_first(response, "current_app", default=""))
    result = []
    for app in _items(_first(response, "apps")):
        uuid = str(_first(app, "uuid", "id", default=""))
        try:
            app_id = int(str(_first(app, "id", default="0")))
        except ValueError:
            app_id = 0
        result.append({
            "uuid": uuid, "app_id": app_id,
            "name": str(_first(app, "name", default="Unnamed application")),
            "running": uuid != "" and uuid == current,
            "playnite": bool(_first(app, "playnite-id", default=False)),
        })
    return sorted(result, key=lambda item: (not item["running"], item["name"].casefold()))


def _normalize_clients(response: Any, rtsp: Any) -> list[dict[str, Any]]:
    sessions = {}
    for session in _items(_first(rtsp, "sessions")):
        uuid = str(_first(session, "uuid", "client_uuid", default=""))
        if uuid:
            sessions[uuid] = session
    result = []
    for client in _items(_first(response, "named_certs", "clients")):
        uuid = str(_first(client, "uuid", "id", default=""))
        session = sessions.get(uuid)
        result.append({
            "uuid": uuid,
            "name": str(_first(client, "name", "device_name", default="Unnamed client")),
            "connected": session is not None,
            "width": _first(session, "width"), "height": _first(session, "height"),
            "fps": _first(session, "fps"), "codec": _first(session, "codec", default=""),
            "hdr": bool(_first(session, "hdr", default=False)),
        })
    return sorted(result, key=lambda item: (not item["connected"], item["name"].casefold()))


def build_control_snapshot(sources: dict[str, Any], probe: dict[str, dict[str, Any]]) \
        -> dict[str, Any]:
    """Control evidence from fresh source reads; an unavailable or malformed source is
    named in `control_unavailable_sources`, never normalized into an empty collection."""
    status, rtsp, webrtc = sources.get("session"), sources.get("rtsp"), sources.get("webrtc")
    apps_raw, clients_raw = sources.get("apps"), sources.get("clients")
    if isinstance(clients_raw, dict) and "named_certs" in clients_raw:
        client_items = clients_raw["named_certs"]
    else:
        client_items = clients_raw.get("clients") if isinstance(clients_raw, dict) else None
    rtsp_ok = isinstance(rtsp, dict) and rtsp.get("sessions") is not None
    webrtc_ok = isinstance(webrtc, dict) and webrtc.get("sessions") is not None
    rtsp_sessions = _items(_first(rtsp, "sessions"))
    web_sessions = _items(_first(webrtc, "sessions"))
    apps = _normalize_apps(apps_raw)
    clients = _normalize_clients(clients_raw, rtsp)
    count_value = _first(status, "activeSessions", "active_sessions", "sessionCount")
    status_valid = isinstance(status, dict) and isinstance(status.get("appRunning"), bool) \
        and type(count_value) is int and count_value >= 0
    current_app = str(_first(apps_raw, "current_app", default=""))
    running = sum(item["running"] for item in apps)
    running_consistent = running == (1 if current_app else 0)
    apps_ok = isinstance(apps_raw, dict) and apps_raw.get("apps") is not None and \
        apps_raw.get("current_app") is not None and running_consistent
    # Builds without /api/session/status: complete fresh transport, app and client
    # sources are equivalent evidence.
    derived_valid = rtsp_ok and webrtc_ok and apps_ok and client_items is not None
    unavailable = [name for name, ok in (
        ("rtsp", rtsp_ok), ("webrtc", webrtc_ok), ("apps", apps_ok),
        ("clients", client_items is not None),
        ("session", status_valid or derived_valid)) if not ok]
    probe = {name: dict(row) for name, row in probe.items()}
    for name in unavailable:
        if probe.get(name, {}).get("outcome") == "ok":
            probe[name]["outcome"] = "schema_invalid"
    active_count = count_value if status_valid else len(rtsp_sessions) + len(web_sessions)
    stream = (rtsp_sessions or web_sessions or [None])[0]
    if stream is None and active_count > 0:
        # Some builds report the connection through /api/session/status only.
        stream = {"uuid": "session-status-fallback", "device_name": "Moonlight client",
                  "state": "running", "telemetry_limited": True}
    return {
        "generated_at": int(time.time() * 1000),
        "control_fresh": not unavailable,
        "control_unavailable_sources": unavailable,
        "control_source_probe": probe,
        "session": {
            "active": stream is not None,
            "count": max(len(rtsp_sessions) + len(web_sessions), active_count),
            "app_running": status["appRunning"] if status_valid else current_app != "",
            "source": "session_status" if status_valid else "derived",
            "app_name": str(_first(status, "appName", default="")),
            "paused": bool(_first(status, "paused", default=False)),
            "stream": stream,
        },
        "apps": apps,
        "clients": clients,
    }


class _PinnedConnection(http.client.HTTPSConnection):
    def __init__(self, host: str, port: int, timeout: float,
                 pin: Callable[[bytes], None]) -> None:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        # Authentication is the certificate pin in connect(), checked before the request.
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        super().__init__(host, port, timeout=timeout, context=context)
        self._pin = pin

    def connect(self) -> None:
        super().connect()
        try:
            self._pin(self.sock.getpeercert(binary_form=True) or b"")
        except BaseException:
            self.close()
            raise


class VibepolloClient:
    def __init__(self, settings_path: Path, export_directory: Path,
                 config_directory: Callable[[], Path] = vibepollo_config_directory) -> None:
        self.settings_path = settings_path
        self.export_directory = export_directory
        self.config_directory = config_directory
        self.started = time.monotonic()
        self.last_error = ""
        self.last_success = 0.0
        self.unavailable_until = 0.0
        self._pin_lock = threading.Lock()
        self._pin: tuple[float, str] | None = None

    def configured(self) -> bool:
        try:
            read_settings(self.settings_path)
            return True
        except VibepolloError:
            return False

    def _check_pin(self, certificate: bytes) -> None:
        presented = hashlib.sha256(certificate).hexdigest()
        with self._pin_lock:
            cached = self._pin
        if cached is None or cached[1] != presented or \
                time.monotonic() - cached[0] > PIN_RELOAD_SECONDS:
            # A different certificate reloads the pin once: Vibepollo may have regenerated it.
            cached = (time.monotonic(), certificate_fingerprint(self.config_directory()))
            with self._pin_lock:
                self._pin = cached
        if not hmac.compare_digest(cached[1], presented):
            raise VibepolloError("vibepollo_certificate_mismatch", kind="config")

    def request(self, method: str, path: str, body: Any = None,
                timeout: float = REQUEST_TIMEOUT_SECONDS, *,
                output: Path | None = None) -> Any:
        """One API call on a fresh pinned connection, all of it within `timeout`; JSON, or
        `output` filled with the body."""
        base_url, token = read_settings(self.settings_path)
        parts = urllib.parse.urlsplit(base_url)
        headers = {"Accept": "application/json", "Authorization": "Bearer " + token}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        deadline = time.monotonic() + timeout
        connection = _PinnedConnection(parts.hostname or "", parts.port or 443, timeout,
                                       self._check_pin)

        def bounded() -> None:
            """Give the next socket operation only what is left of the budget. The response
            keeps reading the socket after the connection lets go of it."""
            left = deadline - time.monotonic()
            if left <= 0:
                raise TimeoutError
            sock.settimeout(left)

        limit = MAX_EXPORT_BYTES if output is not None else MAX_RESPONSE_BYTES
        content, size, sink = bytearray(), 0, None
        try:
            connection.connect()
            sock = connection.sock
            bounded()
            connection.request(method, path, body=data, headers=headers)
            bounded()
            response = connection.getresponse()
            if not 200 <= response.status < 300:
                bounded()
                detail = response.read1(4096).decode("utf-8", "replace").strip()
                raise VibepolloError(f"HTTP {response.status}: {detail[:300]}",
                                     kind="http", status=response.status)
            if output is not None:
                sink = _local(open, output, "wb")
            while not response.isclosed():  # a complete body closes the socket
                bounded()
                chunk = response.read1(64 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > limit:
                    raise VibepolloError("Vibepollo response is too large.", kind="schema",
                                         status=200)
                if sink is None:
                    content += chunk
                else:
                    _local(sink.write, chunk)
            if response.length:
                raise http.client.IncompleteRead(bytes(content), response.length)
            if sink is not None:
                _local(sink.close)
        except VibepolloError:
            raise
        except TimeoutError:
            raise VibepolloError("timed out", kind="timeout") from None
        except (OSError, http.client.HTTPException) as error:
            raise VibepolloError(f"{type(error).__name__}: {error}") from None
        finally:
            connection.close()
            if sink is not None:
                try:
                    sink.close()
                except OSError:
                    pass  # already reported, or the export is being discarded
        if output is not None:
            return {"status": True, "path": str(output), "length": size}
        text = content.decode("utf-8-sig", "replace")
        if not text.strip():
            return {}
        try:
            return json.loads(text)
        except ValueError:
            raise VibepolloError("Vibepollo returned invalid JSON.", kind="schema",
                                 status=200) from None

    def call(self, method: str, path: str, body: Any = None,
             timeout: float = REQUEST_TIMEOUT_SECONDS, output: Path | None = None) -> Any:
        """`request` with the health bookkeeping `api_online` and repair status report."""
        try:
            value = self.request(method, path, body, timeout, output=output)
        except VibepolloError as error:
            self.last_error = f"API {method} {path} failed: {error}"
            if error.kind in {"timeout", "transport"}:
                self.unavailable_until = time.monotonic() + UNAVAILABLE_BACKOFF_SECONDS
            raise
        self.last_error = ""
        self.last_success = time.monotonic()
        self.unavailable_until = 0.0
        return value

    def api_online(self, timeout: float = 1.0) -> bool:
        """The API answered MoonWaker's token recently, or does now."""
        now = time.monotonic()
        if self.last_success and now - self.last_success < ONLINE_EVIDENCE_SECONDS:
            return True
        if now < self.unavailable_until or not self.configured():
            return False
        try:
            self.call("GET", "/api/metadata", timeout=timeout)
            return True
        except VibepolloError:
            return False

    def control_snapshot(self, timeout: float = 8.0) -> tuple[bool, dict[str, Any]]:
        """(any source answered, snapshot) from parallel fresh reads of the control sources."""
        per_source = max(0.1, min(float(timeout), CONTROL_SOURCE_TIMEOUT_SECONDS))

        def fetch(item: tuple[str, str]) -> tuple[str, Any, dict[str, Any]]:
            name, path = item
            started = time.monotonic()
            value, outcome = None, "ok"
            try:
                value = self.call("GET", path, timeout=per_source)
            except VibepolloError as error:
                outcome = {"timeout": "timeout", "schema": "schema_invalid"}.get(
                    error.kind, f"http_{error.status}" if error.kind == "http"
                    else "transport_error")
            return name, value, {"outcome": outcome,
                                 "elapsed_ms": int((time.monotonic() - started) * 1000)}

        with ThreadPoolExecutor(max_workers=len(CONTROL_PATHS)) as workers:
            rows = list(workers.map(fetch, CONTROL_PATHS.items()))
        probe = {name: row for name, _value, row in rows}
        snapshot = build_control_snapshot({name: value for name, value, _row in rows}, probe)
        return any(row["outcome"] == "ok" for row in probe.values()), snapshot

    def close_app(self, timeout: float = 8.0) -> tuple[bool, dict[str, Any]]:
        try:
            return True, {"result": self.call("POST", "/api/apps/close", {}, timeout=timeout)}
        except VibepolloError as error:
            return False, {"error": str(error), "status": error.status}

    def repair_status(self) -> dict[str, Any]:
        try:
            metadata = self.call("GET", "/api/metadata", timeout=2.5)
        except VibepolloError:
            metadata = None
        try:
            base_url = read_settings(self.settings_path)[0]
        except VibepolloError:
            base_url = ""
        return {
            "host": {"online": metadata is not None,
                     "version": str(_first(metadata, "version", default="unknown")),
                     "platform": str(_first(metadata, "platform", default=""))},
            "bridge": {"online": True,
                       "uptime_seconds": round(time.monotonic() - self.started),
                       "api_error": self.last_error, "base_url": base_url},
        }

    def action(self, name: str) -> Any:
        if name == "restart":
            return self.call("POST", "/api/restart", {}, timeout=5.0)
        if name == "reset-display":
            return self.call("POST", "/api/reset-display-device-persistence", {}, timeout=5.0)
        if name == "export-logs":
            # Only the latest export is kept; a failed one leaves no partial file behind.
            _local(self.export_directory.mkdir, 0o777, True, True)
            path = self.export_directory / time.strftime("Vibepollo_Logs_%Y%m%d_%H%M%S.zip")
            try:
                result = self.call("GET", "/api/logs/export", timeout=EXPORT_TIMEOUT_SECONDS,
                                   output=path)
            except BaseException:
                with contextlib.suppress(OSError):
                    path.unlink(missing_ok=True)
                raise
            for previous in self.export_directory.glob("Vibepollo_Logs_*.zip"):
                if previous != path:
                    with contextlib.suppress(OSError):  # open elsewhere: the next export
                        previous.unlink()
            return result
        raise ValueError("Unknown Vibepollo action.")

    def _clients(self) -> list[Any]:
        return _items(_first(self.call("GET", "/api/clients/list"), "named_certs", "clients"))

    def pair(self, pin: str, name: str) -> dict[str, Any]:
        """Submit a pending Moonlight PIN, then grant the new client MoonWaker's permissions."""
        before = {str(_first(client, "uuid", default="")) for client in self._clients()}
        started, attempts = time.monotonic(), 0
        while True:
            attempts += 1
            try:
                accepted = self.call("POST", "/api/pin", {"pin": pin, "name": name})
            except VibepolloError as error:
                raise VibepolloError(f"Vibepollo PIN submission failed: {error}") from None
            if bool(_first(accepted, "status", default=False)):
                break
            if time.monotonic() - started >= 8.0:
                reason = str(_first(accepted, "error", "message", default=""))
                raise VibepolloError(
                    "Vibepollo did not expose a pending Moonlight pairing session within "
                    f"8 seconds after {attempts} PIN attempts" + (f": {reason}" if reason else ""))
            time.sleep(0.25)
        deadline = time.monotonic() + 15.0
        paired = None
        while paired is None:
            time.sleep(0.15)
            candidates = [client for client in self._clients()
                          if str(_first(client, "uuid", default="")) not in before and
                          str(_first(client, "name", default="")).casefold() == name.casefold()]
            if len(candidates) > 1:
                raise VibepolloError("Vibepollo returned multiple newly paired clients")
            if candidates:
                paired = candidates[0]
            elif time.monotonic() >= deadline:
                raise VibepolloError(
                    "Vibepollo accepted the PIN, but the paired Moonlight client did not "
                    "appear in its client list within 15 seconds")
        payload = {
            "uuid": str(_first(paired, "uuid", default="")), "name": name,
            "perm": CLIENT_PERMISSIONS,
            "enable_legacy_ordering": bool(_first(paired, "enable_legacy_ordering", default=True)),
            "allow_client_commands": bool(_first(paired, "allow_client_commands", default=True)),
            "do": _items(_first(paired, "do", default=[])),
            "undo": _items(_first(paired, "undo", default=[])),
            "display_mode": str(_first(paired, "display_mode", default="")),
            "output_name_override": str(_first(paired, "output_name_override", default="")),
            "always_use_virtual_display": bool(
                _first(paired, "always_use_virtual_display", default=False)),
            "virtual_display_mode": str(_first(paired, "virtual_display_mode", default="")),
            "virtual_display_layout": str(_first(paired, "virtual_display_layout", default="")),
            "prefer_10bit_sdr": bool(_first(paired, "prefer_10bit_sdr", default=False)),
            "hdr_profile": str(_first(paired, "hdr_profile", default="")),
            "config_overrides": _first(paired, "config_overrides", default={}),
        }
        try:
            updated = self.call("POST", "/api/clients/update", payload)
        except VibepolloError as error:
            raise VibepolloError(f"Vibepollo client permission update failed: {error}") from None
        if not bool(_first(updated, "status", default=False)):
            reason = str(_first(updated, "error", "message", default=""))
            raise VibepolloError("Vibepollo rejected the MoonWaker client permission update" +
                                 (f": {reason}" if reason else ""))
        verified = next((client for client in self._clients()
                         if str(_first(client, "uuid", default="")) == payload["uuid"]), None)
        try:
            permissions = int(_first(verified, "perm", default=0))
        except (TypeError, ValueError):
            permissions = 0
        if permissions & CLIENT_PERMISSIONS != CLIENT_PERMISSIONS:
            raise VibepolloError(
                "Vibepollo paired the client, but did not persist the required MoonWaker "
                f"permissions (expected {CLIENT_PERMISSIONS}, received {permissions})")
        return {"ok": True, "client_uuid": payload["uuid"], "permissions": permissions}

    def verify_identity(self, challenge: str, certificate_sha256: str,
                        signature: bytes) -> str:
        """The UUID of the paired client whose certificate signed `challenge`; IdentityError
        names the reason otherwise."""
        if (len(challenge.encode("utf-8")) > 512 or not challenge.startswith(IDENTITY_PREFIX) or
                not FINGERPRINT_PATTERN.fullmatch(certificate_sha256) or
                not 1 <= len(signature) <= 1024):
            raise IdentityError("identity_proof_invalid")
        try:
            state_path = configured_file(self.config_directory(), "file_state",
                                         "sunshine_state.json")
        except (OSError, VibepolloError):
            raise IdentityError("identity_binding_unsupported") from None
        host_id, records = read_identity_state(state_path)
        try:
            live_host_id = str(_first(self.call("GET", "/api/apps"), "host_uuid",
                                      "host_uniqueid", "uniqueid", default="")).strip()
            if live_host_id != host_id:
                raise IdentityError("identity_binding_unsupported")
            matches = [record for record in records if record[1] == certificate_sha256]
            if not matches:
                raise IdentityError("pairing_required")
            if len(matches) > 1:
                raise IdentityError("identity_ambiguous")
            uuid, _fingerprint, certificate = matches[0]
            if not rsa_sha256_signature_valid(certificate, challenge.encode("utf-8"), signature):
                raise IdentityError("identity_proof_invalid")
            live = [client for client in self._clients()
                    if str(_first(client, "uuid", default="")) == uuid]
        except VibepolloError:
            raise IdentityError("identity_binding_unavailable") from None
        if not live:
            raise IdentityError("pairing_required")
        if len(live) > 1:
            raise IdentityError("identity_ambiguous")
        return uuid

    def _apps(self) -> list[Any]:
        value = self.call("GET", "/api/apps")
        return _items(_first(value, "apps", default=value))

    @staticmethod
    def _stream_matches(apps: list[Any]) -> list[tuple[dict[str, Any], int]]:
        return [(app, int(_first(app, "index", default=index)))
                for index, app in enumerate(apps)
                if isinstance(app, dict) and str(_first(app, "moonwaker-managed", default="")) ==
                "stream"]

    def ensure_stream(self) -> dict[str, Any]:
        """The one command-free MoonWaker Stream application every game start uses. A foreign
        or hand-edited application holding its name or UUID is never overwritten."""
        apps = self._apps()
        reserved = [app for app in apps if str(_first(app, "uuid", default="")) == STREAM_APP_UUID]
        if len(reserved) > 1:
            raise StreamConflict("uuid_held_by_several", "Multiple applications already use "
                                 "the reserved MoonWaker Stream UUID")
        if reserved and str(_first(reserved[0], "moonwaker-managed", default="")) != "stream":
            raise StreamConflict("uuid_held_unmanaged", "An unmanaged application already "
                                 "uses the reserved MoonWaker Stream UUID")
        managed = self._stream_matches(apps)
        if len(managed) > 1:
            raise StreamConflict("managed_several", "Multiple managed MoonWaker Stream "
                                 "applications were found")
        if managed:
            app, index = managed[0]
            if not stream_app_command_free(app):
                raise StreamConflict("managed_has_commands", "The managed MoonWaker Stream "
                                     "application contains manual commands or hooks")
            if _stream_app_ready(app):
                return self._stream_result(app, index, created=False, updated=False)
            payload, created = {**json.loads(json.dumps(app)), "index": index}, False
        else:
            same_name = [app for app in apps if _normalized_name(_first(app, "name")) ==
                         _normalized_name(STREAM_APP_NAME)]
            if len(same_name) > 1:
                raise StreamConflict("name_held_by_several", "Multiple applications already "
                                     "use the MoonWaker Stream name")
            if same_name:
                raise StreamConflict("name_held_unmarked", "An unmarked application already "
                                     "uses the MoonWaker Stream name")
            payload, created = {"index": -1}, True
        payload.update({"name": STREAM_APP_NAME, "uuid": STREAM_APP_UUID,
                        "moonwaker-managed": "stream", "auto-detach": True})
        self.call("POST", "/api/apps", payload)
        deadline = time.monotonic() + 6.0
        while time.monotonic() < deadline:
            time.sleep(0.35)
            matches = self._stream_matches(self._apps())
            if len(matches) == 1 and stream_app_command_free(matches[0][0]) and \
                    _stream_app_ready(matches[0][0]):
                return self._stream_result(*matches[0], created=created, updated=True)
        raise VibepolloError("Vibepollo did not return the ensured MoonWaker Stream application")

    @staticmethod
    def _stream_result(app: dict[str, Any], index: int, created: bool,
                       updated: bool) -> dict[str, Any]:
        try:
            app_id = int(str(_first(app, "id", default="0")))
        except ValueError:
            app_id = 0
        return {"ok": True, "created": created, "updated": updated,
                "uuid": str(_first(app, "uuid", default="")), "app_id": app_id,
                "index": index, "name": str(_first(app, "name", default=""))}

    def keep_stream_target(self, stop: threading.Event,
                           record: Callable[..., None]) -> None:
        """Ensure MoonWaker Stream now and whenever it goes missing. An unavailable API or an
        unexpected failure is retried every 30 s; a held name or UUID lasts until the user
        resolves it, so it and a ready target are rechecked every 5 minutes. Each change of
        outcome (state and reason or error type) is recorded once."""
        last: tuple[str, str] = ("", "")
        while True:
            error: Exception | None = None
            reason = ""
            try:
                self.ensure_stream()
                outcome, level, wait = "ready", "INFO", STREAM_RECHECK_SECONDS
            except StreamConflict as conflict:
                outcome, level, wait = "conflict", "ERROR", STREAM_RECHECK_SECONDS
                reason = conflict.reason
            except VibepolloError as failure:
                outcome, level, wait = "unavailable", "WARN", STREAM_RETRY_SECONDS
                reason = failure.kind
            except Exception as failure:  # the keeper must outlive any defect in one attempt
                outcome, level, wait = "error", "ERROR", STREAM_RETRY_SECONDS
                error, reason = failure, type(failure).__name__
            if (outcome, reason) != last:
                fields: dict[str, Any] = {"state": outcome, "reason": reason}
                if error is not None:
                    fields["error"] = error
                record("vibepollo-stream-target", level, **fields)
                last = (outcome, reason)
            if stop.wait(wait):
                return
