# AMO reviewer build instructions

This source archive rebuilds the submitted PlayBridge Firefox add-on. It does
not require the rest of the PlayBridge repository.

## Environment

- Node.js 20 or newer (Node.js 22 LTS recommended)
- pnpm 9.15.2, as declared by `packageManager` in `package.json`
- macOS, Linux, or Windows

No credentials or private package registries are required. All dependencies are
release packages downloaded through the public npm registry and locked by
`pnpm-lock.yaml`.

## Reproduce the Firefox bundle

From the directory containing this file:

```bash
corepack enable
corepack prepare pnpm@9.15.2 --activate
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm build
pnpm store:validate
pnpm store:lint:firefox
```

The Firefox package contents are generated in `dist/firefox`. To compare them
with the submitted binary ZIP, extract that ZIP into an empty directory and
compare it recursively with `dist/firefox`. `manifest.json` must be at the root
of both directories.

The production build is minified by esbuild. Source maps are intentionally not
included in the submitted binary package.

## Create all submission archives

```bash
pnpm store:package
```

This writes deterministic Firefox, Chrome, and AMO source ZIPs plus
`artifacts/SHA256SUMS.txt`.

## Third-party packages bundled into the add-on

- [`webextension-polyfill`](https://github.com/mozilla/webextension-polyfill),
  Mozilla Public License 2.0

Build and validation dependencies such as esbuild, TypeScript, web-ext, and
archiver are not included in the binary add-on.
