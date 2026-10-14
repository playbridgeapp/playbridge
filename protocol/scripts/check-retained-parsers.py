#!/usr/bin/env python3
"""Check provenance/integrity of retained pre-contract parser fragments."""
from pathlib import Path
import hashlib
import json
import subprocess
root = Path(__file__).resolve().parents[2]
legacy = root / 'protocol/tests/legacy'
manifest = json.loads((legacy / 'manifest.json').read_text())
wrappers = {
    'android-fields.kt.inc': root / 'protocol/tests/kotlin/stage5/contract/LegacyAndroidParserTest.kt',
    'apple-fields.swift.inc': legacy / 'LegacyAppleParser.swift',
    'desktop-auth.dart.inc': legacy / 'legacy_desktop_parser.dart',
    'rust-models.rs.inc': legacy / 'rust/src/lib.rs',
}
for entry in manifest['fragments']:
    fragment = (legacy / entry['file']).read_bytes()
    assert hashlib.sha256(fragment).hexdigest() == entry['sha256'], entry['file']
    assert fragment.decode() in wrappers[entry['file']].read_text(), entry['file'] + ' wrapper drift'
    original = subprocess.check_output(['git', 'show', manifest['revision'] + ':' + entry['source']], cwd=root)
    assert hashlib.sha256(original).hexdigest() == entry['sourceSha256'], entry['source']
    assert fragment in original, entry['source'] + ' fragment not retained verbatim'
print(f"Retained parser provenance OK: {len(manifest['fragments'])} fragments at {manifest['revision'][:8]}")

old_flow = subprocess.check_output(['git', 'show', manifest['revision'] + ':protocol/docs/WSS_FLOW.md'], cwd=root, text=True)
new_flow = (root / 'protocol/docs/WSS_FLOW.md').read_text()
start, end = '## First-time SAS pairing', '## Reconnection authentication'
assert old_flow[old_flow.index(start):old_flow.index(end)] == new_flow[new_flow.index(start):new_flow.index(end)], 'SAS/protected-credential derivation text changed'
print('SAS transcript, labels, AAD and protected-credential flow text unchanged')
