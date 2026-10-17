#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-atv-storage.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -swift-version 5 -warnings-as-errors -module-cache-path "$test_dir/cache" \
  "$app/Models/Models.swift" "$app/Network/AtvClientModels.swift" \
  "$app/Data/AtvCredentialStore.swift" "$app/Network/AtvDeviceMatcher.swift" \
  "$app/Network/AtvBonjourBrowser.swift" "$repo_root/mobile/apple/tests/AtvStorageDiscoveryTests.swift" \
  -o "$test_dir/check"
"$test_dir/check"
plutil -lint "$app/Info.plist"
python3 - "$app/Info.plist" <<'PY'
import plistlib
import sys
with open(sys.argv[1], 'rb') as f:
    services = plistlib.load(f)['NSBonjourServices']
assert services.count('_androidtvremote2._tcp') == 1
assert '_playbridge._tcp' in services and '_googlecast._tcp' in services
print('Bonjour service declarations passed')
PY
