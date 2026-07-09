import 'dart:async';

import 'package:flutter/material.dart';

import 'update_checker.dart';

/// Renders the update flow driven by [UpdateChecker.state]. Desktop analogue
/// of the Android apps' `UpdateGate` composable: drop it once near the top of
/// the root Stack; it renders nothing while idle.
///
/// Deliberately an inline overlay (modal barrier + card) rather than
/// `showDialog` — declarative rendering from the state machine avoids
/// imperative dialog bookkeeping across async state transitions.
///
/// - [UpdateAvailable]   → "Update available" card (Restart & update / Later)
/// - [UpdateDownloading] → progress card (non-dismissable)
/// - [UpdateInstalling]  → indeterminate card; the app exits mid-state
/// - manual [UpdateUpToDate] / [UpdateError] → auto-dismissing banner
class UpdateGate extends StatefulWidget {
  const UpdateGate({super.key, required this.checker});

  final UpdateChecker checker;

  @override
  State<UpdateGate> createState() => _UpdateGateState();
}

class _UpdateGateState extends State<UpdateGate> {
  Timer? _autoDismiss;

  @override
  void initState() {
    super.initState();
    widget.checker.addListener(_onState);
  }

  @override
  void dispose() {
    widget.checker.removeListener(_onState);
    _autoDismiss?.cancel();
    super.dispose();
  }

  void _onState() {
    _autoDismiss?.cancel();
    final s = widget.checker.state;
    // "You're up to date" is a confirmation, not a decision — clear it itself.
    if (s is UpdateUpToDate) {
      _autoDismiss = Timer(const Duration(seconds: 3), widget.checker.dismiss);
    }
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return switch (widget.checker.state) {
      // Quiet non-blocking strip — background checks shouldn't interrupt video.
      UpdateAvailable(:final info) => _availableBanner(info),
      UpdateDownloading(:final info, :final fraction) => _barrier(_progressCard(
          'Downloading v${info.version}…',
          fraction: fraction,
        )),
      UpdateInstalling(:final info) => _barrier(_progressCard(
          'Installing v${info.version}… the app will restart.',
          fraction: null,
        )),
      UpdateError(manual: true, :final message) =>
        _banner(message, error: true),
      UpdateUpToDate() => _banner("You're on the latest version."),
      _ => const SizedBox.shrink(),
    };
  }

  /// Persistent bottom strip with one-tap restart (not a modal barrier).
  Widget _availableBanner(UpdateInfo info) => Positioned(
        left: 0,
        right: 0,
        bottom: 48,
        child: Center(
          child: Material(
            color: Colors.transparent,
            child: Container(
              constraints: const BoxConstraints(maxWidth: 560),
              margin: const EdgeInsets.symmetric(horizontal: 16),
              padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
              decoration: BoxDecoration(
                color: const Color(0xFF1E1E24),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: Colors.tealAccent.withValues(alpha: 0.35),
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.4),
                    blurRadius: 16,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: Row(
                children: [
                  const Icon(Icons.system_update_alt,
                      size: 20, color: Colors.tealAccent),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'v${info.version} is ready',
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  TextButton(
                    onPressed: widget.checker.dismiss,
                    child: const Text('Later'),
                  ),
                  const SizedBox(width: 4),
                  FilledButton(
                    onPressed: () => widget.checker.accept(info),
                    child: const Text('Restart & update'),
                  ),
                  IconButton(
                    tooltip: 'Dismiss',
                    icon: const Icon(Icons.close, size: 16),
                    onPressed: widget.checker.dismiss,
                  ),
                ],
              ),
            ),
          ),
        ),
      );

  Widget _barrier(Widget child) => Positioned.fill(
        child: Container(
          color: Colors.black54,
          alignment: Alignment.center,
          child: child,
        ),
      );

  Widget _card({required List<Widget> children}) => Container(
        width: 420,
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: const Color(0xFF1E1E24),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: children,
        ),
      );

  Widget _progressCard(String title, {required double? fraction}) =>
      _card(children: [
        Text(title,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
        const SizedBox(height: 16),
        LinearProgressIndicator(
            value: fraction?.clamp(0.0, 1.0).toDouble(), minHeight: 4),
        if (fraction != null) ...[
          const SizedBox(height: 8),
          Text('${(fraction.clamp(0.0, 1.0) * 100).toInt()}%',
              style: const TextStyle(color: Colors.white54, fontSize: 12)),
        ],
      ]);

  Widget _banner(String message, {bool error = false}) => Positioned(
        left: 0,
        right: 0,
        bottom: 48,
        child: Center(
          child: Container(
            constraints: const BoxConstraints(maxWidth: 520),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            decoration: BoxDecoration(
              color: const Color(0xFF1E1E24),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: error
                    ? Colors.redAccent.withValues(alpha: 0.4)
                    : Colors.white.withValues(alpha: 0.08),
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(message,
                      style: const TextStyle(fontSize: 13),
                      overflow: TextOverflow.ellipsis,
                      maxLines: 4),
                ),
                if (error) ...[
                  const SizedBox(width: 12),
                  TextButton(
                    onPressed: () {
                      widget.checker.openDownloadPage();
                      widget.checker.dismiss();
                    },
                    child: const Text('Download page'),
                  ),
                ],
                const SizedBox(width: 4),
                IconButton(
                  icon: const Icon(Icons.close, size: 16),
                  onPressed: widget.checker.dismiss,
                ),
              ],
            ),
          ),
        ),
      );
}
