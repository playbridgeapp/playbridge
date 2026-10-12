/// Desktop player chrome while a title is on screen.
///
/// * [PlayerChromeMode.full] — title and the full control bar. Used while
///   paused, before the first frame (opening / initial buffering), or pinned
///   (menu, playlist, or scrub).
/// * [PlayerChromeMode.mini] — thin bottom bar while playing, including
///   buffering after the first frame. Windowed playback stays on mini.
/// * [PlayerChromeMode.hidden] — fullscreen playback after the pointer has
///   been still for [PlayerChromePolicy.fullscreenIdleDuration]. The cursor
///   hides with the bar; any pointer movement over the player returns to mini.
enum PlayerChromeMode { full, mini, hidden }

class PlayerChromePolicy {
  const PlayerChromePolicy({required this.mode});

  final PlayerChromeMode mode;

  /// Fullscreen playing chrome hides after the pointer stays still this long.
  static const fullscreenIdleDuration = Duration(seconds: 3);

  /// Buffering is still "playing" so a stall does not switch back to full.
  static bool isPlaying(String state) =>
      state == 'playing' || state == 'buffering';

  /// [pointerIdle] hides chrome only when [fullscreen] is also set. Windowed
  /// playback keeps [PlayerChromeMode.mini]. Pin, pause, and not-yet-started
  /// always resolve to [PlayerChromeMode.full].
  static PlayerChromePolicy resolve({
    required bool playing,
    required bool pinned,
    bool started = true,
    bool fullscreen = false,
    bool pointerIdle = false,
  }) {
    if (pinned || !started || !playing) {
      return const PlayerChromePolicy(mode: PlayerChromeMode.full);
    }
    if (fullscreen && pointerIdle) {
      return const PlayerChromePolicy(mode: PlayerChromeMode.hidden);
    }
    return const PlayerChromePolicy(mode: PlayerChromeMode.mini);
  }
}
