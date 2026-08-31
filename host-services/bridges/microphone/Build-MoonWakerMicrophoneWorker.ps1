param([string]$OutputDirectory = (Join-Path $PSScriptRoot "bin"))
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) { $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path -LiteralPath $compiler)) { throw ".NET Framework C# compiler was not found." }
$output = Join-Path $OutputDirectory "MoonWakerMicrophoneWorker.exe"
& $compiler /nologo /target:exe /optimize+ /platform:x64 "/out:$output" (Join-Path $PSScriptRoot "MoonWakerMicrophoneWorker.cs")
if ($LASTEXITCODE -ne 0) { throw "Microphone worker compilation failed." }
& $output --self-test
if ($LASTEXITCODE -ne 0) { throw "Microphone worker self-test failed." }
$output
