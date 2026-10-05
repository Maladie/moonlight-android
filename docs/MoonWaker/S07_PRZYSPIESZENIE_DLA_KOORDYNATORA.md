# S07 — poprawki kontraktu i instrukcja dla koordynatora (projekt)

Status: **projekt do zatwierdzenia przez właściciela**, 2026-10-05. Nic z tego nie obowiązuje,
dopóki właściciel nie przekaże tego koordynatorowi hardeningu własną wiadomością.

Jak użyć: przeczytaj część 1, uzupełnij pola oznaczone `<...>`, usuń poprawki, na które się
nie zgadzasz, i wklej część 2 koordynatorowi. Część 2 jest po angielsku, bo w tym języku
jest prowadzony pakiet hardeningu i `STATE.json`.

## Część 1 — co zatwierdzasz

| Poprawka | Co zmienia | Co musisz uzupełnić lub rozważyć |
| --- | --- | --- |
| A — zamrożenie zakresu | Błędy niebędące regresją S07 idą do zgłoszeń, nie do commitów S07 | Nic |
| B — dowód dla odtworzenia w PREPARING | Kontrola, której lab nie potrafi wywołać, jest zaliczana testem jednostkowym | Nic; wymaga niezależnego przeglądu samej zamiany |
| C — ograniczenie czasowe „8a" | 5 powtórzeń jednej kontroli i scenariusz na TV; brak powtórzenia oznacza otwarte zgłoszenie, nie blokadę | Nic |
| D — sesja na fizycznym telewizorze | Zgoda na instalację kandydata na BRAVII i na Twoim hoście oraz na przepływy z budzeniem i logowaniem | Termin, kto obsługuje pilota, czy zgadzasz się na instalację na prawdziwym hoście |
| E — procedura dowodowa | Pełna seria w labie raz, na końcu; przegląd tylko dla produktu i końcowych dowodów | Nic |
| F — S08 zastąpione | S08 nie jest wykonywane; zastępuje je krok K4 modelu sesji | Już zatwierdzone 2026-10-05 |

Uwagi:

- **Poprawka D to wdrożenie na prawdziwy sprzęt.** Zgodnie z `AGENTS.md` wymaga Twojej
  wyraźnej zgody z określonym celem i zakresem; tekst poniżej jest tak napisany.
- **Wycofanie zmian.** Starsze instalatory hosta przeniosłem 2026-10-05 do
  `D:/Maladie/moonlight-android-archive-20261005/host-services/`. Jeśli wersja obecnie
  zainstalowana na hoście pochodzi z któregoś z nich, jest tam, a nie w repo.
- **Poprawka C nie stwierdza, że zawieszenie „8a" nie istnieje** ani że istniało przed S07.
  Mówi tylko, co robimy, jeśli nie da się go powtórzyć.
- Po sesji na telewizorze S07 może dostać prawdziwe `CHECKPOINT_ACCEPTED`. Jeśli formalne
  wymagania dotyczące odcisków i tak to uniemożliwią, zostaje zamknięcie decyzją
  właściciela, ale już z dowodem z docelowego sprzętu.

## Część 2 — tekst do wklejenia koordynatorowi

