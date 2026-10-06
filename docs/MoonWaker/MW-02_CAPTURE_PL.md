# MW-02 — jak złapać i opisać awarię sesji na TV

Ten dokument jest instrukcją dla właściciela przy telewizorze. Cel: zebrać co najmniej
dziesięć prawdziwych awarii przy ponownym użyciu streamu albo przy złym statusie w UI
i dla każdej ustalić, która ścieżka w kodzie ją wywołała. Od MW-01 każdy ekran błędu
sesji pokazuje krótki **kod przyczyny**, więc logcat nie jest potrzebny.

## 1. Co zrobić, gdy pojawi się błąd

1. **Nie naciskaj od razu „Ponów” ani „Anuluj uruchamianie”.** Najpierw zrób telefonem
   zdjęcie ekranu.
2. Zapisz godzinę z dokładnością do minuty.
3. Przepisz z ekranu trzy rzeczy:
   - duży tytuł, np. „Nie udało się zakończyć”,
   - opis pod nim,
   - **kod**: mały, szary tekst w ostatniej linii opisu, np.
     `close.snapshot_not_idle_after_close`. Kod zawsze ma postać `słowo.słowo`.
4. Sprawdź na monitorze komputera (albo w Playnite/Sunshine), **co naprawdę działało**
   w tej chwili: która gra, czy w ogóle jakaś, czy stream był połączony.
5. Zapisz jednym zdaniem, **co robiłeś tuż przed błędem**, np. „grałem w A, wróciłem
   do biblioteki przyciskiem HOME, wybrałem B, potwierdziłem zamknięcie A”.
6. Dopiero teraz wybierz „Ponów” albo „Anuluj uruchamianie” i zanotuj, co się stało dalej.
7. Wyeksportuj diagnostykę (punkt 2) najlepiej od razu, a najpóźniej tego samego dnia.

Komunikaty **bez kodu** też zapisuj, dosłownie. Kodu nie mają:

- dymek (toast) „Wróć do bieżącej sesji i zakończ ją przed uruchomieniem innej gry.”
  Pokazuje go ekran gry (`Game.java`), którego w MW-01 nie wolno było zmieniać, bo
  równolegle zmienia go S07. Taki przypadek wpisz do tabeli z kodem `toast.switch_blocked`;
- błędy gotowości hosta („Host nie osiągnął stanu gotowości.” z innym opisem niż o 90
  sekundach) i „Nie udało się przygotować streamu”;
- błędy pokazane już na ekranie gry po przełączeniu do streamu.

Panel boczny „APLIKACJA — Stan sesji jest jeszcze sprawdzany” w bibliotece ma kod
dopisany w ostatniej linii opisu (`launch.target_unresolved`).

## 2. Jak wyeksportować diagnostykę

Aplikacja zapisuje zdarzenia lokalnie, w prywatnym katalogu (`no_backup/diagnostics`):
plik `events.jsonl`, dwa starsze pliki po rotacji (`events.1.jsonl`, `events.2.jsonl`,
każdy do 2 MB) i ewentualnie `last-crash.json`. Najstarsze wpisy znikają przy kolejnych
rotacjach, dlatego eksportuj szybko. Zapisywane są tylko pola z listy dozwolonych
(identyfikatory, stany, kody). Tokeny, PIN-y i hasła nie trafiają do pliku.

Eksport w aplikacji:

1. W bibliotece otwórz **Opcje** (ikona zębatki).
2. Wybierz **Ustawienia streamingu ›**.
3. Przewiń do sekcji **Ustawienia zaawansowane** i wybierz **Export app diagnostics**
   (ten napis nie ma polskiego tłumaczenia).
4. Pojawi się systemowe okno zapisu pliku. Zapisz plik `moonwaker-diagnostics.jsonl`
   (np. na pendrive albo w Pobranych).
5. Na dole ekranu pojawi się „Diagnostics exported”. Komunikat „Could not export
   diagnostics” oznacza, że plik nie powstał.

Jeżeli na telewizorze nie otworzy się okno zapisu, zanotuj to. Wersję debug można też
odczytać z komputera przez adb, np.
`adb exec-out run-as com.limelight.debug cat no_backup/diagnostics/events.jsonl > events.jsonl`.
Tego polecenia nie sprawdzano na BRAVII.

## 3. Co wyciąć z pliku dla jednej awarii

