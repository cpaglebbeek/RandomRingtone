# Openstaande Acties — RandomRingtone

> Laatst bijgewerkt: 2026-09-29 — v2.2.0 gepubliceerd (DEBUG) + cloud-backup Fold geconsolideerd; v2.0.0 "Tina_Turner" / "The_Best" gebouwd (release-ondertekend) + backend v1.1.0 + backup_api v3 + HorseAPK v1.2.0 live

## Hoogste prio — afronden v2.0.0

- [ ] **E2E-goedkeuring** (Christian): magic link uit de testmail openen → *Licentie toevoegen* → code overtypen in HorseAPK
  (testaanvragen: `E2E-test (Claude)` = `e2e0000000000001`, `Emulator Test Claude` = `628d5ac1ace1c76e` op redroid)
- [x] APK v2.0.0 gepubliceerd 2026-09-29 als **DEBUG**: HorseAPK (`RandomRingtone-v2.0.0-Tina_Turner_The_Best-release.apk`, sha 3f3a24f5…) + icthorse.nl/RandomRing/Apk (in-app, alleen zichtbaar met 'Old build'-schuif)
- [x] v2.0.1 (141) gepubliceerd als DEBUG — voortgangsbalk + live ETA backup/restore
- [x] v2.2.0 (143) "Stevie_Wonder"/"Superstition" gepubliceerd als DEBUG 29-09 (HorseAPK `…Stevie_Wonder_Superstition-release.apk` + icthorse.nl in-app, sha acafa4c2…) — scanbereik/opschonen, restore-fixes, contact op naam, YouTube-art
- [x] v2.2.1 (144) "Sir_Duke" gepubliceerd als DEBUG 29-09 — BUG #88 (rechtenfout bij restore op Fairphone: contactenrecht/doelmappen vooraf uit backup, EACCES per bestand, bestanden vóór database)
- [x] v2.2.2 (145) "Signed_Sealed_Delivered" DEBUG 29-09 — BUG #89 update-check/download hing (icthorse.nl-timeouts): harde timeouts + fallback HC55-spiegel `/rrlog/apk/`; publiceren voortaan via `tools/publish-inapp.sh`
- [x] v2.2.3 (146) "Isnt_She_Lovely" DEBUG 29-09 — BUG #90 album art na restore + bestanden eerdere installatie vervangen via Android-toestemming
- [ ] **Fairphone/Fold (Christian):** v2.2.3 installeren → restore → toestemming 'wijzigen' toestaan → album art controleren → Backup → Herstellen uit cloud (slot 1 = geconsolideerd) → controleren: 5 actieve playlists (Joy/mam/marius/theo/shad), contacttoewijzingen, album art; playlist **shad is leeg** (inhoud nooit gelogd) → zelf vullen; thomas/algemeen/wa globaal staan UIT
- [ ] Na geslaagd herstel: archief HC55 `/srv/randomringtone-backup-archief/backups-20260929/` mag weg (geconsolideerde kopie blijft in `geconsolideerd-20260929/`)
- [ ] Bevinding (niet aangeraakt): `StorageManager.parseFileName` hasht `nameWithoutExtension`, `TrackIdResolver` hasht `name` (mét extensie) → scan-ids ≠ canonical ids (scan dedupt op bestandsnaam, dus geen dubbelingen, wel inconsistent)
- [ ] Na E2E + test op de Fold: DEBUG-marker weghalen in `build.timestamp` (lokaal + icthorse.nl) = vrijgave; testlicenties verwijderen via `/rrlog/beheer/`
- [ ] **Spotify Client ID + Secret** (developer.spotify.com → app `RandomRingtone`, Web API) → ClaudeSecrets
  `secrets/randomringtone/logger.env` (`SPOTIFY_CLIENT_ID`/`SPOTIFY_CLIENT_SECRET`) → `/root/randomringtone-logger/.env` →
  `systemctl restart randomringtone-logger`. Tot dan geeft de Spotify-bron 503 ("zet bron op WebView").
- [ ] Overige gelicenseerde toestellen (Marius, Joy, Joyce, Thomas, Theo) krijgen een e-mail/account via `/rrlog/beheer/`
  zodra bekend; ze claimen hun token automatisch bij de eerste start van v2.0.0.

## Architectuur v2.0.0 (samenvatting)

- **Licentie-activatie:** app → `POST /rrlog/license/request` → mail met magic link naar
  `cglebbeek+randomringtone.activation.request@gmail.com` → toekennen/afwijzen na **HorseAPK-goedkeuring** (PORTAL_API §7).
- **Beheer:** `https://horsecloud55.ddns.net/rrlog/beheer/` (login via HorseAPK, geen Basic Auth). Oud beheer op
  icthorse.nl/beheer/RandomRing had sinds juni geen `api.php` meer.
- **Account = e-mailadres** in `icthorse.nl/randomringtone/private/devices/<hash>.json` (403); publieke `lics/` ongewijzigd.
- **Toestel-token** (sha256 op de server) voor backup_api v3 + Spotify-bron; oude gedeelde backup-key alleen nog voor
  toestellen zonder token.
- **Restore op nieuwe telefoon:** zelfde e-mailadres ⇒ `backup_api ?action=offers` + `&source=` ⇒ aanbod bij eerste start.

## v1.11.0 feature-test geparkeerd

Selectieve backup/restore + per-track detail (uit oude marathon-sessie 2026-05-21) — pas toestel-validatie zinvol na v2.0.0 release want gebruiker draait nu v1.12.1 (Cronet, defect SpotMate).

## RandomRingtoneRelay — parked v0.1.0

Eigen Spotify→MP3 relay op HC55:3801 is **PARKED**. Service draait (`systemctl status rr-relay`, `/health` groen, `/resolve` werkt) maar `/convert` faalt door YouTube anti-bot + Hetzner IP-blacklist. **Geen actie nodig**, blijft idle. Revive alleen als Spotify Web API onvoldoende blijkt (zie `Meta_Master/.../RandomRingtoneRelay/STATUS.md`).

## CONFLICTS open (uit marathon)

- [ ] R9 — OkHttpClient singleton (LOW, performance)
- [ ] R10 — Pre-download bij playlist activering (LOW, offline)
- [ ] R12 — Y2Mate host dynamisch resolven (MED, 3e relocatie dit jaar)

## Bundel 3 SECURITY (apart WhatIf, destructief, uit marathon)

- [ ] `randomringtone-release.jks.backup` uit git-history
- [ ] Signing key roteren
- [ ] `build.gradle.kts` wachtwoord uit code naar `local.properties`
- [ ] Gedeelde backup-key (`IctHorseBackupClient.API_KEY`, staat in deze PUBLIEKE repo) uitzetten in `backup_api.php` zodra alle toestellen een toestel-token hebben (v2.0.0+); daarna key roteren

## HC55-cleanup (na v2.0.0 stable)

- [ ] `/root/randomringtone-logger.pre-git-backup/` verwijderen
- [ ] Legacy `server.js.bak` verwijderen
- [ ] Nested `randomringtone-logger/` duplicate dir opruimen of .gitignore
- [ ] `rr-relay.service` blijft draaien (parked) of stop+disable als revive niet voorzien

## Geheugen-vraag (uit marathon)

- [ ] "Dubbele items in download in bibliotheek" — verifiëren bij v2.0.0 toestel-test
