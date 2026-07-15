import 'dart:async';

import 'package:bonsoir/bonsoir.dart';
import 'package:flutter/foundation.dart';

/// Publishes the receiver as `_playbridge._tcp.` on mDNS so the phone's
/// NsdHelper can find it. The TXT record carries `uuid=<deviceId>`,
/// matching what `phone/.../NsdHelper.kt` reads.
class DiscoveryPublisher {
  DiscoveryPublisher({
    required this.serviceName,
    required this.deviceId,
  });

  final String serviceName;
  final String deviceId;

  BonsoirBroadcast? _broadcast;
  StreamSubscription? _eventsSub;

  /// Builds the service published by [start]. Both the SRV record and TXT
  /// attribute identify the actual bound secure listener.
  @visibleForTesting
  BonsoirService serviceForPort(int port) {
    if (port < 1 || port > 65535) {
      throw ArgumentError.value(port, 'port', 'must be between 1 and 65535');
    }
    return BonsoirService(
      name: serviceName,
      type: '_playbridge._tcp',
      port: port,
      attributes: {
        'uuid': deviceId,
        'wss_port': '$port',
      },
    );
  }

  Future<void> start({required int port}) async {
    final service = serviceForPort(port);

    final broadcast = BonsoirBroadcast(service: service);
    await broadcast.ready;
    _eventsSub = broadcast.eventStream?.listen((e) {
      debugPrint('[discovery] ${e.type}');
    });
    await broadcast.start();
    _broadcast = broadcast;
    debugPrint('[discovery] published $serviceName on _playbridge._tcp.:$port '
        '(uuid=$deviceId, wss_port=$port)');
  }

  Future<void> stop() async {
    await _eventsSub?.cancel();
    _eventsSub = null;
    final b = _broadcast;
    _broadcast = null;
    if (b != null && !b.isStopped) {
      await b.stop();
    }
  }
}
