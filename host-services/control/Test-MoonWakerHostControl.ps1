#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Join-Path ([IO.Path]::GetTempPath()) ("MoonWakerHostControlTest-" + [guid]::NewGuid().ToString("N"))
$control = Join-Path $root "control"
$gateway = Join-Path $root "gateway"
$profile = Join-Path $root "profiles\default"
$resultPath = Join-Path $root "result.json"
$diagnosticPath = Join-Path $root "steam-web-api-configure.log"
$plain = "0" * 32

try {
    New-Item -ItemType Directory -Path $control, $gateway, (Join-Path $profile "playnite") -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "Invoke-MoonWakerHostControl.ps1") `
        -Destination (Join-Path $control "Invoke-MoonWakerHostControl.ps1")
    [ordered]@{
        profiles = [ordered]@{
            default = [ordered]@{
                name = "Diagnostic test"
                owner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
            }
        }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gateway "gateway.json") -Encoding UTF8

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
    Write-Output "Host Control Steam configuration diagnostic test passed."
} finally {
    Remove-Item Env:MOONWAKER_STEAM_WEB_API_PROTECTED -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
