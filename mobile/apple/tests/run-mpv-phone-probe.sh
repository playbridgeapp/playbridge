#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_dir="$(mktemp -d /tmp/playbridge-mpv-probe.XXXXXX)"
simulator="${IOS_TEST_SIMULATOR:-booted}"
fixture_pid=''
trap 'result=$?; if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; xcrun simctl uninstall "$simulator" com.playbridge.mpv-probe >/dev/null 2>&1 || true; rm -rf "$test_dir"; exit "$result"' EXIT
command -v ffmpeg >/dev/null
export MPV_FIXTURE_HOST=127.0.0.1
if [[ "${MPV_PHONE_PROXY_PROBE:-0}" == 1 ]]; then
  export MPV_FIXTURE_HOST="$(/usr/sbin/ipconfig getifaddr en0 || /usr/sbin/ipconfig getifaddr en1)"
  [[ -n "$MPV_FIXTURE_HOST" ]] || { echo 'A LAN address is required for the explicitly granted upstream fixture.' >&2; exit 1; }
fi
if [[ "${MPV_TLS_REJECTION_PROBE:-0}" == 1 ]]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -keyout "$test_dir/key.pem" -out "$test_dir/cert.pem" \
    -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' >/dev/null 2>&1
  export MPV_NETWORK_PROBE=1
fi
python3 - "$repo_root" "$test_dir" <<'PY'
from pathlib import Path
import os, shutil, sys
repo, root = map(Path, sys.argv[1:])
phone = repo / 'mobile/apple/PlayBridge Phone'
source = root / 'PlayBridge Phone'
source.mkdir()
for name in ['Network/PhonePlaybackEngine.swift', 'Network/MPVPhonePlayback.swift', 'Network/PlaybackSession.swift',
             'Network/StreamRouteService.swift', 'Network/PhonePlayerPreferences.swift',
             'UI/PhonePlayerControls.swift', 'UI/PhonePlayerScrub.swift', 'UI/PhonePlayerSettingsView.swift', 'UI/MPVPhonePlayerView.swift',
             'UI/PhonePlayerOpeningOrientationView.swift', 'UI/Theme.swift']:
    shutil.copy2(phone / 'PlayBridge Phone' / name, source / Path(name).name)
shutil.copy2(repo / 'mobile/apple/tests/MPVPhonePlaybackProbe.swift', source / 'Probe.swift')
# Exercise the actual fullscreen layout without importing the unrelated cast-sheet UI.
view = (phone / 'PlayBridge Phone/UI/CastSheet.swift').read_text().split('struct FullScreenVideoPlayerView: View {', 1)[1]
(source / 'Fullscreen.swift').write_text('import SwiftUI\nstruct FullScreenVideoPlayerView: View {' + view)
if os.environ.get('MPV_PHONE_PROXY_PROBE') == '1':
    for name in ['Network/PhoneSenderServices.swift', 'Network/AppleProxyUpstream.swift']:
        shutil.copy2(phone / 'PlayBridge Phone' / name, source / Path(name).name)
    framework = repo / 'mobile/apple/Native/PlayBridgeCastCore.xcframework'
    config = (framework / 'PlayBridgeCastCore.xcconfig').read_text().replace('$(SRCROOT)/../Native/PlayBridgeCastCore.xcframework', str(framework))
    (root / 'CastCoreOptional.xcconfig').write_text(config)
shutil.copy2(phone / 'PlayBridge Phone/Resources/MozillaRootCertificates.pem', source / 'MozillaRootCertificates.pem')
(source / 'Info.plist').write_text('<?xml version="1.0"?><plist version="1.0"><dict><key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict><key>NSLocalNetworkUsageDescription</key><string>Verify the explicitly granted local media fixture.</string></dict></plist>')
project = root / 'PlayBridge Phone.xcodeproj'
project.mkdir()
s = (phone / 'PlayBridge Phone.xcodeproj/project.pbxproj').read_text()
s = s.replace('\t\t\t\tAA0000000000000000000014 /* Verify Release Cast Core */,\n', '')
if os.environ.get('MPV_PHONE_PROXY_PROBE') != '1':
    s = s.replace('\t\t\tbaseConfigurationReference = AA0000000000000000000013 /* CastCoreOptional.xcconfig */;\n', '')
