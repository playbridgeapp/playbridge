# Android browser lifecycle and port teardown

## Lazy pages, stable UI

The launch Dashboard restores tab metadata and saved history, not live pages.
`BrowserSessionRestorePolicy` permits automatic engine creation/crash recovery
only for the selected page actually displayed in Browser (including bridged-app
mode). Selecting a fallback while closing a tab in Tabs does not load it.
Existing live sessions remain available during Dashboard/Remote switches;
configuration recreation does not suspend them. Finishing the owning Activity
fences stale callbacks before suspending sessions. Ordinary browser history is
retained; fresh bridged-app sessions use their saved Home URLs.

Engine availability must not select a different navigation tree. The single
`AppNavHost`/Tabs list stays mounted while a selected replacement page is
unloaded. Keyed rows animate removal without resetting list position.

Website device-picker requests use monotonic, consumable tokens. Consumed
requests cannot replay to a replacement UI; host closure discards unseen ones.
These are internal UI requests, not a page API change.

## GeckoView disconnect guard

The pinned M150 `WebExtension.Port.disconnect()` checks its disconnected flag,
but the extension callback and common cleanup do not. A queued disconnect after
app-side cleanup can shut down the same native EventDispatcher twice, producing
`NativeException NullHandle()`. The phone build applies a narrow ASM guard to
`disconnectFromExtension()` and `disconnected()` in every flavor/build type.
Cleanup marks the port disconnected before native shutdown. First-time errors
are not caught or suppressed. No GeckoView version, TV dependency, or native
engine binary is changed.

`buildSrc` tests load the actual pinned AAR port bytecode, replacing only its
native dispatcher with a fixture. They demonstrate the unpatched duplicate
shutdown failure and check late callbacks, repeated callbacks, delegate
reentry, and first-time failure propagation. This is not a physical JNI/device
reproduction and does not identify the exact port involved in a user crash.
The transform fails if the upstream method shape changes; review/remove it
when updating GeckoView.

Plugin permission revocation separately marks document authority closed, cancels
work once, and rejects further traffic. It does not forcibly disconnect a port
while Gecko is already tearing down its document. Actual transport cleanup
remains GeckoView-owned.

## Checks

From `mobile/android/` on macOS:

```bash
zsh -c "source ~/.zshrc && ./gradlew -p buildSrc test"
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug :app:assemblePlayDebug :app:lintFossDebug"
```

Phone PR checks and non-skipped phone release builds also run the guard suite
before building application artifacts. `buildSrc` tests are not implicitly run
by the application’s `check` task.

Physical follow-up: open an app destination picker, finish/reopen PlayBridge,
verify a clean lazy Dashboard, then open Browser/app normally. In Tabs, close
selected hibernated and live tabs rapidly; check scroll continuity, absence of
replayed pickers, and crash logs. Also verify rotation and live background
playback. Installation/launch and JVM models alone do not prove these gestures.
