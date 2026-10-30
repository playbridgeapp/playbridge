# App Store submission drafts

Paste-ready text for the steps that need the paid Apple Developer account. Replace every `[bracketed]` placeholder before sending. The readiness audits explain the reasoning behind each answer: [iOS](ios-app-store-readiness-audit.md), [tvOS](tvos-app-store-readiness-audit.md).

| App | Bundle ID | Version (build) |
|---|---|---|
| PlayBridge Phone (iOS) | `app.playbridge.phone` | 0.15.0 (8) |
| PlayBridge TV (tvOS) | `app.playbridge.tv` | 0.15.0 (8) |

Version numbers come from the Xcode projects at the time of writing; use the values of the build you upload.

## 1. Multicast Networking entitlement request (iOS only)

**Granted in October 2026;** `PB_MULTICAST_ENTITLEMENT` is `YES`. The request text is kept for reference. Form: <https://developer.apple.com/contact/request/networking-multicast>. Only the iPhone app needs it. The Apple TV app and Bonjour discovery (PlayBridge TV receivers and Google Cast, through `NetServiceBrowser`) do not.

After approval, set `PB_MULTICAST_ENTITLEMENT = YES` in `mobile/apple/PlayBridge Phone/MulticastEntitlement.xcconfig` (see [`mobile/apple/docs/dlna.md`](../mobile/apple/docs/dlna.md)).

**App name:** PlayBridge

**Bundle ID:** app.playbridge.phone

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
> 2. Open this sample page in the browser tab: https://playbridge.app/cast-demo/. Play the video, then tap the cast button.
> 3. Pick a receiver: an Apple TV running PlayBridge TV, a Google Cast device, AirPlay, or a DLNA/Roku device. To play on the iPhone itself instead, choose **Play on Phone** in the cast sheet.
> 4. A screen recording of pairing with the Apple TV app and casting is here: [recording link].
>
> **Background audio.** PlayBridge is a remote control for video playing on a TV or other receiver on the user's network. During an active cast, PlayBridge keeps running in the background so the user can control the TV from the lock screen and Control Center, and so the phone can keep serving media and advancing episodes that the TV depends on (local files, DLNA renderers, website queues). This stops when the cast ends or after five minutes paused.
>
> **Website integration.** Websites can call `window.playbridge` to send their own video to the user's TV. It exposes only PlayBridge's casting and playback controls, not device APIs. Each website must be approved by the user before it can see or use receivers, and choosing a receiver always requires a tap. Users can save such websites as shortcuts ("Bridged Apps"); the app ships no catalog of them.
>
> **Downloads.** Like Safari, the browser downloads a file only when a website serves it as a download, after the user confirms. There is no button to download detected video streams.
>
> **Streaming services.** PlayBridge doesn't detect or cast video on YouTube or paid streaming and music services (Netflix, Prime Video, Disney+, Hulu, Max, Apple TV+, Paramount+, Peacock, Spotify, Apple Music). Those sites still open and play normally on the iPhone; the cast button explains that the site doesn't allow casting.
>
> **Playlists.** Users can add their own M3U playlists, for example from their own media server (Jellyfin, Plex) or a network TV tuner (HDHomeRun). PlayBridge doesn't provide, suggest or link to any playlists or channels.
>
> **Discovery.** PlayBridge TV and Google Cast receivers are found with Bonjour. DLNA, Roku and DIAL devices are found with SSDP while the device picker is open, using the multicast networking entitlement Apple granted for this app.

### tvOS notes

> PlayBridge TV is the receiver for the PlayBridge iPhone app. It plays video that the user sends from their iPhone, so it needs the iPhone app ([App Store link or "submitted together"]).
>
> **How to test**
> 1. Open PlayBridge TV on the Apple TV and allow Local Network access. It shows a pairing screen.
> 2. On an iPhone on the same Wi-Fi network, open PlayBridge, tap the device picker and choose the Apple TV. Confirm that the code on both screens matches.
> 3. On the iPhone, open https://playbridge.app/cast-demo/ and cast the video. It plays on the Apple TV; use the Siri Remote or the iPhone to control it.
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

Because MPVKit ships GnuTLS, the apps use standard encryption that Apple's operating system doesn't provide. That rules out declaring them exempt. Apple's [documentation table](https://developer.apple.com/help/app-store-connect/reference/app-information/export-compliance-documentation-for-encryption/) maps this case to one document: a French encryption declaration, needed only for distribution in France. A CCATS is needed only for proprietary algorithms.

App Store Connect answers:

