import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'logging/log_store.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// In-app log viewer for the desktop receiver. Reads [LogStore.instance.entries]
/// (fed by the debugPrint tee + structured logs).
class LogsScreen extends StatefulWidget {
  const LogsScreen({super.key});

  @override
  State<LogsScreen> createState() => _LogsScreenState();
}

class _LogsScreenState extends State<LogsScreen> {
  final _scroll = ScrollController();
  String _query = '';
  LogLevel _minLevel = LogLevel.verbose;
  bool _autoScroll = true;
  late bool _enabled = LogStore.instance.enabled;

  static const _levelRank = {
    LogLevel.verbose: 0,
    LogLevel.debug: 1,
    LogLevel.info: 2,
    LogLevel.warn: 3,
    LogLevel.error: 4,
    LogLevel.unknown: 0,
  };

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  bool _matches(LogEntry e) {
    final levelOk = e.level == LogLevel.unknown ||
        (_levelRank[e.level] ?? 0) >= (_levelRank[_minLevel] ?? 0);
    final queryOk = _query.isEmpty ||
        e.message.toLowerCase().contains(_query.toLowerCase()) ||
        e.tag.toLowerCase().contains(_query.toLowerCase());
    return levelOk && queryOk;
  }

  Future<void> _setEnabled(bool v) async {
    await LogStore.instance.setEnabled(v);
    if (mounted) setState(() => _enabled = v);
  }

  Future<void> _copyAll() async {
    final messenger = ScaffoldMessenger.of(context);
    await Clipboard.setData(
        ClipboardData(text: LogStore.instance.combinedText()));
    messenger.showSnackBar(const SnackBar(content: Text('Copied all logs')));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: PbColors.background,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _header(context),
              const SizedBox(height: 16),
              if (!_enabled)
                Expanded(child: _disabledState())
              else ...[
                _warning(),
                const SizedBox(height: 16),
                _filterBar(),
                const SizedBox(height: 16),
                Expanded(child: _list()),
                const SizedBox(height: 12),
                const Text(
                  'Click a line to see it in full and copy it. New lines '
                  'appear at the bottom.',
                  style: TextStyle(color: PbColors.textSecondary),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _header(BuildContext context) {
    return Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 16,
      runSpacing: 12,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            PbIconButton(
              icon: Icons.arrow_back,
              tooltip: 'Back to Settings',
              variant: PbButtonVariant.secondary,
              onPressed: () => Navigator.of(context).maybePop(),
            ),
            const SizedBox(width: 16),
            const Text('Logs',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
            const SizedBox(width: 16),
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                color: _enabled ? PbColors.success : PbColors.outline,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 8),
            Text(
              _enabled ? 'Recording' : 'Not recording',
              style: const TextStyle(color: PbColors.textSecondary),
            ),
          ],
        ),
        if (_enabled)
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              PbButton(label: 'Copy all', onPressed: _copyAll),
              PbButton(
                label: _autoScroll ? 'Auto-scroll on' : 'Auto-scroll off',
                variant: PbButtonVariant.secondary,
                onPressed: () => setState(() => _autoScroll = !_autoScroll),
              ),
              PbButton(
                label: 'Clear',
                variant: PbButtonVariant.secondary,
                onPressed: () async {
                  await LogStore.instance.clear();
                  if (mounted) setState(() {});
                },
              ),
              PbButton(
                label: 'Stop and delete',
                variant: PbButtonVariant.secondary,
                onPressed: () => _setEnabled(false),
              ),
            ],
          ),
      ],
    );
  }

  Widget _warning() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFF3A2C10),
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
      child: const Text(
        'Logs can include video addresses and account tokens. Read them '
        'before sharing.',
        style: TextStyle(color: Color(0xFFF7E3B5)),
      ),
    );
  }

  Widget _disabledState() {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.description_outlined,
                size: 40, color: PbColors.textSecondary),
            const SizedBox(height: 16),
            const Text(
              'Logging is off. Nothing is being saved.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            const Text(
              'Logs can include video addresses and account tokens. Turn '
              'logging on only while reproducing a problem.',
              textAlign: TextAlign.center,
              style: TextStyle(color: PbColors.textSecondary),
            ),
            const SizedBox(height: 20),
            PbButton(
                label: 'Start logging', onPressed: () => _setEnabled(true)),
          ],
        ),
      ),
    );
  }

  Widget _filterBar() {
    const levels = <MapEntry<LogLevel, String>>[
      MapEntry(LogLevel.verbose, 'All'),
      MapEntry(LogLevel.debug, 'Debug'),
      MapEntry(LogLevel.info, 'Info'),
      MapEntry(LogLevel.warn, 'Warnings'),
      MapEntry(LogLevel.error, 'Errors'),
    ];
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final l in levels)
          PbPressable(
            onTap: () => setState(() => _minLevel = l.key),
            selected: _minLevel == l.key,
            semanticLabel: l.value,
            builder: (context, hovered, focused) {
              final sel = _minLevel == l.key;
              return Container(
                constraints:
                    const BoxConstraints(minHeight: PbMetrics.controlHeight),
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
                decoration: BoxDecoration(
                  color: sel
                      ? PbColors.selected
                      : (hovered ? PbColors.raised : PbColors.card),
                  borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
                  border: focused
                      ? Border.all(color: PbColors.accent, width: 2)
                      : null,
                ),
                child: Text(
                  l.value,
                  style: TextStyle(
                    fontWeight: sel ? FontWeight.w600 : FontWeight.w400,
                    color: sel ? PbColors.onSelected : PbColors.text,
                  ),
                ),
              );
            },
          ),
        const SizedBox(width: 4),
        const Text('Find', style: TextStyle(color: PbColors.textSecondary)),
        SizedBox(
          width: 320,
          height: PbMetrics.controlHeight,
          child: TextField(
            onChanged: (v) => setState(() => _query = v),
            style: const TextStyle(fontSize: 14),
            decoration: InputDecoration(
              contentPadding: const EdgeInsets.symmetric(horizontal: 16),
              hintText: 'Word or part of the app',
              fillColor: PbColors.background,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
                borderSide: const BorderSide(color: PbColors.outline),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
                borderSide: const BorderSide(color: PbColors.outline),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
                borderSide: const BorderSide(color: PbColors.accent, width: 2),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _list() {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF08041F),
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
      clipBehavior: Clip.antiAlias,
      child: ValueListenableBuilder<List<LogEntry>>(
        valueListenable: LogStore.instance.entries,
        builder: (context, all, _) {
          final filtered = all.where(_matches).toList();
          if (filtered.isEmpty) {
            return const Center(
              child: Text('No matching log lines.',
                  style: TextStyle(color: PbColors.textSecondary)),
            );
          }
          if (_autoScroll) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (_scroll.hasClients) {
                _scroll.jumpTo(_scroll.position.maxScrollExtent);
              }
            });
          }
          return ListView.builder(
            controller: _scroll,
            padding: const EdgeInsets.all(6),
            itemCount: filtered.length,
            itemBuilder: (context, i) => _LogRow(
              entry: filtered[i],
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                    builder: (_) => _LogDetailScreen(entry: filtered[i])),
              ),
            ),
          );
        },
      ),
    );
  }
}

