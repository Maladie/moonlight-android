# Shared, side-effect-free helpers for proving ownership of a paired
# Moonlight certificate.  The Bridge owns the state lookup; callers never
# provide a state path or receive the certificate material back.

function ConvertTo-VibepolloDerCertificate {
    param([Parameter(Mandatory)][string]$Pem)
    $match = [regex]::Match(
        $Pem,
        '-----BEGIN CERTIFICATE-----\s*(?<body>[A-Za-z0-9+/=\r\n]+?)\s*-----END CERTIFICATE-----',
        [Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { throw "identity_binding_unsupported" }
    try {
        $der = [Convert]::FromBase64String(($match.Groups['body'].Value -replace '\s', ''))
        if ($der.Length -lt 64 -or $der.Length -gt 64KB) {
            throw "invalid certificate size"
        }
        return [Security.Cryptography.X509Certificates.X509Certificate2]::new($der)
    }
    catch {
        throw "identity_binding_unsupported"
    }
}

function Get-VibepolloCertificateFingerprint {
    param([Parameter(Mandatory)]$Certificate)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return (($sha.ComputeHash($Certificate.RawData) | ForEach-Object {
            $_.ToString('x2')
        }) -join '')
    }
    finally { $sha.Dispose() }
}

function Read-VibepolloIdentityState {
    param([Parameter(Mandatory)][string]$StatePath)
    try {
        $stateFile = Get-Item -LiteralPath $StatePath -ErrorAction Stop
        if (($stateFile.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $stateFile.Length -gt 1MB) {
            throw "invalid state file"
        }
        $document = Get-Content -LiteralPath $StatePath -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "identity_binding_unsupported"
    }
    $root = if ($null -ne $document) { $document.root } else { $null }
    $hostId = if ($null -ne $root) { [string]$root.uniqueid } else { '' }
    $rawRecords = if ($null -ne $root) { @($root.named_devices) } else { @() }
    if ([string]::IsNullOrWhiteSpace($hostId) -or $rawRecords.Count -eq 0) {
        throw "identity_binding_unsupported"
    }
    $records = @()
    foreach ($raw in $rawRecords) {
        $uuid = [string]$raw.uuid
        $pem = [string]$raw.cert
        if ([string]::IsNullOrWhiteSpace($uuid) -or
            [string]::IsNullOrWhiteSpace($pem)) { continue }
        try { $certificate = ConvertTo-VibepolloDerCertificate $pem }
        catch { continue }
        $records += [pscustomobject]@{
            uuid = $uuid
            fingerprint = Get-VibepolloCertificateFingerprint $certificate
            certificate = $certificate
        }
    }
    if ($records.Count -eq 0) { throw "identity_binding_unsupported" }
    return [pscustomobject]@{ host_uniqueid = $hostId; records = @($records) }
}

function Test-VibepolloIdentitySignature {
    param(
        [Parameter(Mandatory)][string]$Challenge,
        [Parameter(Mandatory)][byte[]]$Signature,
        [Parameter(Mandatory)]$Certificate
    )
    if ([string]::IsNullOrWhiteSpace($Challenge) -or
        $Challenge.Length -gt 512 -or
        [Text.Encoding]::UTF8.GetBytes($Challenge).Length -gt 512) { return $false }
    if ($Challenge -notmatch '^moonwaker-vibepollo-identity-v1\n') { return $false }
    try {
        $rsa = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($Certificate)
        if ($null -eq $rsa) { return $false }
        try {
            return $rsa.VerifyData(
                [Text.Encoding]::UTF8.GetBytes($Challenge), $Signature,
                [Security.Cryptography.HashAlgorithmName]::SHA256,
                [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        }
        finally { $rsa.Dispose() }
    }
    catch { return $false }
}
