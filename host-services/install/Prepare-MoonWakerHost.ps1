#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$EnableWakeOnLan,
    [switch]$EnsureVibepollo,
    [string]$VibepolloCredentialPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-VibepolloInstallation {
    foreach ($root in @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )) {
        $entry = Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object { [string]$_.DisplayName -like "*Vibepollo*" } |
            Select-Object -First 1
        if ($null -ne $entry) { return $entry }
    }
    return $null
}

function Install-Vibepollo {
    if ($null -ne (Get-VibepolloInstallation)) {
        Write-Host "Vibepollo is already installed."
        return $false
    }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Write-Host "Finding the latest stable Vibepollo release..."
    $headers = @{ Accept = "application/vnd.github+json"; "User-Agent" = "MoonWaker-Host-Installer" }
    $release = Invoke-RestMethod -UseBasicParsing -Headers $headers `
        -Uri "https://api.github.com/repos/Nonary/Vibepollo/releases/latest"
    $asset = @($release.assets | Where-Object { [string]$_.name -ieq "Vibepollo.msi" }) |
        Select-Object -First 1
    if ($null -eq $asset) { throw "The latest stable Vibepollo release has no Vibepollo.msi asset." }
    $downloadUrl = [uri][string]$asset.browser_download_url
    if ($downloadUrl.Scheme -ne "https" -or $downloadUrl.Host -ne "github.com" -or
        -not $downloadUrl.AbsolutePath.StartsWith("/Nonary/Vibepollo/releases/download/",
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Vibepollo returned an unexpected download URL."
    }

    $downloadRoot = Join-Path ([IO.Path]::GetTempPath()) (
        "moonwaker-vibepollo-" + [guid]::NewGuid().ToString("N"))
    $installer = Join-Path $downloadRoot "Vibepollo.msi"
    New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
    try {
        Write-Host "Downloading the signed Vibepollo installer..."
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $downloadUrl.AbsoluteUri `
            -OutFile $installer
        $signature = Get-AuthenticodeSignature -LiteralPath $installer
        if ([string]$signature.Status -ne "Valid") {
            throw "The downloaded Vibepollo installer does not have a valid Authenticode signature ($($signature.Status))."
        }
        Write-Host "Installing Vibepollo..."
        $process = Start-Process -FilePath "msiexec.exe" `
            -ArgumentList @("/i", "`"$installer`"", "/qn", "/norestart") `
            -Wait -PassThru
        if ($process.ExitCode -notin @(0, 3010)) {
            throw "Vibepollo installation failed with exit code $($process.ExitCode)."
        }
    } finally {
        if (Test-Path -LiteralPath $downloadRoot) {
            Remove-Item -LiteralPath $downloadRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    if ($null -eq (Get-VibepolloInstallation)) {
        throw "Vibepollo installation completed but Windows did not register the product."
    }
    return $true
}

function Get-VibepolloExecutable {
    $candidates = @((Join-Path $env:ProgramFiles "Vibepollo\sunshine.exe"))
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
        $candidates += Join-Path ${env:ProgramFiles(x86)} "Vibepollo\sunshine.exe"
    }
    $installation = Get-VibepolloInstallation
    if ($null -ne $installation -and -not [string]::IsNullOrWhiteSpace(
            [string]$installation.InstallLocation)) {
        $candidates = @((Join-Path ([string]$installation.InstallLocation) "sunshine.exe")) +
            $candidates
    }
    return $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
}

