---
date: 2026-05-21
project: RandomRingtone
topic: Sanitycheck P1 Bundel 2 — logger-backend in nieuw git-repo
resume: 2026-05-21-randomringtone-logger-repo-bundel2
status: done
kleur: Geel — versie-controle backend, geen runtime-impact
---

# RandomRingtone — Logger-backend in nieuw repo (Bundel 2)

## Probleem (uit sanitycheck 2026-05-20)
Logger-backend op HC55 (`/root/randomringtone-logger/server.js`) draaide al sinds Apr 2026 als systemd service op poort 3800, maar bestond **alleen op productie** — geen git, geen backup, niet reproducerbaar.

## Acties
1. **SSH HC55** → inventarisatie + content-check (server.js, index.html, install.sh, package.json, .env)
2. **`.env`** bevat `API_KEY=<48-char hex>` + `PORT=3800` — niet in git
3. **Tar** relevante files (zonder node_modules, .env, server.js.bak, nested duplicate dir) naar `/Users/christian/Documents/Gemini_Projects/RandomRingtoneLogger/`
4. **Skeleton documentatie** aangemaakt:
   - `README.md` — project overview + endpoints + deploy quick-start
   - `DEPLOY.md` — eerste-install + update + service-management + .env-rotatie
   - `CLAUDE.md` — project-identiteit + relatie tot RandomRingtone + WhatIf protocol
   - `.gitignore` — node_modules, .env, *.log, .DS_Store, *.bak
   - `.env.example` — placeholder template
5. **GitHub repo** aangemaakt: `cpaglebbeek/RandomRingtoneLogger` (PRIVATE)
6. **Git push** initial commit naar main
7. **Meta_Master sync**:
   - `PROJECTS.json` — RandomRingtone-ecosysteem heeft nu 2 repos
   - `ECOSYSTEMS.md` — tabel-rij toegevoegd

## Niet in scope
- Runtime/code-wijzigingen aan server.js (behoud huidige werking)
- API_KEY-rotatie (zou Android-app side-update vereisen)
- Reverse-proxy config wijzigen (SHARED_INFRASTRUCTURE.md ongewijzigd, port 3800 stond er al)

## Volgende stappen (apart traject)
- **Bundel 3**: jks.backup uit RandomRingtone git-history + key-rotatie
- Logger backend feature-development gebeurt vanaf nu in eigen repo, deploy via `git pull && systemctl restart`
