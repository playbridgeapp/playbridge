#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
output_root=${1:-"$repo_dir/mobile/apple/Native"}
headers_dir="$script_dir/ffi/include"
target_root=${CARGO_TARGET_DIR:-"$repo_dir/target"}
device_target=aarch64-apple-ios
sim_arm_target=aarch64-apple-ios-sim
sim_x64_target=x86_64-apple-ios

if [ "$(uname -s)" != "Darwin" ]; then
    echo "Apple Cast Core builds require macOS and Xcode." >&2
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
    for target in "$device_target" "$sim_arm_target" "$sim_x64_target"; do
        if ! printf '%s\n' "$installed_targets" | grep -qx "$target"; then
            echo "Required Rust target is not installed: $target" >&2
            exit 1
        fi
    done
else
    rustup target add --toolchain stable "$device_target" "$sim_arm_target" "$sim_x64_target"
fi

build_target() {
    target=$1
    env IPHONEOS_DEPLOYMENT_TARGET=16.0 \
        RUSTC="$(rustup which --toolchain stable rustc)" \
        rustup run stable cargo build \
        --manifest-path "$repo_dir/Cargo.toml" \
        --package playbridge-cast-core-ffi \
        --release --locked --features sender-services-apple \
        --target "$target" --target-dir "$target_root"
}

build_target "$device_target"
build_target "$sim_arm_target"
build_target "$sim_x64_target"

staging=$(mktemp -d "${TMPDIR:-/tmp}/playbridge-apple-cast.XXXXXX")
trap 'rm -rf "$staging"' EXIT
simulator_library="$staging/libplaybridge_cast_core_ffi.a"
lipo -create \
    "$target_root/$sim_arm_target/release/libplaybridge_cast_core_ffi.a" \
    "$target_root/$sim_x64_target/release/libplaybridge_cast_core_ffi.a" \
    -output "$simulator_library"

mkdir -p "$output_root"
xcodebuild -create-xcframework \
    -library "$target_root/$device_target/release/libplaybridge_cast_core_ffi.a" \
    -headers "$headers_dir" \
    -library "$simulator_library" \
    -headers "$headers_dir" \
    -output "$framework"

printf '%s\n' \
    'PB_CAST_CORE_ROOT = $(SRCROOT)/../Native/PlayBridgeCastCore.xcframework' \
    'PB_CAST_CORE_SLICE[sdk=iphoneos*] = ios-arm64' \
    'PB_CAST_CORE_SLICE[sdk=iphonesimulator*] = ios-arm64_x86_64-simulator' \
    'SWIFT_INCLUDE_PATHS = $(inherited) "$(PB_CAST_CORE_ROOT)/$(PB_CAST_CORE_SLICE)/Headers"' \
    'OTHER_LDFLAGS = $(inherited) "$(PB_CAST_CORE_ROOT)/$(PB_CAST_CORE_SLICE)/libplaybridge_cast_core_ffi.a"' \
    > "$framework/PlayBridgeCastCore.xcconfig"

python3 "$script_dir/check-apple-abi.py" "$framework"

echo "Created $framework"
echo "The iOS phone target now links Cast Core automatically when this framework is present."
