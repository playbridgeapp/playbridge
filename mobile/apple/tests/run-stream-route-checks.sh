#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-route-tests.XXXXXX)"
fixture_pid=""
trap 'if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$test_dir"' EXIT
if [ "${1:-}" = "--network" ]; then
  python3 "$repo_root/mobile/apple/tests/apple-upstream-fixture.py" "$test_dir/port" &
  fixture_pid=$!
  for attempt in {1..100}; do [ -s "$test_dir/port" ] && break; sleep 0.05; done
  export REMOTE_PROXY_TEST_BASE="http://127.0.0.1:$(cat "$test_dir/port")"
fi
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/StreamRouteService.swift" \
  "$repo_root/mobile/apple/tests/StreamRouteTests.swift" -o "$test_dir/check"
"$test_dir/check"
