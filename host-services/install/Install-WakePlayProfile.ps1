#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]{1,64}$')]
    [string]$ProfileId = "default",
    [string]$ProfileName = "",
    [int]$DiscordPort = 8765,
    [int]$VibepolloPort = 8775,
    [int]$GameProviderPort = 0,
    [int]$PlaynitePort = 0,
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA "WakePlayHost\profiles"),
    [string]$GatewayConfigPath = "",
    [string]$GatewayDirectory = "",
    [string]$HostControlExecutable = "",
    [ValidatePattern('^S-[0-9]+(?:-[0-9]+){2,15}$')][string]$OwnerSid = "",
    [string]$OwnerName = "",
    [switch]$MachineProvisioning,
    [switch]$SkipDiscord,
    [switch]$SkipVibepollo,
    [switch]$SkipPlaynite,
    [switch]$SkipGatewayRegistration,
    [switch]$NonInteractiveConfiguration,
    [switch]$SkipStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$trustedModuleRoot = [IO.Path]::GetFullPath((Join-Path $PSHOME "Modules"))
$env:PSModulePath = $trustedModuleRoot
foreach ($module in @("CimCmdlets", "ScheduledTasks")) {
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
    # An elevated provisioning process must never execute a target-owned Bridge.
    $SkipStart = $true
    if (-not $SkipGatewayRegistration) {
        throw "Machine provisioning may not read or write the mutable Gateway registry as administrator."
    }
}

