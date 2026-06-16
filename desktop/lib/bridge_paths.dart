import 'dart:io';

/// Stable, **Flutter-free** location of the bridge handshake file
/// (`bridge.json`, holding the loopback port + token). Shared by the desktop app
/// (writer, via `ExtensionBridge`) and the standalone native-messaging host
/// (reader). It must NOT depend on Flutter/`path_provider`, since the host is a
/// plain `dart compile exe` binary with no Flutter bindings — and both sides must
/// compute the exact same path or the bridge can't connect.
String bridgeDirPath() {
  if (Platform.isWindows) {
    final base = Platform.environment['APPDATA'] ??
        Platform.environment['USERPROFILE'] ??
        '.';
    return '$base\\PlayBridge';
  }
  final home = Platform.environment['HOME'] ?? '.';
  return '$home/.playbridge';
}

String bridgeFilePath() {
  final dir = bridgeDirPath();
  return Platform.isWindows ? '$dir\\bridge.json' : '$dir/bridge.json';
}
