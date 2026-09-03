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
$instanceFlags = [Reflection.BindingFlags]::Instance -bor [Reflection.BindingFlags]::NonPublic
try {
    if ($form.AutoScaleMode -ne [Windows.Forms.AutoScaleMode]::Dpi) {
        throw "Installer form is not configured for DPI scaling."
    }
    $form.ShowInTaskbar = $false
    $form.StartPosition = [Windows.Forms.FormStartPosition]::Manual
    $form.Location = [Drawing.Point]::new(-32000, -32000)
    $form.Show()
    [Windows.Forms.Application]::DoEvents()

    $pages = $formType.GetField("pages", $instanceFlags).GetValue($form)
    $steps = $formType.GetField("stepLabels", $instanceFlags).GetValue($form)
    if ($pages.Count -ne 5 -or $steps.Count -ne 5) {
        throw "Installer must expose exactly five guided steps."
    }
    $showPage = $formType.GetMethod("ShowPage", $instanceFlags)
    $setLanguage = $formType.GetMethod("SetLanguage", $instanceFlags)
    if ($null -eq $showPage -or $null -eq $setLanguage) {
        throw "Wizard navigation methods are missing."
    }

    $setLanguage.Invoke($form, @(1)) | Out-Null
    [Windows.Forms.Application]::DoEvents()
    if (-not $form.Text.StartsWith("Instalator MoonWaker Host") -or
        $steps[1].Text -ne "Sprawdzenie systemu") {
        throw "Polish localization was not applied to the wizard shell."
    }
    $setLanguage.Invoke($form, @(0)) | Out-Null
    [Windows.Forms.Application]::DoEvents()
    if (-not $form.Text.StartsWith("MoonWaker Host Installer") -or
        $steps[1].Text -ne "System check") {
        throw "English localization was not applied to the wizard shell."
    }

    $automaticToken = $formType.GetField("createVibepolloToken", $instanceFlags).GetValue($form)
    $admin = $formType.GetField("vibepolloAdmin", $instanceFlags).GetValue($form)
    $token = $formType.GetField("vibepolloToken", $instanceFlags).GetValue($form)
    $automaticToken.Checked = $true
    [Windows.Forms.Application]::DoEvents()
    if (-not $admin.Enabled -or $token.Enabled) {
        throw "Automatic token mode did not select administrator credentials."
    }
    $automaticToken.Checked = $false
    [Windows.Forms.Application]::DoEvents()
    if ($admin.Enabled -or -not $token.Enabled) {
        throw "Existing token mode did not select the token field."
    }

    $discord = $formType.GetField("discord", $instanceFlags).GetValue($form)
    $discordCredentials = $formType.GetField("discordCredentials", $instanceFlags).GetValue($form)
    $discordCard = $formType.GetField("discordCard", $instanceFlags).GetValue($form)
    $showPage.Invoke($form, @(3)) | Out-Null
    [Windows.Forms.Application]::DoEvents()
    $discord.Checked = $false
    [Windows.Forms.Application]::DoEvents()
    if ($discordCredentials.Visible -or $discordCard.Height -gt 80) {
        throw "Optional Discord settings did not collapse."
    }
    $discord.Checked = $true
    [Windows.Forms.Application]::DoEvents()
    if (-not $discordCredentials.Visible -or $discordCard.Height -lt 150) {
        throw "Optional Discord settings did not expand."
    }

    $showPage.Invoke($form, @(1)) | Out-Null
    $firmwarePanel = $formType.GetField("firmwarePanel", $instanceFlags).GetValue($form)
    $updateSystemPageLayout = $formType.GetMethod("UpdateSystemPageLayout", $instanceFlags)
    $firmwarePanel.Visible = $true
    $updateSystemPageLayout.Invoke($form, @()) | Out-Null
    [Windows.Forms.Application]::DoEvents()

    $allControls = @(Get-Descendants $form)
    $passwordBoxes = @($allControls | Where-Object {
        $_ -is [Windows.Forms.TextBox] -and $_.UseSystemPasswordChar
    })
    if ($passwordBoxes.Count -lt 3) {
        throw "Sensitive Vibepollo and Discord fields are not masked."
    }

    $resolvedPreview = if ([string]::IsNullOrWhiteSpace($PreviewDirectory)) { "" } else {
        [IO.Path]::GetFullPath($PreviewDirectory)
    }
    if ($resolvedPreview) { New-Item -ItemType Directory -Path $resolvedPreview -Force | Out-Null }
    for ($index = 0; $index -lt $pages.Count; $index++) {
        $showPage.Invoke($form, @($index)) | Out-Null
        [Windows.Forms.Application]::DoEvents()
        $visiblePages = @($pages | Where-Object { $_.Visible })
        if ($visiblePages.Count -ne 1 -or $visiblePages[0] -ne $pages[$index]) {
            throw "Wizard page $index is not the sole visible page."
        }
        if ($pages[$index].HorizontalScroll.Visible) {
            throw "Wizard page $index requires horizontal scrolling at default size."
        }
        if ($pages[$index].VerticalScroll.Visible) {
            throw "Wizard page $index requires vertical scrolling at default size."
        }
        if ($resolvedPreview) {
            Save-Preview $form (Join-Path $resolvedPreview ("installer-step-{0}.png" -f ($index + 1)))
        }
    }

    Write-Output "MoonWaker Host Installer UI test passed."
} finally {
    $form.Close()
    $form.Dispose()
}
