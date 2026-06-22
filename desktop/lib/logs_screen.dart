import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'logging/log_store.dart';

/// In-app log viewer for the desktop receiver. Reads [LogStore.instance.entries]
/// (fed by the debugPrint tee + structured logs) and mirrors the phone's layout.
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Logs'),
        actions: [
          PopupMenuButton<String>(
            onSelected: (value) async {
              switch (value) {
                case 'enable':
                  await _setEnabled(!_enabled);
                  break;
                case 'autoscroll':
                  setState(() => _autoScroll = !_autoScroll);
                  break;
                case 'clear':
                  await LogStore.instance.clear();
                  break;
                case 'copy':
                  final messenger = ScaffoldMessenger.of(context);
                  await Clipboard.setData(
                      ClipboardData(text: LogStore.instance.combinedText()));
                  messenger.showSnackBar(
                    const SnackBar(content: Text('Copied all logs')),
                  );
                  break;
              }
            },
            itemBuilder: (context) => [
              CheckedPopupMenuItem(
                value: 'enable',
                checked: _enabled,
                child: const Text('Enable logging'),
              ),
              CheckedPopupMenuItem(
                value: 'autoscroll',
                checked: _autoScroll,
                child: const Text('Auto-scroll'),
              ),
              const PopupMenuDivider(),
              const PopupMenuItem(value: 'copy', child: Text('Copy all')),
              const PopupMenuItem(value: 'clear', child: Text('Clear logs')),
            ],
          ),
        ],
      ),
      body: !_enabled
          ? _disabledState(context)
          : Column(
              children: [
                _filterBar(),
                const Divider(height: 1),
                Expanded(child: _list()),
              ],
            ),
    );
  }

  Widget _disabledState(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.description_outlined,
                size: 40, color: Colors.white38),
            const SizedBox(height: 16),
            const Text(
              'Logging is disabled. No logs are being saved.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white54),
            ),
            const SizedBox(height: 8),
            const Text(
              'Logs can contain stream URLs and request headers (including Debrid '
              'tokens), so enable only while troubleshooting.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white38, fontSize: 12),
            ),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: () => _setEnabled(true),
              child: const Text('Enable logging'),
            ),
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
      MapEntry(LogLevel.warn, 'Warn'),
      MapEntry(LogLevel.error, 'Error'),
    ];
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Column(
        children: [
          TextField(
            onChanged: (v) => setState(() => _query = v),
            decoration: const InputDecoration(
              isDense: true,
              prefixIcon: Icon(Icons.search, size: 20),
              hintText: 'Filter by tag or message',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                for (final l in levels)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: FilterChip(
                      label: Text(l.value),
                      selected: _minLevel == l.key,
                      onSelected: (_) => setState(() => _minLevel = l.key),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _list() {
    return ValueListenableBuilder<List<LogEntry>>(
      valueListenable: LogStore.instance.entries,
      builder: (context, all, _) {
        final filtered = all.where(_matches).toList();
        if (filtered.isEmpty) {
          return const Center(
            child: Text('No matching log lines.',
                style: TextStyle(color: Colors.white38)),
          );
        }
        if (_autoScroll) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (_scroll.hasClients) {
              _scroll.jumpTo(_scroll.position.maxScrollExtent);
            }
          });
        }
        return ListView.separated(
          controller: _scroll,
          itemCount: filtered.length,
          separatorBuilder: (_, __) =>
              Divider(height: 1, color: Colors.white.withValues(alpha: 0.06)),
          itemBuilder: (context, i) => _LogRow(
            entry: filtered[i],
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(
                  builder: (_) => _LogDetailScreen(entry: filtered[i])),
            ),
          ),
        );
      },
    );
  }
}

Color _dotColor(LogLevel level) => switch (level) {
      LogLevel.error => const Color(0xFFFF5A5A),
      LogLevel.warn => const Color(0xFFFFA726),
      LogLevel.info ||
      LogLevel.debug ||
      LogLevel.verbose =>
        const Color(0xFF7AA2F7),
      LogLevel.unknown => const Color(0xFF8A8A8A),
    };

Color _rowTint(LogLevel level) => switch (level) {
      LogLevel.error => const Color(0xFF2A1416),
      LogLevel.warn => const Color(0xFF241C10),
      _ => Colors.transparent,
    };

class _LogRow extends StatelessWidget {
  const _LogRow({required this.entry, required this.onTap});

  final LogEntry entry;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final subtitle = StringBuffer('at ${entry.timeLabel}');
    if (entry.tag.isNotEmpty) subtitle.write(' in ${entry.tag}');
    return InkWell(
      onTap: onTap,
      child: Container(
        color: _rowTint(entry.level),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 5, right: 14),
              child: Container(
                width: 9,
                height: 9,
                decoration: BoxDecoration(
                    color: _dotColor(entry.level), shape: BoxShape.circle),
              ),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    entry.message,
                    maxLines: 4,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontFamily: 'monospace', fontSize: 14, height: 1.3),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    subtitle.toString(),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white38, fontSize: 12),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: Colors.white38, size: 20),
          ],
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
      appBar: AppBar(title: const Text('Log Detail')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Row(
            children: [
              const Expanded(child: _Label('MESSAGE')),
              IconButton(
                icon: const Icon(Icons.copy, size: 18),
                tooltip: 'Copy message',
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: entry.message));
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Copied')),
                    );
                  }
                },
              ),
            ],
          ),
          _ValueBox(
              child: SelectableText(
            entry.message,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
          )),
          const SizedBox(height: 20),
          const _Label('FROM'),
          _Pill(entry.tag.isEmpty ? '—' : entry.tag),
          const SizedBox(height: 20),
          const _Label('TIME'),
          _Pill(entry.timeLabel),
          const SizedBox(height: 20),
          const _Label('LEVEL'),
          _Pill(entry.level.name),
        ],
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
        style: TextStyle(
          color: Theme.of(context).colorScheme.primary,
          fontWeight: FontWeight.w600,
          fontSize: 13,
          letterSpacing: 0.5,
        ),
      ),
    );
  }
}

class _ValueBox extends StatelessWidget {
  const _ValueBox({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(12),
      ),
      child: child,
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.06),
          borderRadius: BorderRadius.circular(24),
        ),
        child: Text(text,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
      ),
    );
  }
}
