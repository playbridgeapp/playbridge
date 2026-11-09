# Watch Together Workers

Cloudflare Workers behind PlayBridge Watch Together. See
[`docs/design-watch-together.md`](../docs/design-watch-together.md) for the design.

| Folder | Worker | Who runs it |
| --- | --- | --- |
| `pairing-worker/` | TV codes, QR codes and sealed invites (`Lobby` Durable Object) | PlayBridge, for everyone |
| `room-worker/` | Party clock, message routing and limits (`Room` Durable Object) | Users (recommended), or PlayBridge's shared, limited relay |
| `shared/` | Wire types, protocol version, per-IP `Limits` Durable Object | — |

Both Workers only ever see ciphertext. They run on Cloudflare's free plan with
SQLite-backed Durable Objects and WebSocket Hibernation.

## Develop

```sh
pnpm install
pnpm test        # runs both Workers' tests in the local Workers runtime
pnpm typecheck
pnpm --filter @playbridge-together/room-worker dev
```

## Deploy your own room relay

```sh
cd room-worker
pnpm wrangler login
pnpm wrangler deploy
```

Open the deployed address and follow `/setup` to add it in PlayBridge. Limits
come from `vars` in `room-worker/wrangler.jsonc`.

PlayBridge's shared relay deploys with `pnpm wrangler deploy --env shared`, which
applies the smaller shared-tier limits.

## Room protocol (v1)

Members connect to `wss://<relay>/ws/<room id>?member=<member id>` with
`Authorization: Bearer <room token>`. The first connection creates the room and
fixes its token; later ones must present the same token. IDs are 32 lowercase
hex characters.

| Direction | Frame |
| --- | --- |
| member → room | `{"t":"ping","id","t0"}`, `{"t":"msg","to"?,"seq","ct"}`, `{"t":"state","seq","ct"}` |
| room → member | `welcome`, `pong`, `msg` (stamped `from`), `state`, `joined`, `left` |

`ct` is base64url ciphertext the room cannot read. The room keeps the latest
`state` frame and hands it to late joiners in `welcome`.

## Pairing protocol (v1)

| Request | Purpose |
| --- | --- |
| `POST /lobby {"pk"}` | Receiver registers its X25519 key; gets `code`, `token`, `expires_at` |
| `GET /lobby/<code>/ws` (Bearer token) | Receiver waits for `invite` frames |
| `GET /lobby/<code>` | Phone looks up the receiver's key |
| `POST /lobby/<code>/invite {"sealed"}` | Phone sends an invite sealed to that key |
| `DELETE /lobby/<code>` (Bearer token) | Receiver releases the code |

Codes last 10 minutes. Lookups and invites are rate-limited per IP.
