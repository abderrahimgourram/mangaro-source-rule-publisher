#!/usr/bin/env python3
"""Trusted operation entrypoint; health scheduling never imports this signing path."""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from operations import Coordinator, GitJournal, OperationBlocked, fingerprint, require

HERE=Path(__file__).resolve().parent
MANGA=HERE.name=='automation'
ROOT=HERE.parents[2] if MANGA else HERE.parent
REPOSITORY='abderrahimgourram/'+('mangaro-source-rule-publisher' if MANGA else 'mangaro-novel-sources')
sys.path.insert(0,str(HERE.parent if MANGA else HERE))


def api(method,path,data=None):
    command=['gh','api','--method',method,path]
    if data is not None:command+=['--input','-']
    result=subprocess.run(command,input=json.dumps(data) if data is not None else None,capture_output=True,text=True,timeout=30)
    require(result.returncode==0,'AUTHENTICATED_OPERATION_API_UNAVAILABLE')
    return json.loads(result.stdout)


def configuration():
    if MANGA:
        from automation.core import open_state, seal_state, verify
        from automation.publisher import VercelPublisher
        props=dict(line.split('=',1) for line in (ROOT/'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
        public=props['sourceRulesPublicKey']
        inventory=json.loads((HERE.parent/'production/public/index.json').read_text())['sources']
        publisher=VercelPublisher(public,None,props['sourceRulesUrl'],json.loads((HERE.parent/'production/hosting.json').read_text()),HERE.parent/'production/public',allowed_ids={int(s) for s in inventory})
        return inventory,lambda value:verify(value,public),lambda source:publisher.fetch(int(source)),lambda value:open_state(value,public),publisher
    from validate import build
    from verify import verify_pair, verify_signature
    inventory={item['domain']:{'name':item['domain']} for item in build(2)['sources']}
    def authenticate(value):
        return verify_pair(base64.b64decode(value['manifest'],validate=True),base64.b64decode(value['envelope'],validate=True))
    def fetch(source):
        def content(path):return base64.b64decode(api('GET',f'repos/{REPOSITORY}/contents/{path}?ref=main')['content'])
        manifest=content('published/manifest.json');data=json.loads(manifest)
        from verify import manifest_bytes
        manifest_bytes(data)
        envelope=content(data['envelopePath'])
        return {'manifest':base64.b64encode(manifest).decode(),'envelope':base64.b64encode(envelope).decode()}
    def unseal(value):
        body=base64.b64decode(value['body'],validate=True)
        verify_signature(b'MangaroSourceOperationsV1\n'+body,value['signature'],ROOT/'keys/rules-public.der')
        return json.loads(body)
    return inventory,authenticate,fetch,unseal,None


def signing_contract():
    if MANGA:
        from automation.core import seal_state, open_state
        inventory,auth,fetch,unseal,publisher=configuration()
        secret=json.loads(os.environ.pop('MANGARO_AUTOMATION_SECRET','{}'))
        key=base64.b64decode(secret.get('signingKeyBase64',''),validate=True)
        require(bool(key),'SIGNING_CREDENTIAL_UNAVAILABLE')
        # Existing anchored state signature verifies key ownership without publishing any rule.
        require(open_state(seal_state({'contract':'SIGNING_CHECK_ONLY'},key,publisher.public),publisher.public)=={'contract':'SIGNING_CHECK_ONLY'},'SIGNING_CONTRACT_MISMATCH')
        require(bool(os.environ.get('MANGARO_VERCEL_TOKEN') or secret.get('vercelToken')),'PUBLICATION_CREDENTIAL_UNAVAILABLE')
    else:
        key=os.environ.pop('RULE_SIGNING_KEY','')
        require(bool(key),'SIGNING_CREDENTIAL_UNAVAILABLE')
        result=subprocess.run(['openssl','pkey','-pubout','-outform','DER'],input=key.encode(),capture_output=True,timeout=10)
        require(result.returncode==0 and result.stdout==(ROOT/'keys/rules-public.der').read_bytes(),'SIGNING_TRUST_ANCHOR_MISMATCH')
    return {'signing':'VERIFIED_EXISTING_PINNED_KEY','publication':'NOT_EXECUTED'}


def readiness():
    inventory,auth,fetch,unseal,publisher=configuration()
    rows=[];eligible=[];observations=None
    try:
        journal=GitJournal(api,REPOSITORY,lambda value: (_ for _ in ()).throw(OperationBlocked('READ_ONLY_PREFLIGHT')),unseal)
        state=journal.read()
    except (OperationBlocked,ValueError,KeyError):
        state={}
    for source,item in inventory.items():
        reason='AUTHENTICATED_LAST_KNOWN_GOOD_REQUIRED'
        try:
            live=fetch(source)
            require(bool(live),'AUTHENTICATED_LAST_KNOWN_GOOD_REQUIRED')
            current=auth(live)
            row=state.get('sources',{}).get(source,{})
            require(row.get('approved') is True,'SOURCE_PUBLICATION_GATE_CLOSED')
            require(row.get('lastKnownGood') and row.get('witness'),'AUTHENTICATED_LAST_KNOWN_GOOD_REQUIRED')
            auth(row['lastKnownGood'])
            require(row.get('publishedHash')==fingerprint(live),'PUBLISHED_REVISION_CHANGED')
            require(row['witness'].get('complete') is True,'COMPLETENESS_UNPROVEN')
            if observations is None:
                import tempfile
                from reliability import restore_history,load
                with tempfile.TemporaryDirectory() as directory:
                    path=Path(directory)/'state.json'
                    restore_history(path,REPOSITORY,'source-rule-publisher.yml' if MANGA else 'health.yml','main')
                    observations=load(path)['sources']
            observed=observations.get(source,{})
            require(observed.get('classification') not in {'TRANSIENT_NETWORK_ERROR','BLOCKED_BY_CHALLENGE','UNVERIFIED_TRANSPORT','INSUFFICIENT_EVIDENCE'},'OBSERVATION_NOT_REPAIR_EVIDENCE')
            require(observed.get('consecutiveFailures',0)>=3 or row.get('publishedFailures') or row.get('pending'),'NO_SOURCE_OPERATION_DUE')
            eligible.append(source);reason='VERIFIED_JOURNAL_ACTION_DUE'
        except Exception as error:
            reason=str(error) if isinstance(error,OperationBlocked) else 'SIGNED_REVISION_VERIFICATION_FAILED'
        rows.append({'source':item['name'],'id':source,'gate':reason})
    return {'sources':rows,'eligible':eligible,'production':'NOT_EXECUTED'}


def execute():
    require(os.environ.get('MANGARO_AUTOPUBLISH_ENABLED')=='true','AUTOPUBLISH_DISABLED')
    require(os.environ.get('GITHUB_REPOSITORY')==REPOSITORY and os.environ.get('GITHUB_REF')=='refs/heads/main','UNTRUSTED_OPERATION_RUN')
    ready=readiness()
    require(bool(ready['eligible']),'NO_INDEPENDENTLY_VERIFIED_SOURCE_ELIGIBLE')
    # Production adapters require a real independently signed staging acceptance, never
    # the diagnostic fixture, sample-only attestation, or monitor's provisional booleans.
    inventory,auth,fetch,unseal,publisher=configuration()
    key_file=None
    if MANGA:
        from automation.core import seal_state, validate, Http, Unsafe, Transient
        from automation.controller import Controller
        secret=json.loads(os.environ.pop('MANGARO_AUTOMATION_SECRET','{}'))
        key=base64.b64decode(secret['signingKeyBase64'],validate=True)
        publisher.key=key;publisher.token=secret.get('vercelToken') or os.environ.pop('MANGARO_VERCEL_TOKEN',None)
        seal=lambda state:seal_state(state,key,publisher.public)
        def independent(candidate,witness):
            return {'complete':True,'nativeWitness':validate(candidate,Http(budget=80),witness.get('nativeWitness'))}
        def publish(candidate,witness):return publisher(candidate,witness.get('nativeWitness'))
    else:
        import tempfile
        from validate import require_health
        def health(candidate):
            (ROOT/'bundle.json').write_text(json.dumps(candidate,ensure_ascii=False,separators=(',',':')))
            process=subprocess.run([sys.executable,str(HERE/'health.py'),'--bundle',str(ROOT/'bundle.json')],capture_output=True,timeout=600)
            report=json.loads((ROOT/'health.json').read_text())
            if process.returncode:
                failed=[r for r in report if not r.get('completeIndex') or not all(r.get(op) is True for op in ['catalog','search','details','chapters','text'])]
                if len(failed)==1 and failed[0].get('classification')=='DEGRADED':
                    raise OperationBlocked(failed[0].get('reason','FULL_NOVEL_ACCEPTANCE_UNAVAILABLE'))
                raise OperationBlocked('FULL_NOVEL_ACCEPTANCE_UNAVAILABLE')
            return report
        # Keep the existing signing/package contract; keys never enter artifacts or command arguments.
        key=os.environ.pop('RULE_SIGNING_KEY','');require(bool(key),'SIGNING_CREDENTIAL_UNAVAILABLE')
        key_file=tempfile.NamedTemporaryFile(prefix='source-rules-key-',delete=False)
        os.chmod(key_file.name,0o600);key_file.write(key.encode());key_file.close()
        def seal(state):
            raw=json.dumps(state,sort_keys=True,separators=(',',':')).encode()
            signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',key_file.name],input=b'MangaroSourceOperationsV1\n'+raw)
            return {'body':base64.b64encode(raw).decode(),'signature':base64.b64encode(signature).decode()}
        def independent(candidate,witness):
            # Existing health entrypoint checks every bundled source and signed SeaNovel contract.
            report=health(candidate)
            require_health(report,candidate)
            known=witness.get('novelWitness',{})
            observed={r['domain']:{'identityHash':r.get('identityHash'),'chapterCount':r.get('chapterCount')} for r in report}
            require(set(known)==set(observed),'AUTHENTICATED_NOVEL_IDENTITY_WITNESS_REQUIRED')
            for domain,actual in observed.items():
                prior=known[domain]
                require(actual['identityHash'] and actual['identityHash']==prior.get('identityHash'),'NOVEL_IDENTITY_MISMATCH')
                require(type(actual['chapterCount']) is int and actual['chapterCount']>=prior.get('chapterCount',0),'UNEXPLAINED_CHAPTER_COUNT_DROP')
            return {'complete':True,'novelWitness':observed}
        def publish(candidate,witness):
            from package import package
            report=health(candidate);require_health(report,candidate)
            manifest,envelope=package(candidate,key_file.name)
            stage=ROOT/'staging';stage.mkdir(exist_ok=True)
            (stage/'manifest.json').write_bytes(manifest);(stage/f"rules-{candidate['revision']}.json").write_bytes(envelope)
            (ROOT/'health.json').write_text(json.dumps(report))
            env=dict(os.environ,RULE_REVISION=str(candidate['revision']))
            result=subprocess.run([sys.executable,str(HERE/'publish.py')],env=env,capture_output=True,timeout=180)
            require(result.returncode==0,'SOURCE_SCOPED_PUBLICATION_FAILED')
            return fetch('')
    store=GitJournal(api,REPOSITORY,seal,unseal)
    def stage(candidate,witness):
        # Accepted receipts are signed, scoped to the exact candidate, and produced by
        # an independent real-source acceptance run. Lack of a receipt is a hard gate.
        state=store.read()
        proof=state.get('stagingReceipts',{}).get(fingerprint(candidate))
        require(proof is not None,'REAL_SOURCE_STAGING_ACCEPTANCE_UNAVAILABLE')
        receipt=unseal(proof)
        require(receipt.get('purpose')=='SOURCE_STAGING_ACCEPTANCE_V1' and receipt.get('repository')==REPOSITORY,'STAGING_PROVENANCE_MISMATCH')
        require(time.time()-receipt.get('verifiedAt',0)<=3600,'STAGING_ACCEPTANCE_EXPIRED')
        require(receipt.get('independentRun') and receipt.get('producerRun')!=receipt.get('independentRun'),'INDEPENDENT_STAGING_REQUIRED')
        return receipt
    coordinator=Coordinator(store,auth,independent,stage,publish,fetch)
    results=[]
    try:
        for source in ready['eligible']:
            try:
                coordinator.reconcile(source)
                state=store.read();row=state['sources'][source];live=auth(fetch(source))
                def regression(current, witness):
                    try:
                        independent(current,witness)
                        return {'confirmedSemantic':False,'classification':'HEALTHY'}
                    except Exception as failure:
                        # A valid historical mapping must independently work against the SAME
                        # authentic witness before attributing the failure to the published revision.
                        code=str(failure)
                        if any(word in code for word in ['TRANSIENT','HTTP_403','HTTP_429','CHALLENGE','NETWORK','TRANSPORT','TIMEOUT']):
                            return {'confirmedSemantic':False}
                        try:
                            old=auth(row['lastKnownGood'])
                            historical=row.get('rollbackWitness',witness)
                            coordinator.verify(old,historical)
                        except Exception:
                            return {'confirmedSemantic':False}
                        try:
                            independent(current,witness)
                        except Exception as second:
                            if str(second)!=code or not code:
                                return {'confirmedSemantic':False}
                            allowed={'KNOWN_MANGA_IDENTITY_LOST','KNOWN_CHAPTER_IDENTITIES_LOST','KNOWN_READER_PAGE_COUNT_TRUNCATED','SEARCH_IDENTITY_MISMATCH','MANGA_IDENTITY_MISMATCH','MISSING_REQUIRED_FIELD','EMPTY_REQUIRED_FIELD','NO_ROWS',
                                     'DETAILS_IDENTITY_MISMATCH','DETAILS_TITLE_IDENTITY_MISMATCH','TRUNCATED_CHAPTER_INDEX','CHAPTER_OWNERSHIP_MISMATCH','CHAPTER_IDENTITY_MISMATCH','INVALID_CHAPTER_TEXT'}
                            return {'confirmedSemantic':code in allowed,'classification':'CONFIRMED_CONTENT_LOSS'}
                        return {'confirmedSemantic':False}
                recovery=coordinator.observe_regression(source,regression)
                if recovery['action'] in {'ROLLBACK_VERIFIED','REGRESSION_CONFIRMATION_PENDING'}:
                    results.append(recovery);continue
                if MANGA:
                    # Existing generator derives only bounded declarative transformations from real observations.
                    state_input=dict(sourceId=int(source),baseline=live,witness=row['witness'].get('nativeWitness'),evidence=row.get('inferenceEvidence',{}),failures=row.get('consecutiveFailures',0))
                    def accepted(candidate,previous):
                        results.append(coordinator.apply(source,candidate))
                    updated,diagnostic=Controller(lambda:Http(budget=80),accepted).run({'sourceId':int(source),'name':inventory[source]['name']},state_input,remote=live)
                    current=store.read();current['sources'][source]['consecutiveFailures']=updated.get('failures',state_input['failures']);store.write(current)
                    results.append({'source':source,'action':diagnostic['result']})
                else:
                    candidate=derive_novel(live,row,source)
                    results.append(coordinator.apply(source,candidate))
            except (OperationBlocked,ValueError,KeyError):
                results.append({'source':source,'action':'BLOCKED_EVIDENCE_OR_ACCEPTANCE_GATE'})
    finally:
        if key_file:Path(key_file.name).unlink(missing_ok=True)
    return {'operations':results,'production':'GATED_OPERATIONS_ONLY'}


def derive_novel(current,row,source):
    """A unique CSS class/id rename, grounded in authenticated known public identity values."""
    import re
    import requests
    from bs4 import BeautifulSoup
    from validate import validate
    from operations import source_record, scoped_candidate
    baseline=source_record(current,source)
    witnesses=row.get('mappingWitness',[])
    require(3<=len(witnesses)<=12 and len({w['url'] for w in witnesses})>=3,'KNOWN_NOVEL_MAPPING_WITNESS_REQUIRED')
    options={};deadline=time.monotonic()+65
    for witness in witnesses:
        from urllib.parse import urlsplit
        url=witness['url'];parsed=urlsplit(url)
        require(parsed.scheme=='https' and parsed.hostname==source and not parsed.query,'UNAPPROVED_WITNESS_TRANSPORT')
        field=witness['field'];old=baseline['selectors'].get(field,'')
        require(field in {'catalogTitle','detailsTitle','chapterTitle','originalTitle'} and re.fullmatch(r'[.#][A-Za-z_][A-Za-z0-9_-]*',old),'REQUIRES_COMPILED_UPDATE')
        require(time.monotonic()<deadline,'CANDIDATE_BUDGET_EXHAUSTED')
        with requests.get(url,timeout=(10,20),allow_redirects=False,stream=True) as response:
            require(response.status_code==200,'SOURCE_RESPONSE_UNVERIFIED')
            raw=bytearray()
            for chunk in response.iter_content(8192):
                raw.extend(chunk);require(len(raw)<=2*1024*1024 and time.monotonic()<deadline,'SOURCE_RESPONSE_BUDGET_EXHAUSTED')
        require(not any(marker in raw.lower() for marker in [b'challenge-form',b'cf-browser-verification',b'just a moment']),'CHALLENGE_NOT_CONTENT')
        doc=BeautifulSoup(bytes(raw),'html.parser');expected=witness['value'];matches=set()
        for element in doc.find_all(True):
            if element.get_text(' ',strip=True)!=expected:continue
            for css in ['#'+element['id']] if element.get('id') else []:
                if re.fullmatch(r'[.#][A-Za-z_][A-Za-z0-9_-]*',css) and len(doc.select(css))==1:matches.add(css)
            for klass in element.get('class',[]):
                css='.'+klass
                if re.fullmatch(r'[.#][A-Za-z_][A-Za-z0-9_-]*',css) and len(doc.select(css))==1:matches.add(css)
        options[field]=matches if field not in options else options[field]&matches
    require(options and all(len(values)==1 for values in options.values()),'AMBIGUOUS_DECLARATIVE_MAPPING')
    candidate=copy.deepcopy(current);target=next(r for r in candidate['sources'] if r['domain']==source)
    for field,values in options.items():target['selectors'][field]=next(iter(values))
    require(target!=baseline,'NO_SUPPORTED_STRUCTURAL_CHANGE')
    candidate['revision']=max(current['revision'],row.get('revisionFloor',0))+1
    validate(candidate);scoped_candidate(current,candidate,source)
    return candidate


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-signing',action='store_true');parser.add_argument('--execute',action='store_true')
    parser.add_argument('--report',type=Path,default=Path('source-operations-report.json'))
    args=parser.parse_args()
    try:
        result=signing_contract() if args.verify_signing else execute() if args.execute else readiness()
        status=0
    except Exception:
        result={'gate':'BLOCKED_OPERATION_CONTRACT','production':'NOT_EXECUTED'};status=1
    args.report.write_text(json.dumps(result,indent=2)+'\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as stream:stream.write(f"eligible={str(bool(result.get('eligible'))).lower()}\n")
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as stream:
            stream.write('Source operation gates (CI success does not imply publication):\n\n'+json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
    return status

if __name__=='__main__':raise SystemExit(main())
