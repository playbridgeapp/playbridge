import 'history_store.dart';
import 'dart:io';

import 'package:flutter/material.dart';

import 'auto_launch.dart';
import 'keyboard_shortcuts_sheet.dart';
import 'logging/log_store.dart';
import 'logs_screen.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'receiver_server.dart';
import 'settings/settings_parts.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';
import 'update/app_version.dart';
import 'update/update_checker.dart';

/// Settings, split into General, Playback, Advanced and About tabs.
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
    this.autoLaunchForTest,
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

  /// Replaces the real launch-at-login handler in widget tests.
  @visibleForTesting
  final AutoLaunch? autoLaunchForTest;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

const _tabLabels = ['General', 'Playback', 'Advanced', 'About'];

class _SettingsScreenState extends State<SettingsScreen> {
  int _tab = 0;
  bool? _autoLaunchEnabled;
  bool _isSandboxed = false;
  AutoLaunch? _autoLaunch;
  late bool _loggingEnabled = LogStore.instance.enabled;

  PairingStore get _store => widget.store;

  @override
  void initState() {
    super.initState();
    _loadAutoLaunch();
  }

  Future<void> _loadAutoLaunch() async {
    final sandboxed = await AutoLaunch.isLikelySandboxed();
    if (!Platform.isWindows) {
      final al = widget.autoLaunchForTest ??
          AutoLaunch(executablePath: await AutoLaunch.resolveExecutablePath());
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
    if (mounted) setState(() => _autoLaunchEnabled = value);
  }

  /// Runs [change] on the store, then refreshes this screen.
  Future<void> _update(Future<void> Function() change,
      {bool notify = false}) async {
    await change();
    if (mounted) setState(() {});
    if (notify) widget.onSettingsChanged?.call();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([widget.server, widget.player]),
      builder: (context, _) {
        final body = switch (_tab) {
          0 => _general(context),
          1 => _playback(context),
          2 => _advanced(context),
          _ => _about(context),
        };
        return SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Settings',
                style: TextStyle(fontSize: 26, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 20),
              PbSegmentedTabs(
                labels: _tabLabels,
                selectedIndex: _tab,
                onChanged: (i) => setState(() => _tab = i),
              ),
              const SizedBox(height: 20),
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 760),
                child: body,
              ),
            ],
          ),
        );
      },
    );
  }

  // ── General ────────────────────────────────────────────────────────────

  Widget _general(BuildContext context) {
    return SettingsGroup(
      children: [
        PbValueRow(
          title: 'This computer’s name',
          subtitle: 'What phones see in their list of devices',
          value: _store.deviceName,
        ),
        if (!Platform.isWindows)
          PbSwitchRow(
            title: 'Open when I log in',
            subtitle: _isSandboxed
                ? 'Not available in sandboxed builds'
                : 'Starts in the menu bar, ready for casts',
            value: _autoLaunchEnabled ?? false,
            onChanged: (_autoLaunchEnabled == null || _isSandboxed)
                ? null
                : _toggleAutoLaunch,
          ),
        PbValueRow(
          title: 'When I close the window',
          subtitle: 'PlayBridge stays in the menu bar either way',
          value: _store.pauseOnWindowHide ? 'Pause' : 'Keep playing',
          onTap: () async {
            final v = await showSettingsChoiceDialog<bool>(
              context,
              title: 'When I close the window',
              message: 'PlayBridge stays in the menu bar either way.',
              options: const [
                (false, 'Keep playing'),
                (true, 'Pause'),
              ],
              selected: _store.pauseOnWindowHide,
            );
            if (v != null) await _update(() => _store.setPauseOnWindowHide(v));
          },
        ),
        const SettingsSectionHeading('History'),
        // Hidden until a phone can supply the Recent list; nothing is recorded
        // on this computer before then.
        if (kPhoneRecentsSupported)
          PbSwitchRow(
            title: 'Save cast history',
            subtitle: 'Keep recently played media on this computer. Casts that '
                'ask for no history are always left out',
            value: _store.enableHistory,
            onChanged: (v) =>
                _update(() => _store.setEnableHistory(v), notify: true),
          ),
        PbSwitchRow(
          title: 'Keep my casts out of the TV’s history',
          subtitle: 'New casts you send to a PlayBridge Android TV or '
              'computer are not added to its history. Existing history '
              'stays as it is',
          value: _store.preventReceiverHistory,
          onChanged: (v) => _update(() => _store.setPreventReceiverHistory(v)),
        ),
      ],
    );
  }

  // ── Playback ───────────────────────────────────────────────────────────

  static String _minutesLabel(int minutes) {
    if (minutes < 60) return '$minutes minutes';
    final hours = minutes / 60;
    final text = minutes % 60 == 0 ? '${minutes ~/ 60}' : hours.toString();
    return hours == 1 ? '1 hour' : '$text hours';
  }

  static String _secondsLabel(int seconds) {
    if (seconds < 60) return '$seconds seconds';
    final m = seconds ~/ 60;
    return m == 1 ? '1 minute' : '$m minutes';
  }

  Widget _playback(BuildContext context) {
    final stillOn = _store.stillWatchingEnabled;
    return SettingsGroup(
      children: [
        PbSwitchRow(
          title: 'Go full screen when a video starts',
          subtitle: 'Useful when this computer is connected to a TV or '
              'projector',
          value: _store.autoFullScreen,
          onChanged: (v) => _update(() => _store.setAutoFullScreen(v)),
        ),
        ValueListenableBuilder<bool>(
          valueListenable: widget.showStats,
          builder: (context, on, _) => PbSwitchRow(
            title: 'Show playback stats',
            subtitle: 'Dropped frames, frame rate and bitrate over the '
                'video. Press I to toggle. Built-in player only',
            value: on,
            onChanged: (v) => widget.showStats.value = v,
          ),
        ),
        PbSwitchRow(
          title: 'Ask “Still watching?”',
          subtitle: 'Pauses after a long stretch and stops if nobody answers',
          value: stillOn,
          onChanged: (v) =>
              _update(() => _store.setStillWatchingEnabled(v), notify: true),
        ),
        Opacity(
          opacity: stillOn ? 1 : 0.5,
          child: PbValueRow(
            title: 'Ask after',
            subtitle: 'Only time spent playing counts',
            value: _minutesLabel(_store.stillWatchingThresholdMinutes),
            onTap: stillOn
                ? () async {
                    final v = await showSettingsChoiceDialog<int>(
                      context,
                      title: 'Ask after',
                      message: 'Only time spent playing counts.',
                      options: [
                        for (final m in PairingStore.stillWatchingPresets)
                          (m, _minutesLabel(m)),
                      ],
                      selected: _store.stillWatchingThresholdMinutes,
                    );
                    if (v != null) {
                      await _update(
                          () => _store.setStillWatchingThresholdMinutes(v),
                          notify: true);
                    }
                  }
                : null,
          ),
        ),
        Opacity(
          opacity: stillOn ? 1 : 0.5,
          child: PbValueRow(
            title: 'Wait for an answer',
            subtitle: 'How long before playback stops',
            value: _secondsLabel(_store.stillWatchingResponseSeconds),
            onTap: stillOn
                ? () async {
                    final v = await showSettingsChoiceDialog<int>(
                      context,
                      title: 'Wait for an answer',
                      message: 'How long before playback stops.',
                      options: [
                        for (final s
                            in PairingStore.stillWatchingResponsePresets)
                          (s, _secondsLabel(s)),
                      ],
                      selected: _store.stillWatchingResponseSeconds,
                    );
                    if (v != null) {
                      await _update(
                          () => _store.setStillWatchingResponseSeconds(v),
                          notify: true);
                    }
                  }
                : null,
          ),
        ),
        const SettingsSectionHeading('Intros, recaps and credits'),
        SettingsChipsRow(
          title: 'Skip without asking',
          subtitle: 'Jumps past these parts without showing the Skip button. '
              'Works for films and episodes that PlayBridge can identify',
          chips: [
            SettingsChip(
              'Intros',
              _store.autoSkipIntro,
              (v) => _update(() => _store.setAutoSkipIntro(v)),
            ),
            SettingsChip(
              'Recaps',
              _store.autoSkipRecap,
              (v) => _update(() => _store.setAutoSkipRecap(v)),
            ),
            SettingsChip(
              'Credits',
              _store.autoSkipOutro,
              (v) => _update(() => _store.setAutoSkipOutro(v)),
            ),
          ],
        ),
      ],
    );
  }

  // ── Advanced ───────────────────────────────────────────────────────────

  static String _proxyLabel(StreamProxyMode m) => switch (m) {
        StreamProxyMode.off => 'Never',
        StreamProxyMode.auto => 'When needed',
        StreamProxyMode.always => 'Always',
      };

  static String _providerLabel(String p) => switch (p) {
        'introdb' => 'IntroDB',
        'theintrodb' => 'TheIntroDB',
        _ => 'IntroDB and TheIntroDB',
      };

  Widget _advanced(BuildContext context) {
    final authed = widget.server.authedClientCount;
    final total = widget.server.connectedClientCount;
    return SettingsGroup(
      children: [
        const SettingsIntro(
            'For troubleshooting. Most people never need these.'),
        PbValueRow(
          title: 'Load streams through this computer',
          subtitle: 'Helps with sites that block direct playback',
          value: _proxyLabel(_store.streamProxyMode),
          onTap: () async {
            final v = await showSettingsChoiceDialog<StreamProxyMode>(
              context,
              title: 'Load streams through this computer',
              message: 'Never: always play directly. When needed: only after '
                  'a direct stream fails. Always: every video from a browser.',
              options: [
                for (final m in StreamProxyMode.values) (m, _proxyLabel(m)),
              ],
              selected: _store.streamProxyMode,
            );
            if (v != null) await _update(() => _store.setStreamProxyMode(v));
          },
        ),
        PbSwitchRow(
          title: 'Pick the stream quality before playing',
          subtitle: 'Starts faster. Turn off to let the player choose',
          value: widget.player.preselectHlsQuality,
          onChanged: (v) async {
            widget.player.setPreselectHlsQuality(v);
            await _update(() => _store.setPreselectHlsQuality(v));
          },
        ),
        PbValueRow(
          title: 'Where skip times come from',
          subtitle: 'The services asked for intro, recap and credit times',
          value: _providerLabel(_store.skipSegmentsProvider),
          onTap: () async {
            final v = await showSettingsChoiceDialog<String>(
              context,
              title: 'Where skip times come from',
              options: const [
                ('both', 'IntroDB and TheIntroDB'),
                ('introdb', 'IntroDB'),
                ('theintrodb', 'TheIntroDB'),
              ],
              selected: _store.skipSegmentsProvider,
            );
            if (v != null) {
              await _update(() => _store.setSkipSegmentsProvider(v));
            }
          },
        ),
        PbValueRow(
          title: 'Connected phones',
          value: authed > 0 ? '$authed signed in ($total connected)' : 'None',
        ),
        if (authed > 0)
          SettingsActionRow(
            title: 'Disconnect every phone',
            subtitle: 'They have to sign in again the next time they connect',
            danger: true,
            onTap: () async {
              await widget.server.kickAll();
              if (mounted) widget.onNavigateToCast();
            },
          ),
        const SettingsSectionHeading('Bug reports'),
        PbSwitchRow(
          title: 'Keep a log',
          subtitle: 'Saves app logs and memory use every 30 seconds on this '
              'computer. Logs can include video addresses and account tokens. '
              'Turn on only while reproducing a problem',
          value: _loggingEnabled,
          onChanged: (v) async {
            await LogStore.instance.setEnabled(v);
            if (mounted) setState(() => _loggingEnabled = v);
          },
        ),
        SettingsActionRow(
          title: 'View logs',
          subtitle: 'Open the log viewer',
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const LogsScreen()),
          ),
        ),
      ],
    );
  }

  // ── About ──────────────────────────────────────────────────────────────

  static String _updateStatus(UpdateState s, int port) => switch (s) {
        UpdateIdle() => 'Receiving on port $port',
        UpdateChecking() => 'Checking for updates…',
        UpdateUpToDate() => 'Up to date',
        UpdateAvailable(:final info) => 'Version ${info.version} is available',
        UpdateDownloading(:final info) => 'Downloading ${info.version}…',
        UpdateInstalling(:final info) =>
          'Installing ${info.version}, then restarting',
        UpdateError(:final message) => message,
      };

  Widget _about(BuildContext context) {
    final port = widget.server.wssPort ?? kDefaultPort;
    return SettingsGroup(
      children: [
        ListenableBuilder(
          listenable: widget.updateChecker,
          builder: (context, _) {
            final state = widget.updateChecker.state;
            final busy = state is UpdateChecking ||
                state is UpdateDownloading ||
                state is UpdateInstalling;
            return Container(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 20),
              decoration: BoxDecoration(
                color: PbColors.card,
                borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
              ),
              child: Wrap(
                alignment: WrapAlignment.spaceBetween,
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 24,
                runSpacing: 14,
                children: [
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const _AppMark(),
                      const SizedBox(width: 16),
                      ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 360),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Text(
                              'PlayBridge Desktop $kAppVersion',
                              style: TextStyle(
                                  fontSize: 18, fontWeight: FontWeight.w600),
                            ),
                            Text(
                              _updateStatus(state, port),
                              style: TextStyle(
                                fontSize: 13,
                                color: state is UpdateError
                                    ? PbColors.error
                                    : PbColors.textSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  PbButton(
                    label: 'Check for updates',
                    onPressed: busy
                        ? null
                        : () => widget.updateChecker.check(manual: true),
                  ),
                ],
              ),
            );
          },
        ),
        PbValueRow(
          title: 'Port phones connect to',
          value: '$port',
        ),
        SettingsActionRow(
          title: 'Keyboard shortcuts',
          trailing: const Text('?',
              style: TextStyle(color: PbColors.textSecondary, fontSize: 14)),
          onTap: () => showKeyboardShortcutsSheet(context),
        ),
        if (widget.onQuit != null) ...[
          const SizedBox(height: 10),
          SettingsActionRow(
            title: 'Quit PlayBridge',
            subtitle: 'Phones can’t cast here until you open it again. '
                'Closing the window does not quit',
            danger: true,
            onTap: () => _confirmQuit(context),
          ),
        ],
      ],
    );
  }

  Future<void> _confirmQuit(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      barrierColor: PbColors.scrim,
      builder: (ctx) => Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Quit PlayBridge?',
                    style:
                        TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                const SizedBox(height: 8),
                const Text(
                  'The desktop receiver will stop. Phones won’t be able to '
                  'cast here until you open PlayBridge again.',
                  style: TextStyle(color: PbColors.textSecondary),
                ),
                const SizedBox(height: 20),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    PbButton(
                      label: 'Cancel',
                      variant: PbButtonVariant.quiet,
                      onPressed: () => Navigator.of(ctx).pop(false),
                    ),
                    const SizedBox(width: 8),
                    PbButton(
                      label: 'Quit',
                      onPressed: () => Navigator.of(ctx).pop(true),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (ok == true) widget.onQuit?.call();
  }
}

class _AppMark extends StatelessWidget {
  const _AppMark();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        color: PbColors.accent,
        borderRadius: BorderRadius.circular(14),
      ),
      child: const Icon(Icons.play_arrow_rounded,
          size: 30, color: PbColors.background),
    );
  }
}
