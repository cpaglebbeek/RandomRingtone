---
date: 2026-05-20
project: RandomRingtone
topic: MP3 album art embed (overal)
resume: 2026-05-20-randomringtone-mp3-art-embed
status: pending
codename: Michael_Jackson
release: Billie_Jean
version: 1.9.16
build: 127
kleur: Groen (+0.0.1)
---

# RandomRingtone — MP3 album art embed (overal)

## Probleem
M4A-output krijgt `covr` atom embedded (T13 `M4aMetadata`), MP3-output krijgt **geen** embedded art. Art bestaat alleen als JPEG-cache in `cacheDir/album_art/<hash>.jpg` + DB-pad `saved_tracks.albumArtPath`. Cache kan op elk moment door OS gewipte worden → DB-pad wordt dood → MP3-ringtones verliezen art bij share, MediaStore-preview, externe spelers, backup/restore.

## RCA (3 niveaus)
- **Functioneel:** MP3-ringtones missen art zodra cache wipt
- **Technisch:** geen APIC-writer voor MP3 — alleen ID3v1 marker (`Mp3Marker`) die geen binary art kan dragen
- **Architectonisch:** T13 `M4aMetadata` dekt M4A; geen analoog voor MP3 → nieuwe T14 `Mp3AlbumArt` nodig

## Beslissing
- Scope: **overal** embedden (editor-save + YouTube + Spotify + Deezer-preview), per gebruiker akkoord 2026-05-20
- Skip bij bestaand `"ID3"` header in MP3 — geen overschrijven (veilig)
- ID3v2.3 (breder ondersteund dan v2.4 bij Android MediaCodec / ringtone-picker)
- Tmp-write + verify via `MediaMetadataRetriever.embeddedPicture` vóór replace

## Wijzigingen
- `data/Mp3AlbumArt.kt` (nieuw) — pure byte-level ID3v2.3 writer
- `ui/screens/EditorScreen.kt:629, :647` — embed na `injectTrimmedMarker`
- `ui/screens/YouTubeScreen.kt:fetchYouTubeThumbnail` — embed direct na cache-write
- `data/SpotMateDirectClient.kt:downloadTrack` — embed na MP3-download (via `trackInfo.albumArt`)
- `data/RingtoneManager.kt:downloadPreview` — best-effort embed via Deezer `album.coverMedium`
- `app/build.gradle.kts` + `version.json` → v1.9.16 / build 127 / Billie_Jean
- `BUGLIST.md` — entry #71 (Geel — out-of-physical-box)
- `CLAUDE.md` (in repo) — T14 toevoegen aan tech-features
- `build.timestamp` — regel met DEBUG marker

## Build/Deploy
- `JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- `./gradlew assembleRelease` (per `feedback_randomringtone_release_build`)
- APK → `/Users/christian/Downloads/`
- rsync APK + build.timestamp → `icthorse.nl/RandomRing/Apk/` (DEBUG marker per `feedback_randomringtone_deploy`)
- Git: commit + push

## Open items na build
- Sanity-check op echt toestel: getrimde MP3 in ringtone-picker toont art? Spotify-download in externe MP3-speler toont art? YouTube-download idem?
- Eventueel `enrichAll`-uitbreiding om bestaande MP3's in bibliotheek alsnog van APIC te voorzien — **niet in deze release**.
