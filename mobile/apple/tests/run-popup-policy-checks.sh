#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-popup-policy.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
swiftc -warnings-as-errors -module-cache-path "$test_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/PopupPolicy.swift" \
  "$repo_root/mobile/apple/tests/PopupPolicyTests.swift" -o "$test_dir/check"
"$test_dir/check"
node "$repo_root/mobile/apple/tests/PopupInteractionTests.js"
