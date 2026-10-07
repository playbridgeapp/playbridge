#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-apple-upstream.XXXXXX)"
fixture_pid=""
trap 'if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$test_dir"' EXIT
mkdir -p "$test_dir/PlayBridgeCastCore"
cp "$repo_root/cast/ffi/include/playbridge_cast_core.h" "$test_dir/PlayBridgeCastCore/"
echo 'PbUpstreamCheckedCallbacks test_upstream_callbacks(void);' >> "$test_dir/PlayBridgeCastCore/playbridge_cast_core.h"
echo 'module PlayBridgeCastCore { header "playbridge_cast_core.h" export * }' > "$test_dir/PlayBridgeCastCore/module.modulemap"
cat > "$test_dir/shim.c" <<'C'
#include "PlayBridgeCastCore/playbridge_cast_core.h"
static PbUpstreamCheckedCallbacks installed;
uint32_t pb_proxy_upstream_abi_version(void) { return 1; }
void pb_proxy_upstream_set_checked_callbacks(PbUpstreamCheckedCallbacks callbacks) { installed = callbacks; }
int32_t pb_proxy_upstream_checked_callbacks_registered(void) { return installed.open != 0; }
PbUpstreamCheckedCallbacks test_upstream_callbacks(void) { return installed; }
C
python3 "$repo_root/mobile/apple/tests/apple-upstream-fixture.py" "$test_dir/port" &
fixture_pid=$!
# A cold CI runner can take several seconds to start Python.
for attempt in {1..600}; do [ -s "$test_dir/port" ] && break; sleep 0.05; done
if [ ! -s "$test_dir/port" ]; then echo "upstream fixture did not report a port" >&2; exit 1; fi
export UPSTREAM_PROXY_CONNECT_MARKER="$test_dir/port.connect"
export UPSTREAM_TEST_ORIGIN="http://127.0.0.1:$(cat "$test_dir/port")"
clang -c "$test_dir/shim.c" -o "$test_dir/shim.o"
swiftc -module-cache-path "$test_dir/cache" -I "$test_dir" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Network/AppleProxyUpstream.swift" \
  "$repo_root/mobile/apple/tests/AppleProxyUpstreamTests.swift" "$test_dir/shim.o" -o "$test_dir/check"
"$test_dir/check"
