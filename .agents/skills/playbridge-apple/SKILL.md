---
name: playbridge-apple
description: Work on PlayBridge's native Apple phone and Apple TV applications. Use for Swift, SwiftUI, WebKit, AVFoundation, tvOS/iOS playback, Xcode project, CocoaPods, Bonjour, Keychain, TLS pinning, or Apple protocol-consumer changes under mobile/apple/ or tv/apple/.
---

# PlayBridge Apple

## Establish ownership

- Treat `mobile/apple/` and `tv/apple/` as separate Xcode projects with shared product behavior but no shared build root.
- Keep one Apple specialist by default. Split phone and TV work only for substantial, non-overlapping implementations.
- Load `playbridge-protocol` when JSON envelopes, pairing, authentication, or generated Swift bindings change.

## Work safely

1. Follow the root `AGENTS.md` and inspect the owning Xcode project before changing build settings.
2. Preserve Bonjour/local-network declarations, Keychain storage, protected pairing, and SPKI pin validation.
3. Check behavioral parity with the corresponding sender or receiver without assuming Android implementation details translate directly to Apple frameworks.
4. Never commit signing credentials, provisioning data, pairing secrets, or authenticated stream URLs.

## Verify

Build the changed target with Xcode. From `mobile/apple/PlayBridge Phone/`:

```bash
xcodebuild -project "PlayBridge Phone.xcodeproj" -scheme "PlayBridge Phone" -destination 'generic/platform=iOS Simulator' build
```

From `tv/apple/PlayBridge TV/`:

```bash
xcodebuild -workspace "PlayBridge TV.xcworkspace" -scheme "PlayBridge TV" -configuration Debug -destination 'generic/platform=tvOS' build
```

Report simulator, SDK, signing, or CocoaPods limitations explicitly; do not treat an unavailable Apple toolchain as successful verification.
