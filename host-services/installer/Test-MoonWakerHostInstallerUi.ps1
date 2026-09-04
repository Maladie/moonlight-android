#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallerPath,
    [string]$PreviewDirectory = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.IO.Compression

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
$payloadStream = $assembly.GetManifestResourceStream("MoonWaker.HostServices.zip")
if ($null -eq $payloadStream) { throw "Installer payload resource is missing." }
try {
    $payload = [IO.Compression.ZipArchive]::new(
        $payloadStream, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $payloadNames = @($payload.Entries | ForEach-Object { $_.FullName })
        if ($payloadNames -contains "host-services/install/Install-MoonWakerHostBundle.ps1") {
            throw "The GUI installer still embeds the legacy profile-owning bundle entry point."
        }
        if ($payloadNames -notcontains "host-services/install/Install-WakePlayProfile.ps1") {
            throw "Host Control profile provisioning script is missing from the installer payload."
        }
        foreach ($requiredMachineEntry in @(
            "host-services/install/Install-WakePlayHost.ps1",
            "host-services/install/Invoke-MoonWakerMachineInstall.ps1",
            "host-services/install/Prepare-MoonWakerHost.ps1",
            "host-services/install/Uninstall-MoonWakerHostServices.ps1",
            "host-services/gateway/Stop-MoonWakerGateway.ps1",
            "host-services/gateway/Stop-MoonWakerGatewayWorkers.ps1",
            "host-services/gateway/MoonWakerGatewayService.exe",
            "host-services/windows-login/login-broker/MoonWakerLoginBroker.exe",
            "host-services/windows-login/credential-provider/MoonWakerCredentialProvider.dll",
            "host-services/windows-login/login-broker/Remove-MoonWakerLoginCredentials.ps1"
        )) {
            if ($payloadNames -notcontains $requiredMachineEntry) {
                throw "Trusted machine installer payload is missing: $requiredMachineEntry"
            }
        }
    } finally { $payload.Dispose() }
} finally { $payloadStream.Dispose() }
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

    foreach ($fieldName in @("profileId", "profileName", "discord", "discordId", "discordSecret",
            "vibepolloToken", "createVibepolloToken", "vibepolloAdmin", "vibepolloPassword")) {
        if ($null -ne $formType.GetField($fieldName, $instanceFlags)) {
            throw "Installer still owns profile setup field '$fieldName'."
        }
    }

    if ($null -ne $formType.GetField("launchHostControl", $instanceFlags)) {
        throw "The elevated installer must not automatically activate Host Control through per-user COM."
    }
    $uninstall = $formType.GetField("uninstall", $instanceFlags).GetValue($form)
    if ($null -eq $uninstall) { throw "Installer uninstall action is missing." }

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
    if ($passwordBoxes.Count -ne 0) {
        throw "The installer must not collect a Vibepollo bootstrap password."
    }
    $hostControlGuidance = @($allControls | Where-Object {
        $_ -is [Windows.Forms.Label] -and $_.Text -like "*Host Control*"
    })
    if ($hostControlGuidance.Count -eq 0) {
        throw "The installer does not direct profile setup to Host Control."
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
