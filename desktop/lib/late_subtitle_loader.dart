import 'dart:convert';
import 'dart:io';

import 'player_engine.dart';
import 'stream_proxy_server.dart';

const int _maximumSubtitleBytes = 16 * 1024 * 1024;

/// Reject malformed remote resources before giving their URL and headers to the proxy.
bool validLateSubtitleResource(SubtitleRequest resource) {
  final uri = Uri.tryParse(resource.url);
  if (uri == null ||
      !uri.hasAuthority ||
      uri.host.isEmpty ||
      !{'http', 'https'}.contains(uri.scheme.toLowerCase()) ||
      uri.userInfo.isNotEmpty ||
      resource.headers.length > 32 ||
      (resource.label?.length ?? 0) > 256 ||
      (resource.language?.length ?? 0) > 64) {
    return false;
  }
  var headerBytes = 0;
  for (final entry in resource.headers.entries) {
    if (!RegExp(r'^[A-Za-z0-9-]{1,64}$').hasMatch(entry.key) ||
        entry.key.toLowerCase() == 'host' ||
        entry.value.contains('\r') ||
        entry.value.contains('\n') ||
        entry.value.length > 4096) {
      return false;
    }
    headerBytes += entry.key.length + entry.value.length;
  }
  return headerBytes <= 16384;
}

/// The Desktop receiver fetches through its header-aware proxy, then gives mpv a local file.
/// The proxy owns redirect policy; the mpv process never sees subtitle credentials.
Future<File> downloadLateSubtitle(
  SubtitleRequest resource, {
  List<String>? allowedPrivateOrigins,
}) async {
  if (!validLateSubtitleResource(resource)) {
    throw const FormatException('Invalid subtitle resource');
  }
  final proxy = StreamProxyServer.instance;
  await proxy.start();
  final localUrl = await proxy.registerSession(
    resource.url,
    resource.headers,
    allowedPrivateOrigins: allowedPrivateOrigins,
  );
  final localUri = Uri.parse(localUrl);
  if (!proxy.ownsUrl(localUrl) ||
      !{'127.0.0.1', 'localhost', '::1'}.contains(localUri.host)) {
    throw StateError('Subtitle proxy did not return a loopback URL');
  }
  final client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 10)
    ..findProxy = (_) => 'DIRECT';
  final directory =
      await Directory.systemTemp.createTemp('playbridge-subtitle-');
  final extension =
      switch (Uri.parse(resource.url).path.toLowerCase().split('.').last) {
    'srt' => 'srt',
    'ass' => 'ass',
    'ssa' => 'ssa',
    'ttml' => 'ttml',
    _ => 'vtt',
  };
  final file = File('${directory.path}/sidecar.$extension');
  try {
    final request =
        await client.getUrl(localUri).timeout(const Duration(seconds: 20));
    request.followRedirects = false;
    final response = await request.close().timeout(const Duration(seconds: 20));
    if (response.statusCode != HttpStatus.ok ||
        response.contentLength > _maximumSubtitleBytes) {
      throw const HttpException('Subtitle unavailable');
    }
    final sink = file.openWrite();
    var size = 0;
    try {
      await for (final bytes in response.timeout(const Duration(seconds: 30))) {
        size += bytes.length;
        if (size > _maximumSubtitleBytes) {
          throw const HttpException('Subtitle too large');
        }
        sink.add(bytes);
      }
      await sink.flush();
    } finally {
      await sink.close();
    }
    if (size == 0) throw const HttpException('Empty subtitle');
    final prefix = utf8
        .decode(
            await file.openRead(0, 4096).fold<List<int>>(
              <int>[],
              (result, bytes) => result..addAll(bytes),
            ),
            allowMalformed: true)
        .trimLeft();
    if (prefix.toLowerCase().startsWith('<!doctype html') ||
        prefix.toLowerCase().startsWith('<html')) {
      throw const FormatException('Subtitle endpoint returned HTML');
    }
    final detectedExtension = prefix.startsWith('WEBVTT')
        ? 'vtt'
        : RegExp(r'^\d{2}:\d{2}:\d{2}[,.]\d{3}\s+-->', multiLine: true)
                .hasMatch(prefix)
            ? 'srt'
            : prefix.startsWith('[Script Info]')
                ? 'ass'
                : extension;
    if (detectedExtension != extension) {
      return await file.rename('${directory.path}/sidecar.$detectedExtension');
    }
    return file;
  } catch (_) {
    await directory.delete(recursive: true);
    rethrow;
  } finally {
    client.close(force: true);
  }
}
