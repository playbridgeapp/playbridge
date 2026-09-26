import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import 'auto_launch.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'receiver_server.dart';
import 'tv_connection_store.dart';
import 'tv_discovery.dart';
import 'tv_sender_client.dart';
import 'tv_sender_controller.dart';

/// Builds the native menu from coarse sender/receiver state. The device key is
/// a stable protocol + UUID, never a host, URL, or pairing credential.
@visibleForTesting
Menu buildTrayMenu({
  required String senderStatus,
  required String receiverStatus,
  required List<TvRecord> savedDevices,
  required Set<String> nearbyDeviceKeys,
  required String? activeDeviceKey,
  required bool canDisconnect,
  required bool routeThroughDesktop,
  required bool launchAtLogin,
  required bool hasRemotePlayback,
  required String? remoteTitle,
  required String remotePlaybackState,
}) {
  bool isAvailable(TvRecord device) =>
      nearbyDeviceKeys.contains(device.identityKey) ||
      device.identityKey == activeDeviceKey;

  final devices = savedDevices.toList()
    ..sort((a, b) {
      final protocol = a.protocol.index.compareTo(b.protocol.index);
      if (protocol != 0) return protocol;
      final availability =
          (isAvailable(b) ? 1 : 0).compareTo(isAvailable(a) ? 1 : 0);
      if (availability != 0) return availability;
      final name = a.name.toLowerCase().compareTo(b.name.toLowerCase());
      return name != 0 ? name : a.identityKey.compareTo(b.identityKey);
    });
  return Menu(items: [
    MenuItem(key: 'show', label: 'Show window'),
    MenuItem.separator(),
    MenuItem(label: 'Sender', disabled: true),
    MenuItem(label: senderStatus, disabled: true),
    MenuItem(
      label: hasRemotePlayback
          ? '${remotePlaybackState == 'paused' ? 'Paused' : 'Now playing'}: '
              '${_trayTitle(remoteTitle)}'
          : 'Not playing',
      disabled: true,
    ),
    if (hasRemotePlayback) ...[
      MenuItem(
        key: 'sender_play_pause',
        label: switch (remotePlaybackState) {
          'playing' => 'Pause playback',
          'paused' => 'Resume playback',
          _ => 'Play / pause',
        },
      ),
      MenuItem(key: 'sender_stop', label: 'Stop playback'),
    ],
    MenuItem.separator(),
    MenuItem.submenu(
      label: 'Saved devices',
      submenu: Menu(items: [
        if (devices.isEmpty)
          MenuItem(label: 'No saved devices', disabled: true),
        for (final protocol in TvProtocol.values)
          if (devices.any((device) => device.protocol == protocol)) ...[
            if (protocol != devices.first.protocol) MenuItem.separator(),
            MenuItem(label: protocol.label, disabled: true),
            for (final device in devices.where((d) => d.protocol == protocol))
              MenuItem.checkbox(
                key: 'sender_device:${device.identityKey}',
                label: '${device.name.isEmpty ? 'Unnamed device' : device.name}'
                    '${isAvailable(device) ? '' : ' · Not found'}',
                checked: device.identityKey == activeDeviceKey,
                disabled: device.identityKey == activeDeviceKey,
              ),
          ],
        MenuItem.separator(),
        MenuItem(key: 'sender_manage', label: 'Find or manage devices…'),
      ]),
    ),
    MenuItem.submenu(
      label:
          'Send mode: ${routeThroughDesktop ? 'Via this desktop' : 'Direct to TV'}',
      submenu: Menu(items: [
        MenuItem.checkbox(
          key: 'route_direct',
          label: 'Direct to TV',
          checked: !routeThroughDesktop,
        ),
        MenuItem.checkbox(
          key: 'route_desktop',
          label: 'Via this desktop',
          checked: routeThroughDesktop,
        ),
      ]),
    ),
    MenuItem(
      key: 'sender_disconnect',
      label: 'Disconnect',
      disabled: !canDisconnect,
    ),
    MenuItem.separator(),
    MenuItem(label: 'Receiver', disabled: true),
    MenuItem(label: receiverStatus, disabled: true),
    MenuItem.separator(),
    MenuItem.checkbox(
      key: 'launch_at_login',
      label: 'Launch at login',
      checked: launchAtLogin,
    ),
    MenuItem.separator(),
    MenuItem(key: 'quit', label: 'Quit PlayBridge'),
  ]);
}

String _trayTitle(String? title) {
  final normalized = title?.replaceAll(RegExp(r'\s+'), ' ').trim() ?? '';
  if (normalized.isEmpty) return 'Untitled media';
  if (normalized.length <= 72) return normalized;
  return '${normalized.substring(0, 71)}…';
}

