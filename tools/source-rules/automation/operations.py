"""Fail-closed source-scoped publication/rollback coordinator.

Adapters authenticate every revision and persist the journal *before* external writes.
Observational health artifacts never authorize publication. Remote HTML cannot execute.
"""
import copy
import hashlib
import json
import time


class OperationBlocked(ValueError):
    pass


def require(value, code):
    if not value:
        raise OperationBlocked(code)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def source_record(rule, source):
    if 'sourceId' in rule:
        require(str(rule['sourceId']) == str(source), 'SOURCE_IDENTITY_MISMATCH')
        return rule
    matches = [row for row in rule.get('sources', []) if row.get('domain') == source]
    require(len(matches) == 1, 'SOURCE_IDENTITY_MISMATCH')
    return matches[0]


def scoped_candidate(current, candidate, source):
    """Only existing declarative fields may change; every other source stays exact."""
    old = source_record(current, source); new = source_record(candidate, source)
    require(candidate['revision'] > current['revision'], 'REVISION_REUSE')
    if 'sourceId' in current:
        for key in ('sourceId', 'schema', 'baseUrl', 'headers'):
            require(old.get(key) == new.get(key), 'IDENTITY_OR_TRANSPORT_CHANGE_REQUIRES_REVIEW')
        require(set(old) == set(new), 'RULE_SHAPE_CHANGE_REQUIRES_REVIEW')
    else:
        require({k: v for k, v in current.items() if k not in {'sources', 'revision'}} ==
                {k: v for k, v in candidate.items() if k not in {'sources', 'revision'}}, 'ENGINE_CHANGE_REQUIRES_REVIEW')
        require(set(old) == set(new) and old['id'] == new['id'] and old['domain'] == new['domain'], 'SOURCE_IDENTITY_MISMATCH')
        require([r for r in current['sources'] if r['domain'] != source] ==
                [r for r in candidate['sources'] if r['domain'] != source], 'UNRELATED_SOURCE_CHANGED')


def rollback_candidate(current, last_good, source, revision):
    """Reissue authenticated mappings at a new revision, preserving unrelated sources."""
    result = copy.deepcopy(current)
    if 'sourceId' in current:
        result = copy.deepcopy(source_record(last_good, source))
    else:
        old = source_record(last_good, source)
        result['sources'] = [copy.deepcopy(old) if row['domain'] == source else row for row in result['sources']]
    result['revision'] = revision
    scoped_candidate(current, result, source)
    return result


