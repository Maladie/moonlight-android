# Codex handoff

Updated: 2026-07-14

## Repository

- Project: Moonlight Android
- Branch: `feature/android-tv-session-controls`
- Push remote: `origin` (`Maladie/moonlight-android`)
- No separate upstream remote is currently configured in this checkout.
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
  integration profile ID is propagated separately and sent to Gateway as
  `X-WakePlay-Profile`, keeping the overlay on the profile selected in Wake &
  Play.
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

For updates to the TV installation used by Wake & Play, build
`:app:assembleNonRootRelease`. The debug build has application ID
`com.limelight.debug`, while Wake & Play's existing host/session data and the
active TV task use `com.limelight.unofficial`. The release APK is unsigned and
must be aligned and signed with the same historical development certificate as
the installed package before `adb install -r`.

## Continuation notes

- Preserve the external-frontend contract when touching launch, resume, Back, lifecycle, or surface code.
- Do not restore the old behavior that releases the decoder merely because the window surface is destroyed during a handoff.
- Test activity lifecycle and video-surface behavior together; a successful UI return alone does not prove that the transport survived.
- Validate changes together with Wake & Play because session state and return behavior span both applications.
- The Discord overlay has not yet been exercised during a live stream because
  stream testing was not authorized for this iteration.
- The profile-aware `com.limelight.unofficial` release was built, signed with
  the matching installed certificate and installed on the TV. The earlier
  report of missing Discord buttons came from the active
  `com.limelight.unofficial` task while the overlay changes had only been
  installed as the separate `com.limelight.debug` package. A new stream was
  deliberately not started, so live button/voice-state verification remains.
- The inaccessible `moonlight-common-c` gitlink
  `ba7b4c8dabf1aeb346dec63866b9ec45c33568eb` was replaced with the public
  official revision `7b026e77be62175104640e7e722b758df6d3d0d7`. This revision
  retains the microsecond timestamp contract expected by the current JNI layer,
  uses nanors/SIMDe FEC, and includes later upstream fixes. A clean NDK build
  succeeded for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`, followed by a
  successful `assembleNonRootDebug`.
- Vibepollo controls in Wake & Play use the profile-scoped Vibepollo Bridge and
  Gateway API, not Artemis-specific native Control Stream extensions. Vibepollo
  remains compatible with this official core through the standard streaming
  protocol. If Apollo-only client protocol features are added later, port the
  narrowly required Artemis commits onto the official core rather than replacing
  the core wholesale.
- `lintNonRootDebug` currently reports seven pre-existing errors outside the
  Discord changes (API-21 compatibility and broadcast receiver flags). The
  Discord Gateway client only triggered certificate-pinning false positives,
  which are locally suppressed with an explicit explanation in its design.
