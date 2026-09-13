#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$PackagePath = "",
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$HostRoot,
    [switch]$RecoverOnly,
    [switch]$LeaveStopped,
    [switch]$FullInstallCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$HostRoot = [IO.Path]::GetFullPath($HostRoot).TrimEnd('\')
if ($RecoverOnly) {
    $PackagePath = ""
} elseif ([string]::IsNullOrWhiteSpace($PackagePath)) {
    throw "PackagePath is required unless RecoverOnly is specified."
} else {
    $PackagePath = [IO.Path]::GetFullPath($PackagePath)
}
$stateRoot = Join-Path $env:ProgramData "MoonWaker\HotUpdater"
$clientSidPath = Join-Path $stateRoot "client-sid.txt"
$statePath = Join-Path $stateRoot "state.json"
$highWaterPath = Join-Path $stateRoot "high-water.json"
$transactionPath = Join-Path $stateRoot "transaction.json"
$maximumTotalBytes = 64MB

function Assert-PlainDirectory([string]$Path) {
    $cursor = [IO.DirectoryInfo]::new($Path)
    while ($null -ne $cursor) {
        if ($cursor.Exists -and ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Hot update path contains a reparse point: $($cursor.FullName)"
        }
        $cursor = $cursor.Parent
    }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-FileMatches([string]$Path, [string]$Sha256, [long]$Size) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.Length -ne $Size -or (Get-Sha256 $Path) -ne $Sha256.ToLowerInvariant()) {
        throw "Hot-update payload changed after validation: $Path"
    }
}

function New-TransactionSecurity {
    $security = [Security.AccessControl.DirectorySecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    $admins = [Security.Principal.SecurityIdentifier]::new("S-1-5-32-544")
    $security.SetOwner($admins)
    $inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    foreach ($sid in @("S-1-5-18", "S-1-5-32-544")) {
        $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),
            [Security.AccessControl.FileSystemRights]::FullControl, $inheritance,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    return $security
}

function Set-ExactTransactionAcl([string]$Path) {
    [IO.Directory]::SetAccessControl($Path, (New-TransactionSecurity))
    $acl = [IO.Directory]::GetAccessControl($Path)
    $sids = @($acl.GetAccessRules($true, $true,
        [Security.Principal.SecurityIdentifier]) | ForEach-Object {
            $_.IdentityReference.Value })
    if (-not $acl.AreAccessRulesProtected -or
        $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne "S-1-5-32-544" -or
        @($sids | Where-Object { $_ -notin @("S-1-5-18", "S-1-5-32-544") }).Count -gt 0) {
        throw "Transaction directory ACL is not exact and protected: $Path"
    }
}

function New-ExactTransactionDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) { throw "Transaction path already exists: $Path" }
    [IO.DirectoryInfo]::new($Path).Create((New-TransactionSecurity))
    Assert-PlainDirectory $Path
    Set-ExactTransactionAcl $Path
}

