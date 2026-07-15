# Upstream patch register

Official remote: `https://github.com/moonlight-stream/moonlight-android.git`

This file records intentional hooks outside `com.limelight.console.*`. It is not
a list of all console-only files. Each entry must identify the reason, smallest
upstream surface changed, regression evidence, and removal condition.

## Active patches

### P001 - Console Activity manifest registration and launcher routing

- Surface: `app/src/main/AndroidManifest.xml`.
- Reason: register the milestone-1 `ConsoleActivity` as the single launcher
  entry. `PcView` remains registered and reachable internally, and no public
  compatibility intent/provider is removed.
- Risk: Activity theme/configuration divergence on Android TV.
- Regression: manifest merge in debug/release builds; exactly one MAIN/LEANBACK
  launcher and public STREAM/RETURN/DISCONNECT/QUIT actions remain present.
- Removal: revert the launcher filter to `PcView` if the Home vertical slice
  fails build/device focus checks; do not delete `PcView`.

### P002 - Legacy Game session/surface adapters

- Surface: small calls or interface implementation in `Game.java` only when the
  extraction reaches that class.
- Reason: expose state and surface lifetime to console adapters while retaining
  the existing decoder and background fallback during transport extraction.
- Risk: duplicate session ownership or changed `surfaceDestroyed()` behavior.
- Regression: unit tests prohibit adapter start of a second owner; existing
  external-frontend lifecycle test plus two live Home cycles with changing video.
- Removal: after `StreamSessionController` owns the real connection in the
  unified Activity and P0 section D passes on TV.

The first extraction replaces `Game`'s private `surfaceCreated` flag with the
pure `StreamSurfaceHost` policy. Android renderer calls stay in `Game`; the
existing background-surface predicate and fallback are unchanged. Unit tests
characterize create/change/destroy ordering and the keep-session loss action.

The second extraction replaces `Game`'s private gameplay-grab boolean with the
shared `InputRouter`. Renderer/input-provider calls remain in `Game`, while the
decision about which region owns input is now explicit and unit tested. The
same router is used by `ConsoleActivity` for Home, overlay, modal, and gameplay
states.

The third extraction moves construction, one-shot start, asynchronous stop, and
lifecycle state of the real `NvConnection` into
`MoonlightStreamSessionController`. `Game` supplies configuration and remains
the `NvConnectionListener`, but no longer calls `new NvConnection`,
`NvConnection.start()`, or `NvConnection.stop()`. Four characteristic tests
cover single-owner start, off-caller stop, repeated disconnect, and the strict
separation of Quit Host Application from transport stop.

The fourth extraction adds explicit two-phase initialization. The controller
creates `MediaCodecDecoderRenderer` in `prepareRenderer()` so `Game` can query
unchanged codec capabilities/color preferences while building
`StreamConfiguration`. `initializeTransport()` then creates both
`AndroidAudioRenderer` and `NvConnection`, and `connect()` is rejected until
that phase completes. `Game` contains none of those three constructors. The
renderer callback/listener objects, metered-network value, HDR decision, crash
count, GL renderer, audio-FX flag, and Activity/application Context choices are
passed unchanged.

The fifth extraction removes the temporary `legacyConnection()` escape hatch.
The session controller now exposes only the input-scoped `StreamInputSender`,
implemented by a package-private adapter around its owned `NvConnection`.
`Game`, `ControllerHandler`, `AbsoluteTouchContext`, and `RelativeTouchContext`
retain their existing Android event translation and packet ordering, but none
receives or stores a raw connection. Two characteristic tests reject input
access before initialization and verify that the controller exposes the supplied
input boundary independently of its transport. The full 23-test JVM suite and
the non-root debug build pass.

The sixth extraction moves all decoder target operations behind
`StreamRenderTargetController`, implemented by the session owner. A process-local
`ActiveStreamSurfaceBridge` weakly coordinates the compatibility `Game` Activity
and `ConsoleActivity` without owning either one. Before Game reveals Console,
the renderer moves directly to Console's registered persistent `SurfaceHolder`;
if that holder is unavailable, the existing background `ImageReader` remains the
fallback. Returning to Game rebinds the same renderer and clears Console target
ownership. Console surface loss also moves output to the background before the
window surface disappears. Five coordinator tests cover explicit and foreground
handoff, loss fallback, return ownership, and rejection of a second session.
The full JVM suite now contains 28 passing tests.

The seventh extraction adds deterministic, non-sensitive lifecycle evidence.
`MoonlightStreamSessionController` assigns a process-local monotonic diagnostic
ID and logs renderer preparation, transport initialization, connect, recovery,
disconnect, and stop transitions. `ActiveStreamSurfaceBridge.Snapshot` records
only the session generation, active render target, Console surface/foreground
flags, successful target changes, and failed target changes. The same values are
emitted under the `MoonWakerSurface` prefix for filtered logcat capture. No host,
app, certificate, profile, or credential data is included. Three additional
tests cover failed target visibility, idempotent ownership/generation changes,
and unique controller diagnostic IDs; 31 JVM tests pass.

### P003 - User-visible product label and JVM test dependency

- Surface: `app/build.gradle`.
- Reason: expose the accepted MoonWaker Game App working name for both build
  types and add JUnit 4 for pure console boundary tests.
