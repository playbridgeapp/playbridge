#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-tv-review-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
source_root="$repo_root/tv/apple/PlayBridge TV/PlayBridge TV"
swiftc -module-cache-path "$test_dir/cache" \
  "$source_root/Models/PairedDevice.swift" \
  "$source_root/Models/PairingCredentialState.swift" \
  "$source_root/Data/HistoryStore.swift" \
  "$source_root/Player/PlaybackTime.swift" \
  "$repo_root/tv/apple/tests/ReceiverReviewTests.swift" -o "$test_dir/check"
"$test_dir/check"
