# Stream Proxy Rust (`stream-proxy-rust`)

High-performance, lightweight Rust media stream proxy engine for PlayBridge. Built with Tokio, Axum, and FFmpeg AVIO.

## Features

- ⚡ **Ultra-Fast & Lightweight**: ~6.1 MB stripped release binary size, sub-5ms cold startup.
- 🛡️ **Pluggable origin fetch** (Cargo features):
  - `upstream-reqwest` (default): `reqwest` HTTP client
  - `upstream-avio` (default): FFmpeg `libavformat` AVIO fallback after reqwest failure
  - `upstream-jni`: host callbacks (Android `HttpURLConnection` ≈ Media3) — embed-only
- 🔐 **MediaFlow Security Standard**:
  - Stateful session registration via `POST /register`.
  - MediaFlow AES-256-CBC token encryption for stateless proxying (`/proxy/hls/...`).
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
| `API_PASSWORD` | *(None)* | Password for registering sessions and authenticating proxy requests. |

## Quick Start

### Standalone CLI
```bash
cargo run --release -p stream-proxy-rust
```

### Docker Compose
```bash
docker compose up -d
```

## API Endpoints

### 1. Stateful Session Registration (`POST /register`)
```http
POST /register?token=YOUR_API_PASSWORD
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

### 2. MediaFlow Stateless AES Encryption (`GET /proxy/hls/...`)
Encodes destination URL and headers inside an AES-256-CBC encrypted token parameter:
```http
GET /proxy/hls/manifest.m3u8?token=<AES_ENCRYPTED_PAYLOAD>
```

### 3. Health Check (`GET /health`)
Returns `200 OK` with the body `OK`.
