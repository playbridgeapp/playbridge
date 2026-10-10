# App Store submission drafts

Paste-ready text for the steps that need the paid Apple Developer account. Replace every `[bracketed]` placeholder before sending. The readiness audits explain the reasoning behind each answer: [iOS](ios-app-store-readiness-audit.md), [tvOS](tvos-app-store-readiness-audit.md).

| App | Bundle ID | Version (build) |
|---|---|---|
| PlayBridge Phone (iOS) | `com.playbridge.PlayBridge-Phone` | 0.15.0 (7) |
| PlayBridge TV (tvOS) | `com.playbridge.PlayBridge-TV` | 0.15.0 (6) |

Version numbers come from the Xcode projects at the time of writing; use the values of the build you upload.

## 1. Multicast Networking entitlement request (iOS only)

Submit this as soon as the account is active, because approval takes time. Form: <https://developer.apple.com/contact/request/networking-multicast>. Only the iPhone app needs it. The Apple TV app and Bonjour discovery (PlayBridge TV receivers and Google Cast, through `NetServiceBrowser`) do not.

After approval, set `PB_MULTICAST_ENTITLEMENT = YES` in `mobile/apple/PlayBridge Phone/MulticastEntitlement.xcconfig` (see [`mobile/apple/docs/dlna.md`](../mobile/apple/docs/dlna.md)).

**App name:** PlayBridge

**Bundle ID:** com.playbridge.PlayBridge-Phone

**Describe your app and why it needs multicast networking:**

> PlayBridge is a casting app: users browse to video on their iPhone and play it on a TV or media receiver on the same local network. To find receivers, the app sends SSDP discovery requests (UDP multicast to 239.255.255.250, port 1900) and listens for replies from DLNA/UPnP media renderers, Roku devices and DIAL-capable smart TVs. These device classes advertise themselves only through SSDP, not Bonjour, so without multicast the user has to look up and type each device's IP address.
>
> Discovery runs only while the user has the device picker open, lasts about five seconds per scan, and stops when the picker closes. The app does not send or receive multicast traffic otherwise and does not use it for data transfer. After discovery, the app controls the chosen receiver over unicast HTTP (UPnP AVTransport, the Roku External Control Protocol, or DIAL). The app already declares NSLocalNetworkUsageDescription and asks for Local Network permission. Its own receivers and Google Cast devices are found with Bonjour.

**Protocols and addresses:** SSDP (UPnP), IPv4 multicast group 239.255.255.250, UDP port 1900, M-SEARCH for `urn:schemas-upnp-org:device:MediaRenderer:1`, `roku:ecp` and `urn:dial-multiscreen-org:service:dial:1`.

## 2. App Review notes

App Review can't pair a receiver it doesn't have. Before submitting:

- Record a short screen recording of each flow (pairing, casting, playback on the TV) and add the link below.
- Host a rights-cleared sample video (for example, a Blender Foundation open movie) at a stable HTTPS URL, and optionally a neutral test page that uses `window.playbridge`.
- Submit the TV app with the iPhone app, or after it is live, so the reviewer has a sender.

### iOS notes

