# mk-backup

Aplikacja na Androida do **cyklicznego, przyrostowego backupu wybranych folderów z pamięci
wewnętrznej telefonu** na własny serwer. Wszystkie kopie trafiają tam jako **jedno archiwum
`.tar.gz` na wersję**.

Serwer: <https://github.com/michalkulik/mk-backup-server>

---

## Funkcje

* **Wybór folderów** — foldery wybiera się systemowym selektorem (Storage Access Framework) albo,
  po włączeniu w Ustawieniach dostępu do „wszystkich plików”, wbudowaną przeglądarką pamięci —
  dzięki niej można wskazać także foldery ukryte przed selektorem, np. `Android/data` czy
  `Downloads`. Uprawnienie do wybranego katalogu jest zapamiętywane i działa po restarcie telefonu.
* **Cel wysyłki** — adres serwera i token API, osobno dla każdego zestawu (albo domyślnie w
  ustawieniach). Schemat `http://` jest dopisywany automatycznie; wystarczy podać host i port
  (`backup.example.com:8090`), a `https://` wpisuje się tylko wtedy, gdy serwer używa TLS.
* **Harmonogram** — co godzinę, co 3/6/12 godzin, codziennie, co 2 dni lub co tydzień, z wyborem
  **godziny rozpoczęcia** pierwszego uruchomienia. Dodatkowo „tylko podczas ładowania”
  i „tylko w sieciach bez limitu”.
* **Uprawnienia systemowe** — w Ustawieniach jest przycisk do włączenia dostępu do wszystkich
  plików oraz do wyłączenia optymalizacji baterii (żeby Android nie odkładał kopii w tle).
* **Retencja** — ile najnowszych wersji ma zostać na serwerze; starsze archiwa serwer usuwa sam po
  zatwierdzeniu nowej wersji.
* **Diff przyrostowy** — aplikacja liczy SHA-256 tylko dla plików, których rozmiar lub data
  modyfikacji się zmieniły (dla pozostałych używa sumy z poprzedniego przebiegu), więc kolejny
  backup wysyła wyłącznie nowe i zmienione pliki.
* **Jedno archiwum na wersję** — serwer składa wysłane pliki w spójne drzewo i przebudowuje z niego
  pojedynczy `.tar.gz`.
* **Wykluczenia** — wzorce glob (`*.tmp`, `Cache/**`, `.thumbnails`) dopasowywane do ścieżki lub
  nazwy pliku.
* **Natywny Material Design 3** — cały interfejs w Jetpack Compose; aplikacja działa też w trybie
  ciemnym.
* **Historia i podgląd** — ostatnie uruchomienia z liczbą wysłanych plików oraz lista wersji na
  serwerze z możliwością usunięcia.
* **Powiadomienie o wyniku** — po każdym zakończonym przebiegu aplikacja wysyła powiadomienie
  systemowe (sukces lub porażka) ze szczegółami: nazwa zestawu, liczba i rozmiar wysłanych plików,
  nowe / zmienione / usunięte, numer wersji oraz treść błędu. Wysyłanie można wyłączyć przełącznikiem
  w Ustawieniach. Na Androidzie 13+ aplikacja prosi o uprawnienie do powiadomień.

## Jak to działa

```
        WorkManager (co N godzin / „Uruchom teraz”)
                      │
                      ▼
   ┌────────────────────────────────────────────────────────────────┐
   │ 1. skanowanie folderów (DocumentsContract, bez DocumentFile)   │
   │ 2. hash tylko tam, gdzie zmienił się rozmiar lub mtime         │
   │ 3. klasyfikacja: dodane / zmienione / usunięte / bez zmian     │
   │ 4. POST /sessions  →  PUT zmienionych plików (gzip)            │
   │ 5. POST /sessions/{id}/finish  → serwer buduje archiwum         │
   │ 6. zapis lokalnego manifestu (dopiero po potwierdzeniu)        │
   └────────────────────────────────────────────────────────────────┘
```

Kluczowe elementy kodu:

| Plik | Rola |
| --- | --- |
| `core/BackupSet.kt` | model zestawu, wpisu manifestu i przebiegu (`RunRecord`). |
| `core/BackupStore.kt` | trwałość zestawów, manifestów i historii (JSON w `SharedPreferences`). |
| `backup/FileScanner.kt` | przejście po drzewach SAF i policzenie plików. |
| `backup/DiffEngine.kt` | czysta logika klasyfikacji różnic (pokryta testami). |
| `backup/BackupEngine.kt` | cały przebieg: skanowanie → hash → diff → wysyłka → zatwierdzenie. |
| `net/BackupClient.kt` | klient HTTP API (`OkHttp`, ciało gzip strumieniowo). |
| `work/BackupScheduler.kt` | zamiana zestawów na zadania `WorkManager`. |
| `work/BackupWorker.kt` | wykonanie zadania jako usługa pierwszoplanowa (`dataSync`). |
| `ui/` | ekrany Compose: lista zestawów, edycja, szczegóły, ustawienia. |

