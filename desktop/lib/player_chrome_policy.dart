/// Show/hide rules for the desktop player chrome, matching Android TV:
/// visible only while paused or pinned; playing hides it immediately.
/// Until the first frame plays (opening / initial buffering) it stays up so
/// a stalled start never leaves the user without controls.
class PlayerChromePolicy {
  const PlayerChromePolicy({required this.visible});

  final bool visible;

  /// Buffering is still "playing" so a stall does not pin the bar.
  static bool isPlaying(String state) =>
      state == 'playing' || state == 'buffering';

  /// Hover never reveals chrome while playing. Pin (menu / playlist / scrub)
  /// holds it in every state.
  static PlayerChromePolicy resolve({
    required bool playing,
    required bool pinned,
    bool started = true,
  }) =>
      PlayerChromePolicy(visible: pinned || !started || !playing);
}
