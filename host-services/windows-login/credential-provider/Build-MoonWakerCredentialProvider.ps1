[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'out'),
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

function Find-MSBuild {
    $onPath = Get-Command 'MSBuild.exe' -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $candidate = & $vswhere -latest -products '*' `
            -requires Microsoft.Component.MSBuild `
            -find 'MSBuild\**\Bin\MSBuild.exe' | Select-Object -First 1
        if ($candidate) { return $candidate }
    }
    return $null
}

function Find-LlvmMingw {
    $compiler = Get-Command 'x86_64-w64-mingw32-clang++.exe' -ErrorAction SilentlyContinue
    if ($compiler) { return $compiler.Source }
    throw 'Neither MSBuild with the Visual Studio Desktop development with C++ workload nor x86_64-w64-mingw32-clang++.exe was found.'
}

function Assert-X64Pe([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 0x40 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) {
        throw "Not a PE file: $Path"
    }
    $peOffset = [BitConverter]::ToInt32($bytes, 0x3c)
    if ($peOffset -lt 0 -or $peOffset + 6 -gt $bytes.Length -or
        [BitConverter]::ToUInt32($bytes, $peOffset) -ne 0x00004550 -or
        [BitConverter]::ToUInt16($bytes, $peOffset + 4) -ne 0x8664) {
        throw "Expected an x64 PE image: $Path"
    }
}

$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null

& (Join-Path $PSScriptRoot 'Test-MoonWakerCredentialProvider.ps1')

$msbuild = Find-MSBuild
$compiler = if ($msbuild) { $null } else { Find-LlvmMingw }
$providerProject = Join-Path $PSScriptRoot 'MoonWakerCredentialProvider.vcxproj'
$protocolTestProject = Join-Path $PSScriptRoot 'CredentialProviderProtocolTests.vcxproj'
$providerSource = Join-Path $PSScriptRoot 'MoonWakerCredentialProvider.cpp'
$protocolSource = Join-Path $PSScriptRoot 'BrokerProtocol.cpp'
$protocolTestSource = Join-Path $PSScriptRoot 'CredentialProviderProtocolTests.cpp'
$moduleDefinition = Join-Path $PSScriptRoot 'MoonWakerCredentialProvider.def'
$dll = Join-Path $output 'MoonWakerCredentialProvider.dll'
$testExecutable = Join-Path $output 'CredentialProviderProtocolTests.exe'

if ($msbuild) {
    $providerObjectDirectory = Join-Path $output 'obj\provider\'
    & $msbuild $providerProject '/nologo' '/m' '/t:Build' `
        "/p:Configuration=$Configuration" '/p:Platform=x64' `
        "/p:OutDir=$output\" "/p:IntDir=$providerObjectDirectory"
    if ($LASTEXITCODE -ne 0) { throw 'MoonWaker Credential Provider build failed.' }
} else {
    $commonArguments = @(
        '-std=c++17', '-D_WIN32_WINNT=0x0A00', '-DWIN32_LEAN_AND_MEAN',
        '-DNOMINMAX', '-DUNICODE', '-D_UNICODE', '-Wall', '-Wextra', '-Werror'
    )
    $configurationArguments = if ($Configuration -eq 'Debug') {
        @('-O0', '-g')
    } else {
        @('-O2', '-DNDEBUG')
    }
    & $compiler @commonArguments @configurationArguments '-shared' '-static' '-o' $dll `
        $providerSource $protocolSource $moduleDefinition `
        '-lcredui' '-lsecur32' '-ladvapi32' '-lole32' '-luuid'
    if ($LASTEXITCODE -ne 0) { throw 'MoonWaker Credential Provider LLVM-MinGW build failed.' }
}

if (!(Test-Path -LiteralPath $dll)) { throw "Build did not produce $dll" }
Assert-X64Pe $dll

if (!$SkipTests) {
    if ($msbuild) {
        $testObjectDirectory = Join-Path $output 'obj\protocol-tests\'
        & $msbuild $protocolTestProject '/nologo' '/m' '/t:Build' `
            "/p:Configuration=$Configuration" '/p:Platform=x64' `
            "/p:OutDir=$output\" "/p:IntDir=$testObjectDirectory"
        if ($LASTEXITCODE -ne 0) { throw 'Credential Provider protocol test build failed.' }
    } else {
        & $compiler @commonArguments @configurationArguments '-static' '-municode' '-o' $testExecutable `
            $protocolTestSource $protocolSource
        if ($LASTEXITCODE -ne 0) { throw 'Credential Provider protocol test LLVM-MinGW build failed.' }
    }
    Assert-X64Pe $testExecutable
    & $testExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Credential Provider protocol tests failed.' }
}

Write-Host "MoonWaker Credential Provider x64 build passed: $dll"
