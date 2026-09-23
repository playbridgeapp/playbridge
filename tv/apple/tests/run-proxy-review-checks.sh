#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-tv-proxy-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/tv/apple/PlayBridge TV/PlayBridge TV/Network/VLCProxyServer.swift" \
  "$repo_root/tv/apple/tests/ProxyReviewHarness.swift" -o "$test_dir/proxy"
python3 "$repo_root/tv/apple/tests/proxy_review_checks.py" "$test_dir/proxy"
