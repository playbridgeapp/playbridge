import 'package:flutter/material.dart';

import 'devices/cast_files.dart';
import 'devices/connection_banners.dart';
import 'devices/devices_common.dart';
import 'tv_connection_store.dart';
import 'tv_discovery.dart';
import 'tv_sender_client.dart';
import 'tv_sender_controller.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// Shows the browser-receiver pairing prompt.
///
/// The entered value is kept outside a [TextEditingController] so the dialog's
/// dismissal animation cannot rebuild a field whose controller was already
/// disposed.
///
/// The Devices screen now takes the code inline (see
/// `devices/browser_receiver_page.dart`); this dialog is kept as a tested
/// fallback for callers that need a modal prompt.
@visibleForTesting
Future<String?> showBrowserPairingCodeDialog(
  BuildContext context, {
  required String receiverName,
}) {
  var enteredCode = '';
  return showDialog<String>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text('Pair $receiverName'),
      content: TextField(
        autofocus: true,
        keyboardType: TextInputType.number,
        maxLength: 6,
        decoration: const InputDecoration(
          labelText: 'Code shown in the TV browser',
        ),
        onChanged: (value) => enteredCode = value,
        onSubmitted: (value) => Navigator.pop(dialogContext, value),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(dialogContext, enteredCode),
          child: const Text('Pair'),
        ),
      ],
    ),
  );
}

/// "TVs you can send to": connection notices, one row per discovered or
/// remembered TV (Connect / Pair / Disconnect / Forget) and a Search again
/// button. Not scrollable; the Devices page scrolls it.
class SendToTvScreen extends StatelessWidget {
  const SendToTvScreen({
    super.key,
    required this.controller,
    this.onConnectRequested,
  });

  final TvSenderController controller;

  /// Called with the TV's name when the user starts connecting or pairing, so
  /// the pairing-code card can name it.
  final ValueChanged<String>? onConnectRequested;

  static bool _isBusy(SenderConnectionState s) =>
      s == SenderConnectionState.connecting ||
      s == SenderConnectionState.waitingForChallenge ||
      s == SenderConnectionState.waitingForCodeInput ||
      s == SenderConnectionState.verifyingCode;

