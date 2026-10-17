#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$GatewayConfigPath = (Join-Path $env:ProgramFiles "MoonWaker\gateway\gateway.json")
)

$ErrorActionPreference = "Stop"

function Read-Exactly([IO.Stream]$Stream, [int]$Length) {
    [byte[]]$buffer = New-Object byte[] $Length
    $offset = 0
    while ($offset -lt $Length) {
        $read = $Stream.Read($buffer, $offset, $Length - $offset)
        if ($read -le 0) { throw "Login Broker closed the management pipe early." }
        $offset += $read
    }
    return ,$buffer
}

function Write-Field([IO.Stream]$Stream, [byte]$Key, [string]$Value) {
    [byte[]]$data = [Text.Encoding]::UTF8.GetBytes($Value)
    [byte[]]$length = [BitConverter]::GetBytes([uint32]$data.Length)
    $Stream.WriteByte($Key)
    $Stream.Write($length, 0, $length.Length)
    $Stream.Write($data, 0, $data.Length)
}

function Remove-ProfileCredential([string]$ProfileId, [string]$Sid, [string]$AccountName) {
    $pipe = [IO.Pipes.NamedPipeClientStream]::new(".",
        "MoonWakerLoginBroker.Management.v1", [IO.Pipes.PipeDirection]::InOut)
    try {
        $pipe.Connect(1000)
        [byte[]]$header = @(77, 87, 76, 66, 1, 4, 3, 0) # MWLB v1, delete, three fields
        $pipe.Write($header, 0, $header.Length)
        Write-Field $pipe 1 $ProfileId
        Write-Field $pipe 2 $Sid
        Write-Field $pipe 3 $AccountName
        $pipe.Flush()

        [byte[]]$reply = Read-Exactly $pipe 8
        if ([Text.Encoding]::ASCII.GetString($reply, 0, 4) -ne "MWLR" -or
            $reply[4] -ne 1 -or $reply[7] -ne 0 -or $reply[6] -gt 8) {
            throw "Login Broker returned an invalid management response."
        }
        $fields = @{}
        for ($index = 0; $index -lt $reply[6]; $index++) {
            [byte[]]$fieldHeader = Read-Exactly $pipe 5
            $length = [BitConverter]::ToUInt32($fieldHeader, 1)
            if ($length -gt 4096) { throw "Login Broker response field is too large." }
            [byte[]]$data = Read-Exactly $pipe ([int]$length)
            $fields[[int]$fieldHeader[0]] = [Text.Encoding]::UTF8.GetString($data)
        }
        if ($reply[5] -ne 0) {
            $reason = if ($fields.ContainsKey(2)) { $fields[2] } else { "credential_delete_failed" }
            throw "Login Broker could not delete credential for '$ProfileId': $reason"
        }
    }
    finally { $pipe.Dispose() }
}

if (-not (Test-Path -LiteralPath $GatewayConfigPath -PathType Leaf)) {
    Write-Host "No Gateway profile registry was found; no configured Login Broker credentials to delete."
    return
}
$config = Get-Content -LiteralPath $GatewayConfigPath -Raw | ConvertFrom-Json
$profiles = if ($null -ne $config.profiles) { @($config.profiles.PSObject.Properties) } else { @() }
$mapped = @($profiles | Where-Object {
    $profile = $_.Value
    -not [string]::IsNullOrWhiteSpace([string]$profile.windows_account_sid) -and
    -not [string]::IsNullOrWhiteSpace([string]$profile.windows_account_name)
})
if ($mapped.Count -eq 0) {
    Write-Host "No configured Login Broker credentials to delete."
    return
}

$service = Get-Service -Name "MoonWakerLoginBroker" -ErrorAction SilentlyContinue
if ($null -eq $service) { throw "Login Broker must still be installed to delete its credentials." }
$startedHere = $service.Status -eq [ServiceProcess.ServiceControllerStatus]::Stopped
if ($startedHere) {
    Start-Service -Name "MoonWakerLoginBroker"
    (Get-Service -Name "MoonWakerLoginBroker").WaitForStatus(
        [ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(10))
}
try {
    foreach ($entry in $mapped) {
        $profile = $entry.Value
        $profileId = if (-not [string]::IsNullOrWhiteSpace([string]$profile.id)) {
            [string]$profile.id
        } else { [string]$entry.Name }
        Remove-ProfileCredential $profileId ([string]$profile.windows_account_sid) `
            ([string]$profile.windows_account_name)
    }
}
finally {
    if ($startedHere) { Stop-Service -Name "MoonWakerLoginBroker" -Force }
}
Write-Host "Deleted Login Broker credentials for $($mapped.Count) configured profile(s)."
