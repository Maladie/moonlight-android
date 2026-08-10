[CmdletBinding()]
param(
    [string] $Serial,
    [ValidateSet('Debug', 'Release')]
    [string] $Configuration = 'Debug',
    [switch] $LocalSign,
    [string] $SigningKeyPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$localProperties = Join-Path $repoRoot 'local.properties'
$jdk17 = Join-Path $env:USERPROFILE '.jdks\openjdk-17.0.2'

if (Test-Path -LiteralPath (Join-Path $jdk17 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk17
}

if (-not (Test-Path -LiteralPath $localProperties)) {
    throw 'local.properties is missing. Open the project in IntelliJ or set sdk.dir manually.'
}

$sdkEntry = Get-Content -LiteralPath $localProperties |
    Where-Object { $_ -match '^sdk\.dir=' } |
    Select-Object -First 1
if (-not $sdkEntry) {
    throw 'local.properties does not contain sdk.dir.'
}

$sdkRoot = ($sdkEntry -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\', '\'
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB was not found: $adb"
}

if ($Configuration -eq 'Release' -and -not $LocalSign) {
    throw 'Release deployment requires -LocalSign because unsigned APKs cannot be installed.'
}

if ($LocalSign -and $Configuration -ne 'Release') {
    throw '-LocalSign is supported only with -Configuration Release.'
}

if ($LocalSign) {
    $debugKeystore = if ($SigningKeyPath) {
        $SigningKeyPath
    } else {
        Join-Path $env:USERPROFILE '.android\debug.keystore'
    }
    if (-not (Test-Path -LiteralPath $debugKeystore)) {
        throw "The local Android signing key was not found: $debugKeystore"
    }
    if ((Split-Path -Leaf $debugKeystore) -ne 'debug.keystore') {
        throw 'The local signing key must be named debug.keystore.'
    }
    $env:ANDROID_USER_HOME = Split-Path -Parent (Resolve-Path -LiteralPath $debugKeystore)
}

function Get-ConnectedDevices {
    $lines = & $adb devices | Select-Object -Skip 1 |
        Where-Object { $_ -match '^\S+\s+device$' }
    return @($lines | ForEach-Object { ($_ -split '\s+')[0] })
}

$devices = @(Get-ConnectedDevices)
if ($Serial -and $Serial -match '^\S+:\d+$' -and $devices -notcontains $Serial) {
    $connectResult = & $adb connect $Serial
    if ($LASTEXITCODE -ne 0) {
        throw "ADB could not connect to $Serial. $connectResult"
    }
    Write-Host $connectResult
    $devices = @(Get-ConnectedDevices)
}
if ($devices.Count -eq 0) {
    $discovered = @(& $adb mdns services | ForEach-Object {
        if ($_ -match '_adb(?:-tls-connect)?\._tcp\s+(\S+:\d+)\s*$') {
            $Matches[1]
        }
    } | Select-Object -Unique)

    $connectTarget = $null
    if ($Serial -and $Serial -match '^\S+:\d+$') {
        $connectTarget = $Serial
    } elseif ($discovered.Count -eq 1) {
        $connectTarget = $discovered[0]
    } elseif ($discovered.Count -gt 1) {
        throw "Multiple ADB services were discovered. Run again with -Serial <address:port>. Available: $($discovered -join ', ')"
    }

    if ($connectTarget) {
        $connectResult = & $adb connect $connectTarget
        if ($LASTEXITCODE -ne 0) {
            throw "ADB could not connect to $connectTarget. $connectResult"
        }
        Write-Host $connectResult
        $devices = @(Get-ConnectedDevices)
    }
}

if ($Serial) {
    if ($devices -notcontains $Serial) {
        throw "Device $Serial is unavailable. Check: adb devices"
    }
} elseif ($devices.Count -eq 1) {
    $Serial = $devices[0]
} elseif ($devices.Count -eq 0) {
    throw 'ADB cannot see the TV. Reconnect it and accept the debugging authorization prompt.'
} else {
    throw "Multiple devices detected. Run again with -Serial <serial>. Available: $($devices -join ', ')"
}

$env:ANDROID_SERIAL = $Serial
$gradleArguments = @('--no-daemon', '--console=plain')
if ($LocalSign) {
    $gradleArguments += '-PlocalReleaseSigning=true'
}
$gradleArguments += "installNonRoot$Configuration"

& (Join-Path $repoRoot 'gradlew.bat') @gradleArguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$packageName = if ($Configuration -eq 'Release') { 'com.limelight.unofficial' } else { 'com.limelight.debug' }
$component = "$packageName/com.limelight.console.ConsoleActivity"
& $adb -s $Serial shell am start -W -a android.intent.action.MAIN `
    -c android.intent.category.LEANBACK_LAUNCHER -n $component | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Moonlight TV activity could not be launched: $component"
}
Write-Host "Moonlight $Configuration was installed and launched on $Serial."
