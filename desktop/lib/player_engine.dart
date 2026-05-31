import 'package:flutter/foundation.dart';

typedef QueueItem = ({
  String url,
  String title,
  Map<String, String>? headers,
  List<String>? subtitles,
});

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

  final int droppedVo;        // frames dropped by the video output (display can't keep up)
  final int droppedDecoder;   // frames dropped during decoding (CPU/GPU can't keep up)
  final double? fps;          // estimated output fps
  final double? containerFps; // source/container fps
  final double? displayFps;   // monitor refresh estimate
  final double? videoBitrate; // bits/sec
  final double? audioBitrate; // bits/sec
  final String? hwdec;        // active hardware decoder, or "no"
  final double? avsync;       // A/V desync in seconds
  final int? width;
  final int? height;
  final String? videoCodec;
  final double? cacheDuration; // seconds of demuxer cache ahead
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
