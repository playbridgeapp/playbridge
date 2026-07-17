# PlayBridge Stream Proxy (`pb-proxy`)

A lightweight, high-performance streaming proxy server built in Dart. It resolves CDN IP-blocking, referrer restrictions, and TLS/BoringSSL fingerprinting.

It features two connection modes:
1. **Standard `HttpClient` (Default)**: Leverages native connection management.
2. **FFmpeg `AvioIsolateStream` (Fallback)**: Spawns an isolated FFI client using system FFmpeg (`libavformat`/`libavutil`) to bypass strict TLS fingerprint blocks (e.g. CDNs like `strmd.st`).

---

## ❓ Why is this proxy needed?

When playing media streams (like HLS or MP4) from web browsers, casting devices, or external media players, they often face severe networking limitations:

1. **TLS / BoringSSL Fingerprint Blocking**: Modern CDNs (like `strmd.st`) inspect the SSL client handshake. Standard HTTP clients (like Python or Dart's native `HttpClient`) produce different cryptographic fingerprints than web browsers and get rejected with `403 Forbidden` errors. The proxy's FFmpeg AVIO fallback runs isolated native C calls to bypass these blocks.
2. **CORS Restrictions**: Browsers block media tags and `hls.js` from loading cross-origin streams. The proxy implements complete CORS middleware to allow universal playback.
3. **Forbidden Header Injection**: Browsers strictly forbid modifying headers like `User-Agent` or `Referer` from JavaScript. The proxy accepts these headers safely base64-encoded in the URL path and injects them server-side.
4. **External Player Support (VLC/MPV)**: External media players can't read PlayBridge's in-app configurations. Routing streams through the local loopback proxy makes them plug-and-play.
5. **HLS Sub-segment Header Persistence**: When an HLS playlist redirects, standard players often fail to pass original headers to sub-segments. The proxy rewrites the manifest segments, guaranteeing every single chunk carries the proper headers.

---

## 🐋 Running with Docker / Docker Compose

The easiest way to run the stream proxy on a home server (Raspberry Pi, NAS, VPS) to support your Android TV, web, or mobile casting streams is via Docker.

### 1. Prerequisites
- Docker and Docker Compose installed.
- Note: The Docker image installs the `libavformat` runtime and its dependencies, enabling the **AVIO FFI fallback out-of-the-box** without the FFmpeg CLI or development packages.

### 2. Startup
To start the proxy, simply run:
```bash
docker compose up -d
```

### 3. Configuration (`docker-compose.yml`)
You can configure the service using the environment variables in `docker-compose.yml`:

```yaml
services:
  pb-proxy:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: pb-proxy
    ports:
      - "8888:8888"
    environment:
      - PORT=8888
      - ADDRESS=0.0.0.0
      - PB_PROXY_PASSWORD=replace_with_a_unique_proxy_password # Required
    restart: unless-stopped
```

- **`PORT`**: The internal port the server binds to (default: `8888`).
- **`ADDRESS`**: The interface address to bind to. Use `0.0.0.0` for Docker.
- **`PB_PROXY_PASSWORD`**: Required. The proxy refuses to start unless this is a unique, non-empty password. Requests must include a `token` query parameter or `Authorization: Bearer <password>` header matching this value.

---

## 🛠️ Local Development & Running

To run or compile the proxy locally without Docker, make sure you have the [Dart SDK](https://dart.dev/get-dart) installed.

### Run Directly
```bash
dart run bin/stream_proxy.dart -p 8888 -k replace_with_a_unique_proxy_password
```

### Compile to Standalone Native Binary
```bash
mkdir -p build
dart compile exe bin/stream_proxy.dart -o build/pb-proxy
```

### Command Line Options
```text
-p, --port            Port to bind to (default: 8888)
-a, --address         Address to bind to (default: 0.0.0.0)
-k, --password        API authorization password
-f, --ffmpeg-path     Search path override for FFmpeg (libavformat) library
-h, --help            Show usage instructions
```

---

## 🔗 Stateless Proxy Path Architecture

The proxy provides a stateless route `/s/play/` to run playbacks without saving session states on the server side:

```text
http://<proxy_ip>:<proxy_port>/s/play/<original_url_b64>/<headers_json_b64>/<filename>
```

- **`<original_url_b64>`**: The target stream URL, encoded as **base64url** (padding-tolerant; works with or without trailing `=`).
- **`<headers_json_b64>`**: A JSON map of headers (e.g. `{"User-Agent": "...", "Referer": "..."}`), encoded as base64url. Use `empty` if no headers are required.
- **`<filename>`**: Trailing filename with the appropriate extension (e.g. `stream.m3u8` or `video.mp4`). This tells the proxy whether it is rewriting a playlist or forwarding binary media segments.

---

## 🌐 In-browser Link Builder & Player (`demo.html`)

A built-in interactive developer page is provided at `demo.html`. You can open this file in any web browser to:
1. Paste a raw browser `cURL` request.
2. Automatically generate the stateless base64url-encoded proxy link.
3. Test streaming playback of both **HLS (.m3u8)** and **non-HLS (.mp4, .mkv, etc.)** streams using the embedded player (with native/hls.js).
4. Copy the proxy URL or get an updated curl command.

> **Note**: The proxy server implements full CORS middleware support, allowing `demo.html` to play proxied streams directly when opened locally from a `file://` URI or different origin.
