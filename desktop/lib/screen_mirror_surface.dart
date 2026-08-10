import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import 'screen_mirror_receiver.dart';

class ScreenMirrorSurface extends StatelessWidget {
  const ScreenMirrorSurface({super.key, required this.receiver});

  final ScreenMirrorReceiver receiver;

  @override
  Widget build(BuildContext context) {
    final renderer = receiver.renderer;
    return ColoredBox(
      color: Colors.black,
      child: Stack(
        fit: StackFit.expand,
        children: [
          if (renderer != null)
            RTCVideoView(
              renderer,
              objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitContain,
              filterQuality: FilterQuality.medium,
            ),
          if (renderer == null || !receiver.isConnected)
            Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (receiver.error == null)
                    const CircularProgressIndicator(strokeWidth: 2)
                  else
                    const Icon(Icons.error_outline, size: 36),
                  const SizedBox(height: 16),
                  Text(
                    receiver.error == null
                        ? 'Connecting screen mirror…'
                        : 'Screen mirror ended',
                    style: const TextStyle(color: Colors.white70),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
