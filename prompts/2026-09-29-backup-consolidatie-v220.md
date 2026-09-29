---
date: 2026-09-29
repo: RandomRingtone
status: pending
resume: "verder met randomringtone — v2.2.3 Isnt_She_Lovely (DEBUG) LIVE (art na restore + toestemming vervangen 23 oude bestanden). EERST vragen: restore op Fairphone 6 met v2.2.3 — toestemmingsvenster verschenen, alle album art zichtbaar? Log: Restore/Toestemming + Library/Scan. Daarna (2a7d66dd, Fold-backup) nu gelukt, contactenrecht toegestaan? Resultaat staat in app.log (Restore/Resultaat cloud). Daarna: shad-playlist vullen, archief HC55 backups-20260929 opruimen na bevestiging. Nog open uit 29-09: E2E magic link, ProjectGrant Fairphone, DEBUG-marker weg (vrijgave), Spotify-sleutels, accounts overige toestellen."
---

# 2026-09-29 — Backup-consolidatie Fold + v2.2.0 "Stevie_Wonder" / "Superstition"

## Vraag (gebruiker)
"onthoud alles voor later en doe nu: kijk naar de backups die er voor mijn apparaat staan en consolideer tot 1 backup.
verwijder de andere 2. zoom in op de bestanden en zorg dat alle ringtones en tones en youtube downloads ook zichtbare
album art hebben. herstel de playlists en de instellingen en toewijzingen aan contacten. zorg ervoor dat scannen van
library niet buiten de opgegeven folders gaat in instellingen en dat veranderen van mappen en opnieuw scannen dus ook
eerdere entries weg kan halen als die bestanden niet ook in de nieuwe opgegeven map staan" → WhatIf → "akkoord".
Keuze (a)/(b) niet beantwoord → (b) gekozen: 5 playlists van 29-09 actief, thomas/algemeen/wa globaal UIT.

## Bevindingen
- Toestel f7eb5c1c = Fold (SM-F956B). Hostinger had 2 slots: slot_1 (22-05, v1.10.1, onvolledig 82 dl/0 tones) +
  slot_2 (12-04, v1.8.9, 83 dl/12 tones). Unie = 98 bestanden; 6 dubbele namen verschilden alleen 128 B (marker) → mei.
- **Geen playlists/instellingen/contacten in beide backups.** Gereconstrueerd uit `/var/log/randomringtone/app.log`
  (Worker "Alle actieve playlists" + TrackResolver/CallState-regels). Laatste staat 29-09 01:52: Joy, mam, marius, theo
  (contact), shad (globaal). Track-inhoud uit april-logs: mam 6/6, marius 5/5, theo 4/4 compleet; Joy 10, thomas 5,
  algemeen 16/19. **shad: inhoud nooit gelogd → leeg.** Niet in backup: Clouseau – Daar Gaat Ze, Cat Snoring,
  a-ha "vocals split" en de tone-varianten Jump For Joy / Died In Your Arms (volledige download gebruikt).
- 25 bestanden zonder art (4 spotify, 8 youtube, 13 tones). Oorzaak app: `Mp3AlbumArt.write` sloeg bestanden met
  bestaande ID3v2-tag over (alle Y2Mate-bestanden).
- Scan las ook systeem-Download + device-wide MediaStore/marker en ruimde nooit op (68 tracks uit /Download).
- Cloud-restore zette elk localPath in de tones-map, negeerde settings.json/markerType, en contactplaylist zonder URI
  zou globaal werken.

## Opgeleverd
| Onderdeel | Resultaat |
|---|---|
| Hostinger `backups/f7eb5c1c7f465205/` | alleen `slot_1` (geconsolideerd, 103 bestanden, 748 MB, sha256 1-op-1 geverifieerd); slot_1+slot_2 oud verwijderd |
| Album art | 98/98 bestanden; tones erven van bron, Deezer/iTunes, 3× gegenereerd (Blunt Axe, YouTube.mp3, Happy Happy Joy Joy); markers ongewijzigd; `art_log.json` |
| JSON | 98 tracks (canonical ids), 8 playlists (`name:<contact>`-placeholder), 48 koppelingen, settings.json (RandomRing + RandomRing/Tones) |
| Archief HC55 | `/srv/randomringtone-backup-archief/backups-20260929/` (origineel) + `geconsolideerd-20260929/` |
| App v2.2.0 (143) | commit 5e5c8a0; 26/26 tests; smoke emulator-5554 + redroid; DEBUG gepubliceerd HorseAPK (2 toestellen gewekt) + icthorse.nl |

## Open
- Christian: v2.2.0 op de Fold → Herstellen uit cloud → controleren; shad vullen.
- Niet getest op toestel: herstel met echte data, opschoondialoog, contactkoppeling.