- Risk: incorrect variant identity or missing release resources.
- Regression: debug/release manifest merge, APK application ID inspection, and
  `testNonRootDebugUnitTest`.
- Removal: label may be renamed before release; the application ID and signing
  identity must not change with it.

### P004 - Fail-closed external loader and same-task Console return

- Surface: `Game.java`, `PublicStreamIntent.java`,
  `ExternalFrontendLoadingView.java`, plus the Console focus hook in
  `ConsoleActivity.java`/`ActiveStreamSurfaceBridge.java`.
- Reason: a live unified-console launch showed that first decoded frame is not a
  safe application-readiness signal. Carry an explicit readiness requirement,
  keep the loader opaque through a bounded timeout, and expose controller-native
  Retry, Home, Reveal Stream, and Disconnect recovery actions. Return to the same-package
  Console instance without `NEW_TASK`, and retain a session whose renderer is
  already bound to Console's surface.
- Risk: without a profile Bridge, every Console launch pauses at recovery instead
  of revealing automatically. An incorrect task or surface predicate could
  orphan or disconnect the compatibility `Game` session.
- Regression: 39-test JVM suite, debug/release builds, Home/dream/wake surface
  registration, filtered
  `MoonWakerPrivacy`, `MoonWakerGameLifecycle`, `MoonWakerSession`, and
  `MoonWakerSurface` device logs, then two live Stream -> Home -> Stream cycles.
- Removal: replace the explicit-reveal fallback only after the versioned Bridge
  proves visible foreground-window, streamed-display, final-geometry, and stable
  consecutive readiness samples. Remove the lifecycle adapter only when `Game`
  is no longer a separate Activity and P0 section D passes.

### P005 - Read-only profile catalog over the pinned Gateway client

- Surface: `ui/overlay/DiscordGatewayClient.java`.
- Reason: reuse the existing certificate pin, bearer authentication, profile
  header, response-size bound, and timeouts for Wake's read-only
  `/api/v1/profiles` contract. `GatewayProfileAdapter` converts the response into
  console-owned, secret-free service status models.
- Risk: malformed or unexpected profile metadata could otherwise prevent the
  integrations panel from rendering. Invalid profile IDs and suggestions are
  ignored independently; credentials never enter the catalog model.
- Regression: adapter conversion tests and non-root Java compilation. No host
  request is made automatically in this increment.
- Removal: move the shared pinned request transport out of the Discord UI package
  when the final Gateway controller replaces both legacy clients.

### P006 - Shared decoder video-format policy

- Surface: `Game.java`.
- Reason: move the pure HDR/HEVC/AV1 bitmask decision into
  `StreamVideoFormatPolicy`, so the compatibility Game adapter and the future
  in-Activity Console runtime use identical decoder negotiation.
- Risk: an incorrect bitmask could advertise a codec/profile the decoder cannot
  render or unnecessarily disable HDR.
- Regression: exhaustive H.264/HEVC/AV1/Main10 policy tests, Java compilation,
  and the existing decoder capability checks in `Game`.
- Removal: none; this is a neutral session policy and should remain shared after
  the Game Activity adapter is removed.

### P007 - Shared display frame-rate policy

- Surface: `Game.java`.
- Reason: move capped-FPS selection and fallback rules into
  `StreamFrameRatePolicy`, so both stream runtimes make the same decision from
  the requested FPS, pacing mode, and active display refresh rate.
- Risk: a boundary regression could introduce uneven pacing or select a target
  frame rate that the display cannot present smoothly.
- Regression: focused boundary tests around 49 Hz, near-refresh capping, and
  above-refresh fallback, followed by non-root Java compilation.
- Removal: none; this policy remains shared after the compatibility Game
  Activity is retired.

### P008 - Shared initial gamepad mask policy

- Surface: `Game.java`.
- Reason: centralize how physical pads, single-controller compatibility mode,
  and the on-screen controller determine the initial gamepad mask advertised to
  the host. The unified runtime can now preserve the exact legacy behavior.
- Risk: a wrong mask could hide an attached controller or expose extra player
  slots to games with fragile hot-plug support.
- Regression: focused tests for multi-controller, forced primary controller,
  on-screen controller, and empty-mask cases, followed by Java compilation.
- Removal: none; this is transport configuration shared by both runtimes.

### P009 - Shared client refresh-rate override policy

- Surface: `Game.java`.
- Reason: parse the optional actual-display refresh value in one pure policy for
  both runtimes. Invalid, non-positive, non-finite, and overflowing values now
  fail closed instead of aborting stream setup.
- Risk: incorrect unit conversion could report the wrong client refresh rate to
  the host and affect pacing decisions.
- Regression: focused tests for decimal conversion, whitespace, absent values,
  malformed values, non-finite values, and overflow, followed by Java
  compilation.
- Removal: none; this validated configuration boundary remains shared.

## Upstream synchronization policy

- Keep `origin` pointed at `Maladie/moonlight-android`.
- Fetch/merge upstream on a dedicated synchronization branch; no large upstream
  merge belongs to milestone 1.
- Keep `git rerere` enabled locally.
- Avoid formatting unrelated upstream lines.
- Add one entry here before each new hook outside the console package.
