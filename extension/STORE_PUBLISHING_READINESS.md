# Browser extension store publishing readiness

_Audit date: 2026-07-13_

This document records the current publishing findings for the PlayBridge
Firefox add-on and Chrome extension. It is a release checklist, not a guarantee
of approval: Mozilla and Google can request additional changes during review.

## Executive summary

The extension-side policy and packaging blockers identified in this audit have
been addressed. The release workflow still needs to be aligned with the new
store checks and with **listed** Firefox distribution before the next version
bump. The remaining work also requires store accounts, a release-version
decision, production identifiers, public reviewer resources, listing assets, or
manual browser/TV verification. Do not submit the current `0.4.2` artifacts as a
release until those actions are complete.

| Target | Current state | Main blockers |
| --- | --- | --- |
| Firefox Add-ons (AMO) | Extension ready; CI/listing inputs pending | Change CI from unlisted signing to validated artifacts, choose/bump release version, complete reviewer setup and assets, run manual matrix, submit a listed version |
| Chrome Web Store | Extension ready; production ID and listing inputs pending | Create draft item, add production ID to desktop native host, choose/bump release version, host privacy URL, create assets, complete dashboard and manual matrix |

### Repository action required before the next version bump

Update `.github/workflows/extension_build.yml` before merging the release
version. The current workflow:

- correctly detects a Firefox manifest version change on a push to `main`;
- runs `pnpm build`, but not the complete `pnpm store:check` verification;
- signs Firefox with `web-ext sign --channel unlisted`, which creates a
  self-distributed XPI rather than a public AMO-listed release;
- creates a Chrome ZIP and GitHub release, but does not submit it to the Chrome
  Web Store;
- does not preserve the deterministic Firefox ZIP, Chrome ZIP, AMO source ZIP,
  and checksums created by `pnpm store:check`.

Separate the workflow into build and publication concerns:

1. Run `pnpm install --frozen-lockfile` and `pnpm store:check` for the release
   commit.
2. Upload the exact contents of `extension/artifacts/` as immutable workflow and
   GitHub release artifacts.
3. Do not run the current `--channel unlisted` command for a public release.
4. Keep store upload/submission disabled until the initial Chrome and AMO items
   exist and their identifiers, metadata, and credentials are available.
5. When store automation is added, use a protected GitHub environment or manual
   approval gate. Prefer automating upload of a draft first and retaining a
   human decision to submit it for review.

The current workflow compares the Firefox version at `HEAD` with `HEAD^`.
Until that detection is made robust for multi-commit pushes, make the version
bump the final/squashed release commit merged to `main`.

## Owner actions still required

These items cannot be completed safely from the repository alone:

1. **Install and test a desktop build containing the Chrome Web Store ID.** The
   draft ID `gofdcnocpnieoonficfnfccolcocoaim` is now present in
   `desktop/lib/native_host_installer.dart` (`chromeExtIds`) and
   `desktop/native_messaging/com.playbridge.host.chrome.json`
   (`allowed_origins`). Test a store-installed extension, then copy the
   store-issued public key only if the source manifest will retain a stable
   `key`.
2. **Choose the next extension version.** Request an explicit release/uprev with
   the chosen version so both manifests and `CHANGELOG.md` are updated together.
   Do not upload the generated `0.4.2` artifacts as the new release.
3. **Host and verify the privacy policy.** Confirm
   `https://github.com/playbridgeapp/playbridge/blob/main/extension/PRIVACY.md`
   is public after these changes merge, then enter it in both dashboards.
4. **Capture genuine listing assets.** Use a clean release profile with no
   private streams, headers, IPs, PINs, or tokens. Produce at least one 1280x800
   Chrome screenshot and a 440x280 small promo tile; refresh the AMO screenshots.
5. **Complete reviewer setup placeholders.** Replace the bracketed values in
   `STORE_LISTING_COPY.md` with a public desktop build URL, pairing/test-TV
   instructions, and a public test stream/page. Put any unavoidable credentials
   only in the stores' private reviewer fields.
