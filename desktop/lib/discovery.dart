import 'dart:async';

import 'package:bonsoir/bonsoir.dart';
import 'package:flutter/foundation.dart';

/// Publishes the receiver as `_playbridge._tcp.` on mDNS so the phone's
/// NsdHelper can find it. The TXT record carries `uuid=<deviceId>`,
/// matching what `phone/.../NsdHelper.kt` reads.
class DiscoveryPublisher {
  DiscoveryPublisher({
    required this.serviceName,
    required this.port,
    required this.deviceId,
  });

  final String serviceName;
  final int port;
  final String deviceId;

  BonsoirBroadcast? _broadcast;
  StreamSubscription? _eventsSub;

  /// [wssPort] is advertised as the `wss_port` TXT attribute when non-null so
  /// senders can find the encrypted endpoint. Older senders ignore it.
  Future<void> start({int? wssPort}) async {
    final service = BonsoirService(
      name: serviceName,
      type: '_playbridge._tcp',
      port: port,
      attributes: {
        'uuid': deviceId,
        if (wssPort != null) 'wss_port': '$wssPort',
      },
    );

    final broadcast = BonsoirBroadcast(service: service);
    await broadcast.ready;
    _eventsSub = broadcast.eventStream?.listen((e) {
      debugPrint('[discovery] ${e.type}');
    });
    await broadcast.start();
    _broadcast = broadcast;
    debugPrint('[discovery] published $serviceName on _playbridge._tcp.:$port '
        '(uuid=$deviceId, wss_port=${wssPort ?? '-'})');
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
