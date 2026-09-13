#requires -Version 5.1
# Tests the installer validator with in-memory payloads; does not build/run an installer.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'Test-MoonWakerHostInstallerUi.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
$node = $ast.FindAll({ param($n)
    $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-PackagedControlModules'
}, $true) | Select-Object -First 1
if ($null -eq $node) { throw 'Missing production payload validator' }
. ([scriptblock]::Create($node.Extent.Text))
$modules = @('gateway/control_operation_policy.py', 'gateway/child_terminal_operation.py',
    'gateway/provider_control_operation.py', 'bridges/playnite/ControlEffectFence.py')
foreach ($missing in @('', 'gateway/provider_control_operation.py', 'bridges/playnite/ControlEffectFence.py')) {
    $memory = [IO.MemoryStream]::new()
    $zip = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Update, $true)
    try {
        foreach ($module in $modules) {
            if ($module -eq $missing) { continue }
            $entry = $zip.CreateEntry('host-services/' + $module)
            $writer = [IO.StreamWriter]::new($entry.Open())
            try { $writer.Write([IO.File]::ReadAllText((Join-Path (Split-Path $PSScriptRoot) $module))) }
            finally { $writer.Dispose() }
        }
        $rejected = $false
        try { Test-PackagedControlModules $zip }
        catch {
            if (-not $missing -or $_.Exception.Message -notmatch [regex]::Escape($missing)) { throw }
            $rejected = $true
        }
        if ($missing -and -not $rejected) { throw "Incomplete payload accepted: $missing" }
    } finally { $zip.Dispose(); $memory.Dispose() }
}
Write-Output 'PASS: complete control payload imports; each missing recovery module is rejected'
