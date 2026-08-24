[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$Apk = ""
)

$ErrorActionPreference = "Stop"
$jdk = "C:\Users\Basia\.jdks\openjdk-17.0.2"
$java = Join-Path $jdk "bin\java.exe"
$gradle = Join-Path $PSScriptRoot "gradlew.bat"
$sdk = "C:\Users\Basia\Documents\Codex\android-sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $java)) { throw "JDK 17 was not found: $jdk" }
$env:JAVA_HOME = $jdk
& $gradle assembleNonRootDebug
if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed." }
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Join-Path $PSScriptRoot `
        "app\build\outputs\apk\nonRoot\debug\app-nonRoot-debug.apk"
}
$resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
if ($Serial -notmatch ":\d+$") { $Serial = "$Serial`:5555" }

& $adb connect $Serial | Write-Host
if ($LASTEXITCODE -ne 0) { throw "ADB connection failed: $Serial" }
& $adb -s $Serial shell input keyevent WAKEUP
& $adb -s $Serial install -r -d $resolvedApk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
& $adb -s $Serial shell am force-stop com.limelight.debug
& $adb -s $Serial shell am start -a android.intent.action.MAIN `
    -c android.intent.category.LEANBACK_LAUNCHER `
    -n com.limelight.debug/com.limelight.console.ConsoleActivity | Write-Host
if ($LASTEXITCODE -ne 0) { throw "MoonWaker Debug launch failed." }
& $adb -s $Serial shell dumpsys package com.limelight.debug |
    Select-String "versionName=|versionCode=" | Select-Object -First 2
