$ErrorActionPreference = "Stop"
$temporary = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-mic-test-" + [guid]::NewGuid())
try {
    $output = & (Join-Path $PSScriptRoot "Build-MoonWakerMicrophoneWorker.ps1") -OutputDirectory $temporary
    & $output --self-test
    if ($LASTEXITCODE -ne 0) { throw "Microphone worker self-test failed." }
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
