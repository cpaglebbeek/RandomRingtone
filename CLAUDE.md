# ****RANDOM_RINGTONE****

---

## Project Identiteit
- **Doel:** Android app die automatisch je ringtone wisselt op basis van muziek — op schema of willekeurig
- **Scope:** Zoeken/browsen via Deezer API, preview MP3's downloaden (30 sec), als system ringtone instellen, schema/willekeurig wisselen. Wel: ringtone management, Deezer integratie. Niet: muziek afspelen, streaming. Spotify als toekomstige optie.
- **Type:** [x] Standalone  [ ] Onderdeel van ecosysteem
- **Tech Stack:** Kotlin, Jetpack Compose, Gradle, OkHttp3, Room, WorkManager, Deezer API (geen auth nodig)
- **GitHub:** `https://github.com/cpaglebbeek/RandomRingtone.git` (branch: `main`)
- **Lokaal pad:** `/Users/christian/Documents/Gemini_Projects/RandomRingtone`
- **Package:** `nl.icthorse.randomringtone`

---

## Context-Aware Orchestration
- **Location Independence:** Het maakt niet uit in welke map gewerkt wordt — leid uit de context af welk project actief is.
- **Routing:** Alle logica lokaal in dit project
- **Auto Git Sync:** Na elke wijziging automatisch `git commit` + `git push` naar de remote.
- **Expliciete Context:** Elke reactie begint met de projectnaam in hoofdletters: `****RANDOM_RINGTONE****`

---

## Functionele Features

### F1. Zoeken (Deezer)
- Zoek op artiest, nummer of playlist via Deezer API
- Resultaten tonen met artiest, titel en preview-beschikbaarheid
- Direct als ringtone instellen vanuit zoekresultaten
- Opslaan in benoemde playlist vanuit zoekresultaten
- **Status:** Werkend

### F2. Bibliotheek
- Overzicht van alle opgeslagen tracks, gegroepeerd per playlist
- Per track: direct als ringtone instellen of verwijderen
- Download-status icoon (lokaal aanwezig / alleen cloud)
- **Status:** Werkend

### F3. Toewijzingen (per contact / globaal)
- Toewijzing aanmaken: kies scope (globaal of specifiek contact)
- 4 kanalen: Telefoon, Notificatie, SMS, WhatsApp
- 2 modi: Vast (één track) of Random (uit playlist)
- Contact zoeken met auto-complete
- Toewijzingen tonen gegroepeerd per contact
- Verwijderen per toewijzing
- **Status:** Werkend (UI), per-contact ringtone voor Telefoon via ContactsContract, SMS/WhatsApp via NotificationListenerService

### F4. Schema (wisselfrequentie)
- Bij RANDOM modus: kies wanneer de ringtone wisselt
- Opties: Handmatig, Bij elke oproep, Elk uur, Elke dag, Elke week
- WorkManager voert periodieke taken uit op de achtergrond
- **Status:** Werkend (WorkManager ingepland), nog niet getest op Samsung batterij-optimalisatie

### F5. Permissiebeheer
- WRITE_SETTINGS: live status + directe link naar Android instellingen
- Notificatie-toegang: live status + directe link naar listener instellingen
- Hercheck bij terugkeer vanuit instellingen (lifecycle observer)
- **Status:** Werkend

### F6. Spotify Integratie (toekomst)
- OAuth 2.0 PKCE login flow
- Spotify playlists ophalen en tonen
- Preview_url downloaden (waar beschikbaar)
- Fallback naar Deezer voor tracks zonder preview
- **Status:** Geparkeerd (v0.4.0)

---

## Technische Features

### T1. Deezer API Client (`DeezerApi.kt`)
- Open API, geen auth/key nodig
- Endpoints: `/search`, `/playlist/{id}`, `/track/{id}`, `/artist/{id}/top`
- OkHttp3 + kotlinx.serialization voor JSON parsing
- Alle tracks hebben 30 sec MP3 preview
- **Gebruikt door:** F1 (Zoeken), F4 (Schema/Worker)

