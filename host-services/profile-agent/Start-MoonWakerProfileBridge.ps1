#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProfileRoot = $PSScriptRoot,
    [string]$ProfileId = (Split-Path -Leaf $PSScriptRoot)
)
$ErrorActionPreference = "Stop"
$ProfileRoot = [IO.Path]::GetFullPath($ProfileRoot)
$currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$currentUser = $currentIdentity.Name
$currentSid = if ($null -ne $currentIdentity.User) { $currentIdentity.User.Value } else { "" }
try {
    $installRoot = Split-Path -Parent (Split-Path -Parent $ProfileRoot)
    $expectedRoot = [IO.Path]::GetFullPath((Join-Path $installRoot "profiles\$ProfileId")).TrimEnd('\')
    if (-not $ProfileRoot.TrimEnd('\').Equals($expectedRoot,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw "Profile root must be exactly '<ProfilesRoot>\<ProfileId>'."
    }
    $cursor = [IO.DirectoryInfo]::new($expectedRoot)
    while ($null -ne $cursor) {
        if ($cursor.Exists -and ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Profile path contains a reparse point."
        }
        $cursor = $cursor.Parent
    }
    $gateway = Get-Content -LiteralPath (Join-Path $installRoot "gateway\gateway.json") -Raw | ConvertFrom-Json
    $profile = $gateway.profiles.$ProfileId
    if ($null -eq $profile) { throw "Profile '$ProfileId' is not registered." }
    if ($null -ne $profile.PSObject.Properties["deletion_tombstone"] -and
        $null -ne $profile.deletion_tombstone) {
        throw "Profile '$ProfileId' is awaiting deletion."
    }
    $enabledProperty = $profile.PSObject.Properties["enabled"]
    $enabled = if ($null -ne $enabledProperty) { $enabledProperty.Value } else { $null }
    if ($enabled -isnot [bool] -or $enabled -ne $true) {
        throw "Profile '$ProfileId' is disabled or has no authoritative enabled state."
    }
    $expectedSid = if ($null -ne $profile.PSObject.Properties["windows_account_sid"]) {
        [string]$profile.windows_account_sid
    } elseif ($null -ne $profile.PSObject.Properties["owner_sid"]) {
        [string]$profile.owner_sid
    } else { "" }
    $registeredRoot = if ($null -ne $profile.PSObject.Properties["profile_root"]) {
        [string]$profile.profile_root
    } else { "" }
    if ([string]::IsNullOrWhiteSpace($registeredRoot) -or
        -not [IO.Path]::GetFullPath($registeredRoot).TrimEnd('\').Equals(
            $expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Registered profile root does not match the protected profile directory."
    }
    $expectedOwner = if ($null -ne $profile.PSObject.Properties["windows_account_name"]) {
        [string]$profile.windows_account_name
    } else { [string]$profile.owner }
    if ([string]::IsNullOrWhiteSpace($currentSid) -or
        [string]::IsNullOrWhiteSpace($expectedSid) -or
        -not $expectedSid.Equals($currentSid, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Profile '$ProfileId' belongs to SID '$expectedSid' ($expectedOwner) and cannot run under SID '$currentSid' ($currentUser)."
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
