"""Profile authority and liveness guard of the MoonWaker Agent.

Host Control's tray hosts the Agent and restarts it with a backoff after any exit
except AUTHORITY_LOST_EXIT_CODE; after that code it waits for the profile's registry
entry to change. An unreadable registry is not a revocation: that exit uses
REGISTRY_UNREADABLE_EXIT_CODE, which the tray treats like any other exit. The Agent
itself is the only owner of the authority decision, including refusing child profiles.
"""

from __future__ import annotations

import ctypes
import json
import msvcrt
import os
import threading
import time
from ctypes import wintypes
from pathlib import Path
from typing import Any, Callable

AUTHORITY_LOST_EXIT_CODE = 78
REGISTRY_UNREADABLE_EXIT_CODE = 74
# Another Agent already serves this profile's port; the tray retries after its backoff.
PORT_IN_USE_EXIT_CODE = 75
WATCHDOG_EXIT_CODE = 70
AUTHORIZED, REVOKED, UNREADABLE, PENDING = "authorized", "revoked", "unreadable", "pending"
# About 10 s at the 2 s poll: one unreadable read (e.g. mid-replacement) is not a revocation.
UNREADABLE_LIMIT = 5
POLL_SECONDS = 2.0
WATCHDOG_EVERY_POLLS = 5
FILE_ATTRIBUTE_REPARSE_POINT = 0x400

_kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
_advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
_kernel32.CreateFileW.restype = wintypes.HANDLE
_kernel32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                  ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD,
                                  wintypes.HANDLE]
_kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
_kernel32.GetCurrentProcess.restype = wintypes.HANDLE
_kernel32.LocalFree.argtypes = [ctypes.c_void_p]
_advapi32.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                       ctypes.POINTER(wintypes.HANDLE)]
_advapi32.GetTokenInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
                                          wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
_advapi32.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p,
                                             ctypes.POINTER(wintypes.LPWSTR)]
_INVALID_HANDLE = wintypes.HANDLE(-1).value


