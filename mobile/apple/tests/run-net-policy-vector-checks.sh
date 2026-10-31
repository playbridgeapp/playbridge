#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-net-policy-tests.XXXXXX)"
trap 'rm -rf "${test_dir:?}"' EXIT
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/BrowserCastingPolicy.swift" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/PageCastRequest.swift" \
  "$repo_root/mobile/apple/tests/PageCastNetworkPolicyVectorTests.swift" -o "$test_dir/check"
"$test_dir/check" "$repo_root/cast/net-policy/tests/vectors.json"
