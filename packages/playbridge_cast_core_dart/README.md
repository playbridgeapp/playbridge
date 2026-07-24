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

Desktop consumers can also use `SenderServices` when the native library was
built with the `sender-services` feature:

```dart
final services = SenderServices.start();
final media = await services.registerFile(
  host: '192.168.1.20',
  path: '/path/to/video.mp4',
);
final browser = await services.startBrowser();
```

`SenderServices` owns one embedded Rust stream proxy. The browser host is
on-demand and pairing/status events are exposed through `services.events`.
Dispose the service during application shutdown.

Native receiver applications can use `ReceiverRuntime`. Rust owns the secure
network and pairing boundary while the Dart application handles command events
with its platform player:

```dart
final receiver = ReceiverRuntime.start(
  ReceiverRuntimeConfig(
    name: 'My Computer',
    uuid: deviceId,
    certificateDer: certificateDerBase64,
    privateKeyDer: privateKeyDerBase64,
    privateKeyKind: 'pkcs1',
    authorizedTokens: storedTokenDigests,
  ),
);
receiver.events.listen(handleReceiverEvent);
final port = await receiver.started;
```