### T2. Ringtone Engine (`AppRingtoneManager.kt`)
- MP3 download via OkHttp3 → app-interne storage (`filesDir/ringtones/`)
- MediaStore registratie (ringtone of notification type)
- System ringtone instellen via `RingtoneManager.setActualDefaultRingtoneUri()`
- System notificatie instellen via `TYPE_NOTIFICATION`
- Per-contact ringtone via `ContactsContract.Contacts.CUSTOM_RINGTONE`
- Cache management (alle downloads wissen)
- **Gebruikt door:** F1 (direct instellen), F2 (bibliotheek instellen), F3 (toewijzingen), F4 (worker)

### T3. Room Database (`RingtoneDb.kt`)
- **Tabel `assignments`:** contactUri, contactName, channel, mode, schedule, fixedTrackId, playlistName
- **Tabel `saved_tracks`:** deezerTrackId, title, artist, previewUrl, localPath, playlistName
- Type converters voor Channel, Mode, Schedule enums
- DB versie 2, destructive migration
- **Gebruikt door:** F2 (bibliotheek), F3 (toewijzingen), F4 (worker), T5 (NotificationService)

### T4. WorkManager (`RingtoneWorker.kt`)
- 3 periodieke workers: hourly, daily, weekly
- Per worker: zoek RANDOM assignments met matching schedule → pick random track → download → set ringtone
- Constraints: netwerk vereist
- Samsung: battery optimization whitelisting nodig
- **Gebruikt door:** F4 (schema)

### T5. NotificationListenerService (`NotificationService.kt`)
- Onderschept notificaties van WhatsApp en Samsung/Google Messages
- Extraheert contactnaam uit notificatie-titel
- Zoekt matching assignment: eerst per-contact, dan globaal fallback
- Speelt custom geluid af via MediaPlayer
- Ondersteunt zowel FIXED als RANDOM modus
- **Gebruikt door:** F3 (SMS/WhatsApp kanalen)

### T6. Contacten Integratie (`ContactsRepository.kt`)
- Leest contacten via ContactsContract (alleen met telefoonnummer)
- Zoekfunctie op naam
- Per-contact ringtone instellen via CUSTOM_RINGTONE
- Per-contact ringtone verwijderen (terug naar standaard)
- **Gebruikt door:** F3 (contact selectie + per-contact telefoon ringtone)

### T7. UI Framework
- Jetpack Compose + Material 3 + Dynamic Color (Samsung OneUI compatible)
- 4 tabs: Zoeken / Bibliotheek / Toewijzingen / Instellingen
- Navigation Compose met state preservation
- Snackbar feedback bij acties
- **Target:** Samsung, Android 16 (API 36), OneUI 8
- **compileSdk:** 35, **minSdk:** 26

---

## Afhankelijkheden

### Externe afhankelijkheden

```
Deezer API (api.deezer.com)
  └── Geen auth nodig, volledig open
  └── Rate limit: niet gedocumenteerd, in praktijk ruim voldoende
  └── Risico: API kan wijzigen/verdwijnen — geen SLA

Android System APIs
  ├── RingtoneManager — system ringtone instellen
  ├── MediaStore — audio registreren als ringtone/notification
  ├── ContactsContract — contacten lezen + per-contact ringtone
  ├── NotificationListenerService — SMS/WhatsApp interceptie
  ├── WorkManager — periodieke background tasks
  └── Settings.System — WRITE_SETTINGS permissie check
```

### Interne afhankelijkheden (feature → technisch)

```
F1 Zoeken ──────────→ T1 DeezerApi
    │                  T2 RingtoneEngine (direct instellen)
    └────────────────→ T3 Room (opslaan in playlist)

F2 Bibliotheek ─────→ T3 Room (tracks lezen/verwijderen)
    └────────────────→ T2 RingtoneEngine (instellen vanuit bibliotheek)

F3 Toewijzingen ────→ T3 Room (assignments CRUD)
    ├────────────────→ T6 Contacten (contact selectie)
    ├── Telefoon ────→ T2 RingtoneEngine + T6 ContactsContract
    ├── Notificatie ─→ T2 RingtoneEngine (TYPE_NOTIFICATION)
    ├── SMS ─────────→ T5 NotificationService
    └── WhatsApp ────→ T5 NotificationService

F4 Schema ──────────→ T4 WorkManager
    ├────────────────→ T3 Room (assignments + tracks lezen)
    ├────────────────→ T1 DeezerApi (on-the-fly download als lokaal ontbreekt)
    └────────────────→ T2 RingtoneEngine (ringtone instellen)

F5 Permissies ──────→ Android Settings intents (geen eigen technische component)
```

