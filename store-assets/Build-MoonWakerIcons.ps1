#requires -Version 5.1
# Generates every MoonWaker icon from one geometry: the Android TV launcher icon and
# banner, the Host Control icon (also the tray icon) and the host installer icon.
# The banner wordmark is Ubuntu Bold (Ubuntu Font Licence 1.0); pass its .ttf file.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$WordmarkFont,
    [string]$PreviewPath
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName PresentationCore, WindowsBase
$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "app\src\main\res"

$Plate = '#13202F'   # between console_surface and console_surface_focused
$Pad = '#73D7FF'     # console_accent
$Moon = '#F2EEE2'    # warm moonlight, against the cool accent
$Gap = 3.2

function Brush([string]$hex) { [Windows.Media.SolidColorBrush][Windows.Media.ColorConverter]::ConvertFromString($hex) }
function Disc($cx, $cy, $r) { [Windows.Media.EllipseGeometry]::new([Windows.Point]::new($cx, $cy), $r, $r) }
function Minus($a, $b) { [Windows.Media.Geometry]::Combine($a, $b, 'Exclude', $null) }
function Plus($a, $b) { [Windows.Media.Geometry]::Combine($a, $b, 'Union', $null) }
function Dilate($g, $d) {
    $pen = [Windows.Media.Pen]::new([Windows.Media.Brushes]::Black, 2 * $d); $pen.LineJoin = 'Round'
    Plus $g ($g.GetWidenedPathGeometry($pen))
}
function Fit([Windows.Rect]$b, [double]$x, [double]$y, [double]$w, [double]$h) {
    $s = [Math]::Min($w / $b.Width, $h / $b.Height)
    $t = [Windows.Media.TransformGroup]::new()
    $t.Children.Add([Windows.Media.TranslateTransform]::new(-($b.X + $b.Width / 2), -($b.Y + $b.Height / 2)))
    $t.Children.Add([Windows.Media.ScaleTransform]::new($s, $s))
    $t.Children.Add([Windows.Media.TranslateTransform]::new($x + $w / 2, $y + $h / 2))
    $t
}
function Placed($g, $transform) { [Windows.Media.Geometry]::Combine($g, [Windows.Media.Geometry]::Empty, 'Union', $transform) }

# The mark: a pad with a d-pad and two buttons in a row; the waxing moon rises in front
# of its right shoulder, so the moon's unlit disc takes a bite out of the pad.
$padShape = [Windows.Media.Geometry]::Parse('M30,52 H70 C80,52 86,56 88,64 L92,82 C94,91 87,96 80,92 L71,84 C68,81.5 66,81 62,81 H38 C34,81 32,81.5 29,84 L20,92 C13,96 6,91 8,82 L12,64 C14,56 20,52 30,52 Z')
$dpad = [Windows.Media.Geometry]::Parse('F1 M18.5,63.5 h17 v5.5 h-17 Z M24.25,57.75 h5.5 v17 h-5.5 Z')
$holes = Plus $dpad (Plus (Disc 61 71 4.3) (Disc 71 71 4.3))
$moonDisc = Disc 72 33 22
$markMoon = Minus $moonDisc (Disc 62.5 28.5 19)
$markPad = Minus (Minus $padShape $holes) (Dilate $moonDisc $Gap)
$markBounds = $markMoon.Bounds; $markBounds.Union($markPad.Bounds)

# Square icon layers in a 100x100 box: plate, then the mark with a 12% margin.
function Get-IconLayers([switch]$Installer) {
    # The installer moves the mark up and left to leave the corner to the badge.
    $t = if ($Installer) { Fit $markBounds 8 9 72 72 } else { Fit $markBounds 12 12 76 76 }
    $layers = @(
        @{ G = [Windows.Media.RectangleGeometry]::new([Windows.Rect]::new(0, 0, 100, 100), 22, 22); C = $Plate },
        @{ G = (Placed $markMoon $t); C = $Moon },
        @{ G = (Placed $markPad $t); C = $Pad })
    if ($Installer) {
        # A download badge cut out of the bottom-right corner.
        $badge = Disc 81 81 15.5
        $cut = Dilate $badge 3.5
        foreach ($l in $layers) { $l.G = Minus $l.G $cut }
        $arrow = [Windows.Media.Geometry]::Parse('M78.25,71 h5.5 v9 h6 l-8.75,9.5 l-8.75,-9.5 h6 Z')
        $layers += @{ G = (Minus $badge $arrow); C = $Pad }
    }
    $layers
}

