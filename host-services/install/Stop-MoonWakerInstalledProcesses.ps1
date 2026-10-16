#requires -Version 5.1
[CmdletBinding()]
param(
    # Defaults to the one real installation; a test points it at a temporary directory.
    [string]$Root = (Join-Path ([Environment]::GetFolderPath(
        [Environment+SpecialFolder]::ProgramFiles)) "MoonWaker"),
    [int]$TimeoutSeconds = 20
)

# Stops every process that provably runs from the installation directory, in any account:
# executables inside it, and powershell/python interpreters whose script is inside it.
# Parents go first, so a supervisor cannot respawn what is stopped; their descendants
# follow. A process whose membership cannot be proven is never touched.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\') + "\"
$interpreters = @("powershell.exe", "pwsh.exe", "python.exe", "pythonw.exe")
$scriptPattern = [regex]'(?i)[A-Za-z]:\\[^"''<>|*?\r\n]*?\.(?:ps1|py)\b'

function Test-InstalledProcess($Process) {
    $exe = [string]$Process.ExecutablePath
    if ($exe.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    if ($interpreters -notcontains ([string]$Process.Name).ToLowerInvariant()) { return $false }
    # The first full-path script on the command line is the one the interpreter runs.
    $script = $scriptPattern.Match([string]$Process.CommandLine)
    return $script.Success -and
        $script.Value.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Get-ProcessKey($Process) { "{0}:{1:o}" -f $Process.ProcessId, $Process.CreationDate }

$known = @{}
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
while ($true) {
    try {
        $all = @(Get-CimInstance Win32_Process -Property Name, ProcessId, ParentProcessId,
            ExecutablePath, CommandLine, CreationDate)
    } catch {
        throw "Cannot verify which MoonWaker processes are running. Retry the installation."
    }
    $byId = @{}
    foreach ($process in $all) { $byId[[int]$process.ProcessId] = $process }

    # This script and the processes that launched it are never stopped.
    $protected = @{}
    for ($id = $PID; $byId.ContainsKey($id) -and -not $protected.ContainsKey($id);
            $id = [int]$byId[$id].ParentProcessId) {
        $protected[$id] = $true
    }
    $protected[$PID] = $true

    # Provable members, plus descendants of members (recorded: their parent may die first).
    $owned = @{}
    foreach ($process in $all) {
        if (-not $protected.ContainsKey([int]$process.ProcessId) -and
            ((Test-InstalledProcess $process) -or $known.ContainsKey((Get-ProcessKey $process)))) {
            $owned[[int]$process.ProcessId] = $process
        }
    }
    $grew = $true
    while ($grew) {
        $grew = $false
        foreach ($process in $all) {
            $id = [int]$process.ProcessId
            if ($owned.ContainsKey($id) -or $protected.ContainsKey($id)) { continue }
            $parentId = [int]$process.ParentProcessId
            # A reused PID has a parent that is younger than the child.
            if ($owned.ContainsKey($parentId) -and $process.CreationDate -ge $owned[$parentId].CreationDate) {
                $owned[$id] = $process
                $grew = $true
            }
        }
    }
    foreach ($process in $owned.Values) { $known[(Get-ProcessKey $process)] = $true }

    if ($owned.Count -eq 0) { return }
    if ([DateTime]::UtcNow -ge $deadline) {
        $names = @($owned.Values | ForEach-Object { "$($_.Name) $($_.ProcessId)" })
        throw "MoonWaker processes did not stop in time: $($names -join ', ')."
    }

    Write-Host "Stopping MoonWaker processes that run from $prefix ..."
    $roots = @($owned.Values | Where-Object { -not $owned.ContainsKey([int]$_.ParentProcessId) })
    foreach ($process in $roots) {
        Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction SilentlyContinue
    }
    Wait-Process -Id @($roots | ForEach-Object { [int]$_.ProcessId }) -Timeout 5 `
        -ErrorAction SilentlyContinue
}
