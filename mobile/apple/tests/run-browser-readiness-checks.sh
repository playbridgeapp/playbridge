#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
build_dir="$(mktemp -d /private/tmp/playbridge-readiness.XXXXXX)"
trap 'rm -rf "$build_dir"' EXIT
swiftc -module-cache-path "$build_dir/cache" \
  "$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/BrowserStartupReadiness.swift" \
  "$repo_root/mobile/apple/tests/BrowserStartupReadinessTests.swift" -o "$build_dir/check"
"$build_dir/check"
