#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-airplay-subtitle-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/AirPlaySubtitles.swift" \
  "$repo_root/mobile/apple/tests/AirPlaySubtitleTests.swift" -o "$test_dir/check"
"$test_dir/check"