```text
Owner instruction for the S07 coordinator, 2026-10-05.

Goal: close S07 with evidence from the target TV instead of further emulator-harness
iteration. Of the nine recorded lab runs in runs/S07/device-evidence.json, seven failed
for harness reasons, one for a lab trigger limitation, and one (s07-diag-8a) is
unclassified. The remaining gaps are T07, child expiry, T08 headless switch, PREPARING
recreation, the 8a freeze and the physical TV.

Record the following entries in STATE.json contract_amendments with status
USER_AUTHORIZED and this message as the source, then follow the procedure below.
Nothing here authorizes commit, push, publication, credential changes, or any device or
host action other than what amendment D names.

A. id: S07-20261005-SCOPE-FREEZE
   decision: FREEZE_S07_SCOPE
   value: From the commit that completes the fix currently in progress, the S07 candidate
   is frozen. A defect found afterwards enters S07 only if it is a regression introduced
   by S07 or a failure of a check in the S07 checkpoint plan. Every other defect,
   including child-session and S05-area defects, is recorded as a finding with a stable
   ID and left for a separately planned task. It is not fixed in the S07 candidate.

B. id: S07-20261005-PREPARING-RECREATION-JVM-EVIDENCE
   decision: ACCEPT_JVM_EVIDENCE_FOR_T26_PREPARING_RECREATION
   value: The lab cannot force Activity recreation inside the roughly 3.5 s PREPARING
   window (s07-diag-6a, 7a, 8b), and Game declares almost all configChanges, so the case
   is rare on a device. The check "T26-preparing-recreation (+ converted game)" is
   satisfied by a JVM test that uses controlled latches to lose the presentation during
   PREPARING and asserts the runtime outcome required by runs/S07/design.md, plus the
   forbidden effects. If no such test exists, add it as a test-only change. The status is
   recorded as CODE_ONLY, like T25, and never as a device PASS. This substitution needs
   one independent review of the test against the design before it counts.

C. id: S07-20261005-8A-TIMEBOX
   decision: TIMEBOX_UNCLASSIFIED_8A_FREEZE
   value: Run check 18 (child dashboard resume, frame and input) five times in isolation
   on the frozen candidate, and run the same scenario once on the physical TV under
   amendment D. If the freeze reproduces in either place, it is an S07 blocker and must
   be classified. If it does not reproduce, record it as an open finding with status
   UNCLASSIFIED_NOT_REPRODUCED, the s07-diag-8a evidence and an owner outside S07; it then
   does not block S07. This does not claim the freeze is absent, fixed or pre-existing.

D. id: S07-20261005-PHYSICAL-TV-SESSION
   decision: AUTHORIZE_ONE_BOUNDED_PHYSICAL_TV_SESSION
   value: One session on <date and time>, operated by <owner / named person>, on the
   BRAVIA 4K VH22 (API 32) and the owner's Windows host <host name>. Permitted: record
   the currently installed APK and host versions; install the frozen candidate APK by adb
   and the host installer built from the same commit; run the scenarios listed in the
   procedure, including the wake, Windows sign-in and sleep steps that those scenarios
   contain; export MoonWaker diagnostics; reinstall the recorded previous versions
   afterwards if the owner asks. Not permitted: credential or account changes, changes
   to profiles, grants or PINs, any host action outside the listed scenarios, publication
   of artifacts, and any other device or host. Credentials are entered only by the owner
   on the host; none are passed through the agent, command arguments or logs.

E. id: S07-20261005-EVIDENCE-PROCEDURE
   decision: SINGLE_FINAL_LAB_SERIES_AND_SCOPED_REVIEW
   value: While correcting the candidate or the harness, run only the affected checks.
   Run the full lab series three consecutive times once, on the frozen candidate.
   Independent review is required for product changes, for amendment B and for the final
   evidence. Harness-only and read-only-probe changes are recorded in the run log and do
   not need a separate review round.

F. id: S08-20261005-SUPERSEDED-BY-SESSION-MODEL
   decision: CLOSE_S08_AS_SUPERSEDED
   value: S08 is not executed as written. It is closed as CLOSED_BY_OWNER and replaced by
   step K4 of the session model in docs/MoonWaker/MW-04_MODEL_SESJI_PL.md (section 8.1),
   which carries every S08 requirement as an acceptance scenario. S09 must not start
   before K4 is accepted. S10 is unchanged.
   source for F only: User message 2026-10-05: "Zgoda na oba, wpisz do dokumentu i
   zaktualizuj plan"

Procedure

1. Finish the fix in progress, commit it, and record that commit as the frozen S07
   candidate (amendment A). Build the APK and the host installer from that one commit.
2. In the lab, on the frozen candidate: check 18 five times in isolation (C); the JVM
   test for PREPARING recreation and its review (B); T08 headless switch if the existing
   harness reaches it without new harness work, otherwise move it to the TV session.
3. Physical TV session (D). The owner operates the remote and reports what is on screen;
   record each result as PASS or FAIL with the time and the exported diagnostics:
   - F01 cold authorized start, F02 game-only stop, F03 retained switch, F04 whole quit
     and the next start, F05 Home and resume, F06 warm-up, F07 child permissions and time;
   - T07: open the host picker during a retained stream and return without changing host;
   - child expiry with a short limit set by the owner;
   - T08: switch games while no stream screen is attached;
   - the 8a scenario: child dashboard, resume, confirm the picture and input advance.
4. Run the full lab series three consecutive times on the frozen candidate (E).
5. Update runs/S07/device-evidence.json and STATE.json. Report to the owner one of:
   - every required check has PASS or the amended status: propose CHECKPOINT_ACCEPTED;
   - a check failed: name it, with evidence, and stop; do not widen scope to fix
     anything outside amendment A.

Stop conditions: any FAIL on the TV in F01–F07; a reproduced 8a freeze; a need to touch
credentials, accounts or any device not named in D. In each case stop and report.
```
