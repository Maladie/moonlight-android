# MoonWaker — plan hardeningu i kryteria akceptacji

## Zakres i status dowodów

Repozytorium: `Maladie/moonlight-android`.
Gałąź: `codex/multi-profile-remote-sign-in`.
Analizowany commit: `00366fd92a1f283bf3aa04171ed3455ce62897ac`.
Data raportu: 8 września 2026.

Analiza statyczna klienta Android, Gateway i wybranych ścieżek usług Windows. Bez modyfikacji repozytorium, commitów ani wdrożenia. Nie uruchomiono buildów, testów repozytorium ani scenariuszy na rzeczywistym Windows/TV. Opis commita deklaruje 1011 testów Android, 191 Gateway, 248 Bridge oraz build APK debug i instalatora hosta 0.7.95; nie jest to niezależny wynik tego audytu. Opis zaznacza oczekującą pełną akceptację na TV.

Ten plik jest samodzielnym planem implementacji i testów. Rozwinięty raport przyczyn i źródła z odnośnikami znajdują się również w odpowiedzi w rozmowie. Przy zmianie HEAD przed implementacją trzeba sprawdzić, czy ustalenia nadal dotyczą aktualnego kodu.

P0 oznacza blokadę wydania spełniającego opisany kontrakt produktu, nie ocenę CVSS. P1 oznacza podstawową poprawność lub ciągłość sesji. P2 oznacza odporność i jakość przejścia. „Potwierdzone” oznacza potwierdzoną konstrukcję/ścieżkę w kodzie, a nie reprodukcję na urządzeniu.

## 1. Kontrakt operacji

| Intencja | Gra na hoście | Transport | Prezentacja i uprawnienia |
|---|---|---|---|
| Home aplikacji | Pozostaje uruchomiona | Ten sam | Karuzela, brak wejścia do gry z ukrytego widoku |
| Home TV | Pozostaje uruchomiona | Zachowany zgodnie z jawną polityką retencji | Obraz/dźwięk/wejście odłączone od nieaktywnego widoku |
| Wybór hostów bez zmiany hosta | Bez zmiany | Ten sam | Powrót do tego samego kontekstu |
| Zamknij grę | Zatrzymaj dokładnie tę grę | Zachowaj | Przesłona, następnie karuzela; status streamu pozostaje aktywny |
| Zmień A na B | Potwierdź stop A, uruchom B | Ten sam | Artwork B; odsłonięcie dopiero po gotowości B |
| Zamknij stream i grę | Zakończ dokładnie tę sesję gry | Zatrzymaj lokalnie i zakończ sesję hosta | Home natychmiast; „Online” dopiero przy potwierdzeniu stanu hosta |
| Limit / koniec okna / cofnięcie uprawnienia dziecka | Zakończ jego zweryfikowaną grę | Rozłącz jego sesję | Zablokuj wejście i odnowienie; nie przełączaj na rodzica |
| Warm-up | Nie uruchamiaj przypadkowej gry | Przygotuj jeden transport | Brak wejścia do pulpitu; preparing → ready na podstawie transportu |

Nie utożsamiać wyjścia do Home z wylogowaniem profilu ani z Windows logoff. Zmiana aktora (rodzic/dziecko) nie może dziedziczyć uprawnień poprzedniego aktora tylko dlatego, że oba profile używają tego samego konta Windows.

Natychmiastowy efekt lokalny nie jest dowodem zakończenia operacji na odłączonym hoście. Nowa gra może zostać wybrana od razu, lecz destrukcyjne sprzątanie poprzedniej generacji musi być odseparowane od nowego startu.

## 2. Rejestr ustaleń

### H01 — P0 — Limit dziecka zachowuje stream

Pewność: potwierdzona niezgodność ścieżki z wymaganiem.

Źródła: `Game.java::finishChildSession`, `endManagedGameKeepingStream`, `retainNeutralStreamAfterNaturalGameStop`; `wakeplay_gateway.py::_child_time_finish`, `_child_time_stop_execution`; `GameProviderBridge.py::enforce_child_session`.

