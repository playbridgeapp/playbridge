import 'dart:async';

import 'package:bonsoir/bonsoir.dart';
import 'package:flutter/foundation.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';

enum TvProtocol {
  playBridge('PlayBridge'),
  dlna('DLNA'),
  roku('Roku'),
  googleCast('Google Cast');

  const TvProtocol(this.label);
  final String label;

  static TvProtocol fromStorageName(String? value) => switch (value) {
        'dlna' => TvProtocol.dlna,
        'roku' => TvProtocol.roku,
        'googleCast' || 'google_cast' || 'googlecast' => TvProtocol.googleCast,
        'playBridge' ||
        'playbridge' ||
        'native' ||
        null =>
          TvProtocol.playBridge,
        _ => TvProtocol.playBridge,
      };
}

/// A receiver found on the LAN by Rust discovery or the PlayBridge mDNS
/// fallback.
@immutable
class DiscoveredTv {
  /// Stable identity from the TXT `uuid` attribute (matches what the receiver
  /// advertises in [DiscoveryPublisher]). Falls back to the service name.
  final String uuid;

  final TvProtocol protocol;

  /// mDNS service (instance) name — human-facing label.
  final String name;

  /// Resolved LAN host/IP. Re-resolved on each discovery, so it survives DHCP
  /// changes (uuid stays the identity).
  final String host;

  /// All resolved receiver addresses. [host] is the currently preferred
  /// address; retaining the rest allows reconnect code to try fresh alternates.
  final List<String> addresses;

  /// Plain `ws://` port the service advertises.
  final int? port;

  /// Encrypted `wss://` port from the TXT `wss_port` attribute, or null when the
  /// receiver is ws-only.
  final int? wssPort;

  /// Protocol-specific device-description/control endpoint.
  final String? location;

  const DiscoveredTv({
    required this.uuid,
    required this.protocol,
    required this.name,
    required this.host,
    this.addresses = const [],
    required this.port,
    required this.wssPort,
    this.location,
  });

  String get stableId => uuid;
  String get identityKey => '${protocol.name}:$stableId';
  List<String> get allAddresses => List.unmodifiable({
        if (host.isNotEmpty) host,
        ...addresses.where((address) => address.isNotEmpty),
      });

  @override
  bool operator ==(Object other) =>
      other is DiscoveredTv &&
      other.uuid == uuid &&
      other.protocol == protocol &&
      other.host == host &&
      listEquals(other.addresses, addresses) &&
      other.port == port &&
      other.wssPort == wssPort &&
      other.location == location;

  @override
  int get hashCode => Object.hash(
        uuid,
        protocol,
        host,
        Object.hashAll(addresses),
        port,
        wssPort,
        location,
      );
}

/// Browses the LAN for PlayBridge TV receivers — the sender-side counterpart to
/// [DiscoveryPublisher]. Emits the current set of resolved TVs whenever it
/// changes. mDNS gives the desktop sender the same DHCP-proof discovery the
/// phone has (and the browser extension can't do at all).
class TvDiscoveryBrowser {
  static const _serviceType = '_playbridge._tcp';
  static const _scanTimeout = Duration(seconds: 15);

  BonsoirDiscovery? _discovery;
  StreamSubscription<BonsoirDiscoveryEvent>? _sub;
  DiscoveryScanner? _rustScanner;
  StreamSubscription<ReceiverEvent>? _rustSub;
  bool _rustStarting = false;
  bool _started = false;
  int _rustErrorCount = 0;

  final StreamController<List<DiscoveredTv>> _controller =
      StreamController<List<DiscoveredTv>>.broadcast();
  final StreamController<bool> _scanningController =
      StreamController<bool>.broadcast();

  // Keyed by mDNS service name: `uuid` is only known after a service resolves.
  final Map<String, DiscoveredTv> _bonjourResolved = {};
  final Map<String, DiscoveredTv> _rustResolved = {};
  final Set<String> _resolving = {};

  /// Stream of the current resolved-TV list (replaces on every change).
  Stream<List<DiscoveredTv>> get devices => _controller.stream;
  Stream<bool> get scanning => _scanningController.stream;
  bool get isScanning => _rustScanner != null || _rustStarting;

  List<DiscoveredTv> get current => mergeDiscoveredDevices(
        rust: _rustResolved.values,
        bonjourFallback: _bonjourResolved.values,
      );

