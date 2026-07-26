import 'dart:async';
import 'dart:convert';
import 'dart:ffi';

import 'package:ffi/ffi.dart';

import 'cast_core.dart';

typedef _StartNative = Pointer<Void> Function();
typedef _StartDart = Pointer<Void> Function();
typedef _SubmitNative = Bool Function(Pointer<Void>, Pointer<Utf8>);
typedef _SubmitDart = bool Function(Pointer<Void>, Pointer<Utf8>);
typedef _NextNative = Pointer<Utf8> Function(Pointer<Void>, Uint64);
typedef _NextDart = Pointer<Utf8> Function(Pointer<Void>, int);
typedef _HandleNative = Void Function(Pointer<Void>);
typedef _HandleDart = void Function(Pointer<Void>);
typedef _StringFreeNative = Void Function(Pointer<Utf8>);
typedef _StringFreeDart = void Function(Pointer<Utf8>);
typedef _AbiVersionNative = Uint32 Function();
typedef _AbiVersionDart = int Function();

const senderServicesAbiVersion = 1;

final class RegisteredMedia {
  const RegisteredMedia({
    required this.id,
    required this.url,
    this.encryptedUrl,
  });

  factory RegisteredMedia.fromJson(Map<String, Object?> json) =>
      RegisteredMedia(
        id: json['id']! as String,
        url: json['url']! as String,
        encryptedUrl: json['encrypted_url'] as String?,
      );

  final String id;
  final String url;
  final String? encryptedUrl;
}

final class BrowserHostInfo {
  BrowserHostInfo({required List<String> urls, required this.port})
      : urls = List.unmodifiable(urls);

  factory BrowserHostInfo.fromJson(Map<String, Object?> json) =>
      BrowserHostInfo(
        urls: (json['urls']! as List<Object?>).cast<String>(),
        port: json['port']! as int,
      );

  final List<String> urls;
  final int port;
}

final class SenderServices implements Finalizable {
  SenderServices._(
    this._bindings,
    this._handle,
    Duration pollInterval,
  ) : _finalizer = NativeFinalizer(_bindings.freePointer.cast()) {
    _finalizer.attach(this, _handle, detach: this);
    _timer = Timer.periodic(pollInterval, (_) => _poll());
  }

  factory SenderServices.start({
    String? libraryPath,
    Duration pollInterval = const Duration(milliseconds: 50),
    Duration operationTimeout = const Duration(seconds: 15),
  }) {
    if (pollInterval <= Duration.zero) {
      throw ArgumentError.value(
        pollInterval,
        'pollInterval',
        'must be positive',
      );
    }
    if (operationTimeout <= Duration.zero) {
      throw ArgumentError.value(
        operationTimeout,
        'operationTimeout',
        'must be positive',
      );
    }

    final bindings = _SenderServicesBindings(_openLibrary(libraryPath));
    final handle = bindings.start();
    if (handle == nullptr) {
      throw StateError('The native sender services worker could not start');
    }
    return SenderServices._(bindings, handle, pollInterval)
      .._operationTimeout = operationTimeout;
  }

  final _SenderServicesBindings _bindings;
  final Pointer<Void> _handle;
  final NativeFinalizer _finalizer;
  final StreamController<Map<String, Object?>> _events =
      StreamController<Map<String, Object?>>.broadcast();
  final Map<String, Completer<Object?>> _pending = {};
  late final Timer _timer;
  late Duration _operationTimeout;
  int _nextRequestId = 1;
  bool _disposed = false;

  Stream<Map<String, Object?>> get events => _events.stream;
  bool get isDisposed => _disposed;

  Future<RegisteredMedia> registerUrl({
    required String host,
    required String url,
    Map<String, String> headers = const {},
  }) async =>
      RegisteredMedia.fromJson(
        await _submitData('proxy_register_url', {
          'host': host,
          'url': url,
          'headers': headers,
        }),
      );

  Future<RegisteredMedia> registerFile({
    required String host,
    required String path,
    String? contentType,
    Duration ttl = const Duration(hours: 6),
  }) async =>
      RegisteredMedia.fromJson(
        await _submitData('proxy_register_file', {
          'host': host,
          'path': path,
          if (contentType != null) 'content_type': contentType,
          'ttl_ms': ttl.inMilliseconds,
        }),
      );

  Future<bool> revoke(String id) async =>
      (await _submitData('proxy_revoke', {'id': id}))['revoked']! as bool;

  Future<BrowserHostInfo> startBrowser({int? preferredPort}) async =>
      BrowserHostInfo.fromJson(
        await _submitData('browser_start', {
          if (preferredPort != null) 'preferred_port': preferredPort,
        }),
      );

  Future<void> stopBrowser() => _submitVoid('browser_stop');

  Future<void> approveBrowser({
    required String sessionId,
    required String code,
  }) =>
      _submitVoid('browser_approve', {
        'session_id': sessionId,
        'code': code,
      });

  Future<String> loadBrowser({
    required String sessionId,
    required String url,
    String? title,
    String? contentType,
    String? posterUrl,
    String? subtitleUrl,
    Duration? startPosition,
  }) async =>
      (await _submitData('browser_load', {
        'session_id': sessionId,
        'media': {
          'url': url,
          if (title != null) 'title': title,
          if (contentType != null) 'contentType': contentType,
          if (posterUrl != null) 'posterUrl': posterUrl,
          if (subtitleUrl != null) 'subtitleUrl': subtitleUrl,
          if (startPosition != null)
            'startPositionMs': startPosition.inMilliseconds,
        },
      }))['browserRequestId']! as String;