Android po końcu czasu kieruje do kończenia gry z zachowaniem streamu. Hostowy mechanizm limitu również celowo zarządza procesem gry, a nie streamem. Nie dowodzi to dalszej możliwości grania: istnieje blokada wejścia i kończenie zweryfikowanego procesu. Dowodzi braku wymaganego rozłączenia.

Naprawa: rozdzielić powód kończenia przydziału czasu od polityki transportu. Zwykły stop/switch może zachować transport; wygaśnięcie lub cofnięcie dostępu musi zakończyć dokładnie powiązany stream i zablokować jego ponowne użycie przez nieuprawnionego aktora. Egzekwowanie również na hoście, gdy Android nie odpowiada. Opóźnione wygaśnięcie nie może rozłączyć nowej sesji rodzica na tym samym urządzeniu.

Testy: T16–T21.

### H02 — P1 — Pełne zamknięcie dziecka odpytuje zabronione health

Pewność: potwierdzona niespójność caller–API.

Źródła: `Game.java::stopVerifiedProviderGame`; `PlayniteTransitionGateway.connect(..., profileId)` i `snapshot`; lista dozwolonych operacji dziecka w Gateway.

Czteroargumentowy connect domyślnie wybiera `childSession=false`. Snapshot wtedy odpytuje health profilu, którego profil dziecka nie może czytać. Skutkiem jest błąd przed zakończeniem streamu, nie uzyskanie uprawnień rodzica.

Naprawa: przekazywać pełny kontekst aktora/wykonania do jednej fabryki Gateway. Nie rozszerzać uprawnień dziecka do health lub operacji rodzica, aby ukryć błąd klienta. Testować rzeczywisty zestaw wywoływanych endpointów.

Testy: T09, T16, T22.

### H03 — P1 — Pełne zamknięcie czeka na sieć przed lokalnym stopem

Pewność: potwierdzona kolejność efektów.

Źródła: `Game.java::terminateWholeSessionVerified`, `verifySunshineSessionStopped`, `finishWholeSessionTermination`, `closeStreamWithPrivacy`.

Najpierw weryfikacja/stop gry, następnie zakończenie aplikacji streamera i polling, dopiero po sukcesie lokalne zatrzymanie połączenia. Błąd providera może przywrócić zachowany stream. Jest to sprzeczne z intencją „zamknij stream teraz”.

Naprawa: natychmiastowe lokalne zasłonięcie/blokada wejścia/zatrzymanie transportu i powrót Home; osobna skorelowana operacja hostowa. Stan hosta `STOPPING` lub `UNCONFIRMED` do obserwacji skutku. Wznowienie kolejki nowych startów dopiero po odpowiedniej barierze zakończenia, bez zamykania nowszej sesji.

Testy: T09–T12, T24.

### H04 — P1 — Świeży release nie zachowuje streamu w tle

Pewność: potwierdzona różnica konfiguracji produktu.

Źródła: `BackgroundStreamPreferences.readMinutes` — domyślnie debug 5, release 0; `Game.canParkBackgroundStream`, `onStop`.

Naprawa: spójna polityka debug/release, jawne ustawienie produktu oraz migracja rozróżniająca brak ustawienia od świadomego wyłączenia. Określić limit retencji; nie wykorzystywać go jako limitu czasu dziecka.

Testy: T05–T08, T27.

### H05 — P1 — Żywotność transportu związana z Game Activity

Pewność: potwierdzona własność zasobów; moment wystąpienia zależy od lifecycle.

Źródła: `Game.java` — właściciel `NvConnection`, `onDestroy`; `RetainedStreamSessionCoordinator` — odwołanie do kontrolera; `BackgroundStreamService` — utrzymanie procesu/timer, nie niezależny właściciel połączenia.

Zachowany stan logiczny nie zapewnia zachowania połączenia po zniszczeniu właściciela Activity. Obecny kod wtedy oznacza konieczność reconnect i zatrzymuje transport.

