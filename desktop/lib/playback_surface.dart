import 'package:flutter/material.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'player_engine.dart';
import 'player_controller.dart';
import 'engines/mpv_engine.dart';

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
    if (widget.controller.engineType == EngineType.mpvInternal && _mpvVideo == null) {
      _initMpv();
    } else if (widget.controller.engineType != EngineType.mpvInternal && _mpvVideo != null) {
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
    if (widget.controller.engineType != EngineType.mpvInternal) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.rocket_launch, size: 64, color: Colors.tealAccent),
            const SizedBox(height: 24),
            Text(
              'Playing in ${widget.controller.engineType == EngineType.mpvExternal ? 'MPV' : 'VLC'} (External)',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            const Text(
              'Use the external player window for playback.',
              style: TextStyle(color: Colors.white60),
            ),
          ],
        ),
      );
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
