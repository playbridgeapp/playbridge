import 'dart:io';

import 'package:flutter/foundation.dart';

const bool extensionRequestDebugLoggingEnabled = kDebugMode;

/// Writes the HTTP request context received from the browser extension.
///
/// This is enabled only in debug builds. Output goes directly to stderr so it
/// is not captured by PlayBridge's persistent `debugPrint` diagnostics.
void debugLogExtensionCastRequest({
  required String url,
  Map<String, String>? headers,
}) {
  if (!extensionRequestDebugLoggingEnabled) return;
  for (final line in formatExtensionCastRequestForDebug(
    url: url,
    headers: headers,
  )) {
    stderr.writeln(line);
  }
}

void debugLogNetworkRequest({
  required String source,
  required String url,
  Map<String, String>? headers,
}) {
  if (!kDebugMode) return;
  for (final line in formatExtensionCastRequestForDebug(
    url: url,
    headers: headers,
  )) {
    stderr.writeln(line.replaceFirst('[ext-bridge]', '[$source]'));
  }
}

void debugLogNetworkResponse({
  required String source,
  required String url,
  required int statusCode,
  required Map<String, String> headers,
}) {
  if (!kDebugMode) return;
  stderr.writeln('[$source] response: HTTP $statusCode '
      '${_escapeControlCharacters(url)}');
  stderr.writeln('[$source] response headers (${headers.length}):');
  final entries = headers.entries.toList()
    ..sort((a, b) => a.key.toLowerCase().compareTo(b.key.toLowerCase()));
  for (final entry in entries) {
    stderr.writeln('[$source]   ${_escapeControlCharacters(entry.key)}: '
        '${_escapeControlCharacters(entry.value)}');
  }
}

/// Formats a deterministic, complete representation for tests and explicitly
/// enabled debug terminal output.
List<String> formatExtensionCastRequestForDebug({
  required String url,
  Map<String, String>? headers,
}) {
  final entries = headers?.entries.toList() ?? <MapEntry<String, String>>[];
  entries.sort((a, b) {
    final byLowercaseName = a.key.toLowerCase().compareTo(b.key.toLowerCase());
    return byLowercaseName != 0 ? byLowercaseName : a.key.compareTo(b.key);
  });

  final lines = <String>[
    '[ext-bridge] extension cast request:',
    '[ext-bridge]   URL: ${_escapeControlCharacters(url)}',
    '[ext-bridge]   Headers (${entries.length}):',
  ];
  if (entries.isEmpty) {
    lines.add('[ext-bridge]     (none)');
    return lines;
  }

  for (final entry in entries) {
    final name = _escapeControlCharacters(entry.key);
    final value = _escapeControlCharacters(entry.value);
    lines.add('[ext-bridge]     $name: $value');
  }
  return lines;
}

String _escapeControlCharacters(String value) => value
    .replaceAll(r'\', r'\\')
    .replaceAll('\r', r'\r')
    .replaceAll('\n', r'\n')
    .replaceAll('\t', r'\t');
