# Conventions — RandomRingtone

Versie: 1.0 | Datum: 2026-05-03

---

## 1. Package Structuur

```
nl.icthorse.randomringtone/
├── MainActivity.kt              # Entry point + tab navigatie
├── ui/
│   ├── screens/                 # *Screen.kt — één per tab/functie
│   └── theme/                   # Theme.kt — Material 3 configuratie
├── data/                        # Alles dat data beheert of extern communiceert
│   ├── *Api.kt                  # Externe API clients
│   ├── *Client.kt               # HTTP clients voor specifieke services
│   ├── *Manager.kt              # Beheert een domein (opslag, updates, licenties, backup)
│   ├── *Repository.kt           # Data-abstractie over Android APIs
│   ├── *Resolver.kt             # Logica voor het oplossen van conflicten/verwijzingen
│   ├── *Marker.kt / *Metadata.kt / *TagReader.kt  # Bestandsmetadata utilities
│   ├── RingtoneDb.kt            # Room database + entities + DAO's + migraties
│   └── RemoteLogger.kt          # Singleton logging object
├── audio/                       # Audio verwerking (decoder, trimmer, player)
├── service/                     # Android Services (NotificationListenerService)
├── worker/                      # WorkManager tasks
└── receiver/                    # BroadcastReceivers
```

### Regels

- **Eén klasse per bestand** — bestandsnaam = klassenaam
- **Uitzondering:** `RingtoneDb.kt` bevat entities, enums, DAO's en database in één bestand (Room conventie)
- **Data classes** bij entities en API responses mogen in hetzelfde bestand als de gerelateerde klasse (bijv. `ScannedFile`, `DiskUsage`, `SpotifyConverter` in `StorageManager.kt`)

---

## 2. Class Naming

| Suffix | Patroon | Voorbeeld | Locatie |
|--------|---------|-----------|---------|
| `*Screen` | UI Composable (tab of sub-scherm) | `SpotifyScreen`, `EditorScreen` | `ui/screens/` |
| `*Manager` | Beheert een domein of resource | `StorageManager`, `UpdateManager`, `BackupManager` | `data/` |
| `*Client` | HTTP client voor specifieke externe service | `Y2MateClient`, `SpotMateDirectClient`, `IctHorseBackupClient` | `data/` |
| `*Api` | API wrapper (extern, geen auth) | `DeezerApi` | `data/` |
| `*Repository` | Data-abstractie over Android API | `ContactsRepository` | `data/` |
| `*Resolver` | Beslissingslogica | `TrackResolver`, `ConflictResolver` | `data/` |
| `*Worker` | WorkManager PeriodicWorkRequest | `RingtoneWorker` | `worker/` |
| `*Service` | Android Service | `NotificationService` | `service/` |
| `*Receiver` | BroadcastReceiver | `CallStateReceiver` | `receiver/` |
| `*Decoder` / `*Trimmer` / `*Player` | Audio verwerking | `AudioDecoder`, `AudioTrimmer`, `AudioPlayer` | `audio/` |

### Speciale klassen

| Klasse | Type | Reden |
|--------|------|-------|
| `RemoteLogger` | `object` (singleton) | Eén globale logger, geïnitialiseerd bij app start |
| `M4aMetadata` | `object` (singleton) | Stateless utility, pure functies |
| `Mp3Marker` | `object` (singleton) | Stateless utility, pure functies |
| `Mp3TagReader` | `object` (singleton) | Stateless utility, pure functies |

---

## 3. Compose UI Patronen

### State Management

```kotlin
// Mutable state voor UI-specifieke waarden
var isLoading by remember { mutableStateOf(false) }
var errorMessage by remember { mutableStateOf<String?>(null) }
var showDialog by remember { mutableStateOf(false) }

// Int state met geoptimaliseerde recomposition
var selectedTab by remember { mutableIntStateOf(0) }
```

### Naming Conventie voor State

