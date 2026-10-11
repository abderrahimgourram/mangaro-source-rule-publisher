"""Offline state-machine and workflow safety regressions; no production credentials."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
TOOLS = HERE if (HERE / 'reliability.py').exists() else HERE.parent / 'tools'
sys.path.insert(0, str(TOOLS))
import reliability as r
ROOT = TOOLS.parents[2] if TOOLS.name == 'automation' else TOOLS.parent
INV = [{'id': 'one', 'name': 'One'}, {'id': 'two', 'name': 'Two'}]


def healthy():
    return dict(classification='HEALTHY', ok=True, complete=True, operations={'details': 'PASS', 'chapters': 'PASS', 'reader': 'PASS'},
                evidence='NATIVE', chapterCount=10, identityHash='fixture', reason='VERIFIED')


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.state = r.new_state()
    def record(self, obs, now=100000, mode='monitor'):
        return r.record(self.state, 'one', obs, mode, now)
    def test_healthy_native_baseline(self):
        self.record(healthy(), mode='deep')
        self.assertEqual(self.state['sources']['one']['lastVerifiedHealthy'], 100000)
    def test_lightweight_does_not_claim_native_acceptance(self):
        self.record(healthy())
        self.assertNotIn('lastVerifiedHealthy', self.state['sources']['one'])
    def test_transient_retry_hour_and_bounded_backoff(self):
        obs = dict(classification='TRANSIENT_NETWORK_ERROR', ok=False, operations={'details': 'FAILED'}, reason='NETWORK_ERROR')
        self.record(healthy(), mode='deep')
        first = self.record(obs, 101000)
        self.assertFalse(first['notify'])
        self.assertEqual(r.select(self.state, INV, 'retry', 104599), [])
        self.assertEqual(r.select(self.state, INV, 'retry', 104600), ['one'])
        for attempt in range(3):
            r.begin(self.state, ['one'], 'retry', 104600 + attempt*20000)
            row = self.record(obs, 104601 + attempt*20000, 'retry')
        self.assertEqual(row['classification'], 'TRANSIENT_NETWORK_ERROR')
        self.assertEqual(row['retryAt'], 0)
        self.assertEqual(self.state['sources']['one']['lastVerifiedHealthy'], 100000)
        self.assertEqual(r.select(self.state, INV, 'retry', 999999), [])
    def test_repeated_failure_is_not_parser_proof(self):
        for now in [100000, 110000, 120000]:
            row = self.record(dict(classification='DEGRADED', ok=False, operations={'chapters': 'FAILED'}, reason='CHAPTER_PAGE_MISSING'), now)
        self.assertEqual(row['classification'], 'DEGRADED')
        self.assertTrue(row['needsDeep'])
    def test_recovery_preserves_history_and_resets_only_on_success(self):
        self.record(dict(classification='BLOCKED_BY_CHALLENGE', ok=False, reason='CHALLENGE', operations={}))
        self.record(dict(classification='TRANSIENT_NETWORK_ERROR', ok=False, reason='NETWORK_ERROR', operations={}), 110000)
        self.assertEqual(self.state['sources']['one']['consecutiveFailures'], 2)
        row = self.record(healthy(), 120000, 'deep')
        self.assertTrue(row['notify'])
        self.assertEqual(row['consecutiveFailures'], 0)
        self.assertEqual(row['retryAt'], 0)
    def test_unknown_credentials_cannot_reset_failures(self):
        self.record(dict(classification='DEGRADED', ok=False, operations={}, reason='FAILED'))
        self.record(dict(classification='INSUFFICIENT_EVIDENCE', unknown=True, operations={}, reason='SECRET_MISSING'))
        self.assertEqual(self.state['sources']['one']['consecutiveFailures'], 1)
    def test_dependency_failure_cannot_mark_reader_success(self):
        obs = healthy(); obs['operations']['details'] = 'FAILED'
        row = self.record(obs)
        self.assertEqual(row['operations']['reader'], 'DEPENDENT_FAILURE')
        self.assertEqual(row['classification'], 'DEGRADED')
    def test_partial_index_cannot_be_healthy(self):
        obs = healthy(); obs['complete'] = False; obs['operations']['chapters'] = 'PARTIAL'
        self.assertEqual(self.record(obs)['classification'], 'DEGRADED')
    def test_missing_chapters_preserve_verified_count(self):
        self.record(healthy(), mode='deep')
        obs = healthy(); obs['chapterCount'] = 9
        row = self.record(obs, 120000, 'deep')
        self.assertEqual(row['classification'], 'DEGRADED')
        self.assertEqual(self.state['sources']['one']['verifiedChapterCount'], 10)
    def test_transition_deduplication(self):
        obs = dict(classification='BLOCKED_BY_CHALLENGE', ok=False, operations={}, reason='CHALLENGE')
        self.record(obs)
        self.assertTrue(self.record(obs, 110000)['notify'])
        self.assertFalse(self.record(obs, 120000)['notify'])
    def test_cancelled_lease_does_not_reset_counters(self):
        self.record(dict(classification='DEGRADED', ok=False, operations={}, reason='FAILED'))
        r.begin(self.state, ['one'], 'deep', 110000)
        self.assertEqual(self.state['sources']['one']['consecutiveFailures'], 1)
        self.assertNotIn('one', r.select(self.state, INV, 'monitor', 110001))
    def test_daily_no_work_skips_expensive_tier(self):
        for sid in ['one', 'two']:
            r.record(self.state, sid, healthy(), 'deep', 100000)
        self.assertEqual(r.select(self.state, INV, 'deep', 200000), [])
        self.assertEqual(r.select(self.state, INV, 'weekly', 200000), ['one', 'two'])
    def test_daily_rotation_has_strict_budget(self):
        inv = [{'id': str(n), 'name': str(n)} for n in range(10)]
        self.assertEqual(len(r.select(self.state, inv, 'deep', 100000)), 2)
    def test_authenticated_history_checks_scope_and_expiration(self):
        art = {'id': 1, 'expired': True, 'workflow_run': {'id': 10, 'head_branch': 'main'}}
        def api(path):
            return {'artifacts': [art]} if '/artifacts?' in path else dict(path='.github/workflows/health.yml', head_branch='main', event='schedule', status='completed')
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(r.ReliabilityError, 'EXPIRED'):
                r.restore_history(Path(tmp)/'state.json', 'owner/repo', 'health.yml', 'main', api)
    def test_unauthenticated_api_cannot_bootstrap_empty_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(r.ReliabilityError):
                r.restore_history(Path(tmp)/'state.json', 'owner/repo', 'health.yml', 'main', lambda path: (_ for _ in ()).throw(r.ReliabilityError('GITHUB_HISTORY_UNAVAILABLE')))
            self.assertFalse((Path(tmp)/'state.json').exists())
    def test_first_migration_does_not_assume_sources_healthy(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'state.json'
            r.restore_history(path, 'owner/repo', 'health.yml', 'main', lambda path: {'artifacts': []} if '/artifacts?' in path else {'workflow_runs': []})
            self.assertEqual(r.load(path)['sources'], {})
    def test_state_atomic_roundtrip_and_corruption_rejection(self):
        self.record(healthy(), mode='deep')
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'state.json'; r.save(path, self.state)
            self.assertEqual(r.load(path), self.state)
            data = json.loads(path.read_text()); data['state']['sources']['one']['consecutiveFailures'] = 999
            path.write_text(json.dumps(data))
            with self.assertRaises(r.ReliabilityError): r.load(path)
    def test_concurrent_writer_fails_without_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'state.json'
            with r.locked(path):
                with self.assertRaisesRegex(r.ReliabilityError, 'BUSY'):
                    with r.locked(path): pass
    def test_report_retains_no_response_or_credentials(self):
        self.record(healthy(), mode='deep')
        with tempfile.TemporaryDirectory() as tmp:
            row = self.record(dict(classification='DEGRADED', ok=False, reason='SAFE_CODE', operations={}, body='PRIVATE_HTML', cookies='PRIVATE_COOKIE', token='PRIVATE_TOKEN'))
            output = Path(tmp)/'report.json'
            r.report(output, [row], INV, 'monitor', __import__('time').monotonic(), ['one'])
            for secret in ['PRIVATE_HTML', 'PRIVATE_COOKIE', 'PRIVATE_TOKEN']:
                self.assertNotIn(secret, output.read_text())


    def test_invalid_history_response_cannot_bootstrap(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'state.json'
            with self.assertRaisesRegex(r.ReliabilityError, 'RESPONSE_INVALID'):
                r.restore_history(path, 'owner/repo', 'health.yml', 'main', lambda _: {})
            self.assertFalse(path.exists())
    def test_existing_history_cannot_be_silently_bootstrapped(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'state.json'; self.record(healthy()); r.save(path, self.state)
            before = path.read_bytes()
            with self.assertRaisesRegex(r.ReliabilityError, 'NOT_REPLACED'):
                r.restore_history(path, 'owner/repo', 'health.yml', 'main', lambda _: {'artifacts': []})
            self.assertEqual(before, path.read_bytes())
    def test_deleted_persisted_history_does_not_reset(self):
        def api(url):
            if '/artifacts?' in url: return {'artifacts': []}
            if '/workflows/' in url: return {'workflow_runs': [{'id': 1, 'head_branch': 'main', 'event': 'schedule'}]}
            return {'jobs': [{'steps': [{'name': 'Persist source monitoring history', 'conclusion': 'success'}]}]}
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(r.ReliabilityError, 'MISSING_NO_RESET'):
                r.restore_history(Path(tmp)/'state.json', 'owner/repo', 'health.yml', 'main', api)
    def test_authenticated_history_restores_counters(self):
        old = r.new_state(); r.record(old, 'one', dict(classification='DEGRADED', operations={}, ok=False), 'monitor', 100000)
        old.update(repository='owner/repo', workflow='health.yml')
        def api(url):
            return {'artifacts': [{'id': 2, 'expired': False, 'workflow_run': {'id': 10}}]} if '/artifacts?' in url else dict(path='.github/workflows/health.yml', head_branch='main', event='schedule', status='completed')
        def download(command, **kwargs):
            r.save(Path(command[-1])/'state.json', old)
        with tempfile.TemporaryDirectory() as tmp, patch('reliability.subprocess.run', side_effect=download):
            path = Path(tmp)/'state.json'
            self.assertEqual(r.restore_history(path, 'owner/repo', 'health.yml', 'main', api), 'AUTHENTICATED_GITHUB_HISTORY')
            self.assertEqual(r.load(path)['sources']['one']['consecutiveFailures'], 1)
    def test_empty_authenticated_history_is_blocked_without_reset(self):
        empty = r.new_state(); empty.update(repository='owner/repo', workflow='health.yml')
        def api(url):
            return {'artifacts': [{'id': 2, 'expired': False, 'workflow_run': {'id': 10}}]} if '/artifacts?' in url else dict(path='.github/workflows/health.yml', head_branch='main', event='schedule', status='completed')
        def download(command, **kwargs):
            r.save(Path(command[-1])/'state.json', empty)
        with tempfile.TemporaryDirectory() as tmp, patch('reliability.subprocess.run', side_effect=download):
            path = Path(tmp)/'state.json'
            with self.assertRaisesRegex(r.ReliabilityError, 'AUTHENTICATED_HISTORY_EMPTY_NO_RESET'):
                r.restore_history(path, 'owner/repo', 'health.yml', 'main', api)
            self.assertFalse(path.exists())

    def test_periodic_failure_does_not_postpone_due_retry(self):
        failed = dict(classification='TRANSIENT_NETWORK_ERROR', operations={}, ok=False)
        self.record(failed, 100000); retry = self.state['sources']['one']['retryAt']
        self.record(failed, 100100)
        self.assertEqual(self.state['sources']['one']['retryAt'], retry)
    def test_zero_check_cannot_claim_selected_source_coverage(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(r.ReliabilityError, 'INCOMPLETE_CHECK_COVERAGE'):
                r.report(Path(tmp)/'report.json', [], INV, 'monitor', __import__('time').monotonic(), ['one'])
    def test_empty_fixture_is_explicitly_no_sources_due(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = r.report(Path(tmp)/'report.json', [], [], 'monitor', __import__('time').monotonic(), [])
            self.assertEqual(result['coverage'], 'NO_SOURCES_DUE')
            self.assertEqual(result['registeredCount'], 0)

    def test_authenticated_old_generation_cannot_overwrite_current_state(self):
        remote=r.new_state();remote.update(repository='owner/repo',workflow='health.yml')
        def api(url):return {'artifacts':[{'id':1,'expired':False,'workflow_run':{'id':10}}]} if '/artifacts?' in url else dict(path='.github/workflows/health.yml',head_branch='main',event='schedule',status='completed')
        def download(command,**kwargs):r.save(Path(command[-1])/'state.json',remote)
        with tempfile.TemporaryDirectory() as tmp,patch('reliability.subprocess.run',side_effect=download):
            path=Path(tmp)/'state';local=copy.deepcopy(remote);local['generation']=3;r.save(path,local);before=path.read_bytes()
            with self.assertRaisesRegex(r.ReliabilityError,'ROLLBACK_REFUSED'):r.restore_history(path,'owner/repo','health.yml','main',api)
            self.assertEqual(path.read_bytes(),before)

    def test_shared_outage_suppresses_alerts_without_losing_future_transition(self):
        inventory=[{'id':str(n),'name':str(n)} for n in range(3)]
        failed=dict(classification='TRANSIENT_NETWORK_ERROR',ok=False,reason='NETWORK_ERROR',operations={})
        for source in inventory:r.record(self.state,source['id'],failed,'monitor',100000)
        rows=[r.record(self.state,source['id'],failed,'monitor',110000) for source in inventory]
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'state'
            result=r.report(Path(tmp)/'report',rows,inventory,'monitor',__import__('time').monotonic(),[s['id'] for s in inventory],state=self.state,state_path=path)
            restored=r.load(path)
        self.assertEqual(result['infrastructure'],'SHARED_NETWORK_FAILURE_SUSPECTED')
        self.assertTrue(all(not row['notify'] for row in rows))
        self.assertEqual(restored['sources']['0']['consecutiveFailures'],2)
        self.assertTrue(r.record(restored,'0',failed,'monitor',120000)['notify'])


class CoverageTest(unittest.TestCase):
    def test_scheduled_inventory_and_checks_cover_every_eligible_source(self):
        # Deterministic orchestration fixture, never claimed as live source evidence.
        import plan
        module_name = 'automation.monitor' if TOOLS.name == 'automation' else 'monitor'
        import importlib
        monitor = importlib.import_module(module_name)
        manga = TOOLS.name == 'automation'; system = 'manga' if manga else 'novel'
        inv = plan.inventory(ROOT, system)
        self.assertEqual(len(inv), 8 if manga else 4)
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp); event = tmp/'event.json'; event.write_text(json.dumps({'schedule':'17 */6 * * *' if manga else '43 */12 * * *'}))
            state = tmp/'state.json'; output = tmp/'plan.json'; planned = tmp/'planned.json'; report = tmp/'report.json'
            with patch('plan.Path.cwd', return_value=ROOT), patch.dict(os.environ, {'GITHUB_EVENT_NAME':'schedule','GITHUB_EVENT_PATH':str(event)}), patch.object(sys,'argv',['plan','--system',system,'--state',str(state),'--output',str(output),'--report',str(planned)]):
                self.assertEqual(plan.main(),0)
            planned_report=json.loads(planned.read_text())
            self.assertEqual(planned_report['phase'],'PLANNING_ONLY')
            self.assertEqual(planned_report['coverage'],'NOT_EXECUTED')
            self.assertEqual(planned_report['eligibleCount'],len(inv))
            self.assertEqual(set(json.loads(output.read_text())['sources']),{s['id'] for s in inv})
            seen=[]
            def fixture(source,*args):
                seen.append(source['id'] if manga else source['domain'])
                return dict(classification='INSUFFICIENT_EVIDENCE',unknown=True,operations={'details':'SKIPPED'},reason='DETERMINISTIC_COVERAGE_FIXTURE')
            target='lightweight' if manga else 'check_source'
            with patch.object(monitor,target,side_effect=fixture), patch.object(sys,'argv',['monitor','--plan',str(output),'--state',str(state),'--report',str(report)]):
                monitor.main()
            checked=json.loads(report.read_text())
            self.assertEqual(set(seen),{s['id'] for s in inv})
            self.assertEqual(checked['checkedCount'],len(inv))
            self.assertEqual(checked['coverage'],'ALL_ELIGIBLE_ATTEMPTED')
            self.assertTrue(all(row['classification']!='HEALTHY' for row in checked['sources']))


    def test_due_retry_and_noop_deep_use_persisted_state(self):
        import plan
        inv=plan.inventory(ROOT,'manga' if TOOLS.name=='automation' else 'novel')
        state=r.new_state();now=int(__import__('time').time())
        for source in inv:r.record(state,source['id'],healthy(),'deep',now-100000)
        first=inv[0]['id']
        r.record(state,first,dict(classification='TRANSIENT_NETWORK_ERROR',ok=False,operations={},reason='NETWORK_ERROR'),'monitor',now-3700)
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);path=tmp/'state.json';r.save(path,state)
            self.assertEqual(r.select(r.load(path),inv,'retry',now),[first])
            # The remaining healthy sources require no daily deep work.
            state['sources'][first]['needsDeep']=False;r.save(path,state)
            output=tmp/'output';event=tmp/'event';event.write_text(json.dumps({'schedule':'29 3 * * *'}))
            with patch('plan.Path.cwd',return_value=ROOT),patch.dict(os.environ,{'GITHUB_EVENT_NAME':'schedule','GITHUB_EVENT_PATH':str(event),'GITHUB_OUTPUT':str(output)}),patch.object(sys,'argv',['plan','--system','manga' if TOOLS.name=='automation' else 'novel','--state',str(path),'--output',str(tmp/'plan'),'--report',str(tmp/'report')]):
                self.assertEqual(plan.main(),0)
            self.assertIn('work=false',output.read_text())
            self.assertIn('native=false',output.read_text())
            self.assertEqual(r.load(path)['sources'][first]['consecutiveFailures'],1)
    def test_stale_plan_does_not_overwrite_persisted_history(self):
        import plan,importlib
        manga=TOOLS.name=='automation';monitor=importlib.import_module('automation.monitor' if manga else 'monitor')
        inv=plan.inventory(ROOT,'manga' if manga else 'novel');state=r.new_state();state['generation']=2
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);path=tmp/'state';r.save(path,state);before=path.read_bytes()
            p=tmp/'plan';p.write_text(json.dumps(dict(sources=[inv[0]['id']],inventory=inv,generation=1,mode='monitor')))
            with patch.object(sys,'argv',['monitor','--state',str(path),'--plan',str(p),'--report',str(tmp/'report')]):
                with self.assertRaisesRegex(Exception,'STALE_PLAN'):monitor.main()
            self.assertEqual(path.read_bytes(),before)
    def test_documentation_changes_keep_required_check_without_expense(self):
        import changes
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);event=tmp/'event';output=tmp/'out';summary=tmp/'summary'
            event.write_text(json.dumps({'number':1,'pull_request':{}}))
            with patch.dict(os.environ,{'GITHUB_EVENT_PATH':str(event),'GITHUB_REPOSITORY':'owner/repo','GITHUB_OUTPUT':str(output),'GITHUB_STEP_SUMMARY':str(summary)}),patch('changes.gh_json',return_value=[{'filename':'README.md'}]):changes.main()
            self.assertIn('fast=false',output.read_text())
            self.assertIn('native=false',output.read_text())
            self.assertTrue(summary.exists())

    def test_attestation_and_registry_changes_require_fast_checks(self):
        import changes
        for changed in ['vercel-seanovel/api/probe.py','feed.json','rules/source.json']:
            with tempfile.TemporaryDirectory() as tmp:
                tmp=Path(tmp);event=tmp/'event';output=tmp/'out';summary=tmp/'summary';event.write_text(json.dumps({'number':1,'pull_request':{}}))
                with patch.dict(os.environ,{'GITHUB_EVENT_PATH':str(event),'GITHUB_REPOSITORY':'owner/repo','GITHUB_OUTPUT':str(output),'GITHUB_STEP_SUMMARY':str(summary)}),patch('changes.gh_json',return_value=[{'filename':changed}]):changes.main()
                self.assertIn('fast=true',output.read_text())

class WorkflowTest(unittest.TestCase):
    def test_workflow_gates_and_schedules(self):
        manga = TOOLS.name == 'automation'
        file = ROOT/'.github/workflows'/('source-rule-publisher.yml' if manga else 'health.yml')
        text = file.read_text()
        for cron in ['17 */6 * * *' if manga else '43 */12 * * *', '47 */3 * * *' if manga else '53 */3 * * *', '29 3 * * *', '7 4 * * 0']:
            self.assertIn(cron, text)
        self.assertIn("MANGARO_AUTOPUBLISH_ENABLED: 'false'", text)
        self.assertNotIn('vars.MANGARO_AUTOPUBLISH_ENABLED', text)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn('actions: read', text)
        self.assertNotIn('MANGARO_AUTOMATION_SECRET: ${{ secrets.MANGARO_AUTOMATION_SECRET }}', text)
        if manga:
            light = text.split('  lightweight:', 1)[1].split('  native:', 1)[0]
            for forbidden in ['gradlew', 'emulator-runner', 'setup-java', 'SIGNING', 'MANGARO_VERCEL_TOKEN']:
                self.assertNotIn(forbidden, light)
            self.assertIn("needs.plan.outputs.native == 'true'", text)
    def test_yaml_validation_if_available(self):
        try: import yaml
        except ImportError: self.skipTest('PyYAML is an optional local validation tool; actionlint runs separately')
        for file in (ROOT/'.github/workflows').glob('*.yml'):
            parsed = yaml.safe_load(file.read_text())
            self.assertIsInstance(parsed['jobs'], dict)


if __name__ == '__main__':
    unittest.main()
