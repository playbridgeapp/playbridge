# Stream Proxy Rust (`stream-proxy-rust`)

High-performance, lightweight Rust media stream proxy engine for PlayBridge. Built with Tokio, Axum, and FFmpeg AVIO.

## Features

- ⚡ **Ultra-Fast & Lightweight**: ~6.1 MB stripped release binary size, sub-5ms cold startup.
- 🛡️ **Dual Transport Fallback**: Primary `reqwest` HTTP transport with automatic FFmpeg `libavformat` AVIO fallback for 403 / 471 CDN blocks.
- 🔐 **MediaFlow Security Standard**:
  - Stateful session registration via `POST /register`.
  - MediaFlow AES-256-CBC token encryption for stateless proxying (`/proxy/hls/...`).
- 🎬 **HLS & DASH Rewriting**: Dynamic manifest rewriter for `.m3u8` and `.mpd` playlists.
- 🌐 **CDN Signature Compatible**: Preserves query parameter key ordering for Akamai and signed media CDN validation.
- 📺 **Embedded Web Receiver UI**: Serves interactive dark-mode player at `GET /` and `GET /demo.html`.

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
  "session_id": "SAA4j85zgLaBL7x1L91L6A",
  "proxy_url": "http://192.168.1.50:8888/s/SAA4j85zgLaBL7x1L91L6A/manifest.m3u8?token=YOUR_API_PASSWORD",
  "encrypted_url": "http://192.168.1.50:8888/proxy/hls/manifest.m3u8?token=..."
}
```

### 2. MediaFlow Stateless AES Encryption (`GET /proxy/hls/...`)
Encodes destination URL and headers inside an AES-256-CBC encrypted token parameter:
```http
GET /proxy/hls/manifest.m3u8?token=<AES_ENCRYPTED_PAYLOAD>
```

### 3. Health Check (`GET /health`)
Returns `200 OK` with JSON status: `{"status": "ok"}`.