| Prefix | Type | Voorbeeld |
|--------|------|-----------|
| `is*` | Boolean status | `isLoading`, `isActive`, `isPlaying` |
| `show*` | Boolean voor dialogen/UI elementen | `showDialog`, `showMenu`, `showConfirm` |
| `selected*` | Huidige selectie | `selectedTab`, `selectedTrack`, `selectedPlaylist` |
| `current*` | Actieve waarde | `currentUrl`, `currentPhase` |
| `error*` | Foutberichten | `errorMessage` |

### LaunchedEffect Gebruik

- **Eenmalige initialisatie:** `LaunchedEffect(Unit) { ... }` voor data laden bij screen open
- **Waarde-afhankelijk:** `LaunchedEffect(key) { ... }` voor reactie op state changes
- **Geen side effects in composables** buiten LaunchedEffect/rememberCoroutineScope

---

## 4. Database Conventies

### Entity Naming

| Kotlin | Room tabel | Stijl |
|--------|-----------|-------|
| `SavedTrack` | `saved_tracks` | PascalCase → snake_case |
| `Playlist` | `playlists` | PascalCase → snake_case |
| `PlaylistTrack` | `playlist_tracks` | PascalCase → snake_case |

### Field Naming

- **Kotlin:** camelCase (`deezerTrackId`, `localPath`, `lastPlayedTrackId`)
- **Room columns:** identiek aan Kotlin (geen expliciete `@ColumnInfo`)
- **Foreign keys:** `{entity}Id` (bijv. `playlistId`, `trackId`)
- **Nullable:** `String?` voor optionele velden, nooit lege strings als null-equivalent

### Enum Definities

Alle enums in `RingtoneDb.kt` met inline comments:

```kotlin
enum class Channel { CALL, NOTIFICATION, SMS, WHATSAPP }
enum class Mode { FIXED, REAL_RANDOM, SEMI_RANDOM, QUASI_RANDOM }
enum class Schedule { MANUAL, EVERY_CALL, HOURLY_1, ..., DAILY, WEEKLY }
```

Opgeslagen als `String` via Room TypeConverters (enum naam).

### Migraties

- **Naming:** `MIGRATION_{from}_{to}` (bijv. `MIGRATION_4_5`)
- **Locatie:** Companion object van `RingtoneDatabase`
- **Fallback:** `fallbackToDestructiveMigration()` als laatste resort
- **Huidige versie:** 7

---

## 5. DataStore Keys (StorageManager)

DataStore naam: `"storage_settings"` (via `preferencesDataStore`)

| Key | Type | Default | Beschrijving |
|-----|------|---------|-------------|
| `download_path` | `String?` | `{filesDir}/downloads/` | Custom download directory |
| `ringtone_path` | `String?` | `{externalFilesDir}/Music/RandomRingtone/Ringtones/` | Custom ringtone directory |
| `spotify_converter` | `String` | `"spotifydown"` | Actieve Spotify converter ID |
| `backup_uri` | `String?` | `null` | SAF URI voor backup locatie |
| `use_direct_api` | `Boolean` | `false` | SpotMate API i.p.v. WebView converter |
| `debug_logging` | `Boolean` | `true` | Remote logging aan/uit |
| `last_update_check` | `Long` | `0` | Timestamp laatste update check |
| `install_apk_allowed` | `Boolean` | `false` | Gebruiker heeft APK installatie toegestaan |
| `debug_build` | `Boolean` | `false` | Toon DEBUG builds in update lijst ("Old build" schuif) |

---

## 6. Logging Conventies

### RemoteLogger

Singleton `object RemoteLogger` — altijd beschikbaar na `init(context)` bij app start.

#### Log Levels

| Methode | Level | Gebruik |
|---------|-------|---------|
| `d(tag, msg)` | DEBUG | Ontwikkelaar-detail, flow tracing |
| `i(tag, msg)` | INFO | Normale operaties, succesvolle acties |
| `w(tag, msg)` | WARN | Onverwachte maar herstelbare situaties |
| `e(tag, msg)` | ERROR | Fouten die actie vereisen |

