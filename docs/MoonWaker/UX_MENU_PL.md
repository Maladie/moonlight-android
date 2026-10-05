# Menu, filtry i opcje — propozycja przegrupowania i wyglądu (styl PS5)

Status: propozycja do decyzji właściciela, 2026-10-05. Oparta na przeglądzie aplikacji
na BRAVII VH22 (profil BASIA) przez adb: zrzuty ekranów, zrzuty drzewa widoków i kod
`ConsoleActivity`. Nie zawiera zmian w kodzie.

## 1. Co jest dziś

| Miejsce | Jak się otwiera | Zawartość |
| --- | --- | --- |
| Pasek narzędzi (ekran główny) | D-pad w górę | Odśwież · Opcje · Profil Windows (przełącz) · Discord lub Mikrofon USB · Opuść host |
| Panel hosta | OK na tytule hosta; MENU na kaflu hosta na ekranie wyboru | Obudź hosta · Pulpit · Automatyczne przygotowanie streamu · Zamknij stream · Twarde zamknięcie · Odparuj hosta · Test połączenia · (Uśpij hosta) |
| Opcje | ikona zębatki | **Interfejs:** dźwięki, ambient, 3 suwaki głośności, ograniczenie animacji, opis gry w karuzeli, ukryte aplikacje · **Host:** automatyczne logowanie na hosta, integracje hosta · **Streaming:** utrzymuj stream po wyjściu, Autopilot, ustawienia streamingu (ekran z upstreamu), nadpisania · **TV:** wygaszacz · **Pomoc:** README |
| Akcje gry | MENU / długie OK na kaflu | Uruchom · Zainstaluj lub Odinstaluj · Zakończ grę · Zamknij grę i stream · Ustawienia streamingu gry · Zoptymalizuj streaming · Udostępnij grę dziecku · Ukryj/Pokaż · Szczegóły |
| Pasek filtrów (siatka) | D-pad w górę z siatki | Filtr (Wszystkie, Zainstalowane, Niezainstalowane, Ostatnio grane, Najczęściej uruchamiane, Nigdy nie uruchamiane) · Platformy (wielokrotny wybór) · Sortowanie (Ostatnio grane, Nazwa, Biblioteka, Gatunek, Czas gry, Najczęściej uruchamiane) · Wyszukaj (okno tekstowe) |
| Legenda | stała, dół ekranu | w siatce: Filtry · Resetuj · Szukaj · Wróć; na ekranie profili: Wybierz · Wstecz |
| Kontroler | ikona kontrolera | Zidentyfikuj · Wyłącz · Rozparuj · Ustawienia Bluetooth |
| Wyjście | Wstecz na ekranie głównym | okno „Zamknąć MoonWaker?" |

## 2. Co przeszkadza

1. **Jedno „Opcje" miesza trzy poziomy:** ustawienia aplikacji (dźwięki), ustawienia hosta (integracje, logowanie) i doraźne regulacje streamu (nadpisania bitrate'u).
2. **Akcje hosta są w dwóch miejscach** (tytuł hosta i ekran wyboru hosta) z tą samą listą, w której sąsiadują „Pulpit" i „Odparuj hosta".
3. **Panel akcji gry ma dziewięć pozycji bez grup**: uruchamianie, instalacja, sesja, streaming, profil dziecka i widoczność w jednej liście.
4. **Pasek filtrów nie mówi, czym jest.** „Ostatnio grane" to sortowanie, ale nic na to nie wskazuje; „Wszystkie gry" i „Wszystkie platformy" wyglądają identycznie, choć jedno to pojedynczy wybór, a drugie wielokrotny. Prawy drążek tylko przenosi fokus na pasek.
5. **Autopilot uruchamia analizę od razu po wejściu** w pozycję menu, zamiast pokazać stan i przycisk.
6. **README otwiera stronę GitHuba** z przyciskiem „Sign in"; na telewizorze nie da się jej użyć.
7. **Wstecz na ekranie głównym otwiera pytanie o zamknięcie aplikacji**, co łatwo wywołać przypadkiem; przy zdalnym sterowaniu zatrzymało to cały przebieg testu.
8. **Drobne:** „Pozostało dziś: 1381 min" zamiast „23 godz. 1 min"; stały napis „Biblioteka gier jest aktualna"; suwaki w procentach bez widocznego kroku; przełączniki „WŁ./WYŁ." jako pigułki o innym stylu niż reszta.

