#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-dlna.XXXXXX)"
fixture_pid=""
trap 'if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$test_dir"' EXIT
mkdir -p "$test_dir/PlayBridgeCastCore"
cp "$repo_root/cast/ffi/include/playbridge_cast_core.h" "$test_dir/PlayBridgeCastCore/"
echo 'module PlayBridgeCastCore { header "playbridge_cast_core.h" export * }' > "$test_dir/PlayBridgeCastCore/module.modulemap"
python3 "$repo_root/mobile/apple/tests/dlna-fixture.py" "$test_dir/port" &
fixture_pid=$!
for attempt in {1..100}; do [ -s "$test_dir/port" ] && break; sleep 0.05; done
export DLNA_FIXTURE="http://127.0.0.1:$(cat "$test_dir/port")"
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" -I "$test_dir" \
 "$app/Models/Models.swift" "$app/Network/GoogleCastBrowser.swift" \
 "$app/Network/GoogleCastSession.swift" "$app/Network/GoogleCastController.swift" \
 "$app/Network/DLNABrowser.swift" "$repo_root/mobile/apple/tests/DLNASessionTests.swift" \
 "$repo_root/target/debug/libplaybridge_cast_core_ffi.a" \
 -framework Security -framework SystemConfiguration -liconv -lresolv -o "$test_dir/check"
"$test_dir/check"
