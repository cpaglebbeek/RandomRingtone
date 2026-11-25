---
date: 2026-11-25
repo: RandomRingtone
status: pending
resume: "verder met randomringtone — v2.3.2 Tiny_Dancer (DEBUG) LIVE 25-11 (hostnaam icthorsecloud.nl + geroteerde sleutels + bronnen v2.3.0). EERST vragen: v2.3.2 op de Fairphone geïnstalleerd? Dan meten in app.log (toestel 2a7d66dd, appVersion 2.3.2): komen logregels weer binnen, methodetest Spotify-embed/YouTube op toestel, restore zet de 23 geweigerde bestanden terug (fix v2.2.3). Daarna: vrijgave STABLE (DEBUG-marker weg) zodat de overige toestellen (Theo 1.8.9 e.a.) weer loggen; open uit 29-09: E2E magic link, ProjectGrant Fairphone, Spotify-sleutels, accounts overige toestellen, shad-playlist, archief backups-20260929."
---

# 2026-11-25 — v2.3.2 "Elton_John" / "Tiny_Dancer": hostnaam icthorsecloud.nl

## Vraag (gebruiker)
"verder met randomringtone" → WhatIf (v2.3.1 smoken + DEBUG publiceren) → "horsecloud55.ddnsn" → herziene WhatIf →
"horsecloud55.ddns.net wordt icthorsecloud. verwerk".

## Bevindingen (gemeten)
- `horsecloud55.ddns.net` heeft geen A-record meer (1.1.1.1 en 8.8.8.8: NOERROR, leeg). Alle geïnstalleerde apps
  (t/m v2.2.3) hebben die naam hard in de code → HC55 onbereikbaar: logging, licentie-aanvraag, update-spiegel.
- Laatste echte logregel in `/var/log/randomringtone/app.log`: 10-10-2026 (sleutelrotatie 13:16). Daarna niets.
- De klaarliggende v2.3.1-APK (10-10 13:16) was gebouwd vóór de hostnaam-commit `8bab9b2` (17:30): 9× ddns,
  0× icthorsecloud.nl → niet gepubliceerd.
- De 401's op `/rrlog/` in de nginx-log komen van de HetznerHealth-hartslag (127.0.0.1), niet van toestellen.
- Fairphone 2a7d66dd draaide op 10-10 nog v2.2.2; restore 30-09 geslaagd (98 tracks, 8 playlists) maar 23 bestanden
  niet teruggezet (geen schrijfrechten — opgelost in v2.2.3, dus ook in v2.3.2).

## Gedaan
- v2.3.2 (build 149), alleen versie-bump + herbouw; geen functionele codewijziging t.o.v. v2.3.1.
- APK nagemeten: 0× ddns, 9× icthorsecloud.nl, release-sleutel 82fac686…, 42/42 unit-tests.
- Smoke `/adbdebug` op verse AVD's (openclaw-box, run f4c5ea8b…): telefoon Android 16 — 0 FATAL, 0 ANR;
  `RemoteLogger gestart … "app":"2.3.2"` + 9 vervolgregels aangekomen op HC55 via icthorsecloud.nl/rrlog/log.
  Niet getest: methodetest Spotify/YouTube (vraagt bediening + licentie); auto-emulator gaf geen logregel.
- Gepubliceerd als DEBUG: HorseAPK (`…Elton_John_Tiny_Dancer-release.apk`) + icthorse.nl in-app + HC55-spiegel.
- `DEPENDENCY_MAP.md`: laatste ddns-vermelding vervangen.

## Open
Zie `ACTIONS.md` (bovenaan) en de resume-regel.
