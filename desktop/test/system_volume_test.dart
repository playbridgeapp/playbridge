import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/system_volume.dart';
import 'package:playbridge_desktop/protocol.dart';

void main() {
  test('PipeWire parses mute marker and clamps boosted volume', () {
    expect(parseWpctlVolume('Volume: 0.42 [MUTED]')!.muted, true);
    expect(parseWpctlVolume('Volume: 0.00')!.muted, false);
    expect(parseWpctlVolume('Volume: 1.50')!.level, 1);
    expect(parseWpctlVolume('unavailable'), isNull);
  });
  test('PulseAudio averages channels and reads separate explicit mute', () {
    final state = parsePactlVolume(
        'Volume: front-left: 26214 / 40% / -23dB, front-right: 39321 / 60% / -13dB',
        'Mute: no\n')!;
    expect(state.level, .5);
    expect(state.muted, false);
    expect(
        parsePactlVolume('Volume: mono: 0 / 0% / -inf dB', 'Mute: yes')!.muted,
        true);
    expect(parsePactlVolume('unavailable', 'Mute: no'), isNull);
  });
  test('ALSA parses channel switches, including absent mute reporting', () {
    final state = parseAmixerVolume(
        'Front Left: Playback 30 [30%] [-12dB] [off]\nFront Right: Playback 50 [50%] [-6dB] [off]')!;
    expect(state.level, .4);
    expect(state.muted, true);
    expect(parseAmixerVolume('Mono: Playback 0 [0%] [on]')!.muted, false);
    expect(parseAmixerVolume('Mono: Playback 50 [50%]')!.muted, isNull);
    expect(parseAmixerVolume('Capture 50 [50%]'), isNull);
  });
  test('Windows Core Audio channel data is normalized and explicit', () {
    expect(parseWindowsVolume({'level': .62, 'muted': false})!.level, .62);
    expect(parseWindowsVolume({'level': 0, 'muted': false})!.muted, false);
    for (final value in [
      null,
      <Object?, Object?>{'level': double.nan, 'muted': false},
      {'level': 101, 'muted': false},
      {'level': .5}
    ]) {
      expect(parseWindowsVolume(value), isNull);
    }
  });
  test('publication suppresses unchanged values but resync bypasses cache', () {
    final publication = VolumeStatePublication();
    final state = SystemVolumeState(0, false).frame();
    expect(publication.changed(state), state);
    expect(publication.changed(Map.of(state)), isNull);
    expect(publication.sync(state), state);
    expect(publication.changed({...state, 'muted': true}), isNotNull);
    expect(
        publication
            .changed({'type': 'volume_state', 'scope': 'app', 'level': .3}),
        isNotNull);
    publication.reset();
    expect(publication.changed(state), state);
  });
  test('absolute dispatch accepts only a numeric normalized system command',
      () {
    expect(
        parseCommand(
            '{"type":"command","action":"volume","payload":{"scope":"system","level":0.2}}'),
        isA<VolumeCmd>());
    for (final payload in [
      '{"scope":"app","level":0.2}',
      '{"scope":"system","level":20}',
      '{"scope":"system","level":"0.2"}'
    ]) {
      expect(
          parseCommand(
              '{"type":"command","action":"volume","payload":$payload}'),
          isA<UnknownCmd>());
    }
  });
}
