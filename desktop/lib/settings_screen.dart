import 'dart:io';

import 'package:flutter/material.dart';

import 'auto_launch.dart';
import 'keyboard_shortcuts_sheet.dart';
import 'logging/log_store.dart';
import 'logs_screen.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'receiver_server.dart';
import 'update/app_version.dart';
import 'update/update_checker.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.server,
    required this.store,
    required this.player,
    required this.showStats,
    required this.updateChecker,
    required this.onNavigateToCast,
    this.onSettingsChanged,
    this.onQuit,
  });

  final ReceiverServer server;
  final PairingStore store;
  final PlayerController player;
  final ValueNotifier<bool> showStats;
  final UpdateChecker updateChecker;
  final VoidCallback onNavigateToCast;
  final VoidCallback? onSettingsChanged;

  /// Fully quit the app (same as tray → Quit). Red window X only hides.
  final VoidCallback? onQuit;

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
              icon: Icons.visibility_off_outlined,
              title: 'Pause when window is hidden',
              subtitle:
                  'Red close button hides to the menu bar. On: pause playback. '
                  'Off: keep playing in the background (default).',
              trailing: Switch(
                value: widget.store.pauseOnWindowHide,
                onChanged: (v) async {
                  await widget.store.setPauseOnWindowHide(v);
                  if (mounted) setState(() {});
                },
              ),
            ),
            _Tile(
              icon: Icons.high_quality_outlined,
              title: 'Preselect HLS quality',
              subtitle:
                  'Choose the highest compatible H.264 rendition before playback '
                  'for faster startup. Off: let MPV handle the master playlist.',
              trailing: Switch(
                value: widget.player.preselectHlsQuality,
                onChanged: (v) async {
                  widget.player.setPreselectHlsQuality(v);
                  await widget.store.setPreselectHlsQuality(v);
                },
              ),
            ),
            _Tile(
              icon: Icons.network_ping,
              title: 'Stream proxy mode',
              subtitle:
                  'Proxy protected CDN streams through local FFmpeg AVIO. '
                  'Auto: only on failure. Always: for all browser HLS casts.',
              trailing: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.06),
                  border: Border.all(color: Colors.white12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<StreamProxyMode>(
                    value: widget.store.streamProxyMode,
                    borderRadius: BorderRadius.circular(10),
                    dropdownColor: const Color(0xFF202126),
                    icon: const Icon(Icons.expand_more, size: 18),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                    ),
                    onChanged: (v) async {
                      if (v == null) return;
                      await widget.store.setStreamProxyMode(v);
                      if (mounted) setState(() {});
                    },
                    items: const [
                      DropdownMenuItem(
                        value: StreamProxyMode.off,
                        child: Text('Off'),
                      ),
                      DropdownMenuItem(
                        value: StreamProxyMode.auto,
                        child: Text('Auto'),
                      ),
                      DropdownMenuItem(
                        value: StreamProxyMode.always,
                        child: Text('Always'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            _Tile(
              icon: Icons.bedtime_outlined,
              title: 'Still watching reminder',
              subtitle:
                  'Pause after continuous viewing and stop if nobody responds.',
              trailing: Switch(
                value: widget.store.stillWatchingEnabled,
                onChanged: (v) async {
                  await widget.store.setStillWatchingEnabled(v);
                  if (mounted) setState(() {});
                  widget.onSettingsChanged?.call();
                },
              ),
            ),
            _Tile(
              icon: Icons.timer_outlined,
              title: 'Reminder interval',
              subtitle: 'Only active playback counts toward this time.',
              trailing: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.06),
                  border: Border.all(color: Colors.white12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<int>(
                    value: widget.store.stillWatchingThresholdMinutes,
                    borderRadius: BorderRadius.circular(10),
                    dropdownColor: const Color(0xFF202126),
                    icon: const Icon(Icons.expand_more, size: 18),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                    ),
                    onChanged: widget.store.stillWatchingEnabled
                        ? (v) async {
                            if (v == null) return;
                            await widget.store
                                .setStillWatchingThresholdMinutes(v);
                            if (mounted) setState(() {});
                            widget.onSettingsChanged?.call();
                          }
                        : null,
                    items: PairingStore.stillWatchingPresets
                        .map((minutes) => DropdownMenuItem(
                              value: minutes,
                              child: Text(minutes < 60
                                  ? '$minutes min'
                                  : '${minutes ~/ 60}${minutes % 60 == 0 ? '' : '.5'} hr'),
                            ))
                        .toList(),
                  ),
                ),
              ),
            ),
            _Tile(
              icon: Icons.hourglass_bottom,
              title: 'Response time',
              subtitle: 'Time to respond before playback stops.',
              trailing: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.06),
                  border: Border.all(color: Colors.white12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<int>(
                    value: widget.store.stillWatchingResponseSeconds,
                    borderRadius: BorderRadius.circular(10),
                    dropdownColor: const Color(0xFF202126),
                    icon: const Icon(Icons.expand_more, size: 18),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                    ),
                    onChanged: widget.store.stillWatchingEnabled
                        ? (v) async {
                            if (v == null) return;
                            await widget.store
                                .setStillWatchingResponseSeconds(v);
                            if (mounted) setState(() {});
                            widget.onSettingsChanged?.call();
                          }
                        : null,
                    items: PairingStore.stillWatchingResponsePresets
                        .map((seconds) => DropdownMenuItem(
                              value: seconds,
                              child: Text(seconds < 60
                                  ? '$seconds sec'
                                  : '${seconds ~/ 60} min'),
                            ))
                        .toList(),
                  ),
                ),
              ),
            ),
            _Tile(
              icon: Icons.keyboard,
              title: 'Keyboard shortcuts',
              subtitle: 'Space, arrows, F, I, and gestures — press ? anytime.',
              onTap: () => showKeyboardShortcutsSheet(context),
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
                  'Keep recently played media on this Desktop. Casts requesting no history are always excluded.',
              trailing: Switch(
                value: widget.store.enableHistory,
                onChanged: (v) async {
                  await widget.store.setEnableHistory(v);
                  if (mounted) setState(() {});
                  widget.onSettingsChanged?.call();
                },
              ),
            ),

            _Section('Sending'),
            _Tile(
              icon: Icons.history_toggle_off,
              title: 'Prevent receiver cast history',
              subtitle:
                  'Keep new casts out of history on updated PlayBridge Android TV and Desktop receivers. Existing history stays unchanged.',
              trailing: Switch(
                value: widget.store.preventReceiverHistory,
                onChanged: (value) async {
                  await widget.store.setPreventReceiverHistory(value);
                  if (mounted) setState(() {});
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
                'v$kAppVersion  ·  port ${widget.server.wssPort ?? kDefaultPort}',
                style: const TextStyle(color: Colors.white38, fontSize: 12),
              ),
            ),
            ListenableBuilder(
              listenable: widget.updateChecker,
              builder: (context, _) {
                final checking = widget.updateChecker.state is UpdateChecking;
                return _Tile(
                  icon: Icons.system_update_alt,
                  title: 'Check for updates',
                  subtitle: 'Downloads and installs the new version in place, '
                      'then restarts the app.',
                  trailing: checking
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : null,
                  onTap: checking
                      ? null
                      : () => widget.updateChecker.check(manual: true),
                );
              },
            ),
            if (widget.onQuit != null) ...[
              const SizedBox(height: 12),
              _Tile(
                icon: Icons.power_settings_new,
                title: 'Quit PlayBridge',
                subtitle:
                    'Stop the receiver and exit. The red window button only '
                    'hides to the menu bar.',
                danger: true,
                onTap: () => _confirmQuit(context),
              ),
            ],
          ],
        );
      },
    );
  }

  Future<void> _confirmQuit(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Quit PlayBridge?'),
        content: const Text(
          'The desktop receiver will stop. Phones won’t be able to cast here '
          'until you open PlayBridge again.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Quit'),
          ),
        ],
      ),
    );
    if (ok == true) widget.onQuit?.call();
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
