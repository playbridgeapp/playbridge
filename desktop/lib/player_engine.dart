import 'package:flutter/foundation.dart';

typedef QueueItem = ({
  String url,
  String title,
  Map<String, String>? headers,
  List<String>? subtitles,
});

enum EngineType { 
  mpvInternal, 
  mpvExternal, 
  vlcExternal 
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
