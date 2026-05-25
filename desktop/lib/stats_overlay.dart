import 'package:flutter/material.dart';

import 'engines/mpv_engine.dart';
import 'player_engine.dart';

/// Translucent overlay showing live MPV playback stats (dropped frames, fps,
/// bitrate, etc.). While mounted it turns on stats collection in the engine and
/// turns it back off on dispose, so there's no sampling cost when hidden.
class StatsOverlay extends StatefulWidget {
  const StatsOverlay({super.key, required this.engine});

  final MpvEngine engine;

  @override
  State<StatsOverlay> createState() => _StatsOverlayState();
}

class _StatsOverlayState extends State<StatsOverlay> {
  @override
  void initState() {
    super.initState();
    widget.engine.setStatsCollecting(true);
  }

  @override
  void didUpdateWidget(StatsOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.engine != widget.engine) {
      oldWidget.engine.setStatsCollecting(false);
      widget.engine.setStatsCollecting(true);
    }
  }

  @override
  void dispose() {
    widget.engine.setStatsCollecting(false);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<PlaybackStats?>(
      valueListenable: widget.engine.stats,
      builder: (context, s, _) {
        if (s == null) return const SizedBox.shrink();
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.62),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const _Heading('Playback stats'),
              const SizedBox(height: 6),
              _Row(
                'Dropped',
                '${s.droppedVo} vo  /  ${s.droppedDecoder} dec',
                warn: s.droppedVo > 0 || s.droppedDecoder > 0,
              ),
              _Row('FPS', _fps(s)),
              _Row('Bitrate', _bitrate(s)),
              _Row('Decoder', s.hwdec == null || s.hwdec == 'no'
                  ? 'software'
                  : 'hw (${s.hwdec})'),
              _Row('A/V sync', s.avsync == null
                  ? '—'
                  : '${(s.avsync! * 1000).toStringAsFixed(0)} ms',
                  warn: s.avsync != null && s.avsync!.abs() > 0.1),
              _Row('Video', _video(s)),
              _Row('Cache', s.cacheDuration == null
                  ? '—'
                  : '${s.cacheDuration!.toStringAsFixed(1)} s'),
            ],
          ),
        );
      },
    );
  }

  String _fps(PlaybackStats s) {
    final out = s.fps?.toStringAsFixed(2) ?? '—';
    final src = s.containerFps?.toStringAsFixed(2);
    return src == null ? out : '$out / $src src';
  }

  String _bitrate(PlaybackStats s) {
    String mbps(double? b) => b == null ? '—' : (b / 1e6).toStringAsFixed(1);
    return '${mbps(s.videoBitrate)} v  /  ${mbps(s.audioBitrate)} a Mbps';
  }

  String _video(PlaybackStats s) {
    final res = (s.width != null && s.height != null) ? '${s.width}×${s.height}' : '—';
    return s.videoCodec == null ? res : '$res  ${s.videoCodec}';
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Text(
        text.toUpperCase(),
        style: const TextStyle(
          color: Colors.tealAccent,
          fontSize: 10,
          letterSpacing: 1.2,
          fontWeight: FontWeight.w700,
        ),
      );
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {this.warn = false});
  final String label;
  final String value;
  final bool warn;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 1.5),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 64,
            child: Text(
              label,
              style: const TextStyle(color: Colors.white54, fontSize: 11.5),
            ),
          ),
          Text(
            value,
            style: TextStyle(
              color: warn ? Colors.amberAccent : Colors.white,
              fontSize: 11.5,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}
