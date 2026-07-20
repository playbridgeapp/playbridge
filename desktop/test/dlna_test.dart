import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/dlna_client.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_transport.dart';

void main() {
  group('DlnaClient utilities', () {
    test('formats milliseconds to HH:MM:SS format', () {
      expect(DlnaClient.formatMsToHms(0), equals('00:00:00'));
      expect(DlnaClient.formatMsToHms(5000), equals('00:00:05'));
      expect(DlnaClient.formatMsToHms(125000), equals('00:02:05'));
      expect(DlnaClient.formatMsToHms(3661000), equals('01:01:01'));
    });

    test('parses HH:MM:SS and MM:SS strings to milliseconds', () {
      expect(DlnaClient.parseHmsToMs('00:00:00'), equals(0));
      expect(DlnaClient.parseHmsToMs('00:02:05'), equals(125000));
      expect(DlnaClient.parseHmsToMs('01:01:01.500'), equals(3661500));
      expect(DlnaClient.parseHmsToMs('02:05'), equals(125000));
    });
  });

  group('DlnaClient XML & SOAP over HTTP', () {
    late HttpServer server;

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    });

    tearDown(() async {
      await server.close(force: true);
    });

    test('loads description XML and extracts control URLs', () async {
      server.listen((req) {
        if (req.uri.path == '/description.xml') {
          req.response.headers.contentType = ContentType('text', 'xml');
          req.response.write('''<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <friendlyName>Smart TV</friendlyName>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/avt/control</controlURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/rc/control</controlURL>
      </service>
    </serviceList>
  </device>
</root>''');
          req.response.close();
        }
      });

      final client = DlnaClient();
      final location = 'http://${server.address.host}:${server.port}/description.xml';
      final ok = await client.loadDescription(location);

      expect(ok, isTrue);
      expect(client.friendlyName, equals('Smart TV'));
      expect(client.avControlUrl, equals('http://${server.address.host}:${server.port}/avt/control'));
      expect(client.renderingControlUrl, equals('http://${server.address.host}:${server.port}/rc/control'));
      client.close();
    });

    test('issues SOAP commands for SetAVTransportURI, Play, Pause, Stop, Seek', () async {
      final receivedActions = <String>[];
      final receivedBodies = <String>[];

      server.listen((req) async {
        final path = req.uri.path;
        final soapAction = req.headers.value('SOAPAction') ?? '';
        if (soapAction.isNotEmpty) receivedActions.add(soapAction);

        final body = await utf8.decoder.bind(req).join();
        if (body.isNotEmpty) receivedBodies.add(body);

        req.response.headers.contentType = ContentType('text', 'xml');
        if (path == '/description.xml') {
          req.response.write('''<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <friendlyName>Smart TV</friendlyName>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/avt/control</controlURL>
      </service>
    </serviceList>
  </device>
</root>''');
        } else if (soapAction.contains('GetTransportInfo')) {
          req.response.write('''<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
<s:Body><u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
  <CurrentTransportState>PLAYING</CurrentTransportState>
</u:GetTransportInfoResponse></s:Body></s:Envelope>''');
        } else if (soapAction.contains('GetPositionInfo')) {
          req.response.write('''<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
<s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
  <TrackDuration>01:30:00</TrackDuration>
  <RelTime>00:05:00</RelTime>
</u:GetPositionInfoResponse></s:Body></s:Envelope>''');
        } else {
          req.response.write('<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body/></s:Envelope>');
        }
        await req.response.close();
      });

      final location = 'http://${server.address.host}:${server.port}/description.xml';
      final client = DlnaClient();
      final okLoad = await client.loadDescription(location);
      expect(okLoad, isTrue);

      final okSet = await client.setAvTransportUri('http://example.com/video.mp4', title: 'Test');
      final okPlay = await client.play();
      final okPause = await client.pause();
      final okSeek = await client.seek(60000);
      final okStop = await client.stop();

      expect(okSet, isTrue);
      expect(okPlay, isTrue);
      expect(okPause, isTrue);
      expect(okSeek, isTrue);
      expect(okStop, isTrue);

      client.close();
    });
  });

  group('DlnaTransport integration', () {
    late HttpServer server;

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((req) async {
        final path = req.uri.path;
        final soapAction = req.headers.value('SOAPAction') ?? '';

        req.response.headers.contentType = ContentType('text', 'xml');
        if (path == '/desc.xml') {
          req.response.write('''<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <friendlyName>Test TV</friendlyName>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/avt</controlURL>
      </service>
    </serviceList>
  </device>
</root>''');
        } else if (soapAction.contains('GetTransportInfo')) {
          req.response.write('''<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
<s:Body><u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
  <CurrentTransportState>PLAYING</CurrentTransportState>
</u:GetTransportInfoResponse></s:Body></s:Envelope>''');
        } else if (soapAction.contains('GetPositionInfo')) {
          req.response.write('''<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
<s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
  <TrackDuration>00:10:00</TrackDuration>
  <RelTime>00:01:00</RelTime>
</u:GetPositionInfoResponse></s:Body></s:Envelope>''');
        } else {
          req.response.write('<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body/></s:Envelope>');
        }
        await req.response.close();
      });
    });

    tearDown(() async {
      await server.close(force: true);
    });

    test('connects to DLNA device, casts video, toggles play/pause, polls status, and controls playback', () async {
      final transport = DlnaTransport();
      final messages = <String>[];
      final sub = transport.messages.listen(messages.add);

      final tv = DiscoveredTv(
        uuid: 'dlna-123',
        protocol: TvProtocol.dlna,
        name: 'Test TV',
        host: server.address.host,
        port: server.port,
        wssPort: null,
        location: 'http://${server.address.host}:${server.port}/desc.xml',
      );

      await transport.connect(
        tv: tv,
        deviceName: 'Desktop',
        deviceUUID: 'd-123',
      );

      expect(transport.isConnected, isTrue);

      final okCast = transport.castVideo(PlayPayload()
        ..url = 'http://${server.address.host}:${server.port}/video.mp4'
        ..title = 'Test Video');
      expect(okCast, isTrue);

      // Wait for status poll
      await Future<void>.delayed(const Duration(milliseconds: 1100));

      expect(messages, isNotEmpty);
      expect(messages.first, contains('"state":"playing"'));
      expect(messages.first, contains('"position":60000'));
      expect(messages.first, contains('"duration":600000'));

      // Pause via sendControl('pause')
      final okPause = transport.sendControl('pause');
      expect(okPause, isTrue);

      // Toggle from paused -> play via sendControl('toggle')
      final okTogglePlay = transport.sendControl('toggle');
      expect(okTogglePlay, isTrue);

      // Toggle from playing -> pause via sendControl('toggle')
      final okTogglePause = transport.sendControl('toggle');
      expect(okTogglePause, isTrue);

      final okStop = transport.sendControl('stop');
      expect(okStop, isTrue);

      await sub.cancel();
      await transport.dispose();
    });
  });
}
