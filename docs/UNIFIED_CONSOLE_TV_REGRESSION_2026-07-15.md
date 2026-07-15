# Unified Console TV regression run — 2026-07-15

This is the device evidence record for the first live unified-runtime run against
`UNIFIED_CONSOLE_REGRESSION_BASELINE.md`. It records observed behavior without
weakening or checking off the baseline contract.

## Build and device

- Branch: `feature/moonwaker-unified-console`
- Tested commit: `a1eed492` (`Rebind Console surface after decoder startup`)
- APK: `moonwaker-game-app-unified-p0-v6-release.apk`
- APK SHA-256: `9022B16813588D7F6079BCB634DE8B09FA3B09A702DF813B9C97A1A37E0980B7`
- Certificate SHA-256:
  `745d86be25583505b45da74343bd9f868e8f77884fa6e0aaf49fba330b277740`
- Package/activity:
  `com.limelight.unofficial/com.limelight.console.ConsoleActivity`
- Device: Sony BRAVIA 4K VH22 at 1920x1080 UI geometry
- Automated regression: 179 tests, 0 failures, 0 errors, 0 skipped

Evidence retained outside the repository beside the workspace includes the
12-second fresh-launch recording, Wake and MoonWake Home/exit screenshots,
focused app-row screenshots, two Home-over-stream screenshots, and filtered
`MoonWakerSession`/`MoonWakerSurface` logs.

## A. Installation, identity and stored data

| Result | Scenario | Evidence / gap |
| --- | --- | --- |
| PASS | In-place package update | `adb install -r` succeeded; no uninstall or data clear. |
| PASS | Moonlight host data | Existing paired host, certificate-backed connection, app cache, posters and stream preferences remained usable. |
| FAIL | Gateway migration/import | MoonWake reports `GATEWAY NOT PAIRED` while the original Wake installation has its host integration state. A signed, idempotent import path is still required. |
| PARTIAL | Additive/idempotent migration | No legacy data was deleted and both packages remain installed, but Gateway and Wake launch history are not imported. |
| PASS | Launcher identity | The MoonWake package exposes exactly one MAIN/LEANBACK activity: `ConsoleActivity`. Wake remains separately installed for comparison/rollback. |
| PASS | License/attribution source gate | GPLv3 and retained Moonlight notices remain in the repository; no license-bearing core was replaced. |

## B. Console Home

| Result | Scenario | Evidence / gap |
| --- | --- | --- |
| PARTIAL | Host/session summary | Host and availability are correct. After a unified stream connects, Home still reads the legacy provider and incorrectly shows `SESSION · ENDED`. |
| PASS | Required app tiles | Steam Big Picture, intentional Desktop and Baba Is You are controller-reachable. Baba appears later than Wake because Wake launch history is not imported. |
| PASS | Focus stability | Multiple asynchronous availability refreshes did not move the focused app; four horizontal moves scrolled the row without a focus trap. |
| PASS | Passive refresh | No observed refresh requested focus or silently changed page. |
| PARTIAL | Cached art | Poster and separately blurred backdrop load from cache without a layout jump, but the 520 dp hero dominates the right side and does not match Wake's visual balance. |
| PASS | Home Back behavior | Back shows a controller-operable exit confirmation and does not immediately finish. |
| PASS | Non-destructive Home | Two Home/stream cycles retained the same connection and did not send an implicit host-app quit. |

## C. Start and active stream

| Result | Scenario | Evidence / gap |
| --- | --- | --- |
| PASS | Alternate Steam launch | Steam Big Picture resolved, connected and delivered the first video frame. |
| NOT RUN | Fresh Baba Is You launch | Avoided starting a second host app during this run. |
| PASS | Opaque connection gate | Home changed to the opaque loading/privacy layer through resolution, session preparation and first-frame wait. |
| PARTIAL | Desktop privacy | No desktop exposure was observed during the Steam launch, but a retained camera/video capture is still required because `screencap` does not include SurfaceView video. |
| PASS | Negotiated stream status | Decoder configured HEVC 1920x1080 at 60 FPS and audio initialized; these match the requested Home summary. |
| PARTIAL | Controller input | DualSense was enumerated as controller 0 and gameplay routing was enabled. Real in-game response was not independently camera-verified. |
| FAIL | Failure recovery choices | Earlier controlled failure returned Home, but the unified path does not yet expose the complete Retry/Home/Disconnect recovery choice set. |

