import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/receiver_server.dart';

void main() {
  test('queue add failure reports asynchronous playback replacement', () {
    expect(
      queueAddFailureError(
        startingPlaybackId: 'playback-1',
        currentPlaybackId: 'playback-2',
        queueLength: 3,
        itemCount: 1,
      ),
      'stale_playback',
    );
    expect(
      queueAddFailureError(
        startingPlaybackId: 'playback-1',
        currentPlaybackId: null,
        queueLength: 0,
        itemCount: 1,
      ),
      'no_active_playback',
    );
  });

  test('parses the playlist pre-play preference', () {
    final command = parseCommand(jsonEncode({
      'type': 'command',
      'action': 'playlist',
      'payload': {
        'items': [
          {'url': 'https://media.example/song.mp3'}
        ],
        'startIndex': 0,
        'skipPreplay': true,
      },
    }));

    expect(
      command,
      isA<PlaylistCmd>()
          .having((value) => value.skipPreplay, 'skipPreplay', isTrue),
    );
  });

  test('mixed playlist retains per-item presentation fields', () {
    final command = parseCommand(jsonEncode({
      'type': 'command',
      'action': 'playlist',
      'payload': {
        'items': [
          {'url': 'https://media.example/video.mp4', 'mediaKind': 'video'},
          {'url': 'https://media.example/song.mp3', 'mediaKind': 'audio'},
          {
            'url': 'https://media.example/photo.jpg',
            'mediaKind': 'image',
            'displayDurationMs': 10000,
          },
        ],
      },
    })) as PlaylistCmd;

    expect(command.items.map((item) => item.mediaKind),
        ['video', 'audio', 'image']);
    expect(command.items.last.displayDurationMs.toInt(), 10000);
  });

  test('parses compact-pointer mouse command envelopes', () {
    final command = parseCommand(jsonEncode({
      'type': 'command',
      'action': 'mouse',
      'payload': {'event': 'zoom', 'dx': 1.25, 'dy': 0},
    }));

    expect(
      command,
      isA<MouseCmd>()
          .having((value) => value.event, 'event', 'zoom')
          .having((value) => value.dx, 'dx', 1.25),
    );
  });

  test('playlist pre-play preference defaults to false', () {
    final command = parseCommand(jsonEncode({
      'type': 'command',
      'action': 'playlist',
      'payload': {
        'items': [
          {'url': 'https://media.example/video.mp4'}
        ],
      },
    })) as PlaylistCmd;

    expect(command.skipPreplay, isFalse);
  });

  test('parses guarded batch queue operations', () {
    final add = parseCommand(jsonEncode({
      'type': 'command',
      'action': 'queue_add',
      'payload': {
        'items': [
          {'url': 'https://media.example/one.mp4'},
          {'url': 'https://media.example/two.mp4'},
        ],
        'ifPlaybackId': 'playback-1',
      },
    })) as QueueAddCmd;
    expect(add.items, hasLength(2));
    expect(add.ifPlaybackId, 'playback-1');

    final move = parseCommand(jsonEncode({
      'type': 'command',
      'action': 'queue_move',
      'payload': {
        'itemId': 'item-2',
        'beforeItemId': 'item-1',
        'ifPlaybackId': 'playback-1',
      },
    })) as QueueMoveCmd;
    expect(move.itemId, 'item-2');
    expect(move.beforeItemId, 'item-1');
    expect(move.ifPlaybackId, 'playback-1');
  });

  test('parses queue query, remove, clear, and stable-id jump', () {
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'queue_query',
        'payload': {},
      })),
      isA<QueueQueryCmd>(),
    );
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'queue_remove',
        'payload': {
          'itemIds': ['item-1']
        },
      })),
      isA<QueueRemoveCmd>()
          .having((value) => value.itemIds, 'itemIds', ['item-1']),
    );
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'queue_clear',
        'payload': {'ifPlaybackId': 'playback-1'},
      })),
      isA<QueueClearCmd>().having(
        (value) => value.ifPlaybackId,
        'ifPlaybackId',
        'playback-1',
      ),
    );
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'playlist_jump',
        'payload': {'itemId': 'item-3'},
      })),
      isA<PlaylistJumpCmd>()
          .having((value) => value.itemId, 'itemId', 'item-3'),
    );
  });
}
