# Unified console regression baseline

Updated: 2026-07-15

This document describes the observable behavior that must be protected while
Wake & Play and Moonlight X are migrated into one Android TV application and,
ultimately, one Activity. It complements both `docs/CODEX_HANDOFF.md` files.

## Immutable return point

Both repositories have the annotated, pushed tag:

`unified-console-baseline-2026-07-15`

| Project | Repository | Branch | Tagged commit |
| --- | --- | --- | --- |
| Wake & Play | `Maladie/wake-and-play-android-tv` | `main` | `5188905b69de522153838138ca84e883592a14d6` |
| Moonlight X | `Maladie/moonlight-android` | `feature/android-tv-session-controls` | `b5620e8f329371fd7b7d8ab8bd1aca7d01d1d6c7` |

The tags protect source history. Rollback safety additionally requires that the
unified application use additive storage migrations and retain Moonlight's
existing host database/preferences. Installing an older APK cannot repair a
destructively migrated database.

## Baseline build and device

- Android SDK: `C:\Users\Basia\Documents\Codex\android-sdk`
- ADB: `C:\Users\Basia\Documents\Codex\android-sdk\platform-tools\adb.exe`
- TV: `192.168.1.31:5555`
- Wake package: `com.limelight.launcher`
- Moonlight X package used by Wake: `com.limelight.unofficial`
- Moonlight release certificate SHA-256:
  `745d86be25583505b45da74343bd9f868e8f77884fa6e0aaf49fba330b277740`
- Signing instructions:
  `work/_android-signing/BUILD_SIGNING.md`

Baseline build commands:

```powershell
# Wake & Play
.\gradlew.bat :app:assembleDebug

# Moonlight X package used on the TV (unsigned before local signing)
.\gradlew.bat :app:assembleNonRootRelease
```

## Severity and evidence

- **P0**: stream termination, input loss, data loss, desktop/privacy exposure,
  unusable focus, broken signing/update, or inability to roll back.
- **P1**: major feature regression with a controller-operable workaround.
- **P2**: visual polish, transient status, or minor layout regression.

For every TV milestone retain:

1. exact commit and APK certificate digest;
2. 10-20 second `screenrecord` of a fresh launch;
3. screenshots of Home, active session, overlay, Discord and recovery UI;
4. filtered lifecycle/decoder/Gateway logcat;
5. result for each P0 scenario below.

Do not treat a successful UI transition as proof that the stream transport is
still alive. Verify real changing video after every return.

## P0 functional regression suite

### A. Installation, identity and stored data

- [ ] Unified builds update the intended package without uninstalling it.
- [ ] Existing Moonlight host pairing, certificates, app cache and preferences
      remain available.
- [ ] Existing Gateway pairing is migrated or can be imported once without
      exposing its token or certificate fingerprint.
- [ ] Migration is idempotent and preserves the legacy stores for rollback.
- [ ] The Android TV launcher exposes exactly one intended console entry.
- [ ] The build is GPLv3 compliant and retains Moonlight attribution/notices.

### B. Console Home

- [ ] Home displays selected host, availability and meaningful session status.
- [ ] App tiles include Baba Is You, Steam Big Picture and intentional Desktop.
- [ ] The currently selected tile retains controller focus across host/app/status
      refreshes.
- [ ] Passive asynchronous work never requests focus or silently changes pages.
- [ ] Selected artwork appears immediately from cache in stable FIT_CENTER
      geometry.
- [ ] The separately decoded blurred background crossfades asynchronously with
      no intermediate stretched image or layout jump.
- [ ] Back/controller B on Home opens an exit confirmation instead of immediately
      closing the application.
- [ ] Closing Home never sends an implicit stop/quit command to an active host
      application.

### C. Start and active stream

- [ ] A fresh Baba Is You launch can wake/contact the selected host and connect.
- [ ] Steam Big Picture can be used as the alternate launch target.
- [ ] The privacy/loading surface is opaque from launch until the first stable
      decoded frame is ready.
- [ ] No Windows desktop is exposed by an ordinary game launch.
- [ ] Resolution, frame rate and bitrate status match the negotiated stream.
- [ ] Controller input reaches the streamed application after reveal.
- [ ] A connection failure presents controller-operable retry, Home and
      disconnect choices rather than a black screen.

### D. One-session lifecycle

- [ ] Opening Console Home while connected does not recreate `NvConnection`,
      stop transport or destroy the decoder surface.
- [ ] Returning to the game reveals the existing connection; it does not start a
      second Game/connection instance.
- [ ] Two consecutive Game -> Home -> Game cycles retain real changing video and
      controller input.
- [ ] Home shows the active app, host and a prominent return action.
- [ ] Screen off/on and Activity recreation follow an explicit recovery path and
      never silently start a duplicate session.
- [ ] Disconnect transport and quit host application remain distinct confirmed
      actions.
- [ ] A non-destructive future `Return to Playnite` action must not reuse
      `QUIT_STREAM_APP`.

### E. Controller and focus routing

- [ ] Only the visible top-level layer receives navigation/input.
- [ ] Gameplay input is not intercepted while console UI and overlay are closed.
- [ ] Opening Home or overlay releases gameplay input capture without stopping
      the stream.
- [ ] Closing UI restores gameplay input capture and focus deterministically.
- [ ] DPAD navigation has no focus traps on long lists or scroll containers.
- [ ] Async Discord/Gateway/participant/artwork refresh never moves focus.

### F. Stream overlay

