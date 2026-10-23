#!/usr/bin/env node
/**
 * Generates the social preview image and the raster app icons from source:
 *
 *   static/og.png               1200×630 Open Graph / Twitter card
 *   static/apple-touch-icon.png 180×180, full-bleed tile, no transparency
 *   static/favicon-32.png       32×32
 *   static/icon-192.png         192×192 (web manifest)
 *   static/icon-512.png         512×512 (web manifest)
 *
 * Inputs: static/favicon.svg, docs/screenshots/tv-pairing.png and the
 * IBM Plex woff2 files from node_modules/@fontsource. Everything is inlined
 * as data: URIs, so the render does not depend on a dev server or network.
 *
 * Playwright is not a project dependency. Run with a global install:
 *   node scripts/generate-og.mjs
 * or point at a specific build:
 *   PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node scripts/generate-og.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const site = resolve(here, '..');
const repo = resolve(site, '../..');
const staticDir = resolve(site, 'static');

async function loadChromium() {
  const candidates = [
    process.env.PLAYWRIGHT_MODULE,
    'playwright',
    '/opt/node22/lib/node_modules/playwright/index.mjs'
  ].filter(Boolean);
  for (const spec of candidates) {
    try {
      const url = isAbsolute(spec) ? pathToFileURL(spec).href : spec;
      const mod = await import(url);
      return (mod.chromium ?? mod.default?.chromium);
    } catch {
      /* try next */
    }
  }
  throw new Error(
    'Playwright not found. Install it globally (npm i -g playwright) or set PLAYWRIGHT_MODULE.'
  );
}

const dataUri = (path, mime) =>
  `data:${mime};base64,${readFileSync(path).toString('base64')}`;
const font = (pkg, file) =>
  dataUri(resolve(site, 'node_modules/@fontsource', pkg, 'files', file), 'font/woff2');

const faviconSvg = readFileSync(resolve(staticDir, 'favicon.svg'), 'utf8');
const screenshot = dataUri(resolve(repo, 'docs/screenshots/tv-pairing.png'), 'image/png');

const fonts = `
  @font-face { font-family: 'IBM Plex Serif'; font-weight: 600; src: url(${font('ibm-plex-serif', 'ibm-plex-serif-latin-600-normal.woff2')}) format('woff2'); }
  @font-face { font-family: 'IBM Plex Sans'; font-weight: 400; src: url(${font('ibm-plex-sans', 'ibm-plex-sans-latin-400-normal.woff2')}) format('woff2'); }
  @font-face { font-family: 'IBM Plex Sans'; font-weight: 500; src: url(${font('ibm-plex-sans', 'ibm-plex-sans-latin-500-normal.woff2')}) format('woff2'); }
`;

