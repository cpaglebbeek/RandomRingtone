# Deploy — RandomRingtone

Versie: 1.0 | Datum: 2026-05-03

---

## Overzicht

```
Code wijzigen → Versie bumpen → Build → APK uploaden → build.timestamp updaten → Testen → Vrijgeven
```

---

## 1. Versie Bumpen (VERPLICHT vóór build)

### Bestanden bijwerken

**`version.json`** (root):
```json
{
  "version": "1.9.16",
  "versionCode": 127,
  "codename": "Michael_Jackson",
  "releaseName": "Nieuwe_Releasenaam",
  "last_updated": "2026-05-03"
}
```

**`app/build.gradle.kts`:**
```kotlin
val appCodename = "Michael_Jackson"
val appReleaseName = "Nieuwe_Releasenaam"

defaultConfig {
    versionCode = 127
    versionName = "1.9.16"
    buildConfigField("int", "BUILD_NUMBER", "127")
    buildConfigField("String", "BUILD_STATUS", "\"DEBUG\"")  // of "STABLE"
}
```

### Versie-increment Regels

| Wijziging | Increment | Voorbeeld |
|-----------|-----------|-----------|
| Bugfix | +0.0.1 | 1.9.15 → 1.9.16 |
| Nieuwe feature | +0.1.0 | 1.9.15 → 1.10.0 |
| Major redesign | +1.0.0 | 1.9.15 → 2.0.0 |

Buildnummer (versionCode) gaat **altijd** +1 omhoog, ongeacht het type wijziging.

---

## 2. Build

### Vereisten

```bash
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

### Commando

```bash
cd /Users/christian/Documents/Gemini_Projects/RandomRingtone
./gradlew assembleRelease
```

**Altijd `assembleRelease`** — ook als de build status DEBUG is. Release builds zijn ~1.9 MB (vs ~17 MB voor debug).

### APK Locatie

```
app/build/outputs/apk/release/RandomRingtone-v{version}-{codename}-{releaseName}-release.apk
```

### Na succesvolle build

```bash
# Kopieer naar Downloads
cp app/build/outputs/apk/release/RandomRingtone-v*.apk /Users/christian/Downloads/
```

---

## 3. Upload naar icthorse.nl

### APK uploaden

```bash
rsync -avz -e "ssh" \
  app/build/outputs/apk/release/RandomRingtone-v{version}-{codename}-{releaseName}-release.apk \
  icthorse:~/domains/icthorse.nl/public_html/RandomRing/Apk/
```

### build.timestamp bijwerken

Het bestand `build.timestamp` op de server bevat alle beschikbare versies. Formaat per regel:

```
{version}|{buildnummer}|{datum}|{apk_filename}|{marker}|{codename}|{releaseName}
```

Voorbeeld entry:
```
1.9.15|126|2026-04-12|RandomRingtone-v1.9.15-Michael_Jackson-Got_To_Be_There-release.apk|STABLE|Michael_Jackson|Got_To_Be_There
```

### Nieuwe entry toevoegen

```bash
# Download huidige build.timestamp
scp icthorse:~/domains/icthorse.nl/public_html/RandomRing/Apk/build.timestamp /tmp/

# Voeg nieuwe regel toe (standaard DEBUG)
echo "1.9.16|127|2026-05-03|RandomRingtone-v1.9.16-Michael_Jackson-Nieuwe_Releasenaam-release.apk|DEBUG|Michael_Jackson|Nieuwe_Releasenaam" >> /tmp/build.timestamp

# Upload bijgewerkte build.timestamp
rsync -avz -e "ssh" /tmp/build.timestamp icthorse:~/domains/icthorse.nl/public_html/RandomRing/Apk/
```

---

## 4. Markers

| Marker | Betekenis | Wie ziet het |
|--------|-----------|-------------|
| `DEBUG` | Test build, niet vrijgegeven | Alleen gebruikers met "Old build" schuif aan in Instellingen |
| `STABLE` | Vrijgegeven voor alle gebruikers | Iedereen via in-app update check |
| `BUG` | Bekende bug, niet installeren | Niemand (gefilterd door UpdateManager) |
| `UPGRADE` | Upgrade path build | Niemand (gefilterd door UpdateManager) |
| *(leeg)* | Standaard release | Iedereen |

### Standaard flow

1. Nieuwe build wordt **altijd** geupload met marker `DEBUG`
2. Na testen door ontwikkelaar: marker wijzigen naar `STABLE` of leeg
3. Bij ontdekking van bug: marker wijzigen naar `BUG`

### Marker wijzigen

```bash
# Download build.timestamp
scp icthorse:~/domains/icthorse.nl/public_html/RandomRing/Apk/build.timestamp /tmp/

# Bewerk de marker (5e veld) van de betreffende regel
# Van: ...|DEBUG|...
# Naar: ...|STABLE|...
# (of verwijder het marker veld voor een standaard release)

# Upload
rsync -avz -e "ssh" /tmp/build.timestamp icthorse:~/domains/icthorse.nl/public_html/RandomRing/Apk/
```

---

## 5. In-App Update Mechanisme

### Server-side

- **URL:** `https://icthorse.nl/RandomRing/Apk/build_info.php`
- **Response:** Inhoud van `build.timestamp` (plain text)
- **APK's:** `https://icthorse.nl/RandomRing/Apk/{filename}.apk`

### Client-side (UpdateManager.kt)

1. Fetch `build_info.php` → parse alle versies
2. Filter: verwijder `DEBUG`, `BUG`, `UPGRADE` markers
3. Vergelijk hoogste remote build met `BuildConfig.BUILD_NUMBER`
4. Als nieuwer: toon update dialoog
5. Download APK naar `cacheDir/updates/`
6. Installeer via `FileProvider` + `ACTION_VIEW` intent

### Debug builds zichtbaar maken

In de app: **Instellingen → "Old build" schuif** activeert `debug_build` DataStore key. Met deze schuif aan worden ook `DEBUG` versies getoond in de update lijst.

---

## 6. Git & Push

### Na elke build (VERPLICHT)

```bash
cd /Users/christian/Documents/Gemini_Projects/RandomRingtone

git add -A
git commit -m "v{version} (Build {nummer}) \"{releaseName}\" — {beschrijving}"
git push origin main
```

### Commit Message Conventie

```
v1.9.16 (Build 127) "Nieuwe_Releasenaam" — Korte beschrijving van wijziging
```

---

## 7. Stable Release

Wanneer een build als STABLE wordt gemarkeerd:

1. **Marker wijzigen** in `build.timestamp` (DEBUG → STABLE of leeg)
2. **BUILD_STATUS** in `build.gradle.kts` wijzigen naar `"STABLE"`
3. **APK kopiëren** naar `releases/` map in de repo (indien aanwezig)
4. **Commit + push** de statuswijziging

---

## 8. Volledige Checklist

```
[ ] Code wijziging klaar en getest
[ ] version.json bijgewerkt (version, versionCode, releaseName, last_updated)
[ ] build.gradle.kts bijgewerkt (versionName, versionCode, appReleaseName, BUILD_NUMBER, BUILD_STATUS)
[ ] RELEASES.md: nieuwe regel toegevoegd
[ ] BUGLIST.md: eventuele bugs bijgewerkt
[ ] ./gradlew assembleRelease succesvol
[ ] APK gekopieerd naar ~/Downloads
[ ] APK geupload naar icthorse.nl/RandomRing/Apk/
[ ] build.timestamp bijgewerkt met DEBUG marker
[ ] git commit + git push
[ ] App getest op device
[ ] (optioneel) Marker gewijzigd naar STABLE na succesvolle test
```
