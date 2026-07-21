#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
output_root=${1:-"$repo_dir/desktop/native/cast_core"}

add_target() {
    if command -v rustup >/dev/null 2>&1; then
        rustup target add "$1"
    fi
}

case "$(uname -s)" in
    Darwin)
        add_target aarch64-apple-darwin
        add_target x86_64-apple-darwin
        cargo build --manifest-path "$repo_dir/Cargo.toml" \
            --package playbridge-cast-core-ffi --package playbridge-cast-cli --release \
            --target aarch64-apple-darwin
        cargo build --manifest-path "$repo_dir/Cargo.toml" \
            --package playbridge-cast-core-ffi --package playbridge-cast-cli --release \
            --target x86_64-apple-darwin
        mkdir -p "$output_root/macos"
        lipo -create \
            "$repo_dir/target/aarch64-apple-darwin/release/libplaybridge_cast_core_ffi.dylib" \
            "$repo_dir/target/x86_64-apple-darwin/release/libplaybridge_cast_core_ffi.dylib" \
            -output "$output_root/macos/libplaybridge_cast_core_ffi.dylib"
        install_name_tool -id "@rpath/libplaybridge_cast_core_ffi.dylib" \
            "$output_root/macos/libplaybridge_cast_core_ffi.dylib"
        lipo -create \
            "$repo_dir/target/aarch64-apple-darwin/release/playbridge" \
            "$repo_dir/target/x86_64-apple-darwin/release/playbridge" \
            -output "$output_root/macos/playbridge"
        strip -x "$output_root/macos/libplaybridge_cast_core_ffi.dylib"
        strip -x "$output_root/macos/playbridge"
        ;;
    Linux)
        add_target x86_64-unknown-linux-gnu
        cargo build --manifest-path "$repo_dir/Cargo.toml" \
            --package playbridge-cast-core-ffi --package playbridge-cast-cli --release \
            --target x86_64-unknown-linux-gnu
        mkdir -p "$output_root/linux"
        cp "$repo_dir/target/x86_64-unknown-linux-gnu/release/libplaybridge_cast_core_ffi.so" \
            "$output_root/linux/libplaybridge_cast_core_ffi.so"
        cp "$repo_dir/target/x86_64-unknown-linux-gnu/release/playbridge" \
            "$output_root/linux/playbridge"
        strip "$output_root/linux/libplaybridge_cast_core_ffi.so"
        strip "$output_root/linux/playbridge"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        add_target x86_64-pc-windows-msvc
        cargo build --manifest-path "$repo_dir/Cargo.toml" \
            --package playbridge-cast-core-ffi --package playbridge-cast-cli --release \
            --target x86_64-pc-windows-msvc
        mkdir -p "$output_root/windows"
        cp "$repo_dir/target/x86_64-pc-windows-msvc/release/playbridge_cast_core_ffi.dll" \
            "$output_root/windows/playbridge_cast_core_ffi.dll"
        cp "$repo_dir/target/x86_64-pc-windows-msvc/release/playbridge.exe" \
            "$output_root/windows/playbridge.exe"
        ;;
    *)
        echo "Unsupported desktop build host: $(uname -s)" >&2
        exit 1
        ;;
esac
