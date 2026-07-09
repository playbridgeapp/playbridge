import 'dart:io' show Platform;

import 'package:flutter/material.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'player_controller.dart';
import 'engines/mpv_engine.dart';

const _kSubtitleBottomDefault = 24.0;
const _kSubtitleBottomWithControls = 138.0; // clears ~114px controls bar

class PlaybackSurface extends StatefulWidget {
  const PlaybackSurface(
      {super.key, required this.controller, this.controlsVisible = false});

  final PlayerController controller;
  final bool controlsVisible;

  @override
  State<PlaybackSurface> createState() => _PlaybackSurfaceState();
}

class _PlaybackSurfaceState extends State<PlaybackSurface> {
  VideoController? _mpvVideo;
  int _boundGeneration = -1;

  @override
  void initState() {
    super.initState();
    _syncVideoController();
    widget.controller.addListener(_onControllerChange);
  }

  void _onControllerChange() {
    _syncVideoController();
  }

  /// Keep a live [VideoController], recreating it after each stop so a GPU
  /// texture frozen while the window was unfocused cannot flash video A when
  /// video B starts.
  void _syncVideoController() {
    final engine = widget.controller.engine;
    if (engine is! MpvEngine) return;
    final gen = widget.controller.surfaceGeneration;
    if (_mpvVideo != null && gen == _boundGeneration) return;
    _boundGeneration = gen;
    _mpvVideo = VideoController(
      engine.player,
      configuration: VideoControllerConfiguration(
        enableHardwareAcceleration: !Platform.isLinux,
      ),
    );
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChange);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final hasMedia = widget.controller.queue.isNotEmpty;
    final mask = !hasMedia || widget.controller.isOpening;

    // Keep [Video] mounted under a black mask so stop/clear can update the VO
    // while idle; only the mask hides stale pixels (unmounting alone left the
    // old texture intact when the window was unfocused).
    return Stack(
      fit: StackFit.expand,
      children: [
        if (_mpvVideo != null)
          TweenAnimationBuilder<double>(
            tween: Tween(
              end: widget.controlsVisible
                  ? _kSubtitleBottomWithControls
                  : _kSubtitleBottomDefault,
            ),
            duration: const Duration(milliseconds: 150),
            builder: (context, bottomPad, _) => Video(
              controller: _mpvVideo!,
              controls: NoVideoControls,
              subtitleViewConfiguration: SubtitleViewConfiguration(
                padding: EdgeInsets.fromLTRB(16, 0, 16, bottomPad),
              ),
            ),
          ),
        if (mask) const ColoredBox(color: Colors.black),
      ],
    );
  }
}
