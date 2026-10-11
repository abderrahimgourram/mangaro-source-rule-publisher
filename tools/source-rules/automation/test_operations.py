"""Deterministic operation gates; fixtures are never production/source-health evidence."""
import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE if (HERE/'operations.py').exists() else HERE.parent/'tools'))
from operations import Coordinator, GitJournal, OperationBlocked, fingerprint, rollback_candidate, scoped_candidate


def rule(revision=1):
    return {'schema':1,'sourceId':42,'revision':revision,'baseUrl':'https://source.example','operations':{'pages':{'rows':'images'}}}


class Store:
    def __init__(self, live):
        self.state={'schema':1,'sources':{'42':{'approved':True,'lastKnownGood':rule(), 'publishedHash':fingerprint(live),'witness':{'complete':True,'ids':['one']}}}}
        self.writes=[]
    def read(self):return copy.deepcopy(self.state)
    def write(self,value):self.state=copy.deepcopy(value);self.writes.append(copy.deepcopy(value))


class OperationsTest(unittest.TestCase):
    def setUp(self):
        self.live=rule();self.store=Store(self.live);self.published=[]
        self.stage=Mock(side_effect=lambda candidate,witness:{'accepted':True,'independent':True,'runtimeVerified':True,'candidateHash':fingerprint(candidate)})
        def publish(candidate,witness):
            self.assertTrue(self.store.state['sources']['42']['pending'])
            self.live=copy.deepcopy(candidate);self.published.append(candidate);return candidate
        self.coordinator=Coordinator(self.store,lambda value:copy.deepcopy(value),lambda candidate,witness:dict(complete=True,ids=['one']),self.stage,publish,lambda source:copy.deepcopy(self.live),clock=lambda:200000)
    def test_verified_repair_checkpoints_before_publication(self):
        candidate=rule(2);candidate['operations']['pages']['rows']='pages'
        result=self.coordinator.apply('42',candidate)
        self.assertEqual(result['action'],'REPAIR_VERIFIED')
        self.assertEqual(len(self.published),1)
        self.assertEqual(self.store.state['sources']['42']['lastKnownGood'],rule())
        self.assertNotIn('pending',self.store.state['sources']['42'])
    def test_missing_authenticated_lkg_blocks_before_staging(self):
        self.store.state['sources']['42'].pop('lastKnownGood')
        with self.assertRaisesRegex(OperationBlocked,'LAST_KNOWN_GOOD'):self.coordinator.apply('42',rule(2))
        self.stage.assert_not_called();self.assertFalse(self.published)
    def test_source_gate_cannot_be_implied_by_http_or_ci_success(self):
        self.store.state['sources']['42']['approved']=False
        with self.assertRaisesRegex(OperationBlocked,'GATE_CLOSED'):self.coordinator.apply('42',rule(2))
    def test_incomplete_acceptance_cannot_sign_or_publish(self):
        self.coordinator.independent=lambda *args:{'complete':False}
        with self.assertRaisesRegex(OperationBlocked,'INCOMPLETE'):self.coordinator.apply('42',rule(2))
        self.assertFalse(self.published)
    def test_staging_must_bind_exact_candidate_and_runtime(self):
        self.stage.side_effect=lambda *args:{'accepted':True,'candidateHash':'wrong','independent':True,'runtimeVerified':False}
        with self.assertRaisesRegex(OperationBlocked,'STAGING_ACCEPTANCE'):self.coordinator.apply('42',rule(2))
        self.assertFalse(self.published)
    def test_concurrent_feed_update_blocks_before_write(self):
        original=self.coordinator.fetch;calls=[]
        self.coordinator.fetch=lambda source: original(source) if not calls.append(source) and len(calls)==1 else rule(9)
        with self.assertRaisesRegex(OperationBlocked,'CONCURRENT'):self.coordinator.apply('42',rule(2))
        self.assertFalse(self.store.writes)
    def test_unstable_independent_validation_blocks(self):
        self.coordinator.independent=Mock(side_effect=[{'complete':True,'ids':['one']},{'complete':True,'ids':['other']}])
        with self.assertRaisesRegex(OperationBlocked,'MISMATCH'):self.coordinator.apply('42',rule(2))
    def test_domain_headers_and_compiled_changes_block(self):
        for field,value in [('baseUrl','https://other.example'),('headers',{'Cookie':'private'}),('schema',2)]:
            candidate=rule(2);candidate[field]=value
            with self.assertRaises(OperationBlocked):self.coordinator.apply('42',candidate)
        self.assertFalse(self.published)
    def test_challenge_transient_and_single_semantic_failure_cannot_rollback(self):
        for classification in ['TRANSIENT_NETWORK_ERROR','BLOCKED_BY_CHALLENGE','CONFIRMED_PARSER_DEFECT']:
            result=self.coordinator.observe_regression('42',lambda *args:{'classification':classification,'confirmedSemantic':classification=='CONFIRMED_PARSER_DEFECT'})
            self.assertNotEqual(result['action'],'ROLLBACK_VERIFIED')
        self.assertFalse(self.published)
    def test_three_spaced_confirmed_revision_bound_failures_rollback(self):
        self.live=rule(2);self.live['operations']['pages']['rows']='bad';self.store.state['sources']['42']['publishedHash']=fingerprint(self.live)
        for now in [200000,203600,207200]:
            self.coordinator.clock=lambda now=now:now
            result=self.coordinator.observe_regression('42',lambda *args:{'classification':'CONFIRMED_CONTENT_LOSS','confirmedSemantic':True})
        self.assertEqual(result['action'],'ROLLBACK_VERIFIED')
        self.assertEqual(self.live['revision'],3)
        self.assertEqual(self.live['operations'],rule()['operations'])
    def test_durable_write_failure_never_publishes(self):
        self.store.write=Mock(side_effect=OperationBlocked('DURABLE_HISTORY_UNAVAILABLE'))
        with self.assertRaises(OperationBlocked):self.coordinator.apply('42',rule(2))
        self.assertFalse(self.published)
    def test_pending_crash_cannot_overwrite_good_revision(self):
        self.store.state['sources']['42']['pending']={'kind':'repair'}
        with self.assertRaisesRegex(OperationBlocked,'RECONCILIATION'):self.coordinator.apply('42',rule(2))
    def test_novel_rollback_preserves_unrelated_sources(self):
        old={'schemaVersion':1,'engineVersion':2,'revision':1,'sources':[{'id':'novel.one','domain':'one.example','selectors':{'text':'.old'}},{'id':'novel.two','domain':'two.example','selectors':{'text':'.original'}}]}
        current=copy.deepcopy(old);current['revision']=7;current['sources'][0]['selectors']['text']='.bad';current['sources'][1]['selectors']['text']='.healthy-new'
        restored=rollback_candidate(current,old,'one.example',8)
        self.assertEqual(restored['sources'][0],old['sources'][0]);self.assertEqual(restored['sources'][1],current['sources'][1])
        changed=copy.deepcopy(restored);changed['sources'][1]=old['sources'][1]
        with self.assertRaisesRegex(OperationBlocked,'UNRELATED'):scoped_candidate(current,changed,'one.example')
    def test_git_journal_requires_verified_scope_and_cas(self):
        responses=[{'object':{'sha':'a'}},{'tree':{'sha':'tree'}},{'tree':[{'path':'journal.json','type':'blob','sha':'blob'}]},{'content':'e30=','size':2},{'object':{'sha':'concurrent'}}]
        api=Mock(side_effect=responses)
        state={'purpose':'SOURCE_OPERATIONS_V1','repository':'owner/repo','schema':1,'sources':{}}
        store=GitJournal(api,'owner/repo',lambda x:x,lambda x:state)
        self.assertEqual(store.read(),state)
        with self.assertRaisesRegex(OperationBlocked,'CONCURRENT'):store.write(state)
        self.assertFalse(any(call.args[0]=='POST' for call in api.call_args_list))

    def test_crash_reconciliation_never_republishes_unknown_revision(self):
        row=self.store.state['sources']['42'];row['pending']={'candidateHash':fingerprint(rule(2)),'beforeHash':fingerprint(rule()),'beforeEnvelope':rule(),'beforeWitness':row['witness']}
        self.live=rule(9)
        with self.assertRaisesRegex(OperationBlocked,'SAFELY_RECONCILED'):self.coordinator.reconcile('42')
        self.assertIn('pending',self.store.state['sources']['42']);self.assertFalse(self.published)
    def test_crash_reconciliation_retains_authenticated_last_good(self):
        row=self.store.state['sources']['42'];row['pending']={'candidateHash':fingerprint(rule(2)),'beforeHash':fingerprint(rule()),'beforeEnvelope':rule(),'beforeWitness':row['witness']}
        self.live=rule(2)
        result=self.coordinator.reconcile('42')
        self.assertEqual(result['action'],'PUBLISHED_OPERATION_RECONCILED_VERIFICATION_REQUIRED')
        self.assertEqual(self.store.state['sources']['42']['lastKnownGood'],rule())
        self.assertFalse(self.published)
    def test_disabled_operation_never_acquires_credentials_or_fetches(self):
        import operation_adapter,os
        from unittest.mock import patch
        with patch.dict(os.environ,{'MANGARO_AUTOPUBLISH_ENABLED':'false'}),patch.object(operation_adapter,'configuration') as configure:
            with self.assertRaisesRegex(OperationBlocked,'AUTOPUBLISH_DISABLED'):operation_adapter.execute()
        configure.assert_not_called()
    def test_signing_contract_with_missing_secret_is_blocked(self):
        import operation_adapter,os
        from unittest.mock import patch
        with patch.dict(os.environ,{'MANGARO_AUTOMATION_SECRET':'{}','RULE_SIGNING_KEY':''}):
            with self.assertRaisesRegex(OperationBlocked,'CREDENTIAL_UNAVAILABLE'):operation_adapter.signing_contract()
    def test_new_operation_workflow_keeps_production_separate(self):
        root=HERE.parents[2] if (HERE/'operations.py').exists() else HERE.parent
        workflow=(root/'.github/workflows/source-operations.yml').read_text()
        self.assertIn("needs.gates.outputs.eligible == 'true' && vars.MANGARO_AUTOPUBLISH_ENABLED == 'true'",workflow)
        self.assertIn('cancel-in-progress: false',workflow)
        self.assertIn('contents: write',workflow)
        self.assertIn('verify_signing',workflow)
        preflight=workflow.split('  gates:',1)[1].split('  signing-contract:',1)[0]
        self.assertNotIn('secrets.',preflight)

    def test_monitoring_and_disabled_operation_runs_cannot_replace_production_queue(self):
        root=HERE.parents[2] if (HERE/'operations.py').exists() else HERE.parent
        manga=(HERE/'operations.py').exists()
        health=(root/'.github/workflows'/('source-rule-publisher.yml' if manga else 'health.yml')).read_text()
        operations=(root/'.github/workflows/source-operations.yml').read_text()
        self.assertIn("|| 'mangaro-source-monitoring'" if manga else "|| 'novel-source-monitoring'",health)
        self.assertIn("format('source-operations-disabled-{0}', github.run_id)",operations)
        self.assertIn("format('source-signing-check-{0}', github.run_id)",operations)
    def test_only_affected_sources_are_eligible_for_signed_operations(self):
        import operation_adapter,reliability
        from unittest.mock import patch
        inventory={'42':{'name':'deterministic fixture'}}
        journal=self.store.read();journal['sources']['42']['witness']['complete']=True
        def configuration():return inventory,lambda x:x,lambda source:rule(),lambda x:x,None
        for failures,classification,expected in [(0,'HEALTHY',[]),(3,'BLOCKED_BY_CHALLENGE',[]),(3,'DEGRADED',['42'])]:
            def restore(path,*args):
                value=reliability.new_state();value['sources']['42']={'classification':classification,'consecutiveFailures':failures};reliability.save(path,value)
            with patch.object(operation_adapter,'configuration',side_effect=configuration),patch.object(operation_adapter,'GitJournal') as store,patch('reliability.restore_history',side_effect=restore):
                store.return_value.read.return_value=journal
                result=operation_adapter.readiness()
            self.assertEqual(result['eligible'],expected)

if __name__=='__main__':unittest.main()
