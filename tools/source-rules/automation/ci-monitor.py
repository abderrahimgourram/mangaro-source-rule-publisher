#!/usr/bin/env python3
"""Avoid shell interpolation of secrets and restrict activation to the trusted workflow gate."""
import os
from pathlib import Path
import subprocess
import sys
args=[sys.executable,str(Path(__file__).with_name('run.py')),'--native-evidence','native-evidence.json','--state','.publisher-state','--report','publisher-report.json']
requested=os.environ.get('REQUESTED_MODE','audit')
if requested=='production':
    print('PRODUCTION_PUBLICATION_DISABLED_REQUIRES_EXPLICIT_REVIEW')
    sys.exit(1)
# Monitoring never acquires publication authority, even if a repository variable is toggled.
try:
    sys.exit(subprocess.run(args,check=False).returncode)
finally:
    Path('native-evidence.json').unlink(missing_ok=True)