function Test-RuntimeParentAcl([Security.AccessControl.DirectorySecurity]$Acl,
        [Security.Principal.SecurityIdentifier]$PinnedSid) {
    $trustedInstaller = "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464"
    $allowed = @("S-1-5-18", "S-1-5-32-544", $trustedInstaller, $PinnedSid.Value)
    if ($Acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $allowed) {
        return $false
    }
    $mutation = [Security.AccessControl.FileSystemRights]::Write -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    foreach ($rule in $Acl.GetAccessRules($true, $true,
            [Security.Principal.SecurityIdentifier])) {
        if (($rule.PropagationFlags -band
                [Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            $rule.IdentityReference.Value -notin $allowed -and
            ($rule.FileSystemRights -band $mutation) -ne 0) { return $false }
    }
    return $true
}

function Assert-SafeRuntimeParent([string]$Path,
        [Security.Principal.SecurityIdentifier]$PinnedSid) {
    Assert-PlainDirectory $Path
    if (-not (Test-RuntimeParentAcl (Get-Acl -LiteralPath $Path) $PinnedSid)) {
        throw "A SYSTEM runtime parent is mutable by an untrusted account; use the full installer: $Path"
    }
}

function Get-AclBase64([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    return [Convert]::ToBase64String($acl.GetSecurityDescriptorBinaryForm())
}

function Restore-DirectoryAcl([string]$Path, [string]$Base64) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetSecurityDescriptorBinaryForm([Convert]::FromBase64String($Base64))
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Get-VersionFloor([Version]$Installed, [object]$State, [object]$HighWater) {
    $floor = $Installed
    foreach ($candidate in @($State, $HighWater)) {
        try {
            if ($null -ne $candidate -and -not [string]::IsNullOrWhiteSpace([string]$candidate.version)) {
                $parsed = [Version][string]$candidate.version
                if ($parsed -gt $floor) { $floor = $parsed }
            }
        } catch {}
    }
    return $floor
}

function Test-AllowedPayloadPath([string]$Path) {
    return $Path -match '^(gateway/[A-Za-z0-9_.-]+\.py|bridges/(playnite/[A-Za-z0-9_.-]+\.py|vibepollo/[A-Za-z0-9_.-]+\.(ps1|psm1)|discord/[A-Za-z0-9_.-]+\.(ps1|psm1)))$'
}

function Set-StateAtomic([hashtable]$Value) {
    $temporary = $statePath + ".tmp"
    $backup = $statePath + ".bak"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 6),
        [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $statePath) {
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        [IO.File]::Replace($temporary, $statePath, $backup)
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    }
    else { [IO.File]::Move($temporary, $statePath) }
}

function Set-HighWaterAtomic([hashtable]$Value) {
    $temporary = $highWaterPath + ".tmp"
    $backup = $highWaterPath + ".bak"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 4),
        [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $highWaterPath) {
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        [IO.File]::Replace($temporary, $highWaterPath, $backup)
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    } else { [IO.File]::Move($temporary, $highWaterPath) }
}

function Set-TransactionAtomic([hashtable]$Value) {
    $temporary = $transactionPath + ".tmp"
    $backup = $transactionPath + ".bak"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 8),
        [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $transactionPath) {
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        [IO.File]::Replace($temporary, $transactionPath, $backup)
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    } else { [IO.File]::Move($temporary, $transactionPath) }
}

function Get-RuntimePlan {
    $service = Get-Service -Name "MoonWakerGateway" -ErrorAction SilentlyContinue
    $runningTasks = @(Get-ScheduledTask -ErrorAction Stop | Where-Object {
        $_.TaskName -match '^MoonWaker Profile Bridge \([A-Za-z0-9._-]{1,64}\)$'
    } | Where-Object { $_.State -eq "Running" } | ForEach-Object { [string]$_.TaskName })
    return @{ gateway_was_running = [bool]($service -and
            $service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped)
        tasks = $runningTasks }
}

function Start-Runtime([hashtable]$Plan) {
    if ([bool]$Plan.gateway_was_running) { Start-Service -Name "MoonWakerGateway" }
    foreach ($taskName in @($Plan.tasks)) {
        if ($taskName -notmatch '^MoonWaker Profile Bridge \([A-Za-z0-9._-]{1,64}\)$') {
            throw "Invalid saved profile task name."
        }
        Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
    }
}

function Invoke-StopSequence([hashtable]$Plan, [scriptblock]$StopGateway,
        [scriptblock]$StopRemainder, [scriptblock]$Restore) {
    try {
        if ([bool]$Plan.gateway_was_running) { & $StopGateway }
        & $StopRemainder
    } catch {
        try { & $Restore } catch {}
        throw
    }
}

