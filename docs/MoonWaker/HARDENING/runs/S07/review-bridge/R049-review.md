# R049 independent pre-edit review — S07 StreamRuntime

**Verdict: CHANGES_REQUIRED.** The runtime-owned MoonBridge listener and generation-scoped attachment are sound boundaries, but the design leaves reachable production paths unresolved. No S07 product diff exists.

I read R049, the S07 characterization, design, checkpoint plan and dispatch. I verified branch HEAD 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43, the four S07 file hashes, and spot-checked Game, Console, coordinator, service, renderer and termination code. I ran no build, unit suite or lab test and changed no product source. The physical TV OS API remains unverified; saved installation evidence identifies BRAVIA 4K VH22 and APK version 12.2.23, not Android OS version.

## Findings

### [P1] Activity destruction still cancels a live provider transition

Game.java:1738-1742 invokes cleanupUnrevealedProviderLaunch("activity-destroyed") for a non-configuration destroy and closes transitionCoordinator. The cleanup at Game.java:5543-5567 invokes onStreamFailed() and clears the resume record; ConsoleStreamTransitionCoordinator.java:413-415,496-546 may stop the exact owned provider process. This is separate from the transport stop that S07 removes. Under "Don't keep activities" or Home during PREPARING, the socket could survive while the old Activity stops its provider and destroys its readiness owner. Explicitly move or transfer provider-transition ownership and pending callbacks, with an epoch fence, before Activity close. Preserve fresh unrevealed launch cleanup on a real terminal failure. Test recreation during PREPARING and show one provider owner, no provider stop, and reveal or honest reconnect in T26.

### [P1] Headless retained switch is a reachable dashboard path

The design says switchGame requires an attached presentation and Console always attaches Game first. ConsoleActivity.java:1029-1041 skips instant-resume handling for retainedStreamHome; ConsoleActivity.java:13375-13384,14724-14745 checks and invokes retained switch directly from the dashboard. returnToRetainedStream() at 14539-14549 attaches Game only after a user chooses return. Once Game is destroyed but runtime survives, the Home/dashboard can be visible with no Game attached. Making canSwitchGame false would remove A-to-B switching. Specify how the dashboard attaches Game with an opaque privacy handoff before switch, or how a headless runtime safely performs the switch. Cover same-game return, different-game switch, PREPARING switch and actor handoff after presentation loss.

### [P1] Foreground-service start failure must stop the unprotected connection

connectionStarted is asynchronous (Game.java:4841-4876); it can arrive after Home/onStop, so it is not guaranteed to run with a foreground presentation. Current BackgroundStreamService.java:72-110 has no established-state command: absent a matching pending resume record it rejects a non-PARK command, while RESUME stops foreground and self. Both require migration. The proposed "park returns false -> RECONNECT_REQUIRED" fallback is insufficient: RetainedStreamSessionCoordinator.java:551-581 changes state and clears Controller on park failure but does not stop NvConnection. Define a service establishment/acknowledgment and generation-correlation protocol. If service start or park fails, request and await the shared native stop, save reconnect state and release locks. Test late connectionStarted after onStop, service-start refusal, missing acknowledgment, and stop/new-start races.

### [P1] Define which transports may survive presentation loss

The design says every Game.onDestroy detaches and a detached live runtime parks, but Game.java:1719-1737 currently stops both retained and non-retained connections on destroy. Game.java:1873-1900 and surfaceDestroyed at 6104-6121 distinguish park from stop, including PREPARING warm-up. The claim that all finishing paths already stop before finish() lacks a caller inventory; bitrate reconnect at Game.java:6754-6793 is another explicit stop-and-relaunch path. Bind survival to the exact retained session and state. On non-retained finish/destroy or invalid PREPARING cancellation, request shared native stop before dropping the owner. Map all existing stop/finish entrypoints to the new owner and test non-retained teardown, PREPARING cancellation and bitrate restart.

### [P2] Detached whole quit requires runtime privacy effects

Moving host and transport termination out of Game is appropriate, but presentation privacy hooks cannot all become no-ops when detached. Game.java:4433-4450 immediately mutes audio, suppresses input, clears resume state and updates the service before asynchronous local/host stop; WholeSessionTermination.java:61-86 includes these effects in its terminal result. Make runtime mute, input fence, resume clear and expiry cancellation mandatory and generation-correlated. Only view opacity/navigation may be optional. Test whole quit and child expiry while detached, including late old service callbacks after actor/generation change, and preserve S05 transportUnsafe/completion semantics.

### [P2] Pin migration of the control-generation and actor-handoff path

The design lists a Transport.reauthorize seam but omits Game.stageComplete (Game.java:3208-3216), which binds control generation using Game as coordinator owner and persists it into the Intent/resume record. Game.java:3594-3830 checks the exact conn/control generation around host mutations during actor handoff. Include both in the production-path map and JVM checks, with a stale attachment callback during actor rebind and stale stageComplete after a new transport generation. Moving only the connection field and listener could leave Activity-held authority or break actor transfer.

## Answers and evidence limits

- **Switch:** Requiring an attachment is acceptable only after a concrete dashboard attach-before-switch route exists. The current Console route does not provide one.
- **Whole quit:** Runtime should own transport, host and mandatory privacy effects. An attached view adds opacity/navigation only.
- **FGS:** An active-stream notification fits the S07 contract; bootstrap, correlation and failure teardown need the state machine above. The checkpoint plan still lacks a verified physical-TV API level. Do not confuse the lab AVD API 36 or APK 12.2.23 with that value.
- **ControllerHandler/USB:** Presentation ownership is reasonable if detach sends neutral controller state, releases keyboard/mouse state, disables sensors and unbinds USB before stale senders can use the live connection.
- **Branch:** The owner-closure amendment permits proceeding on the current branch, while packet.py branch-check still fails against the old base. Preserve the explicit exception and failed check in the implementation report; VALID_PACKET_METADATA is not a passing branch check. Do not rewrite STATE or Git history merely to hide this.

Update the input-bound design and checkpoint plan and submit a new pre-edit review. Keep protected-flow checks intact. **Verdict: CHANGES_REQUIRED.**