# iOS App Store Readiness Audit

**Audit date:** September 22, 2026

**Scope:** `mobile/apple/PlayBridge Phone`

**Status:** Not ready for App Store submission

## Remediation update — September 23, 2026

- `PrivacyInfo.xcprivacy` now declares the app's UserDefaults, file-timestamp, and system-boot-time reasons. It was verified inside an unsigned Release archive; confirm it again in the distribution-signed archive.
- The iOS marketing version is now `0.3.3`, and the dashboard's `exit(0)` action has been removed.
- Browser settings now links to the privacy policy. The website policy source now describes browser, search, filter-list, receiver, and proxy traffic; deploy and review the live page before submission. The App Store Connect privacy-policy URL and disclosures still require account access.
- Release builds now check the linked app binary for Cast Core symbols. A clean build without Cast Core fails with an actionable error, while a build with the generated framework succeeds. The symbols were also confirmed in the unsigned archive's dSYM; archive stripping removes them from the distributed executable's symbol table. The release pipeline still needs to generate the framework before archiving.
- The multicast entitlement, media-download/content-rights decision, signed archive, account metadata, and physical-device tests remain open.

## Executive summary

The September 22 audit found two probable upload blockers, three likely App Review blockers, and several App Store Connect requirements that could not be verified from the repository alone. The source fixes above address several findings; this document remains a submission checklist, not approval to submit.

The highest-priority findings at audit time were:

1. Add an Apple privacy manifest covering the required-reason APIs used by the app.
2. Change the release marketing version from `0.3.3-alpha` to a valid three-part numeric version.
3. Remove the user-facing action that calls `exit(0)`.
4. Add an in-app privacy policy link and align the published policy with actual network behavior.
5. Properly provision the multicast entitlement used for DLNA/Roku discovery, or disable that discovery path in the App Store build.
6. Resolve the App Review risk created by downloading arbitrary third-party media.

## Must fix before submission

### 1. Add `PrivacyInfo.xcprivacy`

At audit time, no privacy manifest was present in the source project or inspected archive. The app uses APIs that Apple classifies as required-reason APIs:

- `UserDefaults` in `Data/BrowserDataStore.swift` around line 48.
- System uptime in `Browser/BrowserTab.swift` around lines 37, 42, and 76, with related use in browser interaction handling.
- File metadata and timestamps in `Data/PhoneMediaLibrary.swift` around line 73, `Browser/ContentBlocker.swift` around line 409, and `Network/LocalFileServer.swift` around line 93.

The manifest should be added to the phone target and included in the built application. Based on the current uses, the likely declarations are:

| API category | Likely reason code | Current use |
|---|---:|---|
| `NSPrivacyAccessedAPICategoryUserDefaults` | `CA92.1` | Accessing app-owned preferences |
| `NSPrivacyAccessedAPICategoryFileTimestamp` | `C617.1` | Accessing timestamps for files managed by the app |
| `NSPrivacyAccessedAPICategorySystemBootTime` | `35F9.1` | Measuring elapsed time within the app |

Confirm each reason against the final implementation and Apple's current list before submission. See [Describing use of required reason API](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api?changes=_2_8&language=objc).

### 2. Use a valid release version number

The Xcode project set `MARKETING_VERSION` to `0.3.3-alpha` in both configurations at audit time. `CFBundleShortVersionString` must contain three period-separated integers.

Use `0.3.3` for the binary. Put the alpha designation in TestFlight release notes, the build number, or other release metadata instead. See Apple's [version number definition](https://developer.apple.com/help/glossary/version-number/).

### 3. Remove `exit(0)`

At audit time, `DashboardScreen.swift` exposed an **Exit PlayBridge** action that called `exit(0)`. Apple explicitly advises iOS apps not to terminate themselves because it appears to the user as a crash.

