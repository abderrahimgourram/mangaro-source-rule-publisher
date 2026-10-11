#!/usr/bin/env python3
"""Bounded observational checks and targeted native evidence ingestion. Never signs/publishes."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import re
import sys
import time
from urllib.parse import urljoin, urlsplit

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from automation.core import Http, Interpreter, Unsafe, Transient, image, open_state, validate, unique
from automation.controller import compare_native, capture, Controller
from automation.infer import compile_native
from automation.replay import contracts as replay_contracts, check as replay_check
from automation.reliability import atomic, load, locked, save, begin, record, report, ReliabilityError

PROJECT = HERE.parents[2]

class TimedHttp(Http):
    def __init__(self, budget=18, seconds=65):
        super().__init__(budget=budget, delay=.1)
        self.deadline = time.monotonic() + seconds
    def get(self, *args, **kwargs):
        if time.monotonic() >= self.deadline:
            raise Transient('NETWORK_TIME_BUDGET')
        response = super().get(*args, **kwargs)
        if not kwargs.get('image'):
            prefix = response.body[:16384].lower()
            if b'challenge-form' in prefix or b'cf-browser-verification' in prefix or b'<title>just a moment' in prefix:
                raise Unsafe('CHALLENGE_REPRESENTATION')
        return response


def error_class(error):
    code = str(error)
    if isinstance(error, Transient):
        return 'TRANSIENT_NETWORK_ERROR'
    if code in {'HTTP_403', 'HTTP_429', 'CHALLENGE_REPRESENTATION', 'CHALLENGE_PAGE'}:
        return 'BLOCKED_BY_CHALLENGE'
    if code in {'UNOBSERVED_WEBVIEW_TRANSPORT', 'UNVERIFIED_IMAGE_ORIGIN'}:
        return 'UNVERIFIED_TRANSPORT'
    if code in {'HTTP_404', 'HTTP_410'}:
        return 'UNAVAILABLE'
    if code.startswith('HTTP_5'):
        return 'TRANSIENT_NETWORK_ERROR'
    return 'DEGRADED'


def source_origin(source):
    # Inventory is authoritative; Kotlin is read only to resolve the registered source's origin.
    matches = []
    for file in (PROJECT / 'app/src/main/java/eu/kanade/tachiyomi/source/internal').glob('*/*.kt'):
        code = file.read_text()
        name = re.search(r'^class\s+([A-Za-z0-9_]+)', code, re.M)
        base = re.search(r'override val baseUrl(?:\s*:\s*String)?\s*=\s*"(https://[^"]+)"', code)
        if name and name[1] == source['name'].replace(' ', '') and base:
            matches.append((base[1], any('WebView' in related.read_text() for related in file.parent.glob('*.kt'))))
    if len(matches) != 1:
        raise Unsafe('REGISTERED_ORIGIN_NOT_PROVEN')
    return matches[0]


def profile_state(sid, row, public):
    if row.get('profile'):
        if row['profile']['sourceId'] != int(sid):
            raise Unsafe('SOURCE_STATE_IDENTITY_MISMATCH')
        return row['profile'], row.get('witness', {}), row.get('imageHosts', [])
    file = PROJECT / '.publisher-state' / f'{sid}.json'
    if file.exists():
        old = open_state(json.loads(file.read_text()), public)
        if old.get('sourceId') != int(sid):
            raise Unsafe('SIGNED_STATE_SOURCE_MISMATCH')
        rule = old.get('baseline')
        hosts = [urlsplit(r.get('image', '')).hostname for r in old.get('evidence', {}).get('pages', {}).get('before', [])]
        return rule, old.get('witness', {}), [h for h in hosts if h]
    return None, {}, []


def lightweight(source, row, public, http=None):
    http = http or TimedHttp()
    operations = {op: 'SKIPPED' for op in ['popular', 'latest', 'search', 'identity', 'details',
                  'cover', 'pagination', 'chapters', 'reader', 'image']}
    observation = {'operations': operations, 'ok': False, 'complete': False,
                   'classification': 'INSUFFICIENT_EVIDENCE', 'evidence': 'LIGHTWEIGHT_PROVISIONAL'}
    operation = 'reachability'
    try:
        rule, witness, image_hosts = profile_state(source['id'], row, public)
        if rule is None and row.get('replayContracts'):
            return replay_check(source, row, http)
        if rule is None:
            origin, webview = source_origin(source)
            response = http.get(origin)
            if response.status != 200:
                raise Unsafe('HTTP_' + str(response.status))
            if urlsplit(response.url).netloc != urlsplit(origin).netloc:
                observation.update(unknown=True, needsDeep=True, reason='ROOT_REDIRECT_NOT_OPERATION_EVIDENCE')
                return observation
            from bs4 import BeautifulSoup
            doc = BeautifulSoup(response.body, 'html.parser')
            if doc.select('#challenge-form,.challenge-running,#cf-wrapper') or any(s in doc.get_text(' ', strip=True).lower() for s in ['cf-browser-verification', 'verify you are human']):
                raise Unsafe('CHALLENGE_PAGE')
            if doc.title and any(s in doc.title.get_text().lower() for s in ['just a moment', 'access denied', 'attention required']):
                raise Unsafe('CHALLENGE_PAGE')
            if not response.body.strip():
                raise Unsafe('EMPTY_BODY')
            observation.update(unknown=True, needsDeep=not row.get('lastDeepVerification') or time.time() - row['lastDeepVerification'] >= 6 * 86400,
                               reason='NO_VERIFIED_DECLARATIVE_BASELINE',
                               classification='UNVERIFIED_TRANSPORT' if webview else 'INSUFFICIENT_EVIDENCE')
            return observation
        interp = Interpreter(rule, http)
        operation = 'popular'
        popular, more = interp.catalogue('popular')
        operations[operation] = 'PASS'
        operation = 'pagination'
        if more:
            second, _ = interp.catalogue('popular', 2)
            if {r['id'] for r in popular} & {r['id'] for r in second}:
                raise Unsafe('REPEATED_CATALOGUE_PAGE')
        operations[operation] = 'PASS'
        operation = 'latest'
        latest, more = interp.catalogue('latest')
        if more:
            later, _ = interp.catalogue('latest', 2)
            unique(latest + later, 'id')
            unique(latest + later, 'url')
        operations[operation] = 'PASS'
        known = witness.get('works', {})
        sample = next(iter(known.values()), popular[0])
        operation = 'search'
        found, more = interp.catalogue('search', query=sample['title'])
        if more:
            later, _ = interp.catalogue('search', 2, query=sample['title'])
            unique(found + later, 'id')
            unique(found + later, 'url')
            found += later
        expected_id = next(iter(known), None)
        manga = next((m for m in found if m['url'] == sample['url'] and (expected_id is None or m['id'] == expected_id)), None)
        if manga is None:
            raise Unsafe('KNOWN_MANGA_IDENTITY_LOST')
        operations['search'] = operations['identity'] = 'PASS'
        operation = 'details'
        detail = interp.details(manga)
        operations[operation] = 'PASS'
        operation = 'cover'
        if not detail.get('cover') or urlsplit(detail['cover']).scheme != 'https':
            raise Unsafe('INVALID_COVER_URL')
        operations[operation] = 'PASS'
        operation = 'chapters'
        rows, more, total = interp.rows('chapters', manga['url'], {'id': manga['id']})
        if total is None and detail.get('chapterTotal'):
            total = int(detail['chapterTotal'])
        if more:
            later, more, second_total = interp.rows('chapters', manga['url'], {'id': manga['id']}, page=2)
            if second_total != total:
                raise Unsafe('CHANGING_CHAPTER_TOTAL')
            rows += later
        unique(rows, 'id'); unique(rows, 'url')
        if any(urlsplit(urljoin(rule['baseUrl'], c['url'])).netloc != urlsplit(rule['baseUrl']).netloc for c in rows):
            raise Unsafe('CHAPTER_SOURCE_ISOLATION_FAILED')
        complete = not more and total is not None and len(rows) == total
        if not more and total is not None and len(rows) != total:
            raise Unsafe('TRUNCATED_CHAPTER_TOTAL')
        old = known.get(manga['id'], {})
        if total is not None and total < old.get('count', 0):
            raise Unsafe('KNOWN_CHAPTER_COUNT_DROPPED')
        if complete and not set(old.get('chapters', [])) <= {c['id'] for c in rows}:
            raise Unsafe('KNOWN_CHAPTER_IDENTITIES_LOST')
        operations[operation] = 'PASS' if complete else 'PARTIAL'
        if not rows:
            raise Unsafe('NO_READER_SAMPLE')
        operation = 'reader'
        pages = interp.pages(rows[0])
        operations[operation] = 'PASS'
        operation = 'image'
        if not image_hosts or any(urlsplit(p['image']).hostname not in image_hosts for p in pages):
            raise Unsafe('UNVERIFIED_IMAGE_ORIGIN')
        image(http.get(pages[0]['image'], headers=rule.get('headers', {}) | {'Range': 'bytes=0-511'}, image=True))
        operations[operation] = 'PASS'
        observation.update(ok=True, complete=complete, classification='HEALTHY' if complete else 'DEGRADED',
                           chapterCount=total if complete else None, observedChapterCount=len(rows),
                           identityHash=hashlib.sha256((str(source['id']) + ':' + manga['url']).encode()).hexdigest(),
                           needsDeep=False, reason='BOUNDED_OPERATIONS_VERIFIED' if complete else 'PARTIAL_INDEX_SAMPLED')
    except (Unsafe, ValueError, KeyError, TypeError) as error:
        if operation in operations:
            operations[operation] = 'FAILED'
        code = str(error) if isinstance(error, Unsafe) else 'REPRESENTATION_INVALID'
        observation.update(classification=error_class(error), reason=code, operation=operation, needsDeep=operation in {'identity', 'details', 'chapters', 'reader', 'image'})
    return observation


def native_observation(source, evidence):
    steps = evidence.get('steps', {})
    required = {'popular', 'pagination', 'latest', 'search', 'details-0', 'chapters-0', 'reader', 'image-decode'}
    ops = {k: 'PASS' if v.startswith('PASS') else 'DEPENDENT_FAILURE' if v == 'DEPENDENT_FAILURE' else 'FAILED' for k, v in steps.items()}
    failures = [k for k, v in ops.items() if v != 'PASS']
    details = [x for x in evidence.get('observations', []) if x['operation'] == 'details']
    if steps.get('details-0') != 'PASS':
        for k in list(ops):
            if k.startswith('chapters') or k in {'reader', 'image-decode'}:
                ops[k] = 'DEPENDENT_FAILURE'
    if failures:
        primary = next((k for k in failures if ops[k] == 'FAILED'), failures[0])
        transient = steps.get(primary, '').startswith('TRANSIENT')
        return dict(classification='TRANSIENT_NETWORK_ERROR' if transient else 'DEGRADED', ok=False, complete=False,
                    reason='NATIVE_NETWORK_FAILURE' if transient else 'NATIVE_OPERATION_FAILED', operation=primary,
                    evidence='NATIVE', operations=ops, needsDeep=True)
    if not required <= set(steps):
        return dict(classification='INSUFFICIENT_EVIDENCE', reason='INCOMPLETE_NATIVE_EVIDENCE', unknown=True,
                    evidence='NATIVE_INCOMPLETE', operations=ops, needsDeep=True)
    if not details:
        return dict(classification='INSUFFICIENT_EVIDENCE', unknown=True, reason='NATIVE_IDENTITY_MISSING',
                    operations=ops, evidence='NATIVE_INCOMPLETE', needsDeep=True)
    # A WebView source cannot be declared equivalent from unobserved/private transport.
    if source['name'] == 'Manga Starz':
        try:
            compile_native(evidence)
        except Unsafe as error:
            if str(error) != 'DECLARATIVE_EQUIVALENT':
                return dict(classification='UNVERIFIED_TRANSPORT', ok=False, reason='UNOBSERVED_WEBVIEW_TRANSPORT',
                            operations=ops, evidence='NATIVE_UNVERIFIED_TRANSPORT', needsDeep=True)
    complete = all(d.get('completeness') == 'COMPLETE' for d in details)
    example = details[0]
    return dict(classification='HEALTHY' if complete else 'DEGRADED', ok=True, complete=complete,
                reason='NATIVE_VERIFIED' if complete else 'NATIVE_CHAPTERS_UNVERIFIED', operations=ops,
                evidence='NATIVE_IMAGE_DECODED', chapterCount=len(example['chapters']) if complete else None,
                identityHash=hashlib.sha256((source['id'] + ':' + example['input']['url']).encode()).hexdigest(), needsDeep=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, default=Path('health-plan.json'))
    parser.add_argument('--state', type=Path, default=Path('.source-health/state.json'))
    parser.add_argument('--report', type=Path, default=Path('publisher-report.json'))
    parser.add_argument('--native-evidence', type=Path)
    parser.add_argument('--reserve', action='store_true')
    args = parser.parse_args()
    started = time.monotonic()
    plan = json.loads(args.plan.read_text()); selected = plan['sources']; inventory = plan['inventory']
    registered = json.loads((PROJECT / 'tools/source-rules/production/public/index.json').read_text())['sources']
    if {s['id']: s['name'] for s in inventory} != {sid: s['name'] for sid,s in registered.items()} or len(inventory) != len(registered):
        raise ReliabilityError('MANGA_REGISTRY_PLAN_MISMATCH')
    if len(selected) != len(set(selected)) or not set(selected) <= set(registered):
        raise ReliabilityError('INVALID_SOURCE_SELECTION')
    props = dict(l.split('=', 1) for l in (PROJECT / 'gradle.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
    with locked(args.state):
        state = load(args.state)
        if state['generation'] != plan['generation']:
            raise ReliabilityError('STALE_PLAN_NO_STATE_WRITE')
        rows = []
        for source in inventory:
            sid = source['id']
            legacy = PROJECT / '.publisher-state' / f'{sid}.json'
            if sid not in state['sources'] and legacy.exists():
                prior = open_state(json.loads(legacy.read_text()), props['sourceRulesPublicKey'])
                if prior.get('sourceId') != int(sid):
                    raise ReliabilityError('LEGACY_STATE_SCOPE_INVALID')
                state['sources'][sid] = {'consecutiveFailures': prior.get('failures', 0), 'retryAttempts': 0, 'legacyCountersImported': True}
        if args.reserve:
            begin(state, selected, plan['mode']); save(args.state, state)
            plan.update(generation=state['generation'], reserved=True)
            atomic(args.plan, plan)
            return
        if selected:
            if not plan.get('reserved'):
                begin(state, selected, plan['mode']); save(args.state, state)
            sources = [s for s in inventory if s['id'] in selected]
            if args.native_evidence:
                data = json.loads(args.native_evidence.read_text())
                evidence = {str(e['sourceId']): e for e in data['sources']}
                if set(evidence) != set(selected) or len(evidence) != len(data['sources']):
                    raise ReliabilityError('NATIVE_EVIDENCE_INVENTORY_MISMATCH')
                results = []
                for source in sources:
                    native = evidence[source['id']]
                    if native['name'] != source['name']:
                        raise ReliabilityError('NATIVE_SOURCE_IDENTITY_MISMATCH')
                    observation = native_observation(source, native)
                    if observation.get('ok'):
                        state['sources'][source['id']]['replayContracts'] = replay_contracts(native)
                        state['sources'][source['id']]['imageHosts'] = sorted({urlsplit(url).hostname for item in native['observations'] if item['operation']=='pages' for url in item['images'] if urlsplit(url).hostname})
                        try:
                            profile = compile_native(native)
                            witness = validate(profile, TimedHttp(budget=80, seconds=100))
                            compare_native(profile, native, TimedHttp(budget=80, seconds=100))
                            ledger = state['sources'][source['id']]
                            ledger.update(profile=profile, witness=witness, inferenceEvidence=capture(profile, TimedHttp(budget=32, seconds=65)),
                                imageHosts=sorted({urlsplit(url).hostname for item in native['observations'] if item['operation'] == 'pages' for url in item['images'] if urlsplit(url).hostname}))
                        except Unsafe:
                            state['sources'][source['id']]['profileCapability'] = 'NATIVE_ONLY_NO_EQUIVALENT_MAPPING'
                    results.append((source, observation))
            else:
                with ThreadPoolExecutor(max_workers=3) as pool:
                    tasks = {pool.submit(lightweight, s, state['sources'].get(s['id'], {}), props['sourceRulesPublicKey']): s for s in sources}
                    results = []
                    for task in as_completed(tasks):
                        source = tasks[task]
                        try:
                            observation = task.result()
                        except Exception:
                            observation = dict(classification='INSUFFICIENT_EVIDENCE', unknown=True, reason='ISOLATED_CHECK_ERROR', operations={}, needsDeep=True)
                        results.append((source, observation))
            for source, observation in results:
                row = record(state, source['id'], observation, plan['mode'])
                ledger = state['sources'][source['id']]
                if args.native_evidence and not observation.get('ok') and not observation.get('unknown') and ledger.get('consecutiveFailures', 0) >= 3:
                    candidate = {'status': 'REJECTED', 'reason': 'NO_VERIFIED_DECLARATIVE_BASELINE'}
                    if ledger.get('profile') and ledger.get('inferenceEvidence') and observation.get('classification') not in {'TRANSIENT_NETWORK_ERROR', 'BLOCKED_BY_CHALLENGE', 'UNVERIFIED_TRANSPORT'}:
                        deadline = time.monotonic() + 120
                        def factory():
                            return TimedHttp(budget=48, seconds=max(0, deadline-time.monotonic()))
                        def never_publish(candidate, previous):
                            raise Unsafe('PUBLICATION_DISABLED_AUDIT_ONLY')
                        prior = dict(sourceId=int(source['id']), baseline=ledger['profile'], witness=ledger['witness'],
                                     evidence=ledger['inferenceEvidence'], failures=ledger['consecutiveFailures']-1)
                        updated, diagnostic = Controller(factory, never_publish).run(
                            {'sourceId': int(source['id']), 'name': source['name']}, prior)
                        candidate = updated.get('candidate', {'status': 'REJECTED', 'reason': diagnostic.get('result', 'INSUFFICIENT_EVIDENCE')})
                    ledger['candidate'] = candidate
                    row['candidate'] = candidate
                rows.append(row)
                save(args.state, state)
        report(args.report, rows, inventory, plan['mode'], started, selected, state=state, state_path=args.state)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('MONITOR_EXECUTION_FAILED_NO_PUBLICATION')
        raise SystemExit(1)