Każda linia pliku to jedno zdarzenie w formacie JSON. Dla jednej awarii:

1. Znajdź linię z `"event":"session.failed"` i polem `"reason"` równym kodowi z ekranu
   (zgadza się też godzina w polu `ts`, zapisana w UTC).
2. Odczytaj z niej `orchestration_id`. Skopiuj **wszystkie** linie z tym samym
   `orchestration_id`. To są zdarzenia `orchestration.*`: `started`,
   `preflight_completed`, `close_started`, `close_completed` albo `close_failed`,
   `launch`, `presentation_required` i inne.
3. Dołącz linie `projection.published` (komponent `android.session`) oraz
   `retained_stream.state_changed` z około 30 sekund przed błędem i 30 sekund po nim.
   Pokazują, co aplikacja wiedziała o hoście i o streamie w tle.
4. Kody `support.*` i `launch.target_unresolved` powstają poza orkiestratorem
   i mają `orchestration_id` równe 0. Dla nich wytnij zdarzenia po czasie.

Pola w `session.failed`:

| Pole | Znaczenie |
| --- | --- |
| `reason` | kod przyczyny, ten sam co na ekranie |
| `orchestration_id` | numer jednego kliknięcia „Graj”; łączy wszystkie zdarzenia tej próby |
| `host_id`, `profile_id` | host i profil, którego dotyczyła próba |
| `state` | stan sesji w chwili błędu: `NONE`, `PREPARING`, `ACTIVE`, `SUSPENDED`, `RECONNECT_REQUIRED`, `TERMINATING`, `UNCERTAIN` |
| `observation_state` | świeżość informacji z hosta: `PENDING` (czeka na odczyt), `READY`, `IDLE`, `RECOVERY_REQUIRED`, `UNAVAILABLE` |
| `known_live` | czy aplikacja trzymała wtedy żywy stream w tle |
| `app_id`, `game_id` | aplikacja Sunshine i gra, które **widział** snapshot w chwili błędu (nie ta, którą próbowałeś uruchomić) |
| `detail` | tylko dla `switch.*`: powód zgłoszony przez ekran gry, np. `retained_switch_owner_changed` |

### Na co patrzeć po `close.snapshot_not_idle_after_close`

Od wersji z poprawką z 2026-10-05 udane zamknięcie nie czeka już na ponowne odpytanie
hosta: stan `UNCERTAIN` po `CLOSED` prowadzi prosto do uruchomienia gry. Ten kod oznacza
więc teraz, że mimo udanego zamknięcia aplikacja lokalnie nadal widziała inną sesję.
Zapisz `state` z tego zdarzenia (`ACTIVE`, `SUSPENDED`, `RECONNECT_REQUIRED`,
`TERMINATING` albo `PREPARING`) oraz to, czy na PC rzeczywiście coś jeszcze działało.

Jeżeli po `orchestration.close_completed` ze `status` = `CLOSED` gra się uruchamia,
a mimo to coś idzie źle dalej (błąd na ekranie gry, stary obraz), zanotuj to jako osobny
przypadek z dopiskiem „po zamknięciu".

## 4. Kody przyczyn

Ścieżki dotyczą plików w `app/src/main/java/com/limelight/console/`. Kody są
zdefiniowane w `SessionFailure.java`. Kolumna „Ekran” podaje tytuł i opis, które widzi
użytkownik.

Ekrany: **A** = „Nie udało się zakończyć” + „Nie udało się zamknąć poprzedniej sesji
hosta…”; **B** = „Nie udało się zakończyć” + „Gra nadal się uruchamia i nie ma jeszcze
procesu…”; **C** = „Stan sesji jest jeszcze sprawdzany”; **D** = „Host nie osiągnął stanu
gotowości.” + „Żaden port streamingu nie odpowiedział w ciągu 90 sekund…”;
**E** = „Nie udało się wyświetlić streamu”.

