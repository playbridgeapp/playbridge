---
name: playbridge-extension
description: Work on the PlayBridge browser extension. Use for TypeScript, WebExtension APIs, background/content/popup code, media detection, video overlays, native messaging, Desktop bridge reconnection, store packaging, manifests, or changes under extension/.
---

# PlayBridge Extension

## Establish ownership

- Work from `extension/`; source lives primarily under `extension/src/` and is built with TypeScript and esbuild.
- Modify source and manifests rather than generated `dist/` output.
- Load `playbridge-desktop-proxy` when native messaging, Desktop discovery, reconnection, or proxy routing changes.
- Load `playbridge-protocol` when message envelopes, payloads, pairing, or authentication change.

## Work safely

1. Follow the root `AGENTS.md` and preserve Chrome/Firefox target compatibility.
2. Keep content-script, page, popup, background, and native-host trust boundaries explicit.
3. Validate origins, frame identity, lifecycle state, and externally supplied media metadata before acting on them.
4. Never log pairing credentials, authenticated headers, cookies, or complete protected stream URLs.

## Verify

From `extension/`:

```bash
pnpm typecheck
pnpm test
pnpm build
```

For store or manifest work, also run the relevant store validation or the complete check:

```bash
pnpm store:check
```