Naprawa: stabilny właściciel runtime transportu poza Activity, z jedną szeregowaną ścieżką start/stop. Game i Home dołączają/odłączają widok, dekoder, dźwięk i wejście. Zachować istniejący koordynator jako fasadę, nie tworzyć konkurencyjnej maszyny stanów. Po śmierci całego procesu nie udawać zachowanego socketu — wykonać kontrolowany reconnect.

Testy: T05–T08, T25–T26.

### H06 — P1 — Status transportu zależy od zgodności gameId

Pewność: potwierdzona zależność logiczna; scenariusz wymaga rozbieżnych obserwacji.

Źródła: `SessionStateResolver.resolve`; `ConsoleActivity.resolveSessionSnapshot`, `hostStatus`, `consoleHostState`.

`retainedLive` zależy od zgodności obserwowanego gameId. Przy nieświeżej/niespójnej obserwacji gry może zostać odrzucony żywy transport. `UNCERTAIN` może być wybrane przed znanym stanem hosta. Interfejs dodatkowo wylicza status kilkoma ścieżkami.

Naprawa: niezależne osie transport/gra/prezentacja/autoryzacja. Niepewna gra nie zeruje potwierdzonego transportu. Kafelek, belka hosta i decyzja o uruchomieniu korzystają z tej samej projekcji. Obserwacje muszą mieć pochodzenie, rewizję i świeżość.

Testy: T02–T04, T13–T15, T28.

### H07 — P1 — Czekanie na porty streamu przed Windows sign-in

Pewność: potwierdzona kolejność; awaria zależna od konfiguracji hosta.

Źródła: `HostLaunchPreflight.runInternal`; `ConsoleActivity.createHostLaunchPreflight`, effects `prepareHost`; `HostReadiness.await`.

Pierwsza gotowość sieci wymaga hosta ONLINE i portu streamera. Jeśli Gateway/Broker pracuje przed logowaniem, a streamer dopiero po logowaniu, przygotowanie zatrzymuje się przed operacją, która mogłaby uruchomić streamer.

Naprawa: WoL → dostępność Gateway → zgoda/tożsamość → poprawna interaktywna sesja Windows → komponenty profilu → gotowość streamera → połączenie. Osobne deadline i komunikat błędu dla każdego etapu.

Testy: T01, T29–T31.

### H08 — P1 — Timeout lub brak potwierdzenia logowania może przepuszczać preflight

Pewność: potwierdzone gałęzie kodu.

Źródła: `HostLaunchPreflight.awaitInteractiveSession`, `allowsManualSignIn`.

Metoda używa `null` jako „można kontynuować”; zwraca go również przy niektórych niegotowych stanach i po upływie limitu oczekiwania. Wyjątek transportowy jest obsługiwany inaczej. Nie jest to dowód obejścia zabezpieczeń Windows; jest to błędny kontrakt gotowości automatycznego startu.

Naprawa: jawne wyniki READY / DENIED / REQUIRES_INTERACTION / TIMED_OUT / UNAVAILABLE / CANCELLED. W trybie automatycznym tylko potwierdzone READY właściwego profilu pozwala uruchamiać grę. Tryb ręczny oddzielny, jawnie wybrany. Nie pętlić automatycznych prób błędnym hasłem.

Testy: T29–T32.

### H09 — P1 — Callback stop może wyprzedzać faktyczne conn.stop

Pewność: potwierdzona możliwość w sekwencji współbieżnych wywołań.

Źródła: `Game.stopConnection(Runnable)`, `finishWholeSessionTermination`.

Pierwsze wywołanie ustawia flagi niepołączone przed zakończeniem stopu w wątku roboczym. Następny caller może dostać swój callback wcześniej. Pełne zamknięcie również zgłasza sukces przed końcem lokalnego stopu. Nie jest to dowód dwóch jednoczesnych natywnych połączeń; niższa warstwa może je serializować.

Naprawa: jeden współdzielony wynik zakończenia dla generacji transportu. Każdy caller podpina się do tego samego wyniku; nie rekonstruuje zakończenia z boola `connected=false`. Oddzielić „żądanie przyjęte”, „efekt lokalny”, „host potwierdzony”.

