# tvOS App Store Readiness Audit

**Audit date:** October 7, 2026

**Scope:** `tv/apple/PlayBridge TV`

**Status:** Not ready for App Store submission

Companion to [`ios-app-store-readiness-audit.md`](ios-app-store-readiness-audit.md). The September 9 source review ([`tv/apple/REVIEW-2026-09-09.md`](../tv/apple/REVIEW-2026-09-09.md)) covers receiver security and playback correctness. This document covers App Store submission only.

## Executive summary

The Release build for `generic/platform=tvOS` compiles and links (Xcode 26.4.1, unsigned). Icons, the App Store icon, Top Shelf images, local-network declarations and the privacy manifest are present. This audit fixed two source-side gaps: the in-app privacy policy entry and an undeclared required-reason API.

The remaining blockers need a paid developer account (signing, App Store Connect, upload validation), a deployed privacy page and testing on tvOS 17.

## Fixed in this audit

- **In-app privacy policy.** Settings now has an **About** section showing `playbridge.app/privacy` and the app version. tvOS has no web browser, so the URL is shown as text, and the rows are focusable so they scroll into view. Source: `UI/Views/SettingsView.swift`.
- **System boot time API declared.** The MPVKit (FFmpeg/mpv) frameworks reference `mach_absolute_time`. `PrivacyInfo.xcprivacy` now declares `NSPrivacyAccessedAPICategorySystemBootTime` with reason `35F9.1` (measuring elapsed time within the app). This matches the iOS manifest.

## October 8 update: GPL MPVKit resolved

The TV now links the same standard **LGPL** MPVKit 1.0.0 product as the phone, through Swift Package Manager. The GPL `mpv-ios/MPVKit` 0.41.0-av fork (whose FFmpeg was also built with `--enable-nonfree`), its locally patched AudioUnit driver and CocoaPods are gone. SwiftProtobuf now comes from SPM too, so open `PlayBridge TV.xcodeproj` directly.

- MPV renders with `vo=gpu-next` and uses AVFoundation audio, like the phone.
- The **Fix Embedded Framework Minimum OS** build phase clamps the 27 MPVKit frameworks from `MinimumOSVersion = 100.0` to the tvOS deployment target, as on iOS.
- The app bundles `Licenses/MPVKit-LICENSE.txt`, `MPVKit-NOTICE.txt` and `COPYING-GPL-3.0.txt`.
- HDR display switching now waits for mpv's decoded colorimetry. Before this fix, HDR files always played with the display in SDR mode.
- Verified on an Apple TV 4K (A15) over HDMI with a 2160p HEVC HDR10/Dolby Vision remux: VideoToolbox decoding, HDR10 display mode with correct colours, audible AVFoundation audio, smooth playback, and video resumed after returning from the Home Screen.

## October 8 update: privacy policy, Top Shelf @2x, deployment target

- **Privacy policy covers the TV.** The site's privacy page has a "Data on your Apple TV" section. It covers encrypted cast history and favorites (with **Save Cast History** and **Clear All**), paired-phone names and pairing verifiers, the TLS identity in the Keychain, media/subtitle/artwork fetches from sender-supplied addresses, and the cast-scoped progress webhook. Deploy the site before submitting.
- **@2x Top Shelf images added.** 3840×1440 and 4640×1440, rebuilt from the App Store icon's @2x layers on the same background. Scaled to 50% they match the 1x images (RMSE about 0.2%).
- **Deployment target lowered from tvOS 26.4 to 17.0.** At 15.0 the only build errors were two `onChange(of:initial:_:)` calls (tvOS 17). Every Apple TV that ran tvOS 15 can run 17, so going lower would not reach more hardware. Debug and Release build cleanly at 17.0, and the framework clamp now writes 17.0. The audience now includes Apple TV HD and all Apple TV 4K models on tvOS 17 or later. **Not yet tested on tvOS 17–18**: only the tvOS 26.4 simulator runtime is installed here.

## Must resolve before submission

### 1. Disk-space API without a declaration

The MPVKit media frameworks reference `fstatfs`, which Apple lists under `NSPrivacyAccessedAPICategoryDiskSpace`. The PlayBridge Swift code does not use it. mpv calls it to detect network filesystems, and none of Apple's disk-space reason codes describes that use, so it is intentionally undeclared. The iOS binary has the same reference.

