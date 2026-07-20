import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

/// Client for communicating with a DLNA / UPnP MediaRenderer device over HTTP/SOAP.
class DlnaClient {
  DlnaClient({HttpClient? httpClient})
      : _client = httpClient ??
            (HttpClient()
              ..connectionTimeout = const Duration(seconds: 5));

  final HttpClient _client;
  String? _avControlUrl;
  String? _renderingControlUrl;
  String? _friendlyName;

  String? get avControlUrl => _avControlUrl;
  String? get renderingControlUrl => _renderingControlUrl;
  String? get friendlyName => _friendlyName;

  /// Loads UPnP device description XML from [locationUrl] and extracts
  /// the AVTransport & RenderingControl URLs.
  Future<bool> loadDescription(String locationUrl) async {
    try {
      final uri = Uri.parse(locationUrl);
      final req = await _client.getUrl(uri);
      final resp = await req.close();
      if (resp.statusCode != 200) return false;
      final body = await utf8.decoder.bind(resp).join();

      _friendlyName = _extractTagContent(body, 'friendlyName');

      final avService = _extractServiceBlock(
          body, 'urn:schemas-upnp-org:service:AVTransport:1');
      if (avService != null) {
        final relControl = _extractTagContent(avService, 'controlURL');
        if (relControl != null) {
          _avControlUrl = uri.resolve(relControl).toString();
        }
      }

      final renderingService = _extractServiceBlock(
          body, 'urn:schemas-upnp-org:service:RenderingControl:1');
      if (renderingService != null) {
        final relControl = _extractTagContent(renderingService, 'controlURL');
        if (relControl != null) {
          _renderingControlUrl = uri.resolve(relControl).toString();
        }
      }

      return _avControlUrl != null;
    } catch (e) {
      debugPrint(
          '[dlna-client] Failed to load description from $locationUrl: $e');
      return false;
    }
  }

  /// Sends SetAVTransportURI command with DIDL-Lite metadata.
  Future<bool> setAvTransportUri(String mediaUrl, {String? title}) async {
    final controlUrl = _avControlUrl;
    if (controlUrl == null) return false;

    final mediaTitle = (title != null && title.isNotEmpty) ? title : 'Media';
    final mimeType = _detectMimeType(mediaUrl);
    final didlMetadata = _buildDidlLiteXml(mediaUrl, mediaTitle, mimeType);

    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${_escapeXml(mediaUrl)}</CurrentURI>
      <CurrentURIMetaData>${_escapeXml(didlMetadata)}</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>
''';

    return _postSoap(
      controlUrl,
      'urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI',
      body,
    );
  }

  /// Sends Play action.
  Future<bool> play() async {
    final controlUrl = _avControlUrl;
    if (controlUrl == null) return false;
    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>
''';
    return _postSoap(
      controlUrl,
      'urn:schemas-upnp-org:service:AVTransport:1#Play',
      body,
    );
  }

  /// Sends Pause action.
  Future<bool> pause() async {
    final controlUrl = _avControlUrl;
    if (controlUrl == null) return false;
    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Pause>
  </s:Body>
</s:Envelope>
''';
    return _postSoap(
      controlUrl,
      'urn:schemas-upnp-org:service:AVTransport:1#Pause',
      body,
    );
  }

  /// Sends Stop action.
  Future<bool> stop() async {
    final controlUrl = _avControlUrl;
    if (controlUrl == null) return false;
    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Stop>
  </s:Body>
</s:Envelope>
''';
    return _postSoap(
      controlUrl,
      'urn:schemas-upnp-org:service:AVTransport:1#Stop',
      body,
    );
  }

  /// Sends Seek action (target format: HH:MM:SS).
  Future<bool> seek(int positionMs) async {
    final controlUrl = _avControlUrl;
    if (controlUrl == null) return false;
    final targetTime = formatMsToHms(positionMs);
    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Unit>REL_TIME</Unit>
      <Target>${_escapeXml(targetTime)}</Target>
    </u:Seek>
  </s:Body>
</s:Envelope>
''';
    return _postSoap(
      controlUrl,
      'urn:schemas-upnp-org:service:AVTransport:1#Seek',
      body,
    );
  }

  /// Queries GetTransportInfo. Returns transport state string ('PLAYING', 'PAUSED_PLAYBACK', 'STOPPED', 'TRANSITIONING', etc.).
  Future<String?> getTransportState() async {
    final controlUrl = _avControlUrl;
    if (controlUrl == null) return null;
    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:GetTransportInfo>
  </s:Body>
</s:Envelope>
''';
    final resp = await _postSoapResponse(
      controlUrl,
      'urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo',
      body,
    );
    if (resp == null) return null;
    return _extractTagContent(resp, 'CurrentTransportState');
  }

