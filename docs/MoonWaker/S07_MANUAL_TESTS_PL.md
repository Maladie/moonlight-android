# S07: instrukcja testów ręcznych (kandydat 6)

Kandydat 6 to niezrecenzowany artefakt do testów ręcznych, zbudowany z czystego worktree na commicie `94a3d389` (`codex/session-hardening`). Pełny przebieg 24/24 w labie nie został potwierdzony; powody i dowody: `work/s07-continuation/CURRENT-OWNER-20261003.md` (poza repozytorium). Instalację na TV i na hoście wykonuje właściciel.

## Artefakty

Katalog: `E:\Documents\ChatGPT\MoonWaker\outputs\s07-candidate-6\dist`

| Plik | SHA-256 |
|---|---|
| `MoonWakerHostInstaller.exe` | `e2e911a79ad24682c6afe205abba78c70ccbf1a94d78530818621dd0d648108c` |
| `app-nonRoot-debug.apk` | `3fde63cb917b8a3daa970c8dd6e1c485a57d9ef3d35911f0c9be921d6445be14` |
| `host-services-payload.zip` | `97813d7b90e0d6676593adadc42c7627cd467e44daae20fe875e75234eb49a8b` |

Przed instalacją porównaj sumy kontrolne. APK i host muszą pochodzić z tego samego kandydata.

## Instalacja (kolejność)

1. Host: uruchom `MoonWakerHostInstaller.exe` na prawdziwym hoście. Gateway ma być uruchomiony po instalacji (usługa `MoonWakerGateway`).
2. TV (BRAVIA 4K VH22, API 31, `192.168.1.31:5555`): zainstaluj `app-nonRoot-debug.apk` (`adb connect 192.168.1.31:5555`, potem `adb install -r`). Na TV jest APK z mw-01. Jeśli `-r` odrzuci instalację z powodu niezgodnego podpisu, odinstaluj starą aplikację (utracisz jej dane i parowanie) i zainstaluj ponownie.
3. Sparuj TV z hostem, jeśli parowanie nie przetrwało.

## Ustawienia przed testami

- Retencja strumienia w tle: do testów T25 ustaw na **0** (aplikacja → ustawienia strumienia w tle). Do pozostałych testów zostaw domyślną (>0).
- Profil dziecka: dzienny limit czasu ustaw na krótki (np. 5–7 min), tylko na czas testów D1 i D2.

## Testy

Dla każdego testu zapisz wynik (OK / nie OK) i, jeśli nie OK, co widać na ekranie oraz godzinę.

### A. Sesja rodzica (podstawy)
- A1. Start gry z biblioteki: obraz i sterowanie działają, wejście z pilota/pada dociera do gry.
- A2. Home na TV i powrót ikoną aplikacji: strumień wznawia się z obrazem (bez zamrożenia) i z działającym wejściem.
- A3. Menu nakładki (overlay): otwarcie i zamknięcie; wejście działa po zamknięciu.
- A4. Zamknięcie aplikacji ("Close game and stream"), natychmiastowy ponowny start: gra startuje bez ręcznego ponawiania.
- A5. Przełączenie gry A → B bez zrywania strumienia: nowa gra startuje, brak błyskania poprzedniej.
- A6. "Leave host": przy włączonym wyborze profilu otwiera bramkę profilu, a nie listę hostów (to oczekiwane).

### B. Tło i retencja
- B1. T08 (Back, potem Home): aplikacja nie zrywa strumienia, po powrocie ikoną obraz wraca.
- B2. T25 (retencja 0): Home zatrzymuje strumień; powrót ikoną i wybór gry startuje nowy transport bez błędu "Connection Error".
- B3. T26 (PREPARING): start gry, szybkie anulowanie przed pierwszą klatką: brak wiszącego strumienia lub procesu gry na hoście; potem normalny start działa.

### C. Dziecko (D1 i D2 wymagają krótkiego limitu)
- D1. "End game" u dziecka: strumień zostaje, gra się kończy; ponowny start gry działa.
- D2. Wygaśnięcie czasu: po limicie strumień jest cięty **i** gra na hoście jest zakończona (sprawdź na hoście, że proces gry nie działa). Czas od startu do cięcia jest krótszy od limitu o czas startu gry; to oczekiwane.
- D3. Dziecko nie może przekazać działającej gry rodzicowi bez grantu rodzica (próba kończy się odmową, gra nie zostaje na hoście bez streamu).

### D. Audio (ręcznie, nie było testowane w labie)
- E1. Dźwięk gry słychać na TV, także po Home i powrocie (A2).
- E2. Po zamknięciu strumienia dźwięk lokalny na hoście wraca do poprzedniego urządzenia (sprawdź, że host nie został ściszony ani przełączony).

## Znane ograniczenia (nie zgłaszaj jako nowe błędy)
- Dwa przypadki brzegowe bramki dziecka zaakceptowane przez właściciela: (1) okno uruchamiania gry przed fazą `running`; (2) wspólny TV z grantami dziecka i rodzica.
- W labie dwa razy zawiesił się dekoder po wznowieniu (liczniki klatek stoją, transport żywy). Jeśli zobaczysz zamrożony obraz po A2, zapisz godzinę, czy dźwięk gra i czy pomaga ponowne Home i powrót. To jest główny trop do zbadania.
- Opcja systemowa "Don't keep activities" nie jest wspieraną konfiguracją.

## Zgłaszanie
Dla każdego "nie OK": numer testu, godzina, krótki opis. Logi hosta i aplikacji zbierz tylko na prośbę; nie wysyłaj ich z danymi logowania ani zrzutami pulpitu.
