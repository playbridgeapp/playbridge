// PlayBridge native-messaging host.
//
// The browser launches this binary and speaks the native-messaging protocol
// over stdio (4-byte little-endian length prefix + UTF-8 JSON). This stub does
// no logic of its own: it reads `bridge.json` (the loopback port + token the
// running desktop app wrote), connects to that loopback socket, authenticates
// with the token, and relays messages both ways. All the real work (discovery,
// pinned wss to the TV, casting) lives in the long-running desktop app.
//
// Build:  dart compile exe bin/playbridge_host.dart -o build/playbridge_host
// (Plain Dart — no Flutter. Imports only `bridge_paths.dart` + dart: libs.)

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:playbridge_desktop/bridge_paths.dart';

Future<void> main() async {
  final info = await _readBridgeInfo();
  if (info == null) {
    _writeMessage({'type': 'error', 'error': 'PlayBridge is not running'});
    return;
  }

  final port = (info['port'] as num).toInt();
  final token = info['token'] as String;

  final Socket socket;
  try {
    socket = await Socket.connect(InternetAddress.loopbackIPv4, port);
  } catch (_) {
    _writeMessage({'type': 'error', 'error': 'cannot reach PlayBridge'});
    return;
  }

  // Authenticate, then relay app → browser (newline-JSON → native frames).
  socket.write('${jsonEncode({'token': token})}\n');
  final sockBuf = <int>[];
  socket.listen(
    (data) {
      sockBuf.addAll(data);
      int nl;
      while ((nl = sockBuf.indexOf(0x0A)) >= 0) {
        final line = utf8.decode(sockBuf.sublist(0, nl)).trim();
        sockBuf.removeRange(0, nl + 1);
        if (line.isEmpty) continue;
        try {
          _writeMessage(jsonDecode(line));
        } catch (_) {}
      }
    },
    onDone: () => exit(0),
    onError: (_) => exit(0),
  );

  // Relay browser → app (native frames → newline-JSON). Returns when stdin (the
  // browser) closes the port.
  await _readNativeMessages((msg) => socket.write('${jsonEncode(msg)}\n'));
  await socket.close();
  exit(0);
}

Future<Map<String, dynamic>?> _readBridgeInfo() async {
  try {
    final file = File(bridgeFilePath());
    if (!await file.exists()) return null;
    final obj = jsonDecode(await file.readAsString());
    return obj is Map<String, dynamic> ? obj : null;
  } catch (_) {
    return null;
  }
}

/// Frames and writes one native message to stdout.
void _writeMessage(Object message) {
  final bytes = utf8.encode(jsonEncode(message));
  final header = Uint8List(4);
  ByteData.sublistView(header).setUint32(0, bytes.length, Endian.little);
  stdout.add(header);
  stdout.add(bytes);
}

/// Reads length-prefixed native messages from stdin until it closes.
Future<void> _readNativeMessages(
    void Function(Map<String, dynamic>) onMessage) async {
  final buf = <int>[];
  await for (final chunk in stdin) {
    buf.addAll(chunk);
    while (true) {
      if (buf.length < 4) break;
      final len = ByteData.sublistView(Uint8List.fromList(buf.sublist(0, 4)))
          .getUint32(0, Endian.little);
      if (buf.length < 4 + len) break;
      final msgBytes = buf.sublist(4, 4 + len);
      buf.removeRange(0, 4 + len);
      try {
        final obj = jsonDecode(utf8.decode(msgBytes));
        if (obj is Map<String, dynamic>) onMessage(obj);
      } catch (_) {}
    }
  }
}
