"""Per-source state machine; publication requires repeated live and identity validation."""
import copy
import json
import time
from pathlib import Path
from urllib.parse import urlsplit
from .core import Unsafe, Transient, Interpreter, validate, variables, digest, verify, canonical
from .infer import Generator, compile_native

THRESHOLD=3
COOLDOWN=24*3600

def profile_hash(r): return digest({k:v for k,v in r.items() if k!='revision'})
def safe_values(rows):
    # Persist no signed image/cover query strings. Stable source identities are kept verbatim.
    return [{k:(v.split('?',1)[0] if k in {'image','cover'} and isinstance(v,str) else v) for k,v in row.items()} for row in rows]

def capture(r,http):
    i=Interpreter(r,http); evidence={}; first,_=i.catalogue('popular'); sample=first[0]; detail=i.details(sample); chapters=i.chapters(detail)
    calls=[('popular','',{},1,''),('latest','',{},1,''),('search','',{},1,sample['title']),('details',sample['url'],{'id':sample['id']},1,''),('chapters',sample['url'],{'id':sample['id']},1,'')]
    if chapters: calls.append(('pages',chapters[-1]['url'],{'id':chapters[-1]['id']},1,''))
    for op,url,memo,page,query in calls:
        rows,nxt,total=i.rows(op,url,memo,page,query)
        evidence[op]={'before':safe_values(rows[:2]),'url':url,'memo':memo,'page':page,'query':query,'next':nxt,'total':total,'variables':variables(r['baseUrl'],url,memo,page,query)}
    return evidence

def changed_observations(r,state,http):
    result={}
    for op,e in state.get('evidence',{}).items():
        o=r['operations'][op]; v=e['variables']
        from .core import expand, public_url
        from urllib.parse import urljoin
        url=public_url(urljoin(r['baseUrl'],expand(o['endpoint'],v,True)))
        params={k:expand(x,v) for k,x in o.get('parameters',{}).items()}
        if o.get('pagination'): params[o['pagination'].get('pageParameter','page')]=str(e['page'])
        response=http.get(url,o.get('method','GET'),params,r.get('headers',{}),o.get('bodyEncoding','FORM'))
        result[op]=e|{'response':response}
    if set(result)!=set(r['operations']): raise Unsafe('NO_FULL_SUCCESSFUL_BASELINE')
    return result

