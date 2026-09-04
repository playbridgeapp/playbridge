#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

chmod +x \
  "$REPO/scripts/mempalace-refresh.sh" \
  "$REPO/scripts/git-hooks/post-merge" \
  "$REPO/scripts/git-hooks/post-rewrite"

git config core.hooksPath scripts/git-hooks
echo "core.hooksPath=$(git config --get core.hooksPath)"
echo "MemPalace refresh will run after pulls/rebases on main."