## Vervolg — "permissie rechten foutmelding bij restore" → v2.2.1 "Sir_Duke" (144)
- Meting (app.log): het was de **Fairphone 6** (`2a7d66dd`, v2.2.0) die de Fold-backup terugzette. `readContacts=false`
  ⇒ 5 contactplaylists uitgezet; restore stopte 0,5 s later (fout niet gelogd); daarna `missing_files` ⇒ database was al
  gewist. Oorzaken: (1) SetupCheck keek naar het huidige toestel i.p.v. de backup; (2) schrijven naar `Download/RandomRing`
  faalde — vrijwel zeker bestanden van een eerdere installatie (Fairphone draaide vannacht v1.8.9) ⇒ EACCES; exacte tekst
  niet ontvangen; (3) `clearAllTables` vóór de bestanden.
- Akkoord WhatIf → v2.2.1: `restorePlan` (cloud + SAF) ⇒ contacten/telefoon/doelmappen vooraf; per bestand
  zelfde grootte ⇒ overslaan, anders weghalen + schrijven, fout ⇒ overslaan + melden; cloud eerst bestanden dan database,
  0 geschreven ⇒ afbreken zonder DB-wijziging; resultaat naar RemoteLogger. BUG #88, 7 tests (33/33), smoke groen,
  commit 25bc546, gepubliceerd als DEBUG (HorseAPK, 2 toestellen gewekt + icthorse.nl).
- Open: Christian herstelt opnieuw op de Fairphone met v2.2.1 (contactenrecht toestaan).

## Vervolg — "check update loopt nu vast. daarvoor liep downloaden update halverwege vast" → v2.2.2 (145)
- Meting: Fairphone (thuis-IP 178.225.141.153) kreeg timeouts naar icthorse.nl (download 2.2.1 20:24, check 20:25,
  licentiecheck 20:25); logs naar HC55 liepen door. icthorse.nl zelf gezond vanaf HC55 en HorseBoat (APK 0,3 s).
  Oorzaak route thuisnetwerk ↔ Hostinger (afremmen of wifi; niet vast te stellen, Hostinger heeft geen toegangslog).
  App: alleen connect/read-timeout ⇒ druppelende verbinding hangt eindeloos.
- Akkoord WhatIf → HC55-spiegel `/srv/randomringtone-apk` via nginx `^~ /rrlog/apk/` (regressietest 207→208 probes,
  0 regressies, 1 verwacht nieuw), `tools/sync-apk-mirror.sh` + `tools/publish-inapp.sh`; app v2.2.2: callTimeout
  (check 10 s, download 90 s per bron), fallback icthorse.nl → HC55, Content-Length-controle, melding. BUG #89.
  Commit 90ece78, 33/33 tests, smoke groen, DEBUG gepubliceerd (HorseAPK + icthorse.nl + spiegel).
- Niet getest: de fallback zelf op een toestel (emulators hebben geen licentie ⇒ Instellingen/update niet bereikbaar);
  de spiegel is wel live gemeten (200, juiste inhoud).

## Vervolg — "debug: bij leeg begin: restore, geen album art. rescan: meer bestanden meteen na restoren en meer album art maar nog steeds niet allemaal." → v2.2.3 (146)
- Meting restore 20:35/20:37 (Fairphone, v2.2.2): contacten gekoppeld + 4 contactringtones gezet ✅; 75 bestanden ok
  (71 al aanwezig); **23 niet vervangen** = precies de bestanden waaraan op de server album art is toegevoegd (oude
  versie zonder cover van eerdere installatie, EACCES ook bij delete).
- RCA: (1) art-cache alleen voor tracks zonder id3Title — geconsolideerde backup had id3Title ⇒ nooit art na restore;
  enrich draaide bovendien alleen als markers ontbraken; (2) rescan voegt bestaande bestanden in de map toe die niet in
  de backup zaten ⇒ die krijgen wél art; (3) de 23 oude bestanden; (4) art-cache nooit ververst.
- Akkoord WhatIf → v2.2.3: `needsEnrich`, enrich na restore + bij elke refresh, geen albumArtPath uit backup,
  `ForeignFileWriter`/`RestoreFinisher` (MediaStore.createWriteRequest: één Android-toestemming ⇒ vervangen),
  LibraryRescan na restore, scan-logging. BUG #90, 38/38 tests, smoke groen, commit f8cc052, DEBUG gepubliceerd.
- Niet getest op toestel: toestemmingsvenster + vervangen (emulators zonder licentie). SAF-restore krijgt het venster ook
  (BackupScreen), maar parkeert zelf nog geen bestanden — alleen cloud-restore.
