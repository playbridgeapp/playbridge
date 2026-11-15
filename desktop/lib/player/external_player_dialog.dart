import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../engines/tv_cast_media_preparer.dart';
import '../player_engine.dart';
import '../stream_proxy_server.dart';
import '../ui/pb_theme.dart';

/// Prepares the current item's stream for an external player (mpv or VLC) and
/// shows the copyable URL and commands. Moved out of the controls bar unchanged
/// in behaviour; only the wording and colours follow the redesign.
Future<void> openInExternalPlayer(
  BuildContext context,
  QueueItem item,
) async {
  // Prefer original CDN URL + demuxed handoff fields. A bare proxied
  // video-only playlist loses companion audio and the quality ladder.
  final sourceUrl = item.originalUrl ?? item.url;
  final headers = item.originalHeaders ?? item.headers ?? const {};
  String proxiedUrl = item.url;

  try {
    final prepared = await TvCastMediaPreparer.prepare(
      url: sourceUrl,
      lanHost: '127.0.0.1',
      headers: headers,
      playlistBody: item.playlistBody,
      audioUrl: item.audioUrl,
    );
    proxiedUrl = prepared.url;
    stdout.writeln(
      '[external-player] prepared ${prepared.strategy} → $proxiedUrl',
    );
  } catch (e) {
    stdout.writeln('[external-player] prepare failed: $e');
    // Fall back: proxy the single URL if it is not already loopback.
    if (!sourceUrl.contains('127.0.0.1') && !sourceUrl.contains('localhost')) {
      try {
        proxiedUrl = await StreamProxyServer.instance.registerSession(
          sourceUrl,
          headers,
          allowedPrivateOrigins: item.proxyAllowedPrivateOrigins,
          remoteOrigin: item.proxyRemoteOrigin,
        );
      } catch (e2) {
        stdout
            .writeln('[external-player] Failed to register proxy session: $e2');
      }
    }
  }
  if (!context.mounted) return;

  showDialog(
    context: context,
    builder: (dialogCtx) {
      return AlertDialog(
        backgroundColor: PbColors.playerSurface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: Colors.white10),
        ),
        title: const Row(
          children: [
            Icon(Icons.open_in_new, color: PbColors.accent),
            SizedBox(width: 10),
            Text(
              'Open in another player',
              style: TextStyle(color: Colors.white, fontSize: 16),
            ),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Copy this stream address to play it in another player on this computer:',
              style: TextStyle(color: PbColors.playerText, fontSize: 13),
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
              decoration: BoxDecoration(
                color: Colors.black38,
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: Colors.white10),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      proxiedUrl,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white,
                        fontFamily: 'monospace',
                        fontSize: 12,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    tooltip: 'Copy URL',
                    iconSize: 16,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                    icon: const Icon(Icons.copy, color: Colors.white54),
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: proxiedUrl));
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('URL copied'),
                          duration: Duration(seconds: 2),
                        ),
                      );
                    },
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            const Text(
              'Terminal commands:',
              style: TextStyle(
                color: PbColors.accent,
                fontWeight: FontWeight.bold,
                fontSize: 12,
              ),
            ),
            const SizedBox(height: 8),
            _buildCommandRow(context, 'MPV', 'mpv "$proxiedUrl"'),
            const SizedBox(height: 6),
            _buildCommandRow(context, 'VLC', 'vlc "$proxiedUrl"'),
            const SizedBox(height: 14),
            const Text(
              'How to play:\n1. Open your terminal / command prompt.\n2. Copy and paste one of the command lines above, then press Enter.',
              style:
                  TextStyle(color: Colors.white54, fontSize: 11, height: 1.4),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogCtx).pop(),
            child: const Text('Close',
                style: TextStyle(color: PbColors.playerText)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: PbColors.accent,
              foregroundColor: PbColors.background,
            ),
            onPressed: () async {
              Navigator.of(dialogCtx).pop();
              await _launchDirectly(proxiedUrl);
            },
            child: const Text('Launch automatically'),
          ),
        ],
      );
    },
  );
}

Widget _buildCommandRow(BuildContext context, String app, String command) {
  return Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
    decoration: BoxDecoration(
      color: Colors.black12,
      borderRadius: BorderRadius.circular(4),
    ),
    child: Row(
      children: [
        SizedBox(
          width: 40,
          child: Text(
            app,
            style: const TextStyle(
              color: Colors.white38,
              fontWeight: FontWeight.bold,
              fontSize: 11,
            ),
          ),
        ),
        Expanded(
          child: Text(
            command,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: PbColors.playerText,
              fontFamily: 'monospace',
              fontSize: 11,
            ),
          ),
        ),
        const SizedBox(width: 6),
        IconButton(
          tooltip: 'Copy command',
          iconSize: 14,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(),
          icon: const Icon(Icons.copy, color: Colors.white30),
          onPressed: () {
            Clipboard.setData(ClipboardData(text: command));
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text('$app command copied'),
                duration: const Duration(seconds: 2),
              ),
            );
          },
        ),
      ],
    ),
  );
}

Future<bool> _launchDirectly(String url) async {
  if (Platform.isMacOS) {
    try {
      await Process.start('open', ['-a', 'mpv', url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
    try {
      await Process.start('open', ['-a', 'VLC', url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
    try {
      await Process.start('open', [url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
  } else if (Platform.isWindows) {
    try {
      await Process.start('mpv.exe', [url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
    try {
      await Process.start('vlc.exe', [url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
    try {
      await Process.start('cmd.exe', ['/c', 'start', '', url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
  } else if (Platform.isLinux) {
    try {
      await Process.start('mpv', [url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
    try {
      await Process.start('vlc', [url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
    try {
      await Process.start('xdg-open', [url]);
      return true;
    } catch (_) {
      // Launcher missing; try the next one. The URL must not be logged.
    }
  }
  debugPrint('[player] no external player could be launched');
  return false;
}
