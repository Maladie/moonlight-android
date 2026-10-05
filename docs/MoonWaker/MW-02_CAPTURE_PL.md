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

### Na co patrzeć przy hipotezie „zamknięto, a pokazano błąd”

Jeżeli w wycinku widać kolejno `orchestration.close_completed` ze `status` = `CLOSED`,
a zaraz potem `session.failed` z `reason` = `close.snapshot_not_idle_after_close`,
`state` = `UNCERTAIN` i `observation_state` = `PENDING`, to poprzednia sesja **została**
zamknięta. Błąd wziął się stąd, że aplikacja nie zdążyła jeszcze ponownie odpytać hosta.
Zapisz wtedy w kolumnie „Co naprawdę działało”, czy na PC rzeczywiście nic już nie
działało. To jest dokładnie to, co MW-02 ma potwierdzić albo obalić.

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
| `close.snapshot_not_idle_after_close` | Zamknięcie zwróciło sukces, ale stan odczytany zaraz potem nie był ani pusty, ani wybraną grą. | `SessionOrchestrator.closeCompetingSession` po `CLOSED` | A |
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
