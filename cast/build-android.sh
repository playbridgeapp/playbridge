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

# Prefer rustup-managed cargo/rustc (Homebrew cargo often lacks android std).
if [ -d "$HOME/.cargo/bin" ]; then
    PATH="$HOME/.cargo/bin:$PATH"
    export PATH
fi
if command -v rustup >/dev/null 2>&1; then
    rustup_cargo=$(rustup which cargo 2>/dev/null || true)
    rustup_rustc=$(rustup which rustc 2>/dev/null || true)
    if [ -n "$rustup_cargo" ] && [ -n "$rustup_rustc" ]; then
        cargo_dir=$(CDPATH= cd -- "$(dirname -- "$rustup_cargo")" && pwd)
        PATH="$cargo_dir:$PATH"
        export PATH
    fi
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
    rust_env_target=$(printf '%s' "$rust_target" | tr '-' '_')
    cargo_env_target=$(printf '%s' "$rust_target" | tr '[:lower:]-' '[:upper:]_')

    # openssl-sys vendored looks for $TARGET-ar / $TARGET-ranlib on PATH.
    tool_bin=$(mktemp -d "${TMPDIR:-/tmp}/playbridge-android-tools.XXXXXX")
    ln -sf "$toolchain/llvm-ar" "$tool_bin/${rust_target}-ar"
    ln -sf "$toolchain/llvm-ranlib" "$tool_bin/${rust_target}-ranlib"
    ln -sf "$linker" "$tool_bin/${rust_target}-clang"
    ln -sf "$linker" "$tool_bin/${rust_target}-gcc"
    # shellcheck disable=SC2064
    trap 'rm -rf "$tool_bin"' RETURN

    env \
        "PATH=$tool_bin:$PATH" \
        "CARGO_TARGET_${cargo_env_target}_LINKER=$linker" \
        "CC_${rust_env_target}=$linker" \
        "CXX_${rust_env_target}=$toolchain/${clang_target}${android_api}-clang++" \
        "AR_${rust_env_target}=$toolchain/llvm-ar" \
        "RANLIB_${rust_env_target}=$toolchain/llvm-ranlib" \
        "CFLAGS_${rust_env_target}=-fPIC" \
        cargo build \
            --manifest-path "$repo_dir/Cargo.toml" \
            --package playbridge-cast-core-ffi \
            --features playbridge-cast-core-ffi/sender-services \
            --release \
            --target "$rust_target"

    mkdir -p "$output_dir/$android_abi"
    cp "$repo_dir/target/$rust_target/release/libplaybridge_cast_core_ffi.so" \
        "$output_dir/$android_abi/libplaybridge_cast_core_ffi.so"
    "$toolchain/llvm-strip" "$output_dir/$android_abi/libplaybridge_cast_core_ffi.so"
}

build_target aarch64-linux-android aarch64-linux-android arm64-v8a
build_target armv7-linux-androideabi armv7a-linux-androideabi armeabi-v7a
