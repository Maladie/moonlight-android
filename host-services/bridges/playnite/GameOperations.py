"""Provider-specific game operation dispatch and completion probes."""

from __future__ import annotations

import json
import os
import re
import stat
import subprocess
import urllib.parse
from pathlib import Path
from typing import Any, Callable

from OperationJournal import OperationJournal


class GenericPlayniteProvider:
    def dispatch_install(self, game: dict[str, Any],
                         send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return send_command("install", id=str(game["id"]))

    def dispatch_uninstall(self, game: dict[str, Any],
                           send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return send_command("uninstall", id=str(game["id"]))

    def sample(self, _game: dict[str, Any], _operation: str) -> dict[str, Any] | None:
        return None

    def manual_attention(self, _game: dict[str, Any],
                         _operation: str) -> dict[str, Any] | None:
        return None

    def confirm_operation(self, _hwnd: int, _operation: str, _game_name: str,
                          _allow_visual_fallback: bool = False,
                          _probe_only: bool = False) -> dict[str, Any]:
        return {"clicked": False, "reason": "automation_unavailable"}

    def can_auto_confirm(self, _sample: dict[str, Any]) -> bool:
        return False

    def expected_launcher_images(self) -> set[str]:
        return set()


class SteamProvider(GenericPlayniteProvider):
    KEY_VALUE = re.compile(r'^\s*"([^"]+)"\s+"([^"]*)"\s*$')
    OPERATIONS = {
        "install": "+app_install",
        "uninstall": "+app_uninstall",
    }

    def __init__(self, automation_path: Path | None = None,
                 roots: list[Path] | None = None,
                 root_resolver: Callable[[], Path | None] | None = None,
                 command_runner: Callable[..., Any] | None = None) -> None:
        self.automation_path = automation_path
        self.roots = roots
        self.root_resolver = root_resolver
        self.command_runner = command_runner or subprocess.Popen

    @staticmethod
    def _values(path: Path) -> dict[str, str] | None:
        values: dict[str, str] = {}
        try:
            for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
                match = SteamProvider.KEY_VALUE.match(line)
                if match:
                    values[match.group(1).casefold()] = match.group(2)
        except OSError:
            return None
        return values

    @staticmethod
    def _steam_root() -> Path | None:
        if os.name != "nt":
            return None
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as key:
                value, _kind = winreg.QueryValueEx(key, "SteamPath")
                return Path(str(value))
        except (ImportError, OSError):
            return None

    def _root(self) -> Path | None:
        return self.root_resolver() if self.root_resolver is not None else self._steam_root()

    @staticmethod
    def _path_state(path: Path) -> tuple[str, os.stat_result | None]:
        try:
            details = path.stat()
        except FileNotFoundError:
            return "absent", None
        except OSError:
            return "unavailable", None
        if stat.S_ISDIR(details.st_mode):
            return "directory", details
        if stat.S_ISREG(details.st_mode):
            return "file", details
        return "other", details

    def _library_discovery(self) -> dict[str, Any]:
        if self.roots is not None:
            return {
                "available": bool(self.roots), "complete": bool(self.roots),
                "libraries": list(self.roots),
            }
        root = self._root()
        if root is None or self._path_state(root)[0] != "directory":
            return {"available": False, "complete": False, "libraries": []}
        result = [root]
        complete = True
        try:
            lines = (root / "steamapps" / "libraryfolders.vdf").read_text(
                encoding="utf-8-sig", errors="replace").splitlines()
            for line in lines:
                match = self.KEY_VALUE.match(line)
                if match and match.group(1).casefold() == "path":
                    candidate = Path(match.group(2).replace("\\\\", "\\"))
                    if candidate not in result:
                        result.append(candidate)
        except OSError:
            complete = False
        return {"available": True, "complete": complete, "libraries": result}

    def libraries(self) -> list[Path]:
        return list(self._library_discovery()["libraries"])

    def executable(self) -> Path | None:
        root = self._root()
        if root is None:
            return None
        try:
            executable = (root / "steam.exe").resolve(strict=True)
        except OSError:
            return None
        if executable.name.casefold() != "steam.exe" \
                or self._path_state(executable)[0] != "file":
            return None
        return executable

    def _direct_dispatch(self, game: dict[str, Any], operation: str) -> dict[str, Any] | None:
        console_command = self.OPERATIONS.get(operation)
        app_id = str(game.get("providerGameId") or "").strip()
        if console_command is None:
            raise ValueError("Unsupported Steam operation.")
        if not re.fullmatch(r"[0-9]+", app_id):
            return None
        executable = self.executable()
        if executable is None:
            return None
        arguments = [str(executable), "-silent", console_command, app_id]
        try:
            self.command_runner(
                arguments, cwd=str(executable.parent), shell=False,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.SubprocessError):
            return None
        return {
            "accepted": True, "command": operation, "provider": "steam",
            "dispatch": "direct",
        }

    @staticmethod
    def dispatch_playnite(game: dict[str, Any], operation: str,
                          send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        if operation not in SteamProvider.OPERATIONS:
            raise ValueError("Unsupported Steam operation.")
        result = send_command(operation, id=str(game["id"]))
        return {**result, "provider": "steam", "dispatch": "playnite"}

    def dispatch_install(self, game: dict[str, Any],
                         send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return self._direct_dispatch(game, "install") or \
            self.dispatch_playnite(game, "install", send_command)

    def dispatch_uninstall(self, game: dict[str, Any],
                           send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return self._direct_dispatch(game, "uninstall") or \
            self.dispatch_playnite(game, "uninstall", send_command)

    @staticmethod
    def _number(values: dict[str, str], key: str) -> int:
        try:
            return max(0, int(values.get(key, "0")))
        except ValueError:
            return 0

    def scan(self, game: dict[str, Any]) -> dict[str, Any]:
        app_id = str(game.get("providerGameId") or "").strip()
        result: dict[str, Any] = {
            "provider": "steam", "app_id": app_id,
            "scan_available": False, "scan_complete": False,
            "manifest_present": False, "manifest_readable": False,
            "download_present": False, "manifest_signature": None,
            "bytes_downloaded": 0, "bytes_total": 0, "state_flags": 0,
            "install_directory": "", "manifest_complete": False,
        }
        if not re.fullmatch(r"[0-9]+", app_id):
            return result
        discovery = self._library_discovery()
        result["scan_complete"] = bool(discovery["complete"])
        for library in discovery["libraries"]:
            steamapps = library / "steamapps"
            if self._path_state(steamapps)[0] != "directory":
                result["scan_complete"] = False
                continue
            result["scan_available"] = True
            manifest = steamapps / f"appmanifest_{app_id}.acf"
            manifest_state, manifest_stat = self._path_state(manifest)
            if manifest_state == "unavailable":
                result["scan_complete"] = False
            elif manifest_state == "file":
                result["manifest_present"] = True
                values = self._values(manifest)
                if values is None:
                    result["scan_complete"] = False
                elif not result["manifest_readable"]:
                    downloaded = self._number(values, "bytesdownloaded")
                    total = self._number(values, "bytestodownload")
                    flags = self._number(values, "stateflags")
                    install_name = str(values.get("installdir") or "").strip()
                    install_directory = steamapps / "common" / install_name \
                        if install_name else None
                    directory_ready = install_directory is not None and \
                        self._path_state(install_directory)[0] == "directory"
                    signature = (
                        str(manifest), int(manifest_stat.st_mtime_ns),
                        int(manifest_stat.st_size), downloaded, total, flags,
                        install_name,
                    )
                    result.update({
                        "manifest_readable": True,
                        "manifest_signature": signature,
                        "bytes_downloaded": downloaded,
                        "bytes_total": total,
                        "state_flags": flags,
                        "install_directory": str(install_directory or ""),
                        "manifest_complete": flags == 4 and directory_ready,
                    })
            elif manifest_state != "absent":
                result["scan_complete"] = False

            download_state, _download_stat = self._path_state(
                steamapps / "downloading" / app_id)
            if download_state == "directory":
                result["download_present"] = True
            elif download_state == "unavailable":
                result["scan_complete"] = False
        if not discovery["available"]:
            result["scan_complete"] = False
        return result

    def operation_baseline(self, game: dict[str, Any]) -> dict[str, Any]:
        return self.scan(game)

    @staticmethod
    def _started(snapshot: dict[str, Any], baseline: dict[str, Any],
                 restored: bool) -> bool:
        baseline_healthy = bool(
            baseline.get("scan_available") and baseline.get("scan_complete"))
        if snapshot.get("download_present") and (
                restored or (baseline_healthy and not baseline.get("download_present"))):
            return True
        if not snapshot.get("manifest_readable"):
            return False
        if not baseline.get("manifest_present"):
            if not restored:
                return baseline_healthy
            return int(snapshot.get("bytes_downloaded") or 0) > 0 \
                or int(snapshot.get("state_flags") or 0) not in {0, 4}
        if snapshot.get("manifest_signature") != baseline.get("manifest_signature"):
            return True
        if int(snapshot.get("bytes_downloaded") or 0) > \
                int(baseline.get("bytes_downloaded") or 0):
            return True
        return restored and (
            int(snapshot.get("bytes_downloaded") or 0) > 0
            or int(snapshot.get("state_flags") or 0) not in {0, 4})

    def sample(self, game: dict[str, Any], operation: str,
               baseline: dict[str, Any] | None = None) -> dict[str, Any]:
        snapshot = self.scan(game)
        common = {
            "provider": "steam",
            "scan_available": bool(snapshot["scan_available"]),
            "scan_complete": bool(snapshot["scan_complete"]),
            "manifest_present": bool(snapshot["manifest_present"]),
            "steam_snapshot": snapshot,
        }
        if operation == "install" and snapshot.get("manifest_complete"):
            return {
                **common, "installed": True, "started": True,
                "phase": "completed_install", "progress": 100,
                "install_directory": str(snapshot["install_directory"]),
            }
        if operation == "uninstall" and snapshot.get("scan_available") \
                and snapshot.get("scan_complete") \
                and not snapshot.get("manifest_present"):
            return {**common, "uninstalled": True, "phase": "completed_uninstall"}
        operation_baseline = (baseline or {}).get("steam_baseline") or {}
        started = self._started(
            snapshot, operation_baseline, bool((baseline or {}).get("restored")))
        if started and operation == "uninstall":
            return {
                **common, "started": True, "phase": "active_uninstall",
                "requires_attention": False, "reason": "steam_uninstalling",
            }
        if started:
            downloaded = int(snapshot.get("bytes_downloaded") or 0)
            total = int(snapshot.get("bytes_total") or 0)
            progress = min(100, downloaded * 100 // total) if total else None
            verifying = total > 0 and downloaded >= total
            return {
                **common, "started": True,
                "phase": "verifying" if verifying else "active_install",
                "requires_attention": False,
                "reason": "steam_verifying" if verifying else "steam_downloading",
                "progress": progress,
            }
        if not snapshot.get("scan_available"):
            return {
                **common, "started": False, "phase": "scan_unavailable",
                "requires_attention": False, "reason": "steam_scan_unavailable",
            }
        if not snapshot.get("scan_complete"):
            return {
                **common, "started": False, "phase": "scan_incomplete",
                "requires_attention": False, "reason": "steam_scan_incomplete",
            }
        return {
            **common, "started": False, "phase": "not_started",
            "requires_attention": False, "reason": "steam_not_started",
        }

    def confirm_operation(self, hwnd: int, operation: str, game_name: str,
                          allow_visual_fallback: bool = False,
                          probe_only: bool = False) -> dict[str, Any]:
        script = self.automation_path
        if os.name != "nt" or script is None or not script.is_file():
            return {"clicked": False, "reason": "automation_unavailable"}
        try:
            result = subprocess.run([
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", str(script), "-WindowHandle", str(int(hwnd)),
                "-Operation", operation, "-GameName", game_name,
                *(["-AllowVisualFallback"] if allow_visual_fallback else []),
                *(["-ProbeOnly"] if probe_only else []),
            ], capture_output=True, text=True, timeout=15,
                creationflags=subprocess.CREATE_NO_WINDOW, check=False)
            if result.returncode != 0:
                return {"clicked": False, "reason": "automation_failed"}
            payload = json.loads(result.stdout.strip().splitlines()[-1])
            return payload if isinstance(payload, dict) else {
                "clicked": False, "reason": "invalid_automation_result"}
        except (OSError, subprocess.SubprocessError, ValueError, IndexError,
                json.JSONDecodeError):
            return {"clicked": False, "reason": "automation_failed"}

    def can_auto_confirm(self, sample: dict[str, Any]) -> bool:
        return str(sample.get("image") or "").casefold() in self.expected_launcher_images()

    def expected_launcher_images(self) -> set[str]:
        return {"steam.exe", "steamwebhelper.exe"}


class EpicProvider(GenericPlayniteProvider):
    def __init__(self, manifests: Path | None = None) -> None:
        self.manifests = manifests

    def directory(self) -> Path:
        return self.manifests or Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / \
            "Epic" / "EpicGamesLauncher" / "Data" / "Manifests"

    def scan(self) -> dict[str, Any]:
        directory = self.directory()
        if not directory.is_dir():
            return {"available": False, "complete": False, "by_id": {}, "by_name": {}}
        by_id: dict[str, dict[str, Any]] = {}
        by_name: dict[str, list[dict[str, Any]]] = {}
        complete = True
        try:
            paths = list(directory.glob("*.item"))
        except OSError:
            return {"available": False, "complete": False, "by_id": {}, "by_name": {}}
        for path in paths:
            try:
                value = json.loads(path.read_text(encoding="utf-8-sig"))
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                complete = False
                continue
            app_name = str(value.get("AppName") or "").strip().casefold()
            display_name = str(value.get("DisplayName") or "").strip().casefold()
            install_directory = str(value.get("InstallLocation") or "").strip()
            executable = str(value.get("LaunchExecutable") or "").strip()
            installed = not bool(value.get("bIsIncompleteInstall", False)) and \
                bool(install_directory) and Path(install_directory).is_dir() and \
                (not executable or (Path(install_directory) / executable).is_file())
            item = {"installed": installed, "install_directory": install_directory,
                    "provider": "epic"}
            if app_name:
                by_id[app_name] = item
            if display_name:
                by_name.setdefault(display_name, []).append(item)
        return {"available": True, "complete": complete,
                "by_id": by_id, "by_name": by_name}

    @staticmethod
    def installed_from_snapshot(game: dict[str, Any],
                                snapshot: dict[str, Any]) -> dict[str, Any] | None:
        if not snapshot.get("available"):
            return None
        provider_id = str(game.get("providerGameId") or "").strip().casefold()
        name = str(game.get("name") or "").strip().casefold()
        item = (snapshot.get("by_id") or {}).get(provider_id) if provider_id else None
        if item is not None:
            return item if item.get("installed") else None
        matches = (snapshot.get("by_name") or {}).get(name, []) if name else []
        installed = [value for value in matches if value.get("installed")]
        return installed[0] if len(installed) == 1 else None

    def sample(self, game: dict[str, Any], operation: str) -> dict[str, Any] | None:
        snapshot = self.scan()
        installed = self.installed_from_snapshot(game, snapshot)
        if installed:
            return None if operation == "uninstall" else {
                "installed": True, "progress": 100, **installed}
        provider_id = str(game.get("providerGameId") or "").strip().casefold()
        if operation == "uninstall" and snapshot.get("available") \
                and snapshot.get("complete") and provider_id \
                and provider_id not in (snapshot.get("by_id") or {}):
            return {"uninstalled": True, "provider": "epic"}
        if operation == "install" and snapshot.get("available") \
                and snapshot.get("complete") and provider_id \
                and provider_id not in (snapshot.get("by_id") or {}):
            return {"manifest_absent": True, "provider": "epic",
                    "requires_attention": False, "reason": "epic_manifest_absent"}
        return None

    def dispatch_install(self, game: dict[str, Any],
                         _send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        app_name = str(game.get("providerGameId") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]+", app_name):
            raise ValueError("Epic game identifier is unavailable.")
        uri = "com.epicgames.launcher://apps/" + urllib.parse.quote(
            app_name, safe="") + "?action=install"
        if os.name != "nt":
            raise OSError("Epic installation requires Windows.")
        os.startfile(uri)  # type: ignore[attr-defined]
        return {"accepted": True, "command": "install", "provider": "epic"}

    def manual_attention(self, _game: dict[str, Any],
                         _operation: str) -> dict[str, Any]:
        return {"reason": "epic_manual", "launcher": "epicgameslauncher.exe"}


class GameOperationsService:
    def __init__(self, journal: OperationJournal,
                 generic: GenericPlayniteProvider | None = None,
                 steam: SteamProvider | None = None,
                 epic: EpicProvider | None = None) -> None:
        self.journal = journal
        self.generic = generic or GenericPlayniteProvider()
        self.steam = steam or SteamProvider()
        self.epic = epic or EpicProvider()

    @staticmethod
    def provider_label(game: dict[str, Any]) -> str:
        return str(game.get("source") or game.get("pluginName") or "playnite").casefold()

    def provider_for(self, game: dict[str, Any]) -> GenericPlayniteProvider:
        return self.provider_for_label(self.provider_label(game))

    def provider_for_label(self, label: str) -> GenericPlayniteProvider:
        normalized = str(label).casefold()
        if "epic" in normalized:
            return self.epic
        if "steam" in normalized:
            return self.steam
        return self.generic

    def dispatch_install(self, game: dict[str, Any],
                         send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return self.provider_for(game).dispatch_install(game, send_command)

    def dispatch_uninstall(self, game: dict[str, Any],
                           send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return self.provider_for(game).dispatch_uninstall(game, send_command)

    def sample(self, baseline: dict[str, Any]) -> dict[str, Any] | None:
        game = baseline.get("game") or {}
        provider = self.provider_for(game)
        operation = str(baseline.get("operation") or "install")
        if provider is self.steam:
            return self.steam.sample(game, operation, baseline)
        return provider.sample(game, operation)

    def operation_baseline(self, game: dict[str, Any]) -> dict[str, Any] | None:
        if self.provider_for(game) is self.steam:
            return self.steam.operation_baseline(game)
        return None

    def dispatch_steam_fallback(self, game: dict[str, Any], operation: str,
                                send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        if self.provider_for(game) is not self.steam:
            raise ValueError("Playnite fallback is only explicit for Steam operations.")
        return self.steam.dispatch_playnite(game, operation, send_command)

    def manual_attention(self, game: dict[str, Any],
                         operation: str) -> dict[str, Any] | None:
        return self.provider_for(game).manual_attention(game, operation)

    def confirm_operation(self, game: dict[str, Any], hwnd: int, operation: str,
                          game_name: str, allow_visual_fallback: bool = False,
                          probe_only: bool = False) -> dict[str, Any]:
        return self.provider_for(game).confirm_operation(
            hwnd, operation, game_name, allow_visual_fallback, probe_only)

    def can_auto_confirm(self, game: dict[str, Any], sample: dict[str, Any]) -> bool:
        return self.provider_for(game).can_auto_confirm(sample)

    def expected_launcher_images(self, game: dict[str, Any]) -> set[str]:
        return self.provider_for(game).expected_launcher_images()

    def external_snapshot(self) -> dict[str, Any]:
        return self.epic.scan()

    def installed_from_external_snapshot(self, game: dict[str, Any],
                                         snapshot: dict[str, Any]) -> dict[str, Any] | None:
        if self.provider_for(game) is not self.epic:
            return None
        return self.epic.installed_from_snapshot(game, snapshot)
