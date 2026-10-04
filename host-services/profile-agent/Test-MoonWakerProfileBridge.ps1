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
if (Should-Restart-Component "game-provider" $false $counts) {
    throw "A single Game Provider health miss must not interrupt an active launch."
}
if (-not (Should-Restart-Component "game-provider" $false $counts)) {
    throw "Two consecutive Game Provider health misses must restart the component."
}
$healthyRestart = Should-Restart-Component "game-provider" $true $counts
if ($healthyRestart -or [int]$counts["game-provider"] -ne 0) {
    throw "A healthy Game Provider probe must reset its failure streak."
}
if (Should-Restart-Component "game-provider" $false $counts) {
    throw "A post-recovery Game Provider miss must start a new failure streak."
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
function Set-Content {
    param([string]$LiteralPath, [string]$Encoding, [Parameter(ValueFromPipeline)][string]$Value)
    process {
        $script:writeAttempts++
        if ($script:writeAttempts -eq 1) { throw [IO.IOException]::new("transient sharing violation") }
        Microsoft.PowerShell.Management\Set-Content -LiteralPath $LiteralPath -Encoding $Encoding -Value $Value
    }
}
$script:writeAttempts = 0
Write-State "running"
Remove-Item Function:\Set-Content
if ($writeAttempts -ne 2) { throw "The state writer did not retry a transient failure." }
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

if ($source -notmatch '\$Name -eq "game-provider"\) \{ "/observation" \}' -or
    $source -notmatch '\$status\.producer\.process_id' -or
    $source -match 'legacyBridgeIdentity|connector_connected') {
    throw "The supervisor does not use the canonical Game Provider observation identity."
}
$stopSource = Get-Content -LiteralPath `
    (Join-Path $PSScriptRoot "Stop-MoonWakerProfileBridge.ps1") -Raw
if ($stopSource -notmatch '/observation' -or $stopSource -match '/health|connector_connected|PlayniteBridge\.py') {
    throw "The profile stop path still uses a legacy Game Provider contract."
}
$deploySource = Get-Content -LiteralPath `
    (Join-Path (Split-Path -Parent $PSScriptRoot) "bridges\playnite\Deploy-PlayniteBridge.ps1") -Raw
if ($deploySource -notmatch '/observation' -or
    $deploySource -notmatch 'producer\.process_id' -or
    $deploySource -notmatch 'connector_evidence' -or
    $deploySource -match '/health|connector_connected|PlayniteBridge\.py|removeLegacyBridge') {
    throw "The Game Provider deploy path still uses a legacy health contract."
}
$authorityStart = $source.IndexOf("function Test-ExpectedProfileRoot")
$authorityEnd = $source.IndexOf("`nfunction Initialize-AgentDiagnostics", $authorityStart)
if ($authorityStart -lt 0 -or $authorityEnd -le $authorityStart -or
    $source -notmatch 'FileShare\]::ReadWrite -bor \[IO\.FileShare\]::Delete') {
    throw "The profile authority seam or its delete-sharing registry read is missing."
}
Invoke-Expression $source.Substring($authorityStart, $authorityEnd - $authorityStart)
$authorityRoot = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-authority-" + [Guid]::NewGuid().ToString("N"))
$script:ProfileId = "authority-profile"
$script:ProfileRoot = Join-Path $authorityRoot "profiles\$ProfileId"
New-Item -ItemType Directory -Path $ProfileRoot, (Join-Path $authorityRoot "gateway") -Force | Out-Null
$registryPath = Join-Path $authorityRoot "gateway\gateway.json"
function Write-Registry([bool]$Enabled) {
    @{ profiles = @{ $ProfileId = @{ profile_root = $ProfileRoot; enabled = $Enabled
        windows_account_sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value } } } |
        ConvertTo-Json -Depth 5 | Microsoft.PowerShell.Management\Set-Content -LiteralPath $registryPath -Encoding UTF8
}
try {
    Write-Registry $true
    if ((Get-ProfileAuthority) -ne "authorized") { throw "A valid registry did not authorize the profile." }
    $streak = @{ count = 0 }
    $held = [IO.File]::Open($registryPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        if ((Get-ProfileAuthority) -ne "unreadable") { throw "A locked registry was not reported unreadable." }
        foreach ($tick in 1..4) {
            if (Test-ProfileAuthorityLost "unreadable" $streak) {
                throw "A transient unreadable registry revoked the profile authority."
            }
        }
    } finally { $held.Dispose() }
    if ((Test-ProfileAuthorityLost (Get-ProfileAuthority) $streak) -or $streak.count -ne 0) {
        throw "A readable registry did not end the unreadable streak."
    }
    foreach ($tick in 1..4) { [void](Test-ProfileAuthorityLost "unreadable" $streak) }
    if (-not (Test-ProfileAuthorityLost "unreadable" $streak)) {
        throw "A registry unreadable for five ticks did not fail closed."
    }
    Microsoft.PowerShell.Management\Set-Content -LiteralPath $registryPath -Value "{" -Encoding UTF8
    if ((Get-ProfileAuthority) -ne "unreadable") { throw "A partial registry was not reported unreadable." }
    [IO.File]::WriteAllText($registryPath, "")
    $emptyStreak = @{ count = 0 }
    foreach ($tick in 1..4) {
        if (Test-ProfileAuthorityLost (Get-ProfileAuthority) $emptyStreak) {
            throw "An empty registry read revoked the profile authority before the bound."
        }
    }
    if (-not (Test-ProfileAuthorityLost (Get-ProfileAuthority) $emptyStreak)) {
        throw "An empty registry for five ticks did not fail closed."
    }
    Write-Registry $false
    if (-not (Test-ProfileAuthorityLost (Get-ProfileAuthority) @{ count = 0 })) {
        throw "A disabled profile did not revoke authority at once."
    }
} finally {
    Remove-Item -LiteralPath $authorityRoot -Recurse -Force -ErrorAction SilentlyContinue
}
$diagnosticStart = $source.IndexOf("function Write-AgentDiagnosticEvent")
$diagnosticEnd = $source.IndexOf("`nfunction Start-HiddenProcess", $diagnosticStart)
Invoke-Expression $source.Substring($diagnosticStart, $diagnosticEnd - $diagnosticStart)
$diagnosticPath = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-error-location-" + [Guid]::NewGuid().ToString("N"))
$diagnosticClock = [Diagnostics.Stopwatch]::StartNew()
$diagnosticRunId = "test-diagnostic"
$diagnosticMaxBytes = 2MB
try {
    try { throw [ArgumentException]::new("Bearer secret at C:/private/config.json") }
    catch {
        Write-AgentDiagnosticEvent "supervisor.loop_failed" @{
            profile_id = "test-profile"; status = "failed"; stage = "state_write"
            error_line = $_.InvocationInfo.ScriptLineNumber
        } $_.Exception
    }
    Write-AgentDiagnosticEvent "supervisor.loop_failed" @{
        stage = "token-shaped-secret"; error_line = "C:/private/config.json"
    }
    $entries = @(Get-Content -LiteralPath $diagnosticPath | ForEach-Object { $_ | ConvertFrom-Json })
    if ($entries.Count -ne 2 -or $entries[0].stage -ne "state_write" -or
        $entries[0].error_line -le 0 -or $entries[0].error_type -ne "System.ArgumentException") {
        throw "Safe supervisor exception location was not recorded."
    }
    if ($null -ne $entries[1].PSObject.Properties["stage"] -or
        $null -ne $entries[1].PSObject.Properties["error_line"] -or
        (Get-Content -LiteralPath $diagnosticPath -Raw) -match 'secret|private|Bearer') {
        throw "Supervisor diagnostics leaked non-allowlisted exception data."
    }
} finally {
    Remove-Item -LiteralPath $diagnosticPath -Force -ErrorAction SilentlyContinue
}
Write-Output "MoonWakerProfileBridge health restart test passed."
