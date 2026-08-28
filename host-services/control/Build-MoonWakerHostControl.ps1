#requires -Version 5.1
[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path $PSScriptRoot "dist"))
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) { $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path -LiteralPath $compiler)) { throw ".NET Framework C# compiler was not found." }
$output = Join-Path $OutputDirectory "MoonWakerHostControl.exe"

function New-MoonWakerIcon([string]$Path) {
    Add-Type -AssemblyName System.Drawing
    $bitmap = [Drawing.Bitmap]::new(32, 32)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $body = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(116, 100, 255))
    $detail = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(240, 242, 255))
    $shape = [Drawing.Drawing2D.GraphicsPath]::new()
    try {
        $graphics.Clear([Drawing.Color]::Fuchsia)
        $points = [Drawing.Point[]]@(
            [Drawing.Point]::new(3, 15), [Drawing.Point]::new(7, 9), [Drawing.Point]::new(11, 9),
            [Drawing.Point]::new(14, 11), [Drawing.Point]::new(18, 11), [Drawing.Point]::new(21, 9),
            [Drawing.Point]::new(25, 9), [Drawing.Point]::new(29, 15), [Drawing.Point]::new(27, 22),
            [Drawing.Point]::new(23, 24), [Drawing.Point]::new(20, 19), [Drawing.Point]::new(12, 19),
            [Drawing.Point]::new(9, 24), [Drawing.Point]::new(5, 22))
        $graphics.FillPolygon($body, $points)
        $graphics.FillRectangle($detail, 8, 14, 7, 2)
        $graphics.FillRectangle($detail, 10, 12, 2, 6)
        $graphics.FillRectangle($detail, 21, 13, 3, 3)
        $graphics.FillRectangle($detail, 25, 17, 3, 3)
        $bitmap.MakeTransparent([Drawing.Color]::Fuchsia)
        $icon = [Drawing.Icon]::FromHandle($bitmap.GetHicon())
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Create)
        try { $icon.Save($stream) } finally { $stream.Dispose(); $icon.Dispose() }
    } finally {
        $shape.Dispose(); $detail.Dispose(); $body.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
    }
}

$iconPath = Join-Path $OutputDirectory "MoonWakerHostControl.ico"
New-MoonWakerIcon $iconPath
$compilerArguments = @(
    "/nologo", "/target:winexe", "/optimize+", "/out:$output", "/win32icon:$iconPath",
    "/win32manifest:$(Join-Path $PSScriptRoot 'MoonWakerHostControl.manifest')",
    "/reference:System.dll", "/reference:System.Core.dll", "/reference:System.Drawing.dll",
    "/reference:System.Windows.Forms.dll", "/reference:System.Web.Extensions.dll",
    "/reference:System.Security.dll",
    (Join-Path $PSScriptRoot "MoonWakerHostControl.cs"))
& $compiler @compilerArguments
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output)) { throw "MoonWaker Host Control compilation failed." }

if ($false) {
& $compiler /nologo /target:winexe /optimize+ "/out:$output" `
    "/win32manifest:$(Join-Path $PSScriptRoot 'MoonWakerHostControl.manifest')" `
    /reference:System.dll /reference:System.Core.dll /reference:System.Drawing.dll `
    /reference:System.Windows.Forms.dll /reference:System.Web.Extensions.dll /reference:System.Security.dll `
    (Join-Path $PSScriptRoot "MoonWakerHostControl.cs")
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output)) { throw "MoonWaker Host Control compilation failed." }
Get-Item -LiteralPath $output
}
Remove-Item -LiteralPath $iconPath -Force -ErrorAction SilentlyContinue
Get-Item -LiteralPath $output
