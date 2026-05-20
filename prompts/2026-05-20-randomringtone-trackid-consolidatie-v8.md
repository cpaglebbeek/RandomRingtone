---
date: 2026-05-20
project: RandomRingtone
topic: R11 — TrackId-consolidatie (DataStore-flag migratie v8)
resume: 2026-05-20-randomringtone-trackid-consolidatie-v8
status: open
codename: Whitney_Houston
release: I_Will_Always_Love_You
version: 1.10.0
build: 132
kleur: Oranje (+0.1.0) — architectonisch + auto-migratie + nieuwe codenaam
---

# RandomRingtone — R11 TrackId-consolidatie (auto-migratie v8)

## Probleem
Drie inconsistente trackId-strategieën:
- Deezer: echte `track.id`
- YouTube (`YouTubeScreen.kt:371,399`): `file.name.hashCode().toLong()` — **signed** (kan negatief)
- Editor trim (`EditorScreen.kt:694`): `file.absolutePath.hashCode()` positief gemaakt
- Scan (`StorageManager.parseFileName`): `name.hashCode()` positief gemaakt

→ Zelfde file → 2 verschillende trackIds afhankelijk van flow → dupes in DB + mismatches bij scan.

## Beslissingen (per WhatIf-akkoord 2026-05-20)
- **Strategie B**: DataStore-flag eenmalige migratie, geen Room schema-bump (DB blijft v7)
- **Pre-migratie backup**: dump JSON naar `filesDir/pre_trackid_migration_backup/`
- **Codenaam**: Whitney_Houston (nieuwe artiest bij +0.1.0)
- **Releasenaam**: I_Will_Always_Love_You (symbolisch: bestaande playlists worden gerespecteerd)

## Canonical strategie
- **Deezer-style** (behouden): `markerType in ("deezer", null)` EN filename `download_<digits>.mp3` EN `<digits> == deezerTrackId`
- **Else** (recompute): `canonicalTrackIdForName(filename) = abs(name.hashCode())`
- **Editor trim** uitzondering: blijft `absolutePath.hashCode()` (issue #70-fix — bewust uniek per directory)

## Plan
1. `data/TrackIdResolver.kt` — `canonicalTrackIdForName(name)` + `shouldKeepDeezerId(track)` helpers
2. `data/TrackIdMigration.kt` — `migrateV8(db, storage, context)`:
   - Pre-migratie backup
   - Bereken oude→nieuwe id mapping
   - Collision-merge (twee tracks → zelfde new id → keep oudste, merge playlist_tracks)
   - Update saved_tracks + playlist_tracks in transactie
3. `StorageManager.KEY_TRACK_ID_MIGRATION_V8_DONE` flag
4. `MainActivity` — na autoRestoreFromLocal: check flag, run migratie als false
5. Forward-fixes:
   - `YouTubeScreen.kt:371,399` → `canonicalTrackIdForName(file.name)`
   - `SpotifyScreen.kt` — check + gebruik helper waar van toepassing
   - `StorageManager.parseFileName` — gebruik helper (al positief, voor consistentie)
6. Versie bump 1.10.0 / 132 / Whitney_Houston / I_Will_Always_Love_You
7. CONFLICTS R11 → DONE; BUGLIST entry #79

## Risico's
- Migratie faalt halverwege → pre-backup beschikbaar voor handmatige restore
- Collision-merge keep oudste → mogelijk verlies van metadata van duplicaat (mitigatie: log details)
- Dead localPath entries → skip migratie voor die rows; orphan cleanup wist later

## Test op toestel
1. Eerste app-start na install: snackbar/log "TrackId migratie v8: X tracks geremapt, Y merged"
2. Playlists check: alle tracks nog zichtbaar, koppelingen intact
3. Tweede app-start: geen migratie meer (flag = true)
