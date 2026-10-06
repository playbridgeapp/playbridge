# Stream Proxy Rust (`stream-proxy-rust`)

High-performance, lightweight Rust media stream proxy engine for PlayBridge. Built with Tokio, Axum, and FFmpeg AVIO.

## Features

- ⚡ **Ultra-Fast & Lightweight**: ~6.1 MB stripped release binary size, sub-5ms cold startup.
- 🛡️ **Pluggable origin fetch** (Cargo features):
  - `upstream-reqwest` (default): `reqwest` HTTP client
  - `upstream-avio` (default): FFmpeg `libavformat` AVIO fallback after reqwest failure
  - `upstream-jni`: host C callbacks for Android/Apple. Policy-bound fetches use
    `pb_proxy_upstream_set_checked_callbacks` so the native stack keeps hostname
    TLS while Rust dials only pre-checked addresses. Legacy ABI v1 callbacks are
    not sufficient for policy-bound media.
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
| `ADDRESS` | `0.0.0.0` | Standalone/Docker bind address. Embedded `ProxyServer` defaults to loopback and exposes a selected LAN interface only when casting. |
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
registration response fields are unchanged. Encrypted and stateful URLs stay
stable for the active native playback owner, including pause and seek. Registration
alone keeps the initial ten-minute idle cutoff and two-hour maximum; first owner
retain enables renewal with a six-hour abandonment grace. HTTP reads cannot renew
the owner lease. Explicit stop, successful replacement, detach, and host teardown
release ownership. Android and Desktop playback owners allow five minutes for
receiver idle/error/end or empty-playlist reports to recover before releasing;
playing, buffering, and paused reports cancel that grace. Repeated idle reports
cannot extend it. Lost connections without retry get the same grace; definitive
authentication/pinning/pairing failure, receiver switching, and exhausted Desktop
retries release immediately. This is an abandonment backstop, not an absolute cap
on active/live playback or pause duration. Transient Android renewal failures
retry with capped backoff; expired/revoked grants are never revived.

Native/admin local-media registration trusts one origin, with scope by kind:
a LAN IP-literal host the user chose (RFC1918, CGNAT `100.64/10`, ULA) is
trusted host-level, so same-host port redirects work; a DNS-approved name is
trusted for the exact origin (scheme, host, port) only, with every connection
pinned to the IPs checked at registration (a later answer pointing elsewhere is
refused); loopback and `localhost` are the exact origin only. Registration
classifies literal private/loopback addresses and local name suffixes from the
URL text. A native registration the local user started on this device may also
resolve the host once (1.5 s budget, otherwise no LAN trust) and, if every
answer is private LAN or CGNAT, or every answer is loopback, trust that origin;
mixed answers get no trust. Remote-sender payloads (`remote_origin`), HTTP
`/register` and EPG never get DNS-derived trust. Explicit page
private-origin grants remain origin-specific. Policy-bound requests never use
FFmpeg AVIO fallback, because FFmpeg would resolve and redirect outside the
checked path. Headers containing cookies,
authorization, or custom secrets stay on the original media origin; cross-CDN
requests retain only safe browser context. Network policy also partitions
segment-cache entries.

Reqwest validates redirect hops and filters the actual DNS answers used for
connections; policy-bound requests do not use environment HTTP proxies or AVIO
fallback. Embedded Android/Apple fetches open an authenticated loopback gateway:
Rust resolves and connects only to checked addresses, then the native stack uses
HTTP proxy or HTTPS CONNECT while keeping ordinary hostname/SNI/certificate
checks. Legacy callbacks without that gateway fail closed for policy-bound
origins. Receiver playback, LAN/VPN paths, and physical Cast/DLNA still need
manual validation beyond unit tests. An active compatible HTTP media URL remains
a bearer capability for that authorized resource until the playback owner
releases it.

### 3. Health Check (`GET /health`)
Returns `200 OK` with the body `OK`.
