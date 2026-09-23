# Rust sender services on iPhone

The phone target links `mobile/apple/Native/PlayBridgeCastCore.xcframework` as a
static library, not an embedded framework. Generate it before building Xcode:

```sh
sh cast/build-apple.sh
```

The script builds iOS arm64 and simulator arm64/x86_64 slices with
`sender-services-apple`. The generated framework is gitignored. Rebuilding an
existing output requires moving or removing that generated directory first;
the script refuses to overwrite it. No version bump is required.

`PhoneSenderServices` serializes commands and polls the existing sender-services
C ABI on a worker queue. `PhoneProxyRegistration` retains a registered URL until
its consumer releases it, then revokes the Rust session and its HLS children.
`AppleProxyUpstream` implements the existing host callback ABI with URLSession,
bounded buffers, per-response cancellation, and normal platform TLS validation.
There is no Apple-specific HLS rewriter or FFmpeg dependency in this adapter.

The cast sheet offers Direct, Via phone, and Via proxy. Play on phone, Send,
and Queue use the same route service. Direct passes the original URL and headers
without starting Rust. Via phone registers with Rust and passes the proxy URL
without origin headers. Via proxy uses the configured remote server's registration
API; its password is stored in Keychain. A proxy failure does not silently change
the selected route. Configure the remote server with the gear beside the selector.

On Wi-Fi, phone-hosted URLs use the phone's LAN address so AirPlay can fetch them.
With no LAN address, local playback uses loopback with AirPlay disabled, and
receiver sends via phone report an error. Closing local playback releases its
phone registration. The connection owner retains sent and queued registrations
beyond sheet dismissal; replacement playback or disconnect releases them.


Limitations and follow-up:

- Progressive `.mp4` URLs (including a trailing slash) may redirect. Apple returns
  the HTTP status and Location to Rust, which validates every hop, limits the
  chain to ten redirects, and keeps credentials scoped to the initial origin.
  Browser User-Agent and origin-only cross-host Referer survive CDN redirects.
  Redirected HLS playlists still require effective-URL support for relative paths.
- The external Rust proxy uses the same redirect-header policy. Deploy an updated
  proxy server to receive that fix; rebuilding the iOS app updates Via phone only.
  AVIO cannot supply HTTP range metadata, so a failed ranged HTTP request reports
  an error instead of synthesizing an invalid 206/Content-Range response.
- Browser registrations currently allow public origins only. A native caller
  can explicitly allow an exact private origin; arbitrary page-provided private
  destinations must not bypass the Rust network policy.
- A Wi-Fi address change requires preparing playback again.
- Google Cast's existing Swift native adapter now has a linked artifact, but
  discovery/UI/session adoption remains pending. PlayBridge still uses its
  existing authenticated Swift connection. DLNA integration remains pending.
  These can reuse this sender-services wrapper without duplicating the proxy.
- Proxying does not transcode unsupported codecs or remove content protection.

Playback diagnostics in debug builds identify Rust/URLSession routing and redact
proxy session IDs. They do not prove that an AirPlay receiver rendered a frame.
