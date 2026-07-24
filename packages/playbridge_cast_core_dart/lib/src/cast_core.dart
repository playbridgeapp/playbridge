import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';

import 'models.dart';
import 'native_bindings.dart';

final class CastCoreLibrary {
  CastCoreLibrary._(this._bindings);

  final NativeBindings _bindings;

  int get abiVersion => _bindings.abiVersion;

  factory CastCoreLibrary.open({String? libraryPath}) {
    if (libraryPath != null) {
      return CastCoreLibrary._(
          NativeBindings(DynamicLibrary.open(libraryPath)));
    }
    Object? lastError;
    for (final path in candidateLibraryPaths()) {
      try {
        return CastCoreLibrary._(NativeBindings(DynamicLibrary.open(path)));
      } on ArgumentError catch (error) {
        lastError = error;
      }
    }
    throw StateError(
      'Unable to load PlayBridge Cast Core. Tried ${candidateLibraryPaths().join(', ')}. '
      'Last error: $lastError',
    );
  }

  DiscoveryScanner discover({
    Set<ReceiverProtocol> protocols = const {
      ReceiverProtocol.playBridge,
      ReceiverProtocol.dlna,
      ReceiverProtocol.roku,
      ReceiverProtocol.googleCast,
    },
    Duration timeout = const Duration(seconds: 15),
    Duration pollInterval = const Duration(milliseconds: 50),
  }) {
    if (protocols.isEmpty) {
      throw ArgumentError.value(protocols, 'protocols', 'must not be empty');
    }
    if (timeout <= Duration.zero) {
      throw ArgumentError.value(timeout, 'timeout', 'must be positive');
    }
    final mask = protocols.fold(0, (value, protocol) => value | protocol.mask);
    final handle = _bindings.start(mask, timeout.inMilliseconds);
    if (handle == nullptr) {
      throw StateError('The native discovery worker could not start');
    }
    return DiscoveryScanner._(
      _bindings,
      handle,
      protocols,
      timeout,
      pollInterval,
    );
  }

  CastSession startSession(
    ReceiverEndpoint endpoint, {
    Duration timeout = const Duration(seconds: 15),
    Duration pollInterval = const Duration(milliseconds: 50),
  }) {
    if (timeout <= Duration.zero) {
      throw ArgumentError.value(timeout, 'timeout', 'must be positive');
    }
    if (pollInterval <= Duration.zero) {
      throw ArgumentError.value(
        pollInterval,
        'pollInterval',
        'must be positive',
      );
    }

    final target = jsonEncode(endpoint.toJson()).toNativeUtf8();
    try {
      final handle = _bindings.sessionStart(target, timeout.inMilliseconds);
      if (handle == nullptr) {
        throw StateError('The native receiver session could not start');
      }
      return CastSession._(
        _bindings,
        handle,
        timeout,
        pollInterval,
      );
    } finally {
      calloc.free(target);
    }
  }

  Future<CastSession> connect(
    ReceiverEndpoint endpoint, {
    Duration timeout = const Duration(seconds: 15),
    Duration pollInterval = const Duration(milliseconds: 50),
  }) async {
    final session = startSession(
      endpoint,
      timeout: timeout,
      pollInterval: pollInterval,
    );
    try {
      await session.connected.timeout(
        timeout + const Duration(seconds: 1),
        onTimeout: () => throw const CastSessionError(
          operation: 'connect',
          message: 'Timed out waiting for the receiver to connect',
        ),
      );
      return session;
    } on Object {
      session.dispose();
      rethrow;
    }
  }

