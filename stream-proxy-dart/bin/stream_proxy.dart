import 'dart:io';

import 'package:args/args.dart';
import 'package:playbridge_stream_proxy/ffmpeg_avio_client.dart';
import 'package:playbridge_stream_proxy/stream_proxy_server.dart';

void main(List<String> args) async {
  final parser = ArgParser()
    ..addOption('port', abbr: 'p', defaultsTo: '8888', help: 'Port to bind to')
    ..addOption('address',
        abbr: 'a', defaultsTo: '0.0.0.0', help: 'Address to bind to')
    ..addOption('password', abbr: 'k', help: 'API authorization password')
    ..addOption('ffmpeg-path',
        abbr: 'f',
        help: 'Dynamic library search path override for FFmpeg (libavformat)')
    ..addFlag('help',
        abbr: 'h', negatable: false, help: 'Show usage instructions');

  final ArgResults parsed;
  try {
    parsed = parser.parse(args);
  } catch (e) {
    stderr.writeln('Error parsing arguments: $e');
    print(parser.usage);
    exit(1);
  }

  if (parsed['help'] == true) {
    print('PlayBridge Stream Proxy (PB-Proxy) CLI');
    print(parser.usage);
    exit(0);
  }

  final port =
      int.tryParse(Platform.environment['PORT'] ?? parsed['port'] as String) ??
          8888;
  final address =
      Platform.environment['ADDRESS'] ?? parsed['address'] as String;
  final ffmpegPath =
      Platform.environment['FFMPEG_PATH'] ?? parsed['ffmpeg-path'] as String?;
  final password =
      Platform.environment['PASSWORD'] ?? parsed['password'] as String?;

  if (ffmpegPath != null && ffmpegPath.isNotEmpty) {
    AvioClient.dyldFrameworkPathOverride = ffmpegPath;
    stdout
        .writeln('[pb-proxy-cli] Custom FFmpeg library path set: $ffmpegPath');
  }

  final server = StreamProxyServer(password: password);

  // Handle system signals for graceful shutdown
  ProcessSignal.sigint.watch().listen((_) async {
    stdout.writeln('\n[pb-proxy-cli] Shutting down gracefully (SIGINT)...');
    await server.stop();
    exit(0);
  });

  ProcessSignal.sigterm.watch().listen((_) async {
    stdout.writeln('\n[pb-proxy-cli] Shutting down gracefully (SIGTERM)...');
    await server.stop();
    exit(0);
  });

  try {
    await server.start(host: address, port: port);
    stdout.writeln('[pb-proxy-cli] Server running at http://$address:$port');
  } catch (e) {
    stderr.writeln('Failed to start server: $e');
    exit(1);
  }
}