#### Convenience Methods

| Methode | Prefix | Gebruik |
|---------|--------|---------|
| `input(tag, desc, data)` | `"INPUT: "` | Inkomende data (API response, user actie) |
| `output(tag, desc, data)` | `"OUTPUT: "` | Uitgaande actie (ringtone set, download start) |
| `trigger(tag, desc, data)` | `"TRIGGER: "` | Event dat een flow start (call state change, notification) |
| `result(tag, desc, success, data)` | `"RESULT: "` | Resultaat van een operatie (success/fail) |
| `callSummary(ctx, name, number, swaps)` | `"CALL_SUMMARY"` | Samenvatting na afhandeling oproep |

#### TAG Conventies

| Component | TAG | Voorbeeld |
|-----------|-----|-----------|
| RemoteLogger zelf | `"RemoteLogger"` | Init, flush, heartbeat |
| UpdateManager | `"UpdateManager"` | Version fetch, APK download |
| Screens | Screennaam | `"SpotifyScreen"`, `"EditorScreen"` |
| Workers | Workernaam | `"RingtoneWorker"` |

#### Dual Output

Elke `RemoteLogger` call schrijft **altijd** naar:
1. **Lokaal:** Android Logcat via `Log.{d,i,w,e}(tag, message)`
2. **Remote:** Batch POST naar `https://horsecloud55.ddns.net/rrlog/log` (als `enabled = true`)

#### Remote Server

| Eigenschap | Waarde |
|------------|--------|
| URL | `https://horsecloud55.ddns.net/rrlog/log` |
| Auth | `X-Api-Key` header |
| Batch size | Max 50 entries per flush |
| Flush interval | 2 seconden |
| Heartbeat | 30 seconden (memory, threads, queue) |
| Queue max | 500 entries (FIFO, oudste dropped) |

---

## 7. Error Handling

### Strategie

| Context | Aanpak |
|---------|--------|
| API calls (Deezer, Y2Mate, SpotMate) | `try-catch` → `RemoteLogger.e()` → return `null` of `emptyList()` |
| Database operaties | Room gooit exceptions → worden niet expliciet gevangen (crash = bug) |
| Bestandsoperaties | `try-catch` → graceful degradation (bestand overslaan, doorgaan) |
| UI feedback | `Snackbar` voor gebruiker-zichtbare fouten |
| Metadata schrijven (Mp3Marker, M4aMetadata) | `try-catch` → silent fail (audio bestand nog steeds geldig) |
| MediaStore queries | `try-catch` → `null` cursor negeren |

### Patroon

```kotlin
// Typisch error handling patroon
try {
    val result = riskyOperation()
    RemoteLogger.result(TAG, "Operatie X", success = true, mapOf("key" to "value"))
} catch (e: Exception) {
    RemoteLogger.e(TAG, "Operatie X gefaald", mapOf("error" to (e.message ?: "unknown")))
    // Graceful degradation: return null/empty, niet crashen
}
```

---

## 8. Versioning Conventies

### Drie Identifiers

| Identifier | Scope | Voorbeeld | Bijwerk-moment |
|------------|-------|-----------|----------------|
| **Versienummer** (versionName) | SemVer | `1.9.15` | Bij elke code-wijziging |
| **Buildnummer** (versionCode) | Sequentieel | `126` | Bij elke build (+1) |
| **Codename** (buildnaam) | Per major versie | `Michael_Jackson` | Bij +0.1.0 of +1.0.0 (nieuwe artiest) |
| **Releasename** | Per individuele release | `Got_To_Be_There` | Bij elke build (nieuw nummer) |

### Bestanden die bijgewerkt moeten worden

Bij elke versie-bump:
1. `version.json` — `version`, `versionCode`, `codename`, `releaseName`, `last_updated`
2. `app/build.gradle.kts` — `versionName`, `versionCode`, `appCodename`, `appReleaseName`, `BUILD_NUMBER`

