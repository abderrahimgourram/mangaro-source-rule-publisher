#!/usr/bin/env python3
"""Automatic generation/sign/publication acceptance; ONLY the unregistered staging source.
Production feed is never passed to the test publisher. Key stays outside checkout/in memory.
"""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import zlib
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent))
from automation.core import Http, Unsafe, sign, verify, validate, canonical
from automation.controller import Controller,capture
from automation.publisher import VercelPublisher
from automation.run import configuration

SID=9223372036854775708
HOST='https://mangaro-source-rules-staging.vercel.app'

def build(root,changed):
    def write(name,obj):
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(canonical(obj))
    def manga(n):return {'id':f'm{n}','url':f'/m{n}',('postTitle' if changed else 'title'):f'Title {n}',('featuredImage' if changed else 'cover'):HOST+'/image.png'}
    for page in (1,2):
        write(Path(f'api/popular/{page}.json'),{'items':[manga(n) for n in ([1,2] if page==1 else [3,4])],'total':4})
        write(Path(f'api/latest/{page}.json'),{'items':[manga(n) for n in ([3,4] if page==1 else [1,2])],'total':4})
        for n in range(1,5):write(Path(f'api/search/Title {n}/{page}.json'),{'items':[manga(n)] if page==1 else [],'total':1})
    for n,total in [(1,2),(2,6),(3,3),(4,4)]:
        write(Path(f'api/details/m{n}.json'),{'post':manga(n)})
        for page in range(1,(total+1)//2+1):
            rows=[{'id':f'm{n}-c{i}','url':f'/m{n}/c{i}','name':f'Chapter {i}','number':i+.5} for i in range((page-1)*2+1,min(total,page*2)+1)]
            write(Path(f'api/chapters/m{n}/{page}.json'),({'post':{'chapters':rows}} if changed else {'chapters':rows})|{'total':total})
        for i in range(1,total+1):write(Path(f'api/pages/m{n}-c{i}.json'),{('images' if changed else 'pages'):[HOST+'/image.png',HOST+'/image2.png']})
    def chunk(t,b):return struct.pack('!I',len(b))+t+b+struct.pack('!I',zlib.crc32(t+b)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',32,32,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\x00'+bytes([50,120,160])*32)*32))+chunk(b'IEND',b'')
    (root/'image.png').write_bytes(png);(root/'image2.png').write_bytes(png)

def baseline(revision):
    fields={k:{'path':k} for k in ['id','url','title','cover']};paging={'pageParameter':'page','pageSize':2,'total':'total'}
    cat={'endpoint':'/api/popular/{page}.json','rows':'items','fields':fields,'pagination':paging}
    return {'schema':1,'sourceId':SID,'revision':revision,'baseUrl':HOST,'operations':{
        'popular':cat,'latest':dict(cat,endpoint='/api/latest/{page}.json'),'search':dict(cat,endpoint='/api/search/{query}/{page}.json'),
        'details':{'endpoint':'/api/details/{id}.json','rows':'post','fields':{k:v for k,v in fields.items() if k!='url'}},
        'chapters':{'endpoint':'/api/chapters/{id}/{page}.json','rows':'chapters','fields':{k:{'path':k} for k in ['id','url','name','number']},'pagination':paging},
        'pages':{'endpoint':'/api/pages/{id}.json','rows':'pages','fields':{'image':{'path':''}}}}}

def await_representation(changed):
    import time
    for attempt in range(6):
        r=Http(budget=3,delay=0).get(HOST+'/api/popular/1.json')
        if r.status==200:
            data=json.loads(r.body)
            if ('postTitle' in data['items'][0])==changed: return
        time.sleep(2)
    raise Unsafe('STAGING_ALIAS_PROPAGATION_TIMEOUT')

