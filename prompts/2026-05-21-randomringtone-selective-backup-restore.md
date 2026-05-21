---
date: 2026-05-21
project: RandomRingtone
topic: Feature — selectieve backup/restore (per categorie + per track)
resume: 2026-05-21-randomringtone-selective-backup-restore
status: pending
codename: Whitney_Houston
release: I_Wanna_Dance_With_Somebody
version: 1.11.0
build: 134
kleur: Oranje (+0.1.0) — UI + data-structuur + backup-format-uitbreiding
---

# RandomRingtone — Selectieve backup/restore

## Feature
Backup en restore krijgen per-categorie én per-track selectie. Categorieën:
- Downloads (markerType=track)
- Tones (markerType=trimmed)
- YouTube (markerType=youtube)
- Playlists (playlists + playlist_tracks)
- Instellingen (downloadPath, ringtonePath, spotifyConverter, backupUri)

Binnen elke bibliotheek-categorie kan de gebruiker individuele tracks aan/uitvinken; binnen Playlists individuele playlists. **Default: alles aan** (compat met huidig gedrag).

## Data-structuur
```kotlin
data class BackupSelection(
    val downloads: Boolean = true,
    val downloadTrackIds: Set<Long>? = null,  // null = alle van categorie
    val tones: Boolean = true,
    val toneTrackIds: Set<Long>? = null,
    val youtube: Boolean = true,
    val youtubeTrackIds: Set<Long>? = null,
    val playlists: Boolean = true,
    val playlistIds: Set<Long>? = null,
    val settings: Boolean = true
)
```
Null bij `*TrackIds`/`playlistIds` = volledige categorie (geen extra filtering).

## Backup-flow
1. UI laadt huidige tracks + playlists uit DB
2. Toont expandable secties per categorie met checkboxes
3. Bouwt `BackupSelection`
4. Schrijft alleen geselecteerde tracks naar `saved_tracks.json` (filtered op markerType + optionele Id-set)
5. Schrijft alleen geselecteerde audio-bestanden naar `downloads/` of `ringtones/`
6. Schrijft `playlists.json` + `playlist_tracks.json` filtered
7. Schrijft `settings.json` als selectie.settings = true
8. `backup_meta.json` krijgt veld `selection` zodat restore weet wat in backup zit

## Restore-flow
1. UI: knop "Inhoud bekijken" → leest `saved_tracks.json` + `playlists.json` + `backup_meta.json`
2. Toont preview-dialoog met expandable secties + checkboxes (default: alles aan voor wat in backup zit)
3. Bouwt `BackupSelection` voor restore
4. **Full restore** (alle 5 + null id-sets): bestaande flow met `clearAllTables`
5. **Partial restore**: GEEN clearAllTables — per-categorie upsert + filter playlist_tracks op trackId-in-DB (orphan-cleanup)
6. Settings: per veld upsert via DataStore.edit

## Backwards-compat
- Oude backups zonder `selection` in meta → behandeld als full (alle true)
- Oude callers (autoBackup, autoRestoreFromLocal) → full default

## Versie
v1.11.0 / Build 134 / Whitney_Houston / I_Wanna_Dance_With_Somebody / DEBUG

## Implementatie-volgorde
1. BackupManager: BackupSelection data class
2. BackupManager: backup() krijgt selection-param, schrijft filtered
3. BackupManager: nieuw previewBackup(uri): BackupPreview voor inhoudslijst
4. BackupManager: restore() krijgt selection-param, partial restore-path
5. BackupScreen UI: backup-sectie met checkboxes (collapsible per categorie)
6. BackupScreen UI: restore-preview-dialoog met selectie
7. Version bump + BUGLIST entry + RELEASES + build.timestamp
8. Build + deploy + commit
