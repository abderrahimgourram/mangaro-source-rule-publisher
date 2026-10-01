"""Deterministic changed-site simulations; all transport/publication stays in test infrastructure."""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from automation.core import *
from automation.controller import Controller,capture,profile_hash
from automation.infer import Generator

ID=9223372036854775708
BASE='https://publisher-test.example'
def profile(base=BASE):
    fields={k:{'path':k} for k in ['id','url','title','cover']}
    paging={'pageParameter':'page','pageSize':2,'total':'total'}
    cat={'endpoint':'/popular','rows':'items','fields':fields,'pagination':paging}
    return {'schema':1,'sourceId':ID,'revision':1,'baseUrl':base,'operations':{
        'popular':cat,'latest':dict(cat,endpoint='/latest'),'search':dict(cat,endpoint='/search',parameters={'q':'{query}'}),
        'details':{'endpoint':'/details/{id}','rows':'post','fields':{k:v for k,v in fields.items() if k!='url'}},
        'chapters':{'endpoint':'/chapters/{id}','rows':'chapters','fields':{k:{'path':k} for k in ['id','url','name','number']},'pagination':paging},
        'pages':{'endpoint':'/pages/{id}','rows':'pages','fields':{'image':{'path':''}}}}}

class Site:
    def __init__(self): self.change=set(); self.public={}; self.publications=0; self.clients=0
    def http(self): self.clients+=1; return FakeHttp(self,self.clients)
    def publish(self,r,previous=None):
        validate(r,self.http(),previous)
        # Tests use an ephemeral key unless the explicit HTTPS staging acceptance runs with
        # the existing external production private key and unregistered diagnostic ID.
        key=ec.generate_private_key(ec.SECP256R1())
        pem=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
        public=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        envelope=sign(r,pem,public); self.public[r['sourceId']]=envelope
        assert verify(json.loads(canonical(envelope)),public,r['sourceId'])==r
        self.publications+=1
    def manga(self,n):
        item={'id':f'm{n}','url':f'/m{n}','title':f'Title {n}','cover':BASE+'/a.png'}
        if 'title' in self.change:item['postTitle']=item.pop('title')
        if 'cover' in self.change:item['featuredImage']=item.pop('cover')
        return item
