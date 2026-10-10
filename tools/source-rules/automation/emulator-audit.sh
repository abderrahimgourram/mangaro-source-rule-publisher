#!/usr/bin/env bash
set -euo pipefail
# Hard-coded serial for every ADB operation. Raw evidence remains private/ephemeral,
# never a workflow artifact and never printed. No source is installed on physical devices.
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [[ "${PUBLISHER_MODE:-}" == "staging" ]]; then
  adb -s emulator-5554 shell am instrument -w -e publisherEngine activate app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation > publisher-android-result.txt
  if ! grep -Eq 'failures=0' publisher-android-result.txt; then exit 1; fi
  adb -s emulator-5554 shell am force-stop app.manhwaar.reader.dev
  adb -s emulator-5554 shell am instrument -w -e publisherEngine restart app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation >> publisher-android-result.txt
  if grep -Eq 'ERROR|failures=[1-9]' publisher-android-result.txt; then exit 1; fi
  if [[ $(grep -Ec 'Source audit finished failures=0' publisher-android-result.txt) != 2 ]]; then exit 1; fi
else
  adb -s emulator-5554 shell am instrument -w -e publisherNative true app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation > publisher-android-result.txt
  if ! grep -Eq 'Source audit finished failures=0' publisher-android-result.txt; then exit 1; fi
  umask 077
  adb -s emulator-5554 exec-out run-as app.manhwaar.reader.dev cat files/publisher-native-evidence.json > native-evidence.json
  python3 - <<'PY_AUDIT'
import json
from pathlib import Path
index = json.loads(Path("tools/source-rules/production/public/index.json").read_text())["sources"]
report = json.loads(Path("native-evidence.json").read_text())
expected = {(int(sid), value["name"]) for sid, value in index.items()}
evidence = report["sources"]
actual = [(int(item["sourceId"]), item["name"]) for item in evidence]
if len(actual) != len(expected) or len(set(actual)) != len(actual) or set(actual) != expected:
    raise SystemExit("NATIVE_AUDIT_SOURCE_INVENTORY_MISMATCH")
if any(not item.get("steps") for item in evidence):
    raise SystemExit("NATIVE_AUDIT_MISSING_SOURCE_STEPS")
print(f"Publisher native inventory verified: {len(actual)} source IDs")
PY_AUDIT
fi
