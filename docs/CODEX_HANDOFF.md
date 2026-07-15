# Codex handoff

Updated: 2026-07-15

## MoonWaker unified-console milestone 1

Work continues on `feature/moonwaker-unified-console`, created from the clean
documentation HEAD `db5d9266`. The immutable baseline tag was not moved. The
official Moonlight Android repository is configured as `upstream`; `origin`
remains `Maladie/moonlight-android`.

Implemented on this branch:

- `ConsoleActivity` is the single MAIN/LEANBACK launcher and owns one persistent
  five-layer root: stream surface, opaque privacy loader, Home, overlay, and
  modal/recovery. `PcView` remains registered for legacy internal navigation.
- The user-visible debug/release name is `MoonWaker Game App`; release still
  resolves to `com.limelight.unofficial`.
- The first Wake Home vertical slice reads Moonlight's existing saved-host,
  cached-app, poster, and stream-status stores in place. It includes DPAD cards,
  one deterministic initial focus, user-triggered host-to-app focus, an immediate
  FIT_CENTER cached poster preview, an asynchronously prepared backdrop, active
  host/app/session status, Return to Game, and an exit confirmation that does
  not stop the host application.
- `ConsoleStateMachine` covers HOME, CONNECTING, STREAM,
  CONSOLE_OVER_STREAM, OVERLAY, RECOVERY, and DISCONNECTING. Back, input target,
  session reattachment after Activity recreation, and disconnect are explicit.
- Neutral `LaunchOrchestrator` and `LoadingPrivacyGate` boundaries reserve the
  versioned profile/Playnite readiness contract. A managed launch cannot reveal
  on process/first-frame state alone.
- `StreamSessionController` and a non-owning `LegacyGameSessionAdapter` keep
  Disconnect Transport separate from Quit Host Application and prohibit a
  second legacy connection owner.
- `Game` delegates window-surface lifetime policy to `StreamSurfaceHost` and
  gameplay-capture state to the shared `InputRouter`. The real
  `MoonlightStreamSessionController` now constructs, starts, and asynchronously
  stops the sole `NvConnection`. Ordered `prepareRenderer()` and
  `initializeTransport()` phases also move construction of
  `MediaCodecDecoderRenderer` and `AndroidAudioRenderer` behind the controller
  without changing capability negotiation. `Game` remains its listener/view
  adapter. Keyboard, mouse, touch, pen, and controller paths now depend on the
  session-scoped `StreamInputSender`; only the controller's package-private
  adapter sees its owned `NvConnection`. Renderer target changes likewise pass
  through `StreamRenderTargetController`. `ActiveStreamSurfaceBridge` moves the
  same decoder between `Game`, ConsoleActivity's persistent Surface, and the
  existing background fallback without taking session ownership. Structured,
  host-data-free `MoonWakerSession` and `MoonWakerSurface` diagnostics expose a
  process-local session ID/generation, current target, switch counts, and failures
  for the P0 evidence capture.
- Home launches cached apps through the existing `ShortcutTrampoline` with the
  same-package external-frontend contract. The current transitional route is
  ConsoleActivity -> ShortcutTrampoline -> Game -> ConsoleActivity. Back covers
  the live stream with Home, releases Game input capture, and Return to Game
  reveals the existing Game instance without requesting a second connection.

Important: the last bullet is the safe one-APK adapter path, not the final
one-Activity stream. Do not describe the Activity consolidation as complete.
The controller-owned renderer is now bound to `ConsoleActivity`'s persistent
surface through `StreamSurfaceHost`, while `Game` remains the listener/view
compatibility adapter. The next gate is two live P0 Stream -> Home -> Stream
cycles proving changing video, input recovery, one connection, and no surface
destruction. Only after that evidence may the `Game` Activity launch be bypassed.

### Verification

- JDK: `C:\Users\Basia\.jdks\openjdk-17.0.2` (the system Java 24 is not
  compatible with Gradle 8.7/AGP 8.5.1).
- `:app:testNonRootDebugUnitTest`: 31/31 passed; state, privacy readiness, surface
  lifetime, legacy ownership, disconnect/quit separation, input routing, and
  input-boundary initialization plus cross-Activity render-target handoff are
  covered, including diagnostic generation and failed-switch visibility.
- `:app:assembleNonRootDebug`: passed.
- `:app:assembleNonRootRelease`: passed.
- Latest signed surface-telemetry release identity: `com.limelight.unofficial`, certificate SHA-256
  `745d86be25583505b45da74343bd9f868e8f77884fa6e0aaf49fba330b277740`,
  APK SHA-256
  `093c37b50ea5c562551950415a8e6dfb2f71186944e798628220aade51a2dd24`.
  Deliverable: `moonwaker-game-app-surface-telemetry-release.apk`.
  This increment has not been installed yet. The preceding persistent-surface
  APK hash was `e831230f4c3d20d5df025fff0eb466d8db89f51268a0a8ed08b24b29a7c4aa02`.
