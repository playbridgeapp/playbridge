#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-library-checks.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
simulator="${IOS_TEST_SIMULATOR:-booted}"
app="$test_dir/LibraryChecks.app"
mkdir -p "$app"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.playbridge.media-library-checks</string>
<key>CFBundleExecutable</key><string>LibraryChecks</string>
<key>CFBundleName</key><string>LibraryChecks</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>1</string>
<key>UILaunchScreen</key><dict/>
<key>MinimumOSVersion</key><string>16.0</string>
<key>NSPhotoLibraryUsageDescription</key><string>Test library access.</string>
</dict></plist>
PLIST
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
source_dir="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
xcrun --sdk iphonesimulator swiftc -sdk "$sdk" -target "$(uname -m)-apple-ios16.0-simulator" -module-cache-path "$test_dir/cache" \
 "$source_dir/Models/PhoneMedia.swift" "$source_dir/Models/CollectionModels.swift" \
 "$source_dir/Data/PhoneMediaLibrary.swift" "$source_dir/Data/CollectionsStore.swift" \
 "$source_dir/UI/PhoneMediaThumbnail.swift" "$source_dir/UI/PhoneFilesScreen.swift" "$source_dir/UI/PhoneMediaDetail.swift" \
 "$source_dir/UI/CollectionDetailScreen.swift" "$source_dir/UI/Theme.swift" \
 "$source_dir/Network/LocalFileServer.swift" \
 "$repo_root/mobile/apple/tests/PhoneMediaLibraryTests.swift" -o "$app/LibraryChecks"
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "$simulator" "$app"
xcrun simctl launch --console "$simulator" com.playbridge.media-library-checks | tee "$test_dir/result"
rg -q 'PASS: media library' "$test_dir/result"
container="$(xcrun simctl get_app_container "$simulator" com.playbridge.media-library-checks data)"
cp "$container/Documents/library.png" /tmp/playbridge-library.png
