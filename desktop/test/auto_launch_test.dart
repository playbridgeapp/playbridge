@TestOn('mac-os || linux')
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/auto_launch.dart';

void main() {
  late Directory home;

  setUp(() => home = Directory.systemTemp.createTempSync('pb_auto_launch'));
  tearDown(() => home.deleteSync(recursive: true));

  File entry(String id) => Platform.isMacOS
      ? File('${home.path}/Library/LaunchAgents/$id.plist')
      : File('${home.path}/.config/autostart/$id.desktop');

  AutoLaunch auto() => AutoLaunch(
      executablePath: '/Applications/PlayBridge.app', home: home.path);

  test('legacy entries count as enabled and migrate to the new id', () async {
    for (final legacy in AutoLaunch.legacyAppIds) {
      entry(legacy)
        ..parent.createSync(recursive: true)
        ..writeAsStringSync('old');
    }
    expect(await auto().isEnabled(), isTrue);

    await auto().migrateLegacy();

    expect(entry(AutoLaunch.appId).readAsStringSync(),
        contains('/Applications/PlayBridge.app'));
    for (final legacy in AutoLaunch.legacyAppIds) {
      expect(entry(legacy).existsSync(), isFalse);
    }
  });

  test('migration does nothing without a legacy entry', () async {
    await auto().migrateLegacy();
    expect(await auto().isEnabled(), isFalse);
    expect(entry(AutoLaunch.appId).existsSync(), isFalse);
  });

  test('disable removes new and legacy entries', () async {
    await auto().enable();
    entry(AutoLaunch.legacyAppIds.first).writeAsStringSync('old');
    await auto().disable();
    expect(await auto().isEnabled(), isFalse);
  });
}
