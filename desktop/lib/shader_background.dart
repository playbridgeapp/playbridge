import 'package:flutter/material.dart';

/// Static layered gradient background. Replaces the previous shader-driven
/// aurora — those 6 octaves of FBM + domain warping looked great but ate GPU
/// even when fully covered, which made mpv playback lag on lower-end machines.
///
/// The look is approximated with a base diagonal navy gradient and two soft
/// radial highlights — costs essentially nothing per frame.
class AuroraBackground extends StatelessWidget {
  const AuroraBackground({super.key});

  @override
  Widget build(BuildContext context) {
    return const Stack(
      children: [
        // Base diagonal — deep navy with a brighter mid-band.
        Positioned.fill(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0xFF03060F),
                  Color(0xFF0C1A36),
                  Color(0xFF050B1C),
                ],
                stops: [0.0, 0.55, 1.0],
              ),
            ),
          ),
        ),
        // Soft cyan-blue glow off the upper-left — gives the sidebar's
        // BackdropFilter something colorful to blur into.
        Positioned.fill(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: RadialGradient(
                center: Alignment(-0.5, -0.7),
                radius: 1.4,
                colors: [
                  Color(0x66294E8E),
                  Color(0x00294E8E),
                ],
                stops: [0.0, 1.0],
              ),
            ),
          ),
        ),
        // Subtler glow off the lower-right.
        Positioned.fill(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: RadialGradient(
                center: Alignment(0.7, 0.8),
                radius: 1.0,
                colors: [
                  Color(0x44164080),
                  Color(0x00164080),
                ],
                stops: [0.0, 1.0],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
