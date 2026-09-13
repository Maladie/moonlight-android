#requires -Version 5.1
[CmdletBinding()] param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = "Stop"
$compiler = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = "$env:SystemRoot\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler)) { throw ".NET Framework compiler not found." }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
& $compiler /nologo /target:exe /optimize+ `
    "/out:$(Join-Path $OutputDirectory 'MoonWakerHotUpdaterService.exe')" `
    /reference:System.dll /reference:System.Core.dll /reference:System.ServiceProcess.dll `
    /reference:System.Security.dll (Join-Path $PSScriptRoot "MoonWakerHotUpdaterService.cs")
if ($LASTEXITCODE -ne 0) { throw "Hot updater service compilation failed." }
