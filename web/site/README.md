# PlayBridge — web

Static marketing site for PlayBridge. Built with SvelteKit + `adapter-static`. Designed for Cloudflare Pages.

## Develop

```bash
cd web/site
pnpm install
pnpm dev
```

Open http://localhost:5173.

## Build

```bash
pnpm build      # outputs to web/site/build
pnpm preview    # serve the production build locally
```

Every route is prerendered to static HTML — view-source shows real content, which is what makes the site SEO-friendly.

## Deploy on Cloudflare Pages

Two equivalent options:

### A) Connect the repo to Pages (recommended)

1. Pages → Create a project → Connect to Git.
2. Repository: this repo.
3. **Build configuration:**
   - Framework preset: **SvelteKit (static)** (or "None")
   - Build command: `cd web/site && pnpm install && pnpm build`
   - Build output directory: `web/site/build`
   - Root directory: leave blank
4. (Optional) set custom domain to `playbridge.app`.

### B) Deploy via Wrangler from CI

```bash
cd web/site && pnpm install && pnpm build
npx wrangler pages deploy build --project-name playbridge
```

## SEO

- `+layout.ts` exports `prerender = true`, so every route is static HTML.
- `Seo.svelte` injects `<title>`, OG, Twitter, canonical, JSON-LD per route.
- Landing page emits a `SoftwareApplication` JSON-LD block.
- `/sitemap.xml` and `/robots.txt` are generated at build time.
- `static/_headers` sets immutable caching for `/_app/immutable/*` and basic security headers.
- `static/_redirects` provides short links like `/android` → the managed download endpoint.

## Update and download endpoints

Cloudflare Pages Functions resolve stable direct-download releases from GitHub
while keeping the client-facing contract on `playbridge.app`:

- `GET /api/v1/updates/cli?os=<os>&arch=<arch>` returns a versioned JSON manifest
  with the target asset URL and SHA-256 digest.
- `/download/cli-<os>-<arch>` redirects installers to the same resolved asset and
  includes version and checksum response headers. Android, Android TV, Desktop,
  and Firefox download paths use the same resolver.

Only published, non-prerelease releases whose notes' rollout-delay marker has
elapsed are eligible. The marker defaults to 24 hours and accepts `0` through
`168`; see `docs/release.md`. CLI resolution uses GitHub's asset digest when
available and otherwise verifies the release's `SHA256SUMS` entry. Set
`GITHUB_TOKEN` in the Pages environment to increase GitHub API rate limits;
clients never receive the token.

## Color tokens

Marketing art direction lives in [`DESIGN.md`](DESIGN.md). Tokens in `src/app.css`
(Bond blue `#4a90e2`, navy `#06091e`) follow that file, not the native-app indigo
system in the repo-root `DESIGN.md`.
Change `:root` in `app.css` and the tables in `DESIGN.md` together.

## Editing copy

Headlines, features, install steps, FAQ, and stats live in `src/lib/data/site.ts`.
Visual mocks live in `src/lib/components/visuals/`.