| Kod | Co znaczy | Gdzie powstaje | Ekran |
| --- | --- | --- | --- |
| `close.host_unavailable` | Aplikacja nie miała danych hosta, gdy miała zamknąć poprzednią sesję. | `ConsoleActivity` → `closePreviousSession`, początek | A |
| `close.retained_not_closed` | Stream trzymany w tle zgłosił, że zamknięcie całej sesji się nie udało. | `closePreviousSession`, ścieżka `retained-live` | A |
| `close.retained_termination_unavailable` | Koordynator streamu w tle odmówił rozpoczęcia zamykania. | `terminateLiveRetainedForReplacement` | A |
| `close.retained_interrupted` | Oczekiwanie na zamknięcie streamu w tle zostało przerwane. | `terminateLiveRetainedForReplacement` | A |
| `close.retained_timeout` | Stream w tle nie potwierdził zamknięcia w ciągu 60 sekund. | `terminateLiveRetainedForReplacement` | A |
| `close.retained_changed_before_close` | Stream w tle nie miał już kontrolera, a jego stan zmienił się przed zamknięciem awaryjnym. | `closePreviousSession`, ścieżka `reconnect-fallback` | A |
| `close.reconnect_fallback_not_closed` | Awaryjne zamknięcie przez host (dla streamu czekającego na ponowne połączenie) nie zostało potwierdzone. | `closePreviousSession`, ścieżka `reconnect-fallback` → `terminateKnownControlPlane` | A |
| `close.direct_not_closed` | Zamknięcie sesji bezpośrednio na hoście (bez streamu w tle) nie zostało potwierdzone. | `closePreviousSession`, ścieżka `direct-nonretained` → `terminateKnownControlPlane` | A |
| `close.unexpected_error` | Zamykanie przerwał nieoczekiwany wyjątek; jego klasa jest w `orchestration.close_failed` (`error_type`). | `SessionOrchestrator.closeCompetingSession` | A |
| `close.snapshot_not_idle_after_close` | Zamknięcie zwróciło sukces, ale aplikacja lokalnie nadal widziała inną sesję (żywy stream, zapis zawieszenia lub ponownego połączenia). | `SessionOrchestrator.closeCompetingSession` po `CLOSED` | A |
| `switch.not_eligible` | Przełączenie gry w żywym streamie nie mogło się zacząć, a zgoda na zamknięcie była już udzielona. | `switchRetainedProviderGame` | A |
| `switch.refused_not_eligible` | Ekran gry odrzucił przełączenie jako niedozwolone po jego rozpoczęciu, a zgoda na zamknięcie była już udzielona. | `switchRetainedProviderGame`, powód z `Game` | A |
| `switch.interrupted` | Oczekiwanie na wynik przełączenia gry zostało przerwane. | `switchRetainedProviderGame` | A |
| `switch.timeout` | Ekran gry nie zgłosił wyniku przełączenia w ciągu 60 sekund. | `switchRetainedProviderGame` | A |
| `switch.provider_stop_timeout` | Host nie zdążył zatrzymać poprzedniej gry, bo ta nie miała jeszcze procesu. | `switchRetainedProviderGame`, powód `game_stop_timeout` | B |
| `switch.failed` | Przełączenie gry nie powiodło się z innego powodu; powód jest w polu `detail`. | `switchRetainedProviderGame`, powód z `Game` | A |
| `uncertain.recovery_required` | Host wymaga odzyskania sesji, a wybrana gra nie jest tą, która działa. | `SessionOrchestrator.evaluate`, pierwszy warunek | C |
| `uncertain.preparing_not_switchable` | Trwa przygotowanie streamu w tle bez gry, ale nie da się do niego przełączyć wybranej gry. | `SessionOrchestrator.evaluate`, gałąź `PREPARING` | C |
| `uncertain.preparing_refresh_failed` | Trwa przygotowanie streamu z grą, a odświeżenie stanu z hosta się nie udało. | `SessionOrchestrator.evaluate`, gałąź `PREPARING` | C |
| `uncertain.preparing_not_switchable_after_refresh` | Po odświeżeniu stanu z hosta przełączenie przygotowywanego streamu nadal nie jest możliwe. | `SessionOrchestrator.evaluate`, gałąź `PREPARING` | C |
| `uncertain.refresh_failed` | Stan sesji był niepewny, a odświeżenie z hosta się nie udało. | `SessionOrchestrator.awaitFreshSession` | C |
| `uncertain.still_uncertain_after_refresh` | Odświeżenie się udało, ale stan nadal był niepewny. | `SessionOrchestrator.awaitFreshSession` | C |
| `launch.target_unresolved` | Dla wybranej gry nie dało się ustalić celu uruchomienia (aplikacji w Sunshine). | `ConsoleActivity.activatePlayniteItem`, panel boczny | C (panel) |
| `orchestration.pass_limit` | Decyzja o uruchomieniu kręciła się w kółko i przerwano ją po dziewięciu przebiegach. | `SessionOrchestrator.evaluate` | D |
| `orchestration.executor_rejected` | Nie dało się uruchomić zadania w tle. | `SessionOrchestrator.execute` | D |
| `attach.resume_intent_missing` | Stream w tle nie miał ekranu gry, a nie było zapisanych danych, by go otworzyć. Nic nie zamknięto. | `ConsoleActivity.attachPresentationForSwitch` | E |
| `attach.start_activity_failed` | System nie otworzył ekranu gry dla streamu w tle. Nic nie zamknięto. | `ConsoleActivity.attachPresentationForSwitch` | E |
| `support.previous_not_closed` | Przy uruchamianiu instalatora gry nie udało się zamknąć innej sesji na hoście. | `ConsoleActivity.beginInstallationSupportStream` | A |
| `support.host_not_ready` | Przy uruchamianiu instalatora gry host nie osiągnął gotowości. | `ConsoleActivity.beginInstallationSupportStream` | D |