class Controller:
    def __init__(self,http_factory,publisher,clock=time.time):
        self.http_factory=http_factory; self.publisher=publisher; self.clock=clock
    def run(self,source,state,remote=None,native=None):
        """The caller persists this source's state even when another source fails."""
        sid=source['sourceId']; now=self.clock(); new=copy.deepcopy(state)
        report={'sourceId':sid,'source':source['name'],'health':'DEGRADED','result':'NO_PUBLICATION','attempts':[]}
        # Reports contain code/field names only, never bodies, exception messages or URLs.
        if now-new.get('lastCheck',0)<300: return new,report|{'result':'RECENT_CHECK_SKIPPED'}
        new['lastCheck']=now
        rules=remote or new.get('baseline')
        if rules is None:
            if native is None: return new,report|{'result':'NATIVE_AUDIT_REQUIRED'}
            steps=native.get('steps',{})
            failures=[k for k,v in steps.items() if v=='SEMANTIC_ERROR']
            transient=any(v.startswith('TRANSIENT') for v in steps.values())
            report['nativeSteps']=steps
            report['nativeDiagnostics']=native.get('diagnostics',{})
            if not failures and not transient:
                new['failures']=0
                try:
                    baseline=compile_native(native)
                    verified=validate(baseline,self.http_factory())
                    # Verify the generic candidate preserves native identity and metadata before
                    # it can be a generation baseline. Never publish a healthy baseline.
                    compare_native(baseline,native,self.http_factory())
                    new['baseline']=baseline; new['witness']=verified; new['evidence']=capture(baseline,self.http_factory())
                    report['baseline']='DECLARATIVE_EQUIVALENT'
                except Unsafe as e:
                    report['baseline']=str(e); new['nativeCapability']=str(e)
                unverified=any(v.startswith(('PASS_PARTIAL_','PASS_DEGRADED_','PASS_FAILED_')) for v in steps.values())
                return new,report|{'health':'DEGRADED' if unverified else 'HEALTHY','result':'BUILT_IN_CHAPTERS_UNVERIFIED' if unverified else 'BUILT_IN_HEALTHY'}
            if transient:
                new['failures']=0
                return new,report|{'result':'TRANSIENT_NO_REPAIR'}
            new['failures']=new.get('failures',0)+1
            if new['failures']<THRESHOLD: return new,report|{'result':'FAILURE_PENDING','failureCount':new['failures']}
            return new,report|{'health':'UNAVAILABLE','result':'REQUIRES_COMPILED_UPDATE','reason':new.get('nativeCapability','NATIVE_BASELINE_NOT_SAFELY_DECLARABLE'),'operation':failures[0] if failures else 'unknown'}
        try:
            verified=validate(rules,self.http_factory(),new.get('witness'))
            new['baseline']=rules; new['witness']=verified; new['evidence']=capture(rules,self.http_factory()); new['failures']=0
            return new,report|{'health':'HEALTHY','result':'ACTIVE_HEALTHY' if remote else 'BUILT_IN_HEALTHY'}
        except Transient as e:
            new['failures']=0
            return new,report|{'result':'TRANSIENT_NO_REPAIR','reason':str(e)}
        except Unsafe as e: report['failure']=str(e)
        new['failures']=new.get('failures',0)+1
        if new['failures']<THRESHOLD: return new,report|{'result':'FAILURE_PENDING','failureCount':new['failures']}
        if now-new.get('publishedAt',0)<COOLDOWN: return new,report|{'result':'SOURCE_COOLDOWN'}
        generator=Generator(self.http_factory())
        try:
            changes=changed_observations(rules,new,self.http_factory())
            candidate=generator.generate(rules,changes)
            candidate['sourceId']=sid
            candidate['revision']=max(rules['revision'],new.get('revisionFloor',0))+1
            hash_=profile_hash(candidate)
            if hash_ in new.get('publishedHashes',[]): raise Unsafe('CANDIDATE_ALREADY_PUBLISHED')
            if hash_==profile_hash(rules): raise Unsafe('NO_SUPPORTED_STRUCTURAL_CHANGE')
            first=validate(candidate,self.http_factory(),new.get('witness'))
            second=validate(candidate,self.http_factory(),new.get('witness'))
            if digest(first)!=digest(second): raise Unsafe('UNSTABLE_BETWEEN_VALIDATION_PASSES')
            # A hash includes exact mappings, and repeated passes include all chapter identities.
            # Current snapshot is captured only after both passes; publication revalidates again.
            evidence=capture(candidate,self.http_factory())
            new['revisionFloor']=candidate['revision'] # Reserve before publication; never reuse failed revisions.
            self.publisher(candidate,new.get('witness'))
            new.update(baseline=candidate,witness=second,evidence=evidence,publishedAt=now,failures=0)
            new['publishedHashes']=(new.get('publishedHashes',[])+[hash_])[-32:]
            return new,report|{'health':'HEALTHY','result':'REPAIRED','revision':candidate['revision'],'candidateHash':hash_,'attempts':generator.attempts}
        except Transient as e: return new,report|{'result':'TRANSIENT_NO_PUBLICATION','reason':str(e),'attempts':generator.attempts}
        except Unsafe as e:
            if str(e)=='PUBLICATION_DISABLED_AUDIT_ONLY': return new,report|{'result':'VALIDATED_AUDIT_ONLY','attempts':generator.attempts}
            return new,report|{'health':'UNAVAILABLE','result':'REQUIRES_COMPILED_UPDATE','reason':str(e),'attempts':generator.attempts}
        except Exception:
            return new,report|{'result':'PUBLICATION_FAILED','reason':'SANITIZED_PUBLISHER_ERROR','attempts':generator.attempts}


def compare_native(r,native,http):
    """Protect native URL/memo/date/decimal-number/name and metadata semantics on cold start."""
    i=Interpreter(r,http)
    observed={m['url']:m for o in native['observations'] if o['operation']=='popular' for m in o['mangas']}
    produced,_=i.catalogue('popular')
    if {m['url'] for m in produced}!=set(observed): raise Unsafe('NATIVE_CATALOGUE_IDENTITY_CHANGED')
    for m in produced:
        n=observed[m['url']]
        if m['title']!=n['title'] or m.get('cover','')!=n.get('cover',''): raise Unsafe('NATIVE_CATALOGUE_METADATA_CHANGED')
    for obs in [o for o in native['observations'] if o['operation']=='details']:
        inp=obs['input']; m=next((x for x in produced if x['url']==inp['url']),None)
        if m is None: raise Unsafe('NATIVE_REPRESENTATIVE_MANGA_NOT_PRODUCED')
        d=i.details(m); expected=obs['manga']
        for f in ['title','cover','description','author','artist','genre']:
            if d.get(f,'')!=expected.get(f,''): raise Unsafe('NATIVE_DETAILS_'+f.upper()+'_CHANGED')
        remote=i.chapters(d); chapters={x['url']:x for x in obs['chapters']}
        if {c['url'] for c in remote}!=set(chapters): raise Unsafe('NATIVE_CHAPTER_IDENTITIES_CHANGED')
        for c in remote:
            n=chapters[c['url']]
            if c['name']!=n['name'] or float(c.get('number',-1))!=float(n['number']) or int(c.get('date',0))!=n['date'] or c.get('scanlator','')!=n.get('scanlator',''): raise Unsafe('NATIVE_CHAPTER_METADATA_CHANGED')
            for k,v in n.get('memo',{}).items():
                if k!='id' and c.get('memo.'+k)!=str(v): raise Unsafe('NATIVE_REMOTE_MEMO_CHANGED')
