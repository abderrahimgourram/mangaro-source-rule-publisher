#!/usr/bin/env python3
"""Export only committed Android files to the dedicated private publisher repository."""
from pathlib import Path
import subprocess
import tarfile
import tempfile
import io
import sys
project=Path(__file__).resolve().parents[3]
repo='abderrahimgourram/mangaro-source-rule-publisher'
run=lambda args,**kwargs:subprocess.run(args,check=True,**kwargs)
if subprocess.check_output(['git','status','--porcelain'],cwd=project).strip():
    sys.exit('Commit changes before syncing the publisher snapshot.')
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip()
with tempfile.TemporaryDirectory(prefix='mangaro-private-publisher-') as tmp:
    checkout=Path(tmp)/'checkout'
    run(['gh','repo','clone',repo,str(checkout)],stdout=subprocess.DEVNULL)
    for field in ('user.name','user.email'):
        value=subprocess.check_output(['git','config','--get',field],cwd=project,text=True).strip()
        run(['git','config',field,value],cwd=checkout)
    # Keep repository history, replace its tracked snapshot with the current Android commit.
    tracked=subprocess.check_output(['git','ls-files','-z'],cwd=checkout).split(b'\0')
    for name in tracked:
        if name:(checkout/name.decode()).unlink(missing_ok=True)
    archive=subprocess.check_output(['git','archive','HEAD'],cwd=project)
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:source.extractall(checkout,filter='data')
    for workflow in (checkout/'.github/workflows').glob('*'):
        if workflow.name!='source-rule-publisher.yml':workflow.unlink()
    (checkout/'PUBLISHER_ANDROID_HEAD').write_text(head+'\n')
    run(['git','add','-A'],cwd=checkout)
    if subprocess.run(['git','diff','--cached','--quiet'],cwd=checkout).returncode:
        run(['git','commit','-m',f'Sync publisher production parser snapshot {head[:12]}'],cwd=checkout,stdout=subprocess.DEVNULL)
    run(['git','push','origin','HEAD:main'],cwd=checkout)
print('Private publisher snapshot synchronized:',head)
