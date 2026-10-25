#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-phone-files-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
source_dir="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
swiftc -module-cache-path "$test_dir/cache" \
  "$source_dir/Models/PhoneMedia.swift" \
  "$source_dir/UI/PhoneFilesModel.swift" \
  "$repo_root/mobile/apple/tests/PhoneFilesModelTests.swift" -o "$test_dir/check"
"$test_dir/check"
