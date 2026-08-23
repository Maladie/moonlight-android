"""Provider-specific game operation dispatch and completion probes."""

from __future__ import annotations

import json
import os
import re
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

    def __init__(self, automation_path: Path | None = None,
                 roots: list[Path] | None = None) -> None:
        self.automation_path = automation_path
        self.roots = roots

    @staticmethod
    def _values(path: Path) -> dict[str, str]:
        values: dict[str, str] = {}
        try:
            for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
                match = SteamProvider.KEY_VALUE.match(line)
                if match:
                    values[match.group(1).casefold()] = match.group(2)
        except OSError:
            pass
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

    def libraries(self) -> list[Path]:
        if self.roots is not None:
            return self.roots
        root = self._steam_root()
        if root is None:
            return []
        result = [root]
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
            pass
        return result

    def sample(self, game: dict[str, Any], operation: str) -> dict[str, Any] | None:
        app_id = str(game.get("providerGameId") or "").strip()
        if not app_id.isdigit():
            return None
        for library in self.libraries():
            manifest = library / "steamapps" / f"appmanifest_{app_id}.acf"
            if not manifest.is_file():
                continue
            if operation == "uninstall":
                return None
            values = self._values(manifest)
            try:
                downloaded = max(0, int(values.get("bytesdownloaded", "0")))
                total = max(0, int(values.get("bytestodownload", "0")))
                progress = min(100, downloaded * 100 // total) if total else None
                complete = int(values.get("stateflags", "0")) == 4
            except ValueError:
                progress, complete = None, False
            install_dir = library / "steamapps" / "common" / values.get("installdir", "")
            if operation == "install" and complete and install_dir.is_dir():
                return {"installed": True, "provider": "steam",
                        "install_directory": str(install_dir), "progress": 100}
            if operation == "install" and downloaded == 0:
                return None
            return {"requires_attention": False, "reason": "steam_downloading",
                    "provider": "steam", "progress": progress}
        if operation == "uninstall":
            return {"uninstalled": True, "provider": "steam"}
        return None

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
        return self.provider_for(game).sample(
            game, str(baseline.get("operation") or "install"))

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
