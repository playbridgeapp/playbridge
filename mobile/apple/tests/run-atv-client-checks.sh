#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-atv-client.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
app="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -swift-version 5 -module-cache-path "$test_dir/cache" \
  "$app/Network/AtvNativeTransport.swift" "$app/Network/AtvClientModels.swift" \
  "$app/Network/AtvRemoteClient.swift" "$repo_root/mobile/apple/tests/AtvRemoteClientTests.swift" \
  -o "$test_dir/check"
"$test_dir/check"

# Exercise the compiled C-backed branch as well as the no-framework stub above.
mkdir -p "$test_dir/module"
cp "$repo_root/cast/ffi/include/playbridge_cast_core.h" "$test_dir/module/"
cat >> "$test_dir/module/playbridge_cast_core.h" <<'HEADER'
void pb_test_reset(unsigned mode);
unsigned pb_test_count(unsigned index);
HEADER
cp "$repo_root/cast/ffi/include/module.modulemap" "$test_dir/module/"
clang -I "$test_dir/module" -c "$repo_root/mobile/apple/tests/AtvNativeShim.c" -o "$test_dir/shim.o"
swiftc -swift-version 5 -D ATV_NATIVE_SHIM -I "$test_dir/module" -module-cache-path "$test_dir/native-cache" \
  "$app/Network/AtvNativeTransport.swift" "$app/Network/AtvClientModels.swift" \
  "$app/Network/AtvRemoteClient.swift" "$repo_root/mobile/apple/tests/AtvRemoteClientTests.swift" \
  "$test_dir/shim.o" -o "$test_dir/native-check"
"$test_dir/native-check"
