# Console UX backlog

Source for UX-xx items: UI/UX audit by Astra at `b05f3e2c` (14 September 2026), reviewed against code on 28 September 2026. UX-03, UX-10, the unconfirmed-termination message and wake-stage presentation moved into hardening S08/S09 and are not repeated here.

Rules for every item: write user-facing text in the Polish `strings` resources first; every screen works with a TV remote (D-pad, OK, Back) as well as a pad; no migration of stored UI state (re-key and let it reset once); test on the real TV and controller.

## Now — independent of hardening

Small changes that do not touch S07–S09 ownership.

- **UX-01 (P0)** Mask the parent authorization PIN: `ConsoleActivity.authorizeChildManagementFor()` uses `TYPE_CLASS_NUMBER` without `TYPE_NUMBER_VARIATION_PASSWORD`.
- **UX-02 (P1)** Destructive/irreversible confirmations start focused on Cancel. Today `ConsoleUiFeedback.modalCard()` focuses the last action, which `showConfirm()` always makes the confirm button.
- **UX-08 (P1)** Language change on pre-Android-13 must return to the console context; `StreamSettings.onBackPressed()` currently relaunches `PcView` with `CLEAR_TASK`.
- **UX-07a (P1)** Fix focused confirmation contrast (white on `#459EC4` ≈ 3.03:1); use an outline/shape for focus instead of a lighter background.
- **Search normalization (P2)** Diacritic-insensitive matching (`wiedzmin` finds `Wiedźmin`) via `java.text.Normalizer`; no semantic search.

## After S10

- **UX-05 (P1)** Task-oriented in-game overlay: Back to game → Home/Library → community → End… → tools. Today Home is the last item in `OverlayMenuView.buildMenu()`, after quit. Keep overlay and pad-shortcut actions equivalent.
- **UX-06 (P1)** Errors stay in context with the next action (Retry, Pair, Open setup, Show details) instead of transient toasts; uncertain session state always offers a re-check.
- **Child time UX (P1)** Warnings at 5 and 1 minute before the budget/schedule ends; a calm expiry screen that does not look like an error; show when play is allowed again (`next_allowed_at`). Parent manual reveal is never a child-limit bypass.
- **UX-04 (P1 for parent/child, P2 otherwise)** Scope personal library state (selection, search, view, hidden games, filters) by `HostProfileKey`; keep TV-wide settings global. Re-key `ConsoleLibraryViewStateStore`, remove `legacySelectedGameId`, no migration.
- **UX-09 (P1/P2)** Outcome-oriented wording: "Open this computer automatically", "MoonWaker profile", "Windows sign-in"; technical Gateway/Bridge/process terms only under Diagnostics.
- **Remote-only navigation (P1)** Audit every screen with D-pad + OK + Back only; hide controller-only hints (X/Y) in remote mode.
- **First setup readiness chain (P2)** Computer connected → profile available → library available → ready to play, each missing step with its own repair action. Success is the first playable game, not "computer added".
- **Connection quality indicator (P2)** Discreet in-stream warning on degrading network before the picture breaks down (related to hardening T49).
- **Profile selection layout (P2)** Replace the fixed-height badge stack with stable text rows; keep focus on the same profile after refresh.
- **Empty/error states (P2)** Distinguish no filter results (→ Clear filters), no profile access, no library configured, cached library, refresh failed with content (→ Retry), empty child library.
- **Idle Back (P2)** No exit confirmation when there is no active session/operation; keep protection while a game/session is active.
- **Focus after filters (P2)** Keep focus near the filter control and show the result count.
- **Active-session status (P2)** Discreet "Game still running: X — Resume" wherever relevant, from the one session presentation model.
- **Settings grouping (P2)** Separate this TV / this computer / this profile / this game.
- **Reduced motion (P2)** Respect it everywhere, including PIN key animation.
- **UX-07b (P2)** Typography hierarchy for couch distance (titles 24–28sp, primary actions/state 16–18sp); remove rigid heights before enlarging text.

## Installation confirmation

- Diagnose and repair the game-installation confirmation flow.
- Coalesce repeated confirmation-required events into a single notification per installation.
- Do not repeat the same notification every 1–2 seconds while the state is unchanged.
- Primary game action matches actual state (Play/Resume); never label a game playable because installation was only requested.

## Streaming overlay

- Replace Xbox-letter-only hints with polished controller glyphs.
- Detect and show the correct mapping for DualSense and other supported controllers.
- Review and repair focus/navigation across every overlay action.
- Add a streaming setting that allows the PlayStation/Guide button to open the overlay.
- Add a configurable controller button shortcut for opening MoonWaker Home.
- Detect shortcut conflicts and prevent the same input from triggering both actions.

## Streaming settings

- Replace the inherited Moonlight streaming menu with a MoonWaker console-style screen.
- Preserve full controller and remote navigation and the reduced-animation mode.

## Later (P3)

- Installation operations center ("Operations: N"); offer cancel only where the integration can reliably cancel.
- Undo after hiding a game.
- Host Control (WinForms): outcome-first layout (Ready to play / Needs setup, Profile, Libraries, Pair TV, Diagnostics), adaptive layout instead of fixed coordinates, PID/port details under Diagnostics.
- Shared component states/tokens (normal, focused, pressed, disabled, busy, destructive).
- Confirmed-state microinteractions only (installation done, profile changed, session resumable).

UX measurements (time to first playable game, launch success rate, retries per launch, time to resume) come from `MoonWakerDiagnostics` and the lab runner, not from a separate analytics system.
