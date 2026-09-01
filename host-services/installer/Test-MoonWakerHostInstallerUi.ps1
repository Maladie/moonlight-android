#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallerPath,
    [string]$PreviewDirectory = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

function Get-Descendants([Windows.Forms.Control]$Parent) {
    foreach ($child in $Parent.Controls) {
        $child
        foreach ($descendant in Get-Descendants $child) { $descendant }
    }
}

function Save-Preview([Windows.Forms.Form]$Form, [string]$Path) {
    $bitmap = [Drawing.Bitmap]::new($Form.Width, $Form.Height)
    try {
        $Form.DrawToBitmap($bitmap, [Drawing.Rectangle]::new(0, 0, $bitmap.Width, $bitmap.Height))
        $bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
    } finally { $bitmap.Dispose() }
}

$resolvedInstaller = (Resolve-Path -LiteralPath $InstallerPath).Path
$assembly = [Reflection.Assembly]::LoadFile($resolvedInstaller)
$formType = $assembly.GetType("MoonWaker.HostInstaller.InstallerForm", $true)
$form = [Activator]::CreateInstance($formType, $true)
$flags = [Reflection.BindingFlags]::Instance -bor [Reflection.BindingFlags]::NonPublic
try {
    if ($form.AutoScaleMode -ne [Windows.Forms.AutoScaleMode]::Dpi) {
        throw "Installer form is not configured for DPI scaling."
    }
    $form.ShowInTaskbar = $false
    $form.StartPosition = [Windows.Forms.FormStartPosition]::Manual
    $form.Location = [Drawing.Point]::new(-32000, -32000)
    $form.Show()
    [Windows.Forms.Application]::DoEvents()

    $controls = @(Get-Descendants $form)
    $obsolete = @($controls | Where-Object {
        $_ -is [Windows.Forms.CheckBox] -and
        $_.Text -in @("Vibepollo Bridge", "Game Provider Bridge")
    })
    if ($obsolete.Count) { throw "Obsolete required-Bridge checkboxes are still visible." }

    $discord = $formType.GetField("discord", $flags).GetValue($form)
    $credentials = $formType.GetField("discordCredentials", $flags).GetValue($form)
    $discordCard = $formType.GetField("discordCard", $flags).GetValue($form)
    $discord.Checked = $false
    [Windows.Forms.Application]::DoEvents()
    if ($credentials.Visible -or $discordCard.Height -gt 80) {
        throw "Discord credentials did not collapse after Discord Bridge was disabled."
    }
    $discord.Checked = $true
    [Windows.Forms.Application]::DoEvents()
    if (-not $credentials.Visible -or $discordCard.Height -lt 150) {
        throw "Discord credentials did not return after Discord Bridge was enabled."
    }

    $status = $formType.GetField("installationStatus", $flags).GetValue($form)
    $status.Text = "Detected shared components v0.7.56; this installer contains v0.7.57. " +
        "Updating shared components is required and will preserve existing profiles. " +
        "The Vibepollo token for this profile will be preserved when its field is empty. " +
        "To enable automatic client pairing and game permissions, select automatic token " +
        "creation and enter the Vibepollo administrator credentials."
    $preferred = $status.GetPreferredSize([Drawing.Size]::new($status.Width, 0))
    if ($preferred.Height -gt $status.Height) {
        throw "Installation status text needs $($preferred.Height) px but has $($status.Height) px."
    }

    $content = @($form.Controls | Where-Object { $_ -is [Windows.Forms.FlowLayoutPanel] })[0]
    if ($content.HorizontalScroll.Visible) {
        throw "Installer content requires horizontal scrolling at its default size."
    }

    if (-not [string]::IsNullOrWhiteSpace($PreviewDirectory)) {
        $resolvedPreview = [IO.Path]::GetFullPath($PreviewDirectory)
        New-Item -ItemType Directory -Path $resolvedPreview -Force | Out-Null
        $content.AutoScrollPosition = [Drawing.Point]::Empty
        [Windows.Forms.Application]::DoEvents()
        Save-Preview $form (Join-Path $resolvedPreview "installer-ui-top.png")
        $content.AutoScrollPosition = [Drawing.Point]::new(0, $content.VerticalScroll.Maximum)
        [Windows.Forms.Application]::DoEvents()
        Save-Preview $form (Join-Path $resolvedPreview "installer-ui-bottom.png")
    }

    Write-Output "MoonWaker Host Installer UI test passed."
} finally {
    $form.Close()
    $form.Dispose()
}