def run(key,token=None,report_path=None):
    props,_=configuration();public=props['sourceRulesPublicKey'];directory=HERE.parent/'staging';root=directory/'public'
    publisher=VercelPublisher(public,key,HOST,json.loads((directory/'hosting.json').read_text()),root,token,{SID},http_factory=lambda:Http(budget=80,delay=.05))
    if publisher.feed==props['sourceRulesUrl']:raise Unsafe('PRODUCTION_SIMULATION_FORBIDDEN')
    def deploy_fixture():
        import shutil,tempfile
        # Artifact deployment: no Android checkout/Git author metadata or private files.
        with tempfile.TemporaryDirectory(prefix='mangaro-staging-fixture-') as tmp:
            artifact=Path(tmp)
            shutil.copytree(root,artifact/'public')
            shutil.copyfile(directory/'vercel.json',artifact/'vercel.json')
            (artifact/'.vercel').mkdir()
            (artifact/'.vercel/project.json').write_text(canonical(publisher.hosting))
            publisher.command(['deploy','--prod','--yes'],artifact)
    prior=publisher.fetch(SID); revision=verify(prior,public,SID)['revision']+1 if prior else 1
    original=baseline(revision)
    build(root,False)
    signed_baseline=sign(original,key,public)
    path=root/'baseline'/f'{SID}.json';path.parent.mkdir(exist_ok=True);path.write_text(canonical(signed_baseline))
    deploy_fixture()
    await_representation(False)
    http=lambda:Http(budget=80,delay=.05)
    state={'baseline':original,'witness':validate(original,http()),'evidence':capture(original,http())}
    print('Staging healthy: real HTTPS, four manga, full chapter traversal and image verified.')
    build(root,True);deploy_fixture()
    await_representation(True)
    calls=0
    def publish(candidate,previous):
        nonlocal calls
        envelope=publisher(candidate,previous);calls+=1
        (root/f'{SID}.json').write_text(canonical(envelope))
    controller=Controller(http,publish,clock=lambda:1000000)
    statuses=[]
    for poll in range(3):
        controller.clock=lambda poll=poll:1000000+poll*21600
        state,result=controller.run({'sourceId':SID,'name':'unregistered staging acceptance'},state)
        statuses.append(result['result'])
    if statuses!=['FAILURE_PENDING','FAILURE_PENDING','REPAIRED'] or calls!=1:raise Unsafe('E2E_AUTOMATIC_REPAIR_FAILED_'+statuses[-1]+'_'+result.get('reason','UNKNOWN_GATE'))
    fetched=publisher.fetch(SID);current=verify(fetched,public,SID)
    if current!=state['baseline']:raise Unsafe('E2E_PUBLIC_PAYLOAD_MISMATCH')
    # Real HTTPS fresh controller restored from serialized state; no manual candidate payload.
    restored=json.loads(canonical(state));controller.clock=lambda:1200000
    restored,result=controller.run({'sourceId':SID,'name':'unregistered staging acceptance'},restored,remote=current)
    if result['health']!='HEALTHY' or calls!=1:raise Unsafe('E2E_RESTART_DEDUP_FAILED')
    receipt={'sourceId':SID,'feed':HOST,'initialRevision':revision,'activeRevision':current['revision'],'statuses':statuses,'publicationCount':calls,'witnesses':state['witness'],'candidateHash':state['publishedHashes'][-1],'restart':'HEALTHY_NO_DUPLICATE_PUBLICATION'}
    if report_path:report_path.write_text(json.dumps(receipt,indent=2)+'\n')
    print('Staging E2E: generated automatically -> two validations -> production-key signed -> staged/verified -> promoted -> public HTTPS verified -> state restart healthy.')
    return receipt

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--private-key',type=Path);p.add_argument('--report',type=Path);args=p.parse_args()
    try:
        secret=os.environ.pop('MANGARO_AUTOMATION_SECRET',None);bundle=json.loads(secret) if secret else {}
        token=bundle.get('vercelToken') or os.environ.pop('MANGARO_VERCEL_TOKEN',None)
        if bundle.get('signingKeyBase64'):key=base64.b64decode(bundle.pop('signingKeyBase64'),validate=True)
        elif args.private_key:
            path=args.private_key.resolve(strict=True)
            if path.is_relative_to(HERE.parents[2]) or path.stat().st_mode & 0o077:raise Unsafe('INSECURE_SIGNING_PATH')
            key=path.read_bytes()
        else:raise Unsafe('SIGNING_SECRET_REQUIRED')
        run(key,token,args.report)
    except Exception as e:
        print('Staging acceptance failed: '+(str(e) if isinstance(e,Unsafe) else type(e).__name__),file=sys.stderr);sys.exit(1)
