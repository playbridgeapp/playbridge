import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:media_kit/media_kit.dart';
import '../player_engine.dart';

class MpvEngine extends PlayerEngine {
  MpvEngine() {
    _configureMpv();
    _subs.addAll([
      player.stream.playing.listen((_) => notifyListeners()),
      player.stream.position.listen((_) => _emitThrottled()),
      player.stream.duration.listen((_) => notifyListeners()),
      player.stream.buffering.listen((_) => notifyListeners()),
      player.stream.volume.listen((_) => notifyListeners()),
      player.stream.completed.listen((done) {
        if (done) onCompleted?.call();
        notifyListeners();
      }),
      player.stream.tracks.listen((_) => notifyListeners()),
      player.stream.track.listen((_) => notifyListeners()),
      player.stream.error.listen((e) {
        debugPrint('[mpv] error: $e');
        notifyListeners();
      }),
    ]);
  }

  final Player player = Player();
  final List<StreamSubscription> _subs = [];
  VoidCallback? onCompleted;

  DateTime _lastPositionEmit = DateTime.fromMillisecondsSinceEpoch(0);
  static const _positionEmitInterval = Duration(milliseconds: 200);

  void _emitThrottled() {
    final now = DateTime.now();
    if (now.difference(_lastPositionEmit) < _positionEmitInterval) return;
    _lastPositionEmit = now;
    notifyListeners();
  }

  @override
  String get state {
    if (player.state.completed) return 'ended';
    if (player.state.buffering) return 'buffering';
    if (player.state.playing) return 'playing';
    return 'paused'; // Simplified, Controller handles 'idle'
  }

  @override
  int get positionMs => player.state.position.inMilliseconds;
  @override
  int get durationMs => player.state.duration.inMilliseconds;
  @override
  double get volume => player.state.volume / 100.0;

  @override
  Tracks get tracks => player.state.tracks;
  @override
  Track get track => player.state.track;

  // ─── Track selection ────────────────────────────────────────────────────
  //
  // Selections are remembered as *language* preferences (plus an explicit
  // subs-off flag) and re-applied after every item open, so a pick made on
  // episode 3 carries into episode 4 — track ids differ between files, but
  // languages don't.

  String? _preferredAudioLang;
  String? _preferredSubLang;
  bool _subsOff = false;

  @override
  Future<void> setAudioTrack(dynamic t) async {
    final track = t as AudioTrack;
    _rememberAudioPref(track);
    await player.setAudioTrack(track);
  }

  @override
  Future<void> setSubtitleTrack(dynamic t) async {
    final track = t as SubtitleTrack;
    _rememberSubPref(track);
    await player.setSubtitleTrack(track);
  }

  void _rememberAudioPref(AudioTrack track) {
    if (track.id == 'auto') {
      _preferredAudioLang = null;
    } else if (track.id != 'no') {
      final lang = track.language;
      if (lang != null && lang.isNotEmpty) _preferredAudioLang = lang;
    }
  }

  void _rememberSubPref(SubtitleTrack track) {
    if (track.id == 'no') {
      _subsOff = true;
      _preferredSubLang = null;
    } else if (track.id == 'auto') {
      _subsOff = false;
      _preferredSubLang = null;
    } else {
      _subsOff = false;
      final lang = track.language;
      if (lang != null && lang.isNotEmpty) _preferredSubLang = lang;
    }
  }

  String _trackName(String id, String? title, String? language, int n) {
    if (title != null && title.isNotEmpty) {
      return language != null && language.isNotEmpty
          ? '$title ($language)'
          : title;
    }
    if (language != null && language.isNotEmpty) return language;
    return 'Track $n';
  }

  bool _isRealTrack(String id) => id != 'auto' && id != 'no';

