# PlayBridge Jellyfin Web Client

A modern, responsive Jellyfin Web client clone built with **Svelte 5** and **Vite**, featuring direct hardware casting through the **PlayBridge Web Page Bridge** (`window.playbridge.cast()` & `window.playbridge.linkCast()`).

---

## Key Features

1. **PlayBridge Direct Casting Integration**
   - Direct video stream routing to connected PlayBridge receivers, TVs, and desktop bridges using `window.playbridge.cast()`.
   - Rich `VisualMetadata` payload format including title, year, poster, backdrop, genres, synopsis, season, and episode number.
   - **Linked Cast Queues (`window.playbridge.linkCast()`)** for TV Series binge-watching with on-demand item supply via `needitems` events.
   - Real-time **PlayBridge Cast Remote HUD** when casting is active (allows pausing, resuming, and switching back to browser playback).
   - In-app **Diagnostics & Event Inspector** drawer showing bridge status, JSON payloads, and `PlayBridgeFeedback` event stream.

2. **Dual Mode: Live Jellyfin Server + Built-in Demo Library**
   - **Live Jellyfin Server**: Connect to any Jellyfin server (`http://...` or `https://...`) with authentication, library fetching, resume playback sync, and Direct Play / HLS stream generation.
   - **Built-in Demo Library**: Out-of-the-box rich showcase (4K Movies, TV Series with multi-episode queues, Mux adaptive HLS streams) requiring no external server setup.

3. **Jellyfin Dark UI & Experience**
   - Spotlight Hero backdrop banner with quick play and cast actions.
   - Horizontal carousels for *Continue Watching*, *Latest Movies*, *Next Up TV Series*, and *4K UHD Collection*.
   - Filterable views for Movies, TV Shows, Search, and Bookmarked Favorites.
   - TV Series season selector with episode cards, runtimes, and thumbnails.
   - Integrated HTML5 video player with custom controls for non-cast playback.

---

## Development

```bash
cd web/jellyfin
pnpm install
pnpm dev
```

Open [http://localhost:5180](http://localhost:5180).

### Build & Typecheck

```bash
pnpm check
pnpm build
pnpm preview
```
