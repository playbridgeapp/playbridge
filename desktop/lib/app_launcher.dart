// Shared by the plain-Dart native host and OS context-menu launcher.
import 'dart:convert';
import 'dart:io';

import 'bridge_paths.dart';

typedef DesktopLaunchCommand = ({
  String executable,
  List<String> arguments,
  bool waitForExit
});

/// Keep media arguments separate from executable paths; extracted Flutter assets
/// use the GUI path written by the app, while sibling/bundle layouts still work.
DesktopLaunchCommand? desktopLaunchCommand({
  required String hostExecutable,
  required String operatingSystem,
  String? appExecutable,
  String? castFile,
  String? title,
}) {
  final args = <String>[
    if (castFile != null) ...['--cast-file', castFile],
    if (castFile != null && title != null) ...['--cast-title', title],
  ];
  if (operatingSystem == 'macos') {
    for (final executable in [
      if (appExecutable != null && File(appExecutable).existsSync())
        appExecutable,
      hostExecutable
    ]) {
      var directory = File(executable).parent;
      for (var i = 0; i < 5; i++) {
        if (directory.path.endsWith('.app')) {
          return (
            executable: 'open',
            arguments: [
              '-a',
              directory.path,
              if (args.isNotEmpty) ...['--args', ...args],
            ],
            waitForExit: true
          );
        }
        final parent = directory.parent;
        if (parent.path == directory.path) break;
        directory = parent;
      }
    }
  }
  final names = operatingSystem == 'windows'
      ? ['playbridge_desktop.exe', 'PlayBridge.exe']
      : ['playbridge_desktop', 'PlayBridge'];
  for (final path in [
    if (appExecutable != null) appExecutable,
    for (final name in names)
      '${File(hostExecutable).parent.path}${Platform.pathSeparator}$name',
  ]) {
    if (File(path).existsSync()) {
      return (executable: path, arguments: args, waitForExit: false);
    }
  }
  return null;
}

Future<bool> launchDesktopApp({String? castFile, String? title}) async {
  try {
    String? appExecutable;
    try {
      final info = jsonDecode(await File(bridgeFilePath()).readAsString());
      if (info is Map &&
          info['appExecutable'] is String &&
          (info['appExecutable'] as String).isNotEmpty) {
        appExecutable = info['appExecutable'] as String;
      }
    } on Object {
      // An absent/stale bridge file still allows bundled/sibling launch layouts.
    }
    final command = desktopLaunchCommand(
        hostExecutable: Platform.resolvedExecutable,
        operatingSystem: Platform.operatingSystem,
        appExecutable: appExecutable,
        castFile: castFile,
        title: title);
    if (command == null) return false;
    final process = await Process.start(command.executable, command.arguments,
        mode: command.waitForExit
            ? ProcessStartMode.normal
            : ProcessStartMode.detached);
    return command.waitForExit ? await process.exitCode == 0 : true;
  } on Object {
    // Callers report a fixed message, never paths, media arguments or credentials.
    return false;
  }
}
