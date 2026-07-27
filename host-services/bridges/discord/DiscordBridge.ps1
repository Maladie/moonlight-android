#requires -Version 5.1
<#
DiscordBridge.ps1

Local-only HTTP-to-Discord-RPC bridge for a Unified Remote custom remote.

Endpoints (127.0.0.1 only):
  GET /health
  GET /authorize
  GET /guilds
  GET /channels?guild_id=...
  GET /current
  GET /join?channel_id=...
  GET /leave
  GET /voice
  GET /mute?value=toggle|true|false
  GET /deafen?value=toggle|true|false
  GET /repair-status
  GET /repair-rpc
  GET /start-discord
  GET /repair-discord
  GET /repair-virtualhere
  GET /repair-bridge
  GET /diagnostics
  GET /shutdown

The bridge uses Discord RPC over Windows named pipes:
  \\?\pipe\discord-ipc-0 ... discord-ipc-9
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigPath = Join-Path $ScriptRoot "discord_bridge_config.json"
$SecretPath = Join-Path $ScriptRoot "client_secret.dpapi"
$TokenPath = Join-Path $ScriptRoot "oauth_token.dpapi"
$LogDir = Join-Path $ScriptRoot "logs"

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$LogPath = Join-Path $LogDir ("bridge_{0}.log" -f (Get-Date -Format "yyyyMMdd"))

$script:RpcStream = $null
$script:RpcPipeName = $null
$script:RpcAuthenticated = $false
$script:LastRpcError = ""
$script:Running = $true
$script:LastHealthRpcAttempt = [datetime]::MinValue
$script:HealthRpcRetrySeconds = 10

# DISCORD_BRIDGE_ADVANCED_V1
$StatePath = Join-Path $ScriptRoot "discord_remote_state.json"
$script:SpeakingUsers = @{}
$script:SpeakingChannelId = ""
$script:CurrentUserId = ""
$script:VoiceSnapshotCache = $null
$script:VoiceSnapshotAt = [datetime]::MinValue
$script:GuildCache = @()
$script:GuildCacheAt = [datetime]::MinValue
$script:ChannelCache = @{}
$script:VoiceSnapshotTtlMs = 2000
$script:GuildCacheTtlSeconds = 300
$script:ChannelCacheTtlSeconds = 30
$script:VirtualHereCache = $null
$script:VirtualHereCacheAt = [datetime]::MinValue
$script:VirtualHereCacheTtlMs = 1500
$script:WindowsAudioAvailable = $false
$script:WindowsAudioError = ""

$windowsAudioSource = Join-Path $ScriptRoot "WindowsAudio.cs"
try {
    if (-not (Test-Path -LiteralPath $windowsAudioSource)) {
        throw "Missing WindowsAudio.cs"
    }
    Add-Type -Path $windowsAudioSource
    $script:WindowsAudioAvailable = $true
}
catch {
    $script:WindowsAudioError = $_.Exception.Message
    Add-Content -LiteralPath $LogPath -Encoding UTF8 -Value (
        "{0:yyyy-MM-dd HH:mm:ss.fff} [ERROR] PID={1} Load Windows Audio helper: {2}" -f `
        (Get-Date), $PID, $_.Exception.Message
    )
}

function Write-BridgeLog {
    param(
        [Parameter(Mandatory)][string]$Message,
        [ValidateSet("TRACE","INFO","WARN","ERROR")][string]$Level = "INFO"
    )

    $line = "{0:yyyy-MM-dd HH:mm:ss.fff} [{1}] PID={2} {3}" -f `
        (Get-Date), $Level, $PID, $Message

    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
}

function Write-BridgeException {
    param(
        [string]$Context,
        [System.Exception]$Exception
    )

    Write-BridgeLog -Level "ERROR" -Message (
        "$Context | $($Exception.GetType().FullName): $($Exception.Message) | " +
        "HResult=0x{0:X8} | Stack={1}" -f $Exception.HResult, $Exception.StackTrace
    )
}

function Read-Config {
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw "Brak konfiguracji: $ConfigPath. Uruchom Configure-DiscordBridge.ps1."
    }

    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json

    if ([string]::IsNullOrWhiteSpace([string]$config.client_id)) {
        throw "client_id jest pusty w $ConfigPath."
    }

    return $config
}

function Protect-Text {
    param([Parameter(Mandatory)][string]$PlainText)

    $secure = ConvertTo-SecureString -String $PlainText -AsPlainText -Force
    return ConvertFrom-SecureString -SecureString $secure
}

function Unprotect-TextFile {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }

    $cipher = (Get-Content -LiteralPath $Path -Raw).Trim()

    if ([string]::IsNullOrWhiteSpace($cipher)) {
        return $null
    }

    $secure = ConvertTo-SecureString -String $cipher
    $credential = [System.Management.Automation.PSCredential]::new("x", $secure)
    return $credential.GetNetworkCredential().Password
}

function Save-ProtectedTextFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$PlainText
    )

    Protect-Text -PlainText $PlainText |
        Set-Content -LiteralPath $Path -Encoding ASCII
}

function Load-TokenObject {
    try {
        $json = Unprotect-TextFile -Path $TokenPath

        if ([string]::IsNullOrWhiteSpace($json)) {
            return $null
        }

        return $json | ConvertFrom-Json
    }
    catch {
        Write-BridgeException -Context "Load-TokenObject" -Exception $_.Exception
        return $null
    }
}

function Save-TokenObject {
    param([Parameter(Mandatory)]$Token)

    $json = $Token | ConvertTo-Json -Compress -Depth 10
    Save-ProtectedTextFile -Path $TokenPath -PlainText $json
    Write-BridgeLog "OAuth token zapisany przez DPAPI dla użytkownika $env:USERDOMAIN\$env:USERNAME."
}

function Clear-Token {
    Remove-Item -LiteralPath $TokenPath -Force -ErrorAction SilentlyContinue
    Write-BridgeLog -Level "WARN" -Message "Usunięto zapisany OAuth token."
}

function Read-Exactly {
    param(
        [Parameter(Mandatory)][System.IO.Stream]$Stream,
        [Parameter(Mandatory)][int]$Count
    )

    $buffer = New-Object byte[] $Count
    $offset = 0

    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)

        if ($read -le 0) {
            throw "Discord RPC zamknął potok podczas odczytu ($offset/$Count bajtów)."
        }

        $offset += $read
    }

    return $buffer
}

function Write-RpcFrame {
    param(
        [Parameter(Mandatory)][int]$Opcode,
        [Parameter(Mandatory)]$Payload
    )

    if ($null -eq $script:RpcStream -or -not $script:RpcStream.IsConnected) {
        throw "Potok Discord RPC nie jest połączony."
    }

    $json = $Payload | ConvertTo-Json -Compress -Depth 30
    $body = [System.Text.Encoding]::UTF8.GetBytes($json)
    $opcodeBytes = [BitConverter]::GetBytes([int]$Opcode)
    $lengthBytes = [BitConverter]::GetBytes([int]$body.Length)

    $script:RpcStream.Write($opcodeBytes, 0, 4)
    $script:RpcStream.Write($lengthBytes, 0, 4)
    $script:RpcStream.Write($body, 0, $body.Length)
    $script:RpcStream.Flush()

    # Ramka handshake (opcode 0) zawiera tylko v i client_id, bez pola cmd.
    # Przy Set-StrictMode bezpoĹ›redni odczyt $Payload.cmd rzuca wyjÄ…tek.
    $cmdProperty = $Payload.PSObject.Properties["cmd"]
    $cmd = if ($null -ne $cmdProperty) { [string]$cmdProperty.Value } else { "" }
    $safeJson = $json

    if ($cmd -eq "AUTHENTICATE") {
        $safeJson = '{"cmd":"AUTHENTICATE","args":{"access_token":"<redacted>"},"nonce":"<redacted>"}'
    }

    Write-BridgeLog -Level "TRACE" -Message (
        "RPC SEND opcode=$Opcode bytes=$($body.Length) payload=$safeJson"
    )
}

function Read-RpcFrame {
    if ($null -eq $script:RpcStream -or -not $script:RpcStream.IsConnected) {
        throw "Potok Discord RPC nie jest połączony."
    }

    $header = Read-Exactly -Stream $script:RpcStream -Count 8
    $opcode = [BitConverter]::ToInt32($header, 0)
    $length = [BitConverter]::ToInt32($header, 4)

    if ($length -lt 0 -or $length -gt 16777216) {
        throw "Nieprawidłowa długość ramki Discord RPC: $length."
    }

    $body = Read-Exactly -Stream $script:RpcStream -Count $length
    $json = [System.Text.Encoding]::UTF8.GetString($body)

    Write-BridgeLog -Level "TRACE" -Message (
        "RPC RECV opcode=$opcode bytes=$length payload=$json"
    )

    $payload = $null

    if (-not [string]::IsNullOrWhiteSpace($json)) {
        $payload = $json | ConvertFrom-Json
    }

    return [pscustomobject]@{
        Opcode = $opcode
        Payload = $payload
        Raw = $json
    }
}

function Close-Rpc {
    try {
        if ($null -ne $script:RpcStream) {
            $script:RpcStream.Dispose()
        }
    }
    catch {
        Write-BridgeException -Context "Close-Rpc" -Exception $_.Exception
    }

    $script:RpcStream = $null
    $script:RpcPipeName = $null
    $script:RpcAuthenticated = $false
}

