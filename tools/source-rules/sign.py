#!/usr/bin/env python3
"""Offline publisher tool. Private signing material is never bundled in Mangaro."""
import argparse
import base64
import json
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("payload", type=pathlib.Path)
parser.add_argument("--private-key", required=True, type=pathlib.Path)
parser.add_argument("--output", required=True, type=pathlib.Path)
args = parser.parse_args()
payload = args.payload.read_text(encoding="utf-8")
rules = json.loads(payload)
assert rules["schema"] == 1 and rules["revision"] > 0 and rules["sourceId"] > 0
signature = subprocess.run(
    ["openssl", "dgst", "-sha256", "-sign", str(args.private_key)],
    input=payload.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
).stdout
args.output.write_text(json.dumps({"payload": payload, "signature": base64.b64encode(signature).decode("ascii")}, ensure_ascii=False), encoding="utf-8")
print(f"Signed source {rules['sourceId']} revision {rules['revision']} -> {args.output}")
