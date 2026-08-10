import assert from 'node:assert/strict';
import test from 'node:test';

import {
  DEFAULT_ROLLOUT_DELAY_HOURS,
  ReleaseResolverError,
  checksumFromSums,
  downloadTargetForPlatform,
  productAssetName,
  resolveProductRelease,
  rolloutDelayHours
} from './releases.js';

const NOW = Date.parse('2026-08-10T12:00:00Z');
const CLI_ASSET = 'playbridge-cli-macos-aarch64.tar.gz';
const SHA = 'a'.repeat(64);

function response(body, status = 200) {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
}

function release(product, version, ageHours, overrides = {}) {
  const config = {
    cli: { tag: 'cli-v', asset: CLI_ASSET },
    phone: { tag: 'phone-v', asset: `playbridge-phone-${version}-universal-release.apk` },
    'tv-player': {
      tag: 'tv-player-v',
      asset: `playbridge-tv-player-${version}-universal-release.apk`
    },
    'tv-browser': {
      tag: 'tv-geckoview-plugin-v',
      asset: `playbridge-tv-geckoview-plugin-${version}-universal-release.apk`
    },
    desktop: { tag: 'desktop-v', asset: `playbridge-desktop-macos-${version}.zip` },
    extension: { tag: 'extension-v', asset: `playbridge-extension-${version}.xpi` }
  }[product];
  return {
    tag_name: `${config.tag}${version}`,
    published_at: new Date(NOW - ageHours * 60 * 60 * 1000).toISOString(),
    html_url: `https://github.test/releases/tag/${config.tag}${version}`,
    draft: false,
    prerelease: false,
    assets: [
      {
        name: config.asset,
        browser_download_url: `https://github.test/${config.asset}`,
        size: 42,
        digest: product === 'cli' ? `sha256:${SHA}` : undefined
      }
    ],
    ...overrides
  };
}

test('maps supported CLI targets', () => {
  assert.equal(productAssetName('cli', 'linux', 'aarch64'), 'playbridge-cli-linux-aarch64.tar.gz');
  assert.equal(productAssetName('cli', 'windows', 'aarch64'), null);
});

test('maps every direct download platform to a release target', () => {
  assert.deepEqual(downloadTargetForPlatform('cli-macos-aarch64'), {
    product: 'cli',
    target: 'macos-aarch64'
  });
  assert.deepEqual(downloadTargetForPlatform('android'), { product: 'phone', target: 'universal' });
  assert.deepEqual(downloadTargetForPlatform('android-v8a'), { product: 'phone', target: 'arm64-v8a' });
  assert.deepEqual(downloadTargetForPlatform('tv-player-v7a'), {
    product: 'tv-player',
    target: 'armeabi-v7a'
  });
  assert.deepEqual(downloadTargetForPlatform('tv-browser-universal'), {
    product: 'tv-browser',
    target: 'universal'
  });
  assert.deepEqual(downloadTargetForPlatform('macos'), { product: 'desktop', target: 'macos' });
  assert.deepEqual(downloadTargetForPlatform('windows'), { product: 'desktop', target: 'windows' });
  assert.deepEqual(downloadTargetForPlatform('linux'), { product: 'desktop', target: 'linux' });
  assert.deepEqual(downloadTargetForPlatform('firefox'), { product: 'extension', target: 'firefox' });
  assert.equal(downloadTargetForPlatform('unknown'), null);
});

test('parses GNU and BSD checksum lines', () => {
  assert.equal(checksumFromSums(`${SHA}  ${CLI_ASSET}\n`, CLI_ASSET), SHA);
  assert.equal(checksumFromSums(`${SHA} *${CLI_ASSET}\n`, CLI_ASSET), SHA);
});

test('uses the default delay and accepts an explicit bounded release-note override', () => {
  assert.equal(rolloutDelayHours(), DEFAULT_ROLLOUT_DELAY_HOURS);
  assert.equal(rolloutDelayHours('<!-- playbridge-rollout-delay-hours: 0 -->'), 0);
  assert.equal(rolloutDelayHours('<!-- playbridge-rollout-delay-hours: 72 -->'), 72);
  assert.equal(rolloutDelayHours('<!-- playbridge-rollout-delay-hours: 169 -->'), DEFAULT_ROLLOUT_DELAY_HOURS);
  assert.equal(rolloutDelayHours('<!-- playbridge-rollout-delay-hours: soon -->'), DEFAULT_ROLLOUT_DELAY_HOURS);
});

test('holds fresh releases by default and selects the newest eligible semantic version', async () => {
  const releases = [release('cli', '9.0.0', 2), release('cli', '0.9.0', 48), release('cli', '0.10.0', 48)];
  const manifest = await resolveProductRelease({
    product: 'cli',
    os: 'macos',
    arch: 'aarch64',
    now: NOW,
    fetchImpl: async () => response(releases)
  });
  assert.equal(manifest.version, '0.10.0');
});

test('offers an explicitly immediate release without waiting for the default delay', async () => {
  const manifest = await resolveProductRelease({
    product: 'phone',
    target: 'universal',
    now: NOW,
    fetchImpl: async () =>
      response([release('phone', '1.2.3', 1, { body: '<!-- playbridge-rollout-delay-hours: 0 -->' })])
  });
  assert.equal(manifest.version, '1.2.3');
});

test('skips drafts, prereleases, and malformed assets while preserving CLI checksum fallback', async () => {
  const draft = release('cli', '5.0.0', 48, { draft: true });
  const prerelease = release('cli', '4.0.0', 48, { prerelease: true });
  const broken = release('cli', '3.0.0', 48, { assets: [] });
  const valid = release('cli', '2.0.0', 48);
  delete valid.assets[0].digest;
  valid.assets.push({ name: 'SHA256SUMS', browser_download_url: 'https://github.test/sums' });
  const fetchImpl = async (url) =>
    String(url).endsWith('/sums')
      ? response(`${SHA}  ${CLI_ASSET}\n`)
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

test('falls back when a newer eligible release lacks the requested product asset', async () => {
  const missingMac = release('desktop', '2.0.0', 48, {
    assets: [{ name: 'playbridge-desktop-linux-2.0.0.tar.gz', browser_download_url: 'https://github.test/linux' }]
  });
  const manifest = await resolveProductRelease({
    product: 'desktop',
    target: 'macos',
    now: NOW,
    fetchImpl: async () => response([missingMac, release('desktop', '1.0.0', 48)])
  });
  assert.equal(manifest.version, '1.0.0');
});

test('rejects unsupported targets and releases still inside the default hold', async () => {
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
      fetchImpl: async () => response([release('cli', '1.0.0', 23)])
    }),
    /rollout-eligible/
  );
});
