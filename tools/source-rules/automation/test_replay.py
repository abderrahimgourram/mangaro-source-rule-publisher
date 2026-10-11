"""Read-only native witnesses: deterministic fixtures cannot authorize production."""
import copy
import json
from pathlib import Path
import sys
import unittest
from urllib.parse import urljoin
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from automation.core import Unsafe
from automation.replay import contracts, check, safe_request
from test_automation import Site, BASE, ID

class ReplayTest(unittest.TestCase):
    def setUp(self):
        self.site=Site();http=self.site.http()
        traces=[]
        for role,url in [('popular','/popular'),('pagination','/popular?page=2'),('latest','/latest'),('search','/search?q=Title%201'),('details-0','/details/m1'),('details-0','/chapters/m1'),('reader','/pages/m1/c1')]:
            response=http.get(BASE+url)
            traces.append(dict(operation=role,url=BASE+url,finalUrl=response.url,status=200,method='GET',body=response.body.decode(),publicHeaders={}))
        self.native=dict(baseUrl=BASE,steps={k:'PASS' for k in ['popular','pagination','latest','search','details-0','chapters-0','reader','image-decode']},traces=traces,
            observations=[dict(operation='details',input=dict(url='/m1',memo={'id':'m1'}),manga=dict(title='Title 1'),completeness='COMPLETE',chapters=[dict(url='/m1/c1',memo={'id':'m1-c1'}),dict(url='/m1/c2',memo={'id':'m1-c2'})]),dict(operation='pages',images=[BASE+'/a.png',BASE+'/b.png'])])
        self.row=dict(replayContracts=contracts(self.native),imageHosts=['publisher-test.example'])
        self.source={'id':str(ID),'name':'Fixture'}
    def test_matching_native_witness_is_provisional_never_healthy(self):
        result=check(self.source,self.row,self.site.http())
        self.assertTrue(result['ok'],result)
        self.assertEqual(result['classification'],'DEGRADED')
        self.assertFalse(result['complete'])
        self.assertEqual(result['operations']['details'],'PASS')
        self.assertEqual(result['operations']['image'],'PASS')
    def test_failed_native_evidence_cannot_seed_contracts(self):
        self.native['steps']['image-decode']='SEMANTIC_ERROR'
        self.assertEqual(contracts(self.native),[])
    def test_private_request_or_foreign_domain_is_not_replayed(self):
        for url in [BASE+'/api?token=private',BASE+'/api?input=%7B%22nonce%22%3A%22private%22%7D','https://foreign.example/api']:
            with self.assertRaises(Unsafe):safe_request(url,BASE)
    def test_wrong_detail_identity_blocks_reader(self):
        http=self.site.http();original=http.get
        def wrong(url,*args,**kwargs):
            response=original(url,*args,**kwargs)
            if '/details/' in url:
                body=json.loads(response.body);body['post']['id']='another-work';response.body=json.dumps(body).encode()
            return response
        http.get=wrong
        result=check(self.source,self.row,http)
        self.assertFalse(result['ok'])
        self.assertEqual(result['operations']['reader'],'DEPENDENT_FAILURE')
    def test_known_chapter_drop_cannot_pass(self):
        http=self.site.http();original=http.get
        def missing(url,*args,**kwargs):
            response=original(url,*args,**kwargs)
            if '/chapters/' in url:
                body=json.loads(response.body);body['chapters']=body['chapters'][:1];response.body=json.dumps(body).encode()
            return response
        http.get=missing
        result=check(self.source,self.row,http)
        self.assertFalse(result['ok'])
        self.assertEqual(result['reason'],'KNOWN_CHAPTER_IDENTITIES_LOST')
    def test_unapproved_image_domain_needs_native_verification(self):
        self.row['imageHosts']=['different.example']
        result=check(self.source,self.row,self.site.http())
        self.assertEqual(result['classification'],'UNVERIFIED_TRANSPORT')
    def test_challenge_is_not_a_matching_representation(self):
        self.site.change={'challenge'}
        result=check(self.source,self.row,self.site.http())
        self.assertEqual(result['classification'],'BLOCKED_BY_CHALLENGE')
    def test_chapters_without_verified_details_are_dependent(self):
        self.row['replayContracts']=[c for c in self.row['replayContracts'] if c['role']!='details-0']
        result=check(self.source,self.row,self.site.http())
        self.assertEqual(result['operations']['chapters'],'DEPENDENT_FAILURE')
        self.assertFalse(result['ok'])

if __name__=='__main__':unittest.main()
