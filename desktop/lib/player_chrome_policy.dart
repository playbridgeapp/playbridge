/// Show/hide rules for the desktop player chrome, matching Android TV:
/// visible only while paused or pinned; playing hides it immediately.
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
  }) =>
      PlayerChromePolicy(visible: pinned || !playing);
}
