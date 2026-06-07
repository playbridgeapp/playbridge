import 'package:flutter/material.dart';

import 'history_store.dart';
import 'player_controller.dart';

class FavoritesScreen extends StatelessWidget {
  const FavoritesScreen({
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
        final items = store.favorites;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 8),
              child: Text('Favorites',
                  style: Theme.of(context).textTheme.titleLarge),
            ),
            if (items.isEmpty)
              const Expanded(
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.star_border, size: 64, color: Colors.white12),
                      SizedBox(height: 12),
                      Text('No favorites yet',
                          style: TextStyle(color: Colors.white38)),
                      SizedBox(height: 4),
                      Text(
                        'Star items in History to save them here.',
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
                  itemBuilder: (context, i) {
                    final item = items[i];
                    return ListTile(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                      leading:
                          const Icon(Icons.star, color: Colors.amber, size: 20),
                      title: Text(
                        item.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 14),
                      ),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            tooltip: 'Remove from favorites',
                            icon: const Icon(Icons.star,
                                color: Colors.amber, size: 20),
                            onPressed: () => store.toggleFavorite(item.url),
                          ),
                          IconButton(
                            tooltip: 'Cast now',
                            icon: const Icon(Icons.cast,
                                color: Colors.tealAccent, size: 20),
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
                  },
                ),
              ),
          ],
        );
      },
    );
  }
}