6. **Run the manual matrix.** Test clean install/update, consent/revocation,
   production native messaging, media formats, protected headers, frames, SPA,
   fullscreen, private browsing, desktop/TV error states, and successful casting
   on supported Firefox and Chrome versions.
7. **Complete the store accounts.** Fill the prepared dashboard fields, select
   distribution/category, restrict the AMO listing to Firefox desktop because
   Firefox Android cannot use the desktop native-messaging host, verify support
   contact and two-step verification, then upload artifacts created from the
   final release commit with `pnpm store:check`.

## What is already in good shape

- Separate Firefox MV2 and Chrome MV3 manifests are generated into
  `dist/firefox` and `dist/chrome`.
- Both targets use bundled local code and fonts; the extension does not load or
  execute remote code.
- The popup, background script, and all-frame content script are present in both
  builds.
- The Firefox add-on has a stable Gecko ID:
  `video-detector@playbridge`.
- A 128x128 PNG icon is present and included in both packages.
- The privacy policy now describes media URLs, request headers, video metadata,
  session storage, and local per-site preferences.
- Firefox 140+/Android 142+ declares `browsingActivity` and `websiteContent`
  through Firefox's built-in consent experience.
- Chrome starts media handling disabled until the user accepts a full-page
  disclosure; revocation clears detections and disables media/overlay paths.
- Store validation, zero-warning Firefox lint, deterministic binary/source ZIPs,
  and SHA-256 checksums are produced by `pnpm store:check`.
- `STORE_LISTING_COPY.md` contains dashboard permission justifications, privacy
  disclosures, and reviewer-note templates.
- The extension has a narrow user-facing purpose: detect media in the current
  browser tab and cast the selected stream through PlayBridge.
- Current verification passes:
  - `pnpm typecheck`
  - `pnpm test` (23 tests)
  - `pnpm store:check` (Firefox/Chrome build, validation, zero-warning Firefox
    lint, deterministic packaging, and checksums)

## First submission and automation strategy

Automate validation and artifact generation now, but make the first **listed**
submission to each store through its developer dashboard. This creates the
permanent store records, exposes all current declaration fields, and allows any
initial reviewer feedback to be incorporated before CI is given publication
credentials.

The recommended boundary is:

- **CI may build immediately:** install locked dependencies, run
  `pnpm store:check`, retain checksums, and create a GitHub release.
- **The owner performs the first submissions:** create the store accounts/items,
  complete listing/privacy/reviewer fields, choose visibility, and submit the
  final artifacts for review.
- **CI may automate later updates:** after both items are approved, configure
  the AMO and Chrome Web Store APIs to upload new versions. Keep submission or
  publication behind a protected environment/manual approval.

For Firefox, do not treat the XPI generated by the current CI as the public AMO
submission. `--channel unlisted` is for self-distribution. A public AMO version
must use the **listed** channel, either through the Developer Hub or through
`web-ext sign --channel listed` with the required metadata. Do not try to reuse
one version number for separate listed and unlisted release submissions.

For Chrome, create the draft item before publication automation. The draft
provides the permanent item/extension ID needed by the Web Store API and by the
PlayBridge native-messaging allowlist. Store upload and publication are separate
operations; an upload can remain a draft until the listing and testing are
complete.

Official references:

