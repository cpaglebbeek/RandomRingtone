# ****RANDOM_RINGTONE****

---

## Project Identiteit
- **Doel:** Android app die automatisch je ringtone wisselt op basis van Spotify playlist(s) — op schema of willekeurig
- **Scope:** Spotify playlist uitlezen, preview MP3's downloaden (30 sec snippets), als system ringtone instellen, schema/willekeurig wisselen. Wel: ringtone management, Spotify integratie. Niet: muziek afspelen, Spotify streaming.
- **Type:** [x] Standalone  [ ] Onderdeel van ecosysteem
- **Tech Stack:** Kotlin, Jetpack Compose, Gradle, OkHttp3, Room, WorkManager, Spotify Web API (OAuth 2.0 PKCE)
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

## Architectuur

```
RandomRingtone/
├── CLAUDE.md                    # Dit bestand
├── version.json                 # Versie metadata
├── app/
│   └── src/main/
│       ├── java/nl/icthorse/randomringtone/
│       │   ├── MainActivity.kt          # Entry point + navigation
│       │   ├── ui/
│       │   │   ├── screens/
│       │   │   │   ├── PlaylistScreen.kt    # Spotify playlist selectie
│       │   │   │   ├── ScheduleScreen.kt    # Schema instellingen
│       │   │   │   └── SettingsScreen.kt    # App instellingen + permissies
│       │   │   └── theme/
│       │   │       └── Theme.kt             # Material 3 + Dynamic Color
│       │   ├── auth/
│       │   │   └── SpotifyAuth.kt           # OAuth 2.0 PKCE flow
│       │   ├── data/
│       │   │   ├── SpotifyApi.kt            # Spotify Web API client
│       │   │   ├── RingtoneDb.kt            # Room database
│       │   │   └── RingtoneManager.kt       # MP3 download + ringtone instellen
│       │   └── worker/
│       │       └── RingtoneWorker.kt        # WorkManager scheduled swap
│       └── AndroidManifest.xml
├── build.gradle.kts
├── app/build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── .gitignore
```

---

## Technische Details

### Spotify Integratie
- **API:** Spotify Web API (https://api.spotify.com/v1/)
- **Auth:** OAuth 2.0 PKCE flow (geen server nodig, geen client secret)
- **Callback URI:** `randomringtone://spotify-callback`
- **Spotify Developer App:** Moet aangemaakt worden op developer.spotify.com
- **Preview audio:** `preview_url` veld in track objects — 30 sec, 128kbps MP3
- **Caveat:** Niet alle tracks hebben een preview_url; app slaat tracks zonder preview over

### Ringtone Management
- **Permissie:** `WRITE_SETTINGS` — vereist speciale user-grant via `Settings.ACTION_MANAGE_WRITE_SETTINGS`
- **API:** `RingtoneManager.setActualDefaultRingtoneUri()` met `TYPE_RINGTONE`
- **Storage:** MP3's opgeslagen in app-interne storage (`filesDir/ringtones/`)
- **ContentProvider:** MP3 moet via `MediaStore` of `FileProvider` beschikbaar zijn voor het systeem

### Schema & Willekeurig
- **WorkManager:** `PeriodicWorkRequest` voor schema (min. interval 15 min)
- **Opties:** bij elke oproep, elk uur, elke dag, handmatig
- **Samsung OneUI 8:** Battery optimization whitelisting nodig voor betrouwbare WorkManager executie

### Ringtone Duur
- Telefoon gaat standaard **20 seconden** over voordat voicemail inschakelt
- Spotify preview is 30 sec — ruim voldoende, geen trimming nodig
- Optioneel in toekomstige versie: instelbare preview-duur

### Target Platform
- **Primair:** Samsung, Android 16 (API 36), OneUI 8
- **compileSdk:** 35 (bump naar 36 wanneer SDK beschikbaar)
- **minSdk:** 26 (Android 8 — breed compatibel)

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
- **Gebruikte codenamen:** Jimi_Hendrix (v0.1.0)

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

### v0.1.0 "Jimi_Hendrix" — Scaffold (huidig)
- [x] Project skeleton: Gradle, Compose, Navigation, 3 tabs
- [ ] Spotify OAuth 2.0 PKCE login flow
- [ ] Playlist ophalen en tonen

### v0.2.0 — Ringtone Engine
- [ ] Preview MP3 download + opslag
- [ ] System ringtone instellen via RingtoneManager
- [ ] WRITE_SETTINGS permissie flow
- [ ] Samsung OneUI 8 compatibiliteit testen

### v0.3.0 — Schema & Willekeurig
- [ ] WorkManager scheduled ringtone swap
- [ ] Schema-opties: elk uur / elke dag / bij oproep / handmatig
- [ ] Room database voor playlist cache + schema
- [ ] Battery optimization whitelisting (Samsung)

### v1.0.0 — Productie
- [ ] Volledige flow werkend op Samsung Android 16
- [ ] Notificatie bij ringtone wissel
- [ ] Foutafhandeling (tracks zonder preview, netwerk offline)
- [ ] APK build + Google Drive upload
