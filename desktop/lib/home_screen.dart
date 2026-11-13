import 'package:flutter/material.dart';

import 'history_store.dart';
import 'pairing_store.dart';
import 'shell/play_target.dart';
import 'shell/receiver_status.dart';
import 'tv_sender_controller.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// Home destination: "Play something" (choose files, paste a link, pick where it
/// plays), then bookmarks and recent plays. Before anything is paired or played
/// it shows the first-run welcome instead.
class HomeScreen extends StatelessWidget {
  const HomeScreen({
    super.key,
    required this.store,
    required this.history,
    required this.sender,
    required this.deviceName,
    required this.status,
    required this.onChooseFiles,
    required this.onPasteLink,
    required this.onFindDevices,
    required this.onSeeBookmarks,
    required this.onPlayHistoryItem,
    required this.onMessage,
    this.enableHistory = true,
  });

  final PairingStore store;
  final HistoryStore history;
  final TvSenderController sender;
  final String deviceName;

  /// Receiver health, for the "Waiting for a phone" line on first run.
  final ReceiverStatusInfo status;

  /// Settings: history off hides the Recent section.
  final bool enableHistory;

  final VoidCallback onChooseFiles;
  final VoidCallback onPasteLink;

  /// Goes to Devices ("Find more devices", "Find TVs").
  final VoidCallback onFindDevices;
  final VoidCallback onSeeBookmarks;

  /// Plays a bookmark or recent item on the current "Plays on" destination.
  final ValueChanged<PlayHistoryItem> onPlayHistoryItem;
  final ValueChanged<String> onMessage;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([sender, history]),
      builder: (context, _) {
        final firstRun = store.pairedDevices.isEmpty &&
            sender.pairedTvs.isEmpty &&
            history.items.isEmpty;
        return ListView(
          padding: const EdgeInsets.fromLTRB(32, 28, 32, 28),
          children: firstRun ? _firstRun() : _regular(),
        );
      },
    );
  }

  List<Widget> _firstRun() => [
        const SizedBox(height: 28),
        const Text(
          'Welcome to PlayBridge',
          style: TextStyle(
            fontSize: 32,
            height: 1.25,
            fontWeight: FontWeight.w700,
            color: PbColors.text,
          ),
        ),
        const SizedBox(height: 8),
        const Text(
          'This computer is ready. Start with whichever you need first.',
          style: TextStyle(fontSize: 16, color: PbColors.textSecondary),
        ),
        const SizedBox(height: 32),
        LayoutBuilder(
          builder: (context, box) {
            final phone = _StartCard(
              title: 'Cast from your phone',
              body: 'Open PlayBridge on your phone and choose $deviceName. '
                  'A code appears here to confirm.',
              footer: _StatusLine(status: status),
            );
            final tv = _StartCard(
              title: 'Send to a TV',
              body: 'Play files and links from this computer on a PlayBridge '
                  'TV, Chromecast, Roku or DLNA device.',
              footer: Align(
                alignment: Alignment.centerLeft,
                child: PbButton(label: 'Find TVs', onPressed: onFindDevices),
              ),
            );
            if (box.maxWidth < 560) {
              return Column(children: [phone, const SizedBox(height: 16), tv]);
            }
            return IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(child: phone),
                  const SizedBox(width: 16),
                  Expanded(child: tv),
                ],
              ),
            );
          },
        ),
        const SizedBox(height: 32),
        _PlayCard(
          title: 'Or play something here now',
          actions: [
            PbButton(
              label: 'Choose files…',
              variant: PbButtonVariant.secondary,
              onPressed: onChooseFiles,
            ),
            PbButton(
              label: 'Paste link',
              variant: PbButtonVariant.secondary,
              onPressed: onPasteLink,
            ),
          ],
        ),
      ];

  List<Widget> _regular() {
    final bookmarks = history.favorites.take(3).toList();
    final recent = enableHistory ? history.recent.take(3).toList() : const [];
    return [
      const Text(
        'Home',
        style: TextStyle(
          fontSize: 26,
          height: 40 / 26,
          fontWeight: FontWeight.w700,
          color: PbColors.text,
        ),
      ),
      const SizedBox(height: 20),
      _PlayCard(
        title: 'Play something',
        actions: [
          PbButton(
            label: 'Choose files…',
            onPressed: onChooseFiles,
          ),
          PbButton(
            label: 'Paste link',
            variant: PbButtonVariant.secondary,
            onPressed: onPasteLink,
          ),
          const Padding(
            padding: EdgeInsets.only(left: 12),
            child: Text(
              'Plays on',
              style: TextStyle(color: PbColors.textSecondary),
            ),
          ),
          PlayOnPill(
            sender: sender,
            computerName: deviceName,
            onFindDevices: onFindDevices,
            onMessage: onMessage,
          ),
        ],
      ),
      if (bookmarks.isNotEmpty) ...[
        const SizedBox(height: 24),
        PbSectionHeading(
          'Bookmarks',
          trailing: PbButton(
            label: 'See all',
            variant: PbButtonVariant.quiet,
            onPressed: onSeeBookmarks,
          ),
        ),
        const SizedBox(height: 8),
        for (final item in bookmarks) _itemRow(item),
      ],
      if (recent.isNotEmpty) ...[
        const SizedBox(height: 24),
        const PbSectionHeading('Recent'),
        const SizedBox(height: 8),
        for (final item in recent) _itemRow(item),
      ],
    ];
  }

  Widget _itemRow(PlayHistoryItem item) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: PbListRow(
          title: item.title,
          subtitle: describePlayedAt(DateTime.now(), item.playedAt),
          minHeight: 52,
          onTap: () => onPlayHistoryItem(item),
        ),
      );
}

