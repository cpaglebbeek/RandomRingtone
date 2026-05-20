---
date: 2026-05-20
project: RandomRingtone
topic: Storage P1 — Library-filter versoepelen + storage write-test
resume: 2026-05-20-randomringtone-storage-p1
status: open
codename: Michael_Jackson
release: Bad
version: 1.9.19
build: 130
kleur: Groen (+0.0.1) — voorspelbaarheid + falen-melden
---

# RandomRingtone — Storage P1 (voorspelbaarheid)

## Probleem
Twee gaten uit deep-dive 2026-05-20:

- **Fix D (BUG-D):** `LibraryScreen.refresh()` filtert tracks op `localPath.contains("RandomRingtone")` OR naam-prefix. Tracks in custom-locatie met custom-naam (bv. editor-output `MijnTone.mp3` in `/storage/emulated/0/Music/MyTones/`) zijn **onzichtbaar** in Library.
- **Fix F (BUG-F):** `StorageManager.setDownloadDir/setRingtoneDir` doet alleen `mkdirs()` — bij Android 11+ scoped storage zonder MANAGE_EXTERNAL_STORAGE faalt dat stil. Downloads schijnen daarna te gebeuren maar bestanden komen er nooit.

## Plan
1. **`LibraryScreen.kt:145-154`** — filter herschrijven naar markerType-gedreven:
   ```kotlin
   .filter { track ->
       !track.localPath.isNullOrBlank() && track.markerType in setOf("track", "trimmed", "youtube")
   }
   ```
   markerType is via `refresh()` stap 1 al gecached voor alle tracks — geen extra disk I/O nodig.

2. **`StorageManager.testWritable(path: String): Boolean`** — helper die `.rrt_writetest` aanmaakt + verwijdert in de map. Returnt true alleen bij succes.

3. **`SettingsScreen`** picker-callback: bij geldig SAF-pad eerst `testWritable` check; bij fail snackbar "Geen schrijfrechten op deze map" en geen `pendingPath` zetten.

## Versie
v1.9.19 / Build 130 / Michael_Jackson / Bad / DEBUG

## Test op toestel
1. **Library-filter test:** trim een bestand naar `MijnTone.mp3` in een custom ringtoneDir buiten "RandomRingtone"-pad → controleer dat track verschijnt in Tones-tab.
2. **Write-test:** kies een map waar app geen rechten heeft (bv. /storage/emulated/0/Documents/) → controleer snackbar-error en dat path NIET wijzigt.
