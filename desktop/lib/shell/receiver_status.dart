import 'package:flutter/material.dart';

import '../receiver_server.dart';
import '../ui/pb_theme.dart';

/// How healthy the receiver (the part phones cast to) is right now.
enum ReceiverHealth { starting, ready, pairing, limited, failed }

/// Text and tone for the sidebar status block, derived from real receiver state.
class ReceiverStatusInfo {
  const ReceiverStatusInfo({
    required this.health,
    required this.label,
    required this.detail,
    this.extra,
  });

  final ReceiverHealth health;

  /// First line, for example "Ready to receive".
  final String label;

  /// Second line, for example "Phones see this as Studio Mac".
  final String detail;

  /// Optional third line with an error worth surfacing (mDNS, TLS).
  final String? extra;
}

/// Maps receiver state (server start-up, errors, pairing phase) to an honest status.
ReceiverStatusInfo describeReceiverStatus({
  required String deviceName,
  required String hostInfo,
  required PairingPhase phase,
  String? serverError,
  String? discoveryError,
  String? tlsError,
}) {
  if (serverError != null) {
    return ReceiverStatusInfo(
      health: ReceiverHealth.failed,
      label: 'Not receiving',
      detail: 'The receiver failed to start',
      extra: serverError,
    );
  }
  if (tlsError != null) {
    return ReceiverStatusInfo(
      health: ReceiverHealth.failed,
      label: 'Not receiving',
      detail: 'Secure connection unavailable',
      extra: tlsError,
    );
  }
  if (hostInfo == 'starting…') {
    return const ReceiverStatusInfo(
      health: ReceiverHealth.starting,
      label: 'Starting…',
      detail: 'Getting ready to receive',
    );
  }
  if (discoveryError != null) {
    return ReceiverStatusInfo(
      health: ReceiverHealth.limited,
      label: 'Phones may not find this',
      detail: 'Phones see this as $deviceName',
      extra: 'mDNS: $discoveryError',
    );
  }
  if (phase == PairingPhase.awaitingApproval ||
      phase == PairingPhase.awaitingCode) {
    return ReceiverStatusInfo(
      health: ReceiverHealth.pairing,
      label: 'Pairing a phone…',
      detail: 'Phones see this as $deviceName',
    );
  }
  return ReceiverStatusInfo(
    health: ReceiverHealth.ready,
    label: 'Ready to receive',
    detail: 'Phones see this as $deviceName',
  );
}

/// Sidebar card showing whether this computer is ready to receive casts, and under what name.
class ReceiverStatusBlock extends StatelessWidget {
  const ReceiverStatusBlock({super.key, required this.status});

  final ReceiverStatusInfo status;

  @override
  Widget build(BuildContext context) {
    final dot = switch (status.health) {
      ReceiverHealth.ready => PbColors.success,
      ReceiverHealth.pairing => PbColors.accent,
      ReceiverHealth.starting => PbColors.textSecondary,
      ReceiverHealth.limited => PbColors.warning,
      ReceiverHealth.failed => PbColors.error,
    };
    return Semantics(
      container: true,
      label: '${status.label}. ${status.detail}',
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: PbColors.card,
          borderRadius: BorderRadius.circular(PbMetrics.navRadius),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    status.label,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: PbColors.text,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 2),
            Text(
              status.detail,
              style: const TextStyle(
                fontSize: 12,
                color: PbColors.textSecondary,
              ),
            ),
            if (status.extra != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(
                  status.extra!,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: status.health == ReceiverHealth.failed
                        ? PbColors.error
                        : PbColors.warning,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
