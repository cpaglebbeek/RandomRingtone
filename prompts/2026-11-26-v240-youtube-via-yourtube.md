---
date: 2026-11-26
repo: RandomRingtone
status: done
resume: "verder met randomringtone — v2.4.0 Prince/Purple_Rain DEBUG gepubliceerd 26-11 (YouTube-audio via YourTube-motor: app → /rrlog/youtube → logger 1.2.0 → YourTube 0.2.1). EERST vragen: v2.4.0 op Fold of Fairphone geïnstalleerd en een YouTube-download + Spotify 'Volledig nummer' geprobeerd? Dan meten in app.log (appVersion 2.4.0) en in YourTube. Daarna STABLE met BUILD_STATUS=STABLE. Open: WhatIf Spotify-ombouw naar Sunnify-werkwijze (playlist/album via embed + spclient) wacht op akkoord; verder de open punten uit 25-11 (Fairphone-restore 23 bestanden, terugkerende toestellen, E2E magic link)."
---

# 2026-11-26 — v2.4.0 "Prince" / "Purple_Rain": YouTube-download via de YourTube-motor

## Vraag (gebruiker)
"zet versie op stable en accepteer aanvraag zfold8" → v2.3.2 STABLE + Z Fold 8 gelicenseerd (25-11).
"gebruik de engine achter yourtube voor download van youtube videos" → WhatIf → "akkoord".
Tweede akkoord (26-11) op de nginx-route, omdat de WhatIf ten onrechte "geen nginx-wijziging nodig" stelde.
Tijdens de afronding: "en met deze info spotify engine ombouwen: github.com/sunnypatell/sunnify-spotify-downloader"
→ nieuwe WhatIf, wacht op akkoord.

## Gedaan
- **YourTube 0.2.1** (commit a1c105e): dienstsleutels (`/etc/yourtube/diensten.json`, header `X-YT-Dienst`), alleen
  `/api/speel/`, 120 taken per uur; 17/17 tests.
- **Logger 1.2.0** (commit a3716fd): `POST/GET /youtube/:id` achter toestel-token, 30 nieuwe taken per uur per
  toestel; 22/22 tests. `PUBLIC_BASE` naar icthorsecloud.nl (magic link wees nog naar de ddns-naam).
- **nginx** (26-11): `location ^~ /rrlog/youtube/` zonder Basic Auth in `/etc/nginx/snippets/randomringtone-logger.conf`;
  regressietest 431 → 432 probes, 0 regressies; backup `/root/randomringtone-logger.conf.voor-youtube-20261126`.
  Zonder token antwoordt de backend 401 "Toestel niet geautoriseerd".
- **App v2.4.0** (build 150, commit 657985f): `YouTubeServer.kt` + `YouTubeDownload.audio` (server, terugval op
  toestel), `YouTubeMethod {SERVER, DEVICE}`, Y2Mate verwijderd, methodetest in Instellingen, `M4aMetadata` kan
  serverbestanden aan (moov vóór mdat: udta vervangen + `stco`/`co64` meeschuiven; PCM-md5 vóór en na gelijk). 45/45 tests.
- **Smoke** (/adbdebug, verse AVD's op de openclaw-box, run 7662ac71…): telefoon (API 36) en auto (API 35) melden
  zich in app.log met app 2.4.0, 47 regels, 0 fouten; AVD's na afloop verwijderd (nagemeten).
- **Gepubliceerd als DEBUG**: HorseAPK (`…-v2.4.0-Prince_Purple_Rain-release.apk`), icthorse.nl in-app en spiegel (200/200).

## Niet gemeten
- Een download vanuit de app zelf over de hele keten: een verse emulator heeft geen licentie of toestel-token.
  Wel gemeten: de motor met de dienstsleutel (taak klaar, medialink 200) en de publieke route tot aan de tokencontrole.
