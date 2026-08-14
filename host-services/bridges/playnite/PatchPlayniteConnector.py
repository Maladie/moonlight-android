#!/usr/bin/env python3
"""Idempotently add WakePlay launcher snapshots to SunshinePlaynite.psm1."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


PATCH_MARKER_V1 = "# WAKEPLAY-CONSOLE-SNAPSHOT-V1"
PATCH_MARKER_V2 = "# WAKEPLAY-CONSOLE-BRIDGE-V2"
PATCH_MARKER_V3 = "# WAKEPLAY-CONSOLE-BRIDGE-V3"
PATCH_MARKER_V4 = "# WAKEPLAY-CONSOLE-BRIDGE-V4"
PATCH_MARKER_V5 = "# WAKEPLAY-CONSOLE-BRIDGE-V5"
PATCH_MARKER_V6 = "# WAKEPLAY-CONSOLE-BRIDGE-V6"
PATCH_MARKER_V7 = "# WAKEPLAY-CONSOLE-BRIDGE-V7"
PATCH_MARKER_V8 = "# WAKEPLAY-CONSOLE-BRIDGE-V8"
PATCH_MARKER_V9 = "# WAKEPLAY-CONSOLE-BRIDGE-V9"
PATCH_MARKER_V10 = "# WAKEPLAY-CONSOLE-BRIDGE-V10"
PATCH_MARKER_V11 = "# WAKEPLAY-CONSOLE-BRIDGE-V11"
PATCH_MARKER_V12 = "# WAKEPLAY-CONSOLE-BRIDGE-V12"
PATCH_MARKER_V13 = "# WAKEPLAY-CONSOLE-BRIDGE-V13"
PATCH_MARKER_V14 = "# WAKEPLAY-CONSOLE-BRIDGE-V14"
PATCH_MARKER_V15 = "# WAKEPLAY-CONSOLE-BRIDGE-V15"
PATCH_MARKER = "# WAKEPLAY-CONSOLE-BRIDGE-V16"

LAUNCH_PREP_ANCHOR = """          Register-SunshineLaunchedGame -Id $obj.id
          [UIBridge]::StartGameByGuidStringOnUIThread([string]$obj.id)"""

LAUNCH_PREP_REPLACEMENT = """          try {
            $prepareBody = @{ game_id = [string]$obj.id } | ConvertTo-Json -Compress
            Invoke-RestMethod -Uri 'http://127.0.0.1:8780/game/prepare' -Method Post `
              -ContentType 'application/json' -Body $prepareBody -TimeoutSec 3 | Out-Null
          }
          catch {
            Write-Log "WakePlay display preparation skipped for $($obj.id): $($_.Exception.Message)"
          }
          Register-SunshineLaunchedGame -Id $obj.id
          [UIBridge]::StartGameByGuidStringOnUIThread([string]$obj.id)
          # WAKEPLAY-CONSOLE-BRIDGE-V4"""

LAUNCH_CLEAN_REPLACEMENT = """          Register-SunshineLaunchedGame -Id $obj.id
          [UIBridge]::StartGameByGuidStringOnUIThread([string]$obj.id)
          # WAKEPLAY-CONSOLE-BRIDGE-V5"""

READER_ANCHOR = """        if ($obj.type -eq 'command' -and $obj.command -eq 'launch' -and $obj.id) {
          Register-SunshineLaunchedGame -Id $obj.id
          [UIBridge]::StartGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log \"LauncherConn[$Guid]: launch dispatched for $($obj.id)\"
        }
        elseif ($obj.type -and $obj.command) {"""

READER_REPLACEMENT = """        if ($obj.type -eq 'command' -and $obj.command -eq 'launch' -and $obj.id) {
          Register-SunshineLaunchedGame -Id $obj.id
          [UIBridge]::StartGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log \"LauncherConn[$Guid]: launch dispatched for $($obj.id)\"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'snapshot') {
          Send-WakePlaySnapshotToLauncher -Target $Guid
          Write-Log \"LauncherConn[$Guid]: WakePlay snapshot dispatched\"
        }
        elseif ($obj.type -and $obj.command) {"""

FUNCTION_ANCHOR = "function Start-ConnectorLoop {"

SNAPSHOT_FUNCTION = r'''# WAKEPLAY-CONSOLE-SNAPSHOT-V1
function Send-WakePlaySnapshotToLauncher {
  param([Parameter(Mandatory)][string]$Target)
  try {
    $targets = @($Target)
    $payload = @{ type = 'snapshotStart'; payload = @{} } | ConvertTo-Json -Depth 2 -Compress
    Send-PayloadToLauncherConnections -Payload $payload -Targets $targets -Context 'WakePlay snapshot start' | Out-Null
    $plugins = @(Get-PlaynitePlugins)
    $payload = @{ type = 'plugins'; payload = $plugins } | ConvertTo-Json -Depth 6 -Compress
    Send-PayloadToLauncherConnections -Payload $payload -Targets $targets -Context 'WakePlay plugins snapshot' | Out-Null

    $categories = @(Get-PlayniteCategories)
    $payload = @{ type = 'categories'; payload = $categories } | ConvertTo-Json -Depth 6 -Compress
    Send-PayloadToLauncherConnections -Payload $payload -Targets $targets -Context 'WakePlay categories snapshot' | Out-Null

    $games = @(Get-PlayniteGames)
    $batchSize = 100
    for ($i = 0; $i -lt $games.Count; $i += $batchSize) {
      $last = [Math]::Min($i + $batchSize - 1, $games.Count - 1)
      $chunk = $games[$i..$last]
      $payload = @{ type = 'games'; payload = $chunk } | ConvertTo-Json -Depth 8 -Compress
      Send-PayloadToLauncherConnections -Payload $payload -Targets $targets -Context 'WakePlay games snapshot' | Out-Null
    }
    $complete = @{ type = 'snapshotComplete'; payload = @{ games = $games.Count } } | ConvertTo-Json -Depth 4 -Compress
    Send-PayloadToLauncherConnections -Payload $complete -Targets $targets -Context 'WakePlay snapshot complete' | Out-Null
  }
  catch {
    Write-Log "WakePlay snapshot failed for $Target`: $($_.Exception.Message)"
  }
}

