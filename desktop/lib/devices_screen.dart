import 'package:flutter/material.dart';

import 'devices/advanced_section.dart';
import 'devices/browser_receiver_page.dart';
import 'devices/cast_files.dart';
import 'devices/extension_setup.dart';
import 'devices/pair_tv_card.dart';
import 'devices/phones_section.dart';
import 'pairing_store.dart';
import 'send_to_tv_screen.dart';
import 'tv_sender_client.dart';
import 'tv_sender_controller.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// Devices destination: TVs to send to, phones that can cast here, the TV
/// browser receiver and browser extension setup.
class DevicesScreen extends StatefulWidget {
  const DevicesScreen({
    super.key,
    required this.sender,
    required this.store,
    required this.onPairedDevicesChanged,
    required this.onOpenNowCasting,
  });

  final TvSenderController sender;
  final PairingStore store;

  /// Called after paired phones are forgotten so the receiver refreshes tokens.
  final VoidCallback onPairedDevicesChanged;

  /// Opens the remote "now casting" screen (reached only through here).
  final VoidCallback onOpenNowCasting;

  @override
  State<DevicesScreen> createState() => _DevicesScreenState();
}

class _DevicesScreenState extends State<DevicesScreen> {
  bool _browserPage = false;
  String? _pairingName;
  ExtensionSetupResult? _extensionResult;
  bool _extensionBusy = false;

  Future<void> _setUpExtension() async {
    if (_extensionBusy) return;
    setState(() => _extensionBusy = true);
    ExtensionSetupResult? result;
    try {
      result = await runExtensionSetup();
    } on Object catch (error) {
      if (mounted) {
        devicesSnack(context, 'Browser casting setup failed: $error');
      }
    }
    if (!mounted) return;
    setState(() {
      _extensionBusy = false;
      if (result != null) _extensionResult = result;
    });
    if (result != null) await showExtensionSetupDialog(context, result);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.sender,
      builder: (context, _) {
        final state = widget.sender.state;
        final pairing = state == SenderConnectionState.waitingForCodeInput ||
            state == SenderConnectionState.verifyingCode;
        return Stack(
          children: [
            Positioned.fill(
              child: ExcludeFocus(
                excluding: pairing,
                child: _browserPage ? _browserReceiver() : _overview(),
              ),
            ),
            if (pairing) ...[
              const Positioned.fill(
                child: ColoredBox(color: PbColors.scrim),
              ),
              Positioned.fill(
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: PairTvCard(
                      tvName: widget.sender.activeTv?.name ?? _pairingName,
                      isVerifying: state == SenderConnectionState.verifyingCode,
                      lastCodeWrong: widget.sender.lastSasWrong,
                      attemptsLeft: widget.sender.sasAttemptsLeft,
                      onSubmit: (code) => widget.sender.submitSasCode(code),
                      onCancel: () => widget.sender.cancelPairing(),
                    ),
                  ),
                ),
              ),
            ],
          ],
        );
      },
    );
  }

  Widget _browserReceiver() => SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(32, 28, 32, 32),
        child: BrowserReceiverPage(
          controller: widget.sender,
          onBack: () => setState(() => _browserPage = false),
        ),
      );

  Widget _overview() {
    final sender = widget.sender;
    return ListView(
      padding: const EdgeInsets.fromLTRB(32, 28, 32, 32),
      children: [
        const Text(
          'Devices',
          style: TextStyle(
            fontSize: 26,
            height: 40 / 26,
            fontWeight: FontWeight.w700,
            color: PbColors.text,
          ),
        ),
        if (sender.isCasting) ...[
          const SizedBox(height: 12),
          PbListRow(
            title: 'Now casting',
            subtitle: sender.activeTv?.name,
            trailing: const Icon(Icons.chevron_right),
            onTap: widget.onOpenNowCasting,
          ),
        ],
        const SizedBox(height: 24),
        SendToTvScreen(
          controller: sender,
          onConnectRequested: (name) => _pairingName = name,
        ),
        const SizedBox(height: 24),
        PhonesSection(
          store: widget.store,
          onChanged: widget.onPairedDevicesChanged,
        ),
        const SizedBox(height: 24),
        IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: _NavCard(
                  title: 'TV without the app',
                  subtitle: sender.browserPairingRequests.isNotEmpty
                      ? 'A TV is waiting for its code'
                      : sender.browserReceiverRunning
                          ? 'Turned on · open the address on the TV'
                          : "Use the TV's web browser as a receiver",
                  onTap: () => setState(() => _browserPage = true),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _NavCard(
                  title: 'Browser extension',
                  subtitle: describeExtensionStatus(
                    _extensionResult,
                    busy: _extensionBusy,
                  ),
                  onTap: _extensionBusy ? null : _setUpExtension,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        AdvancedSection(controller: sender),
      ],
    );
  }
}

class _NavCard extends StatelessWidget {
  const _NavCard({
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return PbPressable(
      onTap: onTap,
      semanticLabel: '$title. $subtitle',
      builder: (context, hovered, focused) => Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: hovered ? PbColors.raised : PbColors.card,
          borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
          border: focused ? Border.all(color: PbColors.accent, width: 2) : null,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 4),
            Text(
              subtitle,
              style: const TextStyle(
                fontSize: 13,
                color: PbColors.textSecondary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