class FakeHttp:
    def __init__(self,site,view):self.site=site;self.view=view;self.responses=[];self.calls=0
    def get(self,url,method='GET',parameters=None,headers=None,encoding='FORM',image=False):
        self.calls+=1
        if self.calls>80:raise Transient('REQUEST_BUDGET_EXHAUSTED')
        p=urlsplit(url); q=parse_qs(p.query); q.update({k:[str(v)] for k,v in (parameters or {}).items()}); path_=p.path
        changes=self.site.change
        base='https://publisher-new.example' if 'domain' in changes else 'https://'+p.netloc
        def response(value,status=200,ctype='application/json'):
            body=value if isinstance(value,bytes) else json.dumps(value).encode() if not isinstance(value,str) else value.encode()
            r=Response(base+path_,status,body,{'Content-Type':ctype});self.responses.append(r);return r
        if image or path_.endswith('.png'):return response(b'\x89PNG\r\n\x1a\n'+bytes(32),ctype='image/png')
        if 'challenge' in changes:return response('<html><form id="challenge-form"></form></html>',ctype='text/html')
        if path_=='/search' and 'endpoint' in changes:
            return response('<form method="get" action="/manga"><input name="s" type="search"></form>',404,'text/html')
        parameter='p' if 'pagination' in changes else 'page'
        page=int(q.get(parameter,['1'])[0])
        if path_ in {'/popular','/latest','/search','/manga'}:
            ids=[1,2] if page==1 else [3,4]
            if path_=='/latest':ids=[3,4]
            if path_ in {'/search','/manga'}:
                key='s' if 'endpoint' in changes else 'q'; query=q.get(key,[''])[0]
                ids=[n for n in range(1,5) if query==f'Title {n}']
            if 'empty' in changes:ids=[]
            if 'css' in changes or 'css-old' in changes:
                klass='tile' if 'css' in changes else 'card'; title='caption' if 'css' in changes else 'title'
                html=''.join(f'<div class="{klass}" data-id="m{n}"><a class="link" href="/m{n}">Read</a><h3 class="{title}">Title {n}</h3><img src="{BASE}/a.png"></div>' for n in ids)
                if page==1:html+=f'<a rel="next" href="/popular?{parameter}=2">Next</a>'
                return response(html,ctype='text/html')
            data={'items':[self.site.manga(n) for n in ids],'total':4}
            if 'pagination' in changes:data['nextPageUrl']=base+'/popular?p=2' if page==1 else None
            return response(data)
        if path_.startswith('/details/'):
            n=int(path_.rsplit('m',1)[-1]);return response({'post':self.site.manga(n)})
        if path_.startswith('/chapters/'):
            n=int(path_.rsplit('m',1)[-1]);total={1:2,2:6,3:3,4:4}[n]
            if 'unstable' in changes:total+=self.view%2
            chapters=[{'id':f'm{n}-c{i}','url':f'/m{n}/c{i}','name':f'Chapter {i}','number':i+.5} for i in range(1,total+1)]
            if 'invalid' in changes:total+=1
            selected=chapters[(page-1)*2:page*2]
            if 'repeat' in changes:selected=chapters[:2]
            data={'chapters':selected,'total':total}
            if 'nesting' in changes:data['post']={'chapters':data.pop('chapters')}
            if 'pagination' in changes:data['nextPageUrl']=base+path_+'?p=2' if page==1 else None
            return response(data)
        if path_.startswith('/pages/'):
            key='images' if 'pages' in changes else 'pages'
            if 'unsupported' in changes:return response({'encrypted':'requires JavaScript'})
            return response({key:[BASE+'/a.png'] if 'short-reader' in changes else [BASE+'/a.png',BASE+'/b.png']})
        return response({},404)