/// Owns the menu-bar / system-tray icon and keeps it in sync with the
/// sender and receiver state. Wires the native menu to their actions.
class TrayController with TrayListener {
  TrayController({
    required this.player,
    required this.server,
    required this.store,
    required this.sender,
    required this.showSender,
  });

  final PlayerController player;
  final ReceiverServer server;
  final PairingStore store;
  final TvSenderController sender;
  final Future<void> Function() showSender;

  bool _launchAtLogin = false;
  bool _ready = false;
  String? _lastMenuSignature;
  AutoLaunch? _auto;
  bool _refreshing = false;
  bool _refreshPending = false;
  bool _trayConnectionPending = false;

  Future<void> init() async {
    debugPrint('[tray] init starting');
    try {
      _auto = AutoLaunch(
        bundleId: 'com.playbridge.playbridgeDesktop',
        executablePath: await AutoLaunch.resolveExecutablePath(),
      );
      _launchAtLogin = await _auto!.isEnabled();
      if (await AutoLaunch.isLikelySandboxed()) {
        debugPrint('[tray] note: app is sandboxed; LaunchAgent writes will be '
            'redirected to the container and ignored by the system. Disable '
            'app-sandbox in entitlements to use launch-at-login.');
      }
    } catch (e) {
      debugPrint('[tray] auto_launch setup failed: $e');
    }

    try {
      await trayManager.setIcon('assets/tray_icon.png', isTemplate: true);
      debugPrint('[tray] icon set from assets/tray_icon.png');
    } catch (e) {
      debugPrint(
          '[tray] setIcon(asset) failed: $e — will retry with title fallback');
      // Fallback: macOS menu bar accepts a text label when no icon is available.
      try {
        await trayManager.setTitle('PB');
        debugPrint('[tray] fell back to text title "PB"');
      } catch (e2) {
        debugPrint('[tray] setTitle fallback also failed: $e2');
      }
    }

    if (!Platform.isLinux) {
      try {
        await trayManager.setToolTip('PlayBridge');
      } catch (e) {
        debugPrint('[tray] setToolTip failed: $e');
      }
    }

    trayManager.addListener(this);
    player.addListener(_refresh);
    server.addListener(_refresh);
    sender.addListener(_onSenderChange);

    _ready = true;
    await _refresh();
    debugPrint('[tray] init complete');
  }

  Future<void> dispose() async {
    _ready = false;
    player.removeListener(_refresh);
    server.removeListener(_refresh);
    sender.removeListener(_onSenderChange);
    trayManager.removeListener(this);
    await trayManager.destroy();
  }

