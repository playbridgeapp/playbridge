import 'dart:io';

import 'package:flutter/material.dart';

import 'auto_launch.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'player_engine.dart';
import 'server.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.server,
    required this.store,
    required this.player,
    required this.onNavigateToCast,
  });

  final ReceiverServer server;
  final PairingStore store;
  final PlayerController player;
  final VoidCallback onNavigateToCast;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool? _autoLaunchEnabled;
  bool _isSandboxed = false;
  AutoLaunch? _autoLaunch;

  @override
  void initState() {
    super.initState();
    _loadAutoLaunch();
  }

  Future<void> _loadAutoLaunch() async {
    final sandboxed = await AutoLaunch.isLikelySandboxed();
    if (!Platform.isWindows) {
      final execPath = await AutoLaunch.resolveExecutablePath();
      final al = AutoLaunch(
        bundleId: 'com.playbridge.desktop',
        executablePath: execPath,
      );
      final enabled = await al.isEnabled();
      if (mounted) {
        setState(() {
          _autoLaunch = al;
          _autoLaunchEnabled = enabled;
          _isSandboxed = sandboxed;
        });
      }
    } else if (mounted) {
      setState(() => _isSandboxed = false);
    }
  }

  Future<void> _toggleAutoLaunch(bool value) async {
    final al = _autoLaunch;
    if (al == null) return;
    if (value) {
      await al.enable();
    } else {
      await al.disable();
    }
    setState(() => _autoLaunchEnabled = value);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([widget.server, widget.player]),
      builder: (context, _) {
        final authed = widget.server.authedClientCount;
        final total = widget.server.connectedClientCount;
        return ListView(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
          children: [
            Text('Settings', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 24),

            // — Playback ————————————————————————————
            _Section('Playback'),
            _Tile(
              icon: Icons.settings_input_component,
              title: 'Video Engine',
              subtitle: switch (widget.player.engineType) {
                EngineType.mpvInternal => 'High performance (Integrated MPV)',
                EngineType.mpvExternal => 'Native Player (External MPV)',
                EngineType.vlcExternal => 'Native Player (External VLC)',
              },
              trailing: DropdownButton<EngineType>(
                value: widget.player.engineType,
                underline: const SizedBox(),
                dropdownColor: const Color(0xFF1E1E1E),
                items: const [
                  DropdownMenuItem(value: EngineType.mpvInternal, child: Text('MPV (Internal)')),
                  DropdownMenuItem(value: EngineType.mpvExternal, child: Text('MPV (External)')),
                  DropdownMenuItem(value: EngineType.vlcExternal, child: Text('VLC (External)')),
                ],
                onChanged: (type) async {
                  if (type != null) {
                    await widget.store.setEngineType(type);
                    await widget.player.switchEngine(type);
                  }
                },
              ),
            ),

            // — Pairing ————————————————————————————
            _Section('Pairing'),
            _Tile(
              icon: Icons.phonelink,
              title: 'Device name',
              trailing: Text(
                widget.store.deviceName,
                style: const TextStyle(color: Colors.white38, fontSize: 13),
              ),
            ),
            _Tile(
              icon: Icons.pin,
              title: 'Show pairing PIN',
              subtitle: 'Return to the Cast screen to re-pair',
              onTap: widget.onNavigateToCast,
            ),
            _Tile(
              icon: Icons.devices,
              title: 'Connected clients',
              trailing: Text(
                authed > 0 ? '$authed authenticated  ($total total)' : 'none',
                style: TextStyle(
                  color: authed > 0 ? Colors.tealAccent : Colors.white38,
                  fontSize: 13,
                ),
              ),
            ),
            if (authed > 0)
              _Tile(
                icon: Icons.logout,
                title: 'Disconnect all clients',
                subtitle: 'Forces re-authentication on the next connection',
                onTap: () async {
                  await widget.server.kickAll();
                  if (mounted) widget.onNavigateToCast();
                },
                danger: true,
              ),

            // — System ——————————————————————————————
            _Section('System'),
            if (!Platform.isWindows)
              _Tile(
                icon: Icons.launch,
                title: 'Launch at login',
                subtitle: _isSandboxed ? 'Not available in sandboxed builds' : null,
                trailing: _autoLaunchEnabled == null
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Switch(
                        value: _autoLaunchEnabled!,
                        onChanged: _isSandboxed ? null : _toggleAutoLaunch,
                      ),
              ),

            // — About ———————————————————————————————
            _Section('About'),
            _Tile(
              icon: Icons.info_outline,
              title: 'PlayBridge Desktop',
              trailing: const Text(
                'v1.0.0  ·  port 8765',
                style: TextStyle(color: Colors.white38, fontSize: 12),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _Section extends StatelessWidget {
  const _Section(this.label);
  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(0, 20, 0, 4),
      child: Text(
        label.toUpperCase(),
        style: const TextStyle(
          fontSize: 11,
          letterSpacing: 1.2,
          color: Colors.white38,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.danger = false,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      dense: true,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      leading: Icon(icon, size: 20, color: danger ? Colors.redAccent : Colors.white54),
      title: Text(
        title,
        style: TextStyle(
          color: danger ? Colors.redAccent : Colors.white,
          fontSize: 14,
        ),
      ),
      subtitle: subtitle != null
          ? Text(subtitle!, style: const TextStyle(color: Colors.white38, fontSize: 12))
          : null,
      trailing: trailing,
      onTap: onTap,
    );
  }
}