function Connect-RpcPipe {
    param([Parameter(Mandatory)]$Config)

    Close-Rpc
    $bridgeSession = (Get-Process -Id $PID).SessionId
    $discordInSession = Get-Process -Name "Discord" -ErrorAction SilentlyContinue |
        Where-Object { $_.SessionId -eq $bridgeSession } |
        Select-Object -First 1
    if ($null -eq $discordInSession) {
        throw "Discord is not running in this Windows profile session. Start Discord from MoonWaker, then retry."
    }
    $accessDenied = $false

    for ($i = 0; $i -le 9; $i++) {
        $pipeName = "discord-ipc-$i"
        $pipe = [System.IO.Pipes.NamedPipeClientStream]::new(
            ".",
            $pipeName,
            [System.IO.Pipes.PipeDirection]::InOut,
            [System.IO.Pipes.PipeOptions]::None
        )

        try {
            Write-BridgeLog -Level "TRACE" -Message "Próba połączenia z $pipeName."
            $pipe.Connect(400)

            if ($pipe.IsConnected) {
                $pipe.ReadMode = [System.IO.Pipes.PipeTransmissionMode]::Byte
                $script:RpcStream = $pipe
                $script:RpcPipeName = $pipeName

                Write-BridgeLog "Połączono z Discord RPC przez $pipeName."

                Write-RpcFrame -Opcode 0 -Payload ([ordered]@{
                    v = 1
                    client_id = [string]$Config.client_id
                })

                while ($true) {
                    $frame = Read-RpcFrame

                    if ($frame.Opcode -eq 3) {
                        Write-RpcFrame -Opcode 4 -Payload $frame.Payload
                        continue
                    }

                    if ($frame.Opcode -eq 2) {
                        throw "Discord zamknął RPC podczas handshake: $($frame.Raw)"
                    }

                    if ($frame.Opcode -eq 1 -and
                        $null -ne $frame.Payload -and
                        [string]$frame.Payload.evt -eq "READY") {

                        $script:CurrentUserId = [string]$frame.Payload.data.user.id
                        Write-BridgeLog "Discord RPC handshake READY."
                        return
                    }
                }
            }
        }
        catch {
            if ($_.Exception -is [System.UnauthorizedAccessException] -or
                $_.Exception.Message -match "access.*denied|odmowa dost.pu") {
                $accessDenied = $true
            }
            try { $pipe.Dispose() } catch {}
            Write-BridgeLog -Level "TRACE" -Message (
                "Brak połączenia z ${pipeName}: $($_.Exception.Message)"
            )
        }
    }

    if ($accessDenied) {
        throw "Discord RPC is owned by another Windows profile. Fully exit Discord in the other profile, then start it in this Bridge profile."
    }

    throw "Nie znaleziono aktywnego potoku discord-ipc-0..9. Uruchom klienta Discord."
}

function Test-RpcError {
    param($Payload)

    if ($null -eq $Payload) {
        return
    }

    $isError = ([string]$Payload.evt -eq "ERROR")

    if ($isError) {
        $code = ""
        $message = "Nieznany błąd Discord RPC"

        if ($null -ne $Payload.data) {
            if ($null -ne $Payload.data.code) {
                $code = [string]$Payload.data.code
            }

            if ($null -ne $Payload.data.message) {
                $message = [string]$Payload.data.message
            }
        }

        throw "Discord RPC error ${code}: $message"
    }
}

function Invoke-RpcCommand {
    param(
        [Parameter(Mandatory)][string]$Command,
        [hashtable]$Arguments = @{},
        [string]$EventName = $null
    )

    if ($null -eq $script:RpcStream -or -not $script:RpcStream.IsConnected) {
        throw "Discord RPC nie jest połączony."
    }

    $nonce = [Guid]::NewGuid().ToString()

    $payload = [ordered]@{
        cmd = $Command
        args = $Arguments
        nonce = $nonce
    }

    if (-not [string]::IsNullOrWhiteSpace($EventName)) {
        $payload.evt = $EventName
    }

    Write-RpcFrame -Opcode 1 -Payload $payload

    while ($true) {
        $frame = Read-RpcFrame

        if ($frame.Opcode -eq 3) {
            Write-RpcFrame -Opcode 4 -Payload $frame.Payload
            continue
        }

        if ($frame.Opcode -eq 2) {
            throw "Discord zamknął połączenie RPC: $($frame.Raw)"
        }

        if ($frame.Opcode -ne 1 -or $null -eq $frame.Payload) {
            continue
        }

        Test-RpcError -Payload $frame.Payload
        Handle-RpcDispatch -Payload $frame.Payload

        $frameNonce = [string](Get-ObjectValue $frame.Payload "nonce" "")
        if ($frameNonce -eq $nonce) {
            return $frame.Payload
        }

        $frameCommand = [string](Get-ObjectValue $frame.Payload "cmd" "")
        $frameEvent = [string](Get-ObjectValue $frame.Payload "evt" "")
        Write-BridgeLog -Level "TRACE" -Message (
            "PominiÄ™to event/odpowiedĹş z innym nonce. cmd=$frameCommand evt=$frameEvent"
        )
    }
}

function Invoke-TokenRequest {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][hashtable]$Body
    )

    $clientSecret = Unprotect-TextFile -Path $SecretPath

    if ([string]::IsNullOrWhiteSpace($clientSecret)) {
        throw "Brak client_secret.dpapi. Uruchom Configure-DiscordBridge.ps1."
    }

    $Body.client_id = [string]$Config.client_id
    $Body.client_secret = $clientSecret

    Write-BridgeLog "Wywołuję Discord OAuth token endpoint (sekrety nie są logowane)."

    return Invoke-RestMethod `
        -Method Post `
        -Uri "https://discord.com/api/oauth2/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body $Body
}

function Refresh-OAuthToken {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]$Token
    )

    if ($null -eq $Token.refresh_token -or
        [string]::IsNullOrWhiteSpace([string]$Token.refresh_token)) {

        throw "Token nie zawiera refresh_token."
    }

    $body = @{
        grant_type = "refresh_token"
        refresh_token = [string]$Token.refresh_token
    }

    if (-not [string]::IsNullOrWhiteSpace([string]$Config.redirect_uri)) {
        $body.redirect_uri = [string]$Config.redirect_uri
    }

    $newToken = Invoke-TokenRequest -Config $Config -Body $body
    Save-TokenObject -Token $newToken
    return $newToken
}

function Request-NewOAuthToken {
    param([Parameter(Mandatory)]$Config)

    $scopes = @($Config.scopes | ForEach-Object { [string]$_ })

    Write-BridgeLog -Level "WARN" -Message (
        "Żądam autoryzacji Discord RPC. W kliencie Discord powinien pojawić się modal. " +
        "Scopes=$($scopes -join ' ')"
    )

    $authorizeResponse = Invoke-RpcCommand `
        -Command "AUTHORIZE" `
        -Arguments @{
            client_id = [string]$Config.client_id
            scopes = $scopes
        }

    $code = [string]$authorizeResponse.data.code

    if ([string]::IsNullOrWhiteSpace($code)) {
        throw "AUTHORIZE nie zwrócił kodu OAuth."
    }

    $body = @{
        grant_type = "authorization_code"
        code = $code
    }

    if (-not [string]::IsNullOrWhiteSpace([string]$Config.redirect_uri)) {
        $body.redirect_uri = [string]$Config.redirect_uri
    }

    $token = Invoke-TokenRequest -Config $Config -Body $body
    Save-TokenObject -Token $token
    return $token
}

function Authenticate-Rpc {
    param([Parameter(Mandatory)]$Config)

    $token = Load-TokenObject

    if ($null -ne $token -and
        -not [string]::IsNullOrWhiteSpace([string]$token.access_token)) {

        try {
            Write-BridgeLog "Próba AUTHENTICATE zapisanym access_token."
            $response = Invoke-RpcCommand `
                -Command "AUTHENTICATE" `
                -Arguments @{ access_token = [string]$token.access_token }

            $script:RpcAuthenticated = $true
            Write-BridgeLog "Discord RPC AUTHENTICATE zakończone powodzeniem."
            return
        }
        catch {
            Write-BridgeException -Context "AUTHENTICATE zapisanym tokenem" -Exception $_.Exception

            if ($null -ne $token.refresh_token -and
                -not [string]::IsNullOrWhiteSpace([string]$token.refresh_token)) {

                try {
                    Write-BridgeLog -Level "WARN" -Message "Próba odświeżenia OAuth tokenu."
                    $token = Refresh-OAuthToken -Config $Config -Token $token

                    $response = Invoke-RpcCommand `
                        -Command "AUTHENTICATE" `
                        -Arguments @{ access_token = [string]$token.access_token }

                    $script:RpcAuthenticated = $true
                    Write-BridgeLog "AUTHENTICATE po refresh zakończone powodzeniem."
                    return
                }
                catch {
                    Write-BridgeException -Context "Refresh token / AUTHENTICATE" -Exception $_.Exception
                }
            }

            Clear-Token
        }
    }

    $token = Request-NewOAuthToken -Config $Config

    $response = Invoke-RpcCommand `
        -Command "AUTHENTICATE" `
        -Arguments @{ access_token = [string]$token.access_token }

    $script:RpcAuthenticated = $true
    Write-BridgeLog "Nowa autoryzacja i AUTHENTICATE zakończone powodzeniem."
}

