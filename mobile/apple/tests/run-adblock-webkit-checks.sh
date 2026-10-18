#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-adblock-webkit.XXXXXX)"
fixture_pid=''
simulator="${IOS_TEST_SIMULATOR:-9F51E894-3550-4775-90E5-618A3A1E824E}"
app_id=com.playbridge.adblock-webkit-checks
trap 'if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; xcrun simctl uninstall "$simulator" "$app_id" >/dev/null 2>&1 || true; rm -rf "$test_dir"' EXIT
python3 "$repo_root/mobile/apple/tests/browser-fixture.py" "$test_dir/base" &
fixture_pid=$!
for attempt in {1..100}; do [[ -s "$test_dir/base" ]] && break; sleep 0.05; done
export SIMCTL_CHILD_BROWSER_FIXTURE="$(cat "$test_dir/base")"
app="$test_dir/AdblockChecks.app"
mkdir -p "$app"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.playbridge.adblock-webkit-checks</string>
<key>CFBundleExecutable</key><string>AdblockChecks</string>
<key>CFBundleName</key><string>AdblockChecks</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>MinimumOSVersion</key><string>16.0</string>
<key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
</dict></plist>
PLIST
if [[ -n "${ADBLOCK_MEASUREMENT_LISTS:-}" ]]; then cp "$ADBLOCK_MEASUREMENT_LISTS" "$app/measurement-lists.json"; fi
source_dir="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser"
native="$repo_root/mobile/apple/Native/PlayBridgeCastCore.xcframework/ios-arm64_x86_64-simulator"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator swiftc -sdk "$sdk" -target "$(uname -m)-apple-ios16.0-simulator" \
  -module-cache-path "$test_dir/cache" -I "$native/Headers" "$native/libplaybridge_cast_core_ffi.a" \
  -lc++ -framework Security -framework SystemConfiguration \
  "$source_dir/ContentBlocker.swift" "$source_dir/NativeAdblockEngine.swift" "$source_dir/ContentBlockerListSettings.swift" \
  "$source_dir/AdblockListPreparser.swift" "$source_dir/NavigationAdRules.swift" "$source_dir/BrowserDomainRules.swift" \
  "$repo_root/mobile/apple/tests/AdblockWebKitTests.swift" -o "$app/AdblockChecks"
if [[ "${PB_BROWSER_BUILD_ONLY:-0}" == 1 ]]; then echo 'PASS: adblock WebKit fixture build only'; exit 0; fi
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "$simulator" "$app"
xcrun simctl launch --console "$simulator" "$app_id" | tee "$test_dir/result"
rg -q '^PASS: production adblock' "$test_dir/result"
