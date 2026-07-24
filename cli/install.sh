#!/bin/sh
set -eu

REPOSITORY=${PLAYBRIDGE_REPOSITORY:-playbridgeapp/playbridge}
INSTALL_DIR=${PLAYBRIDGE_INSTALL_DIR:-"$HOME/.local/bin"}
VERSION=${PLAYBRIDGE_VERSION:-}

fail() {
  printf 'playbridge installer: %s\n' "$*" >&2
  exit 1
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v tar >/dev/null 2>&1 || fail "tar is required"

case "$(uname -s)" in
  Linux) OS=linux ;;
  Darwin) OS=macos ;;
  *) fail "unsupported operating system: $(uname -s) (use a release archive instead)" ;;
esac

case "$(uname -m)" in
  x86_64|amd64) ARCH=x86_64 ;;
  arm64|aarch64) ARCH=aarch64 ;;
  *) fail "unsupported architecture: $(uname -m)" ;;
esac

if [ -z "$VERSION" ]; then
  RELEASES=$(curl -fsSL \
    -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/$REPOSITORY/releases?per_page=100")
  VERSION=$(printf '%s\n' "$RELEASES" |
    sed -n 's/.*"tag_name": *"cli-v\([^"]*\)".*/\1/p' |
    head -n 1)
  [ -n "$VERSION" ] || fail "no CLI release was found"
fi

case "$VERSION" in
  cli-v*) VERSION=${VERSION#cli-v} ;;
esac

ASSET="playbridge-cli-$OS-$ARCH.tar.gz"
BASE_URL="https://github.com/$REPOSITORY/releases/download/cli-v$VERSION"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/playbridge-install.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM

printf 'Downloading PlayBridge CLI v%s for %s/%s...\n' "$VERSION" "$OS" "$ARCH"
curl -fL "$BASE_URL/$ASSET" -o "$TMP_DIR/$ASSET"
curl -fL "$BASE_URL/SHA256SUMS" -o "$TMP_DIR/SHA256SUMS"

EXPECTED=$(sed -n "s/  \\*$ASSET\$//p; s/  $ASSET\$//p" "$TMP_DIR/SHA256SUMS" | head -n 1)
[ -n "$EXPECTED" ] || fail "release checksum for $ASSET is missing"

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$TMP_DIR/$ASSET" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$TMP_DIR/$ASSET" | awk '{print $1}')
else
  fail "sha256sum or shasum is required to verify the download"
fi
[ "$EXPECTED" = "$ACTUAL" ] || fail "checksum verification failed"

mkdir -p "$TMP_DIR/unpacked" "$INSTALL_DIR"
tar -xzf "$TMP_DIR/$ASSET" -C "$TMP_DIR/unpacked"
install -m 755 "$TMP_DIR/unpacked/playbridge" "$INSTALL_DIR/playbridge"

printf 'Installed playbridge to %s/playbridge\n' "$INSTALL_DIR"
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) printf 'Add %s to PATH to run playbridge from any shell.\n' "$INSTALL_DIR" ;;
esac