function Ensure-Rpc {
    $config = Read-Config

    if ($null -eq $script:RpcStream -or
        -not $script:RpcStream.IsConnected -or
        -not $script:RpcAuthenticated) {

        try {
            Connect-RpcPipe -Config $config
            Authenticate-Rpc -Config $config
            $script:LastRpcError = ""
        }
        catch {
            $script:LastRpcError = $_.Exception.Message
            Close-Rpc
            throw
        }
    }

    return $config
}

function Try-EnsureRpcForHealth {
    $connected = ($null -ne $script:RpcStream -and
        $script:RpcStream.IsConnected -and $script:RpcAuthenticated)
    if ($connected) { return }

    $now = Get-Date
    if (($now - $script:LastHealthRpcAttempt).TotalSeconds -lt
        $script:HealthRpcRetrySeconds) { return }
    $script:LastHealthRpcAttempt = $now

    # Health checks must reconnect an already-authorized Bridge, but must not
    # surprise the user with a Discord OAuth modal during background polling.
    if (-not (Test-Path -LiteralPath $TokenPath)) {
        $script:LastRpcError = "Discord authorization is required."
        return
    }

    try {
        Ensure-Rpc | Out-Null
    }
    catch {
        $script:LastRpcError = $_.Exception.Message
        Write-BridgeException -Context "Health RPC reconnect" -Exception $_.Exception
    }
}

function Get-RepairStatus {
    $rpcConnected = ($null -ne $script:RpcStream -and $script:RpcStream.IsConnected)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $bridgeSession = (Get-Process -Id $PID).SessionId
    $discordInSession = Get-Process -Name "Discord" -ErrorAction SilentlyContinue |
        Where-Object { $_.SessionId -eq $bridgeSession } |
        Select-Object -First 1
    return [pscustomobject]@{
        bridge = $true
        bridge_elevated = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        discord = ($null -ne $discordInSession)
        bridge_session = $bridgeSession
        virtualhere = ($null -ne (Get-Process -Name "vhui64" -ErrorAction SilentlyContinue | Select-Object -First 1))
        rpc_connected = $rpcConnected
        rpc_authenticated = [bool]$script:RpcAuthenticated
        pipe = [string]$script:RpcPipeName
        last_error = [string]$script:LastRpcError
    }
}

function Find-VirtualHereClient {
    $process = Get-Process -Name "vhui64" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $process) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$process.Path) -and
                (Test-Path -LiteralPath ([string]$process.Path))) {
                return [string]$process.Path
            }
        }
        catch {}
    }

    $candidates = @(
        "E:\Programy\Virtual Here\vhui64.exe",
        (Join-Path $env:ProgramFiles "VirtualHere\vhui64.exe"),
        (Join-Path ${env:ProgramFiles(x86)} "VirtualHere\vhui64.exe"),
        (Join-Path $env:LOCALAPPDATA "VirtualHere\vhui64.exe")
    )
    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }
    return $null
}

function Invoke-VirtualHereApi {
    param([Parameter(Mandatory)][string]$Command)

    $clientPath = Find-VirtualHereClient
    if ([string]::IsNullOrWhiteSpace($clientPath)) {
        throw "VirtualHere is not installed."
    }
    if ($null -eq (Get-Process -Name "vhui64" -ErrorAction SilentlyContinue | Select-Object -First 1)) {
        throw "VirtualHere is installed but not running."
    }

    $responsePath = Join-Path $env:TEMP ("virtualhere_api_{0}.txt" -f [guid]::NewGuid().ToString("N"))
    try {
        $apiProcess = Start-Process -FilePath $clientPath -Wait -PassThru -WindowStyle Hidden -ArgumentList @(
            "-t", ('"{0}"' -f $Command.Replace('"', '')), "-r", ('"{0}"' -f $responsePath)
        )
        $response = ""
        if (Test-Path -LiteralPath $responsePath) {
            $response = (Get-Content -LiteralPath $responsePath -Raw).Trim()
        }
        if ($apiProcess.ExitCode -ne 0 -or $response.StartsWith("FAILED") -or $response.StartsWith("ERROR")) {
            throw "VirtualHere command failed. Code=$($apiProcess.ExitCode), response=$response"
        }
        return $response
    }
    finally {
        Remove-Item -LiteralPath $responsePath -Force -ErrorAction SilentlyContinue
    }
}

function Invalidate-VirtualHereCache {
    $script:VirtualHereCacheAt = [datetime]::MinValue
}

function Get-VirtualHereSnapshot {
    param([switch]$Force)

    $ageMs = ((Get-Date) - $script:VirtualHereCacheAt).TotalMilliseconds
    if (-not $Force -and $null -ne $script:VirtualHereCache -and
        $ageMs -lt $script:VirtualHereCacheTtlMs) {
        return $script:VirtualHereCache
    }

    $clientPath = Find-VirtualHereClient
    $running = ($null -ne (Get-Process -Name "vhui64" -ErrorAction SilentlyContinue | Select-Object -First 1))
    $servers = New-Object System.Collections.Generic.List[object]
    $result = [ordered]@{
        installed = (-not [string]::IsNullOrWhiteSpace($clientPath))
        running = $running
        client_path = [string]$clientPath
        servers = @()
        error = ""
        generated_at = (Get-Date).ToString("o")
    }

    if ($result.installed -and $running) {
        try {
            [xml]$xml = Invoke-VirtualHereApi -Command "GET CLIENT STATE"
            foreach ($serverNode in @($xml.SelectNodes("/state/server"))) {
                if ($null -eq $serverNode) { continue }
                $connection = $serverNode.SelectSingleNode("connection")
                if ($null -eq $connection) { continue }
                $devices = New-Object System.Collections.Generic.List[object]
                $hostname = [string]$connection.hostname
                foreach ($device in @($serverNode.SelectNodes("device"))) {
                    if ($null -eq $device) { continue }
                    $state = [int]$device.state
                    $boundHost = [string]$device.boundClientHostname
                    $nickname = [string]$device.nickname
                    $name = if ([string]::IsNullOrWhiteSpace($nickname)) { [string]$device.product } else { $nickname }
                    $autoUse = [string]$device.autoUse
                    $devices.Add([pscustomobject]@{
                        address = "$hostname.$([string]$device.address)"
                        device_address = [string]$device.address
                        name = $name
                        product = [string]$device.product
                        vendor = [string]$device.vendor
                        state = $state
                        available = ($state -eq 1)
                        in_use = ($state -eq 3)
                        in_use_by_me = ($state -eq 3 -and
                            -not [string]::IsNullOrWhiteSpace($boundHost) -and
                            $boundHost -ieq $env:COMPUTERNAME)
                        bound_client = [string]$device.clientId
                        bound_hostname = $boundHost
                        auto_use = (-not [string]::IsNullOrWhiteSpace($autoUse) -and $autoUse -ne "not-set")
                        auto_use_mode = $autoUse
                    })
                }
                $servers.Add([pscustomobject]@{
                    id = [string]$connection.connectionId
                    name = [string]$connection.serverName
                    hostname = $hostname
                    host = [string]$connection.host
                    port = [int]$connection.port
                    secure = ([string]$connection.secure -eq "true")
                    state = [int]$connection.state
                    devices = [object[]]$devices.ToArray()
                    device_count = $devices.Count
                })
            }
            $result.servers = [object[]]$servers.ToArray()
        }
        catch {
            $result.error = $_.Exception.Message
            Write-BridgeException -Context "VirtualHere snapshot" -Exception $_.Exception
        }
    }

    $script:VirtualHereCache = [pscustomobject]$result
    $script:VirtualHereCacheAt = Get-Date
    return $script:VirtualHereCache
}

function Invoke-VirtualHereDeviceAction {
    param(
        [Parameter(Mandatory)][ValidateSet("use","stop","auto")][string]$Action,
        [Parameter(Mandatory)][string]$Address
    )

    if ($Address -notmatch '^[A-Za-z0-9._:-]+$') { throw "Invalid VirtualHere device address." }
    $snapshot = Get-VirtualHereSnapshot -Force
    $known = $false
    foreach ($server in @($snapshot.servers)) {
        foreach ($device in @($server.devices)) {
            if ([string]$device.address -eq $Address) { $known = $true; break }
        }
        if ($known) { break }
    }
    if (-not $known) { throw "VirtualHere device is no longer available: $Address" }

    $command = switch ($Action) {
        "use" { "USE,$Address" }
        "stop" { "STOP USING,$Address" }
        "auto" { "AUTO USE DEVICE,$Address" }
    }
    $response = Invoke-VirtualHereApi -Command $command
    Invalidate-VirtualHereCache
    return $response
}

