# Changelog

All notable changes to the PlayBridge Stream Proxy (`playbridge_stream_proxy`) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-07-14

### Added
- **Dynamic Session Authentication**: Secures loopback endpoints using dynamically generated secure 128-bit session tokens (`?token=...` or `Authorization` header validation).
- **CORS Support**: Full implementation of CORS headers and preflight `OPTIONS` requests for player client compatibility.
- **HLS Manifest Rewriting**: Resolves and rewrites variant playlist URLs and DRM key references in `.m3u8` playlists to route traffic through the loopback proxy.
- **DASH Manifest Rewriting**: Rewrites XML DASH manifests (`.mpd`) to map root-relative segment paths (`_root_/`) and propagate active authentication tokens to prevent `403` errors.
- **FFmpeg AVIO Fallback client**: Seamless native FFI fallback utilizing FFmpeg's network protocol wrapper to bypass strict TLS fingerprint checks on blocking CDNs.
- **EPG Caching**: Electronic Program Guide (EPG) XML cache mapping (`/epg?uri=...`) with a 4-hour TTL.
- **Decompression Handling**: Transparently strips `Content-Length` headers from transparently decompressed (Gzip) CDN manifest payloads to avoid player socket hangs.
- **Docker Support**: Containerized deployment setup using `Dockerfile` and `docker-compose.yml`.
- **CI/CD Integration**: Paths-filtered GitHub Actions checks for format/analyze compliance, dry-runs, and container deployment to GHCR.
