# PlayBridge — iPhone and iPad

A native SwiftUI sender with a multi-tab WebKit browser, automatic media detection,
a remote control, local video files, M3U playlists, collections and browser downloads.
Websites can request playback through `window.playbridge`, with user permission.
PlayBridge provides no content.

Receivers include PlayBridge TV and Desktop, Google Cast, AirPlay, DLNA and Roku.
Pairing credentials use the Keychain; PlayBridge connections validate saved TLS pins.
Phone playback uses standard **MPVKit (LGPL)**, never MPVKit-GPL. Ad blocking uses
WebKit content rules and **adblock-rust**. **Cast Core** supplies native discovery,
external transports and media routing. See [phone playback](../../docs/ios-phone-playback.md)
and the [test runners](tests/README.md) for implementation and verification details.

## Build

Open `PlayBridge Phone/PlayBridge Phone.xcodeproj`, or build without launching a simulator:

```bash
xcodebuild -project "mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj" \
  -scheme "PlayBridge Phone" -configuration Debug \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

MPVKit is resolved through Swift Package Manager. Build the optional native framework
with `sh cast/build-apple.sh` from the repository root. Xcode links it automatically
when present, without embedding it. Debug builds can use the unavailable transport
stub; Release builds require linked Cast Core symbols.

- `PB_DEVELOPER_TOOLS` is a Swift compilation condition set only in Debug. It includes
  the optional developer toggle, detected-media JSON/cURL views and full network-log
  export. Release compiles these out and never reads `pb_developer_tools`; redacted
  network-log export remains available.
- `PB_MULTICAST_ENTITLEMENT = YES` in `PlayBridge Phone/MulticastEntitlement.xcconfig`
  enables the granted multicast entitlement and SSDP discovery. Teams without that
  entitlement can build with `PB_MULTICAST_ENTITLEMENT=NO`.
- iPhone discovery requires Local Network permission. Bonjour services and networking
  purpose strings are declared in `PlayBridge Phone/Info.plist`.

The phone consumes the shared [PlayBridge protocol](../../protocol/README.md).
