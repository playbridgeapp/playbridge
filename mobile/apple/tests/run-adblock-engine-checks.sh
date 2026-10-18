#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /private/tmp/playbridge-adblock-checks.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
cargo build --manifest-path "$repo_root/Cargo.toml" -p playbridge-cast-core-ffi --features adblock-engine,sender-services-apple --locked --offline
native="${CARGO_TARGET_DIR:-$repo_root/target}/debug"
source_dir="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser"
swiftc -warnings-as-errors -module-cache-path "$test_dir/cache" -I "$repo_root/cast/ffi/include" \
  -L "$native" -lplaybridge_cast_core_ffi -Xlinker -rpath -Xlinker "$native" \
  "$source_dir/NativeAdblockEngine.swift" "$source_dir/AdblockListPreparser.swift" \
  "$source_dir/ContentBlockerListSettings.swift" "$source_dir/NavigationAdRules.swift" \
  "$repo_root/mobile/apple/tests/AdblockEngineTests.swift" -o "$test_dir/check"
"$test_dir/check"
