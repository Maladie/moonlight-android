#requires -Version 5.1
$ErrorActionPreference = "Stop"
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot "MoonWakerProfileBridge.ps1") -Raw
$start = $source.IndexOf("function Should-Restart-Component")
$end = $source.IndexOf("`nfunction Restart-Component", $start)
if ($start -lt 0 -or $end -le $start) {
    throw "The health restart decision seam is missing."
}
Invoke-Expression $source.Substring($start, $end - $start)

$counts = @{}
if (Should-Restart-Component "vibepollo" $false $counts) {
    throw "A single Vibepollo health miss must not restart the component."
}
if ([int]$counts["vibepollo"] -ne 1) {
    throw "The first Vibepollo health miss was not recorded."
}
if (-not (Should-Restart-Component "vibepollo" $false $counts)) {
    throw "Two consecutive Vibepollo health misses must restart the component."
}
$healthyRestart = Should-Restart-Component "vibepollo" $true $counts
if ($healthyRestart -or [int]$counts["vibepollo"] -ne 0) {
    throw "A healthy Vibepollo probe must reset the failure count."
}
if (Should-Restart-Component "vibepollo" $false $counts) {
    throw "A post-recovery Vibepollo miss must start a new failure streak."
}
if (-not (Should-Restart-Component "game-provider" $false $counts)) {
    throw "Game Provider health remains an immediate restart signal."
}

$stateStart = $source.IndexOf("function Write-State")
$stateEnd = $source.IndexOf("`nfunction Start-Component", $stateStart)
if ($stateStart -lt 0 -or $stateEnd -le $stateStart) {
    throw "The state writer seam is missing."
}
Invoke-Expression $source.Substring($stateStart, $stateEnd - $stateStart)
$script:statePath = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-profile-state-" + [Guid]::NewGuid().ToString("N"))
$script:ProfileRoot = [IO.Path]::GetTempPath()
$script:ProfileId = "test-profile"
$script:children = @{}
$script:ownsMutex = $false
Write-State "refused"
if (Test-Path -LiteralPath $statePath) {
    throw "A non-owner wrote shared profile state."
}
$script:ownsMutex = $true
Write-State "running"
if (-not (Test-Path -LiteralPath $statePath)) {
    throw "The owning supervisor did not write profile state."
}
Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue

$cleanupStart = $source.IndexOf("function Complete-ProfileBridge")
$cleanupEnd = $source.IndexOf("`nif (-not (Test-ExpectedProfileRoot))", $cleanupStart)
if ($cleanupStart -lt 0 -or $cleanupEnd -le $cleanupStart) {
    throw "The profile cleanup ownership seam is missing."
}
$script:ownsMutex = $false
$script:actions = [Collections.Generic.List[string]]::new()
$script:stopPath = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-profile-stop-" + [Guid]::NewGuid().ToString("N"))
$script:manualStopPath = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-profile-manual-stop-" + [Guid]::NewGuid().ToString("N"))
$script:ProfileId = "test-profile"
function Write-State([string]$Status) { $script:actions.Add("state:$Status") }
function Stop-Components { $script:actions.Add("components") }
function Write-AgentDiagnosticEvent([string]$Event, [hashtable]$Fields = @{}, [Exception]$Exception = $null) {
    $script:actions.Add("diagnostic:$Event")
}
Invoke-Expression $source.Substring($cleanupStart, $cleanupEnd - $cleanupStart)
Complete-ProfileBridge
if ($actions.Count -ne 0) {
    throw "A duplicate supervisor performed cleanup: $($actions -join ', ')"
}
New-Item -ItemType File -Path $stopPath -Force | Out-Null
$script:ownsMutex = $true
Complete-ProfileBridge
if ($actions -notcontains "components" -or
    ($actions | Where-Object { $_ -like "state:*" }).Count -ne 2 -or
    $actions -notcontains "diagnostic:supervisor.stopped" -or
    (Test-Path -LiteralPath $stopPath)) {
    throw "The owning supervisor cleanup did not complete exactly once."
}
Remove-Item -LiteralPath $stopPath,$manualStopPath -Force -ErrorAction SilentlyContinue
Write-Output "MoonWakerProfileBridge health restart test passed."