Remove the action or replace it with normal navigation that leaves the app running. See [QA1561: How do I programmatically quit my iOS application?](https://developer.apple.com/library/archive/qa/qa1561/_index.html).

### 4. Add and correct the privacy policy

At audit time, the app did not provide an easily accessible in-app privacy policy link. Browser settings now includes one; App Store Connect still needs the policy URL in its metadata.

Add a privacy policy entry in Settings or another persistent, discoverable location. App Store Connect also requires the policy URL in the app metadata.

The policy live at audit time says the app runs entirely on the local network and that search, watch, and cast activity does not go to third parties. The website source has been corrected, but that wording remains live until deployment. The old claim is broader than the implementation supports:

- Searches can be sent to Google, DuckDuckGo, or Bing.
- Content-blocking lists can be downloaded from external hosts.
- Browsed websites receive ordinary web traffic and identifiers.
- A user-selected remote proxy or receiver can receive media and connection data.

The policy should distinguish between data PlayBridge itself collects and data necessarily sent to websites, search providers, receiver devices, or user-configured services. See [App Review Guideline 5.1.1](https://developer.apple.com/app-store/review/guidelines/).

### 5. Provision multicast discovery correctly

`mobile/apple/config/DLNAMulticast.entitlements` declares `com.apple.developer.networking.multicast`, but the phone target does not currently set `CODE_SIGN_ENTITLEMENTS`. The local DLNA documentation also notes that the target does not have the entitlement.

Before distributing DLNA/Roku SSDP discovery:

1. Request Apple's restricted multicast networking entitlement.
2. Enable it for the App ID and distribution provisioning profile.
3. Set the phone target's Code Signing Entitlements path to `../config/DLNAMulticast.entitlements`.
4. Verify discovery on a physical device with the distribution-signed build.

If the entitlement will not be available for the initial release, hide or disable automatic SSDP discovery in the App Store configuration. See Apple's [Local Network Privacy FAQ](https://developer.apple.com/news/?id=0oi77447).

## High-risk App Review areas

### Arbitrary third-party media downloads

The browser exposes a **Download Link** action for arbitrary media URLs in `Browser/BrowserTab.swift` around lines 411–435 and 501–511. Downloaded media is persisted and can be imported into the phone media library.

This creates a significant Guideline 5.2.3 risk. Apple may require evidence that the app is authorized to download or save third-party audio and video.

Before submission, choose one of these approaches:

- Remove or restrict downloads to content the user owns or is authorized to save.
- Limit the feature to user-provided media and clearly explain that positioning in the UI and review notes.
- Provide documented authorization for any supported third-party sources.

The App Store Connect **Content Rights** answer must accurately describe this behavior. Give App Review a controlled sample source and precise steps that do not depend on copyrighted third-party media. See [App Review Guidelines 5.2.2 and 5.2.3](https://developer.apple.com/app-store/review/guidelines/).

### Unrestricted web access and age rating

Because PlayBridge contains a general-purpose browser, App Store Connect should identify it as providing unrestricted web access. Under the current rating system, this is expected to produce at least a 16+ rating. Reconfirm the questionnaire at submission time using Apple's [age-rating definitions](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions).

## App Store Connect checklist

The following items cannot be confirmed from source code and must be completed in App Store Connect or the developer account:

- Add the Support URL and Privacy Policy URL.
- Complete App Privacy disclosures based on the final build and privacy policy.
- Mark unrestricted web access in the age-rating questionnaire.
- Complete the Content Rights declaration accurately.
- Confirm EU Digital Services Act trader status if distributing in the EU.
- Complete export-compliance questions.
- Add review notes, test content, and any receiver setup required to exercise the app.
- Upload a distribution-signed archive and run App Store validation.

### Export compliance

The app uses CryptoKit X25519, AES-GCM, HKDF, and HMAC in `SasCrypto.swift`, and it includes a statically linked Rust TLS implementation. The absence of `ITSAppUsesNonExemptEncryption` is not itself a source-code blocker because App Store Connect can ask the export-compliance questions during submission. Do not set the value to `NO` without confirming the applicable exemption and any documentation requirements, including French declarations where relevant.

See Apple's [overview of export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance).

### App Transport Security

`Info.plist`, around line 26, enables `NSAllowsArbitraryLoadsInWebContent`. This may be appropriate for a general-purpose browser, but App Review can ask for a justification. Document why web content needs it and keep the exception limited to WebKit.

The IPTV implementation accepts both HTTP and HTTPS URLs, while non-WebKit public HTTP requests can still be blocked by ATS. This is a functionality and review risk for public HTTP playlists or media. Do not add a global ATS exception without a specific product need and defensible review explanation.

See [`NSAllowsArbitraryLoadsInWebContent`](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowsarbitraryloadsinwebcontent).

## Additional release risks

### Native Cast Core artifact

`mobile/apple/Native/PlayBridgeCastCore.xcframework` is ignored by Git. Clean Debug builds use the native adapter's unavailable stub; `cast/build-apple.sh` generates the framework and optional Xcode configuration that enable Google Cast, DLNA, and Roku. Release builds now fail if Cast Core symbols are absent from the app binary before stripping. The release pipeline must still generate the framework before archiving and verify the distribution archive and its dSYM.

### Background audio

The app declares the `audio` background mode and appears to use it for real media playback. Keep the entitlement limited to active playback and explain the behavior in review notes if requested.

### Swift concurrency warnings

The current build succeeds, but warnings indicate future Swift 6 migration problems:

- `NSLock` use from asynchronous contexts in `ContentBlocker`.
- A non-`Sendable` `AVAssetExportSession` capture.
- Non-`Sendable` captures in `GoogleCastController`.
- A captured mutable `resumed` value in `LocalFileServer`.
- An unused `lists` value.

These warnings are not known blockers for the current Swift language mode, but they should be resolved before enabling strict Swift 6 concurrency checking.

Simulator logs also report Poppins font-weight descriptor warnings. Confirm the bundled font faces and rendered weights on a physical device.

## Verification completed

The following checks passed during this audit:

- Release simulator build with Xcode 26.4.1 and the iOS 26.4 SDK.
- Xcode static analysis, with the warnings described above.
- Unsigned device archive creation; the application binary contains the `arm64` architecture.
- Fresh install and launch on an iPhone 17e simulator running iOS 26.4.
- Fresh install and launch on an iPad Pro 11-inch M5 simulator running iOS 26.4.
- The 1024×1024 App Store icon is present and has no alpha channel.
- Web content uses WebKit rather than a custom browser engine.
- Local Network and Photos purpose strings are present.
- No obvious private API, tracking SDK, account-deletion, Sign in with Apple, or in-app-purchase issue was found.

The following project checks also passed:

- `run-fixture-checks.sh`
- `run-remote-control-checks.sh`
- `run-cast-history-checks.sh`
- `run-google-cast-checks.sh`
- `run-saved-receiver-checks.sh`
- `run-stream-route-checks.sh`
- `run-browser-startup-checks.sh`
- `run-media-library-checks.sh`

The build uses a current supported SDK. Recheck Apple's [upcoming submission requirements](https://developer.apple.com/news/upcoming-requirements/?id=04282026a) immediately before release.

## Verification limitations

This audit did not include:

- A distribution-signed archive or App Store upload validation.
- Testing on a physical iPhone or iPad.
- End-to-end testing with real PlayBridge, Google Cast, DLNA, or Roku receivers.
- Photos and iCloud permission flows on a physical device.
- Developer-account verification of App IDs, entitlements, certificates, provisioning profiles, or App Store Connect metadata.
- Legal determination of content-download rights or export-control classification.

## Submission gate

Do not submit the iOS build until all applicable items are complete:

- [x] Add `PrivacyInfo.xcprivacy` and verify it in an unsigned Release archive.
- [ ] Verify `PrivacyInfo.xcprivacy` in the distribution-signed archive.
- [x] Change `MARKETING_VERSION` to a valid numeric version.
- [x] Remove the `exit(0)` UI and behavior.
- [x] Add an in-app privacy policy link.
- [ ] Deploy the corrected privacy policy and verify the live page.
- [ ] Provision the multicast entitlement or disable automatic SSDP discovery.
- [ ] Resolve and document the media-download/content-rights position.
- [ ] Complete App Privacy, age rating, Content Rights, export compliance, and EU trader declarations.
- [x] Make a Release build fail when Cast Core symbols are absent.
- [ ] Build Cast Core in the clean release pipeline and verify its symbols in the distribution archive's dSYM.
- [ ] Test the release candidate on physical iPhone and iPad hardware.
- [ ] Test against representative real receivers and permission flows.
- [ ] Create a distribution-signed archive and pass App Store validation.
