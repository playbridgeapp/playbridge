import 'package:flutter/material.dart';

import 'history_store.dart';
import 'player_controller.dart';

class HistoryScreen extends StatelessWidget {
  const HistoryScreen({
    super.key,
    required this.store,
    required this.player,
    required this.onNavigateToNowPlaying,
  });

  final HistoryStore store;
  final PlayerController player;
  final VoidCallback onNavigateToNowPlaying;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final items = store.items;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 16, 8),
              child: Row(
                children: [
                  Text('History',
                      style: Theme.of(context).textTheme.titleLarge),
                  const Spacer(),
                  if (items.any((i) => !i.isFavorite))
                    TextButton(
                      onPressed: () => store.clearHistory(),
                      child: const Text('Clear',
                          style: TextStyle(color: Colors.white38)),
                    ),
                ],
              ),
            ),
            if (items.isEmpty)
              const Expanded(
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.history, size: 64, color: Colors.white12),
                      SizedBox(height: 12),
                      Text('No history yet',
                          style: TextStyle(color: Colors.white38)),
                      SizedBox(height: 4),
                      Text(
                        'Videos you cast will appear here.',
                        style: TextStyle(color: Colors.white24, fontSize: 13),
                      ),
                    ],
                  ),
                ),
              )
            else
              Expanded(
                child: ListView.builder(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  itemCount: items.length,
                  itemBuilder: (context, i) => _HistoryTile(
                    item: items[i],
                    store: store,
                    player: player,
                    onNavigateToNowPlaying: onNavigateToNowPlaying,
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _HistoryTile extends StatelessWidget {
  const _HistoryTile({
    required this.item,
    required this.store,
    required this.player,
    required this.onNavigateToNowPlaying,
  });

  final PlayHistoryItem item;
  final HistoryStore store;
  final PlayerController player;
  final VoidCallback onNavigateToNowPlaying;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      leading: const Icon(Icons.play_circle_outline,
          color: Colors.white38, size: 22),
      title: Text(
        item.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 14),
      ),
      subtitle: Text(
        _timeAgo(item.playedAt),
        style: const TextStyle(color: Colors.white38, fontSize: 12),
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            tooltip:
                item.isFavorite ? 'Remove from favorites' : 'Add to favorites',
            icon: Icon(
              item.isFavorite ? Icons.star : Icons.star_border,
              color: item.isFavorite ? Colors.amber : Colors.white38,
              size: 20,
            ),
            onPressed: () => store.toggleFavorite(item.url),
          ),
          IconButton(
            tooltip: 'Cast now',
            icon: const Icon(Icons.cast, color: Colors.tealAccent, size: 20),
            onPressed: () {
              player.playUrl(item.url, title: item.title);
              onNavigateToNowPlaying();
            },
          ),
        ],
      ),
      onTap: () {
        player.playUrl(item.url, title: item.title);
        onNavigateToNowPlaying();
      },
    );
  }

  static String _timeAgo(DateTime dt) {
    final d = DateTime.now().difference(dt);
    if (d.inMinutes < 1) return 'just now';
    if (d.inMinutes < 60) return '${d.inMinutes}m ago';
    if (d.inHours < 24) return '${d.inHours}h ago';
    if (d.inDays < 7) return '${d.inDays}d ago';
    return '${dt.day}/${dt.month}/${dt.year}';
  }
}
