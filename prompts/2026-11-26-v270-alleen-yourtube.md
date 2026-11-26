---
date: 2026-11-26
repo: RandomRingtone
status: pending
resume: "verder met randomringtone — v2.7.0 Chuck_Berry/Johnny_B_Goode (één bron: YourTube-scherm met zoeken + downloaden via eigen server); EERST vragen: v2.7.0 op Fold of Fairphone geïnstalleerd, gezocht en gedownload? Dan meten in app.log (YourTube Zoekresultaat, YourTube Download KLAAR), daarna STABLE met BUILD_STATUS=STABLE; verder open uit 25-11 (Fairphone-restore 23 bestanden, terugkerende toestellen, E2E magic link)"
---

# 2026-11-26 — v2.7.0 "Chuck_Berry" / "Johnny_B_Goode": alleen nog YourTube

## Vraag (gebruiker)
"verwijder spotify ui en youtube ui. maak alleen yourtube ui en backend" → WhatIf → "akkoord. 1 geen terugval. 2. anoniem".

## Gedaan
- App: Spotify-scherm, import, YouTube-WebView, treffercontrole, `YouTubeOnDevice` + NewPipeExtractor en `YouTubeMethod`
  verwijderd. Nieuw: `YourTubeScreen` (zoekveld, trefferlijst, download, geplakte link) en `data/YourTube.kt`.
- YourTube 0.2.2: dienstsleutel mag `/api/zoek` (anoniem). RandomRingtoneLogger 1.4.0: `GET /youtube/zoek?q=`.
- Architectuurontwerp: Spotify-onderdelen eruit, YourTube-scherm en -client erin.

## Gemeten
- App 43 unit-tests, logger 23, YourTube 17: alle groen. Live zoeken via YourTube met de dienstsleutel: 20 treffers.
- APK: versionCode 154, release-sleutel ongewijzigd, geen Spotify-, NewPipe- of m.youtube.com-verwijzing meer.

## Open
- Proef op een gelicenseerd toestel (zoeken, downloaden, geplakte link); de zoekroute van de logger is zonder
  toestel-token alleen op 401 gemeten. Daarna STABLE.
