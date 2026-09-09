#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
source_root="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
test_root="$repo_root/mobile/apple/tests"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/playbridge-ios-fixtures.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT
compiler=(swiftc -module-cache-path "$build_dir/modules" "$source_root/Browser/StreamDebugTrace.swift")

"${compiler[@]}" "$source_root/Models/DetectedVideo.swift" \
  "$source_root/Browser/HLSParser.swift" "$source_root/Browser/DASHParser.swift" \
  "$test_root/CastStreamRankingTests.swift" -o "$build_dir/ranking"
"$build_dir/ranking"
"${compiler[@]}" "$source_root/Models/DetectedVideo.swift" \
  "$source_root/Browser/VideoDetector.swift" "$test_root/VideoDetectorEnrichmentTests.swift" \
  -o "$build_dir/enrichment"
"$build_dir/enrichment"
"${compiler[@]}" "$source_root/Models/DetectedVideo.swift" \
  "$source_root/Browser/HLSParser.swift" "$source_root/Browser/HLSPreviewSample.swift" \
  "$test_root/HLSPreviewSampleTests.swift" -o "$build_dir/sample"
"$build_dir/sample"
"${compiler[@]}" "$source_root/Browser/TransportStreamThumbnail.swift" \
  "$test_root/TransportStreamThumbnailTests.swift" -o "$build_dir/transport"
"$build_dir/transport"
node "$test_root/DetectionScriptLifecycleTests.js"

"${compiler[@]}" -D DEBUG "$test_root/StreamDebugTraceTests.swift" -o "$build_dir/debug-trace"
"$build_dir/debug-trace"
"${compiler[@]}" "$test_root/StreamDebugTraceTests.swift" -o "$build_dir/release-trace"
"$build_dir/release-trace"
