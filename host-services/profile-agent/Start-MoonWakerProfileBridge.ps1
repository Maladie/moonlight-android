#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProfileRoot = $PSScriptRoot,
    [string]$ProfileId = (Split-Path -Leaf $PSScriptRoot)
)
$ErrorActionPreference = "Stop"
$ProfileRoot = [IO.Path]::GetFullPath($ProfileRoot)
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
try {
    $installRoot = Split-Path -Parent (Split-Path -Parent $ProfileRoot)
    $gateway = Get-Content -LiteralPath (Join-Path $installRoot "gateway\gateway.json") -Raw | ConvertFrom-Json
    $expectedOwner = [string]$gateway.profiles.$ProfileId.owner
    if (-not [string]::IsNullOrWhiteSpace($expectedOwner) -and
        -not $expectedOwner.Equals($currentUser, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Profile '$ProfileId' belongs to $expectedOwner and cannot run under $currentUser."
    }
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
$statePath = Join-Path $ProfileRoot "profile-bridge-state.json"
$manualStopPath = Join-Path $ProfileRoot "profile-bridge-manually-stopped"
Remove-Item -LiteralPath $manualStopPath -Force -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $statePath) {
    try {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $existing = Get-Process -Id ([int]$state.supervisor_pid) -ErrorAction SilentlyContinue
        $commandLine = [string](Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$state.supervisor_pid)" `
            -ErrorAction SilentlyContinue).CommandLine
        if ($existing -and -not $existing.HasExited -and $commandLine -like "*MoonWakerProfileBridge.ps1*" -and
            $commandLine -like "*$ProfileRoot*") { Write-Host "Profile Bridge is already running."; return }
    } catch {}
}
$agent = Join-Path $ProfileRoot "MoonWakerProfileBridge.ps1"
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = "powershell.exe"
$info.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -ProfileRoot "{1}" -ProfileId "{2}"' -f `
    $agent.Replace('"', '\"'), $ProfileRoot.Replace('"', '\"'), $ProfileId.Replace('"', '\"')
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
$process = [Diagnostics.Process]::Start($info)
Write-Host "Profile Bridge started (PID $($process.Id))."
