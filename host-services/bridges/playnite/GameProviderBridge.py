#!/usr/bin/env python3
"""Loopback game-provider bridge for catalog, operations, readiness, and Playnite IPC."""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import html
import json
import logging
import logging.handlers
import math
import mimetypes
import ntpath
import os
import queue
import re
import subprocess
import threading
import time
import traceback
import urllib.parse
import urllib.request
import uuid
from collections import deque
from ctypes import wintypes
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable

from GameOperations import (
    EpicProvider, GameOperationsService, GenericPlayniteProvider, SteamProvider,
)
from OperationJournal import ACTIVE_STATES, OperationJournal


PLAYNITE_ID_PATTERN = re.compile(
    r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
GAME_ID_PATTERN = re.compile(
    r"^(?:steam:[0-9]+|epic:[A-Za-z0-9_-]+|(?:playnite:)?[0-9A-Fa-f]{8}-"
    r"[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})$")
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
DIAGNOSTIC_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9._:$-]{1,256}$")
DIAGNOSTIC_ROUTE_PATTERN = re.compile(r"^/[A-Za-z0-9._:{}/-]{0,255}$")
DIAGNOSTIC_MAX_BYTES = 2 * 1024 * 1024
DIAGNOSTIC_BACKUP_COUNT = 9
DIAGNOSTIC_RETENTION_SECONDS = 7 * 24 * 60 * 60
DIAGNOSTIC_FIELDS = {"method", "route", "request_id", "profile_id", "status",
                     "http_status", "duration_ms", "sequence", "game_id", "state",
                     "previous_state", "reason", "kind", "operation", "operation_state",
                     "ready", "requires_attention", "accepted"}
MAX_BODY_BYTES = 16 * 1024
MAX_ARTWORK_BYTES = 8 * 1024 * 1024
MAX_ACTIVE_GAME_TRACE_BYTES = 16 * 1024
REQUIRED_STABLE_SAMPLES = 3
REQUIRED_GAME_STABLE_SAMPLES = 4
REQUIRED_LAUNCHER_STABLE_SAMPLES = 4
LAUNCHER_POSTCONDITION_TIMEOUT = 5.0
PROVIDER_LAUNCHER_PROBE_DELAY = 15.0
GAME_START_TIMEOUT = 60.0
GAME_STOP_TIMEOUT = 20.0
STEAM_PRIMARY_START_TIMEOUT = 30.0
STEAM_CANCELLATION_EVIDENCE_TIMEOUT = 20.0
OPERATION_AUDIT_LOCK = threading.Lock()
DISPLAY_NAME_PATTERN = re.compile(r"^(?:\\\\\.\\)?DISPLAY[0-9]+$", re.IGNORECASE)
PLAYNITE_UI_IMAGES = {
    "playnite.fullscreenapp.exe",
    "playnite.desktopapp.exe",
}
INSTALLER_IMAGES = {
    "steam.exe", "steamwebhelper.exe", "epicgameslauncher.exe", "galaxyclient.exe", "goggalaxy.exe",
    "ubisoftconnect.exe", "upc.exe", "eadesktop.exe", "ealauncher.exe",
    "origin.exe", "xboxpcapp.exe", "gamingservicesui.exe", "msiexec.exe",
}
INSTALLER_EXCLUDED_IMAGES = PLAYNITE_UI_IMAGES | {
    "explorer.exe", "searchhost.exe", "searchapp.exe", "shellexperiencehost.exe",
    "startmenuexperiencehost.exe", "textinputhost.exe", "lockapp.exe",
}


def diagnostic_route(target: str) -> str:
    route = target.split("?", 1)[0].split("#", 1)[0]
    return route if DIAGNOSTIC_ROUTE_PATTERN.fullmatch(route) else ""


class _DiagnosticQueueHandler(logging.handlers.QueueHandler):
    def __init__(self, records: queue.Queue, owner: "ProviderDiagnostics") -> None:
        super().__init__(records)
        self.owner = owner

    def enqueue(self, record: logging.LogRecord) -> None:
        try:
            self.queue.put_nowait(record)
        except (queue.Full, OSError):
            self.owner.dropped += 1

    def handleError(self, record: logging.LogRecord) -> None:  # noqa: N802
        self.owner.dropped += 1


class _DiagnosticFileHandler(logging.handlers.RotatingFileHandler):
    def __init__(self, filename: Path, owner: "ProviderDiagnostics") -> None:
        self.owner = owner
        super().__init__(filename, maxBytes=DIAGNOSTIC_MAX_BYTES,
                         backupCount=DIAGNOSTIC_BACKUP_COUNT,
                         encoding="utf-8", delay=True)

    def handleError(self, record: logging.LogRecord) -> None:  # noqa: N802
        self.owner.dropped += 1


class ProviderDiagnostics:
    def __init__(self) -> None:
        self.dropped = 0
        self.run_id = uuid.uuid4().hex
        self.started = time.monotonic()
        self.logger = self.listener = self.records = None

    def start(self, log_dir: Path) -> None:
        if self.listener is not None:
            return
        try:
            log_dir.mkdir(parents=True, exist_ok=True)
            cutoff = time.time() - DIAGNOSTIC_RETENTION_SECONDS
            for path in log_dir.glob("provider-diagnostics.jsonl*"):
                if not re.fullmatch(r"provider-diagnostics\.jsonl(?:\.\d+)?", path.name):
                    continue
                try:
                    if path.stat().st_mtime < cutoff:
                        path.unlink()
                except OSError:
                    self.dropped += 1
            records = queue.Queue(maxsize=512)
            output = _DiagnosticFileHandler(log_dir / "provider-diagnostics.jsonl", self)
            output.setFormatter(logging.Formatter("%(message)s"))
            logger = logging.Logger(f"moonwaker.provider.{self.run_id}", logging.DEBUG)
            logger.propagate = False
            logger.addHandler(_DiagnosticQueueHandler(records, self))
            listener = logging.handlers.QueueListener(records, output)
            listener.start()
            self.records, self.logger, self.listener = records, logger, listener
        except (OSError, RuntimeError, ValueError):
            self.dropped += 1

    def close(self) -> None:
        listener, records = self.listener, self.records
        self.listener = self.logger = self.records = None
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
               error: BaseException | None = None, frames: bool = False,
               **fields: Any) -> None:
        if self.logger is None:
            return
        try:
            if not DIAGNOSTIC_TOKEN_PATTERN.fullmatch(event):
                return
            entry: dict[str, Any] = {
                "v": 1, "ts": datetime.now(timezone.utc).isoformat(
                    timespec="milliseconds").replace("+00:00", "Z"),
                "mono_ms": int((time.monotonic() - self.started) * 1000),
                "level": level if level in {"INFO", "WARN", "ERROR"} else "INFO",
                "component": "host.game-provider", "event": event, "run_id": self.run_id}
            for key, value in fields.items():
                if key not in DIAGNOSTIC_FIELDS:
                    continue
                if key in {"ready", "requires_attention", "accepted"} and \
                        isinstance(value, bool):
                    entry[key] = value
                elif key in {"http_status", "duration_ms", "sequence"} and \
                        isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                    entry[key] = value
                elif isinstance(value, str) and ((key == "route" and
                        DIAGNOSTIC_ROUTE_PATTERN.fullmatch(value)) or
                        (key != "route" and DIAGNOSTIC_TOKEN_PATTERN.fullmatch(value))):
                    entry[key] = value
            if error is not None:
                error_type = f"{type(error).__module__}.{type(error).__qualname__}"
                if DIAGNOSTIC_TOKEN_PATTERN.fullmatch(error_type):
                    entry["error_type"] = error_type
                if frames:
                    entry["frames"] = self._safe_frames(error)
            self.logger.info(json.dumps(entry, ensure_ascii=False, separators=(",", ":")))
        except Exception:
            self.dropped += 1

    def lifecycle(self, event: str, sequence: int, profile_id: str,
                  request_id: Any, payload: dict[str, Any]) -> None:
        if self.logger is None:
            return
        fields: dict[str, Any] = {"sequence": sequence, "profile_id": profile_id}
        if isinstance(request_id, str) and REQUEST_ID_PATTERN.fullmatch(request_id):
            fields["request_id"] = request_id
        game_id = payload.get("game_id", payload.get("id"))
        if isinstance(game_id, str) and GAME_ID_PATTERN.fullmatch(game_id):
            fields["game_id"] = game_id
        for key in ("state", "previous_state", "reason", "kind", "operation",
                    "operation_state"):
            value = payload.get(key)
            if isinstance(value, str) and DIAGNOSTIC_TOKEN_PATTERN.fullmatch(value):
                fields[key] = value
        for key in ("ready", "requires_attention", "accepted"):
            if isinstance(payload.get(key), bool):
                fields[key] = payload[key]
        self.record(event, **fields)

    @staticmethod
    def _safe_frames(error: BaseException) -> list[dict[str, Any]]:
        result = []
        for frame in traceback.extract_tb(error.__traceback__, limit=16):
            filename, function = Path(frame.filename).name[:120], frame.name[:120]
            result.append({"file": filename if re.fullmatch(r"[A-Za-z0-9_.-]+", filename)
                           else "unknown", "function": function if re.fullmatch(
                               r"[A-Za-z0-9_.$<>-]+", function) else "unknown",
                           "line": frame.lineno})
        return result


DIAGNOSTICS = ProviderDiagnostics()


class StreamDisplayResolver:
    DISPLAY_KEYS = {
        "display", "display_name", "displayname", "monitor", "monitor_name",
        "output", "output_name", "outputname", "output_name_override",
    }

    def __init__(self, vibepollo_bridge: str) -> None:
        self.endpoint = vibepollo_bridge.rstrip("/") + "/diagnostics/stream-sources" \
            if vibepollo_bridge else ""
        self.last_check = 0.0
        self.cached = ""

    @staticmethod
    def _normalize(value: Any) -> str:
        text = str(value or "").strip()
        if not DISPLAY_NAME_PATTERN.fullmatch(text):
            return ""
        if text.upper().startswith("DISPLAY"):
            return "\\\\.\\" + text.upper()
        return text.upper()

    @classmethod
    def displays_from_payload(cls, payload: Any) -> list[str]:
        found: set[str] = set()

        def visit(value: Any, key: str = "") -> None:
            if isinstance(value, dict):
                for child_key, child in value.items():
                    visit(child, str(child_key).casefold())
            elif isinstance(value, list):
                for child in value:
                    visit(child, key)
            elif key in cls.DISPLAY_KEYS:
                normalized = cls._normalize(value)
                if normalized:
                    found.add(normalized)

        visit(payload)
        return sorted(found)

    def resolve(self) -> str:
        now = time.monotonic()
        if not self.endpoint or now - self.last_check < 1.0:
            return self.cached
        self.last_check = now
        try:
            with urllib.request.urlopen(self.endpoint, timeout=0.75) as response:
                payload = json.loads(response.read(256 * 1024).decode("utf-8-sig"))
            displays = self.displays_from_payload(payload)
            self.cached = displays[0] if len(displays) == 1 else ""
        except Exception:
            pass
        return self.cached


