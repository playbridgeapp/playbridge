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

1. Follow the root `AGENTS.md`, including adaptive Serena/graph exploration.
2. Run every Gradle command from the project that owns the change.
3. Preserve TV cleartext stream support and the scoped TLS behavior in `ContentSniffer.kt`.
4. Keep GeckoView and Media3 versions compatible with both Android roots and `prebuilt/media3/`.
5. Never log Debrid tokens, pairing credentials, signing material, or authenticated stream URLs.
6. Preserve Google Cast's physical local-network binding around VPNs, application-ready handshake, fresh-session behavior after receiver exit, and distinction between stopping media and ending the receiver.
7. For phone media detection / cast-sheet expectations (SPA soft-nav keeps rows, hard load clears, site patterns), see `docs/video-detection.md`. The phone detector is generated from `extension/src/core/` and `extension/src/geckoview/`; change source and run `pnpm build` from `extension/`, then inspect the Kotlin consumer and generated assets together.
8. For device/emulator control, load `android-adb`. Reuse an authorized target selected by the user; check existing ADB devices and wireless-debugging mDNS before a LAN scan. Use the device's advertised connection port, then the FOSS debug rebuild/install/launch workflow and verify the actual interaction.
9. For phone `adb logcat` recipes (detection, cast, proxy, TV, downloads), see `mobile/android/docs/logging.md`.

## Website playback and device plugins

- Read `docs/bridged-apps.md` for installed-app lifecycle, detection opt-out, permissions, and the page API. Keep normal browser tab selection separate from app sessions across process death; preserve the app's identity and return target when opening Dashboard or Remote.
- `PagePlaybackCoordinator.kt` selects local/native/external playback; `PagePlayerSession.kt` and `PlayerActivity.kt` own local progress and lazy queues. The page's `play()` uses the existing device picker and selected destination, including **This device**. Recheck it after asynchronous preparation and reject stale document/session results. Unlink releases website authority while playback continues.
- A valid bridged-site declaration suppresses automatic detection. **Media detect → Advanced → Detect on bridged sites** is an override, off by default; toggling categories must stop their work, remove existing rows and reject late results.
- Device Nuvio plugins are opt-in, FOSS-only, and shared by Library and installed Bridged Apps. Keep QuickJS and bundled scraper dependencies in `src/foss/` / `fossImplementation`; `src/play/` advertises no native resolver. Preserve installed-app/top-frame authority, provider code/domain approvals and cancellation. The page may send installed identifiers, never arbitrary code, HTTP fetch commands or account secrets.
- Coordinate page API changes with `extension/src/geckoview/` and the Apple host; companion Streams UI lives in the separate `bridged-apps` repo.

## Verify

On macOS, invoke Gradle through `zsh` after sourcing `~/.zshrc`.

From `mobile/android/`, select the narrowest relevant checks:

```bash
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest"
zsh -c "source ~/.zshrc && ./gradlew :app:assembleFossDebug"
zsh -c "source ~/.zshrc && ./gradlew :app:lintFossDebug"
```

From `tv/android/`, select the narrowest relevant checks:

```bash
zsh -c "source ~/.zshrc && ./gradlew test"
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleFossDebug"
zsh -c "source ~/.zshrc && ./gradlew lint"
```

Validate `shared/` changes through both roots when both consume the affected code.

For flavor-specific plugin or shared phone changes that could affect store builds,
also assemble `:app:assemblePlayDebug`; passing the FOSS build does not verify the
Play source set.

When Cast Core JNI, sender services, proxy callbacks, or their ABI changes, run
`sh cast/build-android.sh` from the repository root before the phone checks and
verify both `armeabi-v7a` and `arm64-v8a` outputs were replaced.
