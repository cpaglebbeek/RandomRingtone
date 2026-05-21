---
date: 2026-05-21
project: RandomRingtone
topic: Sanitycheck P1 Bundel 1 — server-side cleanup + D1 re-render
resume: 2026-05-21-randomringtone-server-cleanup-bundel1
status: done
kleur: Geel — server-side destructief (irreversible deletes)
---

# RandomRingtone — Server-side cleanup (Bundel 1)

## Doel
Sanitycheck-vondsten van 2026-05-20: drift + orphan files op deploy-target.
Server-side cleanup zonder code-wijziging in repo. Sessie-MD documenteert
wat verwijderd is.

## Acties (server-side)

### `icthorse.nl/RandomRing/Apk/`

**Verwijderd — gradle leftover:**
- `app/` (subdir met source kopie)
- `gradle/`, `.gradle/` (build cache)
- `gradlew`, `gradlew.bat`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`

**Verwijderd — docs die in repo horen:**
- `CLAUDE.md`, `BUGLIST.md`, `RELEASES.md`, `CONFLICTS.md`, `FLOW.md`, `FLOW.pdf`, `MARKETING.md`, `version.json`

**Verwijderd — scripts:**
- `mark_stable.sh`, `upload_release.sh`

**Verwijderd — sub-dirs die hier niet thuishoren:**
- `logger-server/` (live backend draait op HC55, niet hier)
- `releases/` (oude APK-kopieën)

**Verwijderd — SECURITY:**
- `randomringtone-release.jks.backup` — was publiek download-baar via
  `icthorse.nl/RandomRing/Apk/randomringtone-release.jks.backup`.
  Git-history rewrite (Bundel 3) blijft separaat traject.

**Verwijderd — VideoRing experiment (`failed_experiment_parked`):**
- 16× v2.0.0 t/m v2.5.2 APKs
- v2.2.1 `.dm` bestand

**Behouden:**
- Alle v1.x APKs (Apr 12) — laatste STABLE v1.9.15 + recent DEBUG 1.10.0
- `build.timestamp` — bron voor build_info.php
- `build_info.php` — live endpoint voor in-app updater

### `icthorse.nl/D1/RandomRingtone/`

**Verwijderd:**
- `architecture.html.bak2`, `dragon1.html.bak2`, `index.html.bak2`

**Re-rendered (uit huidige docs, pandoc + weasyprint):**
- 00_INDEX.pdf ← README.md
- 01_ARCHITECTURE.pdf ← CLAUDE.md (architecture-secties)
- 02_FLOW.pdf ← FLOW.md
- 03_CONFLICTS.pdf ← CONFLICTS.md (incl. R11 DONE)
- 04_RELEASES.pdf ← RELEASES.md (t/m build 132 v1.10.0)
- 05_BUGLIST.pdf ← BUGLIST.md (t/m bug #79)
- 06_CLAUDE.pdf ← CLAUDE.md (volledig)
- 07_MARKETING.pdf ← MARKETING.md

## Open vraag voor latere sessie
- v1.8.x debug-APKs op `/Apk/` (5 stuks ≈90MB): keep of weg? — laat staan tot beslissing.

## Niet in scope (Bundel 2/3)
- Bundel 2: logger-backend in nieuw git-repo (`RandomRingtoneLogger`)
- Bundel 3: jks.backup uit git-history (force-push, key-rotatie) — separaat WhatIf