s = s.replace('"com.playbridge.PlayBridge-Phone"', '"com.playbridge.mpv-probe"')
(project / 'project.pbxproj').write_text(s)
schemes = project / 'xcshareddata/xcschemes'
schemes.mkdir(parents=True)
shutil.copy2(phone / 'PlayBridge Phone.xcodeproj/xcshareddata/xcschemes/PlayBridge Phone.xcscheme', schemes / 'PlayBridge Phone.xcscheme')
(root / 'captions.srt').write_text('1\n00:00:01,000 --> 00:00:11,000\nMKV subtitle fixture\n')
PY
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -i "$test_dir/captions.srt" -t 12 \
  -map 0:v -map 1:a -map 2:s -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -af volume=0.005 -c:s srt -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng "$test_dir/video.mkv"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=black:size=640x360:rate=24' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -i "$test_dir/captions.srt" -t 12 \
  -map 0:v -map 1:a -map 2:s -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -af volume=0.005 -c:s srt -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng "$test_dir/style.mkv"
ffmpeg -hide_banner -loglevel error -i "$test_dir/video.mkv" -map 0:v -map 0:a -c copy "$test_dir/video.mp4"
ffmpeg -hide_banner -loglevel error -i "$test_dir/video.mkv" -map 0:v -map 0:a -c copy -hls_time 4 -hls_playlist_type vod "$test_dir/video.m3u8"
ffmpeg -hide_banner -loglevel error -i "$test_dir/video.mkv" -map 0:v -map 0:a -c copy -f dash -seg_duration 4 "$test_dir/video.mpd"
ffmpeg -hide_banner -loglevel error -i "$test_dir/video.mkv" -vn -c:a pcm_s16le "$test_dir/audio.wav"
python3 - "$test_dir" <<'PY' &
import http.server, os, pathlib, sys
root = pathlib.Path(sys.argv[1]); os.chdir(root)
class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get('Cookie') != 'fixture=present, second=2' or self.headers.get('User-Agent') != 'PlayBridgeFixture':
            self.send_error(403); return
        resource = root / pathlib.Path(self.path.split('?', 1)[0]).name
        types = {'.mkv': 'video/x-matroska', '.mp4': 'video/mp4', '.m3u8': 'application/vnd.apple.mpegurl',
                 '.ts': 'video/mp2t', '.mpd': 'application/dash+xml', '.m4s': 'video/iso.segment', '.wav': 'audio/wav'}
        if resource.suffix not in types or not resource.is_file():
            self.send_error(404); return
        data = resource.read_bytes()
        start, end = 0, len(data) - 1
        header = self.headers.get('Range', '')
        if header.startswith('bytes='):
            first, last = header[6:].split('-', 1)
            start = int(first or 0); end = min(int(last) if last else end, end)
        if start > end:
            self.send_response(416); self.send_header('Content-Range', f'bytes */{len(data)}'); self.end_headers(); return
        self.send_response(206 if header else 200)
        self.send_header('Content-Type', types[resource.suffix]); self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        if header: self.send_header('Content-Range', f'bytes {start}-{end}/{len(data)}')
        self.end_headers()
        try: self.wfile.write(data[start:end + 1])
        except (BrokenPipeError, ConnectionResetError): pass
    def log_message(self, *args): pass
server = http.server.ThreadingHTTPServer((os.environ['MPV_FIXTURE_HOST'], 0), Handler)
if os.environ.get('MPV_TLS_REJECTION_PROBE') == '1':
    import ssl
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(root / 'cert.pem', root / 'key.pem')
    server.socket = context.wrap_socket(server.socket, server_side=True)
(root / 'port').write_text(str(server.server_port))
server.serve_forever()
PY
fixture_pid=$!
for attempt in {1..100}; do [[ -s "$test_dir/port" ]] && break; sleep 0.05; done
package_args=()
if [[ -n "${MPV_PACKAGE_CACHE:-}" ]]; then package_args+=(-packageCachePath "$MPV_PACKAGE_CACHE"); fi
build_dir="${MPV_PROBE_BUILD_DIR:-$test_dir/build}"
xcodebuild -project "$test_dir/PlayBridge Phone.xcodeproj" -scheme 'PlayBridge Phone' \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$build_dir" \
  ${package_args[@]+"${package_args[@]}"} CODE_SIGNING_ALLOWED=NO build > /private/tmp/playbridge-mpv-probe-build.log 2>&1
