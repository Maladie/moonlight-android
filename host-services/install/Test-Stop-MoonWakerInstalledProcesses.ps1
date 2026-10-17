#requires -Version 5.1
[CmdletBinding()]
param()

# Simulates an update on a temporary directory: a live supervisor that respawns a child
# holding a port (the pre-rebuild Agent shape) must be stopped so a new listener can bind,
# while same-named or look-alike processes outside the directory must survive.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) { throw "The .NET Framework C# compiler is required for this test." }
$powershell = Join-Path $PSHOME "powershell.exe"

$temp = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) (
    "moonwaker-install-stop-test-" + [Guid]::NewGuid().ToString("N"))))
$root = Join-Path $temp "MoonWaker"
$profileDir = Join-Path $root "profiles\p1"
$foreign = Join-Path $temp "foreign"
New-Item -ItemType Directory -Path $profileDir, $foreign -Force | Out-Null
$started = [Collections.Generic.List[Diagnostics.Process]]::new()

function Start-Hidden([string]$File, [string[]]$Arguments) {
    $process = if ($Arguments.Count) {
        Start-Process -FilePath $File -ArgumentList $Arguments -WindowStyle Hidden -PassThru
    } else { Start-Process -FilePath $File -WindowStyle Hidden -PassThru }
    $started.Add($process)
    return $process
}

try {
    $port = ([Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0))
    $port.Start(); $number = $port.LocalEndpoint.Port; $port.Stop()

    $child = Join-Path $profileDir "GameProviderBridge.ps1"
    $supervisor = Join-Path $profileDir "MoonWakerProfileBridge.ps1"
    "`$l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $number); `$l.Start(); Start-Sleep 120" |
        Set-Content -LiteralPath $child -Encoding UTF8
    "while (`$true) { `$p = Start-Process '$powershell' -WindowStyle Hidden -PassThru -ArgumentList '-NoProfile','-File','$child'; `$p.WaitForExit(); Start-Sleep -Milliseconds 300 }" |
        Set-Content -LiteralPath $supervisor -Encoding UTF8

    $sleeperSource = Join-Path $temp "Sleeper.cs"
    "static class P { static void Main() { System.Threading.Thread.Sleep(120000); } }" |
        Set-Content -LiteralPath $sleeperSource -Encoding UTF8
    $sleeper = Join-Path $root "Sleeper.exe"
    & $compiler /nologo /target:winexe "/out:$sleeper" $sleeperSource | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to compile the test process." }
    $foreignSleeper = Join-Path $foreign "Sleeper.exe"
    Copy-Item -LiteralPath $sleeper -Destination $foreignSleeper

    $foreignScript = Join-Path $foreign "Other.ps1"
    "Start-Sleep 120" | Set-Content -LiteralPath $foreignScript -Encoding UTF8
    $ownedScript = Join-Path $root "decoy.ps1"
    "Start-Sleep 120" | Set-Content -LiteralPath $ownedScript -Encoding UTF8

    $ownedSupervisor = Start-Hidden $powershell @("-NoProfile", "-File", $supervisor)
    $ownedExe = Start-Hidden $sleeper @()
    $foreignExe = Start-Hidden $foreignSleeper @()
    $foreignPs = Start-Hidden $powershell @("-NoProfile", "-File", $foreignScript)
    # A foreign script that merely names an installation script as an argument.
    $foreignArg = Start-Hidden $powershell @("-NoProfile", "-File", $foreignScript, $ownedScript)

    # Wait until the supervisor's child holds the port.
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ($true) {
        try {
            $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $number)
            $probe.Start(); $probe.Stop()
        } catch { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw "The simulated Agent never took the port." }
        Start-Sleep -Milliseconds 200
    }

    & (Join-Path $PSScriptRoot "Stop-MoonWakerInstalledProcesses.ps1") -Root $root -TimeoutSeconds 20

    foreach ($process in @($ownedSupervisor, $ownedExe)) {
        $process.Refresh()
        if (-not $process.HasExited) { throw "Process $($process.Id) from the installation was not stopped." }
    }
    # The new Agent can bind: neither the child nor a respawn holds the port.
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $number)
    $listener.Start(); $listener.Stop()
    foreach ($process in @($foreignExe, $foreignPs, $foreignArg)) {
        $process.Refresh()
        if ($process.HasExited) { throw "Process $($process.Id) from outside the installation was stopped." }
    }
    Write-Host "Installed process stop test passed." -ForegroundColor Green
} finally {
    foreach ($process in $started) {
        try { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force } } catch {}
        $process.Dispose()
    }
    # Children of the simulated supervisor, if the test failed early.
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { [string]$_.CommandLine -like "*$temp*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($temp.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $temp) -like "moonwaker-install-stop-test-*") {
        Start-Sleep -Milliseconds 500
        Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