  bool _isActive(String identity) =>
      controller.activeTv?.identityKey == identity &&
      (controller.isConnected || controller.reconnectStatus != null);

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => _build(context),
    );
  }

  Widget _build(BuildContext context) {
    final discovered = controller.discovered;
    final paired = controller.pairedTvs;
    final discoveredKeys = discovered.map((d) => d.identityKey).toSet();
    final pairedKeys = paired.map((p) => p.identityKey).toSet();
    // Paired TVs not currently visible on the network stay listed so they can
    // still be reconnected or forgotten.
    final offline =
        paired.where((p) => !discoveredKeys.contains(p.identityKey)).toList();

    // Same three groups as the phone apps: Saved, Nearby, Not available now.
    final saved = <({bool active, Widget row})>[
      for (final tv in discovered)
        if (pairedKeys.contains(tv.identityKey) || _isActive(tv.identityKey))
          (
            active: _isActive(tv.identityKey),
            row: _discoveredRow(
              context,
              tv,
              isPaired: pairedKeys.contains(tv.identityKey),
            ),
          ),
      // A TV that is reconnecting stays listed even while unseen.
      for (final tv in offline)
        if (_isActive(tv.identityKey))
          (active: true, row: _offlineRow(context, tv)),
    ]..sort((a, b) => (a.active ? 0 : 1).compareTo(b.active ? 0 : 1));
    final nearby = [
      for (final tv in discovered)
        if (!pairedKeys.contains(tv.identityKey) && !_isActive(tv.identityKey))
          _discoveredRow(context, tv, isPaired: false),
    ];
    final notFound = [
      for (final tv in offline)
        if (!_isActive(tv.identityKey)) _offlineRow(context, tv),
    ];

    final scanning = controller.isScanning;
    final error = controller.lastPlaybackError;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PbSectionHeading(
          'TVs you can send to',
          trailing: PbButton(
            label: scanning ? 'Searching…' : 'Search again',
            variant: PbButtonVariant.secondary,
            onPressed: scanning ? null : controller.rescan,
          ),
        ),
        if (describeConnection(
              state: controller.state,
              activeTv: controller.activeTv,
              reconnectStatus: controller.reconnectStatus,
            ) !=
            null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: ConnectionBanner(
              state: controller.state,
              activeTv: controller.activeTv,
              reconnectStatus: controller.reconnectStatus,
            ),
          ),
        if (error != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: PlaybackErrorBanner(
              message: error,
              onDismiss: controller.clearPlaybackError,
            ),
          ),
        if (saved.isEmpty && nearby.isEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: DevicesHint(
              scanning
                  ? 'Searching for TVs on your network…'
                  : 'No TVs found right now. Make sure the TV is on and on '
                      'the same network, then choose Search again.',
              busy: scanning,
            ),
          )
        else ...[
          if (saved.isNotEmpty) const _GroupLabel('Saved'),
          for (final r in saved)
            Padding(padding: const EdgeInsets.only(top: 6), child: r.row),
          if (nearby.isNotEmpty) const _GroupLabel('Nearby'),
          for (final row in nearby)
            Padding(padding: const EdgeInsets.only(top: 6), child: row),
        ],
        if (notFound.isNotEmpty) _NotFoundDevices(rows: notFound),
        if (controller.isConnected)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Align(
              alignment: Alignment.centerLeft,
              child: PbButton(
                label: 'Cast files…',
                icon: Icons.video_file,
                variant: PbButtonVariant.secondary,
                onPressed: () => pickAndCastFiles(context, controller),
              ),
            ),
          ),
      ],
    );
  }

  Widget _activeRow(String name, TvProtocol kind) {
    final connected = controller.isConnected;
    final reconnecting = controller.reconnectStatus != null;
    final status = controller.state == SenderConnectionState.selected
        ? 'selected'
        : (connected ? 'connected' : 'reconnecting');
    return DeviceRow(
      title: name,
      subtitle: status,
      protocol: kind,
      selected: true,
      actions: [
        PbButton(
          label: reconnecting && !connected ? 'Cancel' : 'Disconnect',
          variant: PbButtonVariant.secondary,
          onPressed: controller.disconnect,
        ),
      ],
    );
  }

  Widget _discoveredRow(
    BuildContext context,
    DiscoveredTv tv, {
    required bool isPaired,
  }) {
    if (_isActive(tv.identityKey)) {
      return _activeRow(tv.name, tv.protocol);
    }
    final busy = _isBusy(controller.state);
    final insecure = tv.protocol == TvProtocol.playBridge && tv.wssPort == null;
    final String subtitle;
    if (tv.protocol == TvProtocol.webBrowser) {
      subtitle = 'paired for this session';
    } else {
      subtitle = 'on your network';
    }
    final connectable = controller.canConnectTo(tv);
    void connect() {
      onConnectRequested?.call(tv.name);
      controller.connectToDiscovered(tv);
    }

    return DeviceRow(
      title: tv.name,
      subtitle: subtitle,
      protocol: tv.protocol,
      warning: insecure ? 'not encrypted' : null,
      actions: [
        if (!connectable)
          const PbButton(
            label: 'Unavailable',
            variant: PbButtonVariant.secondary,
            onPressed: null,
          )
        else
          PbButton(
            label: controller.needsPairing(tv) ? 'Pair' : 'Connect',
            variant: PbButtonVariant.secondary,
            onPressed: busy ? null : connect,
          ),
        if (isPaired && connectable)
          PbButton(
            label: 'Forget',
            variant: PbButtonVariant.quiet,
            onPressed: () => controller.forget(tv.uuid, protocol: tv.protocol),
          ),
      ],
    );
  }

  Widget _offlineRow(BuildContext context, TvRecord tv) {
    if (_isActive(tv.identityKey)) {
      return _activeRow(tv.name, tv.protocol);
    }
    final busy = _isBusy(controller.state);
    return DeviceRow(
      title: tv.name,
      subtitle: 'not found right now',
      protocol: tv.protocol,
      actions: [
        PbButton(
          label: 'Reconnect',
          variant: PbButtonVariant.secondary,
          onPressed: busy
              ? null
              : () {
                  onConnectRequested?.call(tv.name);
                  controller.reconnect(tv);
                },
        ),
        PbButton(
          label: 'Forget',
          variant: PbButtonVariant.quiet,
          onPressed: () => controller.forget(tv.uuid, protocol: tv.protocol),
        ),
      ],
    );
  }
}

/// Small heading above a group of device rows.
class _GroupLabel extends StatelessWidget {
  const _GroupLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 14, left: 4),
        child: Text(
          text,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: PbColors.textSecondary,
          ),
        ),
      );
}

/// Saved TVs that are not on the network right now, folded behind one line.
class _NotFoundDevices extends StatefulWidget {
  const _NotFoundDevices({required this.rows});

  final List<Widget> rows;

  @override
  State<_NotFoundDevices> createState() => _NotFoundDevicesState();
}

class _NotFoundDevicesState extends State<_NotFoundDevices> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final n = widget.rows.length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Align(
            alignment: Alignment.centerLeft,
            child: PbButton(
              label: 'Not available now · $n',
              icon: _expanded ? Icons.expand_less : Icons.expand_more,
              variant: PbButtonVariant.quiet,
              onPressed: () => setState(() => _expanded = !_expanded),
            ),
          ),
        ),
        if (_expanded)
          for (final row in widget.rows)
            Padding(padding: const EdgeInsets.only(top: 6), child: row),
      ],
    );
  }
}
