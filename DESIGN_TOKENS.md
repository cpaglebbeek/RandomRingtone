# Design Tokens — RandomRingtone

Versie: 1.0 | Datum: 2026-05-03

---

## Thema Systeem

RandomRingtone gebruikt **Material 3 Dynamic Color** zonder eigen kleurenpalet. Alle kleuren worden bepaald door het systeem.

### Kleurstrategie

| Conditie | Bron | Toepassing |
|----------|------|------------|
| Android 12+ (API 31+) | `dynamicDarkColorScheme()` / `dynamicLightColorScheme()` | Kleuren afgeleid van wallpaper |
| Android 8-11 | `darkColorScheme()` / `lightColorScheme()` | Material 3 standaard paars/blauw |
| Dark mode | `isSystemInDarkTheme()` | Automatisch via systeem |

### Implementatie (`Theme.kt`)

```kotlin
val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        if (darkTheme) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    }
    darkTheme -> darkColorScheme()
    else -> lightColorScheme()
}
```

### Fallback Kleuren (Material 3 standaard, Android < 12)

| Token | Light | Dark | Gebruik |
|-------|-------|------|---------|
| `primary` | #6750A4 | #D0BCFF | Knoppen, FAB, actieve tabs |
| `onPrimary` | #FFFFFF | #381E72 | Tekst op primary |
| `secondary` | #625B71 | #CCC2DC | Secundaire acties |
| `surface` | #FFFBFE | #1C1B1F | Achtergronden |
| `error` | #B3261E | #F2B8B5 | Foutmeldingen |

**Let op:** Deze kleuren worden alleen gebruikt als Dynamic Color niet beschikbaar is. Op moderne Samsung/Pixel devices zijn de kleuren altijd wallpaper-gebaseerd.

---

## App Icon

| Eigenschap | Waarde |
|------------|--------|
| Type | Vector drawable (XML) |
| Vorm | Cirkel met muzieknoot |
| Achtergrond | `#6750A4` (Material 3 primary) |
| Voorgrond | `#FFFFFF` |
| Bestand | `res/drawable/ic_launcher.xml` |
| Mipmap | Auto-generated in mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi |

---

## Typografie

Geen eigen typografie gedefinieerd. Material 3 standaard typografie wordt gebruikt:

| Style | Gebruik in app |
|-------|---------------|
| `headlineMedium` | Schermtitels |
| `titleMedium` | Sectie headers, lijst items |
| `bodyLarge` | Primaire tekst, labels |
| `bodyMedium` | Secundaire tekst, metadata |
| `labelLarge` | Knoppen |
| `labelSmall` | Badges, subtekst |

---

## Spacing & Layout

Compose-only project — geen XML layouts. Alle spacing is inline in Composable functies.

### Gebruikte patronen

| Patroon | Waarde | Context |
|---------|--------|---------|
| Padding scherm | `16.dp` | Buitenrand van elke screen |
| Padding kaart | `12.dp` - `16.dp` | Interne padding van Card composables |
| Spacing lijst items | `8.dp` | Verticale ruimte tussen items |
| Spacing secties | `16.dp` - `24.dp` | Tussen secties binnen een scherm |
| Icoon grootte | `24.dp` (standaard) | Material Icons |
| FAB | Material 3 standaard | Floating Action Button |

**Opmerking:** Spacing waarden zijn niet gecentraliseerd in constanten — ze staan inline per screen. Dit is acceptabel gezien de beperkte complexiteit van de UI.

---

## Navigatie

7 tabs in `MainActivity.kt` via Navigation Compose:

| Tab | Icon | Label | Route |
|-----|------|-------|-------|
| 1 | `MusicNote` | Deezer | `deezer` |
| 2 | `Language` | Spotify | `spotify` |
| 3 | `LibraryMusic` | Bibliotheek | `library` |
| 4 | `QueueMusic` | Playlists | `playlists` |
| 5 | `Dashboard` | Overzicht | `overview` |
| 6 | `Backup` | Backup | `backup` |
| 7 | `Settings` | Instellingen | `settings` |

### Navigatie vanuit screens

| Van | Naar | Trigger |
|-----|------|---------|
| Deezer/Spotify/YouTube | Editor | Track downloaden → "Openen in editor" |
| Bibliotheek | Editor | Track selecteren → bewerken |
| Instellingen | Systeem Settings | Permissie-links |
| Instellingen | Update dialog | Nieuwe versie beschikbaar |

---

## UI Componenten (Compose)

### Hergebruikte patronen per screen

