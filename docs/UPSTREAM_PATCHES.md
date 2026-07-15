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

Existing keyboard, mouse, touch, pen, and controller senders temporarily obtain
the controller-owned connection through the named `legacyConnection()` escape
hatch. This does not transfer lifetime ownership. Replace that escape hatch with
an `InputSender` boundary before moving the controller into `ConsoleActivity`.

### P003 - User-visible product label and JVM test dependency

- Surface: `app/build.gradle`.
- Reason: expose the accepted MoonWaker Game App working name for both build
  types and add JUnit 4 for pure console boundary tests.
- Risk: incorrect variant identity or missing release resources.
- Regression: debug/release manifest merge, APK application ID inspection, and
  `testNonRootDebugUnitTest`.
- Removal: label may be renamed before release; the application ID and signing
  identity must not change with it.

## Upstream synchronization policy

- Keep `origin` pointed at `Maladie/moonlight-android`.
- Fetch/merge upstream on a dedicated synchronization branch; no large upstream
  merge belongs to milestone 1.
- Keep `git rerere` enabled locally.
- Avoid formatting unrelated upstream lines.
- Add one entry here before each new hook outside the console package.
