import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/roku_client.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_transport.dart';

void main() {
  group('RokuClient XML & HTTP ECP commands', () {
    late HttpServer server;

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    });

    tearDown(() async {
      await server.close(force: true);
    });

    test('queries device-info XML and extracts user-device-name', () async {
      server.listen((req) {
        if (req.uri.path == '/query/device-info') {
          req.response.headers.contentType = ContentType('text', 'xml');
          req.response.write('''<?xml version="1.0"?>
<device-info>
  <user-device-name>Living Room Roku</user-device-name>
  <model-name>Roku Ultra</model-name>
</device-info>''');
          req.response.close();
        }
      });

      final client = RokuClient();
      client.init(server.address.host, port: server.port);
      final ok = await client.queryDeviceInfo();

      expect(ok, isTrue);
      expect(client.friendlyName, equals('Living Room Roku'));
      client.close();
    });

    test('sends keypress, launchMedia, and queries media-player status', () async {
      final paths = <String>[];

      server.listen((req) async {
        paths.add(req.uri.path);
        req.response.headers.contentType = ContentType('text', 'xml');

        if (req.uri.path == '/query/media-player') {
          req.response.write('''<?xml version="1.0"?>
<player error="false" state="play">
  <plugin bandwidth="12345" id="15985" name="PlayOnRoku" />
  <position>450 s</position>
  <duration>1800 s</duration>
</player>''');
        } else {
          req.response.statusCode = 200;
        }
        await req.response.close();
      });

      final client = RokuClient();
      client.init(server.address.host, port: server.port);

      final okPlay = await client.play();
      final okLaunch = await client.launchMedia('http://example.com/video.mp4', title: 'Roku Movie');
      final status = await client.getMediaPlayerStatus();

      expect(okPlay, isTrue);
      expect(okLaunch, isTrue);
      expect(paths, contains('/keypress/Play'));
      expect(paths, contains('/launch/15985'));

      expect(status, isNotNull);
      expect(status!.state, equals('play'));
      expect(status.positionMs, equals(450000));
      expect(status.durationMs, equals(1800000));

      client.close();
    });
  });

  group('RokuTransport integration', () {
    late HttpServer server;

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((req) async {
        req.response.headers.contentType = ContentType('text', 'xml');
        if (req.uri.path == '/query/device-info') {
          req.response.write('''<device-info><user-device-name>Roku TV</user-device-name></device-info>''');
        } else if (req.uri.path == '/query/media-player') {
          req.response.write('''<player error="false" state="play"><position>10 s</position><duration>100 s</duration></player>''');
        } else {
          req.response.statusCode = 200;
        }
        await req.response.close();
      });
    });

    tearDown(() async {
      await server.close(force: true);
    });

    test('connects to Roku device, casts video, polls status, and controls playback', () async {
      final transport = RokuTransport();
      final messages = <String>[];
      final sub = transport.messages.listen(messages.add);

      final tv = DiscoveredTv(
        uuid: 'roku-123',
        protocol: TvProtocol.roku,
        name: 'Roku TV',
        host: server.address.host,
        port: server.port,
        wssPort: null,
        location: null,
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
      expect(messages.first, contains('"position":10000'));
      expect(messages.first, contains('"duration":100000'));

      final okPause = transport.sendControl('pause');
      expect(okPause, isTrue);

      final okStop = transport.sendControl('stop');
      expect(okStop, isTrue);

      await sub.cancel();
      await transport.dispose();
    });
  });
}
