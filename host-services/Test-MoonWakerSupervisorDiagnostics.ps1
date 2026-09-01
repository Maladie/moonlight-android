#requires -Version 5.1
$ErrorActionPreference = "Stop"

function Get-WriterText([string]$Path, [string]$Name) {
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw "Parser errors in $Path" }
    $function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name }, $true)
    if (-not $function) { throw "Missing $Name" }
    return $function.Extent.Text
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-diagnostics-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    foreach ($writer in @(
        @{ path = Join-Path $PSScriptRoot "gateway\MoonWakerGatewaySupervisor.ps1"; name = "Write-SupervisorDiagnosticEvent"; init = "Initialize-SupervisorDiagnostics"; root = "GatewayDirectory"; file = "gateway-supervisor.jsonl"; component = "host.gateway-supervisor" },
        @{ path = Join-Path $PSScriptRoot "profile-agent\MoonWakerProfileBridge.ps1"; name = "Write-AgentDiagnosticEvent"; init = "Initialize-AgentDiagnostics"; root = "ProfileRoot"; file = "profile-bridge.jsonl"; component = "host.profile-supervisor"; child = "game-provider" })) {
        $writerDirectory = Join-Path $temporary $writer.component
        New-Item -ItemType Directory -Path $writerDirectory | Out-Null
        Invoke-Expression (Get-WriterText $writer.path $writer.init)
        Invoke-Expression (Get-WriterText $writer.path $writer.name)
        Set-Variable -Scope Script -Name $writer.root -Value $writerDirectory
        foreach ($suffix in @("", ".1", ".secret", ".bak")) {
            $retainedPath = Join-Path $writerDirectory ($writer.file + $suffix)
            Set-Content -LiteralPath $retainedPath -Value "old"
            (Get-Item -LiteralPath $retainedPath).LastWriteTimeUtc = [DateTime]::UtcNow.AddDays(-8)
        }
        $initializerName = [string]$writer.init
        & $initializerName
        if ((Test-Path -LiteralPath (Join-Path $writerDirectory $writer.file)) -or
            (Test-Path -LiteralPath (Join-Path $writerDirectory ($writer.file + ".1")))) { throw "Retention failed for $($writer.name)" }
        if (-not (Test-Path -LiteralPath (Join-Path $writerDirectory ($writer.file + ".secret"))) -or
            -not (Test-Path -LiteralPath (Join-Path $writerDirectory ($writer.file + ".bak")))) { throw "Retention suffix filter failed for $($writer.name)" }

        $script:diagnosticPath = Join-Path $writerDirectory $writer.file
        $script:diagnosticClock = [Diagnostics.Stopwatch]::StartNew()
        $script:diagnosticRunId = [Guid]::NewGuid().ToString("N")
        $script:diagnosticMaxBytes = 512
        $fields = @{ status = "failed"; restart_count = 2; profile_id = "default"; child_component = "game-provider"; message = "Bearer secret"; path = "C:\Private\secret" }
        $writerName = [string]$writer.name
        1..20 | ForEach-Object { & $writerName "supervisor.failed" $fields ([InvalidOperationException]::new("Bearer secret")) }
        $files = @(Get-ChildItem -LiteralPath $writerDirectory -Filter ($writer.file + "*") |
            Where-Object Name -Match ('^' + [regex]::Escape($writer.file) + '(?:\.\d+)?$'))
        if ($files.Count -lt 2 -or $files.Count -gt 3) { throw "Rotation failed for $($writer.name)" }
        if ($files | Where-Object Length -gt $script:diagnosticMaxBytes) { throw "Size limit failed for $($writer.name)" }
        $line = Get-Content -LiteralPath $script:diagnosticPath -First 1 | ConvertFrom-Json
        if ($line.v -ne 1 -or $line.component -ne $writer.component -or $line.event -ne "supervisor.failed") { throw "Schema failed for $($writer.name)" }
        if ($writer.child -and $line.child_component -ne $writer.child) { throw "Child component schema failed for $($writer.name)" }
        $raw = $files | Get-Content -Raw
        if ($raw -match 'Bearer secret|C:\\Private|"message"|"path"') { throw "Allowlist failed for $($writer.name)" }
        if (-not $line.error_type) { throw "Missing error_type for $($writer.name)" }
    }
    Write-Host "Supervisor diagnostics tests passed."
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}
