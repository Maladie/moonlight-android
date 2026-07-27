#!/usr/bin/env python3
"""Authenticated HTTPS facade for Discord, Vibepollo, audio and VirtualHere controls."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


API_PREFIX = "/api/v1"
PAIRING_LIFETIME_SECONDS = 10 * 60
IDEMPOTENCY_LIFETIME_SECONDS = 2 * 60
MAX_BODY_BYTES = 16 * 1024
DISCORD_ID_PATTERN = re.compile(r"^[0-9]{5,32}$")
VIRTUALHERE_ADDRESS_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")
AUDIO_DEVICE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:{}-]{1,220}$")
PROFILE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
PLAYNITE_GAME_ID_PATTERN = re.compile(
    r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
PLAYNITE_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{0,128}$")


def compact_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class GatewayState:
    def __init__(self, config_path: Path, pairing_code: str | None) -> None:
        self.config_path = config_path.resolve()
        # Windows PowerShell 5.1 writes UTF-8 files with a BOM by default.
        self.config = json.loads(self.config_path.read_text(encoding="utf-8-sig"))
        self.config.setdefault("listen_host", "0.0.0.0")
        self.config.setdefault("listen_port", 8785)
        self.config.setdefault("discord_bridge", "http://127.0.0.1:8765")
        self.config.setdefault("vibepollo_bridge", "http://127.0.0.1:8775")
        self.config.setdefault("playnite_bridge", "http://127.0.0.1:8780")
        self.config.setdefault("profiles", {})
        self.config["profiles"].setdefault("default", {
            "discord_bridge": self.config["discord_bridge"],
            "vibepollo_bridge": self.config["vibepollo_bridge"],
            "playnite_bridge": self.config["playnite_bridge"],
        })
        self.config.setdefault("clients", [])
        self.pairing_code_hash = sha256_text(pairing_code) if pairing_code else None
        self.pairing_expires_at = time.monotonic() + PAIRING_LIFETIME_SECONDS if pairing_code else 0.0
        self.pairing_control_path = self.config_path.with_name("pairing-code.json")
        self.failed_pair_attempts: dict[str, list[float]] = {}
        self.idempotent_results: dict[str, tuple[float, int, Any]] = {}
        self.lock = threading.RLock()
        self.request_context = threading.local()
        self.runtime_status_path = self.config_path.with_name("runtime-status.json")
        self.last_runtime_profile = ""
        self.last_runtime_write = 0.0

    @property
    def base_dir(self) -> Path:
        return self.config_path.parent

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

    def pair(self, address: str, code: str, client_name: str) -> dict[str, str]:
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
        return {"client_id": client_id, "token": token}

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

    def bridge_url(self, name: str, path: str) -> str:
        profile = self.config.get("profiles", {}).get(self.profile_id, {})
        base = str(profile.get(f"{name}_bridge", "")).rstrip("/")
        if not base and self.profile_id == "default":
            base = str(self.config[f"{name}_bridge"]).rstrip("/")
        if not base.startswith("http://127.0.0.1:") and not base.startswith("http://localhost:"):
            raise ValueError(f"{name} bridge must remain on loopback")
        return base + path

    def proxy(self, name: str, path: str, timeout: float = 2.5) -> tuple[bool, Any]:
        try:
            request = urllib.request.Request(self.bridge_url(name, path), method="GET")
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
            request = urllib.request.Request(self.bridge_url(name, path), method="GET")
            with urllib.request.urlopen(request, timeout=timeout) as response:
                length = int(response.headers.get("Content-Length", "0") or 0)
                if length < 1 or length > 8 * 1024 * 1024:
                    raise ValueError("Artwork response has an unsupported size.")
                body = response.read(8 * 1024 * 1024 + 1)
                if len(body) != length or len(body) > 8 * 1024 * 1024:
                    raise ValueError("Artwork response is incomplete or too large.")
                content_type = response.headers.get_content_type()
                if not content_type.startswith("image/"):
                    raise ValueError("Playnite Bridge returned non-image artwork.")
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
                headers={"Content-Type": "application/json; charset=utf-8"},
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
        playnite_ok, playnite = self.proxy("playnite", "/health", timeout=1.0)
        virtualhere_ok, virtualhere = (False, {"error": "Discord Bridge is offline."})
        if discord_ok:
            virtualhere_ok, virtualhere = self.proxy(
                "discord", "/virtualhere-state", timeout=1.5)
        return {
            "gateway": {
                "online": True,
                "api_version": 1,
                "integration_profile_id": self.profile_id,
            },
            "capabilities": {
                "vibepollo_fix": {"available": vibepollo_ok, "health": vibepollo},
                "vibepollo_apps": {"available": vibepollo_ok, "health": vibepollo},
                "playnite": {"available": playnite_ok, "health": playnite},
                "discord": {"available": discord_ok, "health": discord},
                "virtualhere": {
                    "available": virtualhere_ok and bool(virtualhere.get("installed", False)),
                    "health": virtualhere,
                },
                "host_sleep": {"available": os.name == "nt"},
            },
        }

    @staticmethod
    def _sleep_windows() -> None:
        command = (
            "Add-Type -AssemblyName System.Windows.Forms; "
            "[System.Windows.Forms.Application]::SetSuspendState("
            "[System.Windows.Forms.PowerState]::Suspend, $true, $false)"
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

    def _schedule_system_sleep(self) -> None:
        timer = threading.Timer(0.75, self._sleep_windows)
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
                    "playnite", "/health", timeout=1.0)
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
        ok, result = self.proxy_json("vibepollo", "/apps/ensure", {
            "playnite_game_id": game_id,
            "name": name,
        }, timeout=12.0)
        if not ok:
            return HTTPStatus.BAD_GATEWAY, {
                "ok": False,
                "error": self.upstream_error(
                    result, "Unable to create the Vibepollo application."),
            }
        app = result if isinstance(result, dict) else {}
        return (HTTPStatus.CREATED if app.get("created") else HTTPStatus.OK), {
            "ok": True,
            "app": app,
        }

    @staticmethod
    def _playnite_game_id(value: Any) -> str:
        result = str(value or "").strip()
        if not PLAYNITE_GAME_ID_PATTERN.fullmatch(result):
            raise ValueError("Invalid Playnite game ID.")
        return result.lower()

    def playnite_health(self) -> tuple[int, Any]:
        ok, result = self.proxy("playnite", "/health", timeout=1.5)
        return (HTTPStatus.OK if ok else HTTPStatus.SERVICE_UNAVAILABLE), {
            "ok": ok,
            "bridge": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Playnite Bridge is offline in this profile."),
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
        ok, result = self.proxy("playnite", path, timeout=8.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "library": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Unable to load the Playnite library."),
        }

    def playnite_artwork(self, game_id: Any, kind: Any) -> tuple[int, bytes, str]:
        normalized_id = str(game_id or "").strip()
        normalized_kind = str(kind or "cover").strip().lower()
        if not PLAYNITE_GAME_ID_PATTERN.fullmatch(normalized_id):
            raise ValueError("Invalid Playnite game ID.")
        if normalized_kind not in {"cover", "background", "icon"}:
            raise ValueError("Invalid artwork kind.")
        path = "/artwork?" + urllib.parse.urlencode({
            "game_id": normalized_id,
            "kind": normalized_kind,
        })
        return self.proxy_bytes("playnite", path, timeout=8.0)

    def playnite_state(self, resource: str) -> tuple[int, Any]:
        paths = {
            "current": "/game/current",
            "readiness": "/window/readiness",
        }
        if resource not in paths:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown Playnite resource."}
        ok, result = self.proxy("playnite", paths[resource], timeout=3.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            resource: result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Unable to read Playnite state."),
        }

    def playnite_events(self, after: Any, transition_id: Any = "") -> tuple[int, Any]:
        sequence = int(after or 0)
        if sequence < 0 or sequence > 9_223_372_036_854_775_807:
            raise ValueError("Invalid Playnite event sequence.")
        correlation = str(transition_id or "").strip()
        if len(correlation) > 128 or not re.fullmatch(r"[A-Za-z0-9._:-]*", correlation):
            raise ValueError("Invalid transition ID.")
        ok, result = self.proxy("playnite", "/events?" + urllib.parse.urlencode({
            "after": sequence,
        }), timeout=22.0)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "transition_id": correlation,
            "events": result if ok and isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Unable to read Playnite lifecycle events."),
        }

    def playnite_action(self, action: str, body: dict[str, Any]) -> tuple[int, Any]:
        if action == "game/start":
            payload = {"game_id": self._playnite_game_id(body.get("game_id"))}
            path = "/game/start"
            timeout = 15.0
        elif action == "game/stop":
            payload = {"force": False}
            if body.get("game_id"):
                payload["game_id"] = self._playnite_game_id(body.get("game_id"))
            path = "/game/stop"
            timeout = 15.0
        elif action == "game/focus":
            payload = {}
            path = "/game/focus"
            timeout = 6.0
        elif action == "show-fullscreen":
            payload = {}
            path = "/playnite/show-fullscreen"
            timeout = 10.0
        else:
            return HTTPStatus.NOT_FOUND, {"error": "Unknown Playnite action."}
        ok, result = self.proxy_json("playnite", path, payload, timeout=timeout)
        return (HTTPStatus.OK if ok else HTTPStatus.BAD_GATEWAY), {
            "ok": ok,
            "action": action,
            "result": result if isinstance(result, dict) else {},
            "error": "" if ok else self.upstream_error(
                result, "Playnite action failed."),
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
        print(f"{self.log_date_time_string()} {self.client_address[0]} {fmt % args}", flush=True)

    def send_json(self, status: int, value: Any) -> None:
        body = compact_json(value)
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def send_binary(self, status: int, body: bytes, content_type: str) -> None:
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

    def authenticated(self) -> bool:
        header = self.headers.get("Authorization", "")
        token = header[7:] if header.startswith("Bearer ") else ""
        return bool(token and self.state.client_for_token(token))

    def require_auth(self) -> bool:
        if self.authenticated():
            return True
        self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Authentication required."})
        return False

    def select_profile(self) -> str:
        return self.state.select_profile(
            self.headers.get("X-WakePlay-Profile", "default"), record_use=True)

    def do_GET(self) -> None:  # noqa: N802
        try:
            self._do_GET()
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except Exception as error:
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(error)})

    def _do_GET(self) -> None:
        target = urllib.parse.urlsplit(self.path)
        path = target.path
        query = urllib.parse.parse_qs(target.query, keep_blank_values=True)
        if path == f"{API_PREFIX}/hello":
            self.send_json(HTTPStatus.OK, {"name": "Wake & Play Host Gateway", "api_version": 1, "pairing": self.state.pairing_active()})
            return
        if not self.require_auth():
            return
        if path == f"{API_PREFIX}/profiles":
            self.send_json(HTTPStatus.OK, self.state.profiles_summary())
            return
        self.select_profile()
        if path == f"{API_PREFIX}/capabilities":
            self.send_json(HTTPStatus.OK, self.state.capabilities())
        elif path == f"{API_PREFIX}/vibepollo/repair/status":
            status, result = self.state.vibepollo_status()
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
        elif path == f"{API_PREFIX}/virtualhere/state":
            status, result = self.state.virtualhere_state(
                query.get("force", [""])[0].lower() == "true")
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/playnite/health":
            status, result = self.state.playnite_health()
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/playnite/library/list":
            status, result = self.state.playnite_library(
                query.get("cursor", [""])[0], query.get("limit", ["50"])[0])
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/playnite/artwork":
            status, body, content_type = self.state.playnite_artwork(
                query.get("game_id", [""])[0], query.get("kind", ["cover"])[0])
            if status == HTTPStatus.OK:
                self.send_binary(status, body, content_type)
            else:
                self.send_json(status, {"error": "Playnite artwork is unavailable."})
        elif path == f"{API_PREFIX}/playnite/game/current":
            status, result = self.state.playnite_state("current")
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/playnite/window/readiness":
            status, result = self.state.playnite_state("readiness")
            self.send_json(status, result)
        elif path == f"{API_PREFIX}/playnite/events":
            status, result = self.state.playnite_events(
                query.get("after", ["0"])[0],
                query.get("transition_id", [""])[0])
            self.send_json(status, result)
        else:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})

    def do_POST(self) -> None:  # noqa: N802
        try:
            path = urllib.parse.urlsplit(self.path).path
            if path == f"{API_PREFIX}/pair":
                body = self.read_json()
                result = self.state.pair(self.client_address[0], str(body.get("code", "")), str(body.get("client_name", "Android TV")))
                self.send_json(HTTPStatus.CREATED, result)
                return
            if not self.require_auth():
                return
            profile_id = self.select_profile()
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
            if path == f"{API_PREFIX}/vibepollo/apps/ensure":
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.idempotent(
                    f"{profile_id}:vibepollo-app:{request_id}",
                    lambda: self.state.vibepollo_ensure_app(body))
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
            if path.startswith(playnite_prefix):
                action = path[len(playnite_prefix):]
                request_id = self.headers.get("X-Request-Id", "").strip()
                if not request_id or len(request_id) > 128:
                    self.send_json(HTTPStatus.BAD_REQUEST, {
                        "error": "A valid X-Request-Id header is required."})
                    return
                body = self.read_json()
                status, result = self.state.idempotent(
                    f"{profile_id}:playnite:{request_id}",
                    lambda: self.state.playnite_action(action, body))
                self.send_json(status, result)
                return
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})
        except PermissionError as error:
            self.send_json(HTTPStatus.FORBIDDEN, {"error": str(error)})
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except Exception as error:  # keep the gateway alive on malformed upstream responses
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(error)})


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

    state = GatewayState(Path(args.config), args.pairing_code)
    server = GatewayServer((str(state.config["listen_host"]), int(state.config["listen_port"])), state)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(state.path_from_config("certificate"), state.path_from_config("private_key"))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"Wake & Play Host Gateway listening on https://{state.config['listen_host']}:{state.config['listen_port']}", flush=True)
    print("Pairing is active for 10 minutes." if args.pairing_code else "Pairing is disabled for this run.", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