  @override
  List<TrackInfo> get audioTrackInfos {
    final current = player.state.track.audio.id;
    final real =
        player.state.tracks.audio.where((t) => _isRealTrack(t.id)).toList();
    return [
      for (var i = 0; i < real.length; i++)
        TrackInfo(
          id: real[i].id,
          name: _trackName(real[i].id, real[i].title, real[i].language, i + 1),
          selected: real[i].id == current,
        ),
    ];
  }

  @override
  List<TrackInfo> get subtitleTrackInfos {
    final current = player.state.track.subtitle.id;
    final real =
        player.state.tracks.subtitle.where((t) => _isRealTrack(t.id)).toList();
    return [
      TrackInfo(id: 'no', name: 'Off', selected: current == 'no'),
      for (var i = 0; i < real.length; i++)
        TrackInfo(
          id: real[i].id,
          name: _trackName(real[i].id, real[i].title, real[i].language, i + 1),
          selected: real[i].id == current,
        ),
    ];
  }

  @override
  Future<void> selectAudioTrackById(String id) async {
    if (id == 'auto') return setAudioTrack(AudioTrack.auto());
    if (id == 'no') return setAudioTrack(AudioTrack.no());
    final match = player.state.tracks.audio.where((t) => t.id == id);
    if (match.isNotEmpty) await setAudioTrack(match.first);
  }

  @override
  Future<void> selectSubtitleTrackById(String id) async {
    if (id == 'auto') return setSubtitleTrack(SubtitleTrack.auto());
    if (id == 'no') return setSubtitleTrack(SubtitleTrack.no());
    final match = player.state.tracks.subtitle.where((t) => t.id == id);
    if (match.isNotEmpty) await setSubtitleTrack(match.first);
  }

  /// Re-apply remembered preferences once the new item's tracks are known.
  /// Called after every open; waits (bounded) for mpv to enumerate tracks.
  Future<void> _reapplyTrackPrefs() async {
    if (_preferredAudioLang == null && _preferredSubLang == null && !_subsOff) {
      return;
    }
    var retries = 0;
    while (player.state.tracks.audio.where((t) => _isRealTrack(t.id)).isEmpty &&
        retries < 30) {
      await Future.delayed(const Duration(milliseconds: 100));
      retries++;
    }

    final audioLang = _preferredAudioLang;
    if (audioLang != null) {
      final match = player.state.tracks.audio
          .where((t) => _isRealTrack(t.id) && t.language == audioLang);
      if (match.isNotEmpty) await player.setAudioTrack(match.first);
    }

    if (_subsOff) {
      await player.setSubtitleTrack(SubtitleTrack.no());
    } else {
      final subLang = _preferredSubLang;
      if (subLang != null) {
        final match = player.state.tracks.subtitle
            .where((t) => _isRealTrack(t.id) && t.language == subLang);
        if (match.isNotEmpty) await player.setSubtitleTrack(match.first);
      }
    }
  }

  @override
  Future<void> open(QueueItem item) => openPlaylist([item], 0);

  @override
  Future<void> openPlaylist(List<QueueItem> items, int startIndex) async {
    final playlist = Playlist(
      items.map((i) => Media(i.url, httpHeaders: i.headers)).toList(),
      index: startIndex,
    );
    await player.open(playlist, play: true);

    // External subtitles for the current item
    final item = items[startIndex.clamp(0, items.length - 1)];
    final subs = item.subtitles ?? const <String>[];
    if (subs.isNotEmpty) {
      final native = player.platform;
      if (native is NativePlayer) {
        for (var i = 0; i < subs.length; i++) {
          try {
            await native
                .command(['sub-add', subs[i], 'auto', 'External ${i + 1}']);
          } catch (e) {
            debugPrint('[mpv] sub-add failed: $e');
          }
        }
      }
    }
    await player.play();
    unawaited(_reapplyTrackPrefs());
  }

  @override
  Future<void> resume() => player.play();
  @override
  Future<void> pause() => player.pause();
  @override
  Future<void> seek(Duration position) => player.seek(position);
  @override
  Future<void> setVolume(double volume) => player.setVolume(volume * 100.0);
  @override
  Future<void> stop() => player.stop();

