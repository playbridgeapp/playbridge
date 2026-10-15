# Licensing: why MPL-2.0, and what the CLA means for you

PlayBridge's own code moved from GPL-3.0 (and AGPL-3.0 for the Rust crates) to the
[Mozilla Public License 2.0](../LICENSE) in October 2026
([#344](https://github.com/playbridgeapp/playbridge/pull/344)). This page explains why,
and what it means if you use, fork or contribute to PlayBridge.

## Why the licence changed

- **App stores.** GPL-3.0 requires that users can install modified versions of the
  software, which App Store distribution doesn't allow. MPL-2.0 has no such clash, so the
  iPhone and Apple TV apps can ship on the App Store, and so can forks.
- **Still copyleft.** MPL-2.0 is an OSI-approved open source licence with file-level
  copyleft: if you change a PlayBridge file and distribute the result, that file's source
  must stay available under MPL-2.0. You can still combine PlayBridge with code under other
  licences, including GPL.
- **One licence for the whole project.** The apps, the Rust cast core, the CLI, the
  desktop app, the extension and the website now share the same licence.

At the time of the change, every commit came from the maintainer or their AI tools, so no
contributor's code was relicensed without consent.

## What it means if you use or fork PlayBridge

- You can use, modify and redistribute PlayBridge, including in commercial or closed
  products, as long as files you modify stay available under MPL-2.0.
- Third-party components keep their own licences. In particular, the Android TV player APK
  bundles GPL-3.0 libraries (mpv-android and nextlib), so that APK as a whole is distributed
  under GPL-3.0 terms. See [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md).
- The licence doesn't cover the PlayBridge name and logos. Forks use their own name and
  icon; see [TRADEMARKS.md](../TRADEMARKS.md).

## Why there is a contributor licence agreement

Contributors sign the [CLA](../CLA.md) once, by posting a comment on their first pull
request. You keep the copyright in your contribution; you give the Maintainer permission
to distribute it under MPL-2.0 or another licence.

That permission keeps options open that would otherwise need every contributor's consent
again, such as moving to a newer licence version, dual licensing, or a separately licensed
build for a store or partner. It also avoids the situation PlayBridge just had to check
for: changing licence when some of the code belongs to people who can't be reached.

**The promise in return:** under section 6 of the CLA, everything you contribute stays
available under an OSI-approved open source licence. The Maintainer can offer your work
under other terms as well, but never only under closed terms.

If you'd rather not sign a CLA, you can still open issues, report bugs and suggest changes.

This page explains the project's choices; it isn't legal advice.
