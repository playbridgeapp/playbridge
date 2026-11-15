import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'bookmark_thumbnail.dart';

/// Whether a Recent list can be shown. A receiver keeps bookmarks only; its
/// Recent list is meant to come from the connected phone's own cast history
/// (see docs/design-android-tv-redesign.md, "Sender keeps recents"). No phone
/// app can send that list yet, so nothing is recorded or shown until one can.
const bool kPhoneRecentsSupported = false;

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
    this.bookmarkOnly = false,
    this.imageUrl,
    this.thumbnailPath,
  });

  /// Primary play URL (prefer original CDN / session media, not loopback proxy).
  final String url;
  final String title;
  final DateTime playedAt;
  bool isFavorite;

  /// Kept only as a bookmark: a local file, a private cast, or bookmarked
  /// while the Recent list was off. Never shown in Recent, and removed when
  /// the bookmark is.
  bool bookmarkOnly;

  /// Artwork the cast came with (backdrop, poster or cover).
  String? imageUrl;

  /// A frame captured from the video when it was bookmarked, used when the
  /// cast had no artwork. A file this app owns and deletes with the bookmark.
  String? thumbnailPath;

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
        if (bookmarkOnly) 'bookmarkOnly': true,
        if (imageUrl != null && imageUrl!.isNotEmpty) 'imageUrl': imageUrl,
        if (thumbnailPath != null && thumbnailPath!.isNotEmpty)
          'thumbnailPath': thumbnailPath,
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
      bookmarkOnly: (j['bookmarkOnly'] as bool?) ?? false,
      imageUrl: j['imageUrl'] as String?,
      thumbnailPath: j['thumbnailPath'] as String?,
    );
  }
}

class HistoryStore extends ChangeNotifier {
  HistoryStore._(this.recentsEnabled);

  /// False while no phone can supply a Recent list: plays are not recorded,
  /// [recent] is empty and every stored entry is a bookmark.
  final bool recentsEnabled;

  static const _kKey = 'pb.history_v1';
  static const _kMax = 200;

  final List<PlayHistoryItem> _items = [];

  List<PlayHistoryItem> get items => List.unmodifiable(_items);

  /// What the Recent list shows: everything except bookmark-only entries.
  List<PlayHistoryItem> get recent => recentsEnabled
      ? _items.where((i) => !i.bookmarkOnly).toList(growable: false)
      : const [];
  List<PlayHistoryItem> get favorites =>
      _items.where((i) => i.isFavorite).toList(growable: false);

  static Future<HistoryStore> load({
    bool recentsEnabled = kPhoneRecentsSupported,
  }) async {
    final store = HistoryStore._(recentsEnabled);
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
    if (skipHistory || !recentsEnabled) return;
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

  /// Whether [url] can be bookmarked. Data URLs carry the media inline and
  /// are never stored.
  static bool canBookmark(String url) =>
      url.isNotEmpty && !url.startsWith('data:');

  /// Bookmark a video of any kind, including local files and private casts.
  /// [recordable] is false when the play itself must not appear in Recent.
  Future<void> bookmark({
    required String url,
    required String title,
    required bool recordable,
    String? imageUrl,
    String? thumbnailPath,
    String? playlistBody,
    String? audioUrl,
    Map<String, String>? headers,
    String? contentType,
    String? mediaKind,
    int? displayDurationMs,
  }) async {
    if (!canBookmark(url)) return;
    final idx = _items.indexWhere((i) => i.url == url);
    if (idx >= 0) {
      _items[idx].isFavorite = true;
      _items[idx].imageUrl ??= imageUrl;
      _items[idx].thumbnailPath ??= thumbnailPath;
    } else {
      final prefs = await SharedPreferences.getInstance();
      final historyOn = prefs.getBool('pb.enable_history') ?? true;
      final body = playlistBody?.trim();
      final audio = audioUrl?.trim();
      _items.insert(
        0,
        PlayHistoryItem(
          url: url,
          title: title,
          playedAt: DateTime.now(),
          isFavorite: true,
          imageUrl: imageUrl,
          thumbnailPath: thumbnailPath,
          bookmarkOnly: !recentsEnabled ||
              !recordable ||
              !historyOn ||
              url.startsWith('file:'),
          playlistBody:
              body != null && body.startsWith('#EXTM3U') ? body : null,
          audioUrl: audio != null && audio.isNotEmpty ? audio : null,
          headers: (headers == null || headers.isEmpty)
              ? null
              : Map<String, String>.from(headers),
          contentType: contentType?.trim().isEmpty == true ? null : contentType,
          mediaKind: mediaKind,
          displayDurationMs: displayDurationMs,
        ),
      );
    }
    notifyListeners();
    await _save();
  }

  bool isBookmarked(String url) =>
      _items.any((i) => i.url == url && i.isFavorite);

  Future<void> toggleFavorite(String url) async {
    final idx = _items.indexWhere((i) => i.url == url);
    if (idx < 0) return;
    // With no Recent list to fall back into, removing a bookmark removes it.
    if (_items[idx].isFavorite &&
        (_items[idx].bookmarkOnly || !recentsEnabled)) {
      unawaited(deleteBookmarkThumbnail(_items[idx].thumbnailPath));
      _items.removeAt(idx);
    } else {
      _items[idx].isFavorite = !_items[idx].isFavorite;
      if (!_items[idx].isFavorite) {
        // The captured frame belongs to the bookmark, not to Recent.
        unawaited(deleteBookmarkThumbnail(_items[idx].thumbnailPath));
        _items[idx].thumbnailPath = null;
      }
    }
    notifyListeners();
    await _save();
  }

  Future<void> remove(String url) async {
    for (final i in _items.where((i) => i.url == url)) {
      unawaited(deleteBookmarkThumbnail(i.thumbnailPath));
    }
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
