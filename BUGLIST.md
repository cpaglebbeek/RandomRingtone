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
