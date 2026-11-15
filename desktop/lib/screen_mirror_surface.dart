import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import 'screen_mirror_receiver.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// The screen-mirror view: the sender's screen as large as fits, with a top bar
/// (status, full screen, stop) that hides after [hideAfter] and returns when the
/// pointer moves.
///
/// Playback behaviour lives in [ScreenMirrorReceiver]; this widget only draws it.
/// The buttons appear only when their callback is given.
class ScreenMirrorSurface extends StatefulWidget {
  const ScreenMirrorSurface({
    super.key,
    required this.receiver,
    this.deviceName,
    this.onToggleFullScreen,
    this.onStop,
    this.hideAfter = const Duration(seconds: 3),
  });

  final ScreenMirrorReceiver receiver;

  /// Name of the device being shown, when known.
  final String? deviceName;

  /// Enter or leave full screen. The "Full screen" button is left out when null.
  final VoidCallback? onToggleFullScreen;

  /// Stop showing the sender's screen. The "Stop showing" button is left out
  /// when null.
  final VoidCallback? onStop;

  final Duration hideAfter;

  @override
  State<ScreenMirrorSurface> createState() => _ScreenMirrorSurfaceState();
}

class _ScreenMirrorSurfaceState extends State<ScreenMirrorSurface> {
  Timer? _hideTimer;
  bool _barVisible = true;

  @override
  void initState() {
    super.initState();
    _wake();
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    super.dispose();
  }

  void _wake() {
    _hideTimer?.cancel();
    _hideTimer = Timer(widget.hideAfter, () {
      if (mounted) setState(() => _barVisible = false);
    });
    if (!_barVisible) setState(() => _barVisible = true);
  }

  @override
  Widget build(BuildContext context) {
    final receiver = widget.receiver;
    return ListenableBuilder(
      listenable: receiver,
      builder: (context, _) {
        final renderer = receiver.renderer;
        final name = widget.deviceName;
        final failed = receiver.error != null;
        return MouseRegion(
          onHover: (_) => _wake(),
          child: Focus(
            canRequestFocus: false,
            onFocusChange: (focused) {
              if (focused) _wake();
            },
            child: ColoredBox(
              color: PbColors.playerBackground,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  if (renderer != null)
                    RTCVideoView(
                      renderer,
                      objectFit:
                          RTCVideoViewObjectFit.RTCVideoViewObjectFitContain,
                      filterQuality: FilterQuality.medium,
                    ),
                  if (renderer == null || !receiver.isConnected)
                    Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (!failed)
                            const CircularProgressIndicator(strokeWidth: 2)
                          else
                            const Icon(Icons.error_outline,
                                size: 36, color: PbColors.playerText),
                          const SizedBox(height: 16),
                          Text(
                            failed
                                ? 'Screen mirror ended'
                                : (name == null || name.isEmpty
                                    ? 'Connecting screen mirror…'
                                    : 'Connecting to $name…'),
                            style: const TextStyle(
                                fontSize: 16, color: PbColors.playerText),
                          ),
                        ],
                      ),
                    ),
                  Positioned(
                    top: 24,
                    left: 24,
                    right: 24,
                    child: IgnorePointer(
                      ignoring: !_barVisible,
                      child: ExcludeFocus(
                        excluding: !_barVisible,
                        child: AnimatedOpacity(
                          opacity: _barVisible ? 1 : 0,
                          duration: const Duration(milliseconds: 200),
                          child: _TopBar(
                            status: failed
                                ? 'Screen mirror ended'
                                : receiver.isConnected
                                    ? (name == null || name.isEmpty
                                        ? "Showing the sender's screen"
                                        : "Showing $name's screen")
                                    : 'Connecting…',
                            live: receiver.isConnected && !failed,
                            onToggleFullScreen: widget.onToggleFullScreen,
                            onStop: widget.onStop,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.status,
    required this.live,
    this.onToggleFullScreen,
    this.onStop,
  });

  final String status;
  final bool live;
  final VoidCallback? onToggleFullScreen;
  final VoidCallback? onStop;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Flexible(
            child: Container(
              height: PbMetrics.controlHeight,
              padding: const EdgeInsets.symmetric(horizontal: 18),
              decoration: BoxDecoration(
                color: PbColors.playerSurface,
                borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: live ? PbColors.success : PbColors.warning,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Flexible(
                    child: Text(
                      status,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontWeight: FontWeight.w500, color: Colors.white),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const Spacer(),
          if (onToggleFullScreen != null)
            _PlayerButton(label: 'Full screen', onPressed: onToggleFullScreen),
          if (onStop != null) ...[
            const SizedBox(width: 8),
            _PlayerButton(label: 'Stop showing', onPressed: onStop),
          ],
        ],
      );
}

/// Pill button on the neutral player palette, so it never tints the video.
class _PlayerButton extends StatelessWidget {
  const _PlayerButton({required this.label, required this.onPressed});

  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => PbPressable(
        onTap: onPressed,
        semanticLabel: label,
        builder: (context, hovered, focused) => Container(
          height: PbMetrics.controlHeight,
          padding: const EdgeInsets.symmetric(horizontal: 18),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: hovered
                ? Color.lerp(PbColors.playerRaised, Colors.white, 0.08)
                : PbColors.playerRaised,
            borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
            border:
                focused ? Border.all(color: PbColors.accent, width: 2) : null,
          ),
          child: Text(label,
              style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: Colors.white)),
        ),
      );
}
