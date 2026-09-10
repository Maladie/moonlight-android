# S03-LAB-WINDOWS-SIGNIN — instrukcja operatora

Status: zastąpiona referencja operatorska — nie wykonuj jej jako dowodu checkpointu. Poprawka `S03-A01-DETERMINISTIC-LAB-RUNNER` wymaga całkowicie skryptowego planu bez klikania podczas przebiegu. Fundament znajduje się w `lab/lab_runner.py`; `lab/example-plan.json` pozostaje `UNBOUND`, a konkretne scenariusze urządzeniowe zostaną dodane osobno. Codex nie wykonał logowania, instalacji ani zmian usług.

## Wiązanie kandydata

- Repozytorium/gałąź: `Maladie/moonlight-android`, `codex/session-hardening`
- Fingerprint źródła: `sha256:ddbb8c19e4f0b67ac0bd5fb177f6876e02cca9234217ea1f29816b520788ab83`
- APK debug: `app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk`
- Rozmiar/SHA-256 APK: `46760592` bajty; `sha256:667127002ef99067bd6d6a3874cef4240e43a774e61ade64658785e6909814b0` (lokalny build S03: `2026-09-09T13:35:49.8169885+02:00`).

## Zasady bezpieczeństwa i stan początkowy

1. Wykonuj test tylko na własnym hoście i Android TV. Instalację APK wykonuje użytkownik świadomie; nie jest ona dowodem sama w sobie.
2. Nie wpisuj hasła Windows, PIN-u, tokenu ani pełnego SID w terminalu, nazwie pliku, zrzucie lub raporcie. Dane logowania zmieniaj wyłącznie w oknie MoonWaker Host Control/Configurator.
3. Nie używaj w MoonWaker opcji „Przełącz użytkownika Windows”. Test niewłaściwego użytkownika ma potwierdzić brak niejawnego przejęcia/logoffu.
4. Nie zatrzymuj, nie instaluj i nie rekonfiguruj usług na potrzeby testu. Do scenariusza timeout użyj wyłącznie chwilowego odłączenia sieci Android TV.
5. Zapisz bez sekretów stan do przywrócenia: aktywny użytkownik Windows (`TARGET` albo `OTHER`), stan Wi‑Fi TV, ustawienie „Automatyczne przygotowanie streamu” oraz dwa pola grantu urządzenia: „Dostęp do profilu” i „ZDALNE LOGOWANIE”.
6. Po każdym scenariuszu zamknij uruchomioną grę zwykłą ścieżką. Po całym teście przywróć zapisane ustawienia, Wi‑Fi i ręcznie właściwy profil Windows.

## A. Sukces: Gateway i właściwa tożsamość przed streamerem

Warunki: dla profilu `TARGET` w Host Control → „Urządzenia i dostęp” urządzenie TV ma zaznaczone „Dostęp do profilu” i „ZDALNE LOGOWANIE”; zapisane dane są ważne. Żaden inny użytkownik Windows nie jest aktywny. Wyloguj lub zablokuj `TARGET` ręcznie tak, aby jego komponenty profilu/Sunshine wymagały zalogowania; nie zatrzymuj usług.

1. Na TV wybierz host, profil `TARGET` i jedną dozwoloną grę; naciśnij Play dokładnie raz.
2. Zapisz kolejno widoczne etapy, jeśli się pojawią: „Oczekiwanie na potwierdzenie gotowości hosta…”, „Sprawdzanie dostępu do wybranego profilu…”, „Uruchamianie sesji Windows…”, „Uruchamianie usług profilu…”.
3. PASS: Windows loguje `TARGET`, powstaje jeden stream i jedna instancja gry, a obraz/wejście gry pojawiają się dopiero po istniejącej bramie gotowości. Brak oczekiwania na ręczne uruchomienie Sunshine i brak drugiego logowania/streamu/gry.
4. W Ustawieniach Android wybierz „Export app diagnostics” i zapisz sanitizowany plik jako `S03-A-success-diagnostics.jsonl`.
5. W diagnostyce PASS wymaga jednej bieżącej operacji z kolejnością `NETWORK_READY → GATEWAY_READY → PROFILE_AUTHORIZED → INTERACTIVE_SESSION_READY → PROFILE_READY → (PLAYNITE_READY, jeśli dotyczy) → TARGET_READY`, potem dokładnie jednego `orchestration.launch`.

## B. Ten sam preflight dla automatycznego warm-upu

