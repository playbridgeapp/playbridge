#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
metadata="${MPV_ANDROID_METADATA:-$repo_root/tv/android/player/app/libs/mpv-android-build.env}"
work_dir="${MPV_ANDROID_WORK_DIR:-$repo_root/.build/mpv-android}"
source_dir="$work_dir/source"

if [[ ! -f "$metadata" ]]; then
  echo "Missing mpv-android build metadata: $metadata" >&2
  exit 1
fi

# shellcheck source=/dev/null
source "$metadata"

required_variables=(
  MPV_ANDROID_REPOSITORY
  MPV_ANDROID_REVISION
  MPV_REVISION
  DAV1D_REVISION
  FFMPEG_REVISION
  FREETYPE_REVISION
  LIBASS_REVISION
  LIBPLACEBO_REVISION
  GAS_PREPROCESSOR_REVISION
)
for variable in "${required_variables[@]}"; do
  if [[ -z "${!variable:-}" ]]; then
    echo "Missing required metadata value: $variable" >&2
    exit 1
  fi
done

if [[ -e "$source_dir" ]]; then
  echo "Build source directory already exists: $source_dir" >&2
  echo "Use an empty MPV_ANDROID_WORK_DIR for a reproducible build." >&2
  exit 1
fi

mkdir -p "$source_dir"
git -C "$source_dir" init --quiet
git -C "$source_dir" remote add origin "https://github.com/$MPV_ANDROID_REPOSITORY.git"
git -C "$source_dir" fetch --depth=1 origin "$MPV_ANDROID_REVISION"
git -C "$source_dir" checkout --detach --quiet FETCH_HEAD

actual_fork_revision="$(git -C "$source_dir" rev-parse HEAD)"
if [[ "$actual_fork_revision" != "$MPV_ANDROID_REVISION" ]]; then
  echo "Fork revision mismatch: expected $MPV_ANDROID_REVISION, got $actual_fork_revision" >&2
  exit 1
fi

cd "$source_dir/buildscripts"

if [[ -n "${ANDROID_HOME:-}" && ! -e sdk/android-sdk-linux ]]; then
  mkdir -p sdk
  ln -s "$ANDROID_HOME" sdk/android-sdk-linux
fi

IN_CI=1 ./include/download-sdk.sh

# Upstream's SDK helper downloads this script from a moving master branch.
# Replace it with the exact revision used by the committed AAR before any
# native compilation begins.
wget --quiet \
  "https://raw.githubusercontent.com/FFmpeg/gas-preprocessor/$GAS_PREPROCESSOR_REVISION/gas-preprocessor.pl" \
  -O sdk/bin/gas-preprocessor.pl
chmod +x sdk/bin/gas-preprocessor.pl

IN_CI=1 ./include/download-deps.sh

pin_dependency() {
  local dependency="$1"
  local revision="$2"
  local dependency_dir="deps/$dependency"

  if [[ ! -d "$dependency_dir/.git" ]]; then
    echo "Expected a Git checkout for $dependency at $dependency_dir" >&2
    exit 1
  fi

  git -C "$dependency_dir" fetch --depth=1 origin "$revision"
  git -C "$dependency_dir" checkout --detach --quiet "$revision"

  local actual_revision
  actual_revision="$(git -C "$dependency_dir" rev-parse HEAD)"
  if [[ "$actual_revision" != "$revision" ]]; then
    echo "$dependency revision mismatch: expected $revision, got $actual_revision" >&2
    exit 1
  fi
}

pin_dependency dav1d "$DAV1D_REVISION"
pin_dependency ffmpeg "$FFMPEG_REVISION"
pin_dependency freetype2 "$FREETYPE_REVISION"
pin_dependency libass "$LIBASS_REVISION"
pin_dependency libplacebo "$LIBPLACEBO_REVISION"
pin_dependency mpv "$MPV_REVISION"

git -C deps/freetype2 submodule update --init --recursive --depth=1
git -C deps/libplacebo submodule update --init --recursive --depth=1

build_cores="${MPV_ANDROID_BUILD_CORES:-4}"
cores="$build_cores" ./buildall.sh --arch armv7l
cores="$build_cores" ./buildall.sh --arch arm64

aar_path="$source_dir/libmpv-android/build/outputs/aar/libmpv-android-release.aar"
if [[ ! -s "$aar_path" ]]; then
  echo "The source build did not produce an AAR at $aar_path" >&2
  exit 1
fi

echo "Built source AAR: $aar_path"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "aar_path=$aar_path" >> "$GITHUB_OUTPUT"
  echo "source_dir=$source_dir" >> "$GITHUB_OUTPUT"
fi
