#!/usr/bin/env python3
"""Idempotently add MoonWaker launcher commands and snapshots to SunshinePlaynite.psm1."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


# One patch version. Any connector without this marker is patched from its unmodified
# shape; a connector that is neither (an older MoonWaker patch, another connector version)
# lacks the anchors and is reported as unsupported.
PATCH_MARKER = "# WAKEPLAY-CONSOLE-BRIDGE-V17"

READER_ANCHOR = r"""        if ($obj.type -eq 'command' -and $obj.command -eq 'launch' -and $obj.id) {
          Register-SunshineLaunchedGame -Id $obj.id
          [UIBridge]::StartGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: launch dispatched for $($obj.id)"
        }
        elseif ($obj.type -and $obj.command) {"""

READER_REPLACEMENT = r"""        if ($obj.type -eq 'command' -and $obj.command -eq 'launch' -and $obj.id) {
          Register-SunshineLaunchedGame -Id $obj.id
          [UIBridge]::StartGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: launch dispatched for $($obj.id)"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'install' -and $obj.id) {
          [UIBridge]::InstallGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: install dispatched for $($obj.id)"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'uninstall' -and $obj.id) {
          [UIBridge]::UninstallGameByGuidStringOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: uninstall dispatched for $($obj.id)"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'mark-installed' -and $obj.id) {
          [UIBridge]::MarkGameInstalledOnUIThread([string]$obj.id, [string]$obj.install_directory)
          Write-Log "LauncherConn[$Guid]: installed state synchronized for $($obj.id)"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'mark-uninstalled' -and $obj.id) {
          [UIBridge]::MarkGameUninstalledOnUIThread([string]$obj.id)
          Write-Log "LauncherConn[$Guid]: uninstalled state synchronized for $($obj.id)"
        }
        elseif ($obj.type -eq 'command' -and $obj.command -eq 'snapshot') {
          Send-WakePlaySnapshotToLauncher -Target $Guid
          Write-Log "LauncherConn[$Guid]: WakePlay snapshot dispatched"
        }
        elseif ($obj.type -and $obj.command) {"""

FUNCTION_ANCHOR = "function Start-ConnectorLoop {"

FUNCTION_REPLACEMENT = r'''function Send-WakePlaySnapshotToLauncher {
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

function Start-ConnectorLoop {'''

STATUS_PARAM_ANCHOR = "  param([string]$Name, [object]$Game)"
STATUS_PARAM_REPLACEMENT = "  param([string]$Name, [object]$Game, [int]$ProcessId = 0)"
STATUS_OBJECT_ANCHOR = "$status = @{ name = $Name; id = $Game.Id.ToString(); installDir = $instDir; exe = (Get-GameActionInfo -Game $Game).exe }"
# An emulated game reports its emulator's directory, where its window's process runs.
STATUS_OBJECT_REPLACEMENT = r'''try {
    $actions = $null
    try { $actions = $Game.GameActions } catch {}
    if ($actions -and $actions.Count -gt 0) {
      $play = $actions | Where-Object { $_.IsPlayAction } | Select-Object -First 1
      if (-not $play) { $play = $actions[0] }
      if ($play) {
        $isEmu = $false
        try {
          $type = $play.Type
          if ($type -and ([string]$type -match 'Emulator')) { $isEmu = $true }
        } catch {}
        $emuId = ''
        try { $emuId = [string]$play.EmulatorId } catch {}
        if (-not $isEmu -and $emuId -and
            ($emuId -notmatch '(?i)^0{8}-0{4}-0{4}-0{4}-0{12}$')) { $isEmu = $true }
        if ($isEmu -and $PlayniteApi -and $emuId) {
          try {
            foreach ($emu in $PlayniteApi.Database.Emulators) {
              if ([string]$emu.Id -and
                  ([string]$emu.Id).Equals($emuId, [StringComparison]::OrdinalIgnoreCase)) {
                if ($emu.InstallDir) { $instDir = [string]$emu.InstallDir }
                break
              }
            }
          } catch {}
        }
      }
    }
  } catch {}
  $status = @{ name = $Name; id = $Game.Id.ToString(); installDir = $instDir; exe = (Get-GameActionInfo -Game $Game).exe; processId = $ProcessId }'''
SEND_PARAM_ANCHOR = "  param([string]$Name, [object]$Game, [switch]$ReturnLauncherCount)"
SEND_PARAM_REPLACEMENT = "  param([string]$Name, [object]$Game, [int]$ProcessId = 0, [switch]$ReturnLauncherCount)"
SEND_BUILD_ANCHOR = "try { $payload = Build-StatusPayload -Name $Name -Game $Game }"
SEND_BUILD_REPLACEMENT = "try { $payload = Build-StatusPayload -Name $Name -Game $Game -ProcessId $ProcessId }"
STARTED_ANCHOR = "  Send-StatusMessage -Name 'gameStarted' -Game $game\n}"
STARTED_REPLACEMENT = r"""  $processId = 0
  try { $processId = [int]$evnArgs.StartedProcessId } catch {}
  Send-StatusMessage -Name 'gameStarted' -Game $game -ProcessId $processId
}"""

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
GAME_PAYLOAD_ANCHOR = """      boxArtPath      = $boxArt
      iconPath        = $icon"""
GAME_PAYLOAD_REPLACEMENT = """      boxArtPath      = $boxArt
      backgroundImagePath = $backgroundArt
      description     = [string]$g.Description
      playCount       = [int]$g.PlayCount
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
      providerGameId  = [string]$g.GameId
      iconPath        = $icon
      """ + PATCH_MARKER
INSTALLING_PAYLOAD_ANCHOR = """      installed       = $installed"""
INSTALLING_PAYLOAD_REPLACEMENT = """      installed       = $installed
      installing      = [bool]$g.IsInstalling"""

UI_BRIDGE_ANCHOR = """    public static void StartGameByGuidStringOnUIThread(string guidStr)
    {
        var d = Dispatcher;
        if (d != null) { d.BeginInvoke(new Action(() => StartGameByGuidString(guidStr))); }
        else { StartGameByGuidString(guidStr); }
    }
}"""
UI_BRIDGE_REPLACEMENT = """    public static void StartGameByGuidStringOnUIThread(string guidStr)
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
        var installing = game.GetType().GetProperty("IsInstalling");
        if (installing != null && installing.CanWrite) installing.SetValue(game, false);
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
        var installing = game.GetType().GetProperty("IsInstalling");
        if (installing != null && installing.CanWrite) installing.SetValue(game, false);
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

