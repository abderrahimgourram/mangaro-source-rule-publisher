#!/usr/bin/env bash
set -euo pipefail
# Hard-coded serial for every ADB operation. Raw evidence remains private/ephemeral,
# never a workflow artifact and never printed. No source is installed on physical devices.
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [[ "${PUBLISHER_MODE:-}" == "staging" ]]; then
  adb -s emulator-5554 shell am instrument -w -e publisherEngine activate app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation > publisher-android-result.txt
  if ! rg -q 'failures=0' publisher-android-result.txt; then exit 1; fi
  adb -s emulator-5554 shell am force-stop app.manhwaar.reader.dev
  adb -s emulator-5554 shell am instrument -w -e publisherEngine restart app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation >> publisher-android-result.txt
  if rg -q 'ERROR|failures=[1-9]' publisher-android-result.txt; then exit 1; fi
else
  adb -s emulator-5554 shell am instrument -w -e publisherNative true app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation > publisher-android-result.txt
  if ! rg -q 'sources=7' publisher-android-result.txt; then exit 1; fi
  umask 077
  adb -s emulator-5554 exec-out run-as app.manhwaar.reader.dev cat files/publisher-native-evidence.json > native-evidence.json
fi
