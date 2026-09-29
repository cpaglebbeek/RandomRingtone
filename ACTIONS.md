# Openstaande Acties — RandomRingtone

> Laatst bijgewerkt: 2026-09-29 — v2.2.0 gebouwd; v2.0.0 "Tina_Turner" / "The_Best" gebouwd (release-ondertekend) + backend v1.1.0 + backup_api v3 + HorseAPK v1.2.0 live

## Hoogste prio — afronden v2.0.0

- [ ] **E2E-goedkeuring** (Christian): magic link uit de testmail openen → *Licentie toevoegen* → code overtypen in HorseAPK
  (testaanvragen: `E2E-test (Claude)` = `e2e0000000000001`, `Emulator Test Claude` = `628d5ac1ace1c76e` op redroid)
- [x] APK v2.0.0 gepubliceerd 2026-09-29 als **DEBUG**: HorseAPK (`RandomRingtone-v2.0.0-Tina_Turner_The_Best-release.apk`, sha 3f3a24f5…) + icthorse.nl/RandomRing/Apk (in-app, alleen zichtbaar met 'Old build'-schuif)
- [x] v2.0.1 (141) gepubliceerd als DEBUG — voortgangsbalk + live ETA backup/restore
- [ ] v2.2.0 (143) "Stevie_Wonder"/"Superstition" gebouwd + smoke groen (emulator-5554 + redroid) — scanbereik/opschonen, restore-fixes (mappen, settings, contact op naam, ringtones direct), YouTube-art; **nog publiceren** (build.timestamp + HorseAPK + icthorse.nl) en op de Fold testen met de geconsolideerde cloud-backup
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
