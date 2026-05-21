# Openstaande Acties — RandomRingtone

> Laatst bijgewerkt: 2026-05-21 — parkering einde marathon-sessie (7 releases + 9 bugs + 2 nieuwe repos)

## Hoogste prio — toestel-validatie

- [ ] **Install v1.10.1 STABLE** (How_Will_I_Know) op toestel via gmail-link `https://icthorse.nl/RandomRing/Apk/RandomRingtone-v1.10.1-Whitney_Houston-How_Will_I_Know-release.apk` (21-05)
- [ ] **Migratie v8 verifiëren** bij eerste app-start na install — snackbar zou aantal geremapte + samengevoegde tracks moeten tonen; pre-backup in filesDir/pre_trackid_migration_backup/ aanwezig (21-05)
- [ ] **Functionele check** v1.10.1: YouTube-download / MP3 art / directory-move / restore-routing / Library-filter / scan-perf (21-05)

## v1.11.0 feature-test (DEBUG, niet stable)

- [ ] **Selectieve backup** test: kies map → Backup → dialog → vink alleen Downloads → controleer JSON+files in backup-map (21-05)
- [ ] **Selectieve restore** test: confirm → preview-dialog → vink alleen N playlists → controleer DB partial-update zonder others geraakt (21-05)
- [ ] **Per-track detail** test: expand Tones, vink 3/10 tracks aan → backup bevat alleen die 3 (21-05)
- [ ] Bij OK: `./mark_stable.sh 134` (21-05)

## Bundel 3 — P0 SECURITY (apart WhatIf, destructief)

- [ ] **`randomringtone-release.jks.backup` uit git-history** via `git filter-repo` + force-push (21-05)
- [ ] **Signing key roteren** — nieuwe randomringtone-release.jks genereren; alle bestaande APK-installs incompatibel met nieuwe key → user moet uninstallen + opnieuw installeren (21-05)
- [ ] **`build.gradle.kts` wachtwoord** uit code halen → `local.properties` (niet-gecommit) (21-05)
- [ ] **Apart WhatIf** sessie vereist — niet impulsief uitvoeren (21-05)

## HC55-cleanup (na v1.10.1 stable-test geslaagd)

- [ ] **Backup verwijderen:** `/root/randomringtone-logger.pre-git-backup/` (was veiligheidsnet bij git-init) (21-05)
- [ ] **Legacy `server.js.bak`** verwijderen (21-05)
- [ ] **Nested `randomringtone-logger/` duplicate dir** opruimen of `.gitignore` (21-05)

## CONFLICTS open

- [ ] **R9 — OkHttpClient singleton** (LOW) — performance refactor, geen user-facing impact (21-05)
- [ ] **R10 — Pre-download bij playlist activering** (LOW) — offline support feature (21-05)
- [ ] **R12 — Y2Mate host dynamisch resolven** via OkHttp redirect-follow (MEDIUM) — 3e Y2Mate-relocatie dit jaar; quickfix 1.10.1 = hardcoded v3, structurele fix volgt (21-05)

## Geheugen-vraag

- [ ] **"Dubbele items in download in bibliotheek"** — gemeld 20-05 maar gebruiker zei "vergeet voor nu". Bij toestel-test v1.10.1 verifiëren of dit nog aanwezig is (regressie van #24/#61/#66 of nieuwe variant) (21-05)