## 3. Docelowa struktura (cztery warstwy, jak na PS5)

### 3.1 Szybkie menu (Control Center)

Długie przytrzymanie MENU/OPTIONS w dowolnym miejscu konsoli otwiera dolny pasek kafli,
przyciemniając tło. Zastępuje ikony paska narzędzi i okno „Zamknąć MoonWaker?".

```text
[ Host: Komputer Mariusza · Online ] [ Stream: brak ] [ Profil: Basia ] [ Discord ] [ Kontrolery ] [ Ustawienia ] [ Zamknij MoonWaker ]
```

- Kafel hosta: Obudź lub Uśpij, Pulpit, Zmień hosta.
- Kafel streamu: Wróć do streamu, Zamknij stream, Zamknij grę i stream (tylko gdy coś działa).
- Kafel profilu: Zmień profil MoonWaker, Przełącz użytkownika Windows (z wyraźnym ostrzeżeniem).

### 3.2 Ustawienia (pełny ekran, lista kategorii po lewej, treść po prawej)

| Kategoria | Zawartość |
| --- | --- |
| Wygląd i dźwięk | dźwięki interfejsu, ambient, muzyka (wybór hosta / menu), efekty, ograniczenie animacji, wygaszacz ekranu, opis gry w karuzeli |
| Biblioteka | ukryte aplikacje, domyślne sortowanie i filtr, widoczne platformy |
| Streaming | rozdzielczość, odświeżanie, bitrate, tempo klatek (dzisiejsze „Ustawienia streamingu"), nadpisania, Autopilot jako stan plus przycisk „Uruchom analizę" |
| Hosty i profile | host otwierany automatycznie, lista hostów; wybrany host otwiera: integracje, parowanie Gatewaya, test połączenia, odparuj |
| Kontrolery i akcesoria | kontrolery (identyfikuj, wyłącz, rozparuj), mikrofon USB, Bluetooth |
| Discord | logowanie, powiadomienia, dźwięki DM |
| Informacje | wersja aplikacji i hosta, eksport diagnostyki, pomoc dostępna offline |

Wszystkie pozycje z dzisiejszego „Opcje" mają tu swoje miejsce; żadna nie znika.

### 3.3 Panel hosta (z tytułu hosta i z ekranu wyboru)

Nagłówek: nazwa, stan, aktywny profil Windows, wersja hosta. Trzy akcje główne:
**Pulpit**, **Obudź/Uśpij**, **Zamknij stream**. Poniżej sekcja „Więcej" z pozycjami
„Test połączenia", a na końcu, oddzielone i na czerwono, „Twarde zamknięcie" i „Odparuj
hosta". „Automatyczne przygotowanie streamu" przechodzi do Ustawienia → Hosty i profile.

### 3.4 Karta gry (MENU lub długie OK na kaflu)

Nagłówek z okładką, nazwą, czasem gry, ostatnim uruchomieniem i źródłem. Jedna akcja
główna na górze (Uruchom, Wznów, Zainstaluj lub Dokończ instalację), potem grupy
pokazywane tylko wtedy, gdy mają sens:

- **Sesja:** Zakończ grę, Zamknij grę i stream (tylko gdy gra działa).
- **Streaming:** Ustawienia streamingu gry, Zoptymalizuj streaming.
- **Rodzina:** Udostępnij grę dziecku (tylko profil rodzica).
- **Biblioteka:** Ukryj lub Pokaż, Odinstaluj (czerwone, na końcu), Szczegóły.

### 3.5 Biblioteka: filtry i sortowanie

- Prawy drążek lub L2 otwiera **jeden panel „Filtry"** z trzema sekcjami: Stan
  (pojedynczy wybór), Platformy (wielokrotny), Sortuj (pojedynczy) oraz przyciskiem
  „Wyczyść filtry".
- Pasek nad siatką pokazuje **chipy aktywnych filtrów** z etykietą typu, np.
  „Stan: Zainstalowane", „Sortuj: Czas gry", „Platformy: Steam, Epic"; chip „Wszystkie"
  znika, gdy filtr jest nieaktywny.
- Wyszukiwanie pod Y otwiera klawiaturę ekranową MoonWakera nad siatką, a wyniki
  filtrują siatkę na żywo; nie ma osobnego okna tekstowego.
- Pozycja „34 / 309" i opis gry zostają po lewej.

## 4. Język wizualny

- **Jeden komponent panelu** dla wszystkich list: eyebrow, tytuł, opis; wiersze 72 dp;
  etykieta po lewej, wartość po prawej (przełącznik, wartość z chevronem albo nic);
  nagłówki sekcji wersalikami z 24 dp odstępu nad; stopka z legendą.
- **Fokus:** obrys 2 dp w kolorze akcentu i powiększenie 1,03; bez poświaty i cieni.
- **Akcje niszczące:** zawsze na końcu listy, oddzielone, czerwony tekst, potwierdzenie
  z nazwą obiektu („Odparować Komputer Mariusza?").
- **Typografia:** trzy rozmiary (tytuł 40 sp, etykieta 26 sp, pomocniczy 20 sp); bez
  wersalików w treści poza nagłówkami sekcji.
- **Wartości:** zamiast suwaków w procentach stopnie 0–10 z widoczną wartością
  i zmianą lewo/prawo; przełączniki jako jednolity komponent z dwoma stanami.
- **Legenda:** jeden styl, najwyżej cztery pozycje, nigdy nad treścią (rezerwowany
  dolny margines).
- **Tło:** w menu zawsze przyciemnione i rozmyte raz (statyczna bitmapa), bez
  czytelnego tekstu z grafik gier pod listami.
- **Jeden kolor akcentu** w całej aplikacji; zielony/żółty/czerwony wyłącznie dla stanu
  hosta i akcji niszczących.

## 5. Poprawki niezależne od przeprojektowania

Da się je zrobić od razu, bez czekania na nową strukturę:

1. Wstecz na ekranie głównym wraca do wyboru profilu lub hosta; zamknięcie aplikacji
   tylko z menu.
2. Autopilot: stan i przycisk „Uruchom analizę" zamiast automatycznego startu.
3. Pomoc dostępna offline (krótka strona w aplikacji) zamiast GitHuba.
4. „Pozostało dziś" w godzinach i minutach.
5. Napis o bibliotece tylko w stanach „odświeżam" i „nieaktualna".
6. Etykiety typu na przyciskach filtrów („Sortuj: …").

## 6. Kolejność prac

1. Poprawki z sekcji 5 (jeden mały pakiet).
2. Wspólny komponent panelu i listy (sekcja 4) jako osobna klasa widoku; przepięcie
   istniejących paneli na niego bez zmiany zawartości.
3. Ustawienia pełnoekranowe (3.2) i przeniesienie pozycji z „Opcje".
4. Karta gry (3.4) i panel hosta (3.3).
5. Panel filtrów i chipy (3.5), razem z nową siatką z pakietu P2.
6. Szybkie menu (3.1) i usunięcie paska ikon.

Każdy krok jest niezależnie używalny i nie dotyka kodu sesji, więc może iść
równolegle z hardeningiem; ryzykiem są tylko konflikty w `ConsoleActivity`, dlatego nowe
komponenty powstają jako osobne klasy, a Activity tylko je podpina.
