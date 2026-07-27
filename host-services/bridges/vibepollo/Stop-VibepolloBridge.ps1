#requires -Version 5.1
$configPath = Join-Path $PSScriptRoot "config.json"
$port = 8775
if (Test-Path -LiteralPath $configPath) {
    try { $port = [int]((Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).listen_port) } catch {}
}
try {
    Invoke-RestMethod -Uri "http://127.0.0.1:$port/shutdown" -TimeoutSec 3 | Out-Null
} catch {}
Start-Sleep -Milliseconds 400
try {
    $owners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($ownerPid in $owners) {
        if ($ownerPid -gt 0 -and $ownerPid -ne $PID) { Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue }
    }
} catch {}
