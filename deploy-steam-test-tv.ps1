[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$Apk
)

$ErrorActionPreference = "Stop"
$sdk = "C:\Users\Basia\Documents\Codex\android-sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
if ($Serial -notmatch ":\d+$") { $Serial = "$Serial`:5555" }

& $adb connect $Serial | Write-Host
if ($LASTEXITCODE -ne 0) { throw "ADB connection failed: $Serial" }
& $adb -s $Serial shell input keyevent WAKEUP
& $adb -s $Serial install -r $resolvedApk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
& $adb -s $Serial shell am force-stop com.limelight.debug
& $adb -s $Serial shell am start -W -a android.intent.action.MAIN `
    -c android.intent.category.LEANBACK_LAUNCHER `
    -n com.limelight.debug/com.limelight.console.ConsoleActivity | Write-Host
if ($LASTEXITCODE -ne 0) { throw "MoonWaker Debug launch failed." }
& $adb -s $Serial shell dumpsys package com.limelight.debug |
    Select-String "versionName=|versionCode=" | Select-Object -First 2