def read_registry(path: Path) -> dict[str, Any]:
    """Read gateway.json with delete sharing so its atomic replacement never fails."""
    handle = _kernel32.CreateFileW(str(path), 0x80000000, 0x7, None, 3, 0x80, None)
    if handle in (None, _INVALID_HANDLE):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        descriptor = msvcrt.open_osfhandle(handle, os.O_RDONLY)
    except OSError:
        _kernel32.CloseHandle(handle)
        raise
    with open(descriptor, "rb") as stream:
        value = json.loads(stream.read().decode("utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError("Gateway registry is not an object.")
    return value


def current_user_sid() -> str:
    token = wintypes.HANDLE()
    if not _advapi32.OpenProcessToken(_kernel32.GetCurrentProcess(), 0x0008,
                                      ctypes.byref(token)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        size = wintypes.DWORD()
        _advapi32.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))
        buffer = ctypes.create_string_buffer(size.value)
        if not _advapi32.GetTokenInformation(token, 1, buffer, size, ctypes.byref(size)):
            raise ctypes.WinError(ctypes.get_last_error())
        sid = ctypes.cast(buffer, ctypes.POINTER(ctypes.c_void_p))[0]  # TOKEN_USER.User.Sid
        text = wintypes.LPWSTR()
        if not _advapi32.ConvertSidToStringSidW(sid, ctypes.byref(text)):
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            return str(text.value)
        finally:
            _kernel32.LocalFree(ctypes.cast(text, ctypes.c_void_p))
    finally:
        _kernel32.CloseHandle(token)


def _same_path(left: Any, right: Path) -> bool:
    if not isinstance(left, (str, Path)) or not str(left).strip():
        return False
    return os.path.normcase(os.path.abspath(str(left))).rstrip("\\/") == \
        os.path.normcase(os.path.abspath(str(right))).rstrip("\\/")


def has_reparse_point(path: Path) -> bool:
    for candidate in (path, *path.parents):
        try:
            attributes = os.lstat(candidate).st_file_attributes
        except FileNotFoundError:
            continue
        if attributes & FILE_ATTRIBUTE_REPARSE_POINT:
            return True
    return False


def profile_authority(install_root: Path, profile_root: Path, sid: str,
                      read: Callable[[Path], dict[str, Any]] = read_registry) -> str:
    """AUTHORIZED or REVOKED from a parsed registry; UNREADABLE when it cannot be read now."""
    try:
        registry = read(install_root / "gateway" / "gateway.json")
    except (OSError, ValueError):
        return UNREADABLE
    profiles = registry.get("profiles")
    entry = profiles.get(profile_root.name) if isinstance(profiles, dict) else None
    expected_root = install_root / "profiles" / profile_root.name
    if not isinstance(entry, dict) or entry.get("enabled") is not True or \
            str(entry.get("kind", "")).casefold() == "child" or \
            entry.get("deletion_tombstone") is not None or \
            not _same_path(entry.get("profile_root"), expected_root) or \
            not _same_path(profile_root, expected_root) or \
            has_reparse_point(expected_root):
        return REVOKED
    expected_sid = entry.get("windows_account_sid")
    if not sid or not isinstance(expected_sid, str) or expected_sid.casefold() != sid.casefold():
        return REVOKED
    return AUTHORIZED


class AuthorityMonitor:
    """Fails closed on a revocation at once and on an unreadable registry after a streak."""

    def __init__(self, check: Callable[[], str], limit: int = UNREADABLE_LIMIT) -> None:
        self.check, self.limit, self.unreadable = check, limit, 0

    def poll(self) -> str:
        state = self.check()
        if state != UNREADABLE:
            self.unreadable = 0
            return state
        self.unreadable += 1
        return UNREADABLE if self.unreadable >= self.limit else PENDING

    def wait(self, sleep: Callable[[float], None] = time.sleep) -> str:
        """Startup check: tolerate the same unreadable streak as the running Agent."""
        state = self.poll()
        while state == PENDING:
            sleep(POLL_SECONDS)
            state = self.poll()
        return state


class Watchdog:
    """Names a probe that failed on consecutive ticks (a hung loop or lock)."""

    def __init__(self, probes: dict[str, Callable[[], bool]], allowed_failures: int = 1) -> None:
        self.probes, self.allowed = probes, allowed_failures
        self.failures = dict.fromkeys(probes, 0)

    def tick(self) -> str | None:
        for name, probe in self.probes.items():
            try:
                alive = bool(probe())
            except Exception:
                alive = False
            self.failures[name] = 0 if alive else self.failures[name] + 1
            if self.failures[name] > self.allowed:
                return name
        return None


def authority_exit(state: str) -> tuple[int, str, str]:
    """Exit of an Agent whose authority is REVOKED or UNREADABLE (never a block for the latter)."""
    if state == REVOKED:
        return AUTHORITY_LOST_EXIT_CODE, "agent.authority-lost", state
    return REGISTRY_UNREADABLE_EXIT_CODE, "agent.registry-unreadable", state


def start_guard(monitor: AuthorityMonitor, watchdog: Watchdog,
                on_exit: Callable[[int, str, str], None],
                sleep: Callable[[float], None] = time.sleep) -> threading.Thread:
    """Poll authority every 2 s and the watchdog every 10 s; on_exit never returns."""
    def loop() -> None:
        polls = 0
        while True:
            sleep(POLL_SECONDS)
            state = monitor.poll()
            if state in (REVOKED, UNREADABLE):
                on_exit(*authority_exit(state))
                return
            polls += 1
            if polls % WATCHDOG_EVERY_POLLS == 0:
                hung = watchdog.tick()
                if hung is not None:
                    on_exit(WATCHDOG_EXIT_CODE, "agent.watchdog-hang", hung)
                    return

    thread = threading.Thread(target=loop, name="AgentGuard", daemon=True)
    thread.start()
    return thread


def lock_probe(lock: Any, timeout: float = 5.0) -> Callable[[], bool]:
    def probe() -> bool:
        if not lock.acquire(timeout=timeout):
            return False
        lock.release()
        return True
    return probe
