import 'package:flutter/gestures.dart' show kPrimaryButton;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'player/player_widgets.dart';
import 'ui/pb_theme.dart';

class _ContinueWatchingIntent extends Intent {
  const _ContinueWatchingIntent();
}

/// "Still watching?" card over the dimmed, paused video. Space, Esc, Enter or
/// a click keeps watching; [onStopNow] (when set) adds a "Stop now" button.
class StillWatchingPrompt extends StatefulWidget {
  const StillWatchingPrompt({
    super.key,
    required this.remainingSeconds,
    required this.onContinue,
    this.onStopNow,
    this.title,
  });

  final int remainingSeconds;
  final String? title;
  final VoidCallback onContinue;

  /// Stops playback right away. "Stop now" renders only when this is set.
  final VoidCallback? onStopNow;

  @override
  State<StillWatchingPrompt> createState() => _StillWatchingPromptState();
}

class _StillWatchingPromptState extends State<StillWatchingPrompt> {
  /// The countdown the prompt opened with; the bar drains from here to zero.
  late int _total = widget.remainingSeconds;

  @override
  void didUpdateWidget(StillWatchingPrompt oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.remainingSeconds > _total) _total = widget.remainingSeconds;
  }

  @override
  Widget build(BuildContext context) {
    final remaining = widget.remainingSeconds;
    final minutes = remaining ~/ 60;
    final seconds = remaining % 60;
    final progress = _total > 0 ? (remaining / _total).clamp(0.0, 1.0) : 0.0;
    final title = widget.title;
    final what = title != null && title.isNotEmpty ? title : 'Playback';
    return Shortcuts(
      shortcuts: const {
        SingleActivator(LogicalKeyboardKey.space): _ContinueWatchingIntent(),
        SingleActivator(LogicalKeyboardKey.escape): _ContinueWatchingIntent(),
        SingleActivator(LogicalKeyboardKey.arrowLeft):
            DirectionalFocusIntent(TraversalDirection.left),
        SingleActivator(LogicalKeyboardKey.arrowRight):
            DirectionalFocusIntent(TraversalDirection.right),
        SingleActivator(LogicalKeyboardKey.arrowUp):
            DirectionalFocusIntent(TraversalDirection.up),
        SingleActivator(LogicalKeyboardKey.arrowDown):
            DirectionalFocusIntent(TraversalDirection.down),
        SingleActivator(LogicalKeyboardKey.keyF): DoNothingIntent(),
        SingleActivator(LogicalKeyboardKey.keyI): DoNothingIntent(),
        SingleActivator(LogicalKeyboardKey.slash): DoNothingIntent(),
        SingleActivator(LogicalKeyboardKey.slash, shift: true):
            DoNothingIntent(),
      },
      child: Actions(
        actions: {
          _ContinueWatchingIntent: CallbackAction<_ContinueWatchingIntent>(
            onInvoke: (_) {
              widget.onContinue();
              return null;
            },
          ),
        },
        child: FocusScope(
          autofocus: true,
          child: ColoredBox(
            color: PbColors.playerBackground.withValues(alpha: 0.82),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 440),
                child: Semantics(
                  container: true,
                  label: 'Still watching',
                  child: Container(
                    padding: const EdgeInsets.all(32),
                    decoration: BoxDecoration(
                      color: PbColors.playerSurface,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text(
                          'Still watching?',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 24,
                            fontWeight: FontWeight.w700,
                            color: PlayerTones.foreground,
                          ),
                        ),
                        const SizedBox(height: 16),
                        Text(
                          '$what is paused. It stops in '
                          '$minutes:${seconds.toString().padLeft(2, '0')} '
                          'if nobody answers.',
                          textAlign: TextAlign.center,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 15,
                            color: PbColors.playerText,
                          ),
                        ),
                        const SizedBox(height: 16),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(2),
                          child: SizedBox(
                            height: 4,
                            child: LinearProgressIndicator(
                              value: progress,
                              color: PbColors.accent,
                              backgroundColor: PlayerTones.track,
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Wrap(
                          spacing: 12,
                          runSpacing: 12,
                          alignment: WrapAlignment.center,
                          children: [
                            PlayerPill(
                              label: 'Keep watching',
                              tone: PlayerButtonTone.primary,
                              large: true,
                              autofocus: true,
                              onPressed: widget.onContinue,
                            ),
                            if (widget.onStopNow != null)
                              // The shell treats any pointer-down while the
                              // prompt is open as "keep watching" (it listens
                              // above this card). Stop on pointer-down so the
                              // click is not first read as a Continue.
                              Listener(
                                onPointerDown: (event) {
                                  if (event.buttons == kPrimaryButton) {
                                    widget.onStopNow!();
                                  }
                                },
                                child: PlayerPill(
                                  label: 'Stop now',
                                  large: true,
                                  onPressed: widget.onStopNow,
                                ),
                              ),
                          ],
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          'A click, Space or Esc also keeps it playing.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 13,
                            color: PbColors.playerText,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