  @override
  Future<void> dispose() async {
    _statsTimer?.cancel();
    stats.dispose();
    for (final s in _subs) {
      await s.cancel();
    }
    await player.dispose();
    super.dispose();
  }

  Future<void> _configureMpv() async {
    final native = player.platform;
    if (native is NativePlayer) {
      try {
        await native.setProperty('hwdec', 'auto-safe');
        await native.setProperty('cache', 'yes');
        await native.setProperty('demuxer-max-bytes', '314572800');
        await native.setProperty('demuxer-max-back-bytes', '104857600');
        await native.setProperty('network-timeout', '60');
        // Tuning ported from the tvOS receiver (see MPVPlayerView.swift):
        // - framedrop=decoder+vo: when decode can't keep up (high-bitrate HEVC),
        //   drop at the decoder too instead of letting A/V drift — the default
        //   "vo" only drops already-decoded frames.
        // - demuxer-readahead-secs + audio-buffer: debrid hosts stall in bursts;
        //   a deeper time-based readahead and a 1s audio buffer ride over them.
        // (video-sync=display-resync is deliberately NOT set: media_kit's render
        // API doesn't report vsync timing, so it can't engage and may misbehave.)
        await native.setProperty('framedrop', 'decoder+vo');
        await native.setProperty('demuxer-readahead-secs', '30');
        await native.setProperty('audio-buffer', '1.0');
      } catch (e) {
        debugPrint('[mpv] failed to tune: $e');
      }
    }
  }

  // ─── Live playback stats (driven on-demand by the stats overlay) ───────────

  /// Latest sampled stats, or null while collection is off. Sampled ~1/sec only
  /// while [setStatsCollecting] is enabled, so there's zero cost when hidden.
  final ValueNotifier<PlaybackStats?> stats =
      ValueNotifier<PlaybackStats?>(null);
  Timer? _statsTimer;

  void setStatsCollecting(bool active) {
    if (active) {
      if (_statsTimer != null) return;
      unawaited(_pollStats());
      _statsTimer =
          Timer.periodic(const Duration(seconds: 1), (_) => _pollStats());
    } else {
      _statsTimer?.cancel();
      _statsTimer = null;
      stats.value = null;
    }
  }

  Future<void> _pollStats() async {
    final native = player.platform;
    if (native is! NativePlayer) return;

    Future<String?> read(String prop) async {
      try {
        final v = await native.getProperty(prop);
        return v.isEmpty ? null : v;
      } catch (_) {
        return null;
      }
    }

    int? asInt(String? s) =>
        s == null ? null : int.tryParse(s.split('.').first);
    double? asDouble(String? s) => s == null ? null : double.tryParse(s);

    final r = await Future.wait([
      read('frame-drop-count'), // 0  dropped by VO
      read('decoder-frame-drop-count'), // 1  dropped by decoder
      read('estimated-vf-fps'), // 2
      read('container-fps'), // 3
      read('estimated-display-fps'), // 4
      read('video-bitrate'), // 5
      read('audio-bitrate'), // 6
      read('hwdec-current'), // 7
      read('avsync'), // 8
      read('width'), // 9
      read('height'), // 10
      read('video-codec'), // 11
      read('demuxer-cache-duration'), // 12
    ]);

    stats.value = PlaybackStats(
      droppedVo: asInt(r[0]) ?? 0,
      droppedDecoder: asInt(r[1]) ?? 0,
      fps: asDouble(r[2]),
      containerFps: asDouble(r[3]),
      displayFps: asDouble(r[4]),
      videoBitrate: asDouble(r[5]),
      audioBitrate: asDouble(r[6]),
      hwdec: r[7],
      avsync: asDouble(r[8]),
      width: asInt(r[9]),
      height: asInt(r[10]),
      videoCodec: r[11],
      cacheDuration: asDouble(r[12]),
    );
  }
}
