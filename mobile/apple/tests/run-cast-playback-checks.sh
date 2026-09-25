#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-cast-playback-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$app/Models/Models.swift" "$app/Network/CastPlaybackSession.swift" \
  "$repo_root/mobile/apple/tests/CastPlaybackSessionTests.swift" -o "$test_dir/check"
"$test_dir/check"
