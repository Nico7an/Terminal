#!/usr/bin/env bash
# Runs inside the emulator job of .github/workflows/smoke.yml.
set -euo pipefail

APP=com.nico7an.terminal
OUT=smoke-output
mkdir -p "$OUT"

# 1. Instrumentation tests (debug build) against the runner's sshd.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant $APP.debug android.permission.POST_NOTIFICATIONS || true
RUNNER=$APP.debug.test/androidx.test.runner.AndroidJUnitRunner

# The app is its own adb client: trust its key and expose adbd on TCP, as wireless debugging would.
adb shell am instrument -w -e class $APP.SmokeTest#exportAdbKey $RUNNER
adb pull "/sdcard/Android/data/$APP.debug/files/adb_key.pub" "$OUT/adb_key.pub"
adb root && sleep 3 && adb wait-for-device
adb shell 'cat >> /data/misc/adb/adb_keys' < "$OUT/adb_key.pub"
adb tcpip 5555 && sleep 3 && adb wait-for-device
echo "ro.adb.secure=$(adb shell getprop ro.adb.secure)"

adb shell am instrument -w -r \
  -e sshUser "$SSH_USER" -e sshKey "$SSH_KEY" -e sshHostKey "'$SSH_HOST_KEY'" -e adbPort 5555 \
  $RUNNER | tee "$OUT/instrument.txt"
adb pull "/sdcard/Android/data/$APP.debug/files/." "$OUT/" || true
grep -q "^OK (" "$OUT/instrument.txt"

# 2. The minified release build must start without crashing.
adb logcat -c
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n $APP/.ServerListActivity
sleep 6
adb exec-out screencap -p > "$OUT/4-release-start.png"
adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" || ! adb shell pidof $APP; then
  grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt" || true
  exit 1
fi
echo "Smoke test OK"