### Dlaczego diff, a nie „wszystko od nowa”

Przy pierwszym uruchomieniu wysyłane jest wszystko. Przy kolejnych:

1. plik, którego rozmiar i czas modyfikacji się nie zmieniły, **nie jest w ogóle czytany** — do
   manifestu wraca zapisana wcześniej suma SHA-256,
2. plik, który się zmienił, jest hashowany; jeśli suma jest taka sama jak poprzednio, nie jest
   wysyłany (zmienił się tylko np. znacznik czasu),
3. do serwera lecą wyłącznie pliki nowe i faktycznie zmienione, spakowane gzipem w locie,
4. pliki usunięte na telefonie są usuwane także na serwerze.

Po reinstalacji aplikacji lokalny manifest znika — wtedy aplikacja pobiera manifest z serwera
(`GET /sets/{device}/{set}/manifest`) i używa go jako bazy, żeby nie wysyłać wszystkiego ponownie.

## Wymagania

* Android 8.0+ (`minSdk 26`), zbudowane na Androidzie 16 (API 36).
* Serwer mk-backup (patrz repozytorium obok) — najprościej `docker compose up -d`.
* Wykluczenie z optymalizacji baterii jest zalecane, żeby system nie odkładał zadań w tle.

> **Pamięć wewnętrzna:** Android nie daje zwykłej aplikacji dostępu do dowolnych ścieżek. mk-backup
> domyślnie używa systemowego selektora katalogów, więc folder wskazujesz raz, a aplikacja dostaje
> trwałe uprawnienie tylko do niego. Foldery `Android/data` i `Android/obb` są ukryte przed tym
> selektorem — można je wskazać po włączeniu w Ustawieniach dostępu do „wszystkich plików”
> i użyciu wbudowanej przeglądarki pamięci. `Android/obb` bywa nadal zablokowany przez system.

## Budowanie

```bash
# JDK 17+ (np. JetBrains Runtime z Android Studio) i Android SDK 37
./gradlew :app:assembleDebug     # APK debug
./gradlew :app:assembleRelease   # podpisany APK release
./gradlew :app:testDebugUnitTest # testy jednostkowe
```

`local.properties` musi wskazywać SDK (`sdk.dir=…`) albo ustaw `ANDROID_HOME`.

### Podpisywanie

Klucz `keystore/mkbackup-release.jks` (alias `mkbackup`, hasła w `gradle.properties`) **jest celowo
wersjonowany w repozytorium**, żeby każdy build — lokalny i w CI — dawał APK podpisany tym samym
kluczem i mógł aktualizować zainstalowaną aplikację. To aplikacja osobista, więc klucz nie chroni
żadnego sekretu; jeśli chcesz własny, podmień plik i wpisy w `gradle.properties`.

## Wydania

Wypchnięcie tagu (`v1.0.0`) uruchamia workflow `.github/workflows/release.yml`, który buduje
podpisany APK i dołącza go do GitHub Release.

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## Testy

Testy jednostkowe pokrywają logikę, która decyduje o poprawności przyrostów:

```bash
./gradlew :app:testDebugUnitTest
```

* `DiffEngineTest` — klasyfikacja plików na dodane / zmienione / usunięte / bez zmian.
* `GlobMatcherTest` — wzorce wykluczeń (`*`, `**`, `?`).

## Znane ograniczenia

* Archiwum na serwerze jest przebudowywane przy każdej wersji, więc czas rośnie wraz z łącznym
  rozmiarem danych. Serwer można przełączyć w tryb „tylko drzewo”
  (`MK_BACKUP_CREATE_ARCHIVE=false`), wtedy przyrostowość wysyłki działa dalej, ale nie ma pliku
  archiwum.
* WorkManager nie wykonuje zadań częściej niż co 15 minut; najkrótszy dostępny interwał to godzina.
* Samsung i inne agresywne nakładki mogą odraczać zadania w tle — warto wykluczyć mk-backup z
  optymalizacji baterii (przycisk w Ustawieniach).
* Dostęp do „wszystkich plików” (`MANAGE_EXTERNAL_STORAGE`) nie jest wymagany do zwykłego wyboru
  folderów przez systemowy selektor; jest potrzebny tylko do folderów ukrytych (np.
  `Android/data`). Sklepy takie jak Google Play ograniczają to uprawnienie, ale to aplikacja
  osobista instalowana z APK.
