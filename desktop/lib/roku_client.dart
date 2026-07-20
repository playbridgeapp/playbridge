import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

/// Client for communicating with a Roku device using External Control Protocol (ECP) over HTTP.
class RokuClient {
  RokuClient({HttpClient? httpClient})
      : _client = httpClient ??
            (HttpClient()
              ..connectionTimeout = const Duration(seconds: 5));

  final HttpClient _client;
  String? _baseUrl;
  String? _friendlyName;

  String? get baseUrl => _baseUrl;
  String? get friendlyName => _friendlyName;

  /// Initializes the client with the Roku host and port (default 8060).
  void init(String host, {int port = 8060}) {
    _baseUrl = 'http://$host:$port';
  }

  /// Queries Roku device-info endpoint (`GET /query/device-info`) to extract friendly name.
  Future<bool> queryDeviceInfo() async {
    final url = _baseUrl;
    if (url == null) return false;
    try {
      final req = await _client.getUrl(Uri.parse('$url/query/device-info'));
      final resp = await req.close();
      if (resp.statusCode != 200) return false;
      final body = await utf8.decoder.bind(resp).join();
      _friendlyName = _extractTagContent(body, 'user-device-name') ??
          _extractTagContent(body, 'friendly-device-name') ??
          _extractTagContent(body, 'model-name');
      return true;
    } catch (e) {
      debugPrint('[roku-client] Failed to query device-info from $url: $e');
      return false;
    }
  }

  /// Sends keypress command (`POST /keypress/<key>`).
  Future<bool> keypress(String key) async {
    final url = _baseUrl;
    if (url == null) return false;
    try {
      final req = await _client.postUrl(Uri.parse('$url/keypress/$key'));
      final resp = await req.close();
      return resp.statusCode == 200;
    } catch (e) {
      debugPrint('[roku-client] Keypress $key failed: $e');
      return false;
    }
  }

  /// Sends Play keypress (`POST /keypress/Play`).
  Future<bool> play() => keypress('Play');

  /// Sends Pause keypress (`POST /keypress/Pause`).
  Future<bool> pause() => keypress('Play'); // Roku toggles play/pause with 'Play' or 'Pause'

  /// Sends Stop keypress (`POST /keypress/Stop`).
  Future<bool> stop() => keypress('Stop');

  /// Sends Fwd (seek forward 10s) keypress (`POST /keypress/Fwd`).
  Future<bool> seekForward() => keypress('Fwd');

  /// Sends Rev (seek rewind 10s) keypress (`POST /keypress/Rev`).
  Future<bool> seekRewind() => keypress('Rev');

  /// Launches Roku media player with specified video URL (`POST /launch/15985?u=<url>`).
  Future<bool> launchMedia(String mediaUrl, {String? title}) async {
    final url = _baseUrl;
    if (url == null) return false;
    try {
      // Default to Roku's PlayOnRoku / simple media player app (ID 15985 or dev app)
      final encodedUrl = Uri.encodeComponent(mediaUrl);
      final encodedTitle = title != null ? Uri.encodeComponent(title) : '';
      final launchUri = Uri.parse(
          '$url/launch/15985?u=$encodedUrl&videoFormat=mp4${encodedTitle.isNotEmpty ? '&t=$encodedTitle' : ''}');
      final req = await _client.postUrl(launchUri);
      final resp = await req.close();
      return resp.statusCode == 200;
    } catch (e) {
      debugPrint('[roku-client] Launch media failed: $e');
      return false;
    }
  }

  /// Queries current media player status (`GET /query/media-player`).
  Future<({String state, int positionMs, int durationMs})?> getMediaPlayerStatus() async {
    final url = _baseUrl;
    if (url == null) return null;
    try {
      final req = await _client.getUrl(Uri.parse('$url/query/media-player'));
      final resp = await req.close();
      if (resp.statusCode != 200) return null;
      final body = await utf8.decoder.bind(resp).join();

      final state = _extractAttribute(body, 'player', 'state') ?? 'none';
      final posStr = _extractTagContent(body, 'position');
      final durStr = _extractTagContent(body, 'duration');

      final posMs = _parseSecondsToMs(posStr);
      final durMs = _parseSecondsToMs(durStr);

      return (state: state, positionMs: posMs, durationMs: durMs);
    } catch (e) {
      debugPrint('[roku-client] Query media-player failed: $e');
      return null;
    }
  }

  static int _parseSecondsToMs(String? secStr) {
    if (secStr == null) return 0;
    final match = RegExp(r'(\d+)').firstMatch(secStr);
    if (match != null) {
      final sec = int.tryParse(match.group(1)!) ?? 0;
      return sec * 1000;
    }
    return 0;
  }

  static String? _extractTagContent(String xml, String tagName) {
    final match = RegExp('<$tagName(?:\\s+[^>]*)?>(.*?)</$tagName>',
            dotAll: true)
        .firstMatch(xml);
    return match?.group(1)?.trim();
  }

  static String? _extractAttribute(String xml, String tagName, String attrName) {
    final match = RegExp('<$tagName\\s+[^>]*$attrName="([^"]+)"',
            dotAll: true)
        .firstMatch(xml);
    return match?.group(1);
  }

  void close() {
    _client.close(force: true);
  }
}
