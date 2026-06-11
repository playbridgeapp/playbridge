import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'player_engine.dart';

/// Cinematic pre-play screen shown over the (muted, buffering) video when a cast
/// with visual metadata arrives — desktop counterpart of the tvOS PrePlayView and
/// Android PrePlayActivity. Full-bleed backdrop, gradient scrim, title/logo,
/// metadata chips, overview, poster, and a 7-second countdown. Clicking anywhere
/// (or the countdown reaching zero) starts playback via [onStart].
class PrePlayOverlay extends StatefulWidget {
  const PrePlayOverlay({
    super.key,
    required this.item,
    required this.onStart,
    this.countdownSeconds = 7,
  });

  final QueueItem item;
  final VoidCallback onStart;
  final int countdownSeconds;

  @override
  State<PrePlayOverlay> createState() => _PrePlayOverlayState();
}

class _PrePlayOverlayState extends State<PrePlayOverlay> {
  late int _remaining;
  Timer? _timer;
  bool _started = false;

  @override
  void initState() {
    super.initState();
    _remaining = widget.countdownSeconds;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() => _remaining--);
      if (_remaining <= 0) _start();
    });
  }

  void _start() {
    if (_started) return;
    _started = true;
    _timer?.cancel();
    widget.onStart();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final backdrop = item.backdropUrl ?? item.posterUrl;

    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _start,
      child: Stack(
        fit: StackFit.expand,
        children: [
          // Base — covers the buffering video.
          Container(color: Colors.black),

          // Full-bleed backdrop.
          if (backdrop != null)
            ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 2, sigmaY: 2),
              child: Image.network(
                backdrop,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const SizedBox.shrink(),
              ),
            ),

          // Cinematic scrim: left fade for legibility + bottom/top vignettes.
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.centerLeft,
                end: Alignment.centerRight,
                stops: [0.0, 0.35, 0.65, 1.0],
                colors: [
                  Color(0xF7000000),
                  Color(0xD9000000),
                  Color(0x8C000000),
                  Color(0x33000000),
                ],
              ),
            ),
          ),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.bottomCenter,
                end: Alignment.topCenter,
                stops: [0.0, 0.4],
                colors: [Color(0x99000000), Color(0x00000000)],
              ),
            ),
          ),

          // Content. The poster only renders when there's comfortably room for
          // it next to the text column (e.g. not mid fullscreen transition).
          LayoutBuilder(
            builder: (context, constraints) {
              final showPoster =
                  item.posterUrl != null && constraints.maxWidth >= 900;
              final pad = constraints.maxWidth >= 900 ? 72.0 : 32.0;
              return Padding(
                padding: EdgeInsets.fromLTRB(pad, 48, pad, 48),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
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
    final chips = <String>[
      if (item.year != null) item.year!,
      if (item.rating != null) 'IMDb ${item.rating}',
      if (item.runtime != null) item.runtime!,
    ];
    final hasEpisode = item.season != null && item.episode != null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        // Logo or title text.
        if (item.logoUrl != null)
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420, maxHeight: 110),
            child: Image.network(
              item.logoUrl!,
              fit: BoxFit.contain,
              alignment: Alignment.centerLeft,
              errorBuilder: (_, __, ___) => _titleText(item.title),
            ),
          )
        else
          _titleText(item.title),
        const SizedBox(height: 18),

        if (chips.isNotEmpty)
          Wrap(
            spacing: 8,
            children: [for (final c in chips) _chip(c)],
          ),
        if (hasEpisode) ...[
          const SizedBox(height: 14),
          Text(
            'S${item.season} · E${item.episode}'
            '${item.episodeTitle != null ? '  ${item.episodeTitle}' : ''}',
            style: const TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w600,
              color: Colors.white,
            ),
          ),
        ],
        if (item.overview != null) ...[
          const SizedBox(height: 16),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Text(
              item.overview!,
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 15,
                height: 1.45,
                color: Colors.white.withValues(alpha: 0.75),
              ),
            ),
          ),
        ],
        const SizedBox(height: 28),

        // Countdown + hint.
        Row(
          children: [
            _countdownRing(),
            const SizedBox(width: 16),
            Flexible(
              child: Text(
                'Starting in $_remaining…  click to start now',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 14,
                  color: Colors.white.withValues(alpha: 0.7),
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _titleText(String title) => Text(
        title,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontSize: 52,
          fontWeight: FontWeight.w900,
          color: Colors.white,
          height: 1.05,
        ),
      );

  Widget _chip(String label) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: Colors.white.withValues(alpha: 0.2)),
        ),
        child: Text(
          label,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: Colors.white,
          ),
        ),
      );

  Widget _countdownRing() {
    final progress =
        widget.countdownSeconds > 0 ? _remaining / widget.countdownSeconds : 0.0;
    return SizedBox(
      width: 44,
      height: 44,
      child: Stack(
        fit: StackFit.expand,
        alignment: Alignment.center,
        children: [
          CircularProgressIndicator(
            value: progress.clamp(0.0, 1.0),
            strokeWidth: 3,
            backgroundColor: Colors.white.withValues(alpha: 0.15),
            valueColor: const AlwaysStoppedAnimation(Colors.white),
          ),
          Center(
            child: Text(
              '$_remaining',
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w700,
                color: Colors.white,
              ),
            ),
          ),
        ],
      ),
    );
  }

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
