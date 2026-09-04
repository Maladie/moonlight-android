#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot "Remove-MoonWakerLoginCredentials.ps1"),
    [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) {
    throw "Credential cleanup script has a syntax error: $($errors[0].Message)"
}
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    throw ".NET Framework C# compiler was not found."
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("MoonWakerLoginBrokerTest-" + [guid]::NewGuid().ToString("N"))
$testExe = Join-Path $testRoot "BrokerCoreTests.exe"
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $arguments = @(
        "/nologo", "/target:exe", "/platform:x64", "/optimize+",
        "/main:MoonWaker.WindowsLogin.SelfTestProgram", "/out:$testExe",
        "/reference:System.dll", "/reference:System.Core.dll", "/reference:System.Security.dll",
        "/reference:System.ServiceProcess.dll",
        (Join-Path $PSScriptRoot "BrokerCore.cs"),
        (Join-Path $PSScriptRoot "BrokerService.cs"),
        (Join-Path $PSScriptRoot "BrokerCoreTests.cs"))
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $testExe -PathType Leaf)) {
        throw "MoonWaker Login Broker test compilation failed."
    }
    & $testExe
    if ($LASTEXITCODE -ne 0) { throw "MoonWaker Login Broker tests failed." }
} finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
