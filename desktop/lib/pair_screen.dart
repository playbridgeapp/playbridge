import 'dart:async';

import 'package:flutter/material.dart';

import 'ui/pb_widgets.dart';

import 'pairing_store.dart';
import 'receiver_server.dart';
import 'ui/pb_theme.dart';

/// The receiver gives a phone this long to enter the code (see the Rust
/// receiver's default pairing timeout); the card counts down from it.
const int kPairingExpirySeconds = 60;

/// Pairing request shown over whatever screen is current: a scrim plus the card.
/// Renders nothing unless a phone is asking to pair or being connected.
class PairRequestOverlay extends StatelessWidget {
  const PairRequestOverlay({
    super.key,
    required this.phase,
    required this.pendingRequest,
    required this.onDeny,
  });

  final PairingPhase phase;
  final PendingPairingRequest? pendingRequest;
  final VoidCallback onDeny;

  @override
  Widget build(BuildContext context) {
    final awaitingCode = phase == PairingPhase.awaitingCode;
    final awaitingApproval = phase == PairingPhase.awaitingApproval;
    if (!awaitingCode && !awaitingApproval) return const SizedBox.shrink();
    return Stack(
      fit: StackFit.expand,
      children: [
        const ModalBarrier(color: PbColors.scrim, dismissible: false),
        Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(32),
            child: awaitingCode
                ? PairRequestCard(request: pendingRequest, onDeny: onDeny)
                : const _PairConnectingCard(),
          ),
        ),
      ],
    );
  }
}

/// "Phone wants to pair" with the six-digit code and a "Don't allow" button.
class PairRequestCard extends StatefulWidget {
  const PairRequestCard({
    super.key,
    required this.request,
    required this.onDeny,
    this.expirySeconds = kPairingExpirySeconds,
  });

  final PendingPairingRequest? request;
  final VoidCallback onDeny;
  final int expirySeconds;

  @override
  State<PairRequestCard> createState() => _PairRequestCardState();
}

class _PairRequestCardState extends State<PairRequestCard> {
  Timer? _timer;
  late int _remaining;

  @override
  void initState() {
    super.initState();
    _startCountdown();
  }

  @override
  void didUpdateWidget(PairRequestCard old) {
    super.didUpdateWidget(old);
    if (old.request?.connectionId != widget.request?.connectionId) {
      _startCountdown();
    }
  }

  void _startCountdown() {
    _timer?.cancel();
    _remaining = widget.expirySeconds;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() => _remaining = (_remaining - 1).clamp(0, 1 << 30));
      if (_remaining == 0) _timer?.cancel();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final name = widget.request?.deviceName;
    final sas = widget.request?.sasCode ?? '000000';
    final digits = sas.split('');
    final spoken =
        sas.length == 6 ? '${sas.substring(0, 3)} ${sas.substring(3)}' : sas;
    return Semantics(
      container: true,
      label: 'Pairing request',
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Container(
          padding: const EdgeInsets.fromLTRB(32, 36, 32, 28),
          decoration: BoxDecoration(
            color: PbColors.card,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: PbColors.outline),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                name == null || name.isEmpty
                    ? 'A phone wants to pair'
                    : '$name wants to pair',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w700,
                  color: PbColors.text,
                ),
              ),
              const SizedBox(height: 6),
              const Text(
                'Type this code on the phone to allow it to cast here.',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 15, color: PbColors.textSecondary),
              ),
              const SizedBox(height: 20),
              Semantics(
                label: 'Pairing code $spoken',
                excludeSemantics: true,
                child: Wrap(
                  alignment: WrapAlignment.center,
                  spacing: 10,
                  runSpacing: 10,
                  children: [
                    for (var i = 0; i < digits.length; i++)
                      Padding(
                        padding: EdgeInsets.only(
                            right: digits.length == 6 && i == 2 ? 12 : 0),
                        child: _CodeDigit(digits[i]),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 20),
              Text(
                _remaining > 0
                    ? 'Expires in $_remaining ${_remaining == 1 ? 'second' : 'seconds'}'
                    : 'Expiring\u2026',
                style: const TextStyle(color: PbColors.textSecondary),
              ),
              const SizedBox(height: 20),
              PbButton(
                label: "Don't allow",
                variant: PbButtonVariant.secondary,
                onPressed: widget.onDeny,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CodeDigit extends StatelessWidget {
  const _CodeDigit(this.digit);
  final String digit;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 52,
      height: 68,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: PbColors.background,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        digit,
        style: const TextStyle(
          fontSize: 34,
          fontWeight: FontWeight.w700,
          color: PbColors.text,
        ),
      ),
    );
  }
}

/// Shown between the phone entering the code and the secure link coming up.
class _PairConnectingCard extends StatelessWidget {
  const _PairConnectingCard();

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 460),
      child: Container(
        padding: const EdgeInsets.fromLTRB(32, 36, 32, 36),
        decoration: BoxDecoration(
          color: PbColors.card,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: PbColors.outline),
        ),
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(
              width: 40,
              height: 40,
              child: CircularProgressIndicator(color: PbColors.accent),
            ),
            SizedBox(height: 20),
            Text(
              'Connecting\u2026',
              style: TextStyle(
                fontSize: 22,
                fontWeight: FontWeight.w700,
                color: PbColors.text,
              ),
            ),
            SizedBox(height: 6),
            Text(
              'Establishing a secure connection',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 15, color: PbColors.textSecondary),
            ),
          ],
        ),
      ),
    );
  }
}

