---
name: playbridge-android
description: Work across PlayBridge's Android phone, Android TV, and shared Kotlin projects. Use for Kotlin, Compose, GeckoView, Android playback, Gradle, shared KMP, root version-catalog, or Android-specific protocol consumer changes under mobile/android/, tv/android/, shared/, gradle/, or prebuilt/media3/.
---

# PlayBridge Android

## Establish ownership

- Treat `mobile/android/` and `tv/android/` as separate Gradle roots.
- Treat `shared/` and `gradle/libs.versions.toml` as cross-consumer code. Give them one writer and inspect both Android consumers.
- The phone consumes Cast Core through `cast/ffi/` JNI and checked-in libraries under `mobile/android/app/src/main/jniLibs/`; load `playbridge-rust-core` for session, sender-services, or ABI changes.
- Load `playbridge-stream-proxy-rust` as well when the embedded proxy, HLS rewriting, or Android upstream callbacks change.
- Split phone and TV work between subagents only when their edits do not overlap. Keep shared files with the primary integrator or one designated owner.
- Load `playbridge-protocol` as well when wire messages, pairing, or generated protocol bindings change.

## Work safely

1. Follow the root `AGENTS.md`, including the graph-first exploration workflow.
2. Run every Gradle command from the project that owns the change.
3. Preserve TV cleartext stream support and the scoped TLS behavior in `ContentSniffer.kt`.
4. Keep GeckoView and Media3 versions compatible with both Android roots and `prebuilt/media3/`.
5. Never log Debrid tokens, pairing credentials, signing material, or authenticated stream URLs.
6. Preserve Google Cast's physical local-network binding around VPNs, application-ready handshake, fresh-session behavior after receiver exit, and distinction between stopping media and ending the receiver.
7. For phone video detection / cast-sheet expectations (SPA soft-nav keeps rows, hard load clears, site patterns), see `docs/android-video-detection.md`.
8. For device/emulator control (install, launch, input, screenshots, UI dump), load `android-adb`.
9. For phone `adb logcat` recipes (detection, cast, proxy, TV, downloads), see `mobile/android/docs/logging.md`.

## Verify

On macOS, invoke Gradle through `zsh` after sourcing `~/.zshrc`.

From `mobile/android/`, select the narrowest relevant checks:

```bash
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest"
zsh -c "source ~/.zshrc && ./gradlew :app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew :app:lintFossDebug"
```

From `tv/android/`, select the narrowest relevant checks:

```bash
zsh -c "source ~/.zshrc && ./gradlew test"
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew lint"
```

Validate `shared/` changes through both roots when both consume the affected code.

When Cast Core JNI, sender services, proxy callbacks, or their ABI changes, run
`sh cast/build-android.sh` from the repository root before the phone checks and
verify both `armeabi-v7a` and `arm64-v8a` outputs were replaced.
