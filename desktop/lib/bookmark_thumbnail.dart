import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// Width of a stored bookmark thumbnail. Cards are at most 380 wide.
const int kBookmarkThumbnailWidth = 640;

/// Scales a captured video frame down and saves it as the thumbnail for the
/// bookmark of [url]. Returns the file path, or null if the frame could not be
/// decoded or written. [directory] overrides the app-support folder in tests.
Future<String?> saveBookmarkThumbnail(
  Uint8List frame,
  String url, {
  Directory? directory,
}) async {
  try {
    final codec = await ui.instantiateImageCodec(
      frame,
      targetWidth: kBookmarkThumbnailWidth,
    );
    final image = (await codec.getNextFrame()).image;
    final png = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    codec.dispose();
    if (png == null) return null;
    final dir = directory ??
        Directory(
          '${(await getApplicationSupportDirectory()).path}'
          '${Platform.pathSeparator}bookmark_thumbnails',
        );
    await dir.create(recursive: true);
    final name = sha1.convert(utf8.encode(url)).toString();
    final file = File('${dir.path}${Platform.pathSeparator}$name.png');
    await file.writeAsBytes(png.buffer.asUint8List(), flush: true);
    return file.path;
  } catch (e) {
    debugPrint('[bookmarks] thumbnail not saved: $e');
    return null;
  }
}

/// Deletes a generated thumbnail; missing files are ignored.
Future<void> deleteBookmarkThumbnail(String? path) async {
  if (path == null || path.isEmpty) return;
  try {
    final file = File(path);
    if (await file.exists()) await file.delete();
  } catch (e) {
    debugPrint('[bookmarks] thumbnail not deleted: $e');
  }
}