function Get-WindowsAudioDevices {
    if (-not $script:WindowsAudioAvailable) { return @() }
    $items = New-Object System.Collections.Generic.List[object]
    $flows = @(
        [pscustomobject]@{
            flow = "output"
            path = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render"
            prefix = "{0.0.0.00000000}."
        },
        [pscustomobject]@{
            flow = "input"
            path = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture"
            prefix = "{0.0.1.00000000}."
        }
    )

    foreach ($flow in $flows) {
        $defaultId = [WindowsAudioBridge]::GetDefaultDeviceId([string]$flow.flow, 1)
        $communicationsId = [WindowsAudioBridge]::GetDefaultDeviceId([string]$flow.flow, 2)
        foreach ($key in @(Get-ChildItem -LiteralPath $flow.path -ErrorAction SilentlyContinue)) {
            $state = [int](Get-ItemPropertyValue -LiteralPath $key.PSPath -Name "DeviceState" -ErrorAction SilentlyContinue)
            if (($state -band 1) -ne 1) { continue }
            $propertiesPath = Join-Path $key.PSPath "Properties"
            $name = [string](Get-ItemPropertyValue -LiteralPath $propertiesPath `
                -Name "{a45c254e-df1c-4efd-8020-67d146a850e0},2" -ErrorAction SilentlyContinue)
            if ([string]::IsNullOrWhiteSpace($name)) { $name = [string]$key.PSChildName }
            $id = [string]$flow.prefix + [string]$key.PSChildName
            $items.Add([pscustomobject]@{
                id = $id
                name = $name
                flow = [string]$flow.flow
                is_default = ($id -eq $defaultId)
                is_default_communications = ($id -eq $communicationsId)
            })
        }
    }
    return [object[]]$items.ToArray()
}

function Get-AudioSnapshot {
    $discord = Invoke-RpcSafe -Command "GET_VOICE_SETTINGS"
    $discordDevices = New-Object System.Collections.Generic.List[object]
    foreach ($device in @($discord.data.input.available_devices)) {
        $discordDevices.Add([pscustomobject]@{
            id = [string]$device.id
            name = [string]$device.name
            flow = "input"
            is_default = ([string]$device.id -eq [string]$discord.data.input.device_id)
        })
    }
    foreach ($device in @($discord.data.output.available_devices)) {
        $discordDevices.Add([pscustomobject]@{
            id = [string]$device.id
            name = [string]$device.name
            flow = "output"
            is_default = ([string]$device.id -eq [string]$discord.data.output.device_id)
        })
    }

    $volume = $null
    $systemDevices = @()
    if ($script:WindowsAudioAvailable) {
        $volume = [WindowsAudioBridge]::GetMasterVolume()
        $systemDevices = @(Get-WindowsAudioDevices)
    }
    return [pscustomobject]@{
        system_available = $script:WindowsAudioAvailable
        system_error = $script:WindowsAudioError
        system_volume = if ($null -eq $volume) { 0 } else { [int]$volume.Volume }
        system_muted = if ($null -eq $volume) { $false } else { [bool]$volume.Muted }
        system_devices = [object[]]$systemDevices
        discord_devices = [object[]]$discordDevices.ToArray()
    }
}

function Set-WindowsAudioDefaultDevice {
    param([Parameter(Mandatory)][string]$DeviceId)
    if (-not $script:WindowsAudioAvailable) { throw "Windows Audio helper is unavailable: $($script:WindowsAudioError)" }
    $known = $false
    foreach ($device in @(Get-WindowsAudioDevices)) {
        if ([string]$device.id -eq $DeviceId) { $known = $true; break }
    }
    if (-not $known) { throw "Unknown or inactive Windows audio device." }
    [WindowsAudioBridge]::SetDefaultDevice($DeviceId)
}

function Set-WindowsAudioVolume {
    param([int]$Delta = 0, [Nullable[int]]$Value = $null)
    if (-not $script:WindowsAudioAvailable) { throw "Windows Audio helper is unavailable: $($script:WindowsAudioError)" }
    $current = [WindowsAudioBridge]::GetMasterVolume()
    $target = if ($null -ne $Value) { [int]$Value } else { [int]$current.Volume + $Delta }
    return [WindowsAudioBridge]::SetMasterVolume([Math]::Max(0, [Math]::Min(100, $target)))
}

function Toggle-WindowsAudioMute {
    if (-not $script:WindowsAudioAvailable) { throw "Windows Audio helper is unavailable: $($script:WindowsAudioError)" }
    $current = [WindowsAudioBridge]::GetMasterVolume()
    return [WindowsAudioBridge]::SetMute(-not [bool]$current.Muted)
}

function Repair-RpcConnection {
    Write-BridgeLog "Repair: reconnect Discord RPC."
    Close-Rpc
    Invalidate-VoiceSnapshot
    Ensure-Rpc | Out-Null
    return Get-RepairStatus
}

function Start-DiscordClient {
    Write-BridgeLog "Start Discord client in the Bridge user session."
    Close-Rpc
    Invalidate-VoiceSnapshot

    $updateExe = Join-Path $env:LOCALAPPDATA "Discord\Update.exe"
    if (Test-Path -LiteralPath $updateExe) {
        Start-Process -FilePath $updateExe -ArgumentList "--processStart Discord.exe"
        return
    }

    $discordExe = Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA "Discord") `
            -Directory -Filter "app-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "Discord.exe" } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace([string]$discordExe)) {
        throw "Discord executable was not found for the Bridge user profile."
    }
    Start-Process -FilePath $discordExe
}

function Restart-DiscordClient {
    Write-BridgeLog "Repair: restart Discord client."
    $bridgeSession = (Get-Process -Id $PID).SessionId
    $processes = @(Get-Process -Name "Discord" -ErrorAction SilentlyContinue |
        Where-Object { $_.SessionId -eq $bridgeSession })
    $discordExe = $null
    foreach ($process in $processes) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$process.Path)) {
                $discordExe = [string]$process.Path
                break
            }
        }
        catch {}
    }

    if ($processes.Count -gt 0) {
        $processes | Stop-Process -Force
        Start-Sleep -Milliseconds 700
    }

    Close-Rpc
    Invalidate-VoiceSnapshot
    $updateExe = Join-Path $env:LOCALAPPDATA "Discord\Update.exe"
    if (Test-Path -LiteralPath $updateExe) {
        Start-Process -FilePath $updateExe -ArgumentList "--processStart Discord.exe"
    }
    elseif (-not [string]::IsNullOrWhiteSpace($discordExe) -and (Test-Path -LiteralPath $discordExe)) {
        Start-Process -FilePath $discordExe
    }
    else {
        throw "Discord executable was not found."
    }
}

function Restart-VirtualHereClient {
    Write-BridgeLog "Repair: restart VirtualHere client."
    $process = Get-Process -Name "vhui64" -ErrorAction SilentlyContinue | Select-Object -First 1
    $clientPath = "E:\Programy\Virtual Here\vhui64.exe"
    if ($null -ne $process) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$process.Path)) {
                $clientPath = [string]$process.Path
            }
        }
        catch {}
    }

    if (-not (Test-Path -LiteralPath $clientPath)) {
        throw "VirtualHere executable was not found: $clientPath"
    }

    if ($null -ne $process) {
        $responsePath = Join-Path $env:TEMP ("virtualhere_exit_{0}.txt" -f [guid]::NewGuid().ToString("N"))
        $apiProcess = Start-Process -FilePath $clientPath -Wait -PassThru -WindowStyle Hidden -ArgumentList @(
            "-t", '"EXIT"', "-r", ('"{0}"' -f $responsePath)
        )
        $apiResponse = ""
        if (Test-Path -LiteralPath $responsePath) {
            $apiResponse = (Get-Content -LiteralPath $responsePath -Raw).Trim()
            Remove-Item -LiteralPath $responsePath -Force -ErrorAction SilentlyContinue
        }
        if ($apiProcess.ExitCode -ne 0 -or ($apiResponse -ne "" -and $apiResponse -ne "OK")) {
            throw "VirtualHere EXIT failed. Code=$($apiProcess.ExitCode), response=$apiResponse"
        }
        Start-Sleep -Milliseconds 800
    }

    Start-Process -FilePath $clientPath
}

function Start-DelayedBridgeRestart {
    Write-BridgeLog "Repair: restart bridge requested."
    $bridgeScript = Join-Path $ScriptRoot "DiscordBridge.ps1"
    if (-not (Test-Path -LiteralPath $bridgeScript)) {
        throw "Missing bridge script: $bridgeScript"
    }

    # Keep the replacement process in this same PowerShell child. Calling the
    # start wrapper created a short-lived grandchild that could disappear when
    # the original interactive session was disconnected.
    $command = 'Start-Sleep -Seconds 3; & ''{0}''' -f $bridgeScript.Replace("'", "''")
    Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -ArgumentList @(
        "-NoProfile",
        "-WindowStyle", "Hidden",
        "-ExecutionPolicy", "Bypass",
        "-Command", $command
    )
    $script:Running = $false
}

