# MW-04 — Jeden model sesji: projekt

Status: **szkic v1**, 2026-10-05. Trzy pytania z sekcji 10 rozstrzygnięte przez właściciela;
do decyzji pozostaje zmiana reguł z sekcji 11. Bez zmian w kodzie.
Dotyczy: aplikacja Android. Strona hosta (Gateway, Bridge'e) zostaje bez zmian.

Opis stanu obecnego pochodzi z czytania kodu na `d998b8aa`. Dane z prawdziwych awarii
(MW-02) jeszcze nie istnieją; miejsca, które od nich zależą, są oznaczone jako otwarte
w sekcji 10.

## 1. Cel

Usunąć przyczynę dwóch problemów:

1. interfejs pokazuje stan sesji niezgodny z rzeczywistością, a reużycie żywego streamu
   kończy się błędem o konieczności zamknięcia poprzedniej sesji;
2. poprawka w jednym miejscu wywołuje regresję w innym.

Oba wynikają z tego, że stan sesji nie ma jednego właściciela ani jednego miejsca,
w którym się zmienia.

Poza zakresem: bramka prywatności (`LaunchTransitionController`), fizyczny transport
(`StreamRuntime`), protokół Gatewaya, rdzeń Moonlighta, wygląd ekranów.

## 2. Stan obecny

| Co | Gdzie dziś | Problem |
| --- | --- | --- |
| Stan utrzymanego streamu | `RetainedStreamSessionCoordinator`: 22 statyczne pola, 61 publicznych metod statycznych | Zmieniany z `Game`, `ConsoleActivity`, `StreamRuntime` i `StreamHomeActivity`; brak powiadomień o zmianie |
| Obserwacja hosta | `SessionStateResolver` (statyczna mapa właścicieli projekcji) oraz `ComputerDetails.runningGameId` z odpytywania | Dwa źródła odświeżane niezależnie |
| Sesja do wznowienia i zawieszona | `SessionResumeManager`, `SuspendedSessionStore`, `HostSleepStateStore` (preferencje) | Czytane z dysku przy każdym składaniu stanu |
| Złożenie w jeden obraz | `ConsoleActivity.resolveSessionSnapshot`, wywoływane z ok. 20 miejsc | Obraz powstaje na żądanie, z danych z różnych chwil |
| Kopie w Activity | pola `retainedStream*` (49 odczytów), bezpośrednie odczyty `runningGameId` (34) | Decyzje podejmowane z pominięciem obrazu |
| Decyzja „co zrobić z Play" | `SessionOrchestrator.evaluate`: 7 parametrów, do 8 przebiegów | Reużycie wymaga zgodności kilku pól; reszta spada do zamykania |
| Wykonanie decyzji | anonimowa klasa `Effects` w `ConsoleActivity` (ok. 600 linii) | Nietestowalna; trzy ścieżki zamykania |

## 3. Model

Jeden obiekt `SessionModel` w zakresie procesu (jak `StreamRuntime`), nigdy w Activity.

- **Jeden wątek.** Wszystkie zmiany stanu dzieją się na jednym wątku modelu. Znika
  potrzeba blokad i większości identyfikatorów korelacji używanych dziś do odrzucania
  spóźnionych wywołań.
- **Wejście: zdarzenia.** Nikt nie ustawia stanu bezpośrednio; każdy zgłasza, co się stało.
- **Wyjście: jeden niezmienny `SessionSnapshot`**, rozgłaszany subskrybentom po każdej
  zmianie. Ekrany rysują to, co dostały, i niczego nie doczytują.
- **Czysta funkcja przejścia.** `przejście(stan, zdarzenie) → (nowy stan, lista efektów)`
  nie robi wejścia-wyjścia. Efekty wykonują adaptery, a ich wyniki wracają jako zdarzenia.

```text
 Game / ConsoleActivity ──intencje──┐
 StreamRuntime ──zdarzenia transportu──┤
 Gateway / odpytywanie hosta ──obserwacje──┼──▶ SessionModel ──▶ SessionSnapshot ──▶ ekrany
 wyniki efektów, zegar ─────────────────┘         │
                                                  └──efekty──▶ adaptery (StreamRuntime,
                                                               Gateway, preflight, ekran ładowania)
```

### 3.1 Stan

Zgodnie z `SESSION_CONTRACTS.md` transport, wykonywana gra, prezentacja i uprawnienia
aktora to niezależne fakty. Model trzyma je osobno i nie wyprowadza jednego z drugiego.

| Oś | Wartości | Kto jest źródłem |
| --- | --- | --- |
| Transport (lokalny) | brak / startuje / żywy / zatrzymywany / utracony, plus numer generacji | `StreamRuntime` |
| Prezentacja | podpięta / zaparkowana w tle / bez ekranu | `Game`, `StreamHomeActivity` |
| Gra na hoście | ostatnia obserwacja (tożsamość producenta, rewizja, aplikacja, gra, dowód procesu, wiek) albo „nieznana" | Gateway |
| Operacja w toku | najwyżej jedna: Play, zmiana gry, zamknięcie gry, zamknięcie sesji, warm-up; z identyfikatorem | model |
| Aktor | profil, sesja dziecka, numer epoki | Gateway i wybór profilu |
| Zapis trwały | sesja zawieszona, dane do ponownego połączenia | magazyny, zapisywane wyłącznie przez model |

W procesie istnieje najwyżej jeden transport (ograniczenie natywnego rdzenia), więc model
trzyma jedną aktywną sesję. Dla pozostałych hostów przechowuje tylko obserwacje.

### 3.2 Zdarzenia

- **Intencje użytkownika:** Play (gra lub aplikacja), powrót do streamu, wyjście do menu,
  zakończenie gry, zamknięcie sesji, warm-up, potwierdzenie albo anulowanie pytania.
- **Transport:** wystartował, zakończony przez drugą stronę, zatrzymanie ukończone,
  zatrzymanie niepewne.
- **Prezentacja:** podpięta, odpięta, zaparkowana.
- **Host:** nowa obserwacja, host niedostępny, host uśpiony.
- **Wyniki efektów:** preflight, zezwolenie Gatewaya, zamknięcie, uruchomienie gry,
  zmiana gry.
- **Aktor:** zmiana profilu, wygaśnięcie czasu dziecka.
- **Zegar:** upłynął termin operacji albo obserwacji.

### 3.3 Efekty

Start i stop transportu, zmiana gry na żywym transporcie, operacje kontrolne Gatewaya,
preflight, pokazanie ekranu ładowania, pytanie do użytkownika, zapis do magazynów.
Każdy efekt niesie identyfikator operacji; wynik z nieaktualnym identyfikatorem jest
odrzucany w jednym miejscu, w funkcji przejścia.

## 4. Reguły decyzji

Dziś reużycie trzeba udowodnić, a domyślnie następuje zamknięcie. Proponowane odwrócenie:

| Sytuacja | Decyzja |
| --- | --- |
| Transport żywy, ten sam host i profil wykonawczy, ta sama gra | Powrót do streamu |
| Transport żywy, ten sam host i profil wykonawczy, inna gra | Zmiana gry na tym samym transporcie; transport nie jest zamykany |
| Transport żywy, gra na hoście nieznana lub niezgodna z lokalnym zapisem | Jak wyżej; niepewność co do gry nigdy nie zamyka transportu |
| Transport żywy, inny host lub inny profil wykonawczy | Pytanie do użytkownika, potem zamknięcie całej sesji i nowy start |
| Transport utracony, gra na hoście działa | Ponowne połączenie |
| Brak transportu, na hoście nasza sesja | Wznowienie |
| Brak transportu, na hoście sesja niezarządzana lub obca | Jawny stan z wyborem użytkownika; bez automatycznego sprzątania (zgodnie z decyzją S05-A01) |
| Żądanie w trakcie zamykania | Ustawia się w kolejce za bieżącą operacją; brak drugiego startu |

Dwie zasady pomocnicze:

- **O powodzeniu zamknięcia decyduje wynik operacji zamknięcia**, nie ponowny odczyt
  obserwacji hosta. Do chwili nadejścia świeżej obserwacji stan gry to „nieznana", nie błąd.
- **Zastąpienie działającej gry inną nadal wymaga potwierdzenia**, bo grozi utratą
  niezapisanego postępu. Potwierdzenie dotyczy gry, nigdy transportu.
- **Kiedy pytać o potwierdzenie** (decyzja właściciela, 2026-10-05):
  - host potwierdza, że działa inna gra: pytanie;
  - host potwierdza, że nic nie działa: bez pytania;
  - nie wiadomo: model czeka do 3 s na świeżą obserwację i pyta tylko wtedy, gdy dalej
    nie wiadomo.
  Profil dziecka zachowuje regułę z `SESSION_CONTRACTS.md`: gra nieudostępniona dziecku
  jest zamykana automatycznie, bez pytania.

## 5. Co zostaje, co znika

| Element | Los |
| --- | --- |
| `LaunchTransitionController` | Zostaje jedyną bramką prywatności; dostaje od modelu obserwacje i gotowość |
| `StreamRuntime`, `TransportStopCoordinator` | Zostają; zgłaszają zdarzenia do modelu, zamiast zmieniać stan koordynatora |
| `HostLaunchPreflight`, klienci Gatewaya | Zostają jako adaptery efektów |
| Parser obserwacji hosta z `SessionStateResolver` | Zostaje; statyczna mapa właścicieli projekcji znika |
| `SessionResumeManager`, `SuspendedSessionStore`, `HostSleepStateStore` | Zostają jako zapis trwały; czyta i pisze je tylko model |
| `RetainedStreamSessionCoordinator` | Znika; jego stan przechodzi do modelu |
| `SessionOrchestrator.evaluate` | Znika; decyzje przechodzą do funkcji przejścia |
| Klasa `Effects` w `ConsoleActivity` | Znika; zastępują ją adaptery poza Activity |
| Pola `retainedStream*` i odczyty `runningGameId` w Activity | Znikają |

## 6. Wątki i czas życia

- Model żyje tak długo jak proces. Odtworzenie Activity niczego w nim nie zmienia.
- Po śmierci procesu model startuje z magazynów i ze stanem gry „nieznana" do pierwszej
  obserwacji. Zapis do wznowienia nie jest traktowany jak żywe połączenie.
- Subskrybenci dostają snapshot na wątku głównym. Subskrypcja jest zdejmowana w `onStop`,
  więc model nie trzyma Activity.

## 7. Identyfikatory korelacji

Dziś jest ich co najmniej siedem (generacja transportu, generacja kontrolna, epoka
aktora, epoka zakończenia, numer próby, identyfikator przejścia, rewizja producenta).
Po zmianie zostają cztery, każdy z jednym właścicielem:

| Identyfikator | Właściciel | Do czego |
| --- | --- | --- |
| Generacja transportu | `StreamRuntime` | Odrzucanie zdarzeń poprzedniego połączenia |
| Tożsamość i rewizja producenta | host | Odrzucanie starszej obserwacji |
| Identyfikator operacji | model | Odrzucanie spóźnionych wyników efektów |
| Epoka aktora | Gateway | Uprawnienia i czas dziecka |

Identyfikator przejścia zostaje wewnątrz `LaunchTransitionController`. Generacja
kontrolna Gatewaya (S05) zostaje w protokole; model ją przenosi, ale nie używa do decyzji.

## 8. Migracja

Każdy krok zostawia działającą aplikację i jest osobnym punktem kontrolnym.

| Krok | Zakres | Efekt dla użytkownika |
| --- | --- | --- |
| K1 | Koordynator rozgłasza zmiany; ekrany subskrybują zamiast odpytywać | Status w UI nadąża za rzeczywistością |
| K2 | Activity czytają wyłącznie rozgłoszony snapshot; znikają pola `retainedStream*` i odczyty `runningGameId` | Koniec decyzji z nieaktualnych kopii; domyka kryterium S06 |
| K3 | Stan koordynatora przechodzi do `SessionModel`; wywołania statyczne stają się zdarzeniami | Bez zmian widocznych; znika globalny stan |
| K4 | Funkcja przejścia zastępuje `evaluate` i wprowadza reguły z sekcji 4; adaptery wychodzą z Activity. Zastępuje S08 hardeningu (sekcja 8.1) | Reużycie streamu jest domyślne |
| K5 | Usunięcie pozostałości i testów tekstowych dotyczących sesji | — |

Kolejność „najpierw odczyt, potem zapis" jest celowa: K1 i K2 są małe, dają widoczną
poprawę i nie wymagają jeszcze zmiany reguł hardeningu.

### 8.1 K4 zastępuje S08

Decyzja właściciela z 2026-10-05: S08 nie jest wykonywane osobno. Oba etapy przenoszą tę
samą logikę przygotowania, warm-upu i wspólnego połączenia; robione osobno wymagałyby
przeniesienia jej dwa razy. Wymagania S08 stają się scenariuszami akceptacyjnymi K4:

- warm-up i Play dzielą jedno autoryzowane przygotowanie i połączenie;
- po zamknięciu sesji powrót do menu nie uruchamia streamu ponownie; blokadę zdejmuje
  tylko jawna intencja użytkownika, nigdy zegar;
- Play zgłoszony w trakcie zamykania czeka w kolejce; nie ma drugiego startu;
- utrata Wi-Fi podczas warm-upu kończy się jawnym błędem lub stanem ponownego połączenia,
  nigdy zawieszonym przygotowaniem;
- zmiana z rodzica na dziecko wymaga aktualnej autoryzacji, czasu i biblioteki, także
  przy tym samym profilu Windows;
- etap przygotowania (budzenie, Windows dostępny, logowanie, streamer gotowy, łączenie)
  jest jednym typowanym statusem, z którego korzysta ekran ładowania.

Formalne zamknięcie S08 w `HARDENING/STATE.json` jako zastąpionego wymaga wpisu
koordynatora hardeningu (poprawka kontraktu autoryzowana przez właściciela). S09 i S10
pozostają bez zmian.

## 9. Testy

Funkcja przejścia jest czysta, więc testy to tabele: stan początkowy, sekwencja zdarzeń,
oczekiwany snapshot, oczekiwane efekty i efekty zakazane. Minimalny zestaw:

1. powrót do żywego streamu z tą samą grą;
2. zmiana gry na żywym transporcie, także przy nieznanej grze na hoście;
3. zamknięcie sesji i natychmiastowy nowy start;
4. Play w trakcie zamykania;
5. utrata transportu w tle i ponowne połączenie;
6. obserwacja A dochodząca po nowszej B;
7. przejęcie przez profil dziecka i wygaśnięcie jego czasu;
8. śmierć procesu z zapisaną sesją;
9. warm-up, a potem Play w trakcie przygotowania;
10. zmiana hosta przy żywym transporcie.

Każdy błąd znaleziony w MW-02 dochodzi do tej listy jako osobny scenariusz.

## 10. Otwarte pytania

Decyzje właściciela:

1. **Obca sesja na hoście.** Rozstrzygnięte 2026-10-05: użytkownik widzi tylko
   informację, że na hoście działa stream innego klienta; bez możliwości przejęcia.
2. **Zmiana gry przy nieznanej grze na hoście.** Rozstrzygnięte 2026-10-05: krótkie
   oczekiwanie na świeżą obserwację, pytanie tylko przy dalszej niepewności (sekcja 4).
3. **Zakres K4 względem S08.** Rozstrzygnięte 2026-10-05: K4 zastępuje S08 w całości
   (sekcja 8.1).

Do rozstrzygnięcia danymi z MW-02:

4. Które przyczyny awarii dominują i czy któraś leży poza zakresem tego projektu
   (na przykład po stronie hosta).
5. Czy hipoteza o udanym zamknięciu zgłaszanym jako błąd się potwierdza.

## 11. Zmiany w regułach

Projekt jest sprzeczny z trzema zapisami, które trzeba zmienić przed K3:

| Plik | Dziś | Propozycja |
| --- | --- | --- |
| `HARDENING/RULES.md` | „Retain canonical owners: … RetainedStreamSessionCoordinator as retained facade; SessionOrchestrator for intent" | Właścicielem stanu sesji i decyzji o intencji jest `SessionModel`; `LaunchTransitionController` i `StreamRuntime` bez zmian |
| `HARDENING/DECOMPOSITION.md` | Odrzuca „A new SessionManager with all the old responsibilities under a different filename" | Doprecyzować: zakaz dotyczy przenoszenia bez zmiany własności; model z jednym wątkiem i czystą funkcją przejścia, który usuwa stan globalny, jest dozwolony |
| `docs/agents/SESSION_CONTRACTS.md` | „Start with existing owners: SessionOrchestrator …; RetainedStreamSessionCoordinator …" | Zaktualizować listę właścicieli po K3 |

Zasada z `AGENTS.md` („Keep one authoritative owner per fact") pozostaje i jest przez ten
projekt realizowana.

## 12. Kryteria akceptacji całości

- `RetainedStreamSessionCoordinator` nie istnieje, a w pakietach `console`, `stream`
  i w `Game` nie ma statycznych pól stanu sesji.
- `ConsoleActivity` i `Game` nie czytają `runningGameId`, magazynów sesji ani własnych
  kopii stanu streamu.
- Każda decyzja o sesji jest pokryta testem scenariuszowym; żadna nie jest chroniona
  wyłącznie testem czytającym tekst źródeł.
- Siedem chronionych przepływów hardeningu przechodzi na fizycznym telewizorze.
- Dziesięć kolejnych zmian gry na żywym transporcie przechodzi na telewizorze bez
  zamknięcia transportu.
