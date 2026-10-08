import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/player_chrome_policy.dart';

void main() {
  test('paused shows chrome', () {
    expect(
      PlayerChromePolicy.resolve(playing: false, pinned: false).visible,
      isTrue,
    );
  });

  test('playing hides chrome immediately; hover cannot reveal it', () {
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: false).visible,
      isFalse,
    );
  });

  test('resume hides; pause shows and holds', () {
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: false).visible,
      isFalse,
    );
    expect(
      PlayerChromePolicy.resolve(playing: false, pinned: false).visible,
      isTrue,
    );
  });

  test('buffering is treated as playing so a stall does not pin chrome', () {
    expect(PlayerChromePolicy.isPlaying('playing'), isTrue);
    expect(PlayerChromePolicy.isPlaying('buffering'), isTrue);
    expect(PlayerChromePolicy.isPlaying('paused'), isFalse);
    expect(PlayerChromePolicy.isPlaying('idle'), isFalse);
    final buffering = PlayerChromePolicy.isPlaying('buffering');
    expect(
      PlayerChromePolicy.resolve(playing: buffering, pinned: false).visible,
      isFalse,
    );
  });

  test('menus, playlist, or scrubbing pin chrome while playing', () {
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: true).visible,
      isTrue,
    );
    expect(
      PlayerChromePolicy.resolve(playing: false, pinned: true).visible,
      isTrue,
    );
  });

  test('chrome stays up until the first frame plays', () {
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: false, started: false)
          .visible,
      isTrue,
    );
    expect(
      PlayerChromePolicy.resolve(playing: true, pinned: false, started: true)
          .visible,
      isFalse,
    );
  });
}
