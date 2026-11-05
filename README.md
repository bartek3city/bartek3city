# Rejestrator Rozmów (Android)

Aplikacja na Androida (10+) do automatycznego nagrywania rozmów telefonicznych z automatyczną analizą:
**transkrypcja z podziałem na mówców** + **podsumowanie, ustalenia, zadania, terminy** (Claude).

## Funkcje

- **Automatyczne nagrywanie** każdej rozmowy – przychodzącej i wychodzącej (start po odebraniu, stop po rozłączeniu).
- **Przycisk REC nad ekranem dzwonienia** – pływający, przesuwalny przycisk start/stop z licznikiem czasu.
- **Kafelek w szybkich ustawieniach** („Nagraj rozmowę”) i akcja *Zatrzymaj* w powiadomieniu.
- **Analiza po rozmowie** (w tle, ponawiana przy braku sieci):
  1. AssemblyAI – transkrypcja z diaryzacją (Mówca A / B …), domyślnie język polski,
  2. Claude (`claude-opus-5-5`) – tytuł, temat, kto jest kim, podsumowanie, kluczowe punkty,
     ustalenia, zadania (kto/co/termin), terminy, otwarte kwestie.
- **Archiwum rozmów** – lista z wyszukiwarką (po osobie, treści, ustaleniach), odtwarzacz,
  kliknięcie wypowiedzi przewija nagranie, udostępnianie podsumowania + pliku, ponowna analiza, usuwanie.
- **Import nagrań** – przycisk importu lub *Udostępnij → Rejestrator Rozmów* z innej aplikacji
  (np. z wbudowanego rejestratora rozmów Samsunga/Xiaomi), żeby przeanalizować nagranie najlepszej jakości.
- Dane (nagrania, transkrypcje, podsumowania) przechowywane lokalnie w bazie Room.

## Ważne – ograniczenia Androida

Od Androida 9/10 Google blokuje zwykłym aplikacjom bezpośredni dostęp do dźwięku linii telefonicznej
(źródło `VOICE_CALL` wymaga uprawnienia systemowego). Aplikacja robi maksimum tego, co jest możliwe bez roota:

| Tryb rozmowy | Twój głos | Głos rozmówcy |
|---|---|---|
| Głośnik | ✅ | ✅ dobrze |
| Słuchawka przy uchu | ✅ | ⚠️ zależy od telefonu – często cicho / wcale |
| Bluetooth / słuchawki przewodowe | ⚠️ zależy od telefonu | ❌ zwykle nie (dźwięk idzie tylko do słuchawek) |
| Telefon z rootem / producent pozwala na `VOICE_CALL` | ✅ | ✅ we wszystkich trybach |

W trybie **AUTO** aplikacja próbuje najpierw `VOICE_CALL` (linia – obie strony), a jeśli telefon go blokuje
lub nagrywa ciszę, przełącza się na mikrofon (`VOICE_RECOGNITION` → `VOICE_COMMUNICATION` → `MIC`).
Opcja **„Włączaj głośnik podczas rozmowy”** najbardziej poprawia nagrywanie rozmówcy.
Jeżeli Twój telefon ma **wbudowany rejestrator rozmów** (Samsung, Xiaomi, Google Phone w części krajów),
nagrywa on obie strony we wszystkich trybach – takie nagranie możesz udostępnić do tej aplikacji i dostać pełną analizę.

## Instalacja

1. Pobierz APK: zakładka **Actions** w repozytorium → ostatni build „Build APK” → artefakt `rejestrator-rozmow-apk`
   (albo zbuduj lokalnie: `./gradlew assembleDebug`, plik `app/build/outputs/apk/debug/app-debug.apk`).
2. Zainstaluj APK na telefonie (zezwól na instalację z nieznanych źródeł).
3. Otwórz aplikację → **Ustawienia** i przejdź listę konfiguracji:
   - nadaj uprawnienia,
   - włącz **usługę dostępności** „Rejestrator Rozmów” (na Androidzie 13+ dla aplikacji spoza Sklepu Play najpierw:
     *Informacje o aplikacji → ⋮ → Zezwól na ustawienia z ograniczonym dostępem*),
   - wyłącz optymalizację baterii dla aplikacji,
   - wpisz klucze API:
     - **AssemblyAI** – https://www.assemblyai.com/ (transkrypcja + rozpoznawanie mówców),
     - **Anthropic** – https://console.anthropic.com/ (analiza i podsumowanie przez Claude).

## Architektura

```
service/CallRecorderService   AccessibilityService: stan połączenia (TelephonyCallback), nagrywanie,
                              przycisk REC (TYPE_ACCESSIBILITY_OVERLAY), powiadomienie, rejestr połączeń
service/RecordTileService     kafelek Quick Settings
recorder/CallRecorder         MediaRecorder AAC/M4A z automatycznym wyborem źródła i wykrywaniem ciszy
analysis/AssemblyAiClient     upload + transkrypcja z speaker_labels (OkHttp)
analysis/ClaudeSummarizer     podsumowanie w JSON przez oficjalne SDK Anthropic (anthropic-java)
analysis/AnalysisWorker       WorkManager: transkrypcja → podsumowanie → powiadomienie
data/*                        Room (Recording), ustawienia
ui/*                          Jetpack Compose: lista, szczegóły (podsumowanie/transkrypcja/odtwarzacz), ustawienia
```

Na modelach `claude-opus-5-5` / `claude-sonnet-5-5` / `claude-fable-5-1` włączony jest serwerowy *fallback*
(`server-side-fallback-2026-07-01`) – gdy model odmówi odpowiedzi, API automatycznie ponawia na modelu zapasowym.

## Prywatność i prawo

Nagrania i wyniki trzymane są tylko na telefonie. Do analizy audio trafia do AssemblyAI, a tekst transkrypcji do Anthropic.
W Polsce nagrywanie własnej rozmowy na własny użytek jest co do zasady dopuszczalne, ale jej rozpowszechnianie bez zgody
rozmówcy – nie; dobrą praktyką jest poinformowanie rozmówcy o nagrywaniu.
