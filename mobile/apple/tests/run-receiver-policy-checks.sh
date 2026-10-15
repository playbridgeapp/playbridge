#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-receiver-policy.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
python3 "$repo_root/mobile/apple/tests/make-receiver-policy-fixtures.py" "$repo_root" "$test_dir"
cat > "$test_dir/AppleFixture.swift" <<'SWIFT'
import Foundation
enum PlaybackEngine: String, CaseIterable {
    case avplayer, mpv
    static var capabilityPlayers: [String] { allCases.map(\.rawValue) }
}
enum PlayerAppVolume { static let step = 0.05 }
@main struct AppleFixture {
    static func main() throws {
        let data = try JSONSerialization.data(withJSONObject: TvCapabilities.buildDescriptor())
        try data.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
    }
}
SWIFT
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/tv/apple/PlayBridge TV/PlayBridge TV/Network/TvCapabilities.swift" \
  "$test_dir/AppleFixture.swift" -o "$test_dir/fixture"
"$test_dir/fixture" "$test_dir/apple.json"
swiftc -module-cache-path "$test_dir/cache" \
  "$app/Models/Models.swift" "$app/Network/ConnectionCoordinator.swift" \
  "$app/Network/WireProtocol.swift" "$app/UI/RemoteMode.swift" \
  "$app/Network/ReceiverCapabilities.swift" "$app/Network/ReceiverRemotePolicy.swift" \
  "$repo_root/mobile/apple/tests/ReceiverRemotePolicyTests.swift" -o "$test_dir/check"
"$test_dir/check" "$test_dir" "$repo_root/protocol/tests/fixtures/receiver-capabilities"
