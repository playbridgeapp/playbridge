import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

const pages = 'build';
/**
 * Cloudflare Pages serves a top-level 404.html with a 404 status for unknown
 * paths. Without it, Pages assumes an SPA and answers every bad URL with the
 * homepage and a 200 (soft 404). The fallback shell boots the client router,
 * which renders src/routes/+error.svelte for the unmatched URL.
 */
const fallback = '404.html';

const staticAdapter = adapter({
  pages,
  assets: pages,
  fallback,
  precompress: false,
  strict: true
});

/** adapter-static, plus a static `noindex` in the 404 shell (it has no SSR head). */
const adapterWith404 = {
  ...staticAdapter,
  async adapt(builder) {
    await staticAdapter.adapt(builder);
    const file = join(dirname(fileURLToPath(import.meta.url)), pages, fallback);
    const html = readFileSync(file, 'utf8');
    if (!html.includes('name="robots"')) {
      writeFileSync(
        file,
        html.replace(
          '<meta charset="utf-8" />',
          '<meta charset="utf-8" />\n    <meta name="robots" content="noindex" />'
        )
      );
    }
  }
};

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapterWith404,
    prerender: {
      handleHttpError: 'warn',
      handleMissingId: 'warn'
    }
  }
};

export default config;
