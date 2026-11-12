import 'dart:async';

import 'package:flutter/material.dart';

import 'player/player_widgets.dart';
import 'player_engine.dart';
import 'ui/pb_theme.dart';

/// Pre-play screen shown over the (muted, buffering) video when a cast with
/// visual metadata arrives: desktop counterpart of the tvOS PrePlayView and
/// Android PrePlayActivity. Darkened backdrop, title or logo, year / rating /
/// runtime, overview, and the way to start.
///
/// The video is not held back: it keeps loading and playing muted underneath
/// and [onStart] only lifts this screen and restores the volume. When the cast
/// carries a saved place ([QueueItem.startPositionMs]) the screen offers
/// "Resume from 1:12" and "Start over" ([onStartOver]); otherwise one "Play"
/// button. Either way it starts by itself when the countdown ends, and a click
/// anywhere on the screen starts too.
class PrePlayOverlay extends StatefulWidget {
  const PrePlayOverlay({
    super.key,
    required this.item,
    required this.onStart,
    this.onStartOver,
    this.countdownSeconds = 7,
  });

  final QueueItem item;

  /// Lifts the screen and keeps playing where the video already is (the saved
  /// place, when there is one).
  final VoidCallback onStart;

  /// Starts from the beginning. "Start over" renders only when this is set and
  /// the item has a saved place.
  final VoidCallback? onStartOver;
  final int countdownSeconds;

  @override
  State<PrePlayOverlay> createState() => _PrePlayOverlayState();
}

class _PrePlayOverlayState extends State<PrePlayOverlay> {
  late int _remaining;
  Timer? _timer;
  bool _started = false;

  /// Saved place, read once: the player clears it from the item after seeking.
  int? _resumeMs;

  @override
  void initState() {
    super.initState();
    final saved = widget.item.startPositionMs;
    _resumeMs = saved != null && saved > 0 ? saved : null;
    _remaining = widget.countdownSeconds;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() => _remaining--);
      if (_remaining <= 0) _start();
    });
  }

  void _finish(VoidCallback action) {
    if (_started) return;
    _started = true;
    _timer?.cancel();
    action();
  }

  void _start() => _finish(widget.onStart);

  void _startOver() => _finish(widget.onStartOver ?? widget.onStart);

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  static String _clock(int ms) {
    final total = ms ~/ 1000;
    final h = total ~/ 3600;
    final m = (total % 3600) ~/ 60;
    final s = total % 60;
    final ss = s.toString().padLeft(2, '0');
    return h > 0 ? '$h:${m.toString().padLeft(2, '0')}:$ss' : '$m:$ss';
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final backdrop = item.backdropUrl;

    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _start,
      child: Stack(
        fit: StackFit.expand,
        children: [
          // Base: covers the buffering video.
          const ColoredBox(color: PbColors.playerBackground),
          if (backdrop != null)
            Image.network(
              backdrop,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => const SizedBox.shrink(),
            ),
          // Darken for legibility: left fade plus a bottom vignette.
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.centerLeft,
                end: Alignment.centerRight,
                stops: const [0.0, 0.45, 1.0],
                colors: [
                  PbColors.playerBackground.withValues(alpha: 0.95),
                  PbColors.playerBackground.withValues(alpha: 0.75),
                  PbColors.playerBackground.withValues(alpha: 0.45),
                ],
              ),
            ),
          ),
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.bottomCenter,
                end: Alignment.topCenter,
                stops: const [0.0, 0.5],
                colors: [
                  PbColors.playerBackground.withValues(alpha: 0.7),
                  PbColors.playerBackground.withValues(alpha: 0),
                ],
              ),
            ),
          ),
          LayoutBuilder(
            builder: (context, constraints) {
              // The poster is only shown when it is the only artwork.
              final showPoster = backdrop == null &&
                  item.posterUrl != null &&
                  constraints.maxWidth >= 900;
              final pad = constraints.maxWidth >= 900 ? 56.0 : 32.0;
              return Padding(
                padding: EdgeInsets.all(pad),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Expanded(child: _contentColumn(item)),
                    if (showPoster) ...[
                      const SizedBox(width: 48),
                      _poster(item.posterUrl!),
                    ],
                  ],
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _contentColumn(QueueItem item) {
    final facts = <String>[
      if (item.year != null) item.year!,
      if (item.rating != null) 'IMDb ${item.rating}',
      if (item.runtime != null) item.runtime!,
    ];
    final hasEpisode = item.season != null && item.episode != null;
    final resume = _resumeMs;

    return Align(
      alignment: Alignment.bottomLeft,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            if (item.logoUrl != null)
              ConstrainedBox(
                constraints:
                    const BoxConstraints(maxWidth: 420, maxHeight: 110),
                child: Image.network(
                  item.logoUrl!,
                  fit: BoxFit.contain,
                  alignment: Alignment.centerLeft,
                  errorBuilder: (_, __, ___) => _titleText(item.title),
                ),
              )
            else
              _titleText(item.title),
            if (facts.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text(
                facts.join(' · '),
                style: const TextStyle(
                  fontSize: 15,
                  color: PbColors.playerText,
                ),
              ),
            ],
            if (hasEpisode) ...[
              const SizedBox(height: 12),
              Text(
                'S${item.season} · E${item.episode}'
                '${item.episodeTitle != null ? '  ${item.episodeTitle}' : ''}',
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: PlayerTones.foreground,
                ),
              ),
            ],
            if (item.overview != null) ...[
              const SizedBox(height: 16),
              Text(
                item.overview!,
                maxLines: 5,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 15,
                  height: 1.6,
                  color: Color(0xFFE4E2EE),
                ),
              ),
            ],
            const SizedBox(height: 24),
            Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                PlayerPill(
                  label:
                      resume == null ? 'Play' : 'Resume from ${_clock(resume)}',
                  tone: PlayerButtonTone.primary,
                  large: true,
                  onPressed: _start,
                ),
                if (resume != null && widget.onStartOver != null)
                  PlayerPill(
                    label: 'Start over',
                    large: true,
                    onPressed: _startOver,
                  ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              '${resume == null ? 'Starts' : 'Resumes'} by itself in '
              '$_remaining ${_remaining == 1 ? 'second' : 'seconds'}.',
              style: const TextStyle(
                fontSize: 14,
                color: PbColors.playerText,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _titleText(String title) => Text(
        title,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontSize: 40,
          fontWeight: FontWeight.w700,
          color: PlayerTones.foreground,
          height: 1.15,
        ),
      );

  Widget _poster(String url) => ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Image.network(
          url,
          width: 240,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => const SizedBox.shrink(),
        ),
      );
}
