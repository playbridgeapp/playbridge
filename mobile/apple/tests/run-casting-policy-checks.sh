#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
build_dir="$(mktemp -d /private/tmp/playbridge-casting-policy.XXXXXX)"
trap 'rm -rf "$build_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$build_dir/cache" \
  "$app/Browser/BrowserCastingPolicy.swift" "$app/Browser/DetectionScript.swift" \
  "$repo_root/mobile/apple/tests/BrowserCastingPolicyTests.swift" -o "$build_dir/check"
"$build_dir/check" "$build_dir/detector.js"
PB_DETECTION_SCRIPT="$build_dir/detector.js" node --test "$repo_root/mobile/apple/tests/DetectionScriptTests.js"