## 5. Tabela klasyfikacji

Wypełnij jeden wiersz na awarię. Kolumna „Co naprawdę działało” jest najważniejsza:
to ona rozstrzyga, czy UI mówiło prawdę.

| Nr | Data i godzina | Kod | Co zrobił użytkownik | Co pokazało UI | Co naprawdę działało na PC | `orchestration_id` | Uwagi |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | | | | | | | |
| 2 | | | | | | | |
| 3 | | | | | | | |
| 4 | | | | | | | |
| 5 | | | | | | | |
| 6 | | | | | | | |
| 7 | | | | | | | |
| 8 | | | | | | | |
| 9 | | | | | | | |
| 10 | | | | | | | |
| 11 | | | | | | | |
| 12 | | | | | | | |

Na koniec policz wystąpienia każdego kodu („kod → liczba”). Ta tabela trafi do MW-04.

## 6. Przypadki zaobserwowane poza tabelą

Zdarzenia bez eksportu diagnostyki, ale istotne dla modelu sesji (MW-04/MW-05):

| Nr | Data | Co się stało | Co widział użytkownik | Hipoteza / gdzie szukać |
| --- | --- | --- | --- | --- |
| O1 | 2026-10-05 18:03 | Klient uruchomił stream gry (Civilization V) przez adb, po czym proces aplikacji został ubity (`am force-stop`) bez zamknięcia sesji. | Na hoście stream pozostał otwarty (gra i streamer działały), a po ponownym uruchomieniu aplikacja pokazywała hosta tylko jako „Online” — bez informacji o działającej sesji. | Po śmierci procesu klient nie wznawia ani nie pokazuje sesji hosta; obserwacja hosta nie trafia do stanu „nasza/obca sesja działa” (MW-04 §3.1 „Gra na hoście”, §4 wiersz „Brak transportu, na hoście nasza sesja”). Sprawdzić `SessionStateResolver` + `SuspendedSessionStore` po force-stop i co Gateway raportuje po utracie klienta. |
| O2 | 2026-10-05 18:0x | Próba zamknięcia streamu skrótem klawiszowym na hoście nie zadziałała. | Stream nie został zamknięty; konieczny restart. | Host: obsługa skrótu zamknięcia w Host Control / streamerze po utracie klienta (sesja bez podłączonego klienta). Osobne zgłoszenie po stronie hosta. |
| A1 | 2026-10-05 19:35 | Start Pulpitu z panelu hosta; klient przerwał połączenie w fazie `CONNECTING_STREAM` (Wstecz ~15 s po starcie, zanim Sunshine skończył przygotowanie sesji, ~8 s). | Ekran ładowania → powrót do menu; host pokazany jako „Online”. | Klient: `launch_transition CANCELLED` → `runtime.stop_requested` → `stream.stage_failed stage=4 reason=-1` (RTSP PLAY). Host (Sunshine): „Ping Timeout / brak UDP” to skutek rozłączenia klienta, nie zapory; sesja zakończona 19:35:41. Gateway **nie zwolnił** rekordu zarządzanego streamu (patrz A2). |
| A2 | 2026-10-05 19:41 | Ponowny start Pulpitu 6 min później. | „Desktop failed: 0”, przejście do `ERROR` po 3 s; ekran błędu bez czytelnej przyczyny. | Gateway: `POST /control/operation/acquire` → **409 `managed_stream_conflict`** — host nadal trzymał zarządzany stream z A1 mimo zakończenia sesji w Sunshine. Po stronie klienta kod przyczyny MW-01 nie rozróżnia 409 (`reason=0`). Wymaga: (host) zwolnienie rekordu po zakończeniu sesji Sunshine / po `runtime.stop`, (klient) osobny kod przyczyny dla konfliktu operacji. |
| A3 | od 2026-09-29 (ciągle) | `GET /api/v1/session/observation` i `/discord/status` dla profilu BASIA (rodzic, PIN) odpowiadają **403 `PermissionError`** niemal zawsze (2 sukcesy 2026-10-05 12:37Z, 90+ odmów tego dnia); inne trasy tego profilu działają (library, pin/verify, session/ensure, operation/acquire). | Klient nigdy nie widzi obserwacji sesji hosta → „Online” bez informacji o działającej grze/sesji (źródło O1). | Gateway `authorize_profile(require_pin=True)` → `require_profile_unlock` (klucz: klient × profil × `X-MoonWaker-Profile-Session`). Do sprawdzenia: czy pollery „transition”/„console” wysyłają żądania przed odblokowaniem PIN-em albo z inną tożsamością klienta niż ta, która zweryfikowała PIN; log Gatewaya `C:\Program Files\MoonWaker\gateway\logs\gateway-diagnostics.jsonl`, log klienta `no_backup/diagnostics/events.jsonl`. |

