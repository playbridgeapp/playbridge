import 'dart:async';
import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';

import 'models.dart';
import 'native_bindings.dart';

final class CastCoreLibrary {
  CastCoreLibrary._(this._bindings);

  final NativeBindings _bindings;

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

  static List<String> candidateLibraryPaths() {
    final executable = File(Platform.resolvedExecutable);
    final executableDir = executable.parent.path;
    if (Platform.isMacOS) {
      return [
        '$executableDir/../Frameworks/libplaybridge_cast_core_ffi.dylib',
        '$executableDir/libplaybridge_cast_core_ffi.dylib',
        'libplaybridge_cast_core_ffi.dylib',
      ];
    }
    if (Platform.isWindows) {
      return [
        '$executableDir/playbridge_cast_core_ffi.dll',
        'playbridge_cast_core_ffi.dll',
      ];
    }
    if (Platform.isLinux) {
      return [
        '$executableDir/lib/libplaybridge_cast_core_ffi.so',
        '$executableDir/libplaybridge_cast_core_ffi.so',
        'libplaybridge_cast_core_ffi.so',
      ];
    }
    throw UnsupportedError('PlayBridge Cast Core is not packaged for this OS');
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
