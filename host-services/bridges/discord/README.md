# Discord Bridge

Discord Bridge is a loopback-only PowerShell service used by Wake & Play Host
Gateway. It translates a deliberately limited HTTP API into Discord desktop RPC
commands and also exposes the audio and VirtualHere controls consumed by the
Gateway.

Run `Configure-DiscordBridge.ps1` as the Windows user whose Discord client will
be controlled. The client secret and OAuth token are protected with that user's
DPAPI credentials. Start and test the service with `Start-DiscordBridge.ps1`
and `Test-DiscordBridge.ps1`.

One instance is required per Windows/Discord profile. Give every concurrently
installed profile a distinct loopback port. Discord desktop itself can only own
one machine-global RPC endpoint at a time, so fully exit it in the previous
profile before changing the active Discord profile.

Never bind this service outside loopback and never commit `*.dpapi`, runtime
configuration, state, logs or diagnostics.
