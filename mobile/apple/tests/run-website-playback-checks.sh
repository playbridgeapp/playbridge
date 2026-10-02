#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-website-playback.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
phone="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$phone/Models/Models.swift" \
  "$phone/Browser/PageCastRequest.swift" \
  "$phone/Network/StreamRouteService.swift" \
  "$phone/Network/PhonePlaybackFallback.swift" \
  "$phone/Network/PlaybackSession.swift" \
  "$phone/Network/PhonePlaybackEngine.swift" \
  "$phone/Network/WebsitePhonePlayback.swift" \
  "$phone/Network/WebsiteCaptionParser.swift" \
  "$repo_root/mobile/apple/tests/WebsitePhonePlaybackTests.swift" -o "$test_dir/check"
"$test_dir/check"
