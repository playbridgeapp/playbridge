#!/bin/sh
set -eu

# Proxy-only Cast Core for the Apple TV receiver: the embedded stream proxy with
# host (URLSession) upstream callbacks. No browser receiver and no adblock engine.
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
output_root=${1:-"$repo_dir/tv/apple/Native"}
target_root=${CARGO_TARGET_DIR:-"$repo_dir/target"}
device_target=aarch64-apple-tvos
sim_target=aarch64-apple-tvos-sim

if [ "$(uname -s)" != "Darwin" ]; then
    echo "Apple TV Cast Core builds require macOS and Xcode." >&2
    exit 1
fi
if ! command -v rustup >/dev/null 2>&1; then
    echo "rustup is required. Install it from https://rustup.rs and retry." >&2
    exit 1
fi

framework="$output_root/PlayBridgeCastCore.xcframework"
if [ -e "$framework" ]; then
    echo "Remove the existing $framework before rebuilding." >&2
    exit 1
fi

if [ "${PB_APPLE_NO_INSTALL:-0}" = 1 ]; then
    installed_targets=$(rustup target list --installed --toolchain stable)
    for target in "$device_target" "$sim_target"; do
        if ! printf '%s\n' "$installed_targets" | grep -qx "$target"; then
            echo "Required Rust target is not installed: $target" >&2
            exit 1
        fi
    done
else
    rustup target add --toolchain stable "$device_target" "$sim_target"
fi

build_target() {
    target=$1
    env TVOS_DEPLOYMENT_TARGET=17.0 \
        RUSTC="$(rustup which --toolchain stable rustc)" \
        rustup run stable cargo build \
        --manifest-path "$repo_dir/Cargo.toml" \
        --package playbridge-cast-core-ffi \
        --release --locked --features receiver-proxy-apple \
        --target "$target" --target-dir "$target_root"
}

build_target "$device_target"
build_target "$sim_target"

# The adblock header declares symbols this build does not contain; ship only Cast Core.
staging=$(mktemp -d "${TMPDIR:-/tmp}/playbridge-tvos-cast.XXXXXX")
trap 'rm -rf "${staging:?}"' EXIT
mkdir -p "$staging/Headers"
cp "$script_dir/ffi/include/playbridge_cast_core.h" "$staging/Headers/"
printf '%s\n' \
    'module PlayBridgeCastCore {' \
    '  header "playbridge_cast_core.h"' \
    '  export *' \
    '}' \
    > "$staging/Headers/module.modulemap"

mkdir -p "$output_root"
xcodebuild -create-xcframework \
    -library "$target_root/$device_target/release/libplaybridge_cast_core_ffi.a" \
    -headers "$staging/Headers" \
    -library "$target_root/$sim_target/release/libplaybridge_cast_core_ffi.a" \
    -headers "$staging/Headers" \
    -output "$framework"

printf '%s\n' \
    'PB_CAST_CORE_ROOT = $(SRCROOT)/../Native/PlayBridgeCastCore.xcframework' \
    'PB_CAST_CORE_SLICE[sdk=appletvos*] = tvos-arm64' \
    'PB_CAST_CORE_SLICE[sdk=appletvsimulator*] = tvos-arm64-simulator' \
    '// tvOS has no prebuilt Rust standard library for Intel simulators.' \
    'EXCLUDED_ARCHS[sdk=appletvsimulator*] = x86_64' \
    'SWIFT_INCLUDE_PATHS = $(inherited) "$(PB_CAST_CORE_ROOT)/$(PB_CAST_CORE_SLICE)/Headers"' \
    'OTHER_LDFLAGS = $(inherited) "$(PB_CAST_CORE_ROOT)/$(PB_CAST_CORE_SLICE)/libplaybridge_cast_core_ffi.a"' \
    > "$framework/PlayBridgeCastCore.xcconfig"

for slice in tvos-arm64 tvos-arm64-simulator; do
    for symbol in pb_sender_services_start pb_proxy_upstream_set_checked_callbacks; do
        if ! nm -gU "$framework/$slice/libplaybridge_cast_core_ffi.a" 2>/dev/null | grep -Eq "[[:space:]]_${symbol}$"; then
            echo "Missing $symbol in $slice" >&2
            exit 1
        fi
    done
done

echo "Created $framework"
echo "The Apple TV target now links the receiver proxy automatically when this framework is present."
