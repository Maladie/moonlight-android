# MoonWaker — plan dalszych kroków

Stan na 2026-10-05, gałąź `codex/session-hardening` (`94a3d389`). Plan wynika z porównania
z upstreamem i z analizy dwóch zgłoszonych problemów: niestabilnego reużycia streamu
(komunikat o konieczności zamknięcia poprzedniej sesji, status w UI niezgodny z
rzeczywistością) oraz regresji po poprawkach.

Pozycje są pisane tak, żeby dało się je przenieść jeden do jednego do trackera (MW-13).

## Diagnoza w skrócie

1. **Stan sesji nie ma jednego właściciela.** `ConsoleActivity.resolveSessionSnapshot`
   składa go z sześciu źródeł odświeżanych w różnych chwilach, a UI odpytuje stan, zamiast
   być o nim powiadamiane.
2. **`RetainedStreamSessionCoordinator` to globalny stan**: 22 statyczne pola i 61
   publicznych metod wywoływanych z `Game`, `ConsoleActivity`, `StreamRuntime`
   i `StreamHomeActivity`. Poprawka u jednego wywołującego nie jest znana pozostałym.
3. **Reużycie streamu jest wyjątkiem, zamknięcie domyślną ścieżką**
   (`SessionOrchestrator.evaluate`), a jeden komunikat błędu ma co najmniej sześć przyczyn.
4. **Najbardziej ryzykowna logika jest nietestowalna**: siedzi w anonimowej klasie efektów
   w `ConsoleActivity`, a 41 ze 118 plików testowych sprawdza tekst źródeł, nie zachowanie.
