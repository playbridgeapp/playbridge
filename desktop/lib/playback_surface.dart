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

  @override
  void initState() {
    super.initState();
    _initMpv();
    widget.controller.addListener(_onControllerChange);
  }

  void _onControllerChange() {
    if (_mpvVideo == null) {
      _initMpv();
    }
  }

  void _initMpv() {
    final engine = widget.controller.engine;
    if (engine is MpvEngine) {
      _mpvVideo = VideoController(
        engine.player,
        configuration: VideoControllerConfiguration(
          enableHardwareAcceleration: !Platform.isLinux,
        ),
      );
      setState(() {});
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChange);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_mpvVideo != null) {
      return TweenAnimationBuilder<double>(
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
      );
    }

    return const ColoredBox(color: Colors.black);
  }
}