Color _levelColor(LogLevel level) => switch (level) {
      LogLevel.error => PbColors.error,
      LogLevel.warn => PbColors.warning,
      _ => PbColors.textSecondary,
    };

const _mono = TextStyle(
  fontFamily: 'Menlo',
  fontFamilyFallback: ['Consolas', 'monospace'],
  fontSize: 13,
  height: 1.7,
);

class _LogRow extends StatelessWidget {
  const _LogRow({required this.entry, required this.onTap});

  final LogEntry entry;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = _levelColor(entry.level);
    final isPlain =
        entry.level == LogLevel.error || entry.level == LogLevel.warn;
    final prefix =
        '${entry.timeLabel.substring(0, 8)}  ${entry.level.name.padRight(5)}  '
        '${entry.tag.padRight(8)}  ';
    return PbPressable(
      onTap: onTap,
      semanticLabel: '${entry.level.name} ${entry.tag} ${entry.message}',
      builder: (context, hovered, focused) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        decoration: BoxDecoration(
          color: hovered ? PbColors.raised.withValues(alpha: 0.5) : null,
          borderRadius: BorderRadius.circular(6),
          border: focused ? Border.all(color: PbColors.accent, width: 2) : null,
        ),
        child: Text.rich(
          TextSpan(
            style: _mono.copyWith(color: PbColors.text),
            children: [
              TextSpan(
                text: prefix,
                style: TextStyle(color: color),
              ),
              TextSpan(
                text: entry.message.replaceAll('\n', ' '),
                style: isPlain ? TextStyle(color: color) : null,
              ),
            ],
          ),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }
}

class _LogDetailScreen extends StatelessWidget {
  const _LogDetailScreen({required this.entry});

  final LogEntry entry;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: PbColors.background,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
          child: Align(
            alignment: Alignment.topLeft,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      PbIconButton(
                        icon: Icons.arrow_back,
                        tooltip: 'Back to logs',
                        variant: PbButtonVariant.secondary,
                        onPressed: () => Navigator.of(context).maybePop(),
                      ),
                      const SizedBox(width: 16),
                      const Expanded(
                        child: Text('Log line',
                            style: TextStyle(
                                fontSize: 22, fontWeight: FontWeight.w700)),
                      ),
                      PbButton(
                        label: 'Copy message',
                        icon: Icons.copy,
                        onPressed: () async {
                          final messenger = ScaffoldMessenger.of(context);
                          await Clipboard.setData(
                              ClipboardData(text: entry.message));
                          messenger.showSnackBar(
                              const SnackBar(content: Text('Copied')));
                        },
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  const _Label('Message'),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: const Color(0xFF08041F),
                      borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
                    ),
                    child: SelectableText(entry.message,
                        style: _mono.copyWith(color: PbColors.text)),
                  ),
                  const SizedBox(height: 20),
                  const _Label('From'),
                  _Pill(entry.tag.isEmpty ? '—' : entry.tag),
                  const SizedBox(height: 20),
                  const _Label('Time'),
                  _Pill(entry.timeLabel),
                  const SizedBox(height: 20),
                  const _Label('Level'),
                  _Pill(entry.level.name),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Label extends StatelessWidget {
  const _Label(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        text,
        style: const TextStyle(
          color: PbColors.accent,
          fontWeight: FontWeight.w600,
          fontSize: 13,
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
      ),
      child: Text(text, style: _mono.copyWith(height: 1.3)),
    );
  }
}
