const DEFAULT_GITHUB_REPO = 'playbridgeapp/playbridge';
export const DEFAULT_ROLLOUT_DELAY_HOURS = 24;
export const MAX_ROLLOUT_DELAY_HOURS = 168;

const PRODUCTS = {
  cli: {
    tagPrefix: 'cli-v',
    targets: {
      'linux-x86_64': { assetName: 'playbridge-cli-linux-x86_64.tar.gz', requireChecksum: true },
      'linux-aarch64': { assetName: 'playbridge-cli-linux-aarch64.tar.gz', requireChecksum: true },
      'macos-x86_64': { assetName: 'playbridge-cli-macos-x86_64.tar.gz', requireChecksum: true },
      'macos-aarch64': { assetName: 'playbridge-cli-macos-aarch64.tar.gz', requireChecksum: true },
      'windows-x86_64': { assetName: 'playbridge-cli-windows-x86_64.tar.gz', requireChecksum: true }
    }
  },
  phone: {
    tagPrefix: 'phone-v',
    targets: {
      universal: { assetPattern: /^playbridge-phone-.*-universal-release\.apk$/ },
      'arm64-v8a': { assetPattern: /^playbridge-phone-.*-arm64-v8a-release\.apk$/ },
      'armeabi-v7a': { assetPattern: /^playbridge-phone-.*-armeabi-v7a-release\.apk$/ }
    }
  },
  'tv-player': {
    tagPrefix: 'tv-player-v',
    targets: {
      universal: { assetPattern: /^playbridge-tv-player-.*-universal-release\.apk$/ },
      'arm64-v8a': { assetPattern: /^playbridge-tv-player-.*-arm64-v8a-release\.apk$/ },
      'armeabi-v7a': { assetPattern: /^playbridge-tv-player-.*-armeabi-v7a-release\.apk$/ }
    }
  },
  'tv-browser': {
    tagPrefix: 'tv-geckoview-plugin-v',
    targets: {
      universal: { assetPattern: /^playbridge-tv-geckoview-plugin-.*-universal-release\.apk$/ },
      'arm64-v8a': { assetPattern: /^playbridge-tv-geckoview-plugin-.*-arm64-v8a-release\.apk$/ },
      'armeabi-v7a': { assetPattern: /^playbridge-tv-geckoview-plugin-.*-armeabi-v7a-release\.apk$/ }
    }
  },
  desktop: {
    tagPrefix: 'desktop-v',
    targets: {
      macos: { assetPattern: /^playbridge-desktop-macos-.*\.zip$/ },
      windows: { assetPattern: /^playbridge-desktop-windows-.*\.zip$/ },
      linux: { assetPattern: /^playbridge-desktop-linux-.*\.tar\.gz$/ }
    }
  },
  extension: {
    tagPrefix: 'extension-v',
    targets: {
      firefox: { assetPattern: /^playbridge-extension-.*\.xpi$/ }
    }
  }
};

export class ReleaseResolverError extends Error {
  /** @param {string} message */
  constructor(message) {
    super(message);
    this.name = 'ReleaseResolverError';
  }
}

/**
 * @param {string} product
 * @param {string} os
 * @param {string} arch
 */
export function productAssetName(product, os, arch) {
  return PRODUCTS[product]?.targets[`${os}-${arch}`]?.assetName ?? null;
}

/**
 * Maps the public /download/<platform> path to a release product target.
 * @param {string} platform
 */
export function downloadTargetForPlatform(platform) {
  const cliTarget = /^cli-(macos|linux|windows)-(x86_64|aarch64)$/.exec(platform);
  if (cliTarget) return { product: 'cli', target: `${cliTarget[1]}-${cliTarget[2]}` };

  let target = 'universal';
  let cleanPlatform = platform;
  if (platform.endsWith('-v7a')) {
    target = 'armeabi-v7a';
    cleanPlatform = platform.slice(0, -4);
  } else if (platform.endsWith('-v8a')) {
    target = 'arm64-v8a';
    cleanPlatform = platform.slice(0, -4);
  } else if (platform.endsWith('-universal')) {
    cleanPlatform = platform.slice(0, -10);
  }

  if (cleanPlatform === 'android') return { product: 'phone', target };
  if (cleanPlatform === 'tv-player') return { product: 'tv-player', target };
  if (cleanPlatform === 'tv-browser') return { product: 'tv-browser', target };
  if (cleanPlatform === 'macos' || cleanPlatform === 'windows' || cleanPlatform === 'linux') {
    return { product: 'desktop', target: cleanPlatform };
  }
  if (cleanPlatform === 'firefox') return { product: 'extension', target: 'firefox' };
  return null;
}

/** @param {string} value */
function parseVersion(value) {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(value);
  return match ? match.slice(1).map(Number) : null;
}

/** @param {number[]} left @param {number[]} right */
function compareVersions(left, right) {
  for (let index = 0; index < 3; index += 1) {
    if (left[index] !== right[index]) return right[index] - left[index];
  }
  return 0;
}

/** @param {string} value */
function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * @param {unknown} body
 */