class WindowProbe:
    """Collects Win32 evidence; it never treats the desktop as a valid target."""

    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    MONITOR_DEFAULTTONEAREST = 2
    DWMWA_CLOAKED = 14
    DESKTOP_SWITCHDESKTOP = 0x0100
    WM_CLOSE = 0x0010
    SW_RESTORE = 9
    TH32CS_SNAPPROCESS = 0x00000002

    def __init__(self, game_operations: GameOperationsService) -> None:
        self.game_operations = game_operations
        self.launcher_automation_path = Path(__file__).with_name(
            "Invoke-GameLauncher.ps1")
        self.user32 = None
        self.kernel32 = None
        self.dwmapi = None
        self.advapi32 = None
        if os.name == "nt":
            self.user32 = ctypes.WinDLL("user32", use_last_error=True)
            self.kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            self.advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
            self.advapi32.OpenProcessToken.argtypes = [
                wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
            self.advapi32.OpenProcessToken.restype = wintypes.BOOL
            self.advapi32.GetTokenInformation.argtypes = [
                wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD,
                ctypes.POINTER(wintypes.DWORD)]
            self.advapi32.GetTokenInformation.restype = wintypes.BOOL
            self.advapi32.ConvertSidToStringSidW.argtypes = [
                wintypes.LPVOID, ctypes.POINTER(wintypes.LPWSTR)]
            self.advapi32.ConvertSidToStringSidW.restype = wintypes.BOOL
            self.kernel32.LocalFree.argtypes = [wintypes.HANDLE]
            self.kernel32.LocalFree.restype = wintypes.HANDLE
            self.kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
            self.kernel32.CloseHandle.restype = wintypes.BOOL
            self.kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            self.kernel32.WaitForSingleObject.restype = wintypes.DWORD
            self.user32.GetForegroundWindow.restype = wintypes.HWND
            self.user32.OpenInputDesktop.argtypes = [
                wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            self.user32.OpenInputDesktop.restype = wintypes.HANDLE
            self.user32.SwitchDesktop.argtypes = [wintypes.HANDLE]
            self.user32.SwitchDesktop.restype = wintypes.BOOL
            self.user32.CloseDesktop.argtypes = [wintypes.HANDLE]
            self.user32.CloseDesktop.restype = wintypes.BOOL
            self.user32.IsWindowVisible.argtypes = [wintypes.HWND]
            self.user32.IsWindowVisible.restype = wintypes.BOOL
            self.user32.IsWindow.argtypes = [wintypes.HWND]
            self.user32.IsWindow.restype = wintypes.BOOL
            self.user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
            self.user32.GetWindowTextLengthW.restype = ctypes.c_int
            self.user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR,
                                                    ctypes.c_int]
            self.user32.GetWindowTextW.restype = ctypes.c_int
            self.user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
            self.user32.GetClassNameW.restype = ctypes.c_int
            self.user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND,
                                                              ctypes.POINTER(wintypes.DWORD)]
            self.user32.GetWindowThreadProcessId.restype = wintypes.DWORD
            self.user32.AttachThreadInput.argtypes = [
                wintypes.DWORD, wintypes.DWORD, wintypes.BOOL]
            self.user32.AttachThreadInput.restype = wintypes.BOOL
            self.user32.BringWindowToTop.argtypes = [wintypes.HWND]
            self.user32.BringWindowToTop.restype = wintypes.BOOL
            self.user32.SetActiveWindow.argtypes = [wintypes.HWND]
            self.user32.SetActiveWindow.restype = wintypes.HWND
            self.user32.SetFocus.argtypes = [wintypes.HWND]
            self.user32.SetFocus.restype = wintypes.HWND
            self.user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
            self.user32.GetWindowRect.restype = wintypes.BOOL
            self.user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT,
                                                  wintypes.WPARAM, wintypes.LPARAM]
            self.user32.PostMessageW.restype = wintypes.BOOL
            self.user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
            self.user32.ShowWindow.restype = wintypes.BOOL
            self.user32.SetForegroundWindow.argtypes = [wintypes.HWND]
            self.user32.SetForegroundWindow.restype = wintypes.BOOL
            self.user32.MonitorFromWindow.argtypes = [wintypes.HWND, wintypes.DWORD]
            self.user32.MonitorFromWindow.restype = wintypes.HANDLE
            self.user32.GetMonitorInfoW.argtypes = [wintypes.HANDLE, wintypes.LPVOID]
            self.user32.GetMonitorInfoW.restype = wintypes.BOOL
            self.kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            self.kernel32.OpenProcess.restype = wintypes.HANDLE
            self.kernel32.QueryFullProcessImageNameW.argtypes = [
                wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR,
                ctypes.POINTER(wintypes.DWORD)]
            self.kernel32.QueryFullProcessImageNameW.restype = wintypes.BOOL
            self.kernel32.GetProcessTimes.argtypes = [
                wintypes.HANDLE, ctypes.POINTER(wintypes.FILETIME),
                ctypes.POINTER(wintypes.FILETIME), ctypes.POINTER(wintypes.FILETIME),
                ctypes.POINTER(wintypes.FILETIME)]
            self.kernel32.GetProcessTimes.restype = wintypes.BOOL
            self.kernel32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
            self.kernel32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
            try:
                self.dwmapi = ctypes.WinDLL("dwmapi", use_last_error=True)
                self.dwmapi.DwmGetWindowAttribute.argtypes = [
                    wintypes.HWND, wintypes.DWORD, wintypes.LPVOID, wintypes.DWORD]
                self.dwmapi.DwmGetWindowAttribute.restype = wintypes.LONG
            except OSError:
                pass

    def _process_path(self, process_id: int) -> str:
        if not self.kernel32:
            return ""
        handle = self.kernel32.OpenProcess(
            self.PROCESS_QUERY_LIMITED_INFORMATION, False, process_id)
        if not handle:
            return ""
        try:
            size = wintypes.DWORD(32768)
            buffer = ctypes.create_unicode_buffer(size.value)
            if not self.kernel32.QueryFullProcessImageNameW(
                    handle, 0, buffer, ctypes.byref(size)):
                return ""
            return buffer.value
        finally:
            self.kernel32.CloseHandle(handle)

    def _process_image(self, process_id: int) -> str:
        return os.path.basename(self._process_path(process_id)).casefold()

    def process_identity(self, process_id: int, include_owner: bool = False) -> dict[str, Any] | None:
        if not self.kernel32 or process_id <= 0:
            return None
        handle = self.kernel32.OpenProcess(
            self.PROCESS_QUERY_LIMITED_INFORMATION, False, process_id)
        if not handle:
            return None
        try:
            size = wintypes.DWORD(32768)
            buffer = ctypes.create_unicode_buffer(size.value)
            created = wintypes.FILETIME()
            exited = wintypes.FILETIME()
            kernel = wintypes.FILETIME()
            user = wintypes.FILETIME()
            if not self.kernel32.QueryFullProcessImageNameW(
                    handle, 0, buffer, ctypes.byref(size)) or \
                    not self.kernel32.GetProcessTimes(
                        handle, ctypes.byref(created), ctypes.byref(exited),
                        ctypes.byref(kernel), ctypes.byref(user)):
                return None
            started = (int(created.dwHighDateTime) << 32) | int(created.dwLowDateTime)
            identity = {
                "process_id": process_id,
                "process_path": buffer.value,
                "process_started_filetime": started,
            }
            if include_owner:
                owner = self._process_owner(handle)
                if owner is None:
                    return None
                identity.update(owner)
            return identity
        finally:
            self.kernel32.CloseHandle(handle)

    def _process_owner(self, process_handle: Any) -> dict[str, Any] | None:
        token = wintypes.HANDLE()
        if not self.advapi32 or not self.advapi32.OpenProcessToken(
                process_handle, 0x0008, ctypes.byref(token)):
            return None
        try:
            size = wintypes.DWORD()
            self.advapi32.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))
            if not size.value or size.value > 65536:
                return None
            user = ctypes.create_string_buffer(size.value)
            session = wintypes.DWORD()
            if not self.advapi32.GetTokenInformation(
                    token, 1, user, size.value, ctypes.byref(size)) or \
                    not self.advapi32.GetTokenInformation(
                        token, 12, ctypes.byref(session), ctypes.sizeof(session), ctypes.byref(size)):
                return None
            sid = wintypes.LPWSTR()
            if not self.advapi32.ConvertSidToStringSidW(
                    ctypes.cast(user, ctypes.POINTER(ctypes.c_void_p))[0], ctypes.byref(sid)):
                return None
            try:
                return {"user_sid": sid.value, "session_id": int(session.value)}
            finally:
                self.kernel32.LocalFree(ctypes.cast(sid, wintypes.HANDLE))
        finally:
            self.kernel32.CloseHandle(token)

    def scan_running_processes(self) -> tuple[list[dict[str, Any]], str]:
        own = self.process_identity(os.getpid(), include_owner=True)
        if own is None or not self.user32:
            return [], "unavailable"
        snapshot = self.kernel32.CreateToolhelp32Snapshot(self.TH32CS_SNAPPROCESS, 0)
        if not snapshot or snapshot == ctypes.c_void_p(-1).value:
            return [], "unavailable"
        visible_pids: set[int] = set()
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def visit(hwnd: int, _value: int) -> bool:
            if self.user32.IsWindowVisible(hwnd):
                pid = wintypes.DWORD()
                self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                visible_pids.add(int(pid.value))
            return True

        result: list[dict[str, Any]] = []
        status = "complete"
        deadline = time.monotonic() + 0.25
        entry = self._process_entry()
        try:
            if not self.user32.EnumWindows(callback_type(visit), 0):
                return [], "unavailable"
            if not self.kernel32.Process32FirstW(snapshot, ctypes.byref(entry)):
                return [], "unavailable"
            while True:
                pid = int(entry.th32ProcessID)
                identity = self.process_identity(pid, include_owner=True) if pid > 4 else None
                if identity is None:
                    if pid > 4:
                        status = "partial"
                elif identity["user_sid"] == own["user_sid"] \
                        and identity["session_id"] == own["session_id"]:
                    identity["visible_window"] = pid in visible_pids
                    result.append(identity)
                if time.monotonic() >= deadline:
                    return [], "unavailable"
                if not self.kernel32.Process32NextW(snapshot, ctypes.byref(entry)):
                    if ctypes.get_last_error() != 18:  # ERROR_NO_MORE_FILES
                        return [], "unavailable"
                    break
            return result, status
        finally:
            self.kernel32.CloseHandle(snapshot)

    def stop_verified_process(self, expected: dict[str, Any], timeout: float) -> bool:
        if not self.kernel32 or not self.user32 or self.is_session_locked() \
                or self.uac_consent_pending(fail_closed=True):
            return False
        pid = int(expected["process_id"])
        handle = self.kernel32.OpenProcess(
            self.PROCESS_QUERY_LIMITED_INFORMATION | 0x00100000, False, pid)
        if not handle:
            return False
        try:
            own = self.process_identity(os.getpid(), include_owner=True)
            fresh = self.process_identity(pid, include_owner=True)
            keys = ("process_id", "process_started_filetime", "user_sid", "session_id")
            if own is None or fresh is None or any(fresh.get(key) != expected.get(key) for key in keys) \
                    or fresh["user_sid"] != own["user_sid"] \
                    or fresh["session_id"] != own["session_id"] \
                    or ntpath.normcase(ntpath.normpath(fresh["process_path"])) != \
                    ntpath.normcase(ntpath.normpath(expected["process_path"])):
                return False
            if self.kernel32.WaitForSingleObject(handle, 0) != 258:  # WAIT_TIMEOUT = alive
                return False
            windows = self._matching_windows(process_id=pid)
            if not windows:
                return False
            posted = False
            for hwnd in windows:
                if self.kernel32.WaitForSingleObject(handle, 0) == 0:
                    return posted
                window_pid = wintypes.DWORD()
                self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(window_pid))
                if int(window_pid.value) == pid \
                        and self.kernel32.WaitForSingleObject(handle, 0) == 258:
                    posted = bool(self.user32.PostMessageW(hwnd, self.WM_CLOSE, 0, 0)) or posted
            return posted and self.kernel32.WaitForSingleObject(
                handle, int(max(0, timeout) * 1000)) == 0
        finally:
            self.kernel32.CloseHandle(handle)

    def process_identities(self, exact_path: str) -> list[dict[str, Any]] | None:
        if not self.kernel32:
            return None
        if not exact_path:
            return []
        expected = ntpath.normcase(ntpath.normpath(exact_path))
        snapshot = self.kernel32.CreateToolhelp32Snapshot(self.TH32CS_SNAPPROCESS, 0)
        if not snapshot or snapshot == ctypes.c_void_p(-1).value:
            return None
        result: list[dict[str, Any]] = []
        entry = self._process_entry()
        try:
            if self.kernel32.Process32FirstW(snapshot, ctypes.byref(entry)):
                while True:
                    identity = self.process_identity(int(entry.th32ProcessID))
                    if identity and ntpath.normcase(ntpath.normpath(
                            str(identity.get("process_path") or ""))) == expected:
                        result.append(identity)
                    if not self.kernel32.Process32NextW(snapshot, ctypes.byref(entry)):
                        break
        finally:
            self.kernel32.CloseHandle(snapshot)
        return result

    @staticmethod
    def _process_entry():
        class ProcessEntry(ctypes.Structure):
            _fields_ = [
                ("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
                ("th32ProcessID", wintypes.DWORD),
                ("th32DefaultHeapID", ctypes.c_size_t),
                ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
                ("th32ParentProcessID", wintypes.DWORD),
                ("pcPriClassBase", wintypes.LONG), ("dwFlags", wintypes.DWORD),
                ("szExeFile", wintypes.WCHAR * 260),
            ]
        entry = ProcessEntry()
        entry.dwSize = ctypes.sizeof(entry)
        return entry

    def _exact_process_running(self, executable: Path) -> bool:
        if not self.kernel32:
            return False

        class ProcessEntry(ctypes.Structure):
            _fields_ = [
                ("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
                ("th32ProcessID", wintypes.DWORD), ("th32DefaultHeapID", ctypes.c_size_t),
                ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
                ("th32ParentProcessID", wintypes.DWORD), ("pcPriClassBase", wintypes.LONG),
                ("dwFlags", wintypes.DWORD), ("szExeFile", wintypes.WCHAR * 260),
            ]

        expected = ntpath.normcase(ntpath.normpath(str(executable)))
        snapshot = self.kernel32.CreateToolhelp32Snapshot(self.TH32CS_SNAPPROCESS, 0)
        if not snapshot or snapshot == ctypes.c_void_p(-1).value:
            return False
        entry = ProcessEntry()
        entry.dwSize = ctypes.sizeof(entry)
        try:
            if self.kernel32.Process32FirstW(snapshot, ctypes.byref(entry)):
                while True:
                    process_path = self._process_path(int(entry.th32ProcessID))
                    if str(entry.szExeFile).casefold() == executable.name.casefold() \
                            and ntpath.normcase(ntpath.normpath(process_path)) == expected:
                        return True
                    if not self.kernel32.Process32NextW(snapshot, ctypes.byref(entry)):
                        break
        finally:
            self.kernel32.CloseHandle(snapshot)
        return False

    def _process_tree(self, root_process_id: int) -> set[int]:
        if not self.kernel32 or root_process_id <= 0:
            return set()

        class ProcessEntry(ctypes.Structure):
            _fields_ = [
                ("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
                ("th32ProcessID", wintypes.DWORD), ("th32DefaultHeapID", ctypes.c_size_t),
                ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
                ("th32ParentProcessID", wintypes.DWORD), ("pcPriClassBase", wintypes.LONG),
                ("dwFlags", wintypes.DWORD), ("szExeFile", wintypes.WCHAR * 260),
            ]

        snapshot = self.kernel32.CreateToolhelp32Snapshot(self.TH32CS_SNAPPROCESS, 0)
        if not snapshot or snapshot == ctypes.c_void_p(-1).value:
            return {root_process_id}
        parents: dict[int, int] = {}
        entry = ProcessEntry()
        entry.dwSize = ctypes.sizeof(entry)
        try:
            if self.kernel32.Process32FirstW(snapshot, ctypes.byref(entry)):
                while True:
                    parents[int(entry.th32ProcessID)] = int(entry.th32ParentProcessID)
                    if not self.kernel32.Process32NextW(snapshot, ctypes.byref(entry)):
                        break
        finally:
            self.kernel32.CloseHandle(snapshot)
        result = {root_process_id}
        changed = True
        while changed:
            changed = False
            for child, parent in parents.items():
                if parent in result and child not in result:
                    result.add(child)
                    changed = True
        if root_process_id not in parents:
            result.discard(root_process_id)
        return result

    def _window_title(self, hwnd: int) -> str:
        if not self.user32:
            return ""
        length = int(self.user32.GetWindowTextLengthW(hwnd) or 0)
        if length <= 0 or length > 4096:
            return ""
        buffer = ctypes.create_unicode_buffer(length + 1)
        copied = int(self.user32.GetWindowTextW(hwnd, buffer, len(buffer)) or 0)
        return buffer.value[:copied].strip() if copied > 0 else ""

    def _window_class(self, hwnd: int) -> str:
        if not self.user32:
            return ""
        buffer = ctypes.create_unicode_buffer(256)
        copied = int(self.user32.GetClassNameW(hwnd, buffer, len(buffer)) or 0)
        return buffer.value[:copied].strip() if copied > 0 else ""

    def is_session_locked(self) -> bool:
        if not self.user32:
            return True
        desktop = self.user32.OpenInputDesktop(
            0, False, self.DESKTOP_SWITCHDESKTOP)
        if not desktop:
            return True
        try:
            return not bool(self.user32.SwitchDesktop(desktop))
        finally:
            self.user32.CloseDesktop(desktop)

    @staticmethod
    def uac_consent_pending(fail_closed: bool = False) -> bool:
        if os.name != "nt":
            return fail_closed
        try:
            result = subprocess.run([
                "tasklist.exe", "/FI", "IMAGENAME eq consent.exe", "/NH", "/FO", "CSV",
            ], capture_output=True, text=True, timeout=2,
                creationflags=subprocess.CREATE_NO_WINDOW, check=False)
            if result.returncode != 0:
                return fail_closed
            return '"consent.exe"' in result.stdout.casefold()
        except (OSError, subprocess.SubprocessError):
            return fail_closed

    @staticmethod
    def is_playnite_ui_image(image_name: str) -> bool:
        # Recent Playnite versions can hand Fullscreen activation to the already
        # running DesktopApp process and then exit FullscreenApp. Both images are
        # therefore valid owners of the foreground Playnite UI.
        return image_name.casefold() in PLAYNITE_UI_IMAGES

    @staticmethod
    def belongs_to_install_directory(process_path: str, install_directory: str) -> bool:
        if not process_path or not install_directory:
            return False
        try:
            process = ntpath.normcase(ntpath.normpath(process_path))
            directory = ntpath.normcase(ntpath.normpath(install_directory))
            return ntpath.commonpath([process, directory]) == directory and process != directory
        except ValueError:
            return False

    def _matching_windows(self, process_id: int = 0,
                          image_name: str = "") -> list[int]:
        if not self.user32:
            return []
        result: list[int] = []
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def visit(hwnd: int, _lparam: int) -> bool:
            pid = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            if process_id and int(pid.value) != process_id:
                return True
            if image_name and self._process_image(int(pid.value)) != image_name.casefold():
                return True
            result.append(int(hwnd))
            return True

        self.user32.EnumWindows(callback_type(visit), 0)
        return result

    def request_graceful_close(self, process_id: int) -> bool:
        if not self.user32 or process_id <= 0:
            return False
        windows = self._matching_windows(process_id=process_id)
        for hwnd in windows:
            self.user32.PostMessageW(hwnd, self.WM_CLOSE, 0, 0)
        return bool(windows)

    def interactive_windows(self, include_excluded_images: bool = False) -> list[dict[str, Any]]:
        """Return visible user-facing top-level windows without interpreting their UI."""
        if not self.user32:
            return []
        foreground = int(self.user32.GetForegroundWindow() or 0)
        result: list[dict[str, Any]] = []
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def visit(hwnd: int, _lparam: int) -> bool:
            if not self.user32.IsWindowVisible(hwnd):
                return True
            title = self._window_title(hwnd)
            if not title:
                return True
            cloaked = wintypes.DWORD()
            if self.dwmapi:
                self.dwmapi.DwmGetWindowAttribute(
                    hwnd, self.DWMWA_CLOAKED, ctypes.byref(cloaked), ctypes.sizeof(cloaked))
            rect = wintypes.RECT()
            if cloaked.value or not self.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
                return True
            width, height = rect.right - rect.left, rect.bottom - rect.top
            if width < 240 or height < 120:
                return True
            pid = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            process_id = int(pid.value)
            process_path = self._process_path(process_id)
            image = os.path.basename(process_path).casefold()
            if not image or image in INSTALLER_EXCLUDED_IMAGES \
                    and not include_excluded_images:
                return True
            display, monitor_bounds = self._monitor_details(hwnd)
            result.append({
                "hwnd": int(hwnd), "process_id": process_id, "image": image,
                "process_path": process_path, "title": title[:300],
                "foreground": int(hwnd) == foreground, "display": display,
                "bounds": [rect.left, rect.top, rect.right, rect.bottom],
                "monitor_bounds": monitor_bounds,
            })
            return True

        self.user32.EnumWindows(callback_type(visit), 0)
        return result

    def installation_baseline(self) -> dict[str, Any]:
        windows = self.interactive_windows()
        foreground = next((item["hwnd"] for item in windows if item["foreground"]), 0)
        return {
            "captured_at": time.time(),
            "foreground_hwnd": foreground,
            "windows": {str(item["hwnd"]): {
                "process_id": item["process_id"], "image": item["image"],
                "title": item["title"],
            } for item in windows},
        }

    @staticmethod
    def installation_candidate_score(baseline: dict[str, Any],
                                     candidate: dict[str, Any],
                                     expected_images: set[str]) -> int:
        previous_windows = baseline.get("windows") or {}
        previous = previous_windows.get(str(candidate.get("hwnd") or 0))
        image = str(candidate.get("image") or "").casefold()
        title = str(candidate.get("title") or "").casefold()
        # Only windows owned by supported launchers can confirm an operation.
        # Matching generic words such as "installer" attributed MoonWaker's own
        # installer (and unrelated setup windows) to a pending game operation.
        known_launcher = image in expected_images
        new_window = previous is None
        changed_title = previous is not None and str(previous.get("title") or "") != \
            str(candidate.get("title") or "")
        foreground = bool(candidate.get("foreground"))
        changed_foreground = foreground and int(candidate.get("hwnd") or 0) != \
            int(baseline.get("foreground_hwnd") or 0)
        bounds = list(candidate.get("bounds") or [])
        monitor = list(candidate.get("monitor_bounds") or [])
        dialog_sized = not WindowProbe.fills_monitor(bounds, monitor, .92)
        if not (new_window or changed_title or changed_foreground):
            return 0
        score = (3 if known_launcher else 0) + (4 if new_window else 0) + \
            (3 if changed_title else 0) + (2 if foreground else 0) + \
            (2 if changed_foreground else 0) + (1 if dialog_sized else 0)
        # A newly focused application is not evidence of an installation prompt.
        # Without launcher affinity this used to classify unrelated IntelliJ and
        # browser windows as Steam/Epic confirmation dialogs.
        if not known_launcher:
            return 0
        return score

    def installation_prompt(self, baseline: dict[str, Any]) -> dict[str, Any]:
        game = baseline.get("game") or {}
        operation = str(baseline.get("operation") or "install")
        manual = self.game_operations.manual_attention(game, operation)
        if manual:
            return {"requires_attention": False, "reason": str(manual["reason"])}
        is_epic = self.game_operations.provider_for(game) is self.game_operations.epic
        if is_epic:
            return self.game_operations.sample(baseline) or {
                "provider": "epic", "requires_attention": False,
                "reason": "operation_observation_pending",
            }
        # UAC switches Windows to the secure desktop. A pre-existing Epic manifest
        # must not complete the operation while consent is still waiting.
        if self.is_session_locked() or self.uac_consent_pending():
            return {"requires_attention": True, "reason": "secure_desktop",
                    "hwnd": 0, "title": "", "image": ""}
        provider_sample = self.game_operations.sample(baseline)
        if provider_sample and (any(provider_sample.get(key) for key in (
                "installed", "uninstalled", "started")) or
                bool(provider_sample.get("requires_attention"))):
            return provider_sample
        if not self.user32:
            return provider_sample or {
                "requires_attention": False, "reason": "window_probe_unavailable"}
        expected_images = self.game_operations.expected_launcher_images(game)
        rejected_hwnds: set[int] = set()
        if expected_images:
            provider_windows = [window for window in self.interactive_windows()
                                if str(window.get("image") or "").casefold()
                                in expected_images]
            provider_windows.sort(
                key=lambda window: bool(window.get("foreground")), reverse=True)
            previous_windows = baseline.get("windows") or {}
            for window in provider_windows:
                previous = previous_windows.get(str(window.get("hwnd") or 0)) or {}
                changed = not previous or str(previous.get("title") or "") !=                     str(window.get("title") or "") or int(
                        previous.get("process_id") or 0) != int(window.get("process_id") or 0)
                visual_candidate = is_epic and changed and bool(window.get("foreground"))                     and int(window.get("hwnd") or 0) > 0                     and int(window.get("process_id") or 0) > 0                     and bool(str(game.get("name") or "").strip())
                recognized = self.game_operations.confirm_operation(
                    game,
                    int(window.get("hwnd") or 0), operation,
                    str(game.get("name") or ""),
                    allow_visual_fallback=visual_candidate, probe_only=True)
                if recognized.get("recognized"):
                    visual_safe = visual_candidate and                         recognized.get("method") == "visual" and                         bool(recognized.get("visual_confirmation_safe"))
                    return {"requires_attention": True, "reason": "launcher_prompt",
                            "visual_confirmation_safe": visual_safe, **window}
                if is_epic:
                    rejected_hwnds.add(int(window.get("hwnd") or 0))
        candidates = []
        for candidate in self.interactive_windows():
            if int(candidate.get("hwnd") or 0) in rejected_hwnds:
                continue
            score = self.installation_candidate_score(
                baseline, candidate, expected_images)
            if score >= 5:
                candidates.append((score, candidate))
        if not candidates:
            return provider_sample or {
                "requires_attention": False, "reason": "no_prompt"}
        _score, selected = max(candidates, key=lambda value: (
            value[0], bool(value[1].get("foreground")),
            int(value[1].get("hwnd") or 0)))
        return {"requires_attention": True, "reason": "launcher_prompt",
                "visual_confirmation_safe": False,
                **selected}

    def focus_installation_window(self, hwnd: int) -> dict[str, Any]:
        if not self.user32 or hwnd <= 0 or not self.user32.IsWindow(hwnd):
            raise RuntimeError("The installation window is no longer available.")
        if self.is_session_locked():
            raise PermissionError("The installation requires confirmation on the PC.")
        current_thread = int(self.kernel32.GetCurrentThreadId())
        target_thread = int(self.user32.GetWindowThreadProcessId(hwnd, None))
        foreground = int(self.user32.GetForegroundWindow() or 0)
        foreground_thread = int(
            self.user32.GetWindowThreadProcessId(foreground, None)) if foreground else 0
        attached: list[int] = []
        for thread_id in {target_thread, foreground_thread}:
            if thread_id and thread_id != current_thread and self.user32.AttachThreadInput(
                    current_thread, thread_id, True):
                attached.append(thread_id)
        try:
            self.user32.ShowWindow(hwnd, self.SW_RESTORE)
            self.user32.BringWindowToTop(hwnd)
            self.user32.SetActiveWindow(hwnd)
            self.user32.SetFocus(hwnd)
            focused = bool(self.user32.SetForegroundWindow(hwnd))
        finally:
            for thread_id in reversed(attached):
                self.user32.AttachThreadInput(current_thread, thread_id, False)
        return {"focused": focused, "hwnd": hwnd, "title": self._window_title(hwnd)}

    def focus_game_window(self, process_id: int, install_directory: str,
                          expected_display: str) -> dict[str, Any]:
        sample = self.sample("game", process_id, expected_display, install_directory)
        if sample.get("reason") == "host_session_locked":
            raise PermissionError("The Windows session is locked.")
        hwnd = int(sample.get("hwnd") or 0)
        if not hwnd or str(sample.get("display", "")).casefold() != \
                expected_display.casefold():
            raise RuntimeError("No game window is available on the streamed display.")
        if not self.fills_monitor(
                list(sample.get("bounds") or []),
                list(sample.get("monitor_bounds") or [])):
            raise RuntimeError("The game window does not fill the streamed display.")
        current_thread = int(self.kernel32.GetCurrentThreadId())
        target_thread = int(self.user32.GetWindowThreadProcessId(hwnd, None))
        foreground = int(self.user32.GetForegroundWindow() or 0)
        foreground_thread = int(
            self.user32.GetWindowThreadProcessId(foreground, None)) if foreground else 0
        attached: list[int] = []
        for thread_id in {target_thread, foreground_thread}:
            if thread_id and thread_id != current_thread and self.user32.AttachThreadInput(
                    current_thread, thread_id, True):
                attached.append(thread_id)
        try:
            self.user32.ShowWindow(hwnd, self.SW_RESTORE)
            self.user32.BringWindowToTop(hwnd)
            self.user32.SetActiveWindow(hwnd)
            self.user32.SetFocus(hwnd)
            focused = bool(self.user32.SetForegroundWindow(hwnd))
        finally:
            for thread_id in reversed(attached):
                self.user32.AttachThreadInput(current_thread, thread_id, False)
        return {
            "focused": focused,
            "process_id": int(sample.get("process_id") or 0),
            "display": str(sample.get("display") or ""),
        }

    def show_playnite_fullscreen(self, configured_path: str = "") -> dict[str, Any]:
        if not self.user32:
            raise OSError("Playnite Fullscreen activation requires Windows.")
        windows = self._matching_windows(image_name="playnite.fullscreenapp.exe")
        if windows:
            hwnd = windows[0]
            self.user32.ShowWindow(hwnd, self.SW_RESTORE)
            self.user32.SetForegroundWindow(hwnd)
            return {"started": False, "process_id": 0}
        executable = Path(configured_path).expanduser() if configured_path else None
        if not executable:
            desktop_windows = self._matching_windows(image_name="playnite.desktopapp.exe")
            if desktop_windows:
                pid = wintypes.DWORD()
                self.user32.GetWindowThreadProcessId(desktop_windows[0], ctypes.byref(pid))
                desktop_path = self._process_path(int(pid.value))
                if desktop_path:
                    executable = Path(desktop_path).with_name("Playnite.FullscreenApp.exe")
        if not executable or not executable.is_file():
            raise FileNotFoundError("Playnite.FullscreenApp.exe was not found for this profile.")
        process = subprocess.Popen([str(executable)], cwd=str(executable.parent))
        return {"started": True, "process_id": process.pid}

    @staticmethod
    def _steam_window(window: dict[str, Any], root: Path,
                      expected_display: str) -> bool:
        image = str(window.get("image") or "").casefold()
        process_path = str(window.get("process_path") or "")
        if image not in {"steam.exe", "steamwebhelper.exe"} or not process_path:
            return False
        try:
            normalized_root = ntpath.normcase(ntpath.normpath(str(root)))
            normalized_process = ntpath.normcase(ntpath.normpath(process_path))
            inside_root = ntpath.commonpath([normalized_root, normalized_process]) \
                == normalized_root
        except ValueError:
            return False
        exact_steam = image == "steam.exe" and ntpath.dirname(normalized_process) \
            == normalized_root
        if not (exact_steam or image == "steamwebhelper.exe" and inside_root):
            return False
        display = str(window.get("display") or "")
        return not expected_display or display.casefold() == expected_display.casefold()

    def prepare_steam_launch(self, provider: SteamProvider,
                             expected_display: str = "") -> dict[str, Any]:
        if self.is_session_locked() or self.uac_consent_pending(fail_closed=True) \
                or self.is_session_locked():
            return {"ready": False, "reason": "host_session_locked"}
        if provider.executable() is None:
            return {"ready": False, "reason": "steam_unavailable"}
        result = self.ensure_steam_big_picture(provider, expected_display)
        # Big Picture may take time to open; recheck before dispatching the game.
        if result.get("ready") and (self.is_session_locked()
                or self.uac_consent_pending(fail_closed=True) or self.is_session_locked()):
            return {"ready": False, "reason": "host_session_locked"}
        return result

    def close_steam_big_picture(self, provider: SteamProvider,
                               dispatch_if_current: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        executable = provider.executable()
        if executable is None:
            return {"ready": True, "reason": "steam_not_installed"}
        own = self.process_identity(os.getpid(), include_owner=True)
        if not self.kernel32 or own is None:
            return {"ready": False, "reason": "steam_process_probe_unavailable"}
        expected_path = ntpath.normcase(ntpath.normpath(str(executable)))
        snapshot = self.kernel32.CreateToolhelp32Snapshot(self.TH32CS_SNAPPROCESS, 0)
        if not snapshot or snapshot == ctypes.c_void_p(-1).value:
            return {"ready": False, "reason": "steam_process_probe_unavailable"}
        matches = []
        entry = self._process_entry()
        try:
            if not self.kernel32.Process32FirstW(snapshot, ctypes.byref(entry)):
                return {"ready": False, "reason": "steam_process_probe_unavailable"}
            while True:
                # Only inspect Steam candidates, not the full running-game inventory.
                if entry.szExeFile.casefold() == "steam.exe":
                    identity = self.process_identity(int(entry.th32ProcessID), include_owner=True)
                    if identity is None:
                        return {"ready": False, "reason": "steam_process_probe_unavailable"}
                    if identity["user_sid"] == own["user_sid"] \
                            and identity["session_id"] == own["session_id"] \
                            and ntpath.normcase(ntpath.normpath(identity["process_path"])) == expected_path:
                        matches.append(identity)
                if not self.kernel32.Process32NextW(snapshot, ctypes.byref(entry)):
                    if ctypes.get_last_error() != 18:
                        return {"ready": False, "reason": "steam_process_probe_unavailable"}
                    break
        finally:
            self.kernel32.CloseHandle(snapshot)
        if not matches:
            return {"ready": True, "reason": "steam_not_running"}
        if len(matches) != 1:
            return {"ready": False, "reason": "steam_process_ambiguous"}
        if self.is_session_locked() or self.uac_consent_pending(fail_closed=True):
            return {"ready": False, "reason": "host_session_locked"}
        expected = matches[0]

        def dispatch() -> dict[str, Any]:
            fresh = self.process_identity(expected["process_id"], include_owner=True)
            keys = ("process_id", "process_started_filetime", "user_sid", "session_id")
            if fresh is None or any(fresh.get(key) != expected.get(key) for key in keys) \
                    or ntpath.normcase(ntpath.normpath(fresh["process_path"])) != expected_path:
                return {"ready": False, "reason": "steam_process_identity_changed"}
            try:
                # No wait: dispatch is not an acknowledgement of BP closure/input isolation.
                # A Steam exit between this check and Popen remains an EXE+URI TOCTOU limit.
                provider.command_runner(
                    [str(executable), "steam://close/bigpicture"],
                    cwd=str(executable.parent), shell=False,
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            except (OSError, subprocess.SubprocessError):
                return {"ready": False, "reason": "steam_close_request_failed"}
            return {"ready": True, "reason": "steam_close_request_sent"}

        return dispatch_if_current(dispatch)

    def ensure_steam_big_picture(self, provider: SteamProvider,
                                 expected_display: str,
                                 timeout: float = 15.0) -> dict[str, Any]:
        if self.user32 and self.is_session_locked():
            return {"ready": False, "reason": "host_session_locked"}
        executable = provider.executable()
        if executable is None:
            return {"ready": False, "reason": "steam_big_picture_unavailable"}

        def qualifying() -> list[dict[str, Any]]:
            return [window for window in self.interactive_windows(True)
                    if self._steam_window(window, executable.parent, expected_display)
                    and self.fills_monitor(list(window.get("bounds") or []),
                                           list(window.get("monitor_bounds") or []))]

        # Always request Big Picture: a fullscreen desktop Steam window is not
        # evidence that gamepad UI is active. Opening it again does not restart Steam.
        steam_running = self._exact_process_running(executable) or any(self._steam_window(
            window, executable.parent, "")
            for window in self.interactive_windows(True))
        arguments = [str(executable), "steam://open/bigpicture"] if steam_running \
            else [str(executable), "-gamepadui"]
        try:
            provider.command_runner(
                arguments, cwd=str(executable.parent), shell=False,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.SubprocessError):
            return {"ready": False, "reason": "steam_big_picture_start_failed"}
        deadline = time.monotonic() + max(0.1, timeout)
        stable_signature = None
        stable_samples = 0
        while time.monotonic() < deadline:
            windows = qualifying()
            signature = (windows[0].get("hwnd"), tuple(windows[0].get("bounds") or [])) \
                if windows else None
            stable_samples = stable_samples + 1 if signature == stable_signature \
                and signature is not None else 1 if signature is not None else 0
            stable_signature = signature
            if stable_samples >= REQUIRED_STABLE_SAMPLES:
                return {"ready": True, "started": True}
            time.sleep(0.25)
        return {"ready": False, "reason": "launcher_interaction_required"}

    def _monitor_details(self, hwnd: int) -> tuple[str, list[int]]:
        if not self.user32:
            return "", []

        class MonitorInfoEx(ctypes.Structure):
            _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                        ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD),
                        ("szDevice", wintypes.WCHAR * 32)]

        monitor = self.user32.MonitorFromWindow(hwnd, self.MONITOR_DEFAULTTONEAREST)
        info = MonitorInfoEx()
        info.cbSize = ctypes.sizeof(info)
        if monitor and self.user32.GetMonitorInfoW(monitor, ctypes.byref(info)):
            bounds = info.rcMonitor
            return str(info.szDevice), [
                bounds.left, bounds.top, bounds.right, bounds.bottom,
            ]
        return "", []

    @staticmethod
    def fills_monitor(bounds: list[int], monitor_bounds: list[int],
                      minimum_coverage: float = 0.90) -> bool:
        if len(bounds) != 4 or len(monitor_bounds) != 4:
            return False
        left = max(bounds[0], monitor_bounds[0])
        top = max(bounds[1], monitor_bounds[1])
        right = min(bounds[2], monitor_bounds[2])
        bottom = min(bounds[3], monitor_bounds[3])
        intersection = max(0, right - left) * max(0, bottom - top)
        monitor_area = max(0, monitor_bounds[2] - monitor_bounds[0]) * \
            max(0, monitor_bounds[3] - monitor_bounds[1])
        return monitor_area > 0 and intersection / monitor_area >= minimum_coverage

    @classmethod
    def can_reveal_without_global_foreground(
            cls, bounds: list[int], monitor_bounds: list[int],
            foreground_display: str, expected_display: str) -> bool:
        foreground_is_on_stream = bool(foreground_display) and \
            foreground_display.casefold() == expected_display.casefold()
        return not foreground_is_on_stream and cls.fills_monitor(bounds, monitor_bounds)

    def invoke_game_launcher(self, candidate: dict[str, Any],
                             detect_only: bool = False) -> dict[str, Any]:
        hwnd = int(candidate.get("hwnd") or 0)
        process_id = int(candidate.get("process_id") or 0)
        process_path = str(candidate.get("process_path") or "")
        if not hwnd or not process_id or not process_path \
                or not self.launcher_automation_path.is_file():
            return {"recognized": False, "clicked": False,
                    "reason": "launcher_uia_unavailable"}
        try:
            command = [
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File",
                str(self.launcher_automation_path),
                "-WindowHandle", str(hwnd),
                "-ExpectedProcessId", str(process_id),
                "-ExpectedProcessPath", process_path,
            ]
            if candidate.get("allow_default_action"):
                command.append("-AllowDefaultAction")
            if detect_only:
                command.append("-DetectOnly")
            result = subprocess.run(command, capture_output=True, text=True, timeout=5,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), check=False)
            payload = json.loads((result.stdout or "").strip().splitlines()[-1])
            return payload if isinstance(payload, dict) else {
                "recognized": False, "clicked": False,
                "reason": "launcher_uia_failed"}
        except (OSError, subprocess.SubprocessError, ValueError, IndexError, json.JSONDecodeError):
            return {"recognized": False, "clicked": False,
                    "reason": "launcher_uia_failed"}

    @staticmethod
    def _normalized_title(value: str) -> str:
        return " ".join(re.findall(r"[^\W_]+", str(value or "").casefold()))

    @classmethod
    def title_correlates(cls, actual: str, expected: str) -> bool:
        actual_value = cls._normalized_title(actual)
        expected_value = cls._normalized_title(expected)
        return len(actual_value) >= 4 and len(expected_value) >= 4 and (
            expected_value in actual_value or actual_value in expected_value)

    @staticmethod
    def is_web_launcher_surface(candidate: dict[str, Any]) -> bool:
        window_class = str(candidate.get("window_class") or "").casefold()
        return any(token in window_class for token in (
            "cef", "chrome", "chromium", "sdl_app"))

    @classmethod
    def has_launcher_evidence(cls, candidate: dict[str, Any], expected_executable: str,
                              install_directory: str,
                              expected_launcher_images: set[str] | None = None,
                              expected_title: str = "") -> bool:
        process_path = str(candidate.get("process_path") or "")
        expected = str(expected_executable or "").strip()
        if expected and not ntpath.isabs(expected) and install_directory:
            expected = ntpath.join(install_directory, expected)
        exact_executable = bool(expected and process_path) and \
            ntpath.normcase(ntpath.normpath(process_path)) == \
            ntpath.normcase(ntpath.normpath(expected))
        correlated_surface = exact_executable or cls.belongs_to_install_directory(
            process_path, install_directory)
        surface = " ".join((
            str(candidate.get("image") or ""),
            str(candidate.get("title") or ""),
        )).casefold()
        launcher_named = any(token in surface for token in (
            "launcher", "configuration", "configurator", "settings", "setup",
            "bootstrap", "konfiguracja", "ustawienia"))
        native_dialog = str(candidate.get("window_class") or "").casefold() == "#32770"
        expected_images = {str(value).casefold() for value in
                           (expected_launcher_images or set())}
        provider_web_surface = str(candidate.get("image") or "").casefold() \
            in expected_images and \
            cls.title_correlates(str(candidate.get("title") or ""), expected_title)
        return (correlated_surface and (native_dialog or launcher_named)) or \
            provider_web_surface

    def sample(self, target_kind: str, process_id: int,
               expected_display: str, install_directory: str = "",
               launch_baseline: dict[str, Any] | None = None,
               allow_launcher_invoke: bool = False,
               launcher_interaction_required: bool = False,
               expected_executable: str = "", expected_title: str = "",
               expected_launcher_images: set[str] | None = None,
               launcher_action_attempted: bool = False,
               probe_provider_launcher: bool = False,
               tracked_process_path: str = "") -> dict[str, Any]:
        if not self.user32:
            return {"qualified": False, "reason": "window_probe_unavailable"}
        if self.is_session_locked():
            return {"qualified": False, "reason": "host_session_locked"}

        windows: list[dict[str, Any]] = []
        correlated_windows: list[dict[str, Any]] = []
        launcher_windows: list[dict[str, Any]] = []
        process_tree = self._process_tree(process_id) \
            if target_kind == "game" and process_id else set()
        tracked_process_exited = target_kind == "game" and process_id > 0 \
            and not process_tree
        foreground = int(self.user32.GetForegroundWindow() or 0)
        foreground_display = self._monitor_details(foreground)[0] if foreground else ""
        foreground_process_id = 0
        if foreground:
            foreground_pid = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(foreground, ctypes.byref(foreground_pid))
            foreground_process_id = int(foreground_pid.value)
        foreground_image = self._process_image(foreground_process_id)
        provider_images = {str(value).casefold() for value in
                           (expected_launcher_images or set())}
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def visit(hwnd: int, _lparam: int) -> bool:
            if not self.user32.IsWindowVisible(hwnd):
                return True
            pid = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            candidate_process_id = int(pid.value)
            process_path = self._process_path(candidate_process_id)
            image = os.path.basename(process_path).casefold()
            exact_process = target_kind == "game" and candidate_process_id in process_tree
            installed_process = target_kind == "game" and self.belongs_to_install_directory(
                process_path, install_directory)
            if target_kind == "game":
                correlated_process = exact_process or installed_process
            else:
                correlated_process = False
            if target_kind == "playnite" and not self.is_playnite_ui_image(image):
                return True
            cloaked = wintypes.DWORD()
            if self.dwmapi:
                self.dwmapi.DwmGetWindowAttribute(
                    hwnd, self.DWMWA_CLOAKED, ctypes.byref(cloaked), ctypes.sizeof(cloaked))
            rect = wintypes.RECT()
            if cloaked.value or not self.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
                return True
            width, height = rect.right - rect.left, rect.bottom - rect.top
            display, monitor_bounds = self._monitor_details(hwnd)
            details = {
                "hwnd": int(hwnd), "process_id": candidate_process_id, "image": image,
                "process_path": process_path, "title": self._window_title(hwnd)[:300],
                "window_class": self._window_class(hwnd),
                "display": display,
                "bounds": [rect.left, rect.top, rect.right, rect.bottom],
                "monitor_bounds": monitor_bounds,
                "foreground": int(hwnd) == foreground,
                "foreground_hwnd": foreground,
                "foreground_display": foreground_display,
                "foreground_process_id": foreground_process_id,
                "foreground_image": foreground_image,
            }
            provider_surface_after_action = launcher_action_attempted and \
                image in provider_images and self.is_web_launcher_surface(details)
            provider_surface_probe = probe_provider_launcher and details["foreground"] and \
                image in provider_images
            if target_kind == "game" and width >= 240 and height >= 120 \
                    and image not in INSTALLER_EXCLUDED_IMAGES and details["title"] \
                    and (correlated_process or provider_surface_after_action or
                         provider_surface_probe or
                         self.has_launcher_evidence(
                             details, expected_executable, install_directory,
                             expected_launcher_images, expected_title)):
                launcher_windows.append(details)
            if target_kind == "game" and not correlated_process:
                return True
            if target_kind == "game":
                if tracked_process_exited:
                    details["replacement_process"] = True
                correlated_windows.append(details)
            if width < 640 or height < 360:
                return True
            windows.append(details)
            return True

        callback = callback_type(visit)
        self.user32.EnumWindows(callback, 0)
        if tracked_process_exited and not correlated_windows:
            identities = self.process_identities(tracked_process_path) \
                if tracked_process_path else None
            if identities is None:
                return {"qualified": False,
                        "reason": "game_process_probe_unavailable"}
            if len(identities) == 1:
                return {"qualified": False,
                        "reason": "game_replacement_headless"}
            if len(identities) > 1:
                return {"qualified": False,
                        "reason": "game_process_identity_ambiguous"}
            return {"qualified": False, "reason": "game_process_exited",
                    "process_id": process_id}
        if tracked_process_exited and correlated_windows and not windows:
            candidate = next((item for item in correlated_windows
                              if item["foreground"]), correlated_windows[0])
            return {"qualified": False, "reason": "waiting_for_game_window",
                    **candidate}
        if target_kind == "game" and launch_baseline:
            baseline_windows = (launch_baseline or {}).get("windows") or {}
            candidates = []
            for candidate in launcher_windows:
                previous = baseline_windows.get(str(candidate["hwnd"]))
                became_foreground = candidate["foreground"] and \
                    int(candidate["hwnd"]) != int(
                        (launch_baseline or {}).get("foreground_hwnd") or 0)
                changed = previous is None or \
                    int(previous.get("process_id") or 0) != candidate["process_id"] or \
                    str(previous.get("title") or "") != candidate["title"] or \
                    became_foreground
                provider_surface_after_action = launcher_action_attempted and \
                    str(candidate.get("image") or "").casefold() in provider_images and \
                    self.is_web_launcher_surface(candidate)
                provider_surface_probe = probe_provider_launcher and \
                    str(candidate.get("image") or "").casefold() in provider_images
                ordinary_candidate = changed and not self.fills_monitor(
                    candidate["bounds"], candidate["monitor_bounds"], .90) and \
                    (provider_surface_after_action or self.has_launcher_evidence(
                        candidate, expected_executable, install_directory,
                        expected_launcher_images, expected_title))
                if candidate["foreground"] and (ordinary_candidate or
                                                  provider_surface_probe):
                    candidate["provider_surface_probe"] = provider_surface_probe and \
                        not ordinary_candidate
                    candidates.append(candidate)
            if candidates:
                selected = max(candidates, key=lambda item: (
                    (item["bounds"][2] - item["bounds"][0]) *
                    (item["bounds"][3] - item["bounds"][1]), item["hwnd"]))
                selected["allow_default_action"] = bool(
                    str(selected.get("image") or "").casefold() in {
                        str(value).casefold() for value in
                        (expected_launcher_images or set())
                    } and self.title_correlates(
                        str(selected.get("title") or ""), expected_title))
                if selected.get("provider_surface_probe"):
                    result = self.invoke_game_launcher(selected, detect_only=True)
                    if result.get("recognized") or \
                            result.get("reason") == "launcher_action_ambiguous":
                        return {"qualified": False,
                                "reason": "launcher_interaction_required",
                                "launcher_candidate": True,
                                "launcher_action_attempted": True,
                                "launcher_detail": str(result.get("reason") or
                                                       "launcher_action_detected"),
                                **selected}
                    reason = "waiting_for_game_window" if target_kind == "game" \
                        else "waiting_for_playnite_window"
                    return {"qualified": False, "reason": reason}
                if launcher_interaction_required:
                    return {"qualified": False,
                            "reason": "launcher_interaction_required",
                            "launcher_candidate": True, **selected}
                if not allow_launcher_invoke:
                    return {"qualified": False, "reason": "launcher_candidate_detected",
                            "launcher_candidate": True, **selected}
                result = self.invoke_game_launcher(selected)
                if result.get("recognized") and result.get("clicked"):
                    return {"qualified": False, "reason": "launcher_action_invoked",
                            "launcher_candidate": True,
                            "launcher_action_attempted": True, **selected}
                return {"qualified": False,
                        "reason": "launcher_interaction_required",
                        "launcher_candidate": True,
                        "launcher_action_attempted": True,
                        "launcher_detail": str(result.get("reason") or
                                               "launcher_uia_failed"), **selected}
        if not windows:
            reason = "waiting_for_game_window" if target_kind == "game" else "waiting_for_playnite_window"
            return {"qualified": False, "reason": reason}
        candidate = next((item for item in windows if item["foreground"]), None)
        if candidate is None:
            candidate = max(
                windows,
                key=lambda item: (
                    bool(expected_display) and
                    item["display"].casefold() == expected_display.casefold(),
                    max(0, item["bounds"][2] - item["bounds"][0]) *
                    max(0, item["bounds"][3] - item["bounds"][1]),
                ),
            )
        if not expected_display:
            return {"qualified": False, "reason": "stream_display_not_configured", **candidate}
        if candidate["display"].casefold() != expected_display.casefold():
            return {"qualified": False, "reason": "target_on_wrong_display", **candidate}
        if not self.fills_monitor(candidate["bounds"], candidate["monitor_bounds"]):
            return {"qualified": False, "reason": "target_not_fullscreen", **candidate}
        if not candidate["foreground"] and not self.can_reveal_without_global_foreground(
                candidate["bounds"], candidate["monitor_bounds"],
                foreground_display, expected_display):
            reason = "host_session_locked" \
                if foreground_image.casefold() == "lockapp.exe" else "target_not_foreground"
            return {"qualified": False, "reason": reason, **candidate}
        return {"qualified": True, "reason": "stabilizing_target_window", **candidate}


def compact_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def append_operation_audit(path: Path, event: str, payload: dict[str, Any]) -> None:
    record = {"timestamp": time.time(), "event": event, **payload}
    encoded = json.dumps(
        record, ensure_ascii=False, separators=(",", ":")) + "\n"
    # ponytail: operation volume is tiny; add rotation only if this audit grows
    # enough to matter in real installations.
    with OPERATION_AUDIT_LOCK:
        with path.open("a", encoding="utf-8") as output:
            output.write(encoded)


class BridgeState:
    def __init__(self, expected_display: str = "", cache_path: Path | None = None,
                 version_path: Path | None = None,
                 operations_path: Path | None = None,
                 active_game_path: Path | None = None,
                 game_operations: GameOperationsService | None = None,
                 clock: Callable[[], float] | None = None,
                 operation_audit: Callable[[str, dict[str, Any]], None] | None = None,
                 profile_id: str = "", stop_timeout: float = GAME_STOP_TIMEOUT) -> None:
        self.lock = threading.RLock()
        self.clock = clock or time.time
        self.operation_audit = operation_audit
        self.profile_id = str(profile_id).strip()
        self.request_context = threading.local()
        self.stop_timeout = max(0.01, float(stop_timeout))
        self.events_changed = threading.Condition(self.lock)
        self.connected = False
        self._transport_observed = False
        self.last_error = "Playnite connector is not connected."
        self.library: dict[str, dict[str, Any]] = {}
        self.playnite_library: dict[str, dict[str, Any]] = {}
        self.library_staging: dict[str, dict[str, Any]] = {}
        self.library_revision = ""
        self.running_process_probe: WindowProbe | None = None
        self._running_games: list[dict[str, Any]] = []
        self._running_scan_status = "unavailable"
        self._running_scan_revision = ""
        self._running_scan_at = 0.0
        # Host-owned Epic totals live in the existing profile library cache.
        self.epic_playtime_seconds: dict[str, float] = {}
        self._epic_playtime_samples: dict[str, tuple[str, float]] = {}
        self.snapshot_in_progress = False
        self.categories: list[dict[str, Any]] = []
        self.plugins: list[dict[str, Any]] = []
        self.current: dict[str, Any] = {"state": "idle"}
        self.active_game_path = active_game_path
        self._active_game_trace: dict[str, Any] | None = None
        self._process_identity_action: Callable[[int], dict[str, Any] | None] | None = None
        self._process_identities_action: Callable[
            [str], list[dict[str, Any]] | None] | None = None
        self._native_reconciliation_confirmation: tuple[str, int] | None = None
        self.readiness: dict[str, Any] = {
            "ready": False,
            "reason": "window_probe_pending",
            "target_kind": "playnite",
            "stable_samples": 0,
        }
        self.events: deque[dict[str, Any]] = deque(maxlen=200)
        self.next_sequence = 1
        self.command_sender: Callable[[dict[str, Any]], None] | None = None
        self.expected_display = expected_display.strip()
        self._last_window_signature: tuple[Any, ...] | None = None
        self._launch_baseline: dict[str, Any] = {}
        self._launcher_candidate_signature: tuple[Any, ...] | None = None
        self._launcher_candidate_samples = 0
        self._launcher_automation_attempted = False
        self._launcher_interaction_required = False
        self._launcher_invoked_signature: tuple[Any, ...] | None = None
        self._launcher_invoked_at = 0.0
        self.graceful_close: Callable[[int], bool] | None = None
        self.show_fullscreen_action: Callable[[], dict[str, Any]] | None = None
        self.focus_game_action: Callable[[int, str, str], dict[str, Any]] | None = None
        self.nonsteam_launch_preparation: Callable[..., dict[str, Any]] | None = None
        self.installation_baseline_action: Callable[[], dict[str, Any]] | None = None
        self.installation_probe_action: Callable[[dict[str, Any]], dict[str, Any]] | None = None
        self.focus_installation_action: Callable[[int], dict[str, Any]] | None = None
        self.external_reconciliation_inflight = False
        self.catalog_refresh_inflight = False
        self.catalog_refresh_pending = False
        self.provider_health: dict[str, dict[str, Any]] = {
            "steam": {"available": False, "complete": False,
                      "reason": "steam_catalog_pending"},
            "epic": {"available": False, "complete": False,
                     "reason": "legendary_catalog_pending"},
            "playnite": {"available": False, "complete": False,
                         "reason": "playnite_connector_unavailable"},
        }
        # Legendary is authoritative when the Playnite Epic plugin returns the
        # Epic Launcher's stale IsInstalled/IsInstalling pair.
        self.external_installed_overrides: dict[str, str] = {}
        self.external_uninstalled_overrides: set[str] = set()
        self.installations: dict[str, dict[str, Any]] = {}
        self.game_operations = game_operations or GameOperationsService(
            OperationJournal(operations_path))
        self.operation_journal = self.game_operations.journal
        for operation in self.operation_journal.active():
            provider = self.game_operations.provider_for_label(
                str(operation.get("provider") or ""))
            is_steam = provider is self.game_operations.steam
            if provider is self.game_operations.epic:
                detail = "confirmation_window_expired" \
                    if operation["state"] == "attention_required" \
                    else "epic_operation_interrupted"
                self.operation_journal.update(
                    str(operation["game_id"]), "failed", detail=detail)
                if self.operation_audit is not None:
                    try:
                        self.operation_audit("epic_operation_interrupted", {
                            "game_id": str(operation["game_id"]),
                            "kind": str(operation.get("kind") or ""),
                            "previous_state": str(operation.get("state") or ""),
                        })
                    except Exception:
                        pass
                continue
            manual = provider.manual_attention({}, str(operation.get("kind") or "install"))
            if manual:
                self.operation_journal.update(
                    str(operation["game_id"]), "attention_required",
                    detail=str(manual["reason"]), launcher=str(manual["launcher"]))
                operation = self.operation_journal.get(str(operation["game_id"])) or operation
            if is_steam:
                restored_state = str(operation["state"])
                if restored_state == "attention_required":
                    restored_state = "uninstalling" \
                        if str(operation["kind"]) == "uninstall" else "preparing"
                self.operation_journal.update(
                    str(operation["game_id"]), restored_state,
                    detail="", progress=operation.get("progress"))
                operation = self.operation_journal.get(
                    str(operation["game_id"])) or operation
            # HWND values are process-local and cannot be trusted after a Bridge
            # restart. Preserve the existing non-Steam expiry behavior.
            elif operation["state"] == "attention_required" \
                    and str(operation.get("detail") or "") != "epic_manual":
                self.operation_journal.update(
                    str(operation["game_id"]), "failed",
                    detail="confirmation_window_expired")
                continue
            token = self.operation_token(operation)
            self.installations[str(operation["game_id"])] = {
                "baseline": {"operation": str(operation["kind"]), "restored": True},
                "requested_at": float(operation["requested_at"]),
                "operation": str(operation["kind"]),
                "token": token,
                "requires_attention": operation["state"] == "attention_required",
                "reason": str(operation["detail"] or ""),
                "hwnd": int(operation["window_handle"] or 0),
                "window_title": str(operation["window_title"] or ""),
                "image": str(operation["launcher"] or ""),
                "stable_samples": 0,
                "candidate_signature": None,
                "restored": True,
                "recovery_pending": is_steam,
                "recovery_started_at": self.clock(),
                "primary_attempted": False,
                "primary_started": False,
            }
        self.cache_path = cache_path
        self.started_at = int(time.time())
        self.version_info = self._load_version_info(version_path)
        self._load_library_cache()
        self._load_active_game_trace()

    @staticmethod
    def operation_token(operation: dict[str, Any]) -> tuple[str, str, float]:
        return (
            str(operation.get("game_id") or ""),
            str(operation.get("kind") or ""),
            float(operation.get("requested_at") or 0),
        )

    def _operation_active_locked(self, game_id: str,
                                 token: tuple[str, str, float]) -> bool:
        session = self.installations.get(game_id)
        operation = self.operation_journal.get(game_id)
        return bool(session and operation and session.get("token") == token
                    and self.operation_token(operation) == token
                    and operation.get("state") in ACTIVE_STATES)

    def _operation_result_current_locked(
            self, game_id: str,
            token: tuple[str, str, float] | None) -> bool:
        session = self.installations.get(game_id)
        if not session or session.get("token") != token:
            return False
        return token is None or self._operation_active_locked(game_id, token)

    def _audit_steam(self, event: str, game_id: str,
                     token: tuple[str, str, float], game: dict[str, Any],
                     **details: Any) -> None:
        audit = self.operation_audit
        if audit is None:
            return
        payload = {
            "game_id": game_id,
            "kind": token[1],
            "requested_at": token[2],
            "app_id": str(game.get("providerGameId") or ""),
            **details,
        }
        try:
            audit(event, payload)
        except Exception as error:
            print(json.dumps({
                "event": "steam_operation_audit_failed",
                "game_id": game_id,
                "kind": token[1],
                "requested_at": token[2],
                "error": type(error).__name__,
            }, separators=(",", ":")), flush=True)

    @staticmethod
    def _load_version_info(version_path: Path | None) -> dict[str, Any]:
        result: dict[str, Any] = {"version": "unknown", "build": "unknown"}
        if version_path is None:
            return result
        try:
            value = json.loads(version_path.read_text(encoding="utf-8-sig"))
            if isinstance(value, dict):
                for key in ("version", "build", "protocol_version"):
                    if key in value:
                        result[key] = value[key]
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            pass
        return result

    def _load_library_cache(self) -> None:
        if self.cache_path is None or not self.cache_path.is_file():
            return
        try:
            cached = json.loads(self.cache_path.read_text(encoding="utf-8-sig"))
            if not isinstance(cached, dict) or cached.get("version") not in {1, 2}:
                return
            library: dict[str, dict[str, Any]] = {}
            for game in cached.get("library") or []:
                if not isinstance(game, dict):
                    continue
                game_id = self.game_id(game.get("id"))
                normalized = dict(game)
                normalized["id"] = game_id
                library[game_id] = normalized
            self.library = library
            cached_playtime = cached.get("epic_playtime_seconds")
            if not isinstance(cached_playtime, dict):
                cached_playtime = {}
            self.epic_playtime_seconds = {
                str(key): float(value)
                for key, value in cached_playtime.items()
                if re.fullmatch(r"epic:[A-Za-z0-9_-]+", str(key))
                and isinstance(value, (int, float)) and math.isfinite(value) and value >= 0}
            self._apply_epic_playtime_locked()
            playnite_values = cached.get("playnite_library") \
                if cached.get("version") == 2 else cached.get("library")
            self.playnite_library = {}
            for game in playnite_values or []:
                if not isinstance(game, dict):
                    continue
                raw_id = str(game.get("playniteGameId") or game.get("id") or "").strip()
                if raw_id.startswith("playnite:"):
                    raw_id = raw_id.split(":", 1)[1]
                if PLAYNITE_ID_PATTERN.fullmatch(raw_id):
                    normalized = dict(game)
                    normalized["id"] = raw_id.lower()
                    self.playnite_library[raw_id.lower()] = normalized
            if cached.get("version") == 2 and isinstance(cached.get("providers"), dict):
                self.provider_health.update({
                    str(key): dict(value) for key, value in cached["providers"].items()
                    if str(key) in self.provider_health and isinstance(value, dict)})
            self.categories = [dict(item) for item in (cached.get("categories") or [])
                               if isinstance(item, dict)]
            self.plugins = [dict(item) for item in (cached.get("plugins") or [])
                            if isinstance(item, dict)]
            self.library_revision = str(cached.get("revision") or cached.get("saved_at") or "")
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            # A broken cache must never prevent the Bridge from starting. It will
            # be replaced after the next complete connector snapshot.
            self.library = {}
            self.playnite_library = {}
            self.categories = []
            self.plugins = []

    @staticmethod
    def _normalized_process_path(value: Any) -> str:
        path = str(value or "").strip()
        return ntpath.normcase(ntpath.normpath(path)) if path else ""

    def _remove_active_game_trace(self) -> None:
        if self.active_game_path is None:
            return
        try:
            self.active_game_path.unlink(missing_ok=True)
            self.active_game_path.with_name(
                self.active_game_path.name + ".tmp").unlink(missing_ok=True)
        except OSError as error:
            print(json.dumps({
                "event": "active_game_trace_warning",
                "operation": "remove",
                "error": type(error).__name__,
            }, separators=(",", ":")), flush=True)

    def _load_active_game_trace(self) -> None:
        path = self.active_game_path
        if path is None or not path.is_file():
            return
        try:
            if path.stat().st_size > MAX_ACTIVE_GAME_TRACE_BYTES:
                raise ValueError("Oversized active game trace")
            value = json.loads(path.read_text(encoding="utf-8-sig"))
            if not isinstance(value, dict) or value.get("version") != 1:
                raise ValueError("Unsupported active game trace")
            game_id = str(value.get("game_id") or "")
            provider = str(value.get("provider") or "").casefold()
            provider_game_id = str(value.get("provider_game_id") or "")
            playnite_guid = str(value.get("playnite_guid") or "").casefold()
            process_id = int(value.get("process_id") or 0)
            process_path = self._normalized_process_path(value.get("process_path"))
            process_started = int(value.get("process_started_filetime") or 0)
            if not GAME_ID_PATTERN.fullmatch(game_id) \
                    or provider not in {"steam", "epic", "playnite"} \
                    or not provider_game_id or len(provider_game_id) > 512 \
                    or process_id <= 0 or len(process_path) > 8192 \
                    or not ntpath.isabs(process_path) or process_started <= 0 \
                    or provider == "playnite" \
                    and not PLAYNITE_ID_PATTERN.fullmatch(playnite_guid):
                raise ValueError("Malformed active game trace")
            self._active_game_trace = {
                "version": 1,
                "game_id": game_id,
                "provider": provider,
                "provider_game_id": provider_game_id,
                "playnite_guid": playnite_guid,
                "process_id": process_id,
                "process_path": process_path,
                "process_started_filetime": process_started,
            }
            self.current = {
                "state": "reconciling", "id": game_id,
                "reason": "active_game_verification_pending",
            }
            self.readiness = {
                "ready": False,
                "reason": "active_game_verification_pending",
                "target_kind": "game",
                "game_id": game_id,
                "stable_samples": 0,
            }
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            self._active_game_trace = None
            self._remove_active_game_trace()

    def set_reconciliation_actions(
            self, process_identity: Callable[[int], dict[str, Any] | None],
            process_identities: Callable[
                [str], list[dict[str, Any]] | None]) -> None:
        with self.lock:
            self._process_identity_action = process_identity
            self._process_identities_action = process_identities
            self._attempt_active_game_reconciliation_locked()

    def _trace_library_candidates_locked(
            self, trace: dict[str, Any]) -> list[dict[str, Any]]:
        provider = str(trace["provider"])
        provider_game_id = str(trace["provider_game_id"])
        playnite_guid = str(trace["playnite_guid"])
        return [game for game in self.library.values()
                if str(game.get("provider") or "").casefold() == provider
                and str(game.get("providerGameId") or "") == provider_game_id
                and str(game.get("playniteGameId") or "").casefold() == playnite_guid]

    def _trace_path_matches_game(self, trace: dict[str, Any],
                                 game: dict[str, Any]) -> bool:
        process_path = str(trace["process_path"])
        install_dir = self._normalized_process_path(
            game.get("installDir") or game.get("install_dir"))
        executable = str(game.get("exe") or game.get("executable") or "").strip()
        if executable:
            expected = executable if ntpath.isabs(executable) \
                else ntpath.join(install_dir, executable)
            return process_path == self._normalized_process_path(expected)
        return bool(install_dir) and WindowProbe.belongs_to_install_directory(
            process_path, install_dir)

    def _clear_reconciliation_locked(self, reason: str) -> None:
        previous = dict(self.current)
        self._active_game_trace = None
        self._native_reconciliation_confirmation = None
        self._remove_active_game_trace()
        self.current = {"state": "idle"}
        self.readiness = {
            "ready": False, "reason": reason,
            "target_kind": "none", "stable_samples": 0,
        }
        self._publish_locked("game-reconciliation-cleared", previous)

    def _mark_reconciliation_ambiguous_locked(self, reason: str) -> None:
        trace = self._active_game_trace or {}
        self.current = {
            "state": "ambiguous", "id": str(trace.get("game_id") or ""),
            "reason": reason,
        }
        self.readiness = {
            "ready": False, "reason": reason,
            "target_kind": "game", "game_id": str(trace.get("game_id") or ""),
            "stable_samples": 0,
        }
        self._publish_locked("game-reconciliation-ambiguous", dict(self.current))

    def _attempt_active_game_reconciliation_locked(self) -> None:
        trace = self._active_game_trace
        identity_action = self._process_identity_action
        identities_action = self._process_identities_action
        if trace is None or identity_action is None or identities_action is None:
            return
        if str(self.current.get("state") or "").casefold() not in {
                "reconciling", "ambiguous"}:
            return
        if not self.library:
            return
        candidates = self._trace_library_candidates_locked(trace)
        if len(candidates) > 1:
            self._mark_reconciliation_ambiguous_locked(
                "active_game_library_ambiguous")
            return
        if len(candidates) != 1 or str(candidates[0].get("id") or "") != trace["game_id"] \
                or not self._trace_path_matches_game(trace, candidates[0]):
            self._clear_reconciliation_locked("active_game_trace_stale")
            return
        identity = identity_action(int(trace["process_id"]))
        if identity is None \
                or self._normalized_process_path(identity.get("process_path")) != \
                trace["process_path"] \
                or int(identity.get("process_started_filetime") or 0) != \
                int(trace["process_started_filetime"]):
            self._clear_reconciliation_locked("active_game_process_stale")
            return
        observed = identities_action(str(trace["process_path"]))
        if observed is None:
            return
        matches = [item for item in observed
                   if self._normalized_process_path(item.get("process_path")) ==
                   trace["process_path"]]
        if len(matches) > 1:
            self._mark_reconciliation_ambiguous_locked(
                "active_game_process_ambiguous")
            return
        if len(matches) != 1 or int(matches[0].get("process_id") or 0) != \
                int(trace["process_id"]) or int(
                    matches[0].get("process_started_filetime") or 0) != int(
                        trace["process_started_filetime"]):
            self._clear_reconciliation_locked("active_game_process_stale")
            return
        if trace["provider"] == "playnite":
            if not self._transport_observed:
                return
            if self.connected:
                expected = (str(trace["playnite_guid"]), int(trace["process_id"]))
                if self._native_reconciliation_confirmation != expected:
                    return
        game = candidates[0]
        self.current = {
            "state": "running", "id": str(game["id"]),
            "title": str(game.get("name") or ""),
            "installDir": str(game.get("installDir") or game.get("install_dir") or ""),
            "exe": str(game.get("exe") or game.get("executable") or ""),
            "source": str(game.get("libraryName") or game.get("source") or ""),
            "provider": str(game.get("provider") or ""),
            "providerGameId": str(game.get("providerGameId") or ""),
            "playniteGameId": str(game.get("playniteGameId") or ""),
            "processId": int(trace["process_id"]),
            "processPath": str(trace["process_path"]),
            "processStartedFiletime": int(trace["process_started_filetime"]),
            "reconciled": True,
        }
        self.readiness = {
            "ready": False, "reason": "waiting_for_game_window",
            "target_kind": "game", "game_id": str(game["id"]),
            "stable_samples": 0,
        }
        self._last_window_signature = None
        self._publish_locked("game-reconciled", dict(self.current))

    def _save_active_game_trace_locked(self) -> None:
        if self.active_game_path is None \
                or str(self.current.get("state") or "").casefold() != "running" \
                or self._process_identity_action is None \
                or self._process_identities_action is None:
            return
        game = self.library.get(str(self.current.get("id") or ""))
        process_id = int(self.current.get("processId") or
                         self.current.get("process_id") or 0)
        if game is None or process_id <= 0:
            return
        identity = self._process_identity_action(process_id)
        if identity is None:
            return
        process_path = self._normalized_process_path(identity.get("process_path"))
        process_started = int(identity.get("process_started_filetime") or 0)
        observed = self._process_identities_action(process_path)
        if observed is None:
            return
        matches = [item for item in observed
                   if self._normalized_process_path(item.get("process_path")) == process_path]
        playnite_guid = str(game.get("playniteGameId") or "").casefold()
        payload = {
            "version": 1,
            "game_id": str(game.get("id") or ""),
            "provider": str(game.get("provider") or "").casefold(),
            "provider_game_id": str(game.get("providerGameId") or ""),
            "playnite_guid": playnite_guid,
            "process_id": process_id,
            "process_path": process_path,
            "process_started_filetime": process_started,
        }
        if not process_path or process_started <= 0 or len(matches) != 1 \
                or int(matches[0].get("process_id") or 0) != process_id \
                or int(matches[0].get("process_started_filetime") or 0) != process_started \
                or not payload["provider_game_id"] \
                or payload["provider"] not in {"steam", "epic", "playnite"} \
                or payload["provider"] == "playnite" \
                and not PLAYNITE_ID_PATTERN.fullmatch(playnite_guid) \
                or not self._trace_path_matches_game(payload, game):
            return
        self.current["processPath"] = process_path
        self.current["processStartedFiletime"] = process_started
        temporary = self.active_game_path.with_name(self.active_game_path.name + ".tmp")
        try:
            self.active_game_path.parent.mkdir(parents=True, exist_ok=True)
            with temporary.open("w", encoding="utf-8", newline="") as output:
                json.dump(payload, output, ensure_ascii=False, separators=(",", ":"))
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.active_game_path)
            self._active_game_trace = payload
        except OSError as error:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
            print(json.dumps({
                "event": "active_game_trace_warning",
                "operation": "save",
                "error": type(error).__name__,
            }, separators=(",", ":")), flush=True)

    def _save_library_cache_locked(self) -> None:
        if self.cache_path is None:
            return
        payload = {
            "version": 2,
            "saved_at": int(time.time()),
            "revision": self.library_revision,
            "library": list(self.library.values()),
            "epic_playtime_seconds": dict(self.epic_playtime_seconds),
            "playnite_library": list(self.playnite_library.values()),
            "providers": dict(self.provider_health),
            "categories": list(self.categories),
            "plugins": list(self.plugins),
        }
        temporary = self.cache_path.with_name(self.cache_path.name + ".tmp")
        try:
            self.cache_path.parent.mkdir(parents=True, exist_ok=True)
            temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
                                 encoding="utf-8")
            os.replace(temporary, self.cache_path)
        except OSError:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass

    def set_window_actions(self, graceful_close: Callable[[int], bool],
                           show_fullscreen: Callable[[], dict[str, Any]],
                           focus_game: Callable[[int, str, str], dict[str, Any]],
                           installation_baseline: Callable[[], dict[str, Any]] | None = None,
                           installation_probe: Callable[[dict[str, Any]], dict[str, Any]] | None = None,
                           focus_installation: Callable[[int], dict[str, Any]] | None = None) -> None:
        self.graceful_close = graceful_close
        self.show_fullscreen_action = show_fullscreen
        self.focus_game_action = focus_game
        self.installation_baseline_action = installation_baseline
        self.installation_probe_action = installation_probe
        self.focus_installation_action = focus_installation
        if installation_baseline is not None:
            with self.lock:
                for game_id, session in self.installations.items():
                    if not session.pop("restored", False):
                        continue
                    baseline = installation_baseline()
                    baseline["operation"] = str(session.get("operation") or "install")
                    baseline["restored"] = True
                    game = self.library.get(game_id)
                    if game is not None:
                        baseline["game"] = dict(game)
                    session["baseline"] = baseline

    def _schedule_external_reconciliation_locked(self) -> None:
        if self.external_reconciliation_inflight:
            return
        self.external_reconciliation_inflight = True
        threading.Thread(target=self._reconcile_external_installs,
                         name="EpicLibraryReconciliation", daemon=True).start()

    def _reconcile_external_installs(self) -> None:
        try:
            snapshot = self.game_operations.external_snapshot()
        except Exception:
            snapshot = {"available": False}
        with self.lock:
            try:
                if not snapshot.get("available"):
                    return
                next_overrides: dict[str, str] = {} if snapshot.get("complete") else \
                    dict(self.external_installed_overrides)
                next_uninstalled = set() if snapshot.get("complete") else \
                    set(self.external_uninstalled_overrides)
                for game_id, game in self.library.items():
                    if self.game_operations.provider_for(game) \
                            is not self.game_operations.epic:
                        continue
                    connector_claimed_installed = bool(
                        game.get("installed") or game.get("isInstalled"))
                    installed = self.game_operations.installed_from_external_snapshot(
                        game, snapshot)
                    provider_id = str(game.get("providerGameId") or "").strip()
                    verification_failed = provider_id in set(
                        snapshot.get("invalid_ids") or [])
                    operation = self.operation_journal.get(game_id) or {}
                    active = operation.get("state") in ACTIVE_STATES
                    if not active:
                        self.installations.pop(game_id, None)
                    if installed is not None:
                        directory = str(installed.get("install_directory") or "")
                        next_overrides[game_id] = directory
                        next_uninstalled.discard(game_id)
                        game.update({"installed": True, "installing": False,
                                     "isInstalled": True, "isInstalling": False,
                                     "installDir": directory,
                                     "legendaryImportRequired": False,
                                     "legendaryVerificationFailed": False})
                    elif snapshot.get("complete") or verification_failed:
                        next_overrides.pop(game_id, None)
                        next_uninstalled.add(game_id)
                        migration = None if verification_failed \
                            or not connector_claimed_installed else \
                            self.game_operations.migration_candidate(game)
                        game.update({"installed": False, "installing": False,
                                     "isInstalled": False, "isInstalling": False,
                                     "installDir": "",
                                     "legendaryImportRequired": migration is not None,
                                     "legendaryVerificationFailed": verification_failed})
                        if migration is not None:
                            game["legendaryImportDirectory"] = str(
                                migration.get("install_directory") or "")
                        else:
                            game.pop("legendaryImportDirectory", None)
                    else:
                        continue
                    self._apply_installation_fields_locked(game_id, game)
                self.external_installed_overrides = next_overrides
                self.external_uninstalled_overrides = next_uninstalled
                self._advance_library_revision_locked()
                self._save_library_cache_locked()
                self._publish_locked("library-updated", {
                    "count": len(self.library), "revision": self.library_revision})
            finally:
                self.external_reconciliation_inflight = False

    def set_expected_display(self, display: str) -> None:
        normalized = StreamDisplayResolver._normalize(display)
        with self.lock:
            if normalized and normalized != self.expected_display:
                self.expected_display = normalized
                self._last_window_signature = None
                self.readiness.update({
                    "ready": False, "reason": "stream_display_changed", "stable_samples": 0})
                self._publish_locked("stream-display-resolved", {"display": normalized})

    @staticmethod
    def game_id(value: Any) -> str:
        result = str(value or "").strip()
        if not GAME_ID_PATTERN.fullmatch(result):
            raise ValueError("Invalid game record ID.")
        return result if result.startswith("epic:") else result.lower()

    def resolve_game_id(self, value: Any) -> str:
        normalized = self.game_id(value)
        with self.lock:
            if normalized in self.library:
                return normalized
        if PLAYNITE_ID_PATTERN.fullmatch(normalized):
            with self.lock:
                matches = [game_id for game_id, game in self.library.items()
                           if str(game.get("playniteGameId") or "").casefold()
                           == normalized]
            if len(matches) == 1:
                return matches[0]
        return normalized

    def _schedule_catalog_refresh_locked(self) -> None:
        if self.catalog_refresh_inflight:
            self.catalog_refresh_pending = True
            return
        self.catalog_refresh_inflight = True
        threading.Thread(target=self._refresh_catalog,
                         name="ProviderCatalogRefresh", daemon=True).start()

    def _publish_playnite_snapshot_locked(self) -> None:
        records = {game_id: dict(game) for game_id, game in self.library.items()
                   if str(game.get("provider") or "") in {"steam", "epic"}}
        for metadata in self.playnite_library.values():
            if self.game_operations._playnite_source(metadata).casefold() in {
                    "steam", "epic"}:
                continue
            record = self.game_operations._playnite_record(metadata)
            if record is not None:
                records[str(record["id"])] = record
        self.library = records
        for game_id, game in self.library.items():
            self._apply_installation_fields_locked(game_id, game)
        self._advance_library_revision_locked()
        self._save_library_cache_locked()
        self._attempt_active_game_reconciliation_locked()
        self._publish_locked("library-updated", {
            "count": len(self.library), "revision": self.library_revision})

    def _refresh_catalog(self) -> None:
        with self.lock:
            playnite = [dict(game) for game in self.playnite_library.values()]
            previous = {key: dict(value) for key, value in self.library.items()}
            connected = self.connected
        try:
            aggregated = self.game_operations.aggregate_catalog(playnite, previous)
        except Exception:
            aggregated = {"library": previous, "providers": dict(self.provider_health)}
        with self.lock:
            try:
                library = aggregated.get("library") or {}
                self.library = {str(key): dict(value) for key, value in library.items()
                                if isinstance(value, dict) and GAME_ID_PATTERN.fullmatch(str(key))}
                self._apply_epic_playtime_locked()
                providers = aggregated.get("providers") or {}
                self.provider_health.update({
                    str(key): dict(value) for key, value in providers.items()
                    if str(key) in self.provider_health and isinstance(value, dict)})
                self.provider_health["playnite"] = {
                    "available": connected, "complete": True if connected else False,
                    "reason": "" if connected else "playnite_connector_unavailable",
                }
                for game_id, game in self.library.items():
                    self._apply_installation_fields_locked(game_id, game)
                self._advance_library_revision_locked()
                self._save_library_cache_locked()
                self._attempt_active_game_reconciliation_locked()
                self._publish_locked("library-updated", {
                    "count": len(self.library), "revision": self.library_revision})
            finally:
                self.catalog_refresh_inflight = False
                if self.catalog_refresh_pending:
                    self.catalog_refresh_pending = False
                    self._schedule_catalog_refresh_locked()

    def set_transport(self, connected: bool,
                      sender: Callable[[dict[str, Any]], None] | None,
                      error: str = "") -> None:
        with self.lock:
            changed = self.connected != connected
            self._transport_observed = True
            self.connected = connected
            self.command_sender = sender
            self.last_error = error[:500]
            self.provider_health["playnite"] = {
                "available": connected, "complete": connected,
                "reason": "" if connected else "playnite_connector_unavailable",
            }
            if changed:
                self._publish_locked("bridge-connected" if connected else "bridge-disconnected", {})
            self._attempt_active_game_reconciliation_locked()

    def _publish_locked(self, name: str, payload: dict[str, Any]) -> None:
        event = {
            "sequence": self.next_sequence,
            "event": name,
            "timestamp": int(time.time()),
            "payload": payload,
        }
        self.next_sequence += 1
        self.events.append(event)
        self.events_changed.notify_all()
        DIAGNOSTICS.lifecycle(name, event["sequence"], self.profile_id,
                              getattr(self.request_context, "request_id", None), payload)

    def _advance_library_revision_locked(self) -> None:
        try:
            previous = int(self.library_revision or 0)
        except ValueError:
            previous = 0
        self.library_revision = str(max(time.time_ns(), previous + 1))

    def _apply_installation_fields_locked(self, game_id: str,
                                          game: dict[str, Any]) -> None:
        session = self.installations.get(game_id)
        operation = self.operation_journal.get(game_id)
        attention = bool(session and session.get("requires_attention"))
        kind = str((operation or {}).get("kind") or (session or {}).get("operation") or "install")
        active = bool(operation and operation.get("state") in ACTIVE_STATES)
        if session and kind == "install":
            # The launcher may keep Playnite's IsInstalling false while its own
            # confirmation dialog is open. The Bridge session remains authoritative
            # until Playnite reports completion or cancellation.
            game["installing"] = True
        game["uninstalling"] = bool(session and kind == "uninstall")
        game["operationState"] = str(operation.get("state") or "") if active else ""
        game["operationProgress"] = operation.get("progress") if active else None
        game["installRequiresAttention"] = attention
        game["installAttentionReason"] = str(
            (session or {}).get("reason") or "") if attention else ""
        game["installWindowTitle"] = str(
            (session or {}).get("window_title") or "") if attention else ""
        game["installLauncher"] = str(
            (session or {}).get("image") or "") if attention else ""

    def handle_message(self, message: dict[str, Any]) -> None:
        kind = str(message.get("type", ""))
        with self.lock:
            if kind == "snapshotStart":
                self.library_staging = {}
                self.snapshot_in_progress = True
            elif kind == "plugins":
                self.plugins = list(message.get("payload") or [])
                if not self.snapshot_in_progress:
                    self.library_staging = {}
            elif kind == "categories":
                self.categories = list(message.get("payload") or [])
            elif kind == "games":
                for game in message.get("payload") or []:
                    if not isinstance(game, dict):
                        continue
                    try:
                        game_id = str(game.get("id") or "").strip().lower()
                        if not PLAYNITE_ID_PATTERN.fullmatch(game_id):
                            raise ValueError("Invalid Playnite game ID.")
                    except ValueError:
                        continue
                    normalized = dict(game)
                    normalized["id"] = game_id
                    by_name = {str(key).casefold(): value for key, value in game.items()}
                    last_played = (by_name.get("lastplayed") or
                                   by_name.get("last_activity") or
                                   by_name.get("lastactivity") or "")
                    if last_played:
                        normalized["lastPlayed"] = str(last_played)
                    description = str(by_name.get("description") or
                                      by_name.get("overview") or "").strip()
                    if description:
                        description = re.sub(r"<br\s*/?>", "\n", description,
                                             flags=re.IGNORECASE)
                        description = re.sub(r"<[^>]+>", " ", description)
                        description = html.unescape(description)
                        description = re.sub(r"[ \t\r\f\v]+", " ", description)
                        description = re.sub(r" *\n *", "\n", description).strip()
                        description = re.sub(r"\n{3,}", "\n\n", description)
                    normalized["description"] = description[:2000]
                    try:
                        normalized["playCount"] = max(0, int(
                            by_name.get("playcount") or
                            by_name.get("play_count") or 0))
                    except (TypeError, ValueError):
                        normalized["playCount"] = 0
                    normalized["source"] = str(
                        by_name.get("source") or
                        by_name.get("sourcename") or
                        by_name.get("source_name") or "").strip()
                    normalized["providerGameId"] = str(
                        by_name.get("providergameid") or
                        by_name.get("provider_game_id") or "").strip()
                    record_id = game_id
                    operation = self.operation_journal.get(record_id)
                    if operation and operation.get("state") in ACTIVE_STATES:
                        session = self.installations.setdefault(record_id, {
                            "baseline": {}, "requested_at": operation["requested_at"],
                            "operation": operation["kind"], "requires_attention": False,
                            "token": self.operation_token(operation),
                            "stable_samples": 0, "candidate_signature": None,
                        })
                        session["baseline"]["game"] = normalized
                    genres = by_name.get("genres") or by_name.get("genre") or []
                    if isinstance(genres, str):
                        genres = [genres]
                    normalized["genres"] = [str(value).strip() for value in genres
                                              if str(value).strip()][:16]
                    try:
                        if "playtimeminutes" in by_name:
                            playtime_minutes = int(by_name["playtimeminutes"] or 0)
                        elif "playtime_minutes" in by_name:
                            playtime_minutes = int(by_name["playtime_minutes"] or 0)
                        else:
                            # Playnite's Game.Playtime value is expressed in seconds.
                            playtime_minutes = int(by_name.get("playtime") or 0) // 60
                        normalized["playtimeMinutes"] = max(0, playtime_minutes)
                    except (TypeError, ValueError):
                        normalized["playtimeMinutes"] = 0
                    connector_installing = bool(
                        normalized.get("installing") or normalized.get("isInstalling"))
                    if connector_installing and record_id not in self.installations:
                        baseline = self.installation_baseline_action() \
                            if self.installation_baseline_action else {}
                        baseline["game"] = normalized
                        self.installations[record_id] = {
                            "baseline": baseline, "requested_at": time.time(),
                            "requires_attention": False, "stable_samples": 0,
                            "candidate_signature": None,
                        }
                    self.library_staging[game_id] = normalized
                # Older connectors did not bracket snapshots. Preserve their
                # behavior while keeping the visible cache stable for current
                # connectors until snapshotComplete arrives.
                if not self.snapshot_in_progress:
                    self.playnite_library = dict(self.library_staging)
                    self._publish_playnite_snapshot_locked()
                    self._schedule_catalog_refresh_locked()
            elif kind == "snapshotComplete":
                self.playnite_library = dict(self.library_staging)
                self.snapshot_in_progress = False
                self._publish_playnite_snapshot_locked()
                self._schedule_catalog_refresh_locked()
            elif kind == "status" and isinstance(message.get("status"), dict):
                status = dict(message["status"])
                name = str(status.pop("name", ""))
                game_id = ""
                playnite_id = ""
                try:
                    playnite_id = str(status.get("id") or "").strip().lower()
                    if not PLAYNITE_ID_PATTERN.fullmatch(playnite_id):
                        raise ValueError("Invalid Playnite game ID.")
                    matches = [record_id for record_id, game in self.library.items()
                               if str(game.get("playniteGameId") or "").casefold()
                               == playnite_id]
                    game_id = matches[0] if len(matches) == 1 else ""
                except ValueError:
                    pass
                if game_id:
                    status["id"] = game_id
                game = self.library.get(game_id)
                metadata = self.playnite_library.get(playnite_id)
                advisory = (game is not None and self.game_operations.provider_for(game)
                            in {self.game_operations.steam, self.game_operations.epic}) \
                    or self.game_operations._playnite_source(metadata or {}).casefold() \
                    in {"steam", "epic"}
                current_state = str(self.current.get("state") or "").casefold()
                trace = self._active_game_trace or {}
                reconciliation_status = False
                if name in {"gameStarted", "gameStopped"} \
                        and current_state in {"reconciling", "ambiguous"} \
                        and str(trace.get("provider") or "") == "playnite":
                    reconciliation_status = True
                    expected_guid = str(trace.get("playnite_guid") or "")
                    expected_pid = int(trace.get("process_id") or 0)
                    observed_pid = int(status.get("processId") or
                                       status.get("process_id") or 0)
                    if current_state == "reconciling" and name == "gameStarted":
                        if playnite_id == expected_guid and observed_pid == expected_pid:
                            self._native_reconciliation_confirmation = (
                                playnite_id, observed_pid)
                            self._attempt_active_game_reconciliation_locked()
                        else:
                            self._mark_reconciliation_ambiguous_locked(
                                "active_game_connector_mismatch")
                    elif current_state == "reconciling" and name == "gameStopped" \
                            and playnite_id == expected_guid \
                            and observed_pid == expected_pid:
                        self._clear_reconciliation_locked(
                            "active_game_connector_stopped")
                    self._publish_locked("playnite-status", {"name": name, **status})
                active_id = str(self.current.get("id") or "")
                active_state = str(self.current.get("state") or "").casefold()
                if reconciliation_status:
                    pass
                elif name in {"gameStarted", "gameStopped"} and advisory:
                    self._publish_locked("playnite-status", {"name": name, **status})
                elif name == "gameStarted":
                    if not game_id:
                        self._publish_locked("playnite-status", {"name": name, **status})
                    elif active_state in {"starting", "running", "stopping"} \
                            and active_id and active_id != game_id:
                        self._publish_locked("playnite-status", {"name": name, **status})
                    elif active_state == "stopping" and active_id == game_id:
                        self.current.update(status)
                        self.current["state"] = "stopping"
                        process_id = int(status.get("processId") or
                                         status.get("process_id") or 0)
                        if process_id and not self._request_game_close_locked(process_id):
                            self.readiness.update({
                                "ready": False, "reason": "game_stop_close_rejected",
                                "stable_samples": 0,
                            })
                            self._publish_locked("game-stop-rejected", {
                                "id": game_id, "process_id": process_id,
                                "reason": "game_stop_close_rejected",
                            })
                    else:
                        self.current = {"state": "running", **status}
                        self._native_reconciliation_confirmation = (
                            playnite_id, int(status.get("processId") or status.get("process_id") or 0))
                        self.readiness = {
                            "ready": False,
                            "reason": "waiting_for_game_window",
                            "target_kind": "game",
                            "game_id": game_id,
                            "stable_samples": 0,
                        }
                        self._last_window_signature = None
                        self._save_active_game_trace_locked()
                        self._publish_locked("game-running", dict(self.current))
                elif name == "gameStopped" and game_id and active_id == game_id \
                        and active_state in {"starting", "running", "stopping"}:
                    self._complete_game_stop_locked()
                elif name == "gameStopped":
                    self._publish_locked("playnite-status", {"name": name, **status})
                elif name in {"gameInstalled", "gameInstallationCancelled"}:
                    game = self.library.get(game_id)
                    title = str((game or {}).get("name") or status.get("title") or "")
                    operation = self.operation_journal.get(game_id) or {}
                    journal_provider = self.game_operations.provider_for_label(
                        str(operation.get("provider") or ""))
                    library_provider = self.game_operations.provider_for(game) \
                        if game is not None else self.game_operations.generic
                    if self.game_operations.epic in {
                            journal_provider, library_provider}:
                        # Playnite's Epic connector reflects Epic Launcher/EOSH,
                        # while Legendary is the sole installation authority.
                        self._publish_locked("playnite-status", {"name": name, **status})
                        self._schedule_external_reconciliation_locked()
                    elif self.game_operations.steam in {
                            journal_provider, library_provider}:
                        # Steam manifests remain authoritative; connector events
                        # carry no operation-generation identity and cannot finish
                        # or cancel a newer Steam request for the same game.
                        session = self.installations.get(game_id)
                        token = (session or {}).get("token")
                        if name == "gameInstallationCancelled" \
                                and session is not None \
                                and session.get("primary_started") \
                                and isinstance(token, tuple) \
                                and self._operation_active_locked(game_id, token) \
                                and session.get("cancellation_token") != token:
                            session.update({
                                "cancellation_token": token,
                                "cancellation_observed_at": self.clock(),
                                "cancellation_inactive_samples": 0,
                            })
                        self._publish_locked("playnite-status", {"name": name, **status})
                        sender = self.command_sender
                        if sender is not None:
                            try:
                                sender({"type": "command", "command": "snapshot"})
                            except Exception as error:
                                self.last_error = str(error)[:500]
                    else:
                        self.installations.pop(game_id, None)
                        self.operation_journal.update(
                            game_id, "completed" if name == "gameInstalled" else "cancelled")
                        if game is not None:
                            game["installing"] = False
                            if name == "gameInstalled":
                                game["installed"] = True
                            self._apply_installation_fields_locked(game_id, game)
                            self._save_library_cache_locked()
                        event_name = "game-installed" if name == "gameInstalled" \
                            else "game-installation-cancelled"
                        self._publish_locked(event_name, {
                            "id": game_id,
                            "name": title,
                        })
                else:
                    self._publish_locked(name or "playnite-status", status)

    def send_command(self, command: str, **values: Any) -> dict[str, Any]:
        with self.lock:
            sender = self.command_sender
            if not self.connected or sender is None:
                raise ConnectionError(self.last_error or "Playnite connector is offline.")
            message = {"type": "command", "command": command, **values}
            sender(message)
            return {"accepted": True, "command": command}

    def _operation_baseline_locked(self, game: dict[str, Any], operation: str,
                                   restored: bool = False) -> dict[str, Any]:
        baseline = self.installation_baseline_action() \
            if self.installation_baseline_action else {}
        baseline.update({
            "game": dict(game), "operation": operation, "restored": restored,
        })
        provider_baseline = self.game_operations.operation_baseline(game)
        if provider_baseline is not None:
            baseline["steam_baseline"] = provider_baseline
        return baseline

    def _dispatch_operation(self, game_id: str,
                            token: tuple[str, str, float],
                            game: dict[str, Any], operation: str) -> dict[str, Any]:
        is_steam = self.game_operations.provider_for(game) is self.game_operations.steam
        sender: Callable[..., dict[str, Any]] = self.send_command
        if is_steam:
            with self.lock:
                if not self._operation_active_locked(game_id, token):
                    return {"accepted": False, "command": operation, "stale": True}
                session = self.installations[game_id]
                if session.get("primary_attempted"):
                    return {"accepted": True, "command": operation,
                            "already_dispatched": True}
                session["baseline"] = self._operation_baseline_locked(
                    game, operation, False)
                session.update({
                    "primary_attempted": True,
                    "primary_dispatched_at": self.clock(),
                    "recovery_pending": False,
                })
                self.operation_journal.update(
                    game_id, "uninstalling" if operation == "uninstall" else "preparing")
                current = self.library.get(game_id)
                if current is not None:
                    self._apply_installation_fields_locked(game_id, current)

        dispatch = self.game_operations.dispatch_install \
            if operation == "install" else self.game_operations.dispatch_uninstall
        result = dispatch(dict(game), sender, token) \
            if not is_steam and self.game_operations.provider_for(game) \
            is self.game_operations.epic else dispatch(dict(game), sender)
        if not is_steam and self.game_operations.provider_for(game) is self.game_operations.epic:
            if self._is_epic_terminal_failure(result):
                if self._fail_epic_operation(game_id, result, token, "dispatch"):
                    return {
                        "accepted": False, "command": operation, "provider": "epic",
                        "dispatch": str(result.get("dispatch") or "none"),
                        "requires_attention": False, "operation_state": "failed",
                        "reason": str(result.get("reason") or "epic_operation_failed"),
                        "launcher": str(result.get("launcher") or "legendary.exe"),
                        "exit_code": int(result.get("exit_code") or 0),
                        "error_excerpt": str(result.get("error_excerpt") or "")[:300],
                    }
            if self.operation_audit is not None:
                try:
                    self.operation_audit("epic_operation_dispatched", {
                        "game_id": game_id, "kind": operation,
                        "dispatch": str(result.get("dispatch") or ""),
                        "fallback_reason": str(result.get("fallback_reason") or ""),
                        "reason": str(result.get("reason") or ""),
                    })
                except Exception:
                    pass
        if is_steam:
            direct_dispatched = False
            with self.lock:
                if self._operation_active_locked(game_id, token):
                    session = self.installations[game_id]
                    session["primary_dispatched"] = result.get("dispatch") == "direct"
                    direct_dispatched = bool(session["primary_dispatched"])
                    if result.get("accepted") is False:
                        reason = str(result.get("reason") or
                                     "steam_direct_dispatch_failed")
                        self.operation_journal.update(
                            game_id, "failed", detail=reason, launcher="steam.exe")
                        self.installations.pop(game_id, None)
                        current = self.library.get(game_id)
                        if current is not None:
                            current["installing"] = False
                            current["uninstalling"] = False
                            self._apply_installation_fields_locked(game_id, current)
                            self._save_library_cache_locked()
                        return result
                    journal = self.operation_journal.get(game_id) or {}
                    if operation == "uninstall" \
                            and journal.get("state") != "attention_required":
                        self.operation_journal.update(game_id, "uninstalling")
                        current = self.library.get(game_id)
                        if current is not None:
                            self._apply_installation_fields_locked(game_id, current)
            if direct_dispatched:
                self._audit_steam(
                    "steam_direct_dispatched", game_id, token, game)
        return result

    def _launch_provider(self, game: dict[str, Any], attempt: dict[str, Any],
                         task_id: str | None = None,
                         send_command: Callable[..., dict[str, Any]] | None = None) -> dict[str, Any]:
        def allowed() -> bool:
            with self.lock:
                return self.current is attempt and self.current.get("state") == "starting"

        def dispatch_if_current(action: Callable[[], dict[str, Any]]) -> dict[str, Any]:
            with self.lock:
                if not allowed():
                    return {"ready": False, "reason": "launch_cancelled"}
                return action()

        def prepare() -> dict[str, Any]:
            if not allowed():
                return {"ready": False, "reason": "launch_cancelled"}
            if self.nonsteam_launch_preparation is None:
                return {"ready": False, "reason": "steam_close_preparation_unavailable"}
            return self.nonsteam_launch_preparation(dispatch_if_current)

        return self.game_operations.launch(
            game, task_id, send_command, prepare_nonsteam=prepare, launch_allowed=allowed)

    def start_game(self, game_id: Any) -> dict[str, Any]:
        normalized = self.resolve_game_id(game_id)
        with self.lock:
            game = self.library.get(normalized)
            if game is None:
                raise FileNotFoundError("Game record was not found.")
            current_id = str(self.current.get("id") or "")
            current_state = str(self.current.get("state") or "").casefold()
            if current_state in {"reconciling", "ambiguous"}:
                return {
                    "accepted": False,
                    "command": "launch",
                    "reason": "game_identity_" + current_state,
                    "active_game_id": current_id,
                }
            if current_id and current_state in {"starting", "running", "stopping"}:
                if current_state == "stopping":
                    return {
                        "accepted": False,
                        "command": "launch",
                        "reason": "game_stopping",
                        "active_game_id": current_id,
                    }
                if current_id == normalized:
                    return {
                        "accepted": True,
                        "command": "resume",
                        "provider": str(game.get("provider") or "playnite"),
                        "already_running": True,
                    }
                return {
                    "accepted": False,
                    "command": "launch",
                    "reason": "another_game_running",
                    "active_game_id": current_id,
                }
            provider = self.game_operations.provider_for(game)
            self._active_game_trace = None
            self._native_reconciliation_confirmation = None
            self._remove_active_game_trace()
            baseline_action = self.installation_baseline_action
            previous_current = dict(self.current)
            previous_readiness = dict(self.readiness)
        try:
            launch_baseline = baseline_action() if baseline_action else {}
        except Exception:
            launch_baseline = {}
        with self.lock:
            self._launch_baseline = launch_baseline
            self._launcher_candidate_signature = None
            self._launcher_candidate_samples = 0
            self._launcher_automation_attempted = False
            self._launcher_interaction_required = False
            self._launcher_invoked_signature = None
            self._launcher_invoked_at = 0.0
        if provider in {self.game_operations.epic, self.game_operations.steam}:
            task_id = f"{normalized}:launch:{time.time_ns()}"
            with self.lock:
                self.current = {
                    "state": "starting", "id": normalized,
                    "title": str(game.get("name") or ""),
                    "installDir": str(game.get("installDir") or
                                      game.get("install_dir") or ""),
                    "exe": str(game.get("exe") or game.get("executable") or ""),
                    "source": str(game.get("libraryName") or ""),
                    "provider": str(game.get("provider") or ""),
                    "providerGameId": str(
                        game.get("providerGameId") or ""),
                    "launchTaskId": task_id,
                    "launchRequestedAt": self.clock(),
                }
                self.readiness = {
                    "ready": False,
                    "reason": "game_starting",
                    "target_kind": "game",
                    "game_id": normalized,
                    "launch_task_id": task_id,
                    "stable_samples": 0,
                }
                self._last_window_signature = None
                attempt = self.current
            try:
                result = self._launch_provider(dict(game), attempt, task_id)
            except Exception:
                with self.lock:
                    if self.current is attempt:
                        if self.current.get("state") == "stopping":
                            self._complete_game_stop_locked()
                        elif self.current.get("state") == "starting":
                            self.current = previous_current
                            self.readiness = previous_readiness
                raise
            with self.lock:
                if self.current is attempt:
                    self.current.update({
                        "installDir": str(result.get("install_directory") or
                                          self.current.get("installDir") or ""),
                        "exe": str(result.get("executable") or
                                   self.current.get("exe") or ""),
                    })
                    if not result.get("accepted"):
                        self.game_operations.finish_process(task_id)
                        if self.current.get("state") == "stopping":
                            self._complete_game_stop_locked()
                        elif self.current.get("state") == "starting":
                            self.current = previous_current
                            self.readiness = previous_readiness
                    elif self.current.get("state") == "starting":
                        self._publish_locked("game-starting", {
                            "id": normalized,
                            "source": str(result.get("dispatch") or "direct"),
                        })
                    elif self.current.get("state") == "stopping":
                        self.game_operations.finish_process(task_id)
                        self._publish_locked("game-stop-dispatch-completed", {
                            "id": normalized,
                        })
            return result
        with self.lock:
            self.current = {
                "state": "starting", "id": normalized,
                "title": str((game or {}).get("name") or ""),
                "installDir": str((game or {}).get("installDir") or
                                  (game or {}).get("install_dir") or ""),
                "exe": str((game or {}).get("exe") or
                            (game or {}).get("executable") or ""),
                "source": str((game or {}).get("source") or ""),
                "providerGameId": str((game or {}).get("providerGameId") or ""),
                "launchRequestedAt": self.clock(),
            }
            self.readiness = {
                "ready": False,
                "reason": "game_starting",
                "target_kind": "game",
                "game_id": normalized,
                "stable_samples": 0,
            }
            self._publish_locked("game-starting", {"id": normalized})
            self._last_window_signature = None
            attempt = self.current
        try:
            result = self._launch_provider(dict(game), attempt, send_command=self.send_command)
            if not result.get("accepted"):
                with self.lock:
                    if self.current is attempt:
                        if self.current.get("state") == "stopping":
                            self._complete_game_stop_locked()
                        elif self.current.get("state") == "starting":
                            self.current = previous_current
                            self.readiness = previous_readiness
            return result
        except Exception:
            with self.lock:
                if self.current is attempt:
                    if self.current.get("state") == "stopping":
                        self._complete_game_stop_locked()
                    elif self.current.get("state") == "starting":
                        self.current = {"state": "idle"}
                        self.readiness = {
                            "ready": False,
                            "reason": "waiting_for_game_identity",
                            "target_kind": "playnite",
                            "stable_samples": 0,
                        }
                        self._launch_baseline = {}
                        self._launcher_candidate_signature = None
                        self._launcher_candidate_samples = 0
                        self._launcher_automation_attempted = False
                        self._launcher_interaction_required = False
                        self._launcher_invoked_signature = None
                        self._launcher_invoked_at = 0.0
            raise

    def install_game(self, game_id: Any) -> dict[str, Any]:
        normalized = self.resolve_game_id(game_id)
        with self.lock:
            candidate = self.library.get(normalized)
            is_epic = candidate is not None and self.game_operations.provider_for(candidate) \
                is self.game_operations.epic
        if is_epic:
            snapshot = self.game_operations.external_snapshot()
            installed = self.game_operations.installed_from_external_snapshot(
                candidate, snapshot)
            if not snapshot.get("available") or (installed is None
                                                  and not snapshot.get("complete")):
                return {"accepted": False, "command": "install", "provider": "epic",
                        "requires_attention": False,
                        "reason": "legendary_verification_failed"}
            if installed is None:
                migration = self.game_operations.migration_candidate(candidate)
                if migration is not None:
                    with self.lock:
                        current = self.library.get(normalized)
                        if current is not None:
                            current.update({
                                "installed": False, "isInstalled": False,
                                "installing": False, "isInstalling": False,
                                "installDir": "", "legendaryImportRequired": True,
                                "legendaryImportDirectory": str(
                                    migration.get("install_directory") or ""),
                            })
            with self.lock:
                current = self.library.get(normalized)
                if current is not None:
                    current.update({
                        "installed": installed is not None,
                        "isInstalled": installed is not None,
                        "installDir": str((installed or {}).get(
                            "install_directory") or ""),
                    })
        with self.lock:
            game = self.library.get(normalized)
            if game is None:
                raise FileNotFoundError("Game record was not found.")
            if bool(game.get("installed") or game.get("isInstalled")):
                raise ValueError("Playnite game is already installed.")
            if bool(game.get("installing") or game.get("isInstalling")):
                return {"accepted": True, "command": "install", "already_installing": True}
            self._require_operation_prompt_idle(normalized)
            provider = self.game_operations.provider_label(game)
            operation = self.operation_journal.begin(
                normalized, "install", provider, str(game.get("name") or ""))
            if operation.get("state") in ACTIVE_STATES and normalized in self.installations:
                return {"accepted": True, "command": "install", "already_installing": True,
                        "operation_state": operation["state"]}
            token = self.operation_token(operation)
            self.installations[normalized] = {
                "baseline": {"game": dict(game), "operation": "install",
                             "task_id": token},
                "requested_at": float(operation["requested_at"]),
                "operation": "install",
                "token": token,
                "requires_attention": False, "stable_samples": 0,
                "candidate_signature": None,
                "primary_attempted": False, "primary_started": False,
                "recovery_pending": False,
            }
            game["installing"] = True
            self.external_uninstalled_overrides.discard(normalized)
            self._apply_installation_fields_locked(normalized, game)
            title = str(game.get("name") or "")
            self._save_library_cache_locked()
            self._publish_locked("game-installing", {
                "id": normalized,
                "name": title,
            })
        try:
            result = self._dispatch_operation(
                normalized, token, dict(game), "install")
            manual = self.game_operations.manual_attention(game, "install")
            if manual:
                self._mark_manual_confirmation(normalized, manual, token)
                return {**result, "requires_attention": True,
                        "attention_reason": str(manual["reason"])}
            return result
        except Exception:
            with self.lock:
                if not self._operation_active_locked(normalized, token):
                    raise
                self.operation_journal.update(normalized, "failed", detail="dispatch_failed")
                current = self.library.get(normalized)
                if current is not None:
                    current["installing"] = False
                    self.installations.pop(normalized, None)
                    self._apply_installation_fields_locked(normalized, current)
                    self._save_library_cache_locked()
                self._publish_locked("game-installation-failed", {
                    "id": normalized,
                    "name": title,
                })
            raise

    def uninstall_game(self, game_id: Any) -> dict[str, Any]:
        normalized = self.resolve_game_id(game_id)
        with self.lock:
            candidate = self.library.get(normalized)
            is_epic = candidate is not None and self.game_operations.provider_for(candidate) \
                is self.game_operations.epic
        if is_epic:
            snapshot = self.game_operations.external_snapshot()
            installed = self.game_operations.installed_from_external_snapshot(
                candidate, snapshot)
            if not snapshot.get("available") or (installed is None
                                                  and not snapshot.get("complete")):
                return {"accepted": False, "command": "uninstall", "provider": "epic",
                        "requires_attention": False,
                        "reason": "legendary_verification_failed"}
            with self.lock:
                current = self.library.get(normalized)
                if current is not None:
                    current.update({
                        "installed": installed is not None,
                        "isInstalled": installed is not None,
                        "installDir": str((installed or {}).get(
                            "install_directory") or ""),
                    })
        with self.lock:
            game = self.library.get(normalized)
            if game is None:
                raise FileNotFoundError("Game record was not found.")
            if not bool(game.get("installed") or game.get("isInstalled")):
                raise ValueError("Playnite game is not installed.")
            self._require_operation_prompt_idle(normalized)
            provider = self.game_operations.provider_label(game)
            operation = self.operation_journal.begin(
                normalized, "uninstall", provider, str(game.get("name") or ""))
            if operation.get("state") in ACTIVE_STATES and normalized in self.installations:
                return {"accepted": True, "command": "uninstall", "already_uninstalling": True,
                        "operation_state": operation["state"]}
            token = self.operation_token(operation)
            self.installations[normalized] = {
                "baseline": {"game": dict(game), "operation": "uninstall",
                             "task_id": token},
                "requested_at": float(operation["requested_at"]),
                "operation": "uninstall", "requires_attention": False,
                "token": token,
                "stable_samples": 0, "candidate_signature": None,
                "primary_attempted": False, "primary_started": False,
                "recovery_pending": False,
            }
            self._apply_installation_fields_locked(normalized, game)
            self._save_library_cache_locked()
        try:
            result = self._dispatch_operation(
                normalized, token, dict(game), "uninstall")
            manual = self.game_operations.manual_attention(game, "uninstall")
            if manual:
                self._mark_manual_confirmation(normalized, manual, token)
                return {**result, "requires_attention": True,
                        "attention_reason": str(manual["reason"])}
            return result
        except Exception:
            with self.lock:
                if not self._operation_active_locked(normalized, token):
                    raise
                self.operation_journal.update(normalized, "failed", detail="dispatch_failed")
                self.installations.pop(normalized, None)
                self._apply_installation_fields_locked(normalized, game)
            raise

    def _require_operation_prompt_idle(self, requested_game_id: str) -> None:
        for game_id, session in self.installations.items():
            if game_id == requested_game_id:
                continue
            operation = self.operation_journal.get(game_id) or {}
            game = self.library.get(game_id)
            is_steam = self.game_operations.provider_for_label(
                str(operation.get("provider") or "")) is self.game_operations.steam \
                or (game is not None and self.game_operations.provider_for(game)
                    is self.game_operations.steam)
            active = operation.get("state") in ACTIVE_STATES
            if active and ((is_steam and not session.get("primary_started"))
                           or operation.get("state") in {
                               "preparing", "attention_required"}
                           or session.get("automation_in_progress")):
                raise RuntimeError("Another installation operation is awaiting confirmation.")

    def _mark_manual_confirmation(self, game_id: str, policy: dict[str, Any],
                                  token: tuple[str, str, float] | None = None) -> None:
        with self.lock:
            session = self.installations.get(game_id)
            game = self.library.get(game_id)
            current_token = (session or {}).get("token")
            if session is None or game is None or (token is not None and (
                    current_token != token or not self._operation_active_locked(game_id, token))):
                return
            reason = str(policy["reason"])
            launcher = str(policy["launcher"])
            session.update({
                "requires_attention": True, "reason": reason,
                "hwnd": 0, "window_title": "", "image": launcher,
                "attention_origin": str(policy.get("attention_origin") or "manual"),
                "stable_samples": 0, "candidate_signature": None,
            })
            self.operation_journal.update(
                game_id, "attention_required", detail=reason, launcher=launcher)
            if self.game_operations.provider_for(game) is self.game_operations.steam \
                    and isinstance(current_token, tuple):
                self._audit_steam(
                    "steam_attention_required", game_id, current_token, game,
                    reason=reason, launcher=launcher)
            self._apply_installation_fields_locked(game_id, game)
            self._save_library_cache_locked()
            self._publish_locked("game-installation-attention-required", {
                "id": game_id, "name": str(game.get("name") or ""),
                "reason": reason, "launcher": launcher,
            })

    @staticmethod
    def _is_epic_terminal_failure(sample: dict[str, Any]) -> bool:
        reason = str(sample.get("reason") or "")
        return sample.get("accepted") is False or (
            reason.startswith("legendary_")
            and sample.get("requires_attention") is True)

    def apply_launch_process_sample(self, sample: dict[str, Any],
                                    task_id: str) -> None:
        if not sample.get("requires_attention"):
            return
        with self.lock:
            if self.current.get("launchTaskId") != task_id \
                    or str(self.current.get("state") or "").casefold() != "starting":
                return
            game_id = str(self.current.get("id") or "")
            reason = str(sample.get("reason") or "provider_launch_failed")
            self.current = {"state": "failed", "id": game_id, "reason": reason}
            self.readiness.update({
                "ready": False, "reason": reason, "stable_samples": 0,
                "exit_code": int(sample.get("exit_code") or 0),
                "error_excerpt": str(sample.get("error_excerpt") or "")[:300],
            })
            self._publish_locked("game-launch-failed", {
                "id": game_id, "reason": reason,
                "exit_code": int(sample.get("exit_code") or 0),
                "error_excerpt": str(sample.get("error_excerpt") or "")[:300],
            })

    def _fail_epic_operation(self, game_id: str, sample: dict[str, Any],
                             token: tuple[str, str, float] | None,
                             stage: str) -> bool:
        reason = str(sample.get("reason") or "epic_operation_failed")
        with self.lock:
            session = self.installations.get(game_id)
            game = self.library.get(game_id)
            if session is None or game is None \
                    or not self._operation_result_current_locked(game_id, token) \
                    or self.game_operations.provider_for(game) \
                    is not self.game_operations.epic:
                return False
            operation = str(session.get("operation") or "install")
            self.operation_journal.update(
                game_id, "failed", detail=reason,
                launcher=str(sample.get("launcher") or "legendary.exe"))
            self.installations.pop(game_id, None)
            game["installing"] = False
            game["uninstalling"] = False
            self.external_installed_overrides.pop(game_id, None)
            self.external_uninstalled_overrides.discard(game_id)
            self._apply_installation_fields_locked(game_id, game)
            self._save_library_cache_locked()
            self._publish_locked("game-installation-failed", {
                "id": game_id, "name": str(game.get("name") or ""),
                "operation": operation, "reason": reason,
            })
        if self.operation_audit is not None:
            try:
                self.operation_audit("epic_operation_failed", {
                    "game_id": game_id, "kind": operation, "reason": reason,
                    "stage": stage, "dispatch": str(sample.get("dispatch") or "none"),
                    "exit_code": int(sample.get("exit_code") or 0),
                    "error_excerpt": str(sample.get("error_excerpt") or "")[:300],
                })
            except Exception:
                pass
        return True

    def installation_probes(self) -> list[
            tuple[str, dict[str, Any], tuple[str, str, float] | None]]:
        with self.lock:
            return [(game_id, dict(session.get("baseline") or {}), session.get("token"))
                    for game_id, session in self.installations.items()
                    if (session.get("baseline") or {}).get("game")
                    and self.clock() - float(session.get("requested_at") or 0) >= 1.5]

    def _auto_confirm_installation(self, game_id: str, hwnd: int, operation: str,
                                   game_name: str,
                                   signature: tuple[Any, ...],
                                   allow_visual_fallback: bool,
                                   token: tuple[str, str, float] | None) -> None:
        with self.lock:
            if not self._operation_result_current_locked(game_id, token):
                return
            game = dict(self.library.get(game_id) or {})
        automated = self.game_operations.confirm_operation(
            game, hwnd, operation, game_name, allow_visual_fallback)
        with self.lock:
            session = self.installations.get(game_id)
            game = self.library.get(game_id)
            if session is None or game is None \
                    or not self._operation_result_current_locked(game_id, token) \
                    or session.get("automation_signature") != signature:
                return
            session.update({"stable_samples": 0, "candidate_signature": None})
            session["automation_in_progress"] = False
            if automated.get("clicked"):
                if token is not None \
                        and self.game_operations.provider_for(game) \
                        is self.game_operations.steam:
                    self._audit_steam(
                        "steam_automation_succeeded", game_id, token, game,
                        method=str(automated.get("method") or "unknown"),
                        window_handle=hwnd)
                self.operation_journal.update(
                    game_id, "uninstalling" if operation == "uninstall" else "preparing")
                session["prompt_confirmed_at"] = self.clock()
                self._publish_locked("game-installation-auto-confirmed", {
                    "id": game_id, "name": str(game.get("name") or ""),
                    "operation": operation,
                })
            else:
                session["automation_failure"] = str(
                    automated.get("reason") or "automation_failed")[:200]
                if token is not None \
                        and self.game_operations.provider_for(game) \
                        is self.game_operations.steam:
                    self._audit_steam(
                        "steam_automation_failed", game_id, token, game,
                        method=str(automated.get("method") or "unknown"),
                        reason=session["automation_failure"], window_handle=hwnd)


    def _handle_steam_probe(self, game_id: str, sample: dict[str, Any],
                            token: tuple[str, str, float] | None) -> bool:
        if token is None:
            return False
        action = ""
        game_copy: dict[str, Any] = {}
        operation = "install"
        with self.lock:
            if not self._operation_active_locked(game_id, token):
                return True
            session = self.installations[game_id]
            game = self.library.get(game_id)
            if game is None or self.game_operations.provider_for(game) \
                    is not self.game_operations.steam:
                return False
            session.pop("uninstall_absent_samples", None)
            operation = str(session.get("operation") or "install")
            game_copy = dict(game)
            if sample.get("started"):
                if session.get("cancellation_token") == token:
                    session["cancellation_observed_at"] = self.clock()
                    session["cancellation_inactive_samples"] = 0
                first_activity = not bool(session.get("primary_started"))
                dispatch_path = "direct" if session.get("primary_dispatched") \
                    else "restart_observation"
                was_attention = bool(session.get("requires_attention"))
                session.update({
                    "primary_started": True, "recovery_pending": False,
                    "requires_attention": False, "reason": "", "hwnd": 0,
                    "window_title": "", "image": "", "stable_samples": 0,
                })
                phase = str(sample.get("phase") or "")
                if operation == "uninstall":
                    state = "uninstalling"
                elif phase == "verifying":
                    state = "verifying"
                elif sample.get("progress") is not None:
                    state = "downloading"
                else:
                    state = "installing"
                progress = int(sample["progress"]) \
                    if sample.get("progress") is not None else None
                self.operation_journal.update(game_id, state, progress=progress)
                if first_activity:
                    self._audit_steam(
                        "steam_activity_observed", game_id, token, game,
                        dispatch_path=dispatch_path, phase=phase,
                        progress=progress,
                        manifest_present=bool(sample.get("manifest_present")),
                        scan_complete=bool(sample.get("scan_complete")))
                self._apply_installation_fields_locked(game_id, game)
                self._save_library_cache_locked()
                if was_attention:
                    self._publish_locked("game-installation-resumed", {
                        "id": game_id, "name": str(game.get("name") or ""),
                    })
                return True
            cancellation_token = session.get("cancellation_token")
            if cancellation_token == token:
                if sample.get("requires_attention"):
                    return False
                now = self.clock()
                healthy_inactivity = bool(
                    sample.get("scan_available") and sample.get("scan_complete")
                    and sample.get("phase") == "not_started")
                if healthy_inactivity:
                    inactive_samples = int(
                        session.get("cancellation_inactive_samples") or 0) + 1
                    session["cancellation_inactive_samples"] = inactive_samples
                    if inactive_samples < REQUIRED_STABLE_SAMPLES:
                        return True
                    self.installations.pop(game_id, None)
                    self.operation_journal.update(game_id, "cancelled")
                    game["installing"] = False
                    game["uninstalling"] = False
                    self._apply_installation_fields_locked(game_id, game)
                    self._save_library_cache_locked()
                    self._publish_locked("game-installation-cancelled", {
                        "id": game_id, "name": str(game.get("name") or ""),
                        "operation": operation,
                    })
                    return True
                session["cancellation_inactive_samples"] = 0
                observed_at = session.get("cancellation_observed_at")
                if observed_at is not None and now - float(observed_at) >= \
                        STEAM_CANCELLATION_EVIDENCE_TIMEOUT:
                    action = "cancellation_attention"
                else:
                    return True
            if action == "cancellation_attention":
                pass
            elif session.get("primary_started"):
                return True
            elif session.get("requires_attention") and not session.get("hwnd") \
                    and str(session.get("reason") or "").startswith("steam_"):
                return True
            elif sample.get("requires_attention"):
                if session.get("recovery_pending"):
                    session.update({
                        "recovery_pending": False,
                        "primary_attempted": True,
                    })
                return False
            else:
                now = self.clock()
                phase = str(sample.get("phase") or "")
                if session.get("recovery_pending"):
                    if phase in {"not_started", "scan_unavailable"}:
                        action = "primary"
                    elif now - float(session.get("recovery_started_at") or now) >= \
                            STEAM_PRIMARY_START_TIMEOUT:
                        action = "attention"
                    else:
                        return True
                elif \
                        now - float(session.get("primary_dispatched_at") or now) >= \
                        STEAM_PRIMARY_START_TIMEOUT:
                    action = "attention"
                else:
                    return True
        if action == "cancellation_attention":
            self._mark_manual_confirmation(game_id, {
                "reason": "steam_cancellation_inconclusive",
                "launcher": "steam.exe",
            }, token)
        elif action == "primary":
            try:
                self._dispatch_operation(game_id, token, game_copy, operation)
            except Exception:
                self._mark_manual_confirmation(game_id, {
                    "reason": "steam_direct_dispatch_failed", "launcher": "steam.exe",
                }, token)
        elif action == "attention":
            self._mark_manual_confirmation(game_id, {
                "reason": "steam_operation_not_started", "launcher": "steam.exe",
            }, token)
        return True

    def apply_installation_probe(self, game_id: str, sample: dict[str, Any],
                                 token: tuple[str, str, float] | None = None) -> None:
        with self.lock:
            session = self.installations.get(game_id)
            if session is None:
                return
            current_token = session.get("token")
            if token is None:
                token = current_token
            if token != current_token \
                    or not self._operation_result_current_locked(game_id, token):
                return
        if sample.get("started") and str(sample.get("provider") or "").casefold() == "epic":
            with self.lock:
                session = self.installations.get(game_id)
                game = self.library.get(game_id)
                if session is None or game is None \
                        or not self._operation_result_current_locked(game_id, token):
                    return
                operation = str(session.get("operation") or "install")
                session["primary_started"] = True
                state = "uninstalling" if operation == "uninstall" else \
                    "downloading" if sample.get("progress") is not None else "installing"
                progress = int(sample["progress"]) \
                    if sample.get("progress") is not None else None
                self.operation_journal.update(game_id, state, progress=progress)
                self._apply_installation_fields_locked(game_id, game)
                self._save_library_cache_locked()
            return
        if sample.get("uninstalled"):
            sender = None
            with self.lock:
                session = self.installations.get(game_id)
                game = self.library.get(game_id)
                if session is None or game is None \
                        or not self._operation_result_current_locked(game_id, token) \
                        or session.get("operation") != "uninstall":
                    return
                sampled_provider = self.game_operations.provider_for_label(
                    str(sample.get("provider") or ""))
                if sampled_provider in {
                        self.game_operations.epic, self.game_operations.steam}:
                    absent_samples = int(session.get("uninstall_absent_samples") or 0) + 1
                    session["uninstall_absent_samples"] = absent_samples
                    if absent_samples < REQUIRED_STABLE_SAMPLES:
                        return
                if sampled_provider is self.game_operations.steam and token is not None:
                    self._audit_steam(
                        "steam_authoritative_completion", game_id, token, game,
                        outcome="uninstalled",
                        stable_absence_samples=int(
                            session.get("uninstall_absent_samples") or 1))
                self.installations.pop(game_id, None)
                game.update({"installed": False, "isInstalled": False,
                             "installing": False, "isInstalling": False,
                             "uninstalling": False, "installDir": ""})
                self.external_installed_overrides.pop(game_id, None)
                self.external_uninstalled_overrides.add(game_id)
                self.operation_journal.update(game_id, "completed")
                self._apply_installation_fields_locked(game_id, game)
                self._save_library_cache_locked()
                self._publish_locked("game-uninstalled", {
                    "id": game_id, "name": str(game.get("name") or ""),
                    "source": str(sample.get("provider") or "external"),
                })
                sender = self.command_sender \
                    if sampled_provider is self.game_operations.generic else None
            if sender is not None:
                with self.lock:
                    current = self.operation_journal.get(game_id)
                    correlated = token is None or (
                        current is not None and self.operation_token(current) == token)
                    if correlated:
                        sender({"type": "command", "command": "mark-uninstalled",
                                "id": game_id})
            return
        if sample.get("installed"):
            install_directory = str(sample.get("install_directory") or "")
            sender = None
            with self.lock:
                session = self.installations.get(game_id)
                game = self.library.get(game_id)
                if session is None or game is None \
                        or not self._operation_result_current_locked(game_id, token):
                    return
                provider = self.game_operations.provider_for(game)
                if provider is self.game_operations.steam and token is not None:
                    self._audit_steam(
                        "steam_authoritative_completion", game_id, token, game,
                        outcome="installed", phase=str(sample.get("phase") or ""),
                        install_directory=install_directory)
                self.installations.pop(game_id, None)
                game.update({"installed": True, "isInstalled": True,
                             "installing": False, "isInstalling": False})
                self.external_uninstalled_overrides.discard(game_id)
                self.external_installed_overrides[game_id] = install_directory
                if install_directory:
                    game["installDir"] = install_directory
                self.operation_journal.update(game_id, "completed")
                self._apply_installation_fields_locked(game_id, game)
                self._save_library_cache_locked()
                self._publish_locked("game-installed", {
                    "id": game_id, "name": str(game.get("name") or ""),
                    "source": str(sample.get("provider") or "external"),
                })
                sender = self.command_sender \
                    if provider is self.game_operations.generic else None
            if sender is not None:
                with self.lock:
                    current = self.operation_journal.get(game_id)
                    correlated = token is None or (
                        current is not None and self.operation_token(current) == token)
                    if correlated:
                        sender({"type": "command", "command": "mark-installed",
                                "id": game_id, "install_directory": install_directory})
            return
        is_epic_sample = str(sample.get("provider") or "").casefold() == "epic"
        prompt_reason = str(sample.get("reason") or "")
        if is_epic_sample and (self._is_epic_terminal_failure(sample)
                               or (sample.get("requires_attention")
                                   and prompt_reason not in {
                                       "launcher_prompt", "secure_desktop"})):
            self._fail_epic_operation(game_id, sample, token, "runtime")
            return
        if self._handle_steam_probe(game_id, sample, token):
            return
        with self.lock:
            session = self.installations.get(game_id)
            game = self.library.get(game_id)
            if session is None or game is None \
                    or not self._operation_result_current_locked(game_id, token):
                return
            session.pop("uninstall_absent_samples", None)
            operation = str(session.get("operation") or "install")
            if operation == "install" and bool(
                    game.get("installed") or game.get("isInstalled")):
                return
            if sample.get("progress") is not None:
                self.operation_journal.update(
                    game_id, "downloading", progress=int(sample["progress"]))
                self._apply_installation_fields_locked(game_id, game)
            requires_attention = bool(sample.get("requires_attention"))
            tracked_hwnd = int(session.get("hwnd") or 0)
            sampled_hwnd = int(sample.get("hwnd") or 0)
            # Once a concrete launcher dialog has been identified, only that
            # window can keep the installation blocked. A different Steam/Epic
            # window must not inherit the attention state from the old baseline.
            if session.get("requires_attention") and tracked_hwnd > 0 \
                    and sampled_hwnd != tracked_hwnd:
                requires_attention = False
                sample = {
                    "requires_attention": False, "reason": "prompt_closed",
                    "hwnd": 0, "process_id": 0, "title": "", "image": "",
                }
            signature = (str(sample.get("reason") or ""), int(sample.get("hwnd") or 0),
                         int(sample.get("process_id") or 0), str(sample.get("title") or ""))
            if requires_attention and signature == session.get("acknowledged_signature"):
                requires_attention = False
                sample = {
                    "requires_attention": False, "reason": "prompt_acknowledged",
                    "hwnd": 0, "process_id": 0, "title": "", "image": "",
                }
            if session.get("automation_in_progress"):
                return
            stable = int(session.get("stable_samples") or 0) + 1 \
                if signature == session.get("candidate_signature") else 1
            session["candidate_signature"] = signature
            session["stable_samples"] = stable
            if not requires_attention:
                if session.get("requires_attention") and \
                        session.get("attention_origin") in {
                            "launcher_prompt", "secure_desktop"}:
                    if stable < 3:
                        return
                    session.update({
                        "requires_attention": False, "reason": "", "hwnd": 0,
                        "window_title": "", "image": "", "stable_samples": 0,
                    })
                    session["installing_since"] = time.time()
                    self.operation_journal.update(
                        game_id, "uninstalling" if operation == "uninstall" else "installing")
                    self._apply_installation_fields_locked(game_id, game)
                    self._save_library_cache_locked()
                    self._publish_locked("game-installation-resumed", {
                        "id": game_id, "name": str(game.get("name") or ""),
                    })
                return
            required_samples = 2 if sample.get("reason") == "secure_desktop" else 3
            if stable < required_samples:
                return
            if self.game_operations.can_auto_confirm(game, sample) \
                    and session.get("automation_signature") != signature \
                    :
                session["automation_signature"] = signature
                session["automation_in_progress"] = True
                threading.Thread(
                    target=self._auto_confirm_installation,
                    args=(game_id, sampled_hwnd, operation,
                          str(game.get("name") or ""), signature,
                          bool(sample.get("visual_confirmation_safe")), token),
                    name="LauncherOperationConfirmation", daemon=True).start()
                return
            first_attention = not bool(session.get("requires_attention"))
            session.update({
                "requires_attention": True,
                "reason": str(sample.get("reason") or "launcher_prompt"),
                "attention_origin": str(sample.get("reason") or "launcher_prompt"),
                "hwnd": int(sample.get("hwnd") or 0),
                "process_id": int(sample.get("process_id") or 0),
                "window_title": str(sample.get("title") or "")[:300],
                "image": str(sample.get("image") or "")[:200],
            })
            detail = session["reason"]
            if session.get("automation_failure"):
                detail += "; automation=" + session["automation_failure"]
            self.operation_journal.update(
                game_id, "attention_required", detail=detail,
                window_handle=session["hwnd"], window_title=session["window_title"],
                launcher=session["image"])
            self._apply_installation_fields_locked(game_id, game)
            self._save_library_cache_locked()
            if first_attention:
                if self.game_operations.provider_for(game) \
                        is self.game_operations.steam and token is not None:
                    self._audit_steam(
                        "steam_attention_required", game_id, token, game,
                        reason=session["reason"], launcher=session["image"])
                self._publish_locked("game-installation-attention-required", {
                    "id": game_id, "name": str(game.get("name") or ""),
                    "reason": session["reason"],
                    "window_title": session["window_title"],
                    "launcher": session["image"],
                })

    def focus_installation(self, game_id: Any) -> dict[str, Any]:
        normalized = self.resolve_game_id(game_id)
        with self.lock:
            session = dict(self.installations.get(normalized) or {})
            action = self.focus_installation_action
        if not session or not session.get("requires_attention"):
            raise ValueError("This installation does not require confirmation.")
        if session.get("reason") in {"secure_desktop", "epic_manual"}:
            raise PermissionError("The installation requires confirmation on the PC.")
        hwnd = int(session.get("hwnd") or 0)
        if not action or not hwnd:
            raise RuntimeError("The installation window is unavailable.")
        details = action(hwnd)
        if not details.get("focused"):
            raise RuntimeError("Windows rejected the installation window focus request.")
        return {"accepted": True, "command": "focus-installation", **details}

    def verify_installation(self, game_id: Any) -> dict[str, Any]:
        normalized = self.resolve_game_id(game_id)
        with self.lock:
            session = self.installations.get(normalized)
            probe = self.installation_probe_action
            baseline = dict((session or {}).get("baseline") or {})
            game = self.library.get(normalized)
            token = (session or {}).get("token")
        if session is None:
            if game is not None and bool(game.get("installed") or game.get("isInstalled")):
                return {
                    "accepted": True, "command": "verify-installation",
                    "status": "installed", "requires_attention": False,
                    "installing": False,
                }
            raise ValueError("No pending installation was found for this game.")
        if probe is None:
            raise RuntimeError("Installation verification is unavailable.")

        # Require the same stable evidence as the background observer. This avoids
        # treating a launcher window that merely flickered as a confirmed action.
        for sample_number in range(3):
            self.apply_installation_probe(normalized, probe(baseline), token)
            if sample_number < 2:
                time.sleep(0.15)

        with self.lock:
            current = self.installations.get(normalized) or {}
            if current.get("token") != token:
                return {
                    "accepted": True, "command": "verify-installation",
                    "status": "stale", "requires_attention": False,
                    "installing": bool((self.library.get(normalized) or {}).get("installing")),
                }
            attention = bool(current.get("requires_attention"))
            game = self.library.get(normalized) or {}
            if attention and current.get("reason") != "secure_desktop":
                current["acknowledged_signature"] = (
                    str(current.get("reason") or ""), int(current.get("hwnd") or 0),
                    int(current.get("process_id") or 0),
                    str(current.get("window_title") or ""),
                )
                current.update({
                    "requires_attention": False, "reason": "", "hwnd": 0,
                    "window_title": "", "image": "", "stable_samples": 0,
                })
                self.operation_journal.update(normalized, "installing")
                self._apply_installation_fields_locked(normalized, game)
                self._save_library_cache_locked()
                self._publish_locked("game-installation-resumed", {
                    "id": normalized, "name": str(game.get("name") or ""),
                })
                attention = False
            return {
                "accepted": True,
                "command": "verify-installation",
                "status": "attention_required" if attention else "installing",
                "requires_attention": attention,
                "installing": bool(game.get("installing") or game.get("isInstalling")),
            }

    def _complete_game_stop_locked(self) -> None:
        previous = dict(self.current)
        task_id = previous.get("launchTaskId")
        if task_id:
            self.game_operations.finish_process(task_id)
        self._active_game_trace = None
        self._native_reconciliation_confirmation = None
        self._remove_active_game_trace()
        self.current = {"state": "idle"}
        self.readiness = {
            "ready": False,
            "reason": "game_stopped",
            "target_kind": "none",
            "stable_samples": 0,
        }
        self._launch_baseline = {}
        self._launcher_candidate_signature = None
        self._launcher_candidate_samples = 0
        self._launcher_automation_attempted = False
        self._launcher_interaction_required = False
        self._launcher_invoked_signature = None
        self._launcher_invoked_at = 0.0
        self._last_window_signature = None
        self._publish_locked("game-stopped", previous)

    def _request_game_close_locked(self, process_id: int) -> bool:
        close = self.graceful_close
        if process_id <= 0 or close is None:
            return False
        requested_process_id = int(self.current.get("stopCloseRequestedPid") or 0)
        if requested_process_id:
            if requested_process_id == process_id:
                return True
            if self.current.get("stopTimedOut"):
                return False
        if self.current.get("stopCloseRetryArmed") \
                and not self._exact_stop_process_matches_locked(process_id):
            return False
        if not close(process_id):
            return False
        self.current["stopCloseRequestedPid"] = process_id
        self.current.pop("stopCloseRetryArmed", None)
        self.current.pop("stopTimedOut", None)
        self._publish_locked("game-stop-close-requested", {
            "id": str(self.current.get("id") or ""),
            "process_id": process_id,
        })
        return True

    def _exact_stop_process_matches_locked(self, process_id: int) -> bool:
        expected_path = self._normalized_process_path(
            self.current.get("processPath"))
        expected_started = int(self.current.get("processStartedFiletime") or 0)
        identity_action = self._process_identity_action
        identities_action = self._process_identities_action
        if process_id <= 0 or not expected_path or expected_started <= 0 \
                or identity_action is None or identities_action is None:
            return False
        try:
            identity = identity_action(process_id)
            observed = identities_action(expected_path)
        except Exception:
            return False
        if identity is None or observed is None \
                or self._normalized_process_path(identity.get("process_path")) != expected_path \
                or int(identity.get("process_started_filetime") or 0) != expected_started:
            return False
        matches = [item for item in observed
                   if self._normalized_process_path(item.get("process_path")) == expected_path
                   and int(item.get("process_started_filetime") or 0) == expected_started]
        return len(matches) == 1 \
            and int(matches[0].get("process_id") or 0) == process_id

    def _verified_running_games(self) -> tuple[list[dict[str, Any]], str, str]:
        with self.lock:
            games = [dict(game) for game in self.library.values()]
            revision = self.library_revision
            probe = self.running_process_probe
            current = dict(self.current)
            native_confirmation = self._native_reconciliation_confirmation if self.connected else None
        if probe is None:
            return [], "unavailable", revision
        identities, status = probe.scan_running_processes()
        exact_paths = set()
        for game in games:
            executable = str(game.get("exe") or game.get("executable") or "").strip()
            if executable:
                directory = str(game.get("installDir") or game.get("install_dir") or "")
                exact_paths.add(self._normalized_process_path(
                    executable if ntpath.isabs(executable) else ntpath.join(directory, executable)))
        matches: dict[str, list[dict[str, Any]]] = {}
        for identity in identities:
            path = self._normalized_process_path(identity.get("process_path"))
            if not identity.get("visible_window") and path not in exact_paths:
                continue
            image = ntpath.basename(path)
            if image in INSTALLER_IMAGES | INSTALLER_EXCLUDED_IMAGES \
                    or image in {"launcher.exe", "bootstrapper.exe", "werfault.exe"} \
                    or image.startswith(("crashreport", "crashhandler", "unitycrashhandler", "unins")):
                continue
            candidates = [game for game in games
                          if GAME_ID_PATTERN.fullmatch(str(game.get("id") or ""))
                          and self._trace_path_matches_game({"process_path": path}, game)
                          and (str(game.get("exe") or game.get("executable") or "").strip()
                               or identity.get("visible_window"))]
            if len(candidates) != 1:
                if candidates:
                    status = "partial"
                continue
            game_id = str(candidates[0]["id"])
            if str(candidates[0].get("provider") or "playnite").casefold() == "playnite":
                # A shared emulator executable cannot identify the loaded ROM. Only
                # an exact connector-confirmed native game identity may fill this gap.
                if current.get("id") != game_id or native_confirmation != (
                        str(candidates[0].get("playniteGameId") or "").casefold(),
                        identity.get("process_id")) \
                        or int(current.get("processId") or 0) != identity.get("process_id") \
                        or int(current.get("processStartedFiletime") or 0) != \
                        identity.get("process_started_filetime"):
                    status = "partial"
                    continue
            value = {**identity, "process_path": path, "game_id": game_id}
            fingerprint = [self.profile_id, game_id, value.get("user_sid"),
                           value.get("session_id"), value.get("process_id"), path,
                           value.get("process_started_filetime")]
            if not value.get("user_sid") or int(value.get("process_id") or 0) <= 0 \
                    or int(value.get("process_started_filetime") or 0) <= 0:
                status = "partial"
                continue
            value["process_token"] = hashlib.sha256(compact_json(fingerprint)).hexdigest()
            matches.setdefault(game_id, []).append(value)
        verified = []
        for values in matches.values():
            if len(values) == 1:
                verified.extend(values)
            else:
                status = "partial"
        return verified, status, revision

    def _apply_epic_playtime_locked(self) -> None:
        for game_id, game in self.library.items():
            if game.get("provider") != "epic":
                continue
            # Import the old history once; later Playnite snapshots cannot reset it.
            if game_id not in self.epic_playtime_seconds:
                self.epic_playtime_seconds[game_id] = max(0, int(
                    game.get("playtimeSeconds", int(game.get("playtimeMinutes") or 0) * 60)))
            game["playtimeSeconds"] = int(self.epic_playtime_seconds[game_id])
            game["playtimeMinutes"] = game["playtimeSeconds"] // 60

    def _sample_epic_playtime_locked(self, games: list[dict[str, Any]], now: float) -> None:
        self._apply_epic_playtime_locked()
        samples = {}
        publish = False
        for running in games:
            game_id = running["game_id"]
            if (self.library.get(game_id) or {}).get("provider") != "epic":
                continue
            token = running["process_token"]
            previous = self._epic_playtime_samples.get(game_id)
            before = self.epic_playtime_seconds[game_id]
            if previous is not None and previous[0] == token:
                elapsed = now - previous[1]
                # ponytail: sampled process lifetime, not active input time. Do not
                # charge sleep, Bridge downtime or gaps in process verification.
                if 0 < elapsed <= 15:
                    self.epic_playtime_seconds[game_id] += elapsed
            publish |= int(before // 60) != int(self.epic_playtime_seconds[game_id] // 60)
            samples[game_id] = (token, now)
        publish |= bool(self._epic_playtime_samples.keys() - samples.keys())
        self._epic_playtime_samples = samples
        self._apply_epic_playtime_locked()
        if publish:
            self._advance_library_revision_locked()
            self._save_library_cache_locked()
            self._publish_locked("library-updated", {
                "count": len(self.library), "revision": self.library_revision})

    def refresh_running_games(self) -> None:
        started = time.monotonic()
        try:
            games, status, revision = self._verified_running_games()
        except Exception:
            games, status, revision = [], "unavailable", ""
        with self.lock:
            if revision != self.library_revision:
                self._epic_playtime_samples.clear()
                return
            self._sample_epic_playtime_locked(
                games if status != "unavailable" else [], time.monotonic())
            self._running_games = [{key: game[key] for key in (
                "game_id", "process_id", "process_token")} for game in games]
            self._running_scan_status = status
            self._running_scan_revision = self.library_revision
            self._running_scan_at = time.monotonic()
        elapsed = time.monotonic() - started
        if elapsed > 0.2:
            logging.warning("Running game scan took %.0fms (%s)", elapsed * 1000, status)

    def current_snapshot(self) -> dict[str, Any]:
        with self.lock:
            game_id = str(self.current.get("id") or "")
            game = self.library.get(game_id)
            host_guide_allowed = (
                not game_id and self.current.get("state") == "idle"
            ) or (game is not None and self.game_operations.provider_for(game)
                  is self.game_operations.steam)
            fresh = self._running_scan_revision == self.library_revision \
                and self._running_scan_at > 0 \
                and time.monotonic() - self._running_scan_at <= 10.0
            return {**self.current,
                    "host_guide_allowed": host_guide_allowed,
                    "running_games": [dict(game) for game in self._running_games] if fresh else [],
                    "running_scan_status": self._running_scan_status if fresh else "unavailable",
                    "running_scan_revision": self._running_scan_revision if fresh else self.library_revision}

    def _stop_verified_running_game(self, game_id: Any, token: Any) -> dict[str, Any]:
        if not isinstance(token, str) or not re.fullmatch(r"[0-9a-f]{64}", token) or not game_id:
            raise ValueError("Invalid expected process token or game ID.")
        normalized = self.resolve_game_id(game_id)
        failed = {"accepted": False, "command": "stop", "force": False,
                  "reason": "running_game_identity_changed"}
        games, status, revision = self._verified_running_games()
        expected = next((game for game in games if game["game_id"] == normalized
                         and game["process_token"] == token), None)
        if expected is None or status == "unavailable":
            return failed
        with self.lock:
            if revision != self.library_revision or self.running_process_probe is None:
                return failed
            previous = dict(self.current)
            probe = self.running_process_probe
        # The native action checks identity again, and waits on the exact process handle.
        if not probe.stop_verified_process(expected, min(self.stop_timeout, 20.0)):
            return {**failed, "reason": "game_stop_unconfirmed"}
        with self.lock:
            keys = ("id", "processId", "processPath", "processStartedFiletime",
                    "launchTaskId", "launchRequestedAt", "state")
            stopped_current = previous.get("id") == normalized \
                and int(previous.get("processId") or 0) == expected["process_id"] \
                and self._normalized_process_path(previous.get("processPath")) == expected["process_path"] \
                and int(previous.get("processStartedFiletime") or 0) == expected["process_started_filetime"] \
                and all(self.current.get(key) == previous.get(key) for key in keys)
            if stopped_current:
                self._complete_game_stop_locked()
            self._running_games = [game for game in self._running_games
                                   if game["process_token"] != token]
            return {"accepted": True, "command": "stop", "force": False,
                    "stopped_game_id": normalized, "stopped_current": stopped_current}

    def stop_game(self, game_id: Any = "") -> dict[str, Any]:
        normalized = self.resolve_game_id(game_id) if game_id else ""
        deadline = time.monotonic() + self.stop_timeout
        with self.events_changed:
            current_id = str(self.current.get("id", ""))
            current_state = str(self.current.get("state") or "").casefold()
            if current_state in {"reconciling", "ambiguous"}:
                return {
                    "accepted": False, "command": "stop", "force": False,
                    "reason": "game_identity_" + current_state,
                }
            if current_state == "idle":
                return {"accepted": True, "command": "stop", "force": False,
                        "already_stopped": True}
            process_id = int(self.current.get("processId") or self.current.get("process_id") or 0)
            if normalized and current_id and normalized != current_id:
                raise ValueError("Requested game is not the current provider game.")
            if current_state not in {"starting", "running", "stopping"}:
                return {"accepted": False, "command": "stop", "force": False,
                        "reason": "game_stop_unavailable"}
            task_id = self.current.pop("launchTaskId", None)
            if task_id:
                self.game_operations.finish_process(task_id)
            self.current.update({
                "state": "stopping",
                "stopRequestedAt": self.clock(),
            })
            self.readiness.update({
                "ready": False, "reason": "game_stopping", "stable_samples": 0})
            self._last_window_signature = None
            self._publish_locked("game-stopping", {"id": current_id, "process_id": process_id})
            if process_id and not self._request_game_close_locked(process_id):
                self.readiness["reason"] = "game_stop_close_rejected"
                return {"accepted": False, "command": "stop", "force": False,
                        "reason": "game_stop_close_rejected"}
            while True:
                if str(self.current.get("state") or "").casefold() == "idle":
                    return {"accepted": True, "command": "stop", "force": False}
                if str(self.current.get("id") or "") != current_id:
                    return {"accepted": False, "command": "stop", "force": False,
                            "reason": "game_stop_correlation_lost"}
                if str(self.readiness.get("reason") or "") == \
                        "game_stop_close_rejected":
                    return {"accepted": False, "command": "stop", "force": False,
                            "reason": "game_stop_close_rejected"}
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self.current["stopTimedOut"] = True
                    process_id = int(self.current.get("processId") or
                                     self.current.get("process_id") or 0)
                    if process_id <= 0:
                        self._complete_game_stop_locked()
                        return {"accepted": True, "command": "stop", "force": False,
                                "already_stopped": True}
                    if int(self.current.get("stopCloseRequestedPid") or 0) == process_id \
                            and self._exact_stop_process_matches_locked(process_id):
                        self.current.pop("stopCloseRequestedPid", None)
                        self.current["stopCloseRetryArmed"] = True
                    return {"accepted": False, "command": "stop", "force": False,
                            "reason": "game_stop_timeout"}
                self.events_changed.wait(remaining)

    def show_fullscreen(self) -> dict[str, Any]:
        with self.lock:
            self.readiness = {
                "ready": False,
                "reason": "waiting_for_playnite_window",
                "target_kind": "playnite",
                "stable_samples": 0,
            }
            self._last_window_signature = None
            action = self.show_fullscreen_action
        if not action:
            raise RuntimeError("Playnite Fullscreen activation is unavailable.")
        details = action()
        return {"accepted": True, "command": "show-fullscreen", **details}

    def focus_game(self) -> dict[str, Any]:
        with self.lock:
            if str(self.current.get("state", "")).casefold() != "running":
                raise ValueError("No Playnite game is currently running.")
            process_id = int(
                self.current.get("processId") or self.current.get("process_id") or 0)
            install_directory = str(
                self.current.get("installDir") or self.current.get("install_dir") or "")
            expected_display = self.expected_display
            action = self.focus_game_action
            self.readiness.update({
                "ready": False, "reason": "game_focus_requested", "stable_samples": 0})
            self._last_window_signature = None
        if not action:
            raise RuntimeError("Game focus activation is unavailable.")
        details = action(process_id, install_directory, expected_display)
        if not details.get("focused"):
            raise RuntimeError("Windows rejected the game focus request.")
        return {"accepted": True, "command": "focus", **details}

    def _launcher_postcondition_failed_locked(self) -> bool:
        return self._launcher_invoked_signature is not None and \
            self.clock() - self._launcher_invoked_at >= LAUNCHER_POSTCONDITION_TIMEOUT

    def apply_window_sample(self, sample: dict[str, Any]) -> None:
        with self.lock:
            sample_reason = str(sample.get("reason") or "")
            target_kind = str(self.readiness.get("target_kind") or "").casefold()
            current_state = str(self.current.get("state") or "").casefold()
            if current_state in {"reconciling", "ambiguous"}:
                self.readiness.update({
                    "ready": False,
                    "reason": str(self.current.get("reason") or
                                  "active_game_verification_pending"),
                    "stable_samples": 0,
                })
                self._last_window_signature = None
                return
            observed_game_id = str(sample.get("observed_game_id") or "")
            if target_kind == "game" and observed_game_id and \
                    observed_game_id != str(self.current.get("id") or ""):
                return
            if target_kind == "game" and current_state == "failed":
                return
            if target_kind == "game" and current_state == "running" and \
                    sample.get("launcher_candidate"):
                return
            if target_kind == "game" and current_state == "starting" and \
                    self._launcher_interaction_required and \
                    not sample.get("qualified") and \
                    not sample.get("launcher_candidate") and \
                    sample_reason in {
                        "waiting_for_game_identity", "waiting_for_game_window",
                    }:
                sample = {
                    **sample,
                    "reason": "launcher_interaction_required",
                    "launcher_detail": str(
                        self.readiness.get("launcher_detail") or
                        "provider_interaction_required"),
                }
                sample_reason = "launcher_interaction_required"
            if target_kind == "game" and sample_reason == "game_process_exited":
                game_id = str(self.current.get("id") or "")
                if current_state in {"running", "stopping"}:
                    self._complete_game_stop_locked()
                elif current_state == "starting":
                    task_id = self.current.get("launchTaskId")
                    if task_id:
                        self.game_operations.finish_process(task_id)
                    reason = "game_process_exited_before_window"
                    self.current = {"state": "failed", "id": game_id,
                                    "reason": reason}
                    self.readiness.update({
                        "ready": False, "reason": reason, "stable_samples": 0,
                    })
                    self._publish_locked("game-launch-failed", {
                        "id": game_id, "reason": reason,
                    })
                return
            if target_kind == "game" and current_state == "stopping":
                process_id = int(sample.get("process_id") or 0)
                if process_id > 0 and not sample.get("launcher_candidate"):
                    self.current["processId"] = process_id
                    if not self._request_game_close_locked(process_id):
                        self.readiness.update({
                            "ready": False, "reason": "game_stop_close_rejected",
                            "stable_samples": 0,
                        })
                        self._publish_locked("game-stop-rejected", {
                            "id": str(self.current.get("id") or ""),
                            "process_id": process_id,
                            "reason": "game_stop_close_rejected",
                        })
                return
            if target_kind == "game" and current_state == "running" \
                    and sample.get("replacement_process") \
                    and int(sample.get("process_id") or 0) > 0:
                self.current.update({
                    "processId": int(sample["process_id"]),
                    "processPath": str(sample.get("process_path") or ""),
                })
                self._save_active_game_trace_locked()
            launcher_signature = None
            if sample.get("launcher_candidate"):
                launcher_signature = (
                    int(sample.get("hwnd") or 0),
                    int(sample.get("process_id") or 0),
                )
            next_launcher_samples = 0
            if launcher_signature is not None:
                next_launcher_samples = self._launcher_candidate_samples + 1 \
                    if launcher_signature == self._launcher_candidate_signature else 1
            if self._launcher_invoked_signature is not None and \
                    str(sample.get("reason") or "") != "launcher_action_invoked":
                if launcher_signature != self._launcher_invoked_signature:
                    # Disappearance of the exact prompt confirms the action.
                    # Game startup continues under the existing readiness timeout.
                    self._launcher_invoked_signature = None
                    self._launcher_invoked_at = 0.0
                elif not sample.get("qualified") and \
                        self._launcher_postcondition_failed_locked():
                    sample = {**sample,
                              "reason": "launcher_interaction_required",
                              "launcher_action_attempted": True}
            if sample.get("launcher_candidate") and \
                    self._launcher_automation_attempted and \
                    str(sample.get("reason") or "") == "launcher_candidate_detected" and \
                    launcher_signature != self._launcher_invoked_signature and \
                    next_launcher_samples >= REQUIRED_LAUNCHER_STABLE_SAMPLES:
                sample = {**sample,
                          "reason": "launcher_interaction_required",
                          "launcher_action_attempted": True,
                          "launcher_detail": "provider_interaction_required"}
            sample_reason = str(sample.get("reason") or "")
            first_launcher_failure = sample_reason == "launcher_interaction_required" \
                and not self._launcher_interaction_required
            if sample.get("launcher_candidate"):
                self._launcher_candidate_signature = launcher_signature
                self._launcher_candidate_samples = next_launcher_samples
            if sample.get("launcher_action_attempted"):
                self._launcher_automation_attempted = True
            if str(sample.get("reason") or "") == "launcher_action_invoked":
                self._launcher_invoked_signature = launcher_signature
                self._launcher_invoked_at = self.clock()
            if str(sample.get("reason") or "") == "launcher_interaction_required":
                self._launcher_interaction_required = True
                self._launcher_invoked_signature = None
                self._launcher_invoked_at = 0.0
            launch_requested_at = float(self.current.get("launchRequestedAt") or 0.0)
            if str(self.readiness.get("target_kind") or "").casefold() == "game" \
                    and str(self.current.get("state") or "").casefold() == "starting" \
                    and not sample.get("launcher_candidate") \
                    and sample_reason in {
                        "waiting_for_game_identity", "waiting_for_game_window"} \
                    and launch_requested_at > 0 \
                    and self.clock() - launch_requested_at >= GAME_START_TIMEOUT:
                game_id = str(self.current.get("id") or "")
                task_id = self.current.get("launchTaskId")
                reason = "launcher_closed_without_game" \
                    if self._launcher_automation_attempted \
                    or self._launcher_interaction_required else "game_start_timeout"
                if task_id:
                    self.game_operations.finish_process(task_id)
                self.current = {"state": "failed", "id": game_id, "reason": reason}
                self.readiness.update({
                    "ready": False, "reason": reason, "stable_samples": 0,
                })
                self._launcher_candidate_signature = None
                self._launcher_candidate_samples = 0
                self._launcher_automation_attempted = False
                self._launcher_interaction_required = False
                self._launcher_invoked_signature = None
                self._launcher_invoked_at = 0.0
                self._publish_locked("game-launch-failed", {
                    "id": game_id, "reason": reason,
                })
                return
            if sample.get("qualified"):
                self._launcher_candidate_signature = None
                self._launcher_candidate_samples = 0
                self._launcher_interaction_required = False
                self._launcher_invoked_signature = None
                self._launcher_invoked_at = 0.0
            if str(self.readiness.get("target_kind") or "").casefold() == "game" \
                    and str(self.current.get("state") or "").casefold() == "starting" \
                    and int(sample.get("process_id") or 0) > 0 \
                    and not sample.get("launcher_candidate"):
                self.current.update({
                    "state": "running",
                    "processId": int(sample["process_id"]),
                    "processPath": str(sample.get("process_path") or ""),
                })
                launch_task_id = self.current.pop("launchTaskId", None)
                if launch_task_id:
                    self.game_operations.finish_process(launch_task_id)
                self._save_active_game_trace_locked()
                self._publish_locked("game-running", dict(self.current))
            previous_ready = bool(self.readiness.get("ready"))
            if not sample.get("qualified"):
                self._last_window_signature = None
                self.readiness.update({
                    "ready": False,
                    "reason": str(sample.get("reason", "window_not_ready")),
                    "stable_samples": 0,
                })
                self.readiness.pop("launcher_detail", None)
                if sample.get("launcher_detail"):
                    self.readiness["launcher_detail"] = str(
                        sample["launcher_detail"])[:200]
                for key in (
                        "process_id", "display", "bounds", "monitor_bounds",
                        "foreground", "foreground_hwnd", "foreground_display",
                        "foreground_process_id", "foreground_image"):
                    if key in sample:
                        self.readiness[key] = sample[key]
                if previous_ready:
                    self._publish_locked("privacy-gate-closed", dict(self.readiness))
                if first_launcher_failure:
                    self._publish_locked("launcher-interaction-required", {
                        "id": str(self.current.get("id") or ""),
                        "reason": str(sample.get("launcher_detail") or
                                      "launcher_interaction_required"),
                    })
                    print(json.dumps({
                        "event": "launcher_automation_failed",
                        "game_id": str(self.current.get("id") or ""),
                        "reason": str(sample.get("launcher_detail") or
                                      "launcher_interaction_required")[:200],
                    }, ensure_ascii=False), flush=True)
                return
            signature = (
                sample.get("process_id"), sample.get("hwnd"), sample.get("display"),
                tuple(sample.get("bounds") or []),
            )
            stable = int(self.readiness.get("stable_samples", 0)) + 1 \
                if signature == self._last_window_signature else 1
            self._last_window_signature = signature
            required_samples = REQUIRED_GAME_STABLE_SAMPLES \
                if str(self.readiness.get("target_kind", "")).casefold() == "game" \
                else REQUIRED_STABLE_SAMPLES
            ready = stable >= required_samples
            self.readiness.update({
                "ready": ready,
                "reason": "target_window_ready" if ready else "stabilizing_target_window",
                "stable_samples": stable,
                "process_id": sample.get("process_id"),
                "display": sample.get("display"),
                "bounds": sample.get("bounds"),
                "monitor_bounds": sample.get("monitor_bounds"),
                "foreground": sample.get("foreground"),
                "foreground_hwnd": sample.get("foreground_hwnd"),
                "foreground_display": sample.get("foreground_display"),
                "foreground_process_id": sample.get("foreground_process_id"),
                "foreground_image": sample.get("foreground_image"),
            })
            if ready and not previous_ready:
                self._publish_locked("target-window-ready", dict(self.readiness))

    def library_page(self, cursor: str, limit: int) -> dict[str, Any]:
        offset = int(cursor or "0")
        if offset < 0 or limit < 1 or limit > 100:
            raise ValueError("Invalid library page.")
        with self.lock:
            games = sorted(self.library.values(), key=lambda game: str(game.get("name", "")).casefold())
            page = games[offset:offset + limit]
            next_offset = offset + len(page)
            counts: dict[tuple[str, str, str], int] = {}
            for game in games:
                descriptor = (
                    str(game.get("libraryKey") or "playnite"),
                    str(game.get("libraryName") or "Playnite"),
                    str(game.get("provider") or "playnite"),
                )
                counts[descriptor] = counts.get(descriptor, 0) + 1
            return {
                "games": page,
                "next_cursor": str(next_offset) if next_offset < len(games) else "",
                "total": len(games),
                "revision": self.library_revision,
                "categories": list(self.categories),
                "plugins": list(self.plugins),
                "libraries": [{"key": key, "name": name, "provider": provider,
                               "gameCount": count}
                              for (key, name, provider), count in sorted(
                                  counts.items(), key=lambda item: item[0][1].casefold())],
                "providers": dict(self.provider_health),
            }

    def refresh_library(self) -> dict[str, Any]:
        with self.lock:
            previous_revision = self.library_revision
            self._schedule_catalog_refresh_locked()
        try:
            result = self.send_command("snapshot")
        except ConnectionError:
            result = {"accepted": True, "command": "provider-catalog-refresh",
                      "playnite_connector": False}
        result["previous_revision"] = previous_revision
        return result

    def artwork(self, game_id: Any, kind: str) -> tuple[bytes, str]:
        normalized = self.resolve_game_id(game_id)
        fields = {
            "cover": ("boxArtPath", "cover", "coverImage"),
            "background": ("backgroundImagePath", "background", "backgroundImage"),
            "icon": ("iconPath", "icon"),
        }
        if kind not in fields:
            raise ValueError("Invalid artwork kind.")
        with self.lock:
            game = dict(self.library.get(normalized) or {})
        if not game:
            raise FileNotFoundError("Artwork metadata was not found for this game.")
        value = next((str(game.get(field) or "").strip()
                      for field in fields[kind] if game.get(field)), "")
        if not value and kind == "background":
            value = str(game.get("boxArtPath") or "").strip()
        path = Path(value).expanduser()
        if not value or not path.is_file():
            raise FileNotFoundError("Artwork is unavailable for this game.")
        size = path.stat().st_size
        if size <= 0 or size > MAX_ARTWORK_BYTES:
            raise ValueError("Artwork file has an unsupported size.")
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        if not content_type.startswith("image/"):
            raise ValueError("Artwork file is not an image.")
        return path.read_bytes(), content_type

    def events_after(self, sequence: int, timeout: float) -> list[dict[str, Any]]:
        deadline = time.monotonic() + timeout
        with self.events_changed:
            while not any(item["sequence"] > sequence for item in self.events):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return []
                self.events_changed.wait(remaining)
            return [item for item in self.events if item["sequence"] > sequence]


class WindowReadinessWorker:
    def __init__(self, state: BridgeState, probe: WindowProbe,
                 display_resolver: StreamDisplayResolver) -> None:
        self.state = state
        self.probe = probe
        self.display_resolver = display_resolver

    def run(self) -> None:
        last_provider_probe_key = ""
        last_provider_probe_at = 0.0
        last_running_scan = 0.0
        while True:
            resolved_display = self.display_resolver.resolve()
            if resolved_display:
                self.state.set_expected_display(resolved_display)
            with self.state.lock:
                readiness = dict(self.state.readiness)
                current = dict(self.state.current)
                expected_display = self.state.expected_display
                launch_baseline = dict(self.state._launch_baseline)
                launch_game = dict(self.state.library.get(
                    str(current.get("id") or "")) or {})
                launcher_stable = self.state._launcher_candidate_samples >= \
                    REQUIRED_LAUNCHER_STABLE_SAMPLES
                launcher_attempted = self.state._launcher_automation_attempted
                launcher_interaction_required = \
                    self.state._launcher_interaction_required
                launcher_postcondition_failed = \
                    self.state._launcher_postcondition_failed_locked()
                launch_requested_at = float(current.get("launchRequestedAt") or 0.0)
            target_kind = str(readiness.get("target_kind", "playnite"))
            launch_in_progress = target_kind == "game" and \
                str(current.get("state") or "").casefold() == "starting"
            probe_now = self.state.clock()
            provider_probe_key = "%s:%s" % (
                str(current.get("id") or ""), launch_requested_at)
            probe_provider_launcher = launch_in_progress and not int(
                current.get("processId") or current.get("process_id") or 0) and \
                not launcher_interaction_required and launch_requested_at > 0 and \
                probe_now - launch_requested_at >= PROVIDER_LAUNCHER_PROBE_DELAY and \
                (provider_probe_key != last_provider_probe_key or
                 probe_now - last_provider_probe_at >= LAUNCHER_POSTCONDITION_TIMEOUT)
            if probe_provider_launcher:
                last_provider_probe_key = provider_probe_key
                last_provider_probe_at = probe_now
            launch_task_id = str(current.get("launchTaskId") or "")
            if target_kind == "game" and launch_task_id \
                    and str(current.get("state") or "").casefold() == "starting":
                try:
                    launch_sample = self.state.game_operations.sample_launch(
                        current, launch_task_id)
                    if launch_sample is not None:
                        self.state.apply_launch_process_sample(
                            launch_sample, launch_task_id)
                        if launch_sample.get("requires_attention"):
                            time.sleep(0.25)
                            continue
                except Exception:
                    self.state.apply_launch_process_sample({
                        "requires_attention": True,
                        "reason": "provider_launch_monitor_failed",
                    }, launch_task_id)
                    time.sleep(0.25)
                    continue
            process_id = int(current.get("processId") or current.get("process_id") or 0)
            install_directory = str(
                current.get("installDir") or current.get("install_dir") or "")
            expected_executable = str(
                current.get("exe") or current.get("executable") or "")
            tracked_process_path = str(current.get("processPath") or "")
            expected_title = str(current.get("title") or
                                 launch_game.get("name") or "")
            expected_launcher_images = self.state.game_operations.expected_launcher_images(
                launch_game) if launch_game else set()
            if target_kind == "game" and not process_id and not install_directory:
                self.state.apply_window_sample({
                    "qualified": False, "reason": "waiting_for_game_identity",
                    "observed_game_id": str(current.get("id") or ""),
                })
            else:
                sample = self.probe.sample(
                    target_kind, process_id, expected_display, install_directory,
                    launch_baseline if launch_in_progress else None,
                    launch_in_progress and launcher_stable and not launcher_attempted,
                    launch_in_progress and (
                        launcher_interaction_required or launcher_postcondition_failed),
                    expected_executable, expected_title,
                    expected_launcher_images,
                    launch_in_progress and launcher_attempted,
                    probe_provider_launcher, tracked_process_path)
                if target_kind == "game":
                    sample["observed_game_id"] = str(current.get("id") or "")
                self.state.apply_window_sample(sample)
            for game_id, baseline, token in self.state.installation_probes():
                probe = self.state.installation_probe_action
                if probe:
                    try:
                        self.state.apply_installation_probe(
                            game_id, probe(baseline), token)
                    except Exception:
                        # Window inspection is advisory. A transient Win32 failure must
                        # never stop readiness or installation lifecycle monitoring.
                        pass
            if time.monotonic() - last_running_scan >= 5.0:
                self.state.refresh_running_games()
                last_running_scan = time.monotonic()
            time.sleep(0.25)

class WindowsPipeClient:
    CONTROL_PIPE = r"\\.\pipe\Sunshine.PlayniteExtension"
    GENERIC_READ = 0x80000000
    GENERIC_WRITE = 0x40000000
    OPEN_EXISTING = 3
    ERROR_PIPE_BUSY = 231
    ERROR_MORE_DATA = 234
    INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

    def __init__(self, state: BridgeState) -> None:
        self.state = state
        self.handle: int | None = None
        self.write_lock = threading.Lock()
        self.stopping = threading.Event()
        self.outbound: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=16)
        threading.Thread(
            target=self._write_commands, name="PlaynitePipeWriter", daemon=True).start()

    @staticmethod
    def _kernel32():
        if os.name != "nt":
            raise OSError("Playnite named pipes require Windows.")
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                         wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD,
                                         wintypes.HANDLE]
        kernel32.CreateFileW.restype = wintypes.HANDLE
        kernel32.PeekNamedPipe.argtypes = [
            wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD),
            ctypes.POINTER(wintypes.DWORD)]
        kernel32.PeekNamedPipe.restype = wintypes.BOOL
        return kernel32

    def _available(self, handle: int) -> int:
        """Return queued bytes without starting a blocking synchronous read.

        A synchronous ReadFile pending on the duplex pipe can serialize a WriteFile
        issued by the command-writer thread on the same handle. Peeking first keeps
        the handle free while Playnite is idle, so commands can still flow back to
        the connector.
        """
        kernel32 = self._kernel32()
        available = wintypes.DWORD()
        if not kernel32.PeekNamedPipe(
                handle, None, 0, None, ctypes.byref(available), None):
            raise OSError(ctypes.get_last_error(), "Playnite pipe peek failed")
        return int(available.value)

    def _open(self, path: str, timeout_ms: int = 3000) -> int:
        kernel32 = self._kernel32()
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            handle = kernel32.CreateFileW(
                path, self.GENERIC_READ | self.GENERIC_WRITE, 0, None,
                self.OPEN_EXISTING, 0, None)
            value = int(handle) if handle else 0
            if value and value != self.INVALID_HANDLE_VALUE:
                return value
            error = ctypes.get_last_error()
            if error != self.ERROR_PIPE_BUSY or time.monotonic() >= deadline:
                raise OSError(error, f"Unable to open Playnite pipe {path}")
            kernel32.WaitNamedPipeW(path, 250)

    def _read(self, handle: int, size: int) -> bytes:
        kernel32 = self._kernel32()
        buffer = ctypes.create_string_buffer(size)
        read = wintypes.DWORD()
        succeeded = kernel32.ReadFile(handle, buffer, size, ctypes.byref(read), None)
        if not succeeded:
            error = ctypes.get_last_error()
            # The Playnite data pipe uses message mode. A snapshot batch can be
            # much larger than our read buffer (especially when descriptions are
            # included), in which case Windows returns ERROR_MORE_DATA together
            # with a valid first chunk. Keep accumulating until the trailing
            # newline arrives instead of dropping the connection and stale-cache
            # fallback.
            if error != self.ERROR_MORE_DATA or read.value == 0:
                raise OSError(error, "Playnite pipe read failed")
        return buffer.raw[:read.value]

    def _write(self, handle: int, value: bytes) -> None:
        kernel32 = self._kernel32()
        written = wintypes.DWORD()
        if not kernel32.WriteFile(handle, value, len(value), ctypes.byref(written), None):
            raise OSError(ctypes.get_last_error(), "Playnite pipe write failed")

    def _close(self, handle: int | None) -> None:
        if handle:
            try:
                self._kernel32().CloseHandle(handle)
            except OSError:
                pass

    def _connect(self) -> int:
        control = self._open(self.CONTROL_PIPE)
        try:
            handshake = b""
            while len(handshake) < 80:
                chunk = self._read(control, 80 - len(handshake))
                if not chunk:
                    raise ConnectionError("Playnite handshake ended early.")
                handshake += chunk
            pipe_name = handshake.decode("utf-16-le", errors="ignore").split("\0", 1)[0].strip()
            if not pipe_name:
                raise ConnectionError("Playnite returned an empty data pipe name.")
            self._write(control, b"\x02")
        finally:
            self._close(control)
        prefix = "\\\\.\\pipe\\"
        path = pipe_name if pipe_name.startswith(prefix) else prefix + pipe_name
        data = self._open(path)
        self.handle = data
        self._send({"role": "launcher", "pid": os.getpid(), "client": "WakePlayBridge"})
        self._send({"type": "command", "command": "snapshot"})
        return data

    def _send(self, message: dict[str, Any]) -> None:
        encoded = compact_json(message) + b"\n"
        with self.write_lock:
            if not self.handle:
                raise ConnectionError("Playnite pipe is not connected.")
            self._write(self.handle, encoded)

    def _queue_send(self, message: dict[str, Any]) -> None:
        try:
            self.outbound.put_nowait(dict(message))
        except queue.Full as error:
            raise ConnectionError("Playnite command queue is full.") from error

    def _write_commands(self) -> None:
        while not self.stopping.is_set():
            try:
                message = self.outbound.get(timeout=0.25)
            except queue.Empty:
                continue
            try:
                self._send(message)
            except Exception as error:
                self.state.set_transport(False, None, str(error))
            finally:
                self.outbound.task_done()

    def run(self) -> None:
        while not self.stopping.is_set():
            handle = None
            try:
                handle = self._connect()
                # HTTP requests enqueue commands instead of waiting on a Playnite
                # pipe write. Playnite may stop reading while it swaps UI processes.
                self.state.set_transport(True, self._queue_send)
                pending = b""
                while not self.stopping.is_set():
                    available = self._available(handle)
                    if available <= 0:
                        self.stopping.wait(0.05)
                        continue
                    chunk = self._read(handle, min(8192, available))
                    if not chunk:
                        raise ConnectionError("Playnite pipe closed.")
                    pending += chunk
                    while b"\n" in pending:
                        raw, pending = pending.split(b"\n", 1)
                        if not raw.strip():
                            continue
                        value = json.loads(raw.decode("utf-8-sig"))
                        if isinstance(value, dict):
                            self.state.handle_message(value)
            except Exception as error:
                self.state.set_transport(False, None, str(error))
                self.stopping.wait(1.0)
            finally:
                self.handle = None
                self._close(handle)


