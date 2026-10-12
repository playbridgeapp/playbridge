import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/player_chrome_policy.dart';

void main() {
  test('paused shows full chrome', () {
    expect(
      PlayerChromePolicy.resolve(playing: false, pinned: false).mode,
      PlayerChromeMode.full,
    );
  });

  test('not started stays full even while the engine reports playing', () {
    expect(
      PlayerChromePolicy.resolve(
        playing: true,
        pinned: false,
        started: false,
        fullscreen: true,
        pointerIdle: true,
      ).mode,
      PlayerChromeMode.full,
    );
  });

  test('pinned shows full chrome while playing', () {
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: true).mode,
      PlayerChromeMode.full,
    );
    expect(
      PlayerChromePolicy.resolve(
        playing: true,
        pinned: true,
        fullscreen: true,
        pointerIdle: true,
      ).mode,
      PlayerChromeMode.full,
    );
    expect(
      PlayerChromePolicy.resolve(playing: false, pinned: true).mode,
      PlayerChromeMode.full,
    );
  });

  test('playing windowed shows mini and ignores an idle pointer', () {
    expect(
      PlayerChromePolicy.resolve(
        playing: true,
        pinned: false,
        fullscreen: false,
        pointerIdle: true,
      ).mode,
      PlayerChromeMode.mini,
    );
  });

  test('playing fullscreen stays mini until the pointer is idle', () {
    expect(
      PlayerChromePolicy.resolve(
        playing: true,
        pinned: false,
        fullscreen: true,
        pointerIdle: false,
      ).mode,
      PlayerChromeMode.mini,
    );
    expect(
      PlayerChromePolicy.resolve(
        playing: true,
        pinned: false,
        fullscreen: true,
        pointerIdle: true,
      ).mode,
      PlayerChromeMode.hidden,
    );
  });

  test('resume shows mini; pause shows full', () {
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: false).mode,
      PlayerChromeMode.mini,
    );
    expect(
      PlayerChromePolicy.resolve(playing: false, pinned: false).mode,
      PlayerChromeMode.full,
    );
  });

  test('buffering after start is mini so a stall does not pin full chrome', () {
    expect(PlayerChromePolicy.isPlaying('playing'), isTrue);
    expect(PlayerChromePolicy.isPlaying('buffering'), isTrue);
    expect(PlayerChromePolicy.isPlaying('paused'), isFalse);
    expect(PlayerChromePolicy.isPlaying('idle'), isFalse);
    expect(
        PlayerChromePolicy.fullscreenIdleDuration, const Duration(seconds: 3));
    final buffering = PlayerChromePolicy.isPlaying('buffering');
    expect(
      PlayerChromePolicy.resolve(
              playing: buffering, pinned: false, started: true)
          .mode,
      PlayerChromeMode.mini,
    );
  });

  test('chrome stays full until the first frame plays', () {
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: false, started: false)
          .mode,
      PlayerChromeMode.full,
    );
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: false, started: true)
          .mode,
      PlayerChromeMode.mini,
    );
  });
}
