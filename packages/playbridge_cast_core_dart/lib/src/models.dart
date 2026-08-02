import 'dart:convert';

enum ReceiverProtocol {
  playBridge(1),
  dlna(2),
  roku(4),
  dial(8),
  googleCast(16);

  const ReceiverProtocol(this.mask);
  final int mask;

  static ReceiverProtocol fromWire(String value) => switch (value) {
        'PlayBridge' => ReceiverProtocol.playBridge,
        'Dlna' => ReceiverProtocol.dlna,
        'Roku' => ReceiverProtocol.roku,
        'Dial' => ReceiverProtocol.dial,
        'GoogleCast' => ReceiverProtocol.googleCast,
        _ => throw FormatException('Unknown receiver protocol: $value'),
      };

  String get sessionWireName => switch (this) {
        ReceiverProtocol.dlna => 'dlna',
        ReceiverProtocol.roku => 'roku',
        ReceiverProtocol.googleCast => 'google_cast',
        ReceiverProtocol.playBridge ||
        ReceiverProtocol.dial =>
          throw StateError(
            '$name is not supported by native receiver sessions',
          ),
      };

  static ReceiverProtocol fromSessionWire(String value) => switch (value) {
        'dlna' => ReceiverProtocol.dlna,
        'roku' => ReceiverProtocol.roku,
        'google_cast' => ReceiverProtocol.googleCast,
        _ => throw FormatException('Unknown session protocol: $value'),
      };
}

enum GoogleCastLaunchPolicy {
  forceRelaunch('force_relaunch'),
  reuseOrLaunch('reuse_or_launch');

  const GoogleCastLaunchPolicy(this.wireName);
  final String wireName;
}

final class ReceiverEndpoint {
  ReceiverEndpoint({
    required this.protocol,
    required List<String> addresses,
    this.port,
    this.location,
    this.applicationId,
    this.googleCastLaunchPolicy = GoogleCastLaunchPolicy.reuseOrLaunch,
  }) : addresses = List.unmodifiable(addresses);

  factory ReceiverEndpoint.fromReceiverInfo(ReceiverInfo receiver) =>
      ReceiverEndpoint(
        protocol: receiver.protocol,
        addresses: receiver.addresses,
        port: receiver.port,
        location: receiver.location,
      );

  final ReceiverProtocol protocol;
  final List<String> addresses;
  final int? port;
  final String? location;
  final String? applicationId;
  final GoogleCastLaunchPolicy googleCastLaunchPolicy;

  Map<String, Object?> toJson() {
    if (protocol != ReceiverProtocol.dlna &&
        (addresses.isEmpty ||
            addresses.every((address) => address.trim().isEmpty))) {
      throw StateError('A receiver endpoint requires at least one address');
    }
    return {
      'protocol': protocol.sessionWireName,
      'addresses': addresses,
      if (port != null) 'port': port,
      if (location != null) 'location': location,
      if (applicationId != null) 'application_id': applicationId,
      if (protocol == ReceiverProtocol.googleCast)
        'launch_policy': googleCastLaunchPolicy.wireName,
    };
  }
}

final class MediaRequest {
  const MediaRequest({
    required this.url,
    this.title,
    this.metadata,
    this.contentType,
    this.artUrl,
    this.start = Duration.zero,
    this.streamType,
    this.hlsSegmentFormat,
    this.hlsVideoSegmentFormat,
  });

  final String url;
  final String? title;
  final String? metadata;
  final String? contentType;
  final String? artUrl;
  final Duration start;
  final String? streamType;
  final String? hlsSegmentFormat;
  final String? hlsVideoSegmentFormat;

  Map<String, Object?> toJson() => {
        'url': url,
        if (title != null) 'title': title,
        if (metadata != null) 'metadata': metadata,
        if (contentType != null) 'content_type': contentType,
        if (artUrl != null) 'art_url': artUrl,
        if (start != Duration.zero)
          'start_seconds':
              start.inMicroseconds / Duration.microsecondsPerSecond,
        if (streamType != null) 'stream_type': streamType,
        if (hlsSegmentFormat != null) 'hls_segment_format': hlsSegmentFormat,
        if (hlsVideoSegmentFormat != null)
          'hls_video_segment_format': hlsVideoSegmentFormat,
      };
}

