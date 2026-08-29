#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Join-Path ([IO.Path]::GetTempPath()) ("MoonWakerHostControlTest-" + [guid]::NewGuid().ToString("N"))
$control = Join-Path $root "control"
$gateway = Join-Path $root "gateway"
$profile = Join-Path $root "profiles\default"
$foreignProfile = Join-Path $root "profiles\foreign"
$resultPath = Join-Path $root "result.json"
$diagnosticPath = Join-Path $root "steam-web-api-configure.log"
$plain = "0" * 32
$listenerProcess = $null

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

try {
    New-Item -ItemType Directory -Path $control, $gateway, (Join-Path $profile "playnite"), `
        $foreignProfile -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "Invoke-MoonWakerHostControl.ps1") `
        -Destination (Join-Path $control "Invoke-MoonWakerHostControl.ps1")
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) `
        "gateway\Stop-MoonWakerGateway.ps1") -Destination $gateway
    $gatewayPort = Get-FreeTcpPort
    [ordered]@{
        listen_port = $gatewayPort
        clients = @()
        profiles = [ordered]@{
            default = [ordered]@{
                name = "Diagnostic test"
                owner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
                profile_root = $profile
                discord_bridge = ""
                vibepollo_bridge = ""
                playnite_bridge = ""
            }
            foreign = [ordered]@{
                name = "Foreign profile"
                owner = "OTHER\User"
                profile_root = $foreignProfile
                discord_bridge = ""
                vibepollo_bridge = ""
                playnite_bridge = ""
            }
        }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gateway "gateway.json") -Encoding UTF8
    [ordered]@{ version = "test"; build = "test"; protocol_version = 1 } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "version.json") -Encoding UTF8
    New-Item -ItemType File -Path (Join-Path $gateway "gateway-manually-stopped") -Force | Out-Null

    Add-Type -AssemblyName System.Security
    $plainBytes = [Text.Encoding]::Unicode.GetBytes($plain)
    $protectedBytes = [Security.Cryptography.ProtectedData]::Protect(
        $plainBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try {
        $env:MOONWAKER_STEAM_WEB_API_PROTECTED =
            ($protectedBytes | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        [Array]::Clear($protectedBytes, 0, $protectedBytes.Length)
    }
    $scriptPath = Join-Path $control "Invoke-MoonWakerHostControl.ps1"
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Action ConfigureSteamWebApi ' +
        '-ProfileId default -SteamWebApiKeyProtectedFromEnvironment ' +
        '-SteamWebApiDiagnosticPath "{1}" -ResultPath "{2}"'
    $arguments = $arguments -f $scriptPath, $diagnosticPath, $resultPath
    $startInfo = [Diagnostics.ProcessStartInfo]::new("powershell.exe", $arguments)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($startInfo)
    if (-not $process.WaitForExit(5000)) {
        try { $process.Kill() } catch {}
        throw "Steam configuration timed out."
    }

    $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if (-not $result.ok -or -not $result.connected) {
        $diagnostic = Get-Content -LiteralPath $diagnosticPath -Raw -ErrorAction SilentlyContinue
        throw "Steam configuration did not complete: $($result.error). Diagnostic phases: $diagnostic"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $profile "playnite\steam-web-api-key.dpapi") -PathType Leaf)) {
        throw "The DPAPI-protected key file was not created."
    }
    $diagnostic = Get-Content -LiteralPath $diagnosticPath -Raw
    foreach ($phase in @("start", "profile_resolved", "owner_verified", "protected_value_received",
            "dpapi_decrypted", "key_validated", "directory_ready", "secret_written",
            "secret_verified", "completed")) {
        if ($diagnostic -notmatch ("phase=" + [regex]::Escape($phase) + "(?:\r?\n|$)")) {
            throw "Missing diagnostic phase '$phase'."
        }
    }
    if ($diagnostic.Contains($plain) -or
        $diagnostic.Contains($env:MOONWAKER_STEAM_WEB_API_PROTECTED)) {
        throw "The diagnostic log contains secret material."
    }

    & $scriptPath -Action Status -ResultPath $resultPath | Out-Null
    $status = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    $current = @($status.profiles | Where-Object id -eq "default")[0]
    $foreign = @($status.profiles | Where-Object id -eq "foreign")[0]
    if (-not $current.current_user -or $foreign.current_user) {
        throw "Host Control did not correlate profiles with their Windows owner."
    }

    $listenerScript = Join-Path $root "listener.ps1"
    @'
param([int]$Port)
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
try { $listener.Start(); while ($true) { Start-Sleep -Seconds 1 } } finally { $listener.Stop() }
'@ | Set-Content -LiteralPath $listenerScript -Encoding UTF8
    Remove-Item -LiteralPath (Join-Path $gateway "gateway-manually-stopped") -Force
    $listenerProcess = Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -PassThru `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $listenerScript, "-Port", $gatewayPort)
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    while ([DateTime]::UtcNow -lt $deadline -and
        -not (Get-NetTCPConnection -State Listen -LocalPort $gatewayPort -ErrorAction SilentlyContinue)) {
        Start-Sleep -Milliseconds 100
    }
    [ordered]@{
        pid = $listenerProcess.Id
        started_at = [DateTimeOffset]::new($listenerProcess.StartTime).ToUnixTimeSeconds()
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $gateway "gateway-runtime.json") -Encoding UTF8
    & $scriptPath -Action StopGateway -ResultPath $resultPath | Out-Null
    $stopResult = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if (-not $stopResult.ok -or (Get-Process -Id $listenerProcess.Id -ErrorAction SilentlyContinue)) {
        $remainingOwners = @(Get-NetTCPConnection -State Listen -LocalPort $gatewayPort `
            -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
        $netstat = @(& netstat.exe -ano |
            Select-String ":$gatewayPort\s+\S+\s+LISTENING") -join "; "
        throw "StopGateway did not stop the correlated listener: ok=$($stopResult.ok); error=$($stopResult.error); port=$gatewayPort; listener_pid=$($listenerProcess.Id); listener_exited=$($listenerProcess.HasExited); remaining_owners=$($remainingOwners -join ','); netstat=$netstat"
    }
    $listenerProcess = $null

    Remove-Item -LiteralPath (Join-Path $gateway "gateway-runtime.json"), `
        (Join-Path $gateway "gateway-manually-stopped"), `
        (Join-Path $gateway "gateway-supervisor-stop") -Force -ErrorAction SilentlyContinue
    $listenerProcess = Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -PassThru `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $listenerScript, "-Port", $gatewayPort)
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    while ([DateTime]::UtcNow -lt $deadline -and
        -not (Get-NetTCPConnection -State Listen -LocalPort $gatewayPort -ErrorAction SilentlyContinue)) {
        Start-Sleep -Milliseconds 100
    }
    & $scriptPath -Action StopGateway -ResultPath $resultPath | Out-Null
    $uncorrelatedResult = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if ($uncorrelatedResult.ok -or -not (Get-Process -Id $listenerProcess.Id -ErrorAction SilentlyContinue)) {
        throw "StopGateway did not reject an uncorrelated listener."
    }
    Stop-Process -Id $listenerProcess.Id -Force
    $listenerProcess = $null
    Write-Output "Host Control Steam configuration diagnostic test passed."
    Write-Output "Host Control ownership and correlated Gateway stop tests passed."
} finally {
    if ($listenerProcess -and -not $listenerProcess.HasExited) {
        Stop-Process -Id $listenerProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item Env:MOONWAKER_STEAM_WEB_API_PROTECTED -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
