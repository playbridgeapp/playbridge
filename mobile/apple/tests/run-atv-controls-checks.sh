#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-atv-controls.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -swift-version 5 -warnings-as-errors -module-cache-path "$test_dir/cache" \
  "$app/Models/Models.swift" "$app/Network/ConnectionCoordinator.swift" "$app/Network/WireProtocol.swift" \
  "$app/Network/ReceiverCapabilities.swift" "$app/Network/ReceiverRemotePolicy.swift" "$app/UI/RemoteMode.swift" \
  "$app/Network/AtvClientModels.swift" "$app/Network/AtvControlModels.swift" \
  "$app/Network/RemoteNavigationDispatcher.swift" \
  "$app/Network/CombinedRemotePolicy.swift" "$app/Network/RemoteKeyboardEditor.swift" \
  "$repo_root/mobile/apple/tests/RemoteNavigationDispatchTests.swift" \
  "$repo_root/mobile/apple/tests/AtvControlsTests.swift" -o "$test_dir/check"
"$test_dir/check"
python3 - "$app" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
header=(p/'UI/RemoteControlScreen.swift').read_text()
assert 'atv-connection-state' not in header
for value in ['showDevices = true', 'combined.badge', 'Open app…', 'TV menu', 'Power off', 'atv-pairing-entry']:
    assert value in header, value
keyboard=(p/'UI/RemoteKeyboardSheet.swift').read_text()
for value in ['markedTextRange', 'unmarkText()', 'dismantleUIView', 'privacySensitive()', 'Send Enter to TV']:
    assert value in keyboard, value
assert 'Paired TVs · Full control' in (p/'UI/ConnectionScreen.swift').read_text()
remote=(p/'UI/RemoteControlView.swift').read_text()
assert 'combined.fullControlReady && !vm.isConnected' in remote
assert 'fullControl: combined.fullControlReady' in remote
assert 'keyboardSheet' in remote and 'vm.remote("text:"' not in remote
print('PASS: single-screen/header/TV-menu/keyboard-composition/Devices-row integration fixtures')
PY
