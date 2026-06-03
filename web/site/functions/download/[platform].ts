const GITHUB_REPO = 'playbridgeapp/playbridge';

export const onRequestGet: PagesFunction<unknown, 'platform'> = async (context) => {
  const platform = context.params.platform;
  const url = new URL(context.request.url);

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
    tagPrefix = 'tv-browser-v';
    const finalArch = arch || 'universal';
    assetPattern = `^playbridge-tv-browser-.*-${finalArch}-release\\.apk$`;
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
    const githubUrl = `https://api.github.com/repos/${GITHUB_REPO}/releases`;
    const apiResponse = await fetch(githubUrl, {
      headers: {
        'User-Agent': 'PlayBridge-Downloader',
        'Accept': 'application/vnd.github+json'
      }
    });

    if (!apiResponse.ok) {
      throw new Error(`GitHub API returned status ${apiResponse.status}`);
    }

    const releases = (await apiResponse.json()) as Array<{
      tag_name: string;
      assets?: Array<{ name: string; browser_download_url: string }>;
    }>;

    // Find the latest release matching the tag prefix
    const release = releases.find((r) => r.tag_name && r.tag_name.startsWith(tagPrefix));
    if (!release) {
      throw new Error(`No release found for prefix: ${tagPrefix}`);
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