1. Włącz „Automatyczne przygotowanie streamu”, ręcznie wyloguj/zablokuj `TARGET` jak w A i ponownie wybierz host, ale nie wybieraj gry.
2. PASS: host może się wybudzić/zalogować, a stan przygotowania dochodzi do gotowości bez uruchomienia gry i bez wejścia pulpitu. Diagnostyka ma tę samą kolejność preflightu z akcją `WARM_UP`.
3. Następnie kliknij jedną grę raz. PASS: klik dołącza do przygotowanej ścieżki; jedna gra i najwyżej jeden stream, bez ponownego logowania.
4. Eksport: `S03-B-warmup-diagnostics.jsonl`.

## C. Brak grantu zdalnego logowania

1. W Host Control → „Urządzenia i dostęp” pozostaw dla TV „Dostęp do profilu”, odznacz tylko „ZDALNE LOGOWANIE” i wybierz „Zapisz uprawnienia”. Ręcznie wyloguj `TARGET`, aby grant był potrzebny.
2. Na TV wybierz Play raz.
3. PASS: komunikat „To urządzenie nie może zdalnie zalogować profilu …”; nie uruchamia się gra, stream ani ponowna próba z hasłem. Nie występuje fallback do uprawnień rodzica.
4. Eksport: `S03-C-denied-diagnostics.jsonl`. Po eksporcie przywróć grant w Host Control i ponownie zapisz.

## D. Timeout bez zmiany usług lub danych logowania

1. Przywróć poprawny grant/dane. Ręcznie wyloguj `TARGET`; naciśnij Play raz.
2. Dopiero gdy pojawi się „Uruchamianie sesji Windows…”, odłącz sieć na Android TV na co najmniej 150 sekund. Nie zmieniaj sieci hosta i nie klikaj Retry. Następnie przywróć sieć TV.
3. PASS: bieżące żądanie kończy się komunikatem „Logowanie profilu … trwało zbyt długo” albo równoważnym błędem bieżącego preflightu; nie następuje spóźnione automatyczne uruchomienie gry/streamu po powrocie sieci. Broker może wygasić próbę po swojej stronie.
4. Eksport: `S03-D-timeout-diagnostics.jsonl`.

## E. Inny aktywny użytkownik Windows

1. Na PC ręcznie przełącz i zaloguj bezpośrednio użytkownika `OTHER`. Nie używaj przycisku przełączania użytkownika w MoonWaker. Na TV nadal wybierz profil `TARGET` i Play raz.
2. PASS: komunikat „Na komputerze aktywny jest inny profil Windows”; MoonWaker nie rozłącza, nie wylogowuje i nie przejmuje sesji `OTHER`, nie uruchamia gry ani streamu jako `TARGET` lub rodzic.
3. Eksport: `S03-E-wrong-user-diagnostics.jsonl`. Po eksporcie ręcznie przywróć pierwotny profil Windows.

## Dowód przekazywany do weryfikacji

Użytkownik przekazuje pięć sanitizowanych eksportów diagnostyki oraz plik `S03-LAB-WINDOWS-SIGNIN.json` o poniższej treści. Nie dołącza haseł, PIN-ów, tokenów, pełnych SID-ów ani niesanitizowanych logów hosta.

```json
{
  "check_id": "S03-LAB-WINDOWS-SIGNIN",
  "operator": "USER",
  "source_fingerprint": "sha256:ddbb8c19e4f0b67ac0bd5fb177f6876e02cca9234217ea1f29816b520788ab83",
  "apk_sha256": "sha256:667127002ef99067bd6d6a3874cef4240e43a774e61ade64658785e6909814b0",
  "observed_at_local": "YYYY-MM-DDTHH:MM:SS+02:00",
  "results": {
    "gateway_before_streamer_success": "PASS|FAIL",
    "warm_up_same_preflight": "PASS|FAIL",
    "remote_sign_in_denied_no_launch": "PASS|FAIL",
    "timeout_no_late_launch": "PASS|FAIL",
    "wrong_user_no_takeover": "PASS|FAIL",
    "state_restored": "PASS|FAIL"
  },
  "notes_sanitized": "krótki opis; użyj aliasów TARGET/OTHER"
}
```

Wynik operatora nie jest automatycznie wynikiem Codex. Po otrzymaniu plików niezależna weryfikacja musi sprawdzić hash APK/fingerprint, komplet scenariuszy, kolejność zdarzeń, brak zabronionych efektów i dopiero wtedy oznaczyć check PASS albo FAIL.
