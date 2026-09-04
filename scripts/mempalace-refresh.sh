#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

if ! command -v mempalace >/dev/null 2>&1; then
  echo "mempalace not on PATH; skipping palace refresh" >&2
  exit 0
fi

mempalace mine "$REPO" --wing playbridge
mempalace sync "$REPO" --wing playbridge --apply
