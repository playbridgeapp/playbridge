#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-browser-checks.XXXXXX)"
fixture_pid=''
trap 'if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$test_dir"' EXIT
python3 "$repo_root/mobile/apple/tests/browser-fixture.py" "$test_dir/base" &
fixture_pid=$!
for attempt in {1..100}; do [[ -s "$test_dir/base" ]] && break; sleep 0.05; done
export SIMCTL_CHILD_BROWSER_FIXTURE="$(cat "$test_dir/base")"
if [[ "${1:-}" == "--popup-audit" ]]; then export SIMCTL_CHILD_POPUP_AUDIT=1; fi
if [[ "${1:-}" == "--ad-navigation" ]]; then export SIMCTL_CHILD_AD_NAVIGATION=1; fi
if [[ "${1:-}" == "--network-log" ]]; then export SIMCTL_CHILD_NETWORK_LOG=1; fi
if [[ "${1:-}" == "--domain-block" ]]; then export SIMCTL_CHILD_DOMAIN_BLOCK=1; fi
if [[ "${1:-}" == "--playback-state" ]]; then export SIMCTL_CHILD_TAB_PLAYBACK_STATE=1; fi
if [[ "${1:-}" == "--tab-management" ]]; then export SIMCTL_CHILD_TAB_MANAGEMENT=1; fi
if [[ "${1:-}" == "--picker-menu-ui" ]]; then export SIMCTL_CHILD_PICKER_MENU_UI=1; fi
if [[ "${1:-}" == "--picker-live-ui" ]]; then export SIMCTL_CHILD_PICKER_MENU_UI=1; fi
simulator="${IOS_TEST_SIMULATOR:-booted}"
app="$test_dir/BrowserChecks.app"
mkdir -p "$app"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.playbridge.browser-startup-checks</string>
<key>CFBundleExecutable</key><string>BrowserChecks</string>
<key>CFBundleName</key><string>BrowserChecks</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>1</string>
<key>UIAppFonts</key><array><string>Poppins-Regular.ttf</string></array>
<key>MinimumOSVersion</key><string>16.0</string>
<key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
</dict></plist>
PLIST
python3 - "$repo_root" "$app/element-picker.js" <<'PYTHON'
import sys
from pathlib import Path
source = (Path(sys.argv[1]) / 'mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/ContentBlocker.swift').read_text()
script = source.split('static let elementPickerJS = #"""', 1)[1].split('"""#', 1)[0]
Path(sys.argv[2]).write_text(script)
PYTHON
cp "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Fonts/Poppins-Regular.ttf" "$app/Poppins-Regular.ttf"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
source_dir="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser"
xcrun --sdk iphonesimulator swiftc -sdk "$sdk" -target "$(uname -m)-apple-ios16.0-simulator" -module-cache-path "$test_dir/cache" \
 "$source_dir/BrowserPlaybackState.swift" "$source_dir/BrowserFavicon.swift" "$source_dir/BrowserStore.swift" "$source_dir/BrowserTab.swift" "$source_dir/BrowserInteraction.swift" "$source_dir/BrowserDownloads.swift" "$source_dir/WebViewContainer.swift" \
 "$source_dir/../UI/TabsScreen.swift" "$source_dir/../UI/Theme.swift" "$source_dir/../UI/MenuSheet.swift" "$source_dir/../UI/BrowserNetworkLogView.swift" "$source_dir/BrowserDomainRules.swift" "$source_dir/BrowserNetworkLog.swift" "$source_dir/NavigationAdRules.swift" "$source_dir/../Data/BrowserDataStore.swift" \
 "$repo_root/mobile/apple/tests/BrowserStartupTests.swift" -o "$app/BrowserChecks"
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "$simulator" "$app"
if [[ "${1:-}" == "--popup-touch" || "${1:-}" == "--network-log-ui" || "${1:-}" == "--tabs-ui" || "${1:-}" == "--picker-menu-ui" || "${1:-}" == "--picker-live-ui" ]]; then
    python3 "$repo_root/mobile/apple/tests/make-popup-ui-project.py" "$test_dir"
    simulator_id="$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; print(next(d["udid"] for ds in json.load(sys.stdin)["devices"].values() for d in ds if d["state"] == "Booted"))')"
    if [[ "$simulator" != "booted" ]]; then simulator_id="$simulator"; fi
    test_method="testTrustedPopups"
    if [[ "${1:-}" == "--network-log-ui" ]]; then test_method="testNetworkLogDomainConfirmation"; fi
    if [[ "${1:-}" == "--tabs-ui" ]]; then test_method="testTabRows"; fi
    if [[ "${1:-}" == "--picker-menu-ui" ]]; then test_method="testBlockElementFromMenu"; fi
    if [[ "${1:-}" == "--picker-live-ui" ]]; then test_method="testBlockElementOnLiveImage"; fi
    TEST_RUNNER_BROWSER_FIXTURE="$SIMCTL_CHILD_BROWSER_FIXTURE" xcodebuild \
        -project "$test_dir/PopupTests.xcodeproj" -scheme PopupTests \
        -destination "platform=iOS Simulator,id=$simulator_id" \
        -derivedDataPath "$test_dir/derived" -only-testing:"PopupTests/BrowserPopupUITests/$test_method" test
    exit 0
fi
xcrun simctl launch --console "$simulator" com.playbridge.browser-startup-checks | tee "$test_dir/result"
rg -q "PASS: browser" "$test_dir/result"
