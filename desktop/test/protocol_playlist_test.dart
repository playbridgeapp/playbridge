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
