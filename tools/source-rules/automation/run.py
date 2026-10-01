#!/usr/bin/env python3
"""All-source supervised publisher. Raw native evidence is input only, never a report artifact."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import fcntl
import json
import os
from pathlib import Path
import sys
import threading
import time
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent))
from automation.core import Http, Unsafe, Transient, verify, canonical, seal_state, open_state
from automation.controller import Controller
from automation.publisher import VercelPublisher

PROJECT=HERE.parents[2]

def configuration():
    props=dict(l.split('=',1) for l in (PROJECT/'gradle.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
    inventory=json.loads((HERE.parent/'production/public/index.json').read_text())['sources']
    return props, [{'sourceId':int(k),'name':v['name']} for k,v in inventory.items()]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-evidence',type=Path,required=True)
    parser.add_argument('--state',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--publish',action='store_true')
    parser.add_argument('--private-key',type=Path)
    args=parser.parse_args()
    props,sources=configuration(); feed=props['sourceRulesUrl']; public=props['sourceRulesPublicKey']
    args.state.mkdir(parents=True,exist_ok=True); args.state.chmod(0o700)
    lock=(args.state/'run.lock').open('a'); fcntl.flock(lock,fcntl.LOCK_EX)
    natives={x['sourceId']:x for x in json.loads(args.native_evidence.read_text())['sources']}
    # The bundle is consumed in memory and removed from subprocess environment. Signing never
    # creates a PEM file/artifact and verifies the APK's existing public trust anchor.
    secret=os.environ.pop('MANGARO_AUTOMATION_SECRET',None)
    bundle=json.loads(secret) if secret else {}; secret=None
    key=None; token=bundle.get('vercelToken') or os.environ.pop('MANGARO_VERCEL_TOKEN',None)
    if args.publish or args.private_key or bundle.get("signingKeyBase64"):
        if bundle.get('signingKeyBase64'):
            import base64
            key=base64.b64decode(bundle.pop('signingKeyBase64'),validate=True)
        elif args.private_key:
            p=args.private_key.resolve(strict=True)
            if p.is_relative_to(PROJECT) or p.stat().st_mode & 0o077: raise Unsafe('INSECURE_PRIVATE_KEY_PATH')
            key=p.read_bytes()
        else: raise Unsafe('CI_SECRET_NOT_CONFIGURED')
    publication_lock=threading.Lock()
    pub=VercelPublisher(public,key,feed,json.loads((HERE.parent/'production/hosting.json').read_text()),HERE.parent/'production/public',token,{s['sourceId'] for s in sources}) if args.publish else None
    reports=[]
    def operation(source):
        sid=source['sourceId']; file=args.state/f'{sid}.json'
        try:
            state=open_state(json.loads(file.read_text()),public) if file.exists() else {}
            if state.get('sourceId') is None: state={} # Safely discard old unscoped publisher counters, never app data.
            elif state['sourceId']!=sid: raise Unsafe('SIGNED_STATE_SOURCE_MISMATCH')
            # Public signed feed is authoritative. A valid 404 is BUILT_IN, not a source outage.
            response=Http(budget=4).get(feed+'/'+str(sid)+'.json')
            if response.status==200:
                remote=verify(json.loads(response.body),public,sid)
            elif response.status==404: remote=None
            else: raise Transient('FEED_TRANSPORT_UNAVAILABLE')
            def publish(candidate,previous):
                if pub is None: raise Unsafe('PUBLICATION_DISABLED_AUDIT_ONLY')
                # Reserve monotonic revision before an external action can succeed/fail.
                pending=dict(state,sourceId=sid,revisionFloor=max(state.get('revisionFloor',0),candidate['revision']))
                atomic(file,seal_state(pending,key,public))
                with publication_lock: pub(candidate,previous)
            updated,report=Controller(lambda:Http(budget=80),publish).run(source,state,remote,natives.get(sid))
            if key: atomic(file,seal_state(updated,key,public))
            return report
        except Transient:
            return {'sourceId':sid,'source':source['name'],'health':'UNKNOWN','result':'FEED_OFFLINE_NO_CHANGE'}
        except Exception:
            return {'sourceId':sid,'source':source['name'],'health':'DEGRADED','result':'ISOLATED_CHECK_ERROR'}
    with ThreadPoolExecutor(max_workers=2) as pool:
        reports=list(pool.map(operation,sources))
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps({'schema':1,'checkedAt':int(time.time()),'mode':'publish' if args.publish else 'audit','sources':reports},indent=2)+'\n')
    print(json.dumps({'sources':[{k:v for k,v in r.items() if k in {'source','health','result','revision','reason','failure','failureCount'}} for r in reports]},indent=2))

def atomic(path,value):
    temp=path.with_suffix('.tmp'); temp.write_text(canonical(value)); temp.chmod(0o600); temp.replace(path)

if __name__=='__main__':
    try: main()
    except Exception:
        print('Publisher configuration failed; no credential or raw response diagnostics emitted.',file=sys.stderr)
        sys.exit(1)