## D. One-session lifecycle

| Result | Scenario | Evidence / gap |
| --- | --- | --- |
| PASS | One activity/connection | One PID, one `ConsoleActivity`, one session generation and one connected transport were retained. No legacy `Game` activity launched. |
| PASS | Decoder target | Startup retry changed the output from the expected pre-decoder failure to `target=CONSOLE`; the first frame then rendered. |
| PASS | Two Home/stream cycles | Both cycles kept the same PID, activity, session generation, decoder and transport, with no termination or second connection. |
| FAIL | Active Home presentation | Home lacks an accurate active-session label and prominent live return state because the legacy status provider does not own the in-Activity connection. |
| NOT RUN | Screen off/on and recreation | Requires a separate recovery run after active-session state is moved into the unified repository. |
| FAIL | Separate disconnect/quit UI | The unified Home/session UI does not yet expose both confirmed actions. |

The trace contains one early `target_console_failed` while MediaCodec does not
yet exist, followed by the successful post-connect `target_console`. This is not
the final clean evidence required by the baseline; the bridge should distinguish
"deferred" from an actual failed target change.

## E. Controller and focus routing

| Result | Scenario | Evidence / gap |
| --- | --- | --- |
| PASS | Visible layer navigation | Home, privacy and modal hardware layers remained fully composed while focus moved. The earlier disappearing-view SurfaceView regression is fixed. |
| PASS | Gameplay capture boundary | Back is handled by the activity state router; other keys route to the active session while gameplay is visible. |
| PASS | Home capture release/restore | Home opens without stopping transport and returning restores gameplay routing. |
| PARTIAL | Long-list focus | App-row horizontal scrolling passed; integration/Discord long panels were not exercised. |
| PASS | Async focus retention | Host availability and artwork updates did not move focus in the observed Home run. |

## F. Stream overlay

| Result | Scenario | Evidence / gap |
| --- | --- | --- |
| NOT RUN | Physical configured gesture | ADB cannot reliably synthesize the required long controller gesture. |
| PARTIAL | Back to console | Remote Back deterministically opens Console Home and a second Back returns to the same stream. Icon/action parity is incomplete. |
| NOT RUN | Discord overlay controls | Requires imported Gateway/profile data and physical controller coverage. |
| NOT RUN | Overlay DPAD/shortcuts/pinning | Deferred until the overlay can be opened by the physical configured gesture. |

## Wake & Play visual comparison

The original `com.limelight.launcher/.LauncherActivity` and MoonWake were captured
on the same TV and state. The functional background palette, typography family,
card treatment and focus colors are recognizable, but the layouts are not yet
equivalent.

Material differences:

1. Wake shows `RESUME LAST` with the last app; MoonWake does not import Wake's
   launch history and shows no inactive resume action.
2. Wake exposes Discord and Options at the top-right. MoonWake exposes only
   Options and places Host Integrations in the primary vertical flow.
3. The extra integration status/action shifts controllers, hosts and apps down;
   MoonWake app cards are visibly clipped at the bottom of 1920x1080.
4. Wake's first app is Baba Is You based on its history. MoonWake's independent
   history puts Steam first and moves Baba later in the row.
5. MoonWake's selected-art hero/backdrop is much more visually dominant than
   the Wake capture and reduces legibility/empty-space balance.
6. Exit confirmation behavior matches, but MoonWake uses a centered compact
   modal while Wake uses the established full-height right panel.
7. MoonWake's active stream status is stale after returning Home.

## Next acceptance steps

1. Make unified in-process session state authoritative for Home, including the
   active return action and negotiated status.
2. Restore Wake-compatible Home geometry and navigation without removing Host
   Integrations; move integrations behind the Wake-compatible top/options path.
3. Add an explicit, idempotent Wake launch-history/Gateway import contract.
4. Add Retry/Home/Disconnect recovery and distinct Disconnect/Quit actions.
5. Repeat A-F, including screen off/on, active video/input verification and the
   physical overlay gesture, before enabling the unified runtime by default.

## v10 corrective run — 2026-07-15

