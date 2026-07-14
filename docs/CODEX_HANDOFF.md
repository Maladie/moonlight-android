# Codex handoff

Updated: 2026-07-14

## Repository

- Project: Moonlight Android
- Branch: `feature/android-tv-session-controls`
- Push remote: `fork` (`Maladie/moonlight-android`)
- Upstream remote: `origin` (`MoreOrLessSoftware/moonlight-android`)
- Companion project: Wake & Play for Android TV, branch `main`

## Implemented behavior

- External-frontend session metadata is persisted and reattached across launches and returns.
- Remote Back is handled at the activity boundary and returns to Wake & Play without intentionally stopping the stream transport.
- When the stream activity loses its window surface during external-frontend handoff, decoding moves to a background surface.
- The decoder records the background surface even if decoder creation has not completed yet, preventing a later bind to a destroyed window surface.
- Backgrounding the game activity preserves the external-frontend stream while the activity is connecting or connected.
- Stream state extras are persisted through `SessionResumeManager` for the Wake & Play session panel.
- Fullscreen geometry is applied before content in the shortcut trampoline and game activity.
- Cross-application launches request no window animation to reduce visible scaling and flashes.
- Wake & Play can pass its host-scoped, certificate-pinned Host Gateway
  connection through the explicit stream contract. Moonlight retains this
  private connection across session resume and refreshes it when an active
  external-frontend stream is brought back to the foreground. The stable
  Discord profile ID is propagated separately for the future Gateway profile
  registry.
- When a Gateway connection is present, the streaming overlay shows a compact
  Discord card with the current voice channel, speaking markers, participants,
  local mute state and per-user local volume/mute information.
- Discord Mute/Unmute and Leave Channel are first-class overlay actions. Their
  controller shortcuts are configurable under Overlay menu settings (X and Y
  by default); duplicate shortcuts are resolved by disabling Leave rather than
  firing two actions. Shortcuts are active only while the overlay is open, so
  normal gameplay input is not intercepted.
- Discord voice state is fetched asynchronously only while the overlay is
  visible, at a two-second cadence. Network callbacks update the Discord card
  in place and never rebuild or move menu focus.
- Gateway requests use the Wake & Play token plus the pinned leaf-certificate
  fingerprint. The token is never logged, and mutating actions include unique
  request IDs.

## Latest end-to-end verification

The latest verified session used Steam Big Picture. Real video output was detected before pressing remote Back. Afterwards the game activity remained stopped in the background rather than finishing, the decoder moved to its background surface, and Wake & Play displayed an active streaming session with a return-to-game action.

Do not use the `Sleep PC` entry for automated or manual stream testing. Use `Baba Is You` or `Steam Big Picture` only.

## Build

Run from the repository root:

```powershell
.\gradlew.bat :app:assembleNonRootDebug
```

The non-root debug APK is produced under `app/build/outputs/apk/nonRoot/debug/`.

## Continuation notes

- Preserve the external-frontend contract when touching launch, resume, Back, lifecycle, or surface code.
- Do not restore the old behavior that releases the decoder merely because the window surface is destroyed during a handoff.
- Test activity lifecycle and video-surface behavior together; a successful UI return alone does not prove that the transport survived.
- Validate changes together with Wake & Play because session state and return behavior span both applications.
- The Discord overlay has not yet been exercised during a live stream because
  stream testing was not authorized for this iteration. Java/resource
  compilation and `assembleNonRootDebug` passed locally. The APK deliberately
  reused the existing, previously built native `.so` artifacts; the checkout's
  configured `moonlight-common-c` gitlink
  `ba7b4c8dabf1aeb346dec63866b9ec45c33568eb` is no longer available from its
  configured remote, so a clean NDK rebuild remains unavailable. Do not silently
  substitute a newer native core.
- `lintNonRootDebug` currently reports seven pre-existing errors outside the
  Discord changes (API-21 compatibility and broadcast receiver flags). The
  Discord Gateway client only triggered certificate-pinning false positives,
  which are locally suppressed with an explicit explanation in its design.
