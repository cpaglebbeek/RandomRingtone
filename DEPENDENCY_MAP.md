# Dependency Map — RandomRingtone

Versie: 1.0 | Datum: 2026-05-03

---

## Component Overzicht

### UI Layer (Screens)

```
MainActivity.kt
  ├── SpotifyScreen      ── SpotMateDirectClient, StorageManager, Mp3Marker
  ├── YouTubeScreen      ── Y2MateClient, StorageManager, Mp3Marker
  ├── EditorScreen       ── AudioDecoder, AudioTrimmer, AudioPlayer, StorageManager, Mp3Marker
  ├── LibraryScreen      ── SavedTrackDao, StorageManager, AppRingtoneManager, Mp3TagReader
  ├── PlaylistManagerScreen ── PlaylistDao, PlaylistTrackDao, SavedTrackDao, ContactsRepository, ConflictResolver
  ├── OverviewScreen     ── PlaylistDao, PlaylistTrackDao, ConflictResolver
  ├── BackupScreen       ── BackupManager, IctHorseBackupClient, StorageManager
  └── SettingsScreen     ── StorageManager, RemoteLogger, UpdateManager, LicenseManager
```

### Data Layer

```
DeezerApi            ── OkHttpClient (eigen instantie)
SpotMateDirectClient ── OkHttpClient (eigen instantie)
Y2MateClient         ── OkHttpClient (eigen instantie)
IctHorseBackupClient ── OkHttpClient (eigen instantie)
LicenseManager       ── OkHttpClient (eigen instantie), SharedPreferences
UpdateManager        ── OkHttpClient (eigen instantie), RemoteLogger, FileProvider
AppRingtoneManager   ── OkHttpClient (eigen instantie), MediaStore, ContactsContract, StorageManager
BackupManager        ── RingtoneDatabase, StorageManager, SAF DocumentFile
StorageManager       ── DataStore<Preferences>
ContactsRepository   ── ContactsContract
TrackResolver        ── SavedTrackDao, StorageManager, DeezerApi, AppRingtoneManager
ConflictResolver     ── PlaylistDao
RemoteLogger         ── OkHttpClient (eigen instantie), Settings.Secure, SharedPreferences
```

### Audio Layer

```
AudioDecoder  ── MediaCodec, MediaExtractor
AudioTrimmer  ── MediaExtractor, MediaMuxer, Mp3Marker, M4aMetadata
AudioPlayer   ── MediaPlayer
```

### Background Layer

```
RingtoneWorker    ── PlaylistDao, PlaylistTrackDao, TrackResolver, AppRingtoneManager, RemoteLogger
NotificationService ── PlaylistDao, PlaylistTrackDao, TrackResolver, AppRingtoneManager, RemoteLogger
CallStateReceiver ── PlaylistDao, PlaylistTrackDao, TrackResolver, AppRingtoneManager, RemoteLogger
```

### Utility Layer (Stateless Singletons)

```
Mp3Marker     ── (geen afhankelijkheden, pure byte-level I/O)
Mp3TagReader  ── (geen afhankelijkheden, pure byte-level I/O)
M4aMetadata   ── (geen afhankelijkheden, pure byte-level I/O)
```

---

## Afhankelijkheidsmatrix

### Wie gebruikt wie

| Component | Gebruikt door |
|-----------|--------------|
| **DeezerApi** | SpotifyScreen (indirect), TrackResolver, RingtoneWorker (via TrackResolver) |
| **AppRingtoneManager** | LibraryScreen, PlaylistManagerScreen, TrackResolver, RingtoneWorker, NotificationService, CallStateReceiver |
| **RingtoneDatabase** | BackupManager (direct), alle Screens + Workers via DAO's |
| **PlaylistDao** | PlaylistManagerScreen, OverviewScreen, ConflictResolver, RingtoneWorker, NotificationService, CallStateReceiver |
| **PlaylistTrackDao** | PlaylistManagerScreen, OverviewScreen, RingtoneWorker, NotificationService, CallStateReceiver |
| **SavedTrackDao** | LibraryScreen, PlaylistManagerScreen, TrackResolver |
| **StorageManager** | SpotifyScreen, YouTubeScreen, EditorScreen, LibraryScreen, BackupScreen, SettingsScreen, AppRingtoneManager, BackupManager |
| **TrackResolver** | RingtoneWorker, NotificationService, CallStateReceiver |
| **ConflictResolver** | PlaylistManagerScreen, OverviewScreen |
| **RemoteLogger** | UpdateManager, RingtoneWorker, NotificationService, CallStateReceiver, SettingsScreen |
| **Mp3Marker** | SpotifyScreen, YouTubeScreen, EditorScreen, StorageManager (scan), AudioTrimmer |
| **Mp3TagReader** | LibraryScreen |
| **M4aMetadata** | AudioTrimmer |
| **ContactsRepository** | PlaylistManagerScreen |
| **LicenseManager** | SettingsScreen, RemoteLogger (owner name) |
| **UpdateManager** | SettingsScreen |
| **BackupManager** | BackupScreen |
| **IctHorseBackupClient** | BackupScreen |
| **SpotMateDirectClient** | SpotifyScreen |
| **Y2MateClient** | YouTubeScreen |
| **AudioDecoder** | EditorScreen |
| **AudioTrimmer** | EditorScreen |
| **AudioPlayer** | EditorScreen |

---

## Externe Afhankelijkheden

### API's & Servers

