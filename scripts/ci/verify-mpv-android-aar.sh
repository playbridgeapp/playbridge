#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
metadata="${MPV_ANDROID_METADATA:-$repo_root/tv/android/player/app/libs/mpv-android-build.env}"
source_dir="${1:?Usage: verify-mpv-android-aar.sh SOURCE_DIR [GENERATED_AAR] [COMMITTED_AAR]}"
generated_aar="${2:-$source_dir/libmpv-android/build/outputs/aar/libmpv-android-release.aar}"
committed_aar="${3:-$repo_root/tv/android/player/app/libs/mpv-android.aar}"

# shellcheck source=/dev/null
source "$metadata"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

for file in "$generated_aar" "$committed_aar"; do
  if [[ ! -s "$file" ]]; then
    echo "Missing or empty AAR: $file" >&2
    exit 1
  fi
done

generated_sha="$(sha256 "$generated_aar")"
committed_sha="$(sha256 "$committed_aar")"
if [[ "$committed_sha" != "$MPV_ANDROID_AAR_SHA256" ]]; then
  echo "Committed AAR checksum mismatch" >&2
  echo "Expected:  $MPV_ANDROID_AAR_SHA256" >&2
  echo "Committed: $committed_sha" >&2
  exit 1
fi
if [[ "$generated_sha" != "$MPV_ANDROID_AAR_SHA256" ]]; then
  echo "Source-built AAR checksum mismatch" >&2
  echo "Expected:  $MPV_ANDROID_AAR_SHA256" >&2
  echo "Generated: $generated_sha" >&2
  exit 1
fi
if ! cmp -s "$generated_aar" "$committed_aar"; then
  echo "Generated and committed AARs differ despite checksum validation" >&2
  exit 1
fi

archive_entries="$(unzip -Z1 "$generated_aar")"
actual_abis="$(printf '%s\n' "$archive_entries" | awk -F/ '/^jni\/[^\/]+\/.*\.so$/ {print $2}' | sort -u)"
expected_abis=$'arm64-v8a\narmeabi-v7a'
if [[ "$actual_abis" != "$expected_abis" ]]; then
  echo "Unexpected AAR ABI set" >&2
  echo "Expected:" >&2
  printf '%s\n' "$expected_abis" >&2
  echo "Actual:" >&2
  printf '%s\n' "$actual_abis" >&2
  exit 1
fi

required_libraries=(
  libavcodec.so
  libavdevice.so
  libavfilter.so
  libavformat.so
  libavutil.so
  libc++_shared.so
  libmpv.so
  libplayer.so
  libswresample.so
  libswscale.so
)
for abi in arm64-v8a armeabi-v7a; do
  for library in "${required_libraries[@]}"; do
    if ! grep -qx "jni/$abi/$library" <<< "$archive_entries"; then
      echo "Missing jni/$abi/$library from generated AAR" >&2
      exit 1
    fi
  done
done

for build_arch in arm64 armv7l; do
  config="$source_dir/buildscripts/deps/ffmpeg/_build_$build_arch/config_components.h"
  if [[ ! -f "$config" ]]; then
    echo "Missing FFmpeg configuration for $build_arch: $config" >&2
    exit 1
  fi
  grep -qx '#define CONFIG_IMAGE_PNG_PIPE_DEMUXER 0' "$config" || {
    echo "image_png_pipe is not disabled for $build_arch" >&2
    exit 1
  }
  grep -qx '#define CONFIG_PNG_DECODER 1' "$config" || {
    echo "PNG decoding is not enabled for $build_arch" >&2
    exit 1
  }
done

echo "Verified dual-ABI source build: $generated_sha"
echo "- ABIs: armeabi-v7a, arm64-v8a"
echo "- image_png_pipe demuxer: disabled"
echo "- PNG decoder: enabled"
echo "- committed AAR: byte-identical"
