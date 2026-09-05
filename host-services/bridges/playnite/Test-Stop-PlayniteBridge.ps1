#requires -Version 5.1
$ErrorActionPreference = "Stop"
$sourcePath = Join-Path $PSScriptRoot "Stop-PlayniteBridge.ps1"
$source = Get-Content -LiteralPath $sourcePath -Raw
$start = $source.IndexOf("function Test-ProfileGameProviderCommandLine")
$end = $source.IndexOf("`nGet-CimInstance", $start)
if ($start -lt 0 -or $end -le $start) {
    throw "The profile process identity seam is missing."
}
Invoke-Expression $source.Substring($start, $end - $start)

$temporary = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-profile-process-" + [Guid]::NewGuid().ToString("N"))
$profileRoot = Join-Path $temporary "profile with spaces\game-provider"
$otherRoot = Join-Path $temporary "other-profile\game-provider"
New-Item -ItemType Directory -Path $profileRoot,$otherRoot -Force | Out-Null
try {
    $provider = Join-Path $profileRoot "GameProviderBridge.py"
    $legacy = Join-Path $profileRoot "PlayniteBridge.py"
    $other = Join-Path $otherRoot "GameProviderBridge.py"
    $providerCommand = 'python.exe "{0}" --config "{1}"' -f $provider,(Join-Path $profileRoot "config.json")
    $legacyCommand = 'python.exe "{0}" --config "{1}"' -f $legacy,(Join-Path $profileRoot "config.json")
    $otherCommand = 'python.exe "{0}" --config "{1}"' -f $other,(Join-Path $otherRoot "config.json")
    if (-not (Test-ProfileGameProviderCommandLine $providerCommand $profileRoot)) {
        throw "The current profile provider command was not recognized."
    }
    if (-not (Test-ProfileGameProviderCommandLine $legacyCommand $profileRoot)) {
        throw "The current profile legacy command was not recognized."
    }
    if (Test-ProfileGameProviderCommandLine $otherCommand $profileRoot) {
        throw "A different profile provider command was recognized."
    }
    if (Test-ProfileGameProviderCommandLine ($provider + ".bak") $profileRoot) {
        throw "A provider path with a suffix was recognized."
    }
    if (Test-ProfileGameProviderCommandLine "python.exe unrelated.py" $profileRoot) {
        throw "An unrelated Python command was recognized."
    }
    Write-Output "Stop-PlayniteBridge process identity test passed."
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}
