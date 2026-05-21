---
date: 2026-05-20
project: RandomRingtone
topic: Storage P2 — scan performance (G)
resume: 2026-05-20-randomringtone-storage-p2-scanperf
status: pending
codename: Michael_Jackson
release: Heal_The_World
version: 1.9.20
build: 131
kleur: Geel — performance (out-of-physical-box op IO)
---

# RandomRingtone — Storage P2 (scan performance)

## Probleem (BUG-G uit deep-dive)
`StorageManager.scanAllMediaByMarker()` doet een MediaStore-query zonder selectie en opent ELK audio-bestand op de telefoon om Mp3Marker.hasMarker(file) te checken. Op een telefoon met duizenden audio-files (Spotify-cache, voicememos, andere ringtones) is dit traag en blokkeert de IO-pool langdurig.

De huidige scan-volgorde is bovendien suboptimaal:
1. `scanDir` × 3 (ringtone, download, system download)
2. `scanAllMediaByMarker` — full-device scan (zware operatie, ALTIJD uitgevoerd)
3. `scanViaMediaStore` — fallback, alleen bij empty (lichte operatie)

Resultaat: ook wanneer `scanViaMediaStore` (snel, gefilterd op pad-pattern) zou volstaan, doet `scanAllMediaByMarker` toch het volledige werk.

## RCA (3 niveaus)
- **Functioneel:** scan kan minuten duren bij grote collecties; UI lijkt vast te lopen
- **Technisch:** marker-scan opent élk audio-bestand zonder selection-filter
- **Architectonisch:** geen budget/early-exit; geen ordening van light→heavy fallbacks

## Plan
1. **Herorden scan-pipeline** in `scanExistingFiles()`:
   - Stap 1: scanDir × 3 (ringtone/download/system) — light, app-paden
   - Stap 2: scanViaMediaStore — light, MediaStore-query met selection op pad/naam-pattern
   - Stap 3 (fallback): scanAllMediaByMarker — heavy, alleen ALS results na stap 1+2 nog leeg
2. **Hard limit + MIME-filter** in `scanAllMediaByMarker`:
   - MediaStore selection op `MIME_TYPE IN ('audio/mpeg','audio/mp4','audio/m4a','audio/aac')` — reduceert dataset
   - Counter `processedCount` met max **2000** files — voorkomt UI-blok bij extreme collecties
3. **Logging**: rapporteer in ScanResult hoeveel files daadwerkelijk gecheckt zijn (voor diagnostiek-dialoog).

## Versie
v1.9.20 / Build 131 / Michael_Jackson / Heal_The_World / DEBUG

## Niet in scope nu — geagendeerd
- **Fix H (trackId-consolidatie)** uitgesteld: vereist DB-migratie omdat bestaande DB-entries trackIds hebben gebaseerd op de oude strategieën. Een silent re-hash zou playlist_tracks-koppelingen breken. Aparte WhatIf-sessie nodig met migratie-plan (oude_id → nieuwe_id mapping in playlist_tracks bij upgrade).

## Test op toestel
- Library → Scan op toestel met grote audio-collectie: moet duidelijk sneller zijn (geen full-device read)
- Edge case: leeg toestel zonder app-tracks → marker-scan fallback moet alsnog werken
