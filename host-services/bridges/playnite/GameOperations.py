"""Provider-specific game operation dispatch and completion probes."""

from __future__ import annotations

import json
import os
import re
import stat
import subprocess
import threading
import time
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
                 automation_path: Path | None = None,
                 legendary_path: Path | None = None,
                 legendary_state_path: Path | None = None,
                 epic_install_root: Path | None = None,
                 legendary_enabled: bool = True,
                 command_runner: Callable[..., Any] | None = None,
                 process_runner: Callable[..., Any] | None = None) -> None:
        self.manifests = manifests
        self.automation_path = automation_path
        self.legendary_path = legendary_path
        self.legendary_state_path = legendary_state_path
        self.epic_install_root = epic_install_root or Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Epic Games"
        self.legendary_enabled = legendary_enabled
        self.command_runner = command_runner or subprocess.run
        self.process_runner = process_runner or subprocess.Popen
        self.processes: dict[str, dict[str, Any]] = {}

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
            install_location = value.get("InstallLocation")
            install_directory = str(install_location or "").strip()
            executable = str(value.get("LaunchExecutable") or "").strip()
            installed = not bool(value.get("bIsIncompleteInstall", False)) and \
                bool(install_directory) and Path(install_directory).is_dir() and \
                (not executable or (Path(install_directory) / executable).is_file())
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

    def _installed_legendary(self, app_name: str) -> tuple[bool, dict[str, Any] | None]:
        readable, payload = self._legendary_json(["list-installed", "--json", "--show-dirs"])
        if not readable: return False, None
        if isinstance(payload, list):
            items = payload
        elif isinstance(payload, dict) and isinstance(payload.get("installed"), list):
            items = payload["installed"]
        else:
            return False, None
        for item in items:
            if isinstance(item, dict) and self._app_name(item).casefold() == app_name.casefold():
                return True, {"installed": True, "provider": "epic", "install_directory": str(item.get("install_path") or item.get("install_dir") or item.get("install_location") or "")}
        return True, None

    def _sync_egl(self, mode: str = "--export-only") -> bool:
        executable, environment = self._legendary(), self._legendary_environment()
        try:
            if executable is None or environment is None:
                return False
            result = self.command_runner(
                [str(executable), "-y", "egl-sync", "--one-shot", mode],
                capture_output=True, text=True, shell=False, env=environment,
                cwd=str(executable.parent), timeout=30,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), check=False)
            return result.returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False

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
        if not item or not item.get("installed"):
            return None, ""
        return self._trusted_egl_manifest(game, app_name)

    def _import_egl_install(self, executable: Path, environment: dict[str, str],
                            app_name: str, install_directory: Path) -> bool:
        try:
            result = self.command_runner(
                [str(executable), "-y", "import", app_name, str(install_directory),
                 "--platform", "Windows", "--skip-dlcs"],
                capture_output=True, text=True, shell=False, env=environment,
                cwd=str(executable.parent), timeout=30,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), check=False)
            return result.returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False

    @staticmethod
    def _progress(line: str) -> int | None:
        match = re.search(r"(?:^|\s)(\d{1,3})(?:\.\d+)?%", line)
        return min(100, int(match.group(1))) if match else None

    def _start_legendary(self, game: dict[str, Any], operation: str) -> dict[str, Any] | None:
        self._dispatch_reason = ""
        executable, environment = self._legendary(), self._legendary_environment()
        if executable is None or environment is None:
            self._dispatch_reason = getattr(self, "_legendary_reason", "legendary_unavailable")
            return None
        app_name = self._resolve_legendary_app(game)
        if app_name is None:
            if not self._dispatch_reason: self._dispatch_reason = "legendary_unresolved"
            return None
        imported_manifest: dict[str, str] | None = None
        if operation == "uninstall":
            listed, installed = self._installed_legendary(app_name)
            if not listed:
                self._dispatch_reason = "legendary_installed_query_failed"
                return None
            if not installed:
                trusted, reason = self._trusted_egl_manifest(game, app_name)
                orphaned = self._egl_orphaned(trusted["install_directory"]) \
                    if trusted is not None else None
                if trusted is not None and orphaned is True:
                    reason = self._quarantine_egl_orphan(
                        game, app_name, trusted["manifest_path"],
                        trusted["install_directory"])
                    if reason:
                        self._dispatch_reason = reason
                        return None
                    return {"accepted": True, "command": "uninstall", "provider": "epic",
                            "dispatch": "manifest_cleanup", "app_name": app_name}
                if (trusted is None and reason) or (trusted is not None and orphaned is None):
                    self._dispatch_reason = reason or "epic_manifest_cleanup_unsafe"
                    return None
                synced = self._sync_egl("--import-only")
                if synced:
                    listed, installed = self._installed_legendary(app_name)
                    if not listed:
                        self._dispatch_reason = "legendary_post_sync_query_failed"
                        return None
                    if installed:
                        imported_manifest, reason = self._trusted_egl_manifest(game, app_name)
                        if imported_manifest is None:
                            self._dispatch_reason = reason or "epic_manifest_cleanup_unsafe"
                            return None
                if not installed:
                    trusted, reason = self._trusted_egl_install_directory(game, app_name)
                    if trusted is None:
                        if reason:
                            self._dispatch_reason = reason
                            return None
                        self._dispatch_reason = "legendary_egl_install_missing_or_unsafe"
                        return None
                    if not self._import_egl_install(executable, environment, app_name,
                                                    Path(trusted["install_directory"])):
                        self._dispatch_reason = "legendary_import_failed"
                        return None
                    imported_manifest = trusted
                    listed, installed = self._installed_legendary(app_name)
                    if not listed:
                        self._dispatch_reason = "legendary_post_import_query_failed"
                        return None
                    if not installed:
                        self._dispatch_reason = "legendary_post_import_still_absent"
                        return None
        arguments = [str(executable), "-y", operation, app_name]
        if operation == "install":
            arguments += ["--platform", "Windows", "--skip-dlcs", "--skip-sdl", "--base-path", str(self.epic_install_root)]
        try:
            process = self.process_runner(arguments, shell=False, env=environment, cwd=str(executable.parent),
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.SubprocessError):
            self._dispatch_reason = "legendary_start_failed"
            return None
        tracked = {"process": process, "app": app_name, "progress": None,
                   "error_excerpt": ""}
        if imported_manifest is not None:
            tracked.update({"imported_from_egl": True,
                            "manifest_path": imported_manifest["manifest_path"],
                            "install_directory": imported_manifest["install_directory"]})
        self.processes[str(game.get("id") or app_name)] = tracked
        if process.stdout is not None:
            def collect() -> None:
                for line in process.stdout:
                    text = self._error_excerpt(str(line))
                    if text:
                        tracked["error_excerpt"] = text
                    progress = self._progress(str(line))
                    if progress is not None: tracked["progress"] = progress
            threading.Thread(target=collect, name="LegendaryOutput", daemon=True).start()
        return {"accepted": True, "command": operation, "provider": "epic", "dispatch": "legendary", "app_name": app_name}

    @staticmethod
    def _error_excerpt(line: str) -> str:
        text = "".join(char for char in line if char >= " " and char != "\x7f").strip()
        if any(secret in text.casefold() for secret in (
                "token", "authorization", "refresh", "access_token")):
            return ""
        return text[:300]

    def _sample_legendary(self, game: dict[str, Any], operation: str) -> dict[str, Any] | None:
        key = str(game.get("id") or "")
        tracked = self.processes.get(key)
        if tracked is None: return None
        process = tracked["process"]
        if process.poll() is None:
            result = {"provider": "epic", "started": True, "phase": "active_uninstall" if operation == "uninstall" else "active_install", "requires_attention": False, "reason": "legendary_running"}
            if tracked["progress"] is not None: result["progress"] = tracked["progress"]
            return result
        self.processes.pop(key, None)
        exit_code = int(process.returncode)
        error_excerpt = str(tracked.get("error_excerpt") or "")
        if exit_code != 0:
            return {"provider": "epic", "requires_attention": True,
                    "reason": "legendary_process_failed", "exit_code": exit_code,
                    "error_excerpt": error_excerpt, "launcher": "legendary.exe"}
        listed, installed = self._installed_legendary(str(tracked["app"]))
        if operation == "install" and listed and installed:
            synced = self._sync_egl()
            if not synced:
                print(json.dumps({"event": "epic_egl_sync_failed", "app_name": str(tracked["app"]), "kind": operation}, separators=(",", ":")), flush=True)
            return {**installed, "progress": 100,
                    "reconciliation": "egl_sync_complete" if synced else "egl_sync_failed"}
        if operation == "uninstall" and listed and not installed:
            synced = self._sync_egl()
            if not synced:
                print(json.dumps({"event": "epic_egl_sync_failed", "app_name": str(tracked["app"]), "kind": operation}, separators=(",", ":")), flush=True)
            manifest_path = str(tracked.get("manifest_path") or "")
            if tracked.get("imported_from_egl") and manifest_path and Path(manifest_path).exists():
                reason = self._quarantine_egl_orphan(
                    game, str(tracked["app"]), manifest_path,
                    str(tracked.get("install_directory") or ""))
                if reason:
                    return {"provider": "epic", "requires_attention": True,
                            "reason": reason, "exit_code": exit_code,
                            "error_excerpt": error_excerpt, "launcher": "legendary.exe"}
            if self._egl_manifest_item(game) is not None:
                return {"provider": "epic", "requires_attention": True,
                        "reason": "epic_manifest_cleanup_unsafe", "exit_code": exit_code,
                        "error_excerpt": error_excerpt, "launcher": "legendary.exe"}
            return {"uninstalled": True, "provider": "epic",
                    "reconciliation": "egl_sync_complete" if synced else "egl_sync_failed"}
        return {"provider": "epic", "requires_attention": True,
                "reason": "legendary_verification_failed", "exit_code": exit_code,
                "error_excerpt": error_excerpt, "launcher": "legendary.exe"}

    def sample(self, game: dict[str, Any], operation: str) -> dict[str, Any] | None:
        direct = self._sample_legendary(game, operation)
        if direct is not None: return direct
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
                         send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        direct = self._start_legendary(game, "install")
        if direct is not None: return direct
        app_name = str(game.get("providerGameId") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]+", app_name):
            return {**send_command("install", id=str(game["id"])), "provider": "epic", "dispatch": "egl_auto", "fallback_reason": self._dispatch_reason or "legendary_unresolved"}
        uri = "com.epicgames.launcher://apps/" + urllib.parse.quote(
            app_name, safe="") + "?action=install"
        if os.name != "nt":
            raise OSError("Epic installation requires Windows.")
        os.startfile(uri)  # type: ignore[attr-defined]
        return {"accepted": True, "command": "install", "provider": "epic", "dispatch": "egl_auto", "fallback_reason": self._dispatch_reason or "legendary_unavailable"}

    def dispatch_uninstall(self, game: dict[str, Any], _send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        direct = self._start_legendary(game, "uninstall")
        if direct is not None: return direct
        return {"accepted": False, "command": "uninstall", "provider": "epic",
                "dispatch": "none", "requires_attention": True,
                "reason": self._dispatch_reason or "legendary_unavailable"}

    def confirm_operation(self, hwnd: int, operation: str, game_name: str,
                          allow_visual_fallback: bool = False, probe_only: bool = False) -> dict[str, Any]:
        return SteamProvider.confirm_operation(self, hwnd, operation, game_name, allow_visual_fallback, probe_only)

    def manual_attention(self, game: dict[str, Any], _operation: str) -> dict[str, Any] | None:
        return {"reason": "epic_manual", "launcher": "epicgameslauncher.exe"} if not game else None

    def can_auto_confirm(self, sample: dict[str, Any]) -> bool:
        return str(sample.get("image") or "").casefold() in self.expected_launcher_images()

    def expected_launcher_images(self) -> set[str]:
        return {"epicgameslauncher.exe"}


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
