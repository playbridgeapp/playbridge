#!/usr/bin/env bash
# Compiles the native-messaging host AND the "Play on TV" cast helper into
# assets/host/ so the next `flutter build <platform>` bundles them with the app.
# Run this before building a release. (Dev `flutter run` doesn't need it — the
# app also looks in build/.)
set -euo pipefail

cd "$(dirname "$0")/.."  # → desktop/

EXT=""
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) EXT=".exe" ;;
esac

mkdir -p assets/host build
for bin in playbridge_host playbridge_cast; do
  dart compile exe "bin/$bin.dart" -o "build/$bin$EXT"
  cp "build/$bin$EXT" "assets/host/$bin$EXT"
  echo "Compiled → build/$bin$EXT and assets/host/$bin$EXT"
done
