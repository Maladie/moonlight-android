#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$InstallDirectory,
    [switch]$SkipStart
)
$ErrorActionPreference = "Stop"
$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\')
$source = $PSScriptRoot
$target = Join-Path $InstallDirectory "updater"
$stateRoot = Join-Path $env:ProgramData "MoonWaker\HotUpdater"
$clientSidPath = Join-Path $stateRoot "client-sid.txt"
$readyMarker = Join-Path $stateRoot "installer-ready"
$service = Get-Service -Name "MoonWakerHotUpdater" -ErrorAction SilentlyContinue
$clientSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$localAccount = Get-CimInstance Win32_UserAccount -Filter "SID='$clientSid'" -ErrorAction Stop
if ($null -eq $localAccount -or -not [bool]$localAccount.LocalAccount) {
    throw "Hot Updater setup must be run by the local Windows account that will submit updates."
}

function Disable-UntrustedUpdater {
    if ($null -eq $service) { return }
    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name "MoonWakerHotUpdater" -Force -ErrorAction SilentlyContinue
    }
    Set-Service -Name "MoonWakerHotUpdater" -StartupType Disabled
}

function Assert-NoReparseTree([string]$Path) {
    $cursor = [IO.DirectoryInfo]::new([IO.Path]::GetFullPath($Path))
    while ($null -ne $cursor) {
        if ($cursor.Exists -and ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Updater bootstrap path contains a reparse point: $($cursor.FullName)"
        }
        $cursor = $cursor.Parent
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return }
    $pending = [Collections.Generic.Stack[IO.DirectoryInfo]]::new()
    $pending.Push([IO.DirectoryInfo]::new($Path))
    while ($pending.Count) {
        $directory = $pending.Pop()
        foreach ($entry in $directory.EnumerateFileSystemInfos()) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Updater bootstrap tree contains a reparse point: $($entry.FullName)"
            }
            if ($entry -is [IO.DirectoryInfo]) { $pending.Push($entry) }
        }
    }
}

