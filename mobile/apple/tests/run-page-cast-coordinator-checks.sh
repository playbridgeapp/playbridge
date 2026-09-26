#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-page-cast-coordinator.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
phone="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$phone/Models/Models.swift" \
  "$phone/Browser/PageCastRequest.swift" \
  "$phone/Browser/PageCastPermissions.swift" \
  "$phone/Browser/PageCastSource.swift" \
  "$phone/Browser/PageCastCoordinator.swift" \
  "$repo_root/mobile/apple/tests/PageCastCoordinatorTests.swift" \
  -o "$test_dir/check"
"$test_dir/check"
