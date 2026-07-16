#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
metadata="${MPV_ANDROID_METADATA:-$repo_root/tv/android/player/app/libs/mpv-android-build.env}"
committed_aar="${1:-$repo_root/tv/android/player/app/libs/mpv-android.aar}"

# shellcheck source=/dev/null
source "$metadata"

for variable in MPV_ANDROID_REPOSITORY MPV_ANDROID_REVISION MPV_ANDROID_AAR_SHA256; do
  if [[ -z "${!variable:-}" ]]; then
    echo "Missing required metadata value: $variable" >&2
    exit 1
  fi
done

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

if [[ ! -s "$committed_aar" ]]; then
  echo "Missing or empty AAR: $committed_aar" >&2
  exit 1
fi

committed_sha="$(sha256 "$committed_aar")"
if [[ "$committed_sha" != "$MPV_ANDROID_AAR_SHA256" ]]; then
  echo "Committed AAR checksum mismatch" >&2
  echo "Expected:  $MPV_ANDROID_AAR_SHA256" >&2
  echo "Committed: $committed_sha" >&2
  exit 1
fi

archive_entries="$(unzip -Z1 "$committed_aar")"
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

echo "Verified committed dual-ABI AAR: $committed_sha"
echo "- ABIs: armeabi-v7a, arm64-v8a"
echo "- source: $MPV_ANDROID_REPOSITORY@$MPV_ANDROID_REVISION"
