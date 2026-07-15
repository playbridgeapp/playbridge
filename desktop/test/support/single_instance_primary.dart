import 'dart:convert';
import 'dart:io';

import 'package:playbridge_desktop/single_instance_coordinator.dart';

Future<void> main(List<String> args) async {
  final result = await SingleInstanceCoordinator.coordinate(
    request: const InstanceLaunchRequest(),
    directoryPath: args.single,
  );
  final coordinator = result.coordinator;
  if (coordinator == null) {
    stderr.writeln('helper did not become primary');
    exitCode = 1;
    return;
  }

  stdout.writeln('READY:${coordinator.metadataFilePath}');
  await stdout.flush();

  await for (final command
      in stdin.transform(utf8.decoder).transform(const LineSplitter())) {
    if (command == 'HANDLE') {
      coordinator.setLaunchHandler((request) async {
        stdout.writeln('REQUEST:${jsonEncode({
              'castFile': request.castFile,
              'castTitle': request.castTitle,
            })}');
        await stdout.flush();
      });
    } else if (command == 'STOP') {
      await coordinator.close();
      return;
    }
  }
}
