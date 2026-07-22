# PlayBridge Stream Proxy (Rust) — `stream-proxy-rust`

High-performance streaming reverse proxy written in Rust with dynamic FFmpeg `libavformat` FFI fallback.

## Overview

`stream-proxy-rust` is the Rust implementation of PlayBridge's stream proxy engine. It provides:
1. **Dual Upstream Transport Engine**:
   - Primary: Standard HTTP/TLS client (`reqwest`).
   - Fallback: Dynamic C FFI binding to FFmpeg's `libavformat` (`avio_open2`, `avio_read`) to bypass CDN TLS fingerprinting blocks (e.g. `strmd.st`, `beeg.com`).
2. **HLS Playlist Rewriter**: Rewrites `.m3u8` variant playlists and segment links on-the-fly to route playback through the local authenticated proxy.
3. **DASH Manifest Rewriter**: Rewrites `.mpd` XML tags (`<BaseURL>`, `<Location>`, and media attributes) to forward through the proxy.
4. **EPG Caching**: In-memory caching for XMLTV EPG data with configurable TTL (default 4 hours).
5. **Security & Session Management**: Token authentication, session lifetime management, CORS middleware.

## Usage

### Run from workspace

```bash
cargo run -p stream-proxy-rust -- --port 8888 --password my_secret_token
```

### Options

```
Options:
  -p, --port <PORT>            Port to bind to [default: 8888] [env: PORT=]
  -a, --address <ADDRESS>      Address to bind to [default: 0.0.0.0] [env: ADDRESS=]
  -k, --password <PASSWORD>    API authorization password [env: PB_PROXY_PASSWORD=]
  -f, --ffmpeg-path <PATH>     FFmpeg library path override [env: FFMPEG_PATH=]
  -h, --help                   Print help
```