  Future<void> _refresh() async {
    if (!_ready) return;
    if (_refreshing) {
      _refreshPending = true;
      return;
    }
    _refreshing = true;
    try {
      do {
        _refreshPending = false;
        await _updateMenu();
      } while (_ready && _refreshPending);
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _updateMenu() async {
    // Tooltip + status string driven by current phase + player state.
    final phase = server.phase;
    final status = switch (phase) {
      PairingPhase.idle => '${store.deviceName}  ·  waiting for phone',
      PairingPhase.awaitingApproval => 'Approve connection on screen…',
      PairingPhase.awaitingCode => 'Pairing code shown on screen…',
      PairingPhase.authenticated => player.queue.isNotEmpty
          ? 'Playing: ${player.currentTitle ?? '—'}'
          : 'Paired · idle',
    };
    final saved = sender.pairedTvs;
    final nearby =
        sender.discovered.map((device) => device.identityKey).toSet();
    final active = sender.isConnected ? sender.activeTv : null;
    final senderStatus = switch (sender.state) {
      SenderConnectionState.connected =>
        active == null ? 'Connected' : 'Connected to ${active.name}',
      SenderConnectionState.selected =>
        active == null ? 'Destination selected' : 'Selected: ${active.name}',
      SenderConnectionState.connecting => 'Connecting…',
      SenderConnectionState.waitingForChallenge ||
      SenderConnectionState.waitingForCodeInput ||
      SenderConnectionState.verifyingCode =>
        'Pairing…',
      _ => 'Disconnected',
    };
    final hasRemotePlayback = _hasRemotePlayback;
    final remoteTitle = hasRemotePlayback ? sender.castingTitle : null;
    final remotePlaybackState = hasRemotePlayback ? sender.remoteState : '';
    // Position/status notifications are frequent; only rebuild for visible
    // menu changes. Include route and launch preferences so toggles refresh.
    final signature = jsonEncode([
      status,
      senderStatus,
      active?.identityKey,
      sender.castRouteThroughProxy,
      _launchAtLogin,
      hasRemotePlayback,
      remoteTitle,
      remotePlaybackState,
      for (final tv in saved)
        [tv.identityKey, tv.name, nearby.contains(tv.identityKey)],
    ]);
    if (signature == _lastMenuSignature) return;

    // tray_manager has no Linux setToolTip implementation.
    if (!Platform.isLinux) {
      try {
        await trayManager.setToolTip('PlayBridge\n$status');
      } catch (e) {
        debugPrint('[tray] setToolTip failed: $e');
      }
    }

    await trayManager.setContextMenu(buildTrayMenu(
      senderStatus: senderStatus,
      receiverStatus: status,
      savedDevices: saved,
      nearbyDeviceKeys: nearby,
      activeDeviceKey: active?.identityKey,
      canDisconnect: sender.isConnected,
      routeThroughDesktop: sender.castRouteThroughProxy,
      launchAtLogin: _launchAtLogin,
      hasRemotePlayback: hasRemotePlayback,
      remoteTitle: remoteTitle,
      remotePlaybackState: remotePlaybackState,
    ));
    _lastMenuSignature = signature;
  }

  bool get _hasRemotePlayback =>
      sender.state == SenderConnectionState.connected &&
      (sender.isCasting ||
          const {'playing', 'paused', 'buffering'}
              .contains(sender.remoteState));

  void _onSenderChange() {
    unawaited(_refresh());
    if (!_trayConnectionPending) return;
    switch (sender.state) {
      case SenderConnectionState.waitingForCodeInput:
      case SenderConnectionState.authFailed:
      case SenderConnectionState.pinMismatch:
      case SenderConnectionState.pairingDenied:
      case SenderConnectionState.error:
        _trayConnectionPending = false;
        unawaited(showSender());
        break;
      case SenderConnectionState.connected:
      case SenderConnectionState.selected:
        _trayConnectionPending = false;
        break;
      default:
        break;
    }
  }

  // ---- TrayListener ----

  @override
  void onTrayIconMouseDown() {
    // macOS and Windows send this event; Linux AppIndicator opens its menu
    // natively without sending a tray click event.
    trayManager.popUpContextMenu();
  }

  @override
  void onTrayIconRightMouseDown() {
    trayManager.popUpContextMenu();
  }

  @override
  void onTrayMenuItemClick(MenuItem menuItem) {
    final key = menuItem.key;
    if (key != null && key.startsWith('sender_device:')) {
      final identity = key.substring('sender_device:'.length);
      for (final device in sender.pairedTvs) {
        if (device.identityKey == identity) {
          if (sender.isConnected && sender.activeTv?.identityKey == identity) {
            return;
          }
          _trayConnectionPending = true;
          unawaited(_connectSaved(device));
          return;
        }
      }
      return;
    }
    switch (key) {
      case 'show':
        unawaited(_showWindow());
      case 'sender_manage':
        unawaited(showSender());
      case 'sender_disconnect':
        _trayConnectionPending = false;
        unawaited(sender.disconnect());
      case 'sender_play_pause':
        if (_hasRemotePlayback) unawaited(sender.playPause());
      case 'sender_stop':
        if (_hasRemotePlayback) unawaited(sender.stopCast());
      case 'route_direct':
        unawaited(sender.setCastRouteThroughProxy(false));
      case 'route_desktop':
        unawaited(sender.setCastRouteThroughProxy(true));
      case 'launch_at_login':
        unawaited(_toggleLaunchAtLogin());
      case 'quit':
        unawaited(quit());
    }
  }

  Future<void> _connectSaved(TvRecord device) async {
    try {
      await sender.reconnect(device);
    } on Object {
      _trayConnectionPending = false;
      debugPrint('[tray] could not reconnect saved receiver');
      await showSender();
    }
  }

  Future<void> _showWindow() async {
    await windowManager.show();
    await windowManager.focus();
  }

  Future<void> _toggleLaunchAtLogin() async {
    final auto = _auto;
    if (auto == null) return;
    try {
      if (_launchAtLogin) {
        await auto.disable();
      } else {
        await auto.enable();
      }
      _launchAtLogin = await auto.isEnabled();
      await _refresh();
    } catch (e) {
      debugPrint('[tray] launch-at-login toggle failed: $e');
    }
  }

  /// Fully exit the app (window + tray + process). Used by the tray menu and
  /// Settings → Quit.
  Future<void> quit() async {
    await windowManager.destroy();
    exit(0);
  }
}