- [ ] The configured controller gesture opens and closes the overlay.
- [ ] Back to console is a deterministic action with a gamepad icon.
- [ ] Stream controls remain readable and are not crowded by Discord actions.
- [ ] Discord is rendered at the top-right with Mute, Leave, Rejoin and Pin.
- [ ] From the main vertical controls, DPAD Right enters Discord directly and
      DPAD Left returns without traversing the bottom strip.
- [ ] A pinned people list can remain visible after the overlay closes and can be
      unpinned.
- [ ] Participant refresh updates non-focusable content without rebuilding
      focused action buttons.
- [ ] X toggles local microphone and Y leaves voice only while the overlay is
      open; duplicate shortcut assignments cannot fire two actions.

## Host integration regression suite

### G. Host and integration profiles

- [ ] Host selection also selects the host-scoped Gateway and integration
      profile.
- [ ] Multiple physical hosts retain independent profile selection and pairing.
- [ ] The profile selector is focus-safe and persists per Moonlight host UUID.
- [ ] Every Gateway request includes the selected `X-WakePlay-Profile`.
- [ ] Gateway, Vibepollo Bridge and Discord Bridge each have a visible independent
      health row.
- [ ] A legacy Gateway without `/api/v1/profiles` produces a controlled missing
      endpoint state rather than HTTP 500 or broken navigation.
- [ ] Gateway tokens, DPAPI material and certificate fingerprints are never
      logged.
- [ ] TLS leaf-certificate pinning and request IDs remain enforced.

### H. Discord browser and voice controls

- [ ] Discord entry is visible only when the selected profile exposes Discord.
- [ ] Navigation is Server list -> Voice channel list -> Channel/People detail.
- [ ] Selecting a disconnected channel opens its detail page with Join.
- [ ] Successful Join changes the same page to active state with Leave.
- [ ] Back moves Detail -> Channels -> Servers -> Discord root without leaving
      voice.
- [ ] Leave is always separately reachable while connected.
- [ ] Local Mute and Leave are available on both channel and People pages.
- [ ] Current channel, local user and speaking participants are visible.
- [ ] Each remote participant has local mute and a 0-200% volume slider.
- [ ] Slider DPAD steps are 10%; rapid changes serialize only the latest request.
- [ ] Buttons show pressed/progress/result feedback and use meaningful Discord,
      join, destructive and neutral colors.
- [ ] Auto-start Discord is scoped by `(hostUuid, integrationProfileId)`.
- [ ] Auto-join last channel is separately configurable and shows the remembered
      channel name.
- [ ] A Discord RPC conflict/missing pipe is a controlled status, not HTTP 500.

### I. Audio and VirtualHere

- [ ] Audio Devices shows and controls Windows master volume/mute.
- [ ] Discord and Windows input/output devices can be selected only from
      allow-listed IDs.
- [ ] USB Devices reports VirtualHere client/server health.
- [ ] Shared devices support connect/disconnect and auto-use.
- [ ] VirtualHere client restart is available and confirmed.
- [ ] Empty VirtualHere server XML is treated as a valid zero-device state.

### J. Vibepollo FIX

- [ ] Vibepollo health follows the selected host/profile.
- [ ] FIX is reachable with a controller in a scrollable panel.
- [ ] Status refresh works without moving focus.
- [ ] Vibepollo restart and remembered-display reset require confirmation.
- [ ] Host-side diagnostic/log export remains available.

## Visual and interaction regression suite

- [ ] Home -> loader -> stream and stream -> Home use one stable display geometry.
- [ ] No black task animation, pillarboxing, transient scale or desktop flash is
      visible during ordinary navigation.
- [ ] Layout is stable at 1920x1080 and does not crop posters differently when a
      controller connects.
- [ ] Pressed-state feedback is visible for all controller actions.
- [ ] Long panels scroll the focused row into view; Up at the first action returns
      the viewport to the top.
- [ ] Re-rendering the current panel does not replay its entrance animation.
- [ ] Focus and scroll position restore when the same action still exists.

## Playnite-oriented future regression contract

Playnite is not implemented at the baseline tag. The unified architecture must
reserve and later test these behaviors without weakening the P0 session suite:

- one persistent Vibepollo/Apollo console application owns stream lifetime;
- a tile launch carries a profile-scoped Playnite game GUID;
- first decoder frame is necessary but not sufficient to reveal video;
- the Playnite/Console Bridge confirms a visible, non-cloaked foreground window
  on the streamed display with stable final geometry;
- readiness has a bounded timeout and Retry, Return Home, Reveal Desktop and
  Disconnect recovery actions;
- game exit covers desktop until Playnite Fullscreen is stable again;
- `Return to Playnite` preserves transport and Discord;
- Desktop remains an explicit user action, never an implicit fallback;
- libraries, artwork and GUID mappings are scoped per host/profile.

## Known baseline limitations (not accepted as final unified behavior)

- Android TV still composes one short (~0.25 s) scaled frame during the
  two-Activity launch handoff. The one-Activity architecture is intended to
  eliminate this boundary.
- Physical remote/gamepad Back is device-dependent and remains unstable in the
  two-application baseline. The unified state router must make Back a normal UI
  transition and test actual key codes on the TV.
- ADB cannot reliably synthesize the configured long-Select overlay gesture;
  physical controller verification is required.
- Playnite readiness and library integration do not exist yet.
- `lintNonRootDebug` has seven documented pre-existing errors unrelated to the
  Discord Gateway client.

## Removal gates

Do not delete the legacy external-frontend intents, providers, session status or
background-surface fallback until the equivalent unified path passes sections
A-F on the TV. Remove one compatibility path per separately revertible commit.

