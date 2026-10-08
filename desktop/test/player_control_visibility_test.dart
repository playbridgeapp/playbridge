import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/player_control_visibility.dart';

void main() {
  test('hides menus that have no real choice', () {
    final none = PlayerControlVisibility.resolve(
      realAudioCount: 1,
      realSubtitleCount: 0,
      queueLength: 1,
      isLinux: false,
      hasMedia: true,
      isVideo: true,
      isImage: false,
    );
    expect(none.audioMenu, isFalse);
    expect(none.subtitleMenu, isFalse);
    expect(none.queueControls, isFalse);
    expect(none.videoRenderer, isFalse);

    final some = PlayerControlVisibility.resolve(
      realAudioCount: 2,
      realSubtitleCount: 1,
      queueLength: 3,
      isLinux: true,
      hasMedia: true,
      isVideo: true,
      isImage: false,
    );
    expect(some.audioMenu, isTrue);
    expect(some.subtitleMenu, isTrue);
    expect(some.queueControls, isTrue);
    expect(some.videoRenderer, isTrue);
  });

  test('images hide video-only controls', () {
    final image = PlayerControlVisibility.resolve(
      realAudioCount: 2,
      realSubtitleCount: 1,
      queueLength: 2,
      isLinux: true,
      hasMedia: true,
      isVideo: false,
      isImage: true,
    );
    expect(image.videoRenderer, isFalse);
    expect(image.proxyToggle, isFalse);
    expect(image.speedMenu, isFalse);
    expect(image.playbackSettings, isFalse);
    expect(image.externalPlayer, isFalse);
    expect(image.queueControls, isTrue);
  });

  test('audio keeps speed and proxy, not scaling', () {
    final audio = PlayerControlVisibility.resolve(
      realAudioCount: 2,
      realSubtitleCount: 0,
      queueLength: 1,
      isLinux: true,
      hasMedia: true,
      isVideo: false,
      isImage: false,
    );
    expect(audio.speedMenu, isTrue);
    expect(audio.proxyToggle, isTrue);
    expect(audio.externalPlayer, isTrue);
    expect(audio.playbackSettings, isFalse);
    expect(audio.videoRenderer, isFalse);
  });

  test('realTrackCount ignores auto and no placeholders', () {
    expect(realTrackCount(null), 0);
    expect(
      realTrackCount([
        _Track('auto'),
        _Track('no'),
        _Track('1'),
        _Track('2'),
      ]),
      2,
    );
  });
}

class _Track {
  _Track(this.id);
  final String id;
}
