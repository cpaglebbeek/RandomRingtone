#!/usr/bin/env bash
# Spiegel icthorse.nl/RandomRing/Apk (APK's + build.timestamp) naar HC55 /srv/randomringtone-apk,
# publiek via https://horsecloud55.ddns.net/rrlog/apk/ — fallback voor de in-app update (v2.2.2).
# Draai na elke in-app-publicatie (tools/publish-inapp.sh doet dat zelf).
set -euo pipefail
DEST=/srv/randomringtone-apk
SRC='icthorse:domains/icthorse.nl/public_html/RandomRing/Apk/'
rsync -a --chmod=D755,F644 --delete --include='*.apk' --include='build.timestamp' --exclude='*' "$SRC" "$DEST/"
n=$(ls "$DEST"/*.apk | wc -l)
echo "spiegel: $n APK's, build.timestamp $(wc -l < "$DEST/build.timestamp") regels, $(du -sh "$DEST" | cut -f1)"
