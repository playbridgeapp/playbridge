import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';
import 'package:flutter/foundation.dart';
import 'package:media_kit/generated/libmpv/bindings.dart' as generated;
import 'package:media_kit/media_kit.dart';
// These private APIs own media_kit's debug hot-restart reference buffer. Keep
// their use isolated here until the upstream cleanup clears stale callbacks.
// ignore: implementation_imports
import 'package:media_kit/src/player/native/core/native_library.dart';
// ignore: implementation_imports
import 'package:media_kit/src/player/native/utils/native_reference_holder.dart';

/// Initializes media_kit with a Linux debug hot-restart safety workaround.
///
/// media_kit keeps libmpv handles in native memory across hot restarts. Its
/// stale-handle cleanup sends libmpv a `quit` command without first clearing
/// the wakeup callback owned by the previous Dart isolate. libmpv may then call
/// that deleted callback and abort the process. Normal player disposal already
/// clears this callback; mirror that ordering for stale handles as well.
void ensurePlayBridgeMediaKitInitialized() {
  if (kDebugMode && Platform.isLinux) {
    NativeLibrary.ensureInitialized();
    NativeReferenceHolder.ensureInitialized(_disposeStaleLinuxMpvHandles);
  }

  MediaKit.ensureInitialized();
}

void _disposeStaleLinuxMpvHandles(List<Pointer<Void>> references) {
  if (references.isEmpty) {
    return;
  }

  const tag = NativeReferenceHolder.kTag;
  debugPrint('$tag Found ${references.length} reference(s).');
  debugPrint(
    '$tag Disposing safely:\n'
    '${references.map((reference) => reference.address).join('\n')}',
  );

  final mpv = generated.MPV(DynamicLibrary.open(NativeLibrary.path));
  final command = 'quit'.toNativeUtf8();
  try {
    for (final reference in references) {
      final handle = reference.cast<generated.mpv_handle>();
      mpv.mpv_set_wakeup_callback(handle, nullptr, nullptr);
      mpv.mpv_command_string(handle, command.cast());
    }
  } finally {
    malloc.free(command);
  }
}