1. **Does your app use encryption?** Yes.
2. **Algorithm type:** **Standard encryption algorithms instead of, or in addition to, using or accessing the encryption within Apple's operating system.**
3. **Available in France?** No, until the declaration to ANSSI (France's cybersecurity agency) is filed. Leave France and the French overseas territories out of Pricing and Availability until then. Also leave out **China mainland**: distribution there needs an ICP filing number, which requires a Chinese business entity. To add France, file the declaration ([ANSSI](https://cyber.gouv.fr/controle-relatif-un-moyen-de-cryptologie); Debian's is a [public example](https://www.debian.org/legal/anssi.fr.html)) and upload ANSSI's receipt under App Information → App Encryption Documentation.

**United States:** a free consumer app using only standard cryptography is "mass market". Since the [March 2021 EAR rule](https://www.federalregister.gov/documents/2021/03/29/2021-05481/export-administration-regulations-implementation-of-wassenaar-arrangement-2019-plenary-decisions), it needs no annual self-classification report, and open source code using standard cryptography needs no email notification to BIS.

**Info.plist:** the apps deliberately do **not** declare `ITSAppUsesNonExemptEncryption`. With the key set to `YES`, App Store Connect rejects the upload (ITMS-90592, "Invalid Export Compliance Code") unless an `ITSEncryptionExportComplianceCode` from approved documentation accompanies it, and this case has no documentation and therefore no code. `NO` would skip the question but would be inaccurate. So each uploaded build shows **Missing Compliance** once in App Store Connect (TestFlight → the build → Manage); answer it as above. A future CI upload can set the answer through the App Store Connect API.

This is a reasoned draft, not legal advice. Check Annex 1 of French decree 2007-663 or ask ANSSI (`controle@ssi.gouv.fr`) before distributing in France.

## 5. Store listing text

Limits: subtitle 30 characters, promotional text 170, keywords 100 (comma-separated, no spaces after commas), description 4,000. Words in the app name don't need repeating in keywords. Keywords avoid other companies' trademarks, which App Review rejects there; the description may name compatible devices.

### iPhone: PlayBridge: Cast to TV

**Subtitle:** Cast web video to any TV

**Promotional text:**

> Find a video in the built-in browser, tap cast, and watch it on Apple TV, Google Cast, AirPlay or DLNA TVs. No account and no cloud: it all stays on your Wi-Fi.

**Keywords:**

```
stream,video,player,dlna,upnp,browser,remote,playlist,m3u,receiver,smart,hls,mpv,subtitles,media
```

**Description:**

```
PlayBridge sends the video you find on your iPhone to the big screen. Browse to a video, tap the cast button and pick your TV. Everything happens on your own Wi-Fi network: no account, no sign-up and no cloud servers.

CAST TO THE TV YOU HAVE
• Apple TV with the free PlayBridge TV app, paired once with a 6-digit code
• Google Cast devices and AirPlay
• DLNA and UPnP smart TVs, with nothing to install on the TV
• Or play on the iPhone itself with the built-in player

A BROWSER BUILT FOR CASTING
• Finds the videos on a web page, including HLS and DASH streams
• Built-in blocker for ads and trackers
• Tabs, bookmarks, history and collections

YOUR OWN MEDIA
• Cast videos from your photo library and the Files app
• Load your own M3U playlists

A REMOTE IN YOUR POCKET
• Play, pause, seek and change the volume from the app, the lock screen or Control Center
• A D-pad and touchpad for PlayBridge receivers

PRIVATE BY DESIGN
• No account, no ads, no analytics and no tracking
• Your history and settings stay on your devices
• Open source

PlayBridge doesn't provide any content. Use it only with media you have the right to watch.
```

### Apple TV: PlayBridge TV

**Subtitle:** Play videos from your iPhone

**Promotional text:**

> Send videos from your iPhone to your Apple TV. Pair once with a 6-digit code, then cast with a tap. Plays with AVPlayer or MPV. No account and no cloud.

**Keywords:**

```
receiver,video,player,stream,phone,mpv,subtitles,hls,remote,playback,cast,mkv,local,wifi
```

**Description:**

```
PlayBridge TV turns your Apple TV into a receiver for the free PlayBridge iPhone app. Find a video on your iPhone, tap cast and it plays here, over your own Wi-Fi network, with no account.

• Secure pairing: confirm a 6-digit code once, and your iPhone reconnects by itself
• Two player engines: Apple's AVPlayer, or MPV for formats and codecs AVPlayer can't play
• Subtitles, including subtitle files sent from your iPhone
• Control playback with the Siri Remote or from your iPhone
• An "Are you still watching?" prompt so playback doesn't run all night
• Cast history you can turn off, stored encrypted on the Apple TV
• No account, no ads, no analytics and no tracking
• Open source

Needs the PlayBridge iPhone app on the same Wi-Fi network. PlayBridge TV doesn't provide any content; it plays only what you send it.
```

### Both apps

- **Support URL:** `https://playbridge.app/support` (a plain support page, so App Review doesn't land on the repository first)
- **Marketing URL:** `https://playbridge.app`
- **Copyright:** `2026 [your name]`
- **Privacy Policy URL:** `https://playbridge.app/privacy`

## 6. Screenshots

| Device | Size (portrait unless noted) | Count |
|---|---|---|
| iPhone 6.9" | 1320 × 2868 | 3 to 6 |
| iPad 13" (the iPhone app also runs on iPad) | 2064 × 2752 | 3 to 6 |
| Apple TV | 1920 × 1080 landscape | 3 to 5 |

Rules: show the real app; use only rights-cleared video (Blender Foundation open movies such as Big Buck Bunny, Sintel or Tears of Steel); no third-party websites, logos or personal device names; status bar at 9:41 with full battery (`xcrun simctl status_bar … override`). Captions above the screenshot are allowed.

**iPhone and iPad shot list**

1. Browser on a neutral page with a detected video and the cast sheet open. *Find a video. Tap cast.*
2. Device picker listing an Apple TV, a Google Cast device, AirPlay and a DLNA TV. *Cast to the TV you already have.*
3. Remote during playback: title, seek bar, volume line. *A remote in your pocket.*
4. Built-in phone player playing an open movie. *Or watch right on your iPhone.*
5. Phone Files with a few local videos. *Your own videos, too.*
6. Content blocker settings. *No account. No tracking.*

**Apple TV shot list**

1. Pairing screen with its 6-digit code. *Pair once with your iPhone.*
2. Player with the controls overlay over an open movie. *Plays what you cast.*
3. Settings showing the AVPlayer and MPV engines. *Two player engines.*
4. Ready / waiting screen. *Always ready to receive.*