  /// Queries GetPositionInfo. Returns map with position & duration in ms.
  Future<({int positionMs, int durationMs})?> getPositionInfo() async {
    final controlUrl = _avControlUrl;
    if (controlUrl == null) return null;
    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:GetPositionInfo>
  </s:Body>
</s:Envelope>
''';
    final resp = await _postSoapResponse(
      controlUrl,
      'urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo',
      body,
    );
    if (resp == null) return null;

    final trackDurStr = _extractTagContent(resp, 'TrackDuration');
    final relTimeStr = _extractTagContent(resp, 'RelTime');

    final durationMs = trackDurStr != null ? parseHmsToMs(trackDurStr) : 0;
    final positionMs = relTimeStr != null ? parseHmsToMs(relTimeStr) : 0;

    return (positionMs: positionMs, durationMs: durationMs);
  }

  /// Sets Volume (0-100) via RenderingControl if supported.
  Future<bool> setVolume(int volume) async {
    final controlUrl = _renderingControlUrl;
    if (controlUrl == null) return false;
    final clamped = volume.clamp(0, 100);
    final body = '''
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
      <InstanceID>0</InstanceID>
      <Channel>Master</Channel>
      <DesiredVolume>$clamped</DesiredVolume>
    </u:SetVolume>
  </s:Body>
</s:Envelope>
''';
    return _postSoap(
      controlUrl,
      'urn:schemas-upnp-org:service:RenderingControl:1#SetVolume',
      body,
    );
  }

  // Helper HTTP POST SOAP executor
  Future<bool> _postSoap(String url, String soapAction, String body) async {
    final resp = await _postSoapResponse(url, soapAction, body);
    return resp != null;
  }

  Future<String?> _postSoapResponse(
      String url, String soapAction, String body) async {
    try {
      final uri = Uri.parse(url);
      final req = await _client.postUrl(uri);
      req.headers.contentType = ContentType('text', 'xml', charset: 'utf-8');
      req.headers.set('SOAPAction', '"$soapAction"');
      req.write(body);
      final resp = await req.close();
      if (resp.statusCode != 200) return null;
      return await utf8.decoder.bind(resp).join();
    } catch (e) {
      debugPrint('[dlna-client] SOAP request failed to $url ($soapAction): $e');
      return null;
    }
  }

  static String _buildDidlLiteXml(String url, String title, String mimeType) {
    return '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
        '<item id="0" parentID="-1" restricted="1">'
        '<dc:title>${_escapeXml(title)}</dc:title>'
        '<upnp:class>object.item.videoItem</upnp:class>'
        '<res protocolInfo="http-get:*:$mimeType:*">${_escapeXml(url)}</res>'
        '</item>'
        '</DIDL-Lite>';
  }

  static String _detectMimeType(String url) {
    final lower = url.toLowerCase();
    if (lower.contains('.m3u8')) return 'application/x-mpegURL';
    if (lower.contains('.mp4') || lower.contains('.m4v')) return 'video/mp4';
    if (lower.contains('.mkv')) return 'video/x-matroska';
    if (lower.contains('.webm')) return 'video/webm';
    if (lower.contains('.mov')) return 'video/quicktime';
    if (lower.contains('.mp3')) return 'audio/mpeg';
    if (lower.contains('.flac')) return 'audio/flac';
    if (lower.contains('.aac')) return 'audio/aac';
    if (lower.contains('.wav')) return 'audio/wav';
    return 'video/mp4';
  }

  static String _escapeXml(String value) {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&apos;');
  }

  static String? _extractTagContent(String xml, String tagName) {
    final match = RegExp('<$tagName(?:\\s+[^>]*)?>(.*?)</$tagName>',
            dotAll: true)
        .firstMatch(xml);
    return match?.group(1)?.trim();
  }

  static String? _extractServiceBlock(String xml, String serviceType) {
    final services =
        RegExp('<service>(.*?)</service>', dotAll: true).allMatches(xml);
    for (final match in services) {
      final block = match.group(1);
      if (block != null && block.contains(serviceType)) {
        return block;
      }
    }
    return null;
  }

  static String formatMsToHms(int ms) {
    if (ms <= 0) return '00:00:00';
    final totalSec = ms ~/ 1000;
    final hours = totalSec ~/ 3600;
    final mins = (totalSec % 3600) ~/ 60;
    final secs = totalSec % 60;
    final hStr = hours.toString().padLeft(2, '0');
    final mStr = mins.toString().padLeft(2, '0');
    final sStr = secs.toString().padLeft(2, '0');
    return '$hStr:$mStr:$sStr';
  }

  static int parseHmsToMs(String hms) {
    final parts = hms.trim().split(':');
    if (parts.length < 2) return 0;
    try {
      if (parts.length == 3) {
        final hours = int.parse(parts[0]);
        final mins = int.parse(parts[1]);
        final secParts = parts[2].split('.');
        final secs = int.parse(secParts[0]);
        final ms = secParts.length > 1
            ? int.parse(secParts[1].padRight(3, '0').substring(0, 3))
            : 0;
        return (hours * 3600 + mins * 60 + secs) * 1000 + ms;
      } else {
        final mins = int.parse(parts[0]);
        final secParts = parts[1].split('.');
        final secs = int.parse(secParts[0]);
        final ms = secParts.length > 1
            ? int.parse(secParts[1].padRight(3, '0').substring(0, 3))
            : 0;
        return (mins * 60 + secs) * 1000 + ms;
      }
    } catch (_) {
      return 0;
    }
  }

  void close() {
    _client.close(force: true);
  }
}
