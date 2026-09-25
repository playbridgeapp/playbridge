# iOS AirPlay casting

Connections and the cast destination sheet include Apple's AirPlay route picker.
After choosing a route, browser Play/Queue, Cast a link, collections, IPTV, local
media and cast-history replay use one persistent AVPlayer. Navigation and closing
the cast sheet do not close it. AirPlay remains selected after Stop, but iOS owns
the actual route and may release it. Stop using AirPlay ends PlayBridge's session;
choose iPhone in Apple's picker to change the system output.

The existing Remote screen handles play/pause, relative and absolute scrubbing,
10-second skips, Stop, Next, the upcoming queue and track selection. Queue entries
can be removed, reordered and cleared. Audio volume uses Apple's system control.
TV navigation, browser commands, engine switching and subtitle-offset controls
are not offered for AirPlay. Casting an image over this path is unsupported.

The phone owns the queue and retains each item's proxy/subtitle resources until
that item is replaced or removed. Stop and destination changes invalidate pending
loads. Losing the route pauses the player to prevent continued phone playback;
reselect the output and tap Play. Foreground entry rechecks the system route.
Local playback or an audio interruption pauses AirPlay and yields system controls.
AirPlay uses AVPlayer's background playback support, without a silent audio loop.

Browser web views have `allowsAirPlayForMediaPlayback = false`, including popups,
so webpage video players cannot take over the app's selected AirPlay session.
This also removes the browser-native AirPlay path; use the app's cast sheet.

## Subtitles and routing

Embedded subtitle and audio tracks are selected using AVFoundation media groups.
External subtitles can be attached in the cast sheet or added later through the
same Detected / Local / URL picker used by PlayBridge receivers. Late additions
rebuild the item's HLS presentation and restore its position and paused/playing
state; a short rebuffer may occur. Each queued item keeps its own attached tracks.

The first external-subtitle implementation supports SRT and WebVTT for finite,
unencrypted MPEG-TS HLS without discontinuities or byte ranges. All video variants
are checked. It preserves existing audio/subtitle groups, derives the caption
timestamp map from the first segment, and serves generated manifests and WebVTT
from a private, bounded, memory-only LAN HTTP server. Unknown subtitle languages
use `und`; track display names retain the supplied labels.

External subtitles on progressive MP4, fMP4 HLS, encrypted HLS, live streams and
discontinuous timelines are rejected with a descriptive error. Embedded tracks
remain usable for otherwise playable streams. No video transcoding is performed.

Protected direct streams requiring request headers are routed through the phone
because AirPlay receivers cannot rely on AVURLAsset's private header options.
Generated external subtitles also require the phone on Wi-Fi. The cast sheet
discloses this. Public direct streams and already prepared proxy streams retain
their route. Subtitle requests use their own headers and bounded downloads.

## Validation

Run from the repository root:

```sh
bash mobile/apple/tests/run-airplay-queue-checks.sh
bash mobile/apple/tests/run-airplay-subtitle-checks.sh
bash mobile/apple/tests/run-remote-control-checks.sh
bash mobile/apple/tests/run-cast-playback-checks.sh
```

The queue tests cover ordered advance, replacement, late subtitle updates,
removal, reordering and exhaustion. Subtitle tests cover manifest preservation,
track name collisions, SRT conversion, version/language attributes, timestamp
mapping and unsupported timelines. These do not prove physical AirPlay behavior.

Device validation still required: select a route before playback; replace and
queue several items; change embedded audio/subtitles; add SRT/VTT before and
during playback; turn subtitles off; lock the phone through an item transition;
drop/reselect the route; interrupt with another audio app; stop while preparing;
open a webpage video while casting; switch back to PlayBridge/Google Cast.