function Register-GameCollectionEvents {'''

PATCHES = (
    ("launcher reader", READER_ANCHOR, READER_REPLACEMENT),
    ("connector loop", FUNCTION_ANCHOR, FUNCTION_REPLACEMENT),
    ("status parameters", STATUS_PARAM_ANCHOR, STATUS_PARAM_REPLACEMENT),
    ("status object", STATUS_OBJECT_ANCHOR, STATUS_OBJECT_REPLACEMENT),
    ("sender parameters", SEND_PARAM_ANCHOR, SEND_PARAM_REPLACEMENT),
    ("sender payload", SEND_BUILD_ANCHOR, SEND_BUILD_REPLACEMENT),
    ("game started handler", STARTED_ANCHOR, STARTED_REPLACEMENT),
    ("background artwork lookup", ARTWORK_LOOKUP_ANCHOR, ARTWORK_LOOKUP_REPLACEMENT),
    ("game payload", GAME_PAYLOAD_ANCHOR, GAME_PAYLOAD_REPLACEMENT),
    ("installing game payload", INSTALLING_PAYLOAD_ANCHOR, INSTALLING_PAYLOAD_REPLACEMENT),
    ("UI bridge", UI_BRIDGE_ANCHOR, UI_BRIDGE_REPLACEMENT),
    ("installation lifecycle events", INSTALL_EVENT_ANCHOR, INSTALL_EVENT_REPLACEMENT),
)


def patch_text(source: str) -> tuple[str, bool]:
    if PATCH_MARKER in source:
        return source, False
    missing = [name for name, anchor, _replacement in PATCHES if anchor not in source]
    if missing:
        raise ValueError("Unsupported Sunshine Playnite Connector; missing " + ", ".join(missing))
    patched = source
    for _name, anchor, replacement in PATCHES:
        patched = patched.replace(anchor, replacement, 1)
    return patched, True


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
