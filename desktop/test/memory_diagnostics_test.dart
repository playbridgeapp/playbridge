import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/logging/log_store.dart';
import 'package:playbridge_desktop/logging/memory_diagnostics.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
      'disabled logging reads nothing; enabling starts bounded sampling',
      (tester) async {
    final enabled = ValueNotifier(false);
    var reads = 0;
    final entries = <String>[];
    final diagnostics = MemoryDiagnostics(
      enabled: enabled,
      readSnapshot: () async => {'rssBytes': ++reads * 1024},
      write: (_, message) => entries.add(message),
    )..start();
    await tester.pump(const Duration(minutes: 1));
    diagnostics.playbackChanged('playing');
    expect(reads, 0);
    enabled.value = true;
    await tester.pump();
    expect(entries, hasLength(1));
    expect(jsonDecode(entries.single)['reason'], 'logging_enabled');
    await tester.pump(const Duration(seconds: 30));
    expect(entries, hasLength(2));
    diagnostics.playbackChanged('playing');
    await tester.pump();
    expect(entries, hasLength(2));
    diagnostics.playbackChanged('stopped');
    await tester.pump(const Duration(seconds: 1));
    expect(entries, hasLength(3));
    enabled.value = false;
    await tester.pump(const Duration(minutes: 1));
    expect(reads, 3);
    diagnostics.dispose();
    enabled.dispose();
  });

  test('footprint detects compressed high memory even with a small RSS',
      () async {
    final enabled = ValueNotifier(true);
    var bytes = 200 * 1024 * 1024;
    final entries = <(LogLevel, Map<String, dynamic>)>[];
    final diagnostics = MemoryDiagnostics(
      enabled: enabled,
      readSnapshot: () async => {
        'rssBytes': 200 * 1024 * 1024,
        'footprintBytes': bytes,
      },
      write: (level, message) => entries.add((level, jsonDecode(message))),
    );
    await diagnostics.sample();
    bytes = 12 * 1024 * 1024 * 1024;
    await diagnostics.sample();
    expect(entries.last.$1, LogLevel.warn);
    expect(entries.last.$2['metric'], 'footprint');
    expect(entries.last.$2['highMemory'], true);
    expect(entries.last.$2['rapidGrowth'], true);
    expect(entries.last.$2['observedPeakBytes'], bytes);
    expect(entries.last.$2['baselineBytes'], 200 * 1024 * 1024);
    diagnostics.dispose();
    enabled.dispose();
  });

  test(
      'pending reads cannot overlap or log after disable/re-enable or disposal',
      () async {
    final enabled = ValueNotifier(true);
    var pending = Completer<Map<String, Object?>>();
    var reads = 0;
    final entries = <String>[];
    final diagnostics = MemoryDiagnostics(
      enabled: enabled,
      readSnapshot: () {
        reads++;
        return pending.future;
      },
      write: (_, message) => entries.add(message),
    )..start();
    await diagnostics.sample();
    expect(reads, 1);
    enabled.value = false;
    enabled.value = true;
    pending.complete({'rssBytes': 1024});
    await Future<void>.delayed(Duration.zero);
    expect(entries, isEmpty);
    pending = Completer<Map<String, Object?>>();
    final next = diagnostics.sample();
    diagnostics.dispose();
    pending.complete({'rssBytes': 2048});
    await next;
    expect(entries, isEmpty);
    enabled.dispose();
  });

  test(
      'failed diagnostics do not expose exception contents or block later reads',
      () async {
    final enabled = ValueNotifier(true);
    var failed = true;
    final entries = <String>[];
    final diagnostics = MemoryDiagnostics(
      enabled: enabled,
      readSnapshot: () async {
        if (failed) throw StateError('https://secret.test/video?token=private');
        return {'rssBytes': 1024};
      },
      write: (_, message) => entries.add(message),
    );
    await diagnostics.sample();
    failed = false;
    await diagnostics.sample();
    expect(entries, hasLength(2));
    expect(entries.join(), isNot(contains('secret.test')));
    expect(entries.join(), isNot(contains('private')));
    expect(jsonDecode(entries.last)['rssBytes'], 1024);
    diagnostics.dispose();
    enabled.dispose();
  });

  testWidgets(
      'macOS channel supplies footprint; unknown native fields are omitted',
      (tester) async {
    const channel = MethodChannel('com.playbridge.desktop/memory');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'snapshot');
      return {
        'footprintBytes': 12 * 1024 * 1024 * 1024,
        'peakFootprintBytes': 13 * 1024 * 1024 * 1024,
        'url': 'https://secret.test/?token=private',
      };
    });
    late Map<String, Object?> snapshot;
    await tester.runAsync(() async {
      snapshot = await readProcessMemory();
    });
    expect(snapshot['footprintBytes'], 12 * 1024 * 1024 * 1024);
    expect(snapshot['peakFootprintBytes'], 13 * 1024 * 1024 * 1024);
    expect(snapshot, isNot(contains('url')));
    expect(snapshot['rssBytes'], isPositive);
    messenger.setMockMethodCallHandler(channel, null);
  }, skip: !Platform.isMacOS);
}