  Future<void> start() async {
    if (_started) return;
    _started = true;
    unawaited(_startRustDiscovery());
    try {
      final discovery = BonsoirDiscovery(type: _serviceType);
      await discovery.ready;
      if (!_started) return;
      _sub = discovery.eventStream?.listen(
        _onEvent,
        onError: (Object _) {
          // mDNS records can disappear between discovery and resolution.
          // Bonsoir reports that normal LAN race as a stream error; keep browsing.
          debugPrint('[tv-discovery] transient Bonsoir discovery error');
        },
      );
      await discovery.start();
      if (!_started) {
        await _sub?.cancel();
        _sub = null;
        await discovery.stop();
        return;
      }
      _discovery = discovery;
      debugPrint('[tv-discovery] Bonsoir fallback browsing $_serviceType');
    } on Object {
      debugPrint('[tv-discovery] Bonsoir fallback unavailable');
    }
  }

  Future<void> _startRustDiscovery() async {
    if (!_started || _rustStarting) return;
    if (_rustScanner != null) return;
    _rustStarting = true;
    _rustErrorCount = 0;
    _emitScanning(true);
    try {
      final scanner = CastCoreLibrary.open().discover(
        protocols: const {
          ReceiverProtocol.playBridge,
          ReceiverProtocol.dlna,
          ReceiverProtocol.roku,
          ReceiverProtocol.googleCast,
        },
        timeout: _scanTimeout,
      );
      _rustScanner = scanner;
      _rustSub = scanner.events.listen(
        (event) {
          switch (event) {
            case ReceiverFound(:final receiver):
            case ReceiverUpdated(:final receiver):
              final device = discoveredTvFromRust(receiver);
              if (device != null) {
                _rustResolved[receiver.id] = device;
                _emit();
              }
            case DiscoveryError():
              _rustErrorCount++;
            case DiscoveryFinished():
            case DiscoveryStarted():
              break;
          }
        },
        onError: (Object _) {
          _rustErrorCount++;
        },
        onDone: () {
          if (!identical(_rustScanner, scanner)) return;
          debugPrint(
            '[tv-discovery] Rust scan finished: '
            'PlayBridge=${_count(TvProtocol.playBridge)}, '
            'DLNA=${_count(TvProtocol.dlna)}, '
            'Roku=${_count(TvProtocol.roku)}, errors=$_rustErrorCount',
          );
          _rustScanner = null;
          _rustSub = null;
          _emitScanning(false);
        },
      );
    } on Object {
      _rustErrorCount++;
      _rustSub = null;
      _rustScanner?.dispose();
      _rustScanner = null;
      debugPrint(
        '[tv-discovery] Rust unavailable; using Bonsoir PlayBridge fallback',
      );
      _emitScanning(false);
    } finally {
      _rustStarting = false;
    }
  }

  int _count(TvProtocol protocol) => _rustResolved.values
      .where((device) => device.protocol == protocol)
      .length;

  Future<void> _stopRustDiscovery({bool emitScanning = true}) async {
    final subscription = _rustSub;
    final scanner = _rustScanner;
    _rustSub = null;
    _rustScanner = null;
    await subscription?.cancel();
    scanner?.dispose();
    if (emitScanning) _emitScanning(false);
  }

  /// Starts a fresh bounded Rust scan without restarting the long-lived
  /// PlayBridge Bonjour fallback or touching saved receivers.
  Future<void> rescan() async {
    if (!_started) return;
    await _stopRustDiscovery(emitScanning: false);
    _rustResolved.clear();
    _emit();
    await _startRustDiscovery();
  }

  void _emitScanning(bool value) {
    if (!_scanningController.isClosed) _scanningController.add(value);
  }

  void _onEvent(BonsoirDiscoveryEvent event) {
    final service = event.service;
    switch (event.type) {
      case BonsoirDiscoveryEventType.discoveryServiceFound:
        // Resolving fills in host/port/attributes — do it eagerly so the list
        // is connect-ready without a user gesture.
        if (service != null) unawaited(_resolve(service));
        break;
      case BonsoirDiscoveryEventType.discoveryServiceResolved:
        // Only a resolved service carries the host/IP (5.1.x exposes it on the
        // ResolvedBonsoirService subtype, not the base BonsoirService).
        if (service is ResolvedBonsoirService) {
          _resolving.remove(service.name);
          _addResolved(service);
        }
        break;
      case BonsoirDiscoveryEventType.discoveryServiceLost:
        if (service != null) {
          _resolving.remove(service.name);
          if (_bonjourResolved.remove(service.name) != null) _emit();
        }
        break;
      default:
        break;
    }
  }

