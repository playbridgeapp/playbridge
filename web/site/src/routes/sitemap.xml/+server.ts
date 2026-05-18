import { SITE } from '$lib/data/site';

export const prerender = true;

const ROUTES = ['/', '/privacy', '/security'];

export const GET = async () => {
  const urls = ROUTES.map(
    (path) => `  <url>
    <loc>${SITE.url}${path === '/' ? '' : path}</loc>
    <changefreq>monthly</changefreq>
    <priority>${path === '/' ? '1.0' : '0.6'}</priority>
  </url>`
  ).join('\n');

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls}
</urlset>`;

  return new Response(xml, {
    headers: { 'Content-Type': 'application/xml; charset=utf-8' }
  });
};