| Component | Beschrijving | Gebruikt in |
|-----------|-------------|-------------|
| `LazyColumn` | Scrollbare lijst | Bibliotheek, Playlists, Overzicht, Backup |
| `Card` | Material 3 card | Track items, playlist items, overzicht kaarten |
| `AlertDialog` | Bevestigingsdialogen | Verwijderen, overschrijven, playlist kiezen |
| `Snackbar` | Feedback berichten | Na ringtone instellen, download, backup |
| `WebView` | Embedded browser | Spotify, YouTube tabs |
| `Canvas` | Waveform tekening | Editor screen |
| `FloatingActionButton` | Primaire actie | Download MP3 (Spotify/YouTube) |
| `DropdownMenu` | Selectie menu | Converter keuze, schema opties |
| `Switch` | Toggle | Playlist actief, debug logging, direct API |
| `Slider` | Bereik selectie | Audio trim handles, fade duur |
| `LinearProgressIndicator` | Voortgang | Download, update |
| `CircularProgressIndicator` | Laden | API calls, scan |

---

## Kleurcodering — Documentatie & Processen

### Bug Severity (BUGLIST.md)

| Kleur | Betekenis | Impact |
|-------|-----------|--------|
| **Groen** | Quick fix | Fysiek niveau, code-only |
| **Geel** | Logisch | Out-of-physical-box, architectuur-logica |
| **Oranje** | Design impact | Functioneel/technisch redesign |
| **Rood** | Critical | Conceptueel redesign + Security Audit |
| **Loop** | Debug-loop | Compleet nieuwe invalshoek nodig |

### Feature Impact (CLAUDE.md, versioning)

| Kleur | Versie-impact | Scope |
|-------|--------------|-------|
| **Groen** | +0.0.1 | Code-only, geen design/architectuur impact |
| **Oranje** | +0.1.0 | Design/functioneel impact, architectuur stabiel |
| **Rood** | +1.0.0 | Major architectuurwijziging, redesign |

### Build Status (build.gradle.kts, build.timestamp)

| Marker | Betekenis | Zichtbaar voor gebruiker |
|--------|-----------|--------------------------|
| `DEBUG` | Test build, niet vrijgegeven | Alleen met "Old build" schuif aan |
| `STABLE` | Vrijgegeven, productie-klaar | Altijd zichtbaar bij update check |
| `BUG` | Bekende bug, niet aanbieden | Gefilterd uit update check |
| `UPGRADE` | Upgrade path, niet direct installeerbaar | Gefilterd uit update check |
| *(leeg)* | Standaard release | Altijd zichtbaar |

---

## Bestandsnaam Patronen

### Audio bestanden

| Patroon | Bron | Voorbeeld |
|---------|------|-----------|
| `download_{trackId}.mp3` | Deezer API download | `download_123456.mp3` |
| `ringtone_{trackId}.mp3` | Getrimde ringtone (globaal) | `ringtone_123456.mp3` |
| `ringtone_{trackId}_{playlist}.mp3` | Getrimde ringtone (in playlist) | `ringtone_123456_Rock.mp3` |
| `spotify_mp3_{track}-{artiest}.mp3` | Spotify converter download | `spotify_mp3_Bohemian_Rhapsody-Queen.mp3` |
| `youtube_mp3_{title}.mp3` | YouTube (Y2Mate) download | `youtube_mp3_Never_Gonna_Give_You_Up.mp3` |

### APK bestanden

| Patroon | Voorbeeld |
|---------|-----------|
| `RandomRingtone-v{version}-{codename}-{releaseName}-{buildType}.apk` | `RandomRingtone-v1.9.15-Michael_Jackson-Got_To_Be_There-release.apk` |

---

## String Resources

### Geexternaliseerd (`res/values/strings.xml`)

| Key | Waarde |
|-----|--------|
| `app_name` | "RandomRingtone" |

### Hardcoded in Compose (nog niet geexternaliseerd)

Alle UI strings staan inline in Kotlin @Composable functies. Voorbeelden:

- Tab labels: "Deezer", "Spotify", "Bibliotheek", "Playlists", "Overzicht", "Backup", "Instellingen"
- Knoppen: "Download MP3", "Openen in editor", "Opslaan", "Verwijderen"
- Feedback: "Ringtone ingesteld", "Download gestart", "Backup voltooid"
- Labels: "Playlist", "Actief", "Schema", "Kanaal", "Contact"

**Status:** Geen i18n/lokalisatie. App is volledig Nederlandstalig via hardcoded strings.
