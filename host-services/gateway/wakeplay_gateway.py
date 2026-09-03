#!/usr/bin/env python3
"""Authenticated HTTPS facade for Discord, Vibepollo, audio and VirtualHere controls."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import logging.handlers
import os
import queue
import re
import secrets
import ssl
import subprocess
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


API_PREFIX = "/api/v1"
PAIRING_LIFETIME_SECONDS = 10 * 60
STREAM_PAIR_TICKET_LIFETIME_SECONDS = 60
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
DISCORD_ID_PATTERN = re.compile(r"^[0-9]{5,32}$")
VIRTUALHERE_ADDRESS_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")
AUDIO_DEVICE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:{}-]{1,220}$")
PROFILE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
PLAYNITE_GAME_ID_PATTERN = re.compile(
    r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
GAME_RECORD_ID_PATTERN = re.compile(
    r"^[a-z][a-z0-9_-]{1,31}:[A-Za-z0-9._-]{1,128}$")
PLAYNITE_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{0,128}$")
DIAGNOSTIC_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9._:$-]{1,256}$")
DIAGNOSTIC_ROUTE_PATTERN = re.compile(r"^/[A-Za-z0-9._:{}/-]{0,255}$")
DIAGNOSTIC_MAX_BYTES = 2 * 1024 * 1024
DIAGNOSTIC_BACKUP_COUNT = 9
DIAGNOSTIC_QUEUE_SIZE = 512
DIAGNOSTIC_RETENTION_SECONDS = 7 * 24 * 60 * 60
DIAGNOSTIC_FIELDS = {
    "method", "route", "request_id", "profile_id", "status",
    "http_status", "duration_ms", "error_type",
}


def compact_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


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
    def __init__(self, config_path: Path, pairing_code: str | None) -> None:
        self.config_path = config_path.resolve()
        # Windows PowerShell 5.1 writes UTF-8 files with a BOM by default.
        self.config = json.loads(self.config_path.read_text(encoding="utf-8-sig"))
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
        self.config["profiles"].setdefault("default", {
            "discord_bridge": self.config["discord_bridge"],
            "vibepollo_bridge": self.config["vibepollo_bridge"],
            "game_provider_bridge": self.config["game_provider_bridge"],
            "playnite_bridge": self.config["playnite_bridge"],
        })
        for profile in self.config["profiles"].values():
            if not isinstance(profile, dict):
                continue
            endpoint = str(profile.get("game_provider_bridge") or
                           profile.get("playnite_bridge") or provider_bridge)
            profile.setdefault("game_provider_bridge", endpoint)
            profile.setdefault("playnite_bridge", endpoint)
        self.config.setdefault("clients", [])
        self.pairing_code_hash = sha256_text(pairing_code) if pairing_code else None
        self.pairing_expires_at = time.monotonic() + PAIRING_LIFETIME_SECONDS if pairing_code else 0.0
        self.pairing_control_path = self.config_path.with_name("pairing-code.json")
        self.failed_pair_attempts: dict[str, list[float]] = {}
        self.stream_pair_tickets: dict[str, dict[str, Any]] = {}
        self.idempotent_results: dict[str, tuple[float, int, Any]] = {}
        self.vibepollo_app_operations: dict[str, dict[str, Any]] = {}
        self.microphone_streams: dict[str, str] = {}
        self.discord_audio_streams: dict[str, str] = {}
        self.network_downloads: set[tuple[str, str]] = set()
        self.lock = threading.RLock()
        self.request_context = threading.local()
        self.runtime_status_path = self.config_path.with_name("runtime-status.json")
        self.gateway_runtime_path = self.config_path.with_name("gateway-runtime.json")
        self.started_at = int(time.time())
        self.version_info = self._load_version_info()
        self.last_runtime_profile = ""
        self.last_runtime_write = 0.0

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

    def save(self) -> None:
        temporary = self.config_path.with_suffix(self.config_path.suffix + ".tmp")
        temporary.write_text(json.dumps(self.config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, self.config_path)

    def client_for_token(self, token: str) -> dict[str, Any] | None:
        digest = sha256_text(token)
        with self.lock:
            for client in self.config["clients"]:
                if secrets.compare_digest(str(client.get("token_sha256", "")), digest):
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
        record = {
            "id": client_id,
            "name": client_name[:80] or "Android TV",
            "token_sha256": sha256_text(token),
            "paired_at": int(time.time()),
        }
        with self.lock:
            self.config["clients"].append(record)
            self.save()
        ticket = self.issue_stream_pair_ticket(address, client_id)
        return {
            "client_id": client_id,
            "token": token,
            "stream_pair_ticket": ticket,
            "stream_pair_expires_seconds": STREAM_PAIR_TICKET_LIFETIME_SECONDS,
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
            "vibepollo", "/pair", {"pin": pin, "name": name}, timeout=13.0)
        with self.lock:
            current = self.stream_pair_tickets.get(digest)
            if ok:
                self.stream_pair_tickets.pop(digest, None)
            elif current:
                current["in_use"] = False
        if not ok:
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False,
                "error": self.upstream_error(result, "Unable to pair the Moonlight client."),
            }
        return HTTPStatus.OK, {
            "ok": True,
            "client_uuid": str(result.get("client_uuid", "")),
            "permissions": int(result.get("permissions", 0)),
        }

    def select_profile(self, profile_id: str | None, record_use: bool = False) -> str:
        selected = str(profile_id or "default").strip() or "default"
        if not PROFILE_ID_PATTERN.fullmatch(selected):
            raise ValueError("Invalid integration profile ID.")
        profiles = self.config.get("profiles", {})
        if selected not in profiles:
            raise ValueError(f"Unknown integration profile: {selected}")
        self.request_context.profile_id = selected
        if record_use:
            self.record_profile_use(selected)
        return selected

    def record_profile_use(self, profile_id: str) -> None:
        now = time.monotonic()
        with self.lock:
            if profile_id == self.last_runtime_profile and now - self.last_runtime_write < 5.0:
                return
            temporary = self.runtime_status_path.with_suffix(".json.tmp")
            temporary.write_text(json.dumps({
                "profile_id": profile_id,
                "updated_at": int(time.time()),
            }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            os.replace(temporary, self.runtime_status_path)
            self.last_runtime_profile = profile_id
            self.last_runtime_write = now

    @property
    def profile_id(self) -> str:
        return str(getattr(self.request_context, "profile_id", "default"))

    def loopback_headers(self, content_type: str | None = None) -> dict[str, str]:
        headers = {"Content-Type": content_type} if content_type else {}
        request_id = getattr(self.request_context, "request_id", "")
        if isinstance(request_id, str) and REQUEST_ID_PATTERN.fullmatch(request_id):
            headers["X-Request-Id"] = request_id
        return headers

    def bridge_url(self, name: str, path: str) -> str:
        profile = self.config.get("profiles", {}).get(self.profile_id, {})
        key = "game_provider_bridge" if name in {"game_provider", "playnite"} \
            else f"{name}_bridge"
        base = str(profile.get(key, "")).rstrip("/")
        if not base and key == "game_provider_bridge":
            base = str(profile.get("playnite_bridge", "")).rstrip("/")
        if not base and self.profile_id == "default":
            base = str(self.config.get(key) or (
                self.config.get("playnite_bridge")
                if key == "game_provider_bridge" else "")).rstrip("/")
        if not base.startswith("http://127.0.0.1:") and not base.startswith("http://localhost:"):
            raise ValueError(f"{name} bridge must remain on loopback")
        return base + path

    def proxy(self, name: str, path: str, timeout: float = 2.5) -> tuple[bool, Any]:
        try:
            request = urllib.request.Request(
                self.bridge_url(name, path), headers=self.loopback_headers(), method="GET")
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

    def proxy_bytes(self, name: str, path: str, timeout: float = 8.0) \
            -> tuple[int, bytes, str]:
        try:
            request = urllib.request.Request(
                self.bridge_url(name, path), headers=self.loopback_headers(), method="GET")
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
                   timeout: float = 8.0) -> tuple[bool, Any]:
        try:
            request = urllib.request.Request(
                self.bridge_url(name, path),
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
            return False, value
        except (urllib.error.URLError, TimeoutError, OSError, ValueError,
                json.JSONDecodeError) as error:
            return False, {"error": str(error)}

    def capabilities(self) -> dict[str, Any]:
        vibepollo_ok, vibepollo = self.proxy("vibepollo", "/health", timeout=1.0)
        discord_ok, discord = self.proxy("discord", "/health", timeout=1.0)
        playnite_ok, playnite = self.proxy("game_provider", "/health", timeout=1.0)
        microphone = self.microphone_status()
        discord_audio = self.discord_audio_status()
        virtualhere_ok, virtualhere = (False, {"error": "Discord Bridge is offline."})
        if discord_ok:
            virtualhere_ok, virtualhere = self.proxy(
                "discord", "/virtualhere-state", timeout=1.5)
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
                "game_provider": {"available": playnite_ok, "health": playnite},
                "playnite": {"available": playnite_ok, "health": playnite},
                "discord": {"available": discord_ok, "health": discord},
                "virtualhere": {
                    "available": virtualhere_ok and bool(virtualhere.get("installed", False)),
                    "health": virtualhere,
                },
                "host_sleep": {"available": os.name == "nt"},
                "session_suspend": {"available": os.name == "nt"},
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

    def hard_reset_session(self, body: dict[str, Any]) -> tuple[int, Any]:
        if body.get("force") is not True:
            raise ValueError("Explicit force confirmation is required.")
        provider_ok, provider = self.proxy_json(
            "game_provider", "/session/hard-reset", {"force": True}, timeout=12.0)
        provider_accepted = provider_ok and isinstance(provider, dict) \
            and bool(provider.get("accepted", False))
        session_ok, _session = self.proxy(
            "vibepollo", "/action/close-app", timeout=8.0)
        if not provider_accepted:
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False,
                "accepted": False,
                "error": self.upstream_error(
                    provider, "Game Provider Bridge rejected the hard reset."),
                "session_close_requested": session_ok,
            }
        return HTTPStatus.OK, {
            "ok": True,
            "accepted": True,
            "stopped_game_count": int(provider.get("stopped_count") or 0),
            "session_close_requested": session_ok,
        }

    def profiles_summary(self) -> dict[str, Any]:
        original_profile = self.profile_id
        profiles = []
        suggested_profile_id = ""
        available_profile_id = ""
        try:
            for profile_id in sorted(self.config.get("profiles", {})):
                if not PROFILE_ID_PATTERN.fullmatch(str(profile_id)):
                    continue
                self.select_profile(str(profile_id))
                profile_config = self.config["profiles"].get(profile_id, {})
                display_name = str(
                    profile_config.get("name") or
                    profile_config.get("display_name") or
                    profile_id
                ).strip()[:80] or str(profile_id)
                display_name = re.sub(r"[\x00-\x1f\x7f]", " ", display_name).strip()
                discord = self.discord_status()
                vibepollo_online, _ = self.proxy("vibepollo", "/health", timeout=1.0)
                playnite_online, playnite_health = self.proxy(
                    "game_provider", "/health", timeout=1.0)
                playnite_connector = playnite_online and bool(
                    playnite_health.get("connector_connected", False))
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
                profiles.append({
                    "id": str(profile_id),
                    "name": display_name,
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
            self.select_profile(
                original_profile if original_profile in self.config.get("profiles", {})
                else "default")
        return {
            "profiles": profiles,
            "suggested_profile_id": suggested_profile_id or available_profile_id,
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

    def playnite_health(self) -> tuple[int, Any]:
        ok, result = self.proxy("game_provider", "/health", timeout=1.5)
        return (HTTPStatus.OK if ok else HTTPStatus.SERVICE_UNAVAILABLE), {
            "ok": ok,
            "bridge": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Game Provider Bridge is offline in this profile."),
        }

    def playnite_library(self, cursor: Any, limit: Any) -> tuple[int, Any]:
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

    def playnite_artwork(self, game_id: Any, kind: Any) -> tuple[int, bytes, str]:
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

    def playnite_state(self, resource: str) -> tuple[int, Any]:
        paths = {
            "current": "/game/current",
            "readiness": "/window/readiness",
        }
        if resource not in paths:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown Playnite resource."}
        ok, result = self.proxy("game_provider", paths[resource], timeout=3.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            resource: result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Unable to read game provider state."),
        }

    def playnite_events(self, after: Any, transition_id: Any = "") -> tuple[int, Any]:
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

    def playnite_action(self, action: str, body: dict[str, Any]) -> tuple[int, Any]:
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
        elif action == "game/stop":
            payload = {"force": False}
            if body.get("game_id"):
                payload["game_id"] = self._playnite_game_id(body.get("game_id"))
            path = "/game/stop"
            timeout = 25.0
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
        ok, result = self.proxy_json("game_provider", path, payload, timeout=timeout)
        accepted = not isinstance(result, dict) or bool(result.get("accepted", True))
        if ok and action in {"game/start", "game/install", "game/uninstall", "game/stop", "game/stop-verified"} \
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
        with self.lock:
            expired = [item for item, value in self.idempotent_results.items() if now - value[0] > IDEMPOTENCY_LIFETIME_SECONDS]
            for item in expired:
                self.idempotent_results.pop(item, None)
            cached = self.idempotent_results.get(key)
            if cached:
                return cached[1], cached[2]
        status, result = operation()
        with self.lock:
            self.idempotent_results[key] = (now, status, result)
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
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

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

    def require_auth(self) -> bool:
        if self.authenticated():
            return True
        self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Authentication required."})
        return False

    def select_profile(self) -> str:
        return self.state.select_profile(
            self.headers.get("X-WakePlay-Profile", "default"), record_use=True)

    def _begin_diagnostics(self, method: str) -> None:
        self._diagnostic_method = method
        self._diagnostic_route = diagnostic_route(self.path)
        self._diagnostic_started = time.monotonic()
        self._diagnostic_error: BaseException | None = None
        self._diagnostic_unexpected = False
        self._response_status = 0
        for key in ("request_id", "profile_id"):
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
                    "duration_ms": max(0, int(
                        (time.monotonic() - self._diagnostic_started) * 1000)),
                    "request_id": str(getattr(
                        self.state.request_context, "request_id", "")),
                    "profile_id": str(getattr(
                        self.state.request_context, "profile_id", "")),
                }
                DIAGNOSTICS.record(
                    "request.failed" if failed else "request.completed",
                    level="ERROR" if self._diagnostic_unexpected else
                    ("WARN" if failed else "INFO"),
                    error=self._diagnostic_error,
                    include_frames=self._diagnostic_unexpected,
                    **fields)
        finally:
            for key in ("request_id", "profile_id"):
                if hasattr(self.state.request_context, key):
                    delattr(self.state.request_context, key)

    def do_GET(self) -> None:  # noqa: N802
        self._begin_diagnostics("GET")
        try:
            self._do_GET()
        except (ValueError, json.JSONDecodeError) as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
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
        if not self.require_auth():
            return
        if path == f"{API_PREFIX}/profiles":
            self.send_json(HTTPStatus.OK, self.state.profiles_summary())
            return
        profile_id = self.select_profile()
        if path == f"{API_PREFIX}/diagnostics/network/download":
            client = self.authenticated_client() or {}
            client_id = str(client.get("id") or client.get("token_sha256") or "")
            self.network_download(query.get("size", [None])[0], profile_id, client_id)
        elif path == f"{API_PREFIX}/capabilities":
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
        elif path in {f"{API_PREFIX}/game-provider/health",
                      f"{API_PREFIX}/playnite/health"}:
            status, result = self.state.playnite_health()
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
        elif path in {f"{API_PREFIX}/game/current",
                      f"{API_PREFIX}/playnite/game/current"}:
            status, result = self.state.playnite_state("current")
            self.send_json(status, result)
        elif path in {f"{API_PREFIX}/window/readiness",
                      f"{API_PREFIX}/playnite/window/readiness"}:
            status, result = self.state.playnite_state("readiness")
            self.send_json(status, result)
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
            if not self.require_auth():
                return
            authenticated_client = self.authenticated_client()
            profile_id = self.select_profile()
            if path == f"{API_PREFIX}/microphone/stream":
                self.microphone_stream(profile_id)
                return
            if path == f"{API_PREFIX}/vibepollo/pair/ticket":
                self.read_json()
                self.send_json(HTTPStatus.CREATED,
                               self.state.stream_pair_ticket_for_client(
                                   self.client_address[0], authenticated_client))
                return
            if path == f"{API_PREFIX}/vibepollo/pair":
                body = self.read_json()
                status, result = self.state.vibepollo_pair_client(
                    self.client_address[0], authenticated_client,
                    str(body.pop("ticket", "")), body)
                self.send_json(status, result)
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
            if path == f"{API_PREFIX}/session/hard-reset":
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.idempotent(
                    f"{profile_id}:session-hard-reset:{request_id}",
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
                f"{API_PREFIX}/game/uninstall", f"{API_PREFIX}/game/stop",
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
                    lambda: self.state.playnite_action(action, body))
                self.send_json(status, result)
                return
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})
        except PermissionError as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.FORBIDDEN, {"error": str(error)})
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="gateway.json")
    parser.add_argument("--pairing-code", default=os.environ.get("WAKEPLAY_PAIRING_CODE"))
    args = parser.parse_args()

    config_path = Path(args.config)
    DIAGNOSTICS.start(config_path.resolve().parent / "logs")
    state = GatewayState(config_path, args.pairing_code)
    server = GatewayServer((str(state.config["listen_host"]), int(state.config["listen_port"])), state)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(state.path_from_config("certificate"), state.path_from_config("private_key"))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    state.write_runtime_info()
    print(f"Wake & Play Host Gateway listening on https://{state.config['listen_host']}:{state.config['listen_port']}", flush=True)
    print("Pairing is active for 10 minutes." if args.pairing_code else "Pairing is disabled for this run.", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        DIAGNOSTICS.close()


if __name__ == "__main__":
    main()