final class SessionCapabilities {
  const SessionCapabilities({
    required this.load,
    required this.playbackControl,
    required this.seek,
    required this.status,
    this.receiverAppAvailable,
  });

  final bool load;
  final bool playbackControl;
  final bool seek;
  final bool status;
  final bool? receiverAppAvailable;

  factory SessionCapabilities.fromJson(Map<String, Object?> json) =>
      SessionCapabilities(
        load: json['load'] as bool? ?? false,
        playbackControl: json['playback_control'] as bool? ?? false,
        seek: json['seek'] as bool? ?? false,
        status: json['status'] as bool? ?? false,
        receiverAppAvailable: json['receiver_app_available'] as bool?,
      );
}

enum PlaybackState {
  buffering,
  playing,
  paused,
  stopped,
  finished,
  unknown;

  static PlaybackState fromWire(String? value) => switch (value) {
        'buffering' => PlaybackState.buffering,
        'playing' => PlaybackState.playing,
        'paused' => PlaybackState.paused,
        'stopped' => PlaybackState.stopped,
        'finished' => PlaybackState.finished,
        _ => PlaybackState.unknown,
      };
}

final class PlaybackStatus {
  const PlaybackStatus({
    required this.state,
    required this.position,
    required this.duration,
  });

  final PlaybackState state;
  final Duration position;
  final Duration duration;

  double get positionSeconds =>
      position.inMicroseconds / Duration.microsecondsPerSecond;
  double get durationSeconds =>
      duration.inMicroseconds / Duration.microsecondsPerSecond;

  factory PlaybackStatus.fromJson(Map<String, Object?> json) => PlaybackStatus(
        state: PlaybackState.fromWire(json['state'] as String?),
        position: _secondsToDuration(json['position_seconds']),
        duration: _secondsToDuration(json['duration_seconds']),
      );
}

Duration _secondsToDuration(Object? value) {
  final seconds = value is num ? value.toDouble() : 0.0;
  return Duration(
      microseconds: (seconds * Duration.microsecondsPerSecond).round());
}

sealed class CastSessionEvent {
  const CastSessionEvent();

  factory CastSessionEvent.fromJsonString(String source) {
    final decoded = jsonDecode(source);
    if (decoded is! Map<String, Object?>) {
      throw const FormatException('Session event must be a JSON object');
    }
    return CastSessionEvent.fromJson(decoded);
  }

  factory CastSessionEvent.fromJson(Map<String, Object?> json) {
    final event = json['event'];
    return switch (event) {
      'connected' => CastSessionConnected(
          protocol:
              ReceiverProtocol.fromSessionWire(json['protocol']! as String),
          capabilities: SessionCapabilities.fromJson(
            json['capabilities']! as Map<String, Object?>,
          ),
          name: json['name'] as String?,
          receiverApplicationId: json['receiver_application_id'] as String?,
        ),
      'operation' => CastSessionOperation(
          requestId: _requestId(json),
          operation: json['operation']! as String,
          ok: json['ok']! as bool,
        ),
      'status' => CastSessionStatus(
          requestId: _requestId(json),
          status: PlaybackStatus.fromJson(
            json['status']! as Map<String, Object?>,
          ),
        ),
      'error' => CastSessionError(
          requestId: json['request_id']?.toString(),
          operation: json['operation'] as String?,
          message: json['message']! as String,
          reason: json['reason'] as String?,
        ),
      'finished' => CastSessionFinished(
          json['reason']! as String,
          message: json['message'] as String?,
        ),
      _ => throw FormatException('Unknown session event: $event'),
    };
  }

  static String _requestId(Map<String, Object?> json) {
    final value = json['request_id'];
    if (value == null) throw const FormatException('Missing request_id');
    return value.toString();
  }
}

final class CastSessionConnected extends CastSessionEvent {
  const CastSessionConnected({
    required this.protocol,
    required this.capabilities,
    this.name,
    this.receiverApplicationId,
  });
  final ReceiverProtocol protocol;
  final SessionCapabilities capabilities;
  final String? name;
  final String? receiverApplicationId;
}