function Get-TrustedWindowsPowerShell {
    $systemDirectory = [Environment]::SystemDirectory
    if ([Environment]::Is64BitOperatingSystem -and -not [Environment]::Is64BitProcess) {
        $windowsDirectory = Split-Path -Parent $systemDirectory
        $systemDirectory = Join-Path $windowsDirectory "Sysnative"
    }
    $executable = [IO.Path]::GetFullPath((Join-Path $systemDirectory `
        "WindowsPowerShell\v1.0\powershell.exe"))
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "Trusted Windows PowerShell was not found."
    }
    return $executable
}
$windowsPowerShellPath = Get-TrustedWindowsPowerShell

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
        if ($null -ne $registeredProfile.PSObject.Properties["vibepollo_bridge"]) {
            if ([string]::IsNullOrWhiteSpace([string]$registeredProfile.vibepollo_bridge)) {
                $SkipVibepollo = $true
            } else { $VibepolloPort = Get-RegisteredEndpointPort $registeredProfile "vibepollo_bridge" }
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
    $VibepolloPort -lt 1024 -or $VibepolloPort -gt 65535 -or
    $providerPort -lt 1024 -or $providerPort -gt 65535) {
    throw "Bridge ports must be between 1024 and 65535."
}
$activePorts = @()
if (-not $SkipDiscord) { $activePorts += $DiscordPort }
if (-not $SkipVibepollo) { $activePorts += $VibepolloPort }
$activePorts += $providerPort
$uniqueActivePorts = @($activePorts | Select-Object -Unique)
if ($uniqueActivePorts.Count -ne $activePorts.Count) {
    throw "Discord, Vibepollo and Game Provider Bridges must use different ports."
}
if ($null -eq $registeredProfile -and $ProfileId -ne "default" -and (
    (-not $SkipDiscord -and -not $PSBoundParameters.ContainsKey("DiscordPort")) -or
    (-not $SkipVibepollo -and -not $PSBoundParameters.ContainsKey("VibepolloPort")) -or
    (-not $PSBoundParameters.ContainsKey("GameProviderPort") -and
        -not $PSBoundParameters.ContainsKey("PlaynitePort")))) {
    throw "Additional profiles require explicit, unique Discord, Vibepollo and Game Provider ports."
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

$hostVersionPath = Join-Path (Split-Path -Parent $InstallRoot) "version.json"
if ($MachineProvisioning) {
    Initialize-MachineProfileRoot
} else {
    New-Item -ItemType Directory -Path $profileRoot -Force | Out-Null
    $existingProfileStop = Join-Path $profileRoot "Stop-MoonWakerProfileBridge.ps1"
    if (Test-Path -LiteralPath $existingProfileStop -PathType Leaf) {
        & $existingProfileStop -ProfileRoot $profileRoot
    }
}
if (Test-Path -LiteralPath $hostVersionPath) {
    Copy-Item -LiteralPath $hostVersionPath -Destination (Join-Path $profileRoot "moonwaker-version.json") -Force
}
$agentSource = Join-Path $hostServicesRoot "profile-agent"
if (-not (Test-Path -LiteralPath $agentSource)) {
    $agentSource = Join-Path (Split-Path -Parent $PSScriptRoot) "profile-agent-source"
}
if (-not (Test-Path -LiteralPath $agentSource)) {
    throw "Profile Bridge supervisor package was not found next to the installer."
}

function Install-BridgeFiles {
    param([string]$SourceName, [string[]]$Files, [string]$DestinationName = "")
    if ([string]::IsNullOrWhiteSpace($DestinationName)) { $DestinationName = $SourceName }
    $source = Join-Path $sourceRoot $SourceName
    $destination = Join-Path $profileRoot $DestinationName
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    foreach ($file in $Files) {
        Copy-Item -LiteralPath (Join-Path $source $file) -Destination $destination -Force
    }
    return $destination
}

function Stop-InstalledBridge {
    param([string]$BridgeName, [string]$StopScriptName)
    if ($MachineProvisioning) { return }
    $directory = Join-Path $profileRoot $BridgeName
    $stopScript = Join-Path $directory $StopScriptName
    if (-not (Test-Path -LiteralPath $stopScript)) { return }
    try {
        & $stopScript
    } catch {
        Write-Warning "Unable to stop the existing $BridgeName Bridge cleanly: $($_.Exception.Message)"
    }
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

function Ensure-MoonWakerStreamTarget {
    param([int]$Port)
    $endpoint = "http://127.0.0.1:$Port/apps/stream/ensure"
    $lastError = $null
    $deadline = (Get-Date).AddSeconds(15)
    do {
        try {
            $result = Invoke-RestMethod -Uri $endpoint -Method Post `
                -ContentType "application/json" -Body "{}" -TimeoutSec 2
            if ($result.ok -and [string]$result.name -eq "MoonWaker Stream") {
                Write-Host "MoonWaker Stream target is ready." -ForegroundColor Green
                return
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Write-Warning "MoonWaker Stream target could not be ensured; legacy launch targets remain available. $lastError"
}

function Initialize-DiscordConfig {
    param([string]$Directory, [string]$ConfigPath, [int]$Port)
    if (-not $NonInteractiveConfiguration) {
        & (Join-Path $Directory "Configure-DiscordBridge.ps1")
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

function Initialize-VibepolloConfig {
    param([string]$Directory, [string]$ConfigPath, [int]$Port)
    if (-not $NonInteractiveConfiguration) {
        & (Join-Path $Directory "Configure-VibepolloBridge.ps1")
        return
    }
    $existing = $null
    if (Test-Path -LiteralPath $ConfigPath) {
        try { $existing = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json } catch {}
    }
    $baseUrl = [string]$env:MOONWAKER_VIBEPOLLO_URL
    $apiToken = [string]$env:MOONWAKER_VIBEPOLLO_TOKEN
    if ([string]::IsNullOrWhiteSpace($baseUrl) -and $null -ne $existing) {
        $baseUrl = [string]$existing.base_url
    }
    if ([string]::IsNullOrWhiteSpace($baseUrl)) { $baseUrl = "https://127.0.0.1:47990" }
    $uri = $null
    if (-not [uri]::TryCreate($baseUrl, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne "https" -or $uri.Host -notin @("127.0.0.1", "localhost") -or
        [string]::IsNullOrWhiteSpace($apiToken)) {
        throw "Vibepollo configuration is missing or invalid. Provide it in MoonWaker Host Control."
    }
    [ordered]@{
        base_url = $baseUrl.TrimEnd('/')
        listen_port = $Port
        python_path = if ($null -ne $existing -and
            $null -ne $existing.PSObject.Properties["python_path"]) {
            [string]$existing.python_path
        } else { "" }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
    ConvertTo-SecureString $apiToken -AsPlainText -Force |
        ConvertFrom-SecureString |
        Set-Content -LiteralPath (Join-Path $Directory "api_token.dpapi") -Encoding ASCII
}

function Remove-LegacyBridgeStartup {
    foreach ($name in @("Wake & Play Discord Bridge ($ProfileId)",
        "Wake & Play Vibepollo Bridge ($ProfileId)",
        "Wake & Play Game Provider Bridge ($ProfileId)",
        "Wake & Play Playnite Bridge ($ProfileId)")) {
        try { ScheduledTasks\Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue } catch {}
    }
    if (-not $MachineProvisioning) {
        $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
        try {
            $item = Get-ItemProperty -Path $runKey -ErrorAction SilentlyContinue
            foreach ($property in $item.PSObject.Properties) {
                if ($property.Name -like "MoonWaker*Bridge*$ProfileId*") {
                    Remove-ItemProperty -Path $runKey -Name $property.Name -ErrorAction SilentlyContinue
                }
            }
        } catch {}
        $startupShortcut = Join-Path $env:APPDATA (
            "Microsoft\Windows\Start Menu\Programs\Startup\MoonWaker Profile Bridge ($ProfileId).lnk")
        Remove-Item -LiteralPath $startupShortcut -Force -ErrorAction SilentlyContinue
    }
}

function Register-ProfileAgent {
    param([string]$StartScript)
    $Name = "MoonWaker Profile Bridge ($ProfileId)"
    $profileAgent = Join-Path (Split-Path -Parent $StartScript) "MoonWakerProfileBridge.ps1"
    $identity = $provisioningOwner.sid
    try {
        $action = ScheduledTasks\New-ScheduledTaskAction -Execute $windowsPowerShellPath -Argument (
            '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -ProfileRoot "{1}" -ProfileId "{2}"' -f `
                $profileAgent, (Split-Path -Parent $StartScript), $ProfileId)
        $trigger = ScheduledTasks\New-ScheduledTaskTrigger -AtLogOn -User $identity
        $settings = ScheduledTasks\New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
            -ExecutionTimeLimit (New-TimeSpan -Days 3650) -AllowStartIfOnBatteries `
            -DontStopIfGoingOnBatteries -StartWhenAvailable
        # ScheduledTasks calls the passwordless Task Scheduler logon type
        # "InteractiveToken" simply "Interactive".
        $principal = ScheduledTasks\New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
        ScheduledTasks\Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger -Settings $settings `
            -Principal $principal -Force | Out-Null
        Write-Host "Registered per-user Profile Bridge recovery task."
    } catch {
        if ($MachineProvisioning) { throw }
        Write-Warning "Could not register the Profile Bridge recovery task: $($_.Exception.Message)"
    }
    if ($MachineProvisioning) { return }
    # Keep a per-user Startup shortcut as a fallback for Windows editions where
    # task registration is restricted. The supervisor mutex makes this safe.
    $startup = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup"
    New-Item -ItemType Directory -Path $startup -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $startup "MoonWaker Profile Bridge ($ProfileId).lnk"))
    $shortcut.TargetPath = $windowsPowerShellPath
    $shortcut.Arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -ProfileRoot "{1}" -ProfileId "{2}"' -f `
        $profileAgent, (Split-Path -Parent $StartScript), $ProfileId
    $shortcut.WorkingDirectory = Split-Path -Parent $profileAgent
    $shortcut.Description = "MoonWaker Profile Bridge supervisor ($ProfileId)"
    $shortcut.Save()
    Write-Host "Registered per-user Profile Bridge startup shortcut."
}

function Remove-LegacyGatewayStartup {
    try {
        ScheduledTasks\Unregister-ScheduledTask -TaskName "MoonWaker Gateway Supervisor" `
            -Confirm:$false -ErrorAction SilentlyContinue
    } catch {}
    if ($MachineProvisioning) { return }
    $shortcut = Join-Path $env:APPDATA `
        "Microsoft\Windows\Start Menu\Programs\Startup\MoonWaker Gateway.lnk"
    Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
}

function Register-UserStartup {
    param([string]$Name, [string]$TargetPath, [string]$Arguments, [string]$Description)
    if ([string]::IsNullOrWhiteSpace($TargetPath)) { return }
    if (-not [IO.Path]::IsPathRooted($TargetPath)) {
        $command = Get-Command $TargetPath -ErrorAction SilentlyContinue
        if ($null -eq $command) { return }
        $TargetPath = $command.Source
    }
    if (-not (Test-Path -LiteralPath $TargetPath)) { return }
    $startup = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup"
    New-Item -ItemType Directory -Path $startup -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $startup "$Name.lnk"))
    $shortcut.TargetPath = $TargetPath
    $shortcut.Arguments = $Arguments
    $shortcut.WorkingDirectory = Split-Path -Parent $TargetPath
    $shortcut.Description = $Description
    $shortcut.Save()
}

Remove-LegacyBridgeStartup
Remove-LegacyGatewayStartup
foreach ($file in @("MoonWakerProfileBridge.ps1", "Start-MoonWakerProfileBridge.ps1",
    "Stop-MoonWakerProfileBridge.ps1")) {
    Copy-Item -LiteralPath (Join-Path $agentSource $file) -Destination (Join-Path $profileRoot $file) -Force
}
if ([string]::IsNullOrWhiteSpace($GatewayDirectory)) { $GatewayDirectory = Split-Path -Parent $GatewayConfigPath }
$gatewayStart = Join-Path $GatewayDirectory "Start-MoonWakerGateway.ps1"
if (-not $MachineProvisioning) {
    Register-UserStartup "MoonWaker Host Control" $HostControlExecutable "--tray" "MoonWaker Host Control"
}

$discordDirectory = $null
if (-not $SkipDiscord) {
    Stop-InstalledBridge "discord" "Stop-DiscordBridge.ps1"
    $discordDirectory = Install-BridgeFiles "discord" @(
        "DiscordBridge.ps1", "Configure-DiscordBridge.ps1",
        "discord_bridge_config.example.json", "Start-DiscordBridge.ps1",
        "Stop-DiscordBridge.ps1", "Test-DiscordBridge.ps1", "WindowsAudio.cs", "README.md")
    $discordConfig = Join-Path $discordDirectory "discord_bridge_config.json"
    if ($MachineProvisioning -and -not (Test-Path -LiteralPath $discordConfig)) {
        Copy-Item -LiteralPath (Join-Path $discordDirectory "discord_bridge_config.example.json") `
            -Destination $discordConfig
    } elseif (-not (Test-Path -LiteralPath $discordConfig) -or
        ($NonInteractiveConfiguration -and -not [string]::IsNullOrWhiteSpace($env:MOONWAKER_DISCORD_CLIENT_ID))) {
        Initialize-DiscordConfig $discordDirectory $discordConfig $DiscordPort
    }
    Set-ConfigPort $discordConfig "port" $DiscordPort
}

$vibepolloDirectory = $null
if (-not $SkipVibepollo) {
    Stop-InstalledBridge "vibepollo" "Stop-VibepolloBridge.ps1"
    $vibepolloDirectory = Install-BridgeFiles "vibepollo" @(
        "VibepolloBridge.ps1", "VibepolloTransport.py",
        "Configure-VibepolloBridge.ps1", "config.example.json",
        "moonwaker-token-scopes.example.json",
        "Start-VibepolloBridge.ps1", "Stop-VibepolloBridge.ps1",
        "Test-VibepolloBridge.ps1", "README.md")
    $vibepolloConfig = Join-Path $vibepolloDirectory "config.json"
    if ($MachineProvisioning -and -not (Test-Path -LiteralPath $vibepolloConfig)) {
        Copy-Item -LiteralPath (Join-Path $vibepolloDirectory "config.example.json") `
            -Destination $vibepolloConfig
    } elseif (-not (Test-Path -LiteralPath $vibepolloConfig) -or
        ($NonInteractiveConfiguration -and -not [string]::IsNullOrWhiteSpace($env:MOONWAKER_VIBEPOLLO_TOKEN))) {
        Initialize-VibepolloConfig $vibepolloDirectory $vibepolloConfig $VibepolloPort
    }
    Set-ConfigPort $vibepolloConfig "listen_port" $VibepolloPort
}

$legacyProviderDirectory = Join-Path $profileRoot "playnite"
$gameProviderDirectory = Join-Path $profileRoot "game-provider"
Stop-InstalledBridge "game-provider" "Stop-PlayniteBridge.ps1"
Stop-InstalledBridge "playnite" "Stop-PlayniteBridge.ps1"
if (-not (Test-Path -LiteralPath $gameProviderDirectory) -and
    (Test-Path -LiteralPath $legacyProviderDirectory)) {
    Move-Item -LiteralPath $legacyProviderDirectory -Destination $gameProviderDirectory
}
$gameProviderDirectory = Install-BridgeFiles "playnite" @(
    "GameProviderBridge.py", "GameOperations.py", "OperationJournal.py",
    "Confirm-SteamOperation.ps1", "Invoke-GameLauncher.ps1",
    "config.example.json", "Start-PlayniteBridge.ps1", "Stop-PlayniteBridge.ps1",
    "PatchPlayniteConnector.py", "Install-WakePlayConnectorPatch.ps1", "README.md") `
    "game-provider"
Remove-Item -LiteralPath (Join-Path $gameProviderDirectory "PlayniteBridge.py") `
    -Force -ErrorAction SilentlyContinue
$gameProviderConfig = Join-Path $gameProviderDirectory "config.json"
if (-not (Test-Path -LiteralPath $gameProviderConfig)) {
    Copy-Item -LiteralPath (Join-Path $gameProviderDirectory "config.example.json") `
        -Destination $gameProviderConfig
}
Set-ConfigPort $gameProviderConfig "listen_port" $providerPort
Set-ConfigValue $gameProviderConfig "vibepollo_bridge" `
    $(if ($SkipVibepollo) { "" } else { "http://127.0.0.1:$VibepolloPort" })
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
        $entryVibepolloBridge = if ($null -ne $entry.PSObject.Properties["vibepollo_bridge"]) {
            [string]$entry.vibepollo_bridge
        } else { $(if ($SkipVibepollo) { "" } else { "http://127.0.0.1:$VibepolloPort" }) }
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
            vibepollo_bridge = $entryVibepolloBridge
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
            vibepollo_bridge = if ($SkipVibepollo) { "" } else { "http://127.0.0.1:$VibepolloPort" }
            game_provider_bridge = "http://127.0.0.1:$providerPort"
            playnite_bridge = "http://127.0.0.1:$providerPort"
        } | ConvertTo-Json | Set-Content -LiteralPath $registrationPath -Encoding UTF8
        Write-Warning "Gateway configuration could not be updated: $($_.Exception.Message)"
        Write-Warning "Registration data was written to $registrationPath for an administrator."
    } finally {
        Exit-GatewayRegistryLock $registryLock
    }
}

if ($MachineProvisioning) {
    Register-ProfileAgent (Join-Path $finalProfileRoot "Start-MoonWakerProfileBridge.ps1")
    Protect-ProfileTree $true
} else {
    Register-ProfileAgent (Join-Path $finalProfileRoot "Start-MoonWakerProfileBridge.ps1")
}

if (-not $SkipStart) {
    if (Test-Path -LiteralPath $gatewayStart) {
        & $gatewayStart -GatewayDirectory $GatewayDirectory
    }
    & (Join-Path $profileRoot "Start-MoonWakerProfileBridge.ps1") -ProfileRoot $profileRoot -ProfileId $ProfileId
    if (-not $SkipVibepollo) { Ensure-MoonWakerStreamTarget $VibepolloPort }
}

# Never leak Host Control-provided secrets into Bridge child processes.
$env:MOONWAKER_DISCORD_CLIENT_SECRET = $null
$env:MOONWAKER_VIBEPOLLO_TOKEN = $null

Write-Host "Wake & Play integration profile '$ProfileId' installed for $($provisioningOwner.name)." -ForegroundColor Green
