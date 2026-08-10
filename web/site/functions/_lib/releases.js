const DEFAULT_GITHUB_REPO = 'playbridgeapp/playbridge';
const ROLLOUT_HOLD_MS = 24 * 60 * 60 * 1000;

const PRODUCTS = {
  cli: {
    tagPrefix: 'cli-v',
    targets: {
      'linux-x86_64': 'playbridge-cli-linux-x86_64.tar.gz',
      'linux-aarch64': 'playbridge-cli-linux-aarch64.tar.gz',
      'macos-x86_64': 'playbridge-cli-macos-x86_64.tar.gz',
      'macos-aarch64': 'playbridge-cli-macos-aarch64.tar.gz',
      'windows-x86_64': 'playbridge-cli-windows-x86_64.tar.gz'
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
  const config = PRODUCTS[product];
  if (!config) return null;
  return config.targets[`${os}-${arch}`] ?? null;
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

/**
 * Resolve a rollout-eligible release asset from GitHub.
 *
 * @param {{
 *   product: string,
 *   os: string,
 *   arch: string,
 *   fetchImpl?: typeof fetch,
 *   githubRepo?: string,
 *   githubToken?: string,
 *   now?: number
 * }} options
 */
export async function resolveProductRelease(options) {
  const config = PRODUCTS[options.product];
  if (!config) throw new ReleaseResolverError(`Unsupported product: ${options.product}`);
  const assetName = productAssetName(options.product, options.os, options.arch);
  if (!assetName) {
    throw new ReleaseResolverError(
      `Unsupported target: ${options.product}/${options.os}/${options.arch}`
    );
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
      const rawVersion = tag.startsWith(config.tagPrefix)
        ? tag.slice(config.tagPrefix.length)
        : '';
      return { release, rawVersion, parsedVersion: parseVersion(rawVersion) };
    })
    .filter((candidate) => candidate.parsedVersion)
    .filter((candidate) => {
      const published = Date.parse(candidate.release.published_at ?? '');
      return Number.isFinite(published) && now - published >= ROLLOUT_HOLD_MS;
    })
    .sort((left, right) => compareVersions(left.parsedVersion, right.parsedVersion));

  for (const candidate of candidates) {
    const assets = Array.isArray(candidate.release.assets) ? candidate.release.assets : [];
    const asset = assets.find((value) => value?.name === assetName);
    if (!asset?.browser_download_url) continue;

    let sha256 = digestSha256(asset.digest);
    if (!sha256) {
      const sumsAsset = assets.find((value) => value?.name === 'SHA256SUMS');
      if (!sumsAsset?.browser_download_url) continue;
      const sumsResponse = await fetchImpl(sumsAsset.browser_download_url, {
        headers: { 'User-Agent': 'PlayBridge-Update-Resolver' }
      });
      if (!sumsResponse.ok) continue;
      sha256 = checksumFromSums(await sumsResponse.text(), assetName);
    }
    if (!sha256) continue;

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
        name: assetName,
        url: asset.browser_download_url,
        sha256,
        size: Number.isFinite(asset.size) ? asset.size : null
      }
    };
  }

  throw new ReleaseResolverError('No rollout-eligible release has a verified target asset');
}
