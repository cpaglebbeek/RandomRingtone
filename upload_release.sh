#!/bin/bash
# upload_release.sh — Upload APK en update build.timestamp op icthorse.nl
#
# Gebruik: ./upload_release.sh [marker]
#   marker: optioneel — BUG, DEBUG, UPGRADE
#
# Voorbeeld:
#   ./upload_release.sh          # normale release
#   ./upload_release.sh DEBUG    # debug-only release
#   ./upload_release.sh BUG      # markeer als buggy

set -e

# === Configuratie ===
REMOTE_HOST="icthorse"
REMOTE_PATH="~/domains/icthorse.nl/public_html/RandomRing/Apk"
# ===

MARKER="${1:-}"

# Lees versie-info uit build.gradle.kts
VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
BUILD=$(grep 'versionCode' app/build.gradle.kts | head -1 | grep -o '[0-9]*')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S")

# Zoek APK
APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" 2>/dev/null | head -1)
if [ -z "$APK_PATH" ]; then
    echo "Geen APK gevonden. Eerst builden!"
    exit 1
fi
APK_NAME=$(basename "$APK_PATH")

# Voeg toe aan lokale build.timestamp
ENTRY="${VERSION}|${BUILD}|${TIMESTAMP}|${APK_NAME}"
if [ -n "$MARKER" ]; then
    ENTRY="${ENTRY}|${MARKER}"
fi

echo "$ENTRY" >> build.timestamp
echo "build.timestamp bijgewerkt: $ENTRY"

# Upload APK naar icthorse.nl
echo "Uploading $APK_NAME..."
rsync -avz -e "ssh" "$APK_PATH" "${REMOTE_HOST}:${REMOTE_PATH}/"

# Upload build.timestamp
echo "Uploading build.timestamp..."
rsync -avz -e "ssh" build.timestamp "${REMOTE_HOST}:${REMOTE_PATH}/"

# Kopieer APK naar GitHub releases/
mkdir -p releases
cp "$APK_PATH" "releases/$APK_NAME"
echo "APK gekopieerd naar releases/$APK_NAME"

echo ""
echo "Release v${VERSION} (Build ${BUILD}) geupload naar icthorse.nl"
echo "  APK: ${REMOTE_PATH}/${APK_NAME}"
echo "  Timestamp: ${REMOTE_PATH}/build.timestamp"
