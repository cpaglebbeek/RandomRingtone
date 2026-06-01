# Openstaande Acties — RandomRingtone

> Laatst bijgewerkt: 2026-06-01 — over-en-uit na SpotMate-debug-loop + Cronet-revert; volgende sessie start met v2.0.0 Spotify Web API

## Hoogste prio — v2.0.0 "Tina_Turner" / "The_Best" (Spotify Web API)

Bug #81 SpotMate Direct API is **structureel onhaalbaar** via OkHttp én via Cronet. Cloudflare doet JA3 TLS-fingerprint + UA-string-match. **Nieuwe richting:** Spotify Web API direct (Client Credentials → 30s preview_url).

### Voorbereiding (gebruiker)

- [ ] **Spotify Developer Dashboard** openen: https://developer.spotify.com/dashboard → Log in → Accept Developer Terms → Create app `RandomRingtone` met:
  - App description: vrij
  - Website: leeg of `https://icthorse.nl`
  - Redirect URI: `https://localhost/callback` (verplicht maar ongebruikt)
  - API/SDKs: **Web API**
- [ ] **Settings** → kopieer **Client ID** + klik "View client secret" → kopieer **Client secret**
- [ ] Plak beide aan Claude (NIET in chat in PUBLIC channels — repo is PUBLIC AGPL)

### Implementatie (Claude, nieuwe sessie)

- [ ] `local.properties` (in `.gitignore`): `SPOTIFY_CLIENT_ID=...` + `SPOTIFY_CLIENT_SECRET=...`
- [ ] `app/build.gradle.kts`: BuildConfig-injection van Client ID + Secret uit local.properties
- [ ] `SpotifyWebApiClient.kt`: Client Credentials OAuth flow → `/v1/tracks/{id}` → metadata + `preview_url`
- [ ] Token-cache met expiry-handling (TTL ~1 uur)
- [ ] Vervang `SpotMateDirectClient` in `SpotifyScreen.kt` (verwijder Direct-API knop voor SpotMate, voeg toe voor Web API)
- [ ] Foutgeval: tracks zonder `preview_url` (~5-10%) → snackbar "geen Spotify preview beschikbaar — gebruik WebView converter"
- [ ] Versie bump v2.0.0 build 140 "Tina_Turner" / "The_Best" (oranje +1.0.0 architectonisch nieuwe download-bron, ook al is preview-only)
- [ ] BUGLIST #81 → FIXED met RCA Spotify Web API
- [ ] Build + install via ADB + smoke-test
- [ ] Commit + push

### Scope-bewustzijn (vooraf bespreken indien onduidelijk)

- Spotify Web API geeft **alleen 30s preview MP3** (geen volle track)
- Voor ringtone-use-case is dat fit-for-purpose (ringtones typisch 5-30s)
- Voor wie een volle track wilde: WebView Converter blijft als secundair pad
- ~5-10% van tracks heeft geen `preview_url` (zeldzame edge case)

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

## HC55-cleanup (na v2.0.0 stable)

- [ ] `/root/randomringtone-logger.pre-git-backup/` verwijderen
- [ ] Legacy `server.js.bak` verwijderen
- [ ] Nested `randomringtone-logger/` duplicate dir opruimen of .gitignore
- [ ] `rr-relay.service` blijft draaien (parked) of stop+disable als revive niet voorzien

## Geheugen-vraag (uit marathon)

- [ ] "Dubbele items in download in bibliotheek" — verifiëren bij v2.0.0 toestel-test
