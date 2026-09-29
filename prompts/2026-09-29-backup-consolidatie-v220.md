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
