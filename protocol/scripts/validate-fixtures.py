#!/usr/bin/env python3
"""Validate real AsyncAPI component schemas with JSON Schema draft 2020-12.

Requires existing PyYAML and jsonschema packages; never installs dependencies.
Run from any cwd. Errors identify fixture/schema paths without echoing payloads.
"""
from pathlib import Path
import json
import sys
try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    sys.exit('Requires PyYAML and jsonschema in the selected Python environment; no packages were installed.')

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'tests/fixtures/receiver-capabilities'


def validator(document, name):
    return Draft202012Validator({
        '$ref': f'#/components/schemas/{name}',
        'components': document['components'],
    }, format_checker=FormatChecker())


def check_invariants(document, old):
    schemas = document['components']['schemas']
    retained = old['components']['schemas']
    for name in ('AuthResponse', 'CredentialBundle'):
        current, previous = schemas[name], retained[name]
        assert current['required'] == previous['required'], f'{name} required list changed'
        assert current['additionalProperties'] == previous['additionalProperties']
        assert set(current['properties']) == set(previous['properties']) | {'capabilities'}
        for key, value in previous['properties'].items():
            if key != 'features':
                assert current['properties'][key] == value, f'{name}.{key} changed'
        assert current['properties']['features'] == {'$ref': '#/components/schemas/ReceiverFeatures'}
    assert schemas['PairingApproved'] == retained['PairingApproved'], 'Protected outer frame changed'
    status = dict(schemas['Status'])
    status['properties'] = dict(status['properties'])
    error = status['properties'].pop('error')
    assert error['type'] == ['string', 'null'] and error['maxLength'] == 512
    assert status == retained['Status'], 'Only optional error may extend Status (loop stays in player_settings)'
    for name in ('BrowserCapabilitiesFrame', 'BrowserCapabilities'):
        assert schemas[name] == retained[name], f'{name} changed'
    assert schemas['PlayerSettings']['required'] == retained['PlayerSettings']['required']
    assert {'$ref': '#/components/schemas/ReceiverCapabilitiesFrame'} in schemas['ReceiverFrame']['oneOf']
    assert schemas['ReceiverCapabilitiesFrame']['required'] == ['type', 'revision', 'capabilities']
    assert schemas['ReceiverCapabilitiesFrame']['properties']['revision'] == {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    assert schemas['CapabilityIdentifier']['type'] == 'string'
    assert 'enum' not in schemas['CapabilityIdentifier']
    assert schemas['ReceiverFeatures']['items'] == {'type': 'string'}


def main():
    document = yaml.safe_load((ROOT / 'asyncapi.yaml').read_text())
    schemas = document['components']['schemas']
    for name, schema in schemas.items():
        Draft202012Validator.check_schema(schema)
    old = json.loads((ROOT / 'tests/legacy/schemas-39934a05.json').read_text())
    check_invariants(document, old)
    manifest = json.loads((FIXTURES / 'manifest.json').read_text())
    expected_files = {case['file'] for case in manifest} | {'manifest.json'}
    assert expected_files == {p.name for p in FIXTURES.glob('*.json')}, 'Unlisted/missing fixtures'
    assert len(manifest) == len({case['file'] for case in manifest}), 'Duplicate fixture names'
    failed = []
    for case in manifest:
        payload = json.loads((FIXTURES / case['file']).read_text())
        errors = list(validator(document, case['schema']).iter_errors(payload))
        if bool(errors) == case['valid']:
            paths = ', '.join('/'.join(map(str, e.absolute_path)) or '<root>' for e in errors)
            failed.append(f"{case['file']} ({case['schema']}): unexpected {'rejection at '+paths if errors else 'acceptance'}")
    if failed:
        sys.exit('\n'.join(failed))
    # A passing maintained parser is NOT universal old-schema compatibility.
    minimal = json.loads((FIXTURES / 'minimal-auth.json').read_text())
    assert validator(old, 'AuthResponse').is_valid(minimal)
    with_descriptor = dict(minimal, capabilities={'version': 1, 'surfaces': {}})
    assert not validator(old, 'AuthResponse').is_valid(with_descriptor)
    for name in ('AuthResponse', 'CredentialBundle'):
        baseline = minimal if name == 'AuthResponse' else {'token': 'TEST_ONLY_NOT_A_CREDENTIAL'}
        advertised_today = dict(baseline, features=['subtitle_resource_add_v1'])
        assert not validator(old, name).is_valid(advertised_today), f'{name} mismatch was not reproduced'
        assert validator(document, name).is_valid(advertised_today)
    positive = sum(case['valid'] for case in manifest)
    print(f'Payload schemas OK: {len(schemas)} schemas checked, {positive} positive / {len(manifest)-positive} negative fixtures')
    print('Legacy invariants OK; strict old-schema rejection and pre-existing subtitle-feature mismatches reproduced')


if __name__ == '__main__':
    main()
