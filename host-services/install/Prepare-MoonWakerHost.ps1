#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$EnableWakeOnLan,
    [switch]$EnsureVibepollo,
    [string]$ProtectedStagingDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$trustedModuleRoot = [IO.Path]::GetFullPath((Join-Path $PSHOME "Modules"))
$env:PSModulePath = $trustedModuleRoot
$netAdapterManifest = Join-Path $trustedModuleRoot "NetAdapter\NetAdapter.psd1"
if (-not (Test-Path -LiteralPath $netAdapterManifest -PathType Leaf)) {
    throw "Required trusted Windows PowerShell module is missing: NetAdapter"
}
Import-Module -Name $netAdapterManifest -Force -ErrorAction Stop

function Get-TrustedSystemExecutable([string]$Name) {
    $systemDirectory = [Environment]::SystemDirectory
    if ([Environment]::Is64BitOperatingSystem -and -not [Environment]::Is64BitProcess) {
        $systemDirectory = Join-Path (Split-Path -Parent $systemDirectory) "Sysnative"
    }
    $path = [IO.Path]::GetFullPath((Join-Path $systemDirectory $Name))
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Trusted Windows executable was not found: $path"
    }
    return $path
}

function New-AdministratorOnlyDirectoryAcl {
    $security = [Security.AccessControl.DirectorySecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    $security.SetOwner([Security.Principal.SecurityIdentifier]::new("S-1-5-32-544"))
    $inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    foreach ($sid in @("S-1-5-18", "S-1-5-32-544")) {
        $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),
            [Security.AccessControl.FileSystemRights]::FullControl, $inheritance,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    return $security
}

function Assert-AdministratorOnlyDirectory([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $programData = [IO.Path]::GetFullPath([Environment]::GetFolderPath(
        [Environment+SpecialFolder]::CommonApplicationData)).TrimEnd('\')
    $leaf = [IO.Path]::GetFileName($full)
    $id = [guid]::Empty
    if (-not [IO.Path]::GetDirectoryName($full).Equals($programData,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not $leaf.StartsWith("MoonWakerInstaller-", [StringComparison]::Ordinal) -or
        -not [guid]::TryParseExact($leaf.Substring("MoonWakerInstaller-".Length), "N", [ref]$id)) {
        throw "The protected installer staging path is invalid."
    }
    $item = Get-Item -LiteralPath $full -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "The protected installer staging path is a reparse point."
    }
    $security = [IO.Directory]::GetAccessControl($full,
        [Security.AccessControl.AccessControlSections]::Access -bor
        [Security.AccessControl.AccessControlSections]::Owner)
    if (-not $security.AreAccessRulesProtected -or
        $security.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
            @("S-1-5-18", "S-1-5-32-544")) {
        throw "The protected installer staging owner or inheritance is unsafe."
    }
    $found = @{}
    foreach ($rule in $security.GetAccessRules($true, $false,
            [Security.Principal.SecurityIdentifier])) {
        $sid = $rule.IdentityReference.Value
        if ($rule.IsInherited -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $sid -notin @("S-1-5-18", "S-1-5-32-544") -or
            ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne
                [Security.AccessControl.FileSystemRights]::FullControl) {
            throw "The protected installer staging grants untrusted access."
        }
        $found[$sid] = $true
    }
    if (-not $found["S-1-5-18"] -or -not $found["S-1-5-32-544"]) {
        throw "The protected installer staging ACL is incomplete."
    }
    return $full
}

$msiexecPath = Get-TrustedSystemExecutable "msiexec.exe"
$powercfgPath = Get-TrustedSystemExecutable "powercfg.exe"

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

    if ([string]::IsNullOrWhiteSpace($ProtectedStagingDirectory)) {
        throw "Vibepollo installation requires the protected installer staging directory."
    }
    $protectedRoot = Assert-AdministratorOnlyDirectory $ProtectedStagingDirectory
    $downloadRoot = Join-Path $protectedRoot (
        "vibepollo-" + [guid]::NewGuid().ToString("N"))
    $installer = Join-Path $downloadRoot "Vibepollo.msi"
    [IO.DirectoryInfo]::new($downloadRoot).Create((New-AdministratorOnlyDirectoryAcl))
    try {
        Write-Host "Downloading the signed Vibepollo installer..."
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $downloadUrl.AbsoluteUri `
            -OutFile $installer
        $signature = Get-AuthenticodeSignature -LiteralPath $installer
        if ([string]$signature.Status -ne "Valid") {
            throw "The downloaded Vibepollo installer does not have a valid Authenticode signature ($($signature.Status))."
        }
        if ((Get-Item -LiteralPath $installer -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint) {
            throw "The downloaded Vibepollo installer became a reparse point."
        }
        Write-Host "Installing Vibepollo..."
        $process = Start-Process -FilePath $msiexecPath `
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

function Enable-MoonWakerWakeOnLan {
    $programmable = @(& $powercfgPath /devicequery wake_programmable 2>$null |
        ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $adapters = @(NetAdapter\Get-NetAdapter -Physical -ErrorAction SilentlyContinue |
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
        NetAdapter\Enable-NetAdapterPowerManagement -Name $adapter.Name -WakeOnMagicPacket `
            -Confirm:$false -ErrorAction Stop
        & $powercfgPath /deviceenableawake "$($adapter.InterfaceDescription)" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Windows could not arm $($adapter.InterfaceDescription) for wake."
        }
    }
    $armed = @(& $powercfgPath /devicequery wake_armed 2>$null |
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

if ($EnsureVibepollo) {
    [void](Install-Vibepollo)
}
if ($EnableWakeOnLan) { Enable-MoonWakerWakeOnLan }
Write-Host "MoonWaker host prerequisites are ready."