export function rolloutDelayHours(body) {
  if (typeof body !== 'string') return DEFAULT_ROLLOUT_DELAY_HOURS;
  const match = /<!--\s*playbridge-rollout-delay-hours\s*:\s*(\d+)\s*-->/i.exec(body);
  if (!match) return DEFAULT_ROLLOUT_DELAY_HOURS;
  const hours = Number(match[1]);
  return Number.isSafeInteger(hours) && hours >= 0 && hours <= MAX_ROLLOUT_DELAY_HOURS
    ? hours
    : DEFAULT_ROLLOUT_DELAY_HOURS;
}

/**
 * @param {unknown} digest
 * @returns {string | null}
 */
function digestSha256(digest) {
  if (typeof digest !== 'string') return null;
  const match = /^sha256:([a-f0-9]{64})$/i.exec(digest.trim());
  return match?.[1].toLowerCase() ?? null;
}

/**
 * @param {string} sums
 * @param {string} assetName
 */
export function checksumFromSums(sums, assetName) {
  const pattern = new RegExp(
    `^([a-f0-9]{64})\\s+\\*?${escapeRegex(assetName)}\\s*$`,
    'im'
  );
  return pattern.exec(sums)?.[1].toLowerCase() ?? null;
}

/** @param {{ assetName?: string, assetPattern?: RegExp }} target */
function findAsset(assets, target) {
  return assets.find((asset) =>
    target.assetName ? asset?.name === target.assetName : target.assetPattern?.test(asset?.name ?? '')
  );
}

/**
 * Resolve a rollout-eligible release asset from GitHub.
 *
 * @param {{
 *   product: string,
 *   os?: string,
 *   arch?: string,
 *   target?: string,
 *   fetchImpl?: typeof fetch,
 *   githubRepo?: string,
 *   githubToken?: string,
 *   now?: number
 * }} options
 */
export async function resolveProductRelease(options) {
  const config = PRODUCTS[options.product];
  const targetKey = options.target ?? `${options.os ?? ''}-${options.arch ?? ''}`;
  const target = config?.targets[targetKey];
  if (!config || !target) {
    throw new ReleaseResolverError(`Unsupported target: ${options.product}/${targetKey}`);
  }

  const fetchImpl = options.fetchImpl ?? fetch;
  const githubRepo = options.githubRepo ?? DEFAULT_GITHUB_REPO;
  const headers = {
    'User-Agent': 'PlayBridge-Update-Resolver',
    Accept: 'application/vnd.github+json'
  };
  if (options.githubToken) headers.Authorization = `Bearer ${options.githubToken}`;

  const apiResponse = await fetchImpl(
    `https://api.github.com/repos/${githubRepo}/releases?per_page=100`,
    { headers }
  );
  if (!apiResponse.ok) {
    throw new ReleaseResolverError(`GitHub API returned HTTP ${apiResponse.status}`);
  }

  const releases = await apiResponse.json();
  if (!Array.isArray(releases)) {
    throw new ReleaseResolverError('GitHub API returned an invalid release list');
  }

  const now = options.now ?? Date.now();
  const candidates = releases
    .filter((release) => !release?.draft && !release?.prerelease)
    .map((release) => {
      const tag = typeof release?.tag_name === 'string' ? release.tag_name : '';
      const rawVersion = tag.startsWith(config.tagPrefix) ? tag.slice(config.tagPrefix.length) : '';
      const publishedAt = Date.parse(release.published_at ?? '');
      const delayHours = rolloutDelayHours(release.body);
      return { release, rawVersion, parsedVersion: parseVersion(rawVersion), publishedAt, delayHours };
    })
    .filter((candidate) => candidate.parsedVersion && Number.isFinite(candidate.publishedAt))
    .filter((candidate) => now >= candidate.publishedAt + candidate.delayHours * 60 * 60 * 1000)
    .sort((left, right) => compareVersions(left.parsedVersion, right.parsedVersion));

  for (const candidate of candidates) {
    const assets = Array.isArray(candidate.release.assets) ? candidate.release.assets : [];
    const asset = findAsset(assets, target);
    if (!asset?.browser_download_url) continue;

    let sha256 = null;
    if (target.requireChecksum) {
      sha256 = digestSha256(asset.digest);
      if (!sha256) {
        const sumsAsset = assets.find((value) => value?.name === 'SHA256SUMS');
        if (!sumsAsset?.browser_download_url) continue;
        const sumsResponse = await fetchImpl(sumsAsset.browser_download_url, {
          headers: { 'User-Agent': 'PlayBridge-Update-Resolver' }
        });
        if (!sumsResponse.ok) continue;
        sha256 = checksumFromSums(await sumsResponse.text(), asset.name);
      }
      if (!sha256) continue;
    }

    return {
      schemaVersion: 1,
      product: options.product,
      channel: 'stable',
      version: candidate.rawVersion,
      publishedAt: candidate.release.published_at,
      releaseUrl:
        candidate.release.html_url ??
        `https://github.com/${githubRepo}/releases/tag/${candidate.release.tag_name}`,
      asset: {
        name: asset.name,
        url: asset.browser_download_url,
        sha256,
        size: Number.isFinite(asset.size) ? asset.size : null
      }
    };
  }

  throw new ReleaseResolverError('No rollout-eligible release has a matching target asset');
}
