#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
phone="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
work="$(mktemp -d /tmp/playbridge-phone-player-features.XXXXXX)"
trap 'rm -rf "$work"' EXIT
swiftc -parse-as-library \
  "$phone/Network/PhonePlayerPreferences.swift" "$phone/Network/PhonePlaybackEngine.swift" \
  "$phone/Network/StreamRouteService.swift" "$phone/Network/PlaybackSession.swift" \
  "$phone/UI/PhonePlayerControls.swift" \
  "$repo_root/mobile/apple/tests/TestPhonePlaybackEngine.swift" \
  "$repo_root/mobile/apple/tests/PhonePlayerFeaturesTests.swift" -o "$work/check"
"$work/check"
