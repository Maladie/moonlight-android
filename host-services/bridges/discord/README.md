# Discord Bridge

Discord Bridge is a loopback-only PowerShell service used by Wake & Play Host
Gateway. It translates a deliberately limited HTTP API into Discord desktop RPC
commands and also exposes the audio controls consumed by the Gateway.

It is an optional component: only `Build-HostServices.ps1 -WithDiscord` packages this
directory (installed as `bridge-source\discord`) and the Gateway's
`MoonWakerDiscordAudioWorker.exe`. Without it Host Control offers no Discord settings
and the Gateway reports Discord as unavailable.

Host Control's tray runs it from the installation with `-ProfileDirectory
<install>\profiles\<id>\discord` once that profile is configured (Host Control's
**Integracje Discorda…**, where **Odłącz** removes the profile's client secret and OAuth
token again); the directory holds only its configuration, DPAPI-protected client
secret and OAuth token, state and logs. `/repair-bridge` and `/shutdown` end the
process and the tray starts it again. `Test-DiscordBridge.ps1 -ProfileDirectory ...`
calls a running instance.

One instance is required per Windows/Discord profile. Give every concurrently
installed profile a distinct loopback port. Discord desktop itself can only own
one machine-global RPC endpoint at a time, so fully exit it in the previous
profile before changing the active Discord profile.

Never bind this service outside loopback and never commit `*.dpapi`, runtime
configuration, state, logs or diagnostics.