/// Lists paired phones and lets the user forget one or all; [onChanged] runs after each removal.
Future<void> showPairedDevicesDialog({
  required BuildContext context,
  required PairingStore store,
  required VoidCallback onChanged,
}) {
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black54,
    builder: (ctx) {
      // Local copy so the dialog can rebuild independently when forgetting.
      var local = List<PairedDeviceRecord>.from(store.pairedDevices);
      return StatefulBuilder(builder: (ctx, setDialogState) {
        return Dialog(
          backgroundColor: PbColors.card,
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          child: SizedBox(
            width: 520,
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.devices,
                          size: 20, color: PbColors.textSecondary),
                      const SizedBox(width: 10),
                      const Text(
                        'Paired devices',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.w600),
                      ),
                      const Spacer(),
                      if (local.isNotEmpty)
                        TextButton(
                          onPressed: () async {
                            await store.forgetAllDevices();
                            final updated = store.pairedDevices;
                            setDialogState(() => local = updated);
                            onChanged();
                          },
                          style: TextButton.styleFrom(
                              foregroundColor: PbColors.error),
                          child: const Text('Remove all'),
                        ),
                      const SizedBox(width: 4),
                      IconButton(
                        onPressed: () => Navigator.of(ctx).pop(),
                        icon: const Icon(Icons.close, size: 18),
                        color: PbColors.textSecondary,
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  if (local.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 24),
                      child: Center(
                        child: Text(
                          'No paired devices.',
                          style: TextStyle(color: PbColors.textSecondary),
                        ),
                      ),
                    )
                  else
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 320),
                      child: ListView.separated(
                        shrinkWrap: true,
                        itemCount: local.length,
                        separatorBuilder: (_, __) => const Divider(
                            height: 1, color: PbColors.outlineSoft),
                        itemBuilder: (_, i) {
                          final device = local[i];
                          return _DialogDeviceRow(
                            device: device,
                            onForget: () async {
                              await store.forgetDevice(device.deviceUUID);
                              final updated = store.pairedDevices;
                              setDialogState(() => local = updated);
                              onChanged();
                            },
                          );
                        },
                      ),
                    ),
                ],
              ),
            ),
          ),
        );
      });
    },
  );
}

// ─── Dialog device row ────────────────────────────────────────────────────────

class _DialogDeviceRow extends StatelessWidget {
  const _DialogDeviceRow({required this.device, required this.onForget});
  final PairedDeviceRecord device;
  final VoidCallback onForget;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(vertical: 4),
      leading:
          const Icon(Icons.smartphone, size: 18, color: PbColors.textSecondary),
      title: Text(device.deviceName, style: const TextStyle(fontSize: 14)),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _formatDate(device.lastConnected),
            style: const TextStyle(color: PbColors.textSecondary, fontSize: 11),
          ),
          const SizedBox(height: 2),
          Text(
            device.deviceUUID,
            style: const TextStyle(
              color: PbColors.outline,
              fontSize: 10,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
      trailing: TextButton.icon(
        onPressed: onForget,
        icon: const Icon(Icons.delete_outline, color: PbColors.error, size: 15),
        label: const Text('Forget',
            style: TextStyle(color: PbColors.error, fontSize: 13)),
      ),
    );
  }

  static String _formatDate(DateTime dt) {
    final now = DateTime.now();
    final diff = now.difference(dt);
    if (diff.inDays == 0) {
      return 'Today ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    }
    if (diff.inDays == 1) return 'Yesterday';
    return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
  }
}
