import 'dart:async';

import 'package:flutter/material.dart';

import '../tv_sender_controller.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';
import 'update_checker.dart';

/// The corner cards: the update flow driven by [UpdateChecker.state], the
/// "cast failed" notice and the "reconnecting" notice from [sender]. Drop it
/// once near the top of the root Stack (it returns a [Positioned]); it renders
/// nothing while there is nothing to say.
///
/// Every card is a small non-blocking [PbCornerCard] in the bottom-right
/// corner, so Home and playback stay usable behind it. No update is mandatory
/// today, so there is no blocking dialog.
///
/// - [UpdateAvailable]   -> "Update and restart" / "Later"
/// - [UpdateDownloading] -> progress card (no actions)
/// - [UpdateInstalling]  -> indeterminate card; the app exits mid-state
/// - manual [UpdateUpToDate] / [UpdateError] -> notice, up to date clears itself
/// - [TvSenderController.lastPlaybackError] -> cast failed card with Dismiss
/// - [TvSenderController.reconnectStatus] -> reconnecting card with Stop trying
///
/// The desktop notification for a failed cast while the window is hidden is
/// raised by the shell through `PlaybackErrorNotifier`, not here.
class UpdateGate extends StatefulWidget {
  const UpdateGate({super.key, required this.checker, this.sender});

  final UpdateChecker checker;

  /// When set, the cast failed and reconnecting cards are shown too.
  final TvSenderController? sender;

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
  void didUpdateWidget(UpdateGate oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.checker != widget.checker) {
      oldWidget.checker.removeListener(_onState);
      widget.checker.addListener(_onState);
    }
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
    // "You're up to date" is a confirmation, not a decision: clear it itself.
    if (s is UpdateUpToDate) {
      _autoDismiss = Timer(const Duration(seconds: 3), widget.checker.dismiss);
    }
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final sender = widget.sender;
    if (sender == null) return _positioned(_cards(null));
    return ListenableBuilder(
      listenable: sender,
      builder: (context, _) => _positioned(_cards(sender)),
    );
  }

  Widget _positioned(List<Widget> cards) {
    if (cards.isEmpty) return const SizedBox.shrink();
    return Positioned(
      right: 16,
      bottom: 16,
      child: Material(
        type: MaterialType.transparency,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var i = 0; i < cards.length; i++) ...[
                if (i > 0) const SizedBox(height: 12),
                cards[i],
              ],
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _cards(TvSenderController? sender) {
    final cards = <Widget>[];
    final error = sender?.lastPlaybackError;
    if (sender != null && error != null) cards.add(_castFailed(sender, error));
    final update = _updateCard();
    if (update != null) cards.add(update);
    final reconnecting = sender?.reconnectStatus;
    if (sender != null && reconnecting != null) {
      cards.add(_reconnecting(sender, reconnecting));
    }
    return cards;
  }

  Widget _castFailed(TvSenderController sender, String message) {
    final tv = sender.selectionName;
    return Semantics(
      label: 'Cast failed',
      container: true,
      child: PbCornerCard(
        key: const ValueKey('cast-failed-card'),
        title: tv.isEmpty ? "Couldn't play that" : "$tv couldn't play that",
        message: message,
        actions: [
          PbButton(
            label: 'Dismiss',
            variant: PbButtonVariant.secondary,
            onPressed: sender.clearPlaybackError,
          ),
        ],
      ),
    );
  }

  Widget _reconnecting(TvSenderController sender, ReceiverReconnectStatus s) =>
      Semantics(
        label: 'Reconnecting',
        container: true,
        child: Container(
          key: const ValueKey('reconnecting-card'),
          padding: const EdgeInsets.fromLTRB(18, 12, 12, 12),
          decoration: BoxDecoration(
            color: PbColors.card,
            borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
            border: Border.all(color: PbColors.outline),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      s.deviceName.isEmpty
                          ? 'Reconnecting'
                          : 'Reconnecting to ${s.deviceName}',
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, color: PbColors.text),
                    ),
                    Text('Try ${s.attempt} of ${s.maxAttempts}',
                        style: const TextStyle(
                            fontSize: 13, color: PbColors.textSecondary)),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              // Same as Disconnect: stops the retries and plays on this computer.
              PbButton(
                label: 'Stop trying',
                variant: PbButtonVariant.secondary,
                onPressed: () => unawaited(sender.disconnect()),
              ),
            ],
          ),
        ),
      );

  Widget? _updateCard() {
    final checker = widget.checker;
    return switch (checker.state) {
      UpdateAvailable(:final info) => Semantics(
          label: 'Update available',
          container: true,
          child: PbCornerCard(
            key: const ValueKey('update-card'),
            title: 'PlayBridge ${info.version} is available',
            message:
                'Updating downloads it, then restarts PlayBridge. Restarting stops anything playing here.',
            actions: [
              PbButton(
                label: 'Update and restart',
                onPressed: () => checker.accept(info),
              ),
              PbButton(
                label: 'Later',
                variant: PbButtonVariant.secondary,
                onPressed: checker.dismiss,
              ),
            ],
          ),
        ),
      UpdateDownloading(:final info, :final fraction) => _progressCard(
          'Downloading PlayBridge ${info.version}…',
          fraction: fraction,
        ),
      UpdateInstalling(:final info) => _progressCard(
          'Installing PlayBridge ${info.version}…',
          message: 'PlayBridge will restart.',
          fraction: null,
        ),
      UpdateError(manual: true, :final message) => PbCornerCard(
          key: const ValueKey('update-card'),
          title: "Couldn't update",
          message: message,
          actions: [
            PbButton(
              label: 'Download page',
              onPressed: () {
                checker.openDownloadPage();
                checker.dismiss();
              },
            ),
            PbButton(
              label: 'Dismiss',
              variant: PbButtonVariant.secondary,
              onPressed: checker.dismiss,
            ),
          ],
        ),
      UpdateUpToDate() => PbCornerCard(
          key: const ValueKey('update-card'),
          title: "You're on the latest version",
          actions: [
            PbButton(
              label: 'Dismiss',
              variant: PbButtonVariant.secondary,
              onPressed: checker.dismiss,
            ),
          ],
        ),
      _ => null,
    };
  }

  Widget _progressCard(String title,
          {String? message, required double? fraction}) =>
      PbCornerCard(
        key: const ValueKey('update-card'),
        title: title,
        message: message,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            LinearProgressIndicator(
                value: fraction?.clamp(0.0, 1.0).toDouble(), minHeight: 4),
            if (fraction != null) ...[
              const SizedBox(height: 8),
              Text('${(fraction.clamp(0.0, 1.0) * 100).toInt()}%',
                  style: const TextStyle(
                      color: PbColors.textSecondary, fontSize: 12)),
            ],
          ],
        ),
      );
}
