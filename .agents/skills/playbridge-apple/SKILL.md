---
name: playbridge-apple
description: Work on PlayBridge's native Apple phone and Apple TV applications. Use for SwiftUI, WebKit, AVPlayer/MPVKit playback, website playback bridges, Xcode/SPM, Bonjour, Keychain, TLS pinning, or Apple protocol consumers under mobile/apple/ or tv/apple/.
---

# PlayBridge Apple

## Establish ownership

- Treat `mobile/apple/` and `tv/apple/` as separate Xcode projects with shared product behavior but no shared build root.
- Both apps use Swift Package Manager and pin the same standard (LGPL) MPVKit 1.0.0 product; never link MPVKit-GPL. Open `mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj` or `tv/apple/PlayBridge TV/PlayBridge TV.xcodeproj`. There is no CocoaPods workspace. The TV renders with `vo=gpu-next` and switches the display to HDR from mpv's decoded `video-params`.
- Phone Google Cast/DLNA adapters use optional Cast Core ABI v2 in `Network/GoogleCastSession.swift`. Discovery/controller and receiver UI are implemented, but transport availability depends on the XCFramework. Ordinary phone source builds must keep working without it linked; do not confuse that optional dependency with required MPVKit.
- Keep one Apple specialist by default. Split phone and TV work only for substantial, non-overlapping implementations.
- Load `playbridge-protocol` when JSON envelopes, pairing, authentication, or generated Swift bindings change.
- Load `playbridge-rust-core` when the Cast Core adapter, C ABI, module map, or `cast/build-apple.sh` changes.

## Work safely

1. Follow the root `AGENTS.md` and inspect the owning Xcode project before changing build settings.
2. Preserve Bonjour/local-network declarations, Keychain storage, protected pairing, and SPKI pin validation.
3. Check behavioral parity with the corresponding sender or receiver without assuming Android implementation details translate directly to Apple frameworks.
4. Never commit signing credentials, provisioning data, pairing secrets, or authenticated stream URLs.

## Phone playback and website bridge

- Read `docs/ios-phone-playback.md` for the mpv-only phone policy, TLS trust and verification. Standard MPVKit handles all local streams, audio and files; do not restore format-dependent AVPlayer selection or a silent fallback. Preserve original URL/MIME for diagnostics even when routing produces an opaque proxy URL.
- Preserve the selected route, headers and resume position across preparation/retry. mpv feeds the progress, final-close and lazy-queue contract for every local format. Keep mpv calls/destruction serialized, retain the rendering surface during teardown and fence stale callbacks by decoder generation.
- Local Via phone registrations use loopback, including rewritten HLS children; receiver casts retain LAN advertisement. Preserve route selection, scoped private-origin grants and registration lifetime; never silently fall back to Direct.
- Drawable-size changes rebuild only mpv’s video output, preserving decoder, position and user pause. Metal bounds/property checks alone do not prove visible resizing; keep portrait, both landscapes, return-to-portrait and paused-rotation pixel assertions.
- `PhonePlayerControls` owns the shared three-second chrome timer. Pause/buffering, scrubbing, sheets, VoiceOver and suspension prevent auto-hide; touch lock reveals only Unlock on a tap. Preserve Fit/Fill, accumulating double-tap seeks and accessible alternatives. `PhonePlayerPreferences` persists presentation choices and normalized language codes, never track IDs, titles, URLs or credentials. Subtitle delay survives retry but resets for a new episode; native and website captions share timing/style preferences.
- mpv's phone surface has no Picture in Picture or video AirPlay. The dedicated external AirPlay casting controller still uses the system player; keep it separate from local playback. Background/foreground transitions must preserve user pause intent, and interruptions must pause playback.
- Keep HTTPS verification enabled. MPVKit's GnuTLS needs the bundled Mozilla public CA store; preserve its checksum/source/license notices when refreshing it. Do not replace this with permissive TLS or retry certificate failures without verification.
- Keep standard `MPVKit` and its SPM pin consistent with `THIRD_PARTY_LICENSES.md` and bundled notices. Package-resolution failures must be resolved before reporting a build as verified; do not silently switch to the GPL product or a different project.
- Native diagnostics must copy a nonempty, redacted report. Numeric error `-13` alone does not prove that a stream expired. Classify decoder warnings in memory; never include raw decoder logs, authenticated URLs or header values in clipboard diagnostics.
- Read `docs/bridged-apps.md` and `docs/ios-website-casting.md` for page authority and destination selection. `PageCastCoordinator` and `WebsitePhonePlayback` route website `play()` to the selected local/native/external destination using the existing picker. Recheck destination and document ownership after asynchronous preparation. Unlink releases website control while playback continues. Report the old episode’s progress before manual navigation or EOF changes its identity; unresolved queue tails wait for supply until explicit end-of-list.
- Installed apps save a Home URL (initially the manifest’s `start_url`) and restore lazily at it after a fresh process launch, not at the last deep link. Ordinary browser tabs keep their restoration behavior; Dashboard/Remote switches keep live app pages. Long-press tiles open Info with same-origin name/home editing and confirmed removal. Preserve app identity, icon and tile order, update unloaded tabs after home edits without navigating loaded sessions, and discard cancelled drafts. Removal closes app tabs but keeps website data and casting grants; these settings are native UI, not website API calls.
- Preserve bridged-site detector opt-out, lazy app-tab restoration, ordinary-tab selection and Remote/Dashboard return. Keep the independent Movi fullscreen observer working for canvas playback. The landscape edge handle stays opposite the front camera. iOS does not expose Android's native plugin resolver.

