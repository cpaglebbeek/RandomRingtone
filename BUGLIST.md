# Buglijst — RandomRingtone

| # | Beschrijving | Kleur | Gevonden | Gefixt | Status |
|---|-------------|-------|----------|--------|--------|
| 1 | Playlist-dialoog toont geen bestaande playlists (PlaylistScreen) | Groen | 0.5.0 | 0.5.0 | FIXED |
| 2 | Spotify "niet beschikbaar in deze browser" — WebView UA geblokkeerd | Groen | 0.6.0 | 0.6.1 | FIXED |
| 3 | Klembord Spotify URL niet gedetecteerd na Delen | Groen | 0.6.1 | 0.6.2 | FIXED |
| 4 | Converter-site redirect naar Spotify door cookies | Groen | 0.6.2 | 0.6.3 | FIXED |
| 5 | Converter-site redirect naar Spotify — cookies onvoldoende | Geel | 0.6.3 | 0.6.4 | FIXED |
| 6 | Converter WebView "niet compatible" — UA + Spotify embeds | Groen | 0.6.4 | 0.6.5 | FIXED |
| 7 | Editor: geen naam bij ringtone opslaan | Groen | 0.6.5 | 0.6.6 | FIXED |
| 8 | Editor: playlist-dialoog geen bestaande playlists | Groen | 0.6.5 | 0.6.6 | FIXED |
| 9 | Editor: crash bij opslaan ringtone (parentFile null/read-only) | Groen | 0.6.5 | 0.6.6 | FIXED |
| 10 | AudioTrimmer: "failed to add track to muxer" — MP3 niet ondersteund door MediaMuxer | Geel | 0.6.6 | 0.6.7 | FIXED |
| 11 | Bibliotheek opent editor i.p.v. bestandsoverzicht (restoreState) | Groen | 0.6.7 | 0.6.8 | FIXED |
| 12 | Ringtones tab: playlist-dialoog geen bestaande playlists | Groen | 0.6.7 | 0.6.8 | FIXED |
| 13 | Editor: twee losse flows (Ringtone/Playlist) — niet geconsolideerd | Groen | 0.6.8 | 0.6.9 | FIXED |
| 14 | Editor opent bij Spotify tab na eerdere bewerking | Groen | 0.6.9 | 0.6.10 | FIXED |
| 15 | EVERY_CALL: ringtone wisselt niet — READ_PHONE_STATE niet aangevraagd | Geel | 0.6.9 | 0.6.10 | FIXED |
| 16 | Klembord caching: FAB verschijnt voor oude/verwerkte Spotify URLs | Groen | 0.6.10 | 0.6.11 | FIXED |
| 17 | Spotify branch: Deezer tab nog zichtbaar (main APK i.p.v. spotify branch) | Groen | 0.6.11 | 0.6.12 | FIXED |
| 18 | Klembord niet gedetecteerd na Spotify Delen — LaunchedEffect niet op clipboard change | Geel | 0.6.11 | 0.6.13 | FIXED |
| 19 | Spotify download bestandsnaam "spotify_<timestamp>" i.p.v. track+artiest | Groen | 0.6.13 | 0.6.14 | FIXED |
| 20 | Scope (Globaal/Per contact) niet zichtbaar bij Random modus — dialoog niet scrollbaar | Groen | 0.7.0 | 0.7.1 | FIXED |
| 21 | Contact selectie: tekstveld i.p.v. zoekbare lijst met contacten | Groen | 0.1.0 | 0.7.1 | FIXED |
| 22 | Crash bij Playlists/Bibliotheek tab — Room DB schema mismatch na branch merge | Geel | 0.7.3 | 0.7.4 | FIXED |
| 23 | Contact selectie werkt niet — READ_CONTACTS permissie niet gevraagd, getContacts() op main thread, geen feedback | Geel | 0.7.3 | 0.7.8 | FIXED |
| 24 | Bibliotheek: Downloads en Ringtones tonen dezelfde bestanden | Groen | 0.7.5 | 0.7.7 | FIXED |
| 25 | Contacten laden mislukt: LOOKUP_KEY ontbreekt in cursor projection | Groen | 0.7.8 | 0.7.9 | FIXED |
| 26 | Getrimde ringtones verschijnen niet in Ringtones bibliotheek — opgeslagen in download dir i.p.v. ringtone dir | Groen | 0.7.9 | 0.7.10 | FIXED |
| 27 | Playlists niet effectief — random rotatie werkt niet doordat bestanden in tijdelijke download dir staan | Groen | 0.7.9 | 0.7.10 | FIXED |
| 28 | Spotify WebView: "werkt niet als je beveiligde inhoud blokkeert" — DRM/EME niet ingeschakeld | Groen | 0.7.10 | 0.7.11 | FIXED |
| 29 | Bibliotheek scan toont geen bestanden — File.listFiles() faalt op shared external storage (scoped storage) | Groen | 0.7.11 | 0.7.12 | FIXED |
| 30 | Scan mist .m4a bestanden — extensiefilter alleen .mp3, LibraryScreen toont ook .m4a | Groen | 0.7.12 | 0.7.13 | FIXED |
| 31 | Verwijderde bestanden niet her-importeerbaar bij rescan — orphan DB entry blokkeert import | Groen | 0.7.12 | 0.7.13 | FIXED |
| 32 | Library delete ruimt DB entry niet op — alleen fysiek bestand verwijderd, saved_tracks orphan blijft | Groen | 0.7.12 | 0.7.13 | FIXED |
| 33 | extractTrackId() hash inconsistentie — kan negatieve ID genereren, parseFileName() altijd positief | Groen | 0.7.12 | 0.7.13 | FIXED |
| 34 | Tweede download zelfde nummer geeft access denied — geen duplicate-detectie, overschrijf-dialoog ontbreekt | Groen | 0.7.13 | 0.7.14 | FIXED |
| 35 | SpotMate kan ander nummer downloaden dan Spotify toont — geen bevestiging van track metadata vóór download | Groen | 0.7.14 | 0.7.15 | FIXED |
| 36 | Bibliotheek toont niet alle gescande bestanden — Library disk-only, scan DB-only, geen brug ertussen | Geel | 0.7.15 | 0.7.16 | FIXED |
| 37 | Delete in bibliotheek wist fysiek bestand — geen keuze "uit bibliotheek" vs "van schijf", scan vindt niets meer na delete | Rood | 0.7.16 | 0.7.17 | FIXED |
| 38 | Scan "geen bestanden gevonden" zonder diagnostiek — file.delete() faalt stilletjes, geen zicht op gescande directories | Rood | 0.7.18 | 0.7.19 | FIXED |
| 39 | Scan diagnostiek snackbar te kort om te lezen — vervangen door dialoog met volledige pad + status info | Rood | 0.7.19 | 0.7.20 | FIXED |
| 40 | Scan vindt geen bestanden — scant alleen app-dirs, niet systeem Downloads waar DownloadManager bestanden neerzet | Rood | 0.7.20 | 0.7.21 | FIXED |
| 41 | Scan vindt geen bestanden op scoped storage — File.listFiles() faalt op publieke shared storage Android 11+, MediaStore fallback nodig | Rood | 0.7.21 | 0.7.22 | FIXED |
| 42 | MediaStore fallback vindt niets — READ_MEDIA_AUDIO permissie niet op runtime aangevraagd (Android 13+ vereist dit) | Rood | 0.7.22 | 0.7.23 | FIXED |
| 43 | AddTracksDialog toont alle mediabestanden — geen filter op bestaande lokale bestanden, MediaStore scan voegt te veel toe | Groen | 0.7.23 | 0.7.24 | FIXED |
| 44 | Per-contact ringtone werkt niet — resolveTrackFile() faalt op scoped storage + ringtone niet gezet bij activering | Geel | 0.7.24 | 0.7.25 | FIXED |
| 45 | applyCallPlaylist mislukt zonder diagnostiek — geen zicht op welke stap faalt (tracks, file, MediaStore, ContactsContract) | Oranje | 0.7.25 | 0.7.26 | FIXED |
| 46 | Verwijderd item uit bibliotheek verdwijnt niet — Library toonde disk-bestanden ongeacht DB status, na DB-delete verscheen item weer | Geel | 0.7.26 | 0.7.27 | FIXED |
| 47 | Playlist opslaan dialoog sluit niet — wachtte op DB operaties + snackbar vóór dialog close | Groen | 0.7.27 | 0.7.28 | FIXED |
| 48 | AddTracksDialog toont alle media — File.exists() check faalt op scoped storage, nu filter op non-blank localPath | Oranje | 0.7.27 | 0.7.28 | FIXED |
| 49 | Scan vindt ringtones niet — MediaStore query miste pad-patronen (RandomRingtone/_RandomRingtone) | Oranje | 0.7.27 | 0.7.28 | FIXED |
| 50 | Per-contact ringtone ContactsContract mislukt — WRITE_CONTACTS niet op runtime aangevraagd | Oranje | 0.7.27 | 0.7.28 | FIXED |
| 51 | Geen Single Point of Truth — Library/AddTracks/Scan gebruikten verschillende databronnen en filters | Rood | 0.7.28 | 0.7.29 | FIXED |
| 52 | Playlist refresh na opslaan — snackbar blokkeerde refresh, lijst ververste niet zichtbaar | Groen | 0.7.28 | 0.7.29 | FIXED |
| 53 | WRITE_CONTACTS nooit gevraagd — permissie nu expliciet bij contactselectie (READ+WRITE) en in Instellingen | Rood | 0.7.28 | 0.7.29 | FIXED |
| 54 | Crash bij opslaan per-contact playlist — permissie-launcher aanroep vanuit coroutine na dialog close | Rood | 0.7.29 | 0.7.30 | FIXED |
| 55 | Library toont media van hele telefoon — MediaStore scan download_% patroon te breed, matcht alles | Rood | 0.7.29 | 0.7.30 | FIXED |
| 56 | Ringtone nooit ingesteld — apply verwijderd uit save-flow (crash fix), maar geen ander pad voor nieuwe playlists (isActive=true default) | Rood | 0.7.30 | 0.7.31 | FIXED |
| 57 | EVERY_CALL: contact playlist overgeslagen bij meerdere actieve playlists — exception in loop stopt verwerking van resterende playlists | Geel | 0.7.34 | 0.7.35 | FIXED |
| 58 | Release APK corrupt/niet-installeerbaar — geen signingConfig op release buildType, APK unsigned | Groen | 1.5.10 | 1.5.11 | FIXED |
| 59 | YouTube downloads werken niet meer — Y2Mate init parameter gewijzigd van 'r' naar dynamisch (json[6]) | Groen | 1.6.4 | 1.7.2 | FIXED |
| 60 | Backup naar iCt Horse: meta toont 0 bestanden (hardcoded), .m4a overgeslagen, response resource leak | Groen | 1.6.4 | 1.7.3 | FIXED |
| 61 | Fresh install: scan voegt dubbelen toe — auto-restore trackId vs scan hashCode mismatch, geen localPath dedup | Groen | 1.6.4 | 1.7.4 | FIXED |
| 62 | Tabblad kan altijd gewisseld worden, ook als er een proces op het huidige tabblad bezig is | Groen | 1.6.4 | 1.7.6 | FIXED |
| 63 | Backup/restore toont geen voortgangsbalk met resterende ETA | Groen | 1.7.0 | 1.7.5 | FIXED |
| 64 | Getrimde .m4a bestanden missen embedded metadata (titel, artiest, cover, marker) + enrichAll overschrijft albumArtPath met null | Geel | 1.9.5 | 1.9.6 | FIXED |
| 65 | Playlist bevat spooktracks: verwijderde tracks blijven als orphans in playlist_tracks, geen cascade bij delete, scan ruimt orphans niet op | Geel | 1.9.8 | 1.9.9 | FIXED |
| 66 | Dubbele entries na scan: pad-hash vs naam-hash mismatch + /data/user/0/ vs /data/data/ symlink | Geel | 1.9.9 | 1.9.10 | FIXED |
| 67 | Geen album art na trim: extractAlbumArt probeert alleen origineel, faalt als werkbestand na Toepassen geen embedded art heeft | Geel | 1.9.9 | 1.9.10 | FIXED |
| 68 | Delete van schijf: verwijdert slechts één DB entry, duplicate met ander trackId blijft zichtbaar | Geel | 1.9.10 | 1.9.11 | FIXED |
| 69 | Album art niet bewaard bij Openen in editor voor Spotify/YouTube: geen DB pre-registratie, editor fallback vindt niets | Geel | 1.9.13 | 1.9.14 | FIXED |
| 70 | Trimmed bestand vervangt originele Spotify/YouTube entry: saveToDB() hergebruikte soms deezerTrackId waardoor origineel overschreven werd | Geel | 1.9.14 | 1.9.15 | FIXED |
| 71 | Album art bij MP3 alleen als cache + DB-pad — overleeft cache-wipe / share / backup niet; geen embedded picture | Geel | 1.9.15 | 1.9.16 | FIXED |
| 72 | YouTube-download faalt "auth key niet gevonden" — y2mate.sc verhuisd naar v4.y2mate.nu, protocol gewijzigd (nieuwe /auth endpoint + Bearer-token op /init + ms-timestamp `_=` ipv `t=`) | Geel | 1.9.16 | 1.9.17 | FIXED |
| 73 | Directory-move (Settings → locatie wijzigen → MOVE) updatet saved_tracks.localPath niet → orphan cleanup wist DB-records → playlist_tracks verliezen tracks | Rood | 1.9.17 | 1.9.18 | FIXED |
| 74 | Restore: alle localPaths gezet naar ringtoneDir, ook downloads → DB-disk mismatch, orphan cleanup wist downloads na restore | Rood | 1.9.17 | 1.9.18 | FIXED |
| 75 | Auto-restore (fresh install): localPath letterlijk overgenomen uit backup → wijst naar verdwenen app-internal paden → DB leeg na orphan cleanup | Rood | 1.9.17 | 1.9.18 | FIXED |
| 76 | Library-filter te restrictief — verbergt tracks met custom-naam in custom-locatie (path zonder "RandomRingtone" + niet-prefix-naam) | Geel | 1.9.17 | 1.9.19 | FIXED |
| 77 | Custom path zonder schrijfrechten (Android 11+ scoped storage): mkdirs() faalt stil, downloads landen daarna nergens — geen feedback aan gebruiker | Geel | 1.9.17 | 1.9.19 | FIXED |
| 78 | Scan blokkeerde lang bij grote audio-collecties — markerscan opende elk audiobestand zonder MIME-filter en zonder budget | Geel | 1.9.19 | 1.9.20 | FIXED |
| 79 | TrackId-strategie inconsistent (Deezer-id vs file.name.hashCode signed vs absolutePath.hashCode positief) → dupes in DB + mismatches bij scan/move | Oranje | sinds 0.x | 1.10.0 | FIXED (R11 — canonical helper + auto-migratie v8 + pre-migratie backup) |
| 80 | YouTube-download faalt opnieuw "auth key" — Y2Mate v4 → v3 host-verhuizing + Referer-validatie strenger (alleen v3 geaccepteerd, v4 = HTTP 403) | Geel | 1.10.0 | 1.10.1 | FIXED (SITE_URL v4 → v3) |
| 81 | Spotify direct download faalt "Track info ophalen mislukt — probeer WebView converter" — SpotMateDirectClient.fetchTrackInfo() retourneert null. Root cause via HAR + reproductie (2026-06-01): Cloudflare op spotmate.online doet TLS-fingerprint (JA3) **+ User-Agent string-match** — OkHttp+Conscrypt JA3 = HTTP 403 ongeacht headers; Cronet met Chrome JA3 maar default UA `Cronet/...` óók 403. Verified vanaf 3 IP's (Mac consumer, Z Fold 6 5G, Hetzner) en met volle Chrome-headers — Cronet+Chrome-UA niet meer getest (debug-loop). | Rood | 1.10.1+ | 2.0.0 | FIXED in code (2.0.0) — SpotMate verwijderd; nieuwe bron Spotify Web API → ISRC → Deezer-preview via backend (`/rrlog/spotify/track`, secret alleen server-side). RCA-aanvulling 2026-09-29: Spotify geeft nieuwe apps sinds 27-11-2024 géén `preview_url` meer, dus het oorspronkelijke v2.0.0-plan was onhaalbaar; ISRC blijft wel beschikbaar en Deezer-previews zijn vrij (live getest). **Functioneel pas na Spotify Client ID/Secret in de backend** (tot dan 503 + melding 'zet bron op WebView'). |
| 82 | Cloud-backup meldt 'geslaagd' terwijl audiobestanden ontbreken (backup 22-05: 82 van 124) — `backup_api.php` negeerde het resultaat van `move_uploaded_file`, limiet 50 MB, en `complete` controleerde niets | Oranje | 1.9.x | 2.0.0 | FIXED — server: 500 MB, upload-resultaat gecontroleerd (500 bij mislukken), `complete` telt audio na en zet `complete`/`missing` in backup_meta; app: onvolledige backup = mislukt met aantal ontbrekende bestanden |
| 83 | Remote logging (`/rrlog/log`) sinds 2026-05-02 dood — nginx-snippet `randomringtone-logger.conf` werd niet meer ge-include (vermoedelijk verloren bij herschrijven `sites-enabled/horsecloud`), logs vielen op de Basic Auth-401 van het vhost | Geel | — | server 2026-09-29 | FIXED — include hersteld in `sites-enabled/horsecloud` (met nginx-regressietest 199→206, 0 regressies) |
| 84 | Bibliotheek-scan ging buiten de ingestelde mappen (systeem-Downloads + device-brede MediaStore/marker-fallback) en ruimde nooit iets op — na mapwijziging bleven oude entries staan (68 tracks uit /Download op de Fold) | Geel | 1.9.x | 2.2.0 | FIXED — scan alleen download- + tones-map (`LibraryScope`, 4 tests); na scan/mapwijziging bevestigingsdialoog "N items staan niet (meer) in de ingestelde mappen"; record met zelfde bestandsnaam buiten de map wordt omgezet (behoudt playlist-koppeling) |
| 85 | Cloud-restore zette élk track-pad op de tones-map (downloads wezen naar niet-bestaand pad), negeerde markerType/id3/playedTrackIds en settings.json; cloud-backup nam die velden en settings.json niet mee. Ook SAF-/auto-restore pasten instellingen pas NA het bepalen van de doelmappen toe | Oranje | 1.x | 2.2.0 | FIXED — `inferSubdir` per track, alle velden, settings eerst (`RestoreSupport`) |
| 86 | Contactplaylist zonder (geldige) contactUri — bv. uit reconstructie — werkt als GLOBALE playlist (contactUri null = globaal) | Rood | 0.x | 2.2.0 | FIXED — restore koppelt op naam (`ContactMatcher`, 5 tests; `name:`-placeholder); niet gevonden ⇒ playlist uit, nooit globaal; `setContactRingtone`/`applyCallPlaylist` weigeren placeholder. Na restore worden actieve belplaylists direct toegepast |
| 87 | YouTube-downloads (en tones daaruit) kregen nooit album art — `Mp3AlbumArt.write` sloeg elk bestand met bestaande ID3v2-tag over, Y2Mate levert die altijd; overschrijf-pad haalde geen thumbnail | Geel | 1.x | 2.2.0 | FIXED — tag zonder cover wordt vervangen (titel/artiest behouden, ID3v1-marker blijft; 3 tests); thumbnail met mqdefault-fallback; editor: Deezer-cover alleen bij exacte artiest-match |
| 88 | Restore gaf rechten-foutmelding (Fairphone 6, restore van Fold-backup): controle vooraf keek naar het huidige toestel i.p.v. de backup ⇒ geen contactenrecht gevraagd (5 contactplaylists uit) en doelmap uit settings.json niet getest; schrijven naar `Download/RandomRing` faalde (vrijwel zeker bestanden van eerdere installatie ⇒ EACCES) ⇒ hele restore stopt NA `clearAllTables` (halve bibliotheek); fout niet gelogd | Rood | 2.0.0 | 2.2.1 | FIXED — `restorePlan` (cloud + SAF) leest playlists/settings uit de backup ⇒ SetupCheck vraagt contacten/telefoon en test de doelmappen vooraf; per bestand zelfde grootte ⇒ overslaan, anders weghalen + schrijven, fout ⇒ overslaan + melden; cloud: bestanden vóór database, 0 geschreven ⇒ afbreken zonder DB-wijziging; resultaat + fouten naar RemoteLogger (7 tests) |
| 89 | Update-check en APK-download "liepen vast" op de Fairphone (thuisnetwerk ↔ icthorse.nl timeouts; icthorse.nl zelf gezond vanaf HC55 + HorseBoat): alleen connect/read-timeout (read begint per pakket opnieuw ⇒ druppelende verbinding hangt eindeloos), geen tweede bron, onvolledige download niet gedetecteerd | Oranje | 1.x | 2.2.2 | FIXED — callTimeout (check 10 s, download 90 s per bron), fallback naar publieke HC55-spiegel `https://horsecloud55.ddns.net/rrlog/apk/` (nginx `^~ /rrlog/apk/`, `tools/sync-apk-mirror.sh`), Content-Length-controle, melding "icthorse.nl reageert niet — bezig via HC55"; publiceren via `tools/publish-inapp.sh` (werkt spiegel bij) |
| 90 | Na restore geen album art; na rescan meer bestanden + meer art maar niet alles (Fairphone 6) — (1) art-cache alleen gemaakt voor tracks zonder id3Title (teruggezette tracks hadden die) en enrich alleen bij ontbrekende markers; (2) 23 bestanden met nieuwe cover konden niet vervangen worden (eigendom eerdere installatie, EACCES ook bij delete); (3) art-cache nooit ververst; (4) extra bestanden = bestaande bestanden in de ingestelde map die niet in de backup zaten (scan loggde niets) | Oranje | 1.x | 2.2.3 | FIXED — `Mp3TagReader.needsEnrich` (null/verdwenen art ⇒ lezen, "" = gecontroleerd), enrich na elke restore en bij elke bibliotheek-refresh, restore neemt geen albumArtPath over, art-cache altijd verversen; `ForeignFileWriter` + `RestoreFinisher`: nieuwe inhoud in cache, één Android-toestemming (MediaStore.createWriteRequest), dan vervangen + art opnieuw; na restore LibraryRescan; scan logt added/relinked/stale (5 tests) |

## Features

| # | Feature | Versie |
|---|---------|--------|
| F1 | Selectieve backup/restore — per categorie (Downloads/Tones/YouTube/Playlists/Instellingen) + per-track detail | 1.11.0 |
