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
  MpvEngine? _boundEngine;
  late bool _mask;

  bool get _shouldMask =>
      widget.controller.queue.isEmpty || widget.controller.isOpening;

  @override
  void initState() {
    super.initState();
    _mask = _shouldMask;
    _initMpv();
    widget.controller.addListener(_onControllerChange);
  }

  void _onControllerChange() {
    _initMpv();
    // Position updates arrive about five times per second. Rebuilding the Video
    // texture for those ticks disrupts frame presentation on Linux, so rebuild
    // only when the stale-frame mask actually needs to change.
    final nextMask = _shouldMask;
    if (mounted && nextMask != _mask) {
      setState(() => _mask = nextMask);
    }
  }

  void _initMpv() {
    final engine = widget.controller.engine;
    if (engine is MpvEngine && !identical(engine, _boundEngine)) {
      _boundEngine = engine;
      _mpvVideo = VideoController(
        engine.player,
        configuration: VideoControllerConfiguration(
          enableHardwareAcceleration: widget.controller.hardwareVideoOutput,
        ),
      );
      if (mounted) setState(() {});
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChange);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Cover the VO while idle or while the next item is still opening so a
    // frozen last-frame of the previous title cannot flash (esp. after unfocus).
    if (_mpvVideo == null) {
      return const ColoredBox(color: Colors.black);
    }

    return Stack(
      fit: StackFit.expand,
      children: [
        // Keep Video mounted so the texture stays bound; mask hides stale pixels.
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
        if (_mask) const ColoredBox(color: Colors.black),
      ],
    );
  }
}