Testy: T10–T12, T24–T25.

### H10 — P1 — Gotowość i bieżąca gra składane z różnych chwil

Pewność: ryzyko wyścigu potwierdzone konstrukcją, nie nagraniem błędnego odsłonięcia.

Źródła: `PlayniteTransitionGateway.snapshot`; `ConsoleStreamTransitionCoordinator.applySnapshot`.

Osobne odczyty health/current/readiness są składane w jeden obiekt bez wspólnej rewizji. Lokalny identyfikator przejścia nie dowodzi, że bieżąca gra i gotowe okno pochodzą z tej samej hostowej generacji.

Naprawa: atomowa migawka hostowa albo wspólna rewizja i odrzucanie niespójnej kombinacji. Tożsamość procesu obejmuje PID i czas utworzenia/token, nie sam PID czy nazwę. Odsłanianie wymaga zgodnego celu, gotowości i bieżącej generacji renderowania.

Testy: T13–T15, T23, T28.

### H11 — P2 — Oczekiwanie na przesłonę może zgubić wykonanie operacji

Pewność: potwierdzony mechanizm callbacka; efekt wizualny zależy od renderowania.

Źródła: `ConsoleStreamLoadingView.doAfterNextFrame`; `StreamHomeActivity.onCreate`.

Dwa `postOnAnimation` nie są potwierdzeniem wysłania klatki do kompozytora. Jeśli widok zostanie zatrzymany/odłączony, callback operacji może zostać pominięty bez jawnego wyniku anulowania. StreamHome ma już lepszy punkt integracji z frame-commit na API 29+.

Naprawa: jedna bariera przesłony ze skorelowaną generacją oraz wynikiem wykonano/anulowano/niedostępne. Na API 29+ wykorzystać frame-commit przy renderowaniu sprzętowym; sam callback oznacza przesłanie klatki, nie gwarancję fizycznej prezentacji. Bez renderowalnego UI nie uzależniać odcięcia dostępu dziecka od narysowania widoku.

Testy: T04, T08, T15, T18, T23.

## 3. Docelowe reguły architektury

Nie dodawać kolejnej kompletnej maszyny stanów nad istniejącymi `SessionOrchestrator`, `RetainedStreamSessionCoordinator` i `LaunchTransitionController`. Przypisać im jednoznaczne zakresy: intencja/operacja, rzeczywisty transport, prezentacja i dopuszczenie wejścia. Gateway i Bridge odpowiadają za autoryzację oraz obserwowane skutki hostowe.

Kontekst operacji powinien być niezmienny i obejmować: hostId, actorProfileId, executionProfileId, transportSessionId/generację, operationId, canonicalGameId, tożsamość procesu, rewizję uprawnień, identyfikator uruchomienia hostowej usługi. Nie wszystkie pola muszą być naraz znane; brak ma być jawny, a nie zastępowany bieżącym mutable stanem UI.

Tylko właściciel aktualnej generacji może zamknąć transport, zmienić grę, odsłonić obraz albo opublikować wynik operacji. Odebranie odpowiedzi nie wystarcza — trzeba sprawdzić, do której generacji ona należy.

Powtórzenie żądania z tym samym operationId zwraca ten sam wynik. Utrata odpowiedzi po wykonaniu nie może wywołać drugiego startu ani destrukcyjnego resetu nowszej sesji.

Stan `ONLINE` oznacza osiągalnego hosta bez potwierdzonej aktywnej sesji streamu; przy niepewnej obserwacji jawnie pokazać niepewność. Stan „Stream gotowy” wymaga działającego, lokalnie posiadanego transportu, nie tylko odpowiedzi health lub obecności aplikacji streamera.

Dla profilu dziecka zachowany neutralny transport nie oznacza autoryzacji do sterowania pulpitem Windows rodzica. „Odsłoń teraz” nie może omijać terminalnego cofnięcia dostępu.

## 4. Kolejność pakietów naprawczych

### R0 — Dowody, diagnostyka i regresje przed zmianami