class Coordinator:
    def __init__(self, store, authenticate, independent, stage, publish, fetch, clock=time.time):
        # store.read/write must authenticate state and use a durable compare-and-swap.
        self.store, self.authenticate, self.independent = store, authenticate, independent
        self.stage, self.publish, self.fetch, self.clock = stage, publish, fetch, clock

    def context(self, source):
        journal = self.store.read()
        require(journal.get('schema') == 1, 'AUTHENTICATED_OPERATION_HISTORY_REQUIRED')
        row = journal.get('sources', {}).get(str(source))
        require(row and row.get('approved') is True, 'SOURCE_PUBLICATION_GATE_CLOSED')
        require(not row.get('pending'), 'PENDING_OPERATION_REQUIRES_RECONCILIATION')
        require(row.get('lastKnownGood') and row.get('witness'), 'AUTHENTICATED_LAST_KNOWN_GOOD_REQUIRED')
        old = self.authenticate(row['lastKnownGood'])
        source_record(old, source)
        live = self.fetch(source); current = self.authenticate(live)
        source_record(current, source)
        require(row.get('publishedHash') == fingerprint(live), 'PUBLISHED_REVISION_CHANGED')
        return journal, row, live, current, old

    def verify(self, candidate, witness):
        # Separate clients/passes; adapters require full identity/ownership/completeness.
        first = self.independent(candidate, witness)
        second = self.independent(candidate, witness)
        require(first.get('complete') is True and second.get('complete') is True, 'INCOMPLETE_ACCEPTANCE')
        require(fingerprint(first) == fingerprint(second), 'INDEPENDENT_ACCEPTANCE_MISMATCH')
        return first

    def apply(self, source, candidate, kind='repair'):
        journal, row, live, current, old = self.context(source)
        if kind == 'rollback':
            failures = row.get('publishedFailures', [])
            require(len(failures) >= 3 and failures[-1]['at'] - failures[0]['at'] >= 3600,
                    'PUBLISHED_REGRESSION_NOT_CONFIRMED')
            require(all(f.get('revisionHash') == fingerprint(live) and f.get('confirmedSemantic') is True for f in failures[-3:]),
                    'REGRESSION_NOT_BOUND_TO_PUBLISHED_REVISION')
            candidate = rollback_candidate(current, old, source, max(current['revision'], row.get('revisionFloor', 0))+1)
        else:
            require(kind == 'repair', 'INVALID_OPERATION')
        scoped_candidate(current, candidate, source)
        witness = self.verify(candidate, row['witness'])
        # The staging adapter must verify exact signed revision AND actual runtime acceptance.
        receipt = self.stage(candidate, witness)
        require(receipt.get('candidateHash') == fingerprint(candidate) and receipt.get('accepted') is True
                and receipt.get('independent') is True and receipt.get('runtimeVerified') is True,
                'INDEPENDENT_STAGING_ACCEPTANCE_REQUIRED')
        require(fingerprint(self.fetch(source)) == fingerprint(live), 'CONCURRENT_PUBLICATION')
        row['revisionFloor'] = max(row.get('revisionFloor', 0), candidate['revision'])
        row['pending'] = {'kind': kind, 'candidateHash': fingerprint(candidate), 'beforeHash': fingerprint(live), 'beforeEnvelope': live, 'beforeWitness': copy.deepcopy(row['witness']), 'at': int(self.clock())}
        self.store.write(journal)  # Durable, signed, CAS reservation BEFORE publication.
        published = self.publish(candidate, row['witness'])
        require(self.authenticate(published) == candidate, 'SIGNED_PUBLICATION_MISMATCH')
        require(fingerprint(self.fetch(source)) == fingerprint(published), 'POST_PUBLICATION_FEED_MISMATCH')
        checked = self.verify(candidate, witness)
        if kind == 'repair':
            row['lastKnownGood'] = live
            row['rollbackWitness'] = copy.deepcopy(row['witness'])
        row['publishedHash'] = fingerprint(published)
        row['witness'] = checked
        row['publishedFailures'] = []
        row.setdefault('audit', []).append({'kind': kind, 'fromHash': fingerprint(live), 'toHash': fingerprint(published), 'at': int(self.clock())})
        row.pop('pending')
        self.store.write(journal)
        return {'source': str(source), 'action': kind.upper()+'_VERIFIED', 'revision': candidate['revision']}

    def reconcile(self, source):
        """Crash recovery only from exact authenticated before/after revisions; never replay writes."""
        journal=self.store.read();row=journal.get('sources',{}).get(str(source))
        require(row and row.get('approved') is True,'SOURCE_PUBLICATION_GATE_CLOSED')
        pending=row.get('pending')
        if not pending:return {'source':str(source),'action':'NO_PENDING_OPERATION'}
        live=self.fetch(source);current=self.authenticate(live)
        source_record(current,source)
        if fingerprint(live)==pending['beforeHash']:
            action='RESERVED_OPERATION_NOT_PUBLISHED'
        elif fingerprint(current)==pending['candidateHash']:
            before=self.authenticate(pending['beforeEnvelope']);source_record(before,source)
            row['lastKnownGood']=pending['beforeEnvelope']
            row['rollbackWitness']=pending['beforeWitness']
            row['publishedHash']=fingerprint(live)
            row['witness']=pending['beforeWitness']
            row['publishedFailures']=[]
            action='PUBLISHED_OPERATION_RECONCILED_VERIFICATION_REQUIRED'
        else:
            raise OperationBlocked('PENDING_REVISION_CANNOT_BE_SAFELY_RECONCILED')
        row.setdefault('audit',[]).append({'kind':action,'at':int(self.clock()),'revisionHash':fingerprint(live)})
        row.pop('pending');self.store.write(journal)
        return {'source':str(source),'action':action}

    def observe_regression(self, source, validator):
        journal, row, live, current, old = self.context(source)
        result = validator(current, row['witness'])
        # Never infer confirmation from generic exceptions, 403s, timeouts or workflow status.
        if result.get('confirmedSemantic') is not True or result.get('classification') not in {
                'CONFIRMED_PARSER_DEFECT', 'IDENTITY_MISMATCH', 'CONFIRMED_CONTENT_LOSS'}:
            if result.get('classification') == 'HEALTHY' and row.get('publishedFailures'):
                row['publishedFailures'] = []
                self.store.write(journal)
            return {'source': str(source), 'action': 'NO_ROLLBACK_UNCONFIRMED'}
        failures = row.setdefault('publishedFailures', [])
        now = int(self.clock())
        if not failures or now-failures[-1]['at'] >= 3600:
            failures.append({'at': now, 'revisionHash': fingerprint(live), 'confirmedSemantic': True})
            self.store.write(journal)
        if len(failures) >= 3:
            # Verify the historical revision against its original witness, not a changed sample.
            row['witness'] = row.get('rollbackWitness', row['witness'])
            self.store.write(journal)
            return self.apply(source, None, 'rollback')
        return {'source': str(source), 'action': 'REGRESSION_CONFIRMATION_PENDING'}