- The preceding input-boundary release was installed successfully on the BRAVIA
  TV. The user confirmed that the current milestone UI is visible and differs
  from Wake & Play, as expected for the vertical slice. No live stream/surface
  regression evidence has been collected for the new persistent-surface build.
- GitHub CLI account `Maladie` currently reports an invalid keyring token. Do
  not expose a token; refresh authentication before push if ordinary Git
  credentials do not work.

Architecture details, rollback gates, and all hooks outside the console package
are documented in `MOONWAKER_ARCHITECTURE.md`,
`UNIFIED_CONSOLE_REGRESSION_BASELINE.md`, and `UPSTREAM_PATCHES.md`.

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
- Discord voice state is fetched asynchronously while the overlay or pinned
  participant list is visible, at a two-second cadence. Network callbacks
  update Discord state in place and never rebuild or move menu focus.
- Gateway requests use the Wake & Play token plus the pinned leaf-certificate
  fingerprint. The token is never logged, and mutating actions include unique
  request IDs.
- The Discord card is rendered in a separate top-right overlay layer. Stream
  controls remain at the bottom-left. The overlay includes a
  deterministic `Back to Wake & Play` action for external-frontend streams,
  `Rejoin` for the most recent voice channel, and a persistent Pin/Unpin people
  control. A pinned participant list remains visible after the overlay closes
  and continues its two-second asynchronous voice refresh.
- Returning to Wake & Play proactively moves decoder output to the background
  surface before launching the frontend, reducing the surface-destruction race
  that could otherwise stop the connection. The ordinary Back path and the
  explicit overlay action share this implementation.
- During an external-frontend session, Back is always consumed while connecting
  or connected. If Wake & Play is temporarily unavailable, Moonlight shows a
  toast and keeps the stream alive instead of falling through to Activity finish.
- Android TV landscape and fullscreen geometry are applied before content
  inflation. The opaque external loader remains for 650 ms after the first
  decoder frame so unstable initial Surface sizing is not exposed.

## Latest end-to-end verification

The latest verified session used Baba Is You. Real video output was detected before
pressing remote Back. Two consecutive Back -> Wake & Play -> Return to game cycles
left the game activity stopped in the background rather than finishing, moved the
decoder to its background surface, and preserved Wake & Play's active streaming
session and return-to-game action.

On 2026-07-15 the external launch was additionally recorded with Android TV's
`screenrecord`. The visible startup zoom was isolated to the Activity/task surface
handoff rather than decoder output: the 1920x1080 loader was briefly composed inside
smaller task bounds before returning to fullscreen. Direct single-Activity launch,
`singleTop`/`REORDER_TO_FRONT` reuse, and keeping the trampoline alive until the Game
launch reduced the artifact from roughly 0.7 seconds to one sampled frame (about
0.25 seconds), but did not remove it completely. Eliminating the last compositor
frame would require a larger refactor that runs host negotiation and stream rendering
in one Activity.

The same build was verified with a live Baba Is You session: Wake & Play covered the
stream, Moonlight kept the Game Activity alive, and the public `RETURN_STREAM` intent
restored real video output. Do not revert Game to a separate `singleTask` solely to
change launch animation without rechecking this lifecycle.

Do not use the `Sleep PC` entry for automated or manual stream testing. Use `Baba Is You` or `Steam Big Picture` only.

## Approved next task: Playnite readiness gate

No Playnite-specific Moonlight code has been implemented yet. The approved next
stage keeps the existing opaque `ExternalFrontendLoadingView` visible after the
first decoded frame until a profile-scoped host Console Bridge also reports that
the requested Playnite game window is foreground, correctly sized on the
streamed display and stable. The first frame remains a required signal, but is
no longer sufficient by itself for Playnite-managed launches.

The gate needs a bounded timeout and controller-operable recovery actions rather
than an unconditional reveal. The same privacy surface should be reusable when
a game exits: cover any desktop transition, wait until Playnite Fullscreen is
stable again, then reveal it without disconnecting or recreating the stream.

Add a non-destructive `Return to Playnite` overlay action through the existing
certificate-pinned Gateway contract. It must not call the current quit-app path,
which sets `pendingApplicationQuit`, stops the connection and finishes Game.
Preserve the external-frontend background surface and Back behavior throughout
this work.

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
- Discord actions now live with the top-right Discord card instead of extending
  the crowded bottom tool strip. The card owns a 2x2 action grid (Mute, Leave,
  Rejoin, Pin), while the separately rendered participant layer is reserved for
  the pinned view after the overlay closes. From the main vertical tool column,
  DPAD Right enters Discord directly and DPAD Left returns to the main column;
  users no longer need to traverse the full bottom strip. Asynchronous participant
  refresh only rebuilds the non-focusable content container and does not recreate
  the action buttons.
- `Back to Wake & Play` now uses the overlay gamepad icon. Remote/gamepad Back
  behavior was deliberately left unchanged after the user accepted its remaining
  device-specific instability.
- The Discord layout and navigation build was installed and stream lifecycle was
  exercised, but ADB could not synthesize the configured long-Select overlay gesture;
  final visual/focus verification of the open overlay still needs a physical remote
  or controller pass.
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
