# RandomRingtone

Android app die automatisch je ringtone wisselt op basis van muziek — op schema of willekeurig.

## Wat doet het

- Zoek en download muziek via Deezer API, Spotify (via converter) en YouTube
- Maak playlists, wijs ze toe aan kanalen (Telefoon, SMS, WhatsApp, Notificatie)
- Stel schema's in: bij elke oproep, elk uur, dagelijks, wekelijks
- Per-contact ringtones via ContactsContract
- Audio editor met waveform, trim en fade in/out
- Backup/restore naar cloud (SAF) of iCt Horse server
- In-app updates via icthorse.nl/RandomRing/Apk/

## Tech Stack

| Component | Technologie |
|-----------|-------------|
| Taal | Kotlin 2.1.0 |
| UI | Jetpack Compose + Material 3 + Dynamic Color |
| Database | Room (SQLite) v7, 3 tabellen, 3 migraties |
| Background | WorkManager + BroadcastReceiver + NotificationListenerService |
| Netwerk | OkHttp3 |
| Serialisatie | kotlinx.serialization |
| Build | Gradle 8.7.3, AGP, KSP |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |

## Bouwen

```bash
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# Release build (altijd, ook voor DEBUG status)
./gradlew assembleRelease

# APK locatie
ls app/build/outputs/apk/release/RandomRingtone-v*.apk
```

Signing config: `~/.android/randomringtone-release.jks`

## Project Structuur

```
RandomRingtone/
├── CLAUDE.md              # AI context + feature map + protocollen
├── CONVENTIONS.md         # Naamgeving, patronen, logging
├── DEPLOY.md              # Build → upload → release flow
├── DEPENDENCY_MAP.md      # Component afhankelijkheden
├── DESIGN_TOKENS.md       # Thema, kleuren, UI systeem
├── RELEASES.md            # Alle 126 builds
├── BUGLIST.md             # 70 bugs (alle FIXED)
├── CONFLICTS.md           # 7 conflicten + resoluties
├── FLOW.md                # Volledige flow analyse (1373 regels)
├── MARKETING.md           # Facebook post + kernboodschap
├── version.json           # Versie metadata (source of truth)
└── app/src/main/java/nl/icthorse/randomringtone/
    ├── MainActivity.kt
    ├── ui/screens/         # 8 Compose screens
    ├── ui/theme/           # Material 3 theme
    ├── data/               # API clients, managers, database
    ├── audio/              # Decoder, trimmer, player
    ├── service/            # NotificationListenerService
    ├── worker/             # WorkManager periodic tasks
    └── receiver/           # BroadcastReceiver (EVERY_CALL)
```

## Versioning

- **SemVer:** MAJOR.MINOR.PATCH (1.9.15)
- **Buildnummer:** Sequentieel (126)
- **Codename:** Iconische muzikant per major versie (Michael_Jackson)
- **Releasename:** Nummer van die artiest (Got_To_Be_There)
- **APK:** `RandomRingtone-v1.9.15-Michael_Jackson-Got_To_Be_There-release.apk`

Zie [RELEASES.md](RELEASES.md) voor volledige historie.

## Documentatie

| Document | Inhoud |
|----------|--------|
| [CLAUDE.md](CLAUDE.md) | Features (F1-F7), tech components (T1-T11), roadmap, protocollen |
| [CONVENTIONS.md](CONVENTIONS.md) | Naamgeving, package structuur, logging, error handling |
| [DEPLOY.md](DEPLOY.md) | Build, upload, build.timestamp, DEBUG/STABLE marker flow |
| [DEPENDENCY_MAP.md](DEPENDENCY_MAP.md) | Component relaties en afhankelijkheden |
| [DESIGN_TOKENS.md](DESIGN_TOKENS.md) | Thema systeem, kleuren, typografie, UI patronen |
| [FLOW.md](FLOW.md) | Volledige interactie- en systeemflow analyse |
| [CONFLICTS.md](CONFLICTS.md) | Conflictdetectie, prioriteitshierarchie |
| [BUGLIST.md](BUGLIST.md) | Alle bugs met kleurcodering en status |

## Licentie

Privaat project — niet voor distributie.

---

**Package:** `nl.icthorse.randomringtone`
**GitHub:** `cpaglebbeek/RandomRingtone`
**Huidige versie:** v1.9.15 (Build 126) "Michael_Jackson" / "Got_To_Be_There" — STABLE
