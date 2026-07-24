---
name: playbridge-stream-proxy-rust
description: Work on the PlayBridge Rust stream proxy server. Use for stream proxying, HLS rewriting, AES-256 MediaFlow encryption/decryption, debrid link resolution, Tokio/Axum web server logic, Docker packaging, or changes under stream-proxy-rust/.
---

# PlayBridge Rust Stream Proxy

## Establish ownership

- Treat `stream-proxy-rust/` as a high-performance standalone Rust proxy service.
- Keep proxy routing, HTTP streaming, HLS rewriting, debrid link resolution, and MediaFlow AES-256 encryption within `stream-proxy-rust/`.
- Coordinate changes that affect Desktop proxy integration with the `playbridge-desktop-proxy` owner.
- Load `playbridge-protocol` for any cross-platform wire payload or message contract changes.

## Work safely

1. Follow the root `AGENTS.md` rules and preserve unrelated working-tree edits.
2. Never log Debrid tokens, authentication credentials, private keys, or full authenticated stream URLs.
3. Preserve HTTP header forwarding and HLS manifest rewriting without leaking user session tokens in logs or errors.
4. Ensure non-blocking async IO using Tokio and Axum for high-concurrency stream forwarding.

## Verify

From the repository root or `stream-proxy-rust/`:

```bash
cargo test -p stream-proxy-rust
cargo check -p stream-proxy-rust
```

Build the Docker image when container configuration or dependencies are updated:

```bash
docker build -t playbridge-stream-proxy-rust -f stream-proxy-rust/Dockerfile .
```
