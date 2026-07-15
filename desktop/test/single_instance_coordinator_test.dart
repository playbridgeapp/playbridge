import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/single_instance_coordinator.dart';

void main() {
  late Directory tempDir;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('pb_single_instance_test_');
  });

  tearDown(() async {
    if (tempDir.existsSync()) {
      await tempDir.delete(recursive: true);
    }
  });

  test('parses cast arguments without requiring Flutter', () {
    final request = InstanceLaunchRequest.fromArgs([
      '--cast-file',
      '/tmp/video.mp4',
      '--cast-title',
      'A title',
    ]);

    expect(request.castFile, '/tmp/video.mp4');
    expect(request.castTitle, 'A title');
  });

  test('secondary forwards and primary queues until UI handler is ready',
      () async {
    final primary = await _PrimaryHelper.start(tempDir.path);
    addTearDown(primary.stop);

    final secondaryResult = await SingleInstanceCoordinator.coordinate(
      request: const InstanceLaunchRequest(
        castFile: '/tmp/movie.mkv',
        castTitle: 'Movie',
      ),
      directoryPath: tempDir.path,
    ).timeout(const Duration(seconds: 8));

    expect(secondaryResult.isPrimary, isFalse);
    expect(secondaryResult.forwarded, isTrue);

    await primary.send('HANDLE');
    final line = await primary.nextLine();
    expect(line, startsWith('REQUEST:'));
    final request =
        jsonDecode(line.substring('REQUEST:'.length)) as Map<String, dynamic>;
    expect(request['castFile'], '/tmp/movie.mkv');
    expect(request['castTitle'], 'Movie');
  });

  test('IPC endpoint rejects a request with the wrong token', () async {
    final primaryResult = await SingleInstanceCoordinator.coordinate(
      request: const InstanceLaunchRequest(),
      directoryPath: tempDir.path,
    );
    final primary = primaryResult.coordinator!;
    addTearDown(primary.close);

    final metadata = jsonDecode(
      await File(primary.metadataFilePath).readAsString(),
    ) as Map<String, dynamic>;
    final socket = await Socket.connect(
      InternetAddress.loopbackIPv4,
      metadata['port'] as int,
    );
    socket.writeln(jsonEncode({
      'token': 'wrong-token',
      'action': 'activate',
    }));
    await socket.flush();

    final line =
        await utf8.decoder.bind(socket).transform(const LineSplitter()).first;
    final response = jsonDecode(line) as Map<String, dynamic>;
    expect(response['ok'], isFalse);
    expect(response['error'], 'unauthorized');
    await socket.close();
  });

  test('a forwarding failure never lets the secondary become primary',
      () async {
    final primary = await _PrimaryHelper.start(tempDir.path);
    addTearDown(primary.stop);
    await File(primary.metadataFilePath).delete();

    final secondaryResult = await SingleInstanceCoordinator.coordinate(
      request: const InstanceLaunchRequest(),
      directoryPath: tempDir.path,
      forwardTimeout: const Duration(milliseconds: 150),
    ).timeout(const Duration(seconds: 2));

    expect(secondaryResult.isPrimary, isFalse);
    expect(secondaryResult.forwarded, isFalse);
    expect(secondaryResult.forwardingError, isNotNull);
  });
}

class _PrimaryHelper {
  _PrimaryHelper._(this.process, this._lines, this.metadataFilePath);

  final Process process;
  final StreamIterator<String> _lines;
  final String metadataFilePath;

  static Future<_PrimaryHelper> start(String directoryPath) async {
    final flutterRoot = Platform.environment['FLUTTER_ROOT'];
    final dartExecutable = flutterRoot == null
        ? 'dart'
        : '$flutterRoot${Platform.pathSeparator}bin${Platform.pathSeparator}'
            'cache${Platform.pathSeparator}dart-sdk${Platform.pathSeparator}'
            'bin${Platform.pathSeparator}'
            '${Platform.isWindows ? 'dart.exe' : 'dart'}';
    final process = await Process.start(
      dartExecutable,
      [
        '--packages=.dart_tool/package_config.json',
        'test/support/single_instance_primary.dart',
        directoryPath,
      ],
      workingDirectory: Directory.current.path,
    ).timeout(const Duration(seconds: 5));
    final lines = StreamIterator(
      process.stdout.transform(utf8.decoder).transform(const LineSplitter()),
    );
    final hasLine = await lines.moveNext().timeout(const Duration(seconds: 5));
    if (!hasLine || !lines.current.startsWith('READY:')) {
      final error = await process.stderr.transform(utf8.decoder).join();
      throw StateError('primary helper failed to start: $error');
    }
    return _PrimaryHelper._(
      process,
      lines,
      lines.current.substring('READY:'.length),
    );
  }

  Future<void> send(String command) async {
    process.stdin.writeln(command);
    await process.stdin.flush();
  }

  Future<String> nextLine() async {
    final hasLine = await _lines.moveNext().timeout(const Duration(seconds: 2));
    if (!hasLine) throw StateError('primary helper exited without a response');
    return _lines.current;
  }

  Future<void> stop() async {
    try {
      await send('STOP').timeout(const Duration(seconds: 1));
    } catch (_) {
      // The helper may already have exited after a failed assertion.
    }
    try {
      await process.exitCode.timeout(const Duration(seconds: 3));
    } on TimeoutException {
      process.kill();
      await process.exitCode;
    }
    await _lines.cancel();
  }
}
