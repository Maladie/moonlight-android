#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$GatewayDirectory = $PSScriptRoot,
    [int]$TimeoutSeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($TimeoutSeconds -lt 1) {
    throw "TimeoutSeconds must be at least 1."
}

$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory)
$workerNames = @(
    "MoonWakerMicrophoneWorker",
    "MoonWakerDiscordAudioWorker"
)
$workerPaths = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::OrdinalIgnoreCase)
foreach ($name in $workerNames) {
    [void]$workerPaths.Add([IO.Path]::GetFullPath((Join-Path $GatewayDirectory "$name.exe")))
}

function Get-MoonWakerGatewayWorkerProcesses {
    $matches = [Collections.Generic.List[Diagnostics.Process]]::new()
    foreach ($process in @(Get-Process -Name $workerNames -ErrorAction SilentlyContinue)) {
        try {
            $executablePath = [IO.Path]::GetFullPath($process.MainModule.FileName)
            if ($workerPaths.Contains($executablePath)) {
                $matches.Add($process)
            } else {
                $process.Dispose()
            }
        } catch {
            # Never stop a same-named process when its executable path cannot
            # be proven to belong to this Gateway installation.
            $process.Dispose()
        }
    }
    return $matches.ToArray()
}

$workers = @(Get-MoonWakerGatewayWorkerProcesses)
if ($workers.Count -eq 0) { return }

Write-Host "Stopping MoonWaker Gateway workers before replacing their files..."
foreach ($process in $workers) {
    try {
        Stop-Process -Id $process.Id -Force -ErrorAction Stop
    } catch {
        if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) { throw }
    } finally {
        $process.Dispose()
    }
}

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $remaining = @(Get-MoonWakerGatewayWorkerProcesses)
    if ($remaining.Count -eq 0) { return }
    $remainingIds = @($remaining | ForEach-Object { $_.Id })
    $remaining | ForEach-Object { $_.Dispose() }
    Start-Sleep -Milliseconds 100
} while ([DateTime]::UtcNow -lt $deadline)

throw "MoonWaker Gateway worker PID $($remainingIds -join ', ') did not stop in time."
