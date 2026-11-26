---
date: 2026-11-26
repo: RandomRingtone
status: done
resume: "verder met randomringtone — v2.6.0 Johnny_Cash/Ring_of_Fire (alleen nog Spotify en YouTube, geen Deezer, geen converters, geen previews); wacht op proef op de Fold of Fairphone, daarna STABLE"
---

# 2026-11-26 — v2.6.0 "Johnny_Cash" / "Ring_of_Fire": alleen nog Spotify en YouTube

## Vraag (gebruiker)
1. "debug versie download wel maar installeert niet?" → gemeten in app.log: Fold (SM-F971B, Android 17, app 2.3.2)
   meldt `SetupCheck install:INFO`, twee keer `Download complete`, nooit `Installing APK`. Oorzaak: de schakelaar
   "APK installatie toestaan" / systeemtoestemming staat uit. Geen fout in v2.5.1.
2. "haal alle legacy manier van downloaden weg en laat alleen youtube en spotify over. test en leg me daarna uit hoe
   ik een track zoek, vind en download" → WhatIf → "akkoord." → tijdens de bouw: "geen deezer".

## Gedaan
- Verwijderd: Spotify-preview (embed + Deezer), preview via backend (ISRC), converter-websites (tweede WebView,
  DownloadManager-ontvanger, lijst in Instellingen), `SpotifyMethod`, `SpotifyConverter`, de `DeezerApi`-client,
  de on-the-fly Deezer-herstelstap in `TrackResolver`, `RingtoneManager.downloadPreview`, de hoes-terugval in de editor.
- Gebleven: Spotify-scherm (delen → direct downloaden, knop met bevestiging, import playlist/album) en YouTube-scherm
  (eigen server, terugval "Op toestel"). Model `DeezerTrack` en kolom `deezerTrackId` blijven.
- `BUILD_NUMBER` in BuildConfig van 148 naar 153.
- Architectuurontwerp: converters, Deezer-node en geplande Spotify Web API-preview eruit.

## Gemeten
- 51 unit-tests groen; live treffercontrole: 3 gevonden, 1 verzonnen nummer geweigerd.
- APK: versionCode 153, release-sleutel ongewijzigd, geen `api.deezer.com` of converter-adressen meer in de code.

## Open
- Proef op een gelicenseerd toestel (delen, import, YouTube-tab), daarna STABLE.
- Oud nummer zonder bestand wordt overgeslagen tot het opnieuw is gedownload.
- Eventueel: na het downloaden van een update zelf om de installatietoestemming vragen (niet gevraagd, geen akkoord).
