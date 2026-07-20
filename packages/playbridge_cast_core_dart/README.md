# PlayBridge Cast Core for Dart

Reusable `dart:ffi` bindings for the PlayBridge Rust casting core. The package
works in Flutter Desktop and standalone Dart executables on macOS, Windows, and
Linux.

```dart
final core = CastCoreLibrary.open();
final scanner = core.discover(
  protocols: {ReceiverProtocol.playBridge, ReceiverProtocol.dlna},
);
scanner.events.listen(print);
```

Discovery uses non-blocking native polls on a 50 ms timer. Always call
`scanner.dispose()` when the owning screen or command exits; a native finalizer
is retained as a process-exit safety net.

