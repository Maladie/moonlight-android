#requires -Version 5.1
[CmdletBinding()]
param([string]$GatewayDirectory = $PSScriptRoot)

$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory)
New-Item -ItemType File -Path (Join-Path $GatewayDirectory "gateway-supervisor-stop") -Force | Out-Null
$port = 8785
try { $port = [int](Get-Content -LiteralPath (Join-Path $GatewayDirectory "gateway.json") -Raw | ConvertFrom-Json).listen_port } catch {}
Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$_" -ErrorAction SilentlyContinue
        if ($process -and [string]$process.CommandLine -like "*wakeplay_gateway.py*") {
            Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
        }
    }
