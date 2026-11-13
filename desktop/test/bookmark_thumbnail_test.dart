import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/bookmark_thumbnail.dart';
import 'package:playbridge_desktop/history_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<List<int>> _frame(int width, int height) async {
  final recorder = ui.PictureRecorder();
  Canvas(recorder).drawRect(
    Rect.fromLTWH(0, 0, width.toDouble(), height.toDouble()),
    Paint()..color = const Color(0xFF336699),
  );
  final image = await recorder.endRecording().toImage(width, height);
  final data = await image.toByteData(format: ui.ImageByteFormat.png);
  return data!.buffer.asUint8List();
}

void main() {
  testWidgets('a captured frame is scaled down and saved per video',
      (tester) async {
    await tester.runAsync(() async {
      final dir = Directory.systemTemp.createTempSync('pb_thumbs');
      addTearDown(() => dir.deleteSync(recursive: true));
      final frame = await _frame(1920, 1080);

      final path = await saveBookmarkThumbnail(
        Uint8List.fromList(frame),
        'https://example.com/a.mp4',
        directory: dir,
      );
      expect(path, isNotNull);
      final codec = await ui.instantiateImageCodec(
        File(path!).readAsBytesSync(),
      );
      final saved = (await codec.getNextFrame()).image;
      expect(saved.width, kBookmarkThumbnailWidth);
      expect(saved.height, 360);

      // The same video always maps to the same file; another video does not.
      final again = await saveBookmarkThumbnail(
          Uint8List.fromList(frame), 'https://example.com/a.mp4',
          directory: dir);
      final other = await saveBookmarkThumbnail(
          Uint8List.fromList(frame), 'https://example.com/b.mp4',
          directory: dir);
      expect(again, path);
      expect(other, isNot(path));

      // Bytes that are not an image are refused, not saved.
      expect(
        await saveBookmarkThumbnail(Uint8List.fromList([1, 2, 3]), 'x',
            directory: dir),
        isNull,
      );
    });
  });

  testWidgets('the picture is stored with the bookmark and deleted with it',
      (tester) async {
    await tester.runAsync(() async {
      SharedPreferences.setMockInitialValues({});
      final dir = Directory.systemTemp.createTempSync('pb_thumbs');
      addTearDown(() => dir.deleteSync(recursive: true));
      final path = await saveBookmarkThumbnail(
        Uint8List.fromList(await _frame(320, 180)),
        'https://example.com/a.mp4',
        directory: dir,
      );

      final store = await HistoryStore.load();
      await store.bookmark(
        url: 'https://example.com/a.mp4',
        title: 'A',
        recordable: true,
        thumbnailPath: path,
      );
      await store.bookmark(
        url: 'https://example.com/b.mp4',
        title: 'B',
        recordable: true,
        imageUrl: 'https://example.com/b.jpg',
      );
      final reloaded = await HistoryStore.load();
      expect(reloaded.favorites.map((i) => i.thumbnailPath), [null, path]);
      expect(reloaded.favorites.first.imageUrl, 'https://example.com/b.jpg');

      await store.toggleFavorite('https://example.com/a.mp4');
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(File(path!).existsSync(), isFalse);
    });
  });
}
