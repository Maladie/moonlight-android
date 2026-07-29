#!/usr/bin/env python3
"""Loopback HTTP bridge for Playnite's existing Sunshine connector pipe."""

from __future__ import annotations

import argparse
import ctypes
import html
import json
import mimetypes
import ntpath
import os
import queue
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from collections import deque
from ctypes import wintypes
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable


GAME_ID_PATTERN = re.compile(
    r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
MAX_BODY_BYTES = 16 * 1024
MAX_ARTWORK_BYTES = 8 * 1024 * 1024
REQUIRED_STABLE_SAMPLES = 3
REQUIRED_GAME_STABLE_SAMPLES = 4
DISPLAY_NAME_PATTERN = re.compile(r"^(?:\\\\\.\\)?DISPLAY[0-9]+$", re.IGNORECASE)
PLAYNITE_UI_IMAGES = {
    "playnite.fullscreenapp.exe",
    "playnite.desktopapp.exe",
}


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

    def __init__(self) -> None:
        self.user32 = None
        self.kernel32 = None
        self.dwmapi = None
        if os.name == "nt":
            self.user32 = ctypes.WinDLL("user32", use_last_error=True)
            self.kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
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

    def sample(self, target_kind: str, process_id: int,
               expected_display: str, install_directory: str = "") -> dict[str, Any]:
        if not self.user32:
            return {"qualified": False, "reason": "window_probe_unavailable"}
        if self.is_session_locked():
            return {"qualified": False, "reason": "host_session_locked"}

        windows: list[dict[str, Any]] = []
        foreground = int(self.user32.GetForegroundWindow() or 0)
        foreground_display = self._monitor_details(foreground)[0] if foreground else ""
        foreground_process_id = 0
        if foreground:
            foreground_pid = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(foreground, ctypes.byref(foreground_pid))
            foreground_process_id = int(foreground_pid.value)
        foreground_image = self._process_image(foreground_process_id)
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def visit(hwnd: int, _lparam: int) -> bool:
            if not self.user32.IsWindowVisible(hwnd):
                return True
            pid = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            candidate_process_id = int(pid.value)
            process_path = self._process_path(candidate_process_id)
            image = os.path.basename(process_path).casefold()
            if target_kind == "game":
                exact_process = candidate_process_id == process_id
                installed_process = self.belongs_to_install_directory(
                    process_path, install_directory)
                if not exact_process and not installed_process:
                    return True
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
            if width < 640 or height < 360:
                return True
            display, monitor_bounds = self._monitor_details(hwnd)
            windows.append({
                "hwnd": int(hwnd), "process_id": candidate_process_id, "image": image,
                "display": display,
                "bounds": [rect.left, rect.top, rect.right, rect.bottom],
                "monitor_bounds": monitor_bounds,
                "foreground": int(hwnd) == foreground,
                "foreground_hwnd": foreground,
                "foreground_display": foreground_display,
                "foreground_process_id": foreground_process_id,
                "foreground_image": foreground_image,
            })
            return True

        callback = callback_type(visit)
        self.user32.EnumWindows(callback, 0)
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


class BridgeState:
    def __init__(self, expected_display: str = "", cache_path: Path | None = None,
                 version_path: Path | None = None) -> None:
        self.lock = threading.RLock()
        self.events_changed = threading.Condition(self.lock)
        self.connected = False
        self.last_error = "Playnite connector is not connected."
        self.library: dict[str, dict[str, Any]] = {}
        self.library_staging: dict[str, dict[str, Any]] = {}
        self.categories: list[dict[str, Any]] = []
        self.plugins: list[dict[str, Any]] = []
        self.current: dict[str, Any] = {"state": "idle"}
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
        self.graceful_close: Callable[[int], bool] | None = None
        self.show_fullscreen_action: Callable[[], dict[str, Any]] | None = None
        self.focus_game_action: Callable[[int, str, str], dict[str, Any]] | None = None
        self.cache_path = cache_path
        self.started_at = int(time.time())
        self.version_info = self._load_version_info(version_path)
        self._load_library_cache()

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
            if not isinstance(cached, dict) or cached.get("version") != 1:
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
            self.categories = [dict(item) for item in (cached.get("categories") or [])
                               if isinstance(item, dict)]
            self.plugins = [dict(item) for item in (cached.get("plugins") or [])
                            if isinstance(item, dict)]
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            # A broken cache must never prevent the Bridge from starting. It will
            # be replaced after the next complete connector snapshot.
            self.library = {}
            self.categories = []
            self.plugins = []

    def _save_library_cache_locked(self) -> None:
        if self.cache_path is None:
            return
        payload = {
            "version": 1,
            "saved_at": int(time.time()),
            "library": list(self.library.values()),
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
                           focus_game: Callable[[int, str, str], dict[str, Any]]) -> None:
        self.graceful_close = graceful_close
        self.show_fullscreen_action = show_fullscreen
        self.focus_game_action = focus_game

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
        result = str(value or "").strip().lower()
        if not GAME_ID_PATTERN.fullmatch(result):
            raise ValueError("Invalid Playnite game ID.")
        return result

    def set_transport(self, connected: bool,
                      sender: Callable[[dict[str, Any]], None] | None,
                      error: str = "") -> None:
        with self.lock:
            changed = self.connected != connected
            self.connected = connected
            self.command_sender = sender
            self.last_error = error[:500]
            if changed:
                self._publish_locked("bridge-connected" if connected else "bridge-disconnected", {})

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

    def handle_message(self, message: dict[str, Any]) -> None:
        kind = str(message.get("type", ""))
        with self.lock:
            if kind == "plugins":
                self.plugins = list(message.get("payload") or [])
                self.library_staging = {}
            elif kind == "categories":
                self.categories = list(message.get("payload") or [])
            elif kind == "games":
                for game in message.get("payload") or []:
                    if not isinstance(game, dict):
                        continue
                    try:
                        game_id = self.game_id(game.get("id"))
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
                    self.library_staging[game_id] = normalized
                self.library = dict(self.library_staging)
                self._publish_locked("library-updated", {"count": len(self.library)})
            elif kind == "snapshotComplete":
                self.library = dict(self.library_staging)
                self._save_library_cache_locked()
                self._publish_locked("library-updated", {"count": len(self.library)})
            elif kind == "status" and isinstance(message.get("status"), dict):
                status = dict(message["status"])
                name = str(status.pop("name", ""))
                game_id = ""
                try:
                    game_id = self.game_id(status.get("id"))
                except ValueError:
                    pass
                if game_id:
                    status["id"] = game_id
                if name == "gameStarted":
                    self.current = {"state": "running", **status}
                    self.readiness = {
                        "ready": False,
                        "reason": "waiting_for_game_window",
                        "target_kind": "game",
                        "game_id": game_id,
                        "stable_samples": 0,
                    }
                    self._last_window_signature = None
                    self._publish_locked("game-running", dict(self.current))
                elif name == "gameStopped":
                    previous = dict(self.current)
                    self.current = {"state": "idle"}
                    self.readiness = {
                        "ready": False,
                        "reason": "waiting_for_playnite_window",
                        "target_kind": "playnite",
                        "stable_samples": 0,
                    }
                    self._last_window_signature = None
                    self._publish_locked("game-stopped", previous)
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

    def start_game(self, game_id: Any) -> dict[str, Any]:
        normalized = self.game_id(game_id)
        with self.lock:
            self.readiness = {
                "ready": False,
                "reason": "game_starting",
                "target_kind": "game",
                "game_id": normalized,
                "stable_samples": 0,
            }
            self._publish_locked("game-starting", {"id": normalized})
            self._last_window_signature = None
        return self.send_command("launch", id=normalized)

    def stop_game(self, game_id: Any = "") -> dict[str, Any]:
        normalized = self.game_id(game_id) if game_id else ""
        with self.lock:
            current_id = str(self.current.get("id", ""))
            process_id = int(self.current.get("processId") or self.current.get("process_id") or 0)
            if normalized and current_id and normalized != current_id:
                raise ValueError("Requested game is not the current Playnite game.")
            if not process_id:
                raise ValueError("Current game process is not available for graceful stop.")
            self.readiness.update({
                "ready": False, "reason": "game_stopping", "stable_samples": 0})
            self._last_window_signature = None
            self._publish_locked("game-stopping", {"id": current_id, "process_id": process_id})
            close = self.graceful_close
        if not close or not close(process_id):
            raise RuntimeError("No game window accepted the graceful close request.")
        return {"accepted": True, "command": "stop", "force": False}

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

    def apply_window_sample(self, sample: dict[str, Any]) -> None:
        with self.lock:
            previous_ready = bool(self.readiness.get("ready"))
            if not sample.get("qualified"):
                self._last_window_signature = None
                self.readiness.update({
                    "ready": False,
                    "reason": str(sample.get("reason", "window_not_ready")),
                    "stable_samples": 0,
                })
                for key in (
                        "process_id", "display", "bounds", "monitor_bounds",
                        "foreground", "foreground_hwnd", "foreground_display",
                        "foreground_process_id", "foreground_image"):
                    if key in sample:
                        self.readiness[key] = sample[key]
                if previous_ready:
                    self._publish_locked("privacy-gate-closed", dict(self.readiness))
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
            return {
                "games": page,
                "next_cursor": str(next_offset) if next_offset < len(games) else "",
                "total": len(games),
                "categories": list(self.categories),
                "plugins": list(self.plugins),
            }

    def artwork(self, game_id: Any, kind: str) -> tuple[bytes, str]:
        normalized = self.game_id(game_id)
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
            raise FileNotFoundError("Playnite game was not found.")
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
        while True:
            resolved_display = self.display_resolver.resolve()
            if resolved_display:
                self.state.set_expected_display(resolved_display)
            with self.state.lock:
                readiness = dict(self.state.readiness)
                current = dict(self.state.current)
                expected_display = self.state.expected_display
            target_kind = str(readiness.get("target_kind", "playnite"))
            process_id = int(current.get("processId") or current.get("process_id") or 0)
            install_directory = str(
                current.get("installDir") or current.get("install_dir") or "")
            if target_kind == "game" and not process_id and not install_directory:
                self.state.apply_window_sample({
                    "qualified": False, "reason": "waiting_for_game_identity"})
            else:
                self.state.apply_window_sample(
                    self.probe.sample(
                        target_kind, process_id, expected_display, install_directory))
            time.sleep(0.25)

class WindowsPipeClient:
    CONTROL_PIPE = r"\\.\pipe\Sunshine.PlayniteExtension"
    GENERIC_READ = 0x80000000
    GENERIC_WRITE = 0x40000000
    OPEN_EXISTING = 3
    ERROR_PIPE_BUSY = 231
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
        return kernel32

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
        if not kernel32.ReadFile(handle, buffer, size, ctypes.byref(read), None):
            raise OSError(ctypes.get_last_error(), "Playnite pipe read failed")
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
                    chunk = self._read(handle, 8192)
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


class PlayniteHandler(BaseHTTPRequestHandler):
    server_version = "WakePlayPlayniteBridge/0.1"

    @property
    def state(self) -> BridgeState:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.log_date_time_string()} {fmt % args}", flush=True)

    def send_json(self, status: int, value: Any) -> None:
        body = compact_json(value)
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
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
        value = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
        if not isinstance(value, dict):
            raise ValueError("JSON object expected.")
        return value

    def do_GET(self) -> None:  # noqa: N802
        try:
            target = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(target.query)
            if target.path == "/health":
                with self.state.lock:
                    self.send_json(HTTPStatus.OK, {
                        "ok": True,
                        "connector_connected": self.state.connected,
                        "library_count": len(self.state.library),
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
                with self.state.lock:
                    self.send_json(HTTPStatus.OK, dict(self.state.current))
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
            self.send_json(HTTPStatus.NOT_FOUND, {"error": str(error)})
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except Exception as error:
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(error)})

    def do_POST(self) -> None:  # noqa: N802
        try:
            body = self.read_json()
            path = urllib.parse.urlsplit(self.path).path
            if path == "/game/start":
                result = self.state.start_game(body.get("game_id"))
            elif path == "/game/stop":
                if bool(body.get("force", False)):
                    raise ValueError("Forced game termination is not exposed by this Bridge.")
                result = self.state.stop_game(body.get("game_id", ""))
            elif path == "/game/focus":
                result = self.state.focus_game()
            elif path == "/playnite/show-fullscreen":
                result = self.state.show_fullscreen()
            else:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Endpoint not found."})
                return
            self.send_json(HTTPStatus.ACCEPTED, {"ok": True, **result})
        except ConnectionError as error:
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "error": str(error)})
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": str(error)})
        except Exception as error:
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": str(error)})


class PlayniteServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], state: BridgeState) -> None:
        super().__init__(address, PlayniteHandler)
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
    config = json.loads(config_path.read_text(encoding="utf-8-sig"))
    listen_host = str(config.get("listen_host", "127.0.0.1"))
    if listen_host not in {"127.0.0.1", "localhost"}:
        raise ValueError("Playnite Bridge must remain on loopback.")
    expected_display = str(config.get("streamed_display", "")).strip()
    state = BridgeState(expected_display, config_path.with_name("library-cache.json"),
                        config_path.parent.parent / "moonwaker-version.json")
    window_probe = WindowProbe()
    ensure_playnite_desktop(str(config.get("playnite_desktop_executable", "")).strip())
    fullscreen_path = str(config.get("playnite_fullscreen_executable", "")).strip()
    display_resolver = StreamDisplayResolver(str(config.get("vibepollo_bridge", "")).strip())
    state.set_window_actions(
        window_probe.request_graceful_close,
        lambda: window_probe.show_playnite_fullscreen(fullscreen_path),
        window_probe.focus_game_window)
    threading.Thread(
        target=WindowReadinessWorker(state, window_probe, display_resolver).run,
        name="PlayniteWindowReadiness", daemon=True).start()
    pipe = WindowsPipeClient(state)
    threading.Thread(target=pipe.run, name="PlaynitePipe", daemon=True).start()
    server = PlayniteServer((listen_host, int(config.get("listen_port", 8780))), state)
    print(f"Playnite Bridge listening on http://{listen_host}:{server.server_port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        pipe.stopping.set()
        server.server_close()


if __name__ == "__main__":
    main()
