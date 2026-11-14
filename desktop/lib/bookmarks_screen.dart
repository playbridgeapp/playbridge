import 'dart:io';

import 'package:flutter/material.dart';

import 'history_store.dart';
import 'player_controller.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// Bookmarks destination: a "Bookmarks" tab (stored favourites) and a
/// "Recent" tab (play history), both shown as the same card grid.
///
/// Persisted data keeps its old names (`isFavorite`, `pb.history_v1`); only
/// the user-visible copy changed.
class BookmarksScreen extends StatefulWidget {
  const BookmarksScreen({
    super.key,
    required this.store,
    required this.player,
    required this.enableHistory,
    required this.onNavigateToNowPlaying,
    this.onPlayOnTarget,
    this.playOnTargetLabel = 'Play on TV',
  });

  final HistoryStore store;
  final PlayerController player;

  /// When false (Settings: history off) the "Recent" tab is hidden.
  final bool enableHistory;

  /// Switches the shell to the local player after something starts playing.
  final VoidCallback onNavigateToNowPlaying;

  /// Plays an item on the shared "Plays on" target. The button is hidden when
  /// this is null; when set it becomes the primary action and "Play here"
  /// becomes secondary.
  final void Function(PlayHistoryItem item)? onPlayOnTarget;

  /// Label for the [onPlayOnTarget] button, e.g. "Resume on Living Room TV".
  final String playOnTargetLabel;

  @override
  State<BookmarksScreen> createState() => _BookmarksScreenState();
}

class _BookmarksScreenState extends State<BookmarksScreen> {
  int _tab = 0;

  void _playHere(PlayHistoryItem item) {
    widget.player.playUrl(
      item.url,
      title: item.title,
      headers: item.headers,
      playlistBody: item.playlistBody,
      audioUrl: item.audioUrl,
      contentType: item.contentType,
      declaredMediaKind: item.mediaKind,
      displayDurationMs: item.displayDurationMs,
      localUserInitiated: true,
    );
    widget.onNavigateToNowPlaying();
  }

  @override
  Widget build(BuildContext context) {
    final showRecent = widget.enableHistory;
    final recent = showRecent && _tab == 1;
    return Padding(
      padding: const EdgeInsets.fromLTRB(32, 28, 32, 0),
      child: ListenableBuilder(
        listenable: widget.store,
        builder: (context, _) {
          final items = recent ? widget.store.recent : widget.store.favorites;
          final canClear = recent && items.any((i) => !i.isFavorite);
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Wrap(
                alignment: WrapAlignment.spaceBetween,
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 16,
                runSpacing: 8,
                children: [
                  const Text(
                    'Bookmarks',
                    style: TextStyle(
                      fontSize: 26,
                      height: 40 / 26,
                      fontWeight: FontWeight.w700,
                      color: PbColors.text,
                    ),
                  ),
                  if (showRecent)
                    PbSegmentedTabs(
                      labels: const ['Bookmarks', 'Recent'],
                      selectedIndex: recent ? 1 : 0,
                      onChanged: (i) => setState(() => _tab = i),
                    ),
                ],
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      recent
                          ? 'Videos you played on this computer recently. '
                              'Bookmark one to keep it.'
                          : 'A bookmark keeps a video so you can play it again. '
                              'Bookmarks stay on this computer.',
                      style: const TextStyle(color: PbColors.textSecondary),
                    ),
                  ),
                  if (canClear)
                    PbButton(
                      label: 'Clear recent',
                      variant: PbButtonVariant.quiet,
                      onPressed: widget.store.clearHistory,
                    ),
                ],
              ),
              const SizedBox(height: 16),
              Expanded(
                child: items.isEmpty
                    ? _EmptyState(recent: recent, enableHistory: showRecent)
                    : _CardGrid(
                        key: ValueKey(recent),
                        items: items,
                        recent: recent,
                        store: widget.store,
                        onPlayHere: _playHere,
                        onPlayOnTarget: widget.onPlayOnTarget,
                        playOnTargetLabel: widget.playOnTargetLabel,
                      ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.recent, required this.enableHistory});

  final bool recent;
  final bool enableHistory;

  @override
  Widget build(BuildContext context) {
    final title = recent ? 'Nothing played yet' : 'No bookmarks yet';
    final hint = recent
        ? 'Videos you play will show up here.'
        : enableHistory
            ? 'Bookmark a video from Recent to keep it here.'
            : 'Videos you bookmark will show up here.';
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            recent ? Icons.history : Icons.bookmark_border,
            size: 56,
            color: PbColors.outline,
          ),
          const SizedBox(height: 12),
          Text(
            title,
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: PbColors.text,
            ),
          ),
          const SizedBox(height: 4),
          Text(hint, style: const TextStyle(color: PbColors.textSecondary)),
        ],
      ),
    );
  }
}

