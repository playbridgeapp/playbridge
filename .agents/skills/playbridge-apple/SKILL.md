---
name: playbridge-apple
description: Work on PlayBridge's native Apple phone and Apple TV applications. Use for SwiftUI, WebKit, AVPlayer/MPVKit playback, website playback bridges, Xcode/SPM/CocoaPods, Bonjour, Keychain, TLS pinning, or Apple protocol consumers under mobile/apple/ or tv/apple/.
---

# PlayBridge Apple

## Establish ownership

- Treat `mobile/apple/` and `tv/apple/` as separate Xcode projects with shared product behavior but no shared build root.
- The phone uses Swift Package Manager for pinned standard MPVKit; open `mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj`. The TV uses its separate CocoaPods workspace. Do not transplant the TV's dependencies into the phone project.
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

- Read `docs/ios-phone-playback.md` for engine selection, TLS trust and verification. AVPlayer handles compatible streams; standard MPVKit handles Matroska and explicit local retries. Inspect the original URL/MIME even when routing produces an opaque proxy URL. A forwarding proxy does not convert media.
- Preserve the selected route, headers and resume position across preparation/retry. mpv and AVPlayer feed the same progress, final-close and lazy-queue contract. Keep mpv calls/destruction serialized, retain the rendering surface during teardown and fence stale callbacks by decoder generation.
- mpv's phone surface has no Picture in Picture or video AirPlay. Keep those capabilities with AVPlayer. Background/foreground transitions must preserve user pause intent, and interruptions must pause playback.
- Keep HTTPS verification enabled. MPVKit's GnuTLS needs the bundled Mozilla public CA store; preserve its checksum/source/license notices when refreshing it. Do not replace this with permissive TLS or retry certificate failures without verification.
- Keep standard `MPVKit` and its SPM pin consistent with `THIRD_PARTY_LICENSES.md` and bundled notices. Package-resolution failures must be resolved before reporting a build as verified; do not silently switch to the GPL product or a different project.
- Native diagnostics must copy a nonempty, redacted report. Numeric error `-13` alone does not prove that a stream expired. Classify decoder warnings in memory; never include raw decoder logs, authenticated URLs or header values in clipboard diagnostics.
- Read `docs/bridged-apps.md` and `docs/ios-website-casting.md` for page authority and destination selection. `PageCastCoordinator` and `WebsitePhonePlayback` route website `play()` to the selected local/native/external destination using the existing picker. Recheck destination and document ownership after asynchronous preparation. Unlink releases website control while playback continues.
- Preserve bridged-site detector opt-out, lazy app-tab restoration, ordinary-tab selection and Remote/Dashboard return. Keep the independent Movi fullscreen observer working for canvas playback. The landscape edge handle stays opposite the front camera. iOS does not expose Android's native plugin resolver.

## Verify

Choose focused runners from `mobile/apple/tests/README.md`. For phone local/website
playback, run the applicable checks from the repository root:

```bash
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
xcodebuild -workspace "PlayBridge TV.xcworkspace" -scheme "PlayBridge TV" -configuration Debug -destination 'generic/platform=tvOS' build
```

Report simulator, SDK, signing, or CocoaPods limitations explicitly; do not treat an unavailable Apple toolchain as successful verification.

For Google Cast adapter or native ABI work, build the optional XCFramework from
the repository root with `sh cast/build-apple.sh`, then build the phone target
with it linked as **Do Not Embed**. The script intentionally refuses to
overwrite an existing generated XCFramework.