## Verify

Choose focused runners from `mobile/apple/tests/README.md`. For phone local/website
playback, run the applicable checks from the repository root:

```bash
bash mobile/apple/tests/run-phone-player-features-checks.sh
bash mobile/apple/tests/run-phone-engine-checks.sh
bash mobile/apple/tests/run-website-playback-checks.sh
bash mobile/apple/tests/run-playback-error-checks.sh
bash mobile/apple/tests/run-page-cast-coordinator-checks.sh
node --test mobile/apple/tests/PageCastScriptTests.js
```

Use the isolated simulator MPV probe described in `docs/ios-phone-playback.md`
when changing the decoder, rendering, TLS or engine lifecycle. It needs a booted
simulator and ffmpeg; use a file outside the repo for private stream URLs.
Host model tests do not prove native decoding, hardware behavior or receiver playback.
Use `MPV_FEATURES_PROBE=1` for native speed/language and Fit/Fill/subtitle pixel
checks, `MPV_ORIENTATION_PROBE=1` for rotation, and `MPV_PHONE_PROXY_PROBE=1`
for production loopback playback (requires the existing optional Cast Core
XCFramework). Run modes separately as documented; do not infer visible rendering
from engine options or view geometry alone.

For installed-app lifecycle or Info/edit/remove UI changes, run:

```bash
bash mobile/apple/tests/run-bridged-app-declaration-checks.sh
bash mobile/apple/tests/run-browser-startup-checks.sh --bridged-apps
bash mobile/apple/tests/run-bridged-app-ui-checks.sh
```

The latter two require a booted simulator; UI tests use an isolated installation.
Report physical iPhone gesture checks separately.

Build the changed target with Xcode. From `mobile/apple/PlayBridge Phone/`, the
phone simulator build is:

```bash
xcodebuild -project "PlayBridge Phone.xcodeproj" -scheme "PlayBridge Phone" -configuration Debug -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

For package/native-library integration, also build Release for
`generic/platform=iOS` with `CODE_SIGNING_ALLOWED=NO`. An unsigned generic-device
build checks compilation/linking; it does not install the app or validate signing.

From `tv/apple/PlayBridge TV/`:

```bash
xcodebuild -project "PlayBridge TV.xcodeproj" -scheme "PlayBridge TV" -configuration Debug -destination 'generic/platform=tvOS' build
```

Report simulator, SDK, signing, or package-resolution limitations explicitly; do not treat an unavailable Apple toolchain as successful verification.

For Google Cast adapter or native ABI work, build the optional XCFramework from
the repository root with `sh cast/build-apple.sh`, then build the phone target
with it linked as **Do Not Embed**. The script intentionally refuses to
overwrite an existing generated XCFramework.