### Permissie-afhankelijkheden

| Permissie | Nodig voor | Type |
|-----------|-----------|------|
| `INTERNET` | T1 (Deezer API), T2 (MP3 download) | Normaal |
| `WRITE_SETTINGS` | T2 (system ringtone instellen) | Speciaal (user-grant) |
| `READ_CONTACTS` | T6 (contacten lezen) | Runtime |
| `WRITE_CONTACTS` | T6 (per-contact ringtone) | Runtime |
| `READ_MEDIA_AUDIO` | T2 (MediaStore audio) | Runtime (Android 13+) |
| `POST_NOTIFICATIONS` | Notificaties bij ringtone wissel | Runtime (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | T4 (WorkManager na reboot) | Normaal |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | T5 (SMS/WhatsApp interceptie) | Speciaal (user-grant) |

---

## Overlappingen

### Functionele overlappingen

| Overlap | Componenten | Toelichting |
|---------|------------|-------------|
| **Ringtone instellen** | F1 (Zoeken), F2 (Bibliotheek), F3 (Toewijzingen), F4 (Schema) | Alle vier de features kunnen een ringtone instellen — allen via T2 RingtoneEngine. F1/F2 = handmatig direct, F3 = via assignment lookup, F4 = via WorkManager |
| **Track opslaan** | F1 (→ playlist), F2 (lezen) | F1 schrijft tracks naar Room, F2 leest ze. Zelfde tabel `saved_tracks` |
| **Contact selectie** | F3 (toewijzing aanmaken), T5 (notificatie matching) | F3 selecteert contact via ContactsRepository, T5 matcht op contactnaam uit notificatie. **Risico:** naamverschil (bijv. "Mama" in telefoon vs "Ma" in WhatsApp) |
| **Modus-logica (FIXED/RANDOM)** | F3 (UI keuze), T4 (Worker uitvoering), T5 (NotificationService) | Zelfde Mode enum, maar de resolutie-logica (welke track?) is gedupliceerd in T4 en T5. **Refactor-kandidaat:** gedeelde `TrackResolver` |

### Technische overlappingen

| Overlap | Componenten | Toelichting |
|---------|------------|-------------|
| **MP3 download** | T2 (RingtoneEngine), T4 (Worker), T5 (NotificationService) | Alle drie downloaden MP3's. T4 en T5 gebruiken T2, maar hebben ook eigen download-logica voor on-the-fly resolve. **Refactor-kandidaat:** centraliseer in T2 |
| **Track → File resolve** | T4 (RingtoneWorker.doWork), T5 (NotificationService.resolveTrackFile) | Identieke logica: saved track → check localPath → download indien nodig. **Refactor-kandidaat:** extract naar gedeelde `TrackResolver` |
| **MediaStore registratie** | T2 (addToMediaStore, addToMediaStoreNotification) | Twee methoden die bijna identiek zijn (verschil: IS_RINGTONE vs IS_NOTIFICATION). **Refactor-kandidaat:** één methode met parameter |
| **OkHttp client** | T1 (DeezerApi), T2 (RingtoneEngine) | Twee aparte OkHttpClient instances. **Refactor-kandidaat:** gedeelde singleton |

### Samsung/OneUI specifieke overlappingen

| Aandachtspunt | Componenten | Toelichting |
|---------------|------------|-------------|
| **Batterij-optimalisatie** | T4 (WorkManager), T5 (NotificationListenerService) | Beide worden door Samsung agressief gekilld. Beide vereisen battery optimization whitelisting. **Eén permissie-flow** in F5 kan beide afdekken |
| **Restricted settings** | T5 (NotificationListener) | Samsung OneUI kan NotificationListenerService blokkeren achter "Restricted settings". Mogelijk ADB commando nodig (zie AndroidCallManagement ervaring met targetSdk 36→35) |

---

## Architectuur

```
RandomRingtone/
├── CLAUDE.md                        # Dit bestand
├── version.json                     # Versie metadata
├── app/
│   └── src/main/
│       ├── java/nl/icthorse/randomringtone/
│       │   ├── MainActivity.kt              # Entry point + 4-tab navigation
│       │   ├── ui/
│       │   │   ├── screens/
│       │   │   │   ├── PlaylistScreen.kt    # F1: Deezer zoeken + instellen/opslaan
│       │   │   │   ├── LibraryScreen.kt     # F2: Opgeslagen tracks per playlist
│       │   │   │   ├── AssignmentScreen.kt  # F3+F4: Toewijzingen + schema
│       │   │   │   └── SettingsScreen.kt    # F5: Permissies + app info
│       │   │   └── theme/
│       │   │       └── Theme.kt             # T7: Material 3 + Dynamic Color
│       │   ├── data/
│       │   │   ├── DeezerApi.kt             # T1: Deezer API client
│       │   │   ├── RingtoneDb.kt            # T3: Room database + entities + DAO's
│       │   │   ├── RingtoneManager.kt       # T2: MP3 download + ringtone engine
│       │   │   └── ContactsRepository.kt    # T6: Contacten lezen + ringtone set
│       │   ├── service/
│       │   │   └── NotificationService.kt   # T5: SMS/WhatsApp interceptie
│       │   ├── worker/
│       │   │   └── RingtoneWorker.kt        # T4: Periodieke ringtone swap
│       │   └── auth/                        # (toekomst: Spotify OAuth PKCE)
│       ├── res/
│       │   ├── drawable/ic_launcher.xml     # App icon (muzieknoot vector)
│       │   └── values/
│       │       ├── strings.xml
│       │       └── themes.xml
│       └── AndroidManifest.xml
├── build.gradle.kts
├── app/build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── .gitignore
```

---

## Feature & Bugfix Protocol (Color-Coded)

**Nieuwe Feature:**
- **Groen:** Minor (code only, geen design/arch impact) → versie +0.0.1
- **Oranje:** Design impact (functioneel/technisch), architectuur stabiel → versie +0.1.0
- **Rood:** Major (redesign, architectonische verschuiving) → versie +1.0.0

**Bugfix:**
- **Groen:** Snel herstel (fysiek niveau)
- **Geel:** Out-of-physical-box (logische architectuur van de oplossing)
- **Rood:** Out-of-the-box (conceptueel redesign + Security Audit)
- **Loop:** Debug-loop — probeer een compleet nieuwe invalshoek

**Root Cause Analysis (verplicht bij elke bugfix):**
Benoem de oorzaak op drie niveaus: **Functioneel**, **Technisch**, **Architectonisch**.

---

## WhatIf Protocol (VERPLICHT, ALTIJD, VOOR ELKE ACTIE)

Voordat er code geschreven, bestanden aangemaakt, of builds gestart worden — ALTIJD eerst:

1. **Begrip terugkoppelen:** Wat begrijp je functioneel en technisch van de vraag? Welke aannames maak je?
2. **Plan voorleggen:** Wat ga je doen? Welke bestanden, welke architectuur, welke keuzes?
3. **Impactanalyse (-WhatIf):** Wat verandert er functioneel en technisch? Risico's? Bijwerkingen? Raakt dit andere projecten?
4. **Akkoord vragen:** Pas NA akkoord beginnen met bouwen/wijzigen.

**Uitzondering:** Triviale acties (typo fix, enkele regel op expliciete instructie) → minimaal benoemen wat je doet.

---

## Build Mandate
- **WhatIf bij builds:** Geef vóór elke build een stap-voor-stap analyse. Vraag daarna om akkoord.
- **Change Detection:** Controleer `git status` vóór elke build. Geen wijzigingen → meld dit, vraag of force build gewenst is.
- **Scope:** `build` slaat ALTIJD op het actieve project — nooit impliciet andere projecten meenemen.
- **Android:** APK naamgeving `RandomRingtone-v[Version]-[Codename]-[BuildType].apk` → kopieer naar `/Users/christian/Downloads` na succesvolle build. `JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

---

## Versioning Mandate
- Elke functionele of technische wijziging → versienummer verhogen **vóór** build of sync.
- Bestanden bijwerken: `version.json` en `app/build.gradle.kts` (versionName + versionCode).

### Semantische versioning
| Impact | Versie-increment | Kleurcode |
|--------|-----------------|-----------|
| Minor (code only) | +0.0.1 | Groen |
| Design/functioneel | +0.1.0 | Oranje |
| Architectonisch/Major | +1.0.0 | Rood |

### Thematische Codenamen
- **Thema:** Iconische muzikanten
- Elke build krijgt een **unieke** codenaam gebaseerd op het gekozen thema.
- Uniqueness check: nooit een naam of versie hergebruiken.
- **Gebruikte codenamen:** Jimi_Hendrix (v0.1.0), Freddie_Mercury (v0.2.0)

---

## Vastleggingsprotocol (Impliciet → Expliciet)
- Alles wat gevraagd wordt of impliciet overeengekomen is wordt ALTIJD fysiek vastgelegd.
- **Vóór vastleggen:** expliciet benoemen wát er vastgelegd wordt en wáár, daarna akkoord vragen.
- Na vastleggen: `git commit` + `git push` voor het gewijzigde project.

## "Over en uit" Protocol
Wanneer de gebruiker "over en uit" zegt:
1. Sla alle project-metadata en sessie-context op in relevante bestanden
2. `git add` + `git commit` met beschrijvende boodschap
3. `git push` naar GitHub remote

---

## Roadmap

### v0.1.0 "Jimi_Hendrix" — Scaffold + Deezer zoeken ✅
- [x] Project skeleton: Gradle, Compose, Navigation
- [x] Deezer API client (zoeken, playlist, track, artiest)
- [x] Zoekscherm met resultaten
- [x] RingtoneManager: MP3 download + MediaStore + system ringtone
- [x] WRITE_SETTINGS permissie flow
- [x] Versie zichtbaar in APK naam + app

### v0.2.0 "Freddie_Mercury" — Per-contact + 4 kanalen + playlists ✅
- [x] Room database: assignments + saved tracks
- [x] 4 kanalen: Telefoon, Notificatie, SMS, WhatsApp
- [x] Per-contact en globale toewijzingen
- [x] 2 modi: Vast en Random uit playlist
- [x] Playlist systeem: opslaan vanuit zoekresultaten
- [x] Bibliotheek scherm: overzicht + direct instellen
- [x] NotificationListenerService voor SMS/WhatsApp
- [x] WorkManager schema: handmatig/oproep/uur/dag/week
- [x] Permissiescherm: WRITE_SETTINGS + NotificationListener

### v0.3.0 — Stabilisatie + Samsung testen
- [ ] Samsung OneUI 8 compatibiliteit testen
- [ ] Battery optimization whitelisting (Samsung)
- [ ] Refactor: gedeelde TrackResolver (T4/T5 overlapping)
- [ ] Refactor: gedeelde OkHttpClient singleton
- [ ] Refactor: MediaStore registratie unificatie
- [ ] Notificatie bij ringtone wissel
- [ ] Foutafhandeling: netwerk offline, download failures, tracks zonder preview

### v0.4.0 — Spotify (optioneel)
- [ ] Spotify OAuth 2.0 PKCE login flow
- [ ] Spotify playlists ophalen en tonen
- [ ] Spotify preview_url downloaden (waar beschikbaar)
- [ ] Fallback naar Deezer voor tracks zonder preview

### v1.0.0 — Productie
- [ ] Volledige flow werkend + getest op Samsung Android 16
- [ ] APK build + Google Drive upload