Zamrozić commit bazowy, zebrać wersje APK/hosta/streamera/TV. Dodać liczniki rozpoczęcia i zatrzymania rzeczywistego transportu oraz korelację operacji, nie samych ekranów. Zapisać testy odtwarzające H01/H02/H04/H06/H08/H09. Oddzielić testy zachowania od testów sprawdzających tekst pliku źródłowego.

### R1 — Naprawy punktowe o małym promieniu zmiany

H02: poprawny kontekst Gateway dla dziecka. H04: polityka release/debug. H08: ścisłe wyniki gotowości logowania. Każda zmiana z testem negatywnym i utrzymaniem zakresu uprawnień. Bez dużej ekstrakcji Game w tym samym pakiecie.

### R2 — Jedna semantyka kończenia i bariera zakończenia

H01/H03/H09. Wspólna operacja z jawnym powodem i polityką retain/disconnect. Jeden wynik rzeczywistego stopu transportu, idempotencja hostowego kończenia i blokada spóźnionych efektów. Odcięcie dziecka niezależne od callbacków widoku. Zachować bezpieczną tożsamość procesu przy wymuszonym kończeniu.

### R3 — Prawda o stanie i atomowa obserwacja hosta

H06/H10. Rozdzielić w projekcji transport od gry. Dodać wersjonowaną migawkę hostową. Wszystkie statusy hosta, kafelków i decyzje orchestratora wyprowadzać z tej projekcji. Nie kasować znanego live transportu po błędzie odczytu biblioteki.

### R4 — Właściciel runtime poza Activity

H05. Wydzielić utrzymanie połączenia i szeregowaną kolejkę efektów do stabilnego właściciela. Widoki attach/detach z generacjami powierzchni. Zweryfikować JNI, dekoder, audio, kontrolery i ograniczenia usługi pierwszoplanowej na docelowym TV. Nie zmieniać jednocześnie protokołu hosta i całego UI.

### R5 — Wspólne przygotowanie hosta i warm-up

H07 oraz wspólne wejście launch/warm-up. Jedna aktywna operacja przygotowania na host i profil wykonania; kliknięcie gry w trakcie przygotowania przejmuje tę operację zamiast uruchamiać drugą. Zmiana aktora zawsze ponawia autoryzację. Jawny pełny quit nie może zostać natychmiast odwrócony przez niezamierzony auto-warm-up.

### R6 — Przesłony i akceptacja sprzętowa

H11 oraz walidacja wcześniejszych pakietów. Wspólna bariera klatki, właściwy artwork, pokrycie powrotu do Home i braku powierzchni. Testy Windows/TV w buildzie release i debug; artefakty dokładnie z zaakceptowanego commita.

Zależności: R1 można częściowo równoleglić, lecz R2/R3 powinny ustalić kontrakty przed ekstrakcją R4. R5 i R6 muszą korzystać z ustalonej semantyki R2 oraz projekcji R3. Pliki Game.java i ConsoleActivity.java nie powinny być równocześnie swobodnie modyfikowane przez kilku workerów bez podziału zakresów i końcowego przeglądu integracji.

## 5. Macierz akceptacji

Poniższe przypadki są planem testów, nie wynikami wykonanego przebiegu.

