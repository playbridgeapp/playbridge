# Shared detection core

Pure TypeScript used by:

| Host | Entry |
|---|---|
| Desktop store extension (Chrome + Firefox) | `src/background.ts`, `src/content.ts`, popup |
| Phone GeckoView built-in detector | `src/geckoview/background.ts`, `src/geckoview/content.ts` |

## Modules

- `media-candidate.ts` — URL roles, exclusive bootstrap masters, ranking, cast resolution
- `synthetic-hls.ts` — synthetic multivariant from master body or session observations
- `hls-parser.ts` — playlist body parse + role classification

## Rules

- **No** browser host APIs that differ by product (native messaging, Desktop socket, popup UI).
- **No** Android / Flutter imports.
- Prefer pure functions + small types so tests stay host-agnostic (`extension/test/*`).

Host adapters wire `webRequest`, native messaging, and cast transport outside this folder.
