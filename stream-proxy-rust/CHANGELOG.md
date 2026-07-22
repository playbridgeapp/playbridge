# Changelog - Stream Proxy Rust (`stream-proxy-rust`)

All notable changes to the `stream-proxy-rust` project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-07-22

### Added
- **High-Performance Rust Proxy Engine**: Replaced legacy Dart stream proxy with Tokio/Axum high-concurrency Rust streaming core (~6 MB binary size).
- **Dual Transport Engine**: Built `reqwest` HTTP engine with automatic FFmpeg `libavformat` AVIO fallback for 403 / 471 CDN block bypass.
- **MediaFlow Security & Encryption Pattern**:
  - Stateful session registration via `POST /register`.
  - MediaFlow AES-256-CBC token encryption for stateless proxying (`GET /proxy/hls/...`).
  - Strict enforcement of authentication via `token` query parameters or Bearer headers.
  - Complete removal of insecure, raw unencrypted base64 `/s/play/` routes.
- **Manifest Rewriting**: Built HLS (`.m3u8`) and DASH (`.mpd`) manifest rewriters that automatically rewrite absolute/relative video segments, initialization maps, and key URIs.
- **Order-Preserving Query Parameters**: Preserves original parameter ordering to ensure compatibility with signature-sensitive CDNs (e.g. Akamai, Pornhub).
- **Embedded Interactive Web Player**: Serves stateful dark-mode player UI with PIN authentication directly at `GET /` and `GET /demo.html`.
- **Cross-Platform Containerization**: Dockerfile and `docker-compose.yml` configuration supporting `linux/amd64` and `linux/arm64` deployments.
