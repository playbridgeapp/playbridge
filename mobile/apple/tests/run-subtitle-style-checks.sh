#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
phone="$repo_root/mobile/apple/PlayBridge Phone/PlayBridge Phone"
tv_ui="$repo_root/tv/apple/PlayBridge TV/PlayBridge TV/UI"
work="$(mktemp -d /tmp/playbridge-subtitle-style.XXXXXX)"
trap 'rm -rf "$work"' EXIT

# The subtitle style and font code must stay byte-identical between tvOS and iOS.
for shared in SubtitleStyleSettings.swift SubtitleFontLibrary.swift; do
  if ! cmp -s "$tv_ui/$shared" "$phone/Subtitles/$shared"; then
    echo "FAIL: $shared differs between tv/apple and mobile/apple" >&2
    exit 1
  fi
done

swiftc -parse-as-library -module-cache-path "$work/cache" \
  "$phone/Subtitles/SubtitleStyleSettings.swift" \
  "$phone/Subtitles/SubtitleFontLibrary.swift" \
  "$phone/Subtitles/TvSubtitleStyleController.swift" \
  "$phone/Subtitles/PickedSubtitleFont.swift" \
  "$phone/Subtitles/PhoneSubtitleStyleMigration.swift" \
  "$phone/Network/PhonePlayerPreferences.swift" \
  "$phone/Network/PhonePlaybackEngine.swift" \
  "$phone/Network/WireProtocol.swift" \
  "$repo_root/mobile/apple/tests/SubtitleStyleTests.swift" \
  -o "$work/check"
"$work/check" "$phone/Fonts/Poppins-Regular.ttf"