function Export-BridgeDiagnostics {
    $diagnosticsRoot = Join-Path $ScriptRoot "diagnostics"
    New-Item -ItemType Directory -Path $diagnosticsRoot -Force | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $staging = Join-Path $diagnosticsRoot "staging_$stamp"
    $archive = Join-Path $diagnosticsRoot "DiscordRemote_Diagnostics_$stamp.zip"
    New-Item -ItemType Directory -Path $staging -Force | Out-Null

    $config = Read-Config
    [pscustomobject]@{
        generated_at = (Get-Date).ToString("o")
        windows_user = "$env:USERDOMAIN\$env:USERNAME"
        powershell = [string]$PSVersionTable.PSVersion
        repair_status = Get-RepairStatus
        client_id = [string]$config.client_id
        port = [int]$config.port
        redirect_uri = [string]$config.redirect_uri
        scopes = @($config.scopes)
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $staging "status.json") -Encoding UTF8

    try {
        Get-VoiceSnapshot -Force | ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath (Join-Path $staging "snapshot.json") -Encoding UTF8
    }
    catch {
        $_.Exception.ToString() | Set-Content -LiteralPath (Join-Path $staging "snapshot_error.txt") -Encoding UTF8
    }

    if (Test-Path -LiteralPath $StatePath) {
        Copy-Item -LiteralPath $StatePath -Destination (Join-Path $staging "discord_remote_state.json") -Force
    }
    if (Test-Path -LiteralPath $LogPath) {
        Get-Content -LiteralPath $LogPath -Tail 1500 |
            Set-Content -LiteralPath (Join-Path $staging "bridge_recent.log") -Encoding UTF8
    }
    Get-Process -Name "Discord","vhui64","RemoteServerWin","powershell" -ErrorAction SilentlyContinue |
        Select-Object ProcessName,Id,StartTime,CPU,WorkingSet64 |
        ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $staging "processes.json") -Encoding UTF8

    Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $archive -Force
    Write-BridgeLog "Diagnostics exported: $archive"
    return $archive
}

function Invoke-RpcSafe {
    param(
        [Parameter(Mandatory)][string]$Command,
        [hashtable]$Arguments = @{}
    )

    try {
        Ensure-Rpc | Out-Null
        return Invoke-RpcCommand -Command $Command -Arguments $Arguments
    }
    catch {
        Write-BridgeException -Context "Invoke-RpcSafe first attempt $Command" -Exception $_.Exception
        Close-Rpc

        Ensure-Rpc | Out-Null
        return Invoke-RpcCommand -Command $Command -Arguments $Arguments
    }
}

function Get-ObjectValue {
    param(
        $Object,
        [Parameter(Mandatory)][string]$Name,
        $Default = $null
    )

    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    return $property.Value
}

function Read-RemoteState {
    $empty = [pscustomobject]@{
        favorites = @()
        recent = @()
        last_channel = $null
    }

    if (-not (Test-Path -LiteralPath $StatePath)) { return $empty }

    try {
        $state = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
        if ($null -eq $state.PSObject.Properties["favorites"]) {
            $state | Add-Member -NotePropertyName "favorites" -NotePropertyValue @()
        }
        if ($null -eq $state.PSObject.Properties["recent"]) {
            $state | Add-Member -NotePropertyName "recent" -NotePropertyValue @()
        }
        if ($null -eq $state.PSObject.Properties["last_channel"]) {
            $state | Add-Member -NotePropertyName "last_channel" -NotePropertyValue $null
        }
        return $state
    }
    catch {
        Write-BridgeException -Context "Read-RemoteState" -Exception $_.Exception
        return $empty
    }
}

function Write-RemoteState {
    param([Parameter(Mandatory)]$State)

    $json = $State | ConvertTo-Json -Depth 10
    Set-Content -LiteralPath $StatePath -Value $json -Encoding UTF8
}

function New-ChannelRecord {
    param(
        [string]$ChannelId,
        [string]$GuildId,
        [string]$GuildName,
        [string]$ChannelName
    )

    return [pscustomobject]@{
        channel_id = $ChannelId
        guild_id = $GuildId
        guild_name = $GuildName
        channel_name = $ChannelName
        used_at = (Get-Date).ToString("o")
    }
}

function Add-RecentChannel {
    param([Parameter(Mandatory)]$Record)

    $state = Read-RemoteState
    $recent = New-Object System.Collections.Generic.List[object]
    $recent.Add($Record)

    foreach ($item in @($state.recent)) {
        if ([string]$item.channel_id -ne [string]$Record.channel_id -and $recent.Count -lt 10) {
            $recent.Add($item)
        }
    }

    # Windows PowerShell 5.1 can throw "Niezgodne typy argumentow" when
    # assigning @($genericList) to an existing PSCustomObject property.
    # Convert the generic list explicitly to a plain object array.
    $state.recent = [object[]]$recent.ToArray()
    $state.last_channel = $Record
    Write-RemoteState -State $state
}

function Test-FavoriteChannel {
    param([string]$ChannelId)
    $state = Read-RemoteState
    foreach ($item in @($state.favorites)) {
        if ([string]$item.channel_id -eq $ChannelId) { return $true }
    }
    return $false
}

function Toggle-FavoriteChannel {
    param([Parameter(Mandatory)]$Record)

    $state = Read-RemoteState
    $items = New-Object System.Collections.Generic.List[object]
    $removed = $false

    foreach ($item in @($state.favorites)) {
        if ([string]$item.channel_id -eq [string]$Record.channel_id) {
            $removed = $true
        }
        else {
            $items.Add($item)
        }
    }

    if (-not $removed) { $items.Add($Record) }
    $state.favorites = [object[]]$items.ToArray()
    Write-RemoteState -State $state
    return (-not $removed)
}

function ConvertTo-CompactJson {
    param([Parameter(Mandatory)]$Value)
    return ($Value | ConvertTo-Json -Compress -Depth 20)
}

function Invalidate-VoiceSnapshot {
    $script:VoiceSnapshotAt = [datetime]::MinValue
}

function Invalidate-ChannelCache {
    param([string]$GuildId = "")

    if ([string]::IsNullOrWhiteSpace($GuildId)) {
        $script:ChannelCache = @{}
    }
    elseif ($script:ChannelCache.ContainsKey($GuildId)) {
        $script:ChannelCache.Remove($GuildId)
    }
}

function Get-GuildsCached {
    param([switch]$Force)

    $age = ((Get-Date) - $script:GuildCacheAt).TotalSeconds
    if (-not $Force -and $script:GuildCacheAt -ne [datetime]::MinValue -and
        $age -lt $script:GuildCacheTtlSeconds) {
        return $script:GuildCache
    }

    $response = Invoke-RpcSafe -Command "GET_GUILDS"
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($guild in @($response.data.guilds)) {
        $items.Add([pscustomobject]@{
            id = [string]$guild.id
            name = [string]$guild.name
        })
    }

    $script:GuildCache = [object[]]$items.ToArray()
    $script:GuildCacheAt = Get-Date
    return $script:GuildCache
}

function Get-ChannelsCached {
    param(
        [Parameter(Mandatory)][string]$GuildId,
        [switch]$Force
    )

    if (-not $Force -and $script:ChannelCache.ContainsKey($GuildId)) {
        $cached = $script:ChannelCache[$GuildId]
        if (((Get-Date) - $cached.updated_at).TotalSeconds -lt $script:ChannelCacheTtlSeconds) {
            return $cached.channels
        }
    }

    $response = Invoke-RpcSafe -Command "GET_CHANNELS" -Arguments @{ guild_id = $GuildId }
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($channel in @($response.data.channels)) {
        $channelType = [int]$channel.type
        if ($channelType -ne 2 -and $channelType -ne 13) { continue }

        $details = Invoke-RpcSafe -Command "GET_CHANNEL" -Arguments @{
            channel_id = [string]$channel.id
        }
        $count = @($details.data.voice_states).Count
        $items.Add([pscustomobject]@{
            id = [string]$channel.id
            name = [string]$channel.name
            type = $channelType
            people = $count
            favorite = (Test-FavoriteChannel -ChannelId ([string]$channel.id))
        })
    }

    $channels = [object[]]$items.ToArray()
    $script:ChannelCache[$GuildId] = [pscustomobject]@{
        updated_at = Get-Date
        channels = $channels
    }
    return $channels
}

function Get-VoiceSnapshot {
    param([switch]$Force)

    $ageMs = ((Get-Date) - $script:VoiceSnapshotAt).TotalMilliseconds
    if (-not $Force -and $null -ne $script:VoiceSnapshotCache -and
        $ageMs -lt $script:VoiceSnapshotTtlMs) {
        return $script:VoiceSnapshotCache
    }

    $selected = Invoke-RpcSafe -Command "GET_SELECTED_VOICE_CHANNEL"
    $settings = Invoke-RpcSafe -Command "GET_VOICE_SETTINGS"
    $participants = New-Object System.Collections.Generic.List[object]
    $channel = $null

    if ($null -ne $selected.data) {
        $channelId = [string]$selected.data.id
        Ensure-SpeakingSubscription -ChannelId $channelId
        $channel = [pscustomobject]@{
            id = $channelId
            name = [string]$selected.data.name
            guild_id = [string]$selected.data.guild_id
        }

        foreach ($entry in @($selected.data.voice_states)) {
            $user = Get-ObjectValue $entry "user" $null
            $userId = [string](Get-ObjectValue $user "id" "")
            $name = [string](Get-ObjectValue $entry "nick" "")
            if ([string]::IsNullOrWhiteSpace($name)) {
                $name = [string](Get-ObjectValue $user "global_name" "")
            }
            if ([string]::IsNullOrWhiteSpace($name)) {
                $name = [string](Get-ObjectValue $user "username" "Unknown")
            }

            $speaking = $false
            if ($script:SpeakingUsers.ContainsKey($userId)) {
                $speaking = [bool]$script:SpeakingUsers[$userId]
            }

            $participants.Add([pscustomobject]@{
                id = $userId
                name = $name
                volume = [int](Get-ObjectValue $entry "volume" 100)
                muted = [bool](Get-ObjectValue $entry "mute" $false)
                speaking = $speaking
                is_self = (-not [string]::IsNullOrWhiteSpace($script:CurrentUserId) -and
                    $userId -eq $script:CurrentUserId)
            })
        }
    }

    $script:VoiceSnapshotCache = [pscustomobject]@{
        connected = ($null -ne $channel)
        channel = $channel
        mute = [bool]$settings.data.mute
        deafen = [bool]$settings.data.deaf
        participants = [object[]]$participants.ToArray()
        generated_at = (Get-Date).ToString("o")
    }
    $script:VoiceSnapshotAt = Get-Date
    return $script:VoiceSnapshotCache
}

