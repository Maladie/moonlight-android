param(
    [Parameter(Mandatory)][long]$WindowHandle,
    [Parameter(Mandatory)][ValidateSet("install", "uninstall")][string]$Operation
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class MoonWakerSteamWindow {
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window, int command);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, IntPtr processId);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint from, uint to, bool attach);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out RECT rect);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr window, System.Text.StringBuilder value, int length);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr window, IntPtr deviceContext, uint flags);
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT point);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
"@

$processId = [uint32]0
[MoonWakerSteamWindow]::GetWindowThreadProcessId([IntPtr]$WindowHandle, [ref]$processId) | Out-Null
$process = Get-Process -Id $processId -ErrorAction Stop
$path = [IO.Path]::GetFullPath($process.Path)
$validSteam = $process.ProcessName -eq "steam" -or (
    $process.ProcessName -eq "steamwebhelper" -and
    $path -match '(?i)[\\/]Steam[\\/]bin[\\/]cef[\\/]')
if (-not $validSteam) {
    throw "The requested window does not belong to Steam."
}
$allowedTitles = if ($Operation -eq "install") {
    @("Install", "Zainstaluj", "Steam")
} else {
    @("Uninstall", "Odinstaluj")
}
$title = $process.MainWindowTitle
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class MoonWakerWindowTitle {
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder b, int n);
}
"@
$buffer = [Text.StringBuilder]::new(300)
[MoonWakerWindowTitle]::GetWindowText([IntPtr]$WindowHandle, $buffer, $buffer.Capacity) | Out-Null
$title = $buffer.ToString()
if ($allowedTitles -notcontains $title) {
    throw "The Steam window title does not match the requested operation."
}
$classBuffer = [Text.StringBuilder]::new(100)
[MoonWakerSteamWindow]::GetClassName([IntPtr]$WindowHandle, $classBuffer, $classBuffer.Capacity) | Out-Null
if ($title -eq "Steam" -and ($Operation -ne "install" -or $classBuffer.ToString() -ne "SDL_app")) {
    throw "The generic Steam window is not a verified installation surface."
}

$root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$WindowHandle)
if ($null -eq $root) { throw "Steam window is unavailable." }
$condition = [System.Windows.Automation.PropertyCondition]::new(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::Button)
$buttons = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)
$matches = @($buttons | Where-Object { $allowed -contains $_.Current.Name })
if ($matches.Count -eq 1) {
    $pattern = $matches[0].GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    $pattern.Invoke()
    [pscustomobject]@{ clicked = $true; action = $matches[0].Current.Name; method = "uia" } |
        ConvertTo-Json -Compress
    exit 0
}

