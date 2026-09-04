#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    throw ".NET Framework C# compiler was not found."
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) (
    "MoonWakerGatewayServiceBuild-" + [guid]::NewGuid().ToString("N"))
$testExe = Join-Path $testRoot "GatewayServiceTests.exe"
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    & $compiler /nologo /target:exe /platform:x64 /optimize+ `
        /main:MoonWaker.GatewayService.SelfTestProgram "/out:$testExe" `
        /reference:System.dll /reference:System.Core.dll /reference:System.ServiceProcess.dll `
        (Join-Path $PSScriptRoot "MoonWakerGatewayService.cs") `
        (Join-Path $PSScriptRoot "MoonWakerGatewayServiceTests.cs")
    if ($LASTEXITCODE -ne 0) { throw "Gateway service tests did not compile." }
    & $testExe
    if ($LASTEXITCODE -ne 0) { throw "Gateway service tests failed." }
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}
