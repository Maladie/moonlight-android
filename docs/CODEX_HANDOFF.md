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
- Home now remembers the selected host and the selected app per host without
  allowing asynchronous refreshes to steal focus. Poster/backdrop request
  cancellation is owned by `ConsoleArtworkController`, while Wake-style glass
  card focus, pressed, stroke, elevation, and scale states are centralized in
  `ConsoleTheme`. Modal composition and focus restoration are likewise isolated
  in `ConsoleModalController` instead of accumulating in `ConsoleActivity`.
- Host-scoped Gateway connections use Wake's existing preference schema and
  validate HTTPS endpoints, non-empty bearer tokens, pinned SHA-256 leaf
  certificates, and stable profile IDs before entering the private launch
  contract. Home exposes an offline Host Integrations panel with independent
  Discord, Vibepollo, and VirtualHere status per profile. No token or certificate
  value is rendered, logged, or included in diagnostic summaries.
- The placeholder stream-overlay label has been replaced by a Wake-style,
  controller-only layout with explicit DPAD routes between stream controls and
  the Discord/host-services region. Its status is profile scoped and uses the
  same secret-free presentation model as Home.
- Opening Host Integrations is the only action that starts a read-only profile
  refresh. It reuses the existing certificate-pinned Gateway transport for
  `/api/v1/profiles`; latest-only delivery, panel dismissal, Activity teardown,
  malformed profile records, and unavailable responses are fail-safe. A
  controller profile chooser persists the selected host-scoped ID without an
  asynchronous focus jump. No background Home polling was added.
- Wake Home parity now includes the original generative backdrop, artwork/scrim
  geometry, quick actions, selected-host styling, 300x110 application cards,
  controller inventory and battery state, active-session panel, streaming
  options entry, Wake-compatible launch history labels, and recent-first app
  ordering. Session polling runs only while Home is visible and never rebuilds
  focusable rows.
- Saved host reads now retain port and MAC data. Home performs a latest-only,
  lifecycle-cancelled port probe and renders Wake's CHECKING, ONLINE, SLEEPING,
  OFFLINE, and THIS TV states. App launch uses the bounded Wake sequence: probe
  every 1.2 seconds, send a tested magic packet at most every 5 seconds, stop at
  90 seconds, and enter the existing protected stream contract only after the
  host answers. Back cancels preparation; timeout offers controller-native Retry
  or Stay on Home.
- `ConsoleStateMachine` covers HOME, CONNECTING, STREAM,
  CONSOLE_OVER_STREAM, OVERLAY, RECOVERY, and DISCONNECTING. Back, input target,
  session reattachment after Activity recreation, and disconnect are explicit.
- Neutral `LaunchOrchestrator` and `LoadingPrivacyGate` boundaries reserve the
  versioned profile/Playnite readiness contract. A managed launch cannot reveal
  on process/first-frame state alone. Console launches now arm this gate in
  production. Until the profile Bridge supplies window-readiness samples, the
  opaque loader times out after 12 seconds to a controller-operable recovery
  with Retry, Home, Reveal Stream, and Disconnect. Home receives initial focus,
  Reveal remains disabled before the first decoded frame, and Disconnect never
  quits the host application.
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
  The same-package return uses `REORDER_TO_FRONT` in the existing task and never
  adds `NEW_TASK`; a pure lifecycle policy also treats Console's bound renderer
  target as a valid alternate surface during the handoff.
- The inactive one-Activity replacement path now has validated
  `StreamLaunchParameters`, saved-host resolution through
  `ComputerManagerService`, stale-result cancellation, a staged unified launch
  pipeline, a single-session runtime core, transport callback routing, and a
  `ConsoleStreamRuntime` adapter. Decoder/HDR/codec selection, display pacing,
  controller masks, client refresh rate, bitrate bounds, preference precedence,
  and final `StreamConfiguration` assembly are shared with `Game`. Cached app
  HDR capability is preserved through both launch paths. These components are
  intentionally not selected by `ConsoleActivity` yet.

Important: the transitional `ShortcutTrampoline`/`Game` bullet is the selected
safe one-APK adapter path, not the final one-Activity stream. Do not describe
the Activity consolidation as complete.
The controller-owned renderer is now bound to `ConsoleActivity`'s persistent
surface through `StreamSurfaceHost`, while `Game` remains the selected
listener/view compatibility adapter. Complete the Android renderer/input
environment for `MoonlightConsoleResolvedStreamRuntime`, then perform two live
P0 Stream -> Home -> Stream cycles proving changing video, input recovery, one
connection, audio, and no surface destruction. Only after that evidence may the
runtime selector bypass the `Game` Activity launch.

### Verification

