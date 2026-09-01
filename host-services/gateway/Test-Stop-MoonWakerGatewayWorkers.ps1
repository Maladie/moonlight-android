#requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler)) {
    throw "The .NET Framework C# compiler is required for this test."
}

$tempDirectory = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) (
    "moonwaker-worker-stop-test-" + [Guid]::NewGuid().ToString("N"))))
$managedDirectory = Join-Path $tempDirectory "managed"
$foreignDirectory = Join-Path $tempDirectory "foreign"
$sourcePath = Join-Path $tempDirectory "Sleeper.cs"
$seedExecutable = Join-Path $tempDirectory "Sleeper.exe"
$managedExecutable = Join-Path $managedDirectory "MoonWakerDiscordAudioWorker.exe"
$foreignExecutable = Join-Path $foreignDirectory "MoonWakerDiscordAudioWorker.exe"
$managedProcess = $null
$foreignProcess = $null

try {
    New-Item -ItemType Directory -Path $managedDirectory, $foreignDirectory -Force | Out-Null
    @"
using System.Threading;
internal static class Program
{
    private static void Main() { Thread.Sleep(60000); }
}
"@ | Set-Content -LiteralPath $sourcePath -Encoding UTF8

    & $compiler /nologo /target:winexe "/out:$seedExecutable" $sourcePath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $seedExecutable)) {
        throw "Unable to compile the worker test process."
    }
    Copy-Item -LiteralPath $seedExecutable -Destination $managedExecutable
    Copy-Item -LiteralPath $seedExecutable -Destination $foreignExecutable

    $managedProcess = Start-Process -FilePath $managedExecutable -WindowStyle Hidden -PassThru
    $foreignProcess = Start-Process -FilePath $foreignExecutable -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 300
    if ($managedProcess.HasExited -or $foreignProcess.HasExited) {
        throw "A worker test process exited before the stop test ran."
    }

    & (Join-Path $PSScriptRoot "Stop-MoonWakerGatewayWorkers.ps1") `
        -GatewayDirectory $managedDirectory -TimeoutSeconds 5
    $managedProcess.Refresh()
    $foreignProcess.Refresh()
    if (-not $managedProcess.HasExited) {
        throw "The worker from the updated Gateway directory was not stopped."
    }
    if ($foreignProcess.HasExited) {
        throw "A same-named worker from another directory was stopped."
    }

    Write-Host "Gateway worker update stop test passed." -ForegroundColor Green
} finally {
    foreach ($process in @($managedProcess, $foreignProcess)) {
        if ($null -ne $process) {
            try {
                if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
            } catch {}
            $process.Dispose()
        }
    }
    $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $isTestPath = $tempDirectory.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $tempDirectory) -like "moonwaker-worker-stop-test-*"
    if (-not $isTestPath) {
        throw "Refusing to remove an unexpected test directory: $tempDirectory"
    }
    Remove-Item -LiteralPath $tempDirectory -Recurse -Force -ErrorAction SilentlyContinue
}
