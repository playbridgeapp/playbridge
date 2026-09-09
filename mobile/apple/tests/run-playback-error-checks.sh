#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-playback-errors.XXXXXX)"
fixture_pid=""
trap 'if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$test_dir"' EXIT
python3 "$repo_root/mobile/apple/tests/apple-upstream-fixture.py" "$test_dir/port" &
fixture_pid=$!
for attempt in {1..100}; do [ -s "$test_dir/port" ] && break; sleep 0.05; done
export PLAYBACK_TEST_ORIGIN="http://127.0.0.1:$(cat "$test_dir/port")"
swiftc -module-cache-path "$test_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/StreamRouteService.swift" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/PhonePlaybackFallback.swift" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/PlaybackSession.swift" \
  "$repo_root/mobile/apple/tests/PlaybackSessionTests.swift" -o "$test_dir/check"
"$test_dir/check"
