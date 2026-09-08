#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$EnableWakeOnLan,
    [switch]$EnsureVibepollo,
    [switch]$EnsureViGEmBus,
    [switch]$EnsurePython,
    [switch]$EnsureOpenSsl,
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
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "The protected installer staging path is empty."
    }
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
        $entries = @(Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object {
                $null -ne $_.PSObject.Properties["DisplayName"] -and
                [string]$_.DisplayName -like "*Vibepollo*"
            })
        foreach ($entry in $entries) {
            $locationProperty = $entry.PSObject.Properties["InstallLocation"]
            if ($null -eq $locationProperty -or
                [string]::IsNullOrWhiteSpace([string]$locationProperty.Value)) { continue }
            try {
                $executable = Join-Path ([IO.Path]::GetFullPath([string]$locationProperty.Value)) `
                    "sunshine.exe"
                if (Test-Path -LiteralPath $executable -PathType Leaf) { return $entry }
            } catch {}
        }
    }
    foreach ($programFiles in @(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFilesX86))) {
        if ([string]::IsNullOrWhiteSpace($programFiles)) { continue }
        $executable = Join-Path $programFiles "Vibepollo\sunshine.exe"
        if (Test-Path -LiteralPath $executable -PathType Leaf) {
            return [pscustomobject]@{ InstallLocation = Split-Path -Parent $executable }
        }
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
    $assetsProperty = if ($null -ne $release) { $release.PSObject.Properties["assets"] } else { $null }
    $asset = @($(if ($null -ne $assetsProperty) { $assetsProperty.Value }) | Where-Object {
        $null -ne $_.PSObject.Properties["name"] -and
        [string]$_.name -match '^VibepolloSetup-.*\.exe$'
    }) |
        Select-Object -First 1
    if ($null -eq $asset) {
        throw "The latest stable Vibepollo release has no official setup executable."
    }
    $urlProperty = $asset.PSObject.Properties["browser_download_url"]
    [uri]$downloadUrl = $null
    if ($null -eq $urlProperty -or
        -not [uri]::TryCreate([string]$urlProperty.Value, [UriKind]::Absolute,
            [ref]$downloadUrl)) {
        throw "Vibepollo returned an invalid download URL."
    }
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
    $installer = Join-Path $downloadRoot "VibepolloSetup.exe"
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
        Write-Host "Starting the official Vibepollo setup..."
        $process = Start-Process -FilePath $installer -Wait -PassThru
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

function Get-ViGEmBusInstallation {
    $driverPath = Join-Path $env:SystemRoot "System32\drivers\ViGEmBus.sys"
    if (Test-Path -LiteralPath $driverPath -PathType Leaf) {
        return [pscustomobject]@{
            Path = $driverPath
            Version = [string](Get-Item -LiteralPath $driverPath).VersionInfo.FileVersion
        }
    }
    foreach ($root in @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )) {
        $entry = Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object {
                $null -ne $_.PSObject.Properties["DisplayName"] -and
                ([string]$_.DisplayName -like "*ViGEmBus*" -or
                 [string]$_.DisplayName -like "*Virtual Gamepad Emulation Bus*")
            } |
            Select-Object -First 1
        if ($null -ne $entry) {
            $version = if ($null -ne $entry.PSObject.Properties["DisplayVersion"]) {
                [string]$entry.DisplayVersion
            } else { "" }
            return [pscustomobject]@{ Path = [string]$entry.PSPath; Version = $version }
        }
    }
    return $null
}

function Test-CompatibleViGEmBus {
    $installation = Get-ViGEmBusInstallation
    if ($null -eq $installation -or [string]::IsNullOrWhiteSpace($installation.Version)) {
        return $false
    }
    [version]$installedVersion = $null
    return [version]::TryParse($installation.Version, [ref]$installedVersion) -and
        $installedVersion -ge [version]"1.17"
}

function Install-ViGEmBus {
    if (Test-CompatibleViGEmBus) {
        Write-Host "A compatible ViGEmBus driver is already installed."
        return $false
    }
    if ([string]::IsNullOrWhiteSpace($ProtectedStagingDirectory)) {
        throw "ViGEmBus installation requires the protected installer staging directory."
    }

    $downloadUrl = [uri](
        "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/" +
        "ViGEmBus_1.22.0_x64_x86_arm64.exe")
    $expectedHash = "89220A7865076B342892F98865F3499FB7C4CFD673159E89D352C360FD014C6A"
    $protectedRoot = Assert-AdministratorOnlyDirectory $ProtectedStagingDirectory
    $downloadRoot = Join-Path $protectedRoot ("vigembus-" + [guid]::NewGuid().ToString("N"))
    $installer = Join-Path $downloadRoot "ViGEmBusSetup.exe"
    [IO.DirectoryInfo]::new($downloadRoot).Create((New-AdministratorOnlyDirectoryAcl))
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Write-Host "Downloading the archived ViGEmBus 1.22.0 setup..."
        Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl.AbsoluteUri -OutFile $installer
        $hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash
        if (-not $hash.Equals($expectedHash, [StringComparison]::OrdinalIgnoreCase)) {
            throw "The downloaded ViGEmBus installer does not match its pinned SHA-256 hash."
        }
        $signature = Get-AuthenticodeSignature -LiteralPath $installer
        if ([string]$signature.Status -ne "Valid" -or
            [string]$signature.SignerCertificate.Subject -notlike
                "CN=Nefarius Software Solutions e.U.,*") {
            throw "The downloaded ViGEmBus installer does not have the expected valid publisher signature."
        }
        if ((Get-Item -LiteralPath $installer -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint) {
            throw "The downloaded ViGEmBus installer became a reparse point."
        }

        foreach ($attempt in 1..2) {
            Write-Host "Installing the ViGEmBus virtual gamepad driver (attempt $attempt)..."
            $process = Start-Process -FilePath $installer -ArgumentList @("/qn", "/norestart") `
                -Wait -PassThru
            if ($process.ExitCode -notin @(0, 3010)) {
                throw "ViGEmBus installation failed with exit code $($process.ExitCode)."
            }
            if (Test-CompatibleViGEmBus) { return $true }
        }
    } finally {
        if (Test-Path -LiteralPath $downloadRoot) {
            Remove-Item -LiteralPath $downloadRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    throw "ViGEmBus installation completed but a compatible driver could not be found."
}

function Get-MachinePythonExecutable {
    $candidates = @()
    foreach ($programFiles in @(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFilesX86))) {
        if ([string]::IsNullOrWhiteSpace($programFiles) -or
            -not (Test-Path -LiteralPath $programFiles -PathType Container)) { continue }
        $candidates += Get-ChildItem -LiteralPath $programFiles -Directory -Filter "Python*" `
            -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName "python.exe" }
    }
    foreach ($candidate in @($candidates | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    } | Sort-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $versionText = & $candidate -c `
            "import sys; print('.'.join(map(str, sys.version_info[:3])))" 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        [version]$version = $null
        if ([version]::TryParse([string]$versionText, [ref]$version) -and
            $version -ge [version]"3.10" -and $version -lt [version]"4.0") {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    return $null
}

function Install-Python {
    $existing = Get-MachinePythonExecutable
    if ($null -ne $existing) {
        Write-Host "A compatible system-wide Python installation is already available."
        return $existing
    }
    if ([string]::IsNullOrWhiteSpace($ProtectedStagingDirectory)) {
        throw "Python installation requires the protected installer staging directory."
    }

    $downloadUrl = [uri]"https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe"
    $expectedHash = "67B5635E80EA51072B87941312D00EC8927C4DB9BA18938F7AD2D27B328B95FB"
    $protectedRoot = Assert-AdministratorOnlyDirectory $ProtectedStagingDirectory
    $downloadRoot = Join-Path $protectedRoot ("python-" + [guid]::NewGuid().ToString("N"))
    $installer = Join-Path $downloadRoot "PythonSetup.exe"
    [IO.DirectoryInfo]::new($downloadRoot).Create((New-AdministratorOnlyDirectoryAcl))
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Write-Host "Downloading the verified Python 3.12 runtime..."
        Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl.AbsoluteUri -OutFile $installer
        $hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash
        if (-not $hash.Equals($expectedHash, [StringComparison]::OrdinalIgnoreCase)) {
            throw "The downloaded Python installer does not match its pinned SHA-256 hash."
        }
        $signature = Get-AuthenticodeSignature -LiteralPath $installer
        if ([string]$signature.Status -ne "Valid" -or
            [string]$signature.SignerCertificate.Subject -notlike
                "CN=Python Software Foundation,*") {
            throw "The downloaded Python installer does not have the expected valid publisher signature."
        }
        if ((Get-Item -LiteralPath $installer -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint) {
            throw "The downloaded Python installer became a reparse point."
        }
        Write-Host "Installing Python 3.12 for the MoonWaker Gateway service..."
        $process = Start-Process -FilePath $installer -ArgumentList @(
            "/quiet", "InstallAllUsers=1", "PrependPath=0", "Include_launcher=0",
            "Include_pip=0", "Include_test=0", "Include_doc=0", "Include_tcltk=0",
            "Shortcuts=0") -Wait -PassThru
        if ($process.ExitCode -notin @(0, 3010)) {
            throw "Python installation failed with exit code $($process.ExitCode)."
        }
    } finally {
        if (Test-Path -LiteralPath $downloadRoot) {
            Remove-Item -LiteralPath $downloadRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    $installed = Get-MachinePythonExecutable
    if ($null -eq $installed) {
        throw "Python installation completed but a compatible system-wide runtime could not be found."
    }
    return $installed
}

function Get-OpenSslExecutable {
    foreach ($command in @(Get-Command openssl.exe -CommandType Application `
            -ErrorAction SilentlyContinue)) {
        $pathProperty = $command.PSObject.Properties["Path"]
        $path = if ($null -ne $pathProperty) { [string]$pathProperty.Value } else { "" }
        if (-not [string]::IsNullOrWhiteSpace($path) -and
            (Test-Path -LiteralPath $path -PathType Leaf)) {
            return [IO.Path]::GetFullPath($path)
        }
    }
    $candidates = @()
    foreach ($programFiles in @(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFilesX86))) {
        if ([string]::IsNullOrWhiteSpace($programFiles)) { continue }
        $candidates += Join-Path $programFiles "OpenSSL-Win64\bin\openssl.exe"
        $candidates += Join-Path $programFiles "OpenSSL-Win32\bin\openssl.exe"
        $candidates += Join-Path $programFiles "OpenSSL\bin\openssl.exe"
    }
    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and
            (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    return $null
}

function Install-OpenSsl {
    $existing = Get-OpenSslExecutable
    if ($null -ne $existing) {
        Write-Host "OpenSSL is already installed."
        return $existing
    }
    if ([string]::IsNullOrWhiteSpace($ProtectedStagingDirectory)) {
        throw "OpenSSL installation requires the protected installer staging directory."
    }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Write-Host "Finding the current OpenSSL Light LTS installer..."
    $manifest = Invoke-RestMethod -UseBasicParsing `
        -Uri "https://raw.githubusercontent.com/slproweb/opensslhashes/master/win32_openssl_hashes.json"
    $bits = if ([Environment]::Is64BitOperatingSystem) { 64 } else { 32 }
    $filesProperty = if ($null -ne $manifest) { $manifest.PSObject.Properties["files"] } else { $null }
    $compatible = @()
    if ($null -ne $filesProperty -and $null -ne $filesProperty.Value) {
        foreach ($property in $filesProperty.Value.PSObject.Properties) {
            $candidate = $property.Value
            if ($null -eq $candidate) { continue }
            $required = @("arch", "bits", "light", "installer", "basever", "url", "sha256")
            if (@($required | Where-Object { $null -eq $candidate.PSObject.Properties[$_] }).Count) {
                continue
            }
            [version]$candidateVersion = $null
            if ([string]$candidate.arch -eq "INTEL" -and [int]$candidate.bits -eq $bits -and
                $candidate.light -eq $true -and [string]$candidate.installer -eq "msi" -and
                [version]::TryParse([string]$candidate.basever, [ref]$candidateVersion) -and
                $candidateVersion -ge [version]"3.5" -and $candidateVersion -lt [version]"3.6") {
                $compatible += [pscustomobject]@{ asset = $candidate; version = $candidateVersion }
            }
        }
    }
    $selected = @($compatible | Sort-Object version -Descending) | Select-Object -First 1
    $asset = if ($null -ne $selected) { $selected.asset } else { $null }
    if ($null -eq $asset) {
        throw "The OpenSSL manifest has no compatible OpenSSL Light 3.5 LTS MSI."
    }
    $architecture = if ($bits -eq 64) { "Win64" } else { "Win32" }
    [uri]$downloadUrl = $null
    if (-not [uri]::TryCreate([string]$asset.url, [UriKind]::Absolute,
            [ref]$downloadUrl)) {
        throw "The OpenSSL manifest returned an invalid download URL."
    }
    if ([string]$asset.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw "The OpenSSL manifest returned an invalid SHA-256 hash."
    }
    if ($downloadUrl.Scheme -ne "https" -or $downloadUrl.Host -ne "slproweb.com" -or
        -not $downloadUrl.AbsolutePath.StartsWith("/download/${architecture}OpenSSL_Light-",
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "OpenSSL returned an unexpected download URL."
    }

    $protectedRoot = Assert-AdministratorOnlyDirectory $ProtectedStagingDirectory
    $downloadRoot = Join-Path $protectedRoot ("openssl-" + [guid]::NewGuid().ToString("N"))
    $installer = Join-Path $downloadRoot "OpenSSL-Light.msi"
    [IO.DirectoryInfo]::new($downloadRoot).Create((New-AdministratorOnlyDirectoryAcl))
    try {
        Write-Host "Downloading the verified OpenSSL Light installer..."
        Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl.AbsoluteUri -OutFile $installer
        $hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash
        if (-not $hash.Equals([string]$asset.sha256, [StringComparison]::OrdinalIgnoreCase)) {
            throw "The downloaded OpenSSL installer does not match the published SHA-256 hash."
        }
        $signature = Get-AuthenticodeSignature -LiteralPath $installer
        if ([string]$signature.Status -ne "Valid" -or $null -eq $signature.SignerCertificate) {
            throw "The downloaded OpenSSL installer does not have a valid Authenticode signature."
        }
        Write-Host "Installing OpenSSL Light..."
        $process = Start-Process -FilePath $msiexecPath `
            -ArgumentList @("/i", "`"$installer`"", "/qn", "/norestart") `
            -Wait -PassThru
        if ($process.ExitCode -notin @(0, 3010)) {
            throw "OpenSSL installation failed with exit code $($process.ExitCode)."
        }
    } finally {
        if (Test-Path -LiteralPath $downloadRoot) {
            Remove-Item -LiteralPath $downloadRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    $installed = Get-OpenSslExecutable
    if ($null -eq $installed) {
        throw "OpenSSL installation completed but openssl.exe could not be found."
    }
    return $installed
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
if ($EnsureViGEmBus) { [void](Install-ViGEmBus) }
if ($EnsurePython) { [void](Install-Python) }
if ($EnsureOpenSsl) { [void](Install-OpenSsl) }
if ($EnableWakeOnLan) { Enable-MoonWakerWakeOnLan }
Write-Host "MoonWaker host prerequisites are ready."
