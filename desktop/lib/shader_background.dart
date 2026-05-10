import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

/// Animated aurora background driven by a fragment shader. Sits behind all
/// other UI; the video texture covers it during playback so it pays no
/// rendering cost while the user is watching something.
class AuroraBackground extends StatefulWidget {
  const AuroraBackground({super.key});

  @override
  State<AuroraBackground> createState() => _AuroraBackgroundState();
}

class _AuroraBackgroundState extends State<AuroraBackground>
    with SingleTickerProviderStateMixin {
  ui.FragmentShader? _shader;
  late final Ticker _ticker;
  Duration _elapsed = Duration.zero;

  @override
  void initState() {
    super.initState();
    _load();
    _ticker = createTicker((d) {
      setState(() => _elapsed = d);
    });
    _ticker.start();
  }

  Future<void> _load() async {
    try {
      final program =
          await ui.FragmentProgram.fromAsset('shaders/aurora.frag');
      if (!mounted) return;
      setState(() => _shader = program.fragmentShader());
    } catch (e) {
      debugPrint('[shader] failed to load aurora.frag: $e');
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    _shader?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shader = _shader;
    if (shader == null) {
      // Fallback: a flat dark color while compiling, or if shader compile
      // failed (logged but UI doesn't break).
      return const ColoredBox(color: Color(0xFF0A0612));
    }
    return CustomPaint(
      painter: _AuroraPainter(shader, _elapsed),
      size: Size.infinite,
    );
  }
}

class _AuroraPainter extends CustomPainter {
  _AuroraPainter(this.shader, this.elapsed);

  final ui.FragmentShader shader;
  final Duration elapsed;

  @override
  void paint(Canvas canvas, Size size) {
    shader
      ..setFloat(0, size.width)
      ..setFloat(1, size.height)
      ..setFloat(2, elapsed.inMicroseconds / 1e6);
    canvas.drawRect(
      Offset.zero & size,
      Paint()..shader = shader,
    );
  }

  @override
  bool shouldRepaint(covariant _AuroraPainter old) => true;
}
