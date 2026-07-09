import 'package:flutter/material.dart';

/// Centered, auto-fading on-screen display for pause / seek / volume cues.
class PlaybackOsd extends StatelessWidget {
  const PlaybackOsd({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Center(
        child: AnimatedOpacity(
          opacity: message.isEmpty ? 0 : 1,
          duration: const Duration(milliseconds: 120),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.72),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
            ),
            child: Text(
              message,
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w600,
                color: Colors.white,
                letterSpacing: 0.3,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
