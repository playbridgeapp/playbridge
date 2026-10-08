/// Which player-bar actions have a meaningful choice right now.
///
/// Icons with nothing to offer are not rendered — a disabled control is still
/// clutter. Images hide video-only actions.
class PlayerControlVisibility {
  const PlayerControlVisibility({
    required this.audioMenu,
    required this.subtitleMenu,
    required this.queueControls,
    required this.videoRenderer,
    required this.proxyToggle,
    required this.speedMenu,
    required this.playbackSettings,
    required this.externalPlayer,
  });

  /// More than one real audio track (not `auto` / `no`).
  final bool audioMenu;

  /// At least one real subtitle track (embedded or external).
  final bool subtitleMenu;

  /// Playlist prev/next/drawer when the queue has more than one item.
  final bool queueControls;

  /// Linux software/hardware renderer — video only.
  final bool videoRenderer;

  /// Direct ↔ proxy, only when there is non-image media.
  final bool proxyToggle;

  /// Speed is meaningful for timed A/V, not still images.
  final bool speedMenu;

  /// Fit/Zoom/Fill and subtitle delay — video only.
  final bool playbackSettings;

  /// Open in mpv/VLC — not for still images.
  final bool externalPlayer;

  factory PlayerControlVisibility.resolve({
    required int realAudioCount,
    required int realSubtitleCount,
    required int queueLength,
    required bool isLinux,
    required bool hasMedia,
    required bool isVideo,
    required bool isImage,
  }) {
    final timed = hasMedia && !isImage;
    return PlayerControlVisibility(
      audioMenu: realAudioCount > 1,
      subtitleMenu: realSubtitleCount >= 1,
      queueControls: queueLength > 1,
      videoRenderer: isLinux && isVideo,
      proxyToggle: timed,
      speedMenu: timed,
      playbackSettings: isVideo,
      externalPlayer: timed,
    );
  }
}

/// Count selectable tracks, ignoring mpv's `auto` / `no` placeholders.
int realTrackCount(dynamic tracks) {
  if (tracks is! Iterable) return 0;
  var n = 0;
  for (final t in tracks) {
    final id = t.id;
    if (id is String && id != 'no' && id != 'auto') n++;
  }
  return n;
}
