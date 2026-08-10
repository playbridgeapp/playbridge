# Android WebRTC runtime

This module packages the Android WebRTC runtime vendored from the ScreenStream
Android runtime snapshot.

- Upstream baseline: WebRTC `150.0.7871.63`, branch-head `7871`
- Upstream revision: `1f975dfd761af6e5d76d28333191973b258d82a8`
- Origin wrapper: ScreenStream (MIT), [`SCREENSTREAM_LICENSE`](SCREENSTREAM_LICENSE)

[`WEBRTC_RUNTIME_README.md`](WEBRTC_RUNTIME_README.md) records the SHA-256 digest
for every supplied ABI. The WebRTC Java sources and native libraries are included
directly in this module so a clean checkout does not depend on the inspiration tree.