- JDK: `C:\Users\Basia\.jdks\openjdk-17.0.2` (the system Java 24 is not
  compatible with Gradle 8.7/AGP 8.5.1).
- `:app:testNonRootDebugUnitTest`: 167/167 passed; state, privacy readiness, surface
  lifetime, legacy ownership, disconnect/quit separation, input routing, and
  input-boundary initialization plus cross-Activity render-target handoff are
  covered, including diagnostic generation, failed-switch visibility, Gateway
  validation/storage, focus memory, artwork request ordering, Wake card design
  tokens, profile-scoped integration health, immutable Home/session models,
  resume/layer policies, the isolated legacy launch contract, pinned profile
  conversion, latest-only Gateway refresh cancellation, controller/history
  presentation, host availability, WOL packet construction, bounded wake timing,
  cancellable host preparation, launch resolution, unified pipeline/runtime
  cancellation, configuration planning, codec/HDR negotiation, frame pacing,
  gamepad masks, refresh parsing, bitrate bounds, transport event routing,
  display HDR eligibility, and renderer/transport input boundaries.
- `:app:compileNonRootDebugJavaWithJavac` and the unit-test task passed after
  `3339ca61`. The most recent full `:app:assembleNonRootDebug` passed earlier in
  the same series at `7a0d4706`; full builds are intentionally grouped rather
  than run after every local refactor.
- `:app:assembleNonRootRelease`: passed.
- Latest signed privacy-recovery release identity: `com.limelight.unofficial`, certificate SHA-256
  `745d86be25583505b45da74343bd9f868e8f77884fa6e0aaf49fba330b277740`,
  APK SHA-256
  `2b7e62127645d6a671dfa5d3eef1e2e5c14fc818afd4a7775cf25f582e3bb829`.
  Deliverable: `moonwaker-game-app-privacy-recovery-release.apk`.
  This increment was installed successfully on the BRAVIA TV without launching
  a host application. The preceding surface-telemetry APK hash was
  `093c37b50ea5c562551950415a8e6dfb2f71186944e798628220aade51a2dd24`.
- The preceding input-boundary release was installed successfully on the BRAVIA
  TV. The user confirmed that the current milestone UI is visible and differs
  from Wake & Play, as expected for the vertical slice. No live stream/surface
  regression evidence has been collected for the new persistent-surface build.
- The installed surface-telemetry release passed a Home-only cold-start smoke
  test (`ConsoleActivity`, 978 ms, process remained alive). Its scoped log
  reported `console_surface_registered generation=0 attached=false target=NONE`
  with zero target failures. No host application or stream was launched.
- The first live persistent-surface P0 attempt on 2026-07-15 was deliberately
  aborted after the first decoded frame exposed an existing desktop instead of
  the requested application. The temporary evidence image was deleted. Scoped
  diagnostics then proved `GAME -> CONSOLE` target handoff with zero target
  failures, but the legacy Activity lifecycle immediately disconnected the
  transport. Commit `42c854ef` fixes both defects: managed Console launches now
  remain opaque until Bridge readiness or explicit recovery approval, and the
  same-package return no longer creates/relaunches an Android task. A fresh
  signed install and two live P0 cycles are still required; do not mark section D
  complete from unit tests alone.
- Home-only verification also covered the Sony/Google TV dream overlay. Console's
  inactive surface is safely released while the dream owns the display and is
  registered again on wake. `ConsoleActivity` now mirrors actual window focus
  into `ActiveStreamSurfaceBridge`, covering TV firmware that restores the window
  without a matching `onResume()` callback.
- The final privacy-recovery release passed a fresh Home-only cold start
  (`ConsoleActivity`, 1703 ms). Scoped logs reported `console_foreground` followed
  by `console_surface_registered`, with no attached session and zero target
  failures. At the user's request no tile was opened and no stream test was run;
  the two-cycle P0 remains deliberately pending.
- GitHub CLI account `Maladie` currently reports an invalid keyring token. Do
  not expose a token; refresh authentication before push if ordinary Git
  credentials do not work.

Architecture details, rollback gates, and all hooks outside the console package
are documented in `MOONWAKER_ARCHITECTURE.md`,
`UNIFIED_CONSOLE_REGRESSION_BASELINE.md`, and `UPSTREAM_PATCHES.md`.

## Repository

- Project: Moonlight Android
- Branch: `feature/moonwaker-unified-console`
- Push remote: `origin` (`Maladie/moonlight-android`)
- Official Moonlight is configured as the separate `upstream` remote.
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

## Remaining Playnite readiness work

No Playnite-specific host Bridge has been implemented yet. Android now keeps the
existing opaque `ExternalFrontendLoadingView` visible after the first decoded
frame for managed Console launches. The next stage connects a profile-scoped
host Console Bridge so it can also report that
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