function New-ProtectedSecurity([bool]$IsDirectory, [bool]$UsersMayRead) {
    $system = [Security.Principal.SecurityIdentifier]::new("S-1-5-18")
    $admins = [Security.Principal.SecurityIdentifier]::new("S-1-5-32-544")
    $users = [Security.Principal.SecurityIdentifier]::new("S-1-5-32-545")
    $security = if ($IsDirectory) { [Security.AccessControl.DirectorySecurity]::new() } `
        else { [Security.AccessControl.FileSecurity]::new() }
    $security.SetAccessRuleProtection($true, $false)
    $security.SetOwner($admins)
    $inheritance = if ($IsDirectory) {
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    } else { [Security.AccessControl.InheritanceFlags]::None }
    $propagation = [Security.AccessControl.PropagationFlags]::None
    $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $system, [Security.AccessControl.FileSystemRights]::FullControl,
        $inheritance, $propagation, [Security.AccessControl.AccessControlType]::Allow))
    $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $admins, [Security.AccessControl.FileSystemRights]::FullControl,
        $inheritance, $propagation, [Security.AccessControl.AccessControlType]::Allow))
    if ($UsersMayRead) {
        $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $users, [Security.AccessControl.FileSystemRights]::ReadAndExecute,
            $inheritance, $propagation, [Security.AccessControl.AccessControlType]::Allow))
    }
    return $security
}

function Set-ExactProtectedAcl([string]$Path, [bool]$UsersMayRead) {
    $isDirectory = Test-Path -LiteralPath $Path -PathType Container
    $security = New-ProtectedSecurity $isDirectory $UsersMayRead
    if ($isDirectory) { [IO.Directory]::SetAccessControl($Path, $security) }
    else { [IO.File]::SetAccessControl($Path, $security) }
}

function New-ExactProtectedDirectory([string]$Path, [bool]$UsersMayRead) {
    if (Test-Path -LiteralPath $Path) { throw "Protected directory already exists: $Path" }
    [IO.DirectoryInfo]::new($Path).Create((New-ProtectedSecurity $true $UsersMayRead))
    Assert-ExactProtectedAcl $Path $UsersMayRead
}

function Assert-ExactProtectedAcl([string]$Path, [bool]$UsersMayRead) {
    $security = if (Test-Path -LiteralPath $Path -PathType Container) {
        [IO.Directory]::GetAccessControl($Path) } else { [IO.File]::GetAccessControl($Path) }
    if (-not $security.AreAccessRulesProtected -or
        $security.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne "S-1-5-32-544") {
        throw "Updater path does not have a protected administrator-owned ACL: $Path"
    }
    $allowed = @("S-1-5-18", "S-1-5-32-544")
    if ($UsersMayRead) { $allowed += "S-1-5-32-545" }
    $rules = @($security.GetAccessRules($true, $true,
        [Security.Principal.SecurityIdentifier]))
    if (@($allowed | Where-Object { $_ -notin @($rules | ForEach-Object {
                $_.IdentityReference.Value }) }).Count -gt 0 -or @($rules | Where-Object {
            $_.IdentityReference.Value -notin $allowed -or $_.AccessControlType -ne "Allow"
        }).Count -gt 0) { throw "Updater path contains a foreign or deny ACE: $Path" }
    foreach ($rule in $rules) {
        if ($rule.IdentityReference.Value -in @("S-1-5-18", "S-1-5-32-544") -and
            ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne
                [Security.AccessControl.FileSystemRights]::FullControl) {
            throw "Updater administrator ACL is incomplete: $Path"
        }
        if ($rule.IdentityReference.Value -eq "S-1-5-32-545" -and
            ($rule.FileSystemRights -band ([Security.AccessControl.FileSystemRights]::Write -bor
                [Security.AccessControl.FileSystemRights]::Delete -bor
                [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
                [Security.AccessControl.FileSystemRights]::TakeOwnership))) {
            throw "Updater users ACL grants mutation rights: $Path"
        }
    }
}

if ($service -and $service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
    Stop-Service -Name "MoonWakerHotUpdater" -Force
    $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped,
        [TimeSpan]::FromSeconds(10))
}
$deadline = [DateTime]::UtcNow.AddSeconds(20)
do {
    $remaining = @(Get-CimInstance Win32_Process -Property Name,CommandLine -ErrorAction Stop |
        Where-Object { $_.Name -eq "MoonWakerHotUpdaterService.exe" -or
            [string]$_.CommandLine -like "*Apply-MoonWakerHotUpdate.ps1*$InstallDirectory*" })
    if ($remaining.Count -eq 0) { break }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if ($remaining.Count) { throw "Hot Updater did not quiesce; bootstrap files were not changed." }
Disable-UntrustedUpdater

Assert-NoReparseTree $source
Assert-NoReparseTree $target
Assert-NoReparseTree $stateRoot
$oldTarget = if (Test-Path -LiteralPath $target) {
    Join-Path (Split-Path -Parent $target) (".moonwaker-updater-old-" + [guid]::NewGuid().ToString("N"))
} else { "" }
$oldState = if (Test-Path -LiteralPath $stateRoot) {
    Join-Path (Split-Path -Parent $stateRoot) (".moonwaker-updater-state-old-" + [guid]::NewGuid().ToString("N"))
} else { "" }
$buildRoot = Join-Path $source (".compiled-" + [guid]::NewGuid().ToString("N"))
$bootstrapReady = $false
$targetQuarantined = $false
$stateQuarantined = $false
try {
    if ($oldTarget) { [IO.Directory]::Move($target, $oldTarget); $targetQuarantined = $true }
    if ($oldState) { [IO.Directory]::Move($stateRoot, $oldState); $stateQuarantined = $true }
    & (Join-Path $source "Build-MoonWakerHotUpdater.ps1") -OutputDirectory $buildRoot
    New-ExactProtectedDirectory $target $true
    foreach ($name in @("MoonWakerHotUpdaterService.cs", "Build-MoonWakerHotUpdater.ps1",
            "Apply-MoonWakerHotUpdate.ps1", "Invoke-MoonWakerHotUpdate.ps1",
            "Uninstall-MoonWakerHotUpdater.ps1")) {
        Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $target $name)
    }
    Copy-Item -LiteralPath (Join-Path $buildRoot "MoonWakerHotUpdaterService.exe") `
        -Destination (Join-Path $target "MoonWakerHotUpdaterService.exe")
    foreach ($entry in @(Get-ChildItem -LiteralPath $target -Force -Recurse) + @(Get-Item $target)) {
        Set-ExactProtectedAcl $entry.FullName $true
        Assert-ExactProtectedAcl $entry.FullName $true
    }
    $stateParent = Split-Path -Parent $stateRoot
    if (-not (Test-Path -LiteralPath $stateParent)) {
        New-ExactProtectedDirectory $stateParent $false
    } else { Assert-NoReparseTree $stateParent }
    New-ExactProtectedDirectory $stateRoot $false
    [IO.File]::WriteAllText($clientSidPath, $clientSid, [Text.UTF8Encoding]::new($false))
    Set-ExactProtectedAcl $clientSidPath $false
    Assert-ExactProtectedAcl $stateRoot $false
    Assert-ExactProtectedAcl $clientSidPath $false
    $bootstrapReady = $true
} finally {
    if (Test-Path -LiteralPath $buildRoot) { Remove-Item -LiteralPath $buildRoot -Recurse -Force }
    if ($bootstrapReady) {
        if ($oldTarget -and (Test-Path -LiteralPath $oldTarget)) { [IO.Directory]::Delete($oldTarget, $true) }
        if ($oldState -and (Test-Path -LiteralPath $oldState)) { [IO.Directory]::Delete($oldState, $true) }
    } else {
        if (($targetQuarantined -or -not $oldTarget) -and (Test-Path -LiteralPath $target)) {
            [IO.Directory]::Delete($target, $true)
        }
        if (($stateQuarantined -or -not $oldState) -and (Test-Path -LiteralPath $stateRoot)) {
            [IO.Directory]::Delete($stateRoot, $true)
        }
        if ($targetQuarantined -and (Test-Path -LiteralPath $oldTarget)) {
            [IO.Directory]::Move($oldTarget, $target)
        }
        if ($stateQuarantined -and (Test-Path -LiteralPath $oldState)) {
            [IO.Directory]::Move($oldState, $stateRoot)
        }
    }
}
$binary = Join-Path $target "MoonWakerHotUpdaterService.exe"
$image = '"{0}" --host-root "{1}" --client-sid "{2}"' -f $binary, $InstallDirectory, $clientSid
if ($null -eq $service) {
    New-Service -Name "MoonWakerHotUpdater" -BinaryPathName $image `
        -DisplayName "MoonWaker Hot Updater" `
        -Description "Applies local, allowlisted MoonWaker Gateway and Bridge updates." `
        -StartupType Automatic | Out-Null
} else {
    $config = Get-CimInstance Win32_Service -Filter "Name='MoonWakerHotUpdater'"
    $changed = Invoke-CimMethod -InputObject $config -MethodName Change -Arguments @{
        PathName = $image; StartMode = "Automatic"; StartName = "LocalSystem"
    }
    if ($changed.ReturnValue -ne 0) { throw "Unable to update Hot Updater service configuration." }
}
$sc = Join-Path ([Environment]::SystemDirectory) "sc.exe"
& $sc description MoonWakerHotUpdater `
    "Applies local, allowlisted MoonWaker Gateway and Bridge updates." | Out-Null
[IO.File]::WriteAllText($readyMarker, $clientSid, [Text.UTF8Encoding]::new($false))
Set-ExactProtectedAcl $readyMarker $false
Assert-ExactProtectedAcl $readyMarker $false
if (-not $SkipStart) {
    Start-Service -Name "MoonWakerHotUpdater"
    (Get-Service -Name "MoonWakerHotUpdater").WaitForStatus(
        [ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(10))
}