  Future<String> controlBrowser({
    required String sessionId,
    required String action,
    double? value,
  }) async =>
      (await _submitData('browser_control', {
        'session_id': sessionId,
        'action': action,
        if (value != null) 'value': value,
      }))['browserRequestId']! as String;

  Future<bool> disconnectBrowser(String sessionId) async =>
      (await _submitData('browser_disconnect', {
        'session_id': sessionId,
      }))['disconnected']! as bool;

  /// Clear auto-approve for [receiverId] and disconnect any live sessions.
  /// Returns how many sessions were closed.
  Future<int> forgetBrowserReceiver(String receiverId) async =>
      ((await _submitData('browser_forget', {
        'receiver_id': receiverId,
      }))['forgotten']! as num)
          .toInt();

  Future<void> _submitVoid(
    String command, [
    Map<String, Object?> fields = const {},
  ]) async {
    await _submit(command, fields);
  }

  Future<Map<String, Object?>> _submitData(
    String command, [
    Map<String, Object?> fields = const {},
  ]) async =>
      ((await _submit(command, fields)) as Map).cast<String, Object?>();

  Future<Object?> _submit(
    String command, [
    Map<String, Object?> fields = const {},
  ]) {
    if (_disposed) {
      return Future.error(
        StateError('The native sender services worker has been disposed'),
      );
    }

    final requestId = (_nextRequestId++).toString();
    final completer = Completer<Object?>();
    _pending[requestId] = completer;
    final pointer = jsonEncode({
      'command': command,
      'request_id': requestId,
      ...fields,
    }).toNativeUtf8();
    try {
      if (!_bindings.submit(_handle, pointer)) {
        throw StateError('The native sender services queue rejected $command');
      }
    } on Object catch (error, stackTrace) {
      _pending.remove(requestId);
      completer.completeError(error, stackTrace);
    } finally {
      calloc.free(pointer);
    }

    return completer.future
        .timeout(
          _operationTimeout,
          onTimeout: () => throw TimeoutException(
            'Timed out waiting for $command',
            _operationTimeout,
          ),
        )
        .whenComplete(() => _pending.remove(requestId));
  }

  void _poll() {
    if (_disposed) return;
    try {
      while (true) {
        final pointer = _bindings.next(_handle, 0);
        if (pointer == nullptr) break;
        try {
          final decoded = jsonDecode(pointer.toDartString());
          if (decoded is Map<String, Object?>) _handleEvent(decoded);
        } finally {
          _bindings.freeString(pointer);
        }
      }
    } on Object catch (error, stackTrace) {
      _events.addError(error, stackTrace);
      _failPending(error, stackTrace);
      dispose();
    }
  }

  void _handleEvent(Map<String, Object?> event) {
    final requestId = event['requestId']?.toString();
    if (event['event'] == 'operation' && requestId != null) {
      final completer = _pending[requestId];
      if (completer != null && !completer.isCompleted) {
        completer.complete(event['data']);
      }
    } else if (event['event'] == 'error' && requestId != null) {
      final completer = _pending[requestId];
      if (completer != null && !completer.isCompleted) {
        completer.completeError(
          StateError(
            '${event['operation']}: ${event['message'] ?? 'unknown error'}',
          ),
        );
      }
    }
    _events.add(Map.unmodifiable(event));
  }

  void _failPending(Object error, StackTrace stackTrace) {
    for (final completer in _pending.values) {
      if (!completer.isCompleted) completer.completeError(error, stackTrace);
    }
    _pending.clear();
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _timer.cancel();
    _failPending(
      StateError('The native sender services worker was disposed'),
      StackTrace.current,
    );
    _bindings.cancel(_handle);
    _bindings.free(_handle);
    _finalizer.detach(this);
    _events.close();
  }

  static DynamicLibrary _openLibrary(String? libraryPath) {
    if (libraryPath != null) return DynamicLibrary.open(libraryPath);
    Object? lastError;
    for (final path in CastCoreLibrary.candidateLibraryPaths()) {
      try {
        return DynamicLibrary.open(path);
      } on ArgumentError catch (error) {
        lastError = error;
      }
    }
    throw StateError(
      'Unable to load PlayBridge sender services. '
      'Last error: $lastError',
    );
  }
}

final class _SenderServicesBindings {
  _SenderServicesBindings(DynamicLibrary library)
      : abiVersion = library.lookupFunction<_AbiVersionNative, _AbiVersionDart>(
          'pb_sender_services_abi_version',
        )(),
        start = library.lookupFunction<_StartNative, _StartDart>(
          'pb_sender_services_start',
        ),
        submit = library.lookupFunction<_SubmitNative, _SubmitDart>(
          'pb_sender_services_submit_json',
        ),
        next = library.lookupFunction<_NextNative, _NextDart>(
          'pb_sender_services_next_json',
        ),
        cancel = library.lookupFunction<_HandleNative, _HandleDart>(
          'pb_sender_services_cancel',
        ),
        free = library.lookupFunction<_HandleNative, _HandleDart>(
          'pb_sender_services_free',
        ),
        freeString = library.lookupFunction<_StringFreeNative, _StringFreeDart>(
          'pb_string_free',
        ),
        freePointer = library.lookup<NativeFunction<_HandleNative>>(
          'pb_sender_services_free',
        ) {
    if (abiVersion != senderServicesAbiVersion) {
      throw UnsupportedError(
        'Unsupported sender services ABI $abiVersion; '
        'this package requires ABI $senderServicesAbiVersion',
      );
    }
  }

  final int abiVersion;
  final _StartDart start;
  final _SubmitDart submit;
  final _NextDart next;
  final _HandleDart cancel;
  final _HandleDart free;
  final _StringFreeDart freeString;
  final Pointer<NativeFunction<_HandleNative>> freePointer;
}
