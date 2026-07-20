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

final class NativeBindings {
  NativeBindings(DynamicLibrary library)
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
        );

  final StartDart start;
  final NextDart next;
  final HandleDart cancel;
  final HandleDart free;
  final StringFreeDart freeString;
  final Pointer<NativeFunction<HandleNative>> freePointer;
}
