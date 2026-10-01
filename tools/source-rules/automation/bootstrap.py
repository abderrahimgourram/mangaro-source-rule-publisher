#!/usr/bin/env python3
"""Install CI secrets directly through authenticated APIs; no plaintext secret staging files."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import sys
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent))
from automation.core import Unsafe, load_public
from cryptography.hazmat.primitives import serialization

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo',default='abderrahimgourram/mangaro-source-rule-publisher')
args=parser.parse_args()
project=HERE.parents[2]
key_path=Path.home()/'.local/share/mangaro-source-rules/signing/private.pem'
try:
    if key_path.resolve().is_relative_to(project) or key_path.stat().st_mode & 0o077: raise Unsafe('INSECURE_KEY_LOCATION')
    private=key_path.read_bytes()
    key=serialization.load_pem_private_key(private,password=None)
    props=dict(l.split('=',1) for l in (project/'gradle.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
    if base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()!=props['sourceRulesPublicKey']: raise Unsafe('TRUST_ANCHOR_MISMATCH')
    account=json.loads(subprocess.run(['gh','api','repos/'+args.repo],capture_output=True,check=True).stdout)
    if not account['private'] or not account.get('permissions',{}).get('admin'): raise Unsafe('PRIVATE_ADMIN_REPOSITORY_REQUIRED')
    bundle=json.dumps({'signingKeyBase64':base64.b64encode(private).decode()}).encode()
    for secret in ['MANGARO_AUTOMATION_SECRET','MANGARO_STAGING_AUTOMATION_SECRET']:
        result=subprocess.run(['gh','secret','set',secret,'--repo',args.repo],input=bundle,capture_output=True)
        if result.returncode: raise Unsafe('GITHUB_SECRET_INSTALL_FAILED')
        print(secret+': installed securely; production trust anchor unchanged.')
    names=subprocess.run(['gh','secret','list','--repo',args.repo,'--json','name'],capture_output=True,check=True).stdout
    if 'MANGARO_VERCEL_TOKEN' not in {x['name'] for x in json.loads(names)}:
        raise Unsafe('VERCEL_TOKEN_SETUP_REQUIRED_IN_GITHUB_SECRET_STORE')
    print('Vercel CI secret exists; no credential contents retrieved or printed.')
except Exception as e:
    # Suppress credential-bearing API bodies and subprocess output.
    print('Secure bootstrap failed: '+(str(e) if isinstance(e,Unsafe) else type(e).__name__),file=sys.stderr)
    sys.exit(1)
