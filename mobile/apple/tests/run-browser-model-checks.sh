#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
build_dir="$(mktemp -d /private/tmp/playbridge-browser-models.XXXXXX)"
trap 'rm -rf "$build_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$build_dir/cache" \
  "$app/Browser/BrowserAddressResolver.swift" "$app/Browser/BrowserProcessRecovery.swift" \
  "$app/Data/BrowserDataStore.swift" "$app/Data/BrowserDataPersistence.swift" \
  "$repo_root/mobile/apple/tests/BrowserModelTests.swift" -o "$build_dir/check"
"$build_dir/check"
