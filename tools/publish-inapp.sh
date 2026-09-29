#!/usr/bin/env bash
# In-app publicatie: APK + build.timestamp-regel naar icthorse.nl/RandomRing/Apk, dan spiegel op HC55 bijwerken.
# Gebruik: tools/publish-inapp.sh <apk> <versie> <build> <codename> <releaseName> [DEBUG|""]
set -euo pipefail
APK=$1; VER=$2; BUILD=$3; CODE=$4; REL=$5; STATUS=${6-DEBUG}
cd "$(dirname "$0")/.."
F=$(basename "$APK")
L="$VER|$BUILD|$(date +%s)|$F|$STATUS|$CODE|$REL"
scp -q "$APK" "icthorse:domains/icthorse.nl/public_html/RandomRing/Apk/$F"
ssh icthorse "cd domains/icthorse.nl/public_html/RandomRing/Apk && echo '$L' >> build.timestamp"
echo "$L" >> build.timestamp
tools/sync-apk-mirror.sh
curl -fsS -o /dev/null -w "icthorse %{http_code}\n" "https://icthorse.nl/RandomRing/Apk/$F"
curl -fsS -o /dev/null -w "spiegel  %{http_code}\n" "https://horsecloud55.ddns.net/rrlog/apk/$F"
