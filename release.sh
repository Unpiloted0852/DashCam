#!/usr/bin/env bash
# Build a signed release APK and publish it as a GitHub Release, which the app's updater picks up.
#   ./release.sh 1.0.1 "What changed"
# Needs: gh logged in, and the release key settings in ~/.gradle/gradle.properties.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:?usage: ./release.sh X.Y.Z \"release notes\"}"
NOTES="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must look like 1.2.3"; exit 1; }
grep -q '^DASHCAM_STORE_FILE=' ~/.gradle/gradle.properties || { echo "release key not configured"; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "commit or stash changes first"; exit 1; }

echo "VERSION_NAME=$VERSION" > version.properties
./gradlew -q clean assembleRelease
APK="app/build/outputs/apk/release/app-release.apk"
OUT="app/build/DashCam-v$VERSION.apk"
cp "$APK" "$OUT"

git add version.properties
git commit -q -m "Release v$VERSION"
git tag "v$VERSION"
git push -q origin HEAD "v$VERSION"
gh release create "v$VERSION" "$OUT" --title "DashCam v$VERSION" --notes "${NOTES:-DashCam v$VERSION}"
echo "Published v$VERSION"