function Handle-RpcDispatch {
    param($Payload)

    if ($null -eq $Payload) { return }
    $cmd = [string](Get-ObjectValue $Payload "cmd" "")
    $eventName = [string](Get-ObjectValue $Payload "evt" "")
    if ($cmd -ne "DISPATCH") { return }

    if ($eventName -eq "SPEAKING_START" -or $eventName -eq "SPEAKING_STOP") {
        $data = Get-ObjectValue $Payload "data" $null
        $userId = [string](Get-ObjectValue $data "user_id" "")
        if (-not [string]::IsNullOrWhiteSpace($userId)) {
            $script:SpeakingUsers[$userId] = ($eventName -eq "SPEAKING_START")
        }
    }

    if ($eventName -like "VOICE_*" -or $eventName -eq "SPEAKING_START" -or
        $eventName -eq "SPEAKING_STOP") {
        Invalidate-VoiceSnapshot
    }
}

function Ensure-SpeakingSubscription {
    param([string]$ChannelId)

    if ([string]::IsNullOrWhiteSpace($ChannelId)) { return }
    if ($script:SpeakingChannelId -eq $ChannelId) { return }

    if (-not [string]::IsNullOrWhiteSpace($script:SpeakingChannelId)) {
        foreach ($eventName in @("SPEAKING_START", "SPEAKING_STOP")) {
            try {
                Invoke-RpcCommand -Command "UNSUBSCRIBE" -Arguments @{
                    channel_id = $script:SpeakingChannelId
                } -EventName $eventName | Out-Null
            }
            catch {
                Write-BridgeException -Context "UNSUBSCRIBE $eventName" -Exception $_.Exception
            }
        }
    }

    $script:SpeakingUsers = @{}
    foreach ($eventName in @("SPEAKING_START", "SPEAKING_STOP")) {
        Invoke-RpcCommand -Command "SUBSCRIBE" -Arguments @{
            channel_id = $ChannelId
        } -EventName $eventName | Out-Null
    }

    $script:SpeakingChannelId = $ChannelId
}

function Get-ChannelRecordFromQuery {
    param($Query)

    return New-ChannelRecord `
        -ChannelId ([string]$Query["channel_id"]) `
        -GuildId ([string]$Query["guild_id"]) `
        -GuildName ([string]$Query["guild_name"]) `
        -ChannelName ([string]$Query["channel_name"])
}

function Sanitize-Tsv {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return ""
    }

    return $Value.Replace("`t", " ").Replace("`r", " ").Replace("`n", " ")
}

function Convert-BoolQuery {
    param(
        [string]$Value,
        [bool]$Current
    )

    $normalized = ""

    if ($null -ne $Value) {
        $normalized = $Value.ToLowerInvariant()
    }

    switch ($normalized) {
        "true" { return $true }
        "1" { return $true }
        "on" { return $true }
        "false" { return $false }
        "0" { return $false }
        "off" { return $false }
        default { return -not $Current }
    }
}

function Get-QueryParameters {
    param([string]$Target)

    $uri = [System.Uri]::new("http://127.0.0.1$Target")
    $result = @{}

    if (-not [string]::IsNullOrWhiteSpace($uri.Query)) {
        $query = $uri.Query.TrimStart("?").Split("&")

        foreach ($part in $query) {
            if ([string]::IsNullOrWhiteSpace($part)) {
                continue
            }

            $pair = $part.Split("=", 2)
            $key = [System.Uri]::UnescapeDataString($pair[0].Replace("+", " "))
            $value = ""

            if ($pair.Length -gt 1) {
                $value = [System.Uri]::UnescapeDataString($pair[1].Replace("+", " "))
            }

            $result[$key] = $value
        }
    }

    return [pscustomobject]@{
        Path = $uri.AbsolutePath.ToLowerInvariant()
        Query = $result
    }
}

