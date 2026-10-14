#!/usr/bin/env bash
# Cold-starts the real debug app on a connected device and fails if it crashes.
#
# The instrumented tests run with HiltTestApplication, so they never execute
# DescenteCanyonApp.onCreate nor the production dependency graph: this check does.
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="${SMOKE_PACKAGE:-fr.descentecanyon.app.debug}"
ACTIVITY="fr.descentecanyon.app.ui.MainActivity"
# Covers the deferred startup work (search catalog and prediction warm-ups).
SETTLE_SECONDS="${SMOKE_SETTLE_SECONDS:-15}"

adb install -r "$APK"
adb logcat -c -b crash
adb shell am start -W -n "$PACKAGE/$ACTIVITY"
sleep "$SETTLE_SECONDS"

crashes="$(adb logcat -d -b crash)"
if ! adb shell pidof "$PACKAGE" > /dev/null || grep -q "$PACKAGE" <<< "$crashes"; then
  echo "Launch smoke test failed: $PACKAGE crashed or is not running." >&2
  echo "$crashes" >&2
  exit 1
fi
echo "Launch smoke test passed."
