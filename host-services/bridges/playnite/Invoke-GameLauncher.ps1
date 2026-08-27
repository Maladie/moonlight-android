param(
    [Parameter(Mandatory)][long]$WindowHandle,
    [Parameter(Mandatory)][uint32]$ExpectedProcessId,
    [Parameter(Mandatory)][string]$ExpectedProcessPath,
    [switch]$AllowDefaultAction
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public sealed class MoonWakerNativeButton {
    public IntPtr Handle;
    public int Id;
    public string Text;
}

public static class MoonWakerGameLauncherWindow {
    public delegate bool EnumWindowProc(IntPtr window, IntPtr parameter);
    [StructLayout(LayoutKind.Sequential)] public struct Point { public int X; public int Y; }
    [StructLayout(LayoutKind.Sequential)] public struct Rect {
        public int Left; public int Top; public int Right; public int Bottom;
    }
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr window);
    [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumWindowProc callback, IntPtr parameter);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr window, StringBuilder value, int length);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr window, StringBuilder value, int length);
    [DllImport("user32.dll")] public static extern int GetDlgCtrlID(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr SetActiveWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr window, uint message, IntPtr wparam, IntPtr lparam);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out Rect rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr window, IntPtr deviceContext, uint flags);
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);
    [DllImport("kernel32.dll", SetLastError = true)] public static extern IntPtr OpenProcess(uint access, bool inherit, uint processId);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] public static extern bool QueryFullProcessImageName(IntPtr process, uint flags, StringBuilder path, ref uint size);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);

    public static string ProcessPath(uint processId) {
        IntPtr process = OpenProcess(0x1000, false, processId); // PROCESS_QUERY_LIMITED_INFORMATION
        if (process == IntPtr.Zero) return "";
        try {
            var path = new StringBuilder(32768);
            uint size = (uint)path.Capacity;
            return QueryFullProcessImageName(process, 0, path, ref size)
                ? path.ToString() : "";
        } finally {
            CloseHandle(process);
        }
    }

    public static MoonWakerNativeButton[] Buttons(IntPtr root) {
        var result = new List<MoonWakerNativeButton>();
        EnumChildWindows(root, (window, parameter) => {
            var className = new StringBuilder(64);
            GetClassName(window, className, className.Capacity);
            if (string.Equals(className.ToString(), "Button", StringComparison.OrdinalIgnoreCase) &&
                    IsWindowVisible(window) && IsWindowEnabled(window)) {
                var text = new StringBuilder(512);
                GetWindowText(window, text, text.Capacity);
                result.Add(new MoonWakerNativeButton {
                    Handle = window, Id = GetDlgCtrlID(window), Text = text.ToString()
                });
            }
            return true;
        }, IntPtr.Zero);
        return result.ToArray();
    }

    public static void ClickButton(IntPtr root, IntPtr button) {
        SetActiveWindow(root);
        SendMessage(button, 0x00F5, IntPtr.Zero, IntPtr.Zero); // BM_CLICK
    }

    public static bool ClickPoint(IntPtr root, double left, double top, double right, double bottom) {
        Rect window;
        if (!GetWindowRect(root, out window) || double.IsNaN(left) || double.IsNaN(top) ||
                double.IsNaN(right) || double.IsNaN(bottom) || right <= left || bottom <= top) {
            return false;
        }
        int x = (int)Math.Round((left + right) / 2.0);
        int y = (int)Math.Round((top + bottom) / 2.0);
        if (x < window.Left || x >= window.Right || y < window.Top || y >= window.Bottom) {
            return false;
        }
        Point previous;
        if (!GetCursorPos(out previous) || !SetCursorPos(x, y)) return false;
        mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero); // MOUSEEVENTF_LEFTDOWN
        mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero); // MOUSEEVENTF_LEFTUP
        SetCursorPos(previous.X, previous.Y);
        return true;
    }
}
"@