class GitJournal:
    """Signed journal in a dedicated Git ref; non-force updates retain immutable audit history."""
    def __init__(self, api, repository, seal, unseal, branch='source-operations-state'):
        require(branch == 'source-operations-state', 'INVALID_JOURNAL_REF')
        self.api, self.repository, self.seal, self.unseal, self.branch = api, repository, seal, unseal, branch
        self.head = self.tree = None

    def read(self):
        import base64
        prefix = 'repos/'+self.repository+'/git/'
        ref = self.api('GET', prefix+'ref/heads/'+self.branch)
        self.head = ref['object']['sha']
        commit = self.api('GET', prefix+'commits/'+self.head)
        self.tree = commit['tree']['sha']
        files = self.api('GET', prefix+'trees/'+self.tree)['tree']
        matches = [item for item in files if item['path'] == 'journal.json' and item['type'] == 'blob']
        require(len(matches) == 1, 'AUTHENTICATED_OPERATION_HISTORY_REQUIRED')
        blob = self.api('GET', prefix+'blobs/'+matches[0]['sha'])
        require(blob.get('size', 0) <= 2*1024*1024, 'OPERATION_HISTORY_OVERSIZED')
        state = self.unseal(json.loads(base64.b64decode(blob['content'])))
        require(state.get('repository') == self.repository and state.get('purpose') == 'SOURCE_OPERATIONS_V1', 'OPERATION_HISTORY_SCOPE_MISMATCH')
        return state

    def write(self, state):
        require(self.head and self.tree, 'NO_AUTHENTICATED_JOURNAL_PARENT')
        require(state.get('repository') == self.repository and state.get('purpose') == 'SOURCE_OPERATIONS_V1', 'OPERATION_HISTORY_SCOPE_MISMATCH')
        prefix = 'repos/'+self.repository+'/git/'
        # Double CAS: check parent, then GitHub rejects a competing non-fast-forward update.
        ref = self.api('GET', prefix+'ref/heads/'+self.branch)
        require(ref['object']['sha'] == self.head, 'CONCURRENT_OPERATION_HISTORY_UPDATE')
        blob = self.api('POST', prefix+'blobs', {'content': json.dumps(self.seal(state), separators=(',', ':')), 'encoding': 'utf-8'})
        tree = self.api('POST', prefix+'trees', {'base_tree': self.tree, 'tree': [{'path': 'journal.json', 'mode': '100644', 'type': 'blob', 'sha': blob['sha']}]})
        commit = self.api('POST', prefix+'commits', {'message': 'source operations: authenticated checkpoint', 'tree': tree['sha'], 'parents': [self.head]})
        self.api('PATCH', prefix+'refs/heads/'+self.branch, {'sha': commit['sha'], 'force': False})
        self.head, self.tree = commit['sha'], tree['sha']
