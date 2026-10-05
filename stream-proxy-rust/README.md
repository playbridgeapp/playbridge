# Stream Proxy Rust (`stream-proxy-rust`)

High-performance, lightweight Rust media stream proxy engine for PlayBridge. Built with Tokio, Axum, and FFmpeg AVIO.

## Features

- ⚡ **Ultra-Fast & Lightweight**: ~6.1 MB stripped release binary size, sub-5ms cold startup.
- 🛡️ **Pluggable origin fetch** (Cargo features):
  - `upstream-reqwest` (default): `reqwest` HTTP client
  - `upstream-avio` (default): FFmpeg `libavformat` AVIO fallback after reqwest failure
  - `upstream-jni`: host C callbacks (`pb_proxy_upstream_set_callbacks`) for Android
    `HttpURLConnection` (≈ Media3) — open/read/close/free_string; streaming body via
    `spawn_blocking` (no full-segment buffering)
- 🔌 **Embed features (cast/ffi)**:
  - `sender-services` → reqwest + AVIO (Desktop)
  - `sender-services-android` → upstream-jni only (phone `build-android.sh`)
    - Phone installs callbacks via `SenderServicesNative.installUpstreamHttpClient()`
      → Kotlin `JniUpstreamHttpClient` (`HttpURLConnection`, Media3-like TLS)
- 🔐 **Authenticated media capabilities**:
  - Stateful session registration via `POST /register`.
  - Versioned AES-256-CBC encrypt-then-HMAC-SHA256 tokens with HKDF-derived keys.
  - Destination-bound session resources and bounded DASH template expansion.
- 🎬 **HLS & DASH Rewriting**: Dynamic manifest rewriter for `.m3u8` and `.mpd` playlists.
- 🌐 **CDN Signature Compatible**: Preserves query parameter key ordering for Akamai and signed media CDN validation.
- 📦 **Embeddable Service**: `ProxyServer` starts on an ephemeral port, registers
  remote URLs or local files, and shuts down cleanly with its owning app.
- 📁 **Scoped Local Files**: Unguessable, expiring grants with HEAD and byte-range
  support for seeking.
- 🧪 **Embedded Proxy Demo**: Serves a link builder and local test player at `GET /` and `GET /demo.html` for exercising stateful and encrypted proxy URLs. It is a diagnostic interface, not a casting receiver.

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8888` | Port for the proxy server to listen on. |
| `ADDRESS` | `0.0.0.0` | Bind IP address (`0.0.0.0` for all interfaces). |
| `PB_PROXY_PASSWORD` | *(None)* | Required for `/register` and `/epg`. |
| `FFMPEG_PATH` | *(Auto-detected)* | Optional FFmpeg path for AVIO library discovery. |

## Quick Start

### Standalone CLI
```bash
PB_PROXY_PASSWORD=your-unique-password cargo run --release -p stream-proxy-rust
```

### Docker Compose

Set a unique `PB_PROXY_PASSWORD` in the service's `environment` section in
`docker-compose.yml` before starting it. The checked-in example sets a literal
password; setting only a host-shell variable does not override that value.

```bash
docker compose up -d
```

## API Endpoints

### 1. Stateful Session Registration (`POST /register`)
```http
POST /register?token=YOUR_PROXY_PASSWORD
Content-Type: application/json

{
  "url": "https://example.com/live/master.m3u8",
  "headers": {
    "User-Agent": "Mozilla/5.0",
    "Referer": "https://example.com/"
  }
}
```

**Response**:
```json
{
  "proxy_url": "http://192.168.1.50:8888/s/SAA4j85zgLaBL7x1L91L6A/manifest.m3u8",
  "encrypted_url": "http://192.168.1.50:8888/proxy/hls/manifest.m3u8?token=..."
}
```

The returned scoped `/s/` URL carries a cryptographically random session ID, so
the receiving player does not need the registration password. Registration
itself remains authenticated.

### 2. Authenticated Stateless Capabilities (`GET /proxy/hls/...`)
The opaque `pb2` token encrypts the destination, headers, original credential
origin, and destination policy. Its authentication tag is verified before
any decryption. Token errors share a single rejection response.
```http
GET /proxy/hls/manifest.m3u8?token=<AUTHENTICATED_PAYLOAD>
```

Treat returned URLs as opaque. A root URL authorizes its registered resource,
not a caller-provided `uri`. Manifest rewriting issues individual child
capabilities. Stateful child URLs carry a session-bound `pr2` capability;
DASH template links permit only bounded representation/segment substitutions,
not arbitrary directory traversal or destinations.

**Migration:** unsigned legacy MediaFlow-format CBC tokens are rejected, with
no transparent fallback. Re-register media to obtain new URLs; external
integrations generating tokens themselves must migrate. Endpoint shapes and
registration response fields are unchanged. No new short playback expiry is
introduced.

Native/admin local-media registration authorizes the selected local host,
including same-host port redirects, not every private-network host. Explicit
page private-origin grants remain origin-specific. Headers containing cookies,
authorization, or custom secrets stay on the original media origin; cross-CDN
requests retain only safe browser context. Network policy also partitions
segment-cache entries.

Reqwest validates redirect hops and filters the actual DNS answers used for
connections; policy-bound requests do not use environment HTTP proxies or AVIO
fallback. Android/Apple host callbacks return redirects to Rust for validation
and correct relative-manifest resolution. **Host callback ABI v1 still performs
its own DNS resolution after Rust's preflight check: connection-address binding
against DNS rebinding remains incomplete on these embedded transports.** Do not
interpret the capability fix as complete native DNS-rebinding protection.
Receiver playback, long/live sessions, seeking, and native DNS/TLS integration
also need platform/receiver validation beyond unit tests.

### 3. Health Check (`GET /health`)
Returns `200 OK` with the body `OK`.
