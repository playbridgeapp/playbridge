# Third-Party Licenses

PlayBridge's own code is licensed under **MPL-2.0** (see [LICENSE](LICENSE)).
Third-party components retain their own licences; this change does not relicense
vendored code, native binaries or dependency notices. A first-party SPDX label
alone does not describe the licence obligations of a complete distributed app.

MPL-2.0 permits appropriately licensed larger works with other components. Where
a combined executable includes GPL software, the GPL distribution obligations
still apply, using MPL section 3.3 where needed. PlayBridge has not attached the
Exhibit B incompatibility notice. See [Mozilla's compatibility guidance](https://www.mozilla.org/en-US/MPL/2.0/FAQ/#q14-may-i-combine-mpl-licensed-code-and-lgpl-licensed-code-in-the-same-executable-program).

## Bundled native media libraries

Shipped as prebuilt binaries; these carry copyleft obligations (preserve their license
notices, and make the corresponding source available — see *Source availability* below).

| Component | Where | License |
|-----------|-------|---------|
| **FFmpeg** | `mpv-android.aar`, `lib-decoder-ffmpeg-*.aar`, `nextlib`, desktop `media_kit` | LGPL-2.1-or-later (GPL-2.0-or-later if built with `--enable-gpl`) — https://ffmpeg.org |
| **mpv / libmpv** | `mpv-android.aar`, desktop | LGPL-2.1-or-later — https://github.com/mpv-player/mpv |
| **MPVKit 1.0.0** (standard product, libmpv / FFmpeg and supporting libraries) | iOS phone and Apple TV, Swift Package Manager | LGPL-3.0 for the standard MPVKit bundle; supporting libraries retain their own notices — https://github.com/mpvkit/MPVKit/tree/1.0.0 |
| **mpv-android** (prebuilt AAR build) | `tv/.../libs/mpv-android.aar` | Upstream JNI/app wrapper: MIT; the native bundle has its own GPL/LGPL obligations — https://github.com/mpv-android/mpv-android · https://github.com/marlboro-advance/mpvEx |
| **nextlib** | `nextlib-mediainfo-local.aar` | GPL-3.0 — https://github.com/anilbeesetti/nextlib |
| **libgav1** (AV1) | `lib-decoder-av1-*.aar` | Apache-2.0 — https://chromium.googlesource.com/codecs/libgav1 |
| **libiamf** | `lib-decoder-iamf-*.aar` | BSD / AOM — https://github.com/AOMediaCodec/libiamf |

> The ExoPlayer/Media3 decoder *wrappers* are Apache-2.0; the licenses above cover the
> native codec libraries those `.aar`s bundle.

## Browser engine

| Component | License |
|-----------|---------|
| **GeckoView** & **Mozilla Android Components** | MPL-2.0 |
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

PlayBridge's own source lives in this repository under MPL-2.0. Distributors must
identify the matching source revision and how recipients can obtain it. Preserve
third-party notices and provide the exact corresponding source, modifications,
build scripts and applicable relinking/installation material for bundled
copyleft libraries. A link to an upstream default branch alone is not necessarily
sufficient for a particular binary. Questions may be sent to the contact in the
[README](README.md).

The iOS phone and the Apple TV app pin MPVKit to `1.0.0` (`288527dffbc6d3e63cce147fc7b520c64a791603`)
and links the **MPVKit** product, not **MPVKit-GPL**. Its build scripts and dependency
source/version references are in
[`Sources/BuildScripts`](https://github.com/mpvkit/MPVKit/tree/1.0.0/Sources/BuildScripts)
and [`Package.swift`](https://github.com/mpvkit/MPVKit/blob/1.0.0/Package.swift).
Both apps bundle the MPVKit license and source-reference notice in `Licenses/`.


## Distribution review by app

| App | Native/component evidence and distribution obligations |
| --- | --- |
| Android phone (FOSS and Play) | Both link `prebuilt/media3/lib-decoder-ffmpeg-release.aar`. Production Gradle dependencies do **not** include mpv-android or nextlib; the shared module's mpv dependency is compile-only. Gecko/Mozilla code is MPL; patched WebRTC has BSD/MIT notices. The FFmpeg AAR lacks a corresponding-source/build-configuration record and embedded licence notice, so its exact LGPL/GPL/nonfree configuration is **unverified**. Do not certify a clean MPL-only app distribution until this is resolved. |
| Android TV player | Bundles the mpv-android native AAR and nextlib (GPL-3.0), plus the shared FFmpeg decoder. The complete player APK must be distributed under applicable GPL-3.0 terms, with corresponding source and notices; its first-party source remains MPL-2.0 and may additionally be distributed under the secondary licence as MPL 3.3 permits. This is **not an MPL-only executable**. |
| Android TV browser plugin | Gecko is MPL; uBlock Origin is GPL-3.0 as a separately installed/bundled extension, with its filter-data licences. Preserve the extension's licence/source and filter attribution. Its separate-component treatment does not relicense the plugin's own code; reassess if GPL code is linked or merged into the app. |
| iOS phone | Standard MPVKit 1.0.0 LGPL-3.0, not MPVKit-GPL; MPL certificate data, Swift permissive dependencies and first-party MPL Cast Core. Keep the LGPL licence/source notices and required relinking/reverse-engineering rights. App Store/signing/installation compliance remains a release review; MPL does not waive LGPL obligations. |
| Apple TV | Same standard LGPL MPVKit product and obligations as iOS. Historical GPL/nonfree MPVKit builds were removed; they must not be reintroduced under an MPL-only claim. |
| Desktop | Flutter/media_kit wrappers are permissive, but native libmpv/FFmpeg licences depend on the actual packaged platform builds. Darwin upstream documents LGPL default/full builds and GPL encoder builds. Audit each macOS/Windows/Linux payload, preserve source/relinking material, and use the applicable GPL larger-work terms if GPL libraries are included. A wrapper's MIT licence is not proof of a native binary's licence. |
| CLI | First-party Rust crates and hosted browser receiver are MPL. External mpv is controlled as a separate process, not relicensed by the CLI. Any bundled external player or optional FFmpeg runtime must retain its own licences, notices and source obligations; verify its build flags separately. |
| Browser extension / public website / web receiver | First-party code is MPL; TypeScript/esbuild, Video.js and other declared web dependencies retain their permissive licences. Minified JavaScript is executable form: retain a way for recipients to find the corresponding unminified source. Existing repository/source links serve this purpose. |

The Android phone FFmpeg provenance gap, desktop native build variants and Apple
LGPL delivery requirements remain release obligations. No binaries or third-party
licences were changed as part of the first-party relicensing. TV Gradle packaging
excludes `META-INF/AL2.0` and `META-INF/LGPL2.1`; releases must preserve equivalent
notices elsewhere. This audit does not certify complete notice delivery in every
packaged artifact.

---

_Maintained on a best-effort basis. Entries marked ⚠️ should be confirmed against their
upstream license/patent terms. Spotted an error or omission? Please open an issue._