class AutomationTest(unittest.TestCase):
    def baseline(self,site,r=None):
        r=r or profile(); return {'baseline':copy.deepcopy(r),'witness':validate(r,site.http()),'evidence':capture(r,site.http())}
    def repair(self,change,r=None):
        site=Site();state=self.baseline(site,r);site.change.update(change)
        controller=Controller(site.http,site.publish,clock=lambda:1000000)
        reports=[]
        for n in range(3):
            controller.clock=lambda n=n:1000000+n*21600
            state,report=controller.run({'sourceId':ID,'name':'diagnostic'},state);reports.append(report)
        return site,state,reports
    def test_json_field_rename_title_cover_pages(self):
        site,state,reports=self.repair({'title','cover','pages'})
        self.assertEqual(reports[-1]['result'],'REPAIRED',reports)
        self.assertEqual(site.publications,1);self.assertEqual(state['baseline']['operations']['pages']['rows'],'images')
    def test_json_nesting(self):self.assertEqual(self.repair({'nesting'})[2][-1]['result'],'REPAIRED')
    def test_endpoint_and_search_parameter(self):
        site,state,reports=self.repair({'endpoint'});self.assertEqual(reports[-1]['result'],'REPAIRED',reports)
        self.assertEqual(state['baseline']['operations']['search']['parameters']['s'],'{query}')
    def test_domain(self):
        site,state,reports=self.repair({'domain'});self.assertEqual(reports[-1]['result'],'REPAIRED',reports);self.assertIn('publisher-new',state['baseline']['baseUrl'])
    def test_pagination_parameter(self):
        site,state,reports=self.repair({'pagination'});self.assertEqual(reports[-1]['result'],'REPAIRED',reports)
        self.assertEqual(state['baseline']['operations']['popular']['pagination']['pageParameter'],'p')
    def test_css_selector(self):
        site=Site();site.change={'css-old'};r=profile()
        for op in ['popular','latest','search']:
            r['operations'][op].update(format='HTML',rows='div.card',fields={'id':{'path':'','attribute':'data-id'},'url':{'path':'a.link','attribute':'href'},'title':{'path':'h3.title'},'cover':{'path':'img','attribute':'src'}},pagination={'pageParameter':'page','pageSize':2,'nextSelector':'a[rel=next][href]'})
        state=self.baseline(site,r);site.change={'css'};c=Controller(site.http,site.publish,clock=lambda:1000000)
        for n in range(3):c.clock=lambda n=n:1000000+n*21600;state,report=c.run({'sourceId':ID,'name':'diagnostic'},state)
        self.assertEqual(report['result'],'REPAIRED',report);self.assertEqual(site.publications,1)
    def test_invalid_candidate_no_publish(self):
        site,state,reports=self.repair({'pages','invalid'});self.assertEqual(site.publications,0);self.assertEqual(reports[-1]['result'],'REQUIRES_COMPILED_UPDATE')
    def test_unstable_representation_no_publish(self):
        site,state,reports=self.repair({'pages','unstable'});self.assertEqual(site.publications,0);self.assertEqual(reports[-1]['result'],'REQUIRES_COMPILED_UPDATE')
    def test_unsupported_executable_reader_no_publish(self):
        site,state,reports=self.repair({'unsupported'});self.assertEqual(site.publications,0);self.assertEqual(reports[-1]['result'],'REQUIRES_COMPILED_UPDATE');self.assertNotIn(ID,site.public)
    def test_challenge_200_no_publish(self):self.assertEqual(self.repair({'challenge'})[0].publications,0)
    def test_repeated_chapter_page_no_publish(self):self.assertEqual(self.repair({'pages','repeat'})[0].publications,0)
    def test_signed_manifest_tampering_rejected(self):
        r=profile();key=ec.generate_private_key(ec.SECP256R1());public=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode();pem=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption());envelope=sign(r,pem,public);envelope['payload']=envelope['payload'].replace('"revision":1','"revision":2')
        with self.assertRaises(Unsafe):verify(envelope,public,ID)
    def test_malicious_rule_data_rejected(self):
        r=profile();r['operations']['pages']['fields']['image']['transform']='JS'
        with self.assertRaises(Unsafe):schema(r)
        r=profile();r['code']='Kotlin';
        with self.assertRaises(Unsafe):schema(r)
    def test_single_timeout_cannot_publish(self):
        site=Site();state=self.baseline(site);c=Controller(site.http,site.publish,clock=lambda:1000000)
        with patch('automation.controller.validate',side_effect=Transient('NETWORK_TIMEOUT')):state,report=c.run({'sourceId':ID,'name':'diagnostic'},state)
        self.assertEqual(state['failures'],0);self.assertEqual(site.publications,0)
    def test_one_broken_source_leaves_other_healthy(self):
        good=Site();state=self.baseline(good);bad=self.repair({'unsupported'})
        c=Controller(good.http,good.publish,clock=lambda:1000000);_,r=c.run({'sourceId':ID,'name':'independent'},state)
        self.assertEqual(r['health'],'HEALTHY');self.assertEqual(good.publications,0);self.assertEqual(bad[0].publications,0)
    def test_restart_dedup_cooldown_and_strict_revision(self):
        site,state,reports=self.repair({'pages'});self.assertEqual(site.publications,1)
        restored=json.loads(canonical(state));c=Controller(site.http,site.publish,clock=lambda:1100000);new,report=c.run({'sourceId':ID,'name':'diagnostic'},restored)
        self.assertEqual(report['health'],'HEALTHY');self.assertEqual(site.publications,1);self.assertEqual(new['baseline']['revision'],2)
    def test_known_reader_page_count_cannot_truncate(self):
        site=Site();r=profile();w=validate(r,site.http());site.change={'short-reader'}
        with self.assertRaises(Unsafe):validate(r,site.http(),w)
    def test_known_chapter_floor_and_decimal_identity(self):
        site=Site();r=profile();w=validate(r,site.http());m=Interpreter(r,site.http()).catalogue('popular')[0][0];chapters=Interpreter(r,site.http()).chapters(m)
        self.assertEqual(float(chapters[0]['number']),1.5)
        w['works']['m1']['chapters'].append('removed-middle-id')
        with self.assertRaises(Unsafe):validate(r,site.http(),w)

    def test_other_source_state_cannot_be_reused(self):
        site=Site();state=self.baseline(site);state['sourceId']=ID-1
        c=Controller(site.http,site.publish,clock=lambda:1000000)
        with self.assertRaises(Unsafe):c.run({'sourceId':ID,'name':'diagnostic'},state)
        self.assertEqual(site.publications,0)
    def test_other_source_baseline_cannot_be_reused(self):
        site=Site();state=self.baseline(site);state['baseline']['sourceId']=ID-1
        c=Controller(site.http,site.publish,clock=lambda:1000000)
        with self.assertRaises(Unsafe):c.run({'sourceId':ID,'name':'diagnostic'},state)
        self.assertEqual(site.publications,0)
    def test_signed_state_tampering_rejected(self):
        key=ec.generate_private_key(ec.SECP256R1())
        pem=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
        public=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        envelope=seal_state({'revisionFloor':5},pem,public)
        self.assertEqual(open_state(envelope,public)['revisionFloor'],5)
        envelope['body']=envelope['body'].replace('5','1')
        with self.assertRaises(Unsafe):open_state(envelope,public)
    def test_cooldown_blocks_new_repair(self):
        site,state,reports=self.repair({'pages'})
        site.change.add('title');clock=state['publishedAt']+600
        c=Controller(site.http,site.publish,clock=lambda:clock)
        state['failures']=2
        state,report=c.run({'sourceId':ID,'name':'diagnostic'},state)
        self.assertEqual(report['result'],'SOURCE_COOLDOWN');self.assertEqual(site.publications,1)
    def test_reserved_revision_cannot_be_reused(self):
        site=Site();state=self.baseline(site);state['revisionFloor']=9;site.change={'pages'}
        c=Controller(site.http,site.publish)
        for n in range(3):
            c.clock=lambda n=n:1000000+n*21600
            state,report=c.run({'sourceId':ID,'name':'diagnostic'},state)
        self.assertEqual(report['result'],'REPAIRED');self.assertEqual(state['baseline']['revision'],10)
    def test_wrong_signed_source_rejected(self):
        key=ec.generate_private_key(ec.SECP256R1())
        pem=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
        public=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        with self.assertRaises(Unsafe):verify(sign(profile(),pem,public),public,ID-1)
    def test_transient_offline_keeps_known_good(self):
        site=Site();state=self.baseline(site);prior=copy.deepcopy(state['baseline'])
        c=Controller(site.http,site.publish,clock=lambda:1000000)
        with patch('automation.controller.validate',side_effect=Transient('NETWORK_TIMEOUT')):
            updated,report=c.run({'sourceId':ID,'name':'diagnostic'},state)
        self.assertEqual(updated['baseline'],prior);self.assertEqual(site.publications,0)

