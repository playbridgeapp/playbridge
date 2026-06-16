import 'dart:async';

import 'package:bonsoir/bonsoir.dart';
import 'package:flutter/foundation.dart';

/// A PlayBridge TV receiver found on the LAN via mDNS (`_playbridge._tcp`).
@immutable
class DiscoveredTv {
  /// Stable identity from the TXT `uuid` attribute (matches what the receiver
  /// advertises in [DiscoveryPublisher]). Falls back to the service name.
  final String uuid;

  /// mDNS service (instance) name — human-facing label.
  final String name;

  /// Resolved LAN host/IP. Re-resolved on each discovery, so it survives DHCP
  /// changes (uuid stays the identity).
  final String host;

  /// Plain `ws://` port the service advertises.
  final int port;

  /// Encrypted `wss://` port from the TXT `wss_port` attribute, or null when the
  /// receiver is ws-only.
  final int? wssPort;

  const DiscoveredTv({
    required this.uuid,
    required this.name,
    required this.host,
    required this.port,
    required this.wssPort,
  });

  @override
  bool operator ==(Object other) =>
      other is DiscoveredTv &&
      other.uuid == uuid &&
      other.host == host &&
      other.port == port &&
      other.wssPort == wssPort;

  @override
  int get hashCode => Object.hash(uuid, host, port, wssPort);
}

/// Browses the LAN for PlayBridge TV receivers — the sender-side counterpart to
/// [DiscoveryPublisher]. Emits the current set of resolved TVs whenever it
/// changes. mDNS gives the desktop sender the same DHCP-proof discovery the
/// phone has (and the browser extension can't do at all).
class TvDiscoveryBrowser {
  static const _serviceType = '_playbridge._tcp';

  BonsoirDiscovery? _discovery;
  StreamSubscription<BonsoirDiscoveryEvent>? _sub;

  final StreamController<List<DiscoveredTv>> _controller =
      StreamController<List<DiscoveredTv>>.broadcast();

  // Keyed by mDNS service name: `uuid` is only known after a service resolves.
  final Map<String, DiscoveredTv> _resolved = {};

  /// Stream of the current resolved-TV list (replaces on every change).
  Stream<List<DiscoveredTv>> get devices => _controller.stream;

  List<DiscoveredTv> get current => _resolved.values.toList(growable: false);

  Future<void> start() async {
    if (_discovery != null) return;
    final discovery = BonsoirDiscovery(type: _serviceType);
    await discovery.ready;
    _sub = discovery.eventStream?.listen(_onEvent);
    await discovery.start();
    _discovery = discovery;
    debugPrint('[tv-discovery] browsing $_serviceType');
  }

  void _onEvent(BonsoirDiscoveryEvent event) {
    final service = event.service;
    switch (event.type) {
      case BonsoirDiscoveryEventType.discoveryServiceFound:
        // Resolving fills in host/port/attributes — do it eagerly so the list
        // is connect-ready without a user gesture.
        service?.resolve(_discovery!.serviceResolver);
        break;
      case BonsoirDiscoveryEventType.discoveryServiceResolved:
        // Only a resolved service carries the host/IP (5.1.x exposes it on the
        // ResolvedBonsoirService subtype, not the base BonsoirService).
        if (service is ResolvedBonsoirService) _addResolved(service);
        break;
      case BonsoirDiscoveryEventType.discoveryServiceLost:
        if (service != null && _resolved.remove(service.name) != null) _emit();
        break;
      default:
        break;
    }
  }

  void _addResolved(ResolvedBonsoirService s) {
    final host = s.host;
    if (host == null || host.isEmpty) return;
    final uuid = s.attributes['uuid'];
    final wssPort = int.tryParse(s.attributes['wss_port'] ?? '');
    _resolved[s.name] = DiscoveredTv(
      uuid: (uuid != null && uuid.isNotEmpty) ? uuid : s.name,
      name: s.name,
      host: host,
      port: s.port,
      wssPort: wssPort,
    );
    _emit();
  }

  void _emit() => _controller.add(current);

  Future<void> stop() async {
    await _sub?.cancel();
    _sub = null;
    final d = _discovery;
    _discovery = null;
    if (d != null) await d.stop();
    _resolved.clear();
  }

  Future<void> dispose() async {
    await stop();
    await _controller.close();
  }
}
