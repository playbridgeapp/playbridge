#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
build_dir="$(mktemp -d /private/tmp/playbridge-browser-models.XXXXXX)"
trap 'rm -rf "$build_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$build_dir/cache" \
  "$app/Browser/BrowserAddressResolver.swift" "$app/Browser/BrowserClipboardClassifier.swift" "$app/Browser/BrowserSiteSecurity.swift" "$app/Browser/BrowserProcessRecovery.swift" \
  "$app/Data/BrowserDataStore.swift" "$app/Data/BrowserLibraryQuery.swift" "$app/Browser/BrowserImageData.swift" "$app/Data/BrowserDataPersistence.swift" "$app/Data/BrowserTabStatePersistence.swift" "$app/Browser/AdblockSiteSettings.swift" \
  "$repo_root/mobile/apple/tests/BrowserStep3ModelTests.swift" "$repo_root/mobile/apple/tests/BrowserStep4ModelTests.swift" "$repo_root/mobile/apple/tests/AdblockSiteSettingsModelTests.swift" "$repo_root/mobile/apple/tests/BrowserModelTests.swift" -o "$build_dir/check"
"$build_dir/check"
