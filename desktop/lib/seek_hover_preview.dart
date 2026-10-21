import 'package:flutter/material.dart';

import 'chapter_navigation.dart';
import 'playback_clock.dart';
import 'player_engine.dart';

/// Wraps a seek [Slider] and, while the mouse is over it, shows a small bubble
/// above the pointer with the time there and the chapter it falls in.
///
/// The bubble lives in the overlay because the player bars clip their
/// children. [trackInset] is the slider's horizontal track padding: half the
/// larger of its overlay and thumb widths.
class SeekHoverPreview extends StatefulWidget {
  const SeekHoverPreview({
    super.key,
    required this.durationMs,
    required this.chapters,
    required this.trackInset,
    required this.child,
    this.enabled = true,
  });

  final int durationMs;
  final List<ChapterInfo> chapters;
  final double trackInset;
  final bool enabled;
  final Widget child;

  @override
  State<SeekHoverPreview> createState() => _SeekHoverPreviewState();
}

class _SeekHoverPreviewState extends State<SeekHoverPreview> {
  final _portal = OverlayPortalController();
  int? _hoverMs;
  Offset _anchor = Offset.zero;

  bool get _active => widget.enabled && widget.durationMs > 0;

  @override
  void didUpdateWidget(SeekHoverPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_active) _hide();
  }

  void _onHover(PointerEvent event) {
    if (!_active) return;
    final box = context.findRenderObject() as RenderBox?;
    final overlay =
        Overlay.maybeOf(context)?.context.findRenderObject() as RenderBox?;
    if (box == null || overlay == null || !box.hasSize) return;
    final inset = widget.trackInset;
    final width = box.size.width - 2 * inset;
    if (width <= 0) return;
    final x = event.localPosition.dx.clamp(inset, inset + width);
    final fraction = (x - inset) / width;
    setState(() {
      _hoverMs = (fraction * widget.durationMs).round();
      _anchor = overlay.globalToLocal(box.localToGlobal(Offset(x, 0)));
    });
    if (!_portal.isShowing) _portal.show();
  }

  void _hide() {
    _hoverMs = null;
    if (_portal.isShowing) _portal.hide();
  }

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onHover: _onHover,
      onExit: (_) => setState(_hide),
      child: OverlayPortal(
        controller: _portal,
        overlayChildBuilder: _buildBubble,
        child: widget.child,
      ),
    );
  }

  Widget _buildBubble(BuildContext context) {
    final ms = _hoverMs;
    if (ms == null) return const SizedBox.shrink();
    final chapter = chapterCaptionAt(widget.chapters, ms);
    return Positioned(
      left: _anchor.dx,
      top: _anchor.dy - 4,
      child: IgnorePointer(
        child: FractionalTranslation(
          translation: const Offset(-0.5, -1),
          child:
              SeekHoverBubble(time: formatPlaybackClock(ms), caption: chapter),
        ),
      ),
    );
  }
}

/// The hover bubble: time on top, chapter caption underneath when known.
class SeekHoverBubble extends StatelessWidget {
  const SeekHoverBubble({super.key, required this.time, this.caption});

  final String time;
  final String? caption;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 240),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: const Color(0xE6202020),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                time,
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
              if (caption != null)
                Text(
                  caption!,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 11, color: Colors.white70),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
