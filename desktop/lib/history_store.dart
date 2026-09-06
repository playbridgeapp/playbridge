import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class PlayHistoryItem {
  PlayHistoryItem({
    required this.url,
    required this.title,
    required this.playedAt,
    this.isFavorite = false,
    this.playlistBody,
    this.audioUrl,
    this.headers,
    this.contentType,
    this.mediaKind,
    this.displayDurationMs,
  });

  /// Primary play URL (prefer original CDN / session media, not loopback proxy).
  final String url;
  final String title;
  final DateTime playedAt;
  bool isFavorite;

  /// Synthetic multivariant text (absolute child URIs) for demuxed LL-HLS.
  final String? playlistBody;

  /// Companion demuxed audio media playlist (same live session).
  final String? audioUrl;

  /// Replay headers (Referer / Cookie / UA). Never log values.
  final Map<String, String>? headers;

  final String? contentType;
  final String? mediaKind;
  final int? displayDurationMs;

  bool get hasSyntheticHandoff =>
      (playlistBody != null && playlistBody!.trim().startsWith('#EXTM3U')) ||
      (audioUrl != null && audioUrl!.trim().isNotEmpty);

  Map<String, dynamic> toJson() => {
        'url': url,
        'title': title,
        'playedAt': playedAt.toIso8601String(),
        'isFavorite': isFavorite,
        if (playlistBody != null && playlistBody!.isNotEmpty)
          'playlistBody': playlistBody,
        if (audioUrl != null && audioUrl!.isNotEmpty) 'audioUrl': audioUrl,
        if (headers != null && headers!.isNotEmpty) 'headers': headers,
        if (contentType != null && contentType!.isNotEmpty)
          'contentType': contentType,
        if (mediaKind != null && mediaKind!.isNotEmpty) 'mediaKind': mediaKind,
        if (displayDurationMs != null) 'displayDurationMs': displayDurationMs,
      };

  factory PlayHistoryItem.fromJson(Map<String, dynamic> j) {
    Map<String, String>? headers;
    final rawHeaders = j['headers'];
    if (rawHeaders is Map) {
      headers = rawHeaders.map((k, v) => MapEntry('$k', '$v'));
      if (headers.isEmpty) headers = null;
    }
    return PlayHistoryItem(
      url: j['url'] as String,
      title: j['title'] as String,
      playedAt: DateTime.parse(j['playedAt'] as String),
      isFavorite: (j['isFavorite'] as bool?) ?? false,
      playlistBody: j['playlistBody'] as String?,
      audioUrl: j['audioUrl'] as String?,
      headers: headers,
      contentType: j['contentType'] as String?,
      mediaKind: j['mediaKind'] as String?,
      displayDurationMs: j['displayDurationMs'] as int?,
    );
  }
}

class HistoryStore extends ChangeNotifier {
  HistoryStore._();

  static const _kKey = 'pb.history_v1';
  static const _kMax = 200;

  final List<PlayHistoryItem> _items = [];

  List<PlayHistoryItem> get items => List.unmodifiable(_items);
  List<PlayHistoryItem> get favorites =>
      _items.where((i) => i.isFavorite).toList(growable: false);

  static Future<HistoryStore> load() async {
    final store = HistoryStore._();
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_kKey);
    if (raw != null) {
      try {
        final list = (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
        store._items.addAll(list.map(PlayHistoryItem.fromJson));
      } catch (e) {
        debugPrint('[history] load failed: $e');
      }
    }
    return store;
  }

  /// Record or bump a play. Prefer the original CDN URL (not a loopback proxy
  /// URL) so resume can re-apply headers / synthetic demuxed handoff.
  Future<void> addOrBump({
    required String url,
    required String title,
    String? playlistBody,
    String? audioUrl,
    Map<String, String>? headers,
    String? contentType,
    String? mediaKind,
    int? displayDurationMs,
    bool skipHistory = false,
  }) async {
    if (skipHistory) return;
    final prefs = await SharedPreferences.getInstance();
    if (!(prefs.getBool('pb.enable_history') ?? true)) return;
    if (url.isEmpty) return;
    // Never persist local temp / data playlists as history keys.
    if (url.startsWith('file:') || url.startsWith('data:')) return;

    final body = playlistBody?.trim();
    final audio = audioUrl?.trim();
    final hdrs = (headers == null || headers.isEmpty)
        ? null
        : Map<String, String>.from(headers);

    var wasFavorite = false;
    _items.removeWhere((i) {
      if (i.url != url) return false;
      wasFavorite = wasFavorite || i.isFavorite;
      return true;
    });

    _items.insert(
      0,
      PlayHistoryItem(
        url: url,
        title: title,
        playedAt: DateTime.now(),
        isFavorite: wasFavorite,
        playlistBody: body != null && body.startsWith('#EXTM3U') ? body : null,
        audioUrl: audio != null && audio.isNotEmpty ? audio : null,
        headers: hdrs,
        contentType: contentType?.trim().isEmpty == true ? null : contentType,
        mediaKind: mediaKind,
        displayDurationMs: displayDurationMs,
      ),
    );
    if (_items.length > _kMax) _items.removeRange(_kMax, _items.length);
    notifyListeners();
    await _save();
  }

  Future<void> toggleFavorite(String url) async {
    final idx = _items.indexWhere((i) => i.url == url);
    if (idx < 0) return;
    _items[idx].isFavorite = !_items[idx].isFavorite;
    notifyListeners();
    await _save();
  }

  Future<void> remove(String url) async {
    _items.removeWhere((i) => i.url == url);
    notifyListeners();
    await _save();
  }

  Future<void> clearHistory() async {
    _items.removeWhere((i) => !i.isFavorite);
    notifyListeners();
    await _save();
  }

  Future<void> _save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _kKey,
      jsonEncode(_items.map((i) => i.toJson()).toList()),
    );
  }
}