class _CardGrid extends StatelessWidget {
  const _CardGrid({
    super.key,
    required this.items,
    required this.recent,
    required this.store,
    required this.onPlayHere,
    required this.onPlayOnTarget,
    required this.playOnTargetLabel,
  });

  final List<PlayHistoryItem> items;
  final bool recent;
  final HistoryStore store;
  final void Function(PlayHistoryItem) onPlayHere;
  final void Function(PlayHistoryItem)? onPlayOnTarget;
  final String playOnTargetLabel;

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      padding: const EdgeInsets.only(bottom: 28),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 380,
        mainAxisExtent: 300,
        crossAxisSpacing: 20,
        mainAxisSpacing: 20,
      ),
      itemCount: items.length,
      itemBuilder: (context, i) {
        final item = items[i];
        return BookmarkCard(
          key: ValueKey(item.url),
          item: item,
          recent: recent,
          onPlayHere: () => onPlayHere(item),
          onPlayOnTarget:
              onPlayOnTarget == null ? null : () => onPlayOnTarget!(item),
          playOnTargetLabel: playOnTargetLabel,
          onToggleBookmark: () => store.toggleFavorite(item.url),
          // Recent entries that are bookmarked cannot be removed here:
          // removing deletes the entry, and with it the bookmark.
          // On the Bookmarks tab the bookmark button already removes it, so a
          // second remove button would do the same thing.
          onRemove:
              recent && !item.isFavorite ? () => store.remove(item.url) : null,
        );
      },
    );
  }
}

/// One bookmark / recent entry. Actions appear on hover or keyboard focus.
class BookmarkCard extends StatefulWidget {
  const BookmarkCard({
    super.key,
    required this.item,
    required this.recent,
    required this.onPlayHere,
    required this.onToggleBookmark,
    required this.onRemove,
    this.onPlayOnTarget,
    this.playOnTargetLabel = 'Play on TV',
  });

  final PlayHistoryItem item;
  final bool recent;
  final VoidCallback onPlayHere;
  final VoidCallback onToggleBookmark;

  /// Null hides the remove button.
  final VoidCallback? onRemove;
  final VoidCallback? onPlayOnTarget;
  final String playOnTargetLabel;

  @override
  State<BookmarkCard> createState() => _BookmarkCardState();
}

