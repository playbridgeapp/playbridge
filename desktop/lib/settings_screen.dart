import 'dart:io';

import 'package:flutter/material.dart';

import 'auto_launch.dart';
import 'logging/log_store.dart';
import 'logs_screen.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'server.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.server,
    required this.store,
    required this.player,
    required this.showStats,
    required this.onNavigateToCast,
    this.onSettingsChanged,
  });

  final ReceiverServer server;
  final PairingStore store;
  final PlayerController player;
  final ValueNotifier<bool> showStats;
  final VoidCallback onNavigateToCast;
  final VoidCallback? onSettingsChanged;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool? _autoLaunchEnabled;
  bool _isSandboxed = false;
  AutoLaunch? _autoLaunch;
  late bool _loggingEnabled = LogStore.instance.enabled;

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
              icon: Icons.fullscreen,
              title: 'Full screen on play',
              subtitle:
                  'Automatically enter full screen when a video starts playing.',
              trailing: Switch(
                value: widget.store.autoFullScreen,
                onChanged: (v) async {
                  await widget.store.setAutoFullScreen(v);
                  if (mounted) setState(() {});
                },
              ),
            ),
            _Tile(
              icon: Icons.insights,
              title: 'Show playback stats',
              subtitle:
                  'Overlay dropped frames, fps, bitrate… (toggle with the I key). '
                  'Internal MPV only.',
              trailing: ValueListenableBuilder<bool>(
                valueListenable: widget.showStats,
                builder: (context, on, _) => Switch(
                  value: on,
                  onChanged: (v) => widget.showStats.value = v,
                ),
              ),
            ),
            _Tile(
              icon: Icons.history,
              title: 'Save Cast History',
              subtitle:
                  'Keep track of recently played videos and your progress.',
              trailing: Switch(
                value: widget.store.enableHistory,
                onChanged: (v) async {
                  await widget.store.setEnableHistory(v);
                  if (mounted) setState(() {});
                  widget.onSettingsChanged?.call();
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
                subtitle:
                    _isSandboxed ? 'Not available in sandboxed builds' : null,
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

            // — Diagnostics ————————————————————————
            _Section('Diagnostics'),
            _Tile(
              icon: Icons.bug_report_outlined,
              title: 'Enable logging',
              subtitle:
                  'Save app logs to this device for troubleshooting. Off by default — '
                  'logs can contain stream URLs and request headers (including '
                  'Debrid tokens), so only enable when needed.',
              trailing: Switch(
                value: _loggingEnabled,
                onChanged: (v) async {
                  await LogStore.instance.setEnabled(v);
                  if (mounted) setState(() => _loggingEnabled = v);
                },
              ),
            ),
            _Tile(
              icon: Icons.article_outlined,
              title: 'View logs',
              subtitle: 'Open the in-app log viewer',
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const LogsScreen()),
              ),
            ),

            // — About ———————————————————————————————
            _Section('About'),
            _Tile(
              icon: Icons.info_outline,
              title: 'PlayBridge Desktop',
              trailing: Text(
                'v1.0.0  ·  port ${widget.server.wssPort ?? kDefaultPort}',
                style: const TextStyle(color: Colors.white38, fontSize: 12),
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
      leading: Icon(icon,
          size: 20, color: danger ? Colors.redAccent : Colors.white54),
      title: Text(
        title,
        style: TextStyle(
          color: danger ? Colors.redAccent : Colors.white,
          fontSize: 14,
        ),
      ),
      subtitle: subtitle != null
          ? Text(subtitle!,
              style: const TextStyle(color: Colors.white38, fontSize: 12))
          : null,
      trailing: trailing,
      onTap: onTap,
    );
  }
}