This run supersedes the earlier black-screen and lifecycle results above where
they conflict. Candidate v10 contains `82aaed86`, `f9a0dee2`, and `01d283af`.

| Result | Scenario | Direct evidence |
| --- | --- | --- |
| PASS | Full Home composition after focus change | ADB capture after DPAD focus change retained the complete Home; no focused-card-only damage remained. |
| PASS | Initial decoder target | `target_console_staged` occurred before connect with one successful target change and zero failures. The prior null `SurfaceHolder` crash no longer occurred. |
| PASS | Real stream image | TV capture contained the Windows lock-screen image at 1920x1080 rather than a black frame. |
| PASS | Stream → Home | Home became opaque and reported `SESSION · STREAMING` while the same session remained connected. |
| PASS | Home → Stream | `RETURN TO GAME` exposed the same live image without a second connection or Activity. |
| PASS | Disconnect transport | Confirmed `DISCONNECT STREAM` produced `disconnect_requested`, clean stream shutdown, decoder teardown and `session_detached`; Home returned to `SESSION · ENDED`. |
| PASS | Host app preserved by disconnect | Only transport disconnect was invoked; the distinct quit-host action was not selected. Home retained `RESUME LAST`. |

The black-screen root cause was a combination of two regressions: opaque/persistent
Android hardware layers over the `SurfaceView`, and deferring the initial render
target until after MediaCodec creation even though MediaCodec requires that target
during `setup()`. The unified path now mirrors the proven `Game` ordering: stage
the initial holder first, then use runtime output-surface switching only after the
decoder exists.

## Wake panel and Gateway run — 2026-07-15

This run used the in-place installed release through `b0d4fbeb` on the same TV.
The subsequent `86504f9f` only renames the stream hand-off hint from Wake & Play
to MoonWaker and was compile-verified, not reinstalled during this run.

| Result | Scenario | Direct evidence / limit |
| --- | --- | --- |
| PASS | Real stream image | Steam Big Picture reached a live 1920x1080 image after approximately 16 seconds; the first-frame loading screen was visible before that and no black frame remained. |
| PASS | Active Session panel | Wake right-panel geometry, app title, host, `1920×1080 @ 60 FPS`, Return, Disconnect and End App were visible; Return held initial focus. No destructive action was selected. |
| PASS | Exit panel | Wake right-panel geometry, initial Cancel focus, red Exit MoonWaker action and non-destructive session warning were visible. Exit was not selected. |
| PASS | Controller card/menu | Wake horizontal card with controller icon, compact DualSense name and battery indicator was visible. The original AlertDialog action order and Cancel action matched Wake. |
| PASS | Unsupported controller action | Selecting Identify displayed Wake's explicit `Feature unavailable` explanation; it no longer fails silently. |
| NOT RUN | Controller power-off/unpair confirmation | The confirmation code was copied from Wake and compile-tested, but these destructive actions were not selected on the paired DualSense. |
| PASS | Gateway capability panel | Paired Gateway, Vibepollo Bridge and Discord Bridge reported online and exposed the Wake-style entry actions. |
| PASS | Discord read-only navigation | Live guilds loaded; the run entered a guild/channel and Back returned Channel → Servers without leaving the application. |
| PASS | VirtualHere zero-device state | Android Hub/client status loaded and a valid zero-device state was displayed. Restart/connect actions were not selected. |
| PARTIAL | Vibepollo panel | Wake panel/actions rendered, while the paired endpoint returned `Vibepollo API unavailable`; restart/reset/export actions were not selected. |
| NOT RUN | Physical DualSense Circle routing | The implementation and `ConsoleKeyRoutingTest` distinguish gamepad `BUTTON_B` from remote/Android Back. Sony denies synthetic access to the physical input event, so actual in-game Circle still requires a person pressing the controller. |
| NOT RUN | Physical overlay hold | The existing Moonlight X `OverlayMenuView` and hold listener are wired. ADB's synthetic long-press is shorter/different from the configured 1500 ms controller hold, so the open overlay was not visually certified. |

Focused unit tests for session presentation, key routing and console state all
pass. The complete 196-test run has one expected configuration-gate failure:
`UnifiedConsoleRuntimeGateTest` asserts the unified runtime is disabled, while
this candidate is intentionally built with `unifiedConsoleRuntime=true`.
