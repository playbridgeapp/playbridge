# Third-Party Licenses

PlayBridge is licensed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
It bundles and depends on third-party software under their own licenses, all of which are
compatible with GPLv3. Copyleft components (LGPL / GPL / MPL) are listed first, with their
obligations noted.

## Bundled native media libraries

Shipped as prebuilt binaries; these carry copyleft obligations (preserve their license
notices, and make the corresponding source available — see *Source availability* below).

| Component | Where | License |
|-----------|-------|---------|
| **FFmpeg** | `mpv-android.aar`, `lib-decoder-ffmpeg-*.aar`, `nextlib`, desktop `media_kit` | LGPL-2.1-or-later (GPL-2.0-or-later if built with `--enable-gpl`) — https://ffmpeg.org |
| **mpv / libmpv** | `mpv-android.aar`, desktop | LGPL-2.1-or-later — https://github.com/mpv-player/mpv |
| **MPVKit 1.0.0** (standard product, libmpv / FFmpeg and supporting libraries) | iOS phone and Apple TV, Swift Package Manager | LGPL-3.0 for the standard MPVKit bundle; supporting libraries retain their own notices — https://github.com/mpvkit/MPVKit/tree/1.0.0 |
| **mpv-android** (prebuilt AAR build) | `tv/.../libs/mpv-android.aar` | GPL — https://github.com/mpv-android/mpv-android · https://github.com/marlboro-advance/mpvEx |
| **nextlib** | `nextlib-mediainfo-local.aar` | GPL-3.0 — https://github.com/anilbeesetti/nextlib |
| **libgav1** (AV1) | `lib-decoder-av1-*.aar` | Apache-2.0 — https://chromium.googlesource.com/codecs/libgav1 |
| **libiamf** | `lib-decoder-iamf-*.aar` | BSD / AOM — https://github.com/AOMediaCodec/libiamf |

> The ExoPlayer/Media3 decoder *wrappers* are Apache-2.0; the licenses above cover the
> native codec libraries those `.aar`s bundle.

## Browser engine

| Component | License |
|-----------|---------|
| **GeckoView** & **Mozilla Android Components** | MPL-2.0 (GPLv3-compatible) |
| **Mozilla root CA certificate data** (curl extraction, 2026-09-25) | MPL-2.0; iOS mpv TLS trust store — https://curl.se/docs/caextract.html |

## Bundled browser content (Android TV browser)

| Component | License |
|-----------|---------|
| **uBlock Origin** (`assets/extensions/ublock_origin`) | GPL-3.0 (see bundled `LICENSE.txt`) |
| **EasyList / EasyPrivacy** filter lists | GPL-3.0 / CC BY-SA |
| **Public Suffix List** | MPL-2.0 |
| **CodeMirror** | MIT |
| **js-beautify** | MIT |

## Application libraries (permissive: Apache-2.0 / MIT / BSD)

- **Android / Jetpack** — AndroidX, Jetpack Compose, Media3 (ExoPlayer), Room, DataStore, TV libraries — Apache-2.0
- **Kotlin & kotlinx** (serialization, coroutines), KSP — Apache-2.0
- **OkHttp / Okio**, **Wire** (Square) — Apache-2.0
- **Ktor**, **Koin**, **multiplatform-settings**, **Coil** — Apache-2.0
- **Java-WebSocket** — MIT · **Bouncy Castle** — MIT (Bouncy Castle Licence)
- **Desktop (Flutter)** — `media_kit` (MIT); `video_player`, `shelf`, `web_socket_channel`, `path_provider`, `shared_preferences` (BSD-3, Flutter/Dart); `bonsoir`, `window_manager`, `tray_manager`, `uuid`, `crypto`, `basic_utils` (MIT/BSD)
- **Browser extension** — `@bufbuild/protobuf` (Apache-2.0), `esbuild` (MIT), `typescript` (Apache-2.0)

## Source availability

PlayBridge's own source lives in this repository under GPLv3. For the bundled LGPL/GPL
native binaries listed above, the corresponding source is available from each linked
upstream project, and may also be requested via the contact in the [README](README.md).

The iOS phone and the Apple TV app pin MPVKit to `1.0.0` (`288527dffbc6d3e63cce147fc7b520c64a791603`)
and links the **MPVKit** product, not **MPVKit-GPL**. Its build scripts and dependency
source/version references are in
[`Sources/BuildScripts`](https://github.com/mpvkit/MPVKit/tree/1.0.0/Sources/BuildScripts)
and [`Package.swift`](https://github.com/mpvkit/MPVKit/blob/1.0.0/Package.swift).
Both apps bundle the MPVKit license and source-reference notice in `Licenses/`.

---

_Maintained on a best-effort basis. Entries marked ⚠️ should be confirmed against their
upstream license/patent terms. Spotted an error or omission? Please open an issue._
