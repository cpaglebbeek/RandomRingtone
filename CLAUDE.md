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
- 5 tabs: Zoeken / Bibliotheek / Playlists / Overzicht / Instellingen
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
│       │   ├── MainActivity.kt              # Entry point + 5-tab navigation
│       │   ├── ui/
│       │   │   ├── screens/
│       │   │   │   ├── PlaylistScreen.kt    # F1: Deezer zoeken + instellen/opslaan
│       │   │   │   ├── LibraryScreen.kt     # F2: Opgeslagen tracks per playlist
│       │   │   │   ├── PlaylistManagerScreen.kt # F3+F4: Playlists + triggers + schema
│       │   │   │   ├── OverviewScreen.kt    # F6: Actieve instellingen + conflicten
│       │   │   │   └── SettingsScreen.kt    # F5: Permissies + opslag + Spotify converter
│       │   │   └── theme/
│       │   │       └── Theme.kt             # T7: Material 3 + Dynamic Color
│       │   ├── data/
│       │   │   ├── DeezerApi.kt             # T1: Deezer API client
│       │   │   ├── RingtoneDb.kt            # T3: Room database + entities + DAO's
│       │   │   ├── RingtoneManager.kt       # T2: MP3 download + ringtone engine
│       │   │   ├── ContactsRepository.kt    # T6: Contacten lezen + ringtone set
│       │   │   ├── TrackResolver.kt         # T8: Gedeelde track→file resolver
│       │   │   ├── ConflictResolver.kt      # T9: Conflictdetectie + hiërarchie
│       │   │   └── StorageManager.kt        # T10: DataStore opslag + Spotify converter
│       │   ├── service/
│       │   │   └── NotificationService.kt   # T5: SMS/WhatsApp interceptie (PlaylistDao)
│       │   ├── worker/
│       │   │   └── RingtoneWorker.kt        # T4: Periodieke ringtone swap (PlaylistDao)
│       │   ├── receiver/
│       │   │   └── CallStateReceiver.kt     # T11: EVERY_CALL BroadcastReceiver
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
- **Android:** APK naamgeving `RandomRingtone-v[Version]-[Codename]-[ReleaseName]-[BuildType].apk` → kopieer naar `/Users/christian/Downloads` na succesvolle build. `JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- **Auto Git na build:** Na elke succesvolle build ALTIJD automatisch `git add` + `git commit` + `git push` uitvoeren. Geen build zonder git sync.

---

## Versioning Mandate
- Elke functionele of technische wijziging → versienummer verhogen **vóór** build of sync.
- Elke bugfix → minimaal +0.0.1.
- Bestanden bijwerken: `version.json` en `app/build.gradle.kts` (versionName + versionCode + releaseName).

### Drie identifiers per release
| Identifier | Scope | Voorbeeld | Uniek per |
|------------|-------|-----------|-----------|
| Versienummer | Semantisch | 0.6.10 | Release |
| Buildnummer (versionCode) | Incrementeel | 16 | Release |
| Buildnaam (codenaam) | Per major versie | Janis_Joplin | Major versie |
| Releasenaam | Per individuele release | Try | Release |

**Regel:** Buildnummer + releasenaam = unieke combinatie = equivalent aan versienummer.

### Semantische versioning
| Impact | Versie-increment | Kleurcode |
|--------|-----------------|-----------|
| Minor (code only) | +0.0.1 | Groen |
| Design/functioneel | +0.1.0 | Oranje |
| Architectonisch/Major | +1.0.0 | Rood |

### Thematische Codenamen (buildnamen)
- **Thema:** Iconische muzikanten
- Eén codenaam per major versie (bijv. v0.6.x = Janis_Joplin).
- Uniqueness check: nooit een naam of versie hergebruiken.
- **Nieuwe buildnaam protocol:** Bij functionele wijzigingen (+0.1.0 of hoger) altijd overleggen met gebruiker of een nieuwe artiest als buildnaam nodig is. Bij bugfixes (+0.0.1) blijft de huidige artiest.
- **Grootte-inschatting:** Bij elke build een inschatting geven (klein/middel/groot) als input voor de buildnaam-beslissing.
- **Gebruikte codenamen:** Jimi_Hendrix (v0.1.0), Freddie_Mercury (v0.2.0), David_Bowie (v0.3.0), Amy_Winehouse (v0.4.0), Kurt_Cobain (v0.5.0), Janis_Joplin (v0.6.0), Prince (v0.7.0)

### Releasenamen
- **Thema:** Nummer van de artiest die als buildnaam dient
- Elke individuele release krijgt een unieke releasenaam (bijv. v0.6.x = nummers van Janis Joplin).
- **Uitputting:** Als alle bekende nummers van een artiest op zijn → nieuwe artiest kiezen als buildnaam (ook als de major versie niet verandert). De volgende releases krijgen dan nummers van de nieuwe artiest.
- Zie `RELEASES.md` voor volledige lijst.

### Buglijst & Release Info
- **BUGLIST.md:** Per bug: beschrijving, kleur, gevonden in, gefixt in, status (OPEN/FIXED)
- **RELEASES.md:** Per release: build, versie, buildnaam, releasenaam, datum, stabiel (ja/nee)

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

### v0.3.0 "David_Bowie" — Waveform editor + opslag ✅
- [x] AudioDecoder: MP3 → PCM amplitudes via MediaCodec
- [x] AudioTrimmer: lossless MP3 trim via MediaExtractor + MediaMuxer
- [x] AudioPlayer: preview afspelen van selectie
- [x] EditorScreen: waveform + drag handles + sliders + preview + trim + opslaan
- [x] StorageManager: configureerbare download- en ringtone-locaties (DataStore)
- [x] Bibliotheek herwerkt: Downloads tab (trim) + Ringtones tab (instellen/playlist/delete)
- [x] SettingsScreen: opslag sectie met paden, schijfgebruik, wissen per locatie

### v0.4.0 "Amy_Winehouse" — Playlist tab + uitgebreid schema ✅
- [x] Playlist entity: naam, trigger, modus, schema, contact-koppeling, actief toggle
- [x] PlaylistTrack koppeltabel met sortOrder en CASCADE delete
- [x] Schedule uitgebreid: 1/2/4/8/12 uur, dagelijks, wekelijks
- [x] PlaylistManagerScreen: aanmaken, bewerken, tracks beheren, delete
- [x] Navigatie: Zoeken / Bibliotheek / Playlists / Instellingen
- [x] CONFLICTS.md: volledige afhankelijkheids- en conflictanalyse (7 conflicten, 10 refactoring-acties)

### v0.5.0 "Kurt_Cobain" — Conflictresolutie + Overview tab + Spotify config ✅
- [x] **R8: TrackResolver** — gedeelde track→file resolver (dedup T4/T5)
- [x] **R1: ConflictResolver** — centrale conflictdetectie + hiërarchie-afdwinging
- [x] **R3-R4: Migreer NotificationService + RingtoneWorker naar PlaylistDao**
- [x] **R5: EVERY_CALL** — CallStateReceiver BroadcastReceiver + lastPlayedTrackId dedup
- [x] **R6: Enforce 1-actief-per-kanaal+scope** — auto-deactiveer conflicterende playlists
- [x] **R2: Overview tab** — 5e tab, per kanaal+contact actieve instellingen + conflicten
- [x] **R7: Legacy verwijderd** — AssignmentScreen + RingtoneAssignment + AssignmentDao verwijderd
- [x] **Spotify Converter config** — 9 converters in Instellingen dropdown (DataStore)
- [x] DB versie 4 (destructive migration, assignments tabel verwijderd)
- [x] READ_PHONE_STATE permissie + CallStateReceiver in manifest
- [x] Navigatie: 5 tabs (Zoeken / Bibliotheek / Playlists / Overzicht / Instellingen)

### v0.6.0 "Janis_Joplin" — Spotify Web + converter ✅
- [x] SpotifyScreen: WebView met open.spotify.com + browse/zoekfunctie
- [x] Track URL detectie (/track/) → "Download MP3" FAB
- [x] Automatisch clipboard + navigatie naar gekozen converter-site
- [x] DownloadManager interceptie → editor of bibliotheek
- [x] Twee-fasen UI: BROWSING (Spotify) ↔ CONVERTING (converter)
- [x] 6 tabs: Deezer / Spotify / Bibliotheek / Playlists / Overzicht / Instellingen
- [x] Playlist-dialoog verbeterd: bestaande playlists selecteerbaar + playlist_tracks koppeling

### v0.7.0 "Prince" — Backup/Restore + Locatie picker + Data persistentie ✅
- [x] **Backup/Restore tab:** SAF (`ACTION_OPEN_DOCUMENT_TREE`) → cloud-map kiezen (GDrive/Dropbox/OneDrive)
- [x] BackupManager: tracks + ringtones + database export (JSON) → SAF URI
- [x] Restore: lees van SAF URI → importeer bestanden + database
- [x] Persistent SAF URI via `takePersistableUriPermission()`
- [x] **Locatie picker:** SAF directory picker i.p.v. tekstveld bij download/ringtone locatie
- [x] Bij wijziging locatie: dialoog kopiëren/verplaatsen/niets voor bestaande bestanden
- [x] StorageManager: `moveFilesTo()` + `copyFilesTo()` methoden
- [x] **Data persistentie:** destructive migration verwijderd, lokale auto-backup (filesDir/auto_backup/)
- [x] Auto-restore bij startup als DB leeg + backup beschikbaar
- [x] 7 tabs: Deezer / Spotify / Bibliotheek / Playlists / Overzicht / Backup / Instellingen
- [x] **Spotify download naamgeving:** `spotify_mp3_<track>-<artiest>.mp3` i.p.v. timestamp

### v0.8.0 — Stabilisatie + Samsung testen
- [ ] Samsung OneUI 8 compatibiliteit testen
- [ ] Battery optimization whitelisting
- [ ] Refactor: OkHttpClient singleton, MediaStore unificatie
- [ ] Pre-download bij playlist activering (offline support)
- [ ] Notificatie bij ringtone wissel
- [ ] Foutafhandeling: netwerk offline, download failures

### v1.0.0 — Productie
- [ ] Volledige flow werkend + getest op Samsung Android 16
- [ ] Alle conflicten opgelost, Overview tab toont correcte staat
- [ ] APK build + Google Drive upload

---

## Mogelijke Features (Geparkeerd)

| Feature | Beschrijving | Oorsprong | Impact |
|---------|-------------|-----------|--------|
| Synced Playlists (Spotify sync) | Playlist type LOCAL/SYNCED. SYNCED koppelt aan Spotify playlist via OAuth 2.0 PKCE. SpotifyAuthManager, SpotifyApiClient, PlaylistSyncWorker. DB migratie: Playlist + `type`/`spotifyPlaylistId`/`lastSyncedAt`, SavedTrack + `spotifyTrackId`. UI: keuze LOCAL/SYNCED → login → playlist picker. | Sessie 2026-03-30 | Oranje (+0.1.0) |
