# Session and host-contract guide

Use when changing session orchestration, Gateway integration, privacy, child access, or their ownership. These are product constraints and navigation hints, not a claim that every current implementation already satisfies them.

## Preserve separate facts

Transport, executing game, presentation, and actor entitlement are independent. A stale or unknown game observation must not erase a known live transport. A saved resume record does not prove that a connection exists. Status badges and game tiles should derive from reconciled observations, not independent guesses.

Home/app navigation and game-only stop retain the transport where required by the task. Switching games stops the old execution and starts the new one without replacing a healthy retained stream. Full quit terminates the local transport even when host cleanup cannot be confirmed; show unresolved cleanup truthfully. Background warm-up must not undo an explicit quit. Process death and Activity recreation are different recovery cases.

## Ownership and correlation

Start with existing owners: `SessionOrchestrator` for intent and `RetainedStreamSessionCoordinator` for the retained facade until MW-05 step K3, from which `SessionModel` owns session state and intent (`RetainedStreamSessionCoordinator` is being removed by MW-05 K3-K5); `LaunchTransitionController` for transition/privacy decisions; `SessionResumeManager` for reconnect material; `SuspendedSessionStore` for intentional suspension; host `OperationJournal` for durable GameOps. Verify locations and current contracts before changing them.

Physical runtime may move behind an existing facade. Do not create a second durable journal or competing state machine. `Game` and `ConsoleActivity` should shed the touched domain responsibility, not donate it to another catch-all manager.

Bind asynchronous observations and effects to the identities they can affect: host, actor, execution profile, transport generation, operation/transition, and game/process identity. Fence late stop, ready, entitlement, and reveal results at effect time. A change of actor can require new authorization even when Windows profile and transport stay the same. Avoid inventing one universal ID for unrelated lifetimes.

## Privacy and authority

Automatic reveal needs the matching execution's readiness and fresh-frame evidence; input follows the same authorized transition. Keep the existing explicit manual-reveal route available as intended, including dismissal of the privacy cover. UI teardown must not strand a terminal stop waiting for an invisible view.

Windows sign-in requires the selected profile's configured consent, credential availability, and successful readiness. Do not treat absent data or timeout as success. Device permission to request sign-in is not permission to inspect or log credentials.

A child profile is a presentation and policy facade over its parent's execution profile. It uses the same launch, stop, retained-stream, retry, recovery, settings and host-control mechanisms as the parent. The only child-specific product restrictions are the parent-defined game library, play time/window limits, no child-profile/schedule management, no Discord, and no microphone. Enforce the game grant and time policy at the host boundary, but do not create a second child-owned stream, provider or recovery lifecycle. Entitlement expiry must invoke the common whole-session termination even if Android is absent; a late time-policy effect must not terminate a newer parent or child actor generation. Never replace verified process identity with a process-name-wide kill.

Session recovery is not a parent-only privilege. On the same trusted TV client and execution profile, a child may close, hard-reset, reuse or take over the current MoonWaker session regardless of which actor opened it. Selecting a child profile alone has no session effect. If the running game is shared with that child, entering it adopts the existing game and transport and starts that child's time attachment. If it is not shared, it is absent from the child library; clicking an allowed game automatically performs the common full close without a prompt and then launches the selected game. Parent takeover stops child-time charging without stopping the game or transport. Child-to-child takeover transfers charging at the committed actor handoff. Keep the general client, execution profile, transport generation, actor epoch and observed-app fences so stale callbacks cannot affect another TV, execution profile or newer actor generation.

## Evidence

Use controlled clocks/orderings for races and assert forbidden effects as well as final state. A mock flag cannot prove a retained native stream, successful Windows sign-in, displayed frame, or real input routing. Use the assigned checkpoint's device evidence for those claims.

For deeper ownership context, consult relevant sections of [the architecture document](../../MOONWAKER_ARCHITECTURE.md). Its historical branch/status is not a current checkout instruction.
