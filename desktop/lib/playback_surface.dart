import 'package:flutter/material.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:video_player/video_player.dart' as vp;
import 'player_engine.dart';
import 'player_controller.dart';
import 'engines/mpv_engine.dart';
import 'engines/fvp_engine.dart';

class PlaybackSurface extends StatefulWidget {
  const PlaybackSurface({super.key, required this.controller});

  final PlayerController controller;

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
    if (widget.controller.engineType == EngineType.mpv && _mpvVideo == null) {
      _initMpv();
    } else if (widget.controller.engineType == EngineType.fvp && _mpvVideo != null) {
      _mpvVideo = null;
      setState(() {});
    }
  }

  void _initMpv() {
    final engine = widget.controller.engine;
    if (engine is MpvEngine) {
      _mpvVideo = VideoController(engine.player);
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
    final engine = widget.controller.engine;

    if (widget.controller.engineType == EngineType.fvp && engine is FvpEngine) {
      final vpc = engine.controller;
      if (vpc != null && vpc.value.isInitialized) {
        return Center(
          child: AspectRatio(
            aspectRatio: vpc.value.aspectRatio,
            child: vp.VideoPlayer(vpc),
          ),
        );
      }
      return const ColoredBox(color: Colors.black);
    }

    if (_mpvVideo != null) {
      return Video(
        controller: _mpvVideo!,
        controls: NoVideoControls,
      );
    }

    return const ColoredBox(color: Colors.black);
  }
}
