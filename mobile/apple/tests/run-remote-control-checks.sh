#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-remote-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$app/Models/Models.swift" "$app/Network/ConnectionCoordinator.swift" \
  "$app/Network/WireProtocol.swift" "$app/UI/RemoteMode.swift" "$app/UI/RemoteSeekBehavior.swift" \
  "$app/Network/ReceiverCapabilities.swift" "$app/Network/ReceiverRemotePolicy.swift" \
  "$repo_root/mobile/apple/tests/RemoteNavigationGestureTests.swift" \
  "$app/Network/RemoteAvailability.swift" "$repo_root/mobile/apple/tests/RemoteAvailabilityTests.swift" \
  "$app/UI/RemoteHaptics.swift" "$repo_root/mobile/apple/tests/RemoteHapticsTests.swift" \
  "$app/UI/RemoteSeekTimeline.swift" "$repo_root/mobile/apple/tests/RemoteSeekTimelineTests.swift" \
  "$app/UI/RemoteControlPresentation.swift" "$repo_root/mobile/apple/tests/RemoteControlPresentationTests.swift" \
  "$repo_root/mobile/apple/tests/RemoteParityTests.swift" \
  "$repo_root/mobile/apple/tests/ReceiverPlaybackFailureTests.swift" \
  "$repo_root/mobile/apple/tests/RemoteControlTests.swift" -o "$test_dir/check"
"$test_dir/check" "$repo_root"
# Native macOS SwiftUI layout: no simulator, app installation or network access.
swiftc -parse-as-library -target "$(uname -m)-apple-macosx13.0" -module-cache-path "$test_dir/layout-cache" \
  "$app/UI/RemoteContentBounds.swift" "$repo_root/mobile/apple/tests/RemoteContentBoundsTests.swift" \
  -o "$test_dir/layout-check"
"$test_dir/layout-check"
