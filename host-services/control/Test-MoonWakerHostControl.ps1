#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
# Any accidental return to a machine-service path must fail this fixture.
function Get-Service { throw "Host Control fixture must not inspect installed services." }
function Start-Service { throw "Host Control fixture must not start installed services." }
function Stop-Service { throw "Host Control fixture must not stop installed services." }
function Restart-Service { throw "Host Control fixture must not restart installed services." }
$root = Join-Path ([IO.Path]::GetTempPath()) ("MoonWakerHostControlTest-" + [guid]::NewGuid().ToString("N"))
$control = Join-Path $root "control"
$gateway = Join-Path $root "gateway"
$profile = Join-Path $root "profiles\default"
$foreignProfile = Join-Path $root "profiles\foreign"
$legendaryState = Join-Path $profile "state\legendary"
$fakeLegendary = Join-Path $root "fake-legendary.cmd"
$resultPath = Join-Path $root "result.json"
$diagnosticPath = Join-Path $root "steam-web-api-configure.log"
$plain = "0" * 32
$listenerProcess = $null
$closeListenerProcess = $null
$uiSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot "MoonWakerHostControl.cs") -Raw
$configuratorSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot "MoonWakerHostConfigurator.cs") -Raw
$hostControlSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot "Invoke-MoonWakerHostControl.ps1") -Raw
$profileAgentSource = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) `
    "profile-agent\MoonWakerProfileBridge.ps1") -Raw
$vibepolloBridgeSource = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) `
    "bridges\vibepollo\VibepolloBridge.ps1") -Raw

function Test-ProviderStatusFixture {
    # Execute the real status caller with all file/network/service effects replaced.
    & {
        param($source)
        $ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$null, [ref]$null)
        foreach ($name in 'Test-HttpHealth','Test-ChildProfile','Get-ProfileEndpoint','Get-Status') {
            $definition = $ast.FindAll({ param($node)
                $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
            }, $true)
            if (@($definition).Count -ne 1) { throw 'status_fixture_function_missing' }
            Invoke-Expression $definition[0].Extent.Text
        }
        function Get-GatewayDirectory { 'fixture-gateway' }
        function Ensure-BackgroundServices { }
        function Get-MoonWakerVersion { [pscustomobject]@{ version='test'; build='test' } }
        function Get-ProfileRoot { 'fixture-profile' }
        function Test-SteamConnection { $false }
        function Test-LegendaryConnection { $false }
        function Test-PlayniteConnection { $false }
        function Test-CurrentProfileOwner { $true }
        function Get-SupervisorStatus { 'running' }
        function Get-ProfileProcessId { 123 }
        function Get-LegendaryExecutable { '' }
        function Get-MicrophoneReadiness { [pscustomobject]@{ ready=$false } }
        function Test-TcpPort { $false }
        function Test-Path([string]$LiteralPath) {
            $LiteralPath -eq 'fixture-gateway\gateway.json' -or
                ($LiteralPath -eq 'fixture-profile\profile-bridge-manually-stopped' -and $manuallyStopped)
        }
        function Get-Content([string]$LiteralPath) {
            if ($LiteralPath -ne 'fixture-gateway\gateway.json') { throw 'fixture_optional_file_absent' }
            @{ listen_port=8785; clients=@(); profiles=@{ parent=@{
                game_provider_bridge=$endpoint; playnite_bridge='http://127.0.0.1:45998'
            } } } | ConvertTo-Json -Depth 5
        }
        function Invoke-RestMethod([string]$Uri) {
            [void]$calls.Add($Uri)
            if ($Uri -ne 'http://127.0.0.1:45999/observation') { throw 'removed_health_route_requested' }
            if ($reply -eq 'offline') { throw 'fixture_transport_failure' }
            [pscustomobject]@{ ok=($reply -ne 'error'); schema_version=1 }
        }
        $cases = @(
            @{ endpoint='http://127.0.0.1:45999/'; reply='online'; stopped=$false; expected='online'; count=1 },
            @{ endpoint='http://127.0.0.1:45999'; reply='error'; stopped=$false; expected='error'; count=1 },
            @{ endpoint='http://127.0.0.1:45999'; reply='offline'; stopped=$false; expected='offline'; count=1 },
            @{ endpoint=''; reply='online'; stopped=$false; expected='disabled'; count=0 },
            @{ endpoint='http://127.0.0.1:45999'; reply='online'; stopped=$true; expected='manually_stopped'; count=0 }
        )
        foreach ($case in $cases) {
            $endpoint=$case.endpoint; $reply=$case.reply; $manuallyStopped=$case.stopped
            $calls = New-Object 'Collections.Generic.List[string]'
            $status = Get-Status
            $profileStatus = @($status.profiles)[0]
            if ($profileStatus.game_provider -ne $case.expected -or $calls.Count -ne $case.count -or
                $profileStatus.Contains('playnite') -or $profileStatus.Contains('playnite_pid')) {
                throw 'provider_status_route_or_result_regressed'
            }
        }
        Write-Output 'Host Control provider status: 5/5 isolated cases passed.'
    } $hostControlSource
}

