import 'package:flutter/material.dart';

import '../tv_connection_store.dart';
import '../tv_sender_client.dart';
import '../tv_sender_controller.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// What the TV connection is doing, or why it failed. Null when there is
/// nothing to add beyond what the device rows already say (idle, connected, or
/// the pairing-code card is up).
({IconData icon, Color color, String text})? describeConnection({
  required SenderConnectionState state,
  required TvRecord? activeTv,
  required ReceiverReconnectStatus? reconnectStatus,
}) {
  final reconnecting = reconnectStatus;
  if (reconnecting != null) {
    return (icon: Icons.sync, color: PbColors.accent, text: reconnecting.label);
  }
  final name = activeTv?.name;
  return switch (state) {
    SenderConnectionState.disconnected ||
    SenderConnectionState.connected ||
    SenderConnectionState.waitingForCodeInput ||
    SenderConnectionState.verifyingCode =>
      null,
    SenderConnectionState.selected => (
        icon: Icons.cast,
        color: PbColors.warning,
        text: name != null
            ? '$name is selected. Its Cast session ended or stopped responding; the next cast will start fresh.'
            : 'Cast destination selected. The next cast will start fresh.',
      ),
    SenderConnectionState.connecting => (
        icon: Icons.cast,
        color: PbColors.accent,
        text: 'Connecting…',
      ),
    SenderConnectionState.waitingForChallenge => (
        icon: Icons.hourglass_top,
        color: PbColors.warning,
        text: 'Establishing secure pairing…',
      ),
    SenderConnectionState.pairingDenied => (
        icon: Icons.block,
        color: PbColors.error,
        text: 'Pairing was denied on the TV.',
      ),
    SenderConnectionState.authFailed => (
        icon: Icons.lock_reset,
        color: PbColors.warning,
        text: 'The TV rejected the saved token. Pair again.',
      ),
    SenderConnectionState.pinMismatch => (
        icon: Icons.gpp_bad,
        color: PbColors.error,
        text: "Security check failed: the TV's certificate changed. Refused to "
            'connect. Pair again only if you trust this TV.',
      ),
    SenderConnectionState.error => (
        icon: Icons.error_outline,
        color: PbColors.error,
        text: 'Connection error. Check the TV is reachable and try again.',
      ),
  };
}

/// Inline notice for the connection state (see [describeConnection]).
class ConnectionBanner extends StatelessWidget {
  const ConnectionBanner({
    super.key,
    required this.state,
    required this.activeTv,
    required this.reconnectStatus,
  });

  final SenderConnectionState state;
  final TvRecord? activeTv;
  final ReceiverReconnectStatus? reconnectStatus;

  @override
  Widget build(BuildContext context) {
    final d = describeConnection(
      state: state,
      activeTv: activeTv,
      reconnectStatus: reconnectStatus,
    );
    if (d == null) return const SizedBox.shrink();
    return _Notice(icon: d.icon, color: d.color, text: d.text);
  }
}

/// Playback failure reported by the TV, with a dismiss button.
class PlaybackErrorBanner extends StatelessWidget {
  const PlaybackErrorBanner({
    super.key,
    required this.message,
    required this.onDismiss,
  });

  final String message;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    return _Notice(
      icon: Icons.error_outline,
      color: PbColors.error,
      text: message,
      trailing: PbIconButton(
        icon: Icons.close,
        tooltip: 'Dismiss',
        onPressed: onDismiss,
        size: 36,
        iconSize: 18,
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({
    required this.icon,
    required this.color,
    required this.text,
    this.trailing,
  });

  final IconData icon;
  final Color color;
  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.fromLTRB(16, 12, trailing == null ? 16 : 8, 12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
        border: Border.all(color: color.withValues(alpha: 0.30)),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(fontSize: 14, color: PbColors.text),
            ),
          ),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}
