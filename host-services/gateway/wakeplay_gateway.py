#!/usr/bin/env python3
"""Authenticated HTTPS facade for Discord, Vibepollo, audio and VirtualHere controls."""

from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import json
import logging
import logging.handlers
import os
import platform
import queue
import re
import secrets
import ssl
import socket
import struct
import subprocess
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, datetime, time as datetime_time, timedelta, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from control_operation_policy import ControlOperationPolicy
from child_terminal_operation import ChildTerminalOperation
from provider_control_operation import ProviderControlOperation, StreamerControlOperation

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover - Python 3.8 fallback
    ZoneInfo = None


API_PREFIX = "/api/v1"
PAIRING_LIFETIME_SECONDS = 10 * 60
STREAM_PAIR_TICKET_LIFETIME_SECONDS = 60
VIBEPOOLLO_IDENTITY_CHALLENGE_LIFETIME_SECONDS = 60
VIBEPOOLLO_IDENTITY_CHALLENGE_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{32,128}$")
VIBEPOOLLO_IDENTITY_CERT_PATTERN = re.compile(r"^[0-9a-f]{64}$")
VIBEPOOLLO_IDENTITY_SIGNATURE_MAX_BYTES = 1024
IDEMPOTENCY_LIFETIME_SECONDS = 2 * 60
MAX_BODY_BYTES = 16 * 1024
MICROPHONE_FRAME_BYTES = 1920
MICROPHONE_MAX_CHUNK_BYTES = 8192
MICROPHONE_IDLE_SECONDS = 5.0
DISCORD_AUDIO_FRAME_BYTES = 3840
DISCORD_AUDIO_CONTENT_TYPE = (
    "application/vnd.moonwaker.discord-audio-pcm;"
    "format=s16le;rate=48000;channels=2")
NETWORK_DOWNLOAD_DEFAULT_BYTES = 8 * 1024 * 1024
NETWORK_DOWNLOAD_MAX_BYTES = 512 * 1024 * 1024
NETWORK_DOWNLOAD_CHUNK_BYTES = 64 * 1024
GATEWAY_SCHEMA_VERSION = 3
CHILD_PROFILE_KIND = "child"
STANDARD_PROFILE_KIND = "standard"
CHILD_PROFILES_CAPABILITY = "child_profiles_v1"
CONTROL_OPERATIONS_CAPABILITY = "control_operations_v1"
CHILD_MANAGEMENT_SESSION_SECONDS = 5 * 60
CHILD_PARENT_POLICY_REVISION_FIELD = "children_policy_revision"
CHILD_SHARING_REQUESTS_FIELD = "child_sharing_requests"
CHILD_PROFILE_REQUESTS_FIELD = "child_profile_requests"
CHILD_PROFILE_REQUEST_LIMIT = 32
CHILD_PROFILE_WEEKDAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
CHILD_PROFILE_MAX_DAILY_LIMIT_SECONDS = 24 * 60 * 60
CHILD_PROFILE_WRITER_OPERATION = 11
CHILD_USAGE_SCHEMA_VERSION = 1
CHILD_TIME_CHECKPOINT_SECONDS = 30.0
CHILD_TIME_HEARTBEAT_INTERVAL_SECONDS = 20.0
CHILD_TIME_CLIENT_GRACE_SECONDS = 120.0
CHILD_TERMINAL_JOIN_SECONDS = 15.0
CHILD_TIME_FAILURE_TOLERANCE_SECONDS = 300.0
CHILD_TIME_HOUSEKEEPING_INTERVAL_SECONDS = 5.0
CHILD_TIME_USAGE_FILE = "child-time-usage.json"
CLIENT_LAST_SEEN_WRITE_SECONDS = 5 * 60
PIN_UNLOCK_LEASE_SECONDS = 5 * 60
PIN_FAILURE_WINDOW_SECONDS = 5 * 60
PIN_MAX_COOLDOWN_SECONDS = 30
PROFILE_PERMISSIONS = {"use_profile", "remote_sign_in", "manage_children"}
MANAGE_CHILDREN_PERMISSION = "manage_children"
PROFILE_SESSION_HEADER = "X-MoonWaker-Profile-Session"
DISCORD_ID_PATTERN = re.compile(r"^[0-9]{5,32}$")
VIRTUALHERE_ADDRESS_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")
AUDIO_DEVICE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:{}-]{1,220}$")
PROFILE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
WINDOWS_SID_PATTERN = re.compile(r"^S-\d-\d+(?:-\d+)+$", re.IGNORECASE)
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
PROFILE_SESSION_ID_PATTERN = re.compile(
    r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-"
    r"[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
PLAYNITE_GAME_ID_PATTERN = re.compile(
    r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
GAME_RECORD_ID_PATTERN = re.compile(
    r"^[a-z][a-z0-9_-]{1,31}:[A-Za-z0-9._-]{1,128}$")
CHILD_GAME_KEY_PATTERN = re.compile(
    r"^[A-Za-z0-9._-]{1,64}/[a-z][a-z0-9_-]{1,31}:[A-Za-z0-9._-]{1,128}$")
PLAYNITE_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{0,128}$")
CHILD_GAME_EVENT_KINDS = {
    "game-starting", "game-running", "game-stopping", "game-stopped",
    "game-stop-close-requested", "game-stop-dispatch-completed",
    "game-stop-rejected", "game-launch-failed", "game-reconciled",
    "game-reconciliation-ambiguous", "game-reconciliation-cleared",
    "game-installing", "game-installed", "game-uninstalled",
    "game-installation-attention-required", "game-installation-auto-confirmed",
    "game-installation-cancelled", "game-installation-failed",
    "game-installation-resumed", "launcher-interaction-required",
    "privacy-gate-closed", "target-window-ready", "session-hard-reset",
}
CHILD_TIME_SESSION_ID_PATTERN = PROFILE_SESSION_ID_PATTERN
CHILD_VIBEPOOLLO_UUID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIAGNOSTIC_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9._:$-]{1,256}$")
DIAGNOSTIC_ROUTE_PATTERN = re.compile(r"^/[A-Za-z0-9._:{}/-]{0,255}$")
DIAGNOSTIC_MAX_BYTES = 2 * 1024 * 1024
DIAGNOSTIC_BACKUP_COUNT = 9
DIAGNOSTIC_QUEUE_SIZE = 512
DIAGNOSTIC_RETENTION_SECONDS = 7 * 24 * 60 * 60
DIAGNOSTIC_FIELDS = {
    "method", "route", "request_id", "profile_id", "actor_profile_id",
    "execution_profile_id", "status",
    "http_status", "duration_ms", "error_type", "error_code",
    "response_content_length", "response_body_bytes_written",
    "close_http_ok", "close_http_status", "snapshot_attempts",
    "snapshot_http_ok", "snapshot_http_status", "snapshot_control_fresh",
    "snapshot_unavailable_source_count", "snapshot_session_active",
    "snapshot_app_running", "snapshot_session_count",
    "snapshot_connected_clients", "snapshot_running_apps",
    "operation_id", "generation", "actor_lease_epoch", "effect_sequence",
    "stream_session_id", "child_session_id", "intent", "state", "reason",
}


def profile_deletion_pending(profile: Any) -> bool:
    """Treat any durable deletion marker as unavailable, including malformed ones."""
    return (isinstance(profile, dict) and
            profile.get("deletion_tombstone") is not None)


def compact_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def profile_pin_required(profile: Any) -> bool:
    return isinstance(profile, dict) and profile.get("pin_verifier") is not None


def normalize_profile_session_id(value: str | None) -> str | None:
    """Validate an optional per-process profile authorization identifier."""
    if value is None:
        return None
    normalized = str(value).strip()
    if not PROFILE_SESSION_ID_PATTERN.fullmatch(normalized):
        raise ValueError(
            f"A valid {PROFILE_SESSION_HEADER} header is required.")
    return normalized.lower()


def verify_profile_pin(pin: str, verifier: Any) -> bool:
    if not re.fullmatch(r"[0-9]{4}", pin or "") or not isinstance(verifier, dict):
        return False
    try:
        if (int(verifier.get("version")) != 1 or
                verifier.get("algorithm") != "pbkdf2-sha256"):
            return False
        iterations = int(verifier.get("iterations"))
        if iterations < 100_000 or iterations > 1_000_000:
            return False
        salt = base64.b64decode(str(verifier.get("salt") or ""), validate=True)
        expected = base64.b64decode(str(verifier.get("digest") or ""), validate=True)
        if len(salt) != 16 or len(expected) != 32:
            return False
        actual = hashlib.pbkdf2_hmac("sha256", pin.encode("ascii"), salt, iterations)
        return secrets.compare_digest(actual, expected)
    except (TypeError, ValueError):
        return False


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def resolve_windows_account_sid(account_name: str) -> str:
    """Resolve a Windows account name without invoking a shell."""
    if os.name != "nt" or not account_name.strip():
        return ""
    try:
        import ctypes
        from ctypes import wintypes

        advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        lookup = advapi32.LookupAccountNameW
        lookup.argtypes = [
            wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.LPVOID,
            wintypes.LPDWORD, wintypes.LPWSTR, wintypes.LPDWORD,
            wintypes.LPDWORD,
        ]
        lookup.restype = wintypes.BOOL
        sid_size = wintypes.DWORD()
        domain_size = wintypes.DWORD()
        sid_type = wintypes.DWORD()
        lookup(None, account_name, None, ctypes.byref(sid_size), None,
               ctypes.byref(domain_size), ctypes.byref(sid_type))
        if ctypes.get_last_error() != 122 or sid_size.value <= 0:
            return ""
        sid = ctypes.create_string_buffer(sid_size.value)
        domain = ctypes.create_unicode_buffer(max(1, domain_size.value))
        if not lookup(None, account_name, sid, ctypes.byref(sid_size), domain,
                      ctypes.byref(domain_size), ctypes.byref(sid_type)):
            return ""
        sid_text = wintypes.LPWSTR()
        convert = advapi32.ConvertSidToStringSidW
        convert.argtypes = [wintypes.LPVOID, ctypes.POINTER(wintypes.LPWSTR)]
        convert.restype = wintypes.BOOL
        if not convert(sid, ctypes.byref(sid_text)):
            return ""
        try:
            return str(sid_text.value or "")
        finally:
            kernel32.LocalFree.argtypes = [wintypes.LPVOID]
            kernel32.LocalFree.restype = wintypes.LPVOID
            kernel32.LocalFree(ctypes.cast(sid_text, wintypes.LPVOID))
    except (AttributeError, OSError, TypeError, ValueError):
        return ""


class LoginBrokerClient:
    """Small client for the Broker's bounded MWLB/MWLR v1 Gateway pipe."""

    PIPE_PATH = r"\\.\pipe\MoonWakerLoginBroker.Gateway.v1"
    TIMEOUT_MS = 500
    MAX_FIELD_BYTES = 4096
    MAX_MESSAGE_BYTES = 32768
    MAX_FIELDS = 8

    def capability(self) -> dict[str, Any]:
        return self.request(5, {})

    def profile_state(self, profile: dict[str, Any]) -> dict[str, Any]:
        return self.request(4, {
            1: str(profile.get("id") or ""),
            2: str(profile.get("windows_account_sid") or ""),
            3: str(profile.get("windows_account_name") or ""),
        })

    def begin(self, client_id: str, profile: dict[str, Any],
              request_id: str) -> dict[str, Any]:
        return self.request(1, {
            1: client_id,
            2: str(profile.get("id") or ""),
            3: request_id,
            4: str(profile.get("windows_account_sid") or ""),
            5: str(profile.get("windows_account_name") or ""),
        })

    def attempt_state(self, client_id: str, profile_id: str,
                      request_id: str, attempt_id: str) -> dict[str, Any]:
        return self.request(2, {
            1: client_id, 2: profile_id, 3: request_id, 4: attempt_id,
        })

    def cancel(self, client_id: str, profile_id: str,
               request_id: str, attempt_id: str) -> dict[str, Any]:
        return self.request(3, {
            1: client_id, 2: profile_id, 3: request_id, 4: attempt_id,
        })

    def switch_session(self, client_id: str, profile: dict[str, Any],
                       request_id: str) -> dict[str, Any]:
        return self.request(6, {
            1: client_id,
            2: str(profile.get("id") or ""),
            3: request_id,
            4: str(profile.get("windows_account_sid") or ""),
            5: str(profile.get("windows_account_name") or ""),
        })

    def begin_child(self, client_id: str, actor_profile_id: str,
                    execution_profile: dict[str, Any], request_id: str) \
            -> dict[str, Any]:
        """Use the child operation numbers so a v1 Broker cannot accept a child as a parent."""
        return self.request(7, {
            1: client_id,
            2: actor_profile_id,
            3: request_id,
            4: str(execution_profile.get("id") or ""),
            5: str(execution_profile.get("windows_account_sid") or ""),
            6: str(execution_profile.get("windows_account_name") or ""),
        })

    def child_attempt_state(self, client_id: str, actor_profile_id: str,
                            execution_profile: dict[str, Any], request_id: str,
                            attempt_id: str) -> dict[str, Any]:
        return self.request(8, {
            1: client_id, 2: actor_profile_id, 3: request_id, 4: attempt_id,
            5: str(execution_profile.get("id") or ""),
            6: str(execution_profile.get("windows_account_sid") or ""),
            7: str(execution_profile.get("windows_account_name") or ""),
        })

    def cancel_child(self, client_id: str, actor_profile_id: str,
                     execution_profile: dict[str, Any], request_id: str,
                     attempt_id: str) -> dict[str, Any]:
        return self.request(9, {
            1: client_id, 2: actor_profile_id, 3: request_id, 4: attempt_id,
            5: str(execution_profile.get("id") or ""),
            6: str(execution_profile.get("windows_account_sid") or ""),
            7: str(execution_profile.get("windows_account_name") or ""),
        })

    def switch_child(self, client_id: str, actor_profile_id: str,
                     execution_profile: dict[str, Any], request_id: str) \
            -> dict[str, Any]:
        return self.request(10, {
            1: client_id, 2: actor_profile_id, 3: request_id,
            4: str(execution_profile.get("id") or ""),
            5: str(execution_profile.get("windows_account_sid") or ""),
            6: str(execution_profile.get("windows_account_name") or ""),
        })

    def child_profile_api(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Run one validated Host Control child-profile mutation."""
        if not isinstance(payload, dict):
            raise ValueError("Invalid child profile writer request.")
        result = self.request(CHILD_PROFILE_WRITER_OPERATION, {
            1: json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        })
        fields = result.get("fields") if isinstance(result, dict) else None
        raw = fields.get(3) if isinstance(fields, dict) else None
        if raw:
            try:
                response = json.loads(raw)
            except (TypeError, ValueError, json.JSONDecodeError):
                response = None
            if isinstance(response, dict):
                return {**result, "result": response}
        return result

    def request(self, operation: int, fields: dict[int, str]) -> dict[str, Any]:
        if os.name != "nt":
            return self.unavailable()
        try:
            import ctypes
            wait = ctypes.WinDLL("kernel32", use_last_error=True).WaitNamedPipeW
            wait.argtypes = [ctypes.c_wchar_p, ctypes.c_uint]
            wait.restype = ctypes.c_int
            deadline = time.monotonic() + self.TIMEOUT_MS / 1000
            while not wait(self.PIPE_PATH, max(0, int(
                    (deadline - time.monotonic()) * 1000))):
                error_code = ctypes.get_last_error()
                if error_code != 2 or time.monotonic() >= deadline:
                    DIAGNOSTICS.record("login-broker-unavailable", level="WARN",
                                       error_code=error_code)
                    return self.unavailable()
                time.sleep(0.01)
            try:
                pipe = open(self.PIPE_PATH, "r+b", buffering=0)
            except OSError as error:
                DIAGNOSTICS.record("login-broker-unavailable", level="WARN",
                                   error=error,
                                   error_code=int(getattr(error, "winerror", 0) or
                                                  getattr(error, "errno", 0) or 0))
                return self.unavailable()
            with pipe:
                pipe.write(self.encode_request(operation, fields))
                return self.decode_response(pipe)
        except (OSError, ValueError, UnicodeError, struct.error) as error:
            DIAGNOSTICS.record("login-broker-unavailable", level="WARN", error=error,
                               error_code=int(getattr(error, "winerror", 0) or
                                              getattr(error, "errno", 0) or 0))
            return self.unavailable()

    @classmethod
    def encode_request(cls, operation: int, fields: dict[int, str]) -> bytes:
        if not 0 <= operation <= 255 or len(fields) > cls.MAX_FIELDS:
            raise ValueError("Invalid Broker request.")
        output = bytearray(b"MWLB" + bytes((1, operation, len(fields), 0)))
        for key, text in fields.items():
            if not 0 <= key <= 255:
                raise ValueError("Invalid Broker field.")
            value = str(text).encode("utf-8")
            if (len(value) > cls.MAX_FIELD_BYTES or
                    len(output) + 5 + len(value) > cls.MAX_MESSAGE_BYTES):
                raise ValueError("Broker request is too large.")
            output.extend(bytes((key,)))
            output.extend(struct.pack("<I", len(value)))
            output.extend(value)
        return bytes(output)

    @classmethod
    def decode_response(cls, pipe: Any) -> dict[str, Any]:
        header = cls.read_exact(pipe, 8)
        if (header[:4] != b"MWLR" or header[4] != 1 or
                header[6] > cls.MAX_FIELDS or header[7] != 0):
            raise ValueError("Invalid Broker response.")
        total = 8
        fields: dict[int, str] = {}
        for _ in range(header[6]):
            field_header = cls.read_exact(pipe, 5)
            key = field_header[0]
            length = struct.unpack("<I", field_header[1:])[0]
            if (key in fields or length > cls.MAX_FIELD_BYTES or
                    total + 5 + length > cls.MAX_MESSAGE_BYTES):
                raise ValueError("Invalid Broker response field.")
            fields[key] = cls.read_exact(pipe, length).decode("utf-8")
            total += 5 + length
        return {
            "success": header[5] == 0,
            "state": fields.get(1, "ready" if header[5] == 0 else "action_required"),
            "reason": fields.get(2, "none" if header[5] == 0 else "action_required"),
            "fields": fields,
        }

    @staticmethod
    def read_exact(pipe: Any, length: int) -> bytes:
        value = bytearray()
        while len(value) < length:
            chunk = pipe.read(length - len(value))
            if not chunk:
                raise OSError("Broker pipe closed early.")
            value.extend(chunk)
        return bytes(value)

    @staticmethod
    def unavailable() -> dict[str, Any]:
        return {"success": False, "state": "broker_unavailable",
                "reason": "broker_unavailable", "fields": {}}


def diagnostic_route(target: str) -> str:
    route = target.split("?", 1)[0].split("#", 1)[0]
    return route if DIAGNOSTIC_ROUTE_PATTERN.fullmatch(route) else ""


class _DropQueueHandler(logging.handlers.QueueHandler):
    def __init__(self, records: queue.Queue, owner: "GatewayDiagnostics") -> None:
        super().__init__(records)
        self.owner = owner

    def enqueue(self, record: logging.LogRecord) -> None:
        try:
            self.queue.put_nowait(record)
        except (queue.Full, OSError):
            self.owner.dropped += 1

    def handleError(self, record: logging.LogRecord) -> None:  # noqa: N802
        self.owner.dropped += 1


class _FailOpenRotatingFileHandler(logging.handlers.RotatingFileHandler):
    def __init__(self, filename: Path, owner: "GatewayDiagnostics") -> None:
        self.owner = owner
        super().__init__(filename, maxBytes=DIAGNOSTIC_MAX_BYTES,
                         backupCount=DIAGNOSTIC_BACKUP_COUNT,
                         encoding="utf-8", delay=True)

    def handleError(self, record: logging.LogRecord) -> None:  # noqa: N802
        self.owner.dropped += 1


class GatewayDiagnostics:
    def __init__(self) -> None:
        self.dropped = 0
        self.run_id = uuid.uuid4().hex
        self.started = time.monotonic()
        self._logger: logging.Logger | None = None
        self._listener: logging.handlers.QueueListener | None = None
        self._records: queue.Queue | None = None

    def start(self, log_dir: Path) -> None:
        if self._listener is not None:
            return
        try:
            log_dir.mkdir(parents=True, exist_ok=True)
            cutoff = time.time() - DIAGNOSTIC_RETENTION_SECONDS
            for path in log_dir.glob("gateway-diagnostics.jsonl*"):
                if not re.fullmatch(r"gateway-diagnostics\.jsonl(?:\.\d+)?", path.name):
                    continue
                try:
                    if path.stat().st_mtime < cutoff:
                        path.unlink()
                except OSError:
                    self.dropped += 1
            records: queue.Queue = queue.Queue(maxsize=DIAGNOSTIC_QUEUE_SIZE)
            output = _FailOpenRotatingFileHandler(
                log_dir / "gateway-diagnostics.jsonl", self)
            output.setFormatter(logging.Formatter("%(message)s"))
            logger = logging.Logger(f"moonwaker.gateway.{self.run_id}", logging.DEBUG)
            logger.propagate = False
            logger.addHandler(_DropQueueHandler(records, self))
            listener = logging.handlers.QueueListener(records, output)
            listener.start()
            self._records = records
            self._logger = logger
            self._listener = listener
        except (OSError, RuntimeError, ValueError):
            self.dropped += 1

    def close(self) -> None:
        listener, records = self._listener, self._records
        self._listener = None
        self._logger = None
        self._records = None
        if listener is None or records is None:
            return
        try:
            records.join()
            listener.stop()
            for handler in listener.handlers:
                handler.close()
        except (OSError, RuntimeError, queue.Full):
            self.dropped += 1

    def record(self, event: str, level: str = "INFO", *,
               error: BaseException | None = None, include_frames: bool = False,
               **fields: Any) -> None:
        logger = self._logger
        if logger is None:
            return
        try:
            if not DIAGNOSTIC_TOKEN_PATTERN.fullmatch(event):
                self.dropped += 1
                return
            entry: dict[str, Any] = {
                "v": 1,
                "ts": datetime.now(timezone.utc).isoformat(
                    timespec="milliseconds").replace("+00:00", "Z"),
                "mono_ms": int((time.monotonic() - self.started) * 1000),
                "level": level if level in {"DEBUG", "INFO", "WARN", "ERROR"} else "INFO",
                "component": "host.gateway",
                "event": event,
                "run_id": self.run_id,
            }
            for key, value in fields.items():
                if key not in DIAGNOSTIC_FIELDS:
                    continue
                if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                    entry[key] = value
                elif isinstance(value, str):
                    if key == "route" and DIAGNOSTIC_ROUTE_PATTERN.fullmatch(value):
                        entry[key] = value
                    elif key != "route" and DIAGNOSTIC_TOKEN_PATTERN.fullmatch(value):
                        entry[key] = value
            if error is not None:
                error_type = f"{type(error).__module__}.{type(error).__qualname__}"
                if DIAGNOSTIC_TOKEN_PATTERN.fullmatch(error_type):
                    entry["error_type"] = error_type
                if include_frames:
                    entry["frames"] = self._safe_frames(error)
            logger.log({"DEBUG": logging.DEBUG, "WARN": logging.WARNING,
                        "ERROR": logging.ERROR}.get(entry["level"], logging.INFO),
                       json.dumps(entry, ensure_ascii=False, separators=(",", ":")))
        except Exception:
            self.dropped += 1

    @staticmethod
    def _safe_frames(error: BaseException) -> list[dict[str, Any]]:
        frames = []
        for frame in traceback.extract_tb(error.__traceback__, limit=16):
            filename = Path(frame.filename).name[:120]
            function = frame.name[:120]
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", filename):
                filename = "unknown"
            if not re.fullmatch(r"[A-Za-z0-9_.$<>-]+", function):
                function = "unknown"
            frames.append({"file": filename, "function": function, "line": frame.lineno})
        return frames


DIAGNOSTICS = GatewayDiagnostics()


class GatewayState:
    def __init__(self, config_path: Path, pairing_code: str | None,
                 registry_lock_path: Path | None = None,
                 broker_client: LoginBrokerClient | None = None) -> None:
        self.config_path = config_path.resolve()
        self.registry_lock_path = ((registry_lock_path.resolve()
                                    if registry_lock_path is not None else
                                    self.config_path.with_suffix(
                                        self.config_path.suffix + ".lock")))
        self.client_activity_path = self.config_path.with_name("client-activity.json")
        self.login_broker = broker_client or LoginBrokerClient()
        # Windows PowerShell 5.1 writes UTF-8 files with a BOM by default.
        loaded_registry_text = self.config_path.read_text(encoding="utf-8-sig")
        self.config = json.loads(loaded_registry_text)
        before_migration = json.dumps(
            self.config, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        try:
            schema_version = int(self.config.get("schema_version", 1))
        except (TypeError, ValueError):
            raise ValueError("Invalid Gateway configuration schema version.") from None
        if schema_version > GATEWAY_SCHEMA_VERSION:
            raise ValueError("Gateway configuration was written by a newer version.")
        # Schema 1 is the only compatibility migration.  Schema 2 already
        # has explicit grants and account mapping; 2 -> 3 only adds the
        # child-profile DTO/version and must not recreate defaults or resolve
        # a new SID.
        legacy_schema = schema_version < 2
        self.config.setdefault("listen_host", "0.0.0.0")
        self.config.setdefault("listen_port", 8785)
        self.config.setdefault("discord_bridge", "http://127.0.0.1:8765")
        self.config.setdefault("vibepollo_bridge", "http://127.0.0.1:8775")
        provider_bridge = str(self.config.get("game_provider_bridge") or
                              self.config.get("playnite_bridge") or
                              "http://127.0.0.1:8780")
        self.config.setdefault("game_provider_bridge", provider_bridge)
        self.config.setdefault("playnite_bridge", provider_bridge)
        self.config.setdefault("microphone_worker", "MoonWakerMicrophoneWorker.exe")
        self.config.setdefault("discord_audio_worker", "MoonWakerDiscordAudioWorker.exe")
        self.config.setdefault("profiles", {})
        if not isinstance(self.config["profiles"], dict):
            raise ValueError("Gateway profiles must be an object.")
        # Create the compatibility record only while migrating a schema-1
        # registry.  A schema-2 administrator may deliberately remove it;
        # restarting the Gateway must not silently resurrect that profile.
        if legacy_schema:
            self.config["profiles"].setdefault("default", {
                "discord_bridge": self.config["discord_bridge"],
                "vibepollo_bridge": self.config["vibepollo_bridge"],
                "game_provider_bridge": self.config["game_provider_bridge"],
                "playnite_bridge": self.config["playnite_bridge"],
            })
        for profile_id, profile in self.config["profiles"].items():
            if not isinstance(profile, dict):
                raise ValueError(f"Gateway profile {profile_id!r} must be an object.")
            display_name = str(
                profile.get("display_name") or profile.get("name") or profile_id
            ).strip()[:80] or str(profile_id)
            kind = str(profile.get("kind") or STANDARD_PROFILE_KIND).strip().lower()
            if kind == CHILD_PROFILE_KIND:
                if not legacy_schema and not isinstance(profile.get("enabled"), bool):
                    raise ValueError(f"Gateway child profile {profile_id!r} has invalid enabled state.")
                profile["id"] = str(profile_id)
                profile["kind"] = CHILD_PROFILE_KIND
                profile["name"] = display_name
                profile["display_name"] = display_name
                profile["parent_profile_id"] = str(
                    profile.get("parent_profile_id") or "").strip()
                profile["enabled"] = (
                    (profile.get("enabled") is True or
                     (legacy_schema and "enabled" not in profile)) and
                    not profile_deletion_pending(profile))
                allowed_game_keys = profile.get("allowed_game_keys", [])
                if not isinstance(allowed_game_keys, list):
                    raise ValueError(f"Gateway child profile {profile_id!r} has invalid game policy.")
                profile["allowed_game_keys"] = allowed_game_keys
                try:
                    profile["policy_revision"] = max(0, int(profile.get("policy_revision", 0)))
                except (TypeError, ValueError):
                    raise ValueError(f"Gateway child profile {profile_id!r} has invalid policy revision.") from None
                if "schedule" not in profile:
                    profile["schedule"] = {
                        "weekdays": {
                            day: {"enabled": False}
                            for day in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                        }
                    }
                continue
            if kind != STANDARD_PROFILE_KIND:
                raise ValueError(f"Gateway profile {profile_id!r} has an invalid kind.")
            endpoint = str(profile.get("game_provider_bridge") or
                           profile.get("playnite_bridge") or provider_bridge)
            account_name = str(
                profile.get("windows_account_name") or profile.get("owner") or ""
            ).strip()
            sid_candidates = (
                str(profile.get("windows_account_sid") or "").strip(),
                str(profile.get("owner_sid") or "").strip(),
            )
            account_sid = next((candidate for candidate in sid_candidates
                                if WINDOWS_SID_PATTERN.fullmatch(candidate)), "")
            if not account_sid:
                account_sid = resolve_windows_account_sid(account_name) \
                    if legacy_schema else ""
            mapping_resolved = bool(WINDOWS_SID_PATTERN.fullmatch(account_sid))
            profile["id"] = str(profile_id)
            profile["name"] = display_name
            profile["display_name"] = display_name
            profile["owner_sid"] = account_sid
            profile["windows_account_sid"] = account_sid
            profile["owner"] = account_name
            profile["windows_account_name"] = account_name
            profile["enabled"] = (
                (profile.get("enabled") is True or
                 (legacy_schema and "enabled" not in profile)) and
                not profile_deletion_pending(profile)
            )
            profile["profile_root"] = str(profile.get("profile_root") or "")
            profile.setdefault("discord_bridge", self.config["discord_bridge"])
            profile.setdefault("vibepollo_bridge", self.config["vibepollo_bridge"])
            profile.setdefault("game_provider_bridge", endpoint)
            profile.setdefault("playnite_bridge", endpoint)
            profile["remote_sign_in_enabled"] = (
                bool(profile.get("remote_sign_in_enabled", False)) and
                mapping_resolved and not legacy_schema and
                not profile_deletion_pending(profile))
            profile["account_mapping_status"] = (
                "resolved" if mapping_resolved else "action_required")
            profile["kind"] = STANDARD_PROFILE_KIND
            profile.pop("parent_profile_id", None)
        self._validate_profile_registry(self.config["profiles"])
        self.config.setdefault("clients", [])
        if not isinstance(self.config["clients"], list):
            raise ValueError("Gateway clients must be an array.")
        client_activity: dict[str, Any] = {}
        try:
            activity_document = json.loads(
                self.client_activity_path.read_text(encoding="utf-8-sig"))
            if isinstance(activity_document, dict) and isinstance(
                    activity_document.get("clients"), dict):
                client_activity = activity_document["clients"]
        except (OSError, TypeError, ValueError, json.JSONDecodeError):
            pass
        existing_profiles = {str(profile_id) for profile_id in self.config["profiles"]}
        for client in self.config["clients"]:
            if not isinstance(client, dict):
                raise ValueError("Gateway client records must be objects.")
            grants = ({profile_id: ["use_profile"]
                       for profile_id in sorted(existing_profiles)
                       if self._is_standard_profile(
                           self.config["profiles"].get(profile_id))}
                      if legacy_schema else client.get("profile_grants"))
            if grants is None:
                grants = {}
            elif not isinstance(grants, dict):
                grants = {}
            normalized_grants = {}
            for profile_id, permissions in grants.items():
                if (profile_id not in self.config["profiles"] or
                        not isinstance(permissions, list)):
                    continue
                allowed = PROFILE_PERMISSIONS.intersection(
                    permission for permission in permissions if isinstance(permission, str))
                profile = self.config["profiles"].get(str(profile_id), {})
                if (not self._is_standard_profile(profile) and
                        MANAGE_CHILDREN_PERMISSION in allowed):
                    allowed.remove(MANAGE_CHILDREN_PERMISSION)
                if allowed:
                    normalized_grants[str(profile_id)] = sorted(
                        allowed, key=lambda value: {
                            "use_profile": 0, "remote_sign_in": 1,
                            MANAGE_CHILDREN_PERMISSION: 2}.get(value, 9))
            client["profile_grants"] = normalized_grants
            client_id = str(client.get("id", ""))
            try:
                activity_seen = int(client_activity.get(client_id) or 0)
            except (TypeError, ValueError):
                activity_seen = 0
            client["last_seen_at"] = max(
                int(client.get("last_seen_at") or client.get("paired_at") or 0),
                activity_seen)
        self.config["schema_version"] = GATEWAY_SCHEMA_VERSION
        self.pairing_code_hash = sha256_text(pairing_code) if pairing_code else None
        self.pairing_expires_at = time.monotonic() + PAIRING_LIFETIME_SECONDS if pairing_code else 0.0
        self.pairing_control_path = self.config_path.with_name("pairing-code.json")
        self.failed_pair_attempts: dict[str, list[float]] = {}
        self.stream_pair_tickets: dict[str, dict[str, Any]] = {}
        self.vibepollo_identity_challenges: dict[str, dict[str, Any]] = {}
        self.idempotent_results: dict[str, tuple[float, int, Any]] = {}
        self.idempotent_inflight: set[str] = set()
        self.vibepollo_app_operations: dict[str, dict[str, Any]] = {}
        self.microphone_streams: dict[str, str] = {}
        self.discord_audio_streams: dict[str, str] = {}
        self.network_downloads: set[tuple[str, str]] = set()
        self.pin_failures: dict[tuple[str, str], list[float]] = {}
        self.pin_blocked_until: dict[tuple[str, str], float] = {}
        # The lease key is always (client_id, profile_id). Its session ID is
        # transient process authorization: None means a legacy five-minute
        # lease, while a UUID means the matching process session has no wall-
        # clock expiry. A new verification replaces the prior lease.
        self.pin_unlock_leases: dict[tuple[str, str], tuple[float | None, str, str | None]] = {}
        self.child_management_sessions: dict[
            tuple[str, str, str], tuple[float, str, str]] = {}
        # CP-04 management endpoints stay fail-closed until the complete
        # enforcement capability is enabled. Tests may inject this seam.
        # CP-07 keeps the rollout switch local to gateway.json. A literal JSON
        # true is required; missing, null, numeric, and string values stay
        # disabled until the Gateway is restarted.
        self.child_profiles_api_enabled = (
            self.config.get("child_profiles_enabled") is True)
        self.lock = threading.RLock()
        self.request_context = threading.local()
        self.child_usage_path = self.config_path.with_name(CHILD_TIME_USAGE_FILE)
        self.child_time_clock = None
        self.child_time_last_persist = 0.0
        self.child_time_completed: dict[str, dict[str, Any]] = {}
        self.child_time_housekeeping_stop = threading.Event()
        self.child_time_housekeeping_thread: threading.Thread | None = None
        self.child_time_housekeeping_lock = threading.Lock()
        # Share the state lock so terminal reservation and publication of a new
        # time attachment have one linearization point.
        self.child_terminal_operation = ChildTerminalOperation(self.lock)
        self.child_time_usage = self._load_child_time_usage()
        self.control_condition = threading.Condition(self.lock)
        self.control_state = self.child_time_usage["control"]
        self.provider_control_operation = ProviderControlOperation(
            self._control_provider_binding, self._control_provider_complete,
            lambda *args, **kwargs: self.proxy_json(*args, **kwargs))
        active_control = self.control_state.get("active")
        if isinstance(active_control, dict):
            active_control["state"] = "uncertain"
            active_control["reason"] = "gateway_restarted_before_terminal_result"
        self.child_time_session = self.child_time_usage.get("active_session")
        if isinstance(self.child_time_session, dict):
            # Monotonic values cannot survive a process restart.  Resume the
            # persisted session from this process' clock; the checkpointed
            # usage remains durable and the gap is bounded by the checkpoint.
            self.child_time_session = dict(self.child_time_session)
            self.child_time_session["last_monotonic"] = time.monotonic()
            self.child_time_session["restarted"] = True
        else:
            self.child_time_session = None
        # Recover the existing response index from the authoritative operation
        # journal, including a crash after complete but before session retirement.
        recovery_operations = list(
            self.control_state.get("completed", {}).values())
        if isinstance(active_control, dict) and \
                active_control.get("intent") == "hard_reset":
            recovery_operations.append(active_control)
        for operation in recovery_operations:
            result = operation.get("child_terminal_result")
            session_id = str(operation.get("child_session_id") or "")
            terminal_record = operation.get("state") == "succeeded" or \
                operation.get("intent") == "hard_reset"
            if not terminal_record or not session_id \
                    or not isinstance(result, dict):
                continue
            previous = self.child_time_completed.get(session_id)
            if previous is None or previous.get("reason") == "game_stopped":
                self.child_time_completed[session_id] = dict(result)
            active = self.child_time_session
            if isinstance(active, dict) and active.get("session_id") == session_id \
                    and active.get("terminal_reason") \
                    and self.control_state.get("active") is None \
                    and operation.get("generation") == self.control_state.get("generation") \
                    and all(active.get(key) == result.get(key) for key in (
                        "client_id", "actor_profile_id", "execution_profile_id", "game_id")):
                self.child_time_session = None
                self.child_time_usage["active_session"] = None
        if self.control_state.get("active") is None \
                and self._control_reconcile_verified_provider_stop_locked():
            self._control_save_locked()
        self.runtime_status_path = self.config_path.with_name("runtime-status.json")
        self.gateway_runtime_path = self.config_path.with_name("gateway-runtime.json")
        self.started_at = int(time.time())
        self.version_info = self._load_version_info()
        self.last_runtime_profile = ""
        self.last_runtime_write = 0.0
        after_migration = json.dumps(
            self.config, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        if after_migration != before_migration:
            self.save(expected_registry_text=loaded_registry_text)

    @staticmethod
    def _is_standard_profile(profile: Any) -> bool:
        return (isinstance(profile, dict) and
                str(profile.get("kind") or STANDARD_PROFILE_KIND).strip().lower() ==
                STANDARD_PROFILE_KIND)

    @staticmethod
    def _own_child_count(profiles: Any, parent_profile_id: str) -> int:
        if not isinstance(profiles, dict):
            return 0
        parent_id = str(parent_profile_id or "").strip()
        return sum(1 for profile in profiles.values()
                   if isinstance(profile, dict) and
                   str(profile.get("kind") or STANDARD_PROFILE_KIND).strip().lower() ==
                   CHILD_PROFILE_KIND and
                   str(profile.get("parent_profile_id") or "").strip() == parent_id)

    @staticmethod
    def _validate_profile_registry(profiles: Any) -> None:
        if not isinstance(profiles, dict):
            raise ValueError("Gateway profiles must be an object.")
        for profile_id, profile in profiles.items():
            profile_key = str(profile_id)
            if (not PROFILE_ID_PATTERN.fullmatch(profile_key) or
                    not isinstance(profile, dict) or
                    str(profile.get("id") or profile_key) != profile_key):
                raise ValueError(f"Gateway profile {profile_key!r} has an invalid identity.")
            kind = str(profile.get("kind") or STANDARD_PROFILE_KIND).strip().lower()
            if kind == STANDARD_PROFILE_KIND:
                if profile.get("parent_profile_id") not in (None, ""):
                    raise ValueError(f"Standard profile {profile_key!r} cannot have a parent.")
                parent_revision = profile.get(CHILD_PARENT_POLICY_REVISION_FIELD, 0)
                if (isinstance(parent_revision, bool) or
                        not isinstance(parent_revision, int) or parent_revision < 0):
                    raise ValueError(
                        f"Standard profile {profile_key!r} has an invalid child policy revision.")
                requests = profile.get(CHILD_SHARING_REQUESTS_FIELD, {})
                if not isinstance(requests, dict):
                    raise ValueError(
                        f"Standard profile {profile_key!r} has invalid child sharing requests.")
                continue
            if kind != CHILD_PROFILE_KIND:
                raise ValueError(f"Gateway profile {profile_key!r} has an invalid kind.")
            if not isinstance(profile.get("id"), str) or profile["id"] != profile_key:
                raise ValueError(f"Gateway child profile {profile_key!r} has an invalid identity.")
            parent_id = str(profile.get("parent_profile_id") or "").strip()
            if not PROFILE_ID_PATTERN.fullmatch(parent_id):
                raise ValueError(f"Gateway child profile {profile_key!r} has an invalid parent.")
            parent = profiles.get(parent_id)
            if parent is not None and not GatewayState._is_standard_profile(parent):
                raise ValueError(f"Gateway child profile {profile_key!r} points to a non-parent profile.")
            if not isinstance(profile.get("enabled"), bool):
                raise ValueError(f"Gateway child profile {profile_key!r} has an invalid enabled state.")
            allowed_game_keys = profile.get("allowed_game_keys")
            if (not isinstance(allowed_game_keys, list) or
                    any(not isinstance(value, str) or
                        not CHILD_GAME_KEY_PATTERN.fullmatch(value) or
                        not value.startswith(parent_id + "/")
                        for value in allowed_game_keys)):
                raise ValueError(f"Gateway child profile {profile_key!r} has an invalid game policy.")
            policy_revision = profile.get("policy_revision", 0)
            if (isinstance(policy_revision, bool) or
                    not isinstance(policy_revision, int) or policy_revision < 0):
                raise ValueError(f"Gateway child profile {profile_key!r} has an invalid policy revision.") from None
            # A child is an actor only.  Any executable identity or secret is
            # rejected instead of being silently treated as a parent.
            forbidden = {
                "windows_account_sid", "owner_sid", "windows_account_name", "owner",
                "profile_root", "discord_bridge", "vibepollo_bridge",
                "game_provider_bridge", "playnite_bridge", "integration_token",
                "pin_verifier", "account_mapping_status", "reservation_nonce",
                "remote_sign_in_enabled",
                "ratings", "minimum_age", "age_rating", "pegi", "esrb",
            }
            if forbidden.intersection(profile):
                raise ValueError(f"Gateway child profile {profile_key!r} contains execution identity data.")

    @staticmethod
    def _empty_child_time_usage() -> dict[str, Any]:
        return {
            "schema_version": CHILD_USAGE_SCHEMA_VERSION,
            "usage_revision": 0,
            "days": {},
            "active_session": None,
            "control": {"generation": 0, "actor_lease_epoch": 0,
                        "provider_sequence": 0,
                        "active": None, "stream": None, "completed": {}},
        }

    def _load_child_time_usage(self) -> dict[str, Any]:
        try:
            loaded = json.loads(self.child_usage_path.read_text(encoding="utf-8-sig"))
        except FileNotFoundError:
            return self._empty_child_time_usage()
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise ValueError("Child time usage store is unavailable.") from error
        if not isinstance(loaded, dict) or loaded.get("schema_version") != CHILD_USAGE_SCHEMA_VERSION:
            raise ValueError("Child time usage store has an unsupported schema.")
        revision = loaded.get("usage_revision", 0)
        if (isinstance(revision, bool) or not isinstance(revision, int) or revision < 0):
            raise ValueError("Child time usage store has an invalid revision.")
        days = loaded.get("days")
        if not isinstance(days, dict):
            raise ValueError("Child time usage store has invalid days.")
        for actor_id, actor_days in days.items():
            if (not PROFILE_ID_PATTERN.fullmatch(str(actor_id)) or
                    not isinstance(actor_days, dict)):
                raise ValueError("Child time usage store has invalid actor data.")
            for day_key, used in actor_days.items():
                if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", str(day_key)):
                    raise ValueError("Child time usage store has an invalid day.")
                if (isinstance(used, bool) or not isinstance(used, (int, float)) or
                        used < 0 or used != used):
                    raise ValueError("Child time usage store has invalid usage.")
        active = loaded.get("active_session")
        if active is not None and not isinstance(active, dict):
            raise ValueError("Child time usage store has an invalid active session.")
        control = loaded.get("control")
        if control is None:
            control = self._empty_child_time_usage()["control"]
        if (not isinstance(control, dict) or
                isinstance(control.get("generation", 0), bool) or
                not isinstance(control.get("generation", 0), int) or
                int(control.get("generation", 0)) < 0 or
                isinstance(control.get("actor_lease_epoch", 0), bool) or
                not isinstance(control.get("actor_lease_epoch", 0), int) or
                int(control.get("actor_lease_epoch", 0)) < 0 or
                isinstance(control.get("provider_sequence", 0), bool) or
                not isinstance(control.get("provider_sequence", 0), int) or
                int(control.get("provider_sequence", 0)) < 0 or
                control.get("active") is not None and
                not isinstance(control.get("active"), dict) or
                control.get("stream") is not None and
                not isinstance(control.get("stream"), dict) or
                not isinstance(control.get("completed", {}), dict)):
            raise ValueError("Child time usage store has invalid control state.")
        return {
            "schema_version": CHILD_USAGE_SCHEMA_VERSION,
            "usage_revision": revision,
            "days": days,
            "active_session": dict(active) if isinstance(active, dict) else None,
            "control": control,
        }

    def _save_child_time_usage_locked(self) -> None:
        """Atomically checkpoint Gateway-owned child usage and active identity."""
        active = self.child_time_session
        if isinstance(active, dict):
            persisted = {key: value for key, value in active.items()
                         if key not in {"last_monotonic", "restarted"}}
        else:
            persisted = None
        self.child_time_usage["active_session"] = persisted
        payload = json.dumps(self.child_time_usage, ensure_ascii=False,
                             sort_keys=True, separators=(",", ":")) + "\n"
        temporary = self.child_usage_path.with_name(
            self.child_usage_path.name + "." + uuid.uuid4().hex + ".tmp")
        try:
            with temporary.open("w", encoding="utf-8", newline="\n") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.child_usage_path)
            self.child_time_last_persist = time.monotonic()
        finally:
            try:
                if temporary.exists():
                    temporary.unlink()
            except OSError:
                pass

    def _child_time_bump_revision_locked(self) -> int:
        revision = int(self.child_time_usage.get("usage_revision", 0)) + 1
        self.child_time_usage["usage_revision"] = revision
        return revision

    def _child_time_zone(self, profile: dict[str, Any] | None = None) -> Any:
        schedule = profile.get("schedule") if isinstance(profile, dict) else None
        configured = (schedule.get("timezone") if isinstance(schedule, dict) else None) \
            or (profile.get("timezone") if isinstance(profile, dict) else None) \
            or self.config.get("timezone")
        if isinstance(configured, str) and configured.strip() and ZoneInfo is not None:
            try:
                return ZoneInfo(configured.strip())
            except (KeyError, ValueError):
                pass
        local = datetime.now().astimezone().tzinfo
        return local if local is not None else timezone.utc

    def _child_time_now(self, profile: dict[str, Any] | None = None) -> tuple[float, datetime]:
        clock = getattr(self, "child_time_clock", None)
        if callable(clock):
            value = clock()
            if not isinstance(value, (tuple, list)) or len(value) != 2:
                raise ValueError("Child time clock must return monotonic and local time.")
            monotonic = float(value[0])
            wall = value[1]
            if not isinstance(wall, datetime):
                raise ValueError("Child time clock returned an invalid local time.")
            if wall.tzinfo is None:
                wall = wall.replace(tzinfo=self._child_time_zone(profile))
            return monotonic, wall
        zone = self._child_time_zone(profile)
        return time.monotonic(), datetime.now(zone)

    @staticmethod
    def _child_time_parse_clock(value: Any, default: int) -> int:
        if value is None:
            return default
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("Invalid child schedule time.")
        minutes = value
        if minutes < 0 or minutes > 24 * 60:
            raise ValueError("Invalid child schedule time.")
        return minutes

    @staticmethod
    def _child_time_day_name(day: date) -> str:
        return ("mon", "tue", "wed", "thu", "fri", "sat", "sun")[day.weekday()]

    def _child_time_schedule_entry(self, profile: dict[str, Any], day: date) -> dict[str, Any] | None:
        schedule = profile.get("schedule")
        if not isinstance(schedule, dict):
            return None
        weekdays = schedule.get("weekdays")
        if not isinstance(weekdays, dict):
            weekdays = schedule
        entry = weekdays.get(self._child_time_day_name(day))
        return entry if isinstance(entry, dict) else None

    def _child_time_window(self, profile: dict[str, Any], wall: datetime) -> dict[str, Any]:
        zone = wall.tzinfo or self._child_time_zone(profile)
        day = wall.date()

        def at(day_value: date, minutes: int) -> datetime:
            if minutes == 24 * 60:
                return datetime.combine(day_value + timedelta(days=1),
                                        datetime_time.min, tzinfo=zone)
            return datetime.combine(
                day_value, datetime_time(minutes // 60, minutes % 60), tzinfo=zone)

        entry = self._child_time_schedule_entry(profile, day)
        if entry and entry.get("enabled") is True:
            start_minutes = self._child_time_parse_clock(
                entry.get("start_minute"), 0)
            end_minutes = self._child_time_parse_clock(
                entry.get("end_minute"), 24 * 60)
            if end_minutes <= start_minutes:
                raise ValueError("Child schedule window must end after it starts.")
            start = at(day, start_minutes)
            end = at(day, end_minutes)
            if start <= wall < end:
                return {"active": True, "start": start, "end": end,
                        "entry": entry}
        future = []
        for offset in range(0, 8):
            candidate_day = day + timedelta(days=offset)
            entry = self._child_time_schedule_entry(profile, candidate_day)
            if not entry or entry.get("enabled") is not True:
                continue
            start_minutes = self._child_time_parse_clock(
                entry.get("start_minute"), 0)
            start = at(candidate_day, start_minutes)
            if start > wall:
                future.append(start)
        next_allowed = min(future) if future else None
        return {"active": False, "start": None, "end": None,
                "entry": None, "next_allowed": next_allowed}

    @staticmethod
    def _child_time_daily_budget_seconds(profile: dict[str, Any],
                                         entry: dict[str, Any] | None) -> float:
        if not isinstance(entry, dict) or entry.get("enabled") is not True:
            return 0.0
        raw = entry.get("daily_limit_seconds")
        if raw is None:
            return 0.0
        if isinstance(raw, bool):
            raise ValueError("Invalid child daily time budget.")
        try:
            amount = float(raw)
        except (TypeError, ValueError):
            raise ValueError("Invalid child daily time budget.") from None
        if amount != amount or amount < 0 or amount > 7 * 24 * 60 * 60:
            raise ValueError("Invalid child daily time budget.")
        return amount

    def _child_time_usage_seconds(self, actor_id: str, day_key: str) -> float:
        days = self.child_time_usage.setdefault("days", {})
        actor_days = days.setdefault(actor_id, {})
        try:
            return max(0.0, float(actor_days.get(day_key, 0.0)))
        except (TypeError, ValueError):
            raise ValueError("Child time usage store has invalid usage.") from None

    def _child_time_policy_snapshot(self, actor_id: str, profile: dict[str, Any],
                                    wall: datetime) -> dict[str, Any]:
        day_key = wall.date().isoformat()
        window = self._child_time_window(profile, wall)
        budget = self._child_time_daily_budget_seconds(
            profile, window.get("entry"))
        used = self._child_time_usage_seconds(actor_id, day_key)
        remaining = max(0.0, budget - used)
        window_remaining = max(0.0, window["end"].timestamp() - wall.timestamp()) \
            if window.get("active") else 0.0
        playable = min(remaining, window_remaining)
        reason = "none"
        if not window.get("active"):
            reason = "outside_schedule"
        elif remaining <= 0:
            reason = "daily_limit_reached"
        return {
            "day_key": day_key,
            "weekday": self._child_time_day_name(wall.date()),
            "window_start": window["start"].isoformat() if window.get("start") else "",
            "window_end": window["end"].isoformat() if window.get("end") else "",
            "next_allowed_at": window.get("next_allowed").isoformat()
            if window.get("next_allowed") else "",
            "budget_seconds": budget,
            "used_seconds": used,
            "remaining_daily_seconds": remaining,
            "playable_now_seconds": playable,
            "reason": reason,
        }

    @property
    def base_dir(self) -> Path:
        return self.config_path.parent

    def _load_version_info(self) -> dict[str, Any]:
        fallback = {"product": "MoonWaker Host", "version": "unknown", "build": "unknown"}
        try:
            value = json.loads((self.base_dir / "version.json").read_text(encoding="utf-8-sig"))
            if isinstance(value, dict):
                fallback.update({key: str(value[key]) for key in ("product", "version", "build")
                                 if value.get(key) is not None})
                fallback["protocol_version"] = int(value.get("protocol_version", 1))
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            pass
        return fallback

    def runtime_info(self) -> dict[str, Any]:
        source = Path(__file__).resolve()
        try:
            source_sha256 = hashlib.sha256(source.read_bytes()).hexdigest()
        except OSError:
            source_sha256 = ""
        return {
            **self.version_info,
            "pid": os.getpid(),
            "started_at": self.started_at,
            "source_sha256": source_sha256,
        }

    def write_runtime_info(self) -> None:
        temporary = self.gateway_runtime_path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(self.runtime_info(), ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
        os.replace(temporary, self.gateway_runtime_path)

    def path_from_config(self, key: str) -> Path:
        value = Path(str(self.config[key]))
        return value if value.is_absolute() else (self.base_dir / value).resolve()

    def save(self, expected_registry_text: str | None = None) -> None:
        with self.registry_update_lock():
            if (expected_registry_text is not None and
                    self.config_path.read_text(encoding="utf-8-sig") !=
                    expected_registry_text):
                raise RuntimeError(
                    "Gateway configuration changed during migration; restart the Gateway.")
            self.replace_registry(self.config)

    @contextlib.contextmanager
    def registry_update_lock(self):
        """Serialize registry replacement with local installers/configurators."""
        handle = None
        deadline = time.monotonic() + 10.0
        while handle is None:
            try:
                handle = self.registry_lock_path.open("a+b")
                if handle.seek(0, os.SEEK_END) < 1:
                    handle.write(b"\0")
                    handle.flush()
                handle.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except (OSError, BlockingIOError):
                if handle is not None:
                    handle.close()
                    handle = None
                if time.monotonic() >= deadline:
                    raise TimeoutError("Gateway registry is busy.") from None
                time.sleep(0.05)
        try:
            yield
        finally:
            try:
                handle.seek(0)
                if os.name == "nt":
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            finally:
                handle.close()

    def current_registry(self) -> dict[str, Any]:
        current = json.loads(self.config_path.read_text(encoding="utf-8-sig"))
        if (not isinstance(current, dict) or
                not isinstance(current.get("profiles"), dict) or
                not isinstance(current.get("clients"), list)):
            raise ValueError("Gateway configuration registry is malformed.")
        try:
            schema_version = int(current.get("schema_version", 0))
        except (TypeError, ValueError):
            raise ValueError("Invalid Gateway configuration schema version.") from None
        if schema_version != GATEWAY_SCHEMA_VERSION:
            raise ValueError("Gateway configuration schema changed while running.")
        self._validate_profile_registry(current["profiles"])
        return current

    def replace_registry(self, registry: dict[str, Any]) -> None:
        temporary = self.config_path.with_suffix(self.config_path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, self.config_path)
        self.registry_modified_ns = self.config_path.stat().st_mtime_ns

    def refresh_registry_if_changed(self) -> None:
        if (getattr(self, "registry_modified_ns", None) ==
                self.config_path.stat().st_mtime_ns):
            return
        with self.registry_update_lock():
            current = self.current_registry()
            activity_clients: dict[str, Any] = {}
            try:
                activity = json.loads(
                    self.client_activity_path.read_text(encoding="utf-8-sig"))
                if isinstance(activity, dict) and isinstance(activity.get("clients"), dict):
                    activity_clients = activity["clients"]
            except (OSError, TypeError, ValueError, json.JSONDecodeError):
                pass
            for client in current["clients"]:
                if not isinstance(client, dict):
                    raise ValueError("Gateway client records must be objects.")
                client_id = str(client.get("id", ""))
                try:
                    activity_seen = int(activity_clients.get(client_id) or 0)
                except (TypeError, ValueError):
                    activity_seen = 0
                client["last_seen_at"] = max(
                    int(client.get("last_seen_at") or client.get("paired_at") or 0),
                    activity_seen)
            self.config = current
            self.registry_modified_ns = self.config_path.stat().st_mtime_ns

    def persist_client_last_seen(self, client: dict[str, Any], seen_at: int) -> None:
        """Persist activity separately so it cannot race security-policy edits."""
        client_id = str(client.get("id", ""))
        if not client_id:
            return
        try:
            activity = {"schema_version": 1, "clients": {}}
            if self.client_activity_path.exists():
                loaded = json.loads(
                    self.client_activity_path.read_text(encoding="utf-8-sig"))
                if isinstance(loaded, dict) and isinstance(loaded.get("clients"), dict):
                    activity = loaded
            activity["schema_version"] = 1
            activity["clients"][client_id] = seen_at
            temporary = self.client_activity_path.with_suffix(".json.tmp")
            temporary.write_text(
                json.dumps(activity, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            os.replace(temporary, self.client_activity_path)
        except (OSError, TypeError, ValueError, json.JSONDecodeError):
            # Activity tracking must never make an otherwise valid request fail.
            return

    def client_for_token(self, token: str) -> dict[str, Any] | None:
        digest = sha256_text(token)
        with self.lock:
            try:
                self.refresh_registry_if_changed()
            except (OSError, TimeoutError, TypeError, ValueError, json.JSONDecodeError):
                return None
            for client in self.config["clients"]:
                if secrets.compare_digest(str(client.get("token_sha256", "")), digest):
                    now = int(time.time())
                    if now - int(client.get("last_seen_at") or 0) >= \
                            CLIENT_LAST_SEEN_WRITE_SECONDS:
                        client["last_seen_at"] = now
                        self.persist_client_last_seen(client, now)
                    return client
        return None

    def refresh_pairing_control(self) -> None:
        """Loads a short-lived code activated by the local pairing helper."""
        try:
            control = json.loads(self.pairing_control_path.read_text(encoding="utf-8-sig"))
            expires_at = int(control.get("expires_at", 0))
            digest = str(control.get("code_sha256", ""))
            remaining = expires_at - int(time.time())
            if remaining <= 0 or not re.fullmatch(r"[0-9a-f]{64}", digest):
                return
            with self.lock:
                self.pairing_code_hash = digest
                self.pairing_expires_at = time.monotonic() + min(
                    remaining, PAIRING_LIFETIME_SECONDS)
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            return

    def pairing_active(self) -> bool:
        self.refresh_pairing_control()
        return self.pairing_code_hash is not None and time.monotonic() <= self.pairing_expires_at

    def pairing_allowed(self, address: str) -> bool:
        now = time.monotonic()
        with self.lock:
            recent = [attempt for attempt in self.failed_pair_attempts.get(address, []) if now - attempt < 60]
            self.failed_pair_attempts[address] = recent
            return len(recent) < 5

    def pair(self, address: str, code: str, client_name: str) -> dict[str, Any]:
        self.refresh_pairing_control()
        now = time.monotonic()
        if not self.pairing_code_hash or now > self.pairing_expires_at:
            raise PermissionError("Pairing is not active. Restart the gateway with a new pairing code.")
        if not self.pairing_allowed(address):
            raise PermissionError("Too many pairing attempts. Try again in one minute.")
        if not secrets.compare_digest(self.pairing_code_hash, sha256_text(code)):
            with self.lock:
                self.failed_pair_attempts.setdefault(address, []).append(now)
            raise PermissionError("Invalid pairing code.")

        token = secrets.token_urlsafe(32)
        client_id = secrets.token_hex(12)
        paired_at = int(time.time())
        with self.lock:
            with self.registry_update_lock():
                current = self.current_registry()
                record = {
                    "id": client_id,
                    "name": client_name[:80] or "Android TV",
                    "token_sha256": sha256_text(token),
                    "paired_at": paired_at,
                    "last_seen_at": paired_at,
                    "profile_grants": {
                        str(profile_id): ["use_profile"]
                        for profile_id, profile in sorted(current["profiles"].items())
                        if (self._is_standard_profile(profile) and
                            profile.get("enabled") is True and
                            not profile_deletion_pending(profile))
                    },
                }
                current["clients"].append(record)
                self.replace_registry(current)
                self.config = current
        profiles = []
        for profile_id in record["profile_grants"]:
            profile = current["profiles"][profile_id]
            profiles.append({
                "id": profile_id,
                "name": str(profile.get("name") or profile.get("display_name") or
                            profile_id)[:80],
                "permissions": {"use_profile": True, "remote_sign_in": False,
                                 "manage_children": False},
                "own_children_count": self._own_child_count(
                    current.get("profiles"), profile_id),
            })
        ticket = self.issue_stream_pair_ticket(address, client_id)
        return {
            "client_id": client_id,
            "token": token,
            "stream_pair_ticket": ticket,
            "stream_pair_expires_seconds": STREAM_PAIR_TICKET_LIFETIME_SECONDS,
            "profiles": profiles,
            "suggested_profile_id": profiles[0]["id"] if len(profiles) == 1 else "",
        }

    def issue_stream_pair_ticket(self, address: str, client_id: str) -> str:
        ticket = secrets.token_urlsafe(24)
        digest = sha256_text(ticket)
        now = time.monotonic()
        with self.lock:
            self.stream_pair_tickets = {
                key: value for key, value in self.stream_pair_tickets.items()
                if float(value.get("expires_at", 0)) >= now and not (
                    str(value.get("client_id", "")) == client_id and
                    str(value.get("address", "")) == address)
            }
            self.stream_pair_tickets[digest] = {
                "client_id": client_id,
                "address": address,
                "expires_at": now + STREAM_PAIR_TICKET_LIFETIME_SECONDS,
                "in_use": False,
            }
        return ticket

    def stream_pair_ticket_for_client(self, address: str, client: dict[str, Any]) -> dict[str, Any]:
        ticket = self.issue_stream_pair_ticket(address, str(client.get("id", "")))
        return {
            "stream_pair_ticket": ticket,
            "stream_pair_expires_seconds": STREAM_PAIR_TICKET_LIFETIME_SECONDS,
        }

    def _prune_vibepollo_identity_challenges(self, now: float | None = None) -> None:
        current = time.monotonic() if now is None else float(now)
        self.vibepollo_identity_challenges = {
            key: value for key, value in self.vibepollo_identity_challenges.items()
            if float(value.get("expires_at", 0.0)) >= current and
            not bool(value.get("used", False))
        }

    def vibepollo_identity_challenge(self, address: str,
                                     client: dict[str, Any],
                                     actor_profile_id: str,
                                     request_id: str) -> tuple[int, dict[str, Any]]:
        request_id = str(request_id or "").strip()
        actor_profile_id = str(actor_profile_id or "").strip()
        execution_profile_id = self.execution_profile_id
        client_id = self.pin_client_key(client)
        if (not REQUEST_ID_PATTERN.fullmatch(request_id) or
                not PROFILE_ID_PATTERN.fullmatch(actor_profile_id) or
                not PROFILE_ID_PATTERN.fullmatch(execution_profile_id) or
                not client_id or not address):
            raise PermissionError("identity_proof_invalid")
        expires_wall = int(time.time()) + VIBEPOOLLO_IDENTITY_CHALLENGE_LIFETIME_SECONDS
        challenge = "moonwaker-vibepollo-identity-v1\n" + "\n".join((
            secrets.token_urlsafe(32), client_id, str(address),
            actor_profile_id, execution_profile_id, str(expires_wall)))
        if any(ord(character) > 0x7f for character in challenge):
            raise PermissionError("identity_proof_invalid")
        challenge_id = secrets.token_urlsafe(32)
        expires_at = time.monotonic() + VIBEPOOLLO_IDENTITY_CHALLENGE_LIFETIME_SECONDS
        with self.lock:
            self._prune_vibepollo_identity_challenges()
            self.vibepollo_identity_challenges = {
                key: value for key, value in self.vibepollo_identity_challenges.items()
                if not (str(value.get("client_id") or "") == client_id and
                        str(value.get("address") or "") == str(address) and
                        str(value.get("actor_profile_id") or "") == actor_profile_id and
                        str(value.get("execution_profile_id") or "") == execution_profile_id)
            }
            while len(self.vibepollo_identity_challenges) >= 256:
                self.vibepollo_identity_challenges.pop(
                    next(iter(self.vibepollo_identity_challenges)))
            self.vibepollo_identity_challenges[challenge_id] = {
                "client_id": client_id,
                "address": str(address),
                "actor_profile_id": actor_profile_id,
                "execution_profile_id": execution_profile_id,
                "request_id": request_id,
                "challenge": challenge,
                "expires_at": expires_at,
                "used": False,
            }
        return HTTPStatus.CREATED, {
            "ok": True,
            "challenge_id": challenge_id,
            "challenge": challenge,
            "expires_seconds": VIBEPOOLLO_IDENTITY_CHALLENGE_LIFETIME_SECONDS,
        }

    def vibepollo_identity_bind(self, address: str,
                                client: dict[str, Any],
                                body: dict[str, Any],
                                actor_profile_id: str,
                                request_id: str) -> tuple[int, dict[str, Any]]:
        challenge_id = str(body.get("challenge_id") or "").strip()
        fingerprint = str(body.get("certificate_sha256") or "")
        signature_text = str(body.get("signature") or "")
        actor_profile_id = str(actor_profile_id or "").strip()
        execution_profile_id = self.execution_profile_id
        client_id = self.pin_client_key(client)
        if (not VIBEPOOLLO_IDENTITY_CHALLENGE_ID_PATTERN.fullmatch(challenge_id) or
                not VIBEPOOLLO_IDENTITY_CERT_PATTERN.fullmatch(fingerprint) or
                len(signature_text) > 1400):
            return HTTPStatus.BAD_REQUEST, {
                "ok": False, "error": "identity_proof_invalid",
                "reason": "identity_proof_invalid"}
        try:
            signature = base64.b64decode(signature_text, validate=True)
        except (TypeError, ValueError):
            signature = b""
        if (not signature or len(signature) > VIBEPOOLLO_IDENTITY_SIGNATURE_MAX_BYTES or
                not REQUEST_ID_PATTERN.fullmatch(str(request_id or "").strip())):
            return HTTPStatus.BAD_REQUEST, {
                "ok": False, "error": "identity_proof_invalid",
                "reason": "identity_proof_invalid"}
        with self.lock:
            self._prune_vibepollo_identity_challenges()
            record = self.vibepollo_identity_challenges.get(challenge_id)
            if (not isinstance(record, dict) or bool(record.get("used", False)) or
                    float(record.get("expires_at", 0.0)) < time.monotonic()):
                return HTTPStatus.GONE, {
                    "ok": False, "error": "identity_challenge_expired",
                    "reason": "identity_challenge_expired"}
            if (str(record.get("client_id") or "") != client_id or
                    str(record.get("address") or "") != str(address) or
                    str(record.get("actor_profile_id") or "") != actor_profile_id or
                    str(record.get("execution_profile_id") or "") != execution_profile_id):
                return HTTPStatus.FORBIDDEN, {
                    "ok": False, "error": "identity_proof_invalid",
                    "reason": "identity_proof_invalid"}
            record["used"] = True
            challenge = str(record.get("challenge") or "")
        ok, result = self.proxy_json(
            "vibepollo", "/identity/verify", {
                "challenge": challenge,
                "certificate_sha256": fingerprint,
                "signature": signature_text,
            }, timeout=12.0, profile_id=execution_profile_id)
        if (not ok or not isinstance(result, dict) or
                result.get("ok") is not True):
            explicit_reason = str(result.get("reason") or "") \
                if isinstance(result, dict) else ""
            error_text = str(result.get("error") or "") \
                if isinstance(result, dict) else ""
            try:
                upstream_status = int(result.get("status", 0) or 0) \
                    if isinstance(result, dict) else 0
            except (TypeError, ValueError):
                upstream_status = 0
            allowed = {"pairing_required", "identity_ambiguous",
                       "identity_proof_invalid", "identity_binding_unsupported"}
            if explicit_reason in allowed:
                reason = explicit_reason
            elif upstream_status == 404 or \
                    "unknown endpoint" in error_text.casefold():
                reason = "identity_binding_unsupported"
            elif error_text == "identity_binding_unsupported":
                reason = error_text
            else:
                reason = "identity_binding_unavailable"
            if reason in {"pairing_required", "identity_ambiguous"}:
                status = HTTPStatus.CONFLICT
            elif reason == "identity_binding_unavailable":
                status = HTTPStatus.SERVICE_UNAVAILABLE
            else:
                status = HTTPStatus.BAD_GATEWAY
            return status, {"ok": False, "error": reason, "reason": reason}
        client_uuid = str(result.get("client_uuid") or "").strip()
        if not CHILD_VIBEPOOLLO_UUID_PATTERN.fullmatch(client_uuid):
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False, "error": "identity_binding_unsupported",
                "reason": "identity_binding_unsupported"}
        binding_profile_id = execution_profile_id
        try:
            self._persist_vibepollo_client_uuid(
                client_id, binding_profile_id, client_uuid, client,
                reject_conflict=True)
        except (PermissionError, OSError, ValueError):
            return HTTPStatus.CONFLICT, {
                "ok": False, "error": "identity_binding_conflict",
                "reason": "identity_binding_conflict"}
        return HTTPStatus.OK, {"ok": True, "bound": True}

    def vibepollo_pair_client(self, address: str, client: dict[str, Any],
                              ticket: str, body: dict[str, Any]) -> tuple[int, Any]:
        pin = str(body.get("pin", "")).strip()
        name = str(body.get("name", "")).strip()
        if not re.fullmatch(r"[0-9]{4}", pin):
            raise ValueError("The Moonlight pairing PIN must contain four digits.")
        if not name or len(name) > 80 or re.search(r"[\x00-\x1f\x7f]", name):
            raise ValueError("Invalid Moonlight client name.")
        digest = sha256_text(str(ticket or ""))
        now = time.monotonic()
        with self.lock:
            grant = self.stream_pair_tickets.get(digest)
            if not grant or float(grant.get("expires_at", 0)) < now:
                raise PermissionError("The stream pairing ticket expired.")
            if grant.get("in_use"):
                raise PermissionError("The stream pairing ticket is already in use.")
            if (str(grant.get("client_id", "")) != str(client.get("id", "")) or
                    str(grant.get("address", "")) != address):
                raise PermissionError("The stream pairing ticket belongs to another client.")
            grant["in_use"] = True
        ok, result = self.proxy_json(
            "vibepollo", "/pair", {"pin": pin, "name": name}, timeout=50.0)
        with self.lock:
            current = self.stream_pair_tickets.get(digest)
            if ok:
                self.stream_pair_tickets.pop(digest, None)
            elif current:
                current["in_use"] = False
        if not ok:
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False,
                "stage": "vibepollo_bridge",
                "reason": "vibepollo_pairing_failed",
                "error": "Vibepollo Bridge pairing failed: " + self.upstream_error(
                    result, "The bridge did not return an error description."),
            }
        if not isinstance(result, dict):
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False,
                "error": "Vibepollo returned an invalid pairing response.",
            }
        client_uuid = str(result.get("client_uuid", "")).strip()
        if not CHILD_VIBEPOOLLO_UUID_PATTERN.fullmatch(client_uuid):
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False,
                "error": "Vibepollo did not return a usable client identity.",
            }
        binding_profile_id = (self.execution_profile_id
                              if self.is_child_profile(self.actor_profile_id)
                              else self.profile_id)
        self._persist_vibepollo_client_uuid(
            self.pin_client_key(client), binding_profile_id, client_uuid, client)
        return HTTPStatus.OK, {
            "ok": True,
            "client_uuid": client_uuid,
            "permissions": int(result.get("permissions", 0)),
        }

    def _persist_vibepollo_client_uuid(self, client_id: str,
                                       execution_profile_id: str,
                                       client_uuid: str,
                                       client_record: dict[str, Any] | None = None,
                                       reject_conflict: bool = False) -> None:
        """Bind a paired Gateway client to the exact Vibepollo client UUID."""
        if (not client_id or not PROFILE_ID_PATTERN.fullmatch(str(execution_profile_id)) or
                not CHILD_VIBEPOOLLO_UUID_PATTERN.fullmatch(client_uuid)):
            raise PermissionError("The paired client identity is unavailable.")
        with self.lock:
            with self.registry_update_lock():
                current = self.current_registry()
                target = next((value for value in current["clients"]
                               if isinstance(value, dict) and
                               str(value.get("id") or "") == client_id), None)
                if target is None:
                    raise PermissionError("The paired Gateway client no longer exists.")
                bindings = target.get("vibepollo_client_uuids")
                if not isinstance(bindings, dict):
                    bindings = {}
                existing = str(bindings.get(str(execution_profile_id)) or "").strip()
                if reject_conflict and existing and existing != client_uuid:
                    raise PermissionError("identity_binding_conflict")
                bindings[str(execution_profile_id)] = client_uuid
                target["vibepollo_client_uuids"] = bindings
                self.replace_registry(current)
                self.config = current
                if isinstance(client_record, dict):
                    client_record["vibepollo_client_uuids"] = dict(bindings)

    def resolve_profile_id(self, profile_id: str | None,
                           client: dict[str, Any] | None = None) -> str:
        selected = str(profile_id or "default").strip() or "default"
        if not PROFILE_ID_PATTERN.fullmatch(selected):
            raise ValueError("Invalid integration profile ID.")
        profiles = self.config.get("profiles", {})
        if selected == "default" and selected not in profiles:
            grants = client.get("profile_grants", {}) if client else None
            available = [
                str(candidate) for candidate, profile in profiles.items()
                if (self._is_standard_profile(profile) and profile.get("enabled") is True and
                    not profile_deletion_pending(profile) and
                    (grants is None or "use_profile" in grants.get(str(candidate), [])))
            ]
            if len(available) == 1:
                selected = available[0]
        return selected

    def select_profile(self, profile_id: str | None, record_use: bool = False) -> str:
        selected = self.resolve_profile_id(profile_id)
        profiles = self.config.get("profiles", {})
        if selected not in profiles:
            raise ValueError(f"Unknown integration profile: {selected}")
        profile = profiles[selected]
        if (not isinstance(profile, dict) or profile.get("enabled") is not True or
                profile_deletion_pending(profile) or not self._is_standard_profile(profile)):
            raise PermissionError("The requested integration profile is unavailable.")
        self.request_context.profile_id = selected
        self.request_context.actor_profile_id = selected
        self.request_context.execution_profile_id = selected
        if record_use:
            self.record_profile_use(selected)
        return selected

    def authorize_profile(self, client: dict[str, Any], profile_id: str | None,
                          required_permission: str = "use_profile",
                          record_use: bool = False, allow_child: bool = False) -> str:
        if required_permission not in PROFILE_PERMISSIONS:
            raise ValueError("Invalid profile permission.")
        selected = self.resolve_profile_id(profile_id, client)
        profile = self.config.get("profiles", {}).get(selected)
        if not isinstance(profile, dict):
            raise ValueError(f"Unknown integration profile: {selected}")
        if str(profile.get("kind") or STANDARD_PROFILE_KIND).strip().lower() == CHILD_PROFILE_KIND:
            if not allow_child or required_permission == MANAGE_CHILDREN_PERMISSION:
                raise PermissionError("Child profile gameplay is disabled.")
            return self.authorize_child_profile(
                client, selected, required_permission, record_use=record_use)
        grants = client.get("profile_grants", {})
        permissions = grants.get(selected, []) if isinstance(grants, dict) else []
        authorized = (
            profile.get("enabled") is True and
            not profile_deletion_pending(profile) and
            isinstance(permissions, list) and
            "use_profile" in permissions and
            required_permission in permissions
        )
        if not authorized:
            raise PermissionError(
                "This client is not authorized for the requested profile permission.")
        return self.select_profile(selected, record_use=record_use)

    def authorize_child_profile(self, client: dict[str, Any], actor_profile_id: str,
                                required_permission: str = "use_profile",
                                record_use: bool = False) -> str:
        """Authorize an actor and resolve its executable parent only on the host."""
        if required_permission not in {"use_profile", "remote_sign_in"}:
            raise ValueError("Invalid child profile permission.")
        actor = self.config.get("profiles", {}).get(str(actor_profile_id))
        if (not isinstance(actor, dict) or
                str(actor.get("kind") or STANDARD_PROFILE_KIND).strip().lower() != CHILD_PROFILE_KIND or
                actor.get("enabled") is not True or profile_deletion_pending(actor)):
            raise PermissionError("The requested child profile is unavailable.")
        grants = client.get("profile_grants", {})
        permissions = grants.get(str(actor_profile_id), []) if isinstance(grants, dict) else []
        if (not isinstance(permissions, list) or "use_profile" not in permissions or
                required_permission not in permissions):
            raise PermissionError("This client is not authorized for the requested child permission.")
        parent_id = str(actor.get("parent_profile_id") or "").strip()
        parent = self.config.get("profiles", {}).get(parent_id)
        if (not self._is_standard_profile(parent) or parent.get("enabled") is not True or
                profile_deletion_pending(parent)):
            raise PermissionError("The child profile parent is unavailable.")
        self.request_context.profile_id = str(actor_profile_id)
        self.request_context.actor_profile_id = str(actor_profile_id)
        self.request_context.execution_profile_id = parent_id
        if record_use:
            self.record_profile_use(str(actor_profile_id))
        return str(actor_profile_id)

    def child_execution_profile(self, client: dict[str, Any], actor_profile_id: str,
                                required_permission: str = "remote_sign_in") -> tuple[str, dict[str, Any]]:
        self.authorize_child_profile(client, actor_profile_id, required_permission)
        parent_id = str(self.config["profiles"][actor_profile_id]["parent_profile_id"])
        return parent_id, self.config["profiles"][parent_id]

    def is_child_profile(self, profile_id: str | None) -> bool:
        profile = self.config.get("profiles", {}).get(str(profile_id or ""))
        return (isinstance(profile, dict) and
                str(profile.get("kind") or STANDARD_PROFILE_KIND).strip().lower() ==
                CHILD_PROFILE_KIND)

    @staticmethod
    def pin_client_key(client: dict[str, Any]) -> str:
        return str(client.get("id") or client.get("token_sha256") or "")

    @staticmethod
    def pin_verifier_fingerprint(profile: dict[str, Any]) -> str:
        verifier = profile.get("pin_verifier")
        return hashlib.sha256(json.dumps(
            verifier, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()

    def _verify_pin_attempt(self, client: dict[str, Any], profile_id: str,
                            pin: str) -> tuple[int, dict[str, Any], str | None]:
        profile = self.config.get("profiles", {}).get(profile_id)
        if not isinstance(profile, dict) or not profile_pin_required(profile):
            return HTTPStatus.BAD_REQUEST, {"ok": False, "error": "pin_not_required"}, None
        key = (self.pin_client_key(client), profile_id)
        now = time.monotonic()
        with self.lock:
            blocked_until = self.pin_blocked_until.get(key, 0.0)
            if blocked_until > now:
                return HTTPStatus.TOO_MANY_REQUESTS, {
                    "ok": False, "error": "rate_limited",
                    "retry_after_seconds": max(1, int(blocked_until - now + 0.999)),
                }, None
            failures = [attempt for attempt in self.pin_failures.get(key, [])
                        if now - attempt < PIN_FAILURE_WINDOW_SECONDS]
            if not verify_profile_pin(pin, profile.get("pin_verifier")):
                failures.append(now)
                cooldown = min(PIN_MAX_COOLDOWN_SECONDS, len(failures))
                self.pin_failures[key] = failures
                self.pin_blocked_until[key] = now + cooldown
                return HTTPStatus.FORBIDDEN, {
                    "ok": False, "error": "invalid_pin",
                    "retry_after_seconds": cooldown,
                }, None
            self.pin_failures.pop(key, None)
            self.pin_blocked_until.pop(key, None)
            return HTTPStatus.OK, {"ok": True}, self.pin_verifier_fingerprint(profile)

    def verify_pin(self, client: dict[str, Any], profile_id: str,
                   pin: str, session_id: str | None = None) -> tuple[int, dict[str, Any]]:
        session_id = normalize_profile_session_id(session_id)
        profile = self.config.get("profiles", {}).get(profile_id)
        if not isinstance(profile, dict) or not profile_pin_required(profile):
            return HTTPStatus.BAD_REQUEST, {"ok": False, "error": "pin_not_required"}
        status, result, fingerprint = self._verify_pin_attempt(client, profile_id, pin)
        if status != HTTPStatus.OK:
            return status, result
        key = (self.pin_client_key(client), profile_id)
        now = time.monotonic()
        with self.lock:
            self.pin_unlock_leases[key] = (
                None if session_id is not None else now + PIN_UNLOCK_LEASE_SECONDS,
                fingerprint,
                session_id,
            )
        result = {"ok": True, "unlocked": True}
        if session_id is None:
            result["expires_in_seconds"] = PIN_UNLOCK_LEASE_SECONDS
        else:
            result["session_scoped"] = True
        return HTTPStatus.OK, result

    def require_profile_unlock(self, client: dict[str, Any], profile_id: str,
                               session_id: str | None = None) -> None:
        session_id = normalize_profile_session_id(session_id)
        profile = self.config.get("profiles", {}).get(profile_id)
        if not isinstance(profile, dict) or not profile_pin_required(profile):
            return
        client_id = self.pin_client_key(client)
        key = (client_id, profile_id)
        now = time.monotonic()
        with self.lock:
            lease = self.pin_unlock_leases.get(key)
            fingerprint_matches = (lease is not None and secrets.compare_digest(
                lease[1], self.pin_verifier_fingerprint(profile)))
            session_lease = (session_id is not None and lease is not None and
                             lease[0] is None and lease[2] == session_id)
            legacy_lease = (session_id is None and lease is not None and
                            lease[0] is not None and lease[0] > now and
                            lease[2] is None)
            if fingerprint_matches and (session_lease or legacy_lease):
                return
            # A stale or different process must not be able to invalidate the
            # currently authorized process. Verifier changes and expiry do
            # invalidate the stored lease; session mismatches only deny access.
            if (lease is not None and
                    (not fingerprint_matches or
                     (lease[0] is not None and lease[0] <= now))):
                self.pin_unlock_leases.pop(key, None)
        raise PermissionError("Profile app PIN verification required.")

    def begin_child_management(self, client: dict[str, Any], parent_profile_id: str,
                               pin: str, session_id: str | None) -> tuple[int, dict[str, Any]]:
        """Create the short management lease; a play lease cannot satisfy it."""
        session_id = normalize_profile_session_id(session_id)
        if session_id is None:
            raise ValueError(f"A valid {PROFILE_SESSION_HEADER} header is required.")
        parent = self.config.get("profiles", {}).get(str(parent_profile_id))
        grants = client.get("profile_grants", {})
        permissions = grants.get(str(parent_profile_id), []) if isinstance(grants, dict) else []
        if (not self._is_standard_profile(parent) or parent.get("enabled") is not True or
                profile_deletion_pending(parent) or not isinstance(permissions, list) or
                "use_profile" not in permissions or
                MANAGE_CHILDREN_PERMISSION not in permissions):
            raise PermissionError("Child management is not authorized for this parent profile.")
        if not profile_pin_required(parent):
            return HTTPStatus.FORBIDDEN, {
                "ok": False, "error": "manage_children_pin_required"}
        client_id = self.pin_client_key(client)
        status, result, fingerprint = self._verify_pin_attempt(
            client, str(parent_profile_id), pin)
        if status != HTTPStatus.OK:
            return status, result
        now = time.monotonic()
        with self.lock:
            authorization_id = secrets.token_urlsafe(24)
            self.child_management_sessions[(client_id, str(parent_profile_id), session_id)] = (
                now + CHILD_MANAGEMENT_SESSION_SECONDS, authorization_id,
                fingerprint)
        return HTTPStatus.OK, {
            "ok": True,
            "parent_profile_id": str(parent_profile_id),
            "authorization_id": authorization_id,
            "expires_in_seconds": CHILD_MANAGEMENT_SESSION_SECONDS,
        }

    def require_child_management(self, client: dict[str, Any], parent_profile_id: str,
                                 session_id: str | None, authorization_id: str) -> None:
        session_id = normalize_profile_session_id(session_id)
        if session_id is None or not authorization_id:
            raise PermissionError("A fresh child management session is required.")
        parent = self.config.get("profiles", {}).get(str(parent_profile_id))
        grants = client.get("profile_grants", {})
        permissions = grants.get(str(parent_profile_id), []) if isinstance(grants, dict) else []
        if (not self._is_standard_profile(parent) or parent.get("enabled") is not True or
                profile_deletion_pending(parent) or not isinstance(permissions, list) or
                "use_profile" not in permissions or
                MANAGE_CHILDREN_PERMISSION not in permissions):
            raise PermissionError("Child management is not authorized for this parent profile.")
        key = (self.pin_client_key(client), str(parent_profile_id), session_id)
        now = time.monotonic()
        with self.lock:
            lease = self.child_management_sessions.get(key)
            valid = (lease is not None and lease[0] > now and
                     secrets.compare_digest(lease[1], str(authorization_id)) and
                     secrets.compare_digest(lease[2], self.pin_verifier_fingerprint(parent)))
            if not valid:
                self.child_management_sessions.pop(key, None)
                raise PermissionError("A fresh child management session is required.")

    def delete_profile(self, profile_id: str) -> bool:
        selected = str(profile_id or "").strip()
        if not PROFILE_ID_PATTERN.fullmatch(selected):
            raise ValueError("Invalid integration profile ID.")
        if selected == "default":
            raise ValueError("The compatibility default profile cannot be deleted.")
        with self.lock:
            with self.registry_update_lock():
                current = self.current_registry()
                if selected not in current["profiles"]:
                    return False
                del current["profiles"][selected]
                for client in current["clients"]:
                    if not isinstance(client, dict):
                        continue
                    grants = client.get("profile_grants")
                    if isinstance(grants, dict):
                        grants.pop(selected, None)
                self.replace_registry(current)
                self.config = current
                for key in list(self.pin_unlock_leases):
                    if key[1] == selected:
                        self.pin_unlock_leases.pop(key, None)
        return True

    def record_profile_use(self, profile_id: str) -> None:
        now = time.monotonic()
        with self.lock:
            if profile_id == self.last_runtime_profile and now - self.last_runtime_write < 5.0:
                return
            temporary = self.runtime_status_path.with_suffix(".json.tmp")
            try:
                temporary.write_text(json.dumps({
                    "profile_id": profile_id,
                    "updated_at": int(time.time()),
                }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                os.replace(temporary, self.runtime_status_path)
            except OSError as error:
                # Windows rejects replacing a file another process holds open. This
                # display hint must not fail an authorized request; the next one retries.
                DIAGNOSTICS.record("runtime-status-write-deferred", level="WARN", error=error,
                                   request_id=getattr(self.request_context, "request_id", None),
                                   profile_id=profile_id,
                                   error_code=int(getattr(error, "winerror", 0) or
                                                  getattr(error, "errno", 0) or 0))
                return
            self.last_runtime_profile = profile_id
            self.last_runtime_write = now

    @property
    def profile_id(self) -> str:
        return str(getattr(self.request_context, "profile_id", "default"))

    @property
    def actor_profile_id(self) -> str:
        return str(getattr(self.request_context, "actor_profile_id", self.profile_id))

    @property
    def execution_profile_id(self) -> str:
        return str(getattr(self.request_context, "execution_profile_id", self.profile_id))

    def loopback_headers(self, content_type: str | None = None) -> dict[str, str]:
        headers = {"Content-Type": content_type} if content_type else {}
        request_id = getattr(self.request_context, "request_id", "")
        if isinstance(request_id, str) and REQUEST_ID_PATTERN.fullmatch(request_id):
            headers["X-Request-Id"] = request_id
        return headers

    def bridge_url(self, name: str, path: str,
                  profile_id: str | None = None) -> str:
        selected_profile_id = str(profile_id or self.profile_id)
        profile = self.config.get("profiles", {}).get(selected_profile_id, {})
        key = "game_provider_bridge" if name in {"game_provider", "playnite"} \
            else f"{name}_bridge"
        base = str(profile.get(key, "")).rstrip("/")
        if not base and key == "game_provider_bridge":
            base = str(profile.get("playnite_bridge", "")).rstrip("/")
        if not base and selected_profile_id == "default":
            base = str(self.config.get(key) or (
                self.config.get("playnite_bridge")
                if key == "game_provider_bridge" else "")).rstrip("/")
        if not base.startswith("http://127.0.0.1:") and not base.startswith("http://localhost:"):
            raise ValueError(f"{name} bridge must remain on loopback")
        return base + path

    def proxy(self, name: str, path: str, timeout: float = 2.5,
              profile_id: str | None = None) -> tuple[bool, Any]:
        try:
            request = urllib.request.Request(
                self.bridge_url(name, path, profile_id=profile_id),
                headers=self.loopback_headers(), method="GET")
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read(1024 * 1024).decode("utf-8", errors="replace")
                content_type = response.headers.get_content_type()
                if content_type == "application/json" or raw.lstrip().startswith(("{", "[")):
                    return True, json.loads(raw)
                return True, {"message": raw}
        except urllib.error.HTTPError as error:
            raw = error.read(64 * 1024).decode("utf-8", errors="replace").strip()
            return False, {"error": raw or str(error), "status": error.code}
        except (urllib.error.URLError, TimeoutError, OSError, ValueError,
                json.JSONDecodeError) as error:
            return False, {"error": str(error)}

    def proxy_bytes(self, name: str, path: str, timeout: float = 8.0,
                    profile_id: str | None = None) \
            -> tuple[int, bytes, str]:
        try:
            request = urllib.request.Request(
                self.bridge_url(name, path, profile_id=profile_id),
                headers=self.loopback_headers(), method="GET")
            with urllib.request.urlopen(request, timeout=timeout) as response:
                length = int(response.headers.get("Content-Length", "0") or 0)
                if length < 1 or length > 8 * 1024 * 1024:
                    raise ValueError("Artwork response has an unsupported size.")
                body = response.read(8 * 1024 * 1024 + 1)
                if len(body) != length or len(body) > 8 * 1024 * 1024:
                    raise ValueError("Artwork response is incomplete or too large.")
                content_type = response.headers.get_content_type()
                if not content_type.startswith("image/"):
                    raise ValueError("Game Provider Bridge returned non-image artwork.")
                return HTTPStatus.OK, body, content_type
        except urllib.error.HTTPError as error:
            return error.code, b"", "application/octet-stream"
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
            return HTTPStatus.BAD_GATEWAY, compact_json({"error": str(error)}), \
                "application/json; charset=utf-8"

    def proxy_json(self, name: str, path: str, body: dict[str, Any],
                   timeout: float = 8.0,
                   profile_id: str | None = None) -> tuple[bool, Any]:
        try:
            request = urllib.request.Request(
                self.bridge_url(name, path, profile_id=profile_id),
                data=compact_json(body),
                headers=self.loopback_headers("application/json; charset=utf-8"),
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read(1024 * 1024).decode("utf-8", errors="replace")
                return True, json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as error:
            raw = error.read(64 * 1024).decode("utf-8", errors="replace").strip()
            try:
                value = json.loads(raw) if raw else {"error": str(error)}
            except json.JSONDecodeError:
                value = {"error": raw or str(error)}
            if isinstance(value, dict):
                value.setdefault("status", error.code)
            return False, value
        except (urllib.error.URLError, TimeoutError, OSError, ValueError,
                json.JSONDecodeError) as error:
            return False, {"error": str(error)}

    def capabilities(self) -> dict[str, Any]:
        child_profiles_enabled = bool(
            getattr(self, "child_profiles_api_enabled", False))
        protocol_capabilities = {
            # The server switch and negotiated client header are separate gates.
            CHILD_PROFILES_CAPABILITY: {
                "available": child_profiles_enabled,
                "state": "ready" if child_profiles_enabled else "disabled",
                "reason": "none" if child_profiles_enabled else
                          "child_profiles_not_enforced",
                "protocol_version": 1,
            },
            CONTROL_OPERATIONS_CAPABILITY: {
                "available": True,
                "state": "ready",
                "reason": "none",
            },
        }
        if self.is_child_profile(self.actor_profile_id):
            # Negotiation is not a permission grant. Keep parent health, account
            # and device details private and avoid probing its integrations.
            return {
                "gateway": {"online": True, "api_version": 1},
                "capabilities": protocol_capabilities,
            }
        vibepollo_ok, vibepollo = self.proxy("vibepollo", "/health", timeout=1.0)
        discord_ok, discord = self.proxy("discord", "/health", timeout=1.0)
        playnite_ok, playnite = self.proxy(
            "game_provider", "/observation", timeout=1.0,
            profile_id=self.execution_profile_id)
        microphone = self.microphone_status()
        discord_audio = self.discord_audio_status()
        virtualhere_ok, virtualhere = (False, {"error": "Discord Bridge is offline."})
        if discord_ok:
            virtualhere_ok, virtualhere = self.proxy(
                "discord", "/virtualhere-state", timeout=1.5)
        remote_windows_sign_in = self.remote_windows_sign_in_capability()
        return {
            "gateway": {
                "online": True,
                "api_version": 1,
                "integration_profile_id": self.profile_id,
                **self.runtime_info(),
            },
            "capabilities": {
                "vibepollo_fix": {"available": vibepollo_ok, "health": vibepollo},
                "vibepollo_apps": {"available": vibepollo_ok, "health": vibepollo},
                "vibepollo_pairing": {"available": vibepollo_ok, "health": vibepollo},
                "game_provider": {"available": playnite_ok, "observation": playnite},
                "playnite": {"available": playnite_ok, "observation": playnite},
                "discord": {"available": discord_ok, "health": discord},
                "virtualhere": {
                    "available": virtualhere_ok and bool(virtualhere.get("installed", False)),
                    "health": virtualhere,
                },
                "host_sleep": {"available": os.name == "nt"},
                "session_suspend": {"available": os.name == "nt"},
                "remote_windows_sign_in": remote_windows_sign_in,
                "remote_sign_in": remote_windows_sign_in,
                **protocol_capabilities,
                "microphone": {
                    **microphone,
                    "format": "pcm_s16le",
                    "sample_rate": 48000,
                    "channels": 1,
                    "frame_samples": 960,
                },
                "discord_audio": {
                    **discord_audio,
                    "format": "pcm_s16le",
                    "sample_rate": 48000,
                    "channels": 2,
                    "frame_samples": 960,
                },
            },
        }

    def profile_login_state(self, profile: dict[str, Any]) -> dict[str, str]:
        if not self._is_standard_profile(profile):
            return {"session_state": "unknown",
                    "remote_sign_in_state": "disabled",
                    "reason": "child_profiles_not_enforced"}
        enabled = bool(profile.get("remote_sign_in_enabled", False))
        result = self.login_broker.profile_state(profile)
        if result.get("state") == "broker_unavailable":
            return {"session_state": "unknown",
                    "remote_sign_in_state": "broker_unavailable" if enabled else "disabled",
                    "reason": "broker_unavailable" if enabled else "remote_sign_in_disabled"}
        session_state = str(result.get("state") or "unknown") \
            if result.get("success") else "unknown"
        fields = result.get("fields") if isinstance(result.get("fields"), dict) else {}
        credential_state = str(fields.get(3) or "action_required")
        credential_reason = str(fields.get(4) or result.get("reason") or "credential_missing")
        if not enabled:
            remote_state, reason = "disabled", "remote_sign_in_disabled"
        elif profile.get("account_mapping_status") != "resolved":
            remote_state, reason = "action_required", "account_mapping_required"
        elif not result.get("success") or credential_state != "ready":
            remote_state, reason = "action_required", credential_reason
        elif session_state == "other_user_active":
            remote_state, reason = "action_required", "other_user_active"
        elif session_state == "unknown":
            remote_state, reason = "unavailable", "session_state_unknown"
        else:
            remote_state, reason = "ready", "none"
        return {"session_state": session_state,
                "remote_sign_in_state": remote_state, "reason": reason}

    def remote_windows_sign_in_capability(self) -> dict[str, Any]:
        result = self.login_broker.capability()
        fields = result.get("fields") if isinstance(result.get("fields"), dict) else {}
        compatible = bool(result.get("success")) and str(fields.get(3) or "") == "1"
        return {"available": compatible,
                "state": "ready" if compatible else str(
                    result.get("state") or "broker_unavailable"),
                "reason": "none" if compatible else str(
                    result.get("reason") or "broker_unavailable"),
                "protocol_version": 1}

    @staticmethod
    def broker_client_id(client: dict[str, Any]) -> str:
        value = str(client.get("id") or client.get("token_sha256") or "")
        if not value or len(value) > 128 or any(character.isspace() for character in value):
            raise PermissionError("The paired client has no usable identity.")
        return value

    def _child_attempt_result(self, result: dict[str, Any], actor_profile_id: str,
                              execution_profile_id: str, request_id: str,
                              attempt_id: str = "") -> tuple[int, dict[str, Any]]:
        state = str(result.get("state") or "action_required")
        reason = str(result.get("reason") or "action_required")
        fields = result.get("fields") if isinstance(result.get("fields"), dict) else {}
        attempt = str(fields.get(3) or attempt_id)
        body = {"ok": bool(result.get("success")), "state": state, "reason": reason,
                "actor_profile_id": actor_profile_id,
                "execution_profile_id": execution_profile_id,
                "request_id": request_id, "attempt_id": attempt}
        if state == "broker_unavailable":
            return HTTPStatus.SERVICE_UNAVAILABLE, {key: value for key, value in body.items()
                                                     if key != "ok"} | {"ok": False}
        if reason == "attempt_cancelled":
            body.update({"ok": True, "state": "cancelled"})
            return HTTPStatus.OK, body
        if result.get("success"):
            return (HTTPStatus.ACCEPTED if state != "ready" else HTTPStatus.OK), body
        return HTTPStatus.CONFLICT, body

    def ensure_child_session(self, client: dict[str, Any], actor_profile_id: str,
                             request_id: str) -> tuple[int, dict[str, Any]]:
        if not REQUEST_ID_PATTERN.fullmatch(request_id):
            raise ValueError("A valid X-Request-Id header is required.")
        execution_id, parent = self.child_execution_profile(
            client, actor_profile_id, "use_profile")
        current = self.profile_login_state(parent)
        if current["remote_sign_in_state"] == "broker_unavailable":
            return HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, **current,
                                                     "actor_profile_id": actor_profile_id,
                                                     "execution_profile_id": execution_id,
                                                     "request_id": request_id}
        if current["session_state"] == "active":
            return HTTPStatus.OK, {
                "ok": True, "state": "ready", "reason": "none",
                "session_state": "active", "actor_profile_id": actor_profile_id,
                "execution_profile_id": execution_id, "request_id": request_id,
            }
        if current["session_state"] == "other_user_active":
            return HTTPStatus.CONFLICT, {"ok": False, **current,
                                         "actor_profile_id": actor_profile_id,
                                         "execution_profile_id": execution_id,
                                         "request_id": request_id}
        # Remote sign-in is checked only when the parent really needs a logon.
        self.authorize_child_profile(client, actor_profile_id, "remote_sign_in")
        if (not parent.get("remote_sign_in_enabled") or
                parent.get("account_mapping_status") != "resolved" or
                current["remote_sign_in_state"] == "unavailable"):
            return HTTPStatus.CONFLICT, {
                "ok": False, "state": "action_required",
                "reason": "remote_sign_in_disabled" if not parent.get(
                    "remote_sign_in_enabled") else current.get(
                        "reason") if current["remote_sign_in_state"] == "unavailable"
                    else "account_mapping_required",
                "actor_profile_id": actor_profile_id,
                "execution_profile_id": execution_id,
                "request_id": request_id,
            }
        # Authorization and actor/execution binding stay in the Gateway.  The
        # Windows effect itself must use the parent's exact, proven path so a
        # child does not get a second login state machine with different live
        # behaviour.
        result = self.login_broker.begin(
            self.broker_client_id(client), parent, request_id)
        status, body = self._child_attempt_result(
            result, actor_profile_id, execution_id, request_id)
        body["session_state"] = current["session_state"]
        return status, body

    def switch_child_session(self, client: dict[str, Any], actor_profile_id: str,
                             request_id: str) -> tuple[int, dict[str, Any]]:
        if not REQUEST_ID_PATTERN.fullmatch(request_id):
            raise ValueError("A valid X-Request-Id header is required.")
        execution_id, parent = self.child_execution_profile(
            client, actor_profile_id, "remote_sign_in")
        if not parent.get("remote_sign_in_enabled"):
            return HTTPStatus.CONFLICT, {
                "ok": False, "state": "action_required",
                "reason": "remote_sign_in_disabled",
                "actor_profile_id": actor_profile_id,
                "execution_profile_id": execution_id, "request_id": request_id,
            }
        if parent.get("account_mapping_status") != "resolved":
            return HTTPStatus.CONFLICT, {
                "ok": False, "state": "action_required",
                "reason": "account_mapping_required",
                "actor_profile_id": actor_profile_id,
                "execution_profile_id": execution_id, "request_id": request_id,
            }
        result = self.login_broker.switch_session(
            self.broker_client_id(client), parent, request_id)
        return self._child_attempt_result(
            result, actor_profile_id, execution_id, request_id)

    def child_session_status(self, client: dict[str, Any], actor_profile_id: str) \
            -> tuple[int, dict[str, Any]]:
        execution_id, parent = self.child_execution_profile(
            client, actor_profile_id, "use_profile")
        current = self.profile_login_state(parent)
        return HTTPStatus.OK, {"ok": True, "state": "disabled",
                               "reason": "child_profiles_not_enforced",
                               "session_state": current["session_state"],
                               "actor_profile_id": actor_profile_id,
                               "execution_profile_id": execution_id}

    def child_attempt_status(self, client: dict[str, Any], actor_profile_id: str,
                             request_id: str, attempt_id: str) \
            -> tuple[int, dict[str, Any]]:
        if (not REQUEST_ID_PATTERN.fullmatch(request_id) or
                not re.fullmatch(r"[0-9a-f]{32}", attempt_id)):
            raise ValueError("Valid attempt and request IDs are required.")
        execution_id, parent = self.child_execution_profile(
            client, actor_profile_id, "remote_sign_in")
        result = self.login_broker.attempt_state(
            self.broker_client_id(client), execution_id, request_id, attempt_id)
        status, body = self._child_attempt_result(
            result, actor_profile_id, execution_id, request_id, attempt_id)
        if body["state"] == "completed":
            current = self.profile_login_state(parent)
            body["session_state"] = current["session_state"]
            if current["session_state"] == "active":
                body.update({"ok": True, "state": "ready", "reason": "none"})
                status = HTTPStatus.OK
            elif current["remote_sign_in_state"] == "broker_unavailable":
                status = HTTPStatus.SERVICE_UNAVAILABLE
            else:
                body.update({"ok": True, "state": "session_starting", "reason": "none"})
                status = HTTPStatus.ACCEPTED
        return status, body

    def cancel_child_attempt(self, client: dict[str, Any], actor_profile_id: str,
                             request_id: str, attempt_id: str) \
            -> tuple[int, dict[str, Any]]:
        if (not REQUEST_ID_PATTERN.fullmatch(request_id) or
                not re.fullmatch(r"[0-9a-f]{32}", attempt_id)):
            raise ValueError("Valid attempt and request IDs are required.")
        execution_id, parent = self.child_execution_profile(
            client, actor_profile_id, "remote_sign_in")
        result = self.login_broker.cancel(
            self.broker_client_id(client), execution_id, request_id, attempt_id)
        return self._child_attempt_result(
            result, actor_profile_id, execution_id, request_id, attempt_id)

    def ensure_session(self, client: dict[str, Any], profile_id: str,
                       request_id: str) -> tuple[int, dict[str, Any]]:
        if self.is_child_profile(profile_id):
            return self.ensure_child_session(client, profile_id, request_id)
        if not REQUEST_ID_PATTERN.fullmatch(request_id):
            raise ValueError("A valid X-Request-Id header is required.")
        profile = self.config.get("profiles", {}).get(profile_id)
        if not isinstance(profile, dict):
            return HTTPStatus.CONFLICT, {"ok": False, "state": "action_required",
                                         "reason": "profile_missing"}
        current = self.profile_login_state(profile)
        if current["remote_sign_in_state"] == "broker_unavailable":
            return HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, **current}
        if current["session_state"] == "active":
            return HTTPStatus.OK, {"ok": True, "state": "ready",
                                   "reason": "none", "session_state": "active"}
        if current["session_state"] == "other_user_active":
            return HTTPStatus.CONFLICT, {"ok": False, **current}
        self.authorize_profile(client, profile_id, "remote_sign_in", record_use=True)
        if not profile.get("remote_sign_in_enabled"):
            return HTTPStatus.CONFLICT, {"ok": False, "state": "action_required",
                                         "reason": "remote_sign_in_disabled",
                                         "session_state": current["session_state"]}
        if (current["remote_sign_in_state"] == "unavailable" or
                current["reason"] == "account_mapping_required"):
            return HTTPStatus.CONFLICT, {"ok": False, **current}
        result = self.login_broker.begin(
            self.broker_client_id(client), profile, request_id)
        state = str(result.get("state") or "action_required")
        reason = str(result.get("reason") or "action_required")
        if state == "broker_unavailable":
            return HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "state": state,
                                                     "reason": reason}
        fields = result.get("fields") if isinstance(result.get("fields"), dict) else {}
        body = {"ok": bool(result.get("success")), "state": state, "reason": reason,
                "session_state": current["session_state"],
                "attempt_id": str(fields.get(3) or ""), "request_id": request_id}
        return (HTTPStatus.ACCEPTED if result.get("success") else HTTPStatus.CONFLICT), body

    def switch_session(self, client: dict[str, Any], profile_id: str,
                       request_id: str) -> tuple[int, dict[str, Any]]:
        if self.is_child_profile(profile_id):
            return self.switch_child_session(client, profile_id, request_id)
        if not REQUEST_ID_PATTERN.fullmatch(request_id):
            raise ValueError("A valid X-Request-Id header is required.")
        profile = self.config.get("profiles", {}).get(profile_id)
        if not isinstance(profile, dict):
            return HTTPStatus.CONFLICT, {"ok": False, "state": "action_required",
                                         "reason": "profile_missing"}
        if not profile.get("remote_sign_in_enabled"):
            return HTTPStatus.CONFLICT, {"ok": False, "state": "action_required",
                                         "reason": "remote_sign_in_disabled"}
        if profile.get("account_mapping_status") != "resolved":
            return HTTPStatus.CONFLICT, {"ok": False, "state": "action_required",
                                         "reason": "account_mapping_required"}
        result = self.login_broker.switch_session(
            self.broker_client_id(client), profile, request_id)
        state = str(result.get("state") or "action_required")
        reason = str(result.get("reason") or "action_required")
        if state == "broker_unavailable":
            status = HTTPStatus.SERVICE_UNAVAILABLE
        elif result.get("success") and state == "ready":
            status = HTTPStatus.OK
        elif result.get("success"):
            status = HTTPStatus.ACCEPTED
        else:
            status = HTTPStatus.CONFLICT
        fields = result.get("fields") if isinstance(result.get("fields"), dict) else {}
        return status, {"ok": bool(result.get("success")), "state": state,
                        "reason": reason, "attempt_id": str(fields.get(3) or ""),
                        "request_id": request_id}

    def session_attempt_status(self, client: dict[str, Any], profile_id: str,
                               request_id: str, attempt_id: str) \
            -> tuple[int, dict[str, Any]]:
        if self.is_child_profile(profile_id):
            return self.child_attempt_status(client, profile_id, request_id, attempt_id)
        if (not REQUEST_ID_PATTERN.fullmatch(request_id) or
                not re.fullmatch(r"[0-9a-f]{32}", attempt_id)):
            raise ValueError("Valid attempt and request IDs are required.")
        result = self.login_broker.attempt_state(
            self.broker_client_id(client), profile_id, request_id, attempt_id)
        state = str(result.get("state") or "action_required")
        reason = str(result.get("reason") or "action_required")
        if state == "broker_unavailable":
            return HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "state": state,
                                                     "reason": reason}
        if state == "completed" or (state == "credential_issued" and result.get("success")):
            profile = self.config.get("profiles", {}).get(profile_id, {})
            current = self.profile_login_state(profile)
            if current["session_state"] == "active":
                return HTTPStatus.OK, {"ok": True, "state": "ready", "reason": "none",
                                       "session_state": "active", "attempt_id": attempt_id}
            if state == "completed":
                if current["remote_sign_in_state"] == "broker_unavailable":
                    return HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, **current,
                                                             "attempt_id": attempt_id}
                return HTTPStatus.ACCEPTED, {"ok": True, "state": "session_starting",
                                             "reason": "none",
                                             "session_state": current["session_state"],
                                             "attempt_id": attempt_id}
        body = {"ok": bool(result.get("success")), "state": state, "reason": reason,
                "attempt_id": attempt_id}
        return (HTTPStatus.ACCEPTED if result.get("success") else HTTPStatus.CONFLICT), body

    def cancel_session_attempt(self, client: dict[str, Any], profile_id: str,
                               request_id: str, attempt_id: str) \
            -> tuple[int, dict[str, Any]]:
        if self.is_child_profile(profile_id):
            return self.cancel_child_attempt(client, profile_id, request_id, attempt_id)
        if (not REQUEST_ID_PATTERN.fullmatch(request_id) or
                not re.fullmatch(r"[0-9a-f]{32}", attempt_id)):
            raise ValueError("Valid attempt and request IDs are required.")
        result = self.login_broker.cancel(
            self.broker_client_id(client), profile_id, request_id, attempt_id)
        state = str(result.get("state") or "action_required")
        reason = str(result.get("reason") or "action_required")
        if state == "broker_unavailable":
            return HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "state": state,
                                                     "reason": reason}
        if reason == "attempt_cancelled":
            return HTTPStatus.OK, {"ok": True, "state": "cancelled", "reason": reason,
                                   "attempt_id": attempt_id}
        return HTTPStatus.CONFLICT, {"ok": False, "state": state, "reason": reason,
                                     "attempt_id": attempt_id}

    def microphone_worker(self) -> Path:
        return self.path_from_config("microphone_worker")

    def discord_audio_worker(self) -> Path:
        return self.path_from_config("discord_audio_worker")

    def discord_audio_target(self) -> tuple[str, int]:
        ok, target = self.proxy("discord", "/audio-capture-target", timeout=2.0)
        if not ok or not isinstance(target, dict):
            return "discord_unavailable", 0
        reason = str(target.get("reason", ""))
        if reason not in {
                "ready", "discord_process_missing", "discord_process_ambiguous",
                "discord_process_unavailable"}:
            return "discord_unavailable", 0
        process_id = target.get("pid", 0)
        if reason == "ready" and isinstance(process_id, int) and 0 < process_id <= 0xFFFFFFFF:
            return "ready", process_id
        return reason if reason != "ready" else "discord_unavailable", 0

    def discord_audio_status(self) -> dict[str, Any]:
        worker = self.discord_audio_worker()
        if not worker.is_file():
            return {"available": False, "reason": "worker_missing"}
        reason, process_id = self.discord_audio_target()
        if reason != "ready":
            return {"available": False, "reason": reason}
        try:
            result = subprocess.run(
                [str(worker), "--probe", str(process_id)], stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=3.0,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if result.returncode == 0:
                return {"available": True, "reason": "ready"}
        except (OSError, subprocess.SubprocessError):
            pass
        return {"available": False, "reason": "process_loopback_unsupported"}

    def probe_microphone(self) -> bool:
        return bool(self.microphone_status()["available"])

    def microphone_status(self) -> dict[str, Any]:
        worker = self.microphone_worker()
        if not worker.is_file():
            return {"available": False, "reason": "worker_missing"}
        try:
            result = subprocess.run(
                [str(worker), "--probe"], stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=3.0,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if result.returncode == 0:
                return {"available": True, "reason": "ready"}
        except (OSError, subprocess.SubprocessError):
            pass
        return {"available": False,
                "reason": "steam_endpoint_missing_or_ambiguous_or_unsupported"}

    @staticmethod
    def _sleep_windows(force: bool = False) -> None:
        force_literal = "$true" if force else "$false"
        command = (
            "Add-Type -AssemblyName System.Windows.Forms; "
            "[System.Windows.Forms.Application]::SetSuspendState("
            f"[System.Windows.Forms.PowerState]::Suspend, {force_literal}, $false)"
        )
        try:
            subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                check=True,
                timeout=15,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception as error:
            print(f"Host sleep command failed: {error}", flush=True)

    def _schedule_system_sleep(self, delay_seconds: float = 0.75,
                               force: bool = False) -> None:
        timer = threading.Timer(delay_seconds, self._sleep_windows, args=(force,))
        timer.daemon = True
        timer.start()

    def sleep_host(self) -> tuple[int, Any]:
        if os.name != "nt":
            return HTTPStatus.NOT_IMPLEMENTED, {
                "ok": False,
                "error": "Host sleep is only available on Windows.",
            }
        self._schedule_system_sleep()
        return HTTPStatus.ACCEPTED, {"ok": True, "accepted": True}

    def suspend_session(self, body: dict[str, Any], request_id: str) -> tuple[int, Any]:
        suspend_id = str(body.get("suspend_id") or "").strip()
        if not REQUEST_ID_PATTERN.fullmatch(request_id) or suspend_id != request_id:
            return HTTPStatus.BAD_REQUEST, {
                "ok": False,
                "error": "A matching valid suspend_id is required.",
            }
        if os.name != "nt":
            return HTTPStatus.NOT_IMPLEMENTED, {
                "ok": False,
                "error": "Session suspend is only available on Windows.",
            }
        sunshine_app_id = int(body.get("sunshine_app_id") or 0)
        playnite_game_id = str(body.get("playnite_game_id") or "").strip().lower()[:128]
        title = str(body.get("title") or "").strip()[:200]
        if sunshine_app_id <= 0:
            return HTTPStatus.BAD_REQUEST, {
                "ok": False,
                "error": "A valid Sunshine application id is required.",
            }
        # Leave enough time for the authenticated response to reach the TV and
        # for MoonWaker to close the stream without asking Sunshine to quit it.
        self._schedule_system_sleep(2.5, True)
        return HTTPStatus.ACCEPTED, {
            "ok": True,
            "accepted": True,
            "suspend_id": suspend_id,
            "session": {
                "sunshine_app_id": sunshine_app_id,
                "playnite_game_id": playnite_game_id,
                "title": title,
            },
        }

    def child_suspend_session(self, client: dict[str, Any], actor_profile_id: str,
                              body: dict[str, Any], request_id: str) -> tuple[int, Any]:
        actor_id = str(actor_profile_id or "")
        client_id = self.broker_client_id(client)
        # Resolve the requested game through the provider before the fence; the
        # comparison below requires the same session, so a handoff in between fails.
        resolved_for = self.child_time_session
        requested_game = self._child_request_game(
            str(resolved_for.get("execution_profile_id") or ""),
            body.get("playnite_game_id")) \
            if body.get("playnite_game_id") and isinstance(resolved_for, dict) else ""
        # Hold the common state fence from exact actor/stream validation until
        # the sleep is scheduled and this time attachment is durably released.
        # A concurrent A->B handoff therefore happens wholly before or after
        # this operation, never between its validation and host effect.
        with self.lock:
            session = self.child_time_session
            if not isinstance(session, dict) or any(
                    str(session.get(key) or "") != value for key, value in (
                        ("actor_profile_id", actor_id),
                        ("client_id", client_id))):
                raise PermissionError("child_session_required")
            if requested_game and (session is not resolved_for or requested_game !=
                                   self._child_time_normalize_game_id(session.get("game_id"))):
                raise PermissionError("child_session_binding_mismatch")
            stream = self.control_state.get("stream")
            exact_stream = isinstance(stream, dict) and all(
                stream.get(key) == session.get(session_key)
                for key, session_key in (
                    ("client_id", "client_id"),
                    ("actor_profile_id", "actor_profile_id"),
                    ("execution_profile_id", "execution_profile_id"),
                    ("child_session_id", "session_id")))
            exact_fence = exact_stream and \
                int(stream.get("generation") or 0) == int(
                    self.control_state.get("generation") or 0) and \
                int(stream.get("actor_lease_epoch") or 0) == int(
                    self.control_state.get("actor_lease_epoch") or 0) and \
                not isinstance(self.control_state.get("active"), dict)
            if not exact_fence:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "accepted": False,
                    "reason": "actor_handoff_unconfirmed",
                }
            status, result = self.suspend_session(body, request_id)
            if status != HTTPStatus.ACCEPTED or not isinstance(result, dict) \
                    or result.get("accepted") is not True:
                return status, result
            actor = self.config.get("profiles", {}).get(actor_id)
            _monotonic, wall = self._child_time_now(
                actor if isinstance(actor, dict) else None)
            release_status, released = self._child_time_release_lease(
                client, session, "suspended", wall)
            if release_status != HTTPStatus.OK:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "accepted": False,
                    "reason": "child_time_release_unconfirmed",
                }
        result["child_session_id"] = str(session.get("session_id") or "")
        result["child_time_released"] = True
        result["usage_revision"] = int(released.get("usage_revision") or 0)
        return status, result

    def _hard_reset_effects(self, reset_operation: dict[str, Any],
                            reset_profile_id: str) -> tuple[int, Any]:
        provider: Any = {"error": "hard_reset_effect_unconfirmed"}
        provider_accepted = False
        session_ok = False
        try:
            provider_ok, provider = self.proxy_json(
                "game_provider", "/session/hard-reset", {
                    "force": True,
                    "control_operation": self._control_bridge_binding(reset_operation),
                }, timeout=12.0, profile_id=reset_profile_id)
            provider_accepted = provider_ok and isinstance(provider, dict) \
                and bool(provider.get("accepted", False))
        except Exception as error:
            provider = {"error": type(error).__name__}
        streamer_trace: dict[str, Any] = {}
        try:
            session_ok = StreamerControlOperation.force_close(
                self.proxy, reset_profile_id, trace=streamer_trace)
        except Exception as error:
            session_ok = False
            streamer_trace["close_http_ok"] = 0
            DIAGNOSTICS.record("hard-reset-streamer-close-error", level="WARN",
                               error=error, operation_id=reset_operation.get("operation_id"))
        DIAGNOSTICS.record(
            "hard-reset-streamer-close", level="INFO" if session_ok else "WARN",
            operation_id=reset_operation.get("operation_id"),
            generation=reset_operation.get("generation"),
            actor_lease_epoch=reset_operation.get("actor_lease_epoch"),
            stream_session_id=reset_operation.get("pre_reset_stream_session_id") or
                reset_operation.get("stream_session_id"),
            profile_id=reset_profile_id, **streamer_trace)
        cleanup_confirmed = provider_accepted and session_ok
        with self.control_condition:
            current = self.control_state.get("active")
            if isinstance(current, dict) and all(
                    current.get(key) == reset_operation.get(key) for key in (
                        "operation_id", "generation", "actor_lease_epoch")):
                current["effect_result"] = {
                    "provider_accepted": provider_accepted,
                    "streamer_closed": session_ok,
                }
                # The reset generation and epoch were durably advanced before
                # either best-effort effect. Recovery is therefore complete even
                # when an upstream component cannot confirm physical cleanup.
                self._control_finish_locked(current, "succeeded",
                                            "none" if cleanup_confirmed else
                                            "hard_reset_cleanup_unconfirmed")
        return HTTPStatus.OK, {
            "ok": True,
            "accepted": True,
            "recovered": True,
            "cleanup_confirmed": cleanup_confirmed,
            "stopped_game_count": int(provider.get("stopped_count") or 0)
                if isinstance(provider, dict) else 0,
            "provider_reset_requested": provider_accepted,
            "session_close_requested": session_ok,
            "reason": "none" if cleanup_confirmed else
                "hard_reset_cleanup_unconfirmed",
        }

    def hard_reset_session(self, body: dict[str, Any],
                           requester_client_id: str = "",
                           requester_execution_profile_id: str = "",
                           expected_child_session_id: str = "",
                           expected_actor_profile_id: str = "") \
            -> tuple[int, Any]:
        if body.get("force") is not True:
            raise ValueError("Explicit force confirmation is required.")
        recovered_reset = None
        with self.control_condition:
            active = self.control_state.get("active")
            if isinstance(active, dict) and active.get("intent") == "hard_reset" \
                    and active.get("state") == "uncertain":
                terminal = active.get("child_terminal_result")
                requester_matches = not requester_client_id or (
                    active.get("client_id") == requester_client_id and
                    active.get("execution_profile_id") ==
                    requester_execution_profile_id)
                child_matches = not expected_child_session_id or (
                    active.get("child_session_id") == expected_child_session_id and
                    isinstance(terminal, dict) and
                    terminal.get("actor_profile_id") == expected_actor_profile_id)
                if not requester_matches or not child_matches:
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "accepted": False,
                        "stopped_game_count": 0,
                        "provider_reset_requested": False,
                        "session_close_requested": False,
                        "reason": "hard_reset_requester_conflict",
                    }
                active["state"] = "permitted"
                active["reason"] = "retry_after_gateway_restart"
                self._control_save_locked()
                self.control_condition.notify_all()
                recovered_reset = active
        if isinstance(recovered_reset, dict):
            return self._hard_reset_effects(
                recovered_reset,
                str(recovered_reset.get("execution_profile_id") or
                    self.execution_profile_id))
        # Hard reset is the operator escape hatch. Invalidate every earlier owner
        # even when an upstream effect cannot be confirmed; delayed completions are
        # fenced by the new generation and epoch.
        with self.control_condition:
            stream = self.control_state.get("stream")
            active = self.control_state.get("active")
            child = self.child_time_session
            if isinstance(active, dict) and active.get("intent") == "hard_reset":
                return HTTPStatus.CONFLICT, {
                    "ok": False, "accepted": False,
                    "stopped_game_count": 0,
                    "provider_reset_requested": False,
                    "session_close_requested": False,
                    "reason": "hard_reset_in_progress",
                }
            if expected_child_session_id:
                expected = self.child_time_completed.get(
                    expected_child_session_id)
                if not (isinstance(child, dict) and
                        child.get("session_id") == expected_child_session_id and
                        child.get("actor_profile_id") == expected_actor_profile_id and
                        child.get("client_id") == requester_client_id and
                        child.get("execution_profile_id") ==
                        requester_execution_profile_id):
                    if isinstance(expected, dict) and all(
                            expected.get(key) == value for key, value in (
                                ("actor_profile_id", expected_actor_profile_id),
                                ("client_id", requester_client_id),
                                ("execution_profile_id",
                                 requester_execution_profile_id))):
                        return HTTPStatus.OK, {
                            "ok": True, "accepted": True,
                            "stopped_game_count": 0,
                            "provider_reset_requested": False,
                            "session_close_requested": False,
                            "reason": "child_session_already_ended",
                        }
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "accepted": False,
                        "stopped_game_count": 0,
                        "provider_reset_requested": False,
                        "session_close_requested": False,
                        "reason": "child_session_unmanaged",
                    }
            owner_profiles = {
                str(owner.get("execution_profile_id") or "")
                for owner in (stream, active, child)
                if isinstance(owner, dict) and owner.get("execution_profile_id")
            }
            owner_clients = {
                str(owner.get("client_id") or "")
                for owner in (stream, active, child)
                if isinstance(owner, dict) and owner.get("client_id")
            }
            if len(owner_profiles) > 1:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "accepted": False,
                    "stopped_game_count": 0,
                    "provider_reset_requested": False,
                    "session_close_requested": False,
                    "reason": "hard_reset_owner_conflict",
                }
            if requester_client_id and (
                    (owner_clients and owner_clients != {requester_client_id}) or
                    (owner_profiles and owner_profiles != {
                        requester_execution_profile_id})):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "accepted": False,
                    "stopped_game_count": 0,
                    "provider_reset_requested": False,
                    "session_close_requested": False,
                    "reason": "hard_reset_requester_conflict",
                }
            reset_profile_id = next(iter(owner_profiles), self.execution_profile_id)
            if isinstance(child, dict):
                actor_id = str(child.get("actor_profile_id") or "")
                now_monotonic, wall = self._child_time_now()
                self._child_time_accrue_locked(
                    child, now_monotonic, wall,
                    float(child.get("last_playable_seconds", 0.0)))
                self.child_time_completed[str(child.get("session_id") or "")] = {
                    "reason": "hard_reset",
                    "game_id": str(child.get("game_id") or ""),
                    "actor_profile_id": actor_id,
                    "execution_profile_id": str(child.get("execution_profile_id") or ""),
                    "client_id": str(child.get("client_id") or ""),
                }
            if isinstance(active, dict):
                active = {**active, "state": "failed", "reason": "superseded_by_hard_reset"}
                self.control_state.setdefault("completed", {})[
                    str(active.get("operation_id") or uuid.uuid4())] = active
            self.child_time_session = None
            self.child_time_usage["active_session"] = None
            self.control_state["stream"] = None
            self.control_state["idle_stream"] = None
            self.control_state["generation"] = int(
                self.control_state.get("generation") or 0) + 1
            self.control_state["actor_lease_epoch"] = int(
                self.control_state.get("actor_lease_epoch") or 0) + 1
            reset_generation = int(self.control_state["generation"])
            reset_epoch = int(self.control_state["actor_lease_epoch"])
            child_session_id = str(child.get("session_id") or "") \
                if isinstance(child, dict) else ""
            reset_operation = {
                "generation": reset_generation,
                "actor_lease_epoch": reset_epoch,
                "effect_sequence": 0,
                "operation_id": f"hard-reset:{reset_generation}:{reset_epoch}",
                "stream_session_id": f"hard-reset:{reset_generation}",
                "pre_reset_stream_session_id": str(stream.get("stream_session_id") or "")
                    if isinstance(stream, dict) else "",
                "child_session_id": child_session_id,
                "client_id": requester_client_id or next(iter(owner_clients), ""),
                "vibepollo_client_uuid": "",
                "intent": "hard_reset",
                "direction": "stop",
                "sunshine_app_id": int((stream or active or {}).get(
                    "sunshine_app_id") or 0),
                "expected_generation": reset_generation - 1,
                "observed_sunshine_app_id": int((stream or active or {}).get(
                    "sunshine_app_id") or 0),
                "actor_profile_id": reset_profile_id,
                "execution_profile_id": reset_profile_id,
                "state": "permitted",
                "reason": "none",
            }
            if isinstance(child, dict):
                reset_operation["child_terminal_result"] = {
                    "reason": "hard_reset",
                    "game_id": str(child.get("game_id") or ""),
                    "actor_profile_id": str(child.get("actor_profile_id") or ""),
                    "execution_profile_id": str(
                        child.get("execution_profile_id") or ""),
                    "client_id": str(child.get("client_id") or ""),
                }
            self.control_state["active"] = reset_operation
            self._child_time_bump_revision_locked()
            self._control_save_locked()
            self.control_condition.notify_all()
        return self._hard_reset_effects(reset_operation, reset_profile_id)

    def profiles_summary(self, client: dict[str, Any],
                         include_children: bool = False) -> dict[str, Any]:
        include_children = bool(
            include_children and getattr(self, "child_profiles_api_enabled", False))
        original_profile = getattr(self.request_context, "profile_id", None)
        original_actor = getattr(self.request_context, "actor_profile_id", None)
        original_execution = getattr(self.request_context, "execution_profile_id", None)
        profiles = []
        suggested_profile_id = ""
        available_profile_id = ""
        try:
            for profile_id in sorted(self.config.get("profiles", {})):
                if not PROFILE_ID_PATTERN.fullmatch(str(profile_id)):
                    continue
                # Child actors stay out of the legacy profile/game surface
                # while CP-01 capability is deliberately disabled.
                if not self._is_standard_profile(self.config["profiles"].get(profile_id)):
                    continue
                try:
                    self.authorize_profile(client, str(profile_id), "use_profile")
                except PermissionError:
                    continue
                profile_config = self.config["profiles"].get(profile_id, {})
                display_name = str(
                    profile_config.get("name") or
                    profile_config.get("display_name") or
                    profile_id
                ).strip()[:80] or str(profile_id)
                display_name = re.sub(r"[\x00-\x1f\x7f]", " ", display_name).strip()
                discord = self.discord_status()
                vibepollo_online, _ = self.proxy("vibepollo", "/health", timeout=1.0)
                playnite_online, playnite_observation = self.proxy(
                    "game_provider", "/observation", timeout=1.0,
                    profile_id=str(profile_id))
                provider = playnite_observation.get("provider") \
                    if isinstance(playnite_observation, dict) else None
                playnite_connector = playnite_online and isinstance(provider, dict) \
                    and provider.get("connector_evidence") == "VERIFIED"
                virtualhere_online = False
                if discord["bridge_online"]:
                    virtualhere_ok, virtualhere = self.proxy(
                        "discord", "/virtualhere-state", timeout=1.5)
                    virtualhere_online = virtualhere_ok and bool(
                        virtualhere.get("installed", False))
                if not suggested_profile_id and discord["rpc_connected"]:
                    suggested_profile_id = str(profile_id)
                if not available_profile_id and (
                        discord["bridge_online"] or vibepollo_online or playnite_online):
                    available_profile_id = str(profile_id)
                login = self.profile_login_state(profile_config)
                permissions = client.get("profile_grants", {}).get(str(profile_id), [])
                profiles.append({
                    "id": str(profile_id),
                    "name": display_name,
                    "kind": STANDARD_PROFILE_KIND,
                    "display_name": display_name,
                    "enabled": (profile_config.get("enabled") is True and
                                not profile_deletion_pending(profile_config)),
                    "pin_required": profile_pin_required(profile_config),
                    "remote_sign_in_enabled": bool(
                        profile_config.get("remote_sign_in_enabled", False)),
                    "account_mapping_status": str(
                        profile_config.get("account_mapping_status") or
                        "action_required"),
                    "permissions": {
                        "use_profile": True,
                        "remote_sign_in": (isinstance(permissions, list) and
                                            "remote_sign_in" in permissions),
                        "manage_children": (isinstance(permissions, list) and
                                              MANAGE_CHILDREN_PERMISSION in permissions),
                    },
                    "own_children_count": self._own_child_count(
                        self.config.get("profiles"), str(profile_id)),
                    **login,
                    "discord_bridge_online": discord["bridge_online"],
                    "discord_rpc_connected": discord["rpc_connected"],
                    "discord_authenticated": discord["authenticated"],
                    "vibepollo_bridge_online": vibepollo_online,
                    "game_provider_bridge_online": playnite_online,
                    "playnite_bridge_online": playnite_online,
                    "playnite_connector_connected": playnite_connector,
                    "virtualhere_available": virtualhere_online,
                })
        finally:
            original_config = self.config.get("profiles", {}).get(original_profile)
            if (isinstance(original_config, dict) and
                    original_config.get("enabled") is True and
                    not profile_deletion_pending(original_config)):
                self.request_context.profile_id = str(original_profile)
                if original_actor is not None:
                    self.request_context.actor_profile_id = original_actor
                else:
                    self.request_context.actor_profile_id = str(original_profile)
                if original_execution is not None:
                    self.request_context.execution_profile_id = original_execution
                else:
                    self.request_context.execution_profile_id = str(original_profile)
            else:
                for key in ("profile_id", "actor_profile_id", "execution_profile_id"):
                    if hasattr(self.request_context, key):
                        delattr(self.request_context, key)
        if include_children:
            for profile_id in sorted(self.config.get("profiles", {})):
                profile = self.config["profiles"].get(profile_id)
                if (not isinstance(profile, dict) or
                        str(profile.get("kind") or STANDARD_PROFILE_KIND).strip().lower()
                        != CHILD_PROFILE_KIND):
                    continue
                permissions = client.get("profile_grants", {}).get(
                    str(profile_id), [])
                if (not isinstance(permissions, list) or
                        "use_profile" not in permissions):
                    continue
                profiles.append(self._child_profile_public_dto(
                    client, str(profile_id), profile))
        return {
            "profiles": profiles,
            "suggested_profile_id": suggested_profile_id or available_profile_id,
        }

    def _child_profile_public_dto(self, client: dict[str, Any],
                                  actor_id: str,
                                  actor: dict[str, Any]) -> dict[str, Any]:
        """Project a child actor without copying the parent's execution data."""
        parent_id = str(actor.get("parent_profile_id") or "").strip()
        parent = self.config.get("profiles", {}).get(parent_id)
        parent_available = (self._is_standard_profile(parent) and
                            parent.get("enabled") is True and
                            not profile_deletion_pending(parent))
        _monotonic, wall = self._child_time_now(actor)
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            policy = {
                "reason": "policy_unavailable", "remaining_daily_seconds": 0,
                "playable_now_seconds": 0, "next_allowed_at": "",
            }
        if not parent_available:
            policy = {**policy, "reason": "parent_unavailable",
                      "playable_now_seconds": 0}
        session = self.child_time_session
        client_id = self.broker_client_id(client)
        active_session = (isinstance(session, dict) and
                          str(session.get("actor_profile_id") or "") == actor_id and
                          str(session.get("client_id") or "") == client_id)
        grants = client.get("profile_grants", {})
        permissions = grants.get(actor_id, []) if isinstance(grants, dict) else []
        use_profile = isinstance(permissions, list) and "use_profile" in permissions
        remote_grant = use_profile and isinstance(permissions, list) and \
            "remote_sign_in" in permissions
        if parent_available:
            try:
                login = self.profile_login_state(parent)
            except (AttributeError, OSError, TimeoutError, TypeError, ValueError):
                login = {"session_state": "unknown",
                         "remote_sign_in_state": "unavailable",
                         "reason": "broker_unavailable"}
        else:
            login = {"session_state": "unknown",
                     "remote_sign_in_state": "disabled",
                     "reason": "parent_unavailable"}
        vibepollo_online = False
        playnite_online = False
        playnite_connector = False
        virtualhere_online = False
        if parent_available:
            vibepollo_online, _ = self.proxy(
                "vibepollo", "/health", timeout=1.0, profile_id=parent_id)
            playnite_online, playnite_observation = self.proxy(
                "game_provider", "/observation", timeout=1.0,
                profile_id=parent_id)
            virtualhere_ok, virtualhere = self.proxy(
                "discord", "/virtualhere-state", timeout=1.5,
                profile_id=parent_id)
            provider = playnite_observation.get("provider") \
                if isinstance(playnite_observation, dict) else None
            playnite_connector = playnite_online and isinstance(provider, dict) \
                and provider.get("connector_evidence") == "VERIFIED"
            virtualhere_online = virtualhere_ok and isinstance(
                virtualhere, dict) and bool(virtualhere.get("installed", False))
        reason = str(policy.get("reason") or "none")
        if not actor.get("enabled") or profile_deletion_pending(actor):
            reason = "child_profile_unavailable"
            policy = {**policy, "playable_now_seconds": 0}
        if not parent_available:
            reason = "parent_unavailable"
        elif reason not in {"child_profile_unavailable", "parent_unavailable"}:
            session_gate = self._child_time_session_gate(
                session, actor_id, client_id)
            if session_gate:
                reason = session_gate
                policy = {**policy, "playable_now_seconds": 0}
            else:
                try:
                    self._child_time_client_uuid(client, parent_id)
                    stream_binding_available = True
                except PermissionError:
                    stream_binding_available = False
                if not stream_binding_available and reason == "none":
                    reason = "pairing_required"
                    policy = {**policy, "playable_now_seconds": 0}
        return {
            "id": actor_id,
            "name": str(actor.get("display_name") or actor.get("name") or actor_id).strip()[:80],
            "kind": CHILD_PROFILE_KIND,
            "parent_profile_id": parent_id,
            "execution_profile_id": parent_id,
            "avatar_id": str(actor.get("avatar_id") or "").strip()[:128],
            "enabled": actor.get("enabled") is True and
                       not profile_deletion_pending(actor),
            "permissions": {
                "use_profile": use_profile,
                "remote_sign_in": remote_grant,
                "manage_children": False,
            },
            "pin_required": False,
            "policy_revision": self._child_profile_revision(
                actor.get("policy_revision", 0)),
            "remaining_daily_seconds": int(max(
                0.0, float(policy.get("remaining_daily_seconds", 0.0)))),
            "playable_now_seconds": int(max(
                0.0, float(policy.get("playable_now_seconds", 0.0)))),
            "next_allowed_at": str(policy.get("next_allowed_at") or ""),
            "window_end": str(policy.get("window_end") or ""),
            "server_time": wall.astimezone(timezone.utc).isoformat().replace(
                "+00:00", "Z"),
            "reason": reason,
            "session_state": str(session.get("phase") or "idle")
            if active_session else "idle",
            "remote_sign_in_state": (str(login.get("remote_sign_in_state") or
                                             "unavailable") if remote_grant else
                                      "disabled"),
            "vibepollo_bridge_online": bool(vibepollo_online),
            "playnite_bridge_online": bool(playnite_online),
            "playnite_connector_connected": bool(playnite_connector),
            "virtualhere_available": virtualhere_online,
        }

    @staticmethod
    def _discord_id(value: Any, name: str) -> str:
        result = str(value or "").strip()
        if not DISCORD_ID_PATTERN.fullmatch(result):
            raise ValueError(f"Invalid Discord {name}.")
        return result

    def discord_status(self) -> dict[str, Any]:
        bridge_online, health = self.proxy("discord", "/health", timeout=1.5)
        message = str(health.get("message", "")) if isinstance(health, dict) else ""
        fields: dict[str, str] = {}
        for item in message.split("\t"):
            if "=" in item:
                key, value = item.split("=", 1)
                fields[key.strip()] = value.strip()
        return {
            "ok": True,
            "bridge_online": bridge_online,
            "rpc_connected": fields.get("connected", "false").lower() == "true",
            "authenticated": fields.get("authenticated", "false").lower() == "true",
            "pipe": fields.get("pipe", ""),
            "error": fields.get("error", "") if bridge_online else str(health.get("error", "Bridge unavailable.")),
        }

    def discord_ready(self) -> tuple[bool, str]:
        status = self.discord_status()
        if not status["bridge_online"]:
            return False, "Discord Bridge is offline on this host."
        if not status["rpc_connected"]:
            detail = str(status.get("error", "")).strip()
            if detail:
                return False, detail
            return False, "Discord client is not running in the Bridge user session."
        if not status["authenticated"]:
            return False, "Discord RPC authorization is required."
        return True, ""

    def discord_home(self, force: bool = False) -> tuple[int, Any]:
        ready, error = self.discord_ready()
        if not ready:
            return HTTPStatus.CONFLICT, {"ok": False, "error": error}
        suffix = "?force=true" if force else ""
        ok, result = self.proxy("discord", "/home" + suffix, timeout=8.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "home": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(result, "Unable to load Discord servers."),
        }

    def discord_channels(self, guild_id: Any, force: bool = False) -> tuple[int, Any]:
        ready, error = self.discord_ready()
        if not ready:
            return HTTPStatus.CONFLICT, {"ok": False, "error": error}
        guild = self._discord_id(guild_id, "guild ID")
        query = {"guild_id": guild}
        if force:
            query["force"] = "true"
        ok, result = self.proxy("discord", "/channels-view?" + urllib.parse.urlencode(query), timeout=10.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "channels": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(result, "Unable to load Discord channels."),
        }

    def discord_voice(self, force: bool = False) -> tuple[int, Any]:
        ready, error = self.discord_ready()
        if not ready:
            return HTTPStatus.CONFLICT, {"ok": False, "error": error}
        suffix = "?force=true" if force else ""
        ok, result = self.proxy("discord", "/snapshot" + suffix, timeout=8.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "voice": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(result, "Unable to load Discord voice state."),
        }

    def discord_audio(self) -> tuple[int, Any]:
        ready, error = self.discord_ready()
        if not ready:
            return HTTPStatus.CONFLICT, {"ok": False, "error": error}
        ok, result = self.proxy("discord", "/audio-state", timeout=10.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "audio": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(result, "Unable to load audio devices."),
        }

    def audio_action(self, action: str, body: dict[str, Any]) -> tuple[int, Any]:
        if action == "select":
            scope = str(body.get("scope", "")).lower()
            kind = str(body.get("kind", "")).lower()
            device_id = str(body.get("device_id", ""))
            if scope not in {"discord", "system"}:
                raise ValueError("Audio device scope must be discord or system.")
            if kind not in {"input", "output"}:
                raise ValueError("Audio device kind must be input or output.")
            if not AUDIO_DEVICE_ID_PATTERN.fullmatch(device_id):
                raise ValueError("Invalid audio device ID.")
            if scope == "discord":
                ready, error = self.discord_ready()
                if not ready:
                    return HTTPStatus.CONFLICT, {"ok": False, "error": error}
                path = "/select-device?" + urllib.parse.urlencode({
                    "kind": kind, "device_id": device_id})
            else:
                path = "/system-audio-default?" + urllib.parse.urlencode({
                    "device_id": device_id})
        elif action == "volume":
            delta = int(body.get("delta", 0))
            if delta not in {-5, 5}:
                raise ValueError("System volume delta must be -5 or 5.")
            path = "/system-audio-volume?" + urllib.parse.urlencode({"delta": delta})
        elif action == "mute":
            path = "/system-audio-mute"
        else:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown audio action."}
        ok, result = self.proxy("discord", path, timeout=10.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "action": action,
            "result": result,
            "error": "" if ok else self.upstream_error(result, "Audio action failed."),
        }

    @staticmethod
    def upstream_error(result: Any, fallback: str) -> str:
        if isinstance(result, dict):
            value = result.get("error") or result.get("message")
            if value:
                return str(value)[:500]
        return fallback

    def discord_action(self, action: str, body: dict[str, Any]) -> tuple[int, Any]:
        if action == "start":
            path = "/start-discord"
            timeout = 8.0
        elif action == "connect":
            path = "/authorize" + ("?force=true" if bool(body.get("force", False)) else "")
            timeout = 20.0
        elif action == "join":
            query = {
                "channel_id": self._discord_id(body.get("channel_id"), "channel ID"),
                "guild_id": self._discord_id(body.get("guild_id"), "guild ID"),
                "guild_name": str(body.get("guild_name", ""))[:100],
                "channel_name": str(body.get("channel_name", ""))[:100],
            }
            path = "/join-advanced?" + urllib.parse.urlencode(query)
            timeout = 20.0
        elif action == "leave":
            path = "/leave"
            timeout = 12.0
        elif action in {"mute", "deafen"}:
            value = str(body.get("value", "toggle")).lower()
            if value not in {"toggle", "true", "false"}:
                raise ValueError("Discord voice value must be toggle, true, or false.")
            path = f"/{action}?" + urllib.parse.urlencode({"value": value})
            timeout = 8.0
        elif action == "user-volume":
            user_id = self._discord_id(body.get("user_id"), "user ID")
            query = {"user_id": user_id}
            if "volume" in body:
                volume = int(body.get("volume", -1))
                if volume < 0 or volume > 200 or volume % 10 != 0:
                    raise ValueError("Discord participant volume must be 0..200 in steps of 10.")
                query["value"] = volume
            else:
                delta = int(body.get("delta", 0))
                if delta not in {-10, 10}:
                    raise ValueError("Discord participant volume delta must be -10 or 10.")
                query["delta"] = delta
            path = "/user-volume?" + urllib.parse.urlencode(query)
            timeout = 8.0
        elif action == "user-mute":
            user_id = self._discord_id(body.get("user_id"), "user ID")
            path = "/user-mute?" + urllib.parse.urlencode({"user_id": user_id})
            timeout = 8.0
        else:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown Discord action."}
        if action not in {"start", "connect"}:
            ready, error = self.discord_ready()
            if not ready:
                return HTTPStatus.CONFLICT, {"ok": False, "error": error}
        ok, result = self.proxy("discord", path, timeout=timeout)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "action": action,
            "result": result,
            "error": "" if ok else self.upstream_error(result, "Discord action failed."),
        }

    def virtualhere_state(self, force: bool = False) -> tuple[int, Any]:
        suffix = "?force=true" if force else ""
        ok, result = self.proxy("discord", "/virtualhere-state" + suffix, timeout=8.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "virtualhere": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(result, "Unable to load VirtualHere state."),
        }

    def virtualhere_action(self, action: str, body: dict[str, Any]) -> tuple[int, Any]:
        if action == "restart":
            path = "/repair-virtualhere"
            timeout = 12.0
        elif action in {"use", "stop", "auto"}:
            address = str(body.get("address", "")).strip()
            if not VIRTUALHERE_ADDRESS_PATTERN.fullmatch(address):
                raise ValueError("Invalid VirtualHere device address.")
            path = "/virtualhere-action?" + urllib.parse.urlencode({
                "action": action,
                "address": address,
            })
            timeout = 10.0
        else:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown VirtualHere action."}
        ok, result = self.proxy("discord", path, timeout=timeout)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "action": action,
            "result": result,
            "error": "" if ok else self.upstream_error(result, "VirtualHere action failed."),
        }

    def vibepollo_status(self) -> tuple[int, Any]:
        health_ok, health = self.proxy("vibepollo", "/health")
        if not health_ok:
            return HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "health": health}
        snapshot_ok, snapshot = self.proxy("vibepollo", "/snapshot")
        return HTTPStatus.OK, {
            "ok": True,
            "health": health,
            "host": snapshot.get("host", {}) if snapshot_ok and isinstance(snapshot, dict) else {},
            "bridge": snapshot.get("bridge", {}) if snapshot_ok and isinstance(snapshot, dict) else {},
        }

    def vibepollo_action(self, action: str) -> tuple[int, Any]:
        allowed = {"restart", "reset-display", "export-logs"}
        if action not in allowed:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown Vibepollo action."}
        ok, result = self.proxy("vibepollo", f"/action/{action}", timeout=20.0 if action == "export-logs" else 5.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {"ok": ok, "action": action, "result": result}

    def vibepollo_ensure_app(self, body: dict[str, Any]) -> tuple[int, Any]:
        game_id = self._playnite_game_id(body.get("playnite_game_id"))
        name = str(body.get("name") or "").strip()
        if not name or len(name) > 200 or re.search(r"[\x00-\x1f\x7f]", name):
            raise ValueError("Invalid application name.")
        profile_id = self.profile_id
        key = f"{profile_id}:{game_id}"
        with self.lock:
            current = self.vibepollo_app_operations.get(key, {})
            if current.get("state") == "preparing":
                return HTTPStatus.ACCEPTED, {"ok": True, **current}
            if current.get("state") == "ready":
                return HTTPStatus.OK, {"ok": True, "state": "ready",
                                      "app": current.get("app", {})}
            operation = {"state": "preparing", "playnite_game_id": game_id,
                         "name": name, "updated_at": int(time.time())}
            self.vibepollo_app_operations[key] = operation

        def ensure() -> None:
            self.select_profile(profile_id)
            ok, result = self.proxy_json("vibepollo", "/apps/ensure", {
                "playnite_game_id": game_id, "name": name,
            }, timeout=12.0)
            with self.lock:
                if ok:
                    self.vibepollo_app_operations[key] = {
                        "state": "ready", "playnite_game_id": game_id,
                        "name": name, "app": result if isinstance(result, dict) else {},
                        "updated_at": int(time.time())}
                else:
                    self.vibepollo_app_operations[key] = {
                        "state": "error", "playnite_game_id": game_id, "name": name,
                        "error": self.upstream_error(
                            result, "Unable to create the Vibepollo application."),
                        "updated_at": int(time.time())}

        threading.Thread(target=ensure, name="vibepollo-app-ensure", daemon=True).start()
        return HTTPStatus.ACCEPTED, {"ok": True, **operation}

    def vibepollo_app_status(self, game_id: Any) -> tuple[int, Any]:
        normalized = self._playnite_game_id(game_id)
        key = f"{self.profile_id}:{normalized}"
        with self.lock:
            operation = dict(self.vibepollo_app_operations.get(key, {}))
        if operation:
            return HTTPStatus.OK, {"ok": operation.get("state") != "error", **operation}
        path = "/apps/status?" + urllib.parse.urlencode({"playnite_game_id": normalized})
        ok, result = self.proxy("vibepollo", path, timeout=3.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), (
            result if ok and isinstance(result, dict) else {
                "ok": False, "state": "error",
                "error": self.upstream_error(result, "Unable to read Vibepollo application state.")})

    @staticmethod
    def _playnite_game_id(value: Any) -> str:
        result = str(value or "").strip()
        if not (PLAYNITE_GAME_ID_PATTERN.fullmatch(result)
                or GAME_RECORD_ID_PATTERN.fullmatch(result)):
            raise ValueError("Invalid game record ID.")
        if ":" in result:
            provider, provider_id = result.split(":", 1)
            return provider.lower() + ":" + provider_id
        return result.lower()

    @staticmethod
    def _child_parent_policy_revision(parent: dict[str, Any]) -> int:
        value = parent.get(CHILD_PARENT_POLICY_REVISION_FIELD, 0)
        if (isinstance(value, bool) or not isinstance(value, int) or value < 0):
            raise ValueError("The parent child policy revision is invalid.")
        return value

    @staticmethod
    def _child_catalog_revision(value: Any) -> str:
        revision = "0" if value is None or str(value).strip() == "" else str(value).strip()
        if len(revision) > 128 or not PLAYNITE_CURSOR_PATTERN.fullmatch(revision):
            raise PermissionError("Child game policy is unavailable.")
        return revision

    @staticmethod
    def _child_game_key(parent_profile_id: str, canonical_game_id: str) -> str:
        parent_id = str(parent_profile_id or "").strip()
        canonical = str(canonical_game_id or "").strip()
        if (not PROFILE_ID_PATTERN.fullmatch(parent_id) or
                not GAME_RECORD_ID_PATTERN.fullmatch(canonical) or
                canonical.split(":", 1)[0] != canonical.split(":", 1)[0].lower()):
            raise PermissionError("The game is unavailable to this child.")
        key = parent_id + "/" + canonical
        if not CHILD_GAME_KEY_PATTERN.fullmatch(key):
            raise PermissionError("The game is unavailable to this child.")
        return key

    def _resolve_child_catalog_game(self, parent_profile_id: str,
                                    game_id: Any) -> tuple[str, str, str]:
        """Resolve a parent catalog member without trusting a child supplied alias."""
        parent_id = str(parent_profile_id or "").strip()
        parent = self.config.get("profiles", {}).get(parent_id)
        if (not PROFILE_ID_PATTERN.fullmatch(parent_id) or
                not self._is_standard_profile(parent) or
                parent.get("enabled") is not True or
                profile_deletion_pending(parent)):
            raise PermissionError("The child profile parent is unavailable.")
        requested = str(game_id or "").strip()
        if not (PLAYNITE_GAME_ID_PATTERN.fullmatch(requested) or
                GAME_RECORD_ID_PATTERN.fullmatch(requested)):
            raise ValueError("Invalid game record ID.")
        ok, result = self.proxy_json(
            "game_provider", "/library/resolve", {"game_id": requested},
            timeout=8.0, profile_id=parent_id)
        if not ok or not isinstance(result, dict):
            raise PermissionError("The game is unavailable to this child.")
        canonical = str(result.get("canonical_game_id") or "").strip()
        if not GAME_RECORD_ID_PATTERN.fullmatch(canonical):
            raise PermissionError("The game is unavailable to this child.")
        provider = canonical.split(":", 1)[0]
        if provider != provider.lower():
            raise PermissionError("The game is unavailable to this child.")
        catalog_revision = self._child_catalog_revision(result.get("revision"))
        return canonical, self._child_game_key(parent_id, canonical), catalog_revision

    def _child_request_game(self, parent_profile_id: str, requested: Any) -> str:
        """Normalized canonical id of a child request's game.

        A raw Playnite GUID can name a steam:/epic: record, so a request id is only
        comparable with a time session's canonical game after provider resolution."""
        canonical, _key, _revision = self._resolve_child_catalog_game(parent_profile_id, requested)
        return self._child_time_normalize_game_id(canonical)

    def _child_policy_context(self, client: dict[str, Any], actor_profile_id: str,
                              permission: str = "use_profile") \
            -> tuple[str, str, dict[str, Any], dict[str, Any]]:
        self.refresh_registry_if_changed()
        actor_id = str(actor_profile_id or "").strip()
        self.authorize_child_profile(client, actor_id, permission)
        actor = self.config.get("profiles", {}).get(actor_id)
        parent_id = str(actor.get("parent_profile_id") or "").strip()
        parent = self.config.get("profiles", {}).get(parent_id)
        if (not self._is_standard_profile(parent) or parent.get("enabled") is not True or
                profile_deletion_pending(parent)):
            raise PermissionError("The child profile parent is unavailable.")
        return actor_id, parent_id, parent, actor

    def _authorize_child_game(self, client: dict[str, Any], actor_profile_id: str,
                              game_id: Any, permission: str = "use_profile") \
            -> tuple[str, str, str, dict[str, Any], dict[str, Any]]:
        """Return actor, parent, canonical ID, parent and catalog metadata for one grant."""
        actor_id, parent_id, parent, actor = self._child_policy_context(
            client, actor_profile_id, permission)
        canonical, key, catalog_revision = self._resolve_child_catalog_game(
            parent_id, game_id)
        allowed = actor.get("allowed_game_keys", [])
        if not isinstance(allowed, list) or key not in allowed:
            raise PermissionError("game_not_shared")
        return actor_id, parent_id, canonical, parent, {
            "game_key": key,
            "catalog_revision": catalog_revision,
        }

    def child_game_sharing_snapshot(self, client: dict[str, Any],
                                    parent_profile_id: str, game_id: Any,
                                    session_id: str | None,
                                    authorization_id: str) -> dict[str, Any]:
        """Read the parent-scoped grant snapshot for the later Host Control writer."""
        self.refresh_registry_if_changed()
        parent_id = str(parent_profile_id or "").strip()
        self.require_child_management(
            client, parent_id, session_id, authorization_id)
        parent = self.config.get("profiles", {}).get(parent_id)
        if not self._is_standard_profile(parent):
            raise PermissionError("Child management is not authorized for this parent profile.")
        canonical, game_key, catalog_revision = self._resolve_child_catalog_game(
            parent_id, game_id)
        children = []
        for child_id in sorted(self.config.get("profiles", {})):
            child = self.config["profiles"].get(child_id)
            if (not isinstance(child, dict) or
                    str(child.get("kind") or STANDARD_PROFILE_KIND).strip().lower() !=
                    CHILD_PROFILE_KIND or
                    str(child.get("parent_profile_id") or "").strip() != parent_id):
                continue
            allowed = child.get("allowed_game_keys", [])
            children.append({
                "id": str(child_id),
                "name": str(child.get("display_name") or child.get("name") or child_id)[:80],
                "enabled": child.get("enabled") is True and
                           not profile_deletion_pending(child),
                "granted": isinstance(allowed, list) and game_key in allowed,
            })
        return {
            "ok": True,
            "parent_profile_id": parent_id,
            "game_key": game_key,
            "canonical_game_id": canonical,
            "catalog_revision": catalog_revision,
            "revision": self._child_parent_policy_revision(parent),
            "children": children,
        }

    def prepare_child_game_sharing(self, client: dict[str, Any],
                                   parent_profile_id: str, game_id: Any,
                                   child_profile_ids: Any,
                                   expected_revision: Any, request_id: str,
                                   session_id: str | None,
                                   authorization_id: str,
                                   check_revision: bool = True,
                                   check_children: bool = True) -> dict[str, Any]:
        """Validate a complete grant selection for the existing registry writer.

        The Gateway intentionally returns a writer request instead of modifying the
        registry itself.  Host Control remains the sole authorized registry writer;
        CP-04 supplies the narrow transport for this DTO.
        """
        self.refresh_registry_if_changed()
        parent_id = str(parent_profile_id or "").strip()
        self.require_child_management(
            client, parent_id, session_id, authorization_id)
        if not REQUEST_ID_PATTERN.fullmatch(str(request_id or "").strip()):
            raise ValueError("A valid child sharing request ID is required.")
        if isinstance(expected_revision, bool):
            raise ValueError("Invalid child policy revision.")
        try:
            expected = int(expected_revision)
        except (TypeError, ValueError):
            raise ValueError("Invalid child policy revision.") from None
        if expected < 0:
            raise ValueError("Invalid child policy revision.")
        if not isinstance(child_profile_ids, list):
            raise ValueError("Child profile IDs must be a list.")
        normalized_ids = []
        for value in child_profile_ids:
            child_id = str(value or "").strip()
            if (not PROFILE_ID_PATTERN.fullmatch(child_id) or
                    child_id in normalized_ids):
                raise ValueError("Child profile selection contains an invalid ID.")
            normalized_ids.append(child_id)
        parent = self.config.get("profiles", {}).get(parent_id)
        if not self._is_standard_profile(parent):
            raise PermissionError("Child management is not authorized for this parent profile.")
        revision = self._child_parent_policy_revision(parent)
        if check_revision and expected != revision:
            raise RuntimeError("child_policy_revision_stale")
        canonical, game_key, catalog_revision = self._resolve_child_catalog_game(
            parent_id, game_id)
        own_children = {
            str(child_id): child for child_id, child in self.config.get("profiles", {}).items()
            if isinstance(child, dict) and
            str(child.get("kind") or STANDARD_PROFILE_KIND).strip().lower() == CHILD_PROFILE_KIND and
            str(child.get("parent_profile_id") or "").strip() == parent_id
        }
        if check_children and any(child_id not in own_children
                                  for child_id in normalized_ids):
            raise PermissionError("Child profile selection contains a foreign child.")
        return {
            "ok": True,
            "parent_profile_id": parent_id,
            "game_key": game_key,
            "canonical_game_id": canonical,
            "catalog_revision": catalog_revision,
            "child_profile_ids": normalized_ids,
            "expected_revision": expected,
            "request_id": str(request_id).strip(),
        }

    def set_child_game_sharing(self, client: dict[str, Any],
                               parent_profile_id: str, game_id: Any,
                               child_profile_ids: Any, expected_revision: Any,
                               request_id: str, session_id: str | None,
                               authorization_id: str) -> tuple[int, dict[str, Any]]:
        """Validate a host-resolved selection, then let Host Control commit it."""
        prepared = self.prepare_child_game_sharing(
            client, parent_profile_id, game_id, child_profile_ids,
            expected_revision, request_id, session_id, authorization_id,
            check_revision=False, check_children=False)
        payload = {
            "operation": "sharing_set",
            "parent_profile_id": prepared["parent_profile_id"],
            "game_key": prepared["game_key"],
            "selected_child_ids": prepared["child_profile_ids"],
            "parent_catalog_game_keys": [prepared["game_key"]],
            "expected_revision": prepared["expected_revision"],
            "request_id": prepared["request_id"],
        }
        status, result = self._child_profile_writer_request(payload)
        if isinstance(result, dict):
            result = {
                **result,
                "parent_profile_id": result.get(
                    "parent_profile_id", prepared["parent_profile_id"]),
                "game_key": result.get("game_key", prepared["game_key"]),
                "selected_child_ids": result.get(
                    "selected_child_ids", prepared["child_profile_ids"]),
            }
        return status, result

    @staticmethod
    def _child_profile_revision(value: Any) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError("Invalid child policy revision.")
        return value

    @staticmethod
    def _child_profile_request_id(value: Any) -> str:
        result = str(value or "").strip()
        if not REQUEST_ID_PATTERN.fullmatch(result):
            raise ValueError("A valid child profile request ID is required.")
        return result

    @staticmethod
    def _child_profile_int(value: Any, default: int, name: str) -> int:
        if value is None:
            return default
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"Invalid child profile {name}.")
        return value

    @classmethod
    def _child_profile_schedule_dto(cls, profile: dict[str, Any]) -> dict[str, Any]:
        schedule = profile.get("schedule") if isinstance(profile, dict) else None
        if not isinstance(schedule, dict):
            raise ValueError("Child profile schedule is invalid.")
        weekdays = schedule.get("weekdays")
        if not isinstance(weekdays, dict):
            weekdays = schedule
        result: dict[str, Any] = {}
        for day in CHILD_PROFILE_WEEKDAYS:
            entry = weekdays.get(day)
            if not isinstance(entry, dict):
                raise ValueError("Child profile schedule is invalid.")
            enabled = entry.get("enabled", False)
            if not isinstance(enabled, bool):
                raise ValueError("Invalid child profile day enabled state.")
            start = cls._child_profile_int(entry.get("start_minute"), 0,
                                           "schedule start")
            end = cls._child_profile_int(entry.get("end_minute"), 1440,
                                         "schedule end")
            limit = cls._child_profile_int(entry.get("daily_limit_seconds"), 0,
                                           "daily time limit")
            if (start < 0 or start > 1440 or end <= 0 or end > 1440 or
                    start >= end or limit < 0 or
                    limit > CHILD_PROFILE_MAX_DAILY_LIMIT_SECONDS or limit % 60):
                raise ValueError("Child profile schedule values are invalid.")
            result[day] = {
                "enabled": enabled,
                "start_minute": start,
                "end_minute": end,
                "daily_limit_seconds": limit,
            }
        return {"weekdays": result}

    @classmethod
    def _child_profile_draft(cls, value: Any) -> dict[str, Any]:
        if not isinstance(value, dict):
            raise ValueError("Child profile draft must be an object.")
        if "allowed_game_keys" in value and value.get("allowed_game_keys") not in (
                None, []):
            raise ValueError("Child game grants are managed separately.")
        name = str(value.get("name") or "").strip()
        avatar_id = str(value.get("avatar_id") or "").strip()
        controls = set(chr(code) for code in range(0, 32)) | {chr(127)}
        if (not name or len(name) > 80 or any(character in controls for character in name) or
                len(avatar_id) > 128 or any(character in controls for character in avatar_id)):
            raise ValueError("Child profile name or avatar is invalid.")
        enabled = value.get("enabled")
        if not isinstance(enabled, bool):
            raise ValueError("Child profile enabled state is invalid.")
        schedule = value.get("schedule")
        if not isinstance(schedule, dict):
            raise ValueError("Child profile schedule is invalid.")
        normalized_schedule = cls._child_profile_schedule_dto({"schedule": schedule})
        return {
            "name": name,
            "avatar_id": avatar_id,
            "enabled": enabled,
            "schedule": normalized_schedule,
        }

    @classmethod
    def _child_profile_dto(cls, profile: dict[str, Any]) -> dict[str, Any]:
        profile_id = str(profile.get("id") or "").strip()
        parent_id = str(profile.get("parent_profile_id") or "").strip()
        if (not PROFILE_ID_PATTERN.fullmatch(profile_id) or
                not PROFILE_ID_PATTERN.fullmatch(parent_id)):
            raise ValueError("Child profile identity is invalid.")
        return {
            "id": profile_id,
            "name": str(profile.get("display_name") or
                         profile.get("name") or profile_id).strip()[:80],
            "avatar_id": str(profile.get("avatar_id") or "").strip()[:128],
            "enabled": profile.get("enabled") is True,
            "parent_profile_id": parent_id,
            "policy_revision": cls._child_profile_revision(
                profile.get("policy_revision", 0)),
            "schedule": cls._child_profile_schedule_dto(profile),
        }

    def _child_profile_management_context(
            self, client: dict[str, Any], parent_profile_id: str,
            session_id: str | None, authorization_id: str) \
            -> tuple[str, dict[str, Any], int]:
        self.refresh_registry_if_changed()
        parent_id = str(parent_profile_id or "").strip()
        if not PROFILE_ID_PATTERN.fullmatch(parent_id):
            raise ValueError("A valid parent profile ID is required.")
        self.require_child_management(client, parent_id, session_id, authorization_id)
        parent = self.config.get("profiles", {}).get(parent_id)
        if not self._is_standard_profile(parent):
            raise PermissionError("Child management is not authorized for this parent profile.")
        revision = parent.get(CHILD_PARENT_POLICY_REVISION_FIELD, 0)
        return parent_id, parent, self._child_profile_revision(revision)

    def _child_profile_writer_request(self, payload: dict[str, Any]) \
            -> tuple[int, dict[str, Any]]:
        writer = getattr(self.login_broker, "child_profile_api", None)
        if not callable(writer):
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "error": "broker_unavailable",
                "reason": "broker_unavailable",
            }
        try:
            response = writer(payload)
        except (OSError, TimeoutError, ValueError, TypeError) as error:
            DIAGNOSTICS.record("child-profile-writer-failed", level="WARN", error=error)
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "error": "broker_unavailable",
                "reason": "broker_unavailable",
            }
        if not isinstance(response, dict):
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False, "error": "invalid_writer_response",
                "reason": "invalid_writer_response",
            }
        body = response.get("result")
        if not isinstance(body, dict):
            body = response if "ok" in response else None
        if response.get("success") is False:
            state = str(response.get("state") or "action_required")
            reason = str(response.get("reason") or state)
            status = (HTTPStatus.SERVICE_UNAVAILABLE
                      if state in {"broker_unavailable", "unavailable"}
                      else HTTPStatus.CONFLICT)
            return status, {"ok": False, "error": reason, "reason": reason,
                            "state": state}
        if not isinstance(body, dict):
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False, "error": "invalid_writer_response",
                "reason": "invalid_writer_response",
            }
        if body.get("ok") is False:
            reason = str(body.get("reason") or body.get("error") or
                         "child_profile_write_failed")
            if reason in {"child_policy_revision_stale", "request_id_reused"}:
                status = HTTPStatus.CONFLICT
            elif reason == "child_profile_not_owned":
                status = HTTPStatus.FORBIDDEN
            elif reason in {"invalid_child_profile_request", "invalid_child_game_key",
                            "game_not_in_parent_catalog"}:
                status = HTTPStatus.BAD_REQUEST
            else:
                status = HTTPStatus.BAD_GATEWAY
            return status, {**body, "ok": False, "error": reason,
                            "reason": reason}
        if body.get("cleanup_required") is True:
            affected = body.get("affected_child_profile_ids")
            affected_ids = {str(value).strip() for value in affected
                            if str(value).strip()} if isinstance(affected, list) else set()
            with self.lock:
                active = dict(self.child_time_session) \
                    if isinstance(self.child_time_session, dict) else None
            active_actor = str(active.get("actor_profile_id") or "") \
                if active else ""
            cleanup_required = bool(active and active_actor in affected_ids)
            changed_game = str(body.get("game_key") or "").strip()
            if cleanup_required and changed_game:
                changed_game = changed_game.split("/", 1)[-1]
                cleanup_required = (
                    self._child_time_normalize_game_id(changed_game) ==
                    self._child_time_normalize_game_id(active.get("game_id")))
            if cleanup_required and active_actor:
                cleanup_status, cleanup_result = self.child_time_revoke(
                    active_actor, str(active.get("game_id") or ""))
                cleanup_required = not (
                    cleanup_status < HTTPStatus.BAD_REQUEST and
                    isinstance(cleanup_result, dict) and
                    cleanup_result.get("cleanup_required") is not True)
            body = {**body, "cleanup_required": cleanup_required}
        try:
            self.refresh_registry_if_changed()
        except (OSError, TimeoutError, TypeError, ValueError, json.JSONDecodeError):
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "error": "registry_unavailable",
                "reason": "registry_unavailable",
            }
        return HTTPStatus.OK, {**body, "ok": True}

    def child_profiles_snapshot(self, client: dict[str, Any],
                                parent_profile_id: str, session_id: str | None,
                                authorization_id: str) -> tuple[int, dict[str, Any]]:
        parent_id, _parent, revision = self._child_profile_management_context(
            client, parent_profile_id, session_id, authorization_id)
        children = []
        for profile_id, profile in self.config.get("profiles", {}).items():
            if (not isinstance(profile, dict) or
                    not self.is_child_profile(str(profile_id)) or
                    str(profile.get("parent_profile_id") or "") != parent_id):
                continue
            children.append(self._child_profile_dto(profile))
        children.sort(key=lambda value: (value["name"].casefold(), value["id"]))
        return HTTPStatus.OK, {
            "ok": True, "parent_profile_id": parent_id,
            "revision": revision, "children": children,
        }

    def create_child_profile(self, client: dict[str, Any], parent_profile_id: str,
                             session_id: str | None, authorization_id: str,
                             request_id: str, expected_revision: Any,
                             draft: dict[str, Any],
                             grant_current_device: Any = False) -> tuple[int, dict[str, Any]]:
        parent_id, _parent, revision = self._child_profile_management_context(
            client, parent_profile_id, session_id, authorization_id)
        request = self._child_profile_request_id(request_id)
        expected = self._child_profile_revision(expected_revision)
        normalized = self._child_profile_draft(draft)
        if not isinstance(grant_current_device, bool):
            raise ValueError("grant_current_device must be a boolean.")
        grant_client_id = self.broker_client_id(client) if grant_current_device else ""
        return self._child_profile_writer_request({
            "operation": "create", "parent_profile_id": parent_id,
            "request_id": request, "expected_revision": expected,
            "draft": normalized, "grant_current_device": grant_current_device,
            "grant_client_id": grant_client_id,
        })

    def update_child_profile(self, client: dict[str, Any], parent_profile_id: str,
                             child_profile_id: str, session_id: str | None,
                             authorization_id: str, request_id: str,
                             expected_revision: Any,
                             draft: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        parent_id, _parent, revision = self._child_profile_management_context(
            client, parent_profile_id, session_id, authorization_id)
        child_id = str(child_profile_id or "").strip()
        if not PROFILE_ID_PATTERN.fullmatch(child_id):
            raise ValueError("A valid child profile ID is required.")
        request = self._child_profile_request_id(request_id)
        expected = self._child_profile_revision(expected_revision)
        normalized = self._child_profile_draft(draft)
        return self._child_profile_writer_request({
            "operation": "update", "parent_profile_id": parent_id,
            "child_profile_id": child_id, "request_id": request,
            "expected_revision": expected, "draft": normalized,
        })

    def delete_child_profile(self, client: dict[str, Any], parent_profile_id: str,
                             child_profile_id: str, session_id: str | None,
                             authorization_id: str, request_id: str,
                             expected_revision: Any) -> tuple[int, dict[str, Any]]:
        parent_id, _parent, revision = self._child_profile_management_context(
            client, parent_profile_id, session_id, authorization_id)
        child_id = str(child_profile_id or "").strip()
        if not PROFILE_ID_PATTERN.fullmatch(child_id):
            raise ValueError("A valid child profile ID is required.")
        request = self._child_profile_request_id(request_id)
        expected = self._child_profile_revision(expected_revision)
        return self._child_profile_writer_request({
            "operation": "delete", "parent_profile_id": parent_id,
            "child_profile_id": child_id, "request_id": request,
            "expected_revision": expected,
        })

    get_child_profiles = child_profiles_snapshot

    @staticmethod
    def _child_time_id(value: Any, name: str) -> str:
        result = str(value or "").strip()
        if not CHILD_TIME_SESSION_ID_PATTERN.fullmatch(result):
            raise ValueError(f"A valid {name} is required.")
        return result.lower()

    @staticmethod
    def _child_time_request_id(value: Any) -> str:
        result = str(value or "").strip()
        if not REQUEST_ID_PATTERN.fullmatch(result):
            raise ValueError("A valid request ID is required.")
        return result

    def _child_time_client_uuid(self, client: dict[str, Any],
                                execution_profile_id: str) -> str:
        bindings = client.get("vibepollo_client_uuids")
        value = bindings.get(execution_profile_id) if isinstance(bindings, dict) else None
        value = str(value or "").strip()
        if not CHILD_VIBEPOOLLO_UUID_PATTERN.fullmatch(value):
            raise PermissionError("pairing_required")
        return value

    @staticmethod
    def _child_time_session_gate(session: Any, actor_id: str,
                                 client_id: str) -> str | None:
        """Return the read-only eligibility block for the active generation."""
        if not isinstance(session, dict):
            return None
        # Eligibility describes allowance; the common replacement flow closes the
        # previous actor before start. A different client still owns its session.
        if str(session.get("client_id") or "") != client_id:
            return "session_in_use"
        return None

    @staticmethod
    def _child_time_normalize_game_id(value: Any) -> str:
        try:
            return GatewayState._playnite_game_id(value).casefold()
        except ValueError:
            return str(value or "").strip().casefold()

    def _child_time_current_game(self, parent_profile_id: str,
                                 target: Any = None) -> dict[str, Any]:
        """Provider state, with the exact status of a retained target when one is given.

        Absence from a catalog scan never retires a process: a windowless or exe-less
        emulator is not a catalog match. With a target, idle also requires the exact
        target to be gone (or its PID reused), and ``target_status`` carries that proof.
        """
        token = self._child_time_process_token(target)
        target_pid = int((target or {}).get("process_id") or 0) if token else 0
        path = "/observation"
        if token and target_pid > 0:
            path += "?" + urllib.parse.urlencode({"target_process_id": target_pid,
                                                  "target_process_token": token})
        ok, result = self.proxy("game_provider", path, timeout=3.0,
                                profile_id=parent_profile_id)
        exact = {"target_token": token, "target_status": "unknown"}
        if not ok or not isinstance(result, dict):
            return {"kind": "unavailable", **exact,
                    "snapshot": result if isinstance(result, dict) else {}}
        game = result.get("game")
        process = result.get("process")
        if not isinstance(game, dict) or not isinstance(process, dict):
            return {"kind": "unavailable", **exact, "snapshot": result}
        reported = process.get("target")
        if token and target_pid > 0 and isinstance(reported, dict) \
                and reported.get("process_id") == target_pid \
                and reported.get("status") in {"gone", "reused", "alive", "unknown"}:
            exact["target_status"] = reported["status"]
        target_retired = not token or exact["target_status"] in {"gone", "reused"}
        current_id = self._state_game_id(game)
        running = process.get("running_games")
        if not isinstance(running, list) or any(
                not isinstance(item, dict) for item in running):
            return {"kind": "unavailable", **exact, "snapshot": result}
        identities = []
        for item in running:
            game_id = str(item.get("game_id") or "").strip()
            if game_id:
                identities.append({**item, "game_id": game_id})
        current_state = str(game.get("state") or "").casefold()
        game_evidence = str(game.get("evidence") or "").upper()
        process_evidence = str(process.get("evidence") or "").upper()
        running_scan_status = str(process.get("running_scan_status") or "")
        if (game_evidence == "VERIFIED" and current_id) or identities:
            current_normalized = self._child_time_normalize_game_id(current_id)
            identity = next((item for item in identities if current_normalized and
                             self._child_time_normalize_game_id(
                                 item.get("game_id")) == current_normalized), None)
            # The bridge's running scan is authoritative when the current
            # projection has no top-level id.  Use it only when unambiguous;
            # a multi-game scan remains an active/unknown parent state.
            if not current_id and len(identities) == 1:
                current_id = str(identities[0].get("game_id") or "")
                identity = identities[0]
            if identity is None and current_id:
                identity = {"game_id": current_id}
            return {"kind": "active", "game_id": current_id,
                    "identity": identity, "running_games": identities,
                    "running_scan_status": running_scan_status, **exact,
                    "snapshot": result}
        if game_evidence == "ABSENT" and process_evidence == "ABSENT" \
                and current_state == "idle" and not current_id \
                and running_scan_status == "complete" and target_retired:
            return {"kind": "idle", "game_id": "", "identity": None,
                    "running_games": [], "running_scan_status": running_scan_status,
                    **exact, "snapshot": result}
        return {"kind": "unavailable", **exact, "snapshot": result}

    @staticmethod
    def _child_time_session_matches(session: dict[str, Any], actor_id: str,
                                    client_id: str, canonical_game_id: str,
                                    session_id: str) -> bool:
        return (str(session.get("session_id") or "") == session_id and
                str(session.get("actor_profile_id") or "") == actor_id and
                str(session.get("client_id") or "") == client_id and
                GatewayState._child_time_normalize_game_id(
                    session.get("game_id")) ==
                GatewayState._child_time_normalize_game_id(canonical_game_id))

    def _child_time_find_session(self, client: dict[str, Any], actor_profile_id: str,
                                 session_id: str) -> dict[str, Any]:
        client_id = self.broker_client_id(client)
        session = self.child_time_session
        if (not isinstance(session, dict) or
                str(session.get("session_id") or "") != session_id or
                str(session.get("actor_profile_id") or "") != str(actor_profile_id) or
                str(session.get("client_id") or "") != client_id):
            raise PermissionError("child_session_binding_mismatch")
        return session

    @staticmethod
    def _child_time_process_token(identity: Any) -> str:
        value = identity.get("process_token") if isinstance(identity, dict) else ""
        return value if isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) else ""

    def _child_time_body(self, client: dict[str, Any], actor_id: str,
                         parent_id: str, actor: dict[str, Any], policy: dict[str, Any],
                         wall: datetime, session: dict[str, Any] | None = None,
                         state: str = "blocked", reason: str | None = None,
                         ok: bool = True) -> dict[str, Any]:
        playable = max(0.0, float(policy.get("playable_now_seconds", 0.0)))
        body = {
            "ok": ok,
            "state": state,
            "phase": state,
            "reason": reason or str(policy.get("reason") or "none"),
            "actor_profile_id": actor_id,
            "parent_profile_id": parent_id,
            "execution_profile_id": parent_id,
            "client_id": self.broker_client_id(client),
            "game_id": str(session.get("game_id") if isinstance(session, dict)
                             else policy.get("game_id") or ""),
            "day_key": str(policy.get("day_key") or wall.date().isoformat()),
            "weekday": str(policy.get("weekday") or self._child_time_day_name(wall.date())),
            "window_start": str(policy.get("window_start") or ""),
            "window_end": str(policy.get("window_end") or ""),
            "next_allowed_at": str(policy.get("next_allowed_at") or ""),
            "used_seconds": int(max(0.0, float(policy.get("used_seconds", 0.0)))),
            "remaining_daily_seconds": int(max(
                0.0, float(policy.get("remaining_daily_seconds", 0.0)))),
            "playable_now_seconds": int(playable),
            "deadline_epoch_ms": int((wall.timestamp() + playable) * 1000)
            if playable > 0 else 0,
            "server_time": wall.astimezone(timezone.utc).isoformat().replace(
                "+00:00", "Z"),
            "policy_revision": int(actor.get("policy_revision", 0) or 0),
            "usage_revision": int(self.child_time_usage.get("usage_revision", 0)),
            "session_id": "",
            "cleanup_required": False,
        }
        if isinstance(session, dict):
            body["session_id"] = str(session.get("session_id") or "")
            body["request_id"] = str(session.get("request_id") or "")
            body["phase"] = str(session.get("phase") or state)
            body["control_generation"] = int(
                session.get("control_generation") or 0)
            body["cleanup_required"] = body["phase"] == "cleanup_required"
        return body

    def _child_time_add_usage_locked(self, actor_id: str, day_key: str,
                                     seconds: float) -> None:
        if seconds <= 0:
            return
        days = self.child_time_usage.setdefault("days", {})
        actor_days = days.setdefault(actor_id, {})
        actor_days[day_key] = round(float(actor_days.get(day_key, 0.0)) + seconds, 3)

    def _child_time_accrue_locked(self, session: dict[str, Any], now_monotonic: float,
                                  wall: datetime, maximum: float) -> tuple[float, bool]:
        previous_monotonic = float(session.get("last_monotonic", now_monotonic))
        previous_wall = float(session.get("last_wall_epoch", wall.timestamp()))
        session["last_monotonic"] = now_monotonic
        session["last_wall_epoch"] = wall.timestamp()
        if now_monotonic < previous_monotonic:
            session["clock_rollback"] = True
            return 0.0, True
        if wall.timestamp() < previous_wall - 1.0:
            session["clock_rollback"] = True
            return 0.0, True
        if session.get("clock_rollback"):
            return 0.0, True
        elapsed = min(max(0.0, now_monotonic - previous_monotonic),
                      max(0.0, float(maximum)))
        if elapsed <= 0:
            return 0.0, False
        previous_date = datetime.fromtimestamp(previous_wall, wall.tzinfo).date()
        current_date = wall.date()
        wall_delta = wall.timestamp() - previous_wall
        if previous_date == current_date or wall_delta <= 0:
            self._child_time_add_usage_locked(
                str(session["actor_profile_id"]), current_date.isoformat(), elapsed)
        else:
            cursor_date = previous_date
            previous_fraction = 0.0
            while cursor_date < current_date:
                boundary = datetime.combine(
                    cursor_date + timedelta(days=1), datetime_time.min,
                    tzinfo=wall.tzinfo)
                fraction = min(1.0, max(previous_fraction,
                                        (boundary.timestamp() - previous_wall) / wall_delta))
                self._child_time_add_usage_locked(
                    str(session["actor_profile_id"]), cursor_date.isoformat(),
                    elapsed * (fraction - previous_fraction))
                previous_fraction = fraction
                cursor_date += timedelta(days=1)
                if current_date - cursor_date > timedelta(days=366):
                    break
            self._child_time_add_usage_locked(
                str(session["actor_profile_id"]), current_date.isoformat(),
                elapsed * (1.0 - previous_fraction))
        self._child_time_bump_revision_locked()
        return elapsed, False

    def _child_time_checkpoint_locked(self, force: bool = False) -> None:
        now = time.monotonic()
        if (force or self.child_time_last_persist <= 0 or
                now - self.child_time_last_persist >= CHILD_TIME_CHECKPOINT_SECONDS):
            self._save_child_time_usage_locked()

    @staticmethod
    def _control_text(value: Any, name: str) -> str:
        result = str(value or "").strip()
        if not REQUEST_ID_PATTERN.fullmatch(result):
            raise ValueError(f"A valid {name} is required.")
        return result

    def _control_identity(self, client: dict[str, Any]) -> tuple[str, str, str, str]:
        client_id = self.broker_client_id(client)
        actor_id = self.actor_profile_id
        execution_id = self.execution_profile_id
        client_uuid = self._child_time_client_uuid(client, execution_id)
        return client_id, actor_id, execution_id, client_uuid

    @staticmethod
    def _control_signature(operation: dict[str, Any]) -> tuple[Any, ...]:
        return tuple(operation.get(key) for key in (
            "operation_id", "client_id", "actor_profile_id", "execution_profile_id",
            "intent", "direction", "stream_session_id", "sunshine_app_id",
            "expected_generation", "child_session_id"))

    def _control_result(self, operation: dict[str, Any]) -> dict[str, Any]:
        result = {
            "ok": str(operation.get("state")) in {"permitted", "succeeded"},
            "state": str(operation.get("state") or "uncertain"),
            "reason": str(operation.get("reason") or "none"),
            "operation_id": str(operation.get("operation_id") or ""),
            "generation": int(operation.get("generation") or 0),
            "actor_lease_epoch": int(operation.get("actor_lease_epoch") or 0),
            "effect_sequence": int(operation.get("effect_sequence") or 0),
            "actor_profile_id": str(operation.get("actor_profile_id") or ""),
            "execution_profile_id": str(operation.get("execution_profile_id") or ""),
            "stream_session_id": str(operation.get("stream_session_id") or ""),
            "sunshine_app_id": int(operation.get("sunshine_app_id") or 0),
            "child_session_id": str(operation.get("child_session_id") or ""),
            "provider_game_id": str(operation.get("provider_game_id") or ""),
            "process_token": str(operation.get("process_token") or ""),
            "process_id": int(operation.get("process_id") or 0),
        }
        if isinstance(operation.get("effect_result"), dict):
            result["effect_result"] = dict(operation["effect_result"])
        if isinstance(operation.get("effect_request_sha256"), str):
            result["effect_request_sha256"] = operation["effect_request_sha256"]
        return result

    def _control_save_locked(self) -> None:
        self.child_time_usage["control"] = self.control_state
        self._save_child_time_usage_locked()

    def _control_reconcile_verified_provider_stop_locked(
            self, operation: dict[str, Any] | None = None) -> bool:
        """Retire only the process identity proven stopped by this exact stream permit."""
        stream = self.control_state.get("stream")
        if not isinstance(stream, dict) or not self._child_time_process_token(stream):
            return False
        if operation is None:
            if self.control_state.get("active") is not None:
                return False
            candidates = reversed(list(self.control_state.setdefault(
                "completed", {}).values()))
        else:
            candidates = (operation,)
        game_id = str(stream.get("provider_game_id") or "").strip()
        process_token = self._child_time_process_token(stream)
        expected_hashes = {
            ControlOperationPolicy.effect_hash("/game/stop-verified", payload)
            for payload in (
                {"game_id": game_id, "expected_process_token": process_token},
                {"force": False, "game_id": game_id,
                 "expected_process_token": process_token},
            )
        }
        exact_keys = (
            "client_id", "actor_profile_id", "execution_profile_id",
            "stream_session_id", "sunshine_app_id", "generation",
            "actor_lease_epoch", "child_session_id",
        )
        for candidate in candidates:
            result = candidate.get("effect_result") \
                if isinstance(candidate, dict) else None
            if not isinstance(result, dict) or candidate.get("state") != "succeeded" \
                    or candidate.get("intent") != "provider_transition" \
                    or candidate.get("direction") != "transition" \
                    or result.get("accepted") is not True \
                    or result.get("command") != "stop" \
                    or result.get("stopped_current") is not True \
                    or self._child_time_normalize_game_id(
                        result.get("stopped_game_id")) != \
                    self._child_time_normalize_game_id(game_id) \
                    or candidate.get("effect_request_sha256") not in expected_hashes \
                    or any(candidate.get(key) != stream.get(key)
                           for key in exact_keys):
                continue
            stream.update({
                "provider_game_id": "", "process_token": "", "process_id": 0,
            })
            DIAGNOSTICS.record(
                "control-provider-process-retired",
                generation=stream.get("generation"),
                actor_lease_epoch=stream.get("actor_lease_epoch"),
                stream_session_id=stream.get("stream_session_id"))
            return True
        return False

    def _control_bind_process_identity_locked(self, session: dict[str, Any]) -> bool:
        """Atomically bind the retained actor/time attachment and exact process."""
        if self.child_time_session is session \
                and self.child_terminal_operation.in_progress(session):
            return False
        token = self._child_time_process_token(session)
        game_id = self._child_time_normalize_game_id(session.get("game_id"))
        stream = self.control_state.get("stream")
        if not game_id:
            return False
        if not isinstance(stream, dict):
            return not token
        if any(stream.get(key) != session.get(key) for key in (
                "client_id", "execution_profile_id")):
            return False
        previous_token = self._child_time_process_token(stream)
        previous_game = self._child_time_normalize_game_id(
            stream.get("provider_game_id"))
        if token and previous_token and (previous_token != token or
                                         previous_game and previous_game != game_id):
            return False
        actor_handoff = stream.get("actor_profile_id") != session.get(
            "actor_profile_id") or stream.get("child_session_id") != session.get(
                "session_id")
        if actor_handoff:
            if isinstance(self.control_state.get("active"), dict):
                return False
            generation = int(self.control_state.get("generation") or 0) + 1
            actor_epoch = int(self.control_state.get("actor_lease_epoch") or 0) + 1
            self.control_state.update({
                "generation": generation,
                "actor_lease_epoch": actor_epoch,
            })
            stream.update({
                "actor_profile_id": str(session.get("actor_profile_id") or ""),
                "child_session_id": str(session.get("session_id") or ""),
                "generation": generation,
                "actor_lease_epoch": actor_epoch,
            })
        stream.update({
            "provider_game_id": game_id,
            "process_token": token,
            "process_id": int(session.get("process_id") or 0),
        })
        session["control_generation"] = int(stream.get("generation") or 0)
        return True

    def _control_finish_locked(self, operation: dict[str, Any], state: str,
                               reason: str) -> dict[str, Any]:
        operation["state"] = state
        operation["reason"] = reason
        if state == "succeeded":
            self._control_reconcile_verified_provider_stop_locked(operation)
        completed = self.control_state.setdefault("completed", {})
        completed[str(operation["operation_id"])] = dict(operation)
        while len(completed) > 64:
            completed.pop(next(iter(completed)))
        self.control_state["active"] = None
        if state == "succeeded":
            if operation["direction"] == "start":
                self.control_state["stream"] = {
                    key: operation.get(key) for key in (
                        "client_id", "actor_profile_id", "execution_profile_id",
                        "vibepollo_client_uuid", "stream_session_id", "sunshine_app_id",
                        "generation", "actor_lease_epoch", "child_session_id",
                        "provider_game_id", "process_token", "process_id")
                }
            elif operation["direction"] == "stop":
                self.control_state["stream"] = None
        self._control_save_locked()
        self.control_condition.notify_all()
        DIAGNOSTICS.record("control-operation-terminal", **{
            key: operation.get(key, "") for key in (
                "operation_id", "intent", "state", "reason", "generation",
                "actor_lease_epoch", "effect_sequence", "stream_session_id",
                "child_session_id", "actor_profile_id", "execution_profile_id")})
        return self._control_result(operation)

    def control_operation_acquire(self, client: dict[str, Any], body: dict[str, Any],
                                  request_id: str, wait_seconds: float = 20.0) \
            -> tuple[int, dict[str, Any]]:
        operation_id = self._control_text(body.get("operation_id"), "operation ID")
        if operation_id != request_id:
            raise ValueError("The operation ID must match X-Request-Id.")
        intent = str(body.get("intent") or "").strip().lower()
        direction = ControlOperationPolicy.direction(intent)
        stream_session_id = self._control_text(
            body.get("stream_session_id"), "stream session ID")
        sunshine_app_id = body.get("sunshine_app_id")
        if isinstance(sunshine_app_id, bool) or not isinstance(sunshine_app_id, int) \
                or sunshine_app_id <= 0:
            raise ValueError("A valid Sunshine app ID is required.")
        expected_generation = body.get("expected_generation", 0)
        if (isinstance(expected_generation, bool) or
                not isinstance(expected_generation, int) or expected_generation < 0):
            raise ValueError("A valid expected generation is required.")
        observed_app_id = body.get("observed_sunshine_app_id")
        if isinstance(observed_app_id, bool) or not isinstance(observed_app_id, int) \
                or observed_app_id < 0:
            raise ValueError("A valid observed Sunshine app ID is required.")
        child_session_id = str(body.get("child_session_id") or "").strip().lower()
        if child_session_id and not CHILD_TIME_SESSION_ID_PATTERN.fullmatch(child_session_id):
            raise ValueError("A valid child session ID is required.")
        client_id, actor_id, execution_id, client_uuid = self._control_identity(client)
        if child_session_id:
            child = self.child_time_session
            completed_child = self.child_time_completed.get(child_session_id)
            stream = self.control_state.get("stream")
            retained_terminal = intent == "child_terminal" and \
                isinstance(completed_child, dict) and \
                completed_child.get("reason") == "game_stopped" and \
                all(completed_child.get(key) == value for key, value in (
                    ("client_id", client_id),
                    ("actor_profile_id", actor_id),
                    ("execution_profile_id", execution_id))) and \
                isinstance(stream, dict) and \
                all(stream.get(key) == value for key, value in (
                    ("child_session_id", child_session_id),
                    ("client_id", client_id),
                    ("actor_profile_id", actor_id),
                    ("execution_profile_id", execution_id)))
            if (not isinstance(child, dict) or
                    child_session_id != child.get("session_id") or
                    client_id != child.get("client_id") or
                    actor_id != child.get("actor_profile_id") or
                    execution_id != child.get("execution_profile_id")) and \
                    not retained_terminal:
                return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                             "reason": "child_session_unmanaged"}
        elif self.is_child_profile(actor_id) and intent not in {"full_quit", "hard_reset"}:
            return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                         "reason": "child_session_unmanaged"}
        proposed = {
            "operation_id": operation_id, "client_id": client_id,
            "actor_profile_id": actor_id, "execution_profile_id": execution_id,
            "vibepollo_client_uuid": client_uuid, "intent": intent,
            "direction": direction, "stream_session_id": stream_session_id,
            "sunshine_app_id": sunshine_app_id,
            "expected_generation": expected_generation,
            "observed_sunshine_app_id": observed_app_id,
            "child_session_id": child_session_id,
        }
        # A lost response must not strand a full-quit permit before its owner
        # can perform the effect. Replay only the same still-permitted stop.
        with self.control_condition:
            active = self.control_state.get("active")
            if isinstance(active, dict) and active.get("operation_id") == operation_id \
                    and intent == "full_quit" and active.get("state") == "permitted":
                if (self._control_signature(active) != self._control_signature(proposed)
                        or active.get("observed_sunshine_app_id") != observed_app_id
                        or active.get("vibepollo_client_uuid") != client_uuid):
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                                 "reason": "operation_id_reused"}
                DIAGNOSTICS.record("control-operation-permit-replayed",
                                   operation_id=operation_id, intent=intent,
                                   generation=active.get("generation"),
                                   actor_lease_epoch=active.get("actor_lease_epoch"))
                return HTTPStatus.OK, self._control_result(active)
        parent_cleanup = self._control_finish_child_stream(
            client, proposed, expected_generation, observed_app_id)
        if parent_cleanup is not None:
            return parent_cleanup
        deadline = time.monotonic() + max(0.0, min(float(wait_seconds), 25.0))
        preceding_stop = None
        with self.control_condition:
            completed = self.control_state.setdefault("completed", {}).get(operation_id)
            if isinstance(completed, dict):
                if self._control_signature(completed) != self._control_signature(proposed):
                    return HTTPStatus.CONFLICT, {"ok": False,
                                                 "state": "blocked",
                                                 "reason": "operation_id_reused"}
                return HTTPStatus.OK, self._control_result(completed)
            self._control_recover_provider_locked()
            while isinstance(self.control_state.get("active"), dict):
                active = self.control_state["active"]
                if str(active.get("operation_id")) == operation_id:
                    if self._control_signature(active) != self._control_signature(proposed):
                        return HTTPStatus.CONFLICT, {"ok": False,
                                                     "state": "blocked",
                                                     "reason": "operation_id_reused"}
                    if active.get("state") == "uncertain":
                        return HTTPStatus.CONFLICT, {
                            **self._control_result(active),
                            "reason": str(active.get("reason") or "reconcile_required"),
                        }
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        active["state"] = "uncertain"
                        active["reason"] = "terminal_result_timeout"
                        self._control_save_locked()
                        self.control_condition.notify_all()
                        return HTTPStatus.CONFLICT, self._control_result(active)
                    self.control_condition.wait(remaining)
                    completed = self.control_state.setdefault(
                        "completed", {}).get(operation_id)
                    if isinstance(completed, dict):
                        return HTTPStatus.OK, self._control_result(completed)
                    continue
                if active.get("state") == "uncertain":
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "uncertain",
                                                 "reason": str(active.get("reason") or
                                                               "reconcile_required")}
                if direction == "start" and active.get("direction") == "stop":
                    preceding_stop = active
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    active["state"] = "uncertain"
                    active["reason"] = "terminal_result_timeout"
                    self._control_save_locked()
                    self.control_condition.notify_all()
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "uncertain",
                                                 "reason": "terminal_result_timeout"}
                self.control_condition.wait(remaining)
            stream = self.control_state.get("stream")
            idle_restart = False
            if direction == "start" and observed_app_id == 0 and intent != "reauthorize":
                idle_restart = self._control_reconcile_idle_locked()
                stream = self.control_state.get("stream")
            if direction == "stop":
                idle = self.control_state.get("idle_stream")
                if stream is None and isinstance(idle, dict) and expected_generation == idle.get("generation") \
                        and all(idle.get(key) == proposed.get(key) for key in (
                            "client_id", "actor_profile_id", "execution_profile_id",
                            "stream_session_id", "sunshine_app_id")):
                    receipt = {**proposed, "generation": idle["generation"],
                               "actor_lease_epoch": idle["actor_lease_epoch"]}
                    return HTTPStatus.OK, self._control_finish_locked(
                        receipt, "succeeded", "owned_stream_already_idle")
                close_keys = ("client_id", "execution_profile_id",
                              "stream_session_id", "sunshine_app_id") \
                    if intent in {"full_quit", "hard_reset"} else \
                    ("client_id", "actor_profile_id", "execution_profile_id",
                     "stream_session_id", "sunshine_app_id")
                if (not isinstance(stream, dict) or
                        any(stream.get(key) != proposed.get(key) for key in close_keys) or
                        expected_generation != int(stream.get("generation") or 0)):
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                                 "reason": "unmanaged_stream"}
                if observed_app_id not in {0, int(stream.get("sunshine_app_id") or 0)}:
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                                 "reason": "unmanaged_stream"}
                current = self._child_time_current_game(execution_id, stream)
                current_game_id = str(current.get("game_id") or "")
                current_identity = current.get("identity")
                current_token = self._child_time_process_token(current_identity)
                expected_token = self._child_time_process_token(stream)
                expected_game = self._child_time_normalize_game_id(
                    stream.get("provider_game_id"))
                provider_idle = current.get("kind") == "idle"
                provider_active = current.get("kind") == "active" \
                    and bool(current_game_id) and bool(current_token) \
                    and (not expected_token or current_token == expected_token) \
                    and (not expected_game or self._child_time_normalize_game_id(
                        current_game_id) == expected_game)
                retired_child_process = intent == "child_terminal" \
                    and self._child_time_process_absent(current, expected_token)
                if not (provider_idle or provider_active or retired_child_process):
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "state": "blocked",
                        "reason": "retained_process_unconfirmed",
                    }
                proposed.update({
                    "provider_game_id": (self._child_time_normalize_game_id(
                        current_game_id) if provider_active else
                        expected_game if retired_child_process else ""),
                    "process_token": (current_token if provider_active else
                                      expected_token if retired_child_process else ""),
                    "process_id": (int((current_identity or {}).get(
                        "process_id") or 0) if provider_active else
                        int(stream.get("process_id") or 0)
                        if retired_child_process else 0),
                    "provider_idle_confirmed": provider_idle,
                })
            elif intent == "reauthorize":
                exact = isinstance(stream, dict) and expected_generation == stream.get("generation") \
                    and observed_app_id == stream.get("sunshine_app_id") \
                    and all(stream.get(key) == proposed.get(key) for key in (
                        "client_id", "execution_profile_id", "stream_session_id", "sunshine_app_id"))
                previous_child = str((stream or {}).get("child_session_id") or "")
                previous_ended = self.child_time_completed.get(previous_child, {})
                if not exact or previous_child and previous_child != child_session_id \
                        and previous_ended.get("reason") not in {
                            "game_stopped", "actor_replaced", "replacement"}:
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                                 "reason": "retained_owner_unconfirmed"}
                current = self._child_time_current_game(
                    execution_id, stream if self._child_time_process_token(stream)
                    else self.child_time_session if child_session_id else None)
                current_game_id = str(current.get("game_id") or "")
                current_identity = current.get("identity")
                current_token = self._child_time_process_token(current_identity)
                target_time = self.child_time_session
                child_game_matches = not child_session_id or (
                    isinstance(target_time, dict)
                    and target_time.get("session_id") == child_session_id
                    and self._child_time_normalize_game_id(target_time.get("game_id"))
                    == self._child_time_normalize_game_id(current_game_id))
                provider_idle = current.get("kind") == "idle"
                stream_token = self._child_time_process_token(stream)
                target_token = self._child_time_process_token(target_time) \
                    if child_session_id and isinstance(target_time, dict) else ""
                expected_token = stream_token or target_token
                expected_game = self._child_time_normalize_game_id(
                    stream.get("provider_game_id") or
                    (target_time or {}).get("game_id"))
                provider_active = current.get("kind") == "active" \
                    and bool(current_game_id) and child_game_matches \
                    and bool(expected_token) and current_token == expected_token \
                    and (not expected_game or self._child_time_normalize_game_id(
                        current_game_id) == expected_game)
                if not (provider_idle or provider_active):
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                                 "reason": "retained_process_unconfirmed"}
                proposed.update({
                    "provider_game_id": self._child_time_normalize_game_id(
                        current_game_id) if provider_active else "",
                    "process_token": current_token if provider_active else "",
                    "process_id": int((current_identity or {}).get(
                        "process_id") or 0) if provider_active else 0,
                })
            elif isinstance(stream, dict):
                same_owner = all(stream.get(key) == proposed.get(key) for key in (
                    "client_id", "actor_profile_id", "execution_profile_id",
                    "stream_session_id", "sunshine_app_id"))
                if intent not in {"resume", "reconnect", "warm_up",
                                  "provider_transition"} or not same_owner:
                    same_replacement_transport = intent == "replacement" and \
                        expected_generation == int(stream.get("generation") or 0) and \
                        all(stream.get(key) == proposed.get(key) for key in (
                            "client_id", "execution_profile_id", "sunshine_app_id"))
                    if not same_replacement_transport:
                        return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                                     "reason": "managed_stream_conflict"}
                if observed_app_id != int(stream.get("sunshine_app_id") or 0):
                    return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                                 "reason": "unmanaged_stream"}
                if intent in {"resume", "reconnect", "warm_up"}:
                    current = self._child_time_current_game(execution_id, stream)
                    current_game_id = str(current.get("game_id") or "")
                    current_identity = current.get("identity")
                    current_token = self._child_time_process_token(current_identity)
                    expected_token = self._child_time_process_token(stream)
                    expected_game = self._child_time_normalize_game_id(
                        stream.get("provider_game_id"))
                    provider_idle = current.get("kind") == "idle"
                    provider_active = current.get("kind") == "active" \
                        and bool(current_game_id) and bool(current_token) \
                        and (not expected_token or current_token == expected_token) \
                        and (not expected_game or self._child_time_normalize_game_id(
                            current_game_id) == expected_game)
                    if not (provider_idle or provider_active):
                        return HTTPStatus.CONFLICT, {
                            "ok": False, "state": "blocked",
                            "reason": "retained_process_unconfirmed",
                        }
                    proposed.update({
                        "provider_game_id": self._child_time_normalize_game_id(
                            current_game_id) if provider_active else "",
                        "process_token": current_token if provider_active else "",
                        "process_id": int((current_identity or {}).get(
                            "process_id") or 0) if provider_active else 0,
                    })
            elif intent in {"resume", "reconnect"} and not idle_restart:
                return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                             "reason": "unmanaged_stream"}
            elif observed_app_id != 0 and not (
                    isinstance(preceding_stop, dict) and
                    preceding_stop.get("state") == "succeeded" and
                    int(preceding_stop.get("sunshine_app_id") or 0)
                    == observed_app_id):
                return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                             "reason": "unmanaged_stream"}
            if direction == "transition":
                generation = int(stream.get("generation") or 0)
                actor_epoch = int(stream.get("actor_lease_epoch") or 0)
                effect_sequence = int(self.control_state.get(
                    "provider_sequence") or 0) + 1
            else:
                generation = int(self.control_state.get("generation") or 0) + 1
                actor_epoch = int(self.control_state.get("actor_lease_epoch") or 0) + 1
                effect_sequence = 0
            operation = {**proposed, "generation": generation,
                         "actor_lease_epoch": actor_epoch,
                         "effect_sequence": effect_sequence,
                         "state": "permitted", "reason": "none"}
            if direction == "transition":
                self.control_state["provider_sequence"] = effect_sequence
            else:
                self.control_state.update({"generation": generation,
                                           "actor_lease_epoch": actor_epoch})
            self.control_state["active"] = operation
            self._control_save_locked()
            DIAGNOSTICS.record("control-operation-permitted", **{
                key: operation.get(key, "") for key in (
                    "operation_id", "intent", "generation", "actor_lease_epoch",
                    "effect_sequence", "stream_session_id", "child_session_id",
                    "actor_profile_id", "execution_profile_id")})
            if intent == "reauthorize":
                retired, _result = self.provider_control_operation.retire(operation)
                if retired:
                    return HTTPStatus.OK, self._control_finish_locked(
                        operation, "succeeded", "retained_owner_authorized")
                operation.update(state="uncertain", reason="reauthorization_fence_unconfirmed")
                self._control_save_locked()
                return HTTPStatus.CONFLICT, self._control_result(operation)
            return HTTPStatus.OK, self._control_result(operation)

    def _control_finish_child_stream(self, client: dict[str, Any],
                                     proposed: dict[str, Any],
                                     expected_generation: int, observed_app_id: int):
        """Let any authorized actor on this TV finish the exact shared stream
        while child time is attached to it."""
        if proposed["intent"] not in {"full_quit", "hard_reset"}:
            return None
        orphan = self._child_time_recover_persisted_terminal_orphan()
        if orphan is not None and orphan[0] != HTTPStatus.OK:
            return orphan
        with self.control_condition:
            if proposed["operation_id"] in self.control_state.get("completed", {}):
                return None
            terminal = next((item for item in
                             self.control_state.get("completed", {}).values()
                             if isinstance(item, dict)
                             and str(item.get("operation_id") or "").startswith(
                                 "child-terminal:")
                             and item.get("state") == "succeeded"
                             and item.get("client_id") == proposed.get("client_id")
                             and item.get("execution_profile_id") ==
                                 proposed.get("execution_profile_id")
                             and item.get("stream_session_id") ==
                                 proposed.get("stream_session_id")
                             and item.get("sunshine_app_id") ==
                                 proposed.get("sunshine_app_id")
                             and item.get("expected_generation") == expected_generation
                             and item.get("generation") ==
                                  self.control_state.get("generation")
                             and item.get("actor_lease_epoch") ==
                                  self.control_state.get("actor_lease_epoch")), None)
            if terminal is not None and self.control_state.get("stream") is None \
                    and self.control_state.get("active") is None:
                return self._control_alias_child_terminal_locked(proposed, terminal)
            stream = self.control_state.get("stream")
            session = self.child_time_session
            exact = isinstance(stream, dict) and isinstance(session, dict) \
                and bool(stream.get("child_session_id")) \
                and stream.get("generation") == expected_generation \
                and observed_app_id in {0, proposed["sunshine_app_id"]} \
                and all(stream.get(key) == proposed.get(key) for key in (
                    "client_id", "execution_profile_id", "stream_session_id",
                    "sunshine_app_id")) \
                and stream.get("child_session_id") == session.get("session_id") \
                and all(stream.get(key) == session.get(key) for key in (
                "client_id", "execution_profile_id"))
            if not exact:
                return None
            child_operation_id = "child-terminal:" + str(session["session_id"])
            if not self.child_terminal_operation.reserve_terminal(session):
                return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                             "reason": "child_cleanup_required"}
        _monotonic, wall = self._child_time_now()
        status, result = self._child_time_finish_retry(
            client, session, "parent_terminated", wall)
        if status != HTTPStatus.OK or result.get("cleanup_required"):
            return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                         "reason": "child_cleanup_required"}
        with self.control_condition:
            completed = self.control_state.setdefault("completed", {})
            terminal = completed.get(child_operation_id)
            if self.control_state.get("active") is not None or \
                    self.control_state.get("stream") is not None or \
                    not isinstance(terminal, dict) or \
                    terminal.get("state") != "succeeded" or \
                    terminal.get("generation") != self.control_state.get("generation") or \
                    terminal.get("actor_lease_epoch") != \
                        self.control_state.get("actor_lease_epoch"):
                return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                             "reason": "stale_operation"}
            return self._control_alias_child_terminal_locked(proposed, terminal)

    def _control_alias_child_terminal_locked(self, proposed: dict[str, Any],
                                             terminal: dict[str, Any]):
        receipt = {**proposed, "generation": terminal["generation"],
                   "actor_lease_epoch": terminal["actor_lease_epoch"],
                   "state": "succeeded", "reason": "none"}
        return HTTPStatus.OK, self._control_finish_locked(receipt, "succeeded", "none")

    def control_operation_complete(self, client: dict[str, Any], body: dict[str, Any],
                                   request_id: str) -> tuple[int, dict[str, Any]]:
        operation_id = self._control_text(body.get("operation_id"), "operation ID")
        if operation_id != request_id:
            raise ValueError("The operation ID must match X-Request-Id.")
        generation = body.get("generation")
        actor_epoch = body.get("actor_lease_epoch")
        succeeded = body.get("succeeded")
        effect_result = body.get("effect_result")
        if (isinstance(generation, bool) or not isinstance(generation, int) or
                isinstance(actor_epoch, bool) or not isinstance(actor_epoch, int) or
                not isinstance(succeeded, bool) or
                effect_result is not None and not isinstance(effect_result, dict)):
            raise ValueError("Invalid control operation terminal result.")
        client_id, actor_id, execution_id, _client_uuid = self._control_identity(client)
        with self.control_condition:
            completed = self.control_state.setdefault("completed", {}).get(operation_id)
            if isinstance(completed, dict):
                exact = (int(completed.get("generation") or 0) == generation and
                         int(completed.get("actor_lease_epoch") or 0) == actor_epoch and
                         completed.get("client_id") == client_id and
                         completed.get("actor_profile_id") == actor_id and
                         completed.get("execution_profile_id") == execution_id)
                return ((HTTPStatus.OK, self._control_result(completed)) if exact else
                        (HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                               "reason": "stale_operation"}))
            active = self.control_state.get("active")
            exact = (isinstance(active, dict) and
                     active.get("operation_id") == operation_id and
                     int(active.get("generation") or 0) == generation and
                     int(active.get("actor_lease_epoch") or 0) == actor_epoch and
                     active.get("client_id") == client_id and
                     active.get("actor_profile_id") == actor_id and
                     active.get("execution_profile_id") == execution_id)
            if not exact:
                return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                             "reason": "stale_operation"}
            if not succeeded:
                if isinstance(effect_result, dict) and \
                        effect_result.get("outcome") == "definitive_failure":
                    active["effect_result"] = json.loads(json.dumps(effect_result))
                    return HTTPStatus.OK, self._control_finish_locked(
                        active, "failed", "effect_rejected")
                active["state"] = "uncertain"
                active["reason"] = "effect_result_uncertain"
                self._control_save_locked()
                self.control_condition.notify_all()
                return HTTPStatus.CONFLICT, self._control_result(active)
            if active.get("direction") == "transition" and effect_result is not None:
                active["effect_result"] = json.loads(json.dumps(effect_result))
            return HTTPStatus.OK, self._control_finish_locked(active, "succeeded", "none")

    def control_operation_reconcile(self, client: dict[str, Any], body: dict[str, Any],
                                    request_id: str) -> tuple[int, dict[str, Any]]:
        operation_id = self._control_text(body.get("operation_id"), "operation ID")
        if operation_id != request_id:
            raise ValueError("The operation ID must match X-Request-Id.")
        observed_app_id = body.get("observed_sunshine_app_id")
        if isinstance(observed_app_id, bool) or not isinstance(observed_app_id, int) \
                or observed_app_id < 0:
            raise ValueError("A valid observed Sunshine app ID is required.")
        client_id, actor_id, execution_id, _client_uuid = self._control_identity(client)
        with self.control_condition:
            active = self.control_state.get("active")
            if (not isinstance(active, dict) or active.get("operation_id") != operation_id or
                    active.get("client_id") != client_id or
                    active.get("actor_profile_id") != actor_id or
                    active.get("execution_profile_id") != execution_id):
                completed = self.control_state.setdefault("completed", {}).get(operation_id)
                if isinstance(completed, dict):
                    return HTTPStatus.OK, self._control_result(completed)
                return HTTPStatus.CONFLICT, {"ok": False, "state": "blocked",
                                             "reason": "stale_operation"}
            if active.get("state") != "uncertain":
                return HTTPStatus.CONFLICT, {**self._control_result(active),
                                             "reason": "operation_in_progress"}
            if active.get("intent") == "full_quit" and active.get("direction") == "stop" \
                    and observed_app_id in (0, active.get("sunshine_app_id")):
                game_id = str(active.get("provider_game_id") or "")
                token = str(active.get("process_token") or "")
                expected_hashes = {
                    ControlOperationPolicy.effect_hash("/game/stop-verified", payload)
                    for payload in (
                        {"game_id": game_id, "expected_process_token": token},
                        {"force": False, "game_id": game_id,
                         "expected_process_token": token},
                    )
                }
                if game_id and re.fullmatch(r"[0-9a-f]{64}", token) and \
                        active.get("effect_request_sha256") in expected_hashes:
                    retired, effect = self.provider_control_operation.retire(active)
                    if retired and isinstance(effect, dict) and \
                            effect.get("accepted") is True and \
                            effect.get("command") == "stop" and \
                            effect.get("stopped_current") is True and \
                            self._child_time_normalize_game_id(
                                effect.get("stopped_game_id")) == \
                            self._child_time_normalize_game_id(game_id):
                        active["effect_result"] = effect
                        self._control_save_locked()
                        return HTTPStatus.OK, {**self._control_result(active),
                                               "ok": True, "state": "provider_stopped",
                                               "reason": "none"}
            # An app-id sample cannot fence a permit holder whose direct effect may
            # still be delayed.  Only the exact holder's durable terminal write (or
            # a host-owned child cleanup retry below) may release this barrier.
            del observed_app_id
            return HTTPStatus.CONFLICT, {**self._control_result(active),
                                         "reason": "terminal_owner_required"}

    def _control_provider_binding(self, client: dict[str, Any], request_id: str,
                                  effect_hash: str,
                                  supplied: Any = None) -> tuple[Any, bool]:
        """Return the exact active permit; acquire one only for a standalone action."""
        client_id, actor_id, execution_id, _uuid = self._control_identity(client)
        with self.control_condition:
            active = self.control_state.get("active")
            if isinstance(supplied, dict):
                exact = ControlOperationPolicy.supplied_matches(
                    active, supplied, (client_id, actor_id, execution_id))
                if not exact or active.get("state") != "permitted":
                    return False, False
                previous = str(active.get("effect_request_sha256") or "")
                if previous and previous != effect_hash:
                    return False, False
                active["effect_request_sha256"] = effect_hash
                self._control_save_locked()
                return dict(active), False
            stream = self.control_state.get("stream")
            if not isinstance(stream, dict) or any(stream.get(key) != value for key, value in (
                    ("client_id", client_id), ("actor_profile_id", actor_id),
                    ("execution_profile_id", execution_id))):
                return False, False
            body = ControlOperationPolicy.provider_request(request_id, stream)
            operation_id = body["operation_id"]
        status, permit = self.control_operation_acquire(client, body, operation_id)
        if status != HTTPStatus.OK:
            return False, False
        if permit.get("state") in {"succeeded", "failed"}:
            return (permit, False) if permit.get(
                "effect_request_sha256") == effect_hash else (False, False)
        with self.control_condition:
            active = self.control_state.get("active")
            if not isinstance(active, dict) or active.get("operation_id") != operation_id:
                return False, False
            previous = str(active.get("effect_request_sha256") or "")
            if previous and previous != effect_hash:
                return False, False
            active["effect_request_sha256"] = effect_hash
            self._control_save_locked()
            permit = self._control_result(active)
        return permit, permit.get("state") == "permitted"

    def _control_provider_complete(self, client: dict[str, Any], permit: dict[str, Any],
                                   succeeded: bool, effect_result: Any = None,
                                   definite: bool = False) -> bool:
        operation_id = str(permit.get("operation_id") or "")
        if not succeeded and definite:
            with self.control_condition:
                active = self.control_state.get("active")
                identity = self._control_identity(client)[:3]
                if not ControlOperationPolicy.supplied_matches(active, permit, identity):
                    return False
                active["effect_result"] = effect_result
                self._control_finish_locked(active, "failed",
                    ProviderControlOperation.reason(effect_result))
                return True
        status, result = self.control_operation_complete(client, {
            "operation_id": operation_id,
            "generation": int(permit.get("generation") or 0),
            "actor_lease_epoch": int(permit.get("actor_lease_epoch") or 0),
            "succeeded": succeeded,
            "effect_result": effect_result if isinstance(effect_result, dict) else {},
        }, operation_id)
        return status == HTTPStatus.OK and result.get("state") == "succeeded"

    def _control_recover_provider_locked(self) -> bool:
        """Retire a host-owned RPC before releasing its durable uncertain barrier."""
        active = self.control_state.get("active")
        if not isinstance(active, dict) or active.get("state") != "uncertain" \
                or (active.get("direction") != "transition" and
                    active.get("intent") != "reauthorize"):
            return False
        retired, result = self.provider_control_operation.retire(active)
        if not retired:
            return False
        active["effect_result"] = result if isinstance(result, dict) else {}
        succeeded = active.get("intent") == "reauthorize" or \
            isinstance(result, dict) and result.get("accepted") is True
        self._control_finish_locked(active, "succeeded" if succeeded else "failed",
                                    "provider_effect_retired")
        return True

    def control_operation_status(self, client: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        """Return the exact stream close binding to any actor on its TV/execution profile."""
        client_id, actor_id, execution_id, _uuid = self._control_identity(client)
        with self.control_condition:
            stream = self.control_state.get("stream")
            if not isinstance(stream, dict):
                return HTTPStatus.CONFLICT, {"ok": False, "reason": "managed_stream_unavailable"}
            if stream.get("client_id") != client_id or \
                    stream.get("execution_profile_id") != execution_id:
                return HTTPStatus.CONFLICT, {"ok": False, "reason": "stream_owner_mismatch"}
            return HTTPStatus.OK, {"ok": True, "state": "owned", **{
                key: stream.get(key) for key in (
                    "stream_session_id", "sunshine_app_id", "generation", "actor_lease_epoch",
                    "actor_profile_id", "execution_profile_id", "child_session_id",
                    "provider_game_id", "process_token", "process_id")}}

    def _control_reconcile_idle_locked(self) -> bool:
        """Recover a completed transport's stale ownership, never an in-flight permit."""
        stream = self.control_state.get("stream")
        if self.control_state.get("active") is not None or not isinstance(stream, dict) \
                or self.child_time_session is not None:
            return False
        try:
            ok, snapshot = self.proxy("vibepollo", "/snapshot?force=1", timeout=8.0,
                                      profile_id=stream["execution_profile_id"])
            if not ok or not ControlOperationPolicy.streamer_idle(snapshot):
                return False
            if self._child_time_current_game(
                    stream["execution_profile_id"], stream).get("kind") != "idle":
                return False
        except OSError:
            return False
        self.control_state["idle_stream"] = dict(stream)
        self.control_state["stream"] = None
        self._control_save_locked()
        DIAGNOSTICS.record("control-stream-idle-reconciled", generation=stream["generation"],
                           stream_session_id=stream["stream_session_id"])
        return True

    @staticmethod
    def _control_bridge_binding(permit: dict[str, Any]) -> dict[str, Any]:
        return ControlOperationPolicy.bridge_binding(permit)

    @staticmethod
    def _child_time_process_absent(current: dict[str, Any], token: str) -> bool:
        """Only the provider's exact target status retires A; replacement B is untouched."""
        return bool(token) and current.get("target_token") == token \
            and current.get("target_status") in {"gone", "reused"}

    def _child_time_adopt_terminal_identity(self, session: dict[str, Any],
                                            permit: dict[str, Any],
                                            current: dict[str, Any]) -> bool:
        """Persist only the exact process identity granted by this terminal permit."""
        if current.get("kind") != "active" or \
                self._child_time_normalize_game_id(current.get("game_id")) != \
                self._child_time_normalize_game_id(session.get("game_id")):
            return False
        token = self._child_time_process_token(permit)
        identity = current.get("identity")
        current_token = self._child_time_process_token(identity)
        try:
            process_id = int(permit.get("process_id") or 0)
            current_process_id = int((identity or {}).get("process_id") or 0)
        except (TypeError, ValueError):
            return False
        if not token or process_id <= 0 or token != current_token \
                or process_id != current_process_id:
            return False
        with self.control_condition:
            active = self.control_state.get("active")
            stream = self.control_state.get("stream")
            exact = isinstance(active, dict) and isinstance(stream, dict) \
                and active.get("operation_id") == \
                    "child-terminal:" + str(session.get("session_id") or "") \
                and active.get("state") in {"permitted", "uncertain"} \
                and active.get("intent") == "child_terminal" \
                and active.get("direction") == "stop" \
                and active.get("expected_generation") == stream.get("generation") \
                and active.get("generation") == self.control_state.get("generation") \
                and active.get("actor_lease_epoch") == \
                    self.control_state.get("actor_lease_epoch") \
                and active.get("provider_game_id") \
                and self._child_time_normalize_game_id(
                    active.get("provider_game_id")) == \
                    self._child_time_normalize_game_id(session.get("game_id")) \
                and self._child_time_process_token(active) == token \
                and int(active.get("process_id") or 0) == process_id \
                and all(active.get(key) == session.get(key) for key in (
                    "client_id", "actor_profile_id", "execution_profile_id")) \
                and active.get("child_session_id") == session.get("session_id") \
                and active.get("stream_session_id") == stream.get("stream_session_id") \
                and active.get("sunshine_app_id") == stream.get("sunshine_app_id") \
                and active.get("observed_sunshine_app_id") in (
                    0, stream.get("sunshine_app_id")) \
                and active.get("vibepollo_client_uuid") == \
                    stream.get("vibepollo_client_uuid") \
                and all(stream.get(key) == session.get(session_key) for key, session_key in (
                    ("client_id", "client_id"), ("actor_profile_id", "actor_profile_id"),
                    ("execution_profile_id", "execution_profile_id"),
                    ("child_session_id", "session_id"))) \
                and stream.get("stream_session_id") == session.get("stream_session_id",
                                                                     stream.get("stream_session_id"))
            if not exact:
                return False
            stream_game = self._child_time_normalize_game_id(
                stream.get("provider_game_id"))
            stream_token = self._child_time_process_token(stream)
            try:
                stream_process_id = int(stream.get("process_id") or 0)
            except (TypeError, ValueError):
                return False
            if stream_game and stream_game != self._child_time_normalize_game_id(
                    session.get("game_id")):
                return False
            if stream_token and stream_token != token:
                return False
            if stream_process_id and stream_process_id != process_id:
                return False
            session_token = self._child_time_process_token(session)
            try:
                session_process_id = int(session.get("process_id") or 0)
            except (TypeError, ValueError):
                return False
            if session_token and (session_token != token or
                                  session_process_id != process_id):
                return False
            session["process_token"] = token
            session["process_id"] = process_id
            self._child_time_bump_revision_locked()
            self._child_time_checkpoint_locked(force=True)
            return True

    def _child_time_recover_persisted_terminal_orphan(self):
        """Join the durable child terminal operation, never mint a replacement permit."""
        with self.control_condition:
            if isinstance(self.child_time_session, dict):
                return None
            active = self.control_state.get("active")
            stream = self.control_state.get("stream")
            child_result = active.get("child_terminal_result") \
                if isinstance(active, dict) else None
            if not isinstance(active, dict) or active.get("intent") != "child_terminal" \
                    or active.get("direction") != "stop":
                return None
            if active.get("state") != "uncertain" or \
                    not isinstance(stream, dict) or not isinstance(child_result, dict):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            child_session_id = str(active.get("child_session_id") or "")
            active_game = self._child_time_normalize_game_id(
                active.get("provider_game_id"))
            stream_game = self._child_time_normalize_game_id(
                stream.get("provider_game_id"))
            result_game = self._child_time_normalize_game_id(
                child_result.get("game_id"))
            token = self._child_time_process_token(active)
            try:
                process_id = int(active.get("process_id") or 0)
                stream_process_id = int(stream.get("process_id") or 0)
                active_app_id = int(active.get("sunshine_app_id") or 0)
                stream_app_id = int(stream.get("sunshine_app_id") or 0)
            except (TypeError, ValueError):
                process_id = stream_process_id = active_app_id = stream_app_id = 0
            exact = bool(child_session_id) and \
                CHILD_TIME_SESSION_ID_PATTERN.fullmatch(child_session_id) is not None \
                and bool(str(active.get("stream_session_id") or "")) \
                and active_app_id > 0 and stream_app_id > 0 \
                and active.get("operation_id") == "child-terminal:" + child_session_id \
                and active.get("expected_generation") == stream.get("generation") \
                and active.get("generation") == self.control_state.get("generation") \
                and active.get("actor_lease_epoch") == \
                    self.control_state.get("actor_lease_epoch") \
                and active.get("generation") == int(stream.get("generation") or 0) + 1 \
                and active.get("actor_lease_epoch") == \
                    int(stream.get("actor_lease_epoch") or 0) + 1 \
                and active.get("stream_session_id") == stream.get("stream_session_id") \
                and active.get("sunshine_app_id") == stream.get("sunshine_app_id") \
                and active.get("observed_sunshine_app_id") in (
                    0, stream.get("sunshine_app_id")) \
                and active.get("child_session_id") == stream.get("child_session_id") \
                and all(active.get(key) == stream.get(key) for key in (
                    "client_id", "actor_profile_id", "execution_profile_id",
                    "vibepollo_client_uuid")) \
                and active_game and active_game == result_game \
                and (not stream_game or stream_game == active_game) \
                and token and process_id > 0 \
                and (not self._child_time_process_token(stream) or
                     self._child_time_process_token(stream) == token) \
                and (not stream_process_id or stream_process_id == process_id) \
                and all(child_result.get(key) == active.get(key) for key in (
                    "client_id", "actor_profile_id", "execution_profile_id"))
            if exact and any(key in child_result and
                             child_result.get(key) != active.get(key) for key in (
                                 "child_session_id", "stream_session_id",
                                 "sunshine_app_id", "generation",
                                 "actor_lease_epoch")):
                exact = False
            if exact and "process_token" in child_result and \
                    self._child_time_process_token(child_result) != token:
                exact = False
            if exact and "process_id" in child_result:
                try:
                    result_process_id = int(child_result.get("process_id") or 0)
                except (TypeError, ValueError):
                    result_process_id = 0
                if result_process_id != process_id:
                    exact = False
            if not exact:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                    "generation": int(active.get("generation") or 0),
                    "actor_lease_epoch": int(active.get("actor_lease_epoch") or 0),
                }
            client_id = str(active.get("client_id") or "")
            client = next((item for item in self.config.get("clients", [])
                           if isinstance(item, dict) and
                           str(item.get("id") or "") == client_id), None)
            actor_id = str(active.get("actor_profile_id") or "")
            parent_id = str(active.get("execution_profile_id") or "")
            actor = self.config.get("profiles", {}).get(actor_id)
            parent = self.config.get("profiles", {}).get(parent_id)
            grants = client.get("profile_grants", {}).get(actor_id, []) \
                if isinstance(client, dict) and isinstance(
                    client.get("profile_grants"), dict) else []
            try:
                bound_uuid = self._child_time_client_uuid(client, parent_id) \
                    if isinstance(client, dict) else ""
            except PermissionError:
                bound_uuid = ""
            exact = isinstance(client, dict) and isinstance(actor, dict) \
                and isinstance(parent, dict) and actor.get("kind") == CHILD_PROFILE_KIND \
                and actor.get("enabled") is True and parent.get("kind") == STANDARD_PROFILE_KIND \
                and parent.get("enabled") is True and "use_profile" in grants \
                and bound_uuid == str(active.get("vibepollo_client_uuid") or "")
            if not exact:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            try:
                current = self._child_time_current_game(parent_id, active)
            except (OSError, TimeoutError, TypeError, ValueError):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            current_exact = current.get("kind") == "idle" or (
                current.get("kind") == "active" and
                self._child_time_normalize_game_id(current.get("game_id")) == active_game and
                self._child_time_process_token(current.get("identity")) == token and
                int((current.get("identity") or {}).get("process_id") or 0) == process_id)
            if not current_exact:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            raw_reason = child_result.get("reason")
            if not isinstance(raw_reason, str) or not raw_reason.strip():
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            reason = raw_reason.strip()
            if reason.casefold() == "game_stopped":
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            expected_effect_hash = ControlOperationPolicy.effect_hash(
                "/game/stop-verified", {
                    "game_id": str(child_result.get("game_id") or ""),
                    "expected_process_token": token,
                    "force": reason.casefold() in {"replacement", "user_terminated"},
                })
            recorded_effect_hash = active.get("effect_request_sha256")
            if not isinstance(recorded_effect_hash, str) or not recorded_effect_hash \
                    or recorded_effect_hash != expected_effect_hash:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            _monotonic, wall = self._child_time_now(actor)
            session = {
                "session_id": child_session_id,
                "request_id": str(active.get("operation_id") or child_session_id),
                "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                "client_id": client_id, "game_id": str(child_result.get("game_id") or ""),
                "vibepollo_client_uuid": str(active.get("vibepollo_client_uuid") or ""),
                "phase": "cleanup_required", "started_monotonic": _monotonic,
                "last_monotonic": _monotonic, "last_heartbeat_monotonic": _monotonic,
                "last_wall_epoch": wall.timestamp(),
                "last_heartbeat_wall_epoch": wall.timestamp(),
                "last_playable_seconds": 0.0, "process_token": token,
                "process_id": process_id,
                "policy_revision": int(actor.get("policy_revision", 0) or 0),
                "control_generation": int(stream.get("generation") or 0),
                "terminal_reason": reason, "end_reason": "cleanup_required",
            }
            if not self.child_terminal_operation.reserve_terminal(session):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_in_progress",
                    "operation_id": str(active.get("operation_id") or ""),
                }
            self.child_time_session = session
            self._child_time_bump_revision_locked()
            self._child_time_checkpoint_locked(force=True)
        force = reason.casefold() in {"replacement", "user_terminated"}
        status, result = self._child_time_finish_retry(
            client, session, reason, wall, force=force)
        if status == HTTPStatus.OK and not result.get("cleanup_required"):
            return status, result
        return HTTPStatus.CONFLICT, {
            "ok": False, "state": "blocked", "phase": "cleanup_required",
            "cleanup_required": True, "reason": "child_cleanup_required",
            "operation_id": "child-terminal:" + child_session_id,
        }

    def _child_time_stop_execution(self, client: dict[str, Any],
                                   session: dict[str, Any], wall: datetime,
                                   permit: Any, force: bool = False) \
            -> tuple[bool, str]:
        del client, wall
        current = self._child_time_current_game(
            str(session.get("execution_profile_id") or ""), session)
        if current.get("kind") == "idle":
            return True, "none"
        token = self._child_time_process_token(session)
        if not token and isinstance(permit, dict):
            self._child_time_adopt_terminal_identity(session, permit, current)
            token = self._child_time_process_token(session)
        exact = (current.get("kind") == "active" and
                 self._child_time_normalize_game_id(current.get("game_id")) ==
                 self._child_time_normalize_game_id(session.get("game_id")))
        if not exact or not token or self._child_time_process_token(
                current.get("identity")) != token:
            if self._child_time_process_absent(current, token):
                return True, "none"
            return False, "cleanup_required"
        payload = {
            "game_id": str(session.get("game_id") or ""),
            "expected_process_token": token,
            "force": bool(force),
            "control_operation": self._control_bridge_binding(permit),
        }
        effect_hash = ControlOperationPolicy.effect_hash(
            "/game/stop-verified", {
                "game_id": payload["game_id"],
                "expected_process_token": token,
                "force": bool(force),
            })
        with self.control_condition:
            active = self.control_state.get("active")
            identity = tuple(str(session.get(key) or "") for key in (
                "client_id", "actor_profile_id", "execution_profile_id"))
            if not ControlOperationPolicy.supplied_matches(active, permit, identity):
                return False, "cleanup_required"
            previous_hash = str(active.get("effect_request_sha256") or "")
            if previous_hash and previous_hash != effect_hash:
                return False, "cleanup_required"
            active["effect_request_sha256"] = effect_hash
            self._control_save_locked()
        try:
            ok, result = self.proxy_json(
                "game_provider", "/game/stop-verified", payload,
                timeout=20.0,
                profile_id=str(session.get("execution_profile_id") or ""))
        except (OSError, TimeoutError):
            return False, "cleanup_required"
        accepted = ok and (not isinstance(result, dict)
                           or bool(result.get("accepted", True)))
        if not accepted:
            return False, "cleanup_required"
        deadline = self.child_terminal_operation.clock() + 20.0
        while self.child_terminal_operation.clock() <= deadline:
            observed = self._child_time_current_game(
                str(session.get("execution_profile_id") or ""), session)
            if observed.get("kind") == "idle":
                return True, "none"
            if self._child_time_process_absent(observed, token):
                return True, "none"
            self.child_terminal_operation.wait(1.0)
        return False, "cleanup_required"

    def _child_time_control_call(self, session: dict[str, Any], operation: Any) -> Any:
        keys = ("profile_id", "actor_profile_id", "execution_profile_id")
        original = tuple(getattr(self.request_context, key, None) for key in keys)
        self.request_context.profile_id = str(session.get("actor_profile_id") or "")
        self.request_context.actor_profile_id = str(session.get("actor_profile_id") or "")
        self.request_context.execution_profile_id = str(session.get("execution_profile_id") or "")
        try:
            return operation()
        finally:
            for key, value in zip(keys, original):
                if value is None and hasattr(self.request_context, key):
                    delattr(self.request_context, key)
                elif value is not None:
                    setattr(self.request_context, key, value)

    def _child_time_control_permit(self, client: dict[str, Any],
                                   session: dict[str, Any], game_only: bool = False) \
            -> tuple[Any, str]:
        stream = self.control_state.get("stream")
        if not isinstance(stream, dict):
            return None, "none"
        if (stream.get("client_id") != session.get("client_id") or
                stream.get("actor_profile_id") != session.get("actor_profile_id") or
                stream.get("execution_profile_id") != session.get("execution_profile_id") or
                stream.get("child_session_id") != session.get("session_id")):
            return False, "unmanaged_stream"
        operation_id = ("child-game-stop:" if game_only else "child-terminal:") \
            + str(session.get("session_id") or "")
        # Retry the host-owned exact operation before acquire, which deliberately
        # waits for client-owned permits and cannot advance this cleanup itself.
        with self.control_condition:
            active = self.control_state.get("active")
            idle_identity = isinstance(active, dict) \
                and active.get("provider_idle_confirmed") is True \
                and not self._child_time_normalize_game_id(active.get("provider_game_id")) \
                and not self._child_time_process_token(active) \
                and int(active.get("process_id") or 0) == 0 \
                and not self._child_time_process_token(session) \
                and int(session.get("process_id") or 0) == 0 \
                and not self._child_time_process_token(stream) \
                and int(stream.get("process_id") or 0) == 0
            running_identity = isinstance(active, dict) \
                and self._child_time_normalize_game_id(active.get("provider_game_id")) == \
                    self._child_time_normalize_game_id(session.get("game_id")) \
                and self._child_time_process_token(active) == \
                    self._child_time_process_token(session) \
                and int(active.get("process_id") or 0) == \
                    int(session.get("process_id") or 0)
            if isinstance(active, dict) and active.get("operation_id") == operation_id \
                    and all(active.get(key) == session.get(key) for key in (
                        "client_id", "actor_profile_id", "execution_profile_id")) \
                    and active.get("child_session_id") == session.get("session_id") \
                    and active.get("stream_session_id") == stream.get("stream_session_id") \
                    and active.get("sunshine_app_id") == stream.get("sunshine_app_id") \
                    and active.get("expected_generation") == stream.get("generation") \
                    and active.get("generation") == self.control_state.get("generation") \
                    and active.get("actor_lease_epoch") == \
                        self.control_state.get("actor_lease_epoch") \
                    and (idle_identity or running_identity) \
                    and active.get("state") in {"permitted", "uncertain"}:
                return dict(active), "none"
        status, permit = self._child_time_control_call(session, lambda:
            self.control_operation_acquire(client, {
                "operation_id": operation_id,
                "intent": "provider_transition" if game_only else "child_terminal",
                "stream_session_id": str(stream.get("stream_session_id") or ""),
                "sunshine_app_id": int(stream.get("sunshine_app_id") or 0),
                "expected_generation": int(stream.get("generation") or 0),
                "observed_sunshine_app_id": int(stream.get("sunshine_app_id") or 0),
                "child_session_id": str(session.get("session_id") or ""),
            }, operation_id))
        if status == HTTPStatus.OK and permit.get("state") == "permitted":
            return permit, "none"
        return False, str(permit.get("reason") or "cleanup_required")

    def _child_time_complete_pending_game_stop(
            self, client: dict[str, Any], session: dict[str, Any], wall: datetime) -> bool:
        """Finish an exact game-only receipt before upgrading to full cleanup."""
        operation_id = "child-game-stop:" + str(session.get("session_id") or "")
        with self.control_condition:
            self._control_recover_provider_locked()
            active = self.control_state.get("active")
            stream = self.control_state.get("stream")
            exact = isinstance(active, dict) and active.get("operation_id") == operation_id \
                and active.get("intent") == "provider_transition" \
                and active.get("child_session_id") == session.get("session_id") \
                and all(active.get(key) == session.get(key) for key in (
                    "client_id", "actor_profile_id", "execution_profile_id")) \
                and isinstance(stream, dict) \
                and active.get("stream_session_id") == stream.get("stream_session_id") \
                and active.get("sunshine_app_id") == stream.get("sunshine_app_id") \
                and active.get("expected_generation") == stream.get("generation") \
                and active.get("generation") == self.control_state.get("generation") \
                and active.get("actor_lease_epoch") == \
                    self.control_state.get("actor_lease_epoch") \
                and self._child_time_process_token(active) == \
                    self._child_time_process_token(session) \
                and int(active.get("process_id") or 0) == \
                    int(session.get("process_id") or 0) \
                and active.get("state") in {"permitted", "uncertain"}
            permit = dict(active) if exact else None
        if permit is None:
            return True
        stopped, _reason = self._child_time_stop_execution(client, session, wall, permit)
        return stopped and self._child_time_control_call(session, lambda:
            self._control_provider_complete(client, permit, True))

    def _child_time_stop_stream(self, client: dict[str, Any], session: dict[str, Any],
                                permit: Any, provider_stopped: bool) -> tuple[bool, str]:
        if permit is None:
            return provider_stopped, "none" if provider_stopped else "cleanup_required"
        if permit is False:
            return False, "cleanup_required"
        operation_id = str(permit["operation_id"])
        if not provider_stopped:
            self._child_time_control_call(session, lambda:
                self.control_operation_complete(client, {
                    "operation_id": operation_id, "generation": permit["generation"],
                    "actor_lease_epoch": permit["actor_lease_epoch"],
                    "succeeded": False}, operation_id))
            return False, "cleanup_required"
        with self.control_condition:
            active = self.control_state.get("active")
            stream = self.control_state.get("stream")
            identity = tuple(str(session.get(key) or "") for key in (
                "client_id", "actor_profile_id", "execution_profile_id"))
            if (self.child_time_session is not None and
                    self.child_time_session is not session) or not \
                    ControlOperationPolicy.supplied_matches(active, permit, identity) \
                    or not isinstance(stream, dict) \
                    or stream.get("child_session_id") != session.get("session_id"):
                return False, "stale_session"
            closed, close_reason = StreamerControlOperation.close(
                self.proxy, str(session.get("execution_profile_id") or ""), stream)
            terminal_status, terminal = self._child_time_control_call(session, lambda:
                self.control_operation_complete(client, {
                    "operation_id": operation_id, "generation": permit["generation"],
                    "actor_lease_epoch": permit["actor_lease_epoch"],
                    "succeeded": bool(closed)}, operation_id))
        return (terminal_status == HTTPStatus.OK and terminal.get("state") == "succeeded",
                "none" if closed else close_reason)

    def _child_time_finish(self, client: dict[str, Any], session: dict[str, Any],
                           reason: str, wall: datetime,
                           force: bool = False) -> tuple[int, dict[str, Any]]:
        terminal = str(reason or "").casefold() != "game_stopped"
        if terminal:
            with self.lock:
                if not self.child_terminal_operation.reserve_terminal(session):
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "cleanup_required": True,
                        "reason": "stale_session"}
        return self.child_terminal_operation.run(
            session, terminal,
            lambda: self._child_time_finish_once(
                client, session, reason, wall, force))

    def _child_time_finish_retry(self, client: dict[str, Any], session: dict[str, Any],
                                 reason: str, wall: datetime,
                                 force: bool = False) -> tuple[int, dict[str, Any]]:
        """Join once, then continue the same durable fenced cleanup once if needed."""
        result = self._child_time_finish(client, session, reason, wall, force)
        if result[0] == HTTPStatus.OK and not result[1].get("cleanup_required"):
            return result
        if not result[1].get("cleanup_required") or self.child_time_session is not session:
            return result
        return self._child_time_finish(client, session, reason, wall, force)

    def _child_time_release_lease(self, client: dict[str, Any],
                                  session: dict[str, Any], reason: str,
                                  wall: datetime,
                                  next_actor_profile_id: str = "") \
            -> tuple[int, dict[str, Any]]:
        """Close only child time accounting; provider and stream use common owners."""
        actor_id = str(session.get("actor_profile_id") or "")
        parent_id = str(session.get("execution_profile_id") or "")
        actor = self.config.get("profiles", {}).get(actor_id)
        actor = actor if isinstance(actor, dict) else {
            "id": actor_id, "policy_revision": 0}
        now_monotonic, wall = self._child_time_now(actor)
        with self.lock:
            if self.child_time_session is not session:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "cleanup_required": False,
                    "reason": "stale_session"}
            if self.child_terminal_operation.in_progress(session):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "cleanup_required": True,
                    "reason": "child_cleanup_in_progress",
                    "actor_profile_id": actor_id,
                    "execution_profile_id": parent_id,
                    "client_id": str(session.get("client_id") or ""),
                    "game_id": str(session.get("game_id") or ""),
                    "session_id": str(session.get("session_id") or ""),
                }
            next_actor = str(next_actor_profile_id or "")
            if next_actor:
                target = self.config.get("profiles", {}).get(next_actor)
                stream = self.control_state.get("stream")
                exact_stream = isinstance(stream, dict) and all(
                    stream.get(key) == session.get(session_key)
                    for key, session_key in (
                        ("client_id", "client_id"),
                        ("actor_profile_id", "actor_profile_id"),
                        ("execution_profile_id", "execution_profile_id"),
                        ("child_session_id", "session_id")))
                if (next_actor != parent_id or not self._is_standard_profile(target)
                        or not exact_stream
                        or isinstance(self.control_state.get("active"), dict)):
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "cleanup_required": True,
                        "reason": "actor_handoff_unconfirmed"}
                generation = int(self.control_state.get("generation") or 0) + 1
                actor_epoch = int(self.control_state.get("actor_lease_epoch") or 0) + 1
                self.control_state.update({
                    "generation": generation,
                    "actor_lease_epoch": actor_epoch,
                })
                stream.update({
                    "actor_profile_id": next_actor,
                    "child_session_id": "",
                    "generation": generation,
                    "actor_lease_epoch": actor_epoch,
                })
                session["control_generation"] = generation
            if session.get("phase") == "running":
                self._child_time_accrue_locked(
                    session, now_monotonic, wall,
                    float(session.get("last_playable_seconds", 0.0)))
            session["phase"] = "ended"
            session["end_reason"] = reason
            self.child_time_session = None
            self.child_time_usage["active_session"] = None
            self.child_time_completed[str(session.get("session_id") or "")] = {
                "reason": reason, "game_id": str(session.get("game_id") or ""),
                "actor_profile_id": actor_id,
                "execution_profile_id": parent_id,
                "client_id": str(session.get("client_id") or ""),
                "control_generation": int(session.get("control_generation") or 0),
            }
            self._child_time_bump_revision_locked()
            self._child_time_checkpoint_locked(force=True)
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            policy = {"day_key": wall.date().isoformat(), "weekday":
                      self._child_time_day_name(wall.date()), "used_seconds": 0,
                      "remaining_daily_seconds": 0, "playable_now_seconds": 0}
        return HTTPStatus.OK, self._child_time_body(
            client, actor_id, parent_id, actor, policy, wall, session,
            state="ended", reason=reason)

    def _child_time_finish_once(self, client: dict[str, Any], session: dict[str, Any],
                                reason: str, wall: datetime,
                                force: bool = False) -> tuple[int, dict[str, Any]]:
        with self.lock:
            if self.child_time_session is not None and self.child_time_session is not session:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "cleanup_required": True, "reason": "stale_session"}
            # Preserve intent across timeout/status/housekeeping retries. A
            # failed game-only stop must not silently become whole-session quit.
            saved_reason = session.get("terminal_reason")
            if saved_reason and (saved_reason != "game_stopped" or
                                 reason == session.get("end_reason")):
                reason = str(saved_reason)
            session["terminal_reason"] = reason
        game_only = str(reason or "").casefold() == "game_stopped"
        prior_stop_complete = game_only or self._child_time_complete_pending_game_stop(
            client, session, wall)
        if prior_stop_complete:
            permit, permit_reason = self._child_time_control_permit(
                client, session, game_only)
        else:
            permit, permit_reason = False, "cleanup_required"
        if isinstance(permit, dict):
            with self.control_condition:
                active = self.control_state.get("active")
                if isinstance(active, dict) and active.get("operation_id") == permit["operation_id"]:
                    active["child_terminal_result"] = {
                        "reason": reason, **{key: str(session.get(key) or "") for key in (
                            "game_id", "actor_profile_id", "execution_profile_id", "client_id")}}
                    self._control_save_locked()
        if permit is False:
            stopped, stop_reason = False, permit_reason
        else:
            stopped, stop_reason = self._child_time_stop_execution(
                client, session, wall, permit, force)
        if game_only and permit is not None and permit is not False:
            # Never leave a host-owned permit in "permitted" when the Bridge
            # rejects or loses the stop response. An uncertain terminal write is
            # recoverable through provider retirement; a permitted orphan is not.
            completed = self._child_time_control_call(session, lambda:
                self._control_provider_complete(client, permit, stopped))
            stopped = stopped and completed
            stop_reason = "none" if stopped else "cleanup_required"
        elif not game_only:
            stopped, stop_reason = self._child_time_stop_stream(
                client, session, permit, stopped)
        actor_id = str(session.get("actor_profile_id") or "")
        parent_id = str(session.get("execution_profile_id") or "")
        actor = self.config.get("profiles", {}).get(actor_id)
        actor = actor if isinstance(actor, dict) else {"id": actor_id, "policy_revision": 0}
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            policy = {"day_key": wall.date().isoformat(), "weekday":
                      self._child_time_day_name(wall.date()), "used_seconds": 0,
                      "remaining_daily_seconds": 0, "playable_now_seconds": 0}
        with self.lock:
            current = self.child_time_session
            terminal_pending = game_only \
                and self.child_terminal_operation.terminal_pending(session)
            # The object identity is the generation guard.  A delayed Bridge
            # response must never finish a later session that reused a UUID.
            if current is session:
                if stopped and not terminal_pending:
                    session["phase"] = "ended"
                    session["end_reason"] = reason
                    self.child_time_session = None
                    self.child_time_usage["active_session"] = None
                    self.child_time_completed[str(session.get("session_id"))] = {
                        "reason": reason, "game_id": str(session.get("game_id") or ""),
                        "actor_profile_id": actor_id,
                        "execution_profile_id": parent_id,
                        "client_id": str(session.get("client_id") or ""),
                    }
                elif stopped:
                    session["phase"] = "ending"
                    session["end_reason"] = reason
                else:
                    session["phase"] = "cleanup_required"
                    session["end_reason"] = stop_reason
                self._child_time_bump_revision_locked()
                self._child_time_checkpoint_locked(force=True)
            final_reason = reason if stopped else stop_reason
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall, session,
                state="ended" if stopped else "cleanup_required",
                reason=final_reason, ok=stopped)
            body["phase"] = "ended" if stopped else "cleanup_required"
            body["cleanup_required"] = not stopped
            return (HTTPStatus.OK if stopped else HTTPStatus.CONFLICT), body

    def child_time_eligibility(self, client: dict[str, Any], actor_profile_id: str,
                               game_id: Any) -> tuple[int, dict[str, Any]]:
        self.refresh_registry_if_changed()
        actor_id, parent_id, parent, actor = self._child_policy_context(
            client, actor_profile_id)
        canonical, game_key, _catalog = self._resolve_child_catalog_game(parent_id, game_id)
        _monotonic, wall = self._child_time_now(actor)
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "reason": "policy_unavailable",
                "actor_profile_id": actor_id, "execution_profile_id": parent_id,
            }
        policy = {**policy, "game_id": canonical}
        client_id = self.broker_client_id(client)
        active = self.child_time_session
        reason = str(policy.get("reason") or "none")
        playable = float(policy.get("playable_now_seconds", 0.0))
        allowed = actor.get("allowed_game_keys")
        if not isinstance(allowed, list) or game_key not in allowed:
            reason, playable = "game_not_shared", 0.0
        else:
            session_gate = self._child_time_session_gate(
                active, actor_id, client_id)
            if session_gate:
                reason, playable = session_gate, 0.0
        try:
            self._child_time_client_uuid(client, parent_id)
            stream_binding_available = True
        except PermissionError:
            stream_binding_available = False
        if not stream_binding_available and reason == "none":
            reason, playable = "pairing_required", 0.0
        if reason != "none" or playable <= 0:
            policy["playable_now_seconds"] = 0.0
        body = self._child_time_body(
            client, actor_id, parent_id, actor, policy, wall,
            state="ready" if playable > 0 and reason == "none" else "blocked",
            reason=reason, ok=playable > 0 and reason == "none")
        body["game_id"] = canonical
        body["stream_binding_available"] = stream_binding_available
        return HTTPStatus.OK, body

    def child_time_session_start(self, client: dict[str, Any], actor_profile_id: str,
                                 game_id: Any, session_id: str,
                                 request_id: str = "") -> tuple[int, dict[str, Any]]:
        self.refresh_registry_if_changed()
        session_id = self._child_time_id(session_id, "child session ID")
        request_id = self._child_time_request_id(request_id or session_id)
        orphan = self._child_time_recover_persisted_terminal_orphan()
        if orphan is not None and orphan[0] != HTTPStatus.OK:
            return orphan
        actor_id, parent_id, parent, actor = self._child_policy_context(
            client, actor_profile_id)
        canonical, _key, _catalog = self._resolve_child_catalog_game(parent_id, game_id)
        allowed = actor.get("allowed_game_keys")
        if not isinstance(allowed, list) or _key not in allowed:
            raise PermissionError("game_not_shared")
        client_id = self.broker_client_id(client)
        try:
            client_uuid = self._child_time_client_uuid(client, parent_id)
        except PermissionError:
            return HTTPStatus.CONFLICT, {
                "ok": False, "state": "blocked", "reason": "pairing_required",
                "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                "client_id": client_id, "game_id": canonical, "session_id": session_id,
            }
        _monotonic, wall = self._child_time_now(actor)
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "state": "blocked", "reason": "policy_unavailable",
                "actor_profile_id": actor_id, "execution_profile_id": parent_id,
            }
        if float(policy.get("playable_now_seconds", 0.0)) <= 0:
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall,
                state="blocked", reason=str(policy.get("reason") or "outside_schedule"))
            body["game_id"] = canonical
            body["session_id"] = session_id
            return HTTPStatus.CONFLICT, body
        previous = self.child_time_session
        if isinstance(previous, dict) and self.child_terminal_operation.in_progress(previous):
            if not self.child_terminal_operation.wait_until_idle(previous, 38.0):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_in_progress",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            if self.child_time_session is not previous:
                return self.child_time_session_start(
                    client, actor_profile_id, game_id, session_id, request_id)
        if isinstance(previous, dict) and str(previous.get(
                "phase") or "").casefold() in {"ending", "cleanup_required"}:
            terminal_reason = str(previous.get("terminal_reason")
                                  or previous.get("end_reason")
                                  or "child_session_expired")
            _previous_monotonic, previous_wall = self._child_time_now()
            terminal_status, terminal = self._child_time_finish_retry(
                client, previous, terminal_reason, previous_wall)
            if terminal_status != HTTPStatus.OK or terminal.get("cleanup_required"):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_required",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            return self.child_time_session_start(
                client, actor_profile_id, game_id, session_id, request_id)
        with self.lock:
            active = self.child_time_session
            if isinstance(active, dict) and self.child_terminal_operation.in_progress(active):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_in_progress",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            if isinstance(active, dict) and self._child_time_session_matches(
                    active, actor_id, client_id, canonical, session_id):
                phase = str(active.get("phase") or "launch_pending")
                if str(active.get("request_id") or "") != request_id:
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "state": "blocked",
                        "reason": "request_binding_mismatch",
                        "actor_profile_id": actor_id,
                        "execution_profile_id": parent_id,
                        "client_id": client_id, "game_id": canonical,
                        "session_id": session_id,
                    }
                return (HTTPStatus.OK if phase == "running" else
                        HTTPStatus.ACCEPTED if phase == "launch_pending" else
                        HTTPStatus.CONFLICT), self._child_time_body(
                    client, actor_id, parent_id, actor, policy, wall, active,
                    state=phase,
                    reason=str(active.get("end_reason") or
                               ("none" if phase in {"running", "launch_pending"} else
                                "cleanup_required")),
                    ok=phase in {"running", "launch_pending"})
            same_owner = isinstance(active, dict) and all(
                str(active.get(key) or "") == value for key, value in (
                    ("client_id", client_id),
                    ("execution_profile_id", parent_id)))
        if same_owner:
            # Re-evaluate before the atomic handoff. Any policy/pairing failure
            # must leave the previous actor and its time attachment intact.
            actor_id, refreshed_parent_id, parent, actor = self._child_policy_context(
                client, actor_profile_id)
            if refreshed_parent_id != parent_id:
                return HTTPStatus.SERVICE_UNAVAILABLE, {
                    "ok": False, "state": "blocked", "reason": "policy_unavailable",
                    "actor_profile_id": actor_id,
                    "execution_profile_id": refreshed_parent_id,
                }
            allowed = actor.get("allowed_game_keys")
            if not isinstance(allowed, list) or _key not in allowed:
                raise PermissionError("game_not_shared")
            try:
                client_uuid = self._child_time_client_uuid(client, parent_id)
            except PermissionError:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "reason": "pairing_required",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            _monotonic, wall = self._child_time_now(actor)
            try:
                policy = self._child_time_policy_snapshot(actor_id, actor, wall)
            except (TypeError, ValueError):
                return HTTPStatus.SERVICE_UNAVAILABLE, {
                    "ok": False, "state": "blocked", "reason": "policy_unavailable",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                }
            if float(policy.get("playable_now_seconds", 0.0)) <= 0:
                body = self._child_time_body(
                    client, actor_id, parent_id, actor, policy, wall,
                    state="blocked",
                    reason=str(policy.get("reason") or "outside_schedule"))
                body["game_id"] = canonical
                body["session_id"] = session_id
                return HTTPStatus.CONFLICT, body
        elif isinstance(active, dict):
            return HTTPStatus.CONFLICT, {
                "ok": False, "state": "blocked", "reason": "session_in_use",
                "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                "client_id": client_id, "game_id": canonical,
            }
        current = self._child_time_current_game(parent_id, self.control_state.get("stream"))
        if current.get("kind") == "active" and \
                self._child_time_normalize_game_id(current.get("game_id")) != \
                self._child_time_normalize_game_id(canonical):
            return HTTPStatus.CONFLICT, {
                "ok": False, "state": "blocked",
                "reason": "active_game_replacement_required",
                "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                "client_id": client_id, "game_id": canonical,
                "session_id": session_id,
            }
        current_identity = current.get("identity") \
            if current.get("kind") == "active" and \
            self._child_time_normalize_game_id(current.get("game_id")) == \
            self._child_time_normalize_game_id(canonical) else None
        current_token = self._child_time_process_token(current_identity)
        session = {
            "session_id": session_id, "request_id": request_id,
            "actor_profile_id": actor_id, "execution_profile_id": parent_id,
            "client_id": client_id, "game_id": canonical,
            "vibepollo_client_uuid": client_uuid,
            "phase": "running" if current_token else "launch_pending",
            "started_monotonic": _monotonic, "last_monotonic": _monotonic,
            "last_heartbeat_monotonic": _monotonic,
            "last_wall_epoch": wall.timestamp(),
            "last_heartbeat_wall_epoch": wall.timestamp(),
            "last_playable_seconds": float(policy.get("playable_now_seconds", 0.0)),
            "process_token": current_token,
            "process_id": int((current_identity or {}).get("process_id") or 0),
            "policy_revision": int(actor.get("policy_revision", 0) or 0),
        }
        with self.lock:
            if same_owner and self.child_time_session is active \
                    and self.child_terminal_operation.in_progress(active):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "phase": "cleanup_required",
                    "cleanup_required": True, "reason": "child_cleanup_in_progress",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            if same_owner:
                if self.child_time_session is not active:
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "state": "blocked", "reason": "stale_session",
                    }
            elif self.child_time_session is not None:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked", "reason": "session_in_use",
                }
            if session_id in self.child_time_completed:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked",
                    "reason": "session_id_reused",
                    "actor_profile_id": actor_id,
                    "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            stream = self.control_state.get("stream")
            if same_owner and isinstance(stream, dict) and any(
                    stream.get(key) != active.get(session_key)
                    for key, session_key in (
                        ("client_id", "client_id"),
                        ("actor_profile_id", "actor_profile_id"),
                        ("execution_profile_id", "execution_profile_id"),
                        ("child_session_id", "session_id"))):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked",
                    "reason": "actor_handoff_unconfirmed",
                }
            if isinstance(stream, dict) and self._child_time_process_token(stream) \
                    and not current_token and not self._child_time_process_absent(
                        current, self._child_time_process_token(stream)):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked",
                    "reason": "retained_process_unconfirmed",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            if isinstance(stream, dict) and not self._control_bind_process_identity_locked(session):
                return HTTPStatus.CONFLICT, {
                    "ok": False, "state": "blocked",
                    "reason": "retained_process_unconfirmed",
                    "actor_profile_id": actor_id, "execution_profile_id": parent_id,
                    "client_id": client_id, "game_id": canonical,
                    "session_id": session_id,
                }
            if same_owner:
                previous_actor_id = str(active.get("actor_profile_id") or "")
                previous_actor = self.config.get("profiles", {}).get(previous_actor_id)
                previous_monotonic, previous_wall = self._child_time_now(
                    previous_actor if isinstance(previous_actor, dict) else None)
                if active.get("phase") == "running":
                    self._child_time_accrue_locked(
                        active, previous_monotonic, previous_wall,
                        float(active.get("last_playable_seconds", 0.0)))
                active["phase"] = "ended"
                active["end_reason"] = "actor_replaced"
                self.child_time_completed[str(active.get("session_id") or "")] = {
                    "reason": "actor_replaced",
                    "game_id": str(active.get("game_id") or ""),
                    "actor_profile_id": previous_actor_id,
                    "execution_profile_id": str(active.get("execution_profile_id") or ""),
                    "client_id": str(active.get("client_id") or ""),
                    "control_generation": int(session.get("control_generation") or 0),
                }
            self.child_time_session = session
            self._child_time_bump_revision_locked()
            self._child_time_checkpoint_locked(force=True)
        # The allowance owns policy/time only. The Android launch transition
        # performs common control reauthorization and /game/start after its
        # transport lease is durable; there is no child provider binding.
        with self.lock:
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall, session,
                state=str(session.get("phase") or "launch_pending"), reason="none")
        return HTTPStatus.ACCEPTED, body

    def child_time_session_status(self, client: dict[str, Any], actor_profile_id: str,
                                  session_id: str) -> tuple[int, dict[str, Any]]:
        self.refresh_registry_if_changed()
        session_id = self._child_time_id(session_id, "child session ID")
        try:
            session = self._child_time_find_session(client, actor_profile_id, session_id)
        except PermissionError:
            completed = self.child_time_completed.get(session_id)
            if (not isinstance(completed, dict) or
                    completed.get("actor_profile_id") != actor_profile_id or
                    completed.get("client_id") != self.broker_client_id(client)):
                raise
            return HTTPStatus.OK, {
                "ok": True, "state": "ended", "phase": "ended",
                **completed, "session_id": session_id, "cleanup_required": False,
            }
        parent_id = str(session.get("execution_profile_id") or "")
        actor_id = str(session.get("actor_profile_id") or "")
        actor = self.config.get("profiles", {}).get(actor_id)
        actor = actor if isinstance(actor, dict) else {"id": actor_id, "policy_revision": 0}
        _monotonic, wall = self._child_time_now(actor)
        if session.get("phase") == "cleanup_required":
            return self._child_time_finish(client, session,
                str(session.get("terminal_reason") or session.get("end_reason")
                    or "child_session_expired"), wall)
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "state": "blocked", "reason": "policy_unavailable",
                "session_id": session_id,
            }
        return HTTPStatus.OK, self._child_time_body(
            client, actor_id, parent_id, actor, policy, wall, session,
            state=str(session.get("phase") or "launch_pending"), reason=str(
                session.get("end_reason") or "none"))

    def child_time_session_resume(self, client: dict[str, Any], actor_profile_id: str,
                                  session_id: str) -> tuple[int, dict[str, Any]]:
        status, body = self.child_time_session_status(client, actor_profile_id, session_id)
        if status != HTTPStatus.OK:
            return status, body
        if body.get("phase") != "running":
            body.update({"ok": False, "state": "blocked", "reason": "child_session_expired"})
            return HTTPStatus.CONFLICT, body
        session = self.child_time_session
        current = self._child_time_current_game(str(session.get("execution_profile_id") or "")) \
            if isinstance(session, dict) else {"kind": "idle"}
        if (current.get("kind") != "active" or
                self._child_time_normalize_game_id(current.get("game_id")) !=
                self._child_time_normalize_game_id(session.get("game_id"))):
            body.update({"ok": False, "state": "blocked", "reason": "child_session_expired"})
            return HTTPStatus.CONFLICT, body
        actor = self.config.get("profiles", {}).get(
            str(session.get("actor_profile_id") or ""))
        _resume_monotonic, resume_wall = self._child_time_now(
            actor if isinstance(actor, dict) else None)
        if int(body.get("playable_now_seconds", 0) or 0) <= 0:
            return self._child_time_finish(
                client, session, str(body.get("reason") or "daily_limit_reached"),
                resume_wall)
        token = self._child_time_process_token(current.get("identity"))
        if not token or token != self._child_time_process_token(session):
            return self._child_time_finish(client, session,
                                           "child_session_expired", resume_wall)
        return status, body

    def child_time_session_heartbeat(self, client: dict[str, Any], actor_profile_id: str,
                                      session_id: str,
                                      update_client_heartbeat: bool = True,
                                      expected_session: dict[str, Any] | None = None) \
            -> tuple[int, dict[str, Any]]:
        self.refresh_registry_if_changed()
        session_id = self._child_time_id(session_id, "child session ID")
        session = self._child_time_find_session(client, actor_profile_id, session_id)
        if expected_session is not None and session is not expected_session:
            raise PermissionError("stale_child_session")
        actor_id = str(session.get("actor_profile_id") or "")
        parent_id = str(session.get("execution_profile_id") or "")
        actor = self.config.get("profiles", {}).get(actor_id)
        parent = self.config.get("profiles", {}).get(parent_id)
        actor = actor if isinstance(actor, dict) else {"id": actor_id, "policy_revision": 0}
        _monotonic, wall = self._child_time_now(actor)
        if session.get("phase") == "cleanup_required":
            return self._child_time_finish(
                client, session,
                str(session.get("terminal_reason") or session.get("end_reason")
                    or "child_session_expired"), wall)
        # The game and its budget belong to the host, even with no TV connected.
        # Housekeeping continues policy checks through the common lifecycle.
        if update_client_heartbeat:
            with self.lock:
                if (expected_session is not None and
                        self.child_time_session is not expected_session):
                    raise PermissionError("stale_child_session")
                session["last_heartbeat_monotonic"] = _monotonic
                session["last_heartbeat_wall_epoch"] = wall.timestamp()
        allowed = (isinstance(actor, dict) and actor.get("enabled") is True and
                   not profile_deletion_pending(actor) and
                   isinstance(actor.get("allowed_game_keys"), list) and
                   str(parent_id) + "/" + str(session.get("game_id") or "") in
                   actor.get("allowed_game_keys", []))
        grants = client.get("profile_grants", {}) if isinstance(client, dict) else {}
        permissions = grants.get(actor_id, []) if isinstance(grants, dict) else []
        client_granted = isinstance(permissions, list) and "use_profile" in permissions
        parent_available = (self._is_standard_profile(parent) and
                            parent.get("enabled") is True and
                            not profile_deletion_pending(parent))
        if not allowed or not client_granted or not parent_available:
            return self._child_time_finish(client, session, "game_access_revoked", wall)
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "state": str(session.get("phase") or "launch_pending"),
                "reason": "policy_unavailable", "session_id": session_id,
            }
        current = self._child_time_current_game(parent_id)
        if current.get("kind") == "unavailable":
            with self.lock:
                self._child_time_checkpoint_locked()
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall, session,
                state=str(session.get("phase") or "launch_pending"),
                reason="policy_unavailable", ok=False)
            return HTTPStatus.SERVICE_UNAVAILABLE, body
        identity = current.get("identity") if current.get("kind") == "active" else None
        exact_current = (current.get("kind") == "active" and
                         self._child_time_normalize_game_id(current.get("game_id")) ==
                         self._child_time_normalize_game_id(session.get("game_id")))
        phase = str(session.get("phase") or "launch_pending")
        # Housekeeping also runs while /game/start is still waiting for the
        # launcher. An idle snapshot is expected until that launch is observed.
        awaiting_launch = (phase == "launch_pending" and
                           _monotonic - float(session.get("started_monotonic", 0.0))
                           < CHILD_TIME_CLIENT_GRACE_SECONDS)
        if not exact_current and not (awaiting_launch and current.get("kind") == "idle"):
            if current.get("kind") == "idle" or phase == "launch_pending":
                return self._child_time_release_lease(
                    client, session, "child_session_expired", wall)
            # A replacement process is not proof that the shared transport is
            # finished. Retire only the old exact token, then close the same
            # fenced stream without touching the replacement process.
            return self._child_time_finish(
                client, session, "child_session_expired", wall)
        token = self._child_time_process_token(identity)
        if phase == "launch_pending" and not token:
            if not awaiting_launch:
                return self._child_time_finish(client, session, "child_session_expired", wall)
            if float(policy.get("playable_now_seconds", 0.0)) <= 0:
                return self._child_time_finish(
                    client, session, str(policy.get("reason") or "daily_limit_reached"), wall)
            with self.lock:
                self._child_time_checkpoint_locked()
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall, session,
                state="launch_pending", reason="none")
            return HTTPStatus.ACCEPTED, body
        if phase == "launch_pending":
            binding_mismatch = False
            with self.lock:
                session["process_token"] = token
                session["process_id"] = int(identity.get("process_id") or 0)
                if self.child_time_session is not session:
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "state": "blocked", "reason": "session_in_use",
                    }
                if not self._control_bind_process_identity_locked(session):
                    binding_mismatch = True
                else:
                    session["phase"] = "running"
            if binding_mismatch:
                return self._child_time_finish(
                    client, session, "child_session_expired", wall)
            with self.lock:
                # The time spent waiting for an exact process identity is not
                # playable time.
                session["last_monotonic"] = _monotonic
                session["last_wall_epoch"] = wall.timestamp()
                session["last_playable_seconds"] = float(
                    policy.get("playable_now_seconds", 0.0))
                self._child_time_bump_revision_locked()
                self._child_time_checkpoint_locked(force=True)
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall, session,
                state="running", reason="none")
            return HTTPStatus.OK, body
        if phase != "running":
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall, session,
                state=phase, reason=str(session.get("end_reason") or
                                       "cleanup_required"), ok=False)
            return HTTPStatus.CONFLICT, body
        stored_token = self._child_time_process_token(session)
        if not token or (stored_token and token != stored_token):
            return self._child_time_finish(
                client, session, "child_session_expired", wall)
        with self.lock:
            if session.get("clock_rollback"):
                rolled_back = True
            else:
                # Read time when charging, after the Bridge request. Another
                # heartbeat may have charged a newer timestamp while we waited.
                _monotonic, wall = self._child_time_now(actor)
                _charged, rolled_back = self._child_time_accrue_locked(
                    session, _monotonic, wall,
                    float(session.get("last_playable_seconds", 0.0)))
                if rolled_back:
                    session["clock_rollback"] = True
            self._child_time_checkpoint_locked()
            clock_rollback = bool(session.get("clock_rollback"))
        if clock_rollback:
            return self._child_time_finish(client, session, "policy_unavailable", wall)
        try:
            policy = self._child_time_policy_snapshot(actor_id, actor, wall)
        except (TypeError, ValueError):
            return self._child_time_finish(client, session, "policy_unavailable", wall)
        if float(policy.get("playable_now_seconds", 0.0)) <= 0:
            return self._child_time_finish(client, session,
                                           str(policy.get("reason") or "daily_limit_reached"), wall)
        with self.lock:
            session["last_playable_seconds"] = float(
                policy.get("playable_now_seconds", 0.0))
            body = self._child_time_body(
                client, actor_id, parent_id, actor, policy, wall, session,
                state="running", reason="none")
        return HTTPStatus.OK, body

    def child_time_session_end(self, client: dict[str, Any], actor_profile_id: str,
                               session_id: str, reason: str = "client_closed",
                               next_actor_profile_id: str = "") \
            -> tuple[int, dict[str, Any]]:
        self.refresh_registry_if_changed()
        session_id = self._child_time_id(session_id, "child session ID")
        client_id = self.broker_client_id(client)
        try:
            session = self._child_time_find_session(client, actor_profile_id, session_id)
        except PermissionError:
            # A delayed end from an older generation is a harmless idempotent
            # no-op.  A request from another actor/client still cannot touch
            # the active generation.
            active = self.child_time_session
            if isinstance(active, dict) and (
                    str(active.get("actor_profile_id") or "") != str(actor_profile_id) or
                    str(active.get("client_id") or "") != client_id):
                raise
            completed = self.child_time_completed.get(session_id, {})
            return HTTPStatus.OK, {
                "ok": True,
                "state": "ended",
                "phase": "ended",
                "reason": str(completed.get("reason") or "stale_session"),
                "actor_profile_id": str(completed.get(
                    "actor_profile_id") or actor_profile_id),
                "execution_profile_id": str(completed.get("execution_profile_id") or ""),
                "client_id": client_id,
                "game_id": str(completed.get("game_id") or ""),
                "session_id": session_id,
                "control_generation": int(completed.get("control_generation") or 0),
                "cleanup_required": False,
            }
        terminal_reason = str(reason or "client_closed")
        if terminal_reason.casefold() not in {
                "game_stopped", "replacement", "cancelled", "actor_replaced"}:
            with self.lock:
                if not self.child_terminal_operation.reserve_terminal(session):
                    return HTTPStatus.CONFLICT, {
                        "ok": False, "state": "blocked", "phase": "cleanup_required",
                        "cleanup_required": True, "reason": "child_cleanup_required",
                        "actor_profile_id": str(session.get("actor_profile_id") or ""),
                        "execution_profile_id": str(session.get(
                            "execution_profile_id") or ""),
                        "client_id": client_id, "game_id": str(session.get("game_id") or ""),
                        "session_id": session_id,
                    }
        actor_id = str(session.get("actor_profile_id") or "")
        parent_id = str(session.get("execution_profile_id") or "")
        actor = self.config.get("profiles", {}).get(actor_id)
        actor = actor if isinstance(actor, dict) else {"id": actor_id, "policy_revision": 0}
        _monotonic, wall = self._child_time_now(actor)
        if session.get("phase") == "running":
            try:
                policy = self._child_time_policy_snapshot(actor_id, actor, wall)
            except (TypeError, ValueError):
                policy = {"day_key": wall.date().isoformat(), "weekday":
                          self._child_time_day_name(wall.date()), "used_seconds": 0,
                          "remaining_daily_seconds": 0, "playable_now_seconds": 0}
            current = self._child_time_current_game(parent_id)
            if current.get("kind") == "active" and self._child_time_normalize_game_id(
                    current.get("game_id")) == self._child_time_normalize_game_id(
                    session.get("game_id")):
                current_token = self._child_time_process_token(current.get("identity"))
                session_token = self._child_time_process_token(session)
                if current_token and current_token == session_token:
                    with self.lock:
                        _monotonic, wall = self._child_time_now(actor)
                        self._child_time_accrue_locked(
                            session, _monotonic, wall,
                            float(session.get("last_playable_seconds", 0.0)))
                        self._child_time_checkpoint_locked(force=True)
        if terminal_reason.casefold() in {
                "game_stopped", "replacement", "cancelled", "actor_replaced"}:
            return self._child_time_release_lease(
                client, session, terminal_reason, wall,
                next_actor_profile_id if terminal_reason.casefold() == "actor_replaced"
                else "")
        force = terminal_reason.casefold() in {"replacement", "user_terminated"}
        return self._child_time_finish(
            client, session, terminal_reason, wall, force=force)

    def child_time_session_hard_end(self, client: dict[str, Any],
                                    actor_profile_id: str,
                                    body: dict[str, Any]) \
            -> tuple[int, dict[str, Any]]:
        if body.get("force") is not True:
            raise ValueError("Explicit force confirmation is required.")
        session_id = body.get("session_id")
        if (not isinstance(session_id, str) or not
                CHILD_TIME_SESSION_ID_PATTERN.fullmatch(session_id.strip())):
            raise ValueError("A valid child session ID is required.")
        session_id = session_id.strip().lower()
        self.refresh_registry_if_changed()
        session = self.child_time_session
        client_id = self.broker_client_id(client)
        if not isinstance(session, dict) or any(
                str(session.get(key) or "") != value for key, value in (
                    ("session_id", session_id),
                    ("actor_profile_id", str(actor_profile_id)),
                    ("client_id", client_id))):
            return HTTPStatus.CONFLICT, {
                "ok": False, "accepted": False,
                "reason": "child_session_unmanaged"}
        actor = self.config.get("profiles", {}).get(str(actor_profile_id))
        actor = actor if isinstance(actor, dict) else {
            "id": str(actor_profile_id), "policy_revision": 0}
        _monotonic, wall = self._child_time_now(actor)
        return self._child_time_finish(
            client, session, "hard_reset", wall, force=True)

    def child_time_revoke(self, actor_profile_id: str, game_id: Any) \
            -> tuple[int, dict[str, Any]]:
        self.refresh_registry_if_changed()
        actor_id = str(actor_profile_id or "").strip()
        game = str(game_id or "").strip()
        if "/" in game:
            game = game.split("/", 1)[1]
        session = self.child_time_session
        if (not isinstance(session, dict) or
                str(session.get("actor_profile_id") or "") != actor_id or
                self._child_time_normalize_game_id(session.get("game_id")) !=
                self._child_time_normalize_game_id(game)):
            return HTTPStatus.OK, {"ok": True, "matched": False,
                                   "actor_profile_id": actor_id, "game_id": game}
        client_id = str(session.get("client_id") or "")
        client = next((item for item in self.config.get("clients", [])
                       if isinstance(item, dict) and str(item.get("id") or "") == client_id),
                      {"id": client_id})
        actor = self.config.get("profiles", {}).get(actor_id)
        actor = actor if isinstance(actor, dict) else {"id": actor_id}
        _monotonic, wall = self._child_time_now(actor)
        return self._child_time_finish(client, session, "game_access_revoked", wall)

    def revoke_child_time_session(self, actor_profile_id: str, game_id: Any) \
            -> tuple[int, dict[str, Any]]:
        return self.child_time_revoke(actor_profile_id, game_id)

    def child_time_housekeeping_tick(self) -> tuple[int, dict[str, Any]] | None:
        """Re-evaluate the active child session without refreshing client activity."""
        with self.child_time_housekeeping_lock:
            with self.lock:
                self._control_recover_provider_locked()
                session = self.child_time_session
            if not isinstance(session, dict):
                orphan = self._child_time_recover_persisted_terminal_orphan()
                if orphan is not None:
                    return orphan
                with self.lock:
                    self._control_reconcile_idle_locked()
                return None
            with self.lock:
                session_id = str(session.get("session_id") or "")
                actor_id = str(session.get("actor_profile_id") or "")
                client_id = str(session.get("client_id") or "")
            if not session_id or not actor_id or not client_id:
                return None
            client = next((item for item in self.config.get("clients", [])
                           if isinstance(item, dict) and
                           str(item.get("id") or "") == client_id), None)
            if client is None:
                client = {"id": client_id, "profile_grants": {}}
            try:
                return self.child_time_session_heartbeat(
                    client, actor_id, session_id,
                    update_client_heartbeat=False,
                    expected_session=session)
            except Exception as error:  # the housekeeping loop must survive one bad tick
                DIAGNOSTICS.record("child-time-housekeeping-failed", level="WARN",
                                   error=error)
                return None

    def _child_time_housekeeping_loop(self) -> None:
        while not self.child_time_housekeeping_stop.wait(
                CHILD_TIME_HOUSEKEEPING_INTERVAL_SECONDS):
            self.child_time_housekeeping_tick()

    def child_time_housekeeping_start(self) -> None:
        with self.child_time_housekeeping_lock:
            thread = self.child_time_housekeeping_thread
            if thread is not None and thread.is_alive():
                return
            self.child_time_housekeeping_stop.clear()
            thread = threading.Thread(
                target=self._child_time_housekeeping_loop,
                name="MoonWaker-child-time-housekeeping", daemon=True)
            self.child_time_housekeeping_thread = thread
            thread.start()

    def child_time_housekeeping_stop_now(self) -> None:
        self.child_time_housekeeping_stop.set()
        thread = self.child_time_housekeeping_thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=2.0)
        if thread is not None and not thread.is_alive():
            self.child_time_housekeeping_thread = None

    def child_time_shutdown(self) -> None:
        with self.lock:
            if self.child_time_session is not None:
                self._child_time_bump_revision_locked()
                self._child_time_checkpoint_locked(force=True)

    def child_playnite_library(self, client: dict[str, Any], actor_profile_id: str,
                               cursor: Any, limit: Any) -> tuple[int, Any]:
        actor_id, parent_id, parent, actor = self._child_policy_context(
            client, actor_profile_id)
        normalized_cursor = str(cursor or "").strip()
        page_size = int(limit or 50)
        if (not PLAYNITE_CURSOR_PATTERN.fullmatch(normalized_cursor) or
                page_size < 1 or page_size > 100):
            raise ValueError("Invalid Playnite library cursor or limit.")
        policy_revision = self._child_parent_policy_revision(parent)
        catalog_revision = None
        bridge_cursor = "0"
        if normalized_cursor:
            match = re.fullmatch(r"child:([0-9]+):([A-Za-z0-9._:-]{1,128}):([0-9]+)",
                                 normalized_cursor)
            if not match:
                raise ValueError("Invalid child library cursor.")
            if int(match.group(1)) != policy_revision:
                return HTTPStatus.CONFLICT, {
                    "ok": False, "error": "child_policy_revision_stale"}
            catalog_revision = match.group(2)
            bridge_cursor = match.group(3)
        allowed_ids = []
        allowed = actor.get("allowed_game_keys", [])
        if isinstance(allowed, list):
            prefix = parent_id + "/"
            allowed_ids = sorted({value[len(prefix):] for value in allowed
                                  if isinstance(value, str) and value.startswith(prefix) and
                                  GAME_RECORD_ID_PATTERN.fullmatch(value[len(prefix):])})
        body = {"allowed_game_ids": allowed_ids, "cursor": bridge_cursor,
                "limit": page_size}
        if catalog_revision is not None:
            body["catalog_revision"] = catalog_revision
        ok, result = self.proxy_json(
            "game_provider", "/library/list", body, timeout=8.0,
            profile_id=parent_id)
        if not ok:
            status = HTTPStatus.CONFLICT if isinstance(result, dict) and int(
                result.get("status", 0) or 0) == HTTPStatus.CONFLICT else HTTPStatus.BAD_GATEWAY
            return status, {"ok": False,
                            "error": "child_catalog_revision_stale" if status == HTTPStatus.CONFLICT
                            else "Unable to load the child game library."}
        if not isinstance(result, dict):
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False, "error": "Unable to load the child game library."}
        returned_catalog_revision = self._child_catalog_revision(result.get("revision"))
        next_bridge_cursor = str(result.get("next_cursor") or "").strip()
        if next_bridge_cursor and not PLAYNITE_CURSOR_PATTERN.fullmatch(next_bridge_cursor):
            raise PermissionError("Child game policy is unavailable.")
        next_cursor = ""
        if next_bridge_cursor:
            next_cursor = "child:%d:%s:%s" % (
                policy_revision, returned_catalog_revision, next_bridge_cursor)
            if len(next_cursor) > 128:
                raise PermissionError("Child game policy is unavailable.")
        return HTTPStatus.OK, {
            "ok": True,
            "library": {**result, "next_cursor": next_cursor},
            "policy_revision": policy_revision,
            "catalog_revision": returned_catalog_revision,
            "error": "",
        }

    def child_playnite_artwork(self, client: dict[str, Any], actor_profile_id: str,
                               game_id: Any, kind: Any) -> tuple[int, bytes, str]:
        _actor_id, parent_id, canonical, _parent, _metadata = self._authorize_child_game(
            client, actor_profile_id, game_id)
        normalized_kind = str(kind or "cover").strip().lower()
        if normalized_kind not in {"cover", "background", "hero", "icon"}:
            raise ValueError("Invalid artwork kind.")
        path = "/artwork?" + urllib.parse.urlencode({
            "game_id": canonical, "kind": normalized_kind,
        })
        return self.proxy_bytes("game_provider", path, timeout=8.0,
                                profile_id=parent_id)

    @staticmethod
    def _state_game_id(value: Any) -> str:
        if not isinstance(value, dict):
            return ""
        for key in ("game_id", "gameId", "id"):
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate.strip():
                return candidate.strip()
        for key in ("current", "game", "snapshot", "payload"):
            candidate = GatewayState._state_game_id(value.get(key))
            if candidate:
                return candidate
        return ""

    @staticmethod
    def _event_game_id(event: dict[str, Any]) -> tuple[str, bool]:
        """Return an event game identity and whether the event must have one."""
        kind = str(event.get("event") or event.get("type") or "").strip()
        payload = event.get("payload")
        candidates = [event]
        if isinstance(payload, dict):
            candidates.append(payload)
        for candidate in candidates:
            for key in ("game_id", "gameId"):
                value = candidate.get(key)
                if isinstance(value, str) and value.strip():
                    return value.strip(), True
            if kind in CHILD_GAME_EVENT_KINDS:
                value = candidate.get("id")
                if isinstance(value, str) and value.strip():
                    return value.strip(), True
        return "", kind in CHILD_GAME_EVENT_KINDS

    def child_playnite_events(self, client: dict[str, Any], actor_profile_id: str,
                              after: Any, transition_id: Any = "") -> tuple[int, Any]:
        _actor_id, parent_id, _parent, _actor = self._child_policy_context(
            client, actor_profile_id)
        sequence = int(after or 0)
        if sequence < 0 or sequence > 9_223_372_036_854_775_807:
            raise ValueError("Invalid Playnite event sequence.")
        correlation = str(transition_id or "").strip()
        if len(correlation) > 128 or not re.fullmatch(r"[A-Za-z0-9._:-]*", correlation):
            raise ValueError("Invalid transition ID.")
        ok, result = self.proxy("game_provider", "/events?" + urllib.parse.urlencode({
            "after": sequence,
        }), timeout=22.0, profile_id=parent_id)
        if not ok or not isinstance(result, dict):
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False, "transition_id": correlation, "events": {},
                "error": "Unable to read child game lifecycle events.",
            }
        events = result.get("events")
        if isinstance(events, list):
            visible = []
            for event in events:
                if not isinstance(event, dict):
                    continue
                event_game_id, game_required = self._event_game_id(event)
                event_kind = str(event.get("event") or event.get("type") or "").strip()
                if event_game_id:
                    try:
                        self._authorize_child_game(client, actor_profile_id, event_game_id)
                    except PermissionError as error:
                        if str(error) != "game_not_shared":
                            raise
                        continue
                elif game_required:
                    continue
                else:
                    if event_kind not in {"library-updated", "bridge-connected",
                                          "bridge-disconnected"}:
                        continue
                    if event_kind == "library-updated":
                        revision = event.get("payload", {}).get("revision") \
                            if isinstance(event.get("payload"), dict) else None
                        event = {**event, "payload": {
                            "revision": str(revision or "0")}}
                    else:
                        event = {**event, "payload": {}}
                visible.append(event)
            result = {**result, "events": visible}
        return HTTPStatus.OK, {
            "ok": True, "transition_id": correlation, "events": result,
            "error": "",
        }

    def child_playnite_action(self, client: dict[str, Any], actor_profile_id: str,
                              action: str, body: dict[str, Any]) -> tuple[int, Any]:
        def controlled(path: str, payload: dict[str, Any], timeout: float,
                       parent_id: str) -> tuple[bool, Any]:
            request_id = str(getattr(self.request_context, "request_id", ""))
            if not REQUEST_ID_PATTERN.fullmatch(request_id):
                return self.proxy_json("game_provider", path, payload,
                                       timeout=timeout, profile_id=parent_id)
            return self.provider_control_operation.execute(
                client, request_id, path, payload, timeout, parent_id)

        if action == "library/refresh":
            _actor_id, parent_id, _parent, _actor = self._child_policy_context(
                client, actor_profile_id)
            ok, result = self.proxy_json(
                "game_provider", "/library/refresh", {}, timeout=5.0,
                profile_id=parent_id)
            return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
                "ok": ok,
                "action": action,
                "result": result if isinstance(result, dict) else {},
                "error": "" if ok else self.upstream_error(
                    result, "Game provider library refresh failed."),
            }
        if action in {"game/stop-verified", "game/focus"}:
            actor_id, parent_id, _parent, _actor = self._child_policy_context(client, actor_profile_id)
            session = self.child_time_session
            if (not isinstance(session, dict)
                    or str(session.get("actor_profile_id") or "") != actor_id
                    or str(session.get("client_id") or "") != self.broker_client_id(client)):
                raise PermissionError("child_session_binding_mismatch")
            game_id = str(session.get("game_id") or "")
            if action == "game/stop-verified" and \
                    self._child_request_game(parent_id, body.get("game_id")) != \
                    self._child_time_normalize_game_id(game_id):
                raise PermissionError("child_session_binding_mismatch")
            payload = {"game_id": game_id} \
                if action == "game/stop-verified" else {}
            if action == "game/stop-verified":
                token = body.get("expected_process_token")
                if not isinstance(token, str) or not re.fullmatch(r"[0-9a-f]{64}", token):
                    raise ValueError("A valid expected process token is required.")
                payload["expected_process_token"] = token
            if action == "game/stop-verified":
                ok, result = controlled("/" + action, payload, 22.0, parent_id)
            else:
                ok, result = self.proxy_json("game_provider", "/" + action, payload,
                                             timeout=22.0, profile_id=parent_id)
            accepted = ok and isinstance(result, dict) and bool(result.get("accepted", True))
            return (HTTPStatus.OK if accepted else HTTPStatus.BAD_GATEWAY), {
                "ok": accepted, "accepted": accepted, "action": action, "result": result,
                "error": "" if accepted else self.upstream_error(result, "Game action failed."),
            }
        if action in {"game/install", "game/uninstall", "game/install/focus",
                      "game/install/verify"}:
            _actor_id, parent_id, canonical, _parent, _metadata = \
                self._authorize_child_game(client, actor_profile_id, body.get("game_id"))
            paths = {
                "game/install": ("/game/install", 15.0),
                "game/uninstall": ("/game/uninstall", 15.0),
                "game/install/focus": ("/installation/focus", 6.0),
                "game/install/verify": ("/installation/verify", 8.0),
            }
            path, timeout = paths[action]
            ok, result = self.proxy_json(
                "game_provider", path, {"game_id": canonical},
                timeout=timeout, profile_id=parent_id)
            accepted = ok and (not isinstance(result, dict)
                               or bool(result.get("accepted", True)))
            return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
                "ok": accepted, "accepted": accepted, "action": action,
                "result": result if isinstance(result, dict) else {},
                "error": "" if accepted else self.upstream_error(
                    result, "Game action failed."),
            }
        if action != "game/start":
            raise PermissionError("Child game action is not available.")
        _actor_id, parent_id, canonical, _parent, _metadata = self._authorize_child_game(
            client, actor_profile_id, body.get("game_id"))
        session = self.child_time_session
        if (not isinstance(session, dict)
                or session.get("actor_profile_id") != _actor_id
                or session.get("client_id") != self.broker_client_id(client)
                or self._child_time_normalize_game_id(session.get("game_id")) !=
                self._child_time_normalize_game_id(canonical)):
            raise PermissionError("child_session_binding_mismatch")
        ok, result = controlled(
            "/game/start", {"game_id": canonical}, 22.0, parent_id)
        accepted = not isinstance(result, dict) or bool(result.get("accepted", True))
        if ok and not accepted:
            return HTTPStatus.OK, {
                "ok": False,
                "action": action,
                "result": result,
                "error": str(result.get("reason") or "Child game start was rejected."),
            }
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "action": action,
            "result": result if isinstance(result, dict) else {},
            "error": "" if ok else "Child game start failed.",
        }

    def _observation_control_locked(self) -> dict[str, Any]:
        active = self.control_state.get("active")
        stream = self.control_state.get("stream")
        record = active if isinstance(active, dict) else (
            stream if isinstance(stream, dict) else {})
        return {
            "operation_id": str(record.get("operation_id") or ""),
            "stream_session_id": str(record.get("stream_session_id") or ""),
            "control_generation": int(record.get("generation") or
                                      self.control_state.get("generation") or 0),
            "actor_lease_epoch": int(record.get("actor_lease_epoch") or
                                     self.control_state.get("actor_lease_epoch") or 0),
        }

    def playnite_observation(self, request_ordinal: Any,
                             transition_id: Any) -> tuple[int, Any]:
        if isinstance(request_ordinal, bool):
            raise ValueError("A positive request ordinal is required.")
        ordinal = int(request_ordinal or 0)
        if ordinal <= 0 or ordinal > 9_223_372_036_854_775_807:
            raise ValueError("A positive request ordinal is required.")
        transition = str(transition_id or "").strip()
        if len(transition) > 128 or not re.fullmatch(
                r"[A-Za-z0-9._:-]*", transition):
            raise ValueError("Invalid transition ID.")
        actor_id, execution_id = self.actor_profile_id, self.execution_profile_id
        with self.lock:
            before = self._observation_control_locked()
        ok, provider = self.proxy("game_provider", "/observation", timeout=3.0,
                                  profile_id=execution_id)
        with self.lock:
            after = self._observation_control_locked()
        correlation = {**after, "caller_transition_id": transition,
                       "request_ordinal": ordinal}
        scope = {"actor_profile_id": actor_id,
                 "execution_profile_id": execution_id}
        if not ok or not isinstance(provider, dict):
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False, "scope": scope, "correlation": correlation,
                "reason": "provider_observation_unavailable"}
        if before != after:
            return HTTPStatus.OK, {
                **provider, "ok": False, "scope": scope,
                "correlation": correlation,
                "provider": {"state": "unknown",
                             "connector_evidence": "UNAVAILABLE",
                             "reason": "control_correlation_changed"},
                "game": {"evidence": "UNAVAILABLE", "id": "",
                         "state": "unknown"},
                "process": {"evidence": "UNAVAILABLE", "process_id": 0,
                            "process_token": ""},
                "window": {"evidence": "UNAVAILABLE", "ready": False,
                           "target_kind": "playnite", "stable_samples": 0,
                           "reason": "control_correlation_changed"},
                "reason": "control_correlation_changed"}
        return HTTPStatus.OK, {**provider, "ok": True, "scope": scope,
                              "correlation": correlation}

    def playnite_library(self, cursor: Any, limit: Any,
                         client: dict[str, Any] | None = None,
                         actor_profile_id: str | None = None) -> tuple[int, Any]:
        if client is not None and self.is_child_profile(actor_profile_id or self.actor_profile_id):
            return self.child_playnite_library(
                client, str(actor_profile_id or self.actor_profile_id), cursor, limit)
        normalized_cursor = str(cursor or "").strip()
        if not PLAYNITE_CURSOR_PATTERN.fullmatch(normalized_cursor):
            raise ValueError("Invalid Playnite library cursor.")
        page_size = int(limit or 50)
        if page_size < 1 or page_size > 100:
            raise ValueError("Playnite library limit must be between 1 and 100.")
        path = "/library/list?" + urllib.parse.urlencode({
            "cursor": normalized_cursor,
            "limit": page_size,
        })
        ok, result = self.proxy("game_provider", path, timeout=8.0)
        if ok and isinstance(result, dict):
            with self.lock:
                operations = {key.split(":", 1)[1]: value.get("state", "")
                              for key, value in self.vibepollo_app_operations.items()
                              if key.startswith(self.profile_id + ":")}
            for game in result.get("games", []):
                if isinstance(game, dict):
                    state = operations.get(str(game.get("id", "")).lower(), "")
                    if state:
                        game["vibepollo_state"] = state
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "library": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Unable to load the game library."),
        }

    def playnite_artwork(self, game_id: Any, kind: Any,
                         client: dict[str, Any] | None = None,
                         actor_profile_id: str | None = None) -> tuple[int, bytes, str]:
        if client is not None and self.is_child_profile(actor_profile_id or self.actor_profile_id):
            return self.child_playnite_artwork(
                client, str(actor_profile_id or self.actor_profile_id), game_id, kind)
        normalized_id = str(game_id or "").strip()
        normalized_kind = str(kind or "cover").strip().lower()
        if not (PLAYNITE_GAME_ID_PATTERN.fullmatch(normalized_id)
                or GAME_RECORD_ID_PATTERN.fullmatch(normalized_id)):
            raise ValueError("Invalid game record ID.")
        if normalized_kind not in {"cover", "background", "hero", "icon"}:
            raise ValueError("Invalid artwork kind.")
        path = "/artwork?" + urllib.parse.urlencode({
            "game_id": normalized_id,
            "kind": normalized_kind,
        })
        return self.proxy_bytes("game_provider", path, timeout=8.0)

    def playnite_events(self, after: Any, transition_id: Any = "",
                        client: dict[str, Any] | None = None,
                        actor_profile_id: str | None = None) -> tuple[int, Any]:
        if client is not None and self.is_child_profile(actor_profile_id or self.actor_profile_id):
            return self.child_playnite_events(
                client, str(actor_profile_id or self.actor_profile_id), after, transition_id)
        sequence = int(after or 0)
        if sequence < 0 or sequence > 9_223_372_036_854_775_807:
            raise ValueError("Invalid Playnite event sequence.")
        correlation = str(transition_id or "").strip()
        if len(correlation) > 128 or not re.fullmatch(r"[A-Za-z0-9._:-]*", correlation):
            raise ValueError("Invalid transition ID.")
        ok, result = self.proxy("game_provider", "/events?" + urllib.parse.urlencode({
            "after": sequence,
        }), timeout=22.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "transition_id": correlation,
            "events": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Unable to read game lifecycle events."),
        }

    def playnite_action(self, action: str, body: dict[str, Any],
                        client: dict[str, Any] | None = None,
                        actor_profile_id: str | None = None,
                        request_id: str = "") -> tuple[int, Any]:
        if client is not None and self.is_child_profile(actor_profile_id or self.actor_profile_id):
            return self.child_playnite_action(
                client, str(actor_profile_id or self.actor_profile_id), action, body)
        if action == "game/start":
            payload = {"game_id": self._playnite_game_id(body.get("game_id"))}
            path = "/game/start"
            timeout = 22.0
        elif action == "game/install":
            payload = {"game_id": self._playnite_game_id(body.get("game_id"))}
            path = "/game/install"
            timeout = 15.0
        elif action == "game/uninstall":
            payload = {"game_id": self._playnite_game_id(body.get("game_id"))}
            path = "/game/uninstall"
            timeout = 15.0
        elif action == "game/install/focus":
            payload = {"game_id": self._playnite_game_id(body.get("game_id"))}
            path = "/installation/focus"
            timeout = 6.0
        elif action == "game/install/verify":
            payload = {"game_id": self._playnite_game_id(body.get("game_id"))}
            path = "/installation/verify"
            timeout = 8.0
        elif action == "game/stop-verified":
            token = body.get("expected_process_token")
            if not isinstance(token, str) or not re.fullmatch(r"[0-9a-f]{64}", token):
                raise ValueError("A valid expected process token is required.")
            payload = {"force": False,
                       "game_id": self._playnite_game_id(body.get("game_id")),
                       "expected_process_token": token}
            path = "/game/stop-verified"
            timeout = 25.0
        elif action == "game/focus":
            payload = {}
            path = "/game/focus"
            timeout = 6.0
        elif action == "show-fullscreen":
            payload = {}
            path = "/playnite/show-fullscreen"
            timeout = 10.0
        elif action == "library/refresh":
            payload = {}
            path = "/library/refresh"
            timeout = 5.0
        else:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown Playnite action."}
        if action in {"game/start", "game/stop-verified"} \
                and client is not None:
            if not REQUEST_ID_PATTERN.fullmatch(request_id):
                return HTTPStatus.CONFLICT, {"ok": False,
                                             "error": "control_operation_required"}
            ok, result = self.provider_control_operation.execute(
                client, request_id, path, payload, timeout, self.execution_profile_id,
                body.get("control_operation"))
            if not ok and isinstance(result, dict) and str(result.get("error") or "").startswith("control_"):
                return HTTPStatus.CONFLICT, {"ok": False, **result}
        else:
            ok, result = self.proxy_json("game_provider", path, payload, timeout=timeout)
        accepted = not isinstance(result, dict) or bool(result.get("accepted", True))
        if ok and action in {"game/start", "game/install", "game/uninstall", "game/stop-verified"} \
                and not accepted:
            return HTTPStatus.OK, {
                "ok": False,
                "action": action,
                "result": result,
                "error": str(result.get("reason") or "Game provider action was rejected."),
            }
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "action": action,
            "result": result if isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Game provider action failed."),
        }

    def idempotent(self, key: str, operation) -> tuple[int, Any]:
        now = time.monotonic()
        with self.control_condition:
            expired = [item for item, value in self.idempotent_results.items() if now - value[0] > IDEMPOTENCY_LIFETIME_SECONDS]
            for item in expired:
                self.idempotent_results.pop(item, None)
            while key in self.idempotent_inflight:
                self.control_condition.wait()
                cached = self.idempotent_results.get(key)
                if cached:
                    return cached[1], cached[2]
            cached = self.idempotent_results.get(key)
            if cached:
                return cached[1], cached[2]
            self.idempotent_inflight.add(key)
        try:
            status, result = operation()
        except BaseException:
            with self.control_condition:
                self.idempotent_inflight.discard(key)
                self.control_condition.notify_all()
            raise
        with self.control_condition:
            self.idempotent_results[key] = (time.monotonic(), status, result)
            self.idempotent_inflight.discard(key)
            self.control_condition.notify_all()
        return status, result


class GatewayHandler(BaseHTTPRequestHandler):
    server_version = "WakePlayGateway/0.1"

    @property
    def state(self) -> GatewayState:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        pass

    def send_json(self, status: int, value: Any) -> None:
        self._response_status = int(status)
        body = compact_json(value)
        self._response_content_length = len(body)
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)
        self._response_body_bytes_written = len(body)

    def send_permission_error(self, error: PermissionError) -> None:
        self._diagnostic_error = error
        if error.errno is None:
            self.send_json(HTTPStatus.FORBIDDEN, {"error": str(error)})
            return
        # An OS access failure is a host fault, not an authorization denial, and
        # its message carries host paths that must not reach LAN clients.
        self._diagnostic_unexpected = True
        self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR,
                       {"error": "Host storage is unavailable."})

    def send_session_json(self, status: int, value: dict[str, Any]) -> None:
        if int(status) >= 400 and "error" not in value:
            value = {**value, "error": str(value.get("reason") or "session_request_failed")}
        self.send_json(status, value)

    def send_binary(self, status: int, body: bytes, content_type: str) -> None:
        self._response_status = int(status)
        self.send_response(int(status))
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "private, max-age=3600")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > MAX_BODY_BYTES:
            raise ValueError("Request body is too large.")
        raw = self.rfile.read(length)
        value = json.loads(raw.decode("utf-8")) if raw else {}
        if not isinstance(value, dict):
            raise ValueError("JSON object expected.")
        return value

    def read_microphone_chunks(self, output: Any) -> None:
        pending = bytearray()
        self.connection.settimeout(MICROPHONE_IDLE_SECONDS)
        while True:
            line = self.rfile.readline(18)
            if not line.endswith(b"\r\n") or len(line) > 17:
                raise ValueError("Invalid microphone chunk header.")
            token = line[:-2].split(b";", 1)[0]
            if not token or not re.fullmatch(b"[0-9A-Fa-f]{1,8}", token):
                raise ValueError("Invalid microphone chunk size.")
            size = int(token, 16)
            if size > MICROPHONE_MAX_CHUNK_BYTES:
                raise ValueError("Microphone chunk is too large.")
            if size == 0:
                if self.rfile.read(2) != b"\r\n":
                    raise ValueError("Invalid microphone stream terminator.")
                break
            data = self.rfile.read(size)
            if len(data) != size or self.rfile.read(2) != b"\r\n":
                raise ValueError("Truncated microphone chunk.")
            pending.extend(data)
            while len(pending) >= MICROPHONE_FRAME_BYTES:
                if output.closed:
                    raise BrokenPipeError("Microphone renderer stopped.")
                output.write(pending[:MICROPHONE_FRAME_BYTES])
                output.flush()
                del pending[:MICROPHONE_FRAME_BYTES]
        if pending:
            raise ValueError("Partial microphone frame.")

    def microphone_stream(self, profile_id: str) -> None:
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Chunked transfer encoding is required."})
            return
        if self.headers.get("Content-Type", "").lower() != (
                "application/vnd.moonwaker.microphone-pcm;"
                "format=s16le;rate=48000;channels=1"):
            self.send_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "Unsupported microphone format."})
            return
        session_id = self.headers.get("X-Microphone-Session-Id", "").strip()
        request_id = self.headers.get("X-Request-Id", "").strip()
        if not REQUEST_ID_PATTERN.fullmatch(session_id) or not REQUEST_ID_PATTERN.fullmatch(request_id):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Valid request and microphone session IDs are required."})
            return
        worker = self.state.microphone_worker()
        if not worker.is_file():
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Microphone renderer is unavailable."})
            return
        with self.state.lock:
            if profile_id in self.state.microphone_streams:
                self.send_json(HTTPStatus.CONFLICT, {"error": "A microphone stream is already active."})
                return
            self.state.microphone_streams[profile_id] = session_id
        process = None
        try:
            process = subprocess.Popen(
                [str(worker), "--stream"], stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if process.stdin is None:
                raise OSError("Microphone renderer pipe is unavailable.")
            self.read_microphone_chunks(process.stdin)
            process.stdin.close()
            if process.wait(timeout=3.0) != 0:
                raise OSError("Microphone renderer stopped.")
            self.send_json(HTTPStatus.OK, {"ok": True})
        finally:
            try:
                if process is not None:
                    try:
                        if process.stdin is not None and not process.stdin.closed:
                            process.stdin.close()
                    except OSError:
                        pass
                    if process.poll() is None:
                        try:
                            process.kill()
                        except OSError:
                            pass
                        try:
                            process.wait(timeout=2.0)
                        except (OSError, subprocess.TimeoutExpired):
                            pass
            finally:
                with self.state.lock:
                    if self.state.microphone_streams.get(profile_id) == session_id:
                        self.state.microphone_streams.pop(profile_id, None)

    def discord_audio_stream(self, profile_id: str) -> None:
        session_id = self.headers.get("X-Request-Id", "").strip()
        if not REQUEST_ID_PATTERN.fullmatch(session_id):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "A valid request ID is required."})
            return
        worker = self.state.discord_audio_worker()
        if not worker.is_file():
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE,
                           {"error": "Discord audio is unavailable.", "reason": "worker_missing"})
            return
        reason, process_id = self.state.discord_audio_target()
        if reason != "ready":
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE,
                           {"error": "Discord audio is unavailable.", "reason": reason})
            return
        with self.state.lock:
            if profile_id in self.state.discord_audio_streams:
                self.send_json(HTTPStatus.CONFLICT,
                               {"error": "A Discord audio stream is already active."})
                return
            self.state.discord_audio_streams[profile_id] = session_id
        process = None
        try:
            process = subprocess.Popen(
                [str(worker), "--stream", str(process_id)], stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if process.stdout is None:
                raise OSError("Discord audio worker pipe is unavailable.")
            self._response_status = int(HTTPStatus.OK)
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", DISCORD_AUDIO_CONTENT_TYPE)
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            pending = bytearray()
            while True:
                data = process.stdout.read(DISCORD_AUDIO_FRAME_BYTES - len(pending))
                if not data:
                    break
                pending.extend(data)
                if len(pending) == DISCORD_AUDIO_FRAME_BYTES:
                    self.wfile.write(f"{len(pending):X}\r\n".encode("ascii"))
                    self.wfile.write(pending)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()
                    pending.clear()
            if not pending:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionError, OSError):
            self.close_connection = True
        finally:
            try:
                if process is not None:
                    try:
                        if process.stdout is not None:
                            process.stdout.close()
                    except OSError:
                        pass
                    if process.poll() is None:
                        try:
                            process.kill()
                        except OSError:
                            pass
                        try:
                            process.wait(timeout=2.0)
                        except (OSError, subprocess.TimeoutExpired):
                            pass
            finally:
                with self.state.lock:
                    if self.state.discord_audio_streams.get(profile_id) == session_id:
                        self.state.discord_audio_streams.pop(profile_id, None)

    def network_download(self, size_value: str | None,
                         profile_id: str, client_id: str) -> None:
        raw_size = str(NETWORK_DOWNLOAD_DEFAULT_BYTES) if size_value is None else size_value
        if not re.fullmatch(r"[0-9]{1,9}", raw_size):
            raise ValueError("Invalid network download size.")
        size = int(raw_size)
        if size <= 0 or size > NETWORK_DOWNLOAD_MAX_BYTES:
            raise ValueError("Network download size must be between 1 byte and 512 MiB.")

        slot = (profile_id, client_id)
        with self.state.lock:
            if slot in self.state.network_downloads:
                self.send_json(HTTPStatus.CONFLICT, {
                    "error": "A network download test is already active for this client profile."
                })
                return
            self.state.network_downloads.add(slot)
        try:
            self._response_status = int(HTTPStatus.OK)
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Encoding", "identity")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            chunk = bytes(min(size, NETWORK_DOWNLOAD_CHUNK_BYTES))
            remaining = size
            while remaining:
                count = min(remaining, len(chunk))
                self.wfile.write(chunk if count == len(chunk) else chunk[:count])
                remaining -= count
            self.wfile.flush()
        except (BrokenPipeError, ConnectionError, OSError):
            self.close_connection = True
        finally:
            with self.state.lock:
                self.state.network_downloads.discard(slot)

    def authenticated(self) -> bool:
        return self.authenticated_client() is not None

    def authenticated_client(self) -> dict[str, Any] | None:
        header = self.headers.get("Authorization", "")
        token = header[7:] if header.startswith("Bearer ") else ""
        return self.state.client_for_token(token) if token else None

    def require_auth(self) -> dict[str, Any] | None:
        client = self.authenticated_client()
        if client is not None:
            return client
        self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Authentication required."})
        return None

    def authorize_profile(self, client: dict[str, Any],
                          permission: str = "use_profile",
                          require_pin: bool = True) -> str:
        profile_id = self.state.authorize_profile(
            client, self.headers.get("X-WakePlay-Profile", "default"),
            permission, record_use=True)
        if require_pin:
            self.state.require_profile_unlock(
                client, profile_id, self.profile_session_id())
        return profile_id

    def profile_session_id(self) -> str | None:
        return normalize_profile_session_id(
            self.headers.get(PROFILE_SESSION_HEADER))

    def _request_profile_id(self) -> str:
        profile_id = str(self.headers.get("X-WakePlay-Profile", "default") or
                         "default").strip()
        if not PROFILE_ID_PATTERN.fullmatch(profile_id):
            raise ValueError("Invalid integration profile ID.")
        return profile_id

    def _declares_child_capability(self) -> bool:
        value = str(self.headers.get("X-MoonWaker-Capabilities", "") or "")
        return CHILD_PROFILES_CAPABILITY in {
            item.strip() for item in value.split(",") if item.strip()}

    def _require_child_capability(self) -> bool:
        if not bool(getattr(self.state, "child_profiles_api_enabled", False)):
            self.send_json(HTTPStatus.FORBIDDEN, {
                "ok": False, "error": "child_profiles_not_enforced",
                "reason": "child_profiles_not_enforced"})
            return False
        if not self._declares_child_capability():
            self.send_json(HTTPStatus.FORBIDDEN, {
                "ok": False, "error": "child_profiles_capability_required",
                "reason": "child_profiles_capability_required"})
            return False
        return True

    def _authorize_public_child(self, client: dict[str, Any],
                                permission: str = "use_profile") -> str:
        actor_id = self._request_profile_id()
        if not self.state.is_child_profile(actor_id):
            raise PermissionError("child_profile_required")
        self.state.authorize_child_profile(
            client, actor_id, permission, record_use=True)
        return actor_id

    def _authorize_public_child_session(self, client: dict[str, Any],
                                        session_id: str) -> str:
        """Bind a status/end request to the persisted actor and client.

        A terminal request must remain usable after the child registry record
        is disabled or deleted, so this path intentionally does not call the
        live child grant authorizer.
        """
        actor_id = self._request_profile_id()
        if not PROFILE_ID_PATTERN.fullmatch(actor_id):
            raise ValueError("Invalid child profile ID.")
        normalized_session = GatewayState._child_time_id(
            session_id, "child session ID")
        client_id = self.state.broker_client_id(client)
        active = self.state.child_time_session
        record = active if isinstance(active, dict) and \
            str(active.get("session_id") or "").lower() == normalized_session and \
            str(active.get("actor_profile_id") or "") == actor_id and \
            str(active.get("client_id") or "") == client_id else None
        if record is None:
            completed = self.state.child_time_completed.get(normalized_session, {})
            if (not isinstance(completed, dict) or
                    str(completed.get("actor_profile_id") or "") != actor_id or
                    str(completed.get("client_id") or "") != client_id):
                raise PermissionError("child_session_binding_mismatch")
            record = completed
        parent_id = str(record.get("execution_profile_id") or "")
        if not PROFILE_ID_PATTERN.fullmatch(parent_id):
            raise PermissionError("child_session_binding_mismatch")
        self.state.request_context.profile_id = actor_id
        self.state.request_context.actor_profile_id = actor_id
        self.state.request_context.execution_profile_id = parent_id
        return actor_id

    def _require_child_start_session(self, client: dict[str, Any], actor_id: str) -> None:
        # The game binding is checked by child_playnite_action on the provider-resolved
        # canonical id; the raw request id (e.g. a bare Playnite GUID) cannot be compared here.
        session = self.state.child_time_session
        if not isinstance(session, dict):
            raise PermissionError("child_session_required")
        if (str(session.get("actor_profile_id") or "") != actor_id or
                str(session.get("client_id") or "") !=
                self.state.broker_client_id(client) or
                str(session.get("phase") or "") not in {"launch_pending", "running"}):
            raise PermissionError("child_session_required")

    def _require_child_profile_session(self) -> str:
        profile_session = self.profile_session_id()
        if profile_session is None:
            raise ValueError(f"A valid {PROFILE_SESSION_HEADER} header is required.")
        return profile_session

    def _read_child_session_body(self, action: str) -> dict[str, Any]:
        body = self.read_json()
        fields = {
            "eligibility": {"game_id"},
            "start": {"game_id", "session_id", "request_id"},
            "status": {"session_id"},
            "resume": {"session_id"},
            "heartbeat": {"session_id"},
            "end": {"session_id", "reason", "next_actor_profile_id"},
        }.get(action)
        if fields is None:
            raise ValueError("Unknown child session action.")
        if any(key not in fields for key in body):
            raise ValueError("Invalid child session request body.")
        required = fields if action != "end" else {"session_id"}
        if any(key not in body for key in required):
            raise ValueError("Invalid child session request body.")
        if action in {"eligibility", "start"}:
            game_id = body.get("game_id")
            if (not isinstance(game_id, str) or not game_id.strip() or
                    len(game_id.strip()) > 160):
                raise ValueError("A valid game ID is required.")
        if action in {"start", "status", "resume", "heartbeat", "end"}:
            session_id = body.get("session_id")
            if not isinstance(session_id, str) or not \
                    CHILD_TIME_SESSION_ID_PATTERN.fullmatch(session_id.strip()):
                raise ValueError("A valid child session ID is required.")
            body["session_id"] = session_id.strip().lower()
        if action == "start":
            request_id = body.get("request_id")
            if (not isinstance(request_id, str) or
                    not REQUEST_ID_PATTERN.fullmatch(request_id.strip())):
                raise ValueError("A valid request ID is required.")
            body["request_id"] = request_id.strip()
        if action == "end":
            reason = body.get("reason", "client_closed")
            if (not isinstance(reason, str) or
                    not re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", reason.strip())):
                raise ValueError("A valid child session end reason is required.")
            body["reason"] = reason.strip() or "client_closed"
            next_actor = body.get("next_actor_profile_id", "")
            if (not isinstance(next_actor, str) or next_actor and not
                    PROFILE_ID_PATTERN.fullmatch(next_actor.strip())):
                raise ValueError("A valid next actor profile ID is required.")
            if next_actor and body["reason"].casefold() != "actor_replaced":
                raise ValueError("A next actor requires actor_replaced.")
            body["next_actor_profile_id"] = next_actor.strip()
        return body

    def _child_session_request(self, action: str,
                               client: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        self._require_child_profile_session()
        body = self._read_child_session_body(action)
        if action in {"eligibility", "start"}:
            actor_id = self._authorize_public_child(client)
        else:
            actor_id = self._authorize_public_child_session(
                client, body["session_id"])
        if action == "eligibility":
            return self.state.child_time_eligibility(
                client, actor_id, body["game_id"])
        if action == "start":
            return self.state.child_time_session_start(
                client, actor_id, body["game_id"], body["session_id"],
                body["request_id"])
        if action == "status":
            return self.state.child_time_session_status(
                client, actor_id, body["session_id"])
        if action == "resume":
            return self.state.child_time_session_resume(
                client, actor_id, body["session_id"])
        if action == "heartbeat":
            return self.state.child_time_session_heartbeat(
                client, actor_id, body["session_id"])
        return self.state.child_time_session_end(
            client, actor_id, body["session_id"], body["reason"],
            body.get("next_actor_profile_id", ""))

    def _begin_diagnostics(self, method: str) -> None:
        self._diagnostic_method = method
        self._diagnostic_route = diagnostic_route(self.path)
        self._diagnostic_started = time.monotonic()
        self._diagnostic_error: BaseException | None = None
        self._diagnostic_unexpected = False
        self._response_status = 0
        self._response_content_length = 0
        self._response_body_bytes_written = 0
        for key in ("request_id", "profile_id", "actor_profile_id", "execution_profile_id"):
            if hasattr(self.state.request_context, key):
                delattr(self.state.request_context, key)
        request_id = (self.headers.get("X-Request-Id", "") or "").strip()
        if REQUEST_ID_PATTERN.fullmatch(request_id):
            self.state.request_context.request_id = request_id
        else:
            request_id = ""
        if method == "POST":
            DIAGNOSTICS.record("request.started", method=method,
                               route=self._diagnostic_route, request_id=request_id)

    def _finish_diagnostics(self) -> None:
        try:
            status = self._response_status
            failed = self._diagnostic_error is not None or status <= 0 or status >= 400
            if self._diagnostic_method == "POST" or failed:
                fields: dict[str, Any] = {
                    "method": self._diagnostic_method,
                    "route": self._diagnostic_route,
                    "status": "failed" if failed else "completed",
                    "http_status": status if status > 0 else None,
                    "response_content_length": self._response_content_length,
                    "response_body_bytes_written": self._response_body_bytes_written,
                    "duration_ms": max(0, int(
                        (time.monotonic() - self._diagnostic_started) * 1000)),
                    "request_id": str(getattr(
                        self.state.request_context, "request_id", "")),
                    "profile_id": str(getattr(
                        self.state.request_context, "profile_id", "")),
                    "actor_profile_id": str(getattr(
                        self.state.request_context, "actor_profile_id", "")),
                    "execution_profile_id": str(getattr(
                        self.state.request_context, "execution_profile_id", "")),
                }
                DIAGNOSTICS.record(
                    "request.failed" if failed else "request.completed",
                    level="ERROR" if self._diagnostic_unexpected else
                    ("WARN" if failed else "INFO"),
                    error=self._diagnostic_error,
                    include_frames=self._diagnostic_unexpected,
                    **fields)
        finally:
            for key in ("request_id", "profile_id", "actor_profile_id", "execution_profile_id"):
                if hasattr(self.state.request_context, key):
                    delattr(self.state.request_context, key)

    def do_GET(self) -> None:  # noqa: N802
        self._begin_diagnostics("GET")
        try:
            self._do_GET()
        except (ValueError, json.JSONDecodeError) as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except PermissionError as error:
            self.send_permission_error(error)
        except Exception as error:
            self._diagnostic_error = error
            self._diagnostic_unexpected = True
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(error)})
        finally:
            self._finish_diagnostics()

    def _do_GET(self) -> None:
        target = urllib.parse.urlsplit(self.path)
        path = target.path
        query = urllib.parse.parse_qs(target.query, keep_blank_values=True)
        if path == f"{API_PREFIX}/hello":
            self.send_json(HTTPStatus.OK, {
                "name": "Wake & Play Host Gateway", "api_version": 1,
                "pairing": self.state.pairing_active(), **self.state.runtime_info(),
            })
            return
        authenticated_client = self.require_auth()
        if authenticated_client is None:
            return
        if path == f"{API_PREFIX}/profiles":
            header_profile = self._request_profile_id()
            if (self.state.is_child_profile(header_profile) and
                    not self._require_child_capability()):
                return
            include_children = (bool(
                getattr(self.state, "child_profiles_api_enabled", False)) and
                self._declares_child_capability())
            self.send_json(
                HTTPStatus.OK, self.state.profiles_summary(
                    authenticated_client, include_children=include_children))
            return
        if path == f"{API_PREFIX}/system/session/status":
            header_profile = self._request_profile_id()
            if self.state.is_child_profile(header_profile):
                if not self._require_child_capability():
                    return
                profile_id = self._authorize_public_child(
                    authenticated_client, "use_profile")
            else:
                profile_id = self.authorize_profile(authenticated_client, "use_profile")
            attempt_id = str(query.get("attempt_id", [""])[0]).strip()
            request_id = str(query.get("request_id", [""])[0]).strip()
            if attempt_id or request_id:
                if self.state.is_child_profile(header_profile):
                    self.state.authorize_child_profile(
                        authenticated_client, profile_id, "remote_sign_in")
                    status, result = self.state.child_attempt_status(
                        authenticated_client, profile_id, request_id, attempt_id)
                else:
                    self.authorize_profile(authenticated_client, "remote_sign_in")
                    status, result = self.state.session_attempt_status(
                        authenticated_client, profile_id, request_id, attempt_id)
            else:
                profile = self.state.config.get("profiles", {}).get(profile_id, {})
                if self.state.is_child_profile(header_profile):
                    status, result = self.state.child_session_status(
                        authenticated_client, profile_id)
                    self.send_session_json(status, result)
                    return
                result = self.state.profile_login_state(profile)
                result = {"ok": result["remote_sign_in_state"] != "broker_unavailable",
                          **result}
                status = (HTTPStatus.SERVICE_UNAVAILABLE
                          if result["remote_sign_in_state"] == "broker_unavailable"
                          else HTTPStatus.OK)
            self.send_session_json(status, result)
            return
        if path == f"{API_PREFIX}/diagnostics/network/download":
            raw_profile = str(self.headers.get("X-WakePlay-Profile", "default") or
                              "default").strip()
            if (PROFILE_ID_PATTERN.fullmatch(raw_profile) and
                    self.state.is_child_profile(raw_profile)):
                if not self._require_child_capability():
                    return
                self._authorize_public_child(authenticated_client, "use_profile")
            client_id = str(authenticated_client.get("id") or
                            authenticated_client.get("token_sha256") or "")
            self.network_download(query.get("size", [None])[0], "host", client_id)
            return
        header_profile = self._request_profile_id()
        if self.state.is_child_profile(header_profile):
            if not self._require_child_capability():
                return
            actor_id = self._authorize_public_child(authenticated_client)
            if path == f"{API_PREFIX}/capabilities":
                self.send_json(HTTPStatus.OK, self.state.capabilities())
                return
            child_reads = {
                f"{API_PREFIX}/library": "library",
                f"{API_PREFIX}/playnite/library/list": "library",
                f"{API_PREFIX}/artwork": "artwork",
                f"{API_PREFIX}/playnite/artwork": "artwork",
                f"{API_PREFIX}/game/events": "events",
                f"{API_PREFIX}/playnite/events": "events",
                f"{API_PREFIX}/session/observation": "observation",
            }
            resource = child_reads.get(path)
            if resource == "library":
                status, result = self.state.playnite_library(
                    query.get("cursor", [""])[0],
                    query.get("limit", ["50"])[0],
                    authenticated_client, actor_id)
                self.send_json(status, result)
                return
            if resource == "artwork":
                status, body, content_type = self.state.playnite_artwork(
                    query.get("game_id", [""])[0],
                    query.get("kind", ["cover"])[0],
                    authenticated_client, actor_id)
                if status == HTTPStatus.OK:
                    self.send_binary(status, body, content_type)
                else:
                    self.send_json(status, {"error": "Game artwork is unavailable."})
                return
            if resource == "events":
                status, result = self.state.playnite_events(
                    query.get("after", ["0"])[0],
                    query.get("transition_id", [""])[0],
                    authenticated_client, actor_id)
                self.send_json(status, result)
                return
            if resource == "observation":
                status, result = self.state.playnite_observation(
                    query.get("request_ordinal", ["0"])[0],
                    query.get("transition_id", [""])[0])
                self.send_json(status, result)
                return
            if path == f"{API_PREFIX}/virtualhere/state":
                status, result = self.state.virtualhere_state(
                    query.get("force", [""])[0].lower() == "true")
                self.send_json(status, result)
                return
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})
            return
        self.authorize_profile(authenticated_client)
        if path == f"{API_PREFIX}/capabilities":
            self.send_json(HTTPStatus.OK, self.state.capabilities())
        elif path == f"{API_PREFIX}/vibepollo/repair/status":
            status, result = self.state.vibepollo_status()
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/vibepollo/apps/status":
            status, result = self.state.vibepollo_app_status(
                query.get("playnite_game_id", [""])[0])
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/discord/status":
            self.send_json(HTTPStatus.OK, self.state.discord_status())
        elif path == f"{API_PREFIX}/discord/home":
            status, result = self.state.discord_home(query.get("force", [""])[0].lower() == "true")
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/discord/channels":
            status, result = self.state.discord_channels(
                query.get("guild_id", [""])[0], query.get("force", [""])[0].lower() == "true")
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/discord/voice":
            status, result = self.state.discord_voice(query.get("force", [""])[0].lower() == "true")
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/discord/audio":
            status, result = self.state.discord_audio()
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/discord/audio/stream":
            self.discord_audio_stream(self.state.profile_id)
        elif path == f"{API_PREFIX}/virtualhere/state":
            status, result = self.state.virtualhere_state(
                query.get("force", [""])[0].lower() == "true")
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/session/observation":
            status, result = self.state.playnite_observation(
                query.get("request_ordinal", ["0"])[0],
                query.get("transition_id", [""])[0])
            self.send_json(status, result)
        elif path in {f"{API_PREFIX}/library",
                      f"{API_PREFIX}/playnite/library/list"}:
            status, result = self.state.playnite_library(
                query.get("cursor", [""])[0], query.get("limit", ["50"])[0])
            self.send_json(status, result)
        elif path in {f"{API_PREFIX}/artwork",
                      f"{API_PREFIX}/playnite/artwork"}:
            status, body, content_type = self.state.playnite_artwork(
                query.get("game_id", [""])[0], query.get("kind", ["cover"])[0])
            if status == HTTPStatus.OK:
                self.send_binary(status, body, content_type)
            else:
                self.send_json(status, {"error": "Game artwork is unavailable."})
        elif path in {f"{API_PREFIX}/game/events",
                      f"{API_PREFIX}/playnite/events"}:
            status, result = self.state.playnite_events(
                query.get("after", ["0"])[0],
                query.get("transition_id", [""])[0])
            self.send_json(status, result)
        else:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})

    def do_POST(self) -> None:  # noqa: N802
        self._begin_diagnostics("POST")
        try:
            path = urllib.parse.urlsplit(self.path).path
            if path == f"{API_PREFIX}/pair":
                body = self.read_json()
                result = self.state.pair(self.client_address[0], str(body.get("code", "")), str(body.get("client_name", "Android TV")))
                self.send_json(HTTPStatus.CREATED, result)
                return
            authenticated_client = self.require_auth()
            if authenticated_client is None:
                return
            control_prefix = f"{API_PREFIX}/control/operation/"
            if path.startswith(control_prefix):
                action = path[len(control_prefix):]
                fields = {
                    "acquire": {"operation_id", "intent", "stream_session_id",
                                "sunshine_app_id", "expected_generation",
                                "observed_sunshine_app_id", "child_session_id"},
                    "complete": {"operation_id", "generation",
                                 "actor_lease_epoch", "succeeded", "effect_result"},
                    "reconcile": {"operation_id", "observed_sunshine_app_id"},
                    "status": set(),
                }.get(action)
                if fields is None:
                    self.send_json(HTTPStatus.NOT_FOUND, {
                        "ok": False, "error": "Endpoint not found."})
                    return
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not REQUEST_ID_PATTERN.fullmatch(request_id):
                    raise ValueError("A valid X-Request-Id header is required.")
                header_profile = self._request_profile_id()
                if self.state.is_child_profile(header_profile):
                    if not self._require_child_capability():
                        return
                    self._authorize_public_child(authenticated_client, "use_profile")
                else:
                    self.authorize_profile(authenticated_client, "use_profile")
                body = self.read_json()
                if any(key not in fields for key in body):
                    raise ValueError("Invalid control operation request body.")
                if action == "acquire":
                    status, result = self.state.control_operation_acquire(
                        authenticated_client, body, request_id)
                elif action == "complete":
                    status, result = self.state.control_operation_complete(
                        authenticated_client, body, request_id)
                elif action == "status":
                    status, result = self.state.control_operation_status(authenticated_client)
                else:
                    status, result = self.state.control_operation_reconcile(
                        authenticated_client, body, request_id)
                self.send_json(status, result)
                return
            identity_prefix = f"{API_PREFIX}/vibepollo/identity/"
            if path in {identity_prefix + "challenge", identity_prefix + "bind"}:
                if not self._require_child_capability():
                    return
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not REQUEST_ID_PATTERN.fullmatch(request_id):
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "ok": False, "error": "A valid X-Request-Id header is required."})
                    return
                actor_id = self._authorize_public_child(
                    authenticated_client, "use_profile")
                body = self.read_json()
                if path == identity_prefix + "challenge":
                    if body:
                        self.send_json(HTTPStatus.BAD_REQUEST, {
                            "ok": False, "error": "An empty JSON body is required."})
                        return
                    status, result = self.state.vibepollo_identity_challenge(
                        self.client_address[0], authenticated_client,
                        actor_id, request_id)
                else:
                    status, result = self.state.vibepollo_identity_bind(
                        self.client_address[0], authenticated_client, body,
                        actor_id, request_id)
                self.send_json(status, result)
                return
            child_session_prefix = f"{API_PREFIX}/child/session/"
            if path.startswith(child_session_prefix):
                if not self._require_child_capability():
                    return
                action = path[len(child_session_prefix):]
                if action not in {"eligibility", "start", "status", "resume",
                                  "heartbeat", "end"}:
                    self.send_json(HTTPStatus.NOT_FOUND, {
                        "ok": False, "error": "Endpoint not found."})
                    return
                status, result = self._child_session_request(
                    action, authenticated_client)
                self.send_session_json(status, result)
                return
            if path == f"{API_PREFIX}/vibepollo/pair/ticket":
                if self.state.is_child_profile(self._request_profile_id()):
                    if not self._require_child_capability():
                        return
                    self._authorize_public_child(authenticated_client)
                self.read_json()
                self.send_json(HTTPStatus.CREATED,
                               self.state.stream_pair_ticket_for_client(
                                   self.client_address[0], authenticated_client))
                return
            if path == f"{API_PREFIX}/profiles/pin/verify":
                if self.state.is_child_profile(self._request_profile_id()):
                    if not self._require_child_capability():
                        return
                    self.send_json(HTTPStatus.FORBIDDEN, {
                        "ok": False, "error": "child_action_not_allowed",
                        "reason": "child_action_not_allowed"})
                    return
                profile_id = self.authorize_profile(
                    authenticated_client, "use_profile", require_pin=False)
                body = self.read_json()
                pin = body.get("pin")
                if not isinstance(pin, str) or not re.fullmatch(r"[0-9]{4}", pin):
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "ok": False, "error": "invalid_pin_format"})
                    return
                status, result = self.state.verify_pin(
                    authenticated_client, profile_id, pin,
                    self.profile_session_id())
                self.send_json(status, result)
                return
            child_prefix = f"{API_PREFIX}/profiles/children/"
            if path.startswith(child_prefix):
                if not bool(getattr(self.state, "child_profiles_api_enabled", False)):
                    self.send_json(HTTPStatus.FORBIDDEN, {
                        "ok": False, "error": "child_profiles_not_enforced",
                        "reason": "child_profiles_not_enforced"})
                    return
                body = self.read_json()
                parent_id = str(body.get("parent_profile_id") or "").strip()
                session_id = self.profile_session_id()
                header_profile = self.headers.get("X-WakePlay-Profile", "default")
                header_parent_id = self.state.resolve_profile_id(
                    header_profile, authenticated_client)
                if parent_id != header_parent_id:
                    self.send_json(HTTPStatus.FORBIDDEN, {
                        "ok": False, "error": "parent_profile_context_mismatch",
                        "reason": "parent_profile_context_mismatch"})
                    return
                self.state.request_context.profile_id = parent_id
                self.state.request_context.actor_profile_id = parent_id
                self.state.request_context.execution_profile_id = parent_id
                if path == child_prefix + "authorize":
                    pin = body.get("pin")
                    if (not isinstance(pin, str) or
                            not re.fullmatch(r"[0-9]{4}", pin)):
                        self.send_json(HTTPStatus.BAD_REQUEST, {
                            "ok": False, "error": "invalid_pin_format"})
                        return
                    status, result = self.state.begin_child_management(
                        authenticated_client, parent_id, pin, session_id)
                else:
                    authorization_id = body.get("authorization_id")
                    if not isinstance(authorization_id, str) or not authorization_id:
                        self.send_json(HTTPStatus.BAD_REQUEST, {
                            "ok": False, "error": "invalid_authorization_id"})
                        return
                    if path == child_prefix + "list":
                        status, result = self.state.child_profiles_snapshot(
                            authenticated_client, parent_id, session_id,
                            authorization_id)
                    elif path == child_prefix + "create":
                        status, result = self.state.create_child_profile(
                            authenticated_client, parent_id, session_id,
                            authorization_id, body.get("request_id"),
                            body.get("expected_revision"), body.get("draft"),
                            body.get("grant_current_device", False))
                    elif path == child_prefix + "update":
                        status, result = self.state.update_child_profile(
                            authenticated_client, parent_id,
                            body.get("child_profile_id"), session_id,
                            authorization_id, body.get("request_id"),
                            body.get("expected_revision"), body.get("draft"))
                    elif path == child_prefix + "delete":
                        status, result = self.state.delete_child_profile(
                            authenticated_client, parent_id,
                            body.get("child_profile_id"), session_id,
                            authorization_id, body.get("request_id"),
                            body.get("expected_revision"))
                    elif path == child_prefix + "sharing/get":
                        result = self.state.child_game_sharing_snapshot(
                            authenticated_client, parent_id, body.get("game_id"),
                            session_id, authorization_id)
                        status = HTTPStatus.OK
                    elif path == child_prefix + "sharing/set":
                        status, result = self.state.set_child_game_sharing(
                            authenticated_client, parent_id, body.get("game_id"),
                            body.get("selected_child_ids"),
                            body.get("expected_revision"),
                            body.get("request_id"), session_id,
                            authorization_id)
                    else:
                        self.send_json(HTTPStatus.NOT_FOUND,
                                       {"error": "Endpoint not found."})
                        return
                self.send_json(status, result)
                return
            raw_child_header_profile = str(
                self.headers.get("X-WakePlay-Profile", "default") or
                "default").strip()
            child_header_profile = raw_child_header_profile \
                if PROFILE_ID_PATTERN.fullmatch(raw_child_header_profile) else ""
            if self.state.is_child_profile(child_header_profile) and path in {
                    f"{API_PREFIX}/system/session/ensure",
                    f"{API_PREFIX}/system/session/switch",
                    f"{API_PREFIX}/system/session/cancel"}:
                if not self._require_child_capability():
                    return
                request_id = self.headers.get("X-Request-Id", "").strip()
                if path.endswith("/cancel"):
                    if not REQUEST_ID_PATTERN.fullmatch(request_id):
                        self.send_json(HTTPStatus.BAD_REQUEST, {
                            "error": "A valid X-Request-Id header is required."})
                        return
                    body = self.read_json()
                    attempt_id = str(body.get("attempt_id") or "").strip()
                    attempt_request_id = str(
                        body.get("request_id") or request_id).strip()
                    if attempt_request_id != request_id:
                        self.send_session_json(HTTPStatus.BAD_REQUEST, {
                            "ok": False, "state": "action_required",
                            "reason": "request_id_mismatch"})
                        return
                    actor_id = self._authorize_public_child(
                        authenticated_client, "remote_sign_in")
                    status, result = self.state.cancel_session_attempt(
                        authenticated_client, actor_id, attempt_request_id,
                        attempt_id)
                else:
                    body = self.read_json()
                    if body:
                        self.send_session_json(HTTPStatus.BAD_REQUEST, {
                            "ok": False, "state": "action_required",
                            "reason": "invalid_request_body"})
                        return
                    actor_id = self._authorize_public_child(
                        authenticated_client,
                        "use_profile" if path.endswith("/ensure") else
                        "remote_sign_in")
                    status, result = (self.state.ensure_session(
                        authenticated_client, actor_id, request_id)
                        if path.endswith("/ensure") else
                        self.state.switch_session(
                            authenticated_client, actor_id, request_id))
                self.send_session_json(status, result)
                return
            if path == f"{API_PREFIX}/system/session/ensure":
                request_id = self.headers.get("X-Request-Id", "").strip()
                body = self.read_json()
                if body:
                    self.send_session_json(HTTPStatus.BAD_REQUEST, {
                        "ok": False, "state": "action_required",
                        "reason": "invalid_request_body"})
                    return
                profile_id = self.authorize_profile(
                    authenticated_client, "use_profile")
                status, result = self.state.ensure_session(
                    authenticated_client, profile_id, request_id)
                self.send_session_json(status, result)
                return
            if path == f"{API_PREFIX}/system/session/switch":
                request_id = self.headers.get("X-Request-Id", "").strip()
                body = self.read_json()
                if body:
                    self.send_session_json(HTTPStatus.BAD_REQUEST, {
                        "ok": False, "state": "action_required",
                        "reason": "invalid_request_body"})
                    return
                profile_id = self.authorize_profile(
                    authenticated_client, "remote_sign_in")
                status, result = self.state.switch_session(
                    authenticated_client, profile_id, request_id)
                self.send_session_json(status, result)
                return
            if path == f"{API_PREFIX}/system/session/cancel":
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not REQUEST_ID_PATTERN.fullmatch(request_id):
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                attempt_id = str(body.get("attempt_id") or "").strip()
                attempt_request_id = str(body.get("request_id") or request_id).strip()
                if attempt_request_id != request_id:
                    self.send_session_json(HTTPStatus.BAD_REQUEST, {
                        "ok": False, "state": "action_required",
                        "reason": "request_id_mismatch"})
                    return
                profile_id = self.authorize_profile(
                    authenticated_client, "remote_sign_in")
                status, result = self.state.cancel_session_attempt(
                    authenticated_client, profile_id, attempt_request_id, attempt_id)
                self.send_session_json(status, result)
                return
            raw_child_header_profile = str(
                self.headers.get("X-WakePlay-Profile", "default") or
                "default").strip()
            child_header_profile = raw_child_header_profile \
                if PROFILE_ID_PATTERN.fullmatch(raw_child_header_profile) else ""
            active_child_session = self.state.child_time_session
            revoked_child_hard_reset = path == f"{API_PREFIX}/session/hard-reset" \
                and isinstance(active_child_session, dict) \
                and str(active_child_session.get("actor_profile_id") or "") == \
                child_header_profile
            if self.state.is_child_profile(child_header_profile) or revoked_child_hard_reset:
                if path == f"{API_PREFIX}/system/suspend-session":
                    if not self._require_child_capability():
                        return
                    actor_id = self._authorize_public_child(
                        authenticated_client, "use_profile")
                    self._require_child_profile_session()
                    request_id = self.headers.get("X-Request-Id", "").strip()
                    if not REQUEST_ID_PATTERN.fullmatch(request_id):
                        raise ValueError("A valid X-Request-Id header is required.")
                    body = self.read_json()
                    status, result = self.state.idempotent(
                        f"child-suspend:{self.state.broker_client_id(authenticated_client)}:"
                        f"{actor_id}:{request_id}",
                        lambda: self.state.child_suspend_session(
                            authenticated_client, actor_id, body, request_id))
                    self.send_json(status, result)
                    return
                if path == f"{API_PREFIX}/system/sleep":
                    if not self._require_child_capability():
                        return
                    actor_id = self._authorize_public_child(authenticated_client, "use_profile")
                    self._require_child_profile_session()
                    request_id = self.headers.get("X-Request-Id", "").strip()
                    if not REQUEST_ID_PATTERN.fullmatch(request_id):
                        raise ValueError("A valid X-Request-Id header is required.")
                    if self.read_json():
                        raise ValueError("An empty JSON body is required.")
                    status, result = self.state.idempotent(
                        f"child-sleep:{self.state.broker_client_id(authenticated_client)}:{actor_id}:{request_id}",
                        self.state.sleep_host)
                    self.send_json(status, result)
                    return
                if path == f"{API_PREFIX}/session/hard-reset":
                    if not self._require_child_capability():
                        return
                    request_id = self.headers.get("X-Request-Id", "").strip()
                    if not REQUEST_ID_PATTERN.fullmatch(request_id):
                        raise ValueError("A valid X-Request-Id header is required.")
                    body = self.read_json()
                    actor_id = None
                    if self.state.is_child_profile(child_header_profile):
                        try:
                            actor_id = self._authorize_public_child(
                                authenticated_client, "use_profile")
                        except PermissionError:
                            actor_id = None
                    if actor_id is not None:
                        client_id = self.state.broker_client_id(authenticated_client)
                        execution_id = self.state.execution_profile_id
                        status, result = self.state.idempotent(
                            f"child-hard-reset:"
                            f"{client_id}:"
                            f"{actor_id}:{request_id}",
                            lambda: self.state.hard_reset_session(
                                body, client_id, execution_id))
                    else:
                        # A deleted child may finish only its already-bound legacy
                        # session; it does not gain the global close authority of an
                        # enabled child profile.
                        self._require_child_profile_session()
                        session_id = GatewayState._child_time_id(
                            body.get("session_id"), "child session ID")
                        body["session_id"] = session_id
                        actor_id = self._authorize_public_child_session(
                            authenticated_client, session_id)
                        status, result = self.state.idempotent(
                            f"child-hard-reset:"
                            f"{self.state.broker_client_id(authenticated_client)}:"
                            f"{actor_id}:{session_id}:{request_id}",
                            lambda: self.state.hard_reset_session(
                                body,
                                self.state.broker_client_id(authenticated_client),
                                str(active_child_session.get(
                                    "execution_profile_id") or ""),
                                session_id, actor_id))
                    self.send_json(status, result)
                    return
                child_action = path[len(API_PREFIX) + 1:]
                if child_action.startswith("playnite/"):
                    child_action = child_action[len("playnite/"):]
                child_start = child_action in {
                    "game/start", "game/stop-verified", "game/focus", "game/install",
                    "game/uninstall", "game/install/focus", "game/install/verify",
                }
                child_refresh = path in {
                    f"{API_PREFIX}/library/refresh",
                    f"{API_PREFIX}/playnite/library/refresh",
                }
                if child_start:
                    if not self._require_child_capability():
                        return
                    actor_id = self._authorize_public_child(
                        authenticated_client, "use_profile")
                    request_id = self.headers.get("X-Request-Id", "").strip()
                    if not REQUEST_ID_PATTERN.fullmatch(request_id):
                        self.send_json(HTTPStatus.BAD_REQUEST, {
                            "error": "A valid X-Request-Id header is required."})
                        return
                    body = self.read_json()
                    if child_action == "game/start":
                        self._require_child_start_session(authenticated_client, actor_id)
                    status, result = self.state.idempotent(
                        f"{actor_id}:game-provider:{request_id}",
                        lambda: self.state.playnite_action(
                            child_action, body, authenticated_client, actor_id))
                    self.send_json(status, result)
                    return
                if child_refresh:
                    if not self._require_child_capability():
                        return
                    actor_id = self._authorize_public_child(
                        authenticated_client, "use_profile")
                    request_id = self.headers.get("X-Request-Id", "").strip()
                    if not REQUEST_ID_PATTERN.fullmatch(request_id):
                        self.send_json(HTTPStatus.BAD_REQUEST, {
                            "error": "A valid X-Request-Id header is required."})
                        return
                    body = self.read_json()
                    status, result = self.state.idempotent(
                        f"{actor_id}:game-provider:{request_id}",
                        lambda: self.state.playnite_action(
                            "library/refresh", body, authenticated_client, actor_id))
                    self.send_json(status, result)
                    return
                if path == f"{API_PREFIX}/vibepollo/pair":
                    if not self._require_child_capability():
                        return
                    actor_id = self._authorize_public_child(
                        authenticated_client, "use_profile")
                    body = self.read_json()
                    status, result = self.state.vibepollo_pair_client(
                        self.client_address[0], authenticated_client,
                        str(body.pop("ticket", "")), body)
                    self.send_json(status, result)
                    return
                virtualhere_prefix = f"{API_PREFIX}/virtualhere/"
                if path.startswith(virtualhere_prefix):
                    actor_id = self._authorize_public_child(
                        authenticated_client, "use_profile")
                    request_id = self.headers.get("X-Request-Id", "").strip()
                    if not REQUEST_ID_PATTERN.fullmatch(request_id):
                        raise ValueError("A valid X-Request-Id header is required.")
                    body = self.read_json()
                    action = path[len(virtualhere_prefix):]
                    status, result = self.state.idempotent(
                        f"child-virtualhere:{self.state.broker_client_id(authenticated_client)}:"
                        f"{actor_id}:{request_id}",
                        lambda: self.state.virtualhere_action(action, body))
                    self.send_json(status, result)
                    return
                if not self._require_child_capability():
                    return
                self.send_json(HTTPStatus.FORBIDDEN, {
                    "ok": False, "error": "child_action_not_allowed",
                    "reason": "child_action_not_allowed"})
                return
            if path == f"{API_PREFIX}/system/sleep":
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {"error": "A valid X-Request-Id header is required."})
                    return
                self.read_json()
                status, result = self.state.idempotent(
                    f"system-sleep:{request_id}", self.state.sleep_host)
                self.send_json(status, result)
                return
            if path == f"{API_PREFIX}/system/suspend-session":
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {"error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.idempotent(
                    f"session-suspend:{request_id}",
                    lambda: self.state.suspend_session(body, request_id))
                self.send_json(status, result)
                return
            profile_id = self.authorize_profile(authenticated_client)
            if path == f"{API_PREFIX}/microphone/stream":
                self.microphone_stream(profile_id)
                return
            if path == f"{API_PREFIX}/vibepollo/pair":
                body = self.read_json()
                status, result = self.state.vibepollo_pair_client(
                    self.client_address[0], authenticated_client,
                    str(body.pop("ticket", "")), body)
                self.send_json(status, result)
                return
            if path == f"{API_PREFIX}/session/hard-reset":
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.idempotent(
                    f"session-hard-reset:"
                    f"{self.state.broker_client_id(authenticated_client)}:{request_id}",
                    lambda: self.state.hard_reset_session(body))
                self.send_json(status, result)
                return
            if path == f"{API_PREFIX}/vibepollo/apps/ensure":
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.vibepollo_ensure_app(body)
                self.send_json(status, result)
                return
            prefix = f"{API_PREFIX}/vibepollo/repair/"
            if path.startswith(prefix):
                action = path[len(prefix):]
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {"error": "A valid X-Request-Id header is required."})
                    return
                status, result = self.state.idempotent(
                    f"{profile_id}:{request_id}", lambda: self.state.vibepollo_action(action))
                self.send_json(status, result)
                return
            discord_prefix = f"{API_PREFIX}/discord/"
            if path.startswith(discord_prefix):
                action = path[len(discord_prefix):]
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {"error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                operation = (lambda: self.state.audio_action(action[6:], body)) \
                    if action.startswith("audio/") else \
                    (lambda: self.state.discord_action(action, body))
                status, result = self.state.idempotent(f"{profile_id}:{request_id}", operation)
                self.send_json(status, result)
                return
            virtualhere_prefix = f"{API_PREFIX}/virtualhere/"
            if path.startswith(virtualhere_prefix):
                action = path[len(virtualhere_prefix):]
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {"error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.idempotent(
                    f"{profile_id}:{request_id}",
                    lambda: self.state.virtualhere_action(action, body))
                self.send_json(status, result)
                return
            playnite_prefix = f"{API_PREFIX}/playnite/"
            provider_action = path in {
                f"{API_PREFIX}/game/start", f"{API_PREFIX}/game/install",
                f"{API_PREFIX}/game/uninstall",
                f"{API_PREFIX}/game/stop-verified", f"{API_PREFIX}/game/focus",
                f"{API_PREFIX}/game/install/focus",
                f"{API_PREFIX}/game/install/verify",
                f"{API_PREFIX}/library/refresh"}
            if path.startswith(playnite_prefix) or provider_action:
                action = path[len(playnite_prefix):] if path.startswith(playnite_prefix) \
                    else path[len(API_PREFIX) + 1:]
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.idempotent(
                    f"{profile_id}:game-provider:{request_id}",
                    lambda: self.state.playnite_action(
                        action, body, authenticated_client, profile_id, request_id))
                self.send_json(status, result)
                return
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})
        except PermissionError as error:
            self.send_permission_error(error)
        except (ValueError, json.JSONDecodeError) as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except Exception as error:  # keep the gateway alive on malformed upstream responses
            self._diagnostic_error = error
            self._diagnostic_unexpected = True
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(error)})
        finally:
            self._finish_diagnostics()


class GatewayServer(ThreadingHTTPServer):
    daemon_threads = True
    # Two Gateway processes on Windows split incoming connections and make a
    # valid pairing code appear random. The machine Gateway must be exclusive.
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], state: GatewayState) -> None:
        super().__init__(address, GatewayHandler)
        self.state = state

    def shutdown_request(self, request: socket.socket) -> None:
        if not isinstance(request, ssl.SSLSocket):
            super().shutdown_request(request)
            return
        unwrapped = None
        try:
            # TCPServer's SHUT_WR skips TLS close_notify and can truncate a
            # response already handed to the socket. Bound the peer handshake.
            request.settimeout(0.1)
            unwrapped = request.unwrap()
        except (OSError, ValueError):
            pass
        finally:
            if unwrapped is not None:
                unwrapped.close()
            self.close_request(request)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="gateway.json")
    parser.add_argument("--registry-lock")
    parser.add_argument("--pairing-code", default=os.environ.get("WAKEPLAY_PAIRING_CODE"))
    args = parser.parse_args()

    config_path = Path(args.config)
    DIAGNOSTICS.start(config_path.resolve().parent / "logs")
    state = GatewayState(config_path, args.pairing_code,
                         Path(args.registry_lock) if args.registry_lock else None)
    server = GatewayServer((str(state.config["listen_host"]), int(state.config["listen_port"])), state)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(state.path_from_config("certificate"), state.path_from_config("private_key"))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    state.write_runtime_info()
    state.child_time_housekeeping_start()
    print(f"Wake & Play Host Gateway listening on https://{state.config['listen_host']}:{state.config['listen_port']}", flush=True)
    print("Pairing is active for 10 minutes." if args.pairing_code else "Pairing is disabled for this run.", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        state.child_time_housekeeping_stop_now()
        state.child_time_shutdown()
        server.server_close()
        DIAGNOSTICS.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        Path("gateway-startup-error.txt").write_text(
            f"Python {platform.python_version()}: {type(error).__name__}: {error}",
            encoding="utf-8")
        raise
