"""Sign in memory; build-only Vercel deployment, verify, atomically promote, verify again."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit
from .core import Unsafe, Http, canonical, verify, sign, validate

class VercelPublisher:
    def __init__(self,public,private_bytes,feed,hosting,public_directory,token=None,allowed_ids=None,http_factory=Http):
        self.public=public; self.key=private_bytes; self.feed=feed.rstrip('/'); self.hosting=hosting; self.directory=Path(public_directory); self.token=token; self.allowed=set(allowed_ids or []); self.http_factory=http_factory
    def command(self,args,cwd):
        command=['npx','--yes','vercel@62.1.0','--scope','jalem']
        command+=args
        env={k:v for k,v in os.environ.items() if k not in {'MANGARO_AUTOMATION_SECRET','MANGARO_SIGNING_KEY','MANGARO_SIGNING_KEY_B64'} and not k.startswith(('GITHUB_','VERCEL_GIT_'))}
        if self.token: env['VERCEL_TOKEN']=self.token
        env['VERCEL_TELEMETRY_DISABLED']='1'
        # Never print commands, raw CLI stdout/stderr or exception repr; they could contain credentials.
        p=subprocess.run(command,cwd=cwd,env=env,capture_output=True,text=True,timeout=240)
        if p.returncode: raise Unsafe('VERCEL_'+args[0].upper()+'_FAILED')
        return p.stdout
    def fetch(self,sid):
        response=self.http_factory().get(self.feed+'/'+str(sid)+'.json')
        if response.status==404: return None
        if response.status!=200: raise Unsafe('PUBLIC_FEED_UNAVAILABLE')
        try: envelope=json.loads(response.body)
        except ValueError: raise Unsafe('PUBLIC_FEED_INVALID_JSON') from None
        verify(envelope,self.public,sid)
        return envelope
    def __call__(self,candidate,previous=None):
        sid=candidate['sourceId']
        if sid not in self.allowed: raise Unsafe('UNREGISTERED_PUBLICATION_TARGET')
        validate(candidate,self.http_factory(),previous) # Final independent pre-sign gate.
        signed=sign(candidate,self.key,self.public)
        old={i:self.fetch(i) for i in self.allowed}
        existing=old[sid]
        if existing and candidate['revision']<=verify(existing,self.public,sid)['revision']: raise Unsafe('REVISION_DOWNGRADE_OR_REUSE')
        with tempfile.TemporaryDirectory(prefix='mangaro-public-deployment-') as td:
            root=Path(td)
            shutil.copytree(self.directory,root/'public')
            # Overlay every already-published source, not just this checkout's stale inventory.
            for source_id,envelope in old.items():
                p=root/'public'/f'{source_id}.json'
                if envelope: p.write_text(canonical(envelope))
                else: p.unlink(missing_ok=True)
            (root/'public'/f'{sid}.json').write_text(canonical(signed))
            index=root/'public/index.json'
            if index.exists():
                data=json.loads(index.read_text())
                if str(sid) in data.get('sources',{}): data['sources'][str(sid)].update(state='SIGNED_REPAIR_AVAILABLE',publishedRevision=candidate['revision'])
                index.write_text(canonical(data))
            (root/'.vercel').mkdir(); (root/'.vercel/project.json').write_text(canonical(self.hosting))
            (root/'vercel.json').write_text(canonical({'framework':None,'buildCommand':'','outputDirectory':'public','headers':[{'source':'/(.*)','headers':[{'key':'Cache-Control','value':'public, max-age=0, must-revalidate'},{'key':'X-Content-Type-Options','value':'nosniff'}]}]}))
            # No signing bytes/environment files in this directory. Directory contains public data only.
            stdout=self.command(['deploy','--prod','--skip-domain','--yes'],root)
            import re
            urls=re.findall(r'https://[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.vercel\.app',stdout)
            if not urls: raise Unsafe('STAGED_DEPLOYMENT_URL_MISSING')
            deployment=urls[0]
            # Deployment protection stays enabled. Vercel curl uses authenticated bypass internally.
            result=self.command(['curl','/'+str(sid)+'.json','--deployment',deployment,'--','--silent','--fail'],root)
            try: staged=json.loads(result)
            except ValueError: raise Unsafe('STAGED_FEED_FETCH_FAILED') from None
            verify(staged,self.public,sid)
            if staged['payload']!=signed['payload']: raise Unsafe('STAGED_PAYLOAD_MISMATCH')
            # A concurrent manual publisher must not be overwritten with a stale multi-source snapshot.
            for source_id,envelope in old.items():
                current=self.fetch(source_id)
                if canonical(current)!=canonical(envelope): raise Unsafe('CONCURRENT_FEED_UPDATE')
            self.command(['promote',deployment,'--yes'],root)
            for attempt in range(3):
                public=self.fetch(sid)
                if public and public['payload']==signed['payload']:
                    result=verify(public,self.public,sid)
                    if result['revision']==candidate['revision']: return signed
                import time
                time.sleep(2)
            raise Unsafe('POST_PROMOTION_PUBLIC_VERIFICATION_FAILED')
