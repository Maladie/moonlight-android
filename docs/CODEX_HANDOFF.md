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

## Latest end-to-end verification

The latest verified session used Steam Big Picture. Real video output was detected before pressing remote Back. Afterwards the game activity remained stopped in the background rather than finishing, the decoder moved to its background surface, and Wake & Play displayed an active streaming session with a return-to-game action.

Do not use the `Sleep PC` entry for automated or manual stream testing. Use `Baba Is You` or `Steam Big Picture` only.

## Build

Run from the repository root:

```powershell
gradle.bat :app:assembleNonRootDebug
```

The non-root debug APK is produced under `app/build/outputs/apk/nonRoot/debug/`.

## Continuation notes

- Preserve the external-frontend contract when touching launch, resume, Back, lifecycle, or surface code.
- Do not restore the old behavior that releases the decoder merely because the window surface is destroyed during a handoff.
- Test activity lifecycle and video-surface behavior together; a successful UI return alone does not prove that the transport survived.
- Validate changes together with Wake & Play because session state and return behavior span both applications.
