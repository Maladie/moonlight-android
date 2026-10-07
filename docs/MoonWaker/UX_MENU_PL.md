# Menu, filtry i opcje — propozycja przegrupowania i wyglądu (styl PS5)

Status: propozycja do decyzji właściciela, 2026-10-05; uzupełniona decyzjami
właściciela z 2026-10-06 (struktura ekranu głównego, szybkie menu pod Guide, język
wizualny v4, nowa kolejność prac). Oparta na przeglądzie aplikacji na BRAVII VH22
(profil BASIA) przez adb: zrzuty ekranów, zrzuty drzewa widoków i kod `ConsoleActivity`.
Ten dokument nie zmienia kodu. Kroki 1–4 z sekcji 6 są już zaimplementowane na tej gałęzi.

Makiety wizualne v4 (poza repozytorium, niewersjonowane):
`D:\Maladie\moonlight-android\.claude\handoffs\mockups\` (`*-v4.png`, skrypt
`make_mockups.py`). Liczby w sekcji 4 są podane w px przy 1080p; w makietach 2 px = 1 dp.

## 1. Co jest dziś

| Miejsce | Jak się otwiera | Zawartość |
| --- | --- | --- |
| Pasek narzędzi (ekran główny) | D-pad w górę | Odśwież stan i bibliotekę · Ustawienia · Profil Windows (przełącz) · Discord lub Mikrofon USB · Opuść host |
| Panel hosta (`ConsoleHostPanel`) | OK na tytule hosta; MENU na kaflu hosta na ekranie wyboru | Pulpit · Obudź lub Uśpij · Zamknij stream; sekcja „Więcej": Test połączenia; na końcu czerwone: Twarde zamknięcie · Odparuj hosta |
| Ustawienia (`ConsoleSettingsScreen`) | ikona zębatki | pełny ekran: kategorie po lewej (m.in. Wygląd i dźwięk, Biblioteka, Hosty i profile, Informacje), treść po prawej |
| Karta gry (`ConsoleGameCard` + `ConsoleActionMenu`) | MENU / długie OK na kaflu | akcja główna, potem grupy pokazywane tylko gdy mają sens (sekcja 3.4) |
| Pasek filtrów (siatka) | D-pad w górę z siatki | Filtr (Wszystkie, Zainstalowane, Niezainstalowane, Ostatnio grane, Najczęściej uruchamiane, Nigdy nie uruchamiane) · Platformy (wielokrotny wybór) · Sortowanie (Ostatnio grane, Nazwa, Biblioteka, Gatunek, Czas gry, Najczęściej uruchamiane) · Wyszukaj (okno tekstowe) |
| Legenda | stała, dół ekranu | w siatce: Filtry · Resetuj · Szukaj · Wróć; na ekranie profili: Wybierz · Wstecz |
| Kontroler | ikona kontrolera | Zidentyfikuj · Wyłącz · Rozparuj · Ustawienia Bluetooth |
| Wyjście | z panelu Ustawienia | Wstecz na ekranie głównym opuszcza host (nie zamyka aplikacji); zamknięcie aplikacji tylko z menu |

Wspólny komponent panelu (`ConsolePanel`) obsługuje wszystkie panele. Pasek filtrów
i pasek ikon na ekranie głównym są w dawnej postaci.

## 2. Co przeszkadzało

Kroki 1–4 (sekcja 6) zajęły się punktami 1–3 i 5–8; otwarty zostaje punkt 4 (filtry).

1. **Jedno „Opcje" mieszało trzy poziomy:** ustawienia aplikacji (dźwięki), ustawienia hosta (integracje, logowanie) i doraźne regulacje streamu (nadpisania bitrate'u).
2. **Akcje hosta były w dwóch miejscach** (tytuł hosta i ekran wyboru hosta) z tą samą listą, w której sąsiadują „Pulpit" i „Odparuj hosta".
3. **Panel akcji gry miał dziewięć pozycji bez grup**: uruchamianie, instalacja, sesja, streaming, profil dziecka i widoczność w jednej liście.
4. **Pasek filtrów nie mówi, czym jest.** „Ostatnio grane" to sortowanie, ale nic na to nie wskazuje; „Wszystkie gry" i „Wszystkie platformy" wyglądają identycznie, choć jedno to pojedynczy wybór, a drugie wielokrotny. Prawy drążek tylko przenosi fokus na pasek.
5. **Autopilot uruchamiał analizę od razu po wejściu** w pozycję menu, zamiast pokazać stan i przycisk.
6. **README otwierało stronę GitHuba** z przyciskiem „Sign in"; na telewizorze nie da się jej użyć.
7. **Wstecz na ekranie głównym otwierało pytanie o zamknięcie aplikacji**, co łatwo wywołać przypadkiem; przy zdalnym sterowaniu zatrzymało to cały przebieg testu.
8. **Drobne:** „Pozostało dziś: 1381 min" zamiast „23 godz. 1 min"; stały napis „Biblioteka gier jest aktualna"; suwaki w procentach bez widocznego kroku; przełączniki „WŁ./WYŁ." jako pigułki o innym stylu niż reszta.

## 3. Docelowa struktura

### 3.1 Ekran główny: odchudzony pasek górny i Szukaj

Pasek górny zostaje, ale jest odchudzony; dawny pasek ikon z pięcioma przyciskami znika.

- **Lewa strona: stan hosta**, bez ramki w spoczynku: glif monitora, nazwa hosta, linia
  stanu z kolorową kropką („● Online"; z sesją: „● Stream · Pulpit · 12 min", zielona
  kropka). OK na nim otwiera panel hosta (3.3). Osobna pigułka „Pulpit"/stream znika.
- **Prawa strona: gołe ikony** Szukaj, Profil (awatar), Ustawienia oraz warunkowe ikony
  stanu: Discord (gdy zalogowany; plakietka nieprzeczytanych) i Mikrofon USB (gdy włączony
  i dozwolony dla profilu; plakietka stanu). Etykieta pojawia się tylko pod ikoną z fokusem.
- **Usunięte z paska:**
  - *Odśwież stan i bibliotekę.* Stan hosta podąża sam po K1 (migawka sesji z koordynatora).
    Sprawdzone w kodzie: przycisk woła `refreshDashboard()`, który poza stanem hostów
    odpytuje listę aplikacji (`appListPoller.pollNow`) i odświeża bibliotekę Playnite
    (`requestPlayniteRefresh`), więc to także synchronizacja biblioteki. Ta funkcja
    przechodzi do Ustawienia → Biblioteka jako „Odśwież bibliotekę"; sam stan hosta
    nie potrzebuje przycisku.
  - *Opuść host* → panel hosta, pozycja „Zmień hosta".
  - *Pełne sterowanie Discordem i mikrofonem* → szybkie menu (3.6) i Ustawienia.
- **Szukaj z ekranu głównego** otwiera pełną bibliotekę z klawiaturą ekranową nad
  siatką; wyniki filtrują siatkę na żywo po tytule (gry Playnite i aplikacje Sunshine);
  OK na wyniku = OK na kaflu.

### 3.2 Ustawienia (pełny ekran, lista kategorii po lewej, treść po prawej)

| Kategoria | Zawartość |
| --- | --- |
| Wygląd i dźwięk | dźwięki interfejsu, ambient, muzyka (wybór hosta / menu), efekty, ograniczenie animacji, wygaszacz ekranu, opis gry w karuzeli |
| Biblioteka | „Odśwież bibliotekę" (lista aplikacji + Playnite), ukryte aplikacje, domyślne sortowanie i filtr, widoczne platformy |
| Streaming | rozdzielczość, odświeżanie, bitrate, tempo klatek (dzisiejsze „Ustawienia streamingu"), nadpisania, Autopilot jako stan plus przycisk „Uruchom analizę" |
| Hosty i profile | host otwierany automatycznie, lista hostów; wybrany host otwiera: integracje, parowanie Gatewaya, test połączenia, odparuj |
| Kontrolery i akcesoria | kontrolery (identyfikuj, wyłącz, rozparuj), mikrofon USB, Bluetooth |
| Discord | logowanie, powiadomienia, dźwięki DM |
| Informacje | wersja aplikacji i hosta, eksport diagnostyki, pomoc dostępna offline |

Wszystkie pozycje z dawnego „Opcje" mają tu swoje miejsce; żadna nie znika.

### 3.3 Panel hosta (z tytułu hosta i z ekranu wyboru)

Nagłówek: nazwa, stan, aktywny profil Windows, wersja hosta. Trzy akcje główne:
**Pulpit**, **Obudź/Uśpij**, **Zamknij stream**. Poniżej sekcja „WIĘCEJ" z pozycjami
„Test połączenia" i „Zmień hosta", a na końcu, oddzielone i na czerwono, „Twarde
zamknięcie" i „Odparuj hosta". „Automatyczne przygotowanie streamu" przechodzi do
Ustawienia → Hosty i profile.

**Z aktywną sesją** pierwsze wiersze to „Wróć do streamu" i „Zamknij stream", dalej
„Uśpij hosta", potem sekcja WIĘCEJ; pozycje niszczące zawsze na końcu.

### 3.4 Karta gry (MENU lub długie OK na kaflu)

Nagłówek z okładką, nazwą, czasem gry, ostatnim uruchomieniem i źródłem. Jedna akcja
główna na górze (Uruchom, Wznów, Zainstaluj lub Dokończ instalację), potem grupy
pokazywane tylko wtedy, gdy mają sens:

- **Sesja:** Zakończ grę, Zamknij grę i stream (tylko gdy gra działa).
- **Streaming:** Ustawienia streamingu gry, Zoptymalizuj streaming.
- **Rodzina:** Udostępnij grę dziecku (tylko profil rodzica).
- **Biblioteka:** Ukryj lub Pokaż, Odinstaluj (czerwone, na końcu), Szczegóły.

### 3.5 Biblioteka: filtry i sortowanie

Zawartość bez zmian względem poprzedniej wersji; doprecyzowany wygląd:

- Prawy drążek lub L2 otwiera **jeden panel „Filtry"** z trzema sekcjami: Stan
  (pojedynczy wybór), Platformy (wielokrotny), Sortuj (pojedynczy) oraz przyciskiem
  „Wyczyść filtry".
- Nad siatką jeden rząd: **chipy tylko aktywnych filtrów** z etykietą typu, np.
  „Stan: Zainstalowane", „Sortuj: Czas gry", „Platformy: Steam, Epic" (chip „Wszystkie"
  znika, gdy filtr jest nieaktywny); przycisk **„Filtry"** z plakietką liczby aktywnych
  filtrów; po prawej **„Wyszukaj"**.
- Wyszukiwanie pod Y otwiera klawiaturę ekranową MoonWakera nad siatką, a wyniki
  filtrują siatkę na żywo; nie ma osobnego okna tekstowego.
- Pozycja „34 / 309" i opis gry zostają po lewej.
- **Górna krawędź siatki musi ucinać**, tak by pod nagłówkiem nie było widać paska
  poprzedniego rzędu. Pionowa zanikająca krawędź została usunięta 2026-10-06
  (commit 416a3dc3), więc przycięcie robi przycięcie widoku (clip), nie gradient.

### 3.6 Szybkie menu (pod Guide/PS)

Otwierane **krótkim naciśnięciem Guide/PS** (oczekiwany `KEYCODE_BUTTON_MODE`;
**przed implementacją sprawdzić na telewizorze z padem właściciela**, bo HOME nie da się
przechwycić). MENU/Options zostaje kontekstowe (karta gry na kaflu, panel hosta na
hoście); długie MENU nie jest używane. Podczas streamu Guide trafia do PC (nakładka
Steam/Xbox), więc szybkie menu dotyczy tylko konsoli.

- **Układ:** treść ekranu głównego rozmyta w tle; zegar w prawym górnym rogu; rząd dużych
  kart (Stream — tylko gdy jest sesja, Host, Discord z nieprzeczytanymi) z podpowiedzią
  „☰ Opcje" pod kartą z fokusem.
- **Karta Stream:** OK = „Wróć do streamu"; Opcje na niej = pływające menu: „Wróć do
  streamu", „Zamknij stream", czerwone „Zamknij grę i stream". Karta pulpitu nie ma
  okładki: wygenerowana grafika z gradientem (fiolet → morski), glif monitora w szklanym
  kółku, znacznik „NA ŻYWO" z czerwoną kropką. Bez przechwytu ani rozmycia prawdziwego
  pulpitu (bramka prywatności).
- **Dół:** wyśrodkowany rząd gołych ikon z plakietkami stanu: Stream, Host, Profil,
  Discord, Mikrofon, Kontrolery, Ustawienia, przerwa, Zasilanie/Zamknij MoonWaker.
  Ikona z fokusem dostaje matowe kółko i swoją etykietę.
- **Zawartość dawnych kafli:** Host — Obudź/Uśpij, Pulpit, Zmień hosta; Stream — Wróć do
  streamu, Zamknij stream, Zamknij grę i stream (tylko gdy coś działa); Profil — Zmień
  profil MoonWaker, Przełącz użytkownika Windows (z wyraźnym ostrzeżeniem).
- **Konflikt:** opcja wyzwalacza nakładki DM Discorda „guide" (`DiscordDmShortcutHandler`)
  koliduje z tym skrótem; trzeba ją usunąć albo ograniczyć do streamu.
- Karta Stream czyta migawkę sesji, więc implementacja po MW-05 K3.

### 3.7 Ekrany wejścia: wybór hosta, profil, PIN

Odniesienie wizualne: makiety `10-host-select-v4.png` … `17-intro-4-v4.png` oraz przejście
`flow-host-to-carousel-v4.webp` w niewersjonowanym folderze makiet (patrz nagłówek).
Zawartość, stany i reguły ekranów się nie zmieniają; zmienia się wygląd, kolejność kafli
i przejścia.

**Wspólna oprawa trzech ekranów:**

- **Tło:** ciemna baza z dwiema powolnymi poświatami otoczenia; na wyborze hosta w kolorze
  hosta, po wyborze profilu przechodzą w kolor profilu. To samo tło zostaje przez
  wszystkie trzy ekrany.
- **Nagłówek wyśrodkowany:** eyebrow 24 px (wersaliki z rozstrzeleniem, szary), tytuł
  44 Semibold, podtytuł 26; rząd elementów wyśrodkowany w okolicy y ≈ 520 przy 1080p.
- **Zegar** w prawym górnym rogu (44 Light); numer wersji przenosi się do Ustawienia →
  Informacje.
- **Jedna legenda** wyśrodkowana u dołu w stylu z 4.1 (np. „OK Wybierz · ☰ Opcje ·
  ↩ Wstecz"); pokazuje tylko akcje, które na danym elemencie coś robią.
- **Przejścia bez czarnej klatki:** tło zostaje, a rząd elementów przenika się w nowy
  w 220 ms; Wstecz odtwarza to samo w odwrotną stronę.

**Wybór hosta („Wybierz komputer"):**

- Eyebrow „MOONWAKER", tytuł „Wybierz komputer", podtytuł „Wyszukiwanie hostów w sieci…",
  gdy wykrywanie jest aktywne (nowe hosty nadal nie przejmują fokusu).
- **Kafle 240 × 240 px**, promień 32 px: wygenerowana grafika hosta (jak dzisiejszy
  `HostAvatarDrawable`) z liniowym glifem monitora; pod kaflem nazwa (30 Semibold) i jedna
  linia stanu (24) z kolorową kropką.
- **Stany kafla:**

  | Stan | Wygląd |
  | --- | --- |
  | Online | zielona kropka, „Online" |
  | Sesja aktywna | znacznik „NA ŻYWO" na kaflu, czerwona kropka, „Stream · Pulpit · 12 min" |
  | Budzenie | bursztynowy pierścień postępu wokół kafla, „Budzenie… 12 s" (sekundy liczone na żywo) |
  | Uśpiony | plakietka z księżycem w rogu kafla, szaroniebieska kropka, „Uśpiony" |
  | Offline / niedostępny | kafel odbarwiony i z 60 % krycia, czerwona kropka, „Niedostępny" |
  | Niesparowany | bursztynowa plakietka, „Sparuj"; OK otwiera parowanie |
  | Profil wymaga uwagi | bursztynowa plakietka „!", np. „Zaloguj się w Windows" |

- **„Dodaj hosta" na końcu rzędu** (dziś jest pierwszy): szklany kafel z plusem.
- **Fokus:** jak dla kart (4.2): pierścień, poświata w kolorze hosta, skala 1,08;
  „☰ Opcje" pod kaflem z fokusem.
- **Opcje** otwierają panel hosta v4 (3.3) nad rozmytym tłem (4.3).

**Wybór profilu („Kto gra?"):**

- Eyebrow = nazwa hosta z kropką stanu, tytuł „Kto gra?".
- **Awatary okrągłe 176 px**, pod nimi imię (30 Semibold) i najwyżej dwie linie stanu
  (24) zamiast dzisiejszych stosów plakietek.
- **Plakietki wycięte na krawędzi awatara:** kłódka dla profilu z PIN-em, „A" dla
  logowania automatycznego; „Aktywny na PC" jako zielona kropka w linii stanu.
- **Profil dziecka:** dwie linie, „Dziecko · <rodzic>" i „zostało 1 godz. 20 min".
- **Fokus:** pierścień z odstępem, poświata w kolorze awatara, skala 1,1; imiona bez
  fokusu przygaszone do ok. 70 %.
- **„☰ Opcje" tylko tam, gdzie coś robi.** Opcje otwierają małe pływające menu (jak
  w 3.6): „Logowanie automatyczne" (przełącznik, tylko dla profili, które dziś mogą je
  mieć) i „Zmień komputer".
- Reguły dziecka, PIN-u i logowania automatycznego bez zmian; Wstecz wraca do wyboru
  hosta.

**PIN:**

- Ekran profili zostaje **rozmyty w tle** (4.3); awatar wybranego profilu przesuwa się
  na górę ekranu w 240 ms, pod nim „Wprowadź PIN" i „<profil> · <host>".
- **Cztery kropki**, kontur → wypełnienie, bieżąca pozycja podświetlona;
  **automatyczne zatwierdzenie po czwartej cyfrze bez zmian.**
- **Sposób wpisywania wg ostatniego urządzenia wejścia:**
  - pilot: klawiatura 3 × 4 ze szklanymi klawiszami, „⌫" w lewym dolnym polu, fokus jako
    jasne wypełnienie z ciemną cyfrą;
  - pad: pasek 2 × 5 „cyfra ↔ przycisk" (glify PlayStation albo Xbox, mapowanie
    przycisków bez zmian).
- **Błąd:** kropki drgają ±12 px trzy razy w 360 ms (przy ograniczeniu animacji zamiast
  tego jeden czerwony błysk) i czerwony komunikat „Nieprawidłowy PIN".
- **Odliczanie blokady:** pierścień wokół awatara odlicza czas („Spróbuj ponownie za 25 s"),
  klawisze przygaszone do 40 %.
- **Sprawdzanie i budzenie offline:** cienki pierścień postępu wokół awatara
  z istniejącymi komunikatami („Sprawdzanie PIN-u…", „Wybudzanie komputera…" itd.).
- **`FLAG_SECURE` zostaje.** Zrzuty tego ekranu są z założenia niemożliwe, więc ekran
  PIN sprawdza się okiem na telewizorze; makiety 14–16 powstały z kodu.

### 3.8 Animacja wejścia

Animacja zasłania pierwsze ładowanie karuzeli po wybraniu profilu, po poprawnym PIN-ie
albo przy automatycznym wejściu na profil (bramka pominięta przy starcie lub przy wyborze
hosta; wtedy awatar zaczyna na środku). Po PIN-ie i wejściu automatycznym animacja startuje
na gładkim tle powłoki — z ekranu PIN nie powstaje żaden zrzut, a `FLAG_SECURE` znika
dopiero, gdy pierwsza klatka animacji zakryła ekran PIN. Odniesienie: makiety `17-intro-1-v4.png` … `17-intro-4-v4.png` i końcówka
przejścia `flow-host-to-carousel-v4.webp` (osobno `intro-v4.webp`).

| Czas | Co się dzieje |
| --- | --- |
| 0–150 ms | wybrany awatar rośnie do 1,25, reszta ekranu znika |
| 150–750 ms | awatar przechodzi w miękką kulę światła; jedna szeroka smuga światła przesuwa się z lewej na prawo; 24–40 rozmytych drobin unosi się powoli (krycie ≤ 40 %); poświata otoczenia rośnie |
| 400–1000 ms | pojawia się znak półksiężyca i „Witaj, <profil>" (32 px); trzymają się do gotowości |
| wyjście, ok. 450 ms | ekran główny wchodzi z przybliżenia 1,04 → 1,0; widoczne okładki podnoszą się o 24 px z opóźnieniem 30 ms jedna po drugiej; poświata przechodzi w grafikę gry z fokusem |

- **Warunek gotowości:** dane lokalne pokazane, karuzela widoczna, żadna widoczna okładka
  nie czeka na ładowanie (zdekodowana albo bez grafiki / z nieudanym pobraniem), a dla
  okładki z fokusem grafika tła jest pokazana albo jej pobranie skończyło się bez grafiki. Odświeżenie profili nie
  wchodzi do warunku: bramka właśnie pobrała profile, a ponowne `/profiles` + `/capabilities`
  trwa sekundy i nie zmienia karuzeli.
- **Czas:** co najmniej 1,8 s (jak `intro-v4.webp`). Po 4,5 s animacja przestaje czekać
  i pokazuje ekran główny ze szklanymi zaślepkami okładek.
- **Pominięcie:** dowolny klawisz po 300 ms, jeśli ekran jest gotowy.
- **Kiedy się nie pokazuje:** po powrocie ze streamu (ta ścieżka już jest natychmiastowa)
  ani po zamknięciu szybkiego menu. Przy zmianie hosta wersja skrócona do 600 ms.
- **Ograniczenie animacji:** bez drobin, smugi i przybliżenia; statyczny znak i przenikanie
  200 ms, nadal z czekaniem na warunek gotowości.
- **Koszt na API 31 (BRAVIA):** tylko przesunięcia, skala i alfa na GPU; smuga to gotowa
  bitmapa (ok. 512 × 1080) przesuwana po ekranie; drobiny rysuje jeden widok ze sprite'ami
  z cache; poświata to wcześniej rozmyta bitmapa 480 × 270; żadnego rozmycia co klatkę.
  Smuga, znak i drobiny powstają raz na proces w tle; kolory to stała fioletowa paleta
  makiety, niezależna od profilu.

## 4. Język wizualny (v4)

Źródła: wytyczne Android TV / Compose for TV (układ, system fokusu, kolory, immersive
list, kształty), Apple HIG tvOS (układ, fokus i zaznaczenie, typografia, materiały /
Liquid Glass); odniesienia: PS5, Xbox, Steam Big Picture, Switch 2.

### 4.1 Układ i panel

- **Bezpieczny obszar:** ok. 5 % = 96 px po bokach i 54 px u góry i dołu przy 1080p
  (48/27 dp).
- **Jeden komponent panelu** dla wszystkich list: eyebrow, tytuł, opis; etykieta po
  lewej, wartość po prawej (przełącznik, wartość z chevronem albo nic); nagłówki sekcji
  wersalikami z dużym odstępem nad; stopka z legendą.
- **Akcje niszczące:** zawsze na końcu listy, oddzielone, czerwony tekst, potwierdzenie
  z nazwą obiektu („Odparować Komputer Mariusza?").
- **Wartości:** zamiast suwaków w procentach stopnie 0–10 z widoczną wartością
  i zmianą lewo/prawo; przełączniki jako jednolity komponent z dwoma stanami.
- **Legenda:** jeden styl, najwyżej cztery pozycje, nigdy nad treścią (rezerwowany
  dolny margines).
- **Kolor:** jeden akcent w całej aplikacji; zielony/żółty/czerwony wyłącznie dla stanu
  i akcji niszczących.

### 4.2 Fokus: trzy sposoby wg typu elementu

1. **Obrazy i karty:** cienki biały pierścień 3 px z odstępem 4–5 px, miękka poświata
   w kolorze okładki, cień, skala 1,03–1,1 zależnie od rozmiaru (domyślna `Card` z Compose
   TV: 1,1 i obrys 3 dp).
2. **Kontrolki tekstowe** (stan hosta, wiersze menu i paneli): jasne wypełnienie
   (prawie białe) z ciemnym tekstem, kształt pigułki lub zaokrąglony.
3. **Gołe ikony:** matowe kółko za ikoną i etykieta pod nią.

Na pierścień i poświatę zostaje ok. 12 px zapasu (padding albo wyłączone przycinanie).
Koniec z grubymi cyjanowymi obrysami.

### 4.3 Materiały: rozmycie zamiast ciemnienia

- **Duże menu przykrywające część ekranu** (panele hosta, filtrów, karty gry,
  ustawień oraz szybkie menu): treść pod spodem jest **rozmyta**, nie mocno
  przyciemniona. Rozmycie tła: promień 32 px przy 1080p (16 dp), liczone raz na
  zrzucie w ćwierć rozdzielczości w chwili otwarcia menu; tint rgb(6,8,12) 30 %
  (szybkie menu: rośnie do 50 % ku dołowi pod rząd ikon).
- **Same panele są szkłem:** własne rozmycie 40 px, tint rgb(16,18,26) 50 %, nasycenie
  ×1,3, biały woal 7 %, kontur 1,5 px jaśniejszy u góry, promień narożników 36 px,
  panel pływa z marginesami (np. x 1196–1896, y 24–1056 przy 1080p).
- **Małe pływające menu** (Opcje) mają lżejszą drugą warstwę (rozmycie 10 px, tint 15 %),
  żeby karty za nimi pozostały rozpoznawalne.
- **Nad zaparkowanym streamem** (SurfaceView) rozmycie jest niemożliwe: tylko
  przyciemnienie.

### 4.4 Kolor otoczenia

Tło tonowane kolorem okładki gry z fokusem (Palette lub Material Color Utilities na
bitmapie ok. 32 px, cache na grę); lewa i dolna część przyciemniona gradientem pod tekstem;
poświata fokusu w tym samym kolorze.

### 4.5 Kształty

Okładki promień 20 px, karty 32 px, panele 36 px; skala kształtów Material 4/8/12/16/28 dp.

### 4.6 Typografia (px przy 1080p)

| Element | Rozmiar |
| --- | --- |
| tytuł gry | 46 Semibold |
| tytuł panelu | 44 Semibold |
| tytuł karty | 32 Semibold |
| treść, wiersze | 28–30 |
| podtytuł | 26 |
| podpisy, nagłówki sekcji | 24 (nagłówki: wersaliki z rozstrzeleniem, szare) |

Nic poniżej 24 px: plakietki rysowane dziś w 16–22 px trzeba powiększyć. Czcionka na
urządzeniu: Roboto / Google Sans. Bez wersalików w treści poza nagłówkami sekcji.

**Centrowanie tekstu w pigułkach, chipach i plakietkach:** centrować na polu glifów, nie
na metrykach czcionki; na Androidzie `includeFontPadding=false` i jawna linia bazowa
(`firstBaselineToTopHeight`) albo własne rysowanie.

### 4.7 Ikony

Jeden zestaw ikon liniowych, kreska ok. 2,6 px, zaokrąglone końce; plakietki stanu
wycięte w ikonie; liczniki w małych pigułkach.

### 4.8 Koszt na Android TV API 31 (Sony BRAVIA)

- Rozmycie przez `RenderEffect` na zmniejszonym zrzucie (ok. 0,5 MB dla 480×270); karty
  ze szkła przycinają tę samą rozmytą bitmapę zamiast rozmywać osobno; żadnego
  rozmycia na żywo co klatkę.
- Poświaty i cienie jako bitmapy z cache albo 9-patch na kolor (unikać wielu
  `setShadowLayer` i elevation).
- Animacja fokusu = skala + alfa na GPU.

## 5. Poprawki niezależne od przeprojektowania

Zrobione w krokach 1–2 (sekcja 6): Wstecz na ekranie głównym nie zamyka aplikacji,
Autopilot jako stan i przycisk, pomoc offline, „Pozostało dziś" w godzinach i minutach,
napis o bibliotece tylko w stanach „odświeżam" i „nieaktualna", etykiety typu na
przyciskach filtrów.

## 6. Kolejność prac

Zrobione na tej gałęzi (siatka z pakietu P2 też już jest w użyciu):

1. Poprawki z sekcji 5.
2. Wspólny komponent panelu (`ConsolePanel`) jako osobna klasa widoku; przepięcie
   istniejących paneli bez zmiany zawartości.
3. Ustawienia pełnoekranowe (3.2) i przeniesienie pozycji z „Opcje".
4. Karta gry (3.4) i panel hosta (3.3) w obecnej postaci.

Dalej:

- **5a. Wspólna warstwa wizualna:** panel ze szkła, zrzut tła z rozmyciem, trzy sposoby
  fokusu, centrowanie tekstu, zestaw ikon (sekcja 4), zastosowane do istniejących
  paneli (host, karta gry, ustawienia, menu akcji) jako osobne klasy widoków;
  `ConsoleActivity` tylko je podpina. Przy okazji sprawdzić rozmiary tekstu z kroków 1–2
  względem tabeli 4.6.
- **5b. Panel filtrów i chipy (3.5)** wraz z przycięciem górnej krawędzi siatki.
- **6a. Pasek górny (3.1):** kafel stanu hosta, warunkowe ikony Discord i mikrofon,
  Szukaj z klawiaturą nad siatką; przeniesienie „Odśwież bibliotekę" do Ustawienia →
  Biblioteka; „Zmień hosta" do panelu hosta; wariant panelu hosta z sesją.
- **6b. Szybkie menu pod Guide (3.6):** najpierw weryfikacja kodu klawisza na
  telewizorze, rozstrzygnięcie opcji „guide" w `DiscordDmShortcutHandler`; po MW-05 K3,
  bo karta Stream czyta migawkę sesji.
- **7. Ekrany wejścia (3.7) i animacja wejścia (3.8).** Opiera się na wspólnej warstwie
  wizualnej z 5a (tło z rozmyciem, szkło, sposoby fokusu, centrowanie tekstu, ikony),
  więc zaczyna się dopiero po akceptacji i scaleniu 5a. Jest niezależny od MW-05 K3/K4
  z jednym wyjątkiem: reguła „nie po powrocie ze streamu" czyta migawkę sesji
  (`SessionModel` z K3), więc 7d wchodzi po K3.
  - **7a. Wspólna oprawa ekranów wejścia i wybór hosta.** Warstwa wyboru hosta budowana
    dziś w `ConsoleActivity.buildHostSelectionLayer` / `createHostSelectionTile`
    przechodzi do własnej klasy widoku.
  - **7b. Wybór profilu i jego menu Opcje.**
  - **7c. Ekran PIN.**
  - **7d. Animacja wejścia.** Najpierw pomiar rzeczywistego czasu do gotowości
    `prepareInitialCarouselFrame()` na telewizorze lub w laboratorium, przy zimnym i ciepłym
    starcie; dopiero potem strojenie minimum (1,8 s) i limitu (ustalony przez właściciela: 4,5 s).

  Każdy podkrok: build → zrzuty (emulator laboratorium, gdzie się da; ekran PIN okiem na
  telewizorze, bo `FLAG_SECURE`) → akceptacja właściciela → commit. Nowe ekrany są
  osobnymi klasami widoków.

Każdy krok: build → zrzuty z telewizora → akceptacja właściciela → commit. Każdy jest
niezależnie używalny i nie dotyka kodu sesji, więc może iść równolegle z hardeningiem
(6b i 7d czekają na K3); ryzykiem są tylko konflikty w `ConsoleActivity`, dlatego nowe
komponenty powstają jako osobne klasy, a Activity tylko je podpina i nie może urosnąć netto.