class PublicationGateTest(unittest.TestCase):
    def setUp(self):
        from automation.publisher import VercelPublisher
        self.site=Site();self.feed='https://feed-test.example';self.staged=None;self.promotes=0;self.tamper=False;self.concurrent=False
        self.key=ec.generate_private_key(ec.SECP256R1())
        self.pem=self.key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
        self.public=base64.b64encode(self.key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        old=profile();other=copy.deepcopy(old);other['sourceId']=ID-1
        self.site.public={ID:sign(old,self.pem,self.public),ID-1:sign(other,self.pem,self.public)}
        test=self
        class Transport(FakeHttp):
            def get(self,url,*args,**kwargs):
                if url.startswith(test.feed+'/'):
                    sid=int(urlsplit(url).path[1:].split('.')[0]);value=test.site.public.get(sid)
                    return Response(url,200 if value else 404,canonical(value).encode(),{})
                return super().get(url,*args,**kwargs)
        class Publisher(VercelPublisher):
            def command(self,args,cwd):
                if args[0]=='deploy':
                    test.staged={int(p.stem):json.loads(p.read_text()) for p in (cwd/'public').glob('*.json')}
                    return 'https://staged-test.vercel.app'
                if args[0]=='curl':
                    value=copy.deepcopy(test.staged[ID])
                    if test.tamper:value['payload']+=' '
                    if test.concurrent:
                        r=profile();r['revision']=3;test.site.public[ID]=sign(r,test.pem,test.public)
                    return canonical(value)
                if args[0]=='promote':
                    test.promotes+=1;test.site.public.update(test.staged);return ''
                raise AssertionError('Unexpected command')
        import tempfile
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.publisher=Publisher(self.public,self.pem,self.feed,{},Path(self.tmp.name),allowed_ids={ID,ID-1},http_factory=lambda:Transport(self.site,1))
    def test_cli_token_is_environment_only(self):
        from automation.publisher import VercelPublisher
        from types import SimpleNamespace
        publisher=VercelPublisher(self.public,self.pem,self.feed,{},Path(self.tmp.name),token='test-ci-credential',allowed_ids={ID})
        def invoke(command,**kwargs):
            self.assertNotIn('--token',command);self.assertNotIn('test-ci-credential',command)
            self.assertEqual(kwargs['env']['VERCEL_TOKEN'],'test-ci-credential')
            self.assertNotIn('MANGARO_AUTOMATION_SECRET',kwargs['env'])
            self.assertFalse(any(k.startswith('GITHUB_') for k in kwargs['env']))
            return SimpleNamespace(returncode=0,stdout='public-response')
        with patch('automation.publisher.subprocess.run',side_effect=invoke):
            self.assertEqual(publisher.command(['curl','/manifest.json'],Path(self.tmp.name)),'public-response')
    def test_executable_catalogue_identity_rejected(self):
        candidate=profile();candidate['revision']=2
        candidate['operations']['popular']['identity']='javascript:alert(1)'
        old=copy.deepcopy(self.site.public)
        with self.assertRaises(Unsafe):self.publisher(candidate)
        self.assertEqual(self.site.public,old);self.assertIsNone(self.staged)
    def test_atomic_publication_preserves_other_source(self):
        old=copy.deepcopy(self.site.public[ID-1]);candidate=profile();candidate['revision']=2
        self.publisher(candidate)
        self.assertEqual(self.promotes,1);self.assertEqual(self.site.public[ID-1],old)
        self.assertEqual(verify(self.site.public[ID],self.public,ID)['revision'],2)
    def test_staged_tampering_cannot_promote(self):
        old=copy.deepcopy(self.site.public);self.tamper=True;candidate=profile();candidate['revision']=2
        with self.assertRaises(Unsafe):self.publisher(candidate)
        self.assertEqual(self.promotes,0);self.assertEqual(self.site.public,old)
    def test_concurrent_publication_cannot_be_overwritten(self):
        self.concurrent=True;candidate=profile();candidate['revision']=2
        with self.assertRaises(Unsafe):self.publisher(candidate)
        self.assertEqual(self.promotes,0);self.assertEqual(verify(self.site.public[ID],self.public,ID)['revision'],3)
    def test_bad_candidate_leaves_previous_public_rule(self):
        old=copy.deepcopy(self.site.public);candidate=profile();candidate['revision']=2
        candidate['operations']['pages']['rows']='missing'
        with self.assertRaises(Unsafe):self.publisher(candidate)
        self.assertIsNone(self.staged);self.assertEqual(self.site.public,old)
    def test_revision_downgrade_rejected(self):
        with self.assertRaises(Unsafe):self.publisher(profile())
        self.assertEqual(self.promotes,0);self.assertIsNone(self.staged)

if __name__=='__main__':unittest.main()