function Initialize-VibepolloCredentials {
    param([Parameter(Mandatory)][string]$CredentialPath)
    if (-not (Test-Path -LiteralPath $CredentialPath -PathType Leaf)) {
        throw "The protected Vibepollo credential file is missing."
    }
    Add-Type -AssemblyName System.Security
    $plain = $null
    $password = $null
    try {
        $credential = @(Get-Content -LiteralPath $CredentialPath)
        if ($credential.Count -ne 2) { throw "The protected credential file is invalid." }
        $username = [Text.Encoding]::UTF8.GetString(
            [Convert]::FromBase64String([string]$credential[0]))
        $protected = [Convert]::FromBase64String([string]$credential[1])
        $plain = [Security.Cryptography.ProtectedData]::Unprotect(
            $protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $password = [Text.Encoding]::UTF8.GetString($plain)
        if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
            throw "The Vibepollo administrator credentials are incomplete."
        }
        $executable = Get-VibepolloExecutable
        if ([string]::IsNullOrWhiteSpace($executable)) {
            throw "Vibepollo is installed but sunshine.exe could not be found."
        }
        $service = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue |
            Where-Object { [string]$_.PathName -like "*$executable*" -or
                ([string]$_.Name -like "*Sunshine*" -and [string]$_.PathName -like "*Vibepollo*") } |
            Select-Object -First 1
        try {
            if ($null -ne $service) {
                Stop-Service -Name ([string]$service.Name) -Force -ErrorAction SilentlyContinue
            }
            Write-Host "Creating the Vibepollo administrator account..."
            Push-Location (Split-Path -Parent $executable)
            try {
                & $executable --creds $username $password | Out-Host
                $credentialExitCode = $LASTEXITCODE
            } finally { Pop-Location }
            if ($credentialExitCode -ne 0) {
                throw "Vibepollo rejected the administrator account configuration."
            }
        } finally {
            if ($null -ne $service) {
                Start-Service -Name ([string]$service.Name) -ErrorAction SilentlyContinue
            }
        }
        if ($null -ne $service) {
            $deadline = [DateTime]::UtcNow.AddSeconds(30)
            $connected = $false
            do {
                $client = [Net.Sockets.TcpClient]::new()
                try {
                    $connected = $client.ConnectAsync("127.0.0.1", 47990).Wait(500)
                    if ($connected -and $client.Connected) { break }
                } catch {} finally { $client.Dispose() }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
            if (-not $connected) { throw "Vibepollo did not start its local API in time." }
        }
    } finally {
        if ($null -ne $plain) { [Array]::Clear($plain, 0, $plain.Length) }
        $password = $null
        Remove-Item -LiteralPath $CredentialPath -Force -ErrorAction SilentlyContinue
    }
}

function Enable-MoonWakerWakeOnLan {
    $programmable = @(& powercfg.exe /devicequery wake_programmable 2>$null |
        ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $adapters = @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue |
        Where-Object { $_.HardwareInterface -and [int]$_.NdisPhysicalMedium -eq 14 -and
            $_.Status -ne "Disabled" })
    $supported = @($adapters | Where-Object {
        $_.InterfaceDescription -in $programmable -or $_.Name -in $programmable
    })
    if ($supported.Count -eq 0) {
        Write-Warning "Windows did not report a programmable wired Wake-on-LAN adapter. Check the BIOS/UEFI and network driver settings."
        return
    }
    foreach ($adapter in $supported) {
        Write-Host "Enabling Wake-on-LAN for $($adapter.Name)..."
        Enable-NetAdapterPowerManagement -Name $adapter.Name -WakeOnMagicPacket `
            -Confirm:$false -ErrorAction Stop
        & powercfg.exe /deviceenableawake "$($adapter.InterfaceDescription)" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Windows could not arm $($adapter.InterfaceDescription) for wake."
        }
    }
    $armed = @(& powercfg.exe /devicequery wake_armed 2>$null |
        ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($adapter in $supported) {
        if ($adapter.InterfaceDescription -notin $armed -and $adapter.Name -notin $armed) {
            throw "Windows did not keep $($adapter.InterfaceDescription) armed for wake. Check its driver power-management settings."
        }
    }
}

if (-not (Test-Administrator)) {
    throw "MoonWaker host preparation must run as administrator."
}

$vibepolloInstalled = $false
if ($EnsureVibepollo) {
    $vibepolloInstalled = Install-Vibepollo
    if ($vibepolloInstalled) {
        Initialize-VibepolloCredentials -CredentialPath $VibepolloCredentialPath
    }
}
if ($EnableWakeOnLan) { Enable-MoonWakerWakeOnLan }
Write-Host "MoonWaker host prerequisites are ready."
