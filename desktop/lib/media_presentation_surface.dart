import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import 'media_kind.dart';
import 'playback_surface.dart';
import 'player_controller.dart';
import 'player_engine.dart';

class MediaPresentationSurface extends StatelessWidget {
  const MediaPresentationSurface({
    super.key,
    required this.controller,
    this.controlsVisible = false,
  });

  final PlayerController controller;
  final bool controlsVisible;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: controller,
        builder: (context, _) {
          final item = _currentItem(controller);
          return Stack(
            fit: StackFit.expand,
            children: [
              // Keep the MPV texture mounted across every transition. Music and
              // image presentations are opaque layers above it.
              PlaybackSurface(
                controller: controller,
                controlsVisible: controlsVisible,
              ),
              if (item?.mediaKind == MediaKind.audio)
                _MusicPresentation(item: item!),
              if (item?.mediaKind == MediaKind.image)
                _ImagePresentation(item: item!, controller: controller),
            ],
          );
        },
      );
}

QueueItem? _currentItem(PlayerController controller) {
  final index = controller.currentIndex;
  return index >= 0 && index < controller.queue.length
      ? controller.queue[index]
      : null;
}

class _MusicPresentation extends StatelessWidget {
  const _MusicPresentation({required this.item});
  final QueueItem item;

  @override
  Widget build(BuildContext context) {
    final artwork = item.artworkUrl ?? item.posterUrl ?? item.backdropUrl;
    final artist = item.artist?.trim();
    final album = item.album?.trim();
    return Stack(
      fit: StackFit.expand,
      children: [
        const DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [Color(0xFF18152A), Color(0xFF090B12), Color(0xFF050609)],
            ),
          ),
        ),
        if (artwork != null)
          ImageFiltered(
            imageFilter: ui.ImageFilter.blur(sigmaX: 42, sigmaY: 42),
            child: Opacity(
              opacity: 0.24,
              child: Transform.scale(
                scale: 1.15,
                child: _MediaImage(url: artwork, fit: BoxFit.cover),
              ),
            ),
          ),
        const DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0x22000000), Color(0xCC050609)],
            ),
          ),
        ),
        SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final compact = constraints.maxWidth < 760;
              final artSize = math
                  .min(
                    compact
                        ? constraints.maxWidth * 0.58
                        : constraints.maxWidth * 0.32,
                    constraints.maxHeight * (compact ? 0.46 : 0.62),
                  )
                  .clamp(180.0, 430.0);
              final artworkCard = Container(
                width: artSize,
                height: artSize,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(30),
                  boxShadow: const [
                    BoxShadow(
                        color: Color(0x99000000),
                        blurRadius: 48,
                        offset: Offset(0, 24)),
                    BoxShadow(
                        color: Color(0x335F5CFF),
                        blurRadius: 56,
                        spreadRadius: -12),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(30),
                  child: artwork == null
                      ? const _MusicArtworkFallback()
                      : _MediaImage(url: artwork, fit: BoxFit.cover),
                ),
              );
              final details = ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: compact
                      ? CrossAxisAlignment.center
                      : CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(color: Colors.white24),
                      ),
                      child: const Text(
                        'NOW PLAYING',
                        style: TextStyle(
                            fontSize: 11,
                            letterSpacing: 2.2,
                            fontWeight: FontWeight.w700),
                      ),
                    ),
                    const SizedBox(height: 22),
                    Text(
                      item.title,
                      textAlign: compact ? TextAlign.center : TextAlign.left,
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 38,
                        height: 1.08,
                        color: Colors.white,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    if (artist?.isNotEmpty == true) ...[
                      const SizedBox(height: 14),
                      Text(
                        artist!,
                        textAlign: compact ? TextAlign.center : TextAlign.left,
                        style: const TextStyle(
                            fontSize: 22,
                            color: Color(0xFFE0DFFF),
                            fontWeight: FontWeight.w500),
                      ),
                    ],
                    if (album?.isNotEmpty == true) ...[
                      const SizedBox(height: 8),
                      Text(
                        album!,
                        textAlign: compact ? TextAlign.center : TextAlign.left,
                        style: const TextStyle(
                            fontSize: 16, color: Colors.white60),
                      ),
                    ],
                  ],
                ),
              );
              return Center(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(44, 36, 44, 104),
                  child: compact
                      ? Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            artworkCard,
                            const SizedBox(height: 32),
                            details
                          ],
                        )
                      : Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            artworkCard,
                            const SizedBox(width: 64),
                            details
                          ],
                        ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _MusicArtworkFallback extends StatelessWidget {
  const _MusicArtworkFallback();

  @override
  Widget build(BuildContext context) => const DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFF6D5DFB), Color(0xFF332A73), Color(0xFF151225)],
          ),
        ),
        child: Stack(
          alignment: Alignment.center,
          children: [
            Icon(Icons.album_rounded, size: 210, color: Color(0x66FFFFFF)),
            Icon(Icons.music_note_rounded, size: 88, color: Colors.white),
          ],
        ),
      );
}

class _ImagePresentation extends StatelessWidget {
  const _ImagePresentation({required this.item, required this.controller});
  final QueueItem item;
  final PlayerController controller;

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: Colors.black,
        child: ClipRect(
          child: TweenAnimationBuilder<Offset>(
            duration: const Duration(milliseconds: 70),
            curve: Curves.linear,
            tween: Tween(
              begin: Offset(controller.imageOffsetX, controller.imageOffsetY),
              end: Offset(controller.imageOffsetX, controller.imageOffsetY),
            ),
            builder: (context, offset, child) => Transform.translate(
              offset: offset,
              child: child,
            ),
            child: TweenAnimationBuilder<double>(
              duration: const Duration(milliseconds: 70),
              curve: Curves.linear,
              tween: Tween(
                begin: controller.imageScale,
                end: controller.imageScale,
              ),
              builder: (context, scale, child) => Transform.scale(
                scale: scale,
                child: child,
              ),
              child: TweenAnimationBuilder<double>(
                duration: const Duration(milliseconds: 70),
                curve: Curves.linear,
                tween: Tween(
                  begin: controller.imageRotationDegrees,
                  end: controller.imageRotationDegrees,
                ),
                builder: (context, degrees, child) {
                  // RotatedBox swaps layout constraints for each 90° turn, so a
                  // landscape image remains fully contained after becoming portrait.
                  final quarterTurns = (degrees / 90).round();
                  final residualDegrees = degrees - quarterTurns * 90;
                  return Transform.rotate(
                    angle: residualDegrees * math.pi / 180,
                    child: RotatedBox(
                      quarterTurns: quarterTurns % 4,
                      child: child,
                    ),
                  );
                },
                child: _MediaImage(
                  url: item.url,
                  headers: item.headers,
                  fit: BoxFit.contain,
                ),
              ),
            ),
          ),
        ),
      );
}

class _MediaImage extends StatelessWidget {
  const _MediaImage({required this.url, required this.fit, this.headers});
  final String url;
  final BoxFit fit;
  final Map<String, String>? headers;

  @override
  Widget build(BuildContext context) {
    final uri = Uri.tryParse(url);
    if (uri?.scheme == 'file') {
      return Image.file(
        File.fromUri(uri!),
        fit: fit,
        errorBuilder: _error,
      );
    }
    return Image.network(
      url,
      headers: headers,
      fit: fit,
      errorBuilder: _error,
    );
  }

  Widget _error(BuildContext context, Object error, StackTrace? stack) =>
      const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.broken_image_outlined, size: 72, color: Colors.white54),
            SizedBox(height: 12),
            Text('Unable to display this image'),
          ],
        ),
      );
}
