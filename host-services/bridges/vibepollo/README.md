# Vibepollo Bridge

Vibepollo Bridge is a loopback-only PowerShell service used by Wake & Play Host
Gateway. It exposes health information, allow-listed FIX actions and an
idempotent `POST /apps/ensure` endpoint used to create a Playnite-backed app
without exposing Vibepollo directly to the network. Its small Python transport
is required because it communicates
with the local Vibepollo HTTPS API using a modern TLS stack.

Run `Configure-VibepolloBridge.ps1` as the Windows user that owns the Vibepollo
API token, then use `Start-VibepolloBridge.ps1` and
`Test-VibepolloBridge.ps1`. The token is stored with Windows DPAPI and is not
portable to another Windows profile.

The token must allow `GET` and `POST` on `/api/apps` and `DELETE` on
`/api/apps/*`. Host Control's **Integracje** dialog
uses `moonwaker-token-scopes.example.json` when it creates or renews the token.
Existing apps are matched by Playnite GUID first; legacy exact-name entries are
migrated by sending their complete record so custom commands, images and hooks
are preserved. Records sharing the same non-empty Playnite GUID are migrated
conservatively: the richest record is retained and only the remaining records
with that exact GUID are removed. Apps without a Playnite GUID are never
considered duplicates.

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
