#requires -Version 5.1
[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path $PSScriptRoot "dist"))

$ErrorActionPreference = "Stop"
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    throw ".NET Framework C# compiler was not found."
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$output = Join-Path $OutputDirectory "MoonWakerLoginBroker.exe"
$arguments = @(
    "/nologo", "/target:exe", "/platform:x64", "/optimize+",
    "/main:MoonWaker.WindowsLogin.Program", "/out:$output",
    "/win32manifest:$(Join-Path $PSScriptRoot 'MoonWakerLoginBroker.manifest')",
    "/reference:System.dll", "/reference:System.Core.dll", "/reference:System.Security.dll",
    "/reference:System.ServiceProcess.dll",
    (Join-Path $PSScriptRoot "BrokerCore.cs"),
    (Join-Path $PSScriptRoot "BrokerService.cs"))
& $compiler @arguments
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output -PathType Leaf)) {
    throw "MoonWaker Login Broker compilation failed."
}
Get-Item -LiteralPath $output
