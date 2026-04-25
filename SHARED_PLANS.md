# PlayBridge — Shared KMP Plans

This document tracks the roadmap for shared logic across the PlayBridge ecosystem (Phone, Android TV, Apple TV, Browser Extension).

## Current Active Plans

### 1. Unified Dumb Receiver Migration
**Status**: Planning / Starting Phase 1
**Document**: [UNIFIED_DUMB_RECEIVER_MIGRATION.md](docs/plans/UNIFIED_DUMB_RECEIVER_MIGRATION.md)
**Goal**: Move all Stremio addon resolution and complex metadata parsing from TV receivers to the Phone/Sender.

## Roadmap

### Phase 1: Integrated Smart Hub (NEW BRAIN)
- [x] Initial Hub Scaffold (`hub/`)
- [ ] Implement Go Backend Resolver (`hub/server`)
- [ ] Implement SvelteKit Mobile-First UI (`hub/ui`)
- [ ] JS Bridge integration for Phone Shell

### Phase 2: Dumb Receiver Migration
- [ ] Strip Stremio resolution from Android TV.
- [ ] Build clean AVPlayer receiver for Apple TV.
- [ ] Implement VLC Proxy in Swift (Apple TV).

### Phase 3: Cross-Platform Parity
- [ ] Shared resume/history sync (Go + SQLite).
- [ ] Multi-device state tracking.
- [ ] Unified Watchlist across all shells.
