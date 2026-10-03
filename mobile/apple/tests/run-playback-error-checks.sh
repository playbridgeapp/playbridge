#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-playback-errors.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
phone="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$phone/Network/StreamRouteService.swift" \
  "$phone/Network/PlaybackSession.swift" \
  "$phone/Network/PhonePlayerPreferences.swift" "$phone/Network/PhonePlaybackEngine.swift" \
  "$repo_root/mobile/apple/tests/TestPhonePlaybackEngine.swift" \
  "$repo_root/mobile/apple/tests/PlaybackSessionTests.swift" -o "$test_dir/check"
"$test_dir/check"
