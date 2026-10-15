#requires -Version 5.1
$ErrorActionPreference = "Stop"
$compiler = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = "$env:SystemRoot\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
$temporary = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-hot-updater-test-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $temporary | Out-Null
    $test = Join-Path $temporary "MoonWakerHotUpdaterTests.exe"
    & $compiler /nologo /target:exe "/out:$test" /main:MoonWaker.HotUpdater.SelfTestProgram `
        /reference:System.dll /reference:System.Core.dll /reference:System.ServiceProcess.dll `
        /reference:System.Security.dll `
        (Join-Path $PSScriptRoot "MoonWakerHotUpdaterService.cs") `
        (Join-Path $PSScriptRoot "MoonWakerHotUpdaterServiceTests.cs")
    if ($LASTEXITCODE -ne 0) { throw "Hot updater tests did not compile." }
    & $test
    if ($LASTEXITCODE -ne 0) { throw "Hot updater tests failed." }

    $applyPath = Join-Path $PSScriptRoot "Apply-MoonWakerHotUpdate.ps1"
    $apply = Get-Content -LiteralPath $applyPath -Raw
    foreach ($required in @("Import-PowerShellDataFile",
            "Test-AllowedPayloadPath", "[IO.File]::Replace", "Rollback-Switches",
            "transaction.json", "high-water.json", "RecoverOnly", "MoonWaker\HotUpdater",
            "wakeplay_gateway\.py")) {
        if ($apply.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
            throw "Apply engine contract is missing: $required"
        }
    }
    $tokens = $null; $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $applyPath, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw "Apply engine has PowerShell parse errors." }
    function Import-ApplyFunction([string]$Name) {
        $definition = @($ast.FindAll({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name }, $true)) | Select-Object -First 1
        if ($null -eq $definition) { throw "Apply function is missing: $Name" }
        $body = $definition.Body.Extent.Text
        $parameters = @($definition.Parameters | ForEach-Object { $_.Extent.Text }) -join ","
        Set-Item -Path ("Function:script:" + $Name) -Value (
            [scriptblock]::Create("param($parameters)`n" +
                $body.Substring(1, $body.Length - 2)))
    }
    Import-ApplyFunction "Test-AllowedPayloadPath"
    foreach ($allowed in @("gateway/wakeplay_gateway.py",
            "bridges/playnite/GameProviderBridge.py",
            "bridges/discord/DiscordBridge.ps1")) {
        if (-not (Test-AllowedPayloadPath $allowed)) { throw "Allowed path was rejected: $allowed" }
    }
    foreach ($denied in @("gateway/gateway.json", "../gateway/evil.py",
            "windows-login/login-broker/BrokerService.cs", "gateway/worker.exe",
            "bridges/playnite/subdirectory/evil.py",
            "bridges/vibepollo/VibepolloBridge.ps1")) {
        if (Test-AllowedPayloadPath $denied) { throw "Unsafe path was accepted: $denied" }
    }
    $packageSource = Join-Path $temporary "package-source"
    $packageGateway = Join-Path $packageSource "gateway"
    $unsignedPackage = Join-Path $temporary "developer.mwhu"
    New-Item -ItemType Directory -Path $packageGateway | Out-Null
    [IO.File]::WriteAllText((Join-Path $packageGateway "dev.py"), "print('ok')",
        [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot "New-MoonWakerHotUpdatePackage.ps1") `
        -Version 9.8.7 -Build local-test -Files gateway/dev.py `
        -SourceRoot $packageSource -OutputPath $unsignedPackage | Out-Null
    Add-Type -AssemblyName System.IO.Compression
    $packageArchive = [IO.Compression.ZipFile]::OpenRead($unsignedPackage)
    try {
        if ($null -eq $packageArchive.GetEntry("manifest.psd1") -or
            $null -eq $packageArchive.GetEntry("payload/gateway/dev.py")) {
            throw "Unsigned developer package is incomplete."
        }
    } finally { $packageArchive.Dispose() }
    Write-Host "PASS: unsigned allowlisted developer package is usable"
    Import-ApplyFunction "Rollback-Switches"
    Import-ApplyFunction "Invoke-TransactionRecovery"
    Import-ApplyFunction "Get-AclBase64"
    Import-ApplyFunction "Restore-DirectoryAcl"
    $target = Join-Path $temporary "runtime"
    $backup = Join-Path $temporary ".moonwaker-hot-old-test"
    $failed = Join-Path $temporary ".moonwaker-hot-failed-test"
    New-Item -ItemType Directory -Path $target, $backup | Out-Null
    Set-Content -LiteralPath (Join-Path $target "new.txt") -Value "new"
    Set-Content -LiteralPath (Join-Path $backup "old.txt") -Value "old"
    $pending = [Collections.ArrayList]::new()
    [void]$pending.Add(@{ target = $target; backup = $backup; failed = $failed;
        root_acl = Get-AclBase64 $backup })
    $events = [Collections.ArrayList]::new()
    Invoke-TransactionRecovery $pending @{ gateway_was_running = $false; hosted = @() } `
        { [void]$events.Add("stop") } { param($plan) [void]$events.Add("start") } $false
    if (-not (Test-Path -LiteralPath (Join-Path $target "old.txt")) -or
        (Test-Path -LiteralPath $backup) -or ($events -join ",") -ne "stop,start") {
        throw "Executable transaction recovery did not restore the previous runtime."
    }
    Write-Host "PASS: executable interrupted-transaction recovery"

    Import-ApplyFunction "Get-VersionFloor"
    Import-ApplyFunction "Set-HighWaterAtomic"
    $script:highWaterPath = Join-Path $temporary "high-water.json"
    Set-HighWaterAtomic @{ version = "0.7.108" }
    Set-HighWaterAtomic @{ version = "0.7.109" }
    $persistedHighWater = Get-Content -LiteralPath $script:highWaterPath -Raw | ConvertFrom-Json
    $floor = Get-VersionFloor ([Version]"0.7.107") `
        ([pscustomobject]@{ status = "failed"; version = "0.7.108" }) `
        $persistedHighWater
    if ($floor -ne [Version]"0.7.109" -or
        [Version]"0.7.108" -gt $floor -or [Version]"0.7.107" -gt $floor) {
        throw "A failed higher hot update lowered the monotonic version floor."
    }
    Write-Host "PASS: failed update preserves monotonic version high-water"

    Import-ApplyFunction "Get-Sha256"
    Import-ApplyFunction "Assert-FileMatches"
    Import-ApplyFunction "New-TransactionSecurity"
    $payload = Join-Path $temporary "payload.py"
    [IO.File]::WriteAllText($payload, "trusted", [Text.UTF8Encoding]::new($false))
    $payloadHash = Get-Sha256 $payload
    Assert-FileMatches $payload $payloadHash 7
    [IO.File]::WriteAllText($payload, "changed", [Text.UTF8Encoding]::new($false))
    $substitutionRejected = $false
    try { Assert-FileMatches $payload $payloadHash 7 } catch { $substitutionRejected = $true }
    if (-not $substitutionRejected) { throw "Post-validation payload substitution was accepted." }
    $transactionAcl = New-TransactionSecurity
    $transactionSids = @($transactionAcl.GetAccessRules($true, $true,
        [Security.Principal.SecurityIdentifier]) | ForEach-Object { $_.IdentityReference.Value })
    if (-not $transactionAcl.AreAccessRulesProtected -or
        $transactionAcl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne "S-1-5-32-544" -or
        @($transactionSids | Where-Object { $_ -notin @("S-1-5-18", "S-1-5-32-544") }).Count) {
        throw "Transaction ACL permits a foreign identity."
    }
    Write-Host "PASS: transaction ACL and activation rehash reject substitution"

    Import-ApplyFunction "Test-RuntimeParentAcl"
    $pinnedSid = [Security.Principal.SecurityIdentifier]::new("S-1-5-21-1-2-3-1001")
    $foreignSid = [Security.Principal.SecurityIdentifier]::new("S-1-5-21-1-2-3-1002")
    $trustedInstallerSid = [Security.Principal.SecurityIdentifier]::new(
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464")
    $parentAcl = [Security.AccessControl.DirectorySecurity]::new()
    $parentAcl.SetOwner($trustedInstallerSid)
    $parentAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $trustedInstallerSid, [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.AccessControlType]::Allow))
    foreach ($ruleSpec in @(
            @{ sid = "S-1-5-18"; rights = [Security.AccessControl.FileSystemRights]::FullControl },
            @{ sid = "S-1-5-32-544"; rights = [Security.AccessControl.FileSystemRights]::FullControl },
            @{ sid = "S-1-5-32-545"; rights = [Security.AccessControl.FileSystemRights]::ReadAndExecute })) {
        $parentAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($ruleSpec.sid), $ruleSpec.rights,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    $parentAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        [Security.Principal.SecurityIdentifier]::new("S-1-3-0"),
        [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [Security.AccessControl.InheritanceFlags]::ObjectInherit,
        [Security.AccessControl.PropagationFlags]::InheritOnly,
        [Security.AccessControl.AccessControlType]::Allow))
    if (-not (Test-RuntimeParentAcl $parentAcl $pinnedSid)) {
        throw "Representative Program Files ACL was rejected."
    }
    $parentAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $foreignSid, [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles,
        [Security.AccessControl.AccessControlType]::Allow))
    if (Test-RuntimeParentAcl $parentAcl $pinnedSid) {
        throw "Foreign FILE_DELETE_CHILD permission passed SYSTEM runtime preflight."
    }
    $preflightIndex = $apply.IndexOf('Assert-SafeRuntimeParent $HostRoot',
        [StringComparison]::Ordinal)
    $stopIndex = $apply.IndexOf('Stop-Runtime $plan', [StringComparison]::Ordinal)
    $swapIndex = $apply.IndexOf('[IO.Directory]::Move($item.target', $preflightIndex,
        [StringComparison]::Ordinal)
    if ($preflightIndex -lt 0 -or $preflightIndex -ge $stopIndex -or
        $preflightIndex -ge $swapIndex) {
        throw "SYSTEM runtime parent preflight does not precede stop and swap."
    }
    Write-Host "PASS: foreign FILE_DELETE_CHILD fails before runtime stop/swap"

    Import-ApplyFunction "Invoke-StopSequence"
    $events.Clear()
    try {
        Invoke-StopSequence @{ gateway_was_running = $true; hosted = @() } `
            { [void]$events.Add("gateway-stop") } `
            { [void]$events.Add("remainder"); throw "injected stop failure" } `
            { [void]$events.Add("restore") }
        throw "Injected stop failure was not propagated."
    } catch {
        if ($_.Exception.Message -ne "injected stop failure") { throw }
    }
    if (($events -join ",") -ne "gateway-stop,remainder,restore") {
        throw "Partial runtime stop did not restore the captured restart plan."
    }
    Write-Host "PASS: injected post-Gateway-stop failure restores runtime"

    Import-ApplyFunction "Invoke-GatewayHealthRequest"
    $python = (Get-Command python.exe -ErrorAction Stop).Source
    $healthJson = Invoke-GatewayHealthRequest $python `
        "data:application/json,%7B%22name%22%3A%22Wake%20%26%20Play%20Host%20Gateway%22%7D"
    if (($healthJson | ConvertFrom-Json).name -ne "Wake & Play Host Gateway") {
        throw "Python Gateway health transport changed the response body."
    }
    $hungPython = Join-Path $temporary "python.exe"
    Copy-Item -LiteralPath $test -Destination $hungPython
    $hangTimer = [Diagnostics.Stopwatch]::StartNew()
    try {
        Invoke-GatewayHealthRequest $hungPython "--hang" 200 | Out-Null
        throw "Hung Gateway health process was accepted."
    } catch {
        if ($_.Exception.Message -ne "Gateway HTTPS health request timed out.") { throw }
    } finally { $hangTimer.Stop() }
    if ($hangTimer.ElapsedMilliseconds -gt 2500) {
        throw "Hung Gateway health process exceeded its bounded cleanup."
    }
    $endpointDefinition = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq "Test-EndpointHealth" }, $true)) | Select-Object -First 1
    $endpointBody = $endpointDefinition.Body.Extent.Text
    $trustedIndex = $endpointBody.IndexOf('$listenerPython.Equals($configuredPython',
        [StringComparison]::Ordinal)
    $executeIndex = $endpointBody.IndexOf('Invoke-GatewayHealthRequest $configuredPython',
        [StringComparison]::Ordinal)
    if ($trustedIndex -lt 0 -or $executeIndex -lt 0 -or $trustedIndex -gt $executeIndex) {
        throw "Gateway health executes an interpreter before exact service identity validation."
    }
    Write-Host "PASS: Gateway health bypasses Windows PowerShell Schannel"

    Import-ApplyFunction "Test-EndpointHealth"
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $probe.Start(); $port = ([Net.IPEndPoint]$probe.LocalEndpoint).Port; $probe.Stop()
    $dummy = Start-Process -FilePath $test -ArgumentList @("--dummy-http", "$port") `
        -PassThru -WindowStyle Hidden
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        while ([DateTime]::UtcNow -lt $deadline -and -not
            (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
            Start-Sleep -Milliseconds 50
        }
        $endpoint = @{ component = "discord"; scheme = "http"; port = $port;
            path = "/health"; profile = "default" }
        if (Test-EndpointHealth $endpoint) { throw "Dummy TCP/HTTP listener passed runtime health." }
    } finally {
        if (-not $dummy.HasExited) { $dummy.Kill() }
        [void]$dummy.WaitForExit(5000); $dummy.Dispose()
    }
    Write-Host "PASS: dummy TCP listener fails the health contract"

    # Only processes this installation's tray started from its code are stopped or counted.
    Import-ApplyFunction "Get-GatewayPython"
    Import-ApplyFunction "Get-HostedProcesses"
    $script:HostRoot = "C:\MW Test"
    $trayStart = [DateTime]::new(2026, 1, 1)
    $powershell = Join-Path ([Environment]::SystemDirectory) "WindowsPowerShell\v1.0\powershell.exe"
    function New-FakeProcess([int]$Id, [int]$Parent, [string]$Executable, [string]$Line,
            [int]$AgeSeconds = 10) {
        [pscustomobject]@{ ProcessId = $Id; ParentProcessId = $Parent; ExecutablePath = $Executable
            CommandLine = $Line; CreationDate = $trayStart.AddSeconds($AgeSeconds) }
    }
    function Get-AgentLine([string]$Python = "C:\Python312\pythonw.exe",
            [string]$Root = "C:\MW Test", [string]$Profile = "default") {
        "`"$Python`" `"$Root\bridge-source\playnite\GameProviderBridge.py`" --config " +
            "`"C:\MW Test\profiles\$Profile\game-provider\config.json`""
    }
    $discordLine = "`"$powershell`" -NoProfile -NonInteractive -WindowStyle Hidden " +
        "-ExecutionPolicy Bypass -File `"C:\MW Test\bridge-source\discord\DiscordBridge.ps1`" " +
        "-ProfileDirectory `"C:\MW Test\profiles\default\discord`""
    $script:fakeProcesses = @(
        (New-FakeProcess 10 1 "C:\MW Test\control\MoonWakerHostControl.exe" "tray" 0),
        (New-FakeProcess 11 10 "C:\Python312\pythonw.exe" (Get-AgentLine)),
        (New-FakeProcess 12 10 $powershell $discordLine),
        (New-FakeProcess 20 1 "C:\Windows\explorer.exe" "explorer" 0),
        (New-FakeProcess 13 20 "C:\Python312\pythonw.exe" (Get-AgentLine)),
        (New-FakeProcess 14 10 "C:\Other\pythonw.exe" (Get-AgentLine "C:\Other\pythonw.exe")),
        (New-FakeProcess 15 10 "C:\Python312\pythonw.exe" (Get-AgentLine -Root "C:\MW Test2")),
        (New-FakeProcess 16 10 "C:\Python312\pythonw.exe" ((Get-AgentLine) + " --extra")),
        (New-FakeProcess 17 10 "C:\Python312\pythonw.exe" (Get-AgentLine) -5),
        (New-FakeProcess 18 10 "C:\Python312\pythonw.exe" (Get-AgentLine -Profile "a\b")))
    function script:Get-CimInstance {
        [CmdletBinding()] param([string]$ClassName, [string]$Filter, [string[]]$Property)
        if ($ClassName -eq "Win32_Service") {
            return [pscustomobject]@{ PathName = '"C:\MW Test\gateway\MoonWakerGatewayService.exe" ' +
                '--python "C:\Python312\python.exe"' }
        }
        return $script:fakeProcesses
    }
    try { $hostedFound = @(Get-HostedProcesses | ForEach-Object {
            "$($_.ProcessId):$($_.Profile)/$($_.Component)" }) -join "," }
    finally { Remove-Item Function:\Get-CimInstance }
    if ($hostedFound -ne "11:default/game-provider,12:default/discord") {
        throw "Hosted process matching is not exact: $hostedFound"
    }
    Write-Host "PASS: only tray-hosted installation processes are stopped or counted"

    $installPath = Join-Path $PSScriptRoot "Install-MoonWakerHotUpdater.ps1"
    $installTokens = $null; $installErrors = $null
    $installAst = [Management.Automation.Language.Parser]::ParseFile(
        $installPath, [ref]$installTokens, [ref]$installErrors)
    if ($installErrors.Count) { throw "Updater installer has PowerShell parse errors." }
    $descriptorFunction = @($installAst.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq "New-ProtectedSecurity" }, $true)) | Select-Object -First 1
    $descriptorBody = $descriptorFunction.Body.Extent.Text
    $descriptorParams = @($descriptorFunction.Parameters | ForEach-Object { $_.Extent.Text }) -join ","
    Set-Item Function:script:New-ProtectedSecurity -Value ([scriptblock]::Create(
        "param($descriptorParams)`n" + $descriptorBody.Substring(1, $descriptorBody.Length - 2)))
    $descriptor = New-ProtectedSecurity $true $false
    $descriptorRules = @($descriptor.GetAccessRules($true, $true,
        [Security.Principal.SecurityIdentifier]))
    $descriptorSids = @($descriptorRules | ForEach-Object { $_.IdentityReference.Value })
    if (-not $descriptor.AreAccessRulesProtected -or
        $descriptor.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne "S-1-5-32-544" -or
        @($descriptorSids | Where-Object { $_ -notin @("S-1-5-18", "S-1-5-32-544") }).Count) {
        throw "Bootstrap ACL descriptor is not exact, protected, and administrator-owned."
    }
    $reparseFunction = @($installAst.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq "Assert-NoReparseTree" }, $true)) | Select-Object -First 1
    $reparseBody = $reparseFunction.Body.Extent.Text
    $reparseParams = @($reparseFunction.Parameters | ForEach-Object { $_.Extent.Text }) -join ","
    Set-Item Function:script:Assert-NoReparseTree -Value ([scriptblock]::Create(
        "param($reparseParams)`n" + $reparseBody.Substring(1, $reparseBody.Length - 2)))
    $junctionTarget = Join-Path $temporary "junction-target"
    $junctionRoot = Join-Path $temporary "junction-root"
    New-Item -ItemType Directory -Path $junctionTarget, $junctionRoot | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $junctionRoot "foreign") `
        -Target $junctionTarget | Out-Null
    $reparseRejected = $false
    try { Assert-NoReparseTree $junctionRoot } catch { $reparseRejected = $true }
    if (-not $reparseRejected) { throw "Bootstrap accepted a precreated reparse point." }
    Write-Host "PASS: exact bootstrap ACL and precreated reparse rejection"

    $hostInstallPath = Join-Path (Split-Path -Parent $PSScriptRoot) "install\Install-WakePlayHost.ps1"
    $hostTokens = $null; $hostErrors = $null
    $hostAst = [Management.Automation.Language.Parser]::ParseFile(
        $hostInstallPath, [ref]$hostTokens, [ref]$hostErrors)
    if ($hostErrors.Count) { throw "Host installer has PowerShell parse errors." }
    $eligibilityFunction = @($hostAst.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq "Test-HotUpdaterStartEligible" }, $true)) | Select-Object -First 1
    $eligibilityBody = $eligibilityFunction.Body.Extent.Text
    $eligibilityParams = @($eligibilityFunction.Parameters | ForEach-Object { $_.Extent.Text }) -join ","
    Set-Item Function:script:Test-HotUpdaterStartEligible -Value ([scriptblock]::Create(
        "param($eligibilityParams)`n" + $eligibilityBody.Substring(1, $eligibilityBody.Length - 2)))
    $ready = Join-Path $temporary "installer-ready"
    $fakeService = [pscustomobject]@{ Name = "MoonWakerHotUpdater" }
    if (Test-HotUpdaterStartEligible $ready $fakeService "Automatic") {
        throw "Updater without a trust-ready marker was start-eligible."
    }
    Set-Content -LiteralPath $ready -Value "trusted"
    if (Test-HotUpdaterStartEligible $ready $fakeService "Disabled") {
        throw "Disabled untrusted updater was start-eligible."
    }
    if (-not (Test-HotUpdaterStartEligible $ready $fakeService "Automatic")) {
        throw "Trusted automatic updater was not start-eligible."
    }
    $installSource = Get-Content -LiteralPath $installPath -Raw
    if ($installSource -match 'Authenticode|SignerCertificate|trusted-signer' -or
        $installSource -notmatch 'client-sid\.txt' -or
        $installSource -notmatch 'Disable-UntrustedUpdater') {
        throw "Updater bootstrap is not the pinned-SID fail-closed design."
    }
    Write-Host "PASS: full installer fails closed for failed updater bootstrap"
    if ($apply -match 'Authenticode|trusted-signer|SignerCertificate') {
        throw "Obsolete per-package PKI remains in the local-account updater."
    }
    Write-Host "PASS: pinned-client, allowlist, atomic state and rollback contracts"
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
