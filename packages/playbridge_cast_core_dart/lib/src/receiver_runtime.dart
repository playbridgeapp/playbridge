import 'dart:async';
import 'dart:convert';
import 'dart:ffi';

import 'package:ffi/ffi.dart';

import 'cast_core.dart';

typedef _StartNative = Pointer<Void> Function(Pointer<Utf8>);
typedef _StartDart = Pointer<Void> Function(Pointer<Utf8>);
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

const receiverRuntimeAbiVersion = 1;

final class ReceiverRuntimeConfig {
  const ReceiverRuntimeConfig({
    required this.name,
    required this.uuid,
    required this.certificateDer,
    required this.privateKeyDer,
    required this.privateKeyKind,
    this.preferredPort = 8765,
    this.fallbackAttempts = 32,
    this.authorizedTokens = const [],
    this.players = const [],
    this.browsers = const [],
    this.advertise = false,
  });

  final String name;
  final String uuid;
  final String certificateDer;
  final String privateKeyDer;
  final String privateKeyKind;
  final int preferredPort;
  final int fallbackAttempts;
  final List<String> authorizedTokens;
  final List<String> players;
  final List<String> browsers;
  final bool advertise;

  Map<String, Object?> toJson() => {
        'name': name,
        'uuid': uuid,
        'certificateDer': certificateDer,
        'privateKeyDer': privateKeyDer,
        'privateKeyKind': privateKeyKind,
        'preferredPort': preferredPort,
        'fallbackAttempts': fallbackAttempts,
        'authorizedTokens': authorizedTokens,
        'players': players,
        'browsers': browsers,
        'advertise': advertise,
      };
}

final class ReceiverRuntime implements Finalizable {
  ReceiverRuntime._(
    this._bindings,
    this._handle,
    Duration pollInterval,
  ) : _finalizer = NativeFinalizer(_bindings.freePointer.cast()) {
    _finalizer.attach(this, _handle, detach: this);
    _timer = Timer.periodic(pollInterval, (_) => _poll());
  }

  factory ReceiverRuntime.start(
    ReceiverRuntimeConfig config, {
    String? libraryPath,
    Duration pollInterval = const Duration(milliseconds: 25),
  }) {
    final bindings = _ReceiverRuntimeBindings(_openLibrary(libraryPath));
    final encoded = jsonEncode(config.toJson()).toNativeUtf8();
    late final Pointer<Void> handle;
    try {
      handle = bindings.start(encoded);
    } finally {
      calloc.free(encoded);
    }
    if (handle == nullptr) {
      throw StateError('The native receiver runtime could not start');
    }
    return ReceiverRuntime._(bindings, handle, pollInterval);
  }

  final _ReceiverRuntimeBindings _bindings;
  final Pointer<Void> _handle;
  final NativeFinalizer _finalizer;
  final StreamController<Map<String, Object?>> _events =
      StreamController<Map<String, Object?>>.broadcast();
  final Completer<int> _started = Completer<int>();
  late final Timer _timer;
  bool _disposed = false;

  Stream<Map<String, Object?>> get events => _events.stream;
  Future<int> get started => _started.future;
  bool get isDisposed => _disposed;

  void broadcast(Map<String, Object?> message) {
    _submit({'command': 'broadcast', 'message': message});
  }

  void sendTo(int connectionId, Map<String, Object?> message) {
    _submit({
      'command': 'send_to',
      'connection_id': connectionId,
      'message': message,
    });
  }

  void denyPairing(int connectionId) {
    _submit({
      'command': 'deny_pairing',
      'connection_id': connectionId,
    });
  }

  void disconnectAll() => _submit({'command': 'disconnect_all'});

  void replaceAuthorizedTokens(Iterable<String> tokens) {
    _submit({
      'command': 'replace_authorized_tokens',
      'tokens': tokens.toList(growable: false),
    });
  }

  void _submit(Map<String, Object?> command) {
    if (_disposed) {
      throw StateError('The native receiver runtime has been disposed');
    }
    final encoded = jsonEncode(command).toNativeUtf8();
    try {
      if (!_bindings.submit(_handle, encoded)) {
        throw StateError('The native receiver command queue is unavailable');
      }
    } finally {
      calloc.free(encoded);
    }
  }

  void _poll() {
    if (_disposed) return;
    while (true) {
      final pointer = _bindings.next(_handle, 0);
      if (pointer == nullptr) break;
      late final String text;
      try {
        text = pointer.toDartString();
      } finally {
        _bindings.freeString(pointer);
      }
      final decoded = jsonDecode(text);
      if (decoded is! Map) continue;
      final event = decoded.cast<String, Object?>();
      if (event['event'] == 'started' && !_started.isCompleted) {
        _started.complete(event['port']! as int);
      } else if (event['event'] == 'error' && !_started.isCompleted) {
        _started.completeError(StateError(event['message']! as String));
      }
      _events.add(event);
    }
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _timer.cancel();
    if (!_started.isCompleted) {
      _started.completeError(
        StateError('The native receiver runtime was disposed before startup'),
      );
    }
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
      'Unable to load PlayBridge receiver runtime. Last error: $lastError',
    );
  }
}

final class _ReceiverRuntimeBindings {
  _ReceiverRuntimeBindings(DynamicLibrary library)
      : abiVersion = library.lookupFunction<_AbiVersionNative, _AbiVersionDart>(
          'pb_receiver_runtime_abi_version',
        )(),
        start = library.lookupFunction<_StartNative, _StartDart>(
          'pb_receiver_runtime_start',
        ),
        submit = library.lookupFunction<_SubmitNative, _SubmitDart>(
          'pb_receiver_runtime_submit_json',
        ),
        next = library.lookupFunction<_NextNative, _NextDart>(
          'pb_receiver_runtime_next_json',
        ),
        cancel = library.lookupFunction<_HandleNative, _HandleDart>(
          'pb_receiver_runtime_cancel',
        ),
        free = library.lookupFunction<_HandleNative, _HandleDart>(
          'pb_receiver_runtime_free',
        ),
        freeString = library.lookupFunction<_StringFreeNative, _StringFreeDart>(
          'pb_string_free',
        ),
        freePointer = library.lookup<NativeFunction<_HandleNative>>(
          'pb_receiver_runtime_free',
        ) {
    if (abiVersion != receiverRuntimeAbiVersion) {
      throw UnsupportedError(
        'Unsupported receiver runtime ABI $abiVersion; '
        'this package requires ABI $receiverRuntimeAbiVersion',
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
