#!/usr/bin/env bash
# Signs a built PlayBridge Desktop .app with a Developer ID identity and the
# hardened runtime, as notarization requires. Signs inside-out: every nested
# Mach-O (dylibs, framework binaries, the helper executables shipped as Flutter
# assets), then each nested bundle, then the app with its entitlements.
#
# Usage: scripts/sign_macos.sh <path/to/App.app> "<Developer ID Application: …>" <entitlements.plist>
set -euo pipefail

app=${1:?app path}
identity=${2:?signing identity}
entitlements=${3:?entitlements plist}

sign() {
  codesign --force --timestamp --options runtime --sign "$identity" "$@"
}

# -depth lists a directory's contents before the directory, so nested code is
# signed before the bundle that seals it.
while IFS= read -r -d '' path; do
  if [ -L "$path" ]; then
    continue
  elif [ -d "$path" ]; then
    case "$path" in
      *.framework | *.bundle | *.xpc | *.app) sign "$path" ;;
    esac
  elif file -b "$path" | grep -q '^Mach-O'; then
    sign "$path"
  fi
done < <(find "$app/Contents" -depth \( -type f -o -type d -o -type l \) -not -path '*/_CodeSignature*' -print0)

sign --entitlements "$entitlements" "$app"
codesign --verify --deep --strict --verbose=2 "$app"