/* Tokens mirror src/app.css (dark theme) and the fixed dark device kit. */
const ogHtml = `<!doctype html>
<html><head><meta charset="utf-8"><style>
${fonts}
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { width: 1200px; height: 630px; overflow: hidden; }
body {
  position: relative;
  background:
    radial-gradient(760px 460px at 92% 0%, oklch(32% 0.06 240 / 0.30), transparent 62%),
    radial-gradient(620px 420px at 0% 100%, oklch(30% 0.05 230 / 0.18), transparent 60%),
    linear-gradient(180deg, #06091e 0%, #080c22 100%);
  color: #dce8f8;
  font-family: 'IBM Plex Sans', system-ui, sans-serif;
  -webkit-font-smoothing: antialiased;
}
body::before {
  content: ""; position: absolute; inset: 0;
  background-image:
    linear-gradient(to right, rgba(200,220,255,0.035) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(200,220,255,0.035) 1px, transparent 1px);
  background-size: 56px 56px;
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 60% 40%, #000 20%, transparent 80%);
}
.copy { position: absolute; left: 72px; top: 64px; bottom: 60px; width: 470px; display: flex; flex-direction: column; }
.brand { display: flex; align-items: center; gap: 14px; font-size: 24px; font-weight: 500; letter-spacing: -0.01em; }
.brand svg { width: 44px; height: 44px; display: block; }
.chip {
  font-family: ui-monospace, 'SF Mono', Menlo, 'DejaVu Sans Mono', monospace;
  font-size: 11px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase;
  color: #06091e; background: #4a90e2; padding: 5px 9px; border-radius: 999px; line-height: 1;
}
h1 {
  margin-top: 64px;
  font-family: 'IBM Plex Serif', Georgia, serif; font-weight: 600;
  font-size: 74px; line-height: 1.02; letter-spacing: -0.035em;
}
h1 span { display: block; }
h1 .accent { color: #4a90e2; }
.sub { margin-top: 26px; font-size: 21px; line-height: 1.4; color: #94afd0; }
.url {
  margin-top: auto;
  font-family: ui-monospace, 'SF Mono', Menlo, 'DejaVu Sans Mono', monospace;
  font-size: 15px; letter-spacing: 0.08em; color: #738bad;
}
.tv { position: absolute; left: 584px; top: 150px; width: 560px; }
.tv__shell {
  background: #0a0c10; border: 1px solid #2a3140; border-radius: 14px; padding: 10px;
  box-shadow: 0 40px 80px -40px rgba(0,0,0,0.9);
}
.tv__shell img { display: block; width: 100%; border-radius: 6px; }
.tv__stand { width: 120px; height: 6px; margin: 12px auto 0; border-radius: 3px; background: #1a2030; }
.tv__caption {
  margin-top: 18px; text-align: center;
  font-family: ui-monospace, 'SF Mono', Menlo, 'DejaVu Sans Mono', monospace;
  font-size: 12px; letter-spacing: 0.16em; text-transform: uppercase; color: #738bad;
}
</style></head><body>
  <div class="copy">
    <div class="brand">${faviconSvg.replace(/width="256" height="256"/, '')}<span>PlayBridge</span><span class="chip">Alpha</span></div>
    <h1><span>Your phone.</span><span>Your TV.</span><span class="accent">One bridge.</span></h1>
    <p class="sub">Open source. Local network. No account.</p>
    <div class="url">playbridge.app</div>
  </div>
  <div class="tv">
    <div class="tv__shell"><img src="${screenshot}" alt=""></div>
    <div class="tv__stand"></div>
    <div class="tv__caption">Android TV receiver · pairing</div>
  </div>
</body></html>`;

/** Icon page: the favicon at `size`; `fullBleed` drops the rounded corners. */
const iconHtml = (size, fullBleed) => {
  const svg = faviconSvg
    .replace(/width="256" height="256"/, `width="${size}" height="${size}"`)
    .replace(fullBleed ? /rx="230"/ : /$^/, 'rx="0"');
  return `<!doctype html><html><head><style>
    html, body { margin: 0; width: ${size}px; height: ${size}px; background: transparent; }
    svg { display: block; }
  </style></head><body>${svg}</body></html>`;
};

const icons = [
  { file: 'apple-touch-icon.png', size: 180, fullBleed: true },
  { file: 'favicon-32.png', size: 32, fullBleed: false },
  { file: 'icon-192.png', size: 192, fullBleed: false },
  { file: 'icon-512.png', size: 512, fullBleed: false }
];

const chromium = await loadChromium();
const browser = await chromium.launch();
try {
  const og = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1 });
  await og.setContent(ogHtml, { waitUntil: 'load' });
  await og.evaluate(() => document.fonts.ready);
  const missing = await og.evaluate(() =>
    ['600 74px "IBM Plex Serif"', '400 21px "IBM Plex Sans"', '500 24px "IBM Plex Sans"'].filter(
      (f) => !document.fonts.check(f)
    )
  );
  if (missing.length) throw new Error(`Fonts failed to load: ${missing.join(', ')}`);
  writeFileSync(resolve(staticDir, 'og.png'), await og.screenshot({ type: 'png' }));
  console.log('wrote static/og.png');

  for (const { file, size, fullBleed } of icons) {
    const page = await browser.newPage({ viewport: { width: size, height: size }, deviceScaleFactor: 1 });
    await page.setContent(iconHtml(size, fullBleed), { waitUntil: 'load' });
    const png = await page.screenshot({ type: 'png', omitBackground: !fullBleed });
    writeFileSync(resolve(staticDir, file), png);
    console.log(`wrote static/${file}`);
    await page.close();
  }
} finally {
  await browser.close();
}
