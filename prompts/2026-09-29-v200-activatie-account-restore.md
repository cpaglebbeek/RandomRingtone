---
date: 2026-09-29
repo: RandomRingtone
status: pending
resume: "verder met randomringtone — HorseAPK v1.2.1 op Fold+Fairphone, beheer-login testen, E2E magic link, daarna v2.0.0 vrijgeven (DEBUG weg) + Spotify-sleutels"
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

## Vervolg (zelfde sessie, 02:00–02:20 UTC)
- **Gepubliceerd als DEBUG** (akkoord): HorseAPK `RandomRingtone-v2.0.0-Tina_Turner_The_Best-release.apk` (regex staat één
  codename-segment toe) + icthorse.nl in-app (`build.timestamp` via `build_info.php`). Vrijgave = DEBUG-marker weg.
- **Beheer-login kwam niet binnen:** (1) Fairphone hangt in HorseAPK aan `cglebbeek+residio@`, backend vroeg alleen
  `cglebbeek@` (Fold) → backend **v1.1.1** met `ADMIN_EMAILS` + keuzeveld "Goedkeuren op" (akkoord A). (2) HorseAPK-app:
  na ~290 s bleef de app "ontgrendeld" maar elk verzoek gaf "Ontgrendel HorseAPK eerst" (Fairphone vast), en een
  inlogverzoek bij open app gaf geen melding/scherm (Fold) → **HorseAPK v1.2.1-LimeWire** gepubliceerd + gewekt.
- 429 = HorseAPK-rem (5 startverzoeken per e-mail per 5 min), geen bug.
- Parallelle sessie (HetznerHealth) herstartte om 01:57:45 de HorseAPK-portal en voegde een dienstsleutel toe — niet aangeraakt.
- **RandomRingtone niet zichtbaar in HorseAPK (Fairphone):** `cglebbeek+residio@` heeft geen `ProjectGrant` voor
  RandomRingtone (Fold = beheerder, ziet alles). Toekennen via HorseAPK-beheer op de Fold (icoon AdminPanelSettings in de
  bovenbalk) — of op verzoek ("ken toe") server-side. **Open.**
- **v2.0.1 "Private_Dancer" (build 141):** "bouw voortgangsbar voor backup en restore met live (her)berekende eta" (akkoord
  WhatIf). `TransferMeter` (glijdend venster 8 s, per 64 KB), `TransferProgress` in alle 5 plekken; cloud-restore had
  geen balk. 6 tests, smoke groen, gepubliceerd als DEBUG (HorseAPK + icthorse.nl). Niet visueel getest met een echte
  backup (emulators hebben geen licentie).