```
                    ┌─────────────────────────────────────┐
                    │         RandomRingtone App          │
                    └─────┬──────┬──────┬──────┬─────────┘
                          │      │      │      │
              ┌───────────┘      │      │      └──────────────┐
              ▼                  ▼      ▼                     ▼
    ┌─────────────────┐  ┌────────┐  ┌──────────────┐  ┌───────────────┐
    │ api.deezer.com  │  │Y2Mate  │  │ SpotMate     │  │ icthorse.nl   │
    │ (geen auth)     │  │API     │  │ API          │  │               │
    │ • /search       │  │(MP3    │  │(Spotify→MP3) │  │ • /RandomRing │
    │ • /track/{id}   │  │convert)│  │              │  │   /Apk/ (APK) │
    │ • /playlist/{id}│  └────────┘  └──────────────┘  │ • /Apps/      │
    │ • /artist/{id}  │                                 │   Android/    │
    └─────────────────┘                                 │   (licentie)  │
                                                        └───────┬───────┘
                                                                │
                                                    ┌───────────┘
                                                    ▼
                                          ┌─────────────────┐
                                          │ horsecloud55     │
                                          │ .ddns.net        │
                                          │ • /rrlog/log     │
                                          │   (remote log)   │
                                          └─────────────────┘
```

| Service | URL | Auth | Gebruikt door |
|---------|-----|------|--------------|
| Deezer API | `api.deezer.com` | Geen | DeezerApi |
| Y2Mate | Variabel | Geen | Y2MateClient |
| SpotMate | `spotmate.online` | Geen | SpotMateDirectClient |
| Spotify converters | 10 sites (zie StorageManager) | Geen | SpotifyScreen WebView |
| iCt Horse APK | `icthorse.nl/RandomRing/Apk/` | Geen | UpdateManager |
| iCt Horse License | `icthorse.nl/Apps/Android/RandomRing/lics/` | Device hash | LicenseManager |
| iCt Horse Backup | `icthorse.nl` | API key | IctHorseBackupClient |
| Remote Logger | `horsecloud55.ddns.net/rrlog/log` | X-Api-Key header | RemoteLogger |

### Android System APIs

| API | Gebruikt door | Permissie |
|-----|--------------|-----------|
| `RingtoneManager` | AppRingtoneManager | `WRITE_SETTINGS` |
| `MediaStore` | AppRingtoneManager, StorageManager | `READ_MEDIA_AUDIO` |
| `ContactsContract` | ContactsRepository, AppRingtoneManager | `READ_CONTACTS`, `WRITE_CONTACTS` |
| `NotificationListenerService` | NotificationService | `BIND_NOTIFICATION_LISTENER_SERVICE` |
| `WorkManager` | RingtoneWorker | `RECEIVE_BOOT_COMPLETED` |
| `TelephonyManager` (PHONE_STATE) | CallStateReceiver | `READ_PHONE_STATE`, `READ_CALL_LOG` |
| `FileProvider` | UpdateManager | `REQUEST_INSTALL_PACKAGES` |
| `DataStore` | StorageManager | Geen |
| `Room` | RingtoneDatabase | Geen |
| `MediaCodec` / `MediaExtractor` / `MediaMuxer` | AudioDecoder, AudioTrimmer | Geen |
| `MediaPlayer` | AudioPlayer | Geen |
| `Settings.Secure` | RemoteLogger (ANDROID_ID) | Geen |
| `AccountManager` | RemoteLogger (Google account name) | Geen |
| `SAF (DocumentFile)` | BackupManager, StorageManager | Gebruiker kiest map |

---

## Library Dependencies (gradle)

```
compose-bom (2024.12.01)
├── compose-ui
├── compose-material3
├── compose-ui-tooling-preview
└── compose-icons-extended

activity-compose (1.9.3)
navigation-compose (2.8.5)
lifecycle-viewmodel-compose (2.8.7)
lifecycle-runtime-compose (2.8.7)

room-runtime (2.6.1)
room-ktx (2.6.1)
room-compiler (2.6.1) [KSP]

work-runtime-ktx (2.10.0)
okhttp (4.12.0)
kotlinx-serialization-json (1.7.3)
documentfile (1.0.1)
core-ktx (1.15.0)
datastore-preferences (1.1.1)
```

---

## Kritieke Paden

### Ringtone Instellen (handmatig)

```
User tap → Screen → AppRingtoneManager.setRingtone()
                      ├── OkHttpClient.download(previewUrl)
                      ├── MediaStore.insert()
                      └── RingtoneManager.setActualDefaultRingtoneUri()
```

### Ringtone Wisselen (schema)

```
WorkManager trigger → RingtoneWorker.doWork()
                       ├── PlaylistDao.getActive()
                       ├── PlaylistTrackDao.getTracksForPlaylist()
                       ├── TrackResolver.resolve()
                       │    ├── SavedTrackDao.getById()
                       │    ├── StorageManager.getDownloadDir()
                       │    └── (fallback) DeezerApi.getTrack() + download
                       └── AppRingtoneManager.setRingtone()
```

### SMS/WhatsApp Interceptie

```
Android notificatie → NotificationService.onNotificationPosted()
                       ├── Extract contactnaam uit notificatie
                       ├── PlaylistDao.getActiveForContactAndChannel()
                       ├── (fallback) PlaylistDao.getActiveGlobalForChannel()
                       ├── TrackResolver.resolve()
                       └── MediaPlayer.play(customSound)
```

### In-App Update

```
SettingsScreen → UpdateManager.fetchVersions()
                  ├── HTTP GET build_info.php
                  ├── Parse build.timestamp
                  ├── getBestUpdate(versions, currentBuild)
                  ├── downloadApk(version, onProgress)
                  └── installApk(apkFile) → FileProvider → ACTION_VIEW
```
