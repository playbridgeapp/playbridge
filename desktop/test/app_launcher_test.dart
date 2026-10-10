import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/app_launcher.dart';

void main() {
  test('macOS bundled host launches without file arguments', () {
    final command = desktopLaunchCommand(
        hostExecutable:
            '/Applications/PlayBridge.app/Contents/Resources/playbridge_host',
        operatingSystem: 'macos');
    expect(command!.executable, 'open');
    expect(command.arguments, ['-a', '/Applications/PlayBridge.app']);
  });
  test(
      'extracted host uses the app-written hint and falls back after relocation',
      () async {
    final directory =
        await Directory.systemTemp.createTemp('desktop-mac-launch-');
    try {
      final bundle = '${directory.path}/PlayBridge.app';
      final executable = File('$bundle/Contents/MacOS/playbridge_desktop');
      await executable.parent.create(recursive: true);
      await executable.writeAsString('fixture');
      final command = desktopLaunchCommand(
          hostExecutable: '/tmp/support/host/playbridge_host',
          operatingSystem: 'macos',
          appExecutable: executable.path);
      expect(command!.arguments, ['-a', bundle]);
      final cast = desktopLaunchCommand(
          hostExecutable: '/tmp/support/host/playbridge_cast',
          operatingSystem: 'macos',
          appExecutable: executable.path,
          castFile: '/media/My Movie.mp4',
          title: r'Movie $(literal)');
      expect(cast!.arguments, [
        '-a',
        bundle,
        '--args',
        '--cast-file',
        '/media/My Movie.mp4',
        '--cast-title',
        r'Movie $(literal)'
      ]);
      final moved = desktopLaunchCommand(
          hostExecutable: '$bundle/Contents/Resources/playbridge_host',
          operatingSystem: 'macos',
          appExecutable: '/removed/Old.app/Contents/MacOS/playbridge_desktop');
      expect(moved!.arguments, ['-a', bundle]);
    } finally {
      await directory.delete(recursive: true);
    }
  });
  for (final os in ['windows', 'linux']) {
    test('$os extracted host uses GUI path and sibling fallback', () async {
      final directory =
          await Directory.systemTemp.createTemp('desktop-launch-');
      try {
        final app = File(
            '${directory.path}/${os == 'windows' ? 'playbridge_desktop.exe' : 'playbridge_desktop'}');
        await app.writeAsString('fixture');
        final command = desktopLaunchCommand(
            hostExecutable: '/missing/support/playbridge_host',
            operatingSystem: os,
            appExecutable: app.path);
        expect(command!.executable, app.path);
        expect(command.arguments, isEmpty);
        final sibling = desktopLaunchCommand(
            hostExecutable: '${directory.path}/playbridge_cast',
            operatingSystem: os,
            appExecutable: '/missing/stale-app');
        expect(sibling!.executable, app.path);
      } finally {
        await directory.delete(recursive: true);
      }
    });
  }
}
