import { downloadTargetForPlatform, resolveProductRelease } from '../_lib/releases.js';

const GITHUB_REPO = 'playbridgeapp/playbridge';

interface Env {
  GITHUB_TOKEN?: string;
}

export const onRequestGet: PagesFunction<Env, 'platform'> = async (context) => {
  const platform = context.params.platform;
  if (platform === 'appletv') {
    return Response.redirect(`https://github.com/${GITHUB_REPO}/tree/main/tv/apple`, 302);
  }

  const target = downloadTargetForPlatform(platform);
  if (!target) return new Response('Platform Not Found', { status: 404 });

  const url = new URL(context.request.url);
  const cacheKey = new Request(url.toString(), context.request);
  const cache = caches.default;
  const cachedResponse = await cache.match(cacheKey);
  if (cachedResponse) return cachedResponse;

  try {
    const manifest = await resolveProductRelease({
      ...target,
      githubToken: context.env?.GITHUB_TOKEN
    });
    const headers: Record<string, string> = {
      Location: manifest.asset.url,
      'X-PlayBridge-Version': manifest.version,
      'Cache-Control': 'public, max-age=600'
    };
    if (manifest.asset.sha256) headers['X-PlayBridge-SHA256'] = manifest.asset.sha256;
    const response = new Response(null, { status: 302, headers });
    context.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  } catch (error) {
    console.error(`Download resolver failed for ${platform}`, error);
    if (target.product === 'cli') {
      return new Response('CLI update metadata is temporarily unavailable', {
        status: 502,
        headers: { 'Cache-Control': 'no-store' }
      });
    }
    return Response.redirect(`https://github.com/${GITHUB_REPO}/releases`, 302);
  }
};
