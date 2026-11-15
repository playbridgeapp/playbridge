import 'package:flutter/material.dart';

import '../context_menu_installer.dart';
import '../native_host_installer.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// Outcome of one "Set up browser casting" run.
class ExtensionSetupResult {
  const ExtensionSetupResult({required this.host, required this.menu});

  /// Native-messaging host registration, browser name to status.
  final Map<String, String> host;

  /// "Play on TV" right-click menu installation, target to status.
  final Map<String, String> menu;

  /// Browsers whose host registration reported `ready`.
  List<String> get readyBrowsers => [
        for (final e in host.entries)
          if (e.value == 'ready') e.key,
      ];

  bool get hasProblem =>
      host.values
          .any((v) => v.startsWith('error') || v.startsWith('not found')) ||
      menu.values
          .any((v) => v.startsWith('error') || v.startsWith('not found'));
}

/// Runs the same two installs the old "Set up browser casting" button ran.
Future<ExtensionSetupResult> runExtensionSetup() async {
  final host = await NativeHostInstaller.install();
  final menu = await ContextMenuInstaller.install();
  return ExtensionSetupResult(host: host, menu: menu);
}

/// One-line setup status for the Browser extension card.
///
/// Setup also runs silently when the app starts, so before the user has run it
/// by hand there is no result to report; that is said plainly rather than
/// guessed.
String describeExtensionStatus(ExtensionSetupResult? last,
    {bool busy = false}) {
  if (busy) return 'Setting up…';
  if (last == null) {
    return 'Set up automatically when PlayBridge starts · set up again';
  }
  final ready = last.readyBrowsers;
  if (ready.isEmpty) {
    return last.hasProblem
        ? 'Setup needs attention · try again'
        : 'No supported browser found · set up again';
  }
  final names = ready.length <= 3
      ? ready.join(', ')
      : '${ready.take(3).join(', ')} and ${ready.length - 3} more';
  return 'Set up for $names${last.hasProblem ? ' (with problems)' : ''} · set up again';
}

/// Dialog listing what the setup run did, as before: host registration per
/// browser, the right-click menu, and the reload hint.
Future<void> showExtensionSetupDialog(
  BuildContext context,
  ExtensionSetupResult result,
) {
  Widget lines(Map<String, String> entries) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final e in entries.entries)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                '${e.key}: ${e.value}',
                style: TextStyle(
                  fontSize: 13,
                  color: e.value == 'ready'
                      ? PbColors.success
                      : (e.value.startsWith('error') ||
                              e.value.startsWith('not found'))
                          ? PbColors.error
                          : PbColors.textSecondary,
                ),
              ),
            ),
        ],
      );
  return showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Browser casting setup'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Native-messaging host registered for:'),
            const SizedBox(height: 8),
            lines(result.host),
            const SizedBox(height: 12),
            const Text('"Play on TV" right-click menu:'),
            const SizedBox(height: 8),
            lines(result.menu),
            const SizedBox(height: 12),
            const Text(
              'Reload the extension in your browser; it should connect within a '
              'few seconds.',
              style: TextStyle(fontSize: 12, color: PbColors.textSecondary),
            ),
          ],
        ),
      ),
      actions: [
        PbButton(label: 'OK', onPressed: () => Navigator.of(ctx).pop()),
      ],
    ),
  );
}
