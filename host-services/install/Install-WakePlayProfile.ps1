#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]{1,64}$')]
    [string]$ProfileId = "default",
    [string]$ProfileName = "",
    [int]$DiscordPort = 8765,
    [int]$GameProviderPort = 0,
    [int]$PlaynitePort = 0,
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA "WakePlayHost\profiles"),
    [string]$GatewayConfigPath = "",
    [string]$GatewayDirectory = "",
    [ValidatePattern('^S-[0-9]+(?:-[0-9]+){2,15}$')][string]$OwnerSid = "",
    [string]$OwnerName = "",
    [switch]$MachineProvisioning,
    [switch]$SkipDiscord,
    [switch]$SkipPlaynite,
    [switch]$SkipGatewayRegistration,
    [switch]$NonInteractiveConfiguration,
    [switch]$SkipStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$trustedModuleRoot = [IO.Path]::GetFullPath((Join-Path $PSHOME "Modules"))
$env:PSModulePath = $trustedModuleRoot
foreach ($module in @("CimCmdlets")) {
    $manifest = Join-Path $trustedModuleRoot "$module\$module.psd1"
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        throw "Required trusted Windows PowerShell module is missing: $module"
    }
    Import-Module -Name $manifest -Force -ErrorAction Stop
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Resolve-ProvisioningOwner {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $MachineProvisioning) {
        if (-not [string]::IsNullOrWhiteSpace($OwnerSid) -or
            -not [string]::IsNullOrWhiteSpace($OwnerName)) {
            throw "OwnerSid and OwnerName require MachineProvisioning."
        }
        return [pscustomobject]@{ sid = $identity.User.Value; name = $identity.Name }
    }
    if (-not (Test-IsAdministrator)) {
        throw "Machine profile provisioning requires administrator permission."
    }
    if ([string]::IsNullOrWhiteSpace($OwnerSid) -or
        [string]::IsNullOrWhiteSpace($OwnerName)) {
        throw "Machine profile provisioning requires an explicit target account SID and name."
    }
    $account = @(CimCmdlets\Get-CimInstance Win32_UserAccount -Filter "SID='$OwnerSid'" |
        Where-Object { $_.LocalAccount -and -not $_.Disabled }) | Select-Object -First 1
    $localComputerName = [string](CimCmdlets\Get-CimInstance Win32_ComputerSystem -ErrorAction Stop).Name
    if ($null -eq $account -or
        [string]::IsNullOrWhiteSpace($localComputerName) -or
        -not ([string]$account.Domain).Equals($localComputerName,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "The selected SID is not an enabled local Windows account."
    }
    $resolvedName = "{0}\{1}" -f [string]$account.Domain, [string]$account.Name
    if (-not $resolvedName.Equals($OwnerName, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The selected Windows account name no longer matches its SID."
    }
    return [pscustomobject]@{ sid = $OwnerSid; name = $resolvedName }
}

$provisioningOwner = Resolve-ProvisioningOwner
if ($MachineProvisioning) {
    # An elevated provisioning process never starts profile processes; the owner's
    # Host Control tray does.
    $SkipStart = $true
    if (-not $SkipGatewayRegistration) {
        throw "Machine provisioning may not read or write the mutable Gateway registry as administrator."
    }
}

function Get-RegisteredEndpointPort {
    param([object]$Entry, [string]$Property)
    if ($null -eq $Entry.PSObject.Properties[$Property]) { return 0 }
    $value = [string]$Entry.$Property
    if ([string]::IsNullOrWhiteSpace($value)) { return 0 }
    $uri = $null
    if (-not [uri]::TryCreate($value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne "http" -or $uri.Host -notin @("127.0.0.1", "localhost") -or
        $uri.Port -lt 1024 -or $uri.Port -gt 65535) {
        throw "Existing profile endpoint '$Property' is invalid; refusing to create split configuration."
    }
    return $uri.Port
}

function Enter-GatewayRegistryLock {
    $lockPath = $GatewayRegistryLockPath
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $deadline) {
        $stream = $null
        try {
            $stream = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate,
                [IO.FileAccess]::ReadWrite, [IO.FileShare]::ReadWrite)
            if ($stream.Length -lt 1) {
                $stream.WriteByte(0)
                $stream.Flush($true)
                $stream.Position = 0
            }
            $stream.Lock(0, 1)
            return $stream
        } catch [IO.IOException] {
            if ($stream) { $stream.Dispose() }
            Start-Sleep -Milliseconds 50
        }
    }
    throw "Gateway registry is busy. Try the profile operation again."
}

function Exit-GatewayRegistryLock([IO.FileStream]$Stream) {
    if ($null -eq $Stream) { return }
    try { $Stream.Unlock(0, 1) } finally { $Stream.Dispose() }
}

