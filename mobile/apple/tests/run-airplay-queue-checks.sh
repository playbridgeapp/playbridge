#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-airplay-queue-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/AirPlayQueueState.swift" \
  "$repo_root/mobile/apple/tests/AirPlayQueueStateTests.swift" -o "$test_dir/check"
"$test_dir/check"
