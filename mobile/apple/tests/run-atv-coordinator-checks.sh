#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-atv-coordinator.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -swift-version 5 -warnings-as-errors -module-cache-path "$test_dir/cache" \
  "$app/Models/Models.swift" "$app/Network/AtvClientModels.swift" \
  "$app/Network/AtvNativeTransport.swift" "$app/Data/PlayBridgePhoneIdentity.swift" "$app/Network/AtvRemoteClient.swift" \
  "$app/Data/AtvCredentialStore.swift" "$app/Network/AtvDeviceMatcher.swift" \
  "$app/Network/AtvBonjourBrowser.swift" "$app/Network/AtvCoordinatorDependencies.swift" \
  "$app/Network/RemoteNavigationDispatcher.swift" "$app/Network/AtvControlModels.swift" "$app/Network/AtvConnectionLifetime.swift" "$app/Network/AtvRemoteCoordinator.swift" "$app/UI/AtvPairingPresentation.swift" \
  "$repo_root/mobile/apple/tests/AtvCoordinatorTests.swift" -o "$test_dir/check"
"$test_dir/check"
python3 - "$app" <<'PY'
from pathlib import Path
import sys
app = Path(sys.argv[1])
ui = (app/'UI/AtvPairingViews.swift').read_text()
for value in ['Pairing code', 'Retry pairing', 'Cancel pairing', 'Pair TV', '6 hexadecimal characters', 'Open PlayBridge settings for Local Network access', 'privacySensitive()', 'coordinator.cancelPairing()', 'coordinator.forget(record.fingerprint)']:
    assert value in ui, value
assert 'UIApplication.openSettingsURLString' in ui
assert 'AtvPairedTVsSheet' in (app/'UI/DeviceConnectionSheet.swift').read_text()
assert 'AtvPairedTVsSheet' in (app/'UI/RemoteControlView.swift').read_text()
assert 'AtvPairingPresentation.pairingAction' in (app/'UI/RemoteControlScreen.swift').read_text()
print('PASS: pairing accessibility/actions, Settings recovery and receiver-management integration fixtures')
PY
