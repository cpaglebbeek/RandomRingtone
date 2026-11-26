---
date: 2026-11-26
repo: RandomRingtone
status: pending
resume: "verder met randomringtone — v2.5.1 David_Bowie/Starman DEBUG gepubliceerd 26-11 (delen van een nummer start direct zoeken + download, standaard volledig nummer; v2.5.0: Spotify playlist/album-import volgens Sunnify-werkwijze, strikte YouTube-treffer, album-tags; bovenop v2.4.0 YouTube via YourTube-motor). EERST vragen: v2.5.1 op Fold of Fairphone geïnstalleerd, een nummer gedeeld (direct download) en een album + een playlist geïmporteerd? Dan meten in app.log (SpotifyImport start/klaar, SpotifyCollection Lijst, YouTubeServer Download KLAAR) en in YourTube (dienst randomringtone). Open: hoeveel weigert de strikte controle (eventueel YouTube Music als zoekbron), daarna STABLE met BUILD_STATUS=STABLE; verder open uit 25-11 (Fairphone-restore 23 bestanden, terugkerende toestellen, E2E magic link)."
---

# 2026-11-26 — v2.5.0 "David_Bowie" / "Heroes": Spotify-import volgens de Sunnify-werkwijze

## Vraag (gebruiker)
1. "en met deze info spotify engine ombouwen: github.com/sunnypatell/sunnify-spotify-downloader" → WhatIf.
2. "wat doet deze: github.com/Shabinder/SpotiFlyer" → uitleg (officiële API met sleutels + converter-site, niet meer
   onderhouden, GPL-3.0); advies: niet overnemen.
3. "gebruik deze voor spotify: github.com/zotify-dev/zotify" → **geweigerd**: ontsleutelt Spotify's eigen streams met een
   account (omzeilen van een technische beschermingsmaatregel, accountrisico, verspreiding naar meerdere toestellen).
4. "wat zegt spotify zelf over regels als ik het aantal seconden en/of de kwaliteit beperk?" → voorwaarden nagelezen
   (Developer Terms v10 en Developer Policy van 15-05-2025, gebruikersvoorwaarden 04-09-2026, User Guidelines): geen
   uitzondering op duur of kwaliteit; previews alleen streamen ter promotie; ringtones alleen met schriftelijke
   toestemming; scrapen en combineren met een andere dienst verboden.
5. "ok gebruik dan beste legale versie…" → WhatIf officiële API met zoekveld (sleutels nodig).
6. Sunnify-link opnieuw → keuzevraag → Christian koos **"Sunnify-werkwijze"** (= akkoord op WhatIf 1).

## Gedaan
- **App v2.5.0** (build 151, commit 04f08a2):
  - `data/SpotifyCollection.kt`: `parseLink`, `parseEmbed` (embed-pagina `__NEXT_DATA__` → trackList), `parsePlaylistOrder`
    + `fetch` (playlists ≥ 100: volgorde via `spclient.wg.spotify.com/playlist/v2/playlist/<id>` met het anonieme token
    uit de pagina; ontbrekende nummers per stuk uit `/embed/track/<id>`, 4 tegelijk, maximaal 400 extra).
  - `data/YouTubeMatch.kt`: zuivere treffercontrole; `YouTubeOnDevice.strictMatch` (tweede zoekopdracht zonder haakjestekst).
  - `M4aMetadata.write`: `©alb` + `trkn`; doorgegeven via `YouTubeDownload.audio`.
  - `SpotifyPreviewClient.downloadFullInfo` (losse nummers: eerst strikt, dan de ruimere keuze; import: alleen strikt).
  - `ui/screens/SpotifyImportDialog.kt` + knop in `SpotifyScreen` (URL in de WebView of link op het klembord).
  - Methodetest "Volledig nummer" test nu ook de lijstophaler.
- **Logger 1.3.0** (commit 39902fd, uitgerold): 150 nieuwe taken per uur per toestel. **YourTube**: dienstgrens 300
  (`/etc/yourtube/diensten.json` + ClaudeSecrets), dienst herstart.
- **Gemeten:** 51/51 unit-tests; echte pagina's: playlist 50 nummers, album 18; playlistdienst gaf 200 resp. 150 nummers
  bij embed-lijsten van 100; echt m4a-bestand: album/volgnummer aanwezig, PCM-md5 gelijk; treffercontrole live:
  Toto/Africa, Queen/Bohemian Rhapsody (Remastered) en Pitbull/Global Warming gevonden, verzonnen nummer geweigerd.
- **Smoke** (/adbdebug run 8ac04ac1…, verse AVD's op de openclaw-box): telefoon + auto loggen als 2.5.0, 44 regels,
  0 fouten; AVD's verwijderd.
- **Gepubliceerd als DEBUG**: HorseAPK (`…-v2.5.0-David_Bowie_Heroes-release.apk`), icthorse.nl in-app, spiegel (200/200).

## Niet gemeten
- Het importvenster zelf en een download over de hele keten: vraagt een gelicenseerd toestel (emulator heeft geen
  licentie of toestel-token).
- Hoeveel procent van een echte playlist de strikte controle weigert.

## Vervolg — v2.5.1 "Starman" (zelfde dag)
Christian: "ik ben gewend dat ik per nummer dat ik vind delen kies en dat dan het nummer wordt gezocht en gedownload"
→ WhatIf → "akkoord". Gebouwd (commit 8c5c88f): de klembordluisteraar start bij een gedeelde tracklink direct
`startDownload` (alleen bij de directe methoden, niet bij de converter); knop + bevestigingsvenster blijven voor het
bladeren naar een nummerpagina; standaard `SpotifyMethod.FULL`. 52/52 tests. Smoke (run 560e5ba0…): telefoon-AVD logt
als 2.5.1, 22 regels, 0 fouten; de auto-AVD gaf geen logregel (oorzaak niet onderzocht). DEBUG gepubliceerd
(HorseAPK, icthorse.nl, spiegel 200/200). Niet gemeten: de deel-route zelf (vraagt gelicenseerd toestel).
