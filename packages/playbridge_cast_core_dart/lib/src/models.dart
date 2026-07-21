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
