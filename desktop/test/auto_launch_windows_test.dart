import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/auto_launch.dart';

void main() {
  const exe = r'C:\Program Files\PlayBridge\playbridge_desktop.exe';

  late List<List<String>> calls;
  String? stored;

  Future<ProcessResult> fakeReg(String executable, List<String> args) async {
    calls.add(args);
    switch (args.first) {
      case 'query':
        return stored == null
            ? ProcessResult(0, 1, '', 'not found')
            : ProcessResult(0, 0, '    PlayBridge    REG_SZ    $stored\n', '');
      case 'add':
        stored = args[args.indexOf('/d') + 1];
        return ProcessResult(0, 0, '', '');
      case 'delete':
        stored = null;
        return ProcessResult(0, 0, '', '');
    }
    return ProcessResult(0, 1, '', 'unknown');
  }

  AutoLaunch auto([String path = exe]) => AutoLaunch(
        executablePath: path,
        registryRunner: fakeReg,
        useWindowsRegistry: true,
      );

  setUp(() {
    calls = [];
    stored = null;
  });

  test('enable stores the quoted executable path and reads back as enabled',
      () async {
    expect(await auto().isEnabled(), isFalse);
    await auto().enable();
    expect(stored, '"$exe"');
    expect(await auto().isEnabled(), isTrue);
  });

  test('a value pointing at another install does not count as enabled',
      () async {
    stored = r'"D:\Old\playbridge_desktop.exe"';
    expect(await auto().isEnabled(), isFalse);
  });

  test('disable removes the value and is a no-op when nothing is stored',
      () async {
    await auto().disable();
    expect(calls.where((c) => c.first == 'delete'), isEmpty);

    await auto().enable();
    await auto().disable();
    expect(stored, isNull);
  });

  test('enable and disable report a failing reg.exe', () async {
    Future<ProcessResult> failing(String e, List<String> a) async =>
        a.first == 'query'
            ? ProcessResult(0, 0, exe, '')
            : ProcessResult(0, 5, '', 'Access is denied');
    final broken = AutoLaunch(
        executablePath: exe,
        registryRunner: failing,
        useWindowsRegistry: true);
    expect(broken.enable(), throwsA(isA<ProcessException>()));
    expect(broken.disable(), throwsA(isA<ProcessException>()));
  });
}
