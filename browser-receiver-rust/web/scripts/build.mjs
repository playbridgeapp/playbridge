import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, rm, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = path.resolve(webRoot, '../..');
const browserBundle = path.join(webRoot, 'dist/receiver.js');
const castDirectory = path.join(repositoryRoot, 'web/site/static/cast');
const generatedAssetPattern = /^receiver\.[a-f0-9]{12}\.(js|css)$/;

function fingerprint(contents) {
  return createHash('sha256').update(contents).digest('hex').slice(0, 12);
}

async function bundle(entryPoint) {
  const result = await build({
    entryPoints: [entryPoint],
    bundle: true,
    minify: true,
    target: ['es2015'],
    format: 'iife',
    legalComments: 'none',
    write: false
  });
  return result.outputFiles[0].contents;
}

async function generate(browserOutput, castOutput) {
  await mkdir(path.dirname(browserOutput), { recursive: true });
  await mkdir(castOutput, { recursive: true });

  const browser = await bundle(path.join(webRoot, 'src/receiver.js'));
  await writeFile(browserOutput, browser);

  const cast = await bundle(path.join(webRoot, 'src/cast-receiver.js'));
  const sourceHtml = await readFile(path.join(webRoot, 'index.html'), 'utf8');
  const style = sourceHtml.match(/<style>([\s\S]*?)<\/style>/);
  if (!style) throw new Error('Receiver shell is missing its shared <style> block');
  const css = Buffer.from(style[1].trim() + '\n');
  const jsName = `receiver.${fingerprint(cast)}.js`;
  const cssName = `receiver.${fingerprint(css)}.css`;
  await writeFile(path.join(castOutput, jsName), cast);
  await writeFile(path.join(castOutput, cssName), css);

  const castHtml = sourceHtml
    .replace('<title>PlayBridge Browser Receiver</title>', '<title>PlayBridge Cast Receiver</title>')
    .replace(style[0], `<link rel="stylesheet" href="./${cssName}">`)
    .replace('<img id="brand-logo" alt="PlayBridge">', '<img id="brand-logo" src="./playbridge-cast-logo.svg" alt="PlayBridge">')
    .replace(
      '<script src="/assets/receiver.js"></script>',
      '<script src="https://www.gstatic.com/cast/sdk/libs/caf_receiver/v3/cast_receiver_framework.js"></script>\n' +
        `  <script src="./${jsName}"></script>`
    );
  await writeFile(path.join(castOutput, 'index.html'), castHtml);
}

async function generatedCastFiles(directory) {
  const files = await readdir(directory);
  return files.filter((name) => name === 'index.html' || generatedAssetPattern.test(name)).sort();
}

async function assertSame(expected, actual, label) {
  const [expectedBytes, actualBytes] = await Promise.all([readFile(expected), readFile(actual)]);
  if (!expectedBytes.equals(actualBytes)) throw new Error(`${label} is stale; run pnpm build`);
}

async function checkGenerated() {
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), 'playbridge-receiver-'));
  try {
    const expectedBrowser = path.join(temporaryRoot, 'receiver.js');
    const expectedCast = path.join(temporaryRoot, 'cast');
    await generate(expectedBrowser, expectedCast);
    await assertSame(expectedBrowser, browserBundle, 'browser-receiver-rust/web/dist/receiver.js');
    const [expectedFiles, actualFiles] = await Promise.all([
      generatedCastFiles(expectedCast),
      generatedCastFiles(castDirectory)
    ]);
    if (expectedFiles.join('\n') !== actualFiles.join('\n')) {
      throw new Error('web/site/static/cast generated asset names are stale; run pnpm build');
    }
    for (const file of expectedFiles) {
      await assertSame(path.join(expectedCast, file), path.join(castDirectory, file), `cast/${file}`);
    }
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
}

async function buildGenerated() {
  await mkdir(castDirectory, { recursive: true });
  for (const file of await readdir(castDirectory)) {
    if (generatedAssetPattern.test(file)) await unlink(path.join(castDirectory, file));
  }
  await generate(browserBundle, castDirectory);
}

if (process.argv.includes('--check')) await checkGenerated();
else await buildGenerated();
