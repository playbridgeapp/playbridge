import 'dart:ffi';

import 'package:ffi/ffi.dart';

typedef StartNative = Pointer<Void> Function(Uint32, Uint64);
typedef StartDart = Pointer<Void> Function(int, int);
typedef NextNative = Pointer<Utf8> Function(Pointer<Void>, Uint64);
typedef NextDart = Pointer<Utf8> Function(Pointer<Void>, int);
typedef HandleNative = Void Function(Pointer<Void>);
typedef HandleDart = void Function(Pointer<Void>);
typedef StringFreeNative = Void Function(Pointer<Utf8>);
typedef StringFreeDart = void Function(Pointer<Utf8>);
typedef AbiVersionNative = Uint32 Function();
typedef AbiVersionDart = int Function();
typedef SessionStartNative = Pointer<Void> Function(Pointer<Utf8>, Uint64);
typedef SessionStartDart = Pointer<Void> Function(Pointer<Utf8>, int);
typedef SessionSubmitNative = Bool Function(Pointer<Void>, Pointer<Utf8>);
typedef SessionSubmitDart = bool Function(Pointer<Void>, Pointer<Utf8>);

const castCoreAbiVersion = 1;

final class NativeBindings {
  NativeBindings(DynamicLibrary library)
      : this._(library, _abiVersion(library));

  NativeBindings._(DynamicLibrary library, this.abiVersion)
      : start = library.lookupFunction<StartNative, StartDart>(
          'pb_discovery_start',
        ),
        next = library.lookupFunction<NextNative, NextDart>(
          'pb_discovery_next_json',
        ),
        cancel = library.lookupFunction<HandleNative, HandleDart>(
          'pb_discovery_cancel',
        ),
        free = library.lookupFunction<HandleNative, HandleDart>(
          'pb_discovery_free',
        ),
        freeString = library.lookupFunction<StringFreeNative, StringFreeDart>(
          'pb_string_free',
        ),
        freePointer = library.lookup<NativeFunction<HandleNative>>(
          'pb_discovery_free',
        ),
        sessionStart =
            library.lookupFunction<SessionStartNative, SessionStartDart>(
          'pb_session_start',
        ),
        sessionSubmit =
            library.lookupFunction<SessionSubmitNative, SessionSubmitDart>(
          'pb_session_submit_json',
        ),
        sessionNext = library.lookupFunction<NextNative, NextDart>(
          'pb_session_next_json',
        ),
        sessionCancel = library.lookupFunction<HandleNative, HandleDart>(
          'pb_session_cancel',
        ),
        sessionFree = library.lookupFunction<HandleNative, HandleDart>(
          'pb_session_free',
        ),
        sessionFreePointer = library.lookup<NativeFunction<HandleNative>>(
          'pb_session_free',
        );

  static int _abiVersion(DynamicLibrary library) {
    final int version;
    try {
      version = library.lookupFunction<AbiVersionNative, AbiVersionDart>(
        'pb_cast_core_abi_version',
      )();
    } on ArgumentError catch (error) {
      throw UnsupportedError(
        'The packaged PlayBridge Cast Core predates the session ABI: $error',
      );
    }
    if (version != castCoreAbiVersion) {
      throw UnsupportedError(
        'Unsupported PlayBridge Cast Core ABI $version; '
        'this package requires ABI $castCoreAbiVersion',
      );
    }
    return version;
  }

  final int abiVersion;
  final StartDart start;
  final NextDart next;
  final HandleDart cancel;
  final HandleDart free;
  final StringFreeDart freeString;
  final Pointer<NativeFunction<HandleNative>> freePointer;
  final SessionStartDart sessionStart;
  final SessionSubmitDart sessionSubmit;
  final NextDart sessionNext;
  final HandleDart sessionCancel;
  final HandleDart sessionFree;
  final Pointer<NativeFunction<HandleNative>> sessionFreePointer;
}