# Steam's Chromium modals expose no UI Automation descendants and ignore Enter.
# Their primary action is the only blue button in the lower half of the exact,
# already verified modal. Detect that button instead of hard-coding coordinates.
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[MoonWakerSteamWindow]::ShowWindow([IntPtr]$WindowHandle, 9) | Out-Null
$currentThread = [MoonWakerSteamWindow]::GetCurrentThreadId()
$targetThread = [MoonWakerSteamWindow]::GetWindowThreadProcessId([IntPtr]$WindowHandle, [IntPtr]::Zero)
$foreground = [MoonWakerSteamWindow]::GetForegroundWindow()
$foregroundThread = if ($foreground -ne [IntPtr]::Zero) {
    [MoonWakerSteamWindow]::GetWindowThreadProcessId($foreground, [IntPtr]::Zero)
} else { 0 }
$attached = @($targetThread, $foregroundThread) | Where-Object {
    $_ -ne 0 -and $_ -ne $currentThread
} | Select-Object -Unique
foreach ($thread in $attached) {
    [MoonWakerSteamWindow]::AttachThreadInput($currentThread, $thread, $true) | Out-Null
}
[MoonWakerSteamWindow]::BringWindowToTop([IntPtr]$WindowHandle) | Out-Null
$focused = [MoonWakerSteamWindow]::SetForegroundWindow([IntPtr]$WindowHandle)
foreach ($thread in $attached) {
    [MoonWakerSteamWindow]::AttachThreadInput($currentThread, $thread, $false) | Out-Null
}
if (-not $focused -and [MoonWakerSteamWindow]::GetForegroundWindow() -ne [IntPtr]$WindowHandle) {
    throw "Windows rejected focus for the Steam operation window."
}
Start-Sleep -Milliseconds 100
$rect = New-Object MoonWakerSteamWindow+RECT
if (-not [MoonWakerSteamWindow]::GetWindowRect([IntPtr]$WindowHandle, [ref]$rect)) {
    throw "Steam operation bounds are unavailable."
}
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
if ($width -lt 300 -or $height -lt 150 -or $width -gt 2000 -or $height -gt 1200) {
    throw "Steam operation window has unexpected dimensions."
}
$bitmap = [Drawing.Bitmap]::new($width, $height)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
if ($title -eq "Steam") {
    $deviceContext = $graphics.GetHdc()
    $printed = [MoonWakerSteamWindow]::PrintWindow([IntPtr]$WindowHandle, $deviceContext, 2)
    $graphics.ReleaseHdc($deviceContext)
    if (-not $printed) {
        $graphics.Dispose(); $bitmap.Dispose()
        throw "Steam operation surface could not be captured safely."
    }
} else {
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
}
$runs = @()
for ($y = [int]($height / 2); $y -lt $height; $y++) {
    $runStart = -1
    for ($x = 0; $x -lt $width; $x++) {
        $color = $bitmap.GetPixel($x, $y)
        $blue = $color.B -gt 170 -and $color.G -gt 80 -and $color.G -lt 190 -and
            $color.R -lt 110 -and $color.B -gt ($color.R + 70)
        if ($blue -and $runStart -lt 0) { $runStart = $x }
        if ((-not $blue -or $x -eq ($width - 1)) -and $runStart -ge 0) {
            $runEnd = $(if ($blue) { $x } else { $x - 1 })
            if (($runEnd - $runStart) -ge 100) {
                $runs += [pscustomobject]@{ Start = $runStart; End = $runEnd; Y = $y }
            }
            $runStart = -1
        }
    }
}
$graphics.Dispose(); $bitmap.Dispose()
if ($runs.Count -eq 0) {
    throw "Steam primary action could not be identified safely."
}
$bottom = $runs | Sort-Object @{ Expression = { $_.Y }; Descending = $true }, @{ Expression = { $_.End - $_.Start }; Descending = $true } | Select-Object -First 1
$buttonRows = @($runs | Where-Object {
    [Math]::Abs($_.Y - $bottom.Y) -le 80 -and
    ([Math]::Min($_.End, $bottom.End) - [Math]::Max($_.Start, $bottom.Start)) -ge
        ([Math]::Min($_.End - $_.Start, $bottom.End - $bottom.Start) * 0.8)
})
$bestStart = ($buttonRows | Measure-Object Start -Minimum).Minimum
$bestEnd = ($buttonRows | Measure-Object End -Maximum).Maximum
$bestTop = ($buttonRows | Measure-Object Y -Minimum).Minimum
$bestBottom = ($buttonRows | Measure-Object Y -Maximum).Maximum
$bestY = [int](($bestTop + $bestBottom) / 2)
$clickX = $rect.Left + [int](($bestStart + $bestEnd) / 2)
$clickY = $rect.Top + $bestY
$cursor = New-Object MoonWakerSteamWindow+POINT
[MoonWakerSteamWindow]::GetCursorPos([ref]$cursor) | Out-Null
[MoonWakerSteamWindow]::SetCursorPos($clickX, $clickY) | Out-Null
Start-Sleep -Milliseconds 300
[MoonWakerSteamWindow]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 150
[MoonWakerSteamWindow]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 300
[MoonWakerSteamWindow]::SetCursorPos($cursor.X, $cursor.Y) | Out-Null
$closed = $false
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    Start-Sleep -Milliseconds 200
    if ($title -ne "Steam" -and -not [MoonWakerSteamWindow]::IsWindow([IntPtr]$WindowHandle)) {
        $closed = $true
        break
    }
    if ($title -eq "Steam") {
        $verification = [Drawing.Bitmap]::new($width, $height)
        $verificationGraphics = [Drawing.Graphics]::FromImage($verification)
        $verificationContext = $verificationGraphics.GetHdc()
        $captured = [MoonWakerSteamWindow]::PrintWindow(
            [IntPtr]$WindowHandle, $verificationContext, 2)
        $verificationGraphics.ReleaseHdc($verificationContext)
        $color = $verification.GetPixel($clickX - $rect.Left, $clickY - $rect.Top)
        $verificationGraphics.Dispose(); $verification.Dispose()
        $stillBlue = $captured -and $color.B -gt 170 -and $color.G -gt 80 -and
            $color.G -lt 190 -and $color.R -lt 110 -and $color.B -gt ($color.R + 70)
        if (-not $stillBlue) {
            $closed = $true
            break
        }
    }
}
[pscustomobject]@{ clicked = $closed; action = $title; method = "verified_primary_button";
    reason = $(if ($closed) { "" } else { "window_remained_open" });
    click_x = $clickX; click_y = $clickY;
    button_run = @($bestStart, $bestTop, $bestEnd, $bestBottom);
    window_bounds = @($rect.Left, $rect.Top, $rect.Right, $rect.Bottom) } |
    ConvertTo-Json -Compress
