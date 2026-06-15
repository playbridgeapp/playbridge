import 'package:flutter/foundation.dart';

/// One playlist entry. Carries the playback essentials the engines consume
/// (url/title/headers/subtitles) plus the protocol metadata the server echoes
/// back to the phone in `playlist_status` (season/episode/imdbId/bingeGroup)
/// and the resume point ([startPositionMs]).
class QueueItem {
  QueueItem({
    required this.url,
    required this.title,
    this.headers,
    this.subtitles,
    this.startPositionMs,
    this.bingeGroup,
    this.season,
    this.episode,
    this.imdbId,
    this.backdropUrl,
    this.posterUrl,
    this.logoUrl,
    this.overview,
    this.year,
    this.rating,
    this.runtime,
    this.episodeTitle,
  });

  final String url;
  final String title;
  final Map<String, String>? headers;
  final List<String>? subtitles;

  /// Resume point (ms) seeded from the phone's resume store. Mutable because
  /// it is consumed (nulled) after the first seek, so re-playing this item
  /// from the playlist overlay starts from the beginning.
  int? startPositionMs;

  /// Stremio bingeGroup — echoed in `playlist_status` so the phone can
  /// re-attach its lazy episode queue after a restart.
  final String? bingeGroup;
  final int? season;
  final int? episode;
  final String? imdbId;

  // Visual metadata for the pre-play screen (from the payload's VisualMetadata).
  final String? backdropUrl;
  final String? posterUrl;
  final String? logoUrl;
  final String? overview;
  final String? year;
  final String? rating;
  final String? runtime;
  final String? episodeTitle;

  /// True when there's enough metadata to render a pre-play screen.
  bool get hasPrePlayMetadata => backdropUrl != null || posterUrl != null;
}

enum EngineType {
  mpvInternal,
}

/// Live playback statistics sampled from the player (currently only the internal
/// MPV engine). All fields are nullable because a property may be unavailable
/// until the stream is fully open.
class PlaybackStats {
  const PlaybackStats({
    this.droppedVo = 0,
    this.droppedDecoder = 0,
    this.fps,
    this.containerFps,
    this.displayFps,
    this.videoBitrate,
    this.audioBitrate,
    this.hwdec,
    this.avsync,
    this.width,
    this.height,
    this.videoCodec,
    this.cacheDuration,
  });

  final int
      droppedVo; // frames dropped by the video output (display can't keep up)
  final int
      droppedDecoder; // frames dropped during decoding (CPU/GPU can't keep up)
  final double? fps; // estimated output fps
  final double? containerFps; // source/container fps
  final double? displayFps; // monitor refresh estimate
  final double? videoBitrate; // bits/sec
  final double? audioBitrate; // bits/sec
  final String? hwdec; // active hardware decoder, or "no"
  final double? avsync; // A/V desync in seconds
  final int? width;
  final int? height;
  final String? videoCodec;
  final double? cacheDuration; // seconds of demuxer cache ahead
}

/// Engine-agnostic description of a selectable track, in the shape the phone
/// remote expects (`tracks` message: id/name/selected).
class TrackInfo {
  const TrackInfo(
      {required this.id, required this.name, this.selected = false});
  final String id;
  final String name;
  final bool selected;
}

abstract class PlayerEngine extends ChangeNotifier {
  String get state; // idle | buffering | playing | paused | ended
  int get positionMs;
  int get durationMs;
  double get volume => 1.0;

  // Track management (optional, implementations can return empty/no-op)
  dynamic get tracks;
  dynamic get track;
  Future<void> setAudioTrack(dynamic t);
  Future<void> setSubtitleTrack(dynamic t);

  /// Engine-agnostic track lists for the phone remote. Default: none.
  List<TrackInfo> get audioTrackInfos => const [];
  List<TrackInfo> get subtitleTrackInfos => const [];

  /// Select a track by the id previously reported in [audioTrackInfos] /
  /// [subtitleTrackInfos] ("auto"/"no" allowed). Default: no-op.
  Future<void> selectAudioTrackById(String id) async {}
  Future<void> selectSubtitleTrackById(String id) async {}

  Future<void> open(QueueItem item);
  Future<void> openPlaylist(List<QueueItem> items, int startIndex) =>
      open(items[startIndex]);
  Future<void> resume();
  Future<void> pause();
  Future<void> seek(Duration position);
  Future<void> setVolume(double volume) async {}
  Future<void> stop();
  @override
  Future<void> dispose();
}