final class CastSessionOperation extends CastSessionEvent {
  const CastSessionOperation({
    required this.requestId,
    required this.operation,
    required this.ok,
  });
  final String requestId;
  final String operation;
  final bool ok;
}

final class CastSessionStatus extends CastSessionEvent {
  const CastSessionStatus({required this.requestId, required this.status});
  final String requestId;
  final PlaybackStatus status;
}

final class CastSessionError extends CastSessionEvent implements Exception {
  const CastSessionError({
    required this.message,
    this.requestId,
    this.operation,
    this.reason,
  });
  final String? requestId;
  final String? operation;
  final String message;
  final String? reason;

  bool get receiverEnded => reason == 'receiver_ended';
  bool get sessionUnresponsive => reason == 'session_unresponsive';
  bool get connectionLost => reason == 'connection_lost';

  /// A request-less error with one of these reasons is followed by a finished
  /// event and invalidates the native worker. Request-less maintenance errors
  /// deliberately carry no reason and leave the session usable.
  bool get endsSession =>
      receiverEnded || sessionUnresponsive || connectionLost;

  @override
  String toString() => operation == null
      ? 'Cast session error: $message'
      : 'Cast session $operation error: $message';
}

final class CastSessionFinished extends CastSessionEvent {
  const CastSessionFinished(this.reason, {this.message});
  final String reason;
  final String? message;
}

final class ReceiverInfo {
  const ReceiverInfo({
    required this.id,
    required this.protocol,
    required this.name,
    required this.addresses,
    this.port,
    this.wssPort,
    this.location,
    this.uuid,
  });

  final String id;
  final ReceiverProtocol protocol;
  final String name;
  final List<String> addresses;
  final int? port;
  final int? wssPort;
  final String? location;
  final String? uuid;

  factory ReceiverInfo.fromJson(Map<String, Object?> json) => ReceiverInfo(
        id: json['id']! as String,
        protocol: ReceiverProtocol.fromWire(json['protocol']! as String),
        name: json['name']! as String,
        addresses: List<String>.unmodifiable(
          (json['addresses']! as List<Object?>).cast<String>(),
        ),
        port: json['port'] as int?,
        wssPort: json['wss_port'] as int?,
        location: json['location'] as String?,
        uuid: json['uuid'] as String?,
      );
}

sealed class ReceiverEvent {
  const ReceiverEvent();

  factory ReceiverEvent.fromJsonString(String source) {
    final decoded = jsonDecode(source);
    if (decoded is! Map<String, Object?>) {
      throw const FormatException('Discovery event must be a JSON object');
    }
    return ReceiverEvent.fromJson(decoded);
  }

  factory ReceiverEvent.fromJson(Map<String, Object?> json) {
    final event = json['event'];
    return switch (event) {
      'started' => DiscoveryStarted(
          ReceiverProtocol.fromWire(json['protocol']! as String),
        ),
      'found' => ReceiverFound(
          ReceiverInfo.fromJson(json['receiver']! as Map<String, Object?>),
        ),
      'updated' => ReceiverUpdated(
          ReceiverInfo.fromJson(json['receiver']! as Map<String, Object?>),
        ),
      'error' => DiscoveryError(
          ReceiverProtocol.fromWire(json['protocol']! as String),
          json['message']! as String,
        ),
      'finished' => DiscoveryFinished(
          ReceiverProtocol.fromWire(json['protocol']! as String),
        ),
      _ => throw FormatException('Unknown discovery event: $event'),
    };
  }
}

final class DiscoveryStarted extends ReceiverEvent {
  const DiscoveryStarted(this.protocol);
  final ReceiverProtocol protocol;
}

final class ReceiverFound extends ReceiverEvent {
  const ReceiverFound(this.receiver);
  final ReceiverInfo receiver;
}

final class ReceiverUpdated extends ReceiverEvent {
  const ReceiverUpdated(this.receiver);
  final ReceiverInfo receiver;
}

final class DiscoveryError extends ReceiverEvent {
  const DiscoveryError(this.protocol, this.message);
  final ReceiverProtocol protocol;
  final String message;
}

final class DiscoveryFinished extends ReceiverEvent {
  const DiscoveryFinished(this.protocol);
  final ReceiverProtocol protocol;
}