  Future<void> _resolve(BonsoirService service) async {
    if (!_resolving.add(service.name)) return;
    try {
      final resolver = _discovery?.serviceResolver;
      if (resolver == null) return;
      await service.resolve(resolver);
    } on Object {
      // A service may leave the network or replace its mDNS record while the
      // platform resolver is working. It can be resolved again if rediscovered.
      debugPrint('[tv-discovery] transient service resolve failure');
    } finally {
      _resolving.remove(service.name);
    }
  }

  void _addResolved(ResolvedBonsoirService s) {
    final host = s.host;
    if (host == null || host.isEmpty) return;
    final uuid = s.attributes['uuid'];
    final wssPort = int.tryParse(s.attributes['wss_port'] ?? '');
    _bonjourResolved[s.name] = DiscoveredTv(
      uuid: (uuid != null && uuid.isNotEmpty) ? uuid : s.name,
      protocol: TvProtocol.playBridge,
      name: s.name,
      host: host,
      port: s.port,
      wssPort: wssPort,
    );
    _emit();
  }

  void _emit() => _controller.add(current);

  Future<void> stop() async {
    _started = false;
    await _stopRustDiscovery();
    await _sub?.cancel();
    _sub = null;
    final d = _discovery;
    _discovery = null;
    if (d != null) await d.stop();
    _bonjourResolved.clear();
    _rustResolved.clear();
    _resolving.clear();
  }

  Future<void> dispose() async {
    await stop();
    await _controller.close();
    await _scanningController.close();
  }
}

@visibleForTesting
DiscoveredTv? discoveredTvFromRust(ReceiverInfo receiver) {
  final protocol = switch (receiver.protocol) {
    ReceiverProtocol.playBridge => TvProtocol.playBridge,
    ReceiverProtocol.dlna => TvProtocol.dlna,
    ReceiverProtocol.roku => TvProtocol.roku,
    ReceiverProtocol.googleCast => TvProtocol.googleCast,
    ReceiverProtocol.dial => null,
  };
  if (protocol == null) return null;

  final addresses = _orderedReceiverAddresses(receiver.addresses);
  final location = receiver.location?.trim();
  final hasDlnaLocation =
      protocol == TvProtocol.dlna && location != null && location.isNotEmpty;
  if (addresses.isEmpty && !hasDlnaLocation) return null;

  final identity = receiver.uuid?.trim();
  return DiscoveredTv(
    uuid: identity != null && identity.isNotEmpty ? identity : receiver.id,
    protocol: protocol,
    name: receiver.name,
    host: addresses.isEmpty ? '' : addresses.first,
    addresses: addresses,
    port: receiver.port,
    wssPort: receiver.wssPort,
    location: location,
  );
}

@visibleForTesting
List<DiscoveredTv> mergeDiscoveredDevices({
  required Iterable<DiscoveredTv> rust,
  required Iterable<DiscoveredTv> bonjourFallback,
}) {
  final merged = <String, DiscoveredTv>{};
  for (final device in rust) {
    merged['${device.protocol.name}:${device.uuid}'] = device;
  }
  for (final device in bonjourFallback) {
    final key = '${TvProtocol.playBridge.name}:${device.uuid}';
    final rustDevice = merged[key];
    if (rustDevice == null) {
      merged[key] = device;
    } else {
      // Bonsoir remains a transport-metadata fallback for older packaged Rust
      // libraries and legacy receivers while Rust owns identity and address.
      merged[key] = DiscoveredTv(
        uuid: rustDevice.uuid,
        protocol: rustDevice.protocol,
        name: rustDevice.name,
        host: rustDevice.host,
        addresses: rustDevice.addresses,
        port: device.port ?? rustDevice.port,
        wssPort: device.wssPort ?? rustDevice.wssPort,
        location: rustDevice.location,
      );
    }
  }
  final devices = merged.values.toList();
  devices.sort((left, right) {
    final protocolOrder = left.protocol.index.compareTo(right.protocol.index);
    if (protocolOrder != 0) return protocolOrder;
    return left.name.toLowerCase().compareTo(right.name.toLowerCase());
  });
  return List.unmodifiable(devices);
}

List<String> _orderedReceiverAddresses(Iterable<String> raw) {
  final addresses = <String>{
    for (final address in raw)
      if (address.trim().isNotEmpty) address.trim(),
  }.toList(growable: false);
  addresses.sort((left, right) {
    final leftRank = _addressRank(left);
    final rightRank = _addressRank(right);
    return leftRank != rightRank ? leftRank.compareTo(rightRank) : 0;
  });
  return addresses;
}

int _addressRank(String address) {
  if (!address.contains(':') && address != '127.0.0.1') return 0;
  if (address.contains(':') && !address.startsWith('::1')) return 1;
  return 2;
}
