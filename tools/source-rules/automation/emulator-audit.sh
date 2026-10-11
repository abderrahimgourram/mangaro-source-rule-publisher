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
  python3 - <<'PY_NATIVE'
#!/usr/bin/env python3
"""Exact serial, bounded source selection; private native data never reaches stdout/artifacts."""
import json
import os
from pathlib import Path
import subprocess
import time

SERIAL = 'emulator-5554'
RUNNER = 'app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation'


def audit(plan):
    inventory = {s['id']: s for s in plan['inventory']}
    selected = plan['sources']
    if not selected or any(s not in inventory for s in selected):
        raise ValueError('INVALID_NATIVE_SELECTION')
    all_sources = set(selected) == set(inventory)
    batches = [None] if all_sources else [inventory[s]['name'] for s in selected]
    sources = []
    combined = {}
    started = time.monotonic()
    for name in batches:
        command = ['adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-e', 'publisherNative', 'true']
        if name is not None:
            command += ['-e', 'source', name]
        command.append(RUNNER)
        completed = subprocess.run(command, capture_output=True, timeout=1500 if name is None else 210)
        if completed.returncode or b'Source audit finished failures=0' not in completed.stdout:
            raise ValueError('NATIVE_EXECUTION_INCOMPLETE')
        data = subprocess.run(['adb', '-s', SERIAL, 'exec-out', 'run-as', 'app.manhwaar.reader.dev', 'cat',
                               'files/publisher-native-evidence.json'], capture_output=True, check=True, timeout=20)
        payload = json.loads(data.stdout)
        sources += payload['sources']
        if payload.get('crossSourceEvidence'):
            combined['crossSourceEvidence'] = payload['crossSourceEvidence']
    actual = [(str(s['sourceId']), s['name']) for s in sources]
    expected = {(sid, inventory[sid]['name']) for sid in selected}
    if len(actual) != len(expected) or set(actual) != expected:
        raise ValueError('NATIVE_INVENTORY_MISMATCH')
    os.umask(0o077)
    Path('native-evidence.json').write_text(json.dumps({'sources': sources, **combined}))
    print(json.dumps({'nativeSources': len(sources), 'elapsedSeconds': round(time.monotonic()-started, 3)}))


if __name__ == '__main__':
    try:
        audit(json.loads(Path('health-plan.json').read_text()))
    except Exception:
        print('NATIVE_CHECK_BLOCKED_NO_RAW_EVIDENCE_REPORTED')
        raise SystemExit(1)
PY_NATIVE
fi