  static List<String> candidateLibraryPaths() {
    final executable = File(Platform.resolvedExecutable);
    final executableDir = executable.parent.path;
    final workingDir = Directory.current.path;
    if (Platform.isMacOS) {
      return [
        '$executableDir/../Frameworks/libplaybridge_cast_core_ffi.dylib',
        '$executableDir/libplaybridge_cast_core_ffi.dylib',
        '$workingDir/native/cast_core/macos/libplaybridge_cast_core_ffi.dylib',
        '$workingDir/../../desktop/native/cast_core/macos/libplaybridge_cast_core_ffi.dylib',
        'libplaybridge_cast_core_ffi.dylib',
      ];
    }
    if (Platform.isWindows) {
      return [
        '$executableDir/playbridge_cast_core_ffi.dll',
        '$workingDir/native/cast_core/windows/playbridge_cast_core_ffi.dll',
        '$workingDir/../../desktop/native/cast_core/windows/playbridge_cast_core_ffi.dll',
        'playbridge_cast_core_ffi.dll',
      ];
    }
    if (Platform.isLinux) {
      return [
        '$executableDir/lib/libplaybridge_cast_core_ffi.so',
        '$executableDir/libplaybridge_cast_core_ffi.so',
        '$workingDir/native/cast_core/linux/libplaybridge_cast_core_ffi.so',
        '$workingDir/../../desktop/native/cast_core/linux/libplaybridge_cast_core_ffi.so',
        'libplaybridge_cast_core_ffi.so',
      ];
    }
    throw UnsupportedError('PlayBridge Cast Core is not packaged for this OS');
  }
}

final class CastSession implements Finalizable {
  CastSession._(
    this._bindings,
    this._handle,
    this._operationTimeout,
    Duration pollInterval,
  ) : _finalizer = NativeFinalizer(_bindings.sessionFreePointer.cast()) {
    _finalizer.attach(this, _handle, detach: this);
    _connected.future.ignore();
    _timer = Timer.periodic(pollInterval, (_) => _poll());
  }

  final NativeBindings _bindings;
  final Pointer<Void> _handle;
  final Duration _operationTimeout;
  final NativeFinalizer _finalizer;
  final Completer<CastSessionConnected> _connected = Completer();
  final StreamController<CastSessionEvent> _events =
      StreamController<CastSessionEvent>.broadcast();
  final Map<String, Completer<Object?>> _pending = {};
  late final Timer _timer;
  int _nextRequestId = 1;
  bool _disposed = false;

  Future<CastSessionConnected> get connected => _connected.future;
  Stream<CastSessionEvent> get events => _events.stream;
  bool get isDisposed => _disposed;

  Future<void> load(MediaRequest media) => _operation(
        'load',
        media.toJson(),
      );

  Future<void> play() => _operation('play');
  Future<void> pause() => _operation('pause');
  Future<void> stop() => _operation('stop');
  Future<void> seek(Duration position) => _operation('seek', {
        'position_seconds':
            position.inMicroseconds / Duration.microsecondsPerSecond,
      });
  Future<void> relativeSeek({required bool forward}) =>
      _operation('relative_seek', {'forward': forward});
  Future<PlaybackStatus> status() => _submit<PlaybackStatus>('status');
  Future<void> disconnect() => _operation('disconnect');

  Future<void> _operation(
    String operation, [
    Map<String, Object?> fields = const {},
  ]) =>
      _submit<void>(operation, fields);

  Future<T> _submit<T>(
    String operation, [
    Map<String, Object?> fields = const {},
  ]) {
    if (_disposed) {
      return Future.error(
        CastSessionError(
          operation: operation,
          message: 'The receiver session has been disposed',
        ),
      );
    }

    final requestId = (_nextRequestId++).toString();
    final completer = Completer<Object?>();
    _pending[requestId] = completer;
    final command = <String, Object?>{
      'command': operation,
      'request_id': requestId,
      ...fields,
    };
    final pointer = jsonEncode(command).toNativeUtf8();
    try {
      if (!_bindings.sessionSubmit(_handle, pointer)) {
        throw CastSessionError(
          requestId: requestId,
          operation: operation,
          message: 'The native session command queue rejected the request',
        );
      }
    } on Object catch (error, stackTrace) {
      _pending.remove(requestId);
      completer.completeError(error, stackTrace);
    } finally {
      calloc.free(pointer);
    }

    return completer.future
        .timeout(
          _operationTimeout + const Duration(seconds: 1),
          onTimeout: () => throw CastSessionError(
            requestId: requestId,
            operation: operation,
            message: 'Timed out waiting for the receiver operation',
          ),
        )
        .whenComplete(() => _pending.remove(requestId))
        .then((value) => value as T);
  }

