param(
    [Parameter(Mandatory)][long]$WindowHandle,
    [Parameter(Mandatory)][ValidateSet("install", "uninstall")][string]$Operation,
    [string]$GameName = "",
    [switch]$AllowVisualFallback,
    [switch]$ProbeOnly
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
$validEpic = $process.ProcessName -eq "EpicGamesLauncher" -and
    $path -match '(?i)[\\/]Epic Games[\\/]Launcher[\\/]Portal[\\/]Binaries[\\/]'
if (-not ($validSteam -or $validEpic)) {
    throw "The requested window does not belong to a supported launcher."
}
$allowed = if ($Operation -eq "install") {
    @("Install", "Zainstaluj")
} else {
    @("Uninstall", "Odinstaluj")
}
function Normalize-EpicText([string]$Value) {
    return (($Value -replace '[^\p{L}\p{N}]', '').ToUpperInvariant())
}
function Test-EpicActionEvidence($Action, $Root, [string]$ExpectedGame) {
    $normalizedGame = Normalize-EpicText $ExpectedGame
    if ($null -eq $Action -or $null -eq $Root -or
            [string]::IsNullOrWhiteSpace($normalizedGame)) { return $false }
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    $ancestor = $Action
    for ($depth = 0; $depth -lt 6; $depth++) {
        $ancestor = $walker.GetParent($ancestor)
        if ($null -eq $ancestor -or $ancestor -eq $Root) { break }
        $nearby = $ancestor.FindAll(
            [System.Windows.Automation.TreeScope]::Subtree,
            [System.Windows.Automation.Condition]::TrueCondition)
        if (@($nearby | Where-Object {
            (Normalize-EpicText $_.Current.Name).IndexOf(
                $normalizedGame, [StringComparison]::Ordinal) -ge 0
        }).Count -gt 0) { return $true }
    }
    return $false
}
function Test-EpicModalEvidence([IntPtr]$Handle, [string]$ExpectedGame,
                                 [string[]]$AllowedText) {
    $element = [System.Windows.Automation.AutomationElement]::FromHandle($Handle)
    if ($null -eq $element) { return $false }
    $normalizedGame = Normalize-EpicText $ExpectedGame
    if ([string]::IsNullOrWhiteSpace($normalizedGame)) { return $false }
    $elements = $element.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition)
    $actions = @($elements | Where-Object {
        $AllowedText -contains $_.Current.Name
    })
    return @($actions | Where-Object {
        Test-EpicActionEvidence $_ $element $ExpectedGame
    }).Count -gt 0
}
function Test-UsableCapture([Drawing.Bitmap]$Bitmap) {
    $first = $Bitmap.GetPixel(0, 0).ToArgb()
    $stepX = [Math]::Max(1, [int]($Bitmap.Width / 8))
    $stepY = [Math]::Max(1, [int]($Bitmap.Height / 8))
    for ($y = 0; $y -lt $Bitmap.Height; $y += $stepY) {
        for ($x = 0; $x -lt $Bitmap.Width; $x += $stepX) {
            if ($Bitmap.GetPixel($x, $y).ToArgb() -ne $first) { return $true }
        }
    }
    return $false
}
function Test-BlueButtonGeometry([Drawing.Bitmap]$Bitmap, [int]$Left, [int]$Top,
                                 [int]$Right, [int]$Bottom) {
    if ($Left -lt 0 -or $Top -lt 0 -or $Right -ge $Bitmap.Width -or
            $Bottom -ge $Bitmap.Height -or $Right -le $Left -or $Bottom -le $Top) {
        return $false
    }
    $blue = 0; $samples = 0
    foreach ($x in @($Left + 4, [int](($Left + $Right) / 2), $Right - 4)) {
        foreach ($y in @($Top + 2, [int](($Top + $Bottom) / 2), $Bottom - 2)) {
            $color = $Bitmap.GetPixel($x, $y); $samples++
            if ($color.B -gt 170 -and $color.G -gt 80 -and $color.G -lt 190 -and
                    $color.R -lt 110 -and $color.B -gt ($color.R + 70)) { $blue++ }
        }
    }
    return $samples -gt 0 -and ($blue / $samples) -ge 0.8
}
$allowedTitles = if ($validEpic) {
    @()
} elseif ($Operation -eq "install") {
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
if ($validSteam -and $allowedTitles -notcontains $title) {
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
if ($validEpic) {
    $all = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition)
    $matches = @($all | Where-Object {
        $allowed -contains $_.Current.Name -and
        @($_.GetSupportedPatterns()) -contains [System.Windows.Automation.InvokePattern]::Pattern -and
        (Test-EpicActionEvidence $_ $root $GameName)
    })
}
if ($matches.Count -eq 1) {
    if ($ProbeOnly) {
        [pscustomobject]@{ clicked = $false; recognized = $true; method = "uia" } |
            ConvertTo-Json -Compress
        exit 0
    }
    $pattern = $matches[0].GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    $pattern.Invoke()
    [pscustomobject]@{ clicked = $true; action = $matches[0].Current.Name; method = "uia" } |
        ConvertTo-Json -Compress
    exit 0
}

# Epic's main Chromium window can contain unrelated blue controls. If its
# confirmation button is not exposed by UI Automation, leave it for the
# existing Desktop confirmation fallback instead of guessing coordinates.
if ($validEpic -and -not $AllowVisualFallback) {
    [pscustomobject]@{ clicked = $false; recognized = $false; reason = "uia_button_unavailable" } |
        ConvertTo-Json -Compress
    exit 0
}
if ($validEpic -and -not (Test-EpicModalEvidence ([IntPtr]$WindowHandle) $GameName $allowed)) {
    [pscustomobject]@{ clicked = $false; recognized = $false; reason = "epic_modal_unverified" } |
        ConvertTo-Json -Compress
    exit 0
}

# Chromium launcher modals expose no UI Automation descendants and ignore Enter.
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
Start-Sleep -Milliseconds 100
if ([MoonWakerSteamWindow]::GetForegroundWindow() -ne [IntPtr]$WindowHandle) {
    throw "Windows rejected focus for the operation window."
}
$rect = New-Object MoonWakerSteamWindow+RECT
if (-not [MoonWakerSteamWindow]::GetWindowRect([IntPtr]$WindowHandle, [ref]$rect)) {
    throw "Steam operation bounds are unavailable."
}
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
if ($width -lt 300 -or $height -lt 150 -or $width -gt 2500 -or $height -gt 1600) {
    throw "Steam operation window has unexpected dimensions."
}
$bitmap = [Drawing.Bitmap]::new($width, $height)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
if ($validSteam -or $validEpic) {
    $deviceContext = $graphics.GetHdc()
    $printed = [MoonWakerSteamWindow]::PrintWindow([IntPtr]$WindowHandle, $deviceContext, 2)
    $graphics.ReleaseHdc($deviceContext)
    if (-not $printed -or -not (Test-UsableCapture $bitmap)) {
        $graphics.Dispose(); $bitmap.Dispose()
        throw "Operation surface could not be captured safely."
    }
}
$scanTop = [int]($height / 2)
$scanBottom = $height - 1
$lightPanel = $false
if ($validEpic) {
    $lightRows = @()
    for ($y = 0; $y -lt $height; $y += 4) {
        $samples = 0; $light = 0
        for ($x = 0; $x -lt $width; $x += 8) {
            $samples++
            $color = $bitmap.GetPixel($x, $y)
            if ($color.R -gt 220 -and $color.G -gt 220 -and $color.B -gt 220) { $light++ }
        }
        if ($samples -gt 0 -and ($light / $samples) -ge 0.6) { $lightRows += $y }
    }
    $bands = @(); $bandStart = -1; $previousRow = -8
    foreach ($row in $lightRows) {
        if ($bandStart -lt 0 -or ($row - $previousRow) -gt 4) {
            if ($bandStart -ge 0) {
                $bands += [pscustomobject]@{ Start = $bandStart; End = $previousRow + 3 }
            }
            $bandStart = $row
        }
        $previousRow = $row
    }
    if ($bandStart -ge 0) {
        $bands += [pscustomobject]@{ Start = $bandStart; End = [Math]::Min($height - 1, $previousRow + 3) }
    }
    $panel = $bands | Where-Object { ($_.End - $_.Start) -ge 120 } |
        Sort-Object @{ Expression = { $_.End - $_.Start }; Descending = $true } |
        Select-Object -First 1
    if ($null -ne $panel) {
        $scanTop = [int]$panel.Start
        $scanBottom = [int]$panel.End
        $lightPanel = $true
    } else {
        # Native Epic dialogs are dark and may contain blue cover artwork near
        # the top. Their primary action is confined to the bottom action area.
        $scanTop = [int]($height * 0.55)
    }
}
$runs = @()
for ($y = $scanTop; $y -le $scanBottom; $y++) {
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
$competingRows = @($runs | Where-Object {
    [Math]::Abs($_.Y - $bottom.Y) -gt 80 -or
    ([Math]::Min($_.End, $bottom.End) - [Math]::Max($_.Start, $bottom.Start)) -lt
        ([Math]::Min($_.End - $_.Start, $bottom.End - $bottom.Start) * 0.8)
})
if ($validEpic -and -not $lightPanel -and $competingRows.Count -gt 0) {
    throw "Epic operation surface contains multiple primary-action candidates."
}
$bestStart = ($buttonRows | Measure-Object Start -Minimum).Minimum
$bestEnd = ($buttonRows | Measure-Object End -Maximum).Maximum
$bestTop = ($buttonRows | Measure-Object Y -Minimum).Minimum
$bestBottom = ($buttonRows | Measure-Object Y -Maximum).Maximum
$bestY = [int](($bestTop + $bestBottom) / 2)
if ($validEpic -and $lightPanel -and
        $bestY -lt ($scanTop + [int](($scanBottom - $scanTop) * 0.55))) {
    throw "Epic primary action is outside the modal action area."
}
$clickX = $rect.Left + [int](($bestStart + $bestEnd) / 2)
$clickY = $rect.Top + $bestY
if ($ProbeOnly) {
    [pscustomobject]@{
        clicked = $false; recognized = $true; method = "visual"
        visual_confirmation_safe = $true
        variant = $(if ($lightPanel) { "light" } else { "dark" })
    } | ConvertTo-Json -Compress
    exit 0
}
if ($validEpic) {
    $currentProcessId = [uint32]0
    [MoonWakerSteamWindow]::GetWindowThreadProcessId(
        [IntPtr]$WindowHandle, [ref]$currentProcessId) | Out-Null
    $currentProcess = Get-Process -Id $currentProcessId -ErrorAction Stop
    $currentPath = [IO.Path]::GetFullPath($currentProcess.Path)
    $currentRect = New-Object MoonWakerSteamWindow+RECT
    if (-not [MoonWakerSteamWindow]::IsWindow([IntPtr]$WindowHandle) -or
            $currentProcessId -ne $processId -or
            -not [string]::Equals($currentPath, $path, [StringComparison]::OrdinalIgnoreCase) -or
            [MoonWakerSteamWindow]::GetForegroundWindow() -ne [IntPtr]$WindowHandle -or
            -not [MoonWakerSteamWindow]::GetWindowRect([IntPtr]$WindowHandle, [ref]$currentRect) -or
            $currentRect.Left -ne $rect.Left -or $currentRect.Top -ne $rect.Top -or
            $currentRect.Right -ne $rect.Right -or $currentRect.Bottom -ne $rect.Bottom -or
            -not (Test-EpicModalEvidence ([IntPtr]$WindowHandle) $GameName $allowed)) {
        [pscustomobject]@{ clicked = $false; recognized = $false; reason = "epic_modal_unverified" } |
            ConvertTo-Json -Compress
        exit 0
    }
}
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
    if (-not [MoonWakerSteamWindow]::IsWindow([IntPtr]$WindowHandle)) {
        $closed = $true
        break
    }
    if ($validSteam -or $validEpic) {
        $verification = [Drawing.Bitmap]::new($width, $height)
        $verificationGraphics = [Drawing.Graphics]::FromImage($verification)
        $verificationContext = $verificationGraphics.GetHdc()
        $captured = [MoonWakerSteamWindow]::PrintWindow(
            [IntPtr]$WindowHandle, $verificationContext, 2)
        $verificationGraphics.ReleaseHdc($verificationContext)
        $usable = $captured -and (Test-UsableCapture $verification)
        $buttonPresent = Test-BlueButtonGeometry $verification $bestStart $bestTop $bestEnd $bestBottom
        $color = $verification.GetPixel($clickX - $rect.Left, $clickY - $rect.Top)
        $verificationGraphics.Dispose(); $verification.Dispose()
        if ($validEpic) {
            if (-not $usable) { break }
            if (-not (Test-EpicModalEvidence ([IntPtr]$WindowHandle) $GameName $allowed) -or
                    -not $buttonPresent) {
                $closed = $true
                break
            }
            continue
        }
        $stillBlue = $captured -and $color.B -gt 170 -and $color.G -gt 80 -and
            $color.G -lt 190 -and $color.R -lt 110 -and $color.B -gt ($color.R + 70)
        if (-not $stillBlue) {
            $closed = $true
            break
        }
    }
}
[pscustomobject]@{ clicked = $closed; action = $title; method = "verified_primary_button";
    reason = $(if ($closed) { "" } elseif ($validEpic) { "click_unconfirmed" } else { "window_remained_open" }) } |
    ConvertTo-Json -Compress
