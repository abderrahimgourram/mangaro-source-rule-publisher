#!/usr/bin/env python3
"""Always-present merge check with conservative in-job change filtering; no PR secrets."""
import json
import os
import re
from pathlib import Path
from reliability import gh_json


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    repo = os.environ['GITHUB_REPOSITORY']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid repository')
    if 'pull_request' in event:
        number = int(event['number'])
        files = []
        for page in range(1, 11):
            batch = gh_json(f'repos/{repo}/pulls/{number}/files?per_page=100&page={page}')
            files += [f['filename'] for f in batch]
            if len(batch) < 100:
                break
        else:
            files = ['tools/', '.github/workflows/', 'app/src/main/java/eu/kanade/tachiyomi/source/']
    elif 'before' in event and event['before'] != '0' * 40:
        before, after = event['before'], event['after']
        if not re.fullmatch('[a-f0-9]{40}', before) or not re.fullmatch('[a-f0-9]{40}', after):
            raise ValueError('Invalid commit range')
        data = gh_json(f'repos/{repo}/compare/{before}...{after}')
        files = [f['filename'] for f in data.get('files', [])]
        if len(files) >= 300:
            files += ['tools/', '.github/workflows/', 'app/src/main/java/eu/kanade/tachiyomi/source/']
    else:
        files = ['tools/', '.github/workflows/', 'app/src/main/java/eu/kanade/tachiyomi/source/']
    fast = any(p.startswith(('tools/', 'tests/', 'rules/', 'vercel-seanovel/', '.github/workflows/', 'app/src/main/java/eu/kanade/tachiyomi/source/', 'app/src/androidTest/', 'app/src/main/java/eu/kanade/tachiyomi/network/', 'source-api/', 'app/src/main/java/eu/kanade/tachiyomi/network/')) or p.endswith(('.json', 'requirements.txt')) or p in {'bundle.json', 'gradle.properties'} for p in files)
    native = any(p.startswith(('app/src/main/java/eu/kanade/tachiyomi/source/', 'app/src/androidTest/', 'source-api/', 'app/src/main/java/eu/kanade/tachiyomi/network/')) or 'source-rules' in p and 'schema' in p or p in {'gradle.properties', 'tools/source-rules/production/public/index.json'} for p in files)
    workflows = any(p.startswith('.github/workflows/') for p in files)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(f'fast={str(fast).lower()}\nnative={str(native).lower()}\nworkflows={str(workflows).lower()}\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write(f'Change gate: fast tests {"required" if fast else "skipped (documentation only)"}; native compilation {"required" if native else "skipped"}.\n')


if __name__ == '__main__':
    main()
