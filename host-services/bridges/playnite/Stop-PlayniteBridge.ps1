#requires -Version 5.1
[CmdletBinding()]
param()

function Test-ProfileGameProviderCommandLine([string]$CommandLine, [string]$ProfileRoot) {
    if ([string]::IsNullOrWhiteSpace($CommandLine) -or [string]::IsNullOrWhiteSpace($ProfileRoot)) { return $false }
    foreach ($name in @("GameProviderBridge.py", "PlayniteBridge.py")) {
        $expected = [IO.Path]::GetFullPath((Join-Path $ProfileRoot $name))
        $offset = $CommandLine.IndexOf($expected, [StringComparison]::OrdinalIgnoreCase)
        if ($offset -lt 0) { continue }
        $before = if ($offset -eq 0) { [char]0 } else { $CommandLine[$offset - 1] }
        $afterOffset = $offset + $expected.Length
        $after = if ($afterOffset -ge $CommandLine.Length) { [char]0 } else { $CommandLine[$afterOffset] }
        $beforeBoundary = $offset -eq 0 -or [char]::IsWhiteSpace($before) -or $before -eq '"'
        $afterBoundary = $afterOffset -ge $CommandLine.Length -or [char]::IsWhiteSpace($after) -or $after -eq '"'
        if ($beforeBoundary -and $afterBoundary) { return $true }
    }
    return $false
}

Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { Test-ProfileGameProviderCommandLine ([string]$_.CommandLine) $PSScriptRoot } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
