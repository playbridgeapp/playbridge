#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-browser-receiver-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$app/Network/BrowserReceiverState.swift" \
  "$repo_root/mobile/apple/tests/BrowserReceiverStateTests.swift" -o "$test_dir/check"
"$test_dir/check"
