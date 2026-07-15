import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'bridge_paths.dart';

const _lockFileName = 'instance.lock';
const _metadataFileName = 'instance.json';
const _requestTimeout = Duration(seconds: 2);
const _retryDelay = Duration(milliseconds: 50);

typedef InstanceLaunchHandler = FutureOr<void> Function(
  InstanceLaunchRequest request,
);

/// Arguments a secondary process forwards to the running Desktop instance.
class InstanceLaunchRequest {
  const InstanceLaunchRequest({this.castFile, this.castTitle});

  factory InstanceLaunchRequest.fromArgs(List<String> args) {
    return InstanceLaunchRequest(
      castFile: _argValue(args, '--cast-file'),
      castTitle: _argValue(args, '--cast-title'),
    );
  }

  factory InstanceLaunchRequest.fromJson(Map<String, dynamic> json) {
    if (json['action'] != 'activate') {
      throw const FormatException('unsupported instance action');
    }
    final castFile = json['castFile'];
    final castTitle = json['castTitle'];
    if (castFile != null && castFile is! String) {
      throw const FormatException('castFile must be a string');
    }
    if (castTitle != null && castTitle is! String) {
      throw const FormatException('castTitle must be a string');
    }
    return InstanceLaunchRequest(
      castFile: castFile as String?,
      castTitle: castTitle as String?,
    );
  }

  final String? castFile;
  final String? castTitle;

  Map<String, dynamic> toJson({required String token}) => {
        'token': token,
        'action': 'activate',
        if (castFile != null) 'castFile': castFile,
        if (castTitle != null) 'castTitle': castTitle,
      };
}

/// Result of coordinating this process with any existing Desktop process.
class SingleInstanceStartResult {
  const SingleInstanceStartResult._({
    this.coordinator,
    required this.forwarded,
    this.forwardingError,
  });

  const SingleInstanceStartResult.primary(
    SingleInstanceCoordinator coordinator,
  ) : this._(coordinator: coordinator, forwarded: false);

  const SingleInstanceStartResult.secondary({
    required bool forwarded,
    Object? forwardingError,
  }) : this._(
          forwarded: forwarded,
          forwardingError: forwardingError,
        );

  final SingleInstanceCoordinator? coordinator;
  final bool forwarded;
  final Object? forwardingError;

  bool get isPrimary => coordinator != null;
}

/// Ensures only one Desktop process starts receiver services for this user.
///
/// The primary process holds an operating-system file lock for its lifetime and
/// publishes a token-authenticated loopback endpoint in `instance.json`.
/// Secondary processes forward their launch request and exit. A failed forward
/// never grants primary ownership while the lock is held.
class SingleInstanceCoordinator {
  SingleInstanceCoordinator._({
    required this.directoryPath,
    required RandomAccessFile lockHandle,
    required ServerSocket server,
    required String token,
  })  : _lockHandle = lockHandle,
        _server = server,
        _token = token;

  final String directoryPath;
  final RandomAccessFile _lockHandle;
  final ServerSocket _server;
  final String _token;
  final Queue<InstanceLaunchRequest> _pending = Queue();

  InstanceLaunchHandler? _handler;
  bool _closed = false;

  String get metadataFilePath => _join(directoryPath, _metadataFileName);

  static Future<SingleInstanceStartResult> coordinate({
    required InstanceLaunchRequest request,
    String? directoryPath,
    Duration forwardTimeout = const Duration(seconds: 5),
  }) async {
    final dirPath = directoryPath ?? bridgeDirPath();
    final directory = Directory(dirPath);
    await directory.create(recursive: true);
    await _restrictPermissions(directory.path, '700');

    final lockHandle =
        await File(_join(dirPath, _lockFileName)).open(mode: FileMode.append);
    try {
      await lockHandle.lock(FileLock.exclusive);
    } on FileSystemException catch (lockError) {
      await lockHandle.close();
      return _forwardToPrimary(
        directoryPath: dirPath,
        request: request,
        timeout: forwardTimeout,
        lockError: lockError,
      );
    }

    ServerSocket? server;
    try {
      server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
      final coordinator = SingleInstanceCoordinator._(
        directoryPath: dirPath,
        lockHandle: lockHandle,
        server: server,
        token: _randomToken(),
      );
      server.listen(
        (socket) => unawaited(coordinator._handleClient(socket)),
        onError: (Object error, StackTrace stack) {
          stderr.writeln('[single-instance] IPC server error: $error');
        },
      );
      await coordinator._writeMetadata();
      return SingleInstanceStartResult.primary(coordinator);
    } catch (_) {
      await server?.close();
      await lockHandle.unlock();
      await lockHandle.close();
      rethrow;
    }
  }

  /// Installs the UI handler and drains requests received during app startup.
  void setLaunchHandler(InstanceLaunchHandler handler) {
    if (_closed) return;
    _handler = handler;
    while (_pending.isNotEmpty) {
      _dispatch(_pending.removeFirst());
    }
  }

