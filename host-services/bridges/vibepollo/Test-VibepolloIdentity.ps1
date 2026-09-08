#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "VibepolloIdentity.ps1")

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-vibepollo-identity-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
$rsa = $null
$certificate = $null
$publicCertificate = $null
try {
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
        "CN=MoonWaker identity test",
        $rsa,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $certificate = $request.CreateSelfSigned(
        [DateTimeOffset]::Now.AddMinutes(-1),
        [DateTimeOffset]::Now.AddMinutes(10))
    $der = $certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    $publicCertificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($der)
    $pemBody = [Convert]::ToBase64String($der)
    $pem = "-----BEGIN CERTIFICATE-----`n$pemBody`n-----END CERTIFICATE-----"
    $challenge = "moonwaker-vibepollo-identity-v1`nidentity-test-nonce"
    $signature = $rsa.SignData(
        [Text.Encoding]::UTF8.GetBytes($challenge),
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)

    if (-not (Test-VibepolloIdentitySignature $challenge $signature $publicCertificate)) {
        throw "valid RSA identity signature was rejected"
    }
    if (Test-VibepolloIdentitySignature ($challenge + "x") $signature $publicCertificate) {
        throw "altered identity challenge was accepted"
    }
    $fingerprint = Get-VibepolloCertificateFingerprint $publicCertificate
    $statePath = Join-Path $temporaryRoot "sunshine_state.json"
    $state = [ordered]@{
        root = [ordered]@{
            uniqueid = "identity-test-host"
            named_devices = @([ordered]@{ uuid = "identity-test-client"; cert = $pem })
        }
    }
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
    $loaded = Read-VibepolloIdentityState $statePath
    try {
        if ($loaded.host_uniqueid -ne "identity-test-host" -or
            @($loaded.records).Count -ne 1 -or
            $loaded.records[0].fingerprint -ne $fingerprint) {
            throw "trusted identity state was not parsed correctly"
        }
    } finally {
        foreach ($record in @($loaded.records)) { $record.certificate.Dispose() }
    }

    $oversizedPath = Join-Path $temporaryRoot "oversized-state.json"
    [IO.File]::WriteAllBytes($oversizedPath, (New-Object byte[] (1MB + 1)))
    $rejected = $false
    try { Read-VibepolloIdentityState $oversizedPath | Out-Null }
    catch { $rejected = $_.Exception.Message -match "identity_binding_unsupported" }
    if (-not $rejected) { throw "oversized identity state was accepted" }

    # Load only the production proof/state-path function ASTs.  This avoids
    # starting the Bridge while exercising the real fail-closed branches.
    $bridgeText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot "VibepolloBridge.ps1"))
    $parseTokens = $null
    $parseErrors = $null
    $bridgeAst = [System.Management.Automation.Language.Parser]::ParseInput(
        $bridgeText, [ref]$parseTokens, [ref]$parseErrors)
    foreach ($functionName in @("Get-VibepolloIdentityProof")) {
        $functionAst = $bridgeAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -eq $functionName
        }, $true) | Select-Object -First 1
        if ($null -eq $functionAst) { throw "missing production function $functionName" }
        . ([scriptblock]::Create($functionAst.Extent.Text))
    }
    $script:testProofStatePath = $statePath
    $script:testProofLiveHost = "identity-test-host"
    $script:testProofLiveRecords = @(
        [pscustomobject]@{ uuid = "identity-test-client" })
    function Get-PropertyValue {
        param($Object, [string[]]$Names, $Default = $null)
        if ($null -eq $Object) { return $Default }
        foreach ($name in $Names) {
            $property = $Object.PSObject.Properties[$name]
            if ($null -ne $property -and $null -ne $property.Value) { return $property.Value }
        }
        return $Default
    }
    function Get-VibepolloIdentityStatePath { return $script:testProofStatePath }
    function Get-VibepolloLiveUniqueId { return $script:testProofLiveHost }
    function Get-VibepolloClientRecords { return @($script:testProofLiveRecords) }
    $proofResult = Get-VibepolloIdentityProof -Challenge $challenge `
        -CertificateSha256 $fingerprint -Signature ([Convert]::ToBase64String($signature))
    if ($proofResult.client_uuid -ne "identity-test-client") {
        throw "valid identity proof returned the wrong client UUID"
    }
    function Assert-IdentityFailure {
        param([scriptblock]$Action, [string]$Reason)
        $matched = $false
        try { & $Action | Out-Null }
        catch { $matched = $_.Exception.Message -match [regex]::Escape($Reason) }
        if (-not $matched) { throw "identity proof did not fail as $Reason" }
    }
    Assert-IdentityFailure {
        Get-VibepolloIdentityProof -Challenge $challenge `
            -CertificateSha256 ("0" * 64) -Signature ([Convert]::ToBase64String($signature))
    } "pairing_required"
    $duplicateState = [ordered]@{
        root = [ordered]@{
            uniqueid = "identity-test-host"
            named_devices = @(
                [ordered]@{ uuid = "identity-test-client"; cert = $pem },
                [ordered]@{ uuid = "identity-test-client-2"; cert = $pem })
        }
    }
    $duplicateState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
    Assert-IdentityFailure {
        Get-VibepolloIdentityProof -Challenge $challenge `
            -CertificateSha256 $fingerprint -Signature ([Convert]::ToBase64String($signature))
    } "identity_ambiguous"
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
    $script:testProofLiveHost = "different-host"
    Assert-IdentityFailure {
        Get-VibepolloIdentityProof -Challenge $challenge `
            -CertificateSha256 $fingerprint -Signature ([Convert]::ToBase64String($signature))
    } "identity_binding_unsupported"
    $script:testProofLiveHost = "identity-test-host"
    $script:testProofLiveRecords = @([pscustomobject]@{ uuid = "other-client" })
    Assert-IdentityFailure {
        Get-VibepolloIdentityProof -Challenge $challenge `
            -CertificateSha256 $fingerprint -Signature ([Convert]::ToBase64String($signature))
    } "pairing_required"

    # The explicit file_state parser must retain a scalar path intact.
    $installRoot = Join-Path $temporaryRoot "vibepollo"
    $configRoot = Join-Path $installRoot "config"
    New-Item -ItemType Directory -Path $configRoot -Force | Out-Null
    $customStatePath = Join-Path $configRoot "custom-state.json"
    "{}" | Set-Content -LiteralPath $customStatePath -Encoding UTF8
    "file_state = custom-state.json`n" | Set-Content `
        -LiteralPath (Join-Path $configRoot "sunshine.conf") -Encoding UTF8
    $script:testInstallRoot = $installRoot
    $functionAst = $bridgeAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq "Get-VibepolloIdentityStatePath"
    }, $true) | Select-Object -First 1
    . ([scriptblock]::Create($functionAst.Extent.Text))
    function Get-VibepolloIdentityInstallRoot { return $script:testInstallRoot }
    $resolvedStatePath = Get-VibepolloIdentityStatePath
    if ([IO.Path]::GetFullPath($resolvedStatePath) -ne
        [IO.Path]::GetFullPath($customStatePath)) {
        throw "explicit file_state path was not preserved"
    }
    Write-Output "PASS: Vibepollo identity certificate proof and bounded state parsing"
}
finally {
    if ($publicCertificate) { $publicCertificate.Dispose() }
    if ($certificate) { $certificate.Dispose() }
    if ($rsa) { $rsa.Dispose() }
    $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]"\/") + [IO.Path]::DirectorySeparatorChar
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot).TrimEnd([char[]]"\/")
    if ($resolvedTemporaryRoot.StartsWith($tempParent, [StringComparison]::OrdinalIgnoreCase) -and
        $resolvedTemporaryRoot -ne $tempParent.TrimEnd([char[]]"\/")) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
