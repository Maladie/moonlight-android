$path = Join-Path $PSScriptRoot "DiscordBridge.ps1"

Start-Process `
    -FilePath "powershell.exe" `
    -WindowStyle Hidden `
    -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$path`""
