import { SITE } from '$lib/data/site';

export const prerender = true;

export const GET = async () => {
  const body = `User-agent: *
Allow: /

Sitemap: ${SITE.url}/sitemap.xml
`;
  return new Response(body, {
    headers: { 'Content-Type': 'text/plain; charset=utf-8' }
  });
};
