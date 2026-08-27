"""Provider-specific game operation dispatch and completion probes."""

from __future__ import annotations

import json
import os
import re
import stat
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Hashable

from OperationJournal import OperationJournal


@dataclass
class TrackedOperationProcess:
    """One provider process, correlated to the operation that started it."""

    task_id: Hashable
    game_id: str
    provider: str
    operation_type: str
    process_type: str
    phase: str
    process: Any
    started_at: float = field(default_factory=time.time)
    progress: int | None = None
    error_excerpt: str = ""
    app_name: str = ""
    executable: Path | None = None
    environment: dict[str, str] | None = None
    collector: Any = None
    finalizing: bool = False


class OperationProcessRegistry:
    """Small in-memory registry; the durable owner remains OperationJournal."""

    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.by_task: dict[Hashable, TrackedOperationProcess] = {}
        self.active_by_game: dict[str, Hashable] = {}

    def register(self, tracked: TrackedOperationProcess) -> bool:
        with self.lock:
            previous_id = self.active_by_game.get(tracked.game_id)
            previous = self.by_task.get(previous_id) if previous_id is not None else None
            if previous is not None and (previous.finalizing
                                         or previous.process.poll() is None):
                return False
            if previous_id is not None:
                self.by_task.pop(previous_id, None)
            self.by_task[tracked.task_id] = tracked
            self.active_by_game[tracked.game_id] = tracked.task_id
            return True

    def get(self, task_id: Hashable) -> TrackedOperationProcess | None:
        with self.lock:
            return self.by_task.get(task_id)

    def active(self, game_id: str) -> TrackedOperationProcess | None:
        with self.lock:
            task_id = self.active_by_game.get(game_id)
            return self.by_task.get(task_id) if task_id is not None else None

    def busy(self, game_id: str) -> bool:
        with self.lock:
            tracked = self.active(game_id)
            if tracked is None:
                return False
            if tracked.finalizing or tracked.process.poll() is None:
                return True
            self.remove(tracked)
            return False

    def remove(self, tracked: TrackedOperationProcess) -> None:
        with self.lock:
            if self.by_task.get(tracked.task_id) is tracked:
                self.by_task.pop(tracked.task_id, None)
            if self.active_by_game.get(tracked.game_id) == tracked.task_id:
                self.active_by_game.pop(tracked.game_id, None)

    def owns(self, tracked: TrackedOperationProcess) -> bool:
        with self.lock:
            return self.by_task.get(tracked.task_id) is tracked \
                and self.active_by_game.get(tracked.game_id) == tracked.task_id


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
    def _started(snapshot: dict[str, Any], _baseline: dict[str, Any],
                 _restored: bool, operation: str) -> bool:
        if snapshot.get("download_present"):
            return True
        if not snapshot.get("manifest_readable"):
            return False
        downloaded = int(snapshot.get("bytes_downloaded") or 0)
        total = int(snapshot.get("bytes_total") or 0)
        flags = int(snapshot.get("state_flags") or 0)
        if flags not in {0, 4}:
            return True
        if operation == "install" and total > 0 and 0 < downloaded <= total:
            return True
        return False

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
            snapshot, operation_baseline, bool((baseline or {}).get("restored")),
            operation)
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
    """Epic operations, preferring optional per-profile Legendary."""

    def __init__(self, manifests: Path | None = None,
                 legendary_path: Path | None = None,
                 legendary_state_path: Path | None = None,
                 epic_install_root: Path | None = None,
                 legendary_enabled: bool = True,
                 command_runner: Callable[..., Any] | None = None,
                 process_runner: Callable[..., Any] | None = None,
                 process_registry: OperationProcessRegistry | None = None) -> None:
        self.manifests = manifests
        self.legendary_path = legendary_path
        self.legendary_state_path = legendary_state_path
        self.epic_install_root = epic_install_root
        self.legendary_enabled = legendary_enabled
        self.command_runner = command_runner or subprocess.run
        self.process_runner = process_runner or subprocess.Popen
        self.process_registry = process_registry or OperationProcessRegistry()

    def directory(self) -> Path:
        return self.manifests or Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / \
            "Epic" / "EpicGamesLauncher" / "Data" / "Manifests"

    @staticmethod
    def _valid_install_root(value: str) -> Path | None:
        candidate = Path(os.path.expandvars(str(value or "").strip().strip('"')))
        return candidate if candidate.is_absolute() \
            and candidate != Path(candidate.anchor) else None

    def _install_root(self) -> Path:
        if self.epic_install_root is not None:
            explicit = self._valid_install_root(str(self.epic_install_root))
            if explicit is not None:
                return explicit
        local_app_data = os.environ.get("LOCALAPPDATA", "")
        if local_app_data:
            config_root = Path(local_app_data) / "EpicGamesLauncher" / "Saved" / "Config"
            for platform in ("WindowsEditor", "Windows"):
                settings = config_root / platform / "GameUserSettings.ini"
                try:
                    text = settings.read_bytes().decode("utf-8-sig", errors="ignore")
                except OSError:
                    continue
                match = re.search(
                    r"(?im)^\s*DefaultAppInstallLocation\s*=\s*(.*?)\s*$", text)
                configured = self._valid_install_root(match.group(1) if match else "")
                if configured is not None:
                    return configured
        counts: dict[str, tuple[int, Path]] = {}
        for item in (self.scan().get("by_id") or {}).values():
            location = self._valid_install_root(str(item.get("install_location") or ""))
            if location is None:
                continue
            root = location.parent
            key = os.path.normcase(os.path.normpath(str(root)))
            count, _ = counts.get(key, (0, root))
            counts[key] = (count + 1, root)
        if counts:
            return sorted(counts.values(), key=lambda value: (-value[0],
                          str(value[1]).casefold()))[0][1]
        return Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Epic Games"

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
            install_location = value.get("InstallLocation")
            install_directory = str(install_location or "").strip()
            executable = str(value.get("LaunchExecutable") or "").strip()
            install_path = Path(install_directory) if install_directory else None
            egstore = install_path / ".egstore" if install_path is not None else None
            try:
                pending = bool(egstore and (egstore / "Pending").exists())
            except OSError:
                pending = True
            installed = not bool(value.get("bIsIncompleteInstall", False)) and \
                install_path is not None and install_path.is_dir() and not pending and \
                (not executable or (install_path / executable).is_file())
            item = {"installed": installed, "install_directory": install_directory,
                    "install_location": install_location if isinstance(install_location, str) else "",
                    "manifest_path": str(path.absolute()), "provider": "epic"}
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

    def _legendary(self) -> Path | None:
        if not self.legendary_enabled:
            self._legendary_reason = "legendary_disabled"
            return None
        if self.legendary_path is None:
            self._legendary_reason = "legendary_unavailable"
            return None
        try:
            executable = self.legendary_path.resolve(strict=True)
        except OSError:
            self._legendary_reason = "legendary_unavailable"
            return None
        if executable.is_file() and executable.name.casefold() == "legendary.exe":
            self._legendary_reason = ""
            return executable
        self._legendary_reason = "legendary_unavailable"
        return None

    def _legendary_environment(self) -> dict[str, str] | None:
        executable = self._legendary()
        if executable is None or self.legendary_state_path is None:
            if executable is not None:
                self._legendary_reason = "legendary_unavailable"
            return None
        try:
            self.legendary_state_path.mkdir(parents=True, exist_ok=True)
        except OSError:
            self._legendary_reason = "legendary_unavailable"
            return None
        environment = dict(os.environ)
        environment["LEGENDARY_CONFIG_PATH"] = str(self.legendary_state_path)
        return environment

    def _legendary_json(self, arguments: list[str]) -> tuple[bool, Any | None]:
        executable = self._legendary()
        environment = self._legendary_environment()
        if executable is None or environment is None:
            return False, None
        try:
            result = self.command_runner(
                [str(executable), *arguments], capture_output=True, text=True,
                shell=False, env=environment, cwd=str(executable.parent), timeout=15,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), check=False)
            return (True, json.loads(str(result.stdout or ""))) if result.returncode == 0 else (False, None)
        except (OSError, subprocess.SubprocessError, ValueError, json.JSONDecodeError):
            return False, None

    @staticmethod
    def _app_name(value: dict[str, Any]) -> str:
        return str(value.get("app_name") or value.get("app") or value.get("AppName") or "").strip()

    def _resolve_legendary_app(self, game: dict[str, Any]) -> str | None:
        stable = str(game.get("providerGameId") or "").strip()
        if re.fullmatch(r"[A-Za-z0-9_-]+", stable):
            info_ok, info = self._legendary_json(["info", stable, "--json", "--platform", "Windows"])
            if info_ok and isinstance(info, dict): return stable
        catalog_ok, catalog = self._legendary_json(["list", "--json"])
        if not catalog_ok or not isinstance(catalog, list):
            self._dispatch_reason = "legendary_auth_required"
            return None
        title = str(game.get("name") or "").strip().casefold()
        matches = [self._app_name(item) for item in catalog if isinstance(item, dict)
                   and ((stable and self._app_name(item).casefold() == stable.casefold()) or
                        (title and str(item.get("title") or item.get("app_title") or "").strip().casefold() == title))]
        if len(matches) == 1 and matches[0]: return matches[0]
        self._dispatch_reason = "legendary_unresolved"
        return None

    @staticmethod
    def _verified_legendary_item(item: Any) -> tuple[str, dict[str, Any]] | None:
        if not isinstance(item, dict):
            return None
        if item.get("needs_verification") is True:
            return None
        app_name = EpicProvider._app_name(item)
        install_directory = str(item.get("install_path") or item.get("install_dir")
                                or item.get("install_location") or "").strip()
        executable = str(item.get("executable") or "").strip()
        if not app_name or not install_directory or not executable:
            return None
        directory = Path(install_directory)
        relative_executable = Path(executable)
        if not directory.is_absolute() or directory == Path(directory.anchor) \
                or relative_executable.is_absolute():
            return None
        try:
            resolved_directory = directory.resolve(strict=True)
            resolved_executable = (directory / relative_executable).resolve(strict=True)
            resolved_executable.relative_to(resolved_directory)
        except (OSError, ValueError):
            return None
        if not resolved_directory.is_dir() or not resolved_executable.is_file():
            return None
        return app_name.casefold(), {
            "installed": True, "provider": "epic",
            "install_directory": str(directory), "executable": executable,
            "app_name": app_name,
        }

    def legendary_snapshot(self) -> dict[str, Any]:
        readable, payload = self._legendary_json(
            ["list-installed", "--json", "--show-dirs"])
        if not readable:
            return {"available": False, "complete": False, "by_id": {}}
        if isinstance(payload, list):
            items = payload
        elif isinstance(payload, dict) and isinstance(payload.get("installed"), list):
            items = payload["installed"]
        else:
            return {"available": False, "complete": False, "by_id": {}}
        by_id: dict[str, dict[str, Any]] = {}
        invalid_ids: set[str] = set()
        complete = True
        for item in items:
            verified = self._verified_legendary_item(item)
            if verified is None:
                app_name = self._app_name(item) if isinstance(item, dict) else ""
                if app_name:
                    invalid_ids.add(app_name.casefold())
                else:
                    complete = False
                continue
            by_id[verified[0]] = verified[1]
        return {"available": True, "complete": complete, "by_id": by_id,
                "invalid_ids": sorted(invalid_ids)}

    def _installed_legendary(self, app_name: str) -> tuple[bool, dict[str, Any] | None]:
        snapshot = self.legendary_snapshot()
        if not snapshot.get("available"):
            return False, None
        if app_name.casefold() in set(snapshot.get("invalid_ids") or []):
            return False, None
        installed = (snapshot.get("by_id") or {}).get(app_name.casefold())
        if installed is None and not snapshot.get("complete"):
            return False, None
        return True, installed

    def _migration_candidate(self, game: dict[str, Any],
                             app_name: str) -> dict[str, Any] | None:
        trusted, _reason = self._trusted_egl_install_directory(game, app_name)
        if trusted is None:
            return None
        return {
            "accepted": False, "provider": "epic", "requires_attention": False,
            "reason": "legendary_import_required",
            "install_directory": str(trusted["install_directory"]),
        }

    def launch(self, game: dict[str, Any], task_id: Hashable | None = None) -> dict[str, Any]:
        executable, environment = self._legendary(), self._legendary_environment()
        if executable is None or environment is None:
            return {"accepted": False, "command": "launch", "provider": "epic",
                    "requires_attention": False,
                    "reason": getattr(self, "_legendary_reason", "legendary_unavailable")}
        app_name = self._resolve_legendary_app(game)
        if app_name is None:
            return {"accepted": False, "command": "launch", "provider": "epic",
                    "requires_attention": False,
                    "reason": self._dispatch_reason or "legendary_not_installed"}
        listed, installed = self._installed_legendary(app_name)
        if not listed:
            reason = "legendary_verification_failed"
        elif installed is None:
            migration = self._migration_candidate(game, app_name)
            if migration is not None:
                return {**migration, "command": "launch", "app_name": app_name}
            reason = "legendary_not_installed"
        else:
            arguments = [str(executable), "launch", app_name]
            game_id = str(game.get("id") or app_name)
            task_id = task_id if task_id is not None else \
                (game_id, "launch", time.time_ns())
            if self.process_registry.busy(game_id):
                return {"accepted": False, "command": "launch", "provider": "epic",
                        "requires_attention": False, "reason": "operation_busy",
                        "app_name": app_name}
            try:
                process = self.process_runner(
                    arguments, shell=False, env=environment, cwd=str(executable.parent),
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT, text=True,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            except (OSError, subprocess.SubprocessError):
                process = None
            if process is not None:
                tracked = TrackedOperationProcess(
                    task_id=task_id, game_id=game_id, provider="epic",
                    operation_type="launch", process_type="launch", phase="launch",
                    process=process, app_name=app_name, executable=executable,
                    environment=environment)
                if not self.process_registry.register(tracked):
                    try:
                        process.terminate()
                    except (AttributeError, OSError, subprocess.SubprocessError):
                        pass
                    reason = "operation_busy"
                else:
                    self._collect_legendary_output(tracked)
                    immediate = self.sample_launch(game, task_id)
                    if immediate is None or not immediate.get("requires_attention"):
                        return {"accepted": True, "command": "launch", "provider": "epic",
                                "dispatch": "legendary", "app_name": app_name,
                                "task_id": task_id,
                                "install_directory": installed["install_directory"],
                                "executable": str(Path(installed["install_directory"]) /
                                                  installed["executable"])}
                    return {"accepted": False, "command": "launch", "provider": "epic",
                            "requires_attention": False, "app_name": app_name,
                            **immediate}
            else:
                reason = "legendary_start_failed"
        return {"accepted": False, "command": "launch", "provider": "epic",
                "requires_attention": False, "reason": reason,
                "app_name": app_name}

    def sample_launch(self, game: dict[str, Any], task_id: Hashable) -> dict[str, Any] | None:
        tracked = self.process_registry.get(task_id)
        if tracked is None or tracked.game_id != str(game.get("id") or tracked.app_name) \
                or tracked.operation_type != "launch":
            return None
        if tracked.process.poll() is None:
            return {"provider": tracked.provider, "started": True,
                    "process_type": tracked.process_type,
                    "reason": "legendary_launch_running"}
        self._finish_output_collection(tracked)
        exit_code = int(tracked.process.returncode)
        self.process_registry.remove(tracked)
        if exit_code != 0:
            return {"provider": tracked.provider, "requires_attention": True,
                    "reason": "legendary_process_failed", "exit_code": exit_code,
                    "error_excerpt": tracked.error_excerpt,
                    "launcher": "legendary.exe"}
        return {"provider": tracked.provider, "launched": True,
                "reason": "legendary_launch_completed", "exit_code": 0}





    def _egl_manifest_item(self, game: dict[str, Any]) -> dict[str, Any] | None:
        provider_id = str(game.get("providerGameId") or "").strip()
        if not provider_id:
            return None
        item = (self.scan().get("by_id") or {}).get(provider_id.casefold())
        return item if isinstance(item, dict) else None

    def _trusted_egl_manifest(self, game: dict[str, Any], app_name: str,
                              manifest_path: str | None = None,
                              install_location: str | None = None) -> tuple[dict[str, str] | None, str]:
        provider_id = str(game.get("providerGameId") or "").strip()
        if not provider_id or provider_id != app_name:
            return None, "epic_manifest_changed"
        item = self._egl_manifest_item(game) if manifest_path is None else None
        if item is not None:
            manifest_path = str(item.get("manifest_path") or "")
            install_location = str(item.get("install_location") or "")
        if not manifest_path:
            return None, ""
        try:
            root = self.directory().resolve(strict=True)
            source = Path(manifest_path).absolute()
            resolved = source.resolve(strict=True)
            if not root.is_dir() or source.suffix.casefold() != ".item" \
                    or not source.is_file() or not resolved.is_file() or source != resolved \
                    or resolved == root:
                return None, "epic_manifest_cleanup_unsafe"
            resolved.relative_to(root)
            value = json.loads(source.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            return None, "epic_manifest_cleanup_unsafe"
        actual_app = value.get("AppName")
        actual_location = value.get("InstallLocation")
        if not isinstance(actual_app, str) or actual_app != provider_id \
                or not isinstance(actual_location, str) \
                or (install_location is not None and actual_location != install_location):
            return None, "epic_manifest_changed"
        directory = Path(actual_location)
        if not directory.is_absolute() or directory == Path(directory.anchor):
            return None, "epic_manifest_cleanup_unsafe"
        return {"manifest_path": str(source), "install_directory": actual_location}, ""

    @staticmethod
    def _egl_orphaned(install_directory: str) -> bool | None:
        directory = Path(install_directory)
        if not directory.exists():
            return True
        if not directory.is_dir():
            return None
        try:
            return all(path.name.casefold() == ".egstore" for path in directory.iterdir())
        except OSError:
            return None

    def _quarantine_egl_orphan(self, game: dict[str, Any], app_name: str,
                               manifest_path: str, install_directory: str) -> str:
        trusted, reason = self._trusted_egl_manifest(
            game, app_name, manifest_path, install_directory)
        if trusted is None:
            return reason or "epic_manifest_cleanup_unsafe"
        if self._egl_orphaned(trusted["install_directory"]) is not True:
            return "epic_manifest_cleanup_unsafe"
        return self._quarantine_egl_manifest(trusted)

    def _quarantine_tracked_egl_manifest(self, game: dict[str, Any], app_name: str,
                                         manifest_path: str,
                                         install_directory: str) -> str:
        trusted, reason = self._trusted_egl_manifest(
            game, app_name, manifest_path, install_directory)
        if trusted is None:
            return reason or "epic_manifest_cleanup_unsafe"
        return self._quarantine_egl_manifest(trusted)

    def _quarantine_egl_manifest(self, trusted: dict[str, str]) -> str:
        if self.legendary_state_path is None:
            return "epic_manifest_cleanup_failed"
        try:
            state = self.legendary_state_path
            state.mkdir(parents=True, exist_ok=True)
            state = state.resolve(strict=True)
            quarantine = state / "egl-orphaned-manifests"
            quarantine.mkdir(exist_ok=True)
            quarantine = quarantine.resolve(strict=True)
            quarantine.relative_to(state)
            source = Path(trusted["manifest_path"])
            destination = quarantine / f"{source.stem}-{time.time_ns()}{source.suffix}"
            suffix = 0
            while destination.exists():
                suffix += 1
                destination = quarantine / f"{source.stem}-{time.time_ns()}-{suffix}{source.suffix}"
            source.replace(destination)
            if source.exists():
                return "epic_manifest_cleanup_failed"
        except OSError:
            return "epic_manifest_cleanup_failed"
        return ""

    def _trusted_egl_install_directory(self, game: dict[str, Any],
                                       app_name: str) -> tuple[dict[str, str] | None, str]:
        item = self._egl_manifest_item(game)
        if not item:
            return None, ""
        trusted, reason = self._trusted_egl_manifest(game, app_name)
        if trusted is None:
            return None, reason
        directory = Path(trusted["install_directory"])
        try:
            resolved = directory.resolve(strict=True)
            absolute = directory.absolute()
            if not resolved.is_dir() or os.path.normcase(os.path.normpath(str(absolute))) \
                    != os.path.normcase(os.path.normpath(str(resolved))):
                return None, "epic_manifest_cleanup_unsafe"
            payload = False
            for child in resolved.iterdir():
                if child.name.casefold() == ".egstore":
                    continue
                resolved_child = child.resolve(strict=True)
                resolved_child.relative_to(resolved)
                if os.path.normcase(os.path.normpath(str(child.absolute()))) \
                        != os.path.normcase(os.path.normpath(str(resolved_child))):
                    return None, "epic_manifest_cleanup_unsafe"
                payload = True
                break
            if not payload:
                return None, "epic_manifest_cleanup_unsafe"
        except (OSError, ValueError):
            return None, "epic_manifest_cleanup_unsafe"
        return trusted, ""

    @staticmethod
    def _progress(line: str) -> int | None:
        match = re.search(r"(?:^|\s)(\d{1,3})(?:\.\d+)?%", line)
        return min(100, int(match.group(1))) if match else None

    def _start_legendary(self, game: dict[str, Any], operation: str,
                         task_id: Hashable | None = None) -> dict[str, Any] | None:
        self._dispatch_reason = ""
        executable, environment = self._legendary(), self._legendary_environment()
        if executable is None or environment is None:
            self._dispatch_reason = getattr(self, "_legendary_reason", "legendary_unavailable")
            return None
        app_name = self._resolve_legendary_app(game)
        if app_name is None:
            if not self._dispatch_reason: self._dispatch_reason = "legendary_unresolved"
            return None
        listed, installed = self._installed_legendary(app_name)
        if not listed:
            self._dispatch_reason = "legendary_verification_failed"
            return None
        if operation == "install" and installed is not None:
            return {"accepted": True, "command": "install", "provider": "epic",
                    "dispatch": "legendary_preinstalled", "app_name": app_name,
                    "install_directory": installed["install_directory"]}
        migration = self._migration_candidate(game, app_name) \
            if operation == "install" else None
        if operation == "uninstall" and installed is None:
            return {"accepted": True, "command": "uninstall", "provider": "epic",
                    "dispatch": "already_absent", "app_name": app_name}
        if migration is not None:
            arguments = [str(executable), "-y", "import", app_name,
                         str(migration["install_directory"]),
                         "--platform", "Windows", "--skip-dlcs"]
        else:
            arguments = [str(executable), "-y", operation, app_name]
        if operation == "install" and migration is None:
            arguments += ["--platform", "Windows", "--skip-dlcs", "--skip-sdl",
                          "--base-path", str(self._install_root())]
        game_id = str(game.get("id") or app_name)
        if self.process_registry.busy(game_id):
            self._dispatch_reason = "operation_busy"
            return None
        try:
            process = self.process_runner(arguments, shell=False, env=environment, cwd=str(executable.parent),
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.SubprocessError):
            self._dispatch_reason = "legendary_start_failed"
            return None
        task_id = task_id if task_id is not None else \
            (game_id, operation, time.time_ns())
        tracked = TrackedOperationProcess(
            task_id=task_id, game_id=game_id, provider="epic",
            operation_type=operation,
            process_type="import" if migration is not None else operation,
            phase="import" if migration is not None else operation,
            process=process, app_name=app_name, executable=executable,
            environment=environment)
        if not self.process_registry.register(tracked):
            try:
                process.terminate()
            except (AttributeError, OSError, subprocess.SubprocessError):
                pass
            self._dispatch_reason = "operation_busy"
            return None
        self._collect_legendary_output(tracked)
        return {"accepted": True, "command": operation, "provider": "epic",
                "dispatch": "legendary_import" if migration is not None else "legendary",
                "app_name": app_name, "task_id": task_id}

    def _collect_legendary_output(self, tracked: TrackedOperationProcess) -> None:
        process = tracked.process
        if process.stdout is None:
            return
        def collect() -> None:
            for line in process.stdout:
                text = self._error_excerpt(str(line))
                if text:
                    tracked.error_excerpt = text
                progress = self._progress(str(line))
                if progress is not None:
                    tracked.progress = progress
        tracked.collector = threading.Thread(
            target=collect, name="LegendaryOutput", daemon=True)
        tracked.collector.start()

    @staticmethod
    def _finish_output_collection(tracked: TrackedOperationProcess) -> None:
        collector = tracked.collector
        if collector is not None and hasattr(collector, "join"):
            collector.join(timeout=0.2)

    @staticmethod
    def _error_excerpt(line: str) -> str:
        text = "".join(char for char in line if char >= " " and char != "\x7f").strip()
        if any(secret in text.casefold() for secret in (
                "token", "authorization", "refresh", "access_token")):
            return ""
        return text[:300]

    def _sample_legendary(self, game: dict[str, Any], operation: str,
                          task_id: Hashable | None = None) -> dict[str, Any] | None:
        game_id = str(game.get("id") or "")
        with self.process_registry.lock:
            tracked = self.process_registry.by_task.get(task_id) if task_id is not None \
                else self.process_registry.active(game_id)
            if tracked is None or tracked.game_id != game_id \
                    or tracked.operation_type != operation:
                return None
            phase = "active_uninstall" if operation == "uninstall" else "active_install"
            if tracked.finalizing:
                result = {"provider": "epic", "started": True, "phase": phase,
                          "requires_attention": False, "reason": "legendary_verifying"}
                if tracked.progress is not None:
                    result["progress"] = tracked.progress
                return result
            process = tracked.process
            if process.poll() is None:
                result = {"provider": "epic", "started": True, "phase": phase,
                          "requires_attention": False, "reason": "legendary_running"}
                if tracked.progress is not None: result["progress"] = tracked.progress
                return result
            tracked.finalizing = True
            completed_phase = tracked.phase
        transitioned = False
        try:
            self._finish_output_collection(tracked)
            exit_code = int(process.returncode)
            error_excerpt = tracked.error_excerpt
            if not self.process_registry.owns(tracked):
                return {"provider": "epic", "requires_attention": True,
                        "reason": "legendary_operation_stale",
                        "launcher": "legendary.exe"}
            if exit_code != 0:
                return {"provider": "epic", "requires_attention": True,
                        "reason": "legendary_process_failed", "exit_code": exit_code,
                        "error_excerpt": error_excerpt, "launcher": "legendary.exe"}
            if operation == "install" and completed_phase == "import":
                executable = tracked.executable
                arguments = [str(executable), "-y", "repair", tracked.app_name,
                             "--platform", "Windows", "--skip-dlcs", "--skip-sdl"]
                try:
                    repair = self.process_runner(
                        arguments, shell=False, env=tracked.environment,
                        cwd=str(executable.parent), stdin=subprocess.DEVNULL,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                except (OSError, subprocess.SubprocessError):
                    return {"provider": "epic", "requires_attention": True,
                            "reason": "legendary_repair_start_failed", "exit_code": -1,
                            "error_excerpt": "", "launcher": "legendary.exe"}
                with self.process_registry.lock:
                    if self.process_registry.by_task.get(tracked.task_id) is not tracked \
                            or self.process_registry.active_by_game.get(tracked.game_id) \
                            != tracked.task_id:
                        try:
                            repair.terminate()
                        except (AttributeError, OSError, subprocess.SubprocessError):
                            pass
                        return {"provider": "epic", "requires_attention": True,
                                "reason": "legendary_operation_stale",
                                "launcher": "legendary.exe"}
                    tracked.process = repair
                    tracked.process_type = "repair"
                    tracked.phase = "repair"
                    tracked.progress = None
                    tracked.error_excerpt = ""
                    tracked.finalizing = False
                    transitioned = True
                self._collect_legendary_output(tracked)
                return {"provider": "epic", "started": True,
                        "phase": "active_install", "requires_attention": False,
                        "reason": "legendary_repair_running"}
            listed, installed = self._installed_legendary(tracked.app_name)
            if not self.process_registry.owns(tracked):
                return {"provider": "epic", "requires_attention": True,
                        "reason": "legendary_operation_stale",
                        "launcher": "legendary.exe"}
            if operation == "install" and listed and installed:
                return {**installed, "progress": 100,
                        "reconciliation": "legendary_verified"}
            if operation == "uninstall" and listed and not installed:
                return {"uninstalled": True, "provider": "epic",
                        "reconciliation": "legendary_verified"}
            return {"provider": "epic", "requires_attention": True,
                    "reason": "legendary_verification_failed", "exit_code": exit_code,
                    "error_excerpt": error_excerpt, "launcher": "legendary.exe"}
        finally:
            if not transitioned:
                self.process_registry.remove(tracked)

    def sample(self, game: dict[str, Any], operation: str,
               task_id: Hashable | None = None) -> dict[str, Any] | None:
        direct = self._sample_legendary(game, operation, task_id)
        if direct is not None: return direct
        app_name = str(game.get("providerGameId") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]+", app_name):
            return None
        listed, installed = self._installed_legendary(app_name)
        if not listed:
            return {"provider": "epic", "requires_attention": True,
                    "reason": "legendary_verification_failed",
                    "launcher": "legendary.exe"}
        if installed is not None:
            return None if operation == "uninstall" else {
                **installed, "progress": 100, "reconciliation": "legendary_verified"}
        if operation == "uninstall":
            return {"uninstalled": True, "provider": "epic",
                    "reconciliation": "legendary_verified"}
        return None

    def dispatch_install(self, game: dict[str, Any],
                         send_command: Callable[..., dict[str, Any]],
                         task_id: Hashable | None = None) -> dict[str, Any]:
        direct = self._start_legendary(game, "install", task_id)
        if direct is not None: return direct
        return {"accepted": False, "command": "install", "provider": "epic",
                "dispatch": "none", "requires_attention": False,
                "reason": self._dispatch_reason or "legendary_process_failed",
                "launcher": "legendary.exe"}

    def dispatch_uninstall(self, game: dict[str, Any], _send_command: Callable[..., dict[str, Any]],
                           task_id: Hashable | None = None) -> dict[str, Any]:
        direct = self._start_legendary(game, "uninstall", task_id)
        if direct is not None: return direct
        return {"accepted": False, "command": "uninstall", "provider": "epic",
                "dispatch": "none", "requires_attention": False,
                "reason": self._dispatch_reason or "legendary_process_failed"}

class GameOperationsService:
    def __init__(self, journal: OperationJournal,
                 generic: GenericPlayniteProvider | None = None,
                 steam: SteamProvider | None = None,
                 epic: EpicProvider | None = None) -> None:
        self.journal = journal
        self.generic = generic or GenericPlayniteProvider()
        self.steam = steam or SteamProvider()
        self.epic = epic or EpicProvider()
        self.processes = OperationProcessRegistry()
        self.epic.process_registry = self.processes

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
                         send_command: Callable[..., dict[str, Any]],
                         task_id: Hashable | None = None) -> dict[str, Any]:
        provider = self.provider_for(game)
        return self.epic.dispatch_install(game, send_command, task_id) \
            if provider is self.epic else provider.dispatch_install(game, send_command)

    def dispatch_uninstall(self, game: dict[str, Any],
                           send_command: Callable[..., dict[str, Any]],
                           task_id: Hashable | None = None) -> dict[str, Any]:
        provider = self.provider_for(game)
        return self.epic.dispatch_uninstall(game, send_command, task_id) \
            if provider is self.epic else provider.dispatch_uninstall(game, send_command)

    def sample(self, baseline: dict[str, Any]) -> dict[str, Any] | None:
        game = baseline.get("game") or {}
        provider = self.provider_for(game)
        operation = str(baseline.get("operation") or "install")
        if provider is self.steam:
            return self.steam.sample(game, operation, baseline)
        return self.epic.sample(game, operation, baseline.get("task_id")) \
            if provider is self.epic else provider.sample(game, operation)

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

    def launch(self, game: dict[str, Any], task_id: Hashable | None = None) -> dict[str, Any]:
        if self.provider_for(game) is not self.epic:
            raise ValueError("Direct launch is only available for Epic games.")
        return self.epic.launch(game, task_id)

    def sample_launch(self, game: dict[str, Any], task_id: Hashable) -> dict[str, Any] | None:
        if self.provider_for(game) is not self.epic:
            return None
        return self.epic.sample_launch(game, task_id)

    def finish_process(self, task_id: Hashable) -> None:
        tracked = self.processes.get(task_id)
        if tracked is not None:
            self.processes.remove(tracked)

    def migration_candidate(self, game: dict[str, Any]) -> dict[str, Any] | None:
        if self.provider_for(game) is not self.epic:
            return None
        return self.epic._migration_candidate(
            game, str(game.get("providerGameId") or "").strip())

    def external_snapshot(self) -> dict[str, Any]:
        return self.epic.legendary_snapshot()

    def installed_from_external_snapshot(self, game: dict[str, Any],
                                         snapshot: dict[str, Any]) -> dict[str, Any] | None:
        if self.provider_for(game) is not self.epic:
            return None
        provider_id = str(game.get("providerGameId") or "").strip().casefold()
        return (snapshot.get("by_id") or {}).get(provider_id) if provider_id else None
