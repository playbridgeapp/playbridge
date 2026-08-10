import { productAssetName, resolveProductRelease } from '../_lib/releases.js';

const GITHUB_REPO = 'playbridgeapp/playbridge';

interface Env {
  GITHUB_TOKEN?: string;
}

export const onRequestGet: PagesFunction<Env, 'platform'> = async (context) => {
  const platform = context.params.platform;
  const url = new URL(context.request.url);

  const cliTarget = /^cli-(macos|linux|windows)-(x86_64|aarch64)$/.exec(platform);
  if (cliTarget) {
    const [, os, arch] = cliTarget;
    if (!productAssetName('cli', os, arch)) {
      return new Response('CLI target not found', { status: 404 });
    }
    const cacheKey = new Request(url.toString(), context.request);
    const cache = caches.default;
    const cachedResponse = await cache.match(cacheKey);
    if (cachedResponse) return cachedResponse;
    try {
      const manifest = await resolveProductRelease({
        product: 'cli',
        os,
        arch,
        githubToken: context.env?.GITHUB_TOKEN
      });
      const response = new Response(null, {
        status: 302,
        headers: {
          Location: manifest.asset.url,
          'X-PlayBridge-Version': manifest.version,
          'X-PlayBridge-SHA256': manifest.asset.sha256,
          'Cache-Control': 'public, max-age=600'
        }
      });
      context.waitUntil(cache.put(cacheKey, response.clone()));
      return response;
    } catch {
      return new Response('CLI update metadata is temporarily unavailable', {
        status: 502,
        headers: { 'Cache-Control': 'no-store' }
      });
    }
  }

  let tagPrefix = '';
  let assetPattern = '';
  const fallbackUrl = `https://github.com/${GITHUB_REPO}/releases`;

  // Parse architecture suffix if present (e.g. -v7a, -v8a, -universal)
  let arch = '';
  let cleanPlatform = platform;
  if (platform.endsWith('-v7a')) {
    arch = 'armeabi-v7a';
    cleanPlatform = platform.substring(0, platform.length - 4);
  } else if (platform.endsWith('-v8a')) {
    arch = 'arm64-v8a';
    cleanPlatform = platform.substring(0, platform.length - 4);
  } else if (platform.endsWith('-universal')) {
    arch = 'universal';
    cleanPlatform = platform.substring(0, platform.length - 10);
  }

  if (cleanPlatform === 'android') {
    tagPrefix = 'phone-v';
    const finalArch = arch || 'universal';
    assetPattern = `^playbridge-phone-.*-${finalArch}-release\\.apk$`;
  } else if (cleanPlatform === 'tv-player') {
    tagPrefix = 'tv-player-v';
    const finalArch = arch || 'universal';
    assetPattern = `^playbridge-tv-player-.*-${finalArch}-release\\.apk$`;
  } else if (cleanPlatform === 'tv-browser') {
    tagPrefix = 'tv-geckoview-plugin-v';
    const finalArch = arch || 'universal';
    assetPattern = `^playbridge-tv-geckoview-plugin-.*-${finalArch}-release\\.apk$`;
  } else if (cleanPlatform === 'macos') {
    tagPrefix = 'desktop-v';
    assetPattern = '^playbridge-desktop-macos-.*\\.zip$';
  } else if (cleanPlatform === 'windows') {
    tagPrefix = 'desktop-v';
    assetPattern = '^playbridge-desktop-windows-.*\\.zip$';
  } else if (cleanPlatform === 'linux') {
    tagPrefix = 'desktop-v';
    assetPattern = '^playbridge-desktop-linux-.*\\.tar\\.gz$';
  } else if (cleanPlatform === 'firefox') {
    tagPrefix = 'extension-v';
    assetPattern = '^playbridge-extension-.*\\.xpi$';
  } else if (cleanPlatform === 'appletv') {
    return Response.redirect(`https://github.com/${GITHUB_REPO}/tree/main/tv/apple`, 302);
  } else {
    return new Response('Platform Not Found', { status: 404 });
  }

  // Set up caching key
  const cacheKey = new Request(url.toString(), context.request);
  const cache = caches.default;

  // Try to find in cache
  const cachedResponse = await cache.match(cacheKey);
  if (cachedResponse) {
    return cachedResponse;
  }

  try {
    const githubUrl = `https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=100`;
    const headers: Record<string, string> = {
      'User-Agent': 'PlayBridge-Downloader',
      'Accept': 'application/vnd.github+json'
    };
    if (context.env?.GITHUB_TOKEN) {
      headers['Authorization'] = `Bearer ${context.env.GITHUB_TOKEN}`;
    }
    const apiResponse = await fetch(githubUrl, { headers });

    if (!apiResponse.ok) {
      throw new Error(`GitHub API returned status ${apiResponse.status}`);
    }

    const releases = (await apiResponse.json()) as Array<{
      tag_name: string;
      published_at: string;
      assets?: Array<{ name: string; browser_download_url: string }>;
    }>;

    // Find all releases matching the tag prefix, in order (newest first)
    const matchingReleases = releases.filter((r) => r.tag_name && r.tag_name.startsWith(tagPrefix));
    if (matchingReleases.length === 0) {
      throw new Error(`No release found for prefix: ${tagPrefix}`);
    }

    // Check if the latest release is at least 24 hours old.
    // If not, and there is a previous release, take the previous one.
    let release = matchingReleases[0];
    const now = Date.now();
    const twentyFourHoursMs = 24 * 60 * 60 * 1000;
    const publishedTime = new Date(release.published_at).getTime();

    if (now - publishedTime < twentyFourHoursMs && matchingReleases.length > 1) {
      release = matchingReleases[1];
    }

    // Find the asset matching our pattern
    const regex = new RegExp(assetPattern);
    const asset = release.assets?.find((a) => regex.test(a.name));
    if (!asset || !asset.browser_download_url) {
      throw new Error(`No matching asset found for pattern: ${assetPattern}`);
    }

    // Create redirect response
    const redirectResponse = new Response(null, {
      status: 302,
      headers: {
        'Location': asset.browser_download_url,
        // Cache the redirect for 10 minutes (600 seconds)
        'Cache-Control': 'public, max-age=600'
      }
    });

    // Cache the response
    context.waitUntil(cache.put(cacheKey, redirectResponse.clone()));
    return redirectResponse;
  } catch (err) {
    console.error('Downloader error:', err);
    // Fallback redirect to releases page
    return Response.redirect(fallbackUrl, 302);
  }
};