function Get-EndpointResponse {
    param([Parameter(Mandatory)][string]$Target)

    $request = Get-QueryParameters -Target $Target
    $path = $request.Path
    $query = $request.Query

    Write-BridgeLog "HTTP GET $Target"

    switch ($path) {
        "/health" {
            Try-EnsureRpcForHealth
            $connected = (
                $null -ne $script:RpcStream -and
                $script:RpcStream.IsConnected
            )

            return "ok`tconnected=$connected`tauthenticated=$($script:RpcAuthenticated)`tpipe=$($script:RpcPipeName)`terror=$(Sanitize-Tsv $script:LastRpcError)"
        }

        "/authorize" {
            $force = ([string]$query["force"] -eq "1" -or
                [string]$query["force"] -eq "true")
            if ($force) {
                Write-BridgeLog "Forced OAuth reauthorization requested."
                Close-Rpc
                Clear-Token
                Invalidate-VoiceSnapshot
            }
            Ensure-Rpc | Out-Null
            return "ok`tauthorized"
        }

        "/snapshot" {
            $force = ([string]$query["force"] -eq "1" -or
                [string]$query["force"] -eq "true")
            $snapshot = Get-VoiceSnapshot -Force:$force
            return ConvertTo-CompactJson -Value $snapshot
        }

        "/home" {
            $force = ([string]$query["force"] -eq "1" -or
                [string]$query["force"] -eq "true")
            $state = Read-RemoteState
            $guilds = @(Get-GuildsCached -Force:$force)
            $result = [pscustomobject]@{
                favorites = [object[]]@($state.favorites)
                recent = [object[]]@($state.recent)
                guilds = [object[]]$guilds
            }
            return ConvertTo-CompactJson -Value $result
        }

        "/channels-view" {
            $guildId = [string]$query["guild_id"]
            if ([string]::IsNullOrWhiteSpace($guildId)) { throw "Missing guild_id." }
            $force = ([string]$query["force"] -eq "1" -or
                [string]$query["force"] -eq "true")
            $channels = @(Get-ChannelsCached -GuildId $guildId -Force:$force)
            $result = [pscustomobject]@{
                guild_id = $guildId
                channels = [object[]]$channels
            }
            return ConvertTo-CompactJson -Value $result
        }

        "/guilds" {
            $response = Invoke-RpcSafe -Command "GET_GUILDS"
            $lines = New-Object System.Collections.Generic.List[string]

            foreach ($guild in @($response.data.guilds)) {
                $lines.Add(
                    "$(Sanitize-Tsv ([string]$guild.id))`t$(Sanitize-Tsv ([string]$guild.name))"
                )
            }

            return ($lines -join "`n")
        }

        "/channels" {
            $guildId = [string]$query["guild_id"]

            if ([string]::IsNullOrWhiteSpace($guildId)) {
                throw "Brak parametru guild_id."
            }

            $response = Invoke-RpcSafe `
                -Command "GET_CHANNELS" `
                -Arguments @{ guild_id = $guildId }

            $lines = New-Object System.Collections.Generic.List[string]

            foreach ($channel in @($response.data.channels)) {
                $type = [int]$channel.type

                # 2 = Guild Voice, 13 = Stage Channel
                if ($type -eq 2 -or $type -eq 13) {
                    $lines.Add(
                        "$(Sanitize-Tsv ([string]$channel.id))`t$(Sanitize-Tsv ([string]$channel.name))`t$type"
                    )
                }
            }

            return ($lines -join "`n")
        }

        "/current" {
            $response = Invoke-RpcSafe -Command "GET_SELECTED_VOICE_CHANNEL"

            if ($null -eq $response.data) {
                return "none`t`t"
            }

            return (
                "$(Sanitize-Tsv ([string]$response.data.id))`t" +
                "$(Sanitize-Tsv ([string]$response.data.name))`t" +
                "$(Sanitize-Tsv ([string]$response.data.guild_id))"
            )
        }

        "/join" {
            $channelId = [string]$query["channel_id"]

            if ([string]::IsNullOrWhiteSpace($channelId)) {
                throw "Brak parametru channel_id."
            }

            # Tapping a channel in the remote is explicit user consent to move.
            $response = Invoke-RpcSafe `
                -Command "SELECT_VOICE_CHANNEL" `
                -Arguments @{
                    channel_id = $channelId
                    timeout = 15
                    force = $true
                    navigate = $true
                }

            Invalidate-VoiceSnapshot
            return "ok`t$(Sanitize-Tsv ([string]$response.data.name))"
        }

        "/leave" {
            $response = Invoke-RpcSafe `
                -Command "SELECT_VOICE_CHANNEL" `
                -Arguments @{
                    channel_id = $null
                    timeout = 10
                }

            Invalidate-VoiceSnapshot
            return "ok`tdisconnected"
        }

        "/voice" {
            $response = Invoke-RpcSafe -Command "GET_VOICE_SETTINGS"
            $data = $response.data

            $inputName = [string]$data.input.device_id
            $outputName = [string]$data.output.device_id

            foreach ($device in @($data.input.available_devices)) {
                if ([string]$device.id -eq [string]$data.input.device_id) {
                    $inputName = [string]$device.name
                    break
                }
            }

            foreach ($device in @($data.output.available_devices)) {
                if ([string]$device.id -eq [string]$data.output.device_id) {
                    $outputName = [string]$device.name
                    break
                }
            }

            return (
                "mute=$([bool]$data.mute)`t" +
                "deaf=$([bool]$data.deaf)`t" +
                "input=$(Sanitize-Tsv $inputName)`t" +
                "output=$(Sanitize-Tsv $outputName)"
            )
        }

        "/mute" {
            $settings = Invoke-RpcSafe -Command "GET_VOICE_SETTINGS"
            $current = [bool]$settings.data.mute
            $newValue = Convert-BoolQuery -Value ([string]$query["value"]) -Current $current

            $response = Invoke-RpcSafe `
                -Command "SET_VOICE_SETTINGS" `
                -Arguments @{ mute = $newValue }

            Invalidate-VoiceSnapshot
            return "ok`tmute=$([bool]$response.data.mute)"
        }

        "/deafen" {
            $settings = Invoke-RpcSafe -Command "GET_VOICE_SETTINGS"
            $current = [bool]$settings.data.deaf
            $newValue = Convert-BoolQuery -Value ([string]$query["value"]) -Current $current

            $response = Invoke-RpcSafe `
                -Command "SET_VOICE_SETTINGS" `
                -Arguments @{ deaf = $newValue }

            Invalidate-VoiceSnapshot
            return "ok`tdeaf=$([bool]$response.data.deaf)"
        }

        "/channels-advanced" {
            $guildId = [string]$query["guild_id"]
            if ([string]::IsNullOrWhiteSpace($guildId)) { throw "Missing guild_id." }

            $response = Invoke-RpcSafe -Command "GET_CHANNELS" -Arguments @{ guild_id = $guildId }
            $lines = New-Object System.Collections.Generic.List[string]

            foreach ($channel in @($response.data.channels)) {
                $type = [int]$channel.type
                if ($type -ne 2 -and $type -ne 13) { continue }

                $count = 0
                try {
                    $details = Invoke-RpcSafe -Command "GET_CHANNEL" -Arguments @{
                        channel_id = [string]$channel.id
                    }
                    $count = @($details.data.voice_states).Count
                }
                catch {
                    Write-BridgeException -Context "GET_CHANNEL count" -Exception $_.Exception
                }

                $favorite = Test-FavoriteChannel -ChannelId ([string]$channel.id)
                $lines.Add(
                    "$(Sanitize-Tsv ([string]$channel.id))`t" +
                    "$(Sanitize-Tsv ([string]$channel.name))`t$type`t$count`t$favorite"
                )
            }

            return ($lines -join "`n")
        }

        "/favorites" {
            $state = Read-RemoteState
            $lines = New-Object System.Collections.Generic.List[string]
            foreach ($item in @($state.favorites)) {
                $lines.Add(
                    "$(Sanitize-Tsv ([string]$item.channel_id))`t" +
                    "$(Sanitize-Tsv ([string]$item.guild_id))`t" +
                    "$(Sanitize-Tsv ([string]$item.guild_name))`t" +
                    "$(Sanitize-Tsv ([string]$item.channel_name))"
                )
            }
            return ($lines -join "`n")
        }

        "/recent" {
            $state = Read-RemoteState
            $lines = New-Object System.Collections.Generic.List[string]
            foreach ($item in @($state.recent)) {
                $lines.Add(
                    "$(Sanitize-Tsv ([string]$item.channel_id))`t" +
                    "$(Sanitize-Tsv ([string]$item.guild_id))`t" +
                    "$(Sanitize-Tsv ([string]$item.guild_name))`t" +
                    "$(Sanitize-Tsv ([string]$item.channel_name))"
                )
            }
            return ($lines -join "`n")
        }

        "/favorite-toggle" {
            $record = Get-ChannelRecordFromQuery -Query $query
            if ([string]::IsNullOrWhiteSpace([string]$record.channel_id)) { throw "Missing channel_id." }
            $enabled = Toggle-FavoriteChannel -Record $record
            Invalidate-ChannelCache -GuildId ([string]$record.guild_id)
            return "ok`tfavorite=$enabled"
        }

        "/join-advanced" {
            $record = Get-ChannelRecordFromQuery -Query $query
            if ([string]::IsNullOrWhiteSpace([string]$record.channel_id)) { throw "Missing channel_id." }

            $response = Invoke-RpcSafe -Command "SELECT_VOICE_CHANNEL" -Arguments @{
                channel_id = [string]$record.channel_id
                timeout = 15
                force = $true
                navigate = $true
            }

            Add-RecentChannel -Record $record
            Ensure-SpeakingSubscription -ChannelId ([string]$record.channel_id)
            Invalidate-VoiceSnapshot
            Invalidate-ChannelCache -GuildId ([string]$record.guild_id)
            return "ok`t$(Sanitize-Tsv ([string]$record.channel_name))"
        }

        "/join-last" {
            $state = Read-RemoteState
            $record = $state.last_channel
            if ($null -eq $record -or [string]::IsNullOrWhiteSpace([string]$record.channel_id)) {
                throw "No last channel."
            }

            Invoke-RpcSafe -Command "SELECT_VOICE_CHANNEL" -Arguments @{
                channel_id = [string]$record.channel_id
                timeout = 15
                force = $true
                navigate = $true
            } | Out-Null

            Add-RecentChannel -Record $record
            Ensure-SpeakingSubscription -ChannelId ([string]$record.channel_id)
            Invalidate-VoiceSnapshot
            Invalidate-ChannelCache -GuildId ([string]$record.guild_id)
            return "ok`t$(Sanitize-Tsv ([string]$record.channel_name))"
        }

        "/participants" {
            $response = Invoke-RpcSafe -Command "GET_SELECTED_VOICE_CHANNEL"
            if ($null -eq $response.data) { return "" }

            $channelId = [string]$response.data.id
            Ensure-SpeakingSubscription -ChannelId $channelId

            $lines = New-Object System.Collections.Generic.List[string]

            foreach ($entry in @($response.data.voice_states)) {
                $user = Get-ObjectValue $entry "user" $null
                $userId = [string](Get-ObjectValue $user "id" "")
                $name = [string](Get-ObjectValue $entry "nick" "")
                if ([string]::IsNullOrWhiteSpace($name)) {
                    $name = [string](Get-ObjectValue $user "global_name" "")
                }
                if ([string]::IsNullOrWhiteSpace($name)) {
                    $name = [string](Get-ObjectValue $user "username" "Unknown")
                }

                $volume = [int](Get-ObjectValue $entry "volume" 100)
                $muted = [bool](Get-ObjectValue $entry "mute" $false)
                $speaking = $false
                if ($script:SpeakingUsers.ContainsKey($userId)) {
                    $speaking = [bool]$script:SpeakingUsers[$userId]
                }
                $isSelf = (-not [string]::IsNullOrWhiteSpace($script:CurrentUserId) -and
                    $userId -eq $script:CurrentUserId)

                $lines.Add(
                    "$(Sanitize-Tsv $userId)`t$(Sanitize-Tsv $name)`t$volume`t$muted`t$speaking`t$isSelf"
                )
            }

            return ($lines -join "`n")
        }

        "/user-volume" {
            $userId = [string]$query["user_id"]
            $delta = [int]([string]$query["delta"])
            $valueText = [string]$query["value"]
            if ([string]::IsNullOrWhiteSpace($userId)) { throw "Missing user_id." }
            if ($userId -eq $script:CurrentUserId) { return "ignored`tself" }

            $selected = Invoke-RpcSafe -Command "GET_SELECTED_VOICE_CHANNEL"
            $current = 100
            foreach ($entry in @($selected.data.voice_states)) {
                $user = Get-ObjectValue $entry "user" $null
                if ([string](Get-ObjectValue $user "id" "") -eq $userId) {
                    $current = [int](Get-ObjectValue $entry "volume" 100)
                    break
                }
            }

            $newValue = if ([string]::IsNullOrWhiteSpace($valueText)) {
                [Math]::Max(0, [Math]::Min(200, $current + $delta))
            } else {
                [Math]::Max(0, [Math]::Min(200, [int]$valueText))
            }
            $response = Invoke-RpcSafe -Command "SET_USER_VOICE_SETTINGS" -Arguments @{
                user_id = $userId
                volume = $newValue
            }
            Invalidate-VoiceSnapshot
            return "ok`tvolume=$([int]$response.data.volume)"
        }

        "/user-mute" {
            $userId = [string]$query["user_id"]
            if ([string]::IsNullOrWhiteSpace($userId)) { throw "Missing user_id." }
            if ($userId -eq $script:CurrentUserId) { return "ignored`tself" }

            $selected = Invoke-RpcSafe -Command "GET_SELECTED_VOICE_CHANNEL"
            $current = $false
            foreach ($entry in @($selected.data.voice_states)) {
                $user = Get-ObjectValue $entry "user" $null
                if ([string](Get-ObjectValue $user "id" "") -eq $userId) {
                    $current = [bool](Get-ObjectValue $entry "mute" $false)
                    break
                }
            }

            $response = Invoke-RpcSafe -Command "SET_USER_VOICE_SETTINGS" -Arguments @{
                user_id = $userId
                mute = (-not $current)
            }
            Invalidate-VoiceSnapshot
            return "ok`tmute=$([bool]$response.data.mute)"
        }

        "/devices" {
            $settings = Invoke-RpcSafe -Command "GET_VOICE_SETTINGS"
            $lines = New-Object System.Collections.Generic.List[string]

            foreach ($device in @($settings.data.input.available_devices)) {
                $current = ([string]$device.id -eq [string]$settings.data.input.device_id)
                $lines.Add("input`t$(Sanitize-Tsv ([string]$device.id))`t$(Sanitize-Tsv ([string]$device.name))`t$current")
            }
            foreach ($device in @($settings.data.output.available_devices)) {
                $current = ([string]$device.id -eq [string]$settings.data.output.device_id)
                $lines.Add("output`t$(Sanitize-Tsv ([string]$device.id))`t$(Sanitize-Tsv ([string]$device.name))`t$current")
            }
            return ($lines -join "`n")
        }

        "/audio-state" {
            return ConvertTo-CompactJson -Value (Get-AudioSnapshot)
        }

        "/system-audio-default" {
            $deviceId = [string]$query["device_id"]
            if ([string]::IsNullOrWhiteSpace($deviceId)) { throw "Missing Windows audio device_id." }
            Set-WindowsAudioDefaultDevice -DeviceId $deviceId
            return ConvertTo-CompactJson -Value (Get-AudioSnapshot)
        }

        "/system-audio-volume" {
            $delta = 0
            if (-not [string]::IsNullOrWhiteSpace([string]$query["delta"])) {
                $delta = [int]([string]$query["delta"])
            }
            $result = Set-WindowsAudioVolume -Delta $delta
            return ConvertTo-CompactJson -Value $result
        }

        "/system-audio-mute" {
            $result = Toggle-WindowsAudioMute
            return ConvertTo-CompactJson -Value $result
        }

        "/select-device" {
            $kind = [string]$query["kind"]
            $deviceId = [string]$query["device_id"]
            if ($kind -ne "input" -and $kind -ne "output") { throw "Invalid device kind." }
            if ([string]::IsNullOrWhiteSpace($deviceId)) { throw "Missing device_id." }

            $args = @{}
            if ($kind -eq "input") { $args.input = @{ device_id = $deviceId } }
            else { $args.output = @{ device_id = $deviceId } }

            Invoke-RpcSafe -Command "SET_VOICE_SETTINGS" -Arguments $args | Out-Null
            Invalidate-VoiceSnapshot
            return "ok`t$kind`t$(Sanitize-Tsv $deviceId)"
        }

        "/virtualhere-state" {
            $force = ([string]$query["force"] -eq "1" -or
                [string]$query["force"] -eq "true")
            return ConvertTo-CompactJson -Value (Get-VirtualHereSnapshot -Force:$force)
        }

        "/virtualhere-action" {
            $action = [string]$query["action"]
            $address = [string]$query["address"]
            if ([string]::IsNullOrWhiteSpace($action)) { throw "Missing VirtualHere action." }
            if ([string]::IsNullOrWhiteSpace($address)) { throw "Missing VirtualHere device address." }
            $response = Invoke-VirtualHereDeviceAction -Action $action -Address $address
            return "ok`t$(Sanitize-Tsv $response)"
        }

        "/repair-status" {
            return ConvertTo-CompactJson -Value (Get-RepairStatus)
        }

        "/repair-rpc" {
            $result = Repair-RpcConnection
            return ConvertTo-CompactJson -Value $result
        }

        "/start-discord" {
            Start-DiscordClient
            return "ok`tDiscord start requested"
        }

        "/repair-discord" {
            Restart-DiscordClient
            return "ok`tDiscord restart started"
        }

        "/repair-virtualhere" {
            Restart-VirtualHereClient
            return "ok`tVirtualHere restarted"
        }

        "/repair-bridge" {
            Start-DelayedBridgeRestart
            return "ok`tBridge restart started"
        }

        "/diagnostics" {
            $archive = Export-BridgeDiagnostics
            return "ok`t$(Sanitize-Tsv $archive)"
        }

        "/shutdown" {
            $script:Running = $false
            return "ok`tshutting-down"
        }

        default {
            throw "Nieznany endpoint: $path"
        }
    }
}

