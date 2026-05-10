import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class PlayHistoryItem {
  PlayHistoryItem({
    required this.url,
    required this.title,
    required this.playedAt,
    this.isFavorite = false,
  });

  final String url;
  final String title;
  final DateTime playedAt;
  bool isFavorite;

  Map<String, dynamic> toJson() => {
        'url': url,
        'title': title,
        'playedAt': playedAt.toIso8601String(),
        'isFavorite': isFavorite,
      };

  factory PlayHistoryItem.fromJson(Map<String, dynamic> j) => PlayHistoryItem(
        url: j['url'] as String,
        title: j['title'] as String,
        playedAt: DateTime.parse(j['playedAt'] as String),
        isFavorite: (j['isFavorite'] as bool?) ?? false,
      );
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

  Future<void> addOrBump(String url, String title) async {
    _items.removeWhere((i) => i.url == url);
    _items.insert(
      0,
      PlayHistoryItem(url: url, title: title, playedAt: DateTime.now()),
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
