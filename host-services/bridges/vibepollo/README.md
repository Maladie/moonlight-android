# Vibepollo Bridge

Vibepollo Bridge is a loopback-only PowerShell service used by Wake & Play Host
Gateway. It exposes health information, allow-listed FIX actions and keeps the
single neutral `MoonWaker Stream` application every game start uses, without
exposing Vibepollo directly to the network. It creates no per-game applications. Its small Python transport
is required because it communicates
with the local Vibepollo HTTPS API using a modern TLS stack.

Run `Configure-VibepolloBridge.ps1` as the Windows user that owns the Vibepollo
API token, then use `Start-VibepolloBridge.ps1` and
`Test-VibepolloBridge.ps1`. The token is stored with Windows DPAPI and is not
portable to another Windows profile.

The token must allow `GET` and `POST` on `/api/apps`; the Bridge deletes no
streamer application. Host Control's **Integracje** dialog uses `moonwaker-token-scopes.example.json` when it creates or renews the token.

`POST /pair` is the loopback-only half of MoonWaker's unified host pairing flow.
It submits the pending Moonlight PIN to Vibepollo, identifies the newly paired
client and grants only the standard gameplay permissions: list applications,
view streams, launch applications and controller/touch/pen/mouse/keyboard input.
It intentionally does not grant clipboard, file-transfer or server-command
permissions. The Bridge token therefore also requires `POST /api/pin` and
`POST /api/clients/update`.

Use one instance per profile when credentials or runtime state differ, and give
each concurrently installed instance a distinct loopback port. Never commit
`api_token.dpapi`, `config.json`, logs or exported diagnostics.

`GET /diagnostics/active-displays` returns fresh local display enumeration only.
Each display carries `stream_virtual_display`, true when its adapter is one of
the virtual display drivers Vibepollo ships (`Root\SunshineVirtualDisplay`,
`root\sudomaker\sudovda`).
The Game Provider readiness poll uses this bounded endpoint: it picks the single
active display marked `stream_virtual_display`, or, when none is marked, the
single active display; any other topology yields no display. The richer
`/diagnostics/stream-sources` endpoint remains available for diagnostics and
may perform authenticated Vibepollo API calls.
