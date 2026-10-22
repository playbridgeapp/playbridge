#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-tv-review-tests.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
source_root="$repo_root/tv/apple/PlayBridge TV/PlayBridge TV"
swiftc -module-cache-path "$test_dir/cache" \
  "$source_root/Models/PairedDevice.swift" \
  "$source_root/Models/PairingCredentialState.swift" \
  "$source_root/Data/HistoryStore.swift" \
  "$source_root/Player/PlaybackTime.swift" \
  "$source_root/Player/TimelineReport.swift" \
  "$source_root/Player/ChapterNavigation.swift" \
  "$source_root/Player/SkipSegmentFetcher.swift" \
  "$source_root/Player/PlaybackUIUpdateGate.swift" \
  "$source_root/Player/PlaybackPauseCommand.swift" \
  "$source_root/Player/PlaybackEngine.swift" \
  "$source_root/Player/PlayerRemoteNavigation.swift" \
  "$source_root/Player/TransportChrome.swift" \
  "$source_root/Player/ArtworkURL.swift" \
  "$source_root/Player/TouchScrub.swift" \
  "$source_root/Player/PlayerPlaybackReport.swift" \
  "$source_root/Player/PlayerTuning.swift" \
  "$source_root/Player/PlayerSettingsMenu.swift" \
  "$source_root/Player/PlayerAppVolume.swift" \
  "$source_root/Network/PlaybackProgressWebhook.swift" \
  "$source_root/Player/MPVHTTPHeaders.swift" \
  "$source_root/Player/MPVAudioPolicy.swift" \
  "$source_root/Player/ExternalSubtitleCatalog.swift" \
  "$source_root/Player/ExternalSubtitleDownload.swift" \
  "$source_root/Player/ExternalSubtitleCues.swift" \
  "$source_root/Network/TvCapabilities.swift" \
  "$repo_root/tv/apple/tests/ReceiverReviewTests.swift" -o "$test_dir/check"
"$test_dir/check" "$repo_root/protocol/tests/fixtures/receiver-capabilities"
