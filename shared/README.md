# PlayBridge — Shared Module

Kotlin Multiplatform module (`com.playbridge.shared`) holding logic shared across the Android apps: player-engine abstractions (ExoPlayer and MPV), now-playing / queue state, and protocol bindings.

It is included as `:shared` by both the [phone app](../mobile/) (**PlayBridgePhone**) and the [TV apps](../tv/) (**PlayBridgeTV**) — it is not built standalone.

## Notes

- The MPV engine compiles against the prebuilt `mpv-android.aar`, which the TV player bundles — see [`tv/android/player/app/libs/`](../tv/android/player/app/libs/).