| ID | Scenariusz | Wymagany dowód |
|---|---|---|
| T01 | Host śpi, Windows wymaga logowania, zgoda i hasło istnieją | Jedna operacja: WoL → Gateway → login → komponenty → stream → gra |
| T02 | Zamknij grę A z overlayu | Ta sama generacja transportu; A nie działa; karuzela pokazuje stream aktywny |
| T03 | Home → A na B → Home → B na A | Start transportu dokładnie raz; zero stopów transportu; właściwy proces i kafelek |
| T04 | 100 kolejnych zmian gier | Bez narastających właścicieli, wątków, opóźnionych odsłonięć i błędnych kafelków |
| T05 | Gra → Home TV → powrót | To samo połączenie w przewidzianym okresie retencji; ponowne dołączenie obrazu |
| T06 | Gra → Home aplikacji → powrót | Brak reconnect; wejście nie trafia do gry podczas nawigacji |
| T07 | Gra → wybór hostów → ten sam host | Bez zakończenia transportu i bez zmiany profilu wykonania |
| T08 | Wyjście Home podczas przygotowania, odsłaniania i zmiany gry | Każda operacja ma wynik; brak zgubionego callbacka albo obcej generacji |
| T09 | Pełny quit rodzica i dziecka | Lokalnie transport zatrzymany, host potwierdza zakończenie; dozwolone endpointy |
| T10 | Pełny quit, natychmiast kliknij nową grę | Nowa intencja przyjęta, ale stary stop nie może zamknąć nowej sesji |
| T11 | Zgub odpowiedź po wykonanym stopie | Odczyt stanu/idempotentne ponowienie, bez ślepego ponownego działania |
| T12 | Dwa równoległe stopConnection z zablokowanym fake conn.stop | Oba zakończenia czekają na realny stop; jedno wykonanie natywnego stopu |
| T13 | Opóźnij current gry A, dostarcz readiness B | Brak odsłonięcia i brak przyjęcia mieszanej migawki |
| T14 | Brak Gateway przez kilka odczytów przy działającym streamie | Stream pozostaje aktywny; tylko stan gry staje się niepewny |
| T15 | Stare game-stopped / target-ready po starcie B | B i jego przesłona nie są zmieniane przez zdarzenie A |
| T16 | Dziecko startuje tę samą grę co rodzic w osobnych przebiegach | Identyczna semantyka transportu; różne uprawnienia i czas |
| T17 | Dziecko zmienia gry przed końcem limitu | Transport zachowany; wykorzystany czas nie jest zerowany |
| T18 | Limit dziecka w aktywnej grze i na Home | Natychmiastowa blokada wejścia, odcięcie streamu, zakończenie właściwej gry |
| T19 | Koniec okna godzinowego przy dodatnim limicie dziennym | Takie samo terminalne zakończenie jak po wyczerpaniu budżetu |
| T20 | Limit podczas braku sieci/śmierci aplikacji Android | Host egzekwuje odcięcie i stop, niezależnie od klienta |
| T21 | Opóźnione wygaśnięcie dziecka po autoryzowanym starcie rodzica | Nie kończy nowej gry ani streamu rodzica |
| T22 | Dziecko wywołuje Discord/zarządzanie/granty bezpośrednio w API | Odmowa niezależna od ukrycia elementów UI; brak dziedziczenia sesji rodzica |
| T23 | Zniszczenie widoku przed callbackiem przesłony | Jawny wynik anulowania lub bezpieczne odcięcie, nigdy zawieszone kończenie |
| T24 | Gateway niedostępny przy pełnym quit | Lokalny quit działa; stan hosta nie jest fałszywie potwierdzony |
| T25 | onDestroy konkuruje ze stopem i nową intencją | Jedna generacja właściciela i jeden wspólny wynik stopu |
| T26 | Rekreacja Activity vs śmierć całego procesu | Pierwsza zachowuje runtime po naprawie; druga uczciwie wymaga reconnect |
| T27 | Świeży release, świeży debug i migracja istniejących ustawień | Spójny kontrakt; świadomy wybór użytkownika nie jest nadpisany |
| T28 | Restart Bridge i reset jego licznika zdarzeń | Nowy bootId unieważnia starą rewizję; stan zostaje ponownie uzgodniony |
| T29 | Gateway dostępny, streamer niedostępny przed logowaniem | Logowanie nie jest blokowane przez test portu streamera |
| T30 | Brak zgody, brak hasła, błędne hasło | Brak automatycznego startu i jasny stan wymagający działania |
| T31 | Inny użytkownik Windows aktywny | Brak niejawnego przejęcia sesji; jawny, autoryzowany wybór przełączenia |
| T32 | Timeout / null / anulowanie / późny sukces logowania | Brak fałszywego READY i brak startu po anulowaniu generacji |
| T33 | Warm-up i kliknięcie gry w tej samej chwili | Jeden prepare i jedno połączenie; właściwa ostatnia intencja |
| T34 | Pełny quit przy włączonym auto-warm-up | Brak samoczynnego odtworzenia sesji wskutek powrotu Home |
| T35 | Zmiana biblioteki/odebranie gry podczas gry dziecka | Cofnięcie dostępności, terminalne odcięcie, brak naruszenia innej sesji |
| T36 | Zmiana dnia, DST, korekta zegara i restart Gateway | Zużyty budżet nie znika; harmonogram liczony według jawnej strefy |

