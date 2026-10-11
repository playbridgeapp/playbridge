// Plain-Dart native messaging stub. GUI absence never closes the browser port;
// only an explicit launch_app may launch PlayBridge, without media arguments.
// Build: dart compile exe bin/playbridge_host.dart -o build/playbridge_host
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:playbridge_desktop/native_messaging_host.dart';

Future<void> main() async {
  await NativeMessagingHost(input: stdin, writeMessage: _writeMessage).run();
}

void _writeMessage(Object message) {
  final bytes = utf8.encode(jsonEncode(message));
  final header = Uint8List(4);
  ByteData.sublistView(header).setUint32(0, bytes.length, Endian.little);
  stdout.add(header);
  stdout.add(bytes);
}
