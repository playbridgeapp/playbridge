import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'media_kind.dart';
import 'playback_surface.dart';
import 'player_controller.dart';
import 'player_engine.dart';
import 'ui/pb_theme.dart';

class MediaPresentationSurface extends StatelessWidget {
  const MediaPresentationSurface({
    super.key,
    required this.controller,
    this.subtitleBottomInset = subtitleBottomHidden,
  });

  final PlayerController controller;
  final double subtitleBottomInset;

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
                subtitleBottomInset: subtitleBottomInset,
              ),
              if (item?.mediaKind == MediaKind.audio)
                MusicPresentation(
                  item: item!,
                  queueIndex: controller.currentIndex,
                  queueLength: controller.queue.length,
                ),
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

/// Music view: album art, title, "artist · album" and the place in the queue
/// on the app's own palette. The seek bar, previous / play / next, queue and
/// more live in the player's control bar, which stays over this view.
class MusicPresentation extends StatelessWidget {
  const MusicPresentation({
    super.key,
    required this.item,
    this.queueIndex,
    this.queueLength = 0,
  });

  final QueueItem item;

  /// Zero-based position in the queue; with [queueLength] above one, shows
  /// "3 of 12 in the queue".
  final int? queueIndex;
  final int queueLength;

  @override
  Widget build(BuildContext context) {
    final artwork = item.artworkUrl ?? item.posterUrl ?? item.backdropUrl;
    final byline = [
      for (final part in [item.artist, item.album])
        if (part != null && part.trim().isNotEmpty) part.trim(),
    ].join(' · ');
    final place = queueIndex != null && queueLength > 1
        ? '${queueIndex! + 1} of $queueLength in the queue'
        : null;
    return ColoredBox(
      color: PbColors.background,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final compact = constraints.maxWidth < 760;
          final art = math.min(
            320.0,
            math.max(
              140.0,
              math.min(constraints.maxWidth * (compact ? 0.6 : 0.34),
                  constraints.maxHeight * 0.45),
            ),
          );
          final artCard = ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: SizedBox(
              width: art,
              height: art,
              child: artwork == null
                  ? const ColoredBox(
                      color: PbColors.thumbnail,
                      child: Center(
                        child: Icon(
                          Icons.music_note_rounded,
                          size: 96,
                          color: PbColors.onSelectedSecondary,
                        ),
                      ),
                    )
                  : _MediaImage(url: artwork, fit: BoxFit.cover),
            ),
          );
          final details = ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 460),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: compact
                  ? CrossAxisAlignment.center
                  : CrossAxisAlignment.start,
              children: [
                if (place != null)
                  Text(
                    place,
                    style: const TextStyle(color: PbColors.textSecondary),
                  ),
                const SizedBox(height: 6),
                Text(
                  item.title,
                  textAlign: compact ? TextAlign.center : TextAlign.left,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 32,
                    height: 1.2,
                    fontWeight: FontWeight.w700,
                    color: PbColors.text,
                  ),
                ),
                if (byline.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    byline,
                    textAlign: compact ? TextAlign.center : TextAlign.left,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 17,
                      color: PbColors.textSecondary,
                    ),
                  ),
                ],
              ],
            ),
          );
          return Center(
            child: Padding(
              // Bottom padding keeps the content clear of the control bar.
              padding: const EdgeInsets.fromLTRB(44, 36, 44, 136),
              child: compact
                  ? Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [artCard, const SizedBox(height: 32), details],
                    )
                  : Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [artCard, const SizedBox(width: 56), details],
                    ),
            ),
          );
        },
      ),
    );
  }
}

