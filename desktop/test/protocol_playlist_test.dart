import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/protocol.dart';

void main() {
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
}
