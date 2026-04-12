#!/bin/bash
# mark_stable.sh — Markeer een build als stable en zet alle andere op DEBUG
#
# Gebruik: ./mark_stable.sh <buildnummer>
#
# Voorbeeld:
#   ./mark_stable.sh 97    # Markeer Build 97 als stable, rest → DEBUG
#
# Effect:
#   - De opgegeven build verliest zijn DEBUG marker (wordt stable)
#   - Alle andere builds zonder BUG/UPGRADE marker krijgen DEBUG
#   - build.timestamp wordt geupload naar icthorse.nl

set -e

REMOTE_HOST="icthorse"
REMOTE_PATH="~/domains/icthorse.nl/public_html/RandomRing/Apk"

BUILD="${1:-}"

if [ -z "$BUILD" ]; then
    echo "Gebruik: ./mark_stable.sh <buildnummer>"
    echo ""
    echo "Huidige versies:"
    grep -v "^#" build.timestamp | while IFS='|' read -r ver bld ts apk marker; do
        [ -z "$ver" ] && continue
        printf "  Build %s — v%s %s\n" "$bld" "$ver" "${marker:+[$marker]}"
    done
    exit 1
fi

if [ ! -f build.timestamp ]; then
    echo "build.timestamp niet gevonden!"
    exit 1
fi

echo "Build $BUILD markeren als stable..."

# Verwerk build.timestamp: markeer target als stable, rest als DEBUG
tmpfile=$(mktemp)
while IFS= read -r line; do
    # Skip commentaar en lege regels
    if [[ "$line" =~ ^# ]] || [ -z "$line" ]; then
        echo "$line" >> "$tmpfile"
        continue
    fi

    # Parse: versie|build|timestamp|apk|marker
    IFS='|' read -r ver bld ts apk marker <<< "$line"

    if [ "$bld" = "$BUILD" ]; then
        # Target build: verwijder marker (stable)
        echo "${ver}|${bld}|${ts}|${apk}" >> "$tmpfile"
        echo "  v${ver} (Build ${bld}): STABLE"
    elif [ "$marker" = "BUG" ] || [ "$marker" = "UPGRADE" ]; then
        # BUG/UPGRADE: laat ongewijzigd
        echo "$line" >> "$tmpfile"
        echo "  v${ver} (Build ${bld}): ${marker} (ongewijzigd)"
    else
        # Alles anders: zet op DEBUG
        echo "${ver}|${bld}|${ts}|${apk}|DEBUG" >> "$tmpfile"
        echo "  v${ver} (Build ${bld}): DEBUG"
    fi
done < build.timestamp

mv "$tmpfile" build.timestamp

# Upload naar server
echo ""
echo "Uploading build.timestamp..."
rsync -avz -e "ssh" build.timestamp "${REMOTE_HOST}:${REMOTE_PATH}/"

echo ""
echo "Build $BUILD is nu stable. Alle andere builds zijn DEBUG."