function Read-HttpRequest {
    param([Parameter(Mandatory)][System.Net.Sockets.TcpClient]$Client)

    $stream = $Client.GetStream()
    $reader = [System.IO.StreamReader]::new(
        $stream,
        [System.Text.Encoding]::ASCII,
        $false,
        4096,
        $true
    )

    $requestLine = $reader.ReadLine()

    if ([string]::IsNullOrWhiteSpace($requestLine)) {
        return $null
    }

    # Consume headers.
    while ($true) {
        $line = $reader.ReadLine()

        if ($null -eq $line -or $line.Length -eq 0) {
            break
        }
    }

    $parts = $requestLine.Split(" ")

    if ($parts.Length -lt 2) {
        throw "Nieprawidłowa linia HTTP: $requestLine"
    }

    return [pscustomobject]@{
        Method = $parts[0].ToUpperInvariant()
        Target = $parts[1]
        Stream = $stream
        Reader = $reader
    }
}

function Write-HttpResponse {
    param(
        [Parameter(Mandatory)][System.IO.Stream]$Stream,
        [Parameter(Mandatory)][int]$StatusCode,
        [Parameter(Mandatory)][string]$StatusText,
        [AllowEmptyString()][string]$Content
    )

    $body = [System.Text.Encoding]::UTF8.GetBytes($Content)
    $headerText = (
        "HTTP/1.1 $StatusCode $StatusText`r`n" +
        "Content-Type: text/plain; charset=utf-8`r`n" +
        "Content-Length: $($body.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "Connection: close`r`n" +
        "`r`n"
    )

    $header = [System.Text.Encoding]::ASCII.GetBytes($headerText)
    $Stream.Write($header, 0, $header.Length)

    if ($body.Length -gt 0) {
        $Stream.Write($body, 0, $body.Length)
    }

    $Stream.Flush()
}

# One instance per user/session.
$mutexName = "Local\DiscordUnifiedRemoteRpcBridge"
$createdNew = $false
$mutex = [System.Threading.Mutex]::new($true, $mutexName, [ref]$createdNew)

if (-not $createdNew) {
    Write-BridgeLog -Level "WARN" -Message "Inna instancja bridge już działa. Kończę."
    exit 0
}

$listener = $null

try {
    $config = Read-Config
    $port = [int]$config.port

    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        $port
    )

    $listener.Start()

    Write-BridgeLog "Discord Bridge uruchomiony na 127.0.0.1:$port."
    Write-BridgeLog "User=$env:USERDOMAIN\$env:USERNAME; PS=$($PSVersionTable.PSVersion)"
    Try-EnsureRpcForHealth

    while ($script:Running) {
        $client = $listener.AcceptTcpClient()

        try {
            $request = Read-HttpRequest -Client $client

            if ($null -eq $request) {
                continue
            }

            if ($request.Method -ne "GET") {
                Write-HttpResponse `
                    -Stream $request.Stream `
                    -StatusCode 405 `
                    -StatusText "Method Not Allowed" `
                    -Content "error`tOnly GET is supported"

                continue
            }

            try {
                $content = Get-EndpointResponse -Target $request.Target

                Write-HttpResponse `
                    -Stream $request.Stream `
                    -StatusCode 200 `
                    -StatusText "OK" `
                    -Content ([string]$content)
            }
            catch {
                $script:LastRpcError = $_.Exception.Message
                Write-BridgeException -Context "HTTP endpoint $($request.Target)" -Exception $_.Exception

                Write-HttpResponse `
                    -Stream $request.Stream `
                    -StatusCode 500 `
                    -StatusText "Internal Server Error" `
                    -Content ("error`t" + (Sanitize-Tsv $_.Exception.Message))
            }
        }
        catch {
            Write-BridgeException -Context "Obsługa klienta HTTP" -Exception $_.Exception
        }
        finally {
            try { $client.Close() } catch {}
        }
    }

    Write-BridgeLog "Otrzymano /shutdown. Zatrzymuję listener."
    $listener.Stop()
}
catch {
    Write-BridgeException -Context "Błąd główny bridge" -Exception $_.Exception
    throw
}
finally {
    Close-Rpc

    try {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
    catch {}

    try {
        $mutex.ReleaseMutex()
        $mutex.Dispose()
    }
    catch {}

    Write-BridgeLog "Discord Bridge zakończony."
}