function Stop-Runtime([hashtable]$Plan, [scriptblock]$AfterGatewayStop = {}) {
    Invoke-StopSequence $Plan {
        $service = Get-Service -Name "MoonWakerGateway" -ErrorAction Stop
        Stop-Service -Name "MoonWakerGateway" -Force
        $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds(15))
    } {
        & $AfterGatewayStop
        foreach ($taskName in @($Plan.tasks)) {
            Stop-ScheduledTask -TaskName $taskName -ErrorAction Stop
        }
        $known = '(wakeplay_gateway\.py|MoonWakerProfileBridge\.ps1|DiscordBridge\.ps1|VibepolloBridge\.ps1|GameProviderBridge\.py|PlayniteBridge\.py|MoonWakerDiscordAudioWorker\.exe|MoonWakerMicrophoneWorker\.exe)'
        $rootPattern = [regex]::Escape($HostRoot + '\')
        $processes = @(Get-CimInstance Win32_Process -Property ProcessId,CommandLine -ErrorAction Stop |
            Where-Object { [string]$_.CommandLine -match $rootPattern -and [string]$_.CommandLine -match $known })
        foreach ($process in $processes) {
            Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction SilentlyContinue
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $remaining = @(Get-CimInstance Win32_Process -Property ProcessId,CommandLine -ErrorAction Stop |
                Where-Object { [string]$_.CommandLine -match $rootPattern -and [string]$_.CommandLine -match $known })
            if ($remaining.Count -eq 0) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($remaining.Count -gt 0) { throw "MoonWaker runtime did not stop within 10 seconds." }
    } { Start-Runtime $Plan }
}

function Invoke-GatewayHealthRequest([string]$PythonPath, [string]$Uri,
        [int]$TimeoutMs = 5000) {
    if (-not (Test-Path -LiteralPath $PythonPath -PathType Leaf) -or
            [IO.Path]::GetFileName($PythonPath) -ne "python.exe" -or
            $TimeoutMs -lt 100 -or $TimeoutMs -gt 10000) {
        throw "Gateway listener is not owned by the expected Python runtime."
    }
    $code = "import ssl,sys,urllib.request; print(urllib.request.urlopen(sys.argv[1], context=ssl._create_unverified_context(), timeout=2).read().decode('utf-8'))"
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $PythonPath
    $startInfo.Arguments = "-I -S -c `"$code`" `"$Uri`""
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $worker = [Diagnostics.Process]::new()
    $worker.StartInfo = $startInfo
    try {
        [void]$worker.Start()
        $stdout = $worker.StandardOutput.ReadToEndAsync()
        $stderr = $worker.StandardError.ReadToEndAsync()
        if (-not $worker.WaitForExit($TimeoutMs)) {
            try { $worker.Kill() } catch {}
            [void]$worker.WaitForExit(2000)
            throw "Gateway HTTPS health request timed out."
        }
        $output = $stdout.GetAwaiter().GetResult()
        [void]$stderr.GetAwaiter().GetResult()
        if ($worker.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
            throw "Gateway HTTPS health request failed."
        }
        return $output.Trim()
    } finally {
        $worker.Dispose()
    }
}

function Test-EndpointHealth([hashtable]$Endpoint) {
    try {
        $owners = @(Get-NetTCPConnection -State Listen -LocalPort $Endpoint.port -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess -Unique)
        if ($owners.Count -ne 1) { return $false }
        $ownerPid = [int]$owners[0]
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction Stop
        $commandLine = [string]$process.CommandLine
        if ($null -eq $process -or
            $commandLine.IndexOf([string]$Endpoint.root, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
            $commandLine.IndexOf([string]$Endpoint.script, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            return $false
        }
        $uri = "$($Endpoint.scheme)://127.0.0.1:$($Endpoint.port)$($Endpoint.path)"
        $content = if ($Endpoint.component -eq "gateway") {
            $runtime = Get-Content -LiteralPath (Join-Path $HostRoot "gateway\gateway-runtime.json") -Raw |
                ConvertFrom-Json
            $service = Get-CimInstance Win32_Service -Filter "Name='MoonWakerGateway'" -ErrorAction Stop
            $serviceProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$service.ProcessId)" -ErrorAction Stop
            $pythonArgument = [regex]::Match([string]$service.PathName,
                '(?:^|\s)--python\s+(?:"(?<quoted>[^"]+)"|(?<plain>\S+))(?=\s|$)')
            if (-not $pythonArgument.Success) { return $false }
            $configuredPython = [IO.Path]::GetFullPath($(if ($pythonArgument.Groups['quoted'].Success) {
                $pythonArgument.Groups['quoted'].Value } else { $pythonArgument.Groups['plain'].Value }))
            $listenerPython = [IO.Path]::GetFullPath([string]$process.ExecutablePath)
            $trusted = [int]$runtime.pid -eq $ownerPid -and
                [string]$runtime.source_sha256 -eq (Get-Sha256 (Join-Path $HostRoot "gateway\wakeplay_gateway.py")) -and
                [string]$service.State -eq "Running" -and $null -ne $serviceProcess -and
                [IO.Path]::GetFullPath([string]$serviceProcess.ExecutablePath).Equals(
                    (Join-Path $HostRoot "gateway\MoonWakerGatewayService.exe"),
                    [StringComparison]::OrdinalIgnoreCase) -and
                $listenerPython.Equals($configuredPython, [StringComparison]::OrdinalIgnoreCase)
            if (-not $trusted) { return $false }
            Invoke-GatewayHealthRequest $configuredPython $uri
        } else {
            (Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 2).Content
        }
        if ($Endpoint.component -eq "gateway") {
            $body = $content | ConvertFrom-Json
            return $body.name -eq "Wake & Play Host Gateway" -and [int]$body.api_version -eq 1 -and
                [int]$body.pid -eq $ownerPid
        }
        if ($Endpoint.component -eq "game-provider") {
            $body = $content | ConvertFrom-Json
            return $body.ok -eq $true -and $body.component -eq "game-provider" -and
                [int]$body.pid -eq $ownerPid -and [string]$body.profile_id -eq $Endpoint.profile
        }
        if ($Endpoint.component -eq "vibepollo") {
            $body = $content | ConvertFrom-Json
            return $body.ok -eq $true -and [int]$body.port -eq [int]$Endpoint.port
        }
        return [string]$content -match '^ok\tconnected=(True|False)\tauthenticated=(True|False)\tpipe='
    } catch { return $false }
}

function Test-RuntimeHealthy([hashtable]$Plan) {
    $config = Get-Content -LiteralPath (Join-Path $HostRoot "gateway\gateway.json") -Raw |
        ConvertFrom-Json
    $endpoints = @()
    if ([bool]$Plan.gateway_was_running) {
        $endpoints += @{ component = "gateway"; scheme = "https"; port = [int]$config.listen_port;
            path = "/api/v1/hello"; root = (Join-Path $HostRoot "gateway"); script = "wakeplay_gateway.py" }
    }
    $activeProfileIds = @($Plan.tasks | ForEach-Object {
        if ($_ -match '^MoonWaker Profile Bridge \(([A-Za-z0-9._-]{1,64})\)$') { $Matches[1] }
    })
    foreach ($profile in @($config.profiles.PSObject.Properties | Where-Object {
            $_.Name -in $activeProfileIds })) {
        foreach ($property in @("discord_bridge", "vibepollo_bridge", "game_provider_bridge", "playnite_bridge")) {
            $endpoint = $profile.Value.PSObject.Properties[$property]
            if ($null -eq $endpoint -or [string]::IsNullOrWhiteSpace([string]$endpoint.Value)) { continue }
            $uri = $null
            if (-not ([uri]::TryCreate([string]$endpoint.Value, [UriKind]::Absolute, [ref]$uri) -and
                $uri.Host -in @("127.0.0.1", "localhost"))) { return $false }
            $component = if ($property -in @("game_provider_bridge", "playnite_bridge")) {
                "game-provider" } elseif ($property -eq "vibepollo_bridge") { "vibepollo" } else { "discord" }
            $directory = if ($component -eq "game-provider") { "game-provider" } else { $component }
            $script = if ($component -eq "game-provider") { "GameProviderBridge.py" } `
                elseif ($component -eq "vibepollo") { "VibepolloBridge.ps1" } else { "DiscordBridge.ps1" }
            $endpoints += @{ component = $component; scheme = "http"; port = $uri.Port;
                path = "/health"; profile = $profile.Name;
                root = (Join-Path (Join-Path $HostRoot "profiles\$($profile.Name)") $directory); script = $script }
        }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $allReady = $true
        foreach ($taskName in @($Plan.tasks)) {
            if ((Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue).State -ne "Running") {
                $allReady = $false
            }
        }
        foreach ($endpoint in $endpoints) { if (-not (Test-EndpointHealth $endpoint)) { $allReady = $false } }
        if ($allReady) { return $true }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function Rollback-Switches([Collections.ArrayList]$Switches) {
    for ($index = $Switches.Count - 1; $index -ge 0; $index--) {
        $item = $Switches[$index]
        if (Test-Path -LiteralPath $item.backup) {
            if (Test-Path -LiteralPath $item.target) {
                [IO.Directory]::Move($item.target, $item.failed)
            }
            Restore-DirectoryAcl $item.backup ([string]$item.root_acl)
            [IO.Directory]::Move($item.backup, $item.target)
        }
        if (Test-Path -LiteralPath $item.failed) {
            Remove-Item -LiteralPath $item.failed -Recurse -Force
        }
    }
}

function Invoke-TransactionRecovery([Collections.ArrayList]$Pending,
        [hashtable]$SavedPlan, [scriptblock]$StopAction,
        [scriptblock]$StartAction, [bool]$KeepStopped) {
    & $StopAction
    Rollback-Switches $Pending
    if (-not $KeepStopped) { & $StartAction $SavedPlan }
}

foreach ($required in @((Join-Path $HostRoot "version.json"), $clientSidPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing trusted updater file: $required" }
}
Assert-PlainDirectory $HostRoot
Assert-PlainDirectory $stateRoot

if (Test-Path -LiteralPath $transactionPath -PathType Leaf) {
    $transaction = Get-Content -LiteralPath $transactionPath -Raw | ConvertFrom-Json
    $pending = [Collections.ArrayList]::new()
    foreach ($item in @($transaction.switches)) {
        $target = [IO.Path]::GetFullPath([string]$item.target)
        $backup = [IO.Path]::GetFullPath([string]$item.backup)
        $failed = [IO.Path]::GetFullPath([string]$item.failed)
        $rootPrefix = $HostRoot + '\'
        if (-not $target.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            (Split-Path -Parent $target) -ne (Split-Path -Parent $backup) -or
            (Split-Path -Parent $target) -ne (Split-Path -Parent $failed) -or
            (Split-Path -Leaf $backup) -notlike ".moonwaker-hot-old-*" -or
            (Split-Path -Leaf $failed) -notlike ".moonwaker-hot-failed-*") {
            throw "Pending hot-update transaction contains an unsafe path."
        }
        if ([string]$item.root_acl -notmatch '^[A-Za-z0-9+/]+={0,2}$') {
            throw "Pending hot-update transaction contains an invalid ACL snapshot."
        }
        [void]$pending.Add(@{ target = $target; backup = $backup; failed = $failed;
            root_acl = [string]$item.root_acl })
    }
    $savedPlan = @{ gateway_was_running = [bool]$transaction.gateway_was_running;
        tasks = @($transaction.tasks | ForEach-Object { [string]$_ }) }
    $recoveryPlan = Get-RuntimePlan
    Invoke-TransactionRecovery $pending $savedPlan `
        { Stop-Runtime $recoveryPlan } { param($value) Start-Runtime $value } ([bool]$LeaveStopped)
    Remove-Item -LiteralPath $transactionPath -Force
    Set-StateAtomic @{ status = "failed"; version = [string]$transaction.previous_version;
        error = "Recovered an interrupted hot update by rolling back.";
        updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
}
if ($FullInstallCommit) {
    Remove-Item -LiteralPath $transactionPath, $statePath, $highWaterPath `
        -Force -ErrorAction SilentlyContinue
    Write-Output "Full installer superseded hot-update state."
    return
}
if ($RecoverOnly) { Write-Output "Hot update recovery check completed."; return }
if (-not (Test-Path -LiteralPath $PackagePath -PathType Leaf) -or
    (Get-Item -LiteralPath $PackagePath).Length -gt $maximumTotalBytes) {
    throw "Hot update package is missing or exceeds 64 MiB."
}

$work = Join-Path $stateRoot ("work-" + [guid]::NewGuid().ToString("N"))
$switches = [Collections.ArrayList]::new()
$stages = @()
$stageByTarget = @{}
$plan = @{ gateway_was_running = $false; tasks = @() }
$runtimeStopped = $false
$installedVersion = [Version](Get-Content -LiteralPath (Join-Path $HostRoot "version.json") -Raw |
    ConvertFrom-Json).version
$oldState = $null
$oldHighWater = $null
try { if (Test-Path -LiteralPath $statePath) {
    $oldState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } } catch {}
try { if (Test-Path -LiteralPath $highWaterPath) {
    $oldHighWater = Get-Content -LiteralPath $highWaterPath -Raw | ConvertFrom-Json } } catch {}
$currentVersion = Get-VersionFloor $installedVersion $oldState $oldHighWater
$pinnedSid = [Security.Principal.SecurityIdentifier]::new(
    (Get-Content -LiteralPath $clientSidPath -Raw).Trim())
try {
    New-ExactTransactionDirectory $work
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($PackagePath)
    try {
        $entries = @{}
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName
            if ($name -notmatch '^[A-Za-z0-9_.\-/]+$' -or $name.Contains("\\") -or
                $name.StartsWith("/") -or $name.Split('/') -contains ".." -or
                $entries.ContainsKey($name)) { throw "Unsafe or duplicate archive entry: $name" }
            if ($entry.Length -gt 8MB) { throw "Archive entry exceeds 8 MiB: $name" }
            $entries[$name] = $entry
        }
        if (-not $entries.ContainsKey("manifest.psd1")) { throw "manifest.psd1 is missing." }
        $manifestPath = Join-Path $work "manifest.psd1"
        $stream = $entries["manifest.psd1"].Open()
        $output = [IO.File]::Create($manifestPath)
        try { $stream.CopyTo($output) } finally { $output.Dispose(); $stream.Dispose() }
        $manifest = Import-PowerShellDataFile -LiteralPath $manifestPath
        if ([int]$manifest.Format -ne 1 -or [string]$manifest.Version -notmatch '^\d+\.\d+\.\d+$' -or
            [string]$manifest.Build -notmatch '^[A-Za-z0-9._-]{1,64}$') {
            throw "Manifest format, version, or build is invalid."
        }
        $nextVersion = [Version]$manifest.Version
        if ($nextVersion -le $currentVersion) { throw "Hot updates must increase the installed version." }
        $files = @($manifest.Files)
        if ($files.Count -lt 1 -or $files.Count -gt 256) { throw "Manifest must contain 1 to 256 files." }
        $expected = @("manifest.psd1")
        $seen = @{}
        $total = 0L
        foreach ($file in $files) {
            $path = [string]$file.Path
            $hash = [string]$file.Sha256
            $size = [long]$file.Size
            if (-not (Test-AllowedPayloadPath $path) -or $seen.ContainsKey($path) -or
                $hash -notmatch '^[a-fA-F0-9]{64}$' -or $size -lt 1 -or $size -gt 8MB) {
                throw "Manifest file is invalid or not allowlisted: $path"
            }
            $seen[$path] = $true; $total += $size
            if ($total -gt $maximumTotalBytes) { throw "Manifest payload exceeds 64 MiB." }
            $entryName = "payload/$path"; $expected += $entryName
            if (-not $entries.ContainsKey($entryName) -or $entries[$entryName].Length -ne $size) {
                throw "Payload size does not match manifest: $path"
            }
            $destination = Join-Path $work ($entryName.Replace('/', '\'))
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            $input = $entries[$entryName].Open(); $out = [IO.File]::Create($destination)
            try { $input.CopyTo($out) } finally { $out.Dispose(); $input.Dispose() }
            if ((Get-Sha256 $destination) -ne $hash.ToLowerInvariant()) {
                throw "Payload hash does not match manifest: $path"
            }
        }
        if (@($entries.Keys | Where-Object { $_ -notin $expected }).Count -gt 0) {
            throw "Package contains files not declared by the manifest."
        }
    } finally { $archive.Dispose() }

    $updates = @{}
    $profilesRoot = Join-Path $HostRoot "profiles"
    foreach ($file in $files) {
        $path = [string]$file.Path
        $parts = $path.Split('/')
        $targets = @()
        if ($parts[0] -eq "gateway") { $targets = @((Join-Path $HostRoot "gateway")) }
        else {
            $sourceName = $parts[1]
            $profileName = if ($sourceName -eq "playnite") { "game-provider" } else { $sourceName }
            $targets = @((Join-Path (Join-Path $HostRoot "bridge-source") $sourceName))
            if (Test-Path -LiteralPath $profilesRoot) {
                $targets += @(Get-ChildItem -LiteralPath $profilesRoot -Directory | ForEach-Object {
                    Join-Path $_.FullName $profileName } | Where-Object { Test-Path -LiteralPath $_ })
            }
        }
        foreach ($target in $targets) {
            if (-not (Test-Path -LiteralPath $target -PathType Container)) {
                throw "Required runtime target is missing: $target"
            }
            if (-not $updates.ContainsKey($target)) { $updates[$target] = @() }
            $updates[$target] += @{ source = Join-Path $work ("payload\" + $path.Replace('/', '\'));
                name = $parts[-1]; sha256 = ([string]$file.Sha256).ToLowerInvariant(); size = [long]$file.Size }
        }
    }

    # Gateway executes as LocalSystem. Reject a custom HostRoot whose ACL lets
    # any identity other than SYSTEM, admins, or the pinned developer replace it.
    if ($updates.ContainsKey((Join-Path $HostRoot "gateway"))) {
        Assert-SafeRuntimeParent $HostRoot $pinnedSid
    }

    # Advance before any runtime mutation. Failure and rollback deliberately do
    # not lower this version floor; only a full installer may reset it.
    Set-HighWaterAtomic @{ version = [string]$manifest.Version;
        package_sha256 = Get-Sha256 $PackagePath;
        observed_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }

    # Runtime directories contain durable session state as well as code. Stop
    # writers before cloning them so a hot update cannot resurrect a stale
    # control barrier or lose a just-completed child-session transition.
    Set-StateAtomic @{ status = "applying"; version = [string]$manifest.Version;
        build = [string]$manifest.Build; package_sha256 = Get-Sha256 $PackagePath;
        updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
    $plan = Get-RuntimePlan
    Stop-Runtime $plan
    $runtimeStopped = $true

    foreach ($target in $updates.Keys) {
        Assert-PlainDirectory $target
        $parent = Split-Path -Parent $target
        $stage = Join-Path $parent (".moonwaker-hot-new-" + [guid]::NewGuid().ToString("N"))
        New-ExactTransactionDirectory $stage
        Get-ChildItem -LiteralPath $target -Force | Copy-Item -Destination $stage -Recurse -Force
        foreach ($update in $updates[$target]) {
            Copy-Item -LiteralPath $update.source -Destination (Join-Path $stage $update.name) -Force
        }
        Set-ExactTransactionAcl $stage
        foreach ($update in $updates[$target]) {
            Assert-FileMatches (Join-Path $stage $update.name) $update.sha256 $update.size
        }
        $stages += $stage
        $stageByTarget[$target] = $stage
    }

    foreach ($target in $updates.Keys) {
        [void]$switches.Add(@{
            target = $target
            backup = Join-Path (Split-Path -Parent $target) (".moonwaker-hot-old-" + [guid]::NewGuid().ToString("N"))
            failed = Join-Path (Split-Path -Parent $target) (".moonwaker-hot-failed-" + [guid]::NewGuid().ToString("N"))
            root_acl = Get-AclBase64 $target
        })
    }
    Set-TransactionAtomic @{ previous_version = [string]$currentVersion;
        gateway_was_running = [bool]$plan.gateway_was_running; tasks = @($plan.tasks);
        switches = @($switches) }
    foreach ($item in $switches) {
        [IO.Directory]::Move($item.target, $item.backup)
        Set-ExactTransactionAcl $item.backup
        try { [IO.Directory]::Move([string]$stageByTarget[$item.target], $item.target) }
        catch {
            Restore-DirectoryAcl $item.backup ([string]$item.root_acl)
            [IO.Directory]::Move($item.backup, $item.target)
            throw
        }
        Restore-DirectoryAcl $item.target ([string]$item.root_acl)
    }
    # Revalidate the activated bytes after every rename and immediately before
    # any service or scheduled task can execute them.
    foreach ($target in $updates.Keys) {
        Assert-PlainDirectory $target
        foreach ($update in $updates[$target]) {
            Assert-FileMatches (Join-Path $target $update.name) $update.sha256 $update.size
        }
    }
    Start-Runtime $plan; $runtimeStopped = $false
    if (-not (Test-RuntimeHealthy $plan)) { throw "Updated runtime failed its 30 second health check." }
    Set-StateAtomic @{ status = "ready"; version = [string]$manifest.Version;
        build = [string]$manifest.Build; package_sha256 = Get-Sha256 $PackagePath;
        updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
    Remove-Item -LiteralPath $transactionPath -Force
    foreach ($item in $switches) {
        Remove-Item -LiteralPath $item.backup -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Output "MoonWaker hot update $($manifest.Version)+$($manifest.Build) applied and healthy."
} catch {
    if ($switches.Count -gt 0) {
        try {
            $currentPlan = Get-RuntimePlan
            Stop-Runtime $currentPlan
            Rollback-Switches $switches
            Start-Runtime $plan
            Remove-Item -LiteralPath $transactionPath -Force -ErrorAction SilentlyContinue
        }
        catch { throw "Hot update failed and rollback also failed: $($_.Exception.Message)" }
    } elseif ($runtimeStopped) { try { Start-Runtime $plan } catch {} }
    Set-StateAtomic @{ status = "failed"; version = [string]$currentVersion;
        error = $_.Exception.Message;
        updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
    throw
} finally {
    foreach ($stage in $stages) { if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force } }
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
