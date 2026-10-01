#!/usr/bin/env python3
"""Sign one reviewed source repair and deploy only public static assets (no APK build)."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
HOST = 'https://mangaro-source-rules.vercel.app'
DEPLOY = HERE / 'production'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('payload', type=Path)
parser.add_argument('--private-key', type=Path, default=Path.home() / '.local/share/mangaro-source-rules/signing/private.pem')
args = parser.parse_args()
key = args.private_key.resolve(strict=True)
if key.is_relative_to(PROJECT) or key.stat().st_mode & 0o077:
    parser.error('Private key must be outside the Android project and readable only by its owner (chmod 600).')
public_der = subprocess.run(['openssl', 'pkey', '-in', str(key), '-pubout', '-outform', 'DER'], check=True, capture_output=True).stdout
props = dict(line.split('=', 1) for line in (PROJECT / 'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
if base64.b64encode(public_der).decode() != props['sourceRulesPublicKey'] or props['sourceRulesUrl'] != HOST:
    parser.error('Signing key/feed differs from the production APK configuration.')
payload = args.payload.read_text(encoding='utf-8')
rules = json.loads(payload)
sid = str(rules['sourceId'])
index_path = DEPLOY / 'public/index.json'
index = json.loads(index_path.read_text())
if sid not in index['sources'] or rules.get('schema') != 1 or type(rules.get('revision')) is not int or rules['revision'] < 1:
    parser.error('Unknown internal source, schema, or revision.')
if rules.get('baseUrl', '').rstrip('/') == HOST or any('/verification/' in op.get('endpoint', '') for op in rules.get('operations', {}).values()):
    parser.error('Acceptance fixtures cannot be published as production repairs.')
required = {'popular', 'latest', 'search', 'details', 'chapters', 'pages'}
if set(rules.get('operations', {})) != required:
    parser.error('A complete reviewed six-operation profile is required; do not replace native parsers with partial profiles.')
# The APK performs full schema and live semantic validation before any activation.
# This operator preflight additionally prevents accidental rollback/revision reuse.
target = DEPLOY / 'public' / (sid + '.json')
def revision_of(envelope):
    with tempfile.TemporaryDirectory(prefix='mangaro-signature-') as td:
        directory = Path(td)
        (directory / 'public.der').write_bytes(public_der)
        (directory / 'payload').write_bytes(envelope['payload'].encode('utf-8'))
        (directory / 'signature').write_bytes(base64.b64decode(envelope['signature'], validate=True))
        subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(directory / 'public.der'), '-keyform', 'DER', '-signature', str(directory / 'signature'), str(directory / 'payload')], check=True, capture_output=True)
    previous = json.loads(envelope['payload'])
    if str(previous['sourceId']) != sid:
        parser.error('Published manifest identity mismatch.')
    return previous['revision']
previous_revision = index['sources'][sid].get('publishedRevision') or 0
if target.exists(): previous_revision = max(previous_revision, revision_of(json.loads(target.read_text())))
try:
    with urllib.request.urlopen(HOST + '/' + sid + '.json', timeout=15) as response:
        previous_revision = max(previous_revision, revision_of(json.load(response)))
except urllib.error.HTTPError as error:
    if error.code != 404: raise
# Offline/other transport failures abort publishing rather than guess the revision floor.
if rules['revision'] <= previous_revision:
    parser.error(f'Revision must exceed {previous_revision}; increment revision in the payload.')
old_manifest = target.read_bytes() if target.exists() else None
old_index = index_path.read_bytes()
try:
    subprocess.run(['python3', str(HERE / 'sign.py'), str(args.payload.resolve()), '--private-key', str(key), '--output', str(target)], check=True)
    index['sources'][sid].update(state='SIGNED_REPAIR_AVAILABLE', publishedRevision=rules['revision'])
    index_path.write_text(json.dumps(index, indent=2) + '\n')
    link = DEPLOY / '.vercel/project.json'
    link.parent.mkdir(exist_ok=True)
    expected = json.loads((DEPLOY / 'hosting.json').read_text())
    if link.exists() and json.loads(link.read_text()).get('projectId') != expected['projectId']:
        parser.error('Deployment directory is linked to another project.')
    link.write_text(json.dumps(expected))
    subprocess.run(['npx', '--yes', 'vercel@62.1.0', 'deploy', '--prod', '--yes', '--scope', 'jalem'], cwd=DEPLOY, check=True)
except BaseException:
    if old_manifest is None: target.unlink(missing_ok=True)
    else: target.write_bytes(old_manifest)
    index_path.write_bytes(old_index)
    raise
# Check the public production alias, independent of Vercel authentication.
with urllib.request.urlopen(HOST + '/' + sid + '.json', timeout=20) as response:
    deployed = json.load(response)
if revision_of(deployed) != rules['revision'] or deployed['payload'] != payload:
    raise RuntimeError('Deployment returned success but the production alias has not published this exact payload; inspect before retrying.')
print(f'Published source {sid} revision {rules["revision"]}: {HOST}/{sid}.json')
