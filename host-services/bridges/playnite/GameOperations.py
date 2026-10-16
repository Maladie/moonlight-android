"""Provider-specific game operation dispatch and completion probes."""

from __future__ import annotations

import hashlib
import html
import json
import os
import re
import stat
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Hashable

from OperationJournal import OperationJournal


MAX_PROVIDER_ARTWORK_BYTES = 8 * 1024 * 1024


def _plain_description(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    text = re.sub(r"<(script|style)\b[^>]*>.*?</\1>", " ", text,
                  flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r" *\n *", "\n", text).strip()
    return re.sub(r"\n{3,}", "\n\n", text)[:2000]


class ProviderArtworkCache:
    """On-demand cache for artwork referenced by trusted provider metadata."""

    def __init__(self, root: Path, web_opener: Callable[..., Any]) -> None:
        self.root = root
        self.web_opener = web_opener

    @staticmethod
    def _allowed(provider: str, url: str) -> bool:
        parsed = urllib.parse.urlparse(url)
        try:
            port = parsed.port
        except ValueError:
            return False
        if parsed.scheme != "https" or parsed.username or parsed.password \
                or port not in {None, 443}:
            return False
        host = str(parsed.hostname or "").casefold()
        if provider == "steam":
            return host == "shared.akamai.steamstatic.com"
        return provider == "epic" and (
            host.endswith(".epicgames.com")
            or host.endswith(".unrealengine.com")
        )

    @staticmethod
    def _image_extension(body: bytes, content_type: str) -> str:
        if body.startswith(b"\xff\xd8"):
            return ".jpg"
        if body.startswith(b"\x89PNG\r\n\x1a\n"):
            return ".png"
        if body.startswith(b"RIFF") \
                and body[8:12] == b"WEBP":
            return ".webp"
        raise ValueError("Provider artwork response is not a supported image.")

    def fetch(self, provider: str, game_id: str, kind: str, url: str) -> Path:
        id_pattern = r"[0-9]+" if provider == "steam" else r"[A-Za-z0-9_-]+"
        if not re.fullmatch(id_pattern, game_id) \
                or kind not in {"cover", "background", "hero", "icon"} \
                or not self._allowed(provider, url):
            raise ValueError("Invalid provider artwork reference.")
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:20]
        directory = self.root / provider / game_id
        for extension in (".jpg", ".png", ".webp"):
            cached = directory / f"{kind}-{digest}{extension}"
            try:
                if 0 < cached.stat().st_size <= MAX_PROVIDER_ARTWORK_BYTES:
                    return cached
            except OSError:
                pass
        request = urllib.request.Request(
            url, headers={"Accept": "image/webp,image/png,image/jpeg",
                          "User-Agent": "MoonWakerHost/1"})
        with self.web_opener(request, timeout=8) as response:
            body = response.read(MAX_PROVIDER_ARTWORK_BYTES + 1)
            headers = getattr(response, "headers", None)
            content_type = str(headers.get("Content-Type") or "") if headers else ""
        if not body or len(body) > MAX_PROVIDER_ARTWORK_BYTES:
            raise ValueError("Provider artwork response has an unsupported size.")
        extension = self._image_extension(body, content_type)
        target = directory / f"{kind}-{digest}{extension}"
        temporary = directory / f"{target.name}.{threading.get_ident()}.tmp"
        directory.mkdir(parents=True, exist_ok=True)
        try:
            temporary.write_bytes(body)
            os.replace(temporary, target)
        finally:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
        # ponytail: old revisions are tiny and rare; prune only if real caches
        # prove large enough to justify synchronization around concurrent reads.
        return target


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
    def launch(self, game: dict[str, Any], task_id: Hashable | None = None,
               send_command: Callable[..., dict[str, Any]] | None = None,
               launch_allowed: Callable[[], bool] | None = None) -> dict[str, Any]:
        if send_command is None:
            return {"accepted": False, "command": "launch", "provider": "playnite",
                    "reason": "playnite_connector_unavailable"}
        if launch_allowed is not None and not launch_allowed():
            return {"accepted": False, "command": "launch", "reason": "launch_cancelled"}
        return {**send_command("launch", id=str(game.get("playniteGameId")
                                                      or game.get("providerGameId")
                                                      or game.get("id") or "")),
                "provider": "playnite", "dispatch": "playnite"}

    def dispatch_install(self, game: dict[str, Any],
                         send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return send_command("install", id=str(game.get("playniteGameId")
                                               or game.get("providerGameId")
                                               or game.get("id") or ""))

    def dispatch_uninstall(self, game: dict[str, Any],
                           send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return send_command("uninstall", id=str(game.get("playniteGameId")
                                                 or game.get("providerGameId")
                                                 or game.get("id") or ""))

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
    LAUNCH_LOG_GRACE = 2.0
    VERIFY_STALL_SECONDS = 120.0
    OPERATIONS = {
        "install": "+app_install",
        "uninstall": "+app_uninstall",
    }
    # appmanifest StateFlags (SteamKit EAppState): UpdateRequired, UpdateRunning,
    # UpdatePaused, UpdateStarted. AppRunning (64) is not an update.
    UPDATE_FLAGS = 2 | 256 | 512 | 1024
    # An update whose manifest does not move for this long (Steam closed or stuck on
    # 100%) gets its command sent again; it never ends the update.
    UPDATE_NUDGE_SECONDS = 120.0
    # content_log.txt state text that proves Steam gave up on the update.
    UPDATE_STATE = re.compile(r'AppID (\d+) state changed : (.*)$')
    # "Failed installing AppID 550 (No subscription)": the account has no licence for the app
    # (files left by a Family Sharing lender), so Steam can never update it for this account.
    UPDATE_FAILED = re.compile(r'Failed installing AppID (\d+) \(([^)]*)\)')
    LOG_TIMESTAMP = re.compile(r'^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\]')
    UPDATE_ERROR = re.compile(
        r'Disk write failure|Not enough disk space|Disk read failure', re.IGNORECASE)

    def __init__(self, automation_path: Path | None = None,
                 roots: list[Path] | None = None,
                 root_resolver: Callable[[], Path | None] | None = None,
                 command_runner: Callable[..., Any] | None = None,
                 api_key_path: Path | None = None,
                 secret_reader: Callable[[Path], str] | None = None,
                 web_opener: Callable[..., Any] | None = None,
                 artwork_cache_path: Path | None = None,
                 process_registry: OperationProcessRegistry | None = None,
                 launch_preflight: Callable[["SteamProvider"], dict[str, Any]] | None = None) -> None:
        self.automation_path = automation_path
        self.roots = roots
        self.root_resolver = root_resolver
        self.command_runner = command_runner or subprocess.Popen
        self.api_key_path = api_key_path
        self.secret_reader = secret_reader or self._read_dpapi_secret
        self.web_opener = web_opener or urllib.request.urlopen
        self.artwork_cache = ProviderArtworkCache(
            artwork_cache_path, self.web_opener) if artwork_cache_path is not None else None
        self.process_registry = process_registry or OperationProcessRegistry()
        self.launch_preflight = launch_preflight
        self._dispatch_reason = ""
        self._verify_seen: dict[str, tuple[Any, float]] = {}

    def _verify_stalled(self, app_id: str, signature: Any, verifying: bool) -> bool:
        """True when a finished download sits unchanged in `verifying` (Steam was closed
        mid-update), so the operation asks for attention instead of showing 100% forever."""
        if not verifying:
            self._verify_seen.pop(app_id, None)
            return False
        now = time.monotonic()
        seen = self._verify_seen.get(app_id)
        if seen is None or seen[0] != signature:
            self._verify_seen[app_id] = (signature, now)
            return False
        return now - seen[1] >= self.VERIFY_STALL_SECONDS

    def _json_request(self, request: urllib.request.Request) -> Any:
        with self.web_opener(request, timeout=8) as response:
            body = response.read(8 * 1024 * 1024 + 1)
        if len(body) > 8 * 1024 * 1024:
            raise ValueError("response_too_large")
        return json.loads(body.decode("utf-8"))

    @staticmethod
    def _steam_asset_url(assets: dict[str, Any], *names: str) -> str:
        pattern = str(assets.get("asset_url_format") or "")
        filename = next((str(assets.get(name) or "").strip()
                         for name in names if assets.get(name)), "")
        if not pattern.startswith("steam/apps/") or pattern.count("${FILENAME}") != 1 \
                or not filename or ".." in filename or "\\" in filename \
                or "://" in filename:
            return ""
        return "https://shared.akamai.steamstatic.com/store_item_assets/" + \
            pattern.replace("${FILENAME}", filename)

    @staticmethod
    def _steam_page_background_url(assets: dict[str, Any], app_id: str) -> str:
        path = str(assets.get("page_background_path") or "").strip()
        match = re.fullmatch(r"app/([0-9]+)(\?t=[0-9]+)?", path)
        if match is None or match.group(1) != app_id:
            return ""
        return "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + \
            f"{app_id}/page_bg_raw.jpg{match.group(2) or ''}"

    @classmethod
    def _steam_background_url(cls, assets: dict[str, Any], app_id: str) -> str:
        return cls._steam_asset_url(assets, "main_capsule_2x") or \
            cls._steam_page_background_url(assets, app_id) or \
            cls._steam_asset_url(assets, "main_capsule", "header_2x", "header",
                                 "hero_capsule_2x", "hero_capsule")

    def _enrich_store_metadata(self, games: list[dict[str, Any]]) -> None:
        if self.artwork_cache is None:
            return
        by_id = {str(game.get("providerGameId") or ""): game for game in games}
        app_ids = [app_id for app_id in by_id if re.fullmatch(r"[0-9]+", app_id)]
        for offset in range(0, len(app_ids), 50):
            request_payload = {
                "ids": [{"appid": int(app_id)} for app_id in app_ids[offset:offset + 50]],
                "context": {"language": "english", "country_code": "US"},
                "data_request": {"include_assets": True, "include_basic_info": True},
            }
            query = urllib.parse.urlencode({
                "input_json": json.dumps(request_payload, separators=(",", ":"))})
            request = urllib.request.Request(
                "https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?" + query,
                headers={"Accept": "application/json", "User-Agent": "MoonWakerHost/1"})
            try:
                payload = self._json_request(request)
                items = payload.get("response", {}).get("store_items")
                if not isinstance(items, list):
                    continue
            except Exception:
                continue
            for item in items:
                if not isinstance(item, dict) or not item.get("success"):
                    continue
                game = by_id.get(str(item.get("appid") or ""))
                if game is None:
                    continue
                assets = item.get("assets") if isinstance(item.get("assets"), dict) else {}
                basic = item.get("basic_info") \
                    if isinstance(item.get("basic_info"), dict) else {}
                cover = self._steam_asset_url(
                    assets, "library_capsule_2x", "library_capsule", "main_capsule",
                    "hero_capsule", "header")
                app_id = str(item.get("appid") or "")
                background = self._steam_background_url(assets, app_id)
                hero = self._steam_asset_url(
                    assets, "library_hero_2x", "library_hero", "hero_capsule_2x",
                    "hero_capsule", "page_background")
                description = _plain_description(basic.get("short_description"))
                if description:
                    game["description"] = description
                if cover:
                    game["cover"] = cover
                if background:
                    game["background"] = background
                if hero:
                    game["hero"] = hero
                if cover or background or hero or description:
                    game["metadataProvider"] = "steam"
                    game["artworkVersion"] = hashlib.sha256(
                        f"{cover}\0{background}\0{hero}".encode("utf-8")).hexdigest()

    def artwork(self, game: dict[str, Any], kind: str) -> Path:
        if self.artwork_cache is None:
            raise FileNotFoundError("Steam artwork cache is unavailable.")
        url = str(game.get(kind) or "")
        if kind == "background" and not url:
            url = str(game.get("cover") or "")
        elif kind == "hero" and not url:
            url = str(game.get("background") or "")
        game_id = str(game.get("providerGameId") or "")
        try:
            return self.artwork_cache.fetch("steam", game_id, kind, url)
        except urllib.error.HTTPError as error:
            fallback = str(game.get("hero") or "")
            if kind != "background" or error.code != 404 or not fallback:
                raise
            error.close()
            return self.artwork_cache.fetch("steam", game_id, kind, fallback)

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
        except ImportError:
            return None
        locations = (
            (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam", "InstallPath"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam",
             "InstallPath"),
        )
        for hive, key_path, value_name in locations:
            try:
                with winreg.OpenKey(hive, key_path) as key:
                    value, _kind = winreg.QueryValueEx(key, value_name)
                if str(value).strip():
                    return Path(str(value))
            except OSError:
                continue
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

    @staticmethod
    def _read_dpapi_secret(path: Path) -> str:
        if os.name != "nt" or not path.is_file():
            return ""
        script = (
            "$e=(Get-Content -LiteralPath $env:MOONWAKER_STEAM_SECRET_PATH -Raw).Trim() | "
            "ConvertTo-SecureString;"
            "$c=[pscredential]::new('steam',$e);"
            "$c.GetNetworkCredential().Password")
        try:
            environment = os.environ.copy()
            environment["MOONWAKER_STEAM_SECRET_PATH"] = str(path)
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                 script], capture_output=True, text=True, timeout=5, env=environment,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), check=False)
            return str(result.stdout or "").strip() if result.returncode == 0 else ""
        except (OSError, subprocess.SubprocessError):
            return ""

    def steam_id(self) -> str:
        root = self._root()
        if root is None:
            return ""
        try:
            text = (root / "config" / "loginusers.vdf").read_text(
                encoding="utf-8-sig", errors="replace")
        except OSError:
            return ""
        accounts: list[tuple[bool, str]] = []
        for match in re.finditer(r'"(7656[0-9]{13})"\s*\{(.*?)\n\s*\}', text, re.DOTALL):
            values = {}
            for line in match.group(2).splitlines():
                item = self.KEY_VALUE.match(line)
                if item:
                    values[item.group(1).casefold()] = item.group(2)
            accounts.append((values.get("mostrecent") == "1", match.group(1)))
        return next((account for recent, account in accounts if recent),
                    accounts[0][1] if accounts else "")

    def _installed_catalog(self) -> dict[str, Any]:
        discovery = self._library_discovery()
        result: dict[str, dict[str, Any]] = {}
        complete = bool(discovery["complete"])
        for library in discovery["libraries"]:
            steamapps = library / "steamapps"
            if self._path_state(steamapps)[0] != "directory":
                complete = False
                continue
            try:
                manifests = list(steamapps.glob("appmanifest_*.acf"))
            except OSError:
                complete = False
                continue
            for manifest in manifests:
                match = re.fullmatch(r"appmanifest_([0-9]+)\.acf", manifest.name,
                                     re.IGNORECASE)
                values = self._values(manifest) if match else None
                app_id = match.group(1) if match else ""
                if not values or values.get("appid") != app_id:
                    complete = False
                    continue
                install_name = str(values.get("installdir") or "").strip()
                directory = steamapps / "common" / install_name if install_name else None
                # FullyInstalled (4) stays set while an update is pending or running.
                ready = bool(self._number(values, "stateflags") & 4) and directory is not None \
                    and self._path_state(directory)[0] == "directory"
                result[app_id] = {
                    "name": str(values.get("name") or f"Steam App {app_id}"),
                    "installed": ready,
                    "install_directory": str(directory or "") if ready else "",
                }
        return {"available": bool(discovery["available"]),
                "complete": complete, "by_id": result}

    def catalog(self) -> dict[str, Any]:
        steam_id = self.steam_id()
        installed_snapshot = self._installed_catalog()
        installed = installed_snapshot["by_id"]
        local_games = [{
            "id": f"steam:{app_id}", "provider": "steam",
            "providerGameId": app_id, "libraryKey": "steam",
            "libraryName": "Steam", "source": "Steam", "playniteGameId": "",
            "capabilities": {"launch": True, "install": True, "uninstall": True},
            "providerCapabilities": {
                "requiresConnector": False, "streamMode": "neutral",
                "startBeforeStream": True},
            "name": str(local.get("name") or f"Steam App {app_id}"),
            "installed": bool(local.get("installed")),
            "installDir": str(local.get("install_directory") or ""),
        } for app_id, local in installed.items()]
        try:
            api_key = self.secret_reader(self.api_key_path) if self.api_key_path else ""
        except Exception:
            api_key = ""
        if not re.fullmatch(r"[A-Fa-f0-9]{32}", api_key):
            self._enrich_store_metadata(local_games)
            return {"available": bool(installed_snapshot["available"]),
                    "complete": False, "games": local_games,
                    "installationComplete": bool(installed_snapshot["complete"]),
                    "reason": "steam_api_key_missing"}
        if not re.fullmatch(r"7656[0-9]{13}", steam_id):
            self._enrich_store_metadata(local_games)
            return {"available": bool(installed_snapshot["available"]),
                    "complete": False, "games": local_games,
                    "installationComplete": bool(installed_snapshot["complete"]),
                    "reason": "steam_id_unavailable"}
        query = urllib.parse.urlencode({
            "key": api_key, "steamid": steam_id, "include_appinfo": "true",
            "include_played_free_games": "true", "format": "json",
        })
        request = urllib.request.Request(
            "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?" + query,
            headers={"Accept": "application/json", "User-Agent": "MoonWakerHost/1"})
        try:
            payload = self._json_request(request)
            owned = payload.get("response", {}).get("games")
            if not isinstance(owned, list):
                raise ValueError("invalid_response")
        except Exception:
            self._enrich_store_metadata(local_games)
            return {"available": bool(installed_snapshot["available"]),
                    "complete": False, "games": local_games,
                    "installationComplete": bool(installed_snapshot["complete"]),
                    "reason": "steam_catalog_unavailable"}
        games: list[dict[str, Any]] = []
        for item in owned[:100000]:
            if not isinstance(item, dict):
                continue
            app_id = str(item.get("appid") or "").strip()
            name = str(item.get("name") or "").strip()
            if not re.fullmatch(r"[0-9]+", app_id) or not name:
                continue
            local = installed.get(app_id) or {}
            games.append({
                "id": f"steam:{app_id}", "provider": "steam",
                "providerGameId": app_id, "libraryKey": "steam",
                "libraryName": "Steam", "source": "Steam", "playniteGameId": "",
                "capabilities": {"launch": True, "install": True, "uninstall": True},
                "providerCapabilities": {
                    "requiresConnector": False, "streamMode": "neutral",
                    "startBeforeStream": True},
                "name": name, "installed": bool(local.get("installed")),
                "installDir": str(local.get("install_directory") or ""),
                "playtimeMinutes": max(0, int(item.get("playtime_forever") or 0)),
                "lastPlayed": (datetime.fromtimestamp(int(item["rtime_last_played"]),
                                timezone.utc).isoformat()
                               if item.get("rtime_last_played") else ""),
            })
        self._enrich_store_metadata(games)
        complete = bool(installed_snapshot["available"] and
                        installed_snapshot["complete"])
        return {"available": True, "complete": complete, "games": games,
                "installationComplete": bool(installed_snapshot["complete"]),
                "reason": "" if complete else "steam_manifest_scan_incomplete"}

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

    def dispatch_install(self, game: dict[str, Any],
                         send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return self._direct_dispatch(game, "install") or {
            "accepted": False, "command": "install", "provider": "steam",
            "dispatch": "none", "reason": "steam_direct_dispatch_failed"}

    def dispatch_uninstall(self, game: dict[str, Any],
                           send_command: Callable[..., dict[str, Any]]) -> dict[str, Any]:
        return self._direct_dispatch(game, "uninstall") or {
            "accepted": False, "command": "uninstall", "provider": "steam",
            "dispatch": "none", "reason": "steam_direct_dispatch_failed"}

    def launch(self, game: dict[str, Any], task_id: Hashable | None = None,
               send_command: Callable[..., dict[str, Any]] | None = None) -> dict[str, Any]:
        app_id = str(game.get("providerGameId") or "").strip()
        if not re.fullmatch(r"[0-9]+", app_id):
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "reason": "invalid_steam_app_id"}
        executable = self.executable()
        snapshot = self.scan(game)
        if executable is None:
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "reason": "steam_unavailable"}
        if not snapshot.get("installed"):
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "reason": "steam_game_not_installed"}
        arguments = [str(executable), f"steam://launch/{app_id}/Dialog"]
        game_id = str(game.get("id") or f"steam:{app_id}")
        task_id = task_id if task_id is not None else (game_id, "launch", time.time_ns())
        if self.process_registry.busy(game_id):
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "reason": "operation_busy"}
        preflight = self.launch_preflight(self) if self.launch_preflight else {
            "ready": False, "reason": "steam_launch_preflight_unavailable"}
        if not preflight.get("ready") \
                and preflight.get("reason") != "host_session_locked":
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "requires_attention": preflight.get("reason") in {
                        "launcher_interaction_required", "host_session_locked"},
                    "reason": str(preflight.get("reason") or
                                  "steam_launch_preflight_unavailable")}
        try:
            process = self.command_runner(
                arguments, cwd=str(executable.parent), shell=False,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.SubprocessError):
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "reason": "steam_launch_failed"}
        tracked = TrackedOperationProcess(
            task_id=task_id, game_id=game_id, provider="steam",
            operation_type="launch", process_type="launch", phase="dispatch",
            process=process, app_name=app_id, executable=executable)
        if not self.process_registry.register(tracked):
            try:
                process.terminate()
            except (AttributeError, OSError, subprocess.SubprocessError):
                pass
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "reason": "operation_busy"}
        return {"accepted": True, "command": "launch", "provider": "steam",
                "dispatch": "direct", "task_id": task_id,
                "install_directory": str(snapshot.get("install_directory") or "")}

    def sample_launch(self, game: dict[str, Any], task_id: Hashable) -> dict[str, Any] | None:
        tracked = self.process_registry.get(task_id)
        if tracked is None or tracked.game_id != str(game.get("id") or "") \
                or tracked.provider != "steam" or tracked.operation_type != "launch":
            return None
        if tracked.process.poll() is None:
            return {"provider": "steam", "started": True,
                    "reason": "steam_launch_dispatch_running"}
        exit_code = int(tracked.process.returncode)
        if exit_code != 0:
            self.process_registry.remove(tracked)
            return {"provider": "steam", "requires_attention": True,
                    "reason": "steam_launch_failed", "exit_code": exit_code,
                    "launcher": "steam.exe"}
        if time.time() - tracked.started_at < self.LAUNCH_LOG_GRACE:
            tracked.phase = "client_ack"
            return {"provider": "steam", "dispatched": True,
                    "reason": "steam_launch_dispatched", "exit_code": 0}
        self.process_registry.remove(tracked)
        return {"provider": "steam", "dispatched": True,
                "reason": "steam_launch_dispatched", "exit_code": 0}

    # Steam's own launch state machine, e.g. `[2026-10-08 22:51:17] GameAction [AppID 410110,
    # ActionID 6] : LaunchApp waiting for user response to ShowInterstitials ""`.
    LAUNCH_ACTION = re.compile(
        r'^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\] GameAction \[AppID (\d+), ActionID \d+\] : '
        r'LaunchApp (waiting for user response to|continues with user response|changed task to)'
        r' "?([A-Za-z]*)')
    LAUNCH_PROMPT_GRACE = 3.0
    LAUNCH_LOG_TAIL_BYTES = 256 * 1024

    def _launch_log_lines(self) -> list[str]:
        """Tail of Steam's console log, re-read only when the file changed."""
        root = self._root()
        if root is None:
            return []
        path = root / "logs" / "console_log.txt"
        try:
            details = path.stat()
            key = (str(path), details.st_size, details.st_mtime_ns)
            cached = getattr(self, "_launch_log_cache", None)
            if cached is not None and cached[0] == key:
                return cached[1]
            with path.open("rb") as log:
                log.seek(max(0, details.st_size - self.LAUNCH_LOG_TAIL_BYTES))
                lines = log.read().decode("utf-8", errors="replace").splitlines()
        except OSError:
            return []
        self._launch_log_cache = (key, lines)
        return lines

    def pending_launch_prompt(self, game: dict[str, Any],
                              now: float | None = None) -> str:
        """The Steam launch task of this game waiting for a user answer, or "".

        Read from Steam's console log (local time). The last launch action of the app
        decides, even one older than this request: while an earlier action still waits
        (an unanswered interstitial), Steam ignores new launches of the app and logs nothing
        for them. Steam answers its own short prompts (launch option, process creation)
        within a second, so a wait counts only after a short grace."""
        app_id = str(game.get("providerGameId") or "").strip()
        if not app_id:
            return ""
        pending, waiting_since = "", 0.0
        for line in self._launch_log_lines():
            match = self.LAUNCH_ACTION.match(line)
            if match is None or match.group(2) != app_id:
                continue
            try:
                logged = time.mktime(time.strptime(match.group(1), "%Y-%m-%d %H:%M:%S"))
            except (ValueError, OverflowError):
                continue
            waiting = match.group(3).startswith("waiting")
            pending = match.group(4) if waiting else ""
            waiting_since = logged if waiting else 0.0
        current = time.time() if now is None else now
        return pending if pending and current - waiting_since >= self.LAUNCH_PROMPT_GRACE \
            else ""

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
            "bytes_staged": 0, "bytes_to_stage": 0,
            "install_directory": "",
            "installed": False, "update_pending": False,
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
                    staged = self._number(values, "bytesstaged")
                    to_stage = self._number(values, "bytestostage")
                    install_name = str(values.get("installdir") or "").strip()
                    install_directory = steamapps / "common" / install_name \
                        if install_name else None
                    directory_ready = install_directory is not None and \
                        self._path_state(install_directory)[0] == "directory"
                    signature = (
                        str(manifest), int(manifest_stat.st_mtime_ns),
                        int(manifest_stat.st_size), downloaded, total, flags,
                        install_name, staged, to_stage,
                    )
                    result.update({
                        "manifest_readable": True,
                        "manifest_signature": signature,
                        "bytes_downloaded": downloaded,
                        "bytes_total": total,
                        "bytes_staged": staged,
                        "bytes_to_stage": to_stage,
                        "state_flags": flags,
                        "install_directory": str(install_directory or ""),
                        "installed": bool(flags & 4) and directory_ready,
                        "update_pending": bool(flags & 4) and directory_ready
                        and bool(flags & self.UPDATE_FLAGS),
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

    def dispatch_update(self, game: dict[str, Any]) -> dict[str, Any]:
        """Queue the update of an installed game through Steam's own install command."""
        return self._direct_dispatch(game, "install") or {
            "accepted": False, "command": "update", "provider": "steam",
            "dispatch": "none", "reason": "steam_direct_dispatch_failed"}

    def _content_log_lines(self) -> list[str]:
        """The tail of Steam's content_log.txt (local time), cached by size and mtime."""
        root = self._root()
        if root is None:
            return []
        path = root / "logs" / "content_log.txt"
        try:
            details = path.stat()
            key = (str(path), details.st_size, details.st_mtime_ns)
            cached = getattr(self, "_content_log_cache", None)
            if cached is not None and cached[0] == key:
                return cached[1]
            with path.open("rb") as log:
                log.seek(max(0, details.st_size - self.LAUNCH_LOG_TAIL_BYTES))
                lines = log.read().decode("utf-8", errors="replace").splitlines()
        except OSError:
            return []
        self._content_log_cache = (key, lines)
        return lines

    def _no_subscription_since(self, app_id: str, since: float) -> bool:
        """Whether Steam refused to install the app for lack of a licence at or after
        `since` (epoch seconds); an older refusal must not fail a new install."""
        for line in self._content_log_lines():
            failed = self.UPDATE_FAILED.search(line)
            stamp = self.LOG_TIMESTAMP.match(line)
            if failed is None or stamp is None or failed.group(1) != app_id \
                    or failed.group(2).casefold() != "no subscription":
                continue
            try:
                logged = time.mktime(time.strptime(stamp.group(1), "%Y-%m-%d %H:%M:%S"))
            except (ValueError, OverflowError):
                continue
            # The log has whole seconds: a refusal in the request's own second counts.
            if logged >= int(since):
                return True
        return False

    def _update_error(self, app_id: str) -> str:
        """The error Steam last logged for the app's state, or "" when its last state is fine."""
        # The app's last event decides: a later state line clears an earlier failure.
        last = ""
        for line in self._content_log_lines():
            match = self.UPDATE_STATE.search(line)
            if match is not None and match.group(1) == app_id:
                last = match.group(2)
                continue
            failed = self.UPDATE_FAILED.search(line)
            if failed is not None and failed.group(1) == app_id:
                last = "Failed " + failed.group(2)
        if last.casefold() == "failed no subscription":
            return "no_subscription"
        found = self.UPDATE_ERROR.search(last)
        return found.group(0).casefold().replace(" ", "_") if found else ""

    @staticmethod
    def update_phase(snapshot: dict[str, Any]) -> tuple[str, int]:
        """("downloading" | "patching", progress 0-100 or -1) of a pending update.

        Steam downloads first; once the download is done it patches (stages and commits) the
        files, which has its own byte counts. Committing has none: indeterminate (-1)."""
        total = int(snapshot.get("bytes_total") or 0)
        downloaded = int(snapshot.get("bytes_downloaded") or 0)
        staged = int(snapshot.get("bytes_staged") or 0)
        to_stage = int(snapshot.get("bytes_to_stage") or 0)
        if (total <= 0 or downloaded >= total) and to_stage > 0:
            return "patching", min(100, staged * 100 // to_stage) if staged < to_stage else -1
        return "downloading", min(100, downloaded * 100 // total) if total > 0 else -1

    def update_states(self) -> dict[str, dict[str, Any]]:
        """Pending updates of every installed game by app id (library tiles); cached briefly."""
        now = time.monotonic()
        cached = getattr(self, "_update_states_cache", None)
        if cached is not None and now - cached[0] < 3.0:
            return cached[1]
        states: dict[str, dict[str, Any]] = {}
        discovery = self._library_discovery()
        for library in discovery["libraries"]:
            try:
                manifests = list((library / "steamapps").glob("appmanifest_*.acf"))
            except OSError:
                continue
            for manifest in manifests:
                match = re.fullmatch(r"appmanifest_([0-9]+)\.acf", manifest.name, re.IGNORECASE)
                values = self._values(manifest) if match else None
                if not values:
                    continue
                flags = self._number(values, "stateflags")
                if not (flags & 4 and flags & self.UPDATE_FLAGS) \
                        or self._update_error(match.group(1)) == "no_subscription":
                    continue
                snapshot = {
                    "bytes_total": self._number(values, "bytestodownload"),
                    "bytes_downloaded": self._number(values, "bytesdownloaded"),
                    "bytes_staged": self._number(values, "bytesstaged"),
                    "bytes_to_stage": self._number(values, "bytestostage")}
                phase, progress = self.update_phase(snapshot)
                running = bool(flags & (256 | 1024))
                states[match.group(1)] = {
                    "state": phase if running else "required", "progress": progress}
        self._update_states_cache = (now, states)
        return states

    def update_sample(self, game: dict[str, Any]) -> dict[str, Any]:
        """The state of an update a start waits on. `progress` is 0-100, -1 without bytes;
        `error` is set only on log evidence; `nudge` asks the caller to send the command
        again because the manifest stood still. Nothing here ever times out."""
        snapshot = self.scan(game)
        app_id = str(snapshot.get("app_id") or "")
        pending = bool(snapshot.get("update_pending"))
        phase, progress = self.update_phase(snapshot) if pending else ("downloading", -1)
        error = self._update_error(app_id) if pending else ""
        if error == "no_subscription":
            # Steam cannot update an app this account has no licence for (a Family Sharing
            # lender's files): there is no update to wait for, the game launches as it is.
            pending, error = False, ""
        result = {
            "pending": pending, "installed": bool(snapshot.get("installed")),
            "phase": phase, "progress": progress,
            "error": "steam_update_" + error if error else "", "nudge": False,
        }
        if not pending:
            self._verify_seen.pop(app_id, None)
            return result
        now = time.monotonic()
        seen = self._verify_seen.get(app_id)
        signature = snapshot.get("manifest_signature")
        if seen is None or seen[0] != signature:
            self._verify_seen[app_id] = (signature, now)
        elif now - seen[1] >= self.UPDATE_NUDGE_SECONDS:
            self._verify_seen[app_id] = (signature, now)
            result["nudge"] = True
        return result

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
        # FullyInstalled (bit 4) outlives an update: a game that is installed is an install
        # that is done, even when Steam can never finish its pending update (no licence).
        if operation == "install" and snapshot.get("installed"):
            return {
                **common, "installed": True, "started": True,
                "phase": "completed_install", "progress": 100,
                "install_directory": str(snapshot["install_directory"]),
            }
        requested_at = float((baseline or {}).get("requested_at") or 0)
        if operation == "install" and requested_at > 0 and self._no_subscription_since(
                str(snapshot.get("app_id") or ""), requested_at):
            return {
                **common, "failed": True, "started": False, "phase": "failed",
                "requires_attention": False, "reason": "steam_no_subscription",
                "launcher": "steam.exe",
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
            patch_phase, patch_progress = self.update_phase(snapshot)
            patching = patch_phase == "patching"
            verifying = total > 0 and downloaded >= total and not patching
            if patching:
                progress = patch_progress if patch_progress >= 0 else None
            return {
                **common, "started": True,
                "phase": "patching" if patching else "verifying" if verifying
                else "active_install",
                "requires_attention": False,
                "reason": "steam_patching" if patching else "steam_verifying" if verifying
                else "steam_downloading",
                "progress": progress,
                "stalled": self._verify_stalled(
                    str(snapshot.get("app_id")), snapshot.get("manifest_signature"),
                    verifying and operation == "install"),
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
                 artwork_cache_path: Path | None = None,
                 web_opener: Callable[..., Any] | None = None,
                 process_registry: OperationProcessRegistry | None = None) -> None:
        self.manifests = manifests
        self.legendary_path = legendary_path
        self.legendary_state_path = legendary_state_path
        self.epic_install_root = epic_install_root
        self.legendary_enabled = legendary_enabled
        self.command_runner = command_runner or subprocess.run
        self.process_runner = process_runner or subprocess.Popen
        self.artwork_cache = ProviderArtworkCache(
            artwork_cache_path, web_opener or urllib.request.urlopen) \
            if artwork_cache_path is not None else None
        self.process_registry = process_registry or OperationProcessRegistry()
        self._update_errors: dict[str, str] = {}

    @staticmethod
    def _epic_image(metadata: dict[str, Any], *types: str) -> str:
        images = metadata.get("keyImages")
        if not isinstance(images, list):
            return ""
        by_type = {
            str(image.get("type") or ""): str(image.get("url") or "").strip()
            for image in images if isinstance(image, dict) and image.get("url")}
        return next((by_type[kind] for kind in types if by_type.get(kind)), "")

    def artwork(self, game: dict[str, Any], kind: str) -> Path:
        if self.artwork_cache is None:
            raise FileNotFoundError("Epic artwork cache is unavailable.")
        url = str(game.get(kind) or "")
        if kind == "background" and not url:
            url = str(game.get("cover") or "")
        elif kind == "hero" and not url:
            url = str(game.get("background") or "")
        return self.artwork_cache.fetch(
            "epic", str(game.get("providerGameId") or ""), kind, url)

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
            return {"available": False, "complete": False, "by_id": {}}
        by_id: dict[str, dict[str, Any]] = {}
        complete = True
        try:
            paths = list(directory.glob("*.item"))
        except OSError:
            return {"available": False, "complete": False, "by_id": {}}
        for path in paths:
            try:
                value = json.loads(path.read_text(encoding="utf-8-sig"))
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                complete = False
                continue
            app_name = str(value.get("AppName") or "").strip().casefold()
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
        return {"available": True, "complete": complete, "by_id": by_id}

    @staticmethod
    def installed_from_snapshot(game: dict[str, Any],
                                snapshot: dict[str, Any]) -> dict[str, Any] | None:
        if not snapshot.get("available"):
            return None
        provider_id = str(game.get("providerGameId") or "").strip()
        item = (snapshot.get("by_id") or {}).get(provider_id) if provider_id else None
        return item if item is not None and item.get("installed") else None

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
        if not re.fullmatch(r"[A-Za-z0-9_-]+", stable):
            self._dispatch_reason = "legendary_unresolved"
            return None
        info_ok, info = self._legendary_json(
            ["info", stable, "--json", "--platform", "Windows"])
        if info_ok and isinstance(info, dict):
            return stable
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
        return app_name, {
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
                    invalid_ids.add(app_name)
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
        if app_name in set(snapshot.get("invalid_ids") or []):
            return False, None
        installed = (snapshot.get("by_id") or {}).get(app_name)
        if installed is None and not snapshot.get("complete"):
            return False, None
        return True, installed

    def catalog(self) -> dict[str, Any]:
        readable, payload = self._legendary_json(["list", "--json"])
        if not readable or not isinstance(payload, list):
            return {"available": False, "complete": False, "games": [],
                    "reason": "legendary_catalog_unavailable"}
        installed = self.legendary_snapshot()
        if not installed.get("available"):
            return {"available": False, "complete": False, "games": [],
                    "reason": "legendary_verification_failed"}
        games: list[dict[str, Any]] = []
        seen: set[str] = set()
        for item in payload[:100000]:
            if not isinstance(item, dict):
                continue
            app_name = self._app_name(item)
            metadata = item.get("metadata") if isinstance(item.get("metadata"), dict) else {}
            title = str(item.get("title") or item.get("app_title")
                        or metadata.get("title") or "").strip()
            if not re.fullmatch(r"[A-Za-z0-9_-]+", app_name) or not title \
                    or app_name in seen:
                continue
            seen.add(app_name)
            local = (installed.get("by_id") or {}).get(app_name) or {}
            cover = self._epic_image(
                metadata, "DieselGameBoxTall", "OfferImageTall",
                "DieselStoreFrontTall", "DieselGameBox", "OfferImageWide",
                "DieselStoreFrontWide")
            background = self._epic_image(
                metadata, "OfferImageWide", "DieselGameBox", "DieselStoreFrontWide")
            hero = self._epic_image(
                metadata, "DieselStoreFrontWide", "DieselGameBoxWide",
                "OfferImageWide", "DieselGameBox")
            description = _plain_description(
                metadata.get("description") or metadata.get("shortDescription"))
            record = {
                "id": f"epic:{app_name}", "provider": "epic",
                "providerGameId": app_name, "libraryKey": "epic",
                "libraryName": "Epic", "source": "Epic", "playniteGameId": "",
                "capabilities": {"launch": True, "install": True, "uninstall": True},
                "providerCapabilities": {
                    "requiresConnector": False, "streamMode": "neutral",
                    "startBeforeStream": False},
                "name": title, "installed": bool(local.get("installed")),
                "installDir": str(local.get("install_directory") or ""),
            }
            if description:
                record["description"] = description
            if cover:
                record["cover"] = cover
            if background:
                record["background"] = background
            if hero:
                record["hero"] = hero
            if cover or background or hero or description:
                record["metadataProvider"] = "epic"
                record["artworkVersion"] = hashlib.sha256(
                    f"{cover}\0{background}\0{hero}".encode("utf-8")).hexdigest()
            games.append(record)
        return {"available": True, "complete": bool(installed.get("complete")),
                "games": games, "reason": ""}

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

    def launch(self, game: dict[str, Any], task_id: Hashable | None = None,
               launch_allowed: Callable[[], bool] | None = None) -> dict[str, Any]:
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
            if launch_allowed is not None and not launch_allowed():
                return {"accepted": False, "command": "launch", "reason": "launch_cancelled"}
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

    def _legendary_state(self, name: str) -> Any:
        """One of Legendary's per-profile JSON files, or None when absent or unreadable."""
        if self.legendary_state_path is None:
            return None
        try:
            return json.loads((self.legendary_state_path / name).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def _pending_updates(self) -> set[str] | None:
        """App names whose installed version differs from the latest build Legendary knows;
        None when that is unknown. `legendary launch` compares exactly these (installed.json
        `version`, assets.json `build_version`) and refuses an out-of-date game; the catalog's
        `legendary list` refreshes the assets."""
        if self._legendary() is None:
            return None
        installed = self._legendary_state("installed.json")
        assets = self._legendary_state("assets.json")
        if not isinstance(installed, dict) or not isinstance(assets, dict):
            return None
        latest = {(platform, str(asset.get("app_name") or "")): str(asset.get("build_version") or "")
                  for platform, items in assets.items() if isinstance(items, list)
                  for asset in items if isinstance(asset, dict)}
        return {app for app, item in installed.items() if isinstance(item, dict)
                and latest.get((str(item.get("platform") or "Windows"), app))
                not in (None, "", str(item.get("version") or ""))}

    def update_states(self) -> dict[str, dict[str, Any]]:
        """Pending updates by app name (library tiles): `required`, or `downloading` while a
        Legendary update process runs."""
        states = {app: {"state": "required", "progress": -1}
                  for app in self._pending_updates() or set()}
        with self.process_registry.lock:
            for tracked in self.process_registry.by_task.values():
                if tracked.provider == "epic" and tracked.phase == "update" \
                        and tracked.process.poll() is None:
                    states[tracked.app_name] = {
                        "state": "downloading",
                        "progress": -1 if tracked.progress is None else tracked.progress}
        return states

    def update_sample(self, game: dict[str, Any], refresh: bool = False) -> dict[str, Any]:
        """The state of an update a start waits on (as SteamProvider.update_sample). The update
        is a tracked `legendary install --update-only` process: it reports the progress and
        its exit code is the evidence. `refresh` first asks Epic for the latest builds, as
        `legendary launch` would. Nothing here times out."""
        app_name = str(game.get("providerGameId") or "").strip()
        game_id = str(game.get("id") or app_name)
        result = {"pending": False, "installed": True, "phase": "downloading",
                  "progress": -1, "error": "", "nudge": False}
        finished = None
        with self.process_registry.lock:
            tracked = self.process_registry.active(game_id)
            if tracked is not None and tracked.phase == "update":
                if tracked.finalizing or tracked.process.poll() is None:
                    return {**result, "pending": True,
                            "progress": -1 if tracked.progress is None else tracked.progress}
                if tracked.operation_type == "update":
                    self.process_registry.remove(tracked)
                    finished = tracked
        error = self._update_errors.pop(game_id, "")
        if finished is not None:
            self._finish_output_collection(finished)
            if finished.process.returncode != 0:
                error = "epic_update_failed"
        if error:
            return {**result, "error": error}
        if refresh:
            self._legendary_json(["list-installed", "--check-updates", "--json"])
        pending = app_name in (self._pending_updates() or set())
        if pending and finished is not None:
            return {**result, "error": "epic_update_incomplete"}
        # No update process runs: a pending update asks the caller to start one.
        return {**result, "pending": pending, "nudge": pending}

    def dispatch_update(self, game: dict[str, Any]) -> dict[str, Any]:
        """Start the update of an installed game; a start that fails is the update's error."""
        direct = self._start_legendary(game, "update")
        if direct is not None:
            return direct
        if self._dispatch_reason != "operation_busy":
            self._update_errors[str(game.get("id") or game.get("providerGameId") or "")] = \
                "epic_update_failed"
        return {"accepted": False, "command": "update", "provider": "epic",
                "dispatch": "none", "reason": self._dispatch_reason or "legendary_process_failed"}

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
        # An install of an installed game with a pending update is that update (tile action).
        update = operation == "update" or (operation == "install" and installed is not None)
        if update and installed is None:
            self._dispatch_reason = "legendary_not_installed"
            return None
        if operation == "install" and update \
                and app_name not in (self._pending_updates() or set()):
            return {"accepted": True, "command": "install", "provider": "epic",
                    "dispatch": "legendary_preinstalled", "app_name": app_name,
                    "install_directory": installed["install_directory"]}
        migration = self._migration_candidate(game, app_name) \
            if operation == "install" and not update else None
        if operation == "uninstall" and installed is None:
            return {"accepted": True, "command": "uninstall", "provider": "epic",
                    "dispatch": "already_absent", "app_name": app_name}
        if migration is not None:
            arguments = [str(executable), "-y", "import", app_name,
                         str(migration["install_directory"]),
                         "--platform", "Windows", "--skip-dlcs"]
        elif update:
            arguments = [str(executable), "-y", "install", app_name, "--update-only",
                         "--skip-dlcs", "--skip-sdl"]
        else:
            arguments = [str(executable), "-y", operation, app_name]
        if operation == "install" and migration is None and not update:
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
            process_type="import" if migration is not None else "update" if update else operation,
            phase="import" if migration is not None else "update" if update else operation,
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
                "dispatch": "legendary_import" if migration is not None
                else "legendary_update" if update else "legendary",
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
        self.steam.process_registry = self.processes

    @staticmethod
    def provider_label(game: dict[str, Any]) -> str:
        explicit = str(game.get("provider") or "").strip().casefold()
        if explicit in {"steam", "epic", "playnite"}:
            return explicit
        legacy = str(game.get("source") or game.get("pluginName") or "").strip().casefold()
        return legacy if legacy in {"steam", "epic"} else "playnite"

    def provider_for(self, game: dict[str, Any]) -> GenericPlayniteProvider:
        return self.provider_for_label(self.provider_label(game))

    def provider_for_label(self, label: str) -> GenericPlayniteProvider:
        normalized = str(label).casefold()
        if normalized == "epic":
            return self.epic
        if normalized == "steam":
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

    def update_sample(self, game: dict[str, Any],
                      refresh: bool = False) -> dict[str, Any] | None:
        """The pending update of a Steam or Epic game, or None for a provider without one.
        `refresh` asks the provider for its latest builds first (a start, not a poll)."""
        provider = self.provider_for(game)
        if provider is self.steam:
            return self.steam.update_sample(game)
        return self.epic.update_sample(game, refresh) if provider is self.epic else None

    def update_states(self, providers: set[str]) -> dict[tuple[str, str], dict[str, Any]]:
        """Pending updates of installed games by (provider, provider game id), for tiles."""
        return {(label, key): state
                for label, provider in (("steam", self.steam), ("epic", self.epic))
                if label in providers for key, state in provider.update_states().items()}

    def dispatch_update(self, game: dict[str, Any]) -> dict[str, Any]:
        provider = self.provider_for(game)
        return self.epic.dispatch_update(game) if provider is self.epic \
            else self.steam.dispatch_update(game)

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

    def launch(self, game: dict[str, Any], task_id: Hashable | None = None,
               send_command: Callable[..., dict[str, Any]] | None = None,
               prepare_nonsteam: Callable[[], dict[str, Any]] | None = None,
               launch_allowed: Callable[[], bool] | None = None) -> dict[str, Any]:
        provider = self.provider_for(game)
        if provider is not self.steam and prepare_nonsteam is not None:
            prepared = prepare_nonsteam()
            if not prepared.get("ready") \
                    and prepared.get("reason") != "host_session_locked":
                return {"accepted": False, "command": "launch",
                        "reason": str(prepared.get("reason") or "steam_close_request_failed")}
        if launch_allowed is not None and not launch_allowed():
            return {"accepted": False, "command": "launch", "reason": "launch_cancelled"}
        if provider is self.generic:
            return provider.launch(game, task_id, send_command, launch_allowed=launch_allowed)
        if provider is self.epic:
            return provider.launch(game, task_id, launch_allowed=launch_allowed)
        return provider.launch(game, task_id)

    def sample_launch(self, game: dict[str, Any], task_id: Hashable) -> dict[str, Any] | None:
        provider = self.provider_for(game)
        if provider is self.epic:
            return self.epic.sample_launch(game, task_id)
        if provider is self.steam:
            return self.steam.sample_launch(game, task_id)
        return None

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
        provider_id = str(game.get("providerGameId") or "").strip()
        return (snapshot.get("by_id") or {}).get(provider_id) if provider_id else None

    def artwork(self, game: dict[str, Any], kind: str) -> Path:
        provider = self.provider_for(game)
        if provider not in {self.steam, self.epic}:
            raise FileNotFoundError("Direct provider artwork is unavailable.")
        return provider.artwork(game, kind)

    @staticmethod
    def _playnite_source(game: dict[str, Any]) -> str:
        return str(game.get("source") or game.get("sourceName") or "").strip()

    @staticmethod
    def _playnite_record(game: dict[str, Any]) -> dict[str, Any] | None:
        game_id = str(game.get("id") or "").strip().lower()
        if not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                game_id):
            return None
        source = GameOperationsService._playnite_source(game)
        library_key = re.sub(r"[^a-z0-9_-]+", "-", source.casefold()).strip("-") \
            or "playnite"
        record = dict(game)
        record.update({
            "id": game_id, "provider": "playnite",
            "providerGameId": game_id, "playniteGameId": game_id,
            "libraryKey": library_key,
            "libraryName": source or "Playnite",
            "capabilities": {"launch": True, "install": True, "uninstall": True},
            "providerCapabilities": {
                "requiresConnector": True, "streamMode": "managed",
                "startBeforeStream": False},
            "installed": bool(game.get("installed") or game.get("isInstalled")),
            "installDir": str(game.get("installDir") or game.get("install_dir") or ""),
        })
        return record

    def aggregate_catalog(self, playnite_games: list[dict[str, Any]],
                          previous: dict[str, dict[str, Any]] | None = None) -> dict[str, Any]:
        previous = previous or {}
        steam = self.steam.catalog()
        epic = self.epic.catalog()
        health = {
            "steam": {key: value for key, value in steam.items() if key != "games"},
            "epic": {key: value for key, value in epic.items() if key != "games"},
            "playnite": {"available": True, "complete": True, "reason": ""},
        }
        records: dict[str, dict[str, Any]] = {}
        for provider, snapshot in (("steam", steam), ("epic", epic)):
            if snapshot.get("available"):
                for game in snapshot.get("games") or []:
                    if isinstance(game, dict):
                        records[str(game["id"])] = dict(game)
            if not snapshot.get("available") or not snapshot.get("complete"):
                for game_id, game in previous.items():
                    if str(game.get("provider") or "") == provider:
                        if snapshot.get("installationComplete"):
                            records.setdefault(game_id, dict(game))
                        elif not records.get(game_id, {}).get("installed"):
                            records[game_id] = dict(game)

        standalone: list[dict[str, Any]] = []
        for game in playnite_games:
            if not isinstance(game, dict):
                continue
            source = self._playnite_source(game).casefold()
            if source not in {"steam", "epic"}:
                playnite = self._playnite_record(game)
                if playnite is not None:
                    standalone.append(playnite)
        for record in records.values():
            old = previous.get(str(record["id"])) or {}
            provider = str(record.get("provider") or "")
            if old.get("metadataProvider") == provider:
                for key in ("cover", "background", "hero", "description", "genres",
                            "artworkVersion", "metadataProvider"):
                    if record.get(key) in (None, "", []) \
                            and old.get(key) not in (None, "", []):
                        record[key] = old[key]
            if record.get("provider") == "steam" and "playtimeMinutes" not in record:
                # A manifest-only/offline snapshot has no usage data, not zero usage.
                for key in ("playtimeMinutes", "lastPlayed"):
                    if key in old:
                        record[key] = old[key]
        for record in standalone:
            records[record["id"]] = record
        return {"library": records, "providers": health}
