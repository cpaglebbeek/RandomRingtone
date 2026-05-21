---
date: 2026-05-20
project: RandomRingtone
topic: Y2Mate protocol v2 (domain-migratie + nieuw auth-protocol)
resume: 2026-05-20-randomringtone-y2mate-protocol-v2
status: done
codename: Michael_Jackson
release: Beat_It
version: 1.9.17
build: 128
kleur: Geel (out-of-physical-box — protocol-wijziging)
---

# RandomRingtone — Y2Mate protocol v2 (fix #72)

## Probleem
YouTube-download faalt met snackbar "auth key niet gevonden". `y2mate.sc` is verhuisd naar `v4.y2mate.nu` en het auth-protocol is volledig gewijzigd. Onze HTML-scrape met `JSON.parse('[[…]]')`-regex matched niet meer op de nieuwe pagina-structuur → `fetchAuthKey()` returnt null.

## RCA (3 niveaus)
- **Functioneel:** YouTube-downloadflow stuk, gebruiker kan geen nieuwe tracks halen
- **Technisch:** Protocol-wijziging:
  - Oud: HTML scrape `JSON.parse('[[codes],reverse,[offsets],…,paramCharCode]')` → decodeer auth → `?<param>=<key>` query
  - Nieuw: `GET /api/v1/auth` → JSON `{geo, key, err}` → `GET /api/v1/init` met `Authorization: Bearer <key>` header
  - Timestamp-param: `t=<seconds>` → `_=<milliseconds>`
  - Download-suffix: `&s=3` → `&r=y2mate.nu`
- **Architectonisch:** Y2Mate is 3rd-party scraping target zonder SLA. Tweede protocol-wijziging (bug #59 was de eerste). Single point of failure — kandidaat voor multi-provider fallback of eigen yt-dlp relay.

## Diagnose-stappen
- Curl `https://y2mate.sc/` → **301 Moved Permanently** → `https://v4.y2mate.nu/`
- HTML van nieuw domein bevat geen `JSON.parse('[[…]]')` meer
- `/js/.../y2mate.js` toont nieuwe `authorization()` + `initialize()` functies
- Live curl-verify auth-endpoint: `HTTP 200 {"geo":"0","key":"<16chars>","err":0}`
- Live curl-verify init-endpoint met Bearer: `HTTP 200 {"convertURL":"https://ooccco.etacloud.org/…","error":"0"}`

## Wijzigingen
- `data/Y2MateClient.kt`:
  - Constanten: `SITE_URL=https://v4.y2mate.nu`, nieuw `AUTH_URL`, `INIT_URL`
  - `fetchAuthKey()` herschreven (JSON-call ipv HTML-scrape, geen `AuthResult.paramName` meer — alleen string)
  - `fetchConvertUrl(key)` gebruikt `Authorization: Bearer <key>` header
  - `convert/progress/download`: timestamp van seconden+`t=` naar milliseconds+`_=`
  - `downloadFile`: suffix `&r=y2mate.nu` ipv `&s=3`
  - Init-response `error` is string `"0"`, convert/progress-response `error` is int — beide afgehandeld
- `app/build.gradle.kts` + `version.json` → v1.9.17 / build 128 / Beat_It / BUILD_STATUS DEBUG
- `BUGLIST.md` entry #72 FIXED
- `build.timestamp` regel toegevoegd (DEBUG marker)

## Build/Deploy
- `./gradlew assembleRelease` — BUILD SUCCESSFUL 1m 24s
- APK → `~/Downloads/` + rsync → `icthorse.nl/RandomRing/Apk/`
- Commit `ec62d7f` gepusht naar `cpaglebbeek/RandomRingtone`

## Open items
- Validatie op echt toestel: download MP3 vanaf YouTube → moet doorlopen tot "Klaar"
- Mogelijke vervolgactie: Y2Mate protocol-fragility structureel oplossen (CONFLICTS R11 — multi-provider / eigen yt-dlp relay)
