#!/usr/bin/env bash
# Builds a signed IPA of this branch and uploads it to TestFlight. Runs on the Mac.
# Usage: ENV_FILE=~/path/.env.testflight tools/ios-release/release.sh
# ENV_FILE must export ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH and KEYCHAIN_PASSWORD.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_HOME="$(cat ~/.local/jdk/HOME)" PATH="$(cat ~/.local/jdk/HOME)/bin:/opt/homebrew/bin:$PATH" LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
set -a; source "${ENV_FILE:?set ENV_FILE}" >/dev/null; set +a
# codesign over ssh needs the login keychain unlocked in this session
security unlock-keychain -p "$KEYCHAIN_PASSWORD" ~/Library/Keychains/login.keychain-db

cd "$ROOT/tools/ios-release" && fastlane profile
UUID="$(cat profile-uuid.txt)"

PROPS="$(mktemp -t robovm).properties"
sed "s/^app.build=.*/app.build=$(date +%y%m%d%H%M)/" robovm.properties > "$PROPS"
cd "$ROOT"
# --no-daemon: codesign must run in this session, where the keychain was just unlocked; a daemon from
# another ssh session fails with errSecInternalComponent
./gradlew --no-daemon --no-configuration-cache :ios:createIPA \
  -PiosSkipSigning=false \
  -PiosSignIdentity="Apple Distribution: kyle popp (W843447LPP)" \
  -PiosProvisioningProfile="$UUID" \
  -PiosRoboVmProperties="$PROPS" \
  -Probovm.arch=arm64 -Probovm.archs=arm64

IPA="$(find "$ROOT/ios/build" -name "*.ipa" -mmin -60 -print | head -1)"
[ -n "$IPA" ] || { echo "no IPA produced" >&2; exit 1; }
echo "IPA: $IPA"
cd "$ROOT/tools/ios-release" && fastlane upload ipa:"$IPA"
