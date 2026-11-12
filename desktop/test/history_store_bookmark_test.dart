import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/history_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('a bookmark is stored even when the Recent list is off', () async {
    SharedPreferences.setMockInitialValues({'pb.enable_history': false});
    final store = await HistoryStore.load(recentsEnabled: true);

    await store.addOrBump(url: 'https://example.com/a.mp4', title: 'A');
    expect(store.items, isEmpty);

    await store.bookmark(
        url: 'https://example.com/a.mp4', title: 'A', recordable: true);
    expect(store.isBookmarked('https://example.com/a.mp4'), isTrue);
    expect(store.recent, isEmpty);
  });

  test('local files and private casts can be bookmarked but stay out of Recent',
      () async {
    SharedPreferences.setMockInitialValues({});
    final store = await HistoryStore.load(recentsEnabled: true);

    await store.bookmark(
        url: 'file:///tmp/a.mp4', title: 'File', recordable: true);
    await store.bookmark(
        url: 'https://example.com/p.mp4', title: 'Private', recordable: false);
    expect(store.favorites.map((i) => i.title), ['Private', 'File']);
    expect(store.recent, isEmpty);

    // Removing such a bookmark removes the entry; it never falls into Recent.
    await store.toggleFavorite('https://example.com/p.mp4');
    await store.toggleFavorite('file:///tmp/a.mp4');
    expect(store.items, isEmpty);
  });

  test('an ordinary bookmark stays in Recent when un-bookmarked', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await HistoryStore.load(recentsEnabled: true);
    await store.addOrBump(url: 'https://example.com/a.mp4', title: 'A');
    await store.bookmark(
        url: 'https://example.com/a.mp4', title: 'A', recordable: true);
    expect(store.isBookmarked('https://example.com/a.mp4'), isTrue);
    await store.toggleFavorite('https://example.com/a.mp4');
    expect(store.recent.single.title, 'A');
    expect(store.favorites, isEmpty);
  });

  test('data URLs are never stored and bookmarks survive a reload', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await HistoryStore.load(recentsEnabled: true);
    await store.bookmark(url: 'data:text/plain,x', title: 'D', recordable: true);
    expect(store.items, isEmpty);

    await store.bookmark(
        url: 'file:///tmp/a.mp4', title: 'File', recordable: true);
    final reloaded = await HistoryStore.load(recentsEnabled: true);
    expect(reloaded.favorites.single.bookmarkOnly, isTrue);
    expect(reloaded.recent, isEmpty);
  });

  test('with no phone-supplied Recent list nothing is recorded or listed',
      () async {
    SharedPreferences.setMockInitialValues({});
    final store = await HistoryStore.load();
    expect(store.recentsEnabled, kPhoneRecentsSupported);
    expect(store.recentsEnabled, isFalse);

    await store.addOrBump(url: 'https://example.com/a.mp4', title: 'A');
    expect(store.items, isEmpty);

    await store.bookmark(
        url: 'https://example.com/a.mp4', title: 'A', recordable: true);
    expect(store.favorites.single.title, 'A');
    expect(store.recent, isEmpty);

    // Removing the bookmark leaves nothing behind.
    await store.toggleFavorite('https://example.com/a.mp4');
    expect(store.items, isEmpty);
  });
}
