#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
# First build the host library with sender-services-apple; this test links the
# real Rust archive, not the callback shim used by run-apple-upstream-checks.sh.
archive="$repo_root/target/debug/libplaybridge_cast_core_ffi.a"
[ -f "$archive" ] || { echo 'Build host FFI with --features sender-services-apple first.' >&2; exit 1; }
test_dir="$(mktemp -d /tmp/playbridge-sender-services.XXXXXX)"
fixture_pid=""
segment_pid=""
trap 'if [ -n "$segment_pid" ]; then kill "$segment_pid" 2>/dev/null || true; wait "$segment_pid" 2>/dev/null || true; fi; if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$test_dir"' EXIT
mkdir -p "$test_dir/PlayBridgeCastCore"
cp "$repo_root/cast/ffi/include/playbridge_cast_core.h" "$test_dir/PlayBridgeCastCore/"
echo 'module PlayBridgeCastCore { header "playbridge_cast_core.h" export * }' > "$test_dir/PlayBridgeCastCore/module.modulemap"
fixture_host="$(/usr/sbin/ipconfig getifaddr en0 || /usr/sbin/ipconfig getifaddr en1)"
[ -n "$fixture_host" ] || { echo 'A LAN address is required: Rust deliberately rejects loopback upstreams.' >&2; exit 1; }
python3 "$repo_root/mobile/apple/tests/apple-upstream-fixture.py" "$test_dir/segment-port" "$fixture_host" &
segment_pid=$!
for attempt in {1..100}; do [ -s "$test_dir/segment-port" ] && break; sleep 0.05; done
export UPSTREAM_SEGMENT_ORIGIN="http://$fixture_host:$(cat "$test_dir/segment-port")"
python3 "$repo_root/mobile/apple/tests/apple-upstream-fixture.py" "$test_dir/port" "$fixture_host" "$UPSTREAM_SEGMENT_ORIGIN" &
fixture_pid=$!
for attempt in {1..100}; do [ -s "$test_dir/port" ] && break; sleep 0.05; done
export UPSTREAM_TEST_ORIGIN="http://$fixture_host:$(cat "$test_dir/port")"
swiftc -module-cache-path "$test_dir/cache" -I "$test_dir" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/AppleProxyUpstream.swift" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/PhoneSenderServices.swift" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/PhonePlaybackFallback.swift" \
  "$repo_root/mobile/apple/tests/PhoneSenderServicesTests.swift" "$archive" \
  -framework AVFoundation -framework Security -framework SystemConfiguration -liconv -lresolv -o "$test_dir/check"
"$test_dir/check"
