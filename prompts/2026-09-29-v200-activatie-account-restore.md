---
date: 2026-09-29
repo: RandomRingtone
status: pending
resume: "verder met randomringtone — E2E-goedkeuring magic link (2 testaanvragen), daarna APK v2.0.0 publiceren + Spotify-sleutels"
---

# Sessie 2026-09-29 — v2.0.0: Spotify-bron (ISRC→Deezer), activatie via magic link + HorseAPK, account-restore

## Vraag (gebruiker)
1. "verder met randomringtone" → resume `spotify-webapi-v200`.
2. "bouw switch voor A in instellingen; feature: scherm bij geen licentie en mail activatie link moet mail via backend
   zijn naar cglebbeek+randomringtone.activation.request@gmail.com met magic link … alle informatie over type toestel,
   gebruikersnaam etc. … magic link → pagina met keuzes: add license met de invulvelden vanuit het beheer".
3. "maak van basic auth code bevestigen via apkhorse" → magic link + heel beheer via HorseAPK-goedkeuring.
4. "akkoord, sftp gebruiken. log-dashboard achter basic auth laten" + vraag: mp3's/backups gekoppeld aan deviceId —
   restore op nieuwe telefoon via e-mail? → "akkoord EN verhoog limiet van 50mb naar 500mb".

## Kernbevindingen
- **Spotify `preview_url` is sinds 27-11-2024 uit voor nieuwe apps** → oorspronkelijk v2.0.0-plan onhaalbaar.
  ISRC blijft; Deezer `track/isrc:` levert vrije 30-s-previews (live getest: Come Together, 480 KB MP3). → optie A.
- Oud beheer icthorse.nl/beheer/RandomRing had **geen api.php meer** (sinds v2-sitemigratie juni); API-key stond in
  de client-JS. Nu 301 naar het nieuwe beheer.
- **Remote logging dood sinds 2026-05-02**: nginx-snippet niet meer ge-include. Hersteld (BUG #83).
- **Backup 22-05 onvolledig** (82/124 audio) — upload-resultaat werd genegeerd (BUG #82). April-backup (compleet, 95)
  stond in map `f7eb5c1c7f465205-` → verplaatst naar `f7eb5c1c7f465205/slot_2`. Beide gearchiveerd op HC55
  `/srv/randomringtone-backup-archief/backups-20260929/` (185 bestanden, 1,4 GB).
- Playlists in backups = 0: de playlist-tabel was leeg op het toestel (tracks dragen `playlistName`) — geen bug.

## Opgeleverd
| Onderdeel | Versie | Waar |
|---|---|---|
| HorseAPK dienst-API (externe goedkeuring) | v1.2.0-AresGalaxy | portal live, APK gepubliceerd (3 toestellen gewekt), 293 tests |
| RandomRingtone-backend | v1.1.0 | HC55:3800 via `/rrlog/license|beheer|spotify`, 15 tests |
| backup_api.php | v3 | icthorse.nl — toestel-token, `offers`, `source`, volledigheidscontrole, 500 MB |
| RandomRingtone-app | v2.0.0 build 140 | release-ondertekend (82fac686…), smoke groen op 2 emulators — **nog niet gepubliceerd** |

## Open
- E2E: magic link uit testmail(s) goedkeuren via HorseAPK; daarna testlicenties `e2e0000000000001` en `628d5ac1ace1c76e`
  verwijderen in `/rrlog/beheer/`.
- APK v2.0.0 publiceren (na E2E).
- Spotify Client ID/Secret aanmaken → backend `.env` (tot dan 503).
- Losse bevindingen (niet aangeraakt): `sites-enabled/horsecloud.bak.20260917-pre-asisgate` wordt door nginx geladen;
  HorseAPK-bootstrap op icthorse.nl staat nog op v0.2.3; Android 16-emulator (5584) staat RUNNING_LOCKED.
