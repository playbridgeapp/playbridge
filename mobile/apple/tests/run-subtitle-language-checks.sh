#!/bin/bash
# Subtitle checks on detection, subtitle languages and ranking, preferred languages sent with
# casts, and subtitles ticked in the cast sheet reaching the phone player (#512).
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
phone="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
work="$(mktemp -d /tmp/playbridge-subtitle-languages.XXXXXX)"
trap 'rm -rf "$work"' EXIT
swiftc -parse-as-library \
  "$phone/Subtitles/SubtitleLanguages.swift" "$phone/Subtitles/SubtitleContentCheck.swift" "$phone/Subtitles/PhoneSubtitleDownload.swift" "$phone/Browser/SubtitlePreview.swift" \
  "$phone/Models/DetectedVideo.swift" \
  "$phone/Network/WireProtocol.swift" \
  "$phone/Network/PhonePlayerPreferences.swift" "$phone/Network/PhonePlaybackEngine.swift" \
  "$phone/Network/StreamRouteService.swift" "$phone/Network/PlaybackSession.swift" "$phone/Network/PhonePlaybackLog.swift" \
  "$repo_root/mobile/apple/tests/TestPhonePlaybackEngine.swift" \
  "$repo_root/mobile/apple/tests/SubtitleLanguagesTests.swift" -o "$work/check"
"$work/check"