- [Mozilla: submitting an add-on](https://extensionworkshop.com/documentation/publish/submitting-an-add-on/)
- [Mozilla: `web-ext sign` listed and unlisted channels](https://extensionworkshop.com/documentation/develop/web-ext-command-reference/)
- [Chrome: register a developer account](https://developer.chrome.com/docs/webstore/register)
- [Chrome: first publication](https://developer.chrome.com/docs/webstore/publish/)
- [Chrome Web Store API](https://developer.chrome.com/docs/webstore/using-api)

## Blockers shared by both stores

### 1. Prepare an actual release version

Both manifests still report `0.4.2`, while the current code contains substantial
overlay, media-association, SPA detection, privacy, and test changes that are not
in the `0.4.2` changelog entry.

Before submission:

1. Choose the release version.
2. Update both manifests to exactly the same version.
3. Add the release notes to `CHANGELOG.md`.
4. Build from the release commit and do not alter the generated packages after
   verification.

This is mandatory for an update when a store already has version `0.4.2`. For a
first submission it is still required release hygiene.

### 2. Deterministic packaging — fixed

Run `pnpm store:check`. It now cleans build output and produces:

- `playbridge-extension-firefox-<version>.zip`, with `manifest.json` at the ZIP
  root;
- `playbridge-extension-chrome-<version>.zip`, with `manifest.json` at the ZIP
  root;
- an AMO source archive with the source, lockfile, build instructions, and the
  generated protocol inputs required by the build.

The command runs type-check/tests, builds both targets, validates the manifests,
runs zero-warning AMO lint, creates deterministic archives, and writes
`artifacts/SHA256SUMS.txt`. The artifacts remain versioned `0.4.2` until the
release version is chosen.

### 3. Provide reviewer setup and test instructions

Most casting behavior depends on the separately installed PlayBridge desktop
app and native-messaging host. Store reviewers need:

- a supported desktop test build or precise installation instructions;
- steps for registering `com.playbridge.host`;
- a simple page/stream that demonstrates detection;
- expected behavior when the desktop app is absent, connected without a TV,
  and connected to a TV;
- an explanation that captured media headers are sent only when the user casts.

The public store descriptions must also state prominently that the PlayBridge
desktop app is required for casting.

### 4. Complete real-browser release testing

Automated tests do not exercise browser permission prompts, WebRequest behavior,
native messaging, fullscreen, or store-installed IDs. Before submission, test a
clean profile on the oldest supported browser and current stable Firefox/Chrome:

- install/update behavior and permission prompts;
- popup detection and sorting;
- direct MP4/WebM, HLS, DASH, and MSE/blob-backed players;
- protected streams that need Referer, Cookie, or Authorization headers;
- same-origin and cross-origin iframes;
- SPA navigation and dynamically inserted/replaced videos;
- all five overlay positions, global off, per-site off, reset, and persistence;
- fullscreen and picture-in-picture interactions;
- desktop absent, desktop without a TV, and successful casting;
- private/incognito behavior and data cleanup.

## Firefox Add-ons findings

### Firefox data-collection declarations and consent — fixed

New AMO extensions submitted after 2025-11-03 must use Firefox's built-in data
collection declaration. The Firefox manifest now declares the required
categories and targets Firefox desktop 140+/Android 142+.

This finding assumes PlayBridge is creating a new AMO listing now. Mozilla says
updates to add-ons created before 2025-11-03 do not yet have to adopt the system,
although they will be required to later. Confirm the AMO item creation date if a
listing already exists.

PlayBridge must not declare `none`. Mozilla treats data handled outside the
add-on or local browser—including data sent through native messaging—as data
transmission. PlayBridge sends the user-selected media URL and may send request
headers to the companion app. Based on Mozilla's current taxonomy, the expected
required declarations are:

```json
"data_collection_permissions": {
  "required": ["browsingActivity", "websiteContent"]
}
```

`browsingActivity` covers URLs/domains, while `websiteContent` covers videos,
cookies, page/request headers, and request/response information. Confirm the
final categories in the AMO submission, especially because captured headers can
contain Cookie or Authorization values.

Firefox's built-in consent UI is available from Firefox 140. The implemented
release path is:

1. `strict_min_version` is `140.0` and Firefox Android is `142.0`;
2. required data-collection permissions are declared;
3. the privacy/listing copy uses the same descriptions;
4. the owner must still verify the real install prompt in Firefox 140 and
   current stable before upload.

Keeping support for Firefox 102–139 instead requires an unmissable custom
consent experience before transmission, which is substantially more work.

References:

- [Firefox built-in data collection consent](https://extensionworkshop.com/documentation/develop/firefox-builtin-data-consent/)
- [Firefox Add-on Policies: data collection and native messaging](https://extensionworkshop.com/documentation/publish/add-on-policies/)
- [MDN `browser_specific_settings`](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/browser_specific_settings)

### Reproducible source archive — fixed

The production build is bundled/minified with esbuild, so AMO requires source
and exact build instructions. `package.json` now pins pnpm, the unused protocol
dependency/path has been removed, stale JavaScript source files have been
deleted, `AMO_SOURCE_BUILD.md` documents the clean build, and
`pnpm store:package` creates a self-contained deterministic source ZIP.

Reference: [AMO source code submission requirements](https://extensionworkshop.com/documentation/publish/source-code-submission/).

### Review risk: broad permissions need precise explanations

`<all_urls>`, `webRequest`, `webRequestBlocking`, `tabs`, and
`nativeMessaging` are powerful but connected to the stated casting purpose.
Reviewer notes should explain:

- all-site access is required because media can appear on any user-visited site;
- WebRequest observes media requests and captures only headers needed to replay
  a selected stream;
- `webRequestBlocking` enables Firefox response-body filtering used to identify
  HLS playlists and is not used to modify site traffic;
- `tabs` and `webNavigation` associate detections with the correct tab/frame and
  reset/re-evaluate them on navigation;
- native messaging sends a user-selected stream to the local PlayBridge app.

The redundant `activeTab` permission has been removed. Mozilla still requires
the remaining powerful permissions to be explained in the AMO reviewer notes;
prepared copy is in `STORE_LISTING_COPY.md`.

### Firefox first listed submission instructions

Use the AMO Developer Hub for the first public submission. This is recommended
even though current `web-ext` versions can create a listed item through the API,
because the first version needs listing metadata and may expose new policy or
compatibility questions.

1. Sign in to AMO with the Mozilla Account that will permanently own the add-on.
   Open the Add-ons Developer Hub, accept the current developer agreement, and
   review the add-on policies.
2. Finish the public privacy-policy URL, screenshots, reviewer test path, and
   desktop download/setup instructions before creating the release version.
3. Update the release workflow so it runs `pnpm store:check` and does not sign
   the public release with `--channel unlisted`.
4. Choose the release version. Update `manifests/firefox.json`,
   `manifests/chrome.json`, and `CHANGELOG.md` together, then merge the final
   release commit to `main`.
5. From that exact commit, run or download the results of `pnpm store:check`.
   Verify `artifacts/SHA256SUMS.txt` and retain:
   - `playbridge-extension-firefox-<version>.zip`;
   - `playbridge-extension-source-<version>.zip`;
   - `AMO_SOURCE_BUILD.md` as the reviewer build guide contained in the source
     archive.
6. In the Developer Hub, choose **Submit a New Add-on** and select **On this
   site**. That is the public/listed AMO distribution path; do not choose **On
   your own**, which is self-distribution/unlisted.
7. Upload the Firefox binary ZIP. When asked whether source code is required,
   answer yes and upload the separate source ZIP because the production scripts
   are generated/bundled by esbuild.
8. Complete the name, summary, description, category, license, support/homepage
   URLs, privacy-policy URL, screenshots, release notes, and desktop-only
   compatibility. Use `STORE_LISTING_COPY.md` as the prepared source text.
9. In Notes for Reviewers, include the desktop/native-host setup, public test
   page or stream, expected disconnected/connected states, and an explanation
   of why all-site, WebRequest, tabs/navigation, and native-messaging permissions
   are needed. Never place private tokens or production credentials in public
   listing fields.
10. Confirm that the AMO data declarations match the manifest and policy:
    `browsingActivity` and `websiteContent`. Clearly state that casting requires
    the PlayBridge desktop app and that Firefox Android cannot use its desktop
    native-messaging host.
11. Submit for review and preserve the exact ZIPs and checksums. If Mozilla asks
    for changes, make a focused change and increment the version; do not replace
    a submitted package in place.

After the first listed version is approved, later uploads may be automated with
the AMO submission API or `web-ext sign --channel listed`. Keep the stable Gecko
ID `video-detector@playbridge`, store the AMO API credentials only as protected
CI secrets, upload the source package when required, and place submission behind
a manual approval gate. Use a distinct version for any intentionally unlisted
test build.

References:

- [AMO submission walkthrough](https://extensionworkshop.com/documentation/publish/submitting-an-add-on/)
- [AMO source-code submission requirements](https://extensionworkshop.com/documentation/publish/source-code-submission/)
- [Firefox Add-on Policies](https://extensionworkshop.com/documentation/publish/add-on-policies/)

## Chrome Web Store findings

### Invalid/redundant Chrome permissions — fixed

`webRequestExtraHeaders` and redundant `activeTab` have been removed from the
Chrome manifest. The implementation still requests `extraHeaders` as the
documented listener `extraInfoSpec`, and store validation rejects either
permission if it is accidentally reintroduced.

Reference: [Chrome WebRequest API](https://developer.chrome.com/docs/extensions/reference/api/webRequest).

### Chrome Web Store ID and native messaging

The Chrome manifest contains a development `key`, producing extension ID
`lhkbcaaoomlmoleggodlbafalokdgokn`. The Chrome Web Store draft has assigned the
production ID `gofdcnocpnieoonficfnfccolcocoaim`.

The source manifest keeps this key for stable unpacked development IDs, but
`pnpm store:package` removes `key` from the Chrome runtime ZIP because the
Chrome Web Store rejects that field during upload. The production ID is now
included in the desktop native-host allowlists. A desktop build containing this
change must be distributed before relying on a store-installed extension for
native messaging.

A store-installed extension cannot connect to `com.playbridge.host` unless its
actual production ID appears in the native host's `allowed_origins`. The release
sequence should be:

1. Create/upload a draft item to obtain the permanent Chrome Web Store ID and
   store-generated public key.
2. Decide whether the source manifest should use the store key; Chrome describes
   `key` primarily as a development-ID mechanism and says it is usually not
   needed.
3. Add the permanent store ID to `NativeHostInstaller.chromeExtIds` and the
   checked-in Chrome native-host manifest. (Completed for
   `gofdcnocpnieoonficfnfccolcocoaim`.)
4. Release a desktop build that installs the production origin.
5. Test the extension installed from the store/draft channel, not only unpacked.
6. Submit the extension only after a generally available desktop build supports
   that production ID.

Reference: [Chrome manifest `key`](https://developer.chrome.com/docs/extensions/reference/manifest/key).

### Runtime privacy disclosure and consent — fixed; dashboard work remains

Chrome treats locally processed browsing activity, website resources, and
authentication cookies as user data. The extension now opens a full-page
disclosure on first install/update, starts media handling disabled, gates every
media/cast path, and supports revocation with session cleanup. The owner must
still complete these dashboard/publication steps:

- host `PRIVACY.md` at a stable public HTTPS URL and enter it in the dashboard;
- disclose at least web browsing activity, website content/resources, and
  authentication information in the Privacy practices form;
- provide a single-purpose statement and a justification for every permission;
- certify Limited Use and state explicitly that data is not used for ads,
  analytics, profiling, resale, or unrelated purposes;
- select **No remote code**;
- ensure the store listing, dashboard declarations, privacy policy, and runtime
  behavior all agree.

Use the exact prepared text in `STORE_LISTING_COPY.md` and verify the disclosure
on a clean Chrome profile before submission.

References:

- [Chrome user-data FAQ](https://developer.chrome.com/docs/webstore/program-policies/user-data-faq/)
- [Chrome disclosure requirements](https://developer.chrome.com/docs/webstore/program-policies/disclosure-requirements)
- [Chrome privacy dashboard fields](https://developer.chrome.com/docs/webstore/cws-dashboard-privacy)

### Review risk: minimize and justify permissions

The general all-site media detector reasonably needs broad host access, but it
will receive extra review scrutiny. The dashboard should include concise
justifications for `webRequest`, `<all_urls>`, `webNavigation`, `storage`,
`tabs`, `contextMenus`, `notifications`, and `nativeMessaging`.

The redundant `activeTab` permission is removed. Google still requires the
remaining broad permissions to be justified in the dashboard.

Reference: [Chrome minimum-permission policy](https://developer.chrome.com/docs/webstore/program-policies/user-data-faq/#minimum-permission).

### Blocker: create current listing assets

The repository contains a 128x128 icon and several narrow popup screenshots,
but it does not contain a Chrome Web Store asset set. The current Chrome listing
guidance calls for:

- a 128x128 store icon;
- at least one 1280x800 screenshot (up to five);
- a 440x280 small promo tile;
- an optional 1400x560 marquee tile;
- a clear detailed description, category, language, support URL, and privacy
  policy URL.

The existing `docs/screenshots/ext-*.jpg` files are approximately 722–736 pixels
wide and tall/portrait, so they do not meet the 1280x800 screenshot requirement
without creating new composed store images. New screenshots should show the
current video list, successful desktop/TV state, and per-site overlay controls.

Reference: [Chrome Web Store listing information and assets](https://developer.chrome.com/docs/webstore/cws-dashboard-listing/).

### Chrome first submission instructions

1. Register the long-term owner Google account as a Chrome Web Store developer,
   accept the agreement, pay the one-time registration fee, verify the contact
   email, enable two-step verification, and complete the publisher profile.
2. In the Developer Dashboard, choose **Add new item** and upload a valid Chrome
   ZIP with `manifest.json` at the archive root. To obtain the permanent item ID
   before the final release is ready, the current `0.4.2` ZIP may be uploaded as
   a **draft only**; do not submit it. Treat that version as consumed and upload
   a higher final release version later.
3. Record the permanent item/extension ID and publisher ID. If retaining a
   manifest `key`, copy the store-provided public key and verify the resulting
   unpacked ID rather than assuming the development key is the production key.
4. Add the permanent item ID to:
   - `desktop/lib/native_host_installer.dart` in `chromeExtIds`;
   - `desktop/native_messaging/com.playbridge.host.chrome.json` in
     `allowed_origins` as `chrome-extension://<STORE_ID>/`.
5. Build and publish a PlayBridge desktop release containing that allowlist.
   Test that the host manifest installed by the desktop app contains the store
   origin. The extension should not be publicly launched before a generally
   available compatible desktop build exists.
6. Complete the **Store listing** tab with the prepared long description,
   category/language, homepage and support URLs, 128x128 icon, at least one
   1280x800 screenshot, and 440x280 small promo tile. Use genuine screenshots
   without private streams, IPs, headers, tokens, or pairing PINs.
7. Complete the **Privacy practices** tab using `STORE_LISTING_COPY.md`:
   - provide the narrow single-purpose statement;
   - justify every requested permission and `<all_urls>` host access;
   - disclose web browsing activity, website content/resources, and
     authentication information handled while detecting/casting media;
   - select **No remote code**;
   - certify Limited Use and provide the public privacy-policy URL.
8. Complete the **Distribution** tab. Choose public, unlisted, or private
   visibility and the desired regions. All visibility choices are still subject
   to Chrome Web Store policy review; private/unlisted is not a policy bypass.
9. Add the public desktop download, native-host setup, pairing/test-TV steps, and
   public media test page wherever the dashboard requests reviewer/test
   instructions. State the expected behavior with no desktop app, no TV, and a
   connected TV.
10. After the production ID and owner inputs are committed, choose a higher
    release version, update both manifests and `CHANGELOG.md`, and run
    `pnpm store:check` from the final release commit. Upload
    `playbridge-extension-chrome-<version>.zip` and verify that the dashboard
    reports the expected version and permissions.
11. Test the draft/store-associated build with the production native-messaging
    origin, then submit it for review. Preserve the submitted ZIP and checksum
    and respond to review feedback with a new version when required.

After the first item is approved, automate later updates with the Chrome Web
Store API v2 using the permanent publisher and item IDs. Store OAuth or service
account credentials only as protected CI secrets. Keep the API's upload and
publish operations as separate jobs, place publish behind a manual approval
gate, and fetch the final item status after each operation. If visibility is
changed manually, complete a manual publication with that visibility before
expecting API publication to work.

References:

- [Register a Chrome Web Store developer account](https://developer.chrome.com/docs/webstore/register)
- [Set up the developer account](https://developer.chrome.com/docs/webstore/set-up-account/)
- [Publish an extension for the first time](https://developer.chrome.com/docs/webstore/publish/)
- [Complete the store listing](https://developer.chrome.com/docs/webstore/cws-dashboard-listing/)
- [Complete privacy practices](https://developer.chrome.com/docs/webstore/cws-dashboard-privacy/)
- [Choose distribution and visibility](https://developer.chrome.com/docs/webstore/cws-dashboard-distribution/)
- [Chrome Web Store API v2](https://developer.chrome.com/docs/webstore/using-api)

## Recommended submission order

1. Update extension CI to run `pnpm store:check`, retain its artifacts, and stop
   signing public-release versions through Firefox's unlisted channel. Merge
   this workflow change before any version bump.
2. Register and finish the AMO and Chrome Web Store developer accounts.
3. Create the Chrome draft item with a non-submitted package, capture its
   permanent ID, and update both desktop native-host allowlists.
4. Host/verify the privacy URL, create real listing assets, and fill the reviewer
   setup placeholders with a public desktop build and test URL.
5. Ship or stage the desktop build that recognizes the production Chrome ID.
6. Choose the release version, then request an explicit release/uprev so both
   manifests and the changelog are updated together.
7. Merge the final/squashed release commit and run `pnpm store:check` from that
   exact commit. Preserve all ZIPs and `SHA256SUMS.txt`.
8. Run the clean-profile browser/native-host matrix, including production Chrome
   identity and the Firefox data-consent prompt.
9. Submit the first Firefox version as **listed/on this site** through AMO with
   the separate source ZIP and reviewer notes.
10. Upload the final Chrome ZIP, finish the Listing, Privacy practices, and
    Distribution tabs, and submit it for review.
11. Preserve the exact submitted artifacts and checksums and address reviewer
    requests with focused, versioned updates.
12. Only after the initial store items are approved, add protected CI jobs for
    listed AMO updates and Chrome Web Store upload. Keep final submission or
    publication behind manual approval.

## Final release checklist

### Code and packages

- [x] Firefox data-collection permissions and consent path implemented
- [x] Chrome first-run disclosure/consent implemented
- [x] `webRequestExtraHeaders` manifest entry removed
- [x] redundant `activeTab` permission removed
- [x] Chrome production ID added to native-host allowlists
- [ ] release version and changelog updated
- [x] TypeScript, tests, and both builds pass
- [x] zero-warning `web-ext lint` and packaged-manifest validation pass
- [x] Firefox binary ZIP, Chrome ZIP, and AMO source ZIP generated reproducibly
- [x] artifact checksums generated (regenerate from the final release commit)
- [ ] Release CI runs `pnpm store:check` and uploads the exact generated
      artifacts/checksums
- [ ] Current Firefox `--channel unlisted` release signing removed or isolated
      to explicitly versioned test builds
- [ ] Version detection made robust, or release bump kept in the final/squashed
      commit merged to `main`

### Store dashboards

- [ ] Public privacy-policy URL available
- [ ] Single-purpose and permission justifications completed
- [ ] Data-use declarations match code and privacy policy
- [ ] Desktop-app dependency prominently disclosed
- [ ] Current screenshots and Chrome promo tile uploaded
- [ ] Support/homepage URLs and contact details completed
- [ ] Reviewer instructions and test setup supplied
- [ ] Distribution regions and visibility selected
- [ ] Developer accounts satisfy store security requirements, including Chrome
      Web Store two-step verification
- [ ] Chrome draft item created and permanent item/publisher IDs recorded
- [ ] First Firefox submission uses **listed/on this site**, with source ZIP
- [ ] Initial submissions approved before store publication credentials are
      enabled in CI

### Post-approval automation

- [ ] AMO updates use the listed channel and protected API credentials
- [ ] Chrome updates use the permanent publisher/item IDs and API v2
- [ ] Store upload and publish are separate CI jobs
- [ ] Publish/submit jobs require protected-environment or manual approval
- [ ] CI polls and records the resulting store submission status

### Manual verification

- [ ] Clean install and update tested in Firefox and Chrome
- [ ] Production store IDs tested with native messaging
- [ ] Direct, HLS, DASH, blob/MSE, iframe, SPA, fullscreen, and protected streams tested
- [ ] Global/per-site overlay settings tested and retained
- [ ] Private/incognito behavior and retention verified
- [ ] Desktop/TV error and success states verified