function Set-GatewayRegistry([object]$Registry) {
    $temporary = $GatewayConfigPath + "." + [guid]::NewGuid().ToString("N") + ".tmp"
    try {
        $Registry | ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath $temporary -Encoding UTF8
        if (Test-Path -LiteralPath $GatewayConfigPath) {
            [IO.File]::Replace($temporary, $GatewayConfigPath, $null)
        } else {
            [IO.File]::Move($temporary, $GatewayConfigPath)
        }
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

if ([string]::IsNullOrWhiteSpace($GatewayConfigPath)) {
    $GatewayConfigPath = Join-Path (Split-Path -Parent $InstallRoot) "gateway\gateway.json"
}
$GatewayConfigPath = [IO.Path]::GetFullPath($GatewayConfigPath)
$GatewayRegistryLockPath = "$GatewayConfigPath.lock"
$profileRoot = Join-Path $InstallRoot $ProfileId
$resolvedInstallRoot = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
$profileRoot = [IO.Path]::GetFullPath($profileRoot)

function Assert-MachineProfileRoot {
    if (-not $MachineProvisioning) { return }
    $expected = [IO.Path]::GetFullPath((Join-Path $resolvedInstallRoot $ProfileId)).TrimEnd('\')
    $actual = [IO.Path]::GetFullPath($profileRoot).TrimEnd('\')
    if (-not $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Machine profile root must be exactly '<ProfilesRoot>\<ProfileId>'."
    }
    $cursor = [IO.DirectoryInfo]::new($actual)
    while ($null -ne $cursor) {
        if ($cursor.Exists -and ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "A reparse point exists in the machine profile path; provisioning stopped."
        }
        $cursor = $cursor.Parent
    }
}
Assert-MachineProfileRoot
$registeredProfile = $null
if (-not $MachineProvisioning -and (Test-Path -LiteralPath $GatewayConfigPath -PathType Leaf)) {
    $registryLock = Enter-GatewayRegistryLock
    try {
        $registeredGateway = Get-Content -LiteralPath $GatewayConfigPath -Raw | ConvertFrom-Json
    } finally { Exit-GatewayRegistryLock $registryLock }
    if ($null -ne $registeredGateway.PSObject.Properties["profiles"] -and
        $null -ne $registeredGateway.profiles.PSObject.Properties[$ProfileId]) {
        $registeredProfile = $registeredGateway.profiles.$ProfileId
        if ($null -ne $registeredProfile.PSObject.Properties["profile_root"] -and
            -not [string]::IsNullOrWhiteSpace([string]$registeredProfile.profile_root)) {
            if (-not [IO.Path]::IsPathRooted([string]$registeredProfile.profile_root)) {
                throw "Existing profile root must be an absolute path; refusing to create split configuration."
            }
            $profileRoot = [IO.Path]::GetFullPath([string]$registeredProfile.profile_root)
        }
        if ($null -ne $registeredProfile.PSObject.Properties["discord_bridge"]) {
            if ([string]::IsNullOrWhiteSpace([string]$registeredProfile.discord_bridge)) {
                $SkipDiscord = $true
            } else { $DiscordPort = Get-RegisteredEndpointPort $registeredProfile "discord_bridge" }
        }
        $providerProperty = if ($null -ne $registeredProfile.PSObject.Properties["game_provider_bridge"]) {
            "game_provider_bridge"
        } else { "playnite_bridge" }
        $registeredProviderPort = Get-RegisteredEndpointPort $registeredProfile $providerProperty
        if ($registeredProviderPort) { $GameProviderPort = $registeredProviderPort }
    }
}
Assert-MachineProfileRoot
$finalProfileRoot = $profileRoot
$machineStagingParent = ""
$machineStagingRoot = ""
$providerPort = if ($GameProviderPort) { $GameProviderPort } elseif ($PlaynitePort) {
    $PlaynitePort
} else { 8780 }
$profileDisplayName = if ([string]::IsNullOrWhiteSpace($ProfileName)) {
    $ProfileId
} else {
    $ProfileName.Trim()
}
if ($profileDisplayName.Length -gt 80 -or $profileDisplayName -match '[\x00-\x1f\x7f]') {
    throw "Profile name must contain at most 80 printable characters."
}

if ($DiscordPort -lt 1024 -or $DiscordPort -gt 65535 -or
    $providerPort -lt 1024 -or $providerPort -gt 65535) {
    throw "Bridge ports must be between 1024 and 65535."
}
$activePorts = @()
if (-not $SkipDiscord) { $activePorts += $DiscordPort }
$activePorts += $providerPort
$uniqueActivePorts = @($activePorts | Select-Object -Unique)
if ($uniqueActivePorts.Count -ne $activePorts.Count) {
    throw "Discord and Game Provider Bridges must use different ports."
}
if ($null -eq $registeredProfile -and $ProfileId -ne "default" -and (
    (-not $SkipDiscord -and -not $PSBoundParameters.ContainsKey("DiscordPort")) -or
    (-not $PSBoundParameters.ContainsKey("GameProviderPort") -and
        -not $PSBoundParameters.ContainsKey("PlaynitePort")))) {
    throw "Additional profiles require explicit, unique Discord and Game Provider ports."
}

$hostServicesRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $hostServicesRoot "bridges"
if (-not (Test-Path -LiteralPath $sourceRoot)) {
    $sourceRoot = Join-Path (Split-Path -Parent $PSScriptRoot) "bridge-source"
}
if (-not (Test-Path -LiteralPath $sourceRoot)) {
    throw "Bridge source package was not found next to the installer."
}

function New-ProfileAccessControl([bool]$Directory, [bool]$IncludeOwner) {
    $security = if ($Directory) {
        [Security.AccessControl.DirectorySecurity]::new()
    } else { [Security.AccessControl.FileSecurity]::new() }
    $security.SetAccessRuleProtection($true, $false)
    $security.SetOwner([Security.Principal.SecurityIdentifier]::new("S-1-5-32-544"))
    $inheritance = if ($Directory) {
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [Security.AccessControl.InheritanceFlags]::ObjectInherit
    } else { [Security.AccessControl.InheritanceFlags]::None }
    $propagation = [Security.AccessControl.PropagationFlags]::None
    $rules = @(
        [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new("S-1-5-18"),
            [Security.AccessControl.FileSystemRights]::FullControl, $inheritance, $propagation,
            [Security.AccessControl.AccessControlType]::Allow),
        [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new("S-1-5-32-544"),
            [Security.AccessControl.FileSystemRights]::FullControl, $inheritance, $propagation,
            [Security.AccessControl.AccessControlType]::Allow))
    if ($IncludeOwner) {
        $rules += [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($provisioningOwner.sid),
            [Security.AccessControl.FileSystemRights]::Modify, $inheritance, $propagation,
            [Security.AccessControl.AccessControlType]::Allow)
    }
    foreach ($rule in $rules) {
        $security.SetAccessRule($rule)
    }
    return $security
}

function New-ProfilesRootAccessControl {
    $security = [Security.AccessControl.DirectorySecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    $security.SetOwner([Security.Principal.SecurityIdentifier]::new("S-1-5-32-544"))
    foreach ($rule in @(
        [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new("S-1-5-18"),
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow),
        [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new("S-1-5-32-544"),
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow),
        [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new("S-1-5-32-545"),
            [Security.AccessControl.FileSystemRights]::ReadAndExecute,
            [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))) {
        $security.AddAccessRule($rule)
    }
    return $security
}

function Assert-ProfilesRootAccessControl {
    $effective = [IO.Directory]::GetAccessControl($resolvedInstallRoot)
    if (-not $effective.AreAccessRulesProtected) {
        throw "ProfilesRoot ACL is not protected."
    }
    if ($effective.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
            @("S-1-5-18", "S-1-5-32-544")) {
        throw "ProfilesRoot has an untrusted owner."
    }
    $systemSid = "S-1-5-18"
    $administratorsSid = "S-1-5-32-544"
    $usersSid = "S-1-5-32-545"
    $foundSystem = $false
    $foundAdministrators = $false
    $dangerousUserRights = [Security.AccessControl.FileSystemRights]::Write -bor
        [Security.AccessControl.FileSystemRights]::Modify -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    $rules = $effective.GetAccessRules($true, $false,
        [Security.Principal.SecurityIdentifier])
    foreach ($rule in $rules) {
        $ruleSid = $rule.IdentityReference.Value
        if ($rule.IsInherited -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $rule.InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]::None -or
            $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) {
            throw "ProfilesRoot contains an unexpected access rule."
        }
        if ($ruleSid -eq $systemSid) {
            $foundSystem = $true
        } elseif ($ruleSid -eq $administratorsSid) {
            $foundAdministrators = $true
        } elseif ($ruleSid -eq $usersSid) {
            if (($rule.FileSystemRights -band $dangerousUserRights) -ne 0) {
                throw "Non-administrators can create or delete profile roots; provisioning stopped."
            }
        } else {
            throw "ProfilesRoot grants access to an unexpected identity; provisioning stopped."
        }
    }
    if (-not $foundSystem -or -not $foundAdministrators) {
        throw "ProfilesRoot does not grant SYSTEM and Administrators control."
    }
}

function New-ProfilesRootEpochRegistrySecurity {
    $security = [Security.AccessControl.RegistrySecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    $security.SetOwner([Security.Principal.SecurityIdentifier]::new("S-1-5-32-544"))
    foreach ($sid in @("S-1-5-18", "S-1-5-32-544")) {
        $security.AddAccessRule([Security.AccessControl.RegistryAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),
            [Security.AccessControl.RegistryRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]::ContainerInherit,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    return $security
}

function Assert-TrustedMoonWakerRegistryKey([Microsoft.Win32.RegistryKey]$Key) {
    $security = $Key.GetAccessControl(
        [Security.AccessControl.AccessControlSections]::Access -bor
        [Security.AccessControl.AccessControlSections]::Owner)
    if (-not $security.AreAccessRulesProtected -or
        $security.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
            @("S-1-5-18", "S-1-5-32-544")) {
        throw "A MoonWaker machine registry key has an untrusted owner or inherited ACL."
    }
    $found = @{}
    foreach ($rule in $security.GetAccessRules($true, $false,
            [Security.Principal.SecurityIdentifier])) {
        $sid = $rule.IdentityReference.Value
        if ($rule.IsInherited -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $sid -notin @("S-1-5-18", "S-1-5-32-544") -or
            ($rule.RegistryRights -band [Security.AccessControl.RegistryRights]::FullControl) -ne
                [Security.AccessControl.RegistryRights]::FullControl) {
            throw "A MoonWaker machine registry key grants untrusted access."
        }
        $found[$sid] = $true
    }
    if (-not $found["S-1-5-18"] -or -not $found["S-1-5-32-544"]) {
        throw "A MoonWaker machine registry key lacks SYSTEM/Administrators control."
    }
}

function Open-ProtectedMoonWakerRegistryParent(
        [Microsoft.Win32.RegistryKey]$Machine, [string]$Name) {
    $moonWaker = $null
    try {
        $moonWaker = $Machine.CreateSubKey("SOFTWARE\MoonWaker")
        if ($null -eq $moonWaker) { throw "Cannot create the protected MoonWaker registry." }
        $moonWaker.SetAccessControl((New-ProfilesRootEpochRegistrySecurity))
        Assert-TrustedMoonWakerRegistryKey $moonWaker
        $child = $moonWaker.CreateSubKey($Name)
        if ($null -eq $child) { throw "Cannot create protected MoonWaker registry '$Name'." }
        $child.SetAccessControl((New-ProfilesRootEpochRegistrySecurity))
        Assert-TrustedMoonWakerRegistryKey $child
        return $child
    } finally {
        if ($null -ne $moonWaker) { $moonWaker.Dispose() }
    }
}

function Confirm-ProfilesRootAclEpoch([bool]$WasCreated, [bool]$WasSecureBefore) {
    $epoch = "profiles-root-acl-v1"
    $operatingSystem = CimCmdlets\Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    $bootTime = ([DateTime]$operatingSystem.LastBootUpTime).ToUniversalTime().Ticks.ToString(
        [Globalization.CultureInfo]::InvariantCulture)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $pathBytes = [Text.Encoding]::UTF8.GetBytes($resolvedInstallRoot.ToUpperInvariant())
        try {
            $pathKey = ([BitConverter]::ToString($sha.ComputeHash($pathBytes))).Replace("-", "")
        } finally { [Array]::Clear($pathBytes, 0, $pathBytes.Length) }
    } finally { $sha.Dispose() }
    $registryView = if ([Environment]::Is64BitOperatingSystem) {
        [Microsoft.Win32.RegistryView]::Registry64
    } else { [Microsoft.Win32.RegistryView]::Registry32 }
    $machine = [Microsoft.Win32.RegistryKey]::OpenBaseKey(
        [Microsoft.Win32.RegistryHive]::LocalMachine, $registryView)
    $markerParent = $null
    $marker = $null
    try {
        $markerParent = Open-ProtectedMoonWakerRegistryParent $machine "ProfilesRootAclEpochs"
        $marker = $markerParent.CreateSubKey($pathKey)
        if ($null -eq $marker) { throw "Cannot create the protected ProfilesRoot ACL epoch marker." }
        $marker.SetAccessControl((New-ProfilesRootEpochRegistrySecurity))
        Assert-TrustedMoonWakerRegistryKey $marker
        $markerSecurity = $marker.GetAccessControl(
            [Security.AccessControl.AccessControlSections]::Access -bor
            [Security.AccessControl.AccessControlSections]::Owner)
        if (-not $markerSecurity.AreAccessRulesProtected) {
            throw "ProfilesRoot ACL epoch marker inheritance is enabled."
        }
        if ($markerSecurity.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
                @("S-1-5-18", "S-1-5-32-544")) {
            throw "ProfilesRoot ACL epoch marker has an untrusted owner."
        }
        $allowedMarkerSids = @("S-1-5-18", "S-1-5-32-544")
        foreach ($rule in $markerSecurity.GetAccessRules($true, $false,
                [Security.Principal.SecurityIdentifier])) {
            if ($rule.IsInherited -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.IdentityReference.Value -notin $allowedMarkerSids -or
                ($rule.RegistryRights -band [Security.AccessControl.RegistryRights]::FullControl) -ne
                    [Security.AccessControl.RegistryRights]::FullControl) {
                throw "ProfilesRoot ACL epoch marker is not restricted to SYSTEM and Administrators."
            }
        }
        $storedEpoch = [string]$marker.GetValue("Epoch", "")
        $storedPath = [string]$marker.GetValue("ProfilesRoot", "")
        $pendingBoot = [string]$marker.GetValue("PendingBoot", "")
        $state = [string]$marker.GetValue("State", "")
        if ($WasCreated) {
            $marker.SetValue("Epoch", $epoch)
            $marker.SetValue("ProfilesRoot", $resolvedInstallRoot)
            $marker.SetValue("State", "complete")
            $marker.SetValue("PendingBoot", "")
            return
        }
        if (-not $WasSecureBefore) {
            $marker.SetValue("Epoch", $epoch)
            $marker.SetValue("ProfilesRoot", $resolvedInstallRoot)
            $marker.SetValue("State", "pending_reboot")
            $marker.SetValue("PendingBoot", $bootTime)
            throw "ProfilesRoot had an insecure legacy ACL. Its DACL is now protected, but pre-opened handles " +
                "cannot be revoked online. Reboot Windows, then retry profile creation " +
                "(wymagany restart systemu przed ponowną próbą)."
        }
        if ($storedEpoch -eq $epoch -and $storedPath.Equals($resolvedInstallRoot,
                [StringComparison]::OrdinalIgnoreCase) -and $state -eq "complete") { return }
        if ($storedEpoch -ne $epoch -or
            -not $storedPath.Equals($resolvedInstallRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [string]::IsNullOrWhiteSpace($pendingBoot)) {
            $marker.SetValue("Epoch", $epoch)
            $marker.SetValue("ProfilesRoot", $resolvedInstallRoot)
            $marker.SetValue("State", "pending_reboot")
            $marker.SetValue("PendingBoot", $bootTime)
            throw "Existing ProfilesRoot needs one reboot before safe fresh provisioning; retry after restart."
        }
        if ($pendingBoot -eq $bootTime) {
            throw "ProfilesRoot protection is pending a Windows reboot; same-boot retry is refused."
        }
        $marker.SetValue("State", "complete")
        $marker.SetValue("PendingBoot", "")
    } finally {
        if ($null -ne $marker) { $marker.Dispose() }
        if ($null -ne $markerParent) { $markerParent.Dispose() }
        $machine.Dispose()
    }
}

function Write-ProfileRootProvenance {
    if (-not $MachineProvisioning) { return }
    Assert-MachineProfileRoot
    $registryView = if ([Environment]::Is64BitOperatingSystem) {
        [Microsoft.Win32.RegistryView]::Registry64
    } else { [Microsoft.Win32.RegistryView]::Registry32 }
    $machine = [Microsoft.Win32.RegistryKey]::OpenBaseKey(
        [Microsoft.Win32.RegistryHive]::LocalMachine, $registryView)
    $parent = $null
    $marker = $null
    try {
        $parent = Open-ProtectedMoonWakerRegistryParent $machine "ProfileRootProvenance"
        if ($null -eq $parent) { throw "Cannot create the protected profile-root provenance registry." }
        Assert-TrustedMoonWakerRegistryKey $parent
        $existing = $parent.OpenSubKey($ProfileId, $false)
        if ($null -ne $existing) {
            $existing.Dispose()
            throw "A profile-root provenance marker already exists for this profile ID."
        }
        $marker = $parent.CreateSubKey($ProfileId)
        if ($null -eq $marker) { throw "Cannot create the profile-root provenance marker." }
        $marker.SetAccessControl((New-ProfilesRootEpochRegistrySecurity))
        Assert-TrustedMoonWakerRegistryKey $marker
        $markerSecurity = $marker.GetAccessControl(
            [Security.AccessControl.AccessControlSections]::Access -bor
            [Security.AccessControl.AccessControlSections]::Owner)
        if (-not $markerSecurity.AreAccessRulesProtected -or
            $markerSecurity.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
                @("S-1-5-18", "S-1-5-32-544")) {
            throw "Profile-root provenance marker protection failed."
        }
        $marker.SetValue("Version", 1, [Microsoft.Win32.RegistryValueKind]::DWord)
        $marker.SetValue("ProfileId", $ProfileId)
        $marker.SetValue("ProfileRoot", $finalProfileRoot)
        $marker.SetValue("OwnerSid", $provisioningOwner.sid)
        $marker.Flush()
    } finally {
        if ($null -ne $marker) { $marker.Dispose() }
        if ($null -ne $parent) { $parent.Dispose() }
        $machine.Dispose()
    }
}

function Protect-ProfilesRoot {
    if (Test-Path -LiteralPath $resolvedInstallRoot -PathType Leaf) {
        throw "ProfilesRoot is a file; profile provisioning stopped."
    }
    New-Item -ItemType Directory -Path $resolvedInstallRoot -Force | Out-Null
    return
    $root = [IO.DirectoryInfo]::new($resolvedInstallRoot)
    $wasCreated = -not $root.Exists
    $wasSecureBefore = $false
    if ($root.Exists) {
        try {
            Assert-ProfilesRootAccessControl
            $wasSecureBefore = $true
        } catch { $wasSecureBefore = $false }
    }
    $security = New-ProfilesRootAccessControl
    if ($root.Exists) { [IO.Directory]::SetAccessControl($resolvedInstallRoot, $security) }
    else { $root.Create($security) }
    Assert-MachineProfileRoot
    Assert-ProfilesRootAccessControl
    Confirm-ProfilesRootAclEpoch $wasCreated $wasSecureBefore
}

function Initialize-MachineProfileRoot {
    Protect-ProfilesRoot
    Assert-MachineProfileRoot
    if (Test-Path -LiteralPath $profileRoot) {
        throw "The reserved profile root already exists. Remove the failed profile explicitly and retry."
    }
    New-Item -ItemType Directory -Path $profileRoot -Force | Out-Null
    return
    $staging = Join-Path $resolvedInstallRoot (".moonwaker-new-" + [guid]::NewGuid().ToString("N"))
    $stagingInfo = [IO.DirectoryInfo]::new($staging)
    try {
        $stagingInfo.Create((New-ProfileAccessControl $true $false))
        if (Test-Path -LiteralPath $profileRoot) {
            throw "The reserved profile root appeared during provisioning; retry after cleanup."
        }
        # Rename within the protected parent is the exclusive publication step.
        [IO.Directory]::Move($staging, $profileRoot)
    } finally {
        if (Test-Path -LiteralPath $staging) {
            $item = Get-Item -LiteralPath $staging -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Protected staging unexpectedly became a reparse point."
            }
            Remove-Item -LiteralPath $staging -Recurse -Force
        }
    }
    Assert-MachineProfileRoot
    if ([IO.Directory]::GetAccessControl($profileRoot).
            GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
            @("S-1-5-18", "S-1-5-32-544")) {
        throw "The new protected profile root has an untrusted owner."
    }
}

function Protect-ProfileTree([bool]$IncludeOwner) {
    if (-not $MachineProvisioning) { return }
    if (-not $IncludeOwner) { throw "The machine profile root is initialized only once." }
    $expectedWorkingRoot = if ([string]::IsNullOrWhiteSpace($machineStagingRoot)) {
        $finalProfileRoot
    } else { $machineStagingRoot }
    if (-not [IO.Path]::GetFullPath($profileRoot).TrimEnd('\').Equals(
            [IO.Path]::GetFullPath($expectedWorkingRoot).TrimEnd('\'),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "The protected machine profile working root changed unexpectedly."
    }
    $cursor = [IO.DirectoryInfo]::new($profileRoot)
    while ($null -ne $cursor) {
        if ($cursor.Exists -and ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "A reparse point exists in the machine profile working path."
        }
        $cursor = $cursor.Parent
    }
    $root = [IO.DirectoryInfo]::new($profileRoot)
    if (-not $root.Exists) { throw "The new profile root disappeared during provisioning." }
    $rootSecurity = New-ProfileAccessControl $true $IncludeOwner
    $pending = [Collections.Generic.Stack[IO.DirectoryInfo]]::new()
    $pending.Push([IO.DirectoryInfo]::new($profileRoot))
    while ($pending.Count -gt 0) {
        $directory = $pending.Pop()
        foreach ($item in $directory.EnumerateFileSystemInfos()) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "A reparse point exists inside the profile root; repair it before provisioning."
            }
            if ($item -is [IO.DirectoryInfo]) {
                [IO.Directory]::SetAccessControl($item.FullName,
                    (New-ProfileAccessControl $true $IncludeOwner))
                if ([IO.Directory]::GetAccessControl($item.FullName).
                        GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
                        @("S-1-5-18", "S-1-5-32-544")) {
                    throw "A profile directory has an untrusted ACL owner."
                }
                $pending.Push([IO.DirectoryInfo]$item)
            } else {
                [IO.File]::SetAccessControl($item.FullName,
                    (New-ProfileAccessControl $false $IncludeOwner))
                if ([IO.File]::GetAccessControl($item.FullName).
                        GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
                        @("S-1-5-18", "S-1-5-32-544")) {
                    throw "A profile file has an untrusted ACL owner."
                }
            }
        }
    }
    # The protected root remains inaccessible to the target account while all
    # descendants receive their final DACL. Publishing the root ACL last avoids
    # a partial tree that the target could change during the elevated walk.
    [IO.Directory]::SetAccessControl($profileRoot, $rootSecurity)
    if ([IO.Directory]::GetAccessControl($profileRoot).
            GetOwner([Security.Principal.SecurityIdentifier]).Value -notin
            @("S-1-5-18", "S-1-5-32-544")) {
        throw "The profile root has an untrusted ACL owner."
    }
}

function Publish-MachineProfileRoot {
    if (-not $MachineProvisioning) { return }
    Assert-MachineProfileRoot
    if (-not (Test-Path -LiteralPath $finalProfileRoot -PathType Container)) {
        throw "The protected profile root disappeared before publication."
    }
    $script:machineStagingParent = Join-Path $resolvedInstallRoot (
        ".moonwaker-stage-" + $ProfileId)
    if (Test-Path -LiteralPath $machineStagingParent) {
        throw "A protected staging tree from a failed add already exists. Remove the failed profile and retry."
    }
    [IO.DirectoryInfo]::new($machineStagingParent).Create(
        (New-ProfileAccessControl $true $false))
    $script:machineStagingRoot = Join-Path $machineStagingParent ([guid]::NewGuid().ToString("N"))
    # Hide the complete Admin/SYSTEM-only tree below a non-listable parent.
    # Its random inner name remains undiscoverable while descendant owner ACLs
    # are prepared, then one same-volume rename publishes the ready tree.
    [IO.Directory]::Move($finalProfileRoot, $machineStagingRoot)
    $script:profileRoot = $machineStagingRoot
    Protect-ProfileTree $true
    if (Test-Path -LiteralPath $finalProfileRoot) {
        throw "The reserved profile root appeared during final publication."
    }
    [IO.Directory]::Move($machineStagingRoot, $finalProfileRoot)
    $script:profileRoot = $finalProfileRoot
    $script:machineStagingRoot = ""
    [IO.Directory]::Delete($machineStagingParent, $false)
    $script:machineStagingParent = ""
    Assert-MachineProfileRoot
}

if ($MachineProvisioning) {
    Initialize-MachineProfileRoot
} else {
    New-Item -ItemType Directory -Path $profileRoot -Force | Out-Null
}

# A profile holds only data, configuration and secrets; Bridge code runs from the
# installation. Create the data directory and seed its configuration once.
function Initialize-ProfileData {
    param([string]$SourceName, [string]$DestinationName, [string]$ExampleName, [string]$ConfigName)
    $destination = Join-Path $profileRoot $DestinationName
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $config = Join-Path $destination $ConfigName
    if (-not (Test-Path -LiteralPath $config)) {
        Copy-Item -LiteralPath (Join-Path (Join-Path $sourceRoot $SourceName) $ExampleName) `
            -Destination $config
    }
    return $destination
}

function Set-ConfigPort {
    param([string]$Path, [string]$Property, [int]$Port)
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($null -eq $config.PSObject.Properties[$Property]) {
        $config | Add-Member -NotePropertyName $Property -NotePropertyValue $Port
    } else {
        $config.$Property = $Port
    }
    $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Set-ConfigValue {
    param([string]$Path, [string]$Property, [object]$Value)
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($null -eq $config.PSObject.Properties[$Property]) {
        $config | Add-Member -NotePropertyName $Property -NotePropertyValue $Value
    } else {
        $config.$Property = $Value
    }
    $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Initialize-DiscordConfig {
    param([string]$Directory, [string]$ConfigPath, [int]$Port)
    if (-not $NonInteractiveConfiguration) {
        & (Join-Path $sourceRoot "discord\Configure-DiscordBridge.ps1") -ProfileDirectory $Directory
        return
    }
    $clientId = [string]$env:MOONWAKER_DISCORD_CLIENT_ID
    $clientSecret = [string]$env:MOONWAKER_DISCORD_CLIENT_SECRET
    if ($clientId -notmatch '^[0-9]{17,20}$' -or [string]::IsNullOrWhiteSpace($clientSecret)) {
        throw "Discord configuration is missing. Provide it in MoonWaker Host Control."
    }
    $previousClientId = ""
    if (Test-Path -LiteralPath $ConfigPath) {
        try { $previousClientId = [string](Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json).client_id } catch {}
    }
    [ordered]@{
        client_id = $clientId
        port = $Port
        redirect_uri = ""
        scopes = @("rpc", "identify", "guilds", "rpc.voice.read", "rpc.voice.write")
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
    ConvertTo-SecureString $clientSecret -AsPlainText -Force |
        ConvertFrom-SecureString |
        Set-Content -LiteralPath (Join-Path $Directory "client_secret.dpapi") -Encoding ASCII
    if ($previousClientId -and $previousClientId -ne $clientId) {
        Remove-Item -LiteralPath (Join-Path $Directory "oauth_token.dpapi") `
            -Force -ErrorAction SilentlyContinue
    }
}

if ([string]::IsNullOrWhiteSpace($GatewayDirectory)) { $GatewayDirectory = Split-Path -Parent $GatewayConfigPath }
$gatewayStart = Join-Path $GatewayDirectory "Start-MoonWakerGateway.ps1"

$discordDirectory = $null
if (-not $SkipDiscord) {
    $discordDirectory = Join-Path $profileRoot "discord"
    $discordConfig = Join-Path $discordDirectory "discord_bridge_config.json"
    $discordConfigured = Test-Path -LiteralPath $discordConfig
    [void](Initialize-ProfileData "discord" "discord" "discord_bridge_config.example.json" `
        "discord_bridge_config.json")
    if (-not $MachineProvisioning -and (-not $discordConfigured -or
        ($NonInteractiveConfiguration -and -not [string]::IsNullOrWhiteSpace($env:MOONWAKER_DISCORD_CLIENT_ID)))) {
        Initialize-DiscordConfig $discordDirectory $discordConfig $DiscordPort
    }
    Set-ConfigPort $discordConfig "port" $DiscordPort
}

$gameProviderDirectory = Initialize-ProfileData "playnite" "game-provider" `
    "config.example.json" "config.json"
$gameProviderConfig = Join-Path $gameProviderDirectory "config.json"
Set-ConfigPort $gameProviderConfig "listen_port" $providerPort
Set-ConfigValue $gameProviderConfig "epic_legendary_enabled" $true
Set-ConfigValue $gameProviderConfig "playnite_enabled" (-not $SkipPlaynite)
if ($null -eq (Get-Content -LiteralPath $gameProviderConfig -Raw |
        ConvertFrom-Json).PSObject.Properties["legendary_path"]) {
    Set-ConfigValue $gameProviderConfig "legendary_path" ""
}

if (-not $SkipGatewayRegistration) {
    $registryLock = $null
    try {
        $registryLock = Enter-GatewayRegistryLock
        $gateway = Get-Content -LiteralPath $GatewayConfigPath -Raw | ConvertFrom-Json
        if ($null -eq $gateway.PSObject.Properties["profiles"]) {
            $gateway | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{})
        }
        $isNewProfile = $null -eq $gateway.profiles.PSObject.Properties[$ProfileId]
        $entry = if ($isNewProfile) { [pscustomobject]@{} } else { $gateway.profiles.$ProfileId }
        $accountSid = if ($null -ne $entry.PSObject.Properties["windows_account_sid"] -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.windows_account_sid)) {
            [string]$entry.windows_account_sid
        } elseif ($null -ne $entry.PSObject.Properties["owner_sid"] -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.owner_sid)) {
            [string]$entry.owner_sid
        } elseif ($isNewProfile) {
            $provisioningOwner.sid
        } else { "" }
        $accountName = if (-not $isNewProfile -and
            $null -ne $entry.PSObject.Properties["windows_account_name"] -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.windows_account_name)) {
            [string]$entry.windows_account_name
        } elseif (-not $isNewProfile -and $null -ne $entry.PSObject.Properties["owner"] -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.owner)) {
            [string]$entry.owner
        } else { $provisioningOwner.name }
        $entryDisplayName = if ($null -ne $entry.PSObject.Properties["display_name"]) {
            [string]$entry.display_name
        } elseif ($null -ne $entry.PSObject.Properties["name"]) {
            [string]$entry.name
        } else { $profileDisplayName }
        $entryProfileRoot = if ($null -ne $entry.PSObject.Properties["profile_root"]) {
            [string]$entry.profile_root
        } else { $profileRoot }
        $entryProviderBridge = if ($null -ne $entry.PSObject.Properties["game_provider_bridge"]) {
            [string]$entry.game_provider_bridge
        } elseif ($null -ne $entry.PSObject.Properties["playnite_bridge"]) {
            [string]$entry.playnite_bridge
        } else { "http://127.0.0.1:$providerPort" }
        $entryDiscordBridge = if ($null -ne $entry.PSObject.Properties["discord_bridge"]) {
            [string]$entry.discord_bridge
        } else { $(if ($SkipDiscord) { "" } else { "http://127.0.0.1:$DiscordPort" }) }
        $defaults = [ordered]@{
            id = $ProfileId
            name = $entryDisplayName
            display_name = $entryDisplayName
            owner = $accountName
            windows_account_name = $accountName
            owner_sid = $accountSid
            windows_account_sid = $accountSid
            enabled = $true
            remote_sign_in_enabled = $false
            account_mapping_status = if ([string]::IsNullOrWhiteSpace($accountSid)) {
                "action_required"
            } else { "resolved" }
            profile_root = $entryProfileRoot
            discord_bridge = $entryDiscordBridge
            game_provider_bridge = $entryProviderBridge
            playnite_bridge = $entryProviderBridge
        }
        foreach ($property in $defaults.GetEnumerator()) {
            if ($null -eq $entry.PSObject.Properties[$property.Key]) {
                $entry | Add-Member -NotePropertyName $property.Key -NotePropertyValue $property.Value
            }
        }
        if ($isNewProfile) {
            $gateway.profiles | Add-Member -NotePropertyName $ProfileId -NotePropertyValue $entry
        }
        Set-GatewayRegistry $gateway
        Write-Host "Registered profile '$ProfileId' in the Gateway configuration." -ForegroundColor Green
        Write-Warning "Restart the Gateway to load the updated profile registry."
    } catch {
        if ($MachineProvisioning) { throw }
        $registrationPath = Join-Path $profileRoot "gateway-profile-registration.json"
        [ordered]@{
            profile_id = $ProfileId
            id = $ProfileId
            name = $profileDisplayName
            display_name = $profileDisplayName
            owner = $provisioningOwner.name
            windows_account_name = $provisioningOwner.name
            owner_sid = $provisioningOwner.sid
            windows_account_sid = $provisioningOwner.sid
            enabled = $true
            remote_sign_in_enabled = $false
            account_mapping_status = "resolved"
            profile_root = $profileRoot
            discord_bridge = if ($SkipDiscord) { "" } else { "http://127.0.0.1:$DiscordPort" }
            game_provider_bridge = "http://127.0.0.1:$providerPort"
            playnite_bridge = "http://127.0.0.1:$providerPort"
        } | ConvertTo-Json | Set-Content -LiteralPath $registrationPath -Encoding UTF8
        Write-Warning "Gateway configuration could not be updated: $($_.Exception.Message)"
        Write-Warning "Registration data was written to $registrationPath for an administrator."
    } finally {
        Exit-GatewayRegistryLock $registryLock
    }
}

if ($MachineProvisioning) { Protect-ProfileTree $true }

# The owner's Host Control tray (machine Run entry, --tray) starts the Agent at sign-in.
if (-not $SkipStart -and (Test-Path -LiteralPath $gatewayStart)) {
    & $gatewayStart -GatewayDirectory $GatewayDirectory
}

$env:MOONWAKER_DISCORD_CLIENT_SECRET = $null

Write-Host "Wake & Play integration profile '$ProfileId' installed for $($provisioningOwner.name)." -ForegroundColor Green
