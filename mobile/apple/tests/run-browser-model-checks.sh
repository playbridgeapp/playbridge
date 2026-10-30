#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
build_dir="$(mktemp -d /private/tmp/playbridge-browser-models.XXXXXX)"
trap 'rm -rf "$build_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
for mode in release developer; do
    flags=(-module-cache-path "$build_dir/cache")
    if [[ "$mode" == developer ]]; then flags+=(-D PB_DEVELOPER_TOOLS); fi
    swiftc "${flags[@]}" \
      "$app/Browser/BrowserDeveloperTools.swift" "$app/Browser/BrowserAddressResolver.swift" "$app/Browser/BrowserClipboardClassifier.swift" "$app/Browser/BrowserSiteSecurity.swift" "$app/Browser/BrowserProcessRecovery.swift" \
      "$app/Data/BrowserDataStore.swift" "$app/Data/BrowserLibraryQuery.swift" "$app/Browser/BrowserImageData.swift" "$app/Data/BrowserDataPersistence.swift" "$app/Data/BrowserTabStatePersistence.swift" "$app/Browser/AdblockSiteSettings.swift" "$app/Browser/BrowserNetworkLog.swift" "$app/Models/DetectedVideo.swift" "$app/Browser/DetectedMediaExport.swift" \
      "$repo_root/mobile/apple/tests/BrowserStep3ModelTests.swift" "$repo_root/mobile/apple/tests/BrowserStep4ModelTests.swift" "$repo_root/mobile/apple/tests/NetworkLogCategoryModelTests.swift" "$repo_root/mobile/apple/tests/DetectedMediaExportModelTests.swift" "$repo_root/mobile/apple/tests/AdblockSiteSettingsModelTests.swift" "$repo_root/mobile/apple/tests/BrowserModelTests.swift" -o "$build_dir/check"
    "$build_dir/check"
    if [[ "$mode" == release ]]; then
        strings "$build_dir/check" > "$build_dir/strings.txt"
        if rg 'pb_developer_tools|DetectedMediaExport|full URLs; may contain private tokens' "$build_dir/strings.txt"; then
            echo 'Release browser models contain developer-only exports' >&2
            exit 1
        fi
    fi
done