xcrun simctl install "$simulator" "$build_dir/Build/Products/Debug-iphonesimulator/PlayBridge Phone.app"
fixture_scheme=http
if [[ "${MPV_TLS_REJECTION_PROBE:-0}" == 1 ]]; then fixture_scheme=https; fi
if [[ -n "${MPV_REMOTE_FIXTURE_FILE:-}" ]]; then MPV_REMOTE_FIXTURE="$(cat "$MPV_REMOTE_FIXTURE_FILE")"; fi
SIMCTL_CHILD_MPV_FIXTURE="${MPV_REMOTE_FIXTURE:-$fixture_scheme://$MPV_FIXTURE_HOST:$(cat "$test_dir/port")/video.mkv}" \
  SIMCTL_CHILD_MPV_LAN_HOST="$MPV_FIXTURE_HOST" \
  SIMCTL_CHILD_MPV_PHONE_PROXY_PROBE="${MPV_PHONE_PROXY_PROBE:-0}" \
  SIMCTL_CHILD_MPV_FEATURES_PROBE="${MPV_FEATURES_PROBE:-0}" \
  SIMCTL_CHILD_MPV_ORIENTATION_PROBE="${MPV_ORIENTATION_PROBE:-0}" \
  SIMCTL_CHILD_MPV_OPENING_ORIENTATION="${MPV_OPENING_ORIENTATION:-}" \
  SIMCTL_CHILD_MPV_OPENING_ORIENTATION_PROBE="${MPV_OPENING_ORIENTATION_PROBE:-0}" \
  SIMCTL_CHILD_MPV_NETWORK_PROBE="${MPV_NETWORK_PROBE:-0}" \
  SIMCTL_CHILD_MPV_TLS_REJECTION_PROBE="${MPV_TLS_REJECTION_PROBE:-0}" \
  xcrun simctl launch "$simulator" com.playbridge.mpv-probe
container="$(xcrun simctl get_app_container "$simulator" com.playbridge.mpv-probe data)"
for attempt in {1..240}; do
  [[ -s "$container/Documents/result.txt" ]] && break
  if [[ -s "$container/Documents/stage.txt" ]]; then
    stage="$(cat "$container/Documents/stage.txt")"
    if [[ ! -f "$container/Documents/$stage.ack" ]]; then
      xcrun simctl io "$simulator" screenshot "/private/tmp/playbridge-mpv-$stage.png" >/dev/null
      cp "$container/Documents/$stage.geometry.txt" "/private/tmp/playbridge-mpv-$stage.geometry.txt"
      touch "$container/Documents/$stage.ack"
    fi
  fi
  sleep 0.25
done
if [[ ! -s "$container/Documents/result.txt" ]]; then echo 'FAIL: Native player probe did not complete'; exit 1; fi
cat "$container/Documents/result.txt"
xcrun simctl io "$simulator" screenshot /private/tmp/playbridge-mpv-probe.png >/dev/null
if [[ "$(head -c 4 "$container/Documents/result.txt")" != PASS ]]; then exit 1; fi
if [[ "${MPV_FEATURES_PROBE:-0}" == 1 ]]; then
  swift "$repo_root/mobile/apple/tests/VerifyMPVFeatures.swift" /private/tmp/playbridge-mpv-{fit,fill,subtitle-style,subtitle-delayed}.png
fi
if [[ "${MPV_OPENING_ORIENTATION_PROBE:-0}" == 1 ]]; then
  swift "$repo_root/mobile/apple/tests/VerifyMPVFrames.swift" /private/tmp/playbridge-mpv-native-opening.png
fi
if [[ "${MPV_ORIENTATION_PROBE:-0}" == 1 ]]; then
  swift "$repo_root/mobile/apple/tests/VerifyMPVFrames.swift" \
    /private/tmp/playbridge-mpv-{portrait,landscape-left,landscape-right,portrait-return}.png
fi