> PlayBridge lets you play web and local video on a TV. It is a web browser with a casting button, plus a remote control for the TV. No account or login is needed.
>
> **How to test**
> 1. Open the app and allow Local Network access when asked.
> 2. Open this sample page in the browser tab: [sample page URL]. Play the video, then tap the cast button.
> 3. Pick a receiver: an Apple TV running PlayBridge TV, a Google Cast device, AirPlay, or a DLNA/Roku device. To play on the iPhone itself instead, choose **Play on Phone** in the cast sheet.
> 4. A screen recording of pairing with the Apple TV app and casting is here: [recording link].
>
> **Background audio.** PlayBridge is a remote control for video playing on a TV or other receiver on the user's network. During an active cast, PlayBridge keeps running in the background so the user can control the TV from the lock screen and Control Center, and so the phone can keep serving media and advancing episodes that the TV depends on (local files, DLNA renderers, website queues). This stops when the cast ends or after five minutes paused.
>
> **Website integration.** Websites can call `window.playbridge` to send their own video to the user's TV. It exposes only PlayBridge's casting and playback controls, not device APIs. Each website must be approved by the user before it can see or use receivers, and choosing a receiver always requires a tap. Users can save such websites as shortcuts ("Bridged Apps"); the app ships no catalog of them.
>
> **Downloads.** Like Safari, the browser downloads a file only when a website serves it as a download, after the user confirms. There is no button to download detected video streams.
>
> **Discovery.** [Before the multicast entitlement is granted:] DLNA and Roku devices are added by IP address; automatic discovery is limited to PlayBridge TV, Google Cast and AirPlay. [After it is granted: delete this paragraph.]

### tvOS notes

> PlayBridge TV is the receiver for the PlayBridge iPhone app. It plays video that the user sends from their iPhone, so it needs the iPhone app ([App Store link or "submitted together"]).
>
> **How to test**
> 1. Open PlayBridge TV on the Apple TV and allow Local Network access. It shows a pairing screen.
> 2. On an iPhone on the same Wi-Fi network, open PlayBridge, tap the device picker and choose the Apple TV. Confirm that the code on both screens matches.
> 3. On the iPhone, open [sample page URL] and cast the video. It plays on the Apple TV; use the Siri Remote or the iPhone to control it.
> 4. A screen recording of this flow is here: [recording link].
>
> Settings lets the user choose the player engine (AVPlayer or MPV), turn off cast history and read the privacy policy address. The app plays only media the user sends from their own iPhone; it has no built-in content catalog or web browser.

## 3. App Privacy answers (both apps)

**Do you or your third-party partners collect data from this app?** No → **Data Not Collected**.

Why this is accurate (Apple counts data as *collected* only when it leaves the device to the developer or its partners):

- No account, analytics, advertising or crash-reporting SDK, and no PlayBridge server.
- History, bookmarks, downloads, cast history, favorites and pairing data stay on the device (history on the TV is encrypted).
- Media and subtitle requests go to the servers the user browses to or casts from. The playback-progress webhook goes only to an HTTPS address the sender supplies for that cast (for example, the user's own media server). Neither goes to PlayBridge or a partner.

**Tracking:** No. Both privacy manifests already declare `NSPrivacyTracking = false` and no collected data types.

Re-answer this if a build ever adds analytics, crash reporting, accounts or a PlayBridge-run service.

## 4. Export compliance (both apps)

Both apps use encryption only through standard, published algorithms and protocols:

| Where | What |
|---|---|
| iOS and tvOS | HTTPS/TLS through Apple's URLSession and Network.framework |
| tvOS | TLS WebSocket server for paired phones; CryptoKit (X25519, HKDF, HMAC, AES-GCM) for pairing and encrypted history; swift-crypto and swift-certificates for its TLS certificate |
| iOS | CryptoKit for pairing and TLS certificate pinning; statically linked Rust TLS in Cast Core |
| Both | GnuTLS inside MPVKit for HTTPS media streams |

Suggested App Store Connect answers:

1. **Does your app use encryption?** Yes.
2. **Does it qualify for any of the exemptions in Category 5, Part 2?** It uses only standard encryption (no proprietary algorithms), for securing communications and protecting user data on the device. Choose the option for **standard encryption algorithms instead of, or in addition to, Apple's operating system**.
3. **Distribution in France:** check whether App Store Connect asks for a French encryption declaration for this choice, and file it if so.

After answering once, add `ITSAppUsesNonExemptEncryption` to each Info.plist with the value that matches the answer, so later uploads skip the question.

This is a reasoned draft, not legal advice. The rules are in [Apple's export compliance overview](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance) and the U.S. EAR (Category 5, Part 2, including §740.17). Confirm them before answering.
