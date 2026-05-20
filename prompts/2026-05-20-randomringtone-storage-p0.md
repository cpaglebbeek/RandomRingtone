---
date: 2026-05-20
project: RandomRingtone
topic: Storage P0 — directory-move, restore-routing, auto-restore path-rewrite
resume: 2026-05-20-randomringtone-storage-p0
status: open
codename: Michael_Jackson
release: Man_In_The_Mirror
version: 1.9.18
build: 129
kleur: Rood→Geel (data-verlies bij move/restore; gerichte fixes, geen architectuur-redesign)
---

# RandomRingtone — Storage P0 (data-verlies bij directory-move + restore)

## Probleem
Deep-dive 2026-05-20 toonde 3 data-verlies-paden:

- **Fix A (BUG-A):** `Settings → locatie wijzigen → MOVE` verplaatst fysieke bestanden maar updatet `saved_tracks.localPath` NIET → bij volgende refresh wist orphan-cleanup de DB-records → playlist_tracks verliezen verbinding.
- **Fix B (BUG-B):** `BackupManager.restore()` zet álle `localPath` naar `ringtoneDir`, ongeacht of het een download of ringtone was → DB wijst naar verkeerde locatie → orphan cleanup wist alles.
- **Fix C (BUG-C):** `BackupManager.autoRestoreFromLocal()` migreert `localPath` niet → bij fresh install (na app-uninstall) wijzen paden naar `/data/data/<oldPackage>/...` die niet meer bestaan → DB leeg na orphan cleanup.

## Plan
1. **`StorageManager.migratePathsInDb(db, oldDir, newDir): Int`** — DB-transaction: voor elke `saved_tracks` rij waarvan `localPath.startsWith(oldDir.absolutePath)` herschrijven naar `newDir.absolutePath + rest`.
2. **`SettingsScreen` FileMoveDialog** — bij `MOVE` actie aanroep van migratePathsInDb na move; bij `COPY` géén DB-update (oude bestanden bestaan nog).
3. **`BackupManager.TrackBackup`** krijgt `subdir: String?` veld (`"downloads"` of `"ringtones"`). Bij backup zet, bij restore lees + bestemming kiezen.
4. **Backward-compat infer** — oude backups zonder `subdir`: bepaal uit filename:
   - `ringtone_<id>*` of `.m4a` → ringtones
   - `download_<id>` / `spotify_mp3_*` / `youtube_mp3_*` → downloads
   - `markerType == "trimmed"` → ringtones
   - rest → downloads
5. **`autoRestoreFromLocal`** — voor elke track localPath herschrijven via infer-logic (filename → subdir → huidige downloadDir/ringtoneDir).

## Versie
v1.9.18 / Build 129 / Michael_Jackson / Man_In_The_Mirror / DEBUG

## Test op toestel
1. **Move test:** Settings → Downloads locatie wijzigen → MOVE → controleer: bestanden verhuisd + Library toont nog alle tracks + playlists intact.
2. **Restore test:** Backup maken → app data wissen → Restore → controleer: alle tracks in Library + downloads in download-tab, ringtones in tones-tab.
3. **Auto-restore test:** App uninstall + reinstall → controleer: auto-restore werkt + localPaths kloppen.

## Niet in scope (volgende releases)
- Fix D (Library-filter versoepelen) → P1
- Fix F (storage write-test) → P1
- Fix G/H (scan perf + trackId-consolidatie) → P2
- Fix E/I (UX + docs) → P3
