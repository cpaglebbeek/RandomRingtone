---
date: 2026-06-01
project: RandomRingtone
session: SpotMate-debug-loop → Cronet-revert → Spotify Web API pivot
resume: spotify-webapi-v200
status: pending
---

# Sessie 2026-06-01 — SpotMate-debug-loop + Cronet-experiment + Spotify Web API pivot

## Wat is er gebeurd

Begin: bug-screenshot via ClaudeBug van Spotify-tab snackbar "Track info ophalen mislukt — probeer WebView converter" (bug #81). Doel: oplossen.

### Iteratie 1 — Diagnose-fix v1.11.1 "Greatest_Love_Of_All" (build 135)
- SpotMateDirectClient.kt: vervang `catch (_: Exception) { null }` door `RemoteLogger.e(...)` in fetchTrackInfo, fetchCsrfToken, getTrackData
- Build + ADB-install + reproductie → logcat: `CSRF GET niet ok` → bevestigd: spotmate.online/en1 retourneert HTTP 403
- Bug-detect confirmed: zelf curl vanaf Mac met UA-spoof = 403 ook → Cloudflare anti-bot

### Iteratie 2 — Eigen relay (parked) RandomRingtoneRelay v0.1.0
- Plan: Python `ThreadingHTTPServer` + spotdl + ffmpeg op HC55:3801
- Gebouwd, gedeployed, `/health` + `/resolve` werken; `/convert` faalt door:
  - spotdl YouTube anti-bot vanaf Hetzner-IP ("Sign in to confirm you're not a bot")
  - Alternatieve providers (piped/soundcloud/bandcamp) ook stuk
  - Cloudflare blokkeert ook directe SpotMate-flow vanaf HC55 (HTTP 403 zelfs met Chrome-headers — IP-blacklist)
- **PARKED** met STATUS.md + gecommit c6b3068 (lokaal, geen remote)

### Iteratie 3 — App-headers fix v1.11.2 "Saving_All_My_Love_For_You" (build 136)
- HAR-capture user's Chrome → spotmate.online flow geanalyseerd:
  - `/en1` GET → 200 met CSRF in HTML meta-tag (geen cookies meegestuurd!)
  - `/getTrackData` POST → 200 met `{spotify_url}` body + X-CSRF-Token header
  - `/convert` POST → 200 met `{urls}` body → returnt `{url: "rapid.dlapi.app/..."}` (signed URL)
  - GET rapid.dlapi.app → MP3 (Apache, geen Cloudflare)
- Bevinding: GEEN cookies — alleen headers
- Fix: SpotMateDirectClient.kt UA → Chrome desktop Mac 148, + sec-ch-ua/sec-fetch/Origin
- Gebouwd, geïnstalleerd, getest → `CSRF GET niet ok` opnieuw → bevestigd: niet headers

### Iteratie 4 — Cronet (Chromium TLS) v1.12.0 "Respect" + v1.12.1 "Think" (builds 137+138)
- Hypothese: JA3 TLS-fingerprint is de bot-detect-vector
- Cronet-embedded 119.6045 + cronet-okhttp adapter
- Application class `RingtoneApp.kt` + AndroidManifest registratie
- SpotMateDirectClient: OkHttpClient → CronetCallFactory met fallback
- v1.12.0 build → install → test → `CSRF GET niet ok` ondanks Cronet
- Bevinding: Cronet's eigen default UA `nl.icthorse.randomringtone/137 (..., Cronet/119.0.6045.31)` werd doorgegeven; Cloudflare doet **JA3 + UA-string** check
- v1.12.1 fix: `CronetEngine.Builder.setUserAgent(Chrome desktop UA)` → gebouwd + geïnstalleerd
- **Voordat v1.12.1 getest werd: user trigger "debug loop"** → stop met deze invalshoek

### Iteratie 5 — Cronet revert + Spotify Web API plan (v1.12.2 "Natural_Woman" marker)
- libs.versions.toml: cronet-embedded + cronet-okhttp verwijderd
- app/build.gradle.kts: cronet deps verwijderd
- AndroidManifest.xml: `android:name=".RingtoneApp"` verwijderd
- RingtoneApp.kt: deleted
- SpotMateDirectClient.kt: terug naar OkHttpClient (Chrome-headers van v1.11.2 behouden als hygiëne)
- v1.12.2 build 139 = revert-marker, GEEN APK gebouwd
- Plan v2.0.0 "Tina_Turner" / "The_Best": **Spotify Web API direct** met Client Credentials, gebruik 30s `preview_url` voor ringtone-use-case (fit-for-purpose)

## Wat staat er nu

- **Code:** Cronet weg, headers-fix v1.11.2 behouden in SpotMateDirectClient.kt
- **Version metadata:** v1.12.2 build 139 "Aretha_Franklin" / "Natural_Woman" (revert-marker, geen APK)
- **Op telefoon:** v1.12.1 build 138 (Cronet, defect SpotMate Direct)
- **Op icthorse.nl:** v1.11.1, v1.11.2, v1.12.0, v1.12.1 APK's geüpload (alle DEBUG)
- **HC55:3801 rr-relay:** draait idle (PARKED), `systemctl status rr-relay`
- **RandomRingtoneRelay repo:** lokaal v0.1.0 PARKED gecommit, geen GitHub remote

## Wat te doen volgende sessie (resume "spotify-webapi-v200")

1. **User levert** Spotify Client ID + Client Secret vanaf developer.spotify.com (zie ACTIONS.md)
2. **Claude bouwt** v2.0.0 met SpotifyWebApiClient.kt:
   - Client Credentials OAuth flow
   - `/v1/tracks/{id}` → metadata + `preview_url`
   - Token-cache TTL 1u
   - BuildConfig injection van secrets uit local.properties (NIET in git)
   - Vervang SpotMate-flow in SpotifyScreen.kt
   - Foutgeval: track zonder preview_url → WebView Converter fallback
3. **Build + ADB install + smoke-test** v2.0.0
4. **Bij groen:** BUGLIST #81 → FIXED, commit + push, mark stable optioneel

## Open beslispunten bij volgende sessie

- **Wat doen met SpotMate-code?** Verwijderen (clean) of behouden (wellicht ooit handig)? Voorstel: SpotMateDirectClient.kt verwijderen samen met dependency in SpotifyScreen.kt
- **Wat doen met RandomRingtoneRelay?** Blijft PARKED, of GitHub remote toevoegen voor toekomstige revive? Voorstel: nog niet pushen, blijft lokaal
- **rr-relay service op HC55?** Laten draaien (idle, geen kosten) of `systemctl disable`? Voorstel: laten draaien, `/health` is handig als bewijs dat service ooit gebouwd is
