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
PATCH_MARKER = "# WAKEPLAY-CONSOLE-BRIDGE-V9"

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


def patch_text(source: str) -> tuple[str, bool]:
    if PATCH_MARKER in source:
        return source, False
    if PATCH_MARKER_V8 in source:
        return add_source_support(source), True
    if PATCH_MARKER_V7 in source:
        return add_source_support(add_install_support(source)), True
    if PATCH_MARKER_V6 in source:
        if PLAY_COUNT_PAYLOAD_ANCHOR not in source:
            raise ValueError("Unsupported Sunshine Playnite Connector; V6 game payload is incomplete")
        patched = source.replace(
            PLAY_COUNT_PAYLOAD_ANCHOR, PLAY_COUNT_PAYLOAD_REPLACEMENT, 1)
        return add_source_support(add_install_support(patched)), True
    if PATCH_MARKER_V5 in source:
        if DESCRIPTION_PAYLOAD_ANCHOR not in source:
            raise ValueError("Unsupported Sunshine Playnite Connector; V5 game payload is incomplete")
        patched = source.replace(
            DESCRIPTION_PAYLOAD_ANCHOR, DESCRIPTION_PAYLOAD_REPLACEMENT, 1)
        return add_source_support(add_install_support(patched)), True
    if PATCH_MARKER_V4 in source:
        if LAUNCH_PREP_REPLACEMENT not in source:
            raise ValueError("Unsupported Sunshine Playnite Connector; V4 launch block is incomplete")
        patched = source.replace(LAUNCH_PREP_REPLACEMENT, LAUNCH_CLEAN_REPLACEMENT, 1)
        if DESCRIPTION_PAYLOAD_ANCHOR not in patched:
            raise ValueError("Unsupported Sunshine Playnite Connector; V4 game payload is incomplete")
        patched = patched.replace(
            DESCRIPTION_PAYLOAD_ANCHOR, DESCRIPTION_PAYLOAD_REPLACEMENT, 1)
        return add_source_support(add_install_support(patched)), True
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
    return add_source_support(add_install_support(patched)), True


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
