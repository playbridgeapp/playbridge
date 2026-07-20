#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
output_dir="$repo_dir/mobile/android/app/src/main/jniLibs"
android_api=26

if ! command -v rustup >/dev/null 2>&1; then
    echo "rustup is required. Install it from https://rustup.rs and retry." >&2
    exit 1
fi

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    ndk_dir=$ANDROID_NDK_HOME
elif [ -n "${ANDROID_HOME:-}" ]; then
    ndk_dir=$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)
elif [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    ndk_dir=$(find "$HOME/Library/Android/sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)
else
    echo "Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
    exit 1
fi

case "$(uname -s)" in
    Darwin) host_tag=darwin-x86_64 ;;
    Linux) host_tag=linux-x86_64 ;;
    *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac

toolchain="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin"
rustup target add aarch64-linux-android armv7-linux-androideabi

build_target() {
    rust_target=$1
    clang_target=$2
    android_abi=$3
    linker="$toolchain/${clang_target}${android_api}-clang"

    env \
        "CARGO_TARGET_$(printf '%s' "$rust_target" | tr '[:lower:]-' '[:upper:]_')_LINKER=$linker" \
        "CC_$(printf '%s' "$rust_target" | tr '-' '_')=$linker" \
        "AR_$(printf '%s' "$rust_target" | tr '-' '_')=$toolchain/llvm-ar" \
        cargo build \
            --manifest-path "$script_dir/Cargo.toml" \
            --package playbridge-cast-core-ffi \
            --release \
            --target "$rust_target"

    mkdir -p "$output_dir/$android_abi"
    cp "$script_dir/target/$rust_target/release/libplaybridge_cast_core_ffi.so" \
        "$output_dir/$android_abi/libplaybridge_cast_core_ffi.so"
    "$toolchain/llvm-strip" "$output_dir/$android_abi/libplaybridge_cast_core_ffi.so"
}

build_target aarch64-linux-android aarch64-linux-android arm64-v8a
build_target armv7-linux-androideabi armv7a-linux-androideabi armeabi-v7a