$handle = [IntPtr]$WindowHandle
$processId = [uint32]0
if (-not [MoonWakerGameLauncherWindow]::IsWindow($handle) -or
        [MoonWakerGameLauncherWindow]::GetForegroundWindow() -ne $handle) {
    [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_window_not_foreground" } |
        ConvertTo-Json -Compress
    exit 0
}
[MoonWakerGameLauncherWindow]::GetWindowThreadProcessId($handle, [ref]$processId) | Out-Null
$path = [MoonWakerGameLauncherWindow]::ProcessPath($processId)
if ($processId -ne $ExpectedProcessId -or
        [string]::IsNullOrWhiteSpace($path) -or
        -not [string]::Equals($path, [IO.Path]::GetFullPath($ExpectedProcessPath),
            [StringComparison]::OrdinalIgnoreCase)) {
    [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_identity_changed" } |
        ConvertTo-Json -Compress
    exit 0
}

$root = [System.Windows.Automation.AutomationElement]::FromHandle($handle)
if ($null -eq $root) {
    [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_uia_unavailable" } |
        ConvertTo-Json -Compress
    exit 0
}
$allowed = @(
    "play", "play game", "launch", "launch game", "start", "start game", "continue",
    "graj", "uruchom", "uruchom grę", "uruchom gre", "kontynuuj"
)
$allowedIds = @("play", "playbutton", "launch", "launchbutton", "start", "startbutton", "continuebutton")

function Normalize-LauncherAction([string]$Value) {
    return ((($Value -replace '[^\p{L}\p{N}\s]', '') -replace '\s+', ' ').Trim()).ToLowerInvariant()
}

function Find-VisualPrimaryAction([IntPtr]$Handle) {
    $window = New-Object MoonWakerGameLauncherWindow+Rect
    if (-not [MoonWakerGameLauncherWindow]::GetWindowRect($Handle, [ref]$window)) {
        return $null
    }
    $width = $window.Right - $window.Left
    $height = $window.Bottom - $window.Top
    if ($width -lt 300 -or $height -lt 180 -or $width -gt 2500 -or $height -gt 1600) {
        return $null
    }
    $bitmap = [Drawing.Bitmap]::new($width, $height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $deviceContext = $graphics.GetHdc()
        try {
            if (-not [MoonWakerGameLauncherWindow]::PrintWindow($Handle, $deviceContext, 2)) {
                return $null
            }
        } finally {
            $graphics.ReleaseHdc($deviceContext)
        }
        $runs = @()
        $minimumWidth = [Math]::Max(60, [int]($width * 0.12))
        for ($y = [int]($height * 0.60); $y -lt $height; $y++) {
            $runStart = -1
            for ($x = 0; $x -lt $width; $x++) {
                $color = $bitmap.GetPixel($x, $y)
                $blue = $color.B -gt 170 -and $color.G -gt 80 -and $color.G -lt 210 -and
                    $color.R -lt 120 -and $color.B -gt ($color.R + 60)
                if ($blue -and $runStart -lt 0) { $runStart = $x }
                if ((-not $blue -or $x -eq ($width - 1)) -and $runStart -ge 0) {
                    $runEnd = $(if ($blue) { $x } else { $x - 1 })
                    if (($runEnd - $runStart + 1) -ge $minimumWidth) {
                        $runs += [pscustomobject]@{ Start = $runStart; End = $runEnd; Y = $y }
                    }
                    $runStart = -1
                }
            }
        }
        if ($runs.Count -eq 0) { return $null }
        $seed = $runs | Sort-Object @{ Expression = { $_.Y }; Descending = $true },
            @{ Expression = { $_.End - $_.Start }; Descending = $true } | Select-Object -First 1
        $buttonRows = @($runs | Where-Object {
            [Math]::Abs($_.Y - $seed.Y) -le 80 -and
            ([Math]::Min($_.End, $seed.End) - [Math]::Max($_.Start, $seed.Start)) -ge
                ([Math]::Min($_.End - $_.Start, $seed.End - $seed.Start) * 0.8)
        })
        $competingRows = @($runs | Where-Object { $buttonRows -notcontains $_ })
        $left = ($buttonRows | Measure-Object Start -Minimum).Minimum
        $right = ($buttonRows | Measure-Object End -Maximum).Maximum
        $top = ($buttonRows | Measure-Object Y -Minimum).Minimum
        $bottom = ($buttonRows | Measure-Object Y -Maximum).Maximum
        $centerX = [int](($left + $right) / 2)
        if ($competingRows.Count -gt 0 -or ($bottom - $top + 1) -lt 20 -or
                $centerX -lt [int]($width * 0.50) -or $bottom -lt [int]($height * 0.70)) {
            return $null
        }
        return [pscustomobject]@{
            Left = $window.Left + $left; Top = $window.Top + $top
            Right = $window.Left + $right; Bottom = $window.Top + $bottom
            WindowLeft = $window.Left; WindowTop = $window.Top
            WindowRight = $window.Right; WindowBottom = $window.Bottom
        }
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Test-LauncherIdentity {
    $currentProcessId = [uint32]0
    [MoonWakerGameLauncherWindow]::GetWindowThreadProcessId($handle, [ref]$currentProcessId) | Out-Null
    $currentPath = [MoonWakerGameLauncherWindow]::ProcessPath($currentProcessId)
    return [MoonWakerGameLauncherWindow]::IsWindow($handle) -and
        [MoonWakerGameLauncherWindow]::GetForegroundWindow() -eq $handle -and
        $currentProcessId -eq $ExpectedProcessId -and
        -not [string]::IsNullOrWhiteSpace($currentPath) -and
        [string]::Equals($currentPath, [IO.Path]::GetFullPath($ExpectedProcessPath),
            [StringComparison]::OrdinalIgnoreCase)
}

$elements = @($root.FindAll(
    [System.Windows.Automation.TreeScope]::Descendants,
    [System.Windows.Automation.Condition]::TrueCondition) |
    Where-Object { $_.Current.IsEnabled -and -not $_.Current.IsOffscreen })
$actionable = @($elements | Where-Object {
    $patterns = @($_.GetSupportedPatterns())
    $patterns -contains [System.Windows.Automation.InvokePattern]::Pattern
})
$matches = @($actionable | Where-Object {
    $name = ((($_.Current.Name -replace '[^\p{L}\p{N}\s]', '') -replace '\s+', ' ').Trim()).ToLowerInvariant()
    $automationId = (($_.Current.AutomationId -replace '[^\p{L}\p{N}]', '')).ToLowerInvariant()
    $allowed -contains $name -or $allowedIds -contains $automationId
})
if ($matches.Count -gt 1) {
    [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_action_ambiguous"; button_count = $matches.Count } |
        ConvertTo-Json -Compress
    exit 0
}
if ($AllowDefaultAction -and $matches.Count -eq 1) {
    if (-not (Test-LauncherIdentity)) {
        [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_identity_changed" } |
            ConvertTo-Json -Compress
        exit 0
    }
    $bounds = $matches[0].Current.BoundingRectangle
    if (-not [MoonWakerGameLauncherWindow]::ClickPoint(
            $handle, $bounds.Left, $bounds.Top, $bounds.Right, $bounds.Bottom)) {
        [pscustomobject]@{ recognized = $true; clicked = $false; reason = "launcher_input_failed" } |
            ConvertTo-Json -Compress
        exit 0
    }
    [pscustomobject]@{
        recognized = $true; clicked = $true; method = "uia_pointer"
        action = $(if ($matches[0].Current.Name) { $matches[0].Current.Name }
                   else { $matches[0].Current.AutomationId })
    } | ConvertTo-Json -Compress
    exit 0
}
if ($AllowDefaultAction -and $matches.Count -eq 0) {
    $visual = Find-VisualPrimaryAction $handle
    if ($null -eq $visual -or -not (Test-LauncherIdentity)) {
        [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_action_unavailable" } |
            ConvertTo-Json -Compress
        exit 0
    }
    $currentBounds = New-Object MoonWakerGameLauncherWindow+Rect
    if (-not [MoonWakerGameLauncherWindow]::GetWindowRect($handle, [ref]$currentBounds) -or
            $currentBounds.Left -ne $visual.WindowLeft -or $currentBounds.Top -ne $visual.WindowTop -or
            $currentBounds.Right -ne $visual.WindowRight -or $currentBounds.Bottom -ne $visual.WindowBottom -or
            -not [MoonWakerGameLauncherWindow]::ClickPoint(
                $handle, $visual.Left, $visual.Top, $visual.Right, $visual.Bottom)) {
        [pscustomobject]@{ recognized = $true; clicked = $false; reason = "launcher_input_failed" } |
            ConvertTo-Json -Compress
        exit 0
    }
    [pscustomobject]@{ recognized = $true; clicked = $true; method = "visual_primary" } |
        ConvertTo-Json -Compress
    exit 0
}
if ($matches.Count -eq 1) {
    if (-not (Test-LauncherIdentity)) {
        [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_identity_changed" } |
            ConvertTo-Json -Compress
        exit 0
    }
    $matches[0].GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    [pscustomobject]@{
        recognized = $true; clicked = $true; method = "uia"
        action = $(if ($matches[0].Current.Name) { $matches[0].Current.Name }
                   else { $matches[0].Current.AutomationId })
    } | ConvertTo-Json -Compress
    exit 0
}

$nativeButtons = @([MoonWakerGameLauncherWindow]::Buttons($handle))
$nativeMatches = @($nativeButtons | Where-Object {
    $allowed -contains (Normalize-LauncherAction $_.Text)
})
if ($nativeMatches.Count -gt 1) {
    [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_action_ambiguous"; button_count = $nativeMatches.Count } |
        ConvertTo-Json -Compress
    exit 0
}
if ($nativeMatches.Count -eq 1) {
    if (-not (Test-LauncherIdentity)) {
        [pscustomobject]@{ recognized = $false; clicked = $false; reason = "launcher_identity_changed" } |
            ConvertTo-Json -Compress
        exit 0
    }
    [MoonWakerGameLauncherWindow]::ClickButton($handle, $nativeMatches[0].Handle)
    [pscustomobject]@{
        recognized = $true; clicked = $true; method = "win32"
        action = $nativeMatches[0].Text
    } | ConvertTo-Json -Compress
    exit 0
}

[pscustomobject]@{
    recognized = $false; clicked = $false
    reason = $(if ($actionable.Count -gt 0 -or $nativeButtons.Count -gt 0) {
            "launcher_action_unrecognized"
        } else { "launcher_action_unavailable" })
    button_count = $actionable.Count + $nativeButtons.Count
} | ConvertTo-Json -Compress
exit 0
