---
date: 2026-05-21
project: RandomRingtone
topic: Y2Mate quickfix — Referer-validatie (v4 → v3)
resume: 2026-05-21-randomringtone-y2mate-referer-quickfix
status: done
codename: Whitney_Houston
release: How_Will_I_Know
version: 1.10.1
build: 133
kleur: Geel (groen +0.0.1) — protocol-detail-wijziging
---

# RandomRingtone — Y2Mate referer-quickfix

## Probleem
Sinds ergens vandaag (na v1.9.17 fix vanmiddag): YouTube-download faalt opnieuw "auth key niet gevonden". Y2Mate auth-endpoint geeft HTTP 403.

## RCA (3 niveaus)
- **F**: gebruiker kan geen YouTube-tracks meer downloaden
- **T**: Twee server-side wijzigingen:
  1. `v4.y2mate.nu` → 301 → `v3.y2mate.nu` (host weer verschoven)
  2. Auth-endpoint `eta.etacloud.org/api/v1/auth` heeft Referer-validatie aangezet — accepteert nu uitsluitend `Referer: https://v3.y2mate.nu/`
  Onze client stuurt `Referer: https://v4.y2mate.nu/` (constant `SITE_URL`) → server retourneert 403
- **A**: hostname hardcoded in client — 3e Y2Mate-relocatie dit jaar. Structurele oplossing = host dynamisch resolven via OkHttp redirect-follow

## Live diagnose
| Test | Resultaat |
|---|---|
| Auth + Referer v3 | HTTP 200 {"key":"...","err":0} |
| Auth + Referer v4 (onze code) | HTTP 403 |
| Auth zonder Referer | HTTP 403 |

## Fix
Eén regel in `Y2MateClient.kt:24`:
```kotlin
private const val SITE_URL = "https://v3.y2mate.nu"  // was v4
```

Endpoint constanten (AUTH_URL, INIT_URL op etacloud.org) ongewijzigd — protocol gelijk.

## Versie
v1.10.1 / Build 133 / Whitney_Houston / How_Will_I_Know / DEBUG

## Structurele opvolging (CONFLICTS R12, niet nu)
Host dynamisch resolven: OkHttp `followRedirects(true)` doet al de redirect intern,
maar onze code kent alleen statische SITE_URL. R12 = refactor naar:
1. GET homepage → leveer `response.request().url().toString()`
2. Gebruik effective host als referer voor auth/init/convert
→ vangt toekomstige host-verhuizingen automatisch
