#!/usr/bin/env python3
"""Dependency-free scheduling gate. No source requests, signing keys, Gradle or emulator."""
import argparse
import json
import os
from pathlib import Path
import time
from reliability import ReliabilityError, atomic, load, restore_history, select, report


def inventory(root, system):
    if system == 'manga':
        index = json.loads((root / 'tools/source-rules/production/public/index.json').read_text())['sources']
        return [{'id': sid, 'name': item['name']} for sid, item in index.items()]
    return [{'id': item['domain'], 'name': item['domain']} for item in
            [json.loads(p.read_text()) for p in sorted((root / 'rules').glob('*.json'))]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--system', choices=['manga', 'novel'], required=True)
    parser.add_argument('--state', type=Path, default=Path('.source-health/state.json'))
    parser.add_argument('--output', type=Path, default=Path('health-plan.json'))
    parser.add_argument('--restore', action='store_true')
    parser.add_argument('--report', type=Path, default=Path('health-plan-report.json'))
    parser.add_argument('--mode', choices=['monitor', 'retry', 'deep', 'weekly', 'manual'], default='monitor')
    parser.add_argument('--source', default='all')
    args = parser.parse_args()
    root = Path.cwd()
    sources = inventory(root, args.system)
    started = time.monotonic()
    try:
        workflow = 'source-rule-publisher.yml' if args.system == 'manga' else 'health.yml'
        mode = args.mode
        if os.environ.get('GITHUB_EVENT_NAME') == 'schedule':
            schedule = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())['schedule']
            schedules = {'17 */6 * * *': 'monitor', '43 */12 * * *': 'monitor',
                         '47 */3 * * *': 'retry', '53 */3 * * *': 'retry',
                         '29 3 * * *': 'deep', '7 4 * * 0': 'weekly'}
            if schedule not in schedules:
                raise ReliabilityError('UNKNOWN_SCHEDULE')
            mode = schedules[schedule]
        if args.restore:
            repository = os.environ['GITHUB_REPOSITORY']
            branch = os.environ['TRUSTED_DEFAULT_BRANCH']
            if os.environ.get('GITHUB_REF') != 'refs/heads/' + branch or os.environ.get('GITHUB_EVENT_NAME') not in {'schedule', 'workflow_dispatch', 'push'}:
                raise ReliabilityError('UNTRUSTED_HISTORY_RUN')
            restore_history(args.state, repository, workflow, branch)
        state = load(args.state)
        selected = select(state, sources, mode, source=args.source)
        atomic(args.output, {'schema': 1, 'mode': mode, 'sources': selected, 'inventory': sources,
                             'generation': state['generation'], 'createdAt': int(time.time())})
        output = os.environ.get('GITHUB_OUTPUT')
        if output:
            with open(output, 'a') as stream:
                stream.write(f'work={str(bool(selected)).lower()}\nmode={mode}\nnative={str(bool(selected) and mode in {"deep", "weekly", "manual"}).lower()}\n')
        report(args.report, [], sources, mode, started, selected, planning=True)
    except (ReliabilityError, KeyError, ValueError) as error:
        code = str(error) if isinstance(error, ReliabilityError) else 'SCHEDULER_CONFIGURATION_INVALID'
        atomic(args.report, {'ciExecution': 'FAILED', 'reason': code, 'sources': []})
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a') as stream:
                stream.write(f'History/scheduler blocked: `{code}`. No source status inferred; existing history preserved.\n')
        print(code)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
