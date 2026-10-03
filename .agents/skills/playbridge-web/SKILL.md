---
name: playbridge-web
description: Work on the PlayBridge public Svelte website, Cloudflare Pages download/update functions, and static assets under web/site/. Use for documentation pages, release/download UI, the cast demo, or website deployment; companion Streams and Jellyfin apps belong to the separate bridged-apps repo.
---

# PlayBridge Web

## Establish ownership

- Work from `web/site/`; treat it as an independent Svelte/Vite project.
- Pages are prerendered with the static adapter. `functions/` contains Cloudflare Pages handlers for downloads and update manifests; keep their server-only credentials out of client bundles. The static page build does not exercise those handlers.
- Treat `static/cast/` as generated output for the CAF Custom Web Receiver owned by `browser-receiver-rust/web`; its SVG logo and splash assets remain web-owned inputs.
- Keep website-only work separate from the extension even though both use TypeScript and browser APIs.
- Load `playbridge-protocol` when the cast demo or another web surface emits PlayBridge wire messages.

## Work safely

1. Follow the root `AGENTS.md` and preserve the static-site deployment model.
2. Keep release/download data compatible with the repository's publication conventions.
3. Preserve static pages and the existing Cloudflare Pages Functions boundary; do not introduce a SvelteKit runtime server dependency.
4. Do not place credentials, authenticated URLs, or private operational data in client assets.
5. Keep the Cast receiver URL public, stable, HTTPS-compatible, and consistent with `docs/google-cast-receiver.md`. Use that document's PlayBridge application ID; do not invent or copy another product's ID.

## Verify

From `web/site/`:

```bash
pnpm check
pnpm build
```

Run `pnpm test` for download/update resolution or CLI installer publishing changes.
Use `docs/release.md` for published-release filtering and rollout conventions.

Exercise the affected page locally when behavior cannot be established by type checking and a production build alone.
