import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/mpv_failure_category.dart';

void main() {
  test('a healthy playback event clears a recovered subtitle HTTP error', () {
    final logs = MpvFailureLogState();
    logs.record('ffmpeg/https', 'error', 'HTTP error 404 subtitle missing');
    expect(logs.code, 'mpv_http_404');
    logs.playbackHealthy();
    expect(logs.code, isNull);
    logs.record('cplayer', 'error', 'Failed to open private-url');
    expect(logs.code, isNull,
        reason: 'A later generic error cannot reuse HTTP 404');
  });

  test('continued playback clears a recovered HLS segment error', () {
    final logs = MpvFailureLogState();
    logs.positionChanged(const Duration(seconds: 1),
        playing: true, buffering: false);
    logs.record('ffmpeg/https', 'error', 'HTTP error 404 segment missing');
    logs.positionChanged(const Duration(seconds: 2),
        playing: true, buffering: false);
    expect(logs.code, isNull);
    logs.record('ffmpeg/tcp', 'error', 'Connection timed out private.example');
    expect(logs.code, 'mpv_network_failed');
  });

  test('paused buffering and frozen samples preserve fresh failure evidence',
      () {
    final logs = MpvFailureLogState();
    logs.record('ffmpeg/https', 'error', 'HTTP error 403 expired');
    logs.positionChanged(Duration.zero, playing: true, buffering: false);
    logs.positionChanged(const Duration(seconds: 1),
        playing: false, buffering: false);
    logs.positionChanged(const Duration(seconds: 2),
        playing: true, buffering: true);
    expect(logs.code, 'mpv_http_403');
    logs.beginFile();
    expect(logs.code, isNull);
  });

  test('warnings and per-track decoder logs never create failure evidence', () {
    final logs = MpvFailureLogState();
    logs.record('ffmpeg/https', 'warn', 'HTTP error 404 warning');
    for (final prefix in ['vd', 'ad', 'cplayer', 'ffmpeg']) {
      logs.record(prefix, 'error', 'Failed to initialize a decoder for codec');
      logs.record(prefix, 'warn', 'Error while decoding frame!');
    }
    expect(logs.code, isNull);
  });
}