'''

STATUS_PARAM_ANCHOR = "  param([string]$Name, [object]$Game)"
STATUS_PARAM_REPLACEMENT = "  param([string]$Name, [object]$Game, [int]$ProcessId = 0)"
STATUS_OBJECT_ANCHOR = "$status = @{ name = $Name; id = $Game.Id.ToString(); installDir = $instDir; exe = (Get-GameActionInfo -Game $Game).exe }"
STATUS_OBJECT_REPLACEMENT = "$status = @{ name = $Name; id = $Game.Id.ToString(); installDir = $instDir; exe = (Get-GameActionInfo -Game $Game).exe; processId = $ProcessId }"
SEND_PARAM_ANCHOR = "  param([string]$Name, [object]$Game, [switch]$ReturnLauncherCount)"
SEND_PARAM_REPLACEMENT = "  param([string]$Name, [object]$Game, [int]$ProcessId = 0, [switch]$ReturnLauncherCount)"
SEND_BUILD_ANCHOR = "try { $payload = Build-StatusPayload -Name $Name -Game $Game }"
SEND_BUILD_REPLACEMENT = "try { $payload = Build-StatusPayload -Name $Name -Game $Game -ProcessId $ProcessId }"
STARTED_ANCHOR = "  Send-StatusMessage -Name 'gameStarted' -Game $game\n}"
STARTED_REPLACEMENT = """  $processId = 0
  try { $processId = [int]$evnArgs.StartedProcessId } catch {}
  Send-StatusMessage -Name 'gameStarted' -Game $game -ProcessId $processId
}
# WAKEPLAY-CONSOLE-BRIDGE-V2"""

ARTWORK_LOOKUP_ANCHOR = """    $boxArt = Get-BoxArtPath -Game $g
    $icon = Get-IconPath -Game $g"""
ARTWORK_LOOKUP_REPLACEMENT = """    $boxArt = Get-BoxArtPath -Game $g
    $backgroundArt = ''
    try {
      if ($g.BackgroundImage) {
        $backgroundArt = $PlayniteApi.Database.GetFullFilePath($g.BackgroundImage)
      }
    } catch {}
    $icon = Get-IconPath -Game $g"""
ARTWORK_PAYLOAD_ANCHOR = """      boxArtPath      = $boxArt
      iconPath        = $icon"""
ARTWORK_PAYLOAD_REPLACEMENT = """      boxArtPath      = $boxArt
      backgroundImagePath = $backgroundArt
      iconPath        = $icon
      # WAKEPLAY-CONSOLE-BRIDGE-V3"""

DESCRIPTION_PAYLOAD_ANCHOR = """      backgroundImagePath = $backgroundArt
      iconPath        = $icon"""
DESCRIPTION_PAYLOAD_REPLACEMENT = """      backgroundImagePath = $backgroundArt
      description     = [string]$g.Description
      playCount       = [int]$g.PlayCount
      iconPath        = $icon
      # WAKEPLAY-CONSOLE-BRIDGE-V7"""

PLAY_COUNT_PAYLOAD_ANCHOR = """      description     = [string]$g.Description
      iconPath        = $icon
      # WAKEPLAY-CONSOLE-BRIDGE-V6"""
PLAY_COUNT_PAYLOAD_REPLACEMENT = """      description     = [string]$g.Description
      playCount       = [int]$g.PlayCount
      iconPath        = $icon
      # WAKEPLAY-CONSOLE-BRIDGE-V7"""

SOURCE_PAYLOAD_ANCHOR = """      playCount       = [int]$g.PlayCount
      iconPath        = $icon
      # WAKEPLAY-CONSOLE-BRIDGE-V7"""
SOURCE_PAYLOAD_REPLACEMENT = """      playCount       = [int]$g.PlayCount
      source          = [string]$(try {
        $sourceName = [string]$g.Source.Name
        if ([string]::IsNullOrWhiteSpace($sourceName) -and $g.SourceId) {
          $sourceName = [string]$PlayniteApi.Database.Sources.Get($g.SourceId).Name
        }
        $sourceName
      } catch { '' })
      iconPath        = $icon
      # WAKEPLAY-CONSOLE-BRIDGE-V9"""

GENRE_PAYLOAD_ANCHOR = SOURCE_PAYLOAD_REPLACEMENT
GENRE_PAYLOAD_REPLACEMENT = """      playCount       = [int]$g.PlayCount
      source          = [string]$(try {
        $sourceName = [string]$g.Source.Name
        if ([string]::IsNullOrWhiteSpace($sourceName) -and $g.SourceId) {
          $sourceName = [string]$PlayniteApi.Database.Sources.Get($g.SourceId).Name
        }
        $sourceName
      } catch { '' })
      genres          = @($(try {
        foreach ($genreId in @($g.GenreIds)) {
          $genre = $PlayniteApi.Database.Genres.Get($genreId)
          if ($genre -and $genre.Name) { [string]$genre.Name }
        }
      } catch {}))
      iconPath        = $icon
      # WAKEPLAY-CONSOLE-BRIDGE-V10"""

INSTALL_UI_ANCHOR = """    public static void StartGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => StartGameByGuidString(guidStr))); }
        else { StartGameByGuidString(guidStr); }
    }
}"""
INSTALL_UI_REPLACEMENT = """    public static void StartGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => StartGameByGuidString(guidStr))); }
        else { StartGameByGuidString(guidStr); }
    }

    public static void InstallGameByGuidString(string guidStr)
    {
        if (string.IsNullOrWhiteSpace(guidStr)) return;
        Guid gid; if (!Guid.TryParse(guidStr, out gid)) return;
        var api = Api; if (api == null) return;
        var method = api.GetType().GetMethod("InstallGame", new Type[] { typeof(Guid) });
        if (method != null) method.Invoke(api, new object[] { gid });
    }

    public static void InstallGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => InstallGameByGuidString(guidStr))); }
        else { InstallGameByGuidString(guidStr); }
    }
}"""

INSTALL_READER_ANCHOR = """        elseif ($obj.type -eq 'command' -and $obj.command -eq 'snapshot') {"""
INSTALL_READER_REPLACEMENT = """        elseif ($obj.type -eq 'command' -and $obj.command -eq 'install' -and $obj.id) {
          [UIBridge]::InstallGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: install dispatched for $($obj.id)"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'snapshot') {"""

INSTALLING_PAYLOAD_ANCHOR = """      installed       = $installed"""
INSTALLING_PAYLOAD_REPLACEMENT = """      installed       = $installed
      installing      = [bool]$g.IsInstalling"""

INSTALL_EVENT_ANCHOR = "function Register-GameCollectionEvents {"
INSTALL_EVENT_REPLACEMENT = r'''function OnGameInstalled() {
  param($evnArgs)
  $game = $evnArgs.Game
  Write-Log "OnGameInstalled: $($game.Name) [$($game.Id)]"
  Send-StatusMessage -Name 'gameInstalled' -Game $game
}

