#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-cast-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$app/Models/Models.swift" \
  "$app/Network/GoogleCastBrowser.swift" \
  "$app/Network/GoogleCastSession.swift" \
  "$app/Network/GoogleCastController.swift" \
  "$repo_root/mobile/apple/tests/GoogleCastControllerTests.swift" -o "$test_dir/check"
"$test_dir/check"
