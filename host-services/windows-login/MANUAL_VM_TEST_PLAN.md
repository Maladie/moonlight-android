# Remote Windows Sign-in VM checklist

Run this only on a disposable Windows 10/11 x64 VM or dedicated test host. The
build and unit tests do not register the Credential Provider and do not prove
LogonUI behavior.

## Preparation

1. Create two enabled local password accounts, `Basia` and `Gry`. Do not use
   Microsoft, domain, Entra, Hello-only, or RDP sessions.
2. Install the complete MoonWaker Host package as administrator. Confirm the
   automatic `MoonWakerGateway` and `MoonWakerLoginBroker` services are running.
3. In Host Control add one MoonWaker profile for each account. Configure and
   test each password in the elevated Configurator.
4. Pair a test Android TV/client. Grant it `use_profile` for both profiles and
   `remote_sign_in` only for `Gry` unless a scenario says otherwise.
5. Keep a VM snapshot or out-of-band console open. Built-in Windows sign-in must
   remain available throughout the test.

## Scenarios

- [ ] **1. Signed-out Gry:** with `use_profile` and `remote_sign_in`, launch a
  Gry game from Android. Gry signs in once, its profile Bridge starts, and the
  selected target launches.
- [ ] **2. Locked Gry:** lock Gry and repeat. Gry unlocks once; no repeated
  credential submission appears.
- [ ] **3. Gry already active:** launch again. `session/ensure` returns ready
  without requiring or consuming another credential attempt.
- [ ] **4. Basia is use-only:** while Basia is active, ordinary Basia use works.
  When Basia is locked or signed out, Android shows that this device may not
  remotely sign in that profile.
- [ ] **5. Another user active:** keep Basia active and request signed-out Gry.
  Android reports another active Windows profile; neither account is logged out
  or switched automatically.
- [ ] **6. Wrong stored password:** change Gry's Windows password without
  updating Host Control, then request Gry. Exactly one submission occurs, the
  profile becomes `action_required`, and later requests do not retry until the
  credential is replaced and validated locally.
- [ ] **7. Grant revoked:** remove Gry's `remote_sign_in` grant after pairing.
  The next locked/signed-out Gry request is denied immediately. An active Gry
  session still works with `use_profile`.
- [ ] **8. Gateway unavailable:** stop `MoonWakerGateway`. Remote launch fails
  cleanly and every built-in Windows credential remains usable.
- [ ] **9. Broker unavailable:** start Gateway, stop `MoonWakerLoginBroker`, and
  request sign-in. Android reports the unavailable service; built-in sign-in
  remains usable.
- [ ] **10. Provider disabled/unregistered:** run
  `Disable-MoonWakerCredentialProvider.ps1` (then the explicit provider
  uninstaller as a second pass). MoonWaker exposes no automatic credential and
  built-in providers remain unchanged. Re-enable/register it before continuing.
- [ ] **11. Unauthorized header:** send an authenticated Gateway request with
  `X-WakePlay-Profile` set to a profile lacking `use_profile`. It returns 403;
  filtering that profile from `GET /profiles` is not the only defense.
- [ ] **12. Reboot to LogonUI:** reboot without signing in. From another LAN
  device confirm Gateway and Broker become reachable before interactive logon,
  then request Gry.
- [ ] **13. WoL/resume correlation:** suspend the host, select Gry and launch a
  Gry target. After resume the original `(host, profile, target)` remains pinned
  through preflight and launch.
- [ ] **14. Android profile switch in flight:** request Gry, then switch the
  visible Android selection to Basia before the attempt finishes. The Gry
  orchestration is cancelled/ignored and cannot be redirected into Basia.
- [ ] **15. Remove profile:** remove Gry in Host Control. Its grant, startup
  task, profile data, and Broker credential are removed; the Windows `Gry`
  account and built-in sign-in remain unchanged.
- [ ] **16. Local stream hotkey:** with Host Control running in the tray, start
  a stream and press `Ctrl+Alt+Shift+End` once on the normal desktop, then repeat
  after locking Windows. In both cases Vibepollo closes the stream and Sunshine
  restores the physical displays; no game process is terminated by Host Control.

## Explicit profile-switch acceptance

Run these checks only through the Android **Switch Windows user** action. Merely
selecting a MoonWaker profile, entering the host, refreshing profiles, or loading
the library must not disconnect or switch Windows.

For every row record only anonymous profile IDs, the Windows build, response
state/reason, elapsed time, Gateway reachability, and Bridge readiness. Do not
record account names, credentials, PINs, bearer tokens, certificate material, or
raw diagnostics.

| Initial state | Action and expected result | Passed |
|---|---|---|
| Target profile active | The explicit action is unnecessary; ordinary profile use remains ready and creates no credential attempt. | [ ] |
| Target profile locked | One explicit switch/unlock attempt reaches ready; no duplicate credential submission occurs. | [ ] |
| Target profile signed out, another local profile active | The active console session is disconnected with Fast User Switching, never logged off; one target attempt reaches ready. | [ ] |
| Target profile disconnected, no other active console user | One explicit attempt resumes the target and reaches ready. | [ ] |
| Target credential missing | The current session is disconnected only after the switch is accepted, LogonUI remains usable, and Android reports `attention_required` / `credential_missing`. | [ ] |
| Fast User Switching blocked by policy | The request terminates with a bounded actionable failure; no logoff and no target credential attempt occur. | [ ] |
| Login Broker unavailable | The request terminates with service unavailable; built-in Windows sign-in remains usable. | [ ] |
| Credential Provider unavailable | The request terminates with a bounded provider-unavailable failure; built-in providers remain present. | [ ] |

For each successful active-user-to-target switch:

- [ ] Start a harmless marker process in the previous profile and record its PID
  out of band; after the switch, confirm the same process is still alive in the
  disconnected session.
- [ ] Poll Gateway from a separate LAN client before, during, and after the
  interactive-session change; confirm authenticated health remains reachable.
- [ ] Confirm the selected profile's Bridge processes start in that user's
  session and that Android does not continue normal launch readiness until the
  required Playnite connector and Vibepollo Bridge report ready.
- [ ] Change the Android host/profile or cancel while polling; confirm only the
  exact correlated attempt is cancelled and a late result cannot update the new
  selection.
- [ ] Repeat an accepted request ID and confirm it does not disconnect twice.

Record Windows version, MoonWaker version, account/session state, Gateway
response state/reason, and whether one provider submission occurred for each
case. Do not put passwords or bearer tokens in the record.

## Recovery and limits

Use built-in Windows sign-in if any MoonWaker component fails. Disable only the
MoonWaker provider with `Disable-MoonWakerCredentialProvider.ps1`, or unregister
it with `Uninstall-MoonWakerCredentialProvider.ps1`; neither command changes
other providers. Repair the saved password in Host Control.

Wake from full shutdown depends on the network adapter, firmware, power state,
and platform settings. MoonWaker starts its flow only after Windows networking
and LogonUI are reachable. It cannot cross BitLocker/UEFI prompts, boot errors,
or hardware that does not support wake from the selected power state.