5. **Hardening tego nie obejmuje**: `RULES.md` nakazuje zachować koordynator jako fasadę,
   kryterium S06 („żadna Activity nie odtwarza prawdy z pojedynczych pól") nie jest
   spełnione w kodzie, a monolity w trakcie hardeningu urosły.

## Priorytety

- **P0** — bez tego nie da się diagnozować ani bezpiecznie zamknąć bieżącego etapu.
- **P1** — usuwa przyczynę obu problemów.
- **P2** — obniża koszt dalszej pracy; nie blokuje stabilności.
- **P3** — porządki i rzeczy do zrobienia przy okazji.

## Zestawienie

| ID | Priorytet | Zadanie | Można zacząć teraz | Zależy od |
| --- | --- | --- | --- | --- |
| MW-01 | P0 | Osobne kody przyczyn dla błędów sesji | tak | — |
| MW-02 | P0 | Zebranie i klasyfikacja awarii z fizycznego TV | tak | lepiej po MW-01 |
| MW-03 | P0 | Zamknięcie S07 na fizycznym TV | trwa | — |
| MW-04 | P1 | Projekt jednego modelu sesji i decyzja o regułach | szkic v1 gotowy | MW-02 (dane), zgoda na zmianę reguł |
| MW-05 | P1 | Implementacja modelu sesji w krokach K1–K5; K4 zastępuje S08 | K1 i K2 po MW-03 | MW-03, MW-04 |
| MW-06 | P1 | Testy scenariuszowe zamiast tekstowych | nie | razem z MW-05 |
| MW-07 | P1 | Budżet monolitów: zakaz wzrostu | tak | — |
| MW-08 | P2 | Zaciągnięcie upstreamu v0.5.5 | tak (przygotowanie) | merge po MW-03 |
| MW-09 | P2 | `Game.java` jako cienki adapter | nie | MW-05 |
| MW-10 | P2 | Rozbiór UI `ConsoleActivity` ekran po ekranie | częściowo (MW-10a) | MW-05 |
| MW-11 | P2 | S09 i S10 hardeningu (S08 zastąpione przez K4) | nie | MW-05 |
| MW-12 | P3 | Rozbiór monolitów hosta | nie | MW-11 |
| MW-13 | P3 | Tracker zamiast plików stanu | tak | — |
| MW-14 | P3 | Aktualizacja dokumentu architektury | tak | lepiej po MW-04 |
| MW-15 | P3 | Pozostałe porządki w repo | tak | — |

## P0

### MW-01 — Osobne kody przyczyn dla błędów sesji

**Po co.** `console_previous_session_close_failed` pojawia się w czterech miejscach
`ConsoleActivity` (m.in. `previousSessionCloseFailed`, `presentationLaunchFailed`,
`beginInstallationSupportStream`), a `closePreviousSession` ma trzy ścieżki zamykania
z różnymi wyjątkami sprowadzanymi do tego samego tekstu. Zgłoszenia są dziś
nierozróżnialne.

**Zakres.**
- Każda ścieżka dostaje własny, stabilny kod przyczyny (np. `close.retained_timeout`,
  `close.host_still_running`, `attach.resume_intent_missing`).
- Kod jest widoczny na ekranie błędu drobnym tekstem i trafia do `MoonWakerDiagnostics`
  razem z identyfikatorem orkiestracji.
- `presentationLaunchFailed` dostaje własny komunikat: tam nic nie było zamykane.

**Gotowe, gdy** każde wystąpienie błędu sesji na TV da się przypisać do jednej ścieżki
w kodzie bez czytania logcata.

### MW-02 — Zebranie i klasyfikacja awarii z fizycznego TV

**Po co.** Diagnoza powyżej pochodzi z czytania kodu. Zanim cokolwiek przepiszemy, trzeba
wiedzieć, które ścieżki faktycznie dominują.

**Zakres.**
- Zebrać co najmniej 10 realnych awarii (reużycie streamu, zły status w UI) z BRAVII:
  kod przyczyny z MW-01 i wycinek zdarzeń `orchestration.*`.
- Sprawdzić hipotezę: zamknięcie zakończone statusem `CLOSED`, po którym orkiestrator
  i tak zgłasza błąd, bo snapshot nie pokazuje jeszcze braku sesji
  (`SessionOrchestrator.closeCompetingSession`).
- Wynik: tabela „przyczyna → liczba wystąpień" dołączona do MW-04.

**Gotowe, gdy** wiadomo, które trzy przyczyny odpowiadają za większość awarii.

### MW-03 — Zamknięcie S07 na fizycznym TV

**Po co.** S07 ma status `CODE_VERIFIED_DEVICE_PENDING`. Dowody pochodzą z emulatora
(API 36), a docelowy telewizor (API 32) nie był uruchamiany.

**Zakres.** Otwarte punkty z `STATE.json`: wybór hosta (T07), wygaśnięcie czasu dziecka,
przełączenie gry bez podpiętego ekranu (T08), odtworzenie Activity w stanie PREPARING,
niesklasyfikowane zawieszenie „8a", przebieg na fizycznym TV.

**Gotowe, gdy** zawieszenie „8a" ma ustaloną przyczynę, a siedem chronionych przepływów
przechodzi na BRAVII. Nie zamykać decyzją właściciela bez tych dwóch rzeczy.

## P1

### MW-04 — Projekt jednego modelu sesji i decyzja o regułach

**Po co.** To jest zmiana granic własności, więc wymaga decyzji właściciela przed
implementacją.

**Zakres (dokument projektowy, bez kodu).**
- Jeden model sesji na jednym wątku. Wejście: zdarzenia (obserwacja hosta, start i stop
  transportu, intencja użytkownika, wynik operacji Gatewaya). Wyjście: jeden niezmienny
  `SessionSnapshot` rozgłaszany do subskrybentów.
- Model zastępuje: statyczne pola `RetainedStreamSessionCoordinator`,
  `SessionStateResolver`, pętlę `SessionOrchestrator.evaluate` i klasę efektów
  w `ConsoleActivity`. `LaunchTransitionController` i `StreamRuntime` zostają.
- Odwrócona decyzja domyślna: żywy transport na tym samym hoście i profilu wykonawczym
  jest zawsze reużywany; zamknięcie tylko na żądanie użytkownika albo przy zmianie hosta
  lub profilu.
- Lista zmian w `RULES.md`, `DECOMPOSITION.md` i `AGENTS.md` (dziś nakazują zachować
  koordynator jako fasadę i zakazują „nowego SessionManagera").
- Plan migracji w krokach, z których każdy zostawia działającą aplikację.

**Stan na 2026-10-05.** Szkic v1: [MW-04_MODEL_SESJI_PL.md](MW-04_MODEL_SESJI_PL.md).
Rozstrzygnięte przez właściciela: przy obcej sesji na hoście tylko informacja; przy
zmianie gry krótkie oczekiwanie na świeżą obserwację i pytanie tylko przy niepewności;
K4 zastępuje S08. Otwarte: zmiana reguł (sekcja 11 projektu) i dane z MW-02.

**Gotowe, gdy** właściciel zaakceptuje zmianę reguł, a projekt uwzględnia wyniki MW-02.

### MW-05 — Implementacja modelu sesji (K1–K5; K4 zastępuje S08)

**Po co.** S08 przenosiłoby logikę przygotowania i warm-upu za istniejącą fasadę, a model
sesji przenosi ją ponownie. Decyzją właściciela z 2026-10-05 S08 nie jest wykonywane
osobno; jego wymagania są scenariuszami akceptacyjnymi kroku K4.

**Zakres.** Kroki z sekcji 8 projektu MW-04, każdy jako osobny punkt kontrolny:
- K1: koordynator rozgłasza zmiany, ekrany subskrybują;
- K2: Activity czytają wyłącznie rozgłoszony snapshot;
- K3: stan koordynatora przechodzi do modelu (wymaga zmiany reguł);
- K4: funkcja przejścia zastępuje `evaluate`, adaptery wychodzą z Activity; tu wchodzą
  wymagania S08;
- K5: usunięcie pozostałości.

K1 i K2 nie wymagają zmiany reguł i mogą ruszyć zaraz po zamknięciu S07.

**Gotowe, gdy**
- `RetainedStreamSessionCoordinator` nie ma statycznych pól stanu;
- `ConsoleActivity` i `Game` nie czytają bezpośrednio `runningGameId`, magazynów sesji ani
  własnych kopii pól utrzymanego streamu (dziś odpowiednio 34 i 49 odczytów w Activity);
  to zarazem domyka niespełnione kryterium S06;
- siedem chronionych przepływów przechodzi na fizycznym TV.

### MW-06 — Testy scenariuszowe zamiast tekstowych

**Zakres.** Dla modelu z MW-05: sekwencja zdarzeń i oczekiwany snapshot oraz lista
zakazanych efektów. Minimalny zestaw: powrót do żywego streamu, zmiana gry na żywym
transporcie, zamknięcie i ponowny start, utrata transportu w tle, spóźniona obserwacja
hosta, przejęcie przez profil dziecka. Każdy test tekstowy (`contains("...")` na pliku
źródłowym) dotyczący przenoszonej odpowiedzialności jest usuwany w tym samym commicie.

**Gotowe, gdy** żadna decyzja o sesji nie jest chroniona wyłącznie testem tekstowym.

### MW-07 — Budżet monolitów: zakaz wzrostu

**Po co.** Od 4.09 do 4.10 `ConsoleActivity.java` urosło z 15,0 do 17,7 tys. linii,
a `Game.java` z 6,5 do 7,8 tys., mimo obowiązkowej dekompozycji.

**Zakres.** Reguła w `AGENTS.md`: commit nie może zwiększyć liczby linii
`ConsoleActivity.java`, `Game.java`, `wakeplay_gateway.py` ani `GameProviderBridge.py`.
Nowa logika trafia do nowej lub istniejącej klasy poza monolitem. Prosty skrypt
porównujący liczby z bazą gałęzi, uruchamiany razem z testami.

**Gotowe, gdy** reguła jest w `AGENTS.md`, a skrypt w zestawie kontroli.

## P2

### MW-08 — Zaciągnięcie upstreamu v0.5.5

**Stan.** Wspólny przodek to v0.4.10. Upstream ma 37 nowych commitów (`9a0a1900`).
Próbny merge daje cztery konflikty: `.gitignore`, dwa hunki w `Game.java`, jeden
w `MediaCodecDecoderRenderer.java` i wskaźnik submodułu `moonlight-common-c`.

**Zakres.**
1. Pełny merge `main` z `MoreOrLessSoftware/moonlight-android`, bez cherry-picków.
2. Decyzja o submodule: upstream przeszedł na własny fork (gałąź `mlsoft-pyrowave`).
   Przed przyjęciem sprawdzić, czy fork zawiera poprawki RTSP z commita przypiętego
   w MoonWakerze (`1cc32cf6`).
3. Renderer Vulkan: zostaje za ustawieniem, domyślnie wyłączony. Sprawdzić, czy jego
   ścieżka wywołuje `setOnFrameRenderedListener`; bramka prywatności
   (`LaunchTransitionController`) zależy od tego sygnału. Jeśli nie, podpiąć odpowiednik
   przed włączeniem.
4. PyroWave: nie włączać. Binarka jest tylko dla arm64, kodek wymaga bitrate'ów rzędu
   setek Mbps i wsparcia po stronie hosta, którego dla Vibepollo nie potwierdzono.
5. Przycisk wyboru kodeka i bitrate per kodek: uzgodnić z Autopilotem, który jest
   właścicielem bitrate'u, albo ukryć.
6. Przyjąć bez zmian: poprawkę utraty klatek na Androidzie 17, ukrywanie paska nawigacji,
   wibracje, pomiar odświeżania ekranu wysyłany do hosta.

**Gotowe, gdy** aplikacja buduje się dla obu ABI, testy jednostkowe przechodzą, a siedem
chronionych przepływów działa na TV z rendererem MediaCodec.

**Kolejność.** Konflikty można rozwiązać już teraz na osobnej gałęzi. Sam merge do
gałęzi hardeningu dopiero po MW-03, bo S07 zmienia `Game.java`, a przed MW-05 i MW-09,
bo one przebudowują ten sam plik.

### MW-09 — `Game.java` jako cienki adapter

**Zakres.** Po MW-05 przenieść pozostałą logikę MoonWakera (przełączanie gier, kończenie
sesji, monitorowanie dziecka, powiadomienia Discorda) do pakietu `stream/` i kontrolerów.
Cel: `Game` to wersja upstreamowa (ok. 3,2 tys. linii) plus wywołania zaczepów.

**Gotowe, gdy** merge upstreamu nie daje konfliktów w `Game.java` poza zaczepami.

### MW-10 — Rozbiór UI `ConsoleActivity` ekran po ekranie

**Zakres.**
- **MW-10a (od razu):** przenieść klasy rysujące z końca pliku do osobnych plików
  (`ControllerGlyphDrawable`, `ControllerBatteryDrawable`, `ProfileToolbarDrawable`,
  `HostAvatarDrawable`, `HostSelectionBackdropDrawable`). Nie mają stanu Activity,
  więc to przeniesienie mechaniczne.
- **Po MW-05:** osobne widoki z własnymi kontrolerami dla wyboru hosta, bramki profilu,
  biblioteki i paneli bocznych. Każdy subskrybuje snapshot sesji; stary kod znika w tym
  samym commicie.

**Gotowe, gdy** `ConsoleActivity` tylko składa widoki i przekazuje intencje.

### MW-11 — S09 i S10 hardeningu

S08 jest zastąpione przez krok K4 z MW-05; zamknięcie w `HARDENING/STATE.json` wpisuje
koordynator hardeningu jako poprawkę kontraktu autoryzowaną przez właściciela. S09
(bariera okładki i przekazanie prezentacji) zostaje w zakresie, ale po K4, bo korzysta
z typowanego etapu przygotowania. S10 to kwalifikacja całości na fizycznym TV.

## P3

### MW-12 — Rozbiór monolitów hosta

`wakeplay_gateway.py` (8,7 tys. linii): rozdzielić obsługę HTTP, czas dziecka i operacje
kontrolne. `GameProviderBridge.py` (5,7 tys.): wydzielić dostawców Steam, Epic
i ogólnego Playnite (punkt C4 dokumentu architektury). Po zakończeniu hardeningu, bo
bieżące zadania zmieniają te pliki.

### MW-13 — Tracker zamiast plików stanu

Założyć GitHub Issues z tablicą Projects i przenieść pozycje z tego pliku oraz otwarte
punkty S07. Szablon zgłoszenia błędu: kroki, kod przyczyny z ekranu (MW-01), wycinek
diagnostyki, wersje aplikacji i hosta.

### MW-14 — Aktualizacja dokumentu architektury

`MOONWAKER_ARCHITECTURE.md` ma nagłówek „draft" i gałąź docelową
`feature/steam-operations`, a mapa C1–C10 nie odpowiada stanowi kodu. Zaktualizować po
decyzji z MW-04.

### MW-15 — Pozostałe porządki w repo

- Zdecydować o trzech worktree z niezacommitowanymi zmianami: `work/s05-input-focus`,
  `work/s05-supervisor-repair`, `work/steam-operations`.
- Zdecydować o trzech worktree poza repo: `D:/Maladie/moonlight-android-rollback-069`,
  `E:/Documents/Codex/baseline-playnite-library`, `E:/MoonWakerLab/s05-source`.
- Usunąć archiwum `D:/Maladie/moonlight-android-archive-20261005` po upewnieniu się, że
  nic z niego nie jest potrzebne.
- Dodać do repo katalog `.agents/` (skill `moonwaker-host-artifacts`, do którego odsyła
  `AGENTS.md`, jest dziś nieśledzony).
- Gateway zapisuje `child-time-usage.json` obok źródeł przy lokalnych uruchomieniach;
  wskazać mu katalog danych.
- Po zamknięciu S07 przenieść do archiwum `.tmp/` i pozostałe katalogi `work/`.

## Co można zacząć teraz, równolegle z hardeningiem

Zasady pracy równoległej:
- osobny worktree od bieżącego HEAD-a gałęzi hardeningu;
- bez zmian w plikach, które S07 właśnie rusza: `Game.java`, `stream/StreamRuntime.java`,
  `stream/AndroidStreamRuntime.java`, ścieżki sesji dziecka w Gatewayu;
- małe diffy, rebase po każdym commicie S07.

| Tor | Zadania | Dotyka kodu hardeningu |
| --- | --- | --- |
| A — diagnostyka | MW-01, potem MW-02 | mały diff w `ConsoleActivity` i `SessionOrchestrator`; MW-02 bez kodu |
| B — projekt | MW-04 | nie, sam dokument |
| C — upstream | MW-08, przygotowanie na osobnej gałęzi | nie, do chwili merge'a |
| D — higiena | MW-07, MW-10a, MW-13, MW-15 | MW-10a rusza koniec `ConsoleActivity`; reszta nie |

Tory A–D są od siebie niezależne. Tor A daje najwięcej najszybciej: po nim każda kolejna
awaria na TV zasila MW-04 konkretnymi danymi.

## Kolejność całości

1. Teraz: tory A–D oraz MW-03.
2. Po zamknięciu S07: merge upstreamu (MW-08).
3. K1 i K2 z MW-05.
4. Zmiana reguł hardeningu (MW-04, sekcja 11).
5. K3, potem K4 (dawne S08) z testami scenariuszowymi (MW-06), potem K5.
6. MW-09 i MW-10.
7. S09, potem S10 (MW-11); na końcu MW-12 i MW-14.