### Wnioski z HOST-GW1 (2026-10-05, diagnoza w kodzie Gatewaya; przegląd w toku)

- **A1→A2 (409):** po przerwaniu w `CONNECTING_STREAM` host trzymał rekord zarządzanego
  streamu, a Sunshine zostawiał aplikację „Desktop" w pauzie (resumable). Poprawka hosta:
  przy `resume/reconnect/warm_up` tego samego właściciela (klient, aktor, profil
  wykonawczy, aplikacja, sesja dziecka) rekord może zostać przepięty na nowy
  `stream_session_id`, gdy snapshot Vibepollo dowodzi braku żywego transportu
  (`session.count == 0`, wszyscy klienci `connected: false`); inne przypadki nadal 409.
- **A3 (403):** host działa zgodnie z projektem — dzierżawa PIN jest na proces aplikacji
  (`X-MoonWaker-Profile-Session`), a każde 403 poprzedza `pin/verify` w tym samym procesie:
  pollery obserwacji sesji, statusu Discord i audio Discord startują, zanim użytkownik poda
  PIN. Zadania po stronie klienta (do MW-05 K3/K4 lub osobny bilet):
  1. nie uruchamiać pollerów profilu `pin_required` przed `pin/verify` 200 (albo mapować
     403 „PIN verification required" na stan „zablokowany", nie „niedostępny");
  2. po anulowaniu w `CONNECTING_STREAM` po już zakończonym `operation/complete` klient ma
     wysłać `full_quit` albo zachować materiał do wznowienia (`stream_session_id`,
     generacja) — dziś porzuca działającą, zapauzowaną aplikację Sunshine;
  3. kod przyczyny MW-01 dla 409 `managed_stream_conflict` (dziś `reason=0`);
  4. „Online" bez informacji o sesji po odblokowaniu PIN-em nie wynika z 403, lecz z
     uzgadniania sesji po stronie klienta (`SessionStateResolver`/`SuspendedSessionStore`)
     — zakres MW-04 §4 (wiersz „Brak transportu, na hoście nasza sesja").

### Weryfikacja HOST-GW1 na żywo (2026-10-05, host 0.7.124, TV przez adb; czasy UTC)

| Punkt | Wynik | Dowód |
| --- | --- | --- |
| (b) A1→A2 | **NIEROZSTRZYGNIĘTE** — przepięcie zablokowane przez znany problem providera | 18:57:08 `control-operation-permitted intent=launch generation=216 stream_session_id=2eddec3d…`; Wstecz w `CONNECTING_STREAM` 18:57:12 (klient: `runtime.stop_requested`, potem `Video stream start failed: -5`); Sunshine „Session pausing" + „Session ended" 18:57:29. Drugi start 18:59:06 (po 97 s) i trzeci 19:02:11: `POST /control/operation/acquire` → **409 `retained_process_unconfirmed`** (406 ms / 375 ms). Brak `control-operation-permitted intent=resume`, brak `control-stream-transport-rebound`, stream nie ruszył; klient: „Nie udało się uruchomić Desktop (error 0)", `stream.stage_failed stage=-1 reason=0`. |
| (c) pełne zamknięcie | **NIEWYKONANE** — nie było działającego streamu | Zastępczo „Zamknij stream" z panelu hosta 19:04:12: `acquire` → 409 `retained_process_unconfirmed` (0 ms), `reconcile` → 409 `stale_operation`. Sesja Sunshine zakończona wcześniej sama (18:57:29); `serverinfo`: `currentgame=0`, `SUNSHINE_SERVER_FREE`. |
| (d) `lock-held-slow` | **OK** | 0 zdarzeń `lock-held-slow` i 0 `observation-slow` w oknie 18:55–19:07. |
| (e) 403 a PIN | **OK** | Uruchomienie 19:05:14, bramka PIN 19:05:26, `pin/verify` 200 o 19:05:44: przed — 12× 403 (`session/observation` 5, `discord/audio/stream` 5, `discord/status` 2), po — 0 (55 s). Pierwsze wejście (18:55:10–18:56:17, ~50 s na bramce): 22× 403 przed, 0 po przez 9 min. |

Uwagi:

- W (b) nie wrócił `managed_stream_conflict` z A2: żądanie przeszło warunek właściciela
  i odpadło dopiero na kontroli providera gry (`_child_time_current_game` ani `idle`, ani
  `active`). Odmowa przyszła po ~0,4 s, bez `observation-slow` — to nie 3-sekundowy
  timeout; obserwacja po stronie klienta w tym czasie: `observation_state=IDLE`,
  `app_id=0`, `process_evidence=PENDING`.
- Ta sama kontrola blokuje też „Zamknij stream", więc rekord zarządzanego streamu
  (generacja 216) pozostał w Gatewayu mimo braku sesji w Sunshine — kolejny start Pulpitu
  z tego klienta prawdopodobnie znów dostanie 409.
- Okno błędu „Błąd połączenia" nie zamyka się klawiszem Wstecz (tylko OK).

### Weryfikacja HOST-GW1 na żywo — host 0.7.125 (2026-10-05, Gateway uruchomiony 19:30:55Z; czasy UTC)

| Punkt | Wynik | Dowód |
| --- | --- | --- |
| (0) stan po restarcie | rekord nadal trzymany | Od 19:30 brak `control-stream-idle-reconciled` (w całym oknie 0); `control.stream.generation = 216` o 19:31:55. |
| (1) „Zamknij stream" na zaległym rekordzie | **OK** | 19:34:45.957 `control-operation-permitted intent=full_quit generation=217 stream_session_id=2eddec3d…` → 19:34:48.526 `control-operation-terminal state=succeeded`; Sunshine 19:34:46: „Restoring retained audio state after terminal app end", „Virtual display cleanup completed after app termination"; potem `control.stream = null`. Bez 409. |
| (2) A1→A2 | **OK** | Start 19:35:39 `intent=launch generation=218 stream_session_id=652574b4…`; Wstecz w `CONNECTING_STREAM` 19:35:43 (`Video stream start failed: -5`); Sunshine „Session pausing" 19:35:59.254, „Session ended" 19:35:59.758. Drugi start po 82 s: 19:37:21.800 `control-operation-permitted intent=resume generation=219 stream_session_id=a2120deb…` + 19:37:21.801 `control-stream-transport-rebound generation=218 stream_session_id=652574b4…`; logcat 19:37:33 `Starting video stream...`, „Received first video packet after 800 ms"; Sunshine „Session resuming for app [Desktop]", „CLIENT CONNECTED". Bez 409, bez twardego resetu; stream grał ~95 s. |
| (3) pełne zamknięcie z działającego streamu | **OK** | Wstecz → ekran „Aktywna sesja · Desktop" → panel hosta → „Zamknij stream" + potwierdzenie: 19:39:09.874 `control-operation-permitted intent=full_quit generation=220 stream_session_id=a2120deb…` → 19:39:16.886 `state=succeeded`; Sunshine 19:39:09.7 „CLIENT DISCONNECTED", 19:39:10.5 „Session ended", 19:39:12.0 „Virtual display cleanup completed after app termination"; `control.stream = null`, `control.generation = 220`. |
| (4) `lock-held-slow` / 409 | **1× `lock-held-slow`** | 19:37:21.801 `lock-held-slow duration_ms=797 reason=holder-control_operation_acquire-L4657` — dokładnie na `acquire` z przepięciem (odczyt snapshotu streamera pod blokadą Gatewaya; `acquire` trwał 797 ms). Poza tym 0× `lock-held-slow`, 0× `observation-slow`, 0× 409 w oknie 19:30–19:40. 403: 8× przed `pin/verify` (19:32:44), 0 po. |

Uwagi:

- Korekta do poprzedniej weryfikacji: `serverinfo` dla niesparowanego zapytania pokazywał
  `currentgame=0`, ale aplikacja Desktop pozostawała w Sunshine wstrzymana aż do
  `full_quit` o 19:34:46 (dopiero wtedy „app termination"). „Session ended" nie dowodzi
  zakończenia aplikacji — dowodem jest „Virtual display cleanup completed after app
  termination".
- Housekeeping nie zwolnił sam rekordu z przerwanego startu (ani generacji 216 przez
  ~4 min po restarcie, ani 218 przez 82 s) — zgodnie z warunkiem „aplikacja streamera nie
  działa"; rekord zwalnia dopiero `full_quit` albo przepięcie przy `resume`.
- Stan końcowy: brak sesji i brak aplikacji Desktop w Sunshine (ostatnie wpisy po
  19:39:12 to tylko synchronizacja biblioteki), `control.stream = null`.

### Weryfikacja na żywo — host 0.7.126 + integracja (2026-10-06, APK z gałęzi integracyjnej; czasy UTC)

Stan wyjściowy 13:36: `control.stream = null`, `control.generation = 222`; APK zainstalowany („Success").

| Punkt | Wynik | Dowód |
| --- | --- | --- |
| (a) A1→A2 | **OK** | Start 13:38:21.890 `intent=launch generation=223 stream_session_id=b9c86edc…`; Wstecz w fazie „Przygotowywanie komputera i streamu" 13:38:26 (`Video stream start failed: -5`); Sunshine „Session pausing" 13:38:43.772, „Session ended" 13:38:44.599. Drugi start po ~80 s: 13:40:09.310 `control-operation-permitted intent=resume generation=224 stream_session_id=1b762798…` + `control-stream-transport-rebound generation=223 stream_session_id=b9c86edc…` (`acquire` 1702 ms); logcat 13:40:15 `Starting video stream...`, „Received first video packet after 1100 ms"; Sunshine „Session resuming for app [Desktop]". Bez 409. Skan providera w tej chwili: ostatnie `running-scan.status-changed` przed `acquire` = 13:38:57.436 `status=complete reason=verified`. |
| (b) pełne zamknięcie | **OK** | Panel hosta → „Zamknij stream" + potwierdzenie: 13:41:42.028 `control-operation-permitted intent=full_quit generation=225 stream_session_id=1b762798…` → 13:41:49.245 `state=succeeded`; Sunshine 13:41:42.353 „Restoring retained audio state after terminal app end", 13:41:43.499 „Virtual display cleanup completed after app termination"; `control.stream = null`, `control.generation = 225`. |
| (c) `lock-held-slow` | **OK (0)** | 0 w oknie 13:36–13:42, także przy `acquire` z przepięciem. |
| (d) 403 a PIN | **OK** | 10× 403 (`discord/audio/stream` 5, `session/observation` 3, `discord/status` 2), ostatnie 13:37:21.954; `pin/verify` 13:37:25.086; po nim 0. |
| (e) kafel hosta po zamknięciu | **OK** | 13:41:41 „Zamykanie streamu…", 13:41:48 „Online" (bez Odśwież). |

Uwagi:

- Pozostałe ostrzeżenia w oknie: 1× `observation-slow` 13:38:37 (`provider-3000`, GET
  `session/observation` → 503) oraz `GET /library` → 502 po 8 s, oba w trakcie pierwszego
  startu; skan providera w tym czasie przełączał się `complete` ↔ `unavailable`
  (`process_scan_unavailable`). 409: 0.
- Po Wstecz z działającego streamu (ekran „Aktywna sesja · Desktop") fokus stoi na
  kaflu gry, nie na kaflu hosta — do panelu hosta trzeba przejść ręcznie.
- Stan końcowy: po ostatnim starcie (13:40:15) w Sunshine „Session ended" 13:41:42 i
  „app termination" 13:41:43, później tylko synchronizacja biblioteki; `control.stream = null`.
