# Plan: Unified Dumb Receiver Migration

## Objective
Transition the PlayBridge ecosystem to a **"Phone-as-Brain, TV-as-Display"** architecture. This involves stripping all Stremio addon resolution and complex metadata parsing from the TV (Receiver) apps and centralizing it in the Phone (Sender) app using Kotlin Multiplatform (KMP).

## Rationale
*   **App Store Acceptance**: Minimizes the risk of Apple TV rejection by removing piracy-related "facilitation" logic (Stremio SDK/Addon resolution) from the tvOS binary.
*   **Performance**: Offloads heavy network I/O and JSON parsing from low-power TV hardware to high-performance smartphones.
*   **Stability**: Fixes and updates to addon logic are deployed only to the Phone app, making the TV receiver a "forever-stable" utility.
*   **Consistent UX**: Standardizes the "Pre-Play" experience across Android TV and Apple TV.

---

## Technical Architecture

### 1. Smart Addon Redirection (Total Dumb Receiver)
The core of the "Dumb Receiver" approach is moving from a **List of Streams** to a **Single Smart Redirect**.

Instead of the TV receiving multiple stream options and resolving them, it receives a single, preference-aware URL (e.g., `t134:3:4`). This URL identifies the content and implicitly carries the user's quality/provider preferences.

*   **Logic Hub (Smart Addon / Phone Server)**:
    1.  Receives a request for a specific content ID (e.g., `t134:3:4`).
    2.  Applies user preferences (e.g., "Always 4K," "Prefer RealDebrid").
    3.  Performs the background resolution (scraping/fetching).
    4.  Returns a **302 Found** redirect directly to the final streaming file (HLS, MP4, etc.).
*   **Receiver (TV)**:
    1.  Receives the "Unified Redirect URL" from the Phone sender.
    2.  Passes it to the native player (`AVPlayer` or `ExoPlayer`).
    3.  The player follows the redirect automatically and begins playback.
    4.  **Result**: The TV app's code is entirely agnostic to addons, scraping, or complex stream selection.

### 2. Payload Simplification
The `play` command in the protocol will be simplified. The TV app becomes agnostic to the content origin.

**Example Command Structure**:
```json
{
  "type": "play",
  "metadata": {
    "title": "Interstellar",
    "year": "2014",
    "posterUrl": "https://image.tmdb.org/...",
    "backdropUrl": "https://image.tmdb.org/..."
  },
  "stream": {
    "url": "http://192.168.1.15:8765/stream/xyz",
    "isLive": false,
    "headers": { "User-Agent": "..." }
  }
}
```

---

## Implementation Phases

### Phase 1: Shared KMP Centralization
*   Verify all addon resolution logic is fully functional in `shared/src/commonMain/kotlin/com.playbridge.shared.stremio`.
*   Ensure `ConnectionViewModel` (Phone) can handle the full resolution lifecycle previously handled by `PrePlayActivity` (TV).

### Phase 2: TV Receiver "De-coring"
*   **Android TV**: Deprecate resolution logic in `PrePlayActivity`. Convert it into a simple metadata display that waits for the Phone to provide a direct link.
*   **Apple TV**: Build the native receiver to expect a direct URL. Ensure no "future" Stremio SDK integration remains in the roadmap for tvOS.

### Phase 3: The Redirect Handler (Phone)
*   Implement a robust redirect server on the Android Phone to handle backpressure and header injection for the TV receiver.

---

## UI/UX Guidelines
*   **Posters/Backdrops**: **KEEP.** They make the app feel premium. Fetch them via stable, legal URLs (TMDB/Fanart.tv).
*   **Technical Labels**: **REMOVE.** Eliminate labels like "Torrentio," "Debrid," or "Peer Count" from the TV UI to stay under the radar during review.
*   **Control Flow**: All stream quality selection (4K vs 1080p) should happen on the Phone before casting begins.

## Verification Plan
1.  Verify that `AVPlayer` on Apple TV correctly follows the `302 Redirect` from the Android Phone server.
2.  Ensure that closing the Phone app during playback doesn't kill the redirect (if the stream link is static).
3.  Test casting from a "clean" metadata-only command to ensure the TV app remains agnostic to the content source.