Dodatkowo testować co najmniej grę natywną Steam, grę z dodatkowym launcherem i emulator, który może ignorować zwykłe zamknięcie okna. Zwykły stop i wymuszone zakończenie po limicie powinny mieć jawnie różną politykę; nie stosować globalnego zabijania procesów po nazwie.

## 6. Diagnostyka i definicja zakończenia

Każda operacja powinna mieć zapis: operationId, hostId, actorProfileId, executionProfileId, transportSessionId/generacja, gameId/processToken, przyczyna, poprzedni/nowy stan, rewizja migawki, host bootId, czas monotoniczny i czas obserwacji. Nie logować haseł, PIN-ów, tokenów ani nagłówków uwierzytelnienia.

Osobne zdarzenia: request accepted, opaque frame submitted, input blocked, host game stop requested/confirmed, local transport stop requested/completed, host stream stop confirmed, Home frame submitted, new game window ready, new video frame accepted, input reopened.

Wskaźniki: liczba rzeczywistych połączeń i rozłączeń, czas do pierwszej sterowalnej klatki, czas do Home, opóźnienie aktualizacji kafelka, liczba odrzuconych spóźnionych callbacków. Cele czasowe ustalić po pomiarze bazowym; nie wpisywać niezmierzonych deklaracji wydajności jako wyników.

Warunek akceptacji: wszystkie siedem ścieżek użytkownika przechodzi w release na docelowym Windows/TV; H01–H09 mają testy regresji lub jawne zaakceptowane ograniczenie; H10/H11 mają testy wymuszające niekorzystną kolejność i walidację obrazu; żaden test nie traktuje samego tekstu źródła lub flagi UI jako dowodu działania streamu. Przebieg zawiera również utratę odpowiedzi, restart usługi, brak powierzchni i wygaśnięcie dziecka w tle.

## 7. Mapa plików źródłowych

Wszystkie odwołania dotyczą przypiętego commita powyżej.

- `app/src/main/java/com/limelight/Game.java`
- `app/src/main/java/com/limelight/console/ConsoleActivity.java`
- `app/src/main/java/com/limelight/console/SessionOrchestrator.java`
- `app/src/main/java/com/limelight/console/SessionStateResolver.java`
- `app/src/main/java/com/limelight/console/HostLaunchPreflight.java`
- `app/src/main/java/com/limelight/console/HostReadiness.java`
- `app/src/main/java/com/limelight/console/PlayniteTransitionGateway.java`
- `app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java`
- `app/src/main/java/com/limelight/console/StreamHomeActivity.java`
- `app/src/main/java/com/limelight/stream/RetainedStreamSessionCoordinator.java`
- `app/src/main/java/com/limelight/stream/BackgroundStreamPreferences.java`
- `app/src/main/java/com/limelight/stream/BackgroundStreamService.java`
- `app/src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java`
- `app/src/main/AndroidManifest.xml`
- `host-services/gateway/wakeplay_gateway.py`
- `host-services/bridges/playnite/GameProviderBridge.py`
- `host-services/windows-login/login-broker/BrokerCore.cs`
- `host-services/windows-login/login-broker/README.md`

Publiczne kontrakty platformy sprawdzone dodatkowo w dokumentacji Android Developers: Activity lifecycle, Processes and app lifecycle, ViewTreeObserver.registerFrameCommitCallback. Frame commit wymaga renderowania sprzętowego i jest dostępny od API 29; nie jest gwarancją fizycznej widoczności klatki. Usługa pierwszoplanowa nie zapewnia nieśmiertelności procesu.