/// "Played today", "Played yesterday", "Played Monday" or "Played 3 Mar 2026".
String describePlayedAt(DateTime now, DateTime playedAt) {
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(playedAt.year, playedAt.month, playedAt.day);
  final days = today.difference(day).inDays;
  if (days <= 0) return 'Played today';
  if (days == 1) return 'Played yesterday';
  if (days < 7) {
    const names = [
      'Monday',
      'Tuesday',
      'Wednesday',
      'Thursday',
      'Friday',
      'Saturday',
      'Sunday',
    ];
    return 'Played ${names[playedAt.weekday - 1]}';
  }
  const months = [
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ];
  return 'Played ${playedAt.day} ${months[playedAt.month - 1]} ${playedAt.year}';
}

/// The dashed "Play something" card with its actions.
class _PlayCard extends StatelessWidget {
  const _PlayCard({required this.title, required this.actions});

  final String title;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      foregroundPainter: _DashedBorderPainter(),
      child: Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: PbColors.card,
          borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w600,
                color: PbColors.text,
              ),
            ),
            const SizedBox(height: 6),
            const Text(
              // The window-wide drop target only accepts what the player and
              // the TV sender accept: video and audio files, and web links.
              'Drop a video or song anywhere in this window, or paste a link.',
              style: TextStyle(color: PbColors.textSecondary),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 12,
              runSpacing: 12,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: actions,
            ),
          ],
        ),
      ),
    );
  }
}

class _DashedBorderPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final rrect = RRect.fromRectAndRadius(
      (Offset.zero & size).deflate(0.5),
      const Radius.circular(PbMetrics.cardRadius),
    );
    final paint = Paint()
      ..color = PbColors.outline
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    final path = Path()..addRRect(rrect);
    for (final metric in path.computeMetrics()) {
      var distance = 0.0;
      while (distance < metric.length) {
        canvas.drawPath(metric.extractPath(distance, distance + 6), paint);
        distance += 10;
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _StartCard extends StatelessWidget {
  const _StartCard({
    required this.title,
    required this.body,
    required this.footer,
  });

  final String title;
  final String body;
  final Widget footer;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            title,
            style: const TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w600,
              color: PbColors.text,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            body,
            style: const TextStyle(
              height: 1.5,
              color: PbColors.textSecondary,
            ),
          ),
          const SizedBox(height: 12),
          footer,
        ],
      ),
    );
  }
}

class _StatusLine extends StatelessWidget {
  const _StatusLine({required this.status});

  final ReceiverStatusInfo status;

  @override
  Widget build(BuildContext context) {
    final ready = status.health == ReceiverHealth.ready ||
        status.health == ReceiverHealth.pairing;
    final color = switch (status.health) {
      ReceiverHealth.ready || ReceiverHealth.pairing => PbColors.success,
      ReceiverHealth.limited || ReceiverHealth.starting => PbColors.warning,
      ReceiverHealth.failed => PbColors.error,
    };
    return SizedBox(
      height: PbMetrics.controlHeight,
      child: Row(
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              ready ? 'Waiting for a phone' : status.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontWeight: FontWeight.w500,
                color: PbColors.text,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
