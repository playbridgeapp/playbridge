#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-history-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/WireProtocol.swift" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Data/CastHistoryStore.swift" \
  "$repo_root/mobile/apple/tests/CastHistoryTests.swift" -o "$test_dir/check"
"$test_dir/check"
