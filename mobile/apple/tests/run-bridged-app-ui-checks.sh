#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-bridged-ui.XXXXXX)"
fixture_pid=''
trap 'if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$test_dir"' EXIT
python3 "$repo_root/mobile/apple/tests/browser-fixture.py" "$test_dir/base" --bridged-apps &
fixture_pid=$!
for attempt in {1..100}; do [[ -s "$test_dir/base" ]] && break; sleep 0.05; done
fixture_base="$(cat "$test_dir/base")"
simulator="${IOS_TEST_SIMULATOR:-booted}"
if [[ "$simulator" == "booted" ]]; then
  simulator="$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; print(next(d["udid"] for ds in json.load(sys.stdin)["devices"].values() for d in ds if d["state"] == "Booted"))')"
fi
bundle_id="com.playbridge.bridged-app-ui-checks"
# Keep the user's simulator installation, saved tabs and cast permissions untouched.
xcrun simctl uninstall "$simulator" "$bundle_id" 2>/dev/null || true
xcodebuild -project "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj" \
  -scheme 'PlayBridge Phone' -destination "platform=iOS Simulator,id=$simulator" \
  -derivedDataPath "$test_dir/app-build" PRODUCT_BUNDLE_IDENTIFIER="$bundle_id" build > "$test_dir/build.log" 2>&1 || {
    cat "$test_dir/build.log"; exit 1;
  }
xcrun simctl install "$simulator" "$test_dir/app-build/Build/Products/Debug-iphonesimulator/PlayBridge Phone.app"
python3 "$repo_root/mobile/apple/tests/make-popup-ui-project.py" "$test_dir" \
  "$repo_root/mobile/apple/tests/BridgedAppUITests.swift"
TEST_RUNNER_BROWSER_FIXTURE="$fixture_base" xcodebuild -project "$test_dir/PopupTests.xcodeproj" -scheme PopupTests \
  -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$test_dir/test-build" \
  -resultBundlePath "${IOS_UI_RESULT_BUNDLE:-$test_dir/results.xcresult}" \
  -only-testing:"${IOS_UI_TEST_FILTER:-PopupTests/BridgedAppUITests}" test
