import assert from 'node:assert/strict';
import test from 'node:test';

import {
  ReleaseResolverError,
  checksumFromSums,
  productAssetName,
  resolveProductRelease
} from './releases.js';

const NOW = Date.parse('2026-08-10T12:00:00Z');
const ASSET = 'playbridge-cli-macos-aarch64.tar.gz';
const SHA = 'a'.repeat(64);

function response(body, status = 200) {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
}

function release(version, ageHours, overrides = {}) {
  return {
    tag_name: `cli-v${version}`,
    published_at: new Date(NOW - ageHours * 60 * 60 * 1000).toISOString(),
    html_url: `https://github.test/releases/tag/cli-v${version}`,
    draft: false,
    prerelease: false,
    assets: [
      {
        name: ASSET,
        browser_download_url: `https://github.test/${ASSET}`,
        size: 42,
        digest: `sha256:${SHA}`
      }
    ],
    ...overrides
  };
}

test('maps supported CLI targets', () => {
  assert.equal(productAssetName('cli', 'linux', 'aarch64'), 'playbridge-cli-linux-aarch64.tar.gz');
  assert.equal(productAssetName('cli', 'windows', 'aarch64'), null);
});

test('parses GNU and BSD checksum lines', () => {
  assert.equal(checksumFromSums(`${SHA}  ${ASSET}\n`, ASSET), SHA);
  assert.equal(checksumFromSums(`${SHA} *${ASSET}\n`, ASSET), SHA);
});

test('holds a fresh release and selects the newest eligible semantic version', async () => {
  const releases = [release('9.0.0', 2), release('0.9.0', 48), release('0.10.0', 48)];
  const manifest = await resolveProductRelease({
    product: 'cli',
    os: 'macos',
    arch: 'aarch64',
    now: NOW,
    fetchImpl: async () => response(releases)
  });
  assert.equal(manifest.version, '0.10.0');
});

test('skips drafts, prereleases, malformed assets, and uses SHA256SUMS', async () => {
  const draft = release('5.0.0', 48, { draft: true });
  const prerelease = release('4.0.0', 48, { prerelease: true });
  const broken = release('3.0.0', 48, { assets: [] });
  const valid = release('2.0.0', 48);
  delete valid.assets[0].digest;
  valid.assets.push({ name: 'SHA256SUMS', browser_download_url: 'https://github.test/sums' });
  const fetchImpl = async (url) =>
    String(url).endsWith('/sums')
      ? response(`${SHA}  ${ASSET}\n`)
      : response([draft, prerelease, broken, valid]);
  const manifest = await resolveProductRelease({
    product: 'cli',
    os: 'macos',
    arch: 'aarch64',
    now: NOW,
    fetchImpl
  });
  assert.equal(manifest.version, '2.0.0');
  assert.equal(manifest.asset.sha256, SHA);
});

test('rejects unsupported targets and releases still inside the hold', async () => {
  await assert.rejects(
    resolveProductRelease({ product: 'cli', os: 'windows', arch: 'aarch64' }),
    ReleaseResolverError
  );
  await assert.rejects(
    resolveProductRelease({
      product: 'cli',
      os: 'macos',
      arch: 'aarch64',
      now: NOW,
      fetchImpl: async () => response([release('1.0.0', 23)])
    }),
    /rollout-eligible/
  );
});