  void _poll() {
    if (_disposed) return;
    try {
      while (true) {
        final jsonPointer = _bindings.sessionNext(_handle, 0);
        if (jsonPointer == nullptr) break;
        try {
          _handleEvent(
            CastSessionEvent.fromJsonString(jsonPointer.toDartString()),
          );
        } finally {
          _bindings.freeString(jsonPointer);
        }
        if (_disposed) break;
      }
    } on Object catch (error, stackTrace) {
      if (!_connected.isCompleted) {
        _connected.completeError(error, stackTrace);
      }
      _failPending(error, stackTrace);
      _events.addError(error, stackTrace);
      dispose();
    }
  }

  void _handleEvent(CastSessionEvent event) {
    _events.add(event);
    switch (event) {
      case CastSessionConnected():
        if (!_connected.isCompleted) _connected.complete(event);
      case CastSessionOperation():
        final completer = _pending[event.requestId];
        if (completer == null || completer.isCompleted) return;
        if (event.ok) {
          completer.complete();
        } else {
          completer.completeError(
            CastSessionError(
              requestId: event.requestId,
              operation: event.operation,
              message: 'The receiver rejected the operation',
            ),
          );
        }
      case CastSessionStatus():
        final completer = _pending[event.requestId];
        if (completer != null && !completer.isCompleted) {
          completer.complete(event.status);
        }
      case CastSessionError():
        final requestId = event.requestId;
        final completer = requestId == null ? null : _pending[requestId];
        if (completer != null && !completer.isCompleted) {
          completer.completeError(event);
        } else if (!_connected.isCompleted) {
          _connected.completeError(event);
        } else if (requestId == null) {
          _failPending(event, StackTrace.current);
        }
      case CastSessionFinished():
        final error = CastSessionError(
          operation: 'session',
          message: 'Receiver session finished: ${event.reason}',
        );
        if (!_connected.isCompleted) _connected.completeError(error);
        _failPending(error, StackTrace.current);
        dispose();
    }
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
    final error = const CastSessionError(
      operation: 'session',
      message: 'The receiver session was disposed',
    );
    if (!_connected.isCompleted) _connected.completeError(error);
    _failPending(error, StackTrace.current);
    _bindings.sessionCancel(_handle);
    _bindings.sessionFree(_handle);
    _finalizer.detach(this);
    _events.close();
  }
}

final class DiscoveryScanner implements Finalizable {
  DiscoveryScanner._(
    this._bindings,
    this._handle,
    this._protocols,
    Duration timeout,
    Duration pollInterval,
  )   : _finalizer = NativeFinalizer(_bindings.freePointer.cast()),
        _deadline = DateTime.now().add(timeout + const Duration(seconds: 1)) {
    _finalizer.attach(this, _handle, detach: this);
    _timer = Timer.periodic(pollInterval, (_) => _poll());
  }

  final NativeBindings _bindings;
  final Pointer<Void> _handle;
  final Set<ReceiverProtocol> _protocols;
  final NativeFinalizer _finalizer;
  final DateTime _deadline;
  final Set<ReceiverProtocol> _finished = {};
  final StreamController<ReceiverEvent> _events =
      StreamController<ReceiverEvent>();
  late final Timer _timer;
  bool _disposed = false;

  Stream<ReceiverEvent> get events => _events.stream;

  void _poll() {
    if (_disposed) return;
    try {
      while (true) {
        final jsonPointer = _bindings.next(_handle, 0);
        if (jsonPointer == nullptr) break;
        try {
          final event =
              ReceiverEvent.fromJsonString(jsonPointer.toDartString());
          _events.add(event);
          if (event is DiscoveryFinished) _finished.add(event.protocol);
        } finally {
          _bindings.freeString(jsonPointer);
        }
      }
      if (_finished.containsAll(_protocols) ||
          DateTime.now().isAfter(_deadline)) {
        dispose();
      }
    } on Object catch (error, stackTrace) {
      _events.addError(error, stackTrace);
      dispose();
    }
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _timer.cancel();
    _bindings.cancel(_handle);
    _bindings.free(_handle);
    _finalizer.detach(this);
    _events.close();
  }
}
