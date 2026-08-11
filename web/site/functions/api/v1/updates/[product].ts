import {
  productAssetName,
  ReleaseResolverError,
  resolveProductRelease
} from '../../../_lib/releases.js';

interface Env {
  GITHUB_TOKEN?: string;
}

export const onRequestGet: PagesFunction<Env, 'product'> = async (context) => {
  const product = context.params.product;
  const requestUrl = new URL(context.request.url);
  const os = requestUrl.searchParams.get('os') ?? '';
  const arch = requestUrl.searchParams.get('arch') ?? '';

  if (!product || !os || !arch) {
    return jsonError('product, os, and arch are required', 400);
  }
  if (!productAssetName(product, os, arch)) {
    return jsonError('unsupported product or target', 404);
  }

  const cache = caches.default;
  const cacheKey = new Request(requestUrl.toString(), context.request);
  const cached = await cache.match(cacheKey);
  if (cached) return cached;

  try {
    const manifest = await resolveProductRelease({
      product,
      os,
      arch,
      githubToken: context.env?.GITHUB_TOKEN
    });
    const response = new Response(JSON.stringify(manifest), {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'public, max-age=600',
        'Access-Control-Allow-Origin': '*',
        'X-Content-Type-Options': 'nosniff'
      }
    });
    context.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  } catch (error) {
    if (!(error instanceof ReleaseResolverError)) {
      console.error('Update manifest resolver failed unexpectedly');
    }
    return jsonError('update metadata is temporarily unavailable', 502);
  }
};

function jsonError(message: string, status: number): Response {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': '*',
      'X-Content-Type-Options': 'nosniff'
    }
  });
}
