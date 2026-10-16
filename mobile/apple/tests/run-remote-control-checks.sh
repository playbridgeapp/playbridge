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
  "$app/UI/RemoteControlPresentation.swift" "$repo_root/mobile/apple/tests/RemoteControlPresentationTests.swift" \
  "$repo_root/mobile/apple/tests/ReceiverPlaybackFailureTests.swift" \
  "$repo_root/mobile/apple/tests/RemoteControlTests.swift" -o "$test_dir/check"
"$test_dir/check" "$repo_root"
