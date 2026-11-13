import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../player_chrome_policy.dart';
import '../player_controller.dart';
import '../skip_segment.dart';
import 'player_controls_bar.dart';
import 'player_panels.dart';
import 'player_widgets.dart';
import 'skip_segment_button.dart';
import 'title_overlay.dart';

/// Everything the local player draws over the video, in one widget: title and
/// back button, the controls bar, the skip button and the right-hand side
/// panels (Subtitles and audio, Queue, More).
///
/// Place it with `Positioned.fill` above the video. Empty areas let clicks
/// through to the video. While a panel is open, clicking anywhere else on the
/// video, or pressing Esc, closes it; other keys are left to the app's
/// shortcuts.
class PlayerChrome extends StatefulWidget {
  const PlayerChrome({
    super.key,
    required this.player,
    required this.mode,
    required this.showQueueControls,
    required this.onPanelOpened,
    required this.onPanelClosed,
    required this.onScrubbing,
    required this.isFullScreen,
    required this.onToggleFullScreen,
    required this.onToggleProxy,
    required this.segments,
    required this.lastSkipped,
    required this.autoPrefs,
    required this.onSkip,
    this.showStats,
    this.prePlayActive = false,
    this.onBack,
    this.onBookmark,
    this.bookmarked = false,
    this.onMoveToTv,
  });

  final PlayerController player;
  final PlayerChromeMode mode;
  final bool showQueueControls;

  /// A panel opened: keep the chrome on screen. Called once per open.
  final VoidCallback onPanelOpened;

  /// The last panel closed: the chrome may hide again.
  final VoidCallback onPanelClosed;
  final ValueChanged<bool> onScrubbing;
  final bool isFullScreen;
  final VoidCallback onToggleFullScreen;
  final Future<void> Function() onToggleProxy;

  final List<SkipSegment> segments;
  final SkipSegment? lastSkipped;
  final SkipAutoPrefs autoPrefs;
  final ValueChanged<SkipSegment> onSkip;

  /// The playback-stats switch shown in More; hidden when null.
  final ValueNotifier<bool>? showStats;

  /// The pre-play screen is up; the skip button stays hidden under it.
  final bool prePlayActive;

  /// Back button beside the title. Renders nothing when null.
  final VoidCallback? onBack;

  /// Bookmark button. Renders nothing when null.
  final VoidCallback? onBookmark;
  final bool bookmarked;

  /// "Move to a TV" button. Renders nothing when null.
  final VoidCallback? onMoveToTv;

  @override
  State<PlayerChrome> createState() => _PlayerChromeState();
}

class _PlayerChromeState extends State<PlayerChrome> {
  PlayerPanel? _panel;

  void _setPanel(PlayerPanel? next) {
    final was = _panel;
    if (was == next) return;
    setState(() => _panel = next);
    if (was == null) {
      HardwareKeyboard.instance.addHandler(_onKey);
      widget.onPanelOpened();
    } else if (next == null) {
      HardwareKeyboard.instance.removeHandler(_onKey);
      widget.onPanelClosed();
    }
  }

  void _toggle(PlayerPanel panel) => _setPanel(_panel == panel ? null : panel);

  bool _onKey(KeyEvent event) {
    if (_panel == null ||
        event is! KeyDownEvent ||
        event.logicalKey != LogicalKeyboardKey.escape) {
      return false;
    }
    // A dialog on top (for example the shortcuts sheet) takes Esc itself.
    if (ModalRoute.of(context)?.isCurrent == false) return false;
    _setPanel(null);
    return true;
  }

  @override
  void dispose() {
    if (_panel != null) {
      HardwareKeyboard.instance.removeHandler(_onKey);
      // The shell is rebuilt by this callback; do not do it while unmounting.
      scheduleMicrotask(widget.onPanelClosed);
    }
    super.dispose();
  }

  @override
  void didUpdateWidget(PlayerChrome oldWidget) {
    super.didUpdateWidget(oldWidget);
    // The queue shrank to one item while the Queue panel was open.
    if (_panel == PlayerPanel.queue && !widget.showQueueControls) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _setPanel(null);
      });
    }
  }

  Widget _panelWidget(PlayerPanel panel) => switch (panel) {
        PlayerPanel.subtitles => SubtitlesPanel(
            player: widget.player,
            onClose: () => _setPanel(null),
          ),
        PlayerPanel.queue => QueuePanel(
            player: widget.player,
            onClose: () => _setPanel(null),
          ),
        PlayerPanel.more => MorePanel(
            player: widget.player,
            onClose: () => _setPanel(null),
            onToggleProxy: widget.onToggleProxy,
            showStats: widget.showStats,
          ),
      };

  @override
  Widget build(BuildContext context) {
    final panel = _panel;
    final skipBottom = switch (widget.mode) {
      PlayerChromeMode.full => kPlayerFullBarHeight + 16,
      PlayerChromeMode.mini => 80.0,
      PlayerChromeMode.hidden => 28.0,
    };
    return Stack(
      fit: StackFit.expand,
      children: [
        Positioned(
          left: 0,
          right: 0,
          top: 0,
          child: TitleOverlay(
            player: widget.player,
            visible: widget.mode == PlayerChromeMode.full,
            onBack: widget.onBack,
          ),
        ),
        if (panel != null)
          // Clicking the video closes the panel instead of pausing it.
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => _setPanel(null),
            ),
          ),
        if (!widget.prePlayActive)
          Positioned(
            right: 24 + (panel == null ? 0 : kPlayerPanelWidth),
            bottom: skipBottom,
            child: SkipSegmentButton(
              player: widget.player,
              segments: widget.segments,
              lastSkipped: widget.lastSkipped,
              autoPrefs: widget.autoPrefs,
              onSkip: widget.onSkip,
            ),
          ),
        if (panel != null)
          Positioned(
            right: 0,
            top: 0,
            bottom: kPlayerFullBarHeight,
            width: kPlayerPanelWidth,
            child: _panelWidget(panel),
          ),
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          child: PlayerControlsBar(
            player: widget.player,
            mode: widget.mode,
            showQueueControls: widget.showQueueControls,
            openPanel: panel,
            onTogglePanel: _toggle,
            onScrubbing: widget.onScrubbing,
            isFullScreen: widget.isFullScreen,
            onToggleFullScreen: widget.onToggleFullScreen,
            onBookmark: widget.onBookmark,
            bookmarked: widget.bookmarked,
            onMoveToTv: widget.onMoveToTv,
          ),
        ),
      ],
    );
  }
}