class _ImagePresentation extends StatelessWidget {
  const _ImagePresentation({required this.item, required this.controller});
  final QueueItem item;
  final PlayerController controller;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (context, constraints) {
          controller.setImageViewportSize(
            constraints.maxWidth,
            constraints.maxHeight,
          );
          return ColoredBox(
            color: Colors.black,
            child: ClipRect(
              child: TweenAnimationBuilder<Offset>(
                duration: const Duration(milliseconds: 70),
                curve: Curves.linear,
                tween: Tween(
                  begin:
                      Offset(controller.imageOffsetX, controller.imageOffsetY),
                  end: Offset(controller.imageOffsetX, controller.imageOffsetY),
                ),
                builder: (context, offset, child) => Transform.translate(
                  offset: offset,
                  child: child,
                ),
                child: TweenAnimationBuilder<double>(
                  // Keep scale and translation on the same timeline. Different
                  // durations make a panned image visibly wobble during zoom.
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
                    // Rotation and anchor-compensating translation must
                    // interpolate together or the gesture midpoint drifts.
                    duration: const Duration(milliseconds: 70),
                    curve: Curves.linear,
                    tween: Tween(
                      begin: controller.imageRotationDegrees,
                      end: controller.imageRotationDegrees,
                    ),
                    builder: (context, degrees, child) =>
                        _FittedRotatedMediaImage(
                      url: item.url,
                      headers: item.headers,
                      degrees: degrees,
                    ),
                  ),
                ),
              ),
            ),
          );
        },
      );
}

class _FittedRotatedMediaImage extends StatefulWidget {
  const _FittedRotatedMediaImage({
    required this.url,
    required this.headers,
    required this.degrees,
  });

  final String url;
  final Map<String, String>? headers;
  final double degrees;

  @override
  State<_FittedRotatedMediaImage> createState() =>
      _FittedRotatedMediaImageState();
}

class _FittedRotatedMediaImageState extends State<_FittedRotatedMediaImage> {
  ImageStream? _stream;
  ImageStreamListener? _listener;
  double? _imageWidth;
  double? _imageHeight;

  ImageProvider<Object> get _provider {
    final uri = Uri.tryParse(widget.url);
    if (uri?.scheme == 'file') return FileImage(File.fromUri(uri!));
    return NetworkImage(widget.url, headers: widget.headers);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _resolveImage();
  }

  @override
  void didUpdateWidget(covariant _FittedRotatedMediaImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) _resolveImage();
  }

  void _resolveImage() {
    final nextStream =
        _provider.resolve(createLocalImageConfiguration(context));
    if (nextStream.key == _stream?.key) return;
    if (_stream != null && _listener != null) {
      _stream!.removeListener(_listener!);
    }
    _stream = nextStream;
    _listener = ImageStreamListener((info, _) {
      final width = info.image.width.toDouble();
      final height = info.image.height.toDouble();
      info.dispose();
      if (mounted && (width != _imageWidth || height != _imageHeight)) {
        setState(() {
          _imageWidth = width;
          _imageHeight = height;
        });
      }
    });
    nextStream.addListener(_listener!);
  }

  @override
  void dispose() {
    if (_stream != null && _listener != null) {
      _stream!.removeListener(_listener!);
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (context, constraints) {
          final viewportWidth = constraints.maxWidth;
          final viewportHeight = constraints.maxHeight;
          final sourceWidth = _imageWidth;
          final sourceHeight = _imageHeight;
          var rotationFit = 1.0;
          var imageWidth = viewportWidth;
          var imageHeight = viewportHeight;

          if (sourceWidth != null &&
              sourceHeight != null &&
              sourceWidth > 0 &&
              sourceHeight > 0 &&
              viewportWidth.isFinite &&
              viewportHeight.isFinite) {
            final baseScale = math.min(
              viewportWidth / sourceWidth,
              viewportHeight / sourceHeight,
            );
            imageWidth = sourceWidth * baseScale;
            imageHeight = sourceHeight * baseScale;
            final nearestQuarterTurn = (widget.degrees / 90).round();
            final residualDegrees = widget.degrees - nearestQuarterTurn * 90;
            final isExactOddQuarterTurn =
                nearestQuarterTurn.isOdd && residualDegrees.abs() < 0.01;
            if (isExactOddQuarterTurn) {
              // Fit precise ±90° button rotations without continuously resizing
              // the image during a free-form twist. Continuous bounding-box
              // fitting made portrait photos visibly breathe in and out.
              rotationFit = math.min(
                1.0,
                math.min(
                  viewportWidth / imageHeight,
                  viewportHeight / imageWidth,
                ),
              );
            }
          }

          return Center(
            child: Transform.rotate(
              angle: widget.degrees * math.pi / 180,
              child: Transform.scale(
                scale: rotationFit,
                child: SizedBox(
                  width: imageWidth,
                  height: imageHeight,
                  child: _MediaImage(
                    url: widget.url,
                    headers: widget.headers,
                    fit: BoxFit.fill,
                  ),
                ),
              ),
            ),
          );
        },
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