class GameProviderHandler(BaseHTTPRequestHandler):
    server_version = "WakePlayPlayniteBridge/0.1"

    @property
    def state(self) -> BridgeState:
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
        value = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
        if not isinstance(value, dict):
            raise ValueError("JSON object expected.")
        return value

    def _begin_diagnostics(self, method: str) -> None:
        self._diagnostic_method, self._diagnostic_started = method, time.monotonic()
        self._diagnostic_route = diagnostic_route(self.path)
        self._diagnostic_error, self._diagnostic_unexpected, self._response_status = None, False, 0
        if hasattr(self.state.request_context, "request_id"):
            del self.state.request_context.request_id
        request_id = (self.headers.get("X-Request-Id", "") or "").strip()
        if REQUEST_ID_PATTERN.fullmatch(request_id):
            self.state.request_context.request_id = request_id
        else:
            request_id = ""
        if method == "POST":
            DIAGNOSTICS.record("request.started", method=method,
                               route=self._diagnostic_route, request_id=request_id,
                               profile_id=self.state.profile_id)

    def _finish_diagnostics(self) -> None:
        try:
            status = self._response_status
            failed = self._diagnostic_error is not None or status <= 0 or status >= 400
            if self._diagnostic_method == "POST" or failed:
                DIAGNOSTICS.record(
                    "request.failed" if failed else "request.completed",
                    level="ERROR" if self._diagnostic_unexpected else
                    ("WARN" if failed else "INFO"), error=self._diagnostic_error,
                    frames=self._diagnostic_unexpected, method=self._diagnostic_method,
                    route=self._diagnostic_route,
                    request_id=getattr(self.state.request_context, "request_id", ""),
                    profile_id=self.state.profile_id,
                    status="failed" if failed else "completed",
                    http_status=status if status > 0 else None,
                    duration_ms=max(0, int((time.monotonic() - self._diagnostic_started) * 1000)))
        finally:
            if hasattr(self.state.request_context, "request_id"):
                del self.state.request_context.request_id

    def do_GET(self) -> None:  # noqa: N802
        self._begin_diagnostics("GET")
        try:
            target = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(target.query)
            if target.path == "/health":
                with self.state.lock:
                    self.send_json(HTTPStatus.OK, {
                        "ok": True,
                        "component": "game-provider",
                        "compatibility_component": "playnite",
                        "profile_id": self.state.profile_id,
                        "connector_connected": self.state.connected,
                        "library_count": len(self.state.library),
                        "providers": dict(self.state.provider_health),
                        "error": self.state.last_error,
                        "pid": os.getpid(),
                        "started_at": self.state.started_at,
                        **self.state.version_info,
                    })
            elif target.path == "/library/list":
                cursor = query.get("cursor", ["0"])[0] or "0"
                limit = int(query.get("limit", ["50"])[0])
                self.send_json(HTTPStatus.OK, self.state.library_page(cursor, limit))
            elif target.path == "/artwork":
                body, content_type = self.state.artwork(
                    query.get("game_id", [""])[0],
                    query.get("kind", ["cover"])[0])
                self.send_binary(HTTPStatus.OK, body, content_type)
            elif target.path == "/game/current":
                self.send_json(HTTPStatus.OK, self.state.current_snapshot())
            elif target.path == "/window/readiness":
                with self.state.lock:
                    self.send_json(HTTPStatus.OK, dict(self.state.readiness))
            elif target.path == "/events":
                after = int(query.get("after", ["0"])[0])
                events = self.state.events_after(after, 20.0)
                self.send_json(HTTPStatus.OK, {"events": events})
            else:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})
        except FileNotFoundError as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.NOT_FOUND, {"error": str(error)})
        except (ValueError, json.JSONDecodeError) as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except Exception as error:
            self._diagnostic_error, self._diagnostic_unexpected = error, True
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(error)})
        finally:
            self._finish_diagnostics()

    def do_POST(self) -> None:  # noqa: N802
        self._begin_diagnostics("POST")
        try:
            body = self.read_json()
            path = urllib.parse.urlsplit(self.path).path
            if path == "/game/start":
                result = self.state.start_game(body.get("game_id"))
            elif path == "/game/install":
                result = self.state.install_game(body.get("game_id"))
            elif path == "/game/uninstall":
                result = self.state.uninstall_game(body.get("game_id"))
            elif path == "/installation/focus":
                result = self.state.focus_installation(body.get("game_id"))
            elif path == "/installation/verify":
                result = self.state.verify_installation(body.get("game_id"))
            elif path == "/library/refresh":
                result = self.state.refresh_library()
            elif path == "/game/stop":
                if bool(body.get("force", False)):
                    raise ValueError("Forced game termination is not exposed by this Bridge.")
                result = self.state.stop_game(body.get("game_id", ""))
            elif path == "/game/stop-verified":
                if bool(body.get("force", False)):
                    raise ValueError("Forced game termination is not exposed by this Bridge.")
                result = self.state._stop_verified_running_game(
                    body.get("game_id"), body.get("expected_process_token"))
            elif path == "/game/focus":
                result = self.state.focus_game()
            elif path == "/playnite/show-fullscreen":
                result = self.state.show_fullscreen()
            else:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})
                return
            self.send_json(HTTPStatus.ACCEPTED, {"ok": True, **result})
        except ConnectionError as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "error": str(error)})
        except (ValueError, json.JSONDecodeError) as error:
            self._diagnostic_error = error
            self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": str(error)})
        except Exception as error:
            self._diagnostic_error, self._diagnostic_unexpected = error, True
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": str(error)})
        finally:
            self._finish_diagnostics()


class GameProviderServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], state: BridgeState) -> None:
        super().__init__(address, GameProviderHandler)
        self.state = state


def ensure_playnite_desktop(desktop_executable: str) -> None:
    """Start Playnite closed to tray so its connector can publish the library."""
    executable = Path(desktop_executable).resolve() if desktop_executable else None
    if os.name != "nt" or executable is None or not executable.is_file():
        return
    try:
        processes = subprocess.run(
            ["tasklist.exe", "/FI", "IMAGENAME eq Playnite.DesktopApp.exe", "/NH"],
            capture_output=True, text=True, timeout=3,
            creationflags=subprocess.CREATE_NO_WINDOW, check=False)
        if "playnite.desktopapp.exe" in processes.stdout.casefold():
            return
    except (OSError, subprocess.SubprocessError):
        # Playnite itself enforces a single instance, so falling through is safe.
        pass
    subprocess.Popen(
        [str(executable), "--hidesplashscreen", "--startclosedtotray"],
        cwd=str(executable.parent), stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NO_WINDOW)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="config.json")
    args = parser.parse_args()
    config_path = Path(args.config).resolve()
    DIAGNOSTICS.start(config_path.parent / "logs")
    config = json.loads(config_path.read_text(encoding="utf-8-sig"))
    listen_host = str(config.get("listen_host", "127.0.0.1"))
    if listen_host not in {"127.0.0.1", "localhost"}:
        raise ValueError("Game Provider Bridge must remain on loopback.")
    expected_display = str(config.get("streamed_display", "")).strip()
    journal = OperationJournal(config_path.with_name("operations.sqlite3"))
    profile_root = config_path.parent.parent
    install_root = profile_root.parent.parent
    legendary_path = str(config.get("legendary_path", "")).strip()
    steam_provider = SteamProvider(
        Path(__file__).with_name("Confirm-SteamOperation.ps1"),
        api_key_path=config_path.with_name("steam-web-api-key.dpapi"))
    game_operations = GameOperationsService(
        journal,
        GenericPlayniteProvider(),
        steam_provider,
        EpicProvider(
            legendary_path=Path(legendary_path) if legendary_path else install_root / "tools" / "legendary" / "legendary.exe",
            legendary_state_path=profile_root / "state" / "legendary",
            epic_install_root=Path(str(config.get("epic_install_root", "")).strip()) if str(config.get("epic_install_root", "")).strip() else None,
            legendary_enabled=bool(config.get("epic_legendary_enabled", True))))
    audit_path = config_path.with_name("playnite-operation-audit.jsonl")
    state = BridgeState(expected_display, config_path.with_name("library-cache.json"),
                        config_path.parent.parent / "moonwaker-version.json",
                        active_game_path=config_path.with_name("active-game.json"),
                        game_operations=game_operations,
                        operation_audit=lambda event, payload: append_operation_audit(
                            audit_path, event, payload),
                        profile_id=profile_root.name)
    window_probe = WindowProbe(game_operations)
    state.running_process_probe = window_probe
    state.set_reconciliation_actions(
        window_probe.process_identity, window_probe.process_identities)
    steam_provider.launch_preflight = lambda provider: window_probe.prepare_steam_launch(
        provider, state.expected_display)
    state.nonsteam_launch_preparation = lambda dispatch: window_probe.close_steam_big_picture(
        steam_provider, dispatch)
    ensure_playnite_desktop(str(config.get("playnite_desktop_executable", "")).strip())
    fullscreen_path = str(config.get("playnite_fullscreen_executable", "")).strip()
    display_resolver = StreamDisplayResolver(str(config.get("vibepollo_bridge", "")).strip())
    state.set_window_actions(
        window_probe.request_graceful_close,
        lambda: window_probe.show_playnite_fullscreen(fullscreen_path),
        window_probe.focus_game_window,
        window_probe.installation_baseline,
        window_probe.installation_prompt,
        window_probe.focus_installation_window)
    with state.lock:
        state._schedule_catalog_refresh_locked()
    threading.Thread(
        target=WindowReadinessWorker(state, window_probe, display_resolver).run,
        name="PlayniteWindowReadiness", daemon=True).start()
    pipe = WindowsPipeClient(state)
    threading.Thread(target=pipe.run, name="PlaynitePipe", daemon=True).start()
    server = GameProviderServer((listen_host, int(config.get("listen_port", 8780))), state)
    print(f"Game Provider Bridge listening on http://{listen_host}:{server.server_port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        pipe.stopping.set()
        server.server_close()
        DIAGNOSTICS.close()


if __name__ == "__main__":
    main()