function OnGameInstallationCancelled() {
  param($evnArgs)
  $game = $evnArgs.Game
  Write-Log "OnGameInstallationCancelled: $($game.Name) [$($game.Id)]"
  Send-StatusMessage -Name 'gameInstallationCancelled' -Game $game
}
# WAKEPLAY-CONSOLE-BRIDGE-V8

function Register-GameCollectionEvents {'''


def add_install_support(source: str) -> str:
    anchors = (
        ("UI install bridge", INSTALL_UI_ANCHOR),
        ("launcher install command", INSTALL_READER_ANCHOR),
        ("installing game payload", INSTALLING_PAYLOAD_ANCHOR),
        ("installation lifecycle events", INSTALL_EVENT_ANCHOR),
    )
    missing = [name for name, anchor in anchors if anchor not in source]
    if missing:
        raise ValueError("Unsupported Sunshine Playnite Connector; missing " + ", ".join(missing))
    patched = source.replace(INSTALL_UI_ANCHOR, INSTALL_UI_REPLACEMENT, 1)
    patched = patched.replace(INSTALL_READER_ANCHOR, INSTALL_READER_REPLACEMENT, 1)
    patched = patched.replace(INSTALLING_PAYLOAD_ANCHOR, INSTALLING_PAYLOAD_REPLACEMENT, 1)
    return patched.replace(INSTALL_EVENT_ANCHOR, INSTALL_EVENT_REPLACEMENT, 1)


def add_source_support(source: str) -> str:
    if SOURCE_PAYLOAD_ANCHOR not in source:
        raise ValueError("Unsupported Sunshine Playnite Connector; source payload is incomplete")
    return source.replace(SOURCE_PAYLOAD_ANCHOR, SOURCE_PAYLOAD_REPLACEMENT, 1)


def add_genre_support(source: str) -> str:
    if GENRE_PAYLOAD_ANCHOR not in source:
        raise ValueError("Unsupported Sunshine Playnite Connector; V9 game payload is incomplete")
    return source.replace(GENRE_PAYLOAD_ANCHOR, GENRE_PAYLOAD_REPLACEMENT, 1)


def add_latest_metadata(source: str) -> str:
    return add_genre_support(add_source_support(source))


def add_uninstall_support(source: str) -> str:
    ui_anchor = """    public static void InstallGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => InstallGameByGuidString(guidStr))); }
        else { InstallGameByGuidString(guidStr); }
    }
}"""
    ui_replacement = """    public static void InstallGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => InstallGameByGuidString(guidStr))); }
        else { InstallGameByGuidString(guidStr); }
    }

    public static void UninstallGameByGuidString(string guidStr)
    {
        if (string.IsNullOrWhiteSpace(guidStr)) return;
        Guid gid; if (!Guid.TryParse(guidStr, out gid)) return;
        var api = Api; if (api == null) return;
        var method = api.GetType().GetMethod("UninstallGame", new Type[] { typeof(Guid) });
        if (method != null) method.Invoke(api, new object[] { gid });
    }

    public static void UninstallGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => UninstallGameByGuidString(guidStr))); }
        else { UninstallGameByGuidString(guidStr); }
    }
}"""
    reader_anchor = """        elseif ($obj.type -eq 'command' -and $obj.command -eq 'snapshot') {"""
    reader_replacement = """        elseif ($obj.type -eq 'command' -and $obj.command -eq 'uninstall' -and $obj.id) {
          [UIBridge]::UninstallGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: uninstall dispatched for $($obj.id)"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'snapshot') {"""
    if ui_anchor not in source or reader_anchor not in source:
        raise ValueError("Unsupported V10 connector; uninstall anchors are missing")
    patched = source.replace(ui_anchor, ui_replacement, 1)
    patched = patched.replace(reader_anchor, reader_replacement, 1)
    return patched.replace(PATCH_MARKER_V10, PATCH_MARKER_V11, 1)


def add_external_install_completion_support(source: str) -> str:
    ui_anchor = """    public static void UninstallGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => UninstallGameByGuidString(guidStr))); }
        else { UninstallGameByGuidString(guidStr); }
    }
}"""
    ui_replacement = """    public static void UninstallGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => UninstallGameByGuidString(guidStr))); }
        else { UninstallGameByGuidString(guidStr); }
    }

    public static void MarkGameInstalled(string guidStr, string installDirectory)
    {
        if (string.IsNullOrWhiteSpace(guidStr)) return;
        Guid gid; if (!Guid.TryParse(guidStr, out gid)) return;
        var api = Api; if (api == null) return;
        var dbProp = api.GetType().GetProperty("Database");
        var db = dbProp != null ? dbProp.GetValue(api) : null; if (db == null) return;
        var gamesProp = db.GetType().GetProperty("Games");
        var games = gamesProp != null ? gamesProp.GetValue(db) : null; if (games == null) return;
        var get = games.GetType().GetMethod("Get", new Type[] { typeof(Guid) });
        var game = get != null ? get.Invoke(games, new object[] { gid }) : null;
        if (game == null) return;
        var installed = game.GetType().GetProperty("IsInstalled");
        if (installed != null && installed.CanWrite) installed.SetValue(game, true);
        var directory = game.GetType().GetProperty("InstallDirectory");
        if (directory != null && directory.CanWrite && !string.IsNullOrWhiteSpace(installDirectory))
            directory.SetValue(game, installDirectory);
        System.Reflection.MethodInfo update = null;
        foreach (var method in games.GetType().GetMethods()) {
            var parameters = method.GetParameters();
            if (method.Name == "Update" && parameters.Length == 1 &&
                parameters[0].ParameterType.IsAssignableFrom(game.GetType())) {
                update = method; break;
            }
        }
        if (update == null) throw new MissingMethodException("Playnite game update method unavailable");
        update.Invoke(games, new object[] { game });
    }

    public static void MarkGameInstalledOnUIThread(string guidStr, string installDirectory)
    {
        var d = Dispatcher;
        if (d != null) d.Invoke(new Action(() => MarkGameInstalled(guidStr, installDirectory)));
        else MarkGameInstalled(guidStr, installDirectory);
    }
}"""
    reader_anchor = """        elseif ($obj.type -eq 'command' -and $obj.command -eq 'uninstall' -and $obj.id) {
          [UIBridge]::UninstallGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: uninstall dispatched for $($obj.id)"
        }"""
    reader_replacement = reader_anchor + """
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'mark-installed' -and $obj.id) {
          [UIBridge]::MarkGameInstalledOnUIThread([string]$obj.id, [string]$obj.install_directory)
          Write-Log "LauncherConn[$Guid]: installed state synchronized for $($obj.id)"
        }"""
    if ui_anchor not in source or reader_anchor not in source:
        raise ValueError("Unsupported V11 connector; completion anchors are missing")
    patched = source.replace(ui_anchor, ui_replacement, 1)
    patched = patched.replace(reader_anchor, reader_replacement, 1)
    return patched.replace(PATCH_MARKER_V11, PATCH_MARKER_V12, 1)


def add_provider_game_id_support(source: str) -> str:
    anchor = "      iconPath        = $icon"
    replacement = "      providerGameId  = [string]$g.GameId\n" + anchor
    if anchor not in source:
        raise ValueError("Unsupported V12 connector; provider game ID anchor is missing")
    return source.replace(anchor, replacement, 1).replace(
        PATCH_MARKER_V12, PATCH_MARKER_V13, 1)


def add_external_uninstall_completion_support(source: str) -> str:
    ui_anchor_async = """    public static void MarkGameInstalledOnUIThread(string guidStr, string installDirectory)
    {
        var d = Dispatcher;
        if (d != null) d.BeginInvoke(new Action(() => MarkGameInstalled(guidStr, installDirectory)));
        else MarkGameInstalled(guidStr, installDirectory);
    }
}"""
    ui_anchor_sync = ui_anchor_async.replace("d.BeginInvoke(", "d.Invoke(")
    ui_replacement = """    public static void MarkGameInstalledOnUIThread(string guidStr, string installDirectory)
    {
        var d = Dispatcher;
        if (d != null) d.Invoke(new Action(() => MarkGameInstalled(guidStr, installDirectory)));
        else MarkGameInstalled(guidStr, installDirectory);
    }

    public static void MarkGameUninstalled(string guidStr)
    {
        if (string.IsNullOrWhiteSpace(guidStr)) return;
        Guid gid; if (!Guid.TryParse(guidStr, out gid)) return;
        var api = Api; if (api == null) return;
        var databaseProperty = api.GetType().GetProperty("Database");
        var database = databaseProperty != null ? databaseProperty.GetValue(api) : null;
        if (database == null) throw new MissingMemberException("Playnite database is unavailable");
        var gamesProperty = database.GetType().GetProperty("Games");
        var games = gamesProperty != null ? gamesProperty.GetValue(database) : null;
        if (games == null) throw new MissingMemberException("Playnite games database is unavailable");
        var get = games.GetType().GetMethod("Get", new Type[] { typeof(Guid) });
        var game = get != null ? get.Invoke(games, new object[] { gid }) : null;
        if (game == null) return;
        var installed = game.GetType().GetProperty("IsInstalled");
        if (installed == null || !installed.CanWrite)
            throw new MissingMemberException("Playnite installed state is unavailable");
        installed.SetValue(game, false);
        var directory = game.GetType().GetProperty("InstallDirectory");
        if (directory != null && directory.CanWrite) directory.SetValue(game, "");
        System.Reflection.MethodInfo update = null;
        foreach (var method in games.GetType().GetMethods()) {
            var parameters = method.GetParameters();
            if (method.Name == "Update" && parameters.Length == 1 &&
                parameters[0].ParameterType.IsAssignableFrom(game.GetType())) {
                update = method; break;
            }
        }
        if (update == null) throw new MissingMethodException("Playnite game update method unavailable");
        update.Invoke(games, new object[] { game });
    }

    public static void MarkGameUninstalledOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) d.Invoke(new Action(() => MarkGameUninstalled(guidStr)));
        else MarkGameUninstalled(guidStr);
    }
}"""
    reader_anchor = """        elseif ($obj.type -eq 'command' -and $obj.command -eq 'mark-installed' -and $obj.id) {
          [UIBridge]::MarkGameInstalledOnUIThread([string]$obj.id, [string]$obj.install_directory)
          Write-Log "LauncherConn[$Guid]: installed state synchronized for $($obj.id)"
        }"""
    reader_replacement = reader_anchor + """
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'mark-uninstalled' -and $obj.id) {
          [UIBridge]::MarkGameUninstalledOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: uninstalled state synchronized for $($obj.id)"
        }"""
    ui_anchor = ui_anchor_sync if ui_anchor_sync in source else ui_anchor_async
    if ui_anchor not in source or reader_anchor not in source:
        raise ValueError("Unsupported V13 connector; external uninstall anchors are missing")
    return source.replace(ui_anchor, ui_replacement, 1).replace(
        reader_anchor, reader_replacement, 1).replace(PATCH_MARKER_V13, PATCH_MARKER_V14, 1)


def add_installing_state_cleanup(source: str) -> str:
    installed_anchor = \
        "if (installed != null && installed.CanWrite) installed.SetValue(game, true);"
    installed_replacement = installed_anchor + """
        var installing = game.GetType().GetProperty("IsInstalling");
        if (installing != null && installing.CanWrite) installing.SetValue(game, false);"""
    uninstalled_anchor = "installed.SetValue(game, false);"
    uninstalled_replacement = uninstalled_anchor + """
        var installing = game.GetType().GetProperty("IsInstalling");
        if (installing != null && installing.CanWrite) installing.SetValue(game, false);"""
    if installed_anchor not in source or uninstalled_anchor not in source:
        raise ValueError("Unsupported V14 connector; installing-state anchors are missing")
    return source.replace(installed_anchor, installed_replacement, 1).replace(
        uninstalled_anchor, uninstalled_replacement, 1).replace(
        PATCH_MARKER_V14, PATCH_MARKER_V15, 1)


def add_snapshot_start(source: str) -> str:
    anchor = """  try {
    $targets = @($Target)
    $plugins = @(Get-PlaynitePlugins)"""
    replacement = """  try {
    $targets = @($Target)
    $payload = @{ type = 'snapshotStart'; payload = @{} } | ConvertTo-Json -Depth 2 -Compress
    Send-PayloadToLauncherConnections -Payload $payload -Targets $targets -Context 'WakePlay snapshot start' | Out-Null
    $plugins = @(Get-PlaynitePlugins)"""
    if anchor not in source:
        if "type = 'snapshotStart'" in source and PATCH_MARKER_V15 in source:
            return source.replace(PATCH_MARKER_V15, PATCH_MARKER, 1)
        raise ValueError("Unsupported V15 connector; snapshot function is incomplete")
    return source.replace(anchor, replacement, 1).replace(
        PATCH_MARKER_V15, PATCH_MARKER, 1)


def patch_text_v12(source: str) -> tuple[str, bool]:
    if PATCH_MARKER_V12 in source:
        return source, False
    if PATCH_MARKER_V11 in source:
        return add_external_install_completion_support(source), True
    if PATCH_MARKER_V10 in source:
        return add_external_install_completion_support(add_uninstall_support(source)), True
    if PATCH_MARKER_V9 in source:
        return add_external_install_completion_support(
            add_uninstall_support(add_genre_support(source))), True
    if PATCH_MARKER_V8 in source:
        return add_external_install_completion_support(
            add_uninstall_support(add_latest_metadata(source))), True
    if PATCH_MARKER_V7 in source:
        return add_external_install_completion_support(add_uninstall_support(
            add_latest_metadata(add_install_support(source)))), True
    if PATCH_MARKER_V6 in source:
        if PLAY_COUNT_PAYLOAD_ANCHOR not in source:
            raise ValueError("Unsupported Sunshine Playnite Connector; V6 game payload is incomplete")
        patched = source.replace(
            PLAY_COUNT_PAYLOAD_ANCHOR, PLAY_COUNT_PAYLOAD_REPLACEMENT, 1)
        return add_external_install_completion_support(add_uninstall_support(
            add_latest_metadata(add_install_support(patched)))), True
    if PATCH_MARKER_V5 in source:
        if DESCRIPTION_PAYLOAD_ANCHOR not in source:
            raise ValueError("Unsupported Sunshine Playnite Connector; V5 game payload is incomplete")
        patched = source.replace(
            DESCRIPTION_PAYLOAD_ANCHOR, DESCRIPTION_PAYLOAD_REPLACEMENT, 1)
        return add_external_install_completion_support(add_uninstall_support(
            add_latest_metadata(add_install_support(patched)))), True
    if PATCH_MARKER_V4 in source:
        if LAUNCH_PREP_REPLACEMENT not in source:
            raise ValueError("Unsupported Sunshine Playnite Connector; V4 launch block is incomplete")
        patched = source.replace(LAUNCH_PREP_REPLACEMENT, LAUNCH_CLEAN_REPLACEMENT, 1)
        if DESCRIPTION_PAYLOAD_ANCHOR not in patched:
            raise ValueError("Unsupported Sunshine Playnite Connector; V4 game payload is incomplete")
        patched = patched.replace(
            DESCRIPTION_PAYLOAD_ANCHOR, DESCRIPTION_PAYLOAD_REPLACEMENT, 1)
        return add_external_install_completion_support(add_uninstall_support(
            add_latest_metadata(add_install_support(patched)))), True
    anchors = [
        ("launch display preparation", LAUNCH_PREP_ANCHOR),
    ]
    if PATCH_MARKER_V3 not in source:
        anchors += [
            ("background artwork lookup", ARTWORK_LOOKUP_ANCHOR),
            ("background artwork payload", ARTWORK_PAYLOAD_ANCHOR),
        ]
    if PATCH_MARKER_V2 not in source:
        anchors += [
            ("status parameters", STATUS_PARAM_ANCHOR),
            ("status object", STATUS_OBJECT_ANCHOR),
            ("sender parameters", SEND_PARAM_ANCHOR),
            ("sender payload", SEND_BUILD_ANCHOR),
            ("game started handler", STARTED_ANCHOR),
        ]
    if PATCH_MARKER_V1 not in source:
        anchors += [("launcher reader", READER_ANCHOR), ("connector loop", FUNCTION_ANCHOR)]
    missing = [name for name, anchor in anchors if anchor not in source]
    if missing:
        raise ValueError("Unsupported Sunshine Playnite Connector; missing " + ", ".join(missing))
    patched = source
    if PATCH_MARKER_V1 not in patched:
        patched = patched.replace(READER_ANCHOR, READER_REPLACEMENT, 1)
        patched = patched.replace(FUNCTION_ANCHOR, SNAPSHOT_FUNCTION + FUNCTION_ANCHOR, 1)
    if PATCH_MARKER_V2 not in patched:
        for anchor, replacement in (
            (STATUS_PARAM_ANCHOR, STATUS_PARAM_REPLACEMENT),
            (STATUS_OBJECT_ANCHOR, STATUS_OBJECT_REPLACEMENT),
            (SEND_PARAM_ANCHOR, SEND_PARAM_REPLACEMENT),
            (SEND_BUILD_ANCHOR, SEND_BUILD_REPLACEMENT),
            (STARTED_ANCHOR, STARTED_REPLACEMENT),
        ):
            patched = patched.replace(anchor, replacement, 1)
    if PATCH_MARKER_V3 not in source:
        patched = patched.replace(ARTWORK_LOOKUP_ANCHOR, ARTWORK_LOOKUP_REPLACEMENT, 1)
        patched = patched.replace(ARTWORK_PAYLOAD_ANCHOR, ARTWORK_PAYLOAD_REPLACEMENT, 1)
    # The connector has no authoritative stream target while the session is
    # starting. Preparation is performed by Android before launch instead.
    patched = patched.replace(LAUNCH_PREP_ANCHOR, LAUNCH_CLEAN_REPLACEMENT, 1)
    if DESCRIPTION_PAYLOAD_ANCHOR in patched:
        patched = patched.replace(
            DESCRIPTION_PAYLOAD_ANCHOR, DESCRIPTION_PAYLOAD_REPLACEMENT, 1)
    return add_external_install_completion_support(add_uninstall_support(
        add_latest_metadata(add_install_support(patched)))), True


def patch_text(source: str) -> tuple[str, bool]:
    if PATCH_MARKER in source:
        return source, False
    if PATCH_MARKER_V15 in source:
        return add_snapshot_start(source), True
    if PATCH_MARKER_V14 in source:
        return add_snapshot_start(add_installing_state_cleanup(source)), True
    if PATCH_MARKER_V13 in source:
        return add_snapshot_start(add_installing_state_cleanup(
            add_external_uninstall_completion_support(source))), True
    if PATCH_MARKER_V12 in source:
        return add_snapshot_start(add_installing_state_cleanup(
            add_external_uninstall_completion_support(
                add_provider_game_id_support(source)))), True
    patched, _changed = patch_text_v12(source)
    return add_snapshot_start(add_installing_state_cleanup(
        add_external_uninstall_completion_support(
            add_provider_game_id_support(patched)))), True


def patch_file(path: Path, apply: bool) -> str:
    resolved = path.resolve()
    source = resolved.read_text(encoding="utf-8-sig")
    patched, changed = patch_text(source)
    if not changed:
        return "already-patched"
    if not apply:
        return "compatible"
    backup = resolved.with_suffix(resolved.suffix + ".wakeplay-backup")
    if not backup.exists():
        shutil.copy2(resolved, backup)
    temporary = resolved.with_suffix(resolved.suffix + ".wakeplay-new")
    temporary.write_text(patched, encoding="utf-8")
    temporary.replace(resolved)
    return "patched"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(patch_file(args.module, args.apply))


if __name__ == "__main__":
    main()
