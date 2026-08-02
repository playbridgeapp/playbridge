#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
output_root=${1:-"$repo_dir/mobile/apple/Native"}
headers_dir="$script_dir/ffi/include"
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

rustup target add "$device_target" "$sim_arm_target" "$sim_x64_target"

build_target() {
    target=$1
    env IPHONEOS_DEPLOYMENT_TARGET=16.0 \
        rustup run stable cargo build \
        --manifest-path "$repo_dir/Cargo.toml" \
        --package playbridge-cast-core-ffi \
        --release \
        --target "$target"
}

build_target "$device_target"
build_target "$sim_arm_target"
build_target "$sim_x64_target"

staging=$(mktemp -d "${TMPDIR:-/tmp}/playbridge-apple-cast.XXXXXX")
trap 'rm -rf "$staging"' EXIT
simulator_library="$staging/libplaybridge_cast_core_ffi.a"
lipo -create \
    "$repo_dir/target/$sim_arm_target/release/libplaybridge_cast_core_ffi.a" \
    "$repo_dir/target/$sim_x64_target/release/libplaybridge_cast_core_ffi.a" \
    -output "$simulator_library"

mkdir -p "$output_root"
framework="$output_root/PlayBridgeCastCore.xcframework"
if [ -e "$framework" ]; then
    echo "Remove the existing $framework before rebuilding." >&2
    exit 1
fi
xcodebuild -create-xcframework \
    -library "$repo_dir/target/$device_target/release/libplaybridge_cast_core_ffi.a" \
    -headers "$headers_dir" \
    -library "$simulator_library" \
    -headers "$headers_dir" \
    -output "$framework"

echo "Created $framework"
echo "Add it to the iOS target's Frameworks, Libraries, and Embedded Content as Do Not Embed."
