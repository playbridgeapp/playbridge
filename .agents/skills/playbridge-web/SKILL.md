---
name: playbridge-web
description: Work on the PlayBridge Svelte website and static web assets. Use for Svelte, TypeScript, Vite, public documentation pages, release/download UI, the cast demo, static deployment, or changes under web/site/.
---

# PlayBridge Web

## Establish ownership

- Work from `web/site/`; treat it as an independent Svelte/Vite project.
- Keep website-only work separate from the extension even though both use TypeScript and browser APIs.
- Load `playbridge-protocol` when the cast demo or another web surface emits PlayBridge wire messages.

## Work safely

1. Follow the root `AGENTS.md` and preserve the static-site deployment model.
2. Keep release/download data compatible with the repository's publication conventions.
3. Avoid introducing runtime server assumptions into pages built for static hosting.
4. Do not place credentials, authenticated URLs, or private operational data in client assets.

## Verify

From `web/site/`:

```bash
pnpm check
pnpm build
```

Exercise the affected page locally when behavior cannot be established by type checking and a production build alone.
