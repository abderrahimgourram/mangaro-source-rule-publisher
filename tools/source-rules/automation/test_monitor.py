"""Fixture-backed manga transport and fail-closed native evidence tests."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from automation.monitor import lightweight, native_observation
from automation.core import validate, Transient
from test_automation import Site, profile, ID, BASE

class MonitorTest(unittest.TestCase):
    def setUp(self):
        self.site=Site(); self.rule=profile()
        self.row={'profile':self.rule, 'witness':validate(self.rule,self.site.http()), 'imageHosts':['publisher-test.example']}
        self.source={'id':str(ID),'name':'Fixture'}
    def test_real_operation_shape_in_fixture_is_required_for_healthy(self):
        result=lightweight(self.source,self.row,'unused',self.site.http())
        self.assertTrue(result['ok'],result)
        self.assertTrue(result['complete'])
        self.assertEqual(result['classification'],'HEALTHY')
        self.assertLessEqual(self.site.clients,3)
    def test_invalid_details_never_reaches_chapters_or_reader(self):
        http=self.site.http(); original=http.get
        def wrong_identity(url,*args,**kwargs):
            response=original(url,*args,**kwargs)
            if '/details/' in url:
                body=json.loads(response.body);body['post']['id']='wrong-work'
                response.body=json.dumps(body).encode()
            return response
        http.get=wrong_identity
        result=lightweight(self.source,self.row,'unused',http)
        self.assertFalse(result['ok'])
        self.assertEqual(result['operations']['details'],'FAILED')
        self.assertEqual(result['operations']['reader'],'SKIPPED')
    def test_unapproved_cdn_cannot_be_automatically_authorized(self):
        self.row['imageHosts']=['different.example']
        result=lightweight(self.source,self.row,'unused',self.site.http())
        self.assertEqual(result['classification'],'UNVERIFIED_TRANSPORT')
        self.assertFalse(result['ok'])
    def test_missing_chapter_page_is_not_complete(self):
        self.site.change={'invalid'}
        result=lightweight(self.source,self.row,'unused',self.site.http())
        self.assertFalse(result['ok'])
        self.assertEqual(result['operation'],'chapters')
    def test_native_missing_decode_is_unverified(self):
        result=native_observation(self.source,{'steps':{'popular':'PASS'},'observations':[]})
        self.assertEqual(result['classification'],'INSUFFICIENT_EVIDENCE')
    def test_native_dependency_failure_cannot_pass_reader(self):
        steps={k:'PASS' for k in ['popular','pagination','latest','search','details-0','chapters-0','reader','image-decode']}
        steps['details-0']='SEMANTIC_ERROR'
        result=native_observation(self.source,{'steps':steps,'observations':[]})
        self.assertFalse(result['ok'])
        self.assertEqual(result['operations']['reader'],'DEPENDENT_FAILURE')
    def test_production_disabled_even_with_enabled_variable(self):
        script=Path(__file__).with_name('ci-monitor.py')
        result=subprocess.run([sys.executable,str(script)],env={**os.environ,'AUTOPUBLISH_ENABLED':'true','REQUESTED_MODE':'production'},capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('PRODUCTION_PUBLICATION_DISABLED',result.stdout)
    def test_scheduled_monitor_contains_no_publisher(self):
        script=Path(__file__).with_name('monitor.py').read_text()
        self.assertNotIn('VercelPublisher',script)
        self.assertNotIn('MANGARO_AUTOMATION_SECRET',script)
        self.assertNotIn('signingKey',script)

if __name__=='__main__':unittest.main()
