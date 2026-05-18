import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import 'auto_launch.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'server.dart';

/// Owns the menu-bar / system-tray icon and keeps it in sync with the
/// receiver's state. Wires "show window", "launch at login" and "quit"
/// menu items to their actions.
class TrayController with TrayListener {
  TrayController({
    required this.player,
    required this.server,
    required this.store,
  });

  final PlayerController player;
  final ReceiverServer server;
  final PairingStore store;

  bool _launchAtLogin = false;
  bool _ready = false;
  AutoLaunch? _auto;

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
      debugPrint('[tray] setIcon(asset) failed: $e — will retry with title fallback');
      // Fallback: macOS menu bar accepts a text label when no icon is available.
      try {
        await trayManager.setTitle('PB');
        debugPrint('[tray] fell back to text title "PB"');
      } catch (e2) {
        debugPrint('[tray] setTitle fallback also failed: $e2');
      }
    }

    try {
      await trayManager.setToolTip('PlayBridge');
    } catch (e) {
      debugPrint('[tray] setToolTip failed: $e');
    }

    trayManager.addListener(this);
    player.addListener(_refresh);
    server.addListener(_refresh);

    _ready = true;
    await _refresh();
    debugPrint('[tray] init complete');
  }

  Future<void> dispose() async {
    player.removeListener(_refresh);
    server.removeListener(_refresh);
    trayManager.removeListener(this);
    await trayManager.destroy();
  }

  Future<void> _refresh() async {
    if (!_ready) return;

    // Tooltip + status string driven by current phase + player state.
    final phase = server.phase;
    final status = switch (phase) {
      PairingPhase.idle => '${store.deviceName}  ·  waiting for phone',
      PairingPhase.awaitingApproval => 'Approve connection on screen…',
      PairingPhase.authenticated =>
        player.queue.isNotEmpty
            ? 'Playing: ${player.currentTitle ?? '—'}'
            : 'Paired · idle',
    };
    await trayManager.setToolTip('PlayBridge\n$status');

    final menu = Menu(items: [
      MenuItem(label: status, disabled: true),
      MenuItem.separator(),
      MenuItem(key: 'show', label: 'Show window'),
      MenuItem.checkbox(
        key: 'launch_at_login',
        label: 'Launch at login',
        checked: _launchAtLogin,
      ),
      MenuItem.separator(),
      MenuItem(key: 'quit', label: 'Quit PlayBridge'),
    ]);
    await trayManager.setContextMenu(menu);
  }

  // ---- TrayListener ----

  @override
  void onTrayIconMouseDown() {
    // Single click on the tray icon shows the window.
    _showWindow();
  }

  @override
  void onTrayIconRightMouseDown() {
    trayManager.popUpContextMenu();
  }

  @override
  void onTrayMenuItemClick(MenuItem menuItem) {
    switch (menuItem.key) {
      case 'show':
        _showWindow();
      case 'launch_at_login':
        _toggleLaunchAtLogin();
      case 'quit':
        _quit();
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

  Future<void> _quit() async {
    await windowManager.destroy();
    exit(0);
  }
}