function Render($layers, [int]$w, [int]$h, [double]$unit) {
    $dv = [Windows.Media.DrawingVisual]::new()
    $dc = $dv.RenderOpen()
    $dc.PushTransform([Windows.Media.ScaleTransform]::new($unit, $unit))
    foreach ($l in $layers) { $dc.DrawGeometry((Brush $l.C), $null, $l.G) }
    $dc.Pop(); $dc.Close()
    $bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new($w, $h, 96, 96, [Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($dv)
    $bitmap
}

function Get-Png($bitmap) {
    $encoder = [Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $stream = [IO.MemoryStream]::new(); $encoder.Save($stream); , $stream.ToArray()
}

# Small frames are 32-bit DIBs with an AND mask (the notification area still reads the
# mask); 256 px is PNG.
function Write-Ico($layers, [string]$path) {
    $frames = foreach ($size in 16, 20, 24, 32, 40, 48, 64, 256) {
        $bitmap = Render $layers $size $size ($size / 100)
        if ($size -eq 256) { , (Get-Png $bitmap); continue }
        $bgra = [Windows.Media.Imaging.FormatConvertedBitmap]::new($bitmap, [Windows.Media.PixelFormats]::Bgra32, $null, 0)
        $pixels = [byte[]]::new($size * $size * 4)
        $bgra.CopyPixels($pixels, $size * 4, 0)
        $maskStride = [int]([Math]::Ceiling($size / 32) * 4)
        $mask = [byte[]]::new($maskStride * $size)
        $data = [IO.MemoryStream]::new(); $writer = [IO.BinaryWriter]::new($data)
        foreach ($v in 40, $size, (2 * $size)) { $writer.Write([int]$v) }
        $writer.Write([int16]1); $writer.Write([int16]32)
        foreach ($v in 0, ($pixels.Length + $mask.Length), 0, 0, 0, 0) { $writer.Write([int]$v) }
        for ($y = $size - 1; $y -ge 0; $y--) {
            $writer.Write($pixels, $y * $size * 4, $size * 4)
            for ($x = 0; $x -lt $size; $x++) {
                if ($pixels[($y * $size + $x) * 4 + 3] -eq 0) {
                    $mask[($size - 1 - $y) * $maskStride + [Math]::Floor($x / 8)] = $mask[($size - 1 - $y) * $maskStride + [Math]::Floor($x / 8)] -bor (0x80 -shr ($x % 8))
                }
            }
        }
        $writer.Write($mask); $writer.Flush()
        , $data.ToArray()
    }
    $sizes = 16, 20, 24, 32, 40, 48, 64, 256
    $out = [IO.MemoryStream]::new(); $writer = [IO.BinaryWriter]::new($out)
    $writer.Write([int16]0); $writer.Write([int16]1); $writer.Write([int16]$frames.Count)
    $offset = 6 + 16 * $frames.Count
    for ($i = 0; $i -lt $frames.Count; $i++) {
        $s = $sizes[$i] % 256
        $writer.Write([byte]$s); $writer.Write([byte]$s); $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([int16]1); $writer.Write([int16]32)
        $writer.Write([int]$frames[$i].Length); $writer.Write([int]$offset)
        $offset += $frames[$i].Length
    }
    foreach ($f in $frames) { $writer.Write($f) }
    $writer.Flush()
    [IO.File]::WriteAllBytes($path, $out.ToArray())
}

function PathData($geometry) {
    $path = [Windows.Media.PathGeometry]::CreateFromGeometry($geometry)
    $data = $path.ToString([Globalization.CultureInfo]::InvariantCulture) -replace '^F[01]', ''
    $data = [regex]::Replace($data, '-?\d+\.\d+(E-?\d+)?', { param($m) [Math]::Round([double]::Parse($m.Value, [Globalization.CultureInfo]::InvariantCulture), 2).ToString([Globalization.CultureInfo]::InvariantCulture) })
    $fill = if ($path.FillRule -eq 'EvenOdd') { ' android:fillType="evenOdd"' } else { '' }
    "    <path android:fillColor=`"$($script:fillColor)`"$fill android:pathData=`"$data`" />"
}

function Write-Vector([string]$path, [int]$w, [int]$h, $layers) {
    $paths = foreach ($l in $layers) { $script:fillColor = $l.C; PathData $l.G }
    $xml = @(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        "    android:width=`"${w}dp`" android:height=`"${h}dp`"",
        "    android:viewportWidth=`"$w`" android:viewportHeight=`"$h`">") + $paths + '</vector>'
    [IO.File]::WriteAllText($path, ($xml -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
}

# Android adaptive foreground: the mark inside the 66 dp safe zone of the 108 dp canvas.
$t = Fit $markBounds 26 26 56 56
$foreground = @(@{ G = (Placed $markMoon $t); C = $Moon }, @{ G = (Placed $markPad $t); C = $Pad })
Write-Vector (Join-Path $res "drawable\ic_launcher_foreground.xml") 108 108 $foreground

# Android TV banner, 320x180 dp: the mark and the wordmark side by side.
$family = [Windows.Media.FontFamily]::new([Uri]::new((Resolve-Path -LiteralPath $WordmarkFont).Path), "./#Ubuntu")
$typeface = [Windows.Media.Typeface]::new($family, 'Normal', 'Bold', 'Normal')
$text = [Windows.Media.FormattedText]::new("MoonWaker", [Globalization.CultureInfo]::InvariantCulture,
    'LeftToRight', $typeface, 100, [Windows.Media.Brushes]::White, 1.0)
$word = Placed ($text.BuildGeometry([Windows.Point]::new(0, 0))) $null
$markHeight = 92; $space = 16
$markWidth = $markHeight * $markBounds.Width / $markBounds.Height
$wordWidth = 320 - 2 * 26 - $markWidth - $space
$wordHeight = $wordWidth * $word.Bounds.Height / $word.Bounds.Width
$tm = Fit $markBounds 26 (90 - $markHeight / 2) $markWidth $markHeight
# Align the wordmark's x-height centre with the pad, not with the whole mark.
$tw = Fit $word.Bounds (26 + $markWidth + $space) (90 - $wordHeight / 2 + 12) $wordWidth $wordHeight
$banner = @(
    @{ G = [Windows.Media.RectangleGeometry]::new([Windows.Rect]::new(0, 0, 320, 180)); C = $Plate },
    @{ G = (Placed $markMoon $tm); C = $Moon },
    @{ G = (Placed $markPad $tm); C = $Pad },
    @{ G = (Placed $word $tw); C = $Moon })
Write-Vector (Join-Path $res "drawable\moonwaker_launcher_banner.xml") 320 180 $banner

$hostIcon = Get-IconLayers
$installerIcon = Get-IconLayers -Installer
Write-Ico $hostIcon (Join-Path $root "host-services\control\moonwaker-host-control.ico")
Write-Ico $installerIcon (Join-Path $root "host-services\installer\moonwaker-host.ico")

if ($PreviewPath) {
    # Large and actual-size renders of every icon on dark and light backgrounds.
    $dv = [Windows.Media.DrawingVisual]::new(); $dc = $dv.RenderOpen()
    $dc.DrawRectangle((Brush '#202020'), $null, [Windows.Rect]::new(0, 0, 1200, 330))
    $dc.DrawRectangle((Brush '#F3F3F3'), $null, [Windows.Rect]::new(0, 330, 1200, 330))
    foreach ($row in 0, 1) {
        $y = 20 + 330 * $row
        $tv = @(@{ G = [Windows.Media.RectangleGeometry]::new([Windows.Rect]::new(0, 0, 108, 108)); C = $Plate }) + $foreground
        $dc.DrawImage((Render $tv 216 216 2), [Windows.Rect]::new(20, $y, 216, 216))
        $dc.DrawEllipse($null, [Windows.Media.Pen]::new((Brush '#55FF5555'), 1), [Windows.Point]::new(128, $y + 108), 66, 66)
        $dc.DrawImage((Render $banner 480 270 1.5), [Windows.Rect]::new(256, $y, 480, 270))
        $x = 756
        foreach ($set in $hostIcon, $installerIcon) {
            $dc.DrawImage((Render $set 128 128 1.28), [Windows.Rect]::new($x, $y, 128, 128))
            $sx = $x
            foreach ($s in 48, 32, 24, 16) {
                $dc.DrawImage((Render $set $s $s ($s / 100)), [Windows.Rect]::new($sx, $y + 150, $s, $s)); $sx += $s + 12
            }
            $x += 220
        }
    }
    $dc.Close()
    $bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new(1200, 660, 96, 96, [Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($dv)
    [IO.File]::WriteAllBytes($PreviewPath, (Get-Png $bitmap))
}
