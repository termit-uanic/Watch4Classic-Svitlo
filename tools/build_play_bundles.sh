#!/usr/bin/env bash
# Builds the two Android App Bundles for Google Play (phone and watch), signed with the upload key.
#
#   tools/build_play_bundles.sh BUILD_NO
#
# BUILD_NO is part of the version code; raise it for every upload to Google Play (the first one is 1).
# The key store is ~/.ssh/GooglePlay/upload-keystore.jks unless WATTLO_UPLOAD_STORE says otherwise. The passwords
# are asked here (nothing is echoed, nothing is written to a file) and handed to Gradle through the environment.
# The bundles are copied to ~/wattlo-play/ (outside the repository).
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_NO="${1:?usage: tools/build_play_bundles.sh BUILD_NO}"
STORE="${WATTLO_UPLOAD_STORE:-$HOME/.ssh/GooglePlay/upload-keystore.jks}"
[ -f "$STORE" ] || { echo "no key store: $STORE" >&2; exit 1; }

read -r -p "Key alias [upload]: " ALIAS
ALIAS="${ALIAS:-upload}"
read -r -s -p "Key store password: " STORE_PASSWORD; echo
read -r -s -p "Key password (Enter: the same): " KEY_PASSWORD; echo
KEY_PASSWORD="${KEY_PASSWORD:-$STORE_PASSWORD}"

export WATTLO_UPLOAD_STORE="$STORE"
export WATTLO_UPLOAD_KEY_ALIAS="$ALIAS"
export WATTLO_UPLOAD_STORE_PASSWORD="$STORE_PASSWORD"
export WATTLO_UPLOAD_KEY_PASSWORD="$KEY_PASSWORD"

./gradlew :phone:bundleRelease :app:bundleRelease -PbuildNo="$BUILD_NO"

VERSION="$(grep '^appVersion=' gradle.properties | cut -d= -f2)$(grep '^versionSuffix=' gradle.properties | cut -d= -f2)"
OUT="$HOME/wattlo-play"
mkdir -p "$OUT"
cp phone/build/outputs/bundle/release/phone-release.aab "$OUT/wattlo-phone-$VERSION-$BUILD_NO.aab"
cp app/build/outputs/bundle/release/app-release.aab "$OUT/wattlo-watch-$VERSION-$BUILD_NO.aab"

echo
echo "Signed with:"
for f in "$OUT"/wattlo-*-"$VERSION-$BUILD_NO".aab; do
    echo "  $f"
    keytool -printcert -jarfile "$f" 2>/dev/null | grep -E "SHA256|Owner" | sed 's/^/    /'
done
echo "The SHA256 above has to be the same for both and equal to the upload certificate in the Play Console."