Upload a build and check Apple's email. If it reports `ITMS-91053` for the disk-space category, add the closest accurate reason or remove the code path from the media build.

## App Review risks

### Minimum functionality and reviewer access (Guidelines 2.1 and 4.2)

The TV app is a receiver and does nothing useful without a sender. In the review notes:

- Explain the pairing flow and link the iOS app. Submit both apps together, or the iOS app first, so the reviewer can pair.
- Provide a controlled, rights-cleared sample stream URL and step-by-step instructions.
- Consider attaching a short screen recording of pairing and playback.

### Content rights

The app plays arbitrary URLs sent by the phone, including HTTP media allowed by `NSAllowsArbitraryLoadsForMedia`. Answer the Content Rights question accurately. Present the app as a player for content the user is authorized to access.

### App Transport Security

`NSAllowsArbitraryLoadsForMedia` and `NSAllowsLocalNetworking` are scoped exceptions. Be ready to explain that users' own media servers and IPTV sources often use plain HTTP.

## Assets

| Asset | Status |
|---|---|
| App Icon (layered), 400×240 and 800×480 | Present; back layer opaque |
| App Store icon (layered), 1280×768 | Present; back layer opaque |
| Top Shelf Image, 1920×720 and 3840×1440 | Present |
| Top Shelf Image Wide, 2320×720 and 4640×1440 | Present |
| Screenshots, 1920×1080 or 3840×2160 | Create in App Store Connect |

## App Store Connect checklist

- Register the bundle ID `com.playbridge.PlayBridge-TV`, then create distribution certificates and profiles.
- Add the Support URL and Privacy Policy URL.
- Complete the App Privacy disclosures. The app stores no account data, has no analytics and does no tracking.
- Answer the age-rating questionnaire. There is no built-in browser, but the app plays user-supplied media.
- Complete the Content Rights declaration.
- Answer the export-compliance questions. The app uses TLS for the WebSocket receiver, CryptoKit pairing (`SasCrypto.swift`) and TLS for webhooks. Do not set `ITSAppUsesNonExemptEncryption = NO` without confirming the exemption.
- Confirm EU trader status if distributing in the EU.
- Add review notes and test content (see App Review risks above). Draft text for the review notes, App Privacy and export compliance is in [`app-store-submission-drafts.md`](app-store-submission-drafts.md).

## Additional release notes

- **Dependencies are SPM only.** A clean checkout resolves MPVKit 1.0.0 (exact), SwiftProtobuf 1.38.x and swift-certificates from `PlayBridge TV.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`.
- **Build warnings.** The linker reports missing debug symbols in MPVKit's nettle objects (harmless). There is also one Swift concurrency warning in `Models/PairingCredentialState.swift:24`. Neither is a known submission blocker.
- **Physical-device tests** from the September 9 review are still open: pairing and revocation, preplay/favorites, engine switching during buffering, queue jumps, and HDR-to-SDR transitions. HDR10 playback, HDMI audio and Home Screen return passed on October 8.

## Verification completed

- Release build for `generic/platform=tvOS` with `CODE_SIGNING_ALLOWED=NO`, both before and after the changes above.
- The built bundle contains `PrivacyInfo.xcprivacy` with the system-boot-time declaration.
- Icon and Top Shelf sizes and opacity checked with `sips`.
- Required-reason symbol scan of the linked binary with `nm -u`.

## Verification limitations

Not done: a distribution-signed archive, App Store validation or upload, the full physical Apple TV test pass, or any App Store Connect configuration. Physical testing so far covers one 4K HDR file (see the October 8 update).

## Submission gate

- [x] Add an in-app privacy policy entry.
- [x] Declare the system boot time API in `PrivacyInfo.xcprivacy`.
- [x] Replace the GPL MPVKit fork with standard LGPL MPVKit 1.0.0 and verify on a real Apple TV.
- [x] Extend the privacy policy to cover the TV receiver and progress webhook.
- [ ] Deploy the updated privacy page.
- [x] Lower the deployment target (now tvOS 17.0).
- [ ] Test on tvOS 17 (simulator runtime or device) before submitting.
- [x] Add @2x Top Shelf images.
- [ ] Complete the App Store Connect items above.
- [ ] Run the physical Apple TV tests from the September 9 review.
- [ ] Create a distribution-signed archive and pass App Store validation. Check for `ITMS-91053` (disk space).
- [ ] Submit with review notes, a sample stream and an available iOS sender.
