"""Monitoring history, bounded eligibility and sanitized reports (never publication authority).

Remote history is restored only from immutable artifacts of this exact workflow on
its trusted default branch using GitHub's authenticated API. PRs cannot read/write it.
Legacy signed publisher state remains a separate, unchanged trust domain.
"""
from __future__ import annotations
import contextlib
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

CLASSES = {'HEALTHY', 'DEGRADED', 'UNAVAILABLE', 'TRANSIENT_NETWORK_ERROR',
           'BLOCKED_BY_CHALLENGE', 'UNVERIFIED_TRANSPORT', 'CONFIRMED_PARSER_DEFECT',
           'REQUIRES_COMPILED_UPDATE', 'INSUFFICIENT_EVIDENCE'}
STEPS = {'PASS', 'FAILED', 'DEPENDENT_FAILURE', 'SKIPPED', 'PARTIAL'}
ARTIFACT = 'source-health-state-v1'
MAX_RETRIES = 3
DEEP_INTERVAL = 86400

class ReliabilityError(Exception):
    """Stable codes only; never propagate remote exception messages."""


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix='.health-')
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(canonical(data))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextlib.contextmanager
def locked(path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(str(path) + '.lock', 'a') as stream:
        # No blocking forever and no two local writers. Actions also serializes the workflow.
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ReliabilityError('STATE_WRITER_BUSY') from None
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


def new_state():
    return {'schema': 1, 'generation': 0, 'sources': {}, 'infrastructure': ''}


def load(path):
    if not Path(path).exists():
        return new_state()
    try:
        wrapped = json.loads(Path(path).read_text())
        state = wrapped['state']
        if set(wrapped) != {'sha256', 'state'} or hashlib.sha256(canonical(state).encode()).hexdigest() != wrapped['sha256']:
            raise ValueError()
        if state['schema'] != 1 or type(state['generation']) is not int or not isinstance(state['sources'], dict):
            raise ValueError()
        return state
    except (ValueError, KeyError, TypeError):
        raise ReliabilityError('STATE_INTEGRITY_INVALID') from None


def save(path, state):
    atomic(path, {'state': state, 'sha256': hashlib.sha256(canonical(state).encode()).hexdigest()})


def select(state, inventory, mode, now=None, source='all'):
    now = int(time.time()) if now is None else now
    ids = [str(x['id']) for x in inventory]
    if source != 'all':
        if source not in ids:
            raise ReliabilityError('UNKNOWN_SOURCE')
        ids = [source]
    available = [sid for sid in ids if state['sources'].get(sid, {}).get('leaseUntil', 0) <= now]
    if mode in {'monitor', 'weekly', 'manual'}:
        return available
    if mode == 'retry':
        return [sid for sid in available if 0 < state['sources'].get(sid, {}).get('retryAt', 0) <= now
                and state['sources'][sid].get('retryAttempts', 0) < MAX_RETRIES]
    if mode == 'deep':
        due = [sid for sid in available if
               state['sources'].get(sid, {}).get('needsDeep', True) and
               now - state['sources'].get(sid, {}).get('lastDeepAttempt', 0) >= DEEP_INTERVAL]
        # Daily budget: two sources; rotate by oldest attempt, never an unlimited dispatch chain.
        return sorted(due, key=lambda sid: state['sources'].get(sid, {}).get('lastDeepAttempt', 0))[:2]
    raise ReliabilityError('UNKNOWN_MODE')


def begin(state, selected, mode, now=None):
    now = int(time.time()) if now is None else now
    for sid in selected:
        row = state['sources'].setdefault(sid, {'consecutiveFailures': 0, 'retryAttempts': 0})
        if row.get('leaseUntil', 0) > now:
            raise ReliabilityError('SOURCE_ALREADY_CHECKING')
        row['leaseUntil'] = now + 3600
        if mode == 'retry':
            row['retryAttempts'] = row.get('retryAttempts', 0) + 1
        if mode in {'deep', 'weekly', 'manual'}:
            row['lastDeepAttempt'] = now
    state['generation'] += 1


def record(state, sid, observation, mode, now=None):
    now = int(time.time()) if now is None else now
    row = state['sources'].setdefault(str(sid), {})
    previous = row.get('classification', 'INSUFFICIENT_EVIDENCE')
    obs = copy.deepcopy(observation)
    classification = obs.get('classification', 'INSUFFICIENT_EVIDENCE')
    if classification not in CLASSES:
        raise ReliabilityError('INVALID_CLASSIFICATION')
    operations = obs.get('operations', {})
    if any(not re.fullmatch(r'[a-zA-Z0-9_-]{1,48}', k) or v not in STEPS for k, v in operations.items()):
        raise ReliabilityError('INVALID_OPERATION_STATUS')
    # Downstream success is forbidden after an invalid identity/detail dependency.
    if any(operations.get(k) in {'FAILED', 'DEPENDENT_FAILURE'} for k in ('identity', 'details')):
        for name in ('chapters', 'pagination', 'reader', 'image', 'text'):
            operations[name] = 'DEPENDENT_FAILURE'
        obs['ok'] = False
        if classification == 'HEALTHY':
            classification = 'DEGRADED'
    if classification == 'HEALTHY' and (not obs.get('ok') or not obs.get('complete') or
            not operations or any(v in {'FAILED', 'DEPENDENT_FAILURE', 'PARTIAL'} for v in operations.values())):
        classification = 'DEGRADED'
    old_count = row.get('verifiedChapterCount')
    count = obs.get('chapterCount')
    if (obs.get('complete') and old_count is not None and count is not None and
            obs.get('identityHash') == row.get('identityHash') and count < old_count):
        classification = 'DEGRADED'
        obs.update(ok=False, reason='CHAPTER_COUNT_DROP', operation='chapters', needsDeep=True)
        operations['chapters'] = 'FAILED'
    row.update(classification=classification, lastCheck=now, operations=operations,
               failedOperation=obs.get('operation', ''), reason=obs.get('reason', 'OBSERVED'),
               evidence=obs.get('evidence', 'LIGHTWEIGHT_PROVISIONAL'), leaseUntil=0)
    if obs.get('unknown'):
        # Missing evidence or credentials is not a new source failure or a recovery.
        row['needsDeep'] = row.get('needsDeep', False) or obs.get('needsDeep', True)
    elif obs.get('ok'):
        row.update(consecutiveFailures=0, retryAttempts=0, retryAt=0, lastSuccessfulObservation=now,
                   needsDeep=obs.get('needsDeep', False))
        if classification == 'HEALTHY' and mode in {'deep', 'weekly', 'manual'}:
            row['lastVerifiedHealthy'] = now
        if operations.get('reader', operations.get('text')) == 'PASS':
            row['lastChapterReaderVerification'] = now
        if obs.get('complete') and count is not None:
            row.update(verifiedChapterCount=count, identityHash=obs.get('identityHash'))
    else:
        row['consecutiveFailures'] = row.get('consecutiveFailures', 0) + 1
        row['lastFailure'] = now
        row['lastFailureClassification'] = classification
        attempts = row.get('retryAttempts', 0)
        scheduled = now + 3600 * (2 ** attempts) if attempts < MAX_RETRIES else 0
        pending = row.get('retryAt', 0)
        row['retryAt'] = min(pending, scheduled) if mode != 'retry' and pending and scheduled else scheduled
        row['needsDeep'] = obs.get('needsDeep', False) or row['consecutiveFailures'] >= 2 or classification in {
            'BLOCKED_BY_CHALLENGE', 'UNVERIFIED_TRANSPORT', 'REQUIRES_COMPILED_UPDATE'}
    if mode in {'deep', 'weekly', 'manual'}:
        row['lastDeepVerification'] = now
    row['lastTransition'] = now if previous != classification else row.get('lastTransition', now)
    signature = hashlib.sha256(canonical([classification, row['failedOperation'], row['reason']]).encode()).hexdigest()
    meaningful = (classification == 'HEALTHY' and mode in {'deep', 'weekly', 'manual'} and bool(row.get('lastFailure'))) or (
        not obs.get('ok') and not obs.get('unknown') and row.get('consecutiveFailures', 0) >= 2)
    previous_notification = row.get('lastNotification')
    notify = meaningful and signature != previous_notification
    if notify:
        row['lastNotification'] = signature
    metadata = {k: obs[k] for k in ['indexState', 'observedChapterCount', 'chapterCount', 'chapterPages', 'sampledTexts', 'attestationState', 'sourceObservation', 'bridgeReachability'] if k in obs}
    row['lastObservation'] = metadata
    return {**metadata, 'id': str(sid), **{k: row.get(k) for k in ('classification', 'failedOperation', 'reason', 'evidence',
            'consecutiveFailures', 'retryAt', 'needsDeep', 'lastVerifiedHealthy')}, 'operations': operations,
            'changed': previous != classification, 'notify': notify, '_previousNotification': previous_notification}


def report(path, rows, inventory, mode, started, selected, planning=False, state=None, state_path=None):
    ids = [str(s['id']) for s in inventory]
    actual = [str(row['id']) for row in rows]
    if len(ids) != len(set(ids)) or len(selected) != len(set(selected)) or not set(selected) <= set(ids):
        raise ReliabilityError('INVALID_SOURCE_SELECTION')
    if not planning and (len(actual) != len(selected) or set(actual) != set(selected)):
        raise ReliabilityError('INCOMPLETE_CHECK_COVERAGE')
    # An execution success means reports were written, not that sources were healthy.
    failures = [r for r in rows if r['classification'] == 'TRANSIENT_NETWORK_ERROR']
    shared = len(failures) >= 3 and len(failures) * 2 >= len(rows)
    if shared:
        for row in rows:
            if row['notify'] and state is not None:
                prior = row.get('_previousNotification')
                if prior is None:
                    state['sources'][row['id']].pop('lastNotification', None)
                else:
                    state['sources'][row['id']]['lastNotification'] = prior
            row['notify'] = False
    for row in rows:
        row.pop('_previousNotification', None)
    if state is not None:
        state['infrastructure'] = 'SHARED_NETWORK_FAILURE_SUSPECTED' if shared else 'NO_SHARED_FAILURE_OBSERVED'
        save(state_path, state)
    elapsed = round(time.monotonic() - started, 3)
    result = {'schema': 1, 'ciExecution': 'COMPLETED', 'mode': mode, 'elapsedSeconds': elapsed,
              'phase': 'PLANNING_ONLY' if planning else 'SOURCE_CHECKS',
              'registeredCount': len(ids), 'eligibleCount': len(selected), 'checkedCount': len(rows),
              'coverage': 'NOT_EXECUTED' if planning else 'NO_SOURCES_DUE' if not selected else 'ALL_ELIGIBLE_ATTEMPTED',
              'plannedSources': selected if planning else [],
              'sources': rows, 'skipped': [str(x['id']) for x in inventory if str(x['id']) not in selected],
              'checksExecuted': sum(v in {'PASS', 'FAILED', 'PARTIAL'} for row in rows for v in row['operations'].values()),
              'checksSkipped': sum(v in {'SKIPPED', 'DEPENDENT_FAILURE'} for row in rows for v in row['operations'].values()),
              'probeRunnerMinutes': round(elapsed/60, 4),
              'infrastructure': 'SHARED_NETWORK_FAILURE_SUSPECTED' if shared else 'NO_SHARED_FAILURE_OBSERVED'}
    atomic(path, result)
    names = {str(x['id']): x['name'] for x in inventory}
    text = '| Source | Health | Failing operation | Evidence | Action |\n|---|---|---|---|---|\n'
    for row in rows:
        action = 'Deep verification eligible' if row['needsDeep'] else 'None'
        if row['retryAt']:
            action = 'Bounded retry scheduled'
        text += f"| {names[row['id']]} | {row['classification']} | {row['failedOperation'] or 'None'} | {row['evidence']} | {action} |\n"
    if planning:
        text += f'\nPlanning only: {len(selected)}/{len(ids)} registered sources eligible. Source checks have NOT run.\n'
    text += f'\nMode: {mode}; executed: {len(rows)}; skipped: {len(result["skipped"])}; elapsed: {elapsed}s. '
    text += 'No production publication. HEALTHY observations retain their verification tier.\n'
    if not selected:
        text += 'No sources due; network and expensive verification skipped.\n'
    if shared:
        text += 'Shared runner/network failure suspected; individual parser-defect alerts suppressed.\n'
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as stream:
            stream.write(text)
    print(json.dumps({'phase': result['phase'], 'eligible': len(selected), 'coverage': result['coverage'], 'mode': mode, 'checked': len(rows), 'skipped': len(result['skipped']), 'elapsedSeconds': elapsed}))
    for row in rows:
        if row['notify']:
            print(f"::notice title=Source health transition::{names[row['id']]}: {row['classification']}")
    return result


def gh_json(path):
    try:
        process = subprocess.run(['gh', 'api', path], capture_output=True, timeout=25, check=True)
        return json.loads(process.stdout)
    except (OSError, subprocess.SubprocessError, ValueError):
        raise ReliabilityError('GITHUB_HISTORY_UNAVAILABLE') from None


def restore_history(destination, repository, workflow, branch, api=gh_json):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or not re.fullmatch(r'[a-zA-Z0-9_-]+\.yml', workflow):
        raise ReliabilityError('INVALID_HISTORY_SCOPE')
    artifacts = api(f'repos/{repository}/actions/artifacts?name={ARTIFACT}&per_page=100')
    if not isinstance(artifacts, dict) or not isinstance(artifacts.get('artifacts'), list):
        raise ReliabilityError('GITHUB_HISTORY_RESPONSE_INVALID')
    candidates = sorted(artifacts['artifacts'], key=lambda a: a['id'], reverse=True)
    for artifact in candidates:
        run_id = artifact.get('workflow_run', {}).get('id')
        if not run_id:
            continue
        run = api(f'repos/{repository}/actions/runs/{int(run_id)}')
        if run.get('path') != '.github/workflows/' + workflow or run.get('head_branch') != branch or run.get('event') not in {'schedule', 'workflow_dispatch', 'push'}:
            continue
        if run.get('status') != 'completed':
            continue
        if artifact.get('expired'):
            raise ReliabilityError('AUTHENTICATED_HISTORY_EXPIRED')
        with tempfile.TemporaryDirectory() as directory:
            try:
                subprocess.run(['gh', 'run', 'download', str(run_id), '--repo', repository,
                                '--name', ARTIFACT, '--dir', directory], capture_output=True, timeout=30, check=True)
                source = Path(directory) / 'state.json'
                if source.stat().st_size > 4 * 1024 * 1024:
                    raise ReliabilityError('OVERSIZED_HISTORY')
                state = load(source)
                if state.get('repository') != repository or state.get('workflow') != workflow:
                    raise ReliabilityError('HISTORY_SCOPE_MISMATCH')
                if Path(destination).exists():
                    local = load(destination)
                    if local['generation'] > state['generation'] or local['generation'] == state['generation'] and canonical(local) != canonical(state):
                        raise ReliabilityError('HISTORY_ROLLBACK_REFUSED')
                if not state['sources']:
                    raise ReliabilityError('AUTHENTICATED_HISTORY_EMPTY_NO_RESET')
                save(destination, state)
                return 'AUTHENTICATED_GITHUB_HISTORY'
            except (OSError, subprocess.SubprocessError):
                raise ReliabilityError('GITHUB_HISTORY_DOWNLOAD_FAILED') from None
    # First migration only. Any existing artifact from this workflow is never silently reset.
    if any(a.get('workflow_run', {}).get('head_branch') == branch for a in candidates):
        raise ReliabilityError('NO_TRUSTED_COMPLETED_HISTORY')
    if Path(destination).exists():
        raise ReliabilityError('EXISTING_HISTORY_NOT_REPLACED_WITH_BOOTSTRAP')
    # Detect deleted artifacts from an earlier monitoring run before bootstrapping.
    runs = api(f'repos/{repository}/actions/workflows/{workflow}/runs?branch={branch}&status=completed&per_page=20')
    if not isinstance(runs, dict) or not isinstance(runs.get('workflow_runs'), list):
        raise ReliabilityError('GITHUB_HISTORY_RESPONSE_INVALID')
    for run in runs['workflow_runs']:
        if run.get('head_branch') != branch or run.get('event') not in {'schedule', 'workflow_dispatch', 'push'}:
            continue
        jobs = api(f'repos/{repository}/actions/runs/{int(run["id"])}/jobs?per_page=100')
        if not isinstance(jobs, dict) or not isinstance(jobs.get('jobs'), list):
            raise ReliabilityError('GITHUB_HISTORY_RESPONSE_INVALID')
        if any(step.get('name') == 'Persist source monitoring history' and step.get('conclusion') == 'success'
               for job in jobs['jobs'] for step in job.get('steps', [])):
            raise ReliabilityError('AUTHENTICATED_HISTORY_MISSING_NO_RESET')
    state = new_state()
    state.update(repository=repository, workflow=workflow)
    save(destination, state)
    return 'FIRST_RUN_BOOTSTRAP_NO_HEALTH_ASSUMED'


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description='Validate durable nonempty monitoring checkpoint before upload')
    parser.add_argument('--checkpoint', type=Path, required=True)
    args = parser.parse_args()
    ready = args.checkpoint.is_file() and bool(load(args.checkpoint)['sources'])
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'ready={str(ready).lower()}\n')
    if not ready:
        print('NO_NONEMPTY_HISTORY_CHECKPOINT; counters were not reset or replaced')