class _BookmarkCardState extends State<BookmarkCard> {
  bool _hovered = false;
  bool _focusWithin = false;

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final showActions = _hovered || _focusWithin;
    final hasTarget = widget.onPlayOnTarget != null;
    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: Focus(
        canRequestFocus: false,
        skipTraversal: true,
        onFocusChange: (v) => setState(() => _focusWithin = v),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: showActions ? PbColors.raised : Colors.transparent,
            borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // The thumbnail, title and meta are one focusable play target.
              Expanded(
                child: PbPressable(
                  onTap: hasTarget ? widget.onPlayOnTarget : widget.onPlayHere,
                  semanticLabel: item.title,
                  builder: (context, hovered, focused) => DecoratedBox(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(PbMetrics.navRadius),
                      border: focused
                          ? Border.all(color: PbColors.accent, width: 2)
                          : null,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Expanded(child: _Thumbnail(item: item)),
                        const SizedBox(height: 8),
                        Text(
                          item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                            color: PbColors.text,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          cardMetaText(item),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 13,
                            color: PbColors.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              // Kept in the tree while hidden so Tab can reach it; focusing
              // any action reveals the row.
              IgnorePointer(
                ignoring: !showActions,
                child: AnimatedOpacity(
                  duration: const Duration(milliseconds: 120),
                  opacity: showActions ? 1 : 0,
                  child: Row(
                    children: [
                      // Icons only; the tooltip says what each one does.
                      if (hasTarget) ...[
                        PbIconButton(
                          icon: Icons.cast,
                          tooltip: widget.playOnTargetLabel,
                          variant: PbButtonVariant.primary,
                          onPressed: widget.onPlayOnTarget,
                        ),
                        const SizedBox(width: 6),
                      ],
                      PbIconButton(
                        icon: Icons.play_arrow,
                        tooltip: 'Play here',
                        variant: hasTarget
                            ? PbButtonVariant.secondary
                            : PbButtonVariant.primary,
                        onPressed: widget.onPlayHere,
                      ),
                      const Spacer(),
                      PbIconButton(
                        icon: item.isFavorite
                            ? Icons.bookmark
                            : Icons.bookmark_border,
                        tooltip: item.isFavorite
                            ? 'Remove bookmark'
                            : 'Add bookmark',
                        variant: PbButtonVariant.secondary,
                        onPressed: widget.onToggleBookmark,
                      ),
                      if (widget.onRemove != null) ...[
                        const SizedBox(width: 6),
                        PbIconButton(
                          icon: Icons.close,
                          tooltip: widget.recent
                              ? 'Remove from recent'
                              : 'Remove bookmark',
                          variant: PbButtonVariant.secondary,
                          onPressed: widget.onRemove,
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Neutral placeholder block: the store keeps no thumbnails, so this never
/// pretends to be a picture.
class _Thumbnail extends StatelessWidget {
  const _Thumbnail({required this.item});

  final PlayHistoryItem item;

  @override
  Widget build(BuildContext context) {
    final icon = switch (item.mediaKind) {
      'audio' => Icons.music_note,
      'image' => Icons.image_outlined,
      _ => Icons.play_arrow_rounded,
    };
    final placeholder = Container(
      alignment: Alignment.center,
      color: PbColors.thumbnail,
      child: Icon(icon, size: 36, color: PbColors.onSelectedSecondary),
    );
    // The cast's own artwork first, then the frame captured on bookmarking.
    final image = item.imageUrl;
    final frame = item.thumbnailPath;
    final Widget picture;
    if (image != null && image.isNotEmpty) {
      picture = Image.network(
        image,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => placeholder,
      );
    } else if (frame != null && frame.isNotEmpty) {
      picture = Image.file(
        File(frame),
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => placeholder,
      );
    } else {
      picture = placeholder;
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(PbMetrics.navRadius),
      child: SizedBox.expand(child: picture),
    );
  }
}

/// "8 min · 2h ago": only what the store actually holds (length when known,
/// and when it was last played).
String cardMetaText(PlayHistoryItem item, {DateTime? now}) {
  final parts = <String>[];
  final ms = item.displayDurationMs;
  if (ms != null && ms > 0) parts.add(formatLength(ms));
  parts.add(timeAgo(item.playedAt, now: now));
  return parts.join(' · ');
}

String formatLength(int ms) {
  if (ms < 60000) return '${(ms / 1000).round().clamp(1, 59)} sec';
  final minutes = (ms / 60000).round();
  if (minutes < 60) return '$minutes min';
  final h = minutes ~/ 60;
  final m = minutes % 60;
  return m == 0 ? '$h h' : '$h h $m min';
}

String timeAgo(DateTime dt, {DateTime? now}) {
  final d = (now ?? DateTime.now()).difference(dt);
  if (d.inMinutes < 1) return 'Just now';
  if (d.inMinutes < 60) return '${d.inMinutes} min ago';
  if (d.inHours < 24) return '${d.inHours} h ago';
  if (d.inDays < 7) return '${d.inDays} d ago';
  return '${dt.day}/${dt.month}/${dt.year}';
}
