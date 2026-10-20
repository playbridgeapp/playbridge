import 'playback_speed.dart';
import 'video_scaling.dart';

sealed class RemotePlayerSetting {
  const RemotePlayerSetting();
}

class RemotePlaybackSpeed extends RemotePlayerSetting {
  const RemotePlaybackSpeed(this.rate);
  final double rate;
}

class RemoteSubtitleOffset extends RemotePlayerSetting {
  const RemoteSubtitleOffset(this.deltaMs);
  final int deltaMs;
}

class RemoteLoop extends RemotePlayerSetting {
  const RemoteLoop(this.enabled);
  final bool enabled;
}

class RemoteScaling extends RemotePlayerSetting {
  const RemoteScaling(this.mode);
  final VideoScalingMode mode;
}

/// Invalid/non-finite/unknown values never change a setting to a guessed default.
RemotePlayerSetting? parseRemotePlayerSetting(String command) {
  if (command.startsWith('speed:')) {
    final rate = double.tryParse(command.substring('speed:'.length));
    if (rate == null || !rate.isFinite) return null;
    return RemotePlaybackSpeed(nearestPlaybackSpeed(rate.clamp(0.25, 2.0)));
  }
  if (command.startsWith('sub_offset:')) {
    final delta = int.tryParse(command.substring('sub_offset:'.length));
    return delta == null ? null : RemoteSubtitleOffset(delta);
  }
  return switch (command) {
    'loop_on' => const RemoteLoop(true),
    'loop_off' => const RemoteLoop(false),
    'scaling:Fit' => const RemoteScaling(VideoScalingMode.fit),
    'scaling:Zoom' => const RemoteScaling(VideoScalingMode.zoom),
    'scaling:Fill' => const RemoteScaling(VideoScalingMode.fill),
    _ => null,
  };
}
