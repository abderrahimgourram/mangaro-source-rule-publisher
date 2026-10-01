#!/usr/bin/env python3
"""Avoid shell interpolation of secrets and restrict activation to the trusted workflow gate."""
import os
from pathlib import Path
import subprocess
import sys
args=[sys.executable,str(Path(__file__).with_name('run.py')),'--native-evidence','native-evidence.json','--state','.publisher-state','--report','publisher-report.json']
enabled=os.environ.get('AUTOPUBLISH_ENABLED')=='true'
requested=os.environ.get('REQUESTED_MODE','audit')
if enabled and (requested=='production' or os.environ.get('EVENT_NAME')=='schedule'):args.append('--publish')
try:
    sys.exit(subprocess.run(args,check=False).returncode)
finally:
    Path('native-evidence.json').unlink(missing_ok=True)
