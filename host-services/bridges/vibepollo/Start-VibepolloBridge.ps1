#requires -Version 5.1
$bridge = Join-Path $PSScriptRoot "VibepolloBridge.ps1"
$port = 8775
try { $port = [int]((Get-Content -LiteralPath (Join-Path $PSScriptRoot "config.json") -Raw | ConvertFrom-Json).listen_port) } catch {}
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 2
    if ($health.ok) { exit 0 }
} catch {}
Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"{0}"' -f $bridge)
) -RedirectStandardOutput (Join-Path $PSScriptRoot "vibepollo-bridge.log") `
  -RedirectStandardError (Join-Path $PSScriptRoot "vibepollo-bridge-error.log")
