#requires -Version 5.1
# A full stdin pipe delays the parent before WaitForExit; the total deadline
# must still include that delay and release the Bridge listener promptly.
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'VibepolloBridge.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
foreach ($name in @('ConvertTo-JsonSafe', 'Invoke-VibepolloApi')) {
    $node = $ast.FindAll({ param($n)
        $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name
    }, $true) | Select-Object -First 1
    if ($null -eq $node) { throw "Missing production function $name" }
    . ([scriptblock]::Create($node.Extent.Text))
}
function Write-BridgeLog {}
$script:PythonExe = (Get-Command python.exe -ErrorAction Stop).Source
$script:PythonPrefix = ''
$script:BaseUrl = 'https://127.0.0.1'
$script:ApiToken = 'T' * 131072
$script:ApiUnavailableUntil = [datetime]::MinValue
$script:TransportPath = Join-Path ([IO.Path]::GetTempPath()) (
    'mw-slow-transport-' + [guid]::NewGuid().ToString('N') + '.py')
Set-Content -LiteralPath $script:TransportPath -Encoding ASCII -Value @'
import sys
import time
time.sleep(0.55)
sys.stdin.read()
time.sleep(1.0)
'@
try {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $timedOut = $false
    try {
        $null = Invoke-VibepolloApi -ControlBatch -TimeoutMs 850
    } catch {
        $timedOut = $_.Exception.Message -match 'timed out'
    }
    $clock.Stop()
    if (-not $timedOut -or $clock.ElapsedMilliseconds -gt 1250) {
        throw "Transport did not enforce total deadline: $($clock.ElapsedMilliseconds) ms"
    }
    Write-Output "PASS: total transport deadline $($clock.ElapsedMilliseconds) ms"
} finally {
    Remove-Item -LiteralPath $script:TransportPath -ErrorAction SilentlyContinue
}
