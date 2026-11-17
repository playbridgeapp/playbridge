#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-watch-together.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT

tv_together="$repo_root/tv/apple/PlayBridge TV/PlayBridge TV/Together"
phone="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
phone_together="$phone/Together"

# Shared Swift files must remain byte-identical between tvOS and iOS.
for shared in TogetherClient.swift TogetherModels.swift; do
  if ! cmp -s "$tv_together/$shared" "$phone_together/$shared"; then
    echo "FAIL: $shared differs between tv/apple and mobile/apple" >&2
    exit 1
  fi
done

# Entitlements checks: preserve multicast + add Watch Together associated domain.
multicast_ent="$repo_root/mobile/apple/config/DLNAMulticast.entitlements"
assoc_ent="$repo_root/mobile/apple/config/AssociatedDomains.entitlements"
grep -q 'com.apple.developer.networking.multicast' "$multicast_ent"
grep -q 'applinks:party.playbridge.app' "$multicast_ent"
grep -q 'applinks:party.playbridge.app' "$assoc_ent"

swiftc -module-cache-path "$test_dir/cache" \
  "$phone_together/TogetherModels.swift" \
  "$phone_together/TogetherClient.swift" \
  "$phone_together/WatchTogetherLinks.swift" \
  "$phone_together/SavedWatchParties.swift" \
  "$phone_together/PhoneTogether.swift" \
  "$phone/Network/WireProtocol.swift" \
  "$phone/Browser/BrowserCastingPolicy.swift" \
  "$phone/Models/Models.swift" \
  "$phone/Browser/PageCastRequest.swift" \
  "$phone/Network/StreamRouteService.swift" \
  "$phone/Network/PhonePlayerPreferences.swift" \
  "$phone/Network/PhonePlaybackEngine.swift" \
  "$phone/Network/PlaybackSession.swift" \
  "$phone/Network/WebsitePhonePlayback.swift" \
  "$phone/Network/WebsiteCaptionParser.swift" \
  "$repo_root/mobile/apple/tests/WatchTogetherTests.swift" \
  -o "$test_dir/check"
"$test_dir/check"
