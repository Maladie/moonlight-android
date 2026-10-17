#requires -Version 5.1
[CmdletBinding()] param([Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Package)
$ErrorActionPreference = "Stop"
$Package = [IO.Path]::GetFullPath($Package)
if (-not (Test-Path -LiteralPath $Package -PathType Leaf)) { throw "Package not found: $Package" }
$length = (Get-Item -LiteralPath $Package).Length
if ($length -lt 1 -or $length -gt 64MB) { throw "Package must be between 1 byte and 64 MiB." }
$pipe = [IO.Pipes.NamedPipeClientStream]::new(".", "MoonWakerHotUpdater.v1",
    [IO.Pipes.PipeDirection]::InOut, [IO.Pipes.PipeOptions]::None)
try {
    $pipe.Connect(5000)
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class MoonWakerPipeServer {
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint pid);
}
'@
    [uint32]$serverPid = 0
    if (-not [MoonWakerPipeServer]::GetNamedPipeServerProcessId($pipe.SafePipeHandle, [ref]$serverPid)) {
        throw "Cannot verify the Hot Updater service process."
    }
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    $owner = Invoke-CimMethod -InputObject $server -MethodName GetOwnerSid
    $service = Get-CimInstance Win32_Service -Filter "Name='MoonWakerHotUpdater'"
    $localSystemService = $service -and [uint32]$service.ProcessId -eq $serverPid -and
        [string]$service.StartName -eq "LocalSystem" -and [string]$service.State -eq "Running"
    if (($owner.ReturnValue -eq 0 -and [string]$owner.Sid -ne "S-1-5-18") -or
            ($owner.ReturnValue -ne 0 -and -not $localSystemService)) {
        throw "The Hot Updater pipe is not owned by LocalSystem."
    }
    $header = [BitConverter]::GetBytes([int64]$length)
    $pipe.Write($header, 0, $header.Length)
    $input = [IO.File]::OpenRead($Package)
    try { $input.CopyTo($pipe) } finally { $input.Dispose() }
    $pipe.Flush()
    $responseHeader = [byte[]]::new(4)
    $offset = 0
    while ($offset -lt 4) {
        $read = $pipe.Read($responseHeader, $offset, 4 - $offset)
        if ($read -eq 0) { throw "Hot Updater closed the response early." }
        $offset += $read
    }
    $responseLength = [BitConverter]::ToInt32($responseHeader, 0)
    if ($responseLength -lt 2 -or $responseLength -gt 1MB) { throw "Invalid Hot Updater response." }
    $body = [byte[]]::new($responseLength); $offset = 0
    while ($offset -lt $body.Length) {
        $read = $pipe.Read($body, $offset, $body.Length - $offset)
        if ($read -eq 0) { throw "Hot Updater closed the response early." }
        $offset += $read
    }
    $response = [Text.Encoding]::UTF8.GetString($body) | ConvertFrom-Json
    if (-not $response.ok) { throw [string]$(if ($response.error) { $response.error } else { $response.detail }) }
    $response
} finally { $pipe.Dispose() }