  void _accept(InstanceLaunchRequest request) {
    final handler = _handler;
    if (handler == null) {
      _pending.add(request);
      return;
    }
    _dispatch(request);
  }

  void _dispatch(InstanceLaunchRequest request) {
    final handler = _handler;
    if (handler == null) {
      _pending.add(request);
      return;
    }
    Future<void>.sync(() => handler(request)).catchError(
      (Object error, StackTrace stack) {
        stderr.writeln('[single-instance] launch handler failed: $error');
      },
    );
  }

  Future<void> _handleClient(Socket socket) async {
    var response = <String, dynamic>{'ok': false, 'error': 'invalid request'};
    try {
      final line = await utf8.decoder
          .bind(socket)
          .transform(const LineSplitter())
          .first
          .timeout(_requestTimeout);
      final decoded = jsonDecode(line);
      if (decoded is! Map<String, dynamic>) {
        throw const FormatException('request must be an object');
      }
      if (decoded['token'] != _token) {
        response = {'ok': false, 'error': 'unauthorized'};
      } else {
        final request = InstanceLaunchRequest.fromJson(decoded);
        _accept(request);
        response = {'ok': true};
      }
    } catch (error) {
      response = {'ok': false, 'error': '$error'};
    }

    try {
      socket.writeln(jsonEncode(response));
      await socket.flush();
    } catch (_) {
      // The secondary may have exited while the request was being processed.
    } finally {
      await socket.close();
    }
  }

  Future<void> _writeMetadata() async {
    final file = File(metadataFilePath);
    final handle = await file.open(mode: FileMode.write);
    try {
      await _restrictPermissions(file.path, '600');
      await handle.writeString(jsonEncode({
        'port': _server.port,
        'token': _token,
      }));
      await handle.flush();
    } finally {
      await handle.close();
    }
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _handler = null;
    _pending.clear();
    await _server.close();
    try {
      await File(metadataFilePath).delete();
    } on FileSystemException {
      // A missing stale metadata file does not affect lock ownership.
    }
    await _lockHandle.unlock();
    await _lockHandle.close();
  }

  static Future<SingleInstanceStartResult> _forwardToPrimary({
    required String directoryPath,
    required InstanceLaunchRequest request,
    required Duration timeout,
    required Object lockError,
  }) async {
    final deadline = DateTime.now().add(timeout);
    Object lastError = lockError;
    do {
      Socket? socket;
      try {
        final metadata = await _readMetadata(directoryPath);
        socket = await Socket.connect(
          InternetAddress.loopbackIPv4,
          metadata.port,
          timeout: _requestTimeout,
        );
        socket.writeln(jsonEncode(request.toJson(token: metadata.token)));
        await socket.flush();
        final line = await utf8.decoder
            .bind(socket)
            .transform(const LineSplitter())
            .first
            .timeout(_requestTimeout);
        final decoded = jsonDecode(line);
        if (decoded is Map<String, dynamic> && decoded['ok'] == true) {
          await socket.close();
          return const SingleInstanceStartResult.secondary(forwarded: true);
        }
        throw StateError(
          decoded is Map<String, dynamic>
              ? '${decoded['error'] ?? 'primary rejected request'}'
              : 'invalid primary response',
        );
      } catch (error) {
        lastError = error;
        socket?.destroy();
        if (DateTime.now().isBefore(deadline)) {
          await Future<void>.delayed(_retryDelay);
        }
      }
    } while (DateTime.now().isBefore(deadline));

    return SingleInstanceStartResult.secondary(
      forwarded: false,
      forwardingError: lastError,
    );
  }

  static Future<_InstanceMetadata> _readMetadata(String directoryPath) async {
    final value = jsonDecode(
      await File(_join(directoryPath, _metadataFileName)).readAsString(),
    );
    if (value is! Map<String, dynamic>) {
      throw const FormatException('instance metadata must be an object');
    }
    final port = value['port'];
    final token = value['token'];
    if (port is! int || port < 1 || port > 65535) {
      throw const FormatException('invalid instance port');
    }
    if (token is! String || token.isEmpty) {
      throw const FormatException('invalid instance token');
    }
    return _InstanceMetadata(port: port, token: token);
  }
}

class _InstanceMetadata {
  const _InstanceMetadata({required this.port, required this.token});

  final int port;
  final String token;
}

String? _argValue(List<String> args, String flag) {
  final index = args.indexOf(flag);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : null;
}

String _join(String directory, String name) {
  return Platform.isWindows ? '$directory\\$name' : '$directory/$name';
}

String _randomToken() {
  final random = Random.secure();
  return base64Url
      .encode(List<int>.generate(32, (_) => random.nextInt(256)))
      .replaceAll('=', '');
}

Future<void> _restrictPermissions(String path, String mode) async {
  if (Platform.isWindows) return;
  final result = await Process.run('chmod', [mode, path]);
  if (result.exitCode != 0) {
    throw FileSystemException('failed to chmod $mode', path);
  }
}