### Gebruikte Artiesten (codenamen)

| Artiest | Major versie | Status |
|---------|-------------|--------|
| Jimi_Hendrix | v0.1.x | Afgerond |
| Freddie_Mercury | v0.2.x | Afgerond |
| David_Bowie | v0.3.x | Afgerond |
| Amy_Winehouse | v0.4.x | Afgerond |
| Kurt_Cobain | v0.5.x | Afgerond |
| Janis_Joplin | v0.6.x | Afgerond |
| Prince | v0.7.x | Afgerond |
| Michael_Jackson | v1.x.x | Actief |

### Regels

- Bij bugfix (+0.0.1): huidige artiest blijft, nieuw nummer kiezen
- Bij feature (+0.1.0): overleggen of nieuwe artiest nodig is
- Bij major (+1.0.0): altijd nieuwe artiest
- Nooit een artiest of releasename hergebruiken
- AI kiest zelf de artiest (niet aan gebruiker vragen)
- Releasename = nummer van de actieve artiest (underscore-separated)

---

## 9. Build & APK Conventies

### APK Naamgeving

```
RandomRingtone-v{versionName}-{codename}-{releaseName}-{buildType}.apk
```

Voorbeeld: `RandomRingtone-v1.9.15-Michael_Jackson-Got_To_Be_There-release.apk`

### Build Types

| Type | Minify | Shrink | Signing | Grootte |
|------|--------|--------|---------|---------|
| `release` | Ja (R8/ProGuard) | Ja | Release JKS | ~1.9 MB |
| `debug` | Nee | Nee | Debug keystore | ~17 MB |

**Regel:** Altijd `assembleRelease` bouwen, ook voor DEBUG-status builds.

### BuildConfig Fields

| Field | Type | Bron | Gebruik |
|-------|------|------|---------|
| `CODENAME` | `String` | `appCodename` variabele | SettingsScreen "Over" |
| `RELEASE_NAME` | `String` | `appReleaseName` variabele | SettingsScreen "Over" |
| `BUILD_NUMBER` | `int` | Hardcoded | SettingsScreen "Over" + UpdateManager |
| `BUILD_STATUS` | `String` | `"DEBUG"` of `"STABLE"` | SettingsScreen badge |

---

## 10. Bestandsnaam Conventies (Downloads)

| Bron | Patroon | Voorbeeld |
|------|---------|-----------|
| Deezer | `download_{trackId}.mp3` | `download_123456.mp3` |
| Spotify | `spotify_mp3_{track}-{artiest}.mp3` | `spotify_mp3_Bohemian_Rhapsody-Queen.mp3` |
| YouTube | `youtube_mp3_{title}.mp3` | `youtube_mp3_Never_Gonna_Give_You_Up.mp3` |
| Trimmed | `ringtone_{trackId}.mp3` | `ringtone_123456.mp3` |
| Trimmed (playlist) | `ringtone_{trackId}_{playlistName}.mp3` | `ringtone_123456_Rock.mp3` |

### MP3 Markers (ID3v1 comment)

| Marker | Betekenis | Gezet door |
|--------|-----------|-----------|
| `"RandomRingtone track"` | Originele download | `Mp3Marker.injectIfMissing()` |
| `"RandomRingtone trimmed"` | Getrimde ringtone | `AudioTrimmer` via `Mp3Marker` |
| `"RandomRingtone youtube"` | YouTube download | `YouTubeScreen` via `Mp3Marker` |

---

## 11. Conflict Resolutie Hiërarchie

Prioriteit bij meerdere actieve playlists voor hetzelfde kanaal:

1. **Per-contact playlist** (hoogste prioriteit)
2. **Globale playlist** (actief)
3. **Handmatige directe instelling**
4. **Legacy toewijzing** (deprecated, verwijderd in v0.5.0)

Afdwinging: `ConflictResolver` + `PlaylistDao.deactivateOtherGlobal()` / `deactivateOtherForContact()`