Test-ProviderStatusFixture
if (-not $uiSource.Contains('vibepolloUrl.Text = vibepolloConfigured ? "" :')) {
    throw "The integrations dialog no longer preserves an existing Vibepollo base URL."
}
foreach ($contract in @(
    'private const int ProfileCreatedExitCode = 10;',
    'process.ExitCode == ProfileCreatedExitCode',
    'ProcessStartInfo("https://127.0.0.1:47990")'
)) {
    if (-not $uiSource.Contains($contract)) {
        throw "Host Control profile-to-Vibepollo redirect is missing: $contract"
    }
}
if (-not $configuratorSource.Contains(
        'if (mode == "add" && form.DialogResult == DialogResult.OK) return 10;') -or
    -not $configuratorSource.Contains('DialogResult = DialogResult.OK;')) {
    throw "The elevated profile configurator does not report successful profile creation."
}
foreach ($contract in @(
    'function Get-CompatiblePythonExecutable',
    '$request | & $PythonExecutable $transport',
    'python_path = $python',
    '[string]::IsNullOrWhiteSpace($existingPython)',
    '-not [string]::IsNullOrWhiteSpace($_)',
    'Get-Command python.exe -CommandType Application'
)) {
    if (-not $hostControlSource.Contains($contract)) {
        throw "Host Control Vibepollo Python resolution is missing: $contract"
    }
}
if ($hostControlSource.Contains(
        'Test-Path -LiteralPath ([string]$existing.python_path)')) {
    throw "Host Control can still pass an empty configured Python path to LiteralPath."
}
if (-not $vibepolloBridgeSource.Contains('$script:PythonExe = Get-CompatiblePythonExecutable') -or
    -not $profileAgentSource.Contains('return Start-HiddenProcess $python')) {
    throw "Profile Bridges do not use the resolved Python executable."
}
if (-not $uiSource.Contains('Ctrl+Alt+Shift+End') -or
    -not $uiSource.Contains('RegisterHotKey(Handle, StreamHotkeyId') -or
    -not $uiSource.Contains('RunControlAsync("CloseStream", null)') -or
    -not $uiSource.Contains('MoonWakerHostControl.StreamHotkey.')) {
    throw "Host Control stream hotkey or its secure-desktop pipe is missing."
}

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Test-HostControlSingleton {
    $compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    if (-not (Test-Path -LiteralPath $compiler)) {
        $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
    }
    if (-not (Test-Path -LiteralPath $compiler)) {
        throw ".NET Framework C# compiler was not found for the headless singleton test."
    }

    $separatorCharacters = [char[]]@([IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
    $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd($separatorCharacters)
    $testRoot = [IO.Path]::GetFullPath((Join-Path $tempParent `
        ("MoonWakerHostControlSingletonTest-" + [guid]::NewGuid().ToString("N"))))
    if ([IO.Directory]::GetParent($testRoot).FullName.TrimEnd($separatorCharacters) -ine $tempParent) {
        throw "Headless Host Control singleton test directory escaped the temporary parent."
    }
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    try {
        $testExecutable = Join-Path $testRoot "HostControlSingletonTests.exe"
        $compilerArguments = @(
            "/nologo", "/target:exe", "/main:MoonWaker.HostControl.SingletonTests",
            "/out:$testExecutable", "/reference:System.dll", "/reference:System.Core.dll",
            "/reference:System.Drawing.dll", "/reference:System.Windows.Forms.dll",
            "/reference:System.Web.Extensions.dll", "/reference:System.Security.dll",
            (Join-Path $PSScriptRoot "MoonWakerHostControl.cs"),
            (Join-Path $PSScriptRoot "Test-MoonWakerHostControlSingleton.cs"))
        $compilerOutput = & $compiler @compilerArguments 2>&1
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $testExecutable)) {
            throw ("Headless Host Control singleton test compilation failed: " +
                ($compilerOutput -join [Environment]::NewLine))
        }
        & $testExecutable
        if ($LASTEXITCODE -ne 0) {
            throw "Headless Host Control singleton test failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
        $resolvedTestParent = ([IO.Directory]::GetParent($resolvedTestRoot).FullName).TrimEnd(
            $separatorCharacters)
        if ($resolvedTestParent -ine $tempParent -or
            [IO.Path]::GetFileName($resolvedTestRoot) -notmatch `
                '^MoonWakerHostControlSingletonTest-[0-9a-f]{32}$') {
            throw "Refusing to recursively remove an unexpected singleton test directory."
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Protect-TestValue([string]$Value) {
    Add-Type -AssemblyName System.Security
    $plainBytes = [Text.Encoding]::Unicode.GetBytes($Value)
    $protectedBytes = [Security.Cryptography.ProtectedData]::Protect(
        $plainBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try { return ($protectedBytes | ForEach-Object { $_.ToString("x2") }) -join "" }
    finally {
        [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        [Array]::Clear($protectedBytes, 0, $protectedBytes.Length)
    }
}

try {
    Test-HostControlSingleton
    New-Item -ItemType Directory -Path $control, $gateway, (Join-Path $profile "game-provider"), `
        (Join-Path $profile "discord"), (Join-Path $profile "vibepollo"), $legendaryState, `
        $foreignProfile -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "Invoke-MoonWakerHostControl.ps1") `
        -Destination (Join-Path $control "Invoke-MoonWakerHostControl.ps1")
    # Exercise Host Control's delegation with a fixture-owned listener stopper.
    # The production stopper also controls machine services and legacy installs,
    # so copying it into a temporary directory does not isolate those effects.
    $fixtureStopScript = Join-Path $gateway "Stop-MoonWakerGateway.ps1"
    @'
param([string]$GatewayDirectory)
$ErrorActionPreference = "Stop"
if ([IO.Path]::GetFullPath($GatewayDirectory) -ne [IO.Path]::GetFullPath($PSScriptRoot)) {
    throw "Fixture stop directory mismatch."
}
$runtimePath = Join-Path $PSScriptRoot "gateway-runtime.json"
if (-not (Test-Path -LiteralPath $runtimePath -PathType Leaf)) {
    throw "Fixture listener has no captured runtime identity."
}
$runtime = Get-Content -LiteralPath $runtimePath -Raw | ConvertFrom-Json
$process = Get-Process -Id ([int]$runtime.pid) -ErrorAction Stop
$started = [DateTimeOffset]::new($process.StartTime).ToUnixTimeSeconds()
if ($started -ne [int64]$runtime.started_at) { throw "Fixture listener identity changed." }
Stop-Process -Id $process.Id -Force -ErrorAction Stop
New-Item -ItemType File -Path (Join-Path $PSScriptRoot "gateway-manually-stopped"), `
    (Join-Path $PSScriptRoot "gateway-supervisor-stop") -Force | Out-Null
'@ | Set-Content -LiteralPath $fixtureStopScript -Encoding UTF8
    # A wrong root must be rejected before any file/process/service effect.
    $foreignStopRejected = $false
    try { & $fixtureStopScript -GatewayDirectory $foreignProfile }
    catch {
        if ($_.Exception.Message -ne "Fixture stop directory mismatch.") { throw }
        $foreignStopRejected = $true
    }
    if (-not $foreignStopRejected) { throw "Fixture stopper accepted a foreign directory." }
    $gatewayPort = Get-FreeTcpPort
    $vibepolloPort = Get-FreeTcpPort
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $currentSid = $currentIdentity.User.Value
    [ordered]@{
        listen_port = $gatewayPort
        clients = @()
        profiles = [ordered]@{
            default = [ordered]@{
                name = "Diagnostic test"
                owner = $currentIdentity.Name
                owner_sid = $currentSid
                windows_account_sid = $currentSid
                profile_root = $profile
                discord_bridge = "http://127.0.0.1:8765"
                vibepollo_bridge = "http://127.0.0.1:$vibepolloPort"
                game_provider_bridge = ""
            }
            foreign = [ordered]@{
                name = "Foreign profile"
                # Account names are display-only; an identical name must not
                # override a different authoritative SID.
                owner = $currentIdentity.Name
                owner_sid = "S-1-5-21-100-200-300-4999"
                windows_account_sid = "S-1-5-21-100-200-300-4999"
                profile_root = $foreignProfile
            }
            child = [ordered]@{
                id = "child"
                kind = "child"
                name = "Child profile"
                enabled = $true
                parent_profile_id = "default"
                allowed_game_keys = @()
                policy_revision = 0
            }
        }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gateway "gateway.json") -Encoding UTF8
    # Fresh profiles intentionally start without a pinned Python path. This must
    # fall through to discovery instead of reaching Test-Path with an empty value.
    [ordered]@{
        base_url = "https://127.0.0.1:47990"
        listen_port = $vibepolloPort
        python_path = ""
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $profile `
        "vibepollo\config.json") -Encoding UTF8
    [ordered]@{ version = "test"; build = "test"; protocol_version = 1 } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "version.json") -Encoding UTF8
    New-Item -ItemType File -Path (Join-Path $gateway "gateway-manually-stopped") -Force | Out-Null

    @'
@echo off
set "LT=<"
set "GT=>"
if /I "%MOONWAKER_TEST_LEGENDARY_MODE%"=="placeholder" (
    echo {"account":"%LT%not logged in%GT%"}
    exit /b 0
)
if /I "%MOONWAKER_TEST_LEGENDARY_MODE%"=="empty" (
    echo {"account":""}
    exit /b 0
)
if /I "%MOONWAKER_TEST_LEGENDARY_MODE%"=="null" (
    echo {"account":null}
    exit /b 0
)
if /I "%MOONWAKER_TEST_LEGENDARY_MODE%"=="real" (
    echo {"account":"epic-test-user"}
    exit /b 0
)
exit /b 7
'@ | Set-Content -LiteralPath $fakeLegendary -Encoding ASCII
    $tokens = $null
    $parseErrors = $null
    $sourceAst = [System.Management.Automation.Language.Parser]::ParseInput(
        $hostControlSource, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) { throw "Host Control source parsing failed." }
    $legendaryFunction = @($sourceAst.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -eq "Test-LegendaryConnection"
        }, $true)) | Select-Object -First 1
    if ($null -eq $legendaryFunction) {
        throw "Test-LegendaryConnection was not found in Host Control source."
    }
    $legendaryFunctionText = $legendaryFunction.Extent.Text
    $legendaryContract = [scriptblock]::Create(@"
param([string]`$executablePath, [string]`$profileRoot)
function Get-LegendaryExecutable { return `$executablePath }
$legendaryFunctionText
`$previousMode = `$env:MOONWAKER_TEST_LEGENDARY_MODE
try {
    function Assert-LegendaryConnection([string]`$mode, [bool]`$expected) {
        `$env:MOONWAKER_TEST_LEGENDARY_MODE = `$mode
        `$env:LEGENDARY_CONFIG_PATH = "moonwaker-test-sentinel"
        `$actual = Test-LegendaryConnection `$profileRoot
        if ([bool]`$actual -ne `$expected) {
            throw "Legendary status mode '`$mode' returned '`$actual'; expected '`$expected'."
        }
        if (`$env:LEGENDARY_CONFIG_PATH -ne "moonwaker-test-sentinel") {
            throw "Legendary config path was not restored after status probing."
        }
    }
    Assert-LegendaryConnection "placeholder" `$false
    Assert-LegendaryConnection "empty" `$false
    Assert-LegendaryConnection "null" `$false
    Assert-LegendaryConnection "real" `$true
    Assert-LegendaryConnection "failure" `$false
} finally {
    `$env:MOONWAKER_TEST_LEGENDARY_MODE = `$previousMode
}
"@)
    & $legendaryContract $fakeLegendary $profile

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
    if (-not (Test-Path -LiteralPath (Join-Path $profile "game-provider\steam-web-api-key.dpapi") -PathType Leaf)) {
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

    $discordId = "123456789012345678"
    $discordSecret = [guid]::NewGuid().ToString("N")
    $vibepolloToken = [guid]::NewGuid().ToString("N")
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Action ConfigureIntegrations ' +
        '-ProfileId default -IntegrationDataProtectedFromEnvironment -ResultPath "{1}"'
    $arguments = $arguments -f $scriptPath, $resultPath
    $startInfo = [Diagnostics.ProcessStartInfo]::new("powershell.exe", $arguments)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.EnvironmentVariables["MOONWAKER_DISCORD_CONFIGURE"] = "1"
    $startInfo.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_ID"] = $discordId
    $startInfo.EnvironmentVariables["MOONWAKER_DISCORD_CLIENT_SECRET_PROTECTED"] = `
        Protect-TestValue $discordSecret
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CONFIGURE"] = "1"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_URL"] = "https://127.0.0.1:47990"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_TOKEN_PROTECTED"] = `
        Protect-TestValue $vibepolloToken
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CREATE_TOKEN"] = "0"
    $process = [Diagnostics.Process]::Start($startInfo)
    if (-not $process.WaitForExit(10000)) {
        try { $process.Kill() } catch {}
        throw "Integration configuration timed out."
    }
    $integration = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if (-not $integration.ok -or -not $integration.discord_configured -or
        -not $integration.vibepollo_configured) {
        throw "Integration configuration failed: $($integration.error)"
    }
    $discordConfig = Get-Content -LiteralPath (Join-Path $profile `
        "discord\discord_bridge_config.json") -Raw | ConvertFrom-Json
    $vibepolloConfig = Get-Content -LiteralPath (Join-Path $profile `
        "vibepollo\config.json") -Raw | ConvertFrom-Json
    if ([string]$discordConfig.client_id -ne $discordId -or $discordConfig.port -ne 8765 -or
        [string]$vibepolloConfig.base_url -ne "https://127.0.0.1:47990" -or
        $vibepolloConfig.listen_port -ne $vibepolloPort) {
        throw "Integration configuration did not preserve the profile endpoints."
    }
    $savedDiscord = [pscredential]::new("discord", ((Get-Content -LiteralPath (Join-Path $profile `
        "discord\client_secret.dpapi") -Raw).Trim() | ConvertTo-SecureString)).GetNetworkCredential().Password
    $savedVibepollo = [pscredential]::new("vibepollo", ((Get-Content -LiteralPath (Join-Path $profile `
        "vibepollo\api_token.dpapi") -Raw).Trim() | ConvertTo-SecureString)).GetNetworkCredential().Password
    if ($savedDiscord -ne $discordSecret -or $savedVibepollo -ne $vibepolloToken) {
        throw "Integration secrets were not stored with CurrentUser DPAPI."
    }

    $customVibepolloUrl = "https://localhost:48990"
    $vibepolloConfig.base_url = $customVibepolloUrl
    $vibepolloConfig | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $profile `
        "vibepollo\config.json") -Encoding UTF8
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) `
        "bridges\vibepollo\moonwaker-token-scopes.example.json") `
        -Destination (Join-Path $profile "vibepollo\moonwaker-token-scopes.example.json")
    $autoToken = "auto-" + [guid]::NewGuid().ToString("N")
    $fakeTransport = @'
import json
import sys

request = json.load(sys.stdin)
if request.get("username") == "reject":
    print(json.dumps({"ok": False, "status": 401, "error": "rejected"}))
    raise SystemExit(2)
valid = (
    request.get("base_url") == "https://localhost:48990"
    and request.get("path") == "/api/token"
    and request.get("method") == "POST"
    and request.get("username") == "admin"
    and request.get("password") == "test-password"
    and request.get("body", {}).get("scopes")
)
if not valid:
    print(json.dumps({"ok": False, "status": 422, "error": "bad request"}))
    raise SystemExit(2)
print(json.dumps({"ok": True, "status": 200,
                  "content": json.dumps({"token": "__AUTO_TOKEN__"})}))
'@.Replace("__AUTO_TOKEN__", $autoToken)
    [IO.File]::WriteAllText((Join-Path $profile "vibepollo\VibepolloTransport.py"),
        $fakeTransport, [Text.UTF8Encoding]::new($false))

    $startInfo = [Diagnostics.ProcessStartInfo]::new("powershell.exe", $arguments)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.EnvironmentVariables["MOONWAKER_DISCORD_CONFIGURE"] = "0"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CONFIGURE"] = "1"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_URL"] = ""
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CREATE_TOKEN"] = "1"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_USERNAME"] = "admin"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD_PROTECTED"] = `
        Protect-TestValue "test-password"
    $process = [Diagnostics.Process]::Start($startInfo)
    if (-not $process.WaitForExit(10000)) {
        try { $process.Kill() } catch {}
        throw "Automatic Vibepollo token configuration timed out."
    }
    $autoIntegration = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    $savedVibepollo = [pscredential]::new("vibepollo", ((Get-Content -LiteralPath (Join-Path $profile `
        "vibepollo\api_token.dpapi") -Raw).Trim() | ConvertTo-SecureString)).GetNetworkCredential().Password
    $vibepolloConfig = Get-Content -LiteralPath (Join-Path $profile `
        "vibepollo\config.json") -Raw | ConvertFrom-Json
    if (-not $autoIntegration.ok -or $savedVibepollo -ne $autoToken -or
        [string]$vibepolloConfig.base_url -ne $customVibepolloUrl -or
        -not (Test-Path -LiteralPath ([string]$vibepolloConfig.python_path) -PathType Leaf)) {
        throw "Automatic Vibepollo token configuration did not preserve the URL or store the token."
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new("powershell.exe", $arguments)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.EnvironmentVariables["MOONWAKER_DISCORD_CONFIGURE"] = "0"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CONFIGURE"] = "1"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_URL"] = ""
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_CREATE_TOKEN"] = "1"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_USERNAME"] = "reject"
    $startInfo.EnvironmentVariables["MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD_PROTECTED"] = `
        Protect-TestValue "test-password"
    $process = [Diagnostics.Process]::Start($startInfo)
    if (-not $process.WaitForExit(10000)) {
        try { $process.Kill() } catch {}
        throw "Rejected Vibepollo token configuration timed out."
    }
    $rejectedIntegration = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if ($rejectedIntegration.ok -or [string]$rejectedIntegration.error -notmatch 'HTTP 401') {
        throw "Vibepollo token rejection did not preserve the HTTP status."
    }

    $savedDiscord = $null
    $savedVibepollo = $null
    $discordSecret = $null
    $vibepolloToken = $null
    $autoToken = $null

    New-Item -ItemType Directory -Path (Join-Path $gateway "logs"),
        (Join-Path $profile "game-provider\logs") -Force | Out-Null
    $diagnosticFiles = @{
        (Join-Path $gateway "gateway-supervisor.jsonl") = '{"event":"supervisor.started"}'
        (Join-Path $gateway "gateway-supervisor.jsonl.1") = '{"event":"supervisor.stopped"}'
        (Join-Path $gateway "logs\gateway-diagnostics.jsonl") = '{"event":"request.failed"}'
        (Join-Path $gateway "logs\gateway-diagnostics.jsonl.1") = '{"event":"request.started"}'
        (Join-Path $profile "profile-bridge.jsonl") = '{"event":"component.exited"}'
        (Join-Path $profile "profile-bridge.jsonl.2") = '{"event":"supervisor.started"}'
        (Join-Path $profile "game-provider\logs\provider-diagnostics.jsonl") = '{"event":"lifecycle"}'
        (Join-Path $profile "game-provider\logs\provider-diagnostics.jsonl.3") = '{"event":"request.failed"}'
    }
    foreach ($entry in $diagnosticFiles.GetEnumerator()) {
        [IO.File]::WriteAllText($entry.Key, $entry.Value, [Text.UTF8Encoding]::new($false))
    }
    Set-Content -LiteralPath (Join-Path $gateway "gateway-supervisor.jsonl.secret") `
        -Value "SECRET-MATERIAL"
    Set-Content -LiteralPath (Join-Path $profile "game-provider\logs\provider-diagnostics.jsonl.bak") `
        -Value "SECRET-MATERIAL"
    Set-Content -LiteralPath (Join-Path $profile "private-config.json") -Value "SECRET-MATERIAL"
    $exportDirectory = Join-Path $root "exports"
    & $scriptPath -Action ExportDiagnostics -DiagnosticsOutputDirectory $exportDirectory `
        -ResultPath $resultPath | Out-Null
    $export = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if (-not $export.ok -or $export.files -ne 8 -or
        -not (Test-Path -LiteralPath $export.path -PathType Leaf)) {
        throw "Diagnostic export did not return the expected archive."
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead([string]$export.path)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        $expected = @(
            "gateway/supervisor/gateway-supervisor.jsonl",
            "gateway/supervisor/gateway-supervisor.jsonl.1",
            "gateway/service/gateway-diagnostics.jsonl",
            "gateway/service/gateway-diagnostics.jsonl.1",
            "profiles/default/supervisor/profile-bridge.jsonl",
            "profiles/default/supervisor/profile-bridge.jsonl.2",
            "profiles/default/provider/provider-diagnostics.jsonl",
            "profiles/default/provider/provider-diagnostics.jsonl.3",
            "manifest.json")
        foreach ($name in $expected) {
            if ($names -notcontains $name) { throw "Missing diagnostic archive entry '$name'." }
        }
        if ($names.Count -ne $expected.Count) {
            throw "Diagnostic archive contains unexpected entries."
        }
        if (@($names | Where-Object {
                $_ -match 'secret|config|\.bak|^[A-Za-z]:|^[\\/]|(^|/)\.\.(/|$)'
            }).Count) {
            throw "Diagnostic archive contains a forbidden entry."
        }
        $manifestEntry = @($archive.Entries | Where-Object FullName -eq "manifest.json")[0]
        $reader = [IO.StreamReader]::new($manifestEntry.Open())
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($manifest.schema_version -ne 1 -or $manifest.file_count -ne 8 -or
            $manifest.version -ne "test" -or $manifest.build -ne "test" -or
            $manifest.PSObject.Properties["path"] -or
            $manifest.PSObject.Properties["profiles"]) {
            throw "Diagnostic manifest contains unexpected data."
        }
        foreach ($entry in @($archive.Entries | Where-Object { $_.Length -gt 0 })) {
            $reader = [IO.StreamReader]::new($entry.Open())
            try {
                if ($reader.ReadToEnd().Contains("SECRET-MATERIAL")) {
                    throw "Diagnostic archive contains secret material."
                }
            } finally { $reader.Dispose() }
        }
    } finally { $archive.Dispose() }

    & $scriptPath -Action Status -ResultPath $resultPath | Out-Null
    $status = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    $current = @($status.profiles | Where-Object id -eq "default")[0]
    $foreign = @($status.profiles | Where-Object id -eq "foreign")[0]
    $child = @($status.profiles | Where-Object id -eq "child")
    if ($child.Count -ne 0) {
        throw "Host Control exposed a child profile as a Windows account."
    }
    if (-not $current.current_user -or $foreign.current_user) {
        throw "Host Control did not correlate profiles with their Windows owner."
    }
    if ($foreign.discord -ne "disabled" -or $foreign.vibepollo -ne "disabled" -or
        $foreign.game_provider -ne "disabled") {
        throw "Host Control did not tolerate missing optional standard profile endpoints."
    }
    if (-not $current.discord_configured -or -not $current.vibepollo_configured) {
        throw "Host Control status did not report configured profile integrations."
    }
    if ($status.microphone.ready -or $status.microphone.reason -ne "worker_missing" -or
        $status.microphone.PSObject.Properties["path"] -or
        $status.microphone.PSObject.Properties["error"]) {
        throw "Host Control did not return the expected safe microphone readiness."
    }

    [ordered]@{ profile_id = "default"; updated_at = 1 } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $gateway "runtime-status.json") -Encoding UTF8
    $closeRequestPath = Join-Path $root "close-stream-request.txt"
    $closeReadyPath = Join-Path $root "close-stream-port.txt"
    $closeListenerScript = Join-Path $root "close-listener.ps1"
    @'
param([int]$Port, [string]$RequestPath, [string]$ReadyPath)
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
$client = $null
try {
    $listener.Start()
    [IO.File]::WriteAllText($ReadyPath,
        ([Net.IPEndPoint]$listener.LocalEndpoint).Port.ToString(), [Text.Encoding]::ASCII)
    $client = $listener.AcceptTcpClient()
    $stream = $client.GetStream()
    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 1024, $true)
    $firstLine = $reader.ReadLine()
    while (-not [string]::IsNullOrEmpty($reader.ReadLine())) {}
    [IO.File]::WriteAllText($RequestPath, $firstLine, [Text.Encoding]::ASCII)
    $body = '{"ok":true}'
    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)
    $headers = "HTTP/1.1 200 OK`r`nContent-Type: application/json`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
    $headerBytes = [Text.Encoding]::ASCII.GetBytes($headers)
    $stream.Write($headerBytes, 0, $headerBytes.Length)
    $stream.Write($bodyBytes, 0, $bodyBytes.Length)
    $stream.Flush()
} finally {
    if ($client) { $client.Dispose() }
    $listener.Stop()
}
'@ | Set-Content -LiteralPath $closeListenerScript -Encoding UTF8
    $closeListenerProcess = Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -PassThru `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $closeListenerScript,
            "-Port", 0, "-RequestPath", $closeRequestPath, "-ReadyPath", $closeReadyPath)
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    while ([DateTime]::UtcNow -lt $deadline -and
        -not (Test-Path -LiteralPath $closeReadyPath) -and -not $closeListenerProcess.HasExited) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $closeReadyPath)) {
        throw "The close-stream test listener did not start."
    }
    $closePort = [int](Get-Content -LiteralPath $closeReadyPath -Raw)
    $gatewayConfig = Get-Content -LiteralPath (Join-Path $gateway "gateway.json") -Raw |
        ConvertFrom-Json
    $gatewayConfig.profiles.default.vibepollo_bridge = "http://127.0.0.1:$closePort"
    $gatewayConfig | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $gateway "gateway.json") -Encoding UTF8
    & $scriptPath -Action CloseStream -ResultPath $resultPath | Out-Null
    $closeResult = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    $closeListenerExited = $closeListenerProcess.WaitForExit(5000)
    $closeRequest = Get-Content -LiteralPath $closeRequestPath -Raw -ErrorAction SilentlyContinue
    if (-not $closeResult.ok -or -not $closeListenerExited -or
        $closeRequest -ne "POST /action/close-app HTTP/1.1") {
        throw "Host Control did not send the close-stream request to the active local profile: error=$($closeResult.error); listener_exited=$closeListenerExited; request=$closeRequest"
    }
    $closeListenerProcess = $null

    # A stale runtime-status hint must not choose between profiles of one account.
    $ambiguousConfig = Get-Content -LiteralPath (Join-Path $gateway "gateway.json") -Raw |
        ConvertFrom-Json
    $ambiguousConfig.profiles.foreign.owner_sid = $currentSid
    $ambiguousConfig.profiles.foreign.windows_account_sid = $currentSid
    $ambiguousConfig | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $gateway "gateway.json") -Encoding UTF8
    & $scriptPath -Action CloseStream -ResultPath $resultPath | Out-Null
    $ambiguousClose = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if ($ambiguousClose.ok -or [string]$ambiguousClose.error -notmatch 'jednoznacznie') {
        throw "Host Control selected a close target from the runtime-status hint: error=$($ambiguousClose.error)"
    }
    $gatewayConfig | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $gateway "gateway.json") -Encoding UTF8

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
    Write-Output "Host Control Discord and Vibepollo configuration test passed."
    Write-Output "Host Control diagnostic export test passed."
    Write-Output "Host Control ownership and correlated Gateway stop tests passed."
    Write-Output "Host Control stream hotkey action test passed."
} finally {
    if ($listenerProcess -and -not $listenerProcess.HasExited) {
        Stop-Process -Id $listenerProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($closeListenerProcess -and -not $closeListenerProcess.HasExited) {
        Stop-Process -Id $closeListenerProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item Env:MOONWAKER_STEAM_WEB_API_PROTECTED -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
