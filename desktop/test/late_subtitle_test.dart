import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/late_subtitle_loader.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/protocol.dart';

void main() {
  test('parses late subtitle with resource-specific headers', () {
    final command = parseCommand(jsonEncode({
      'type': 'command',
      'action': 'control',
      'payload': {
        'command': 'add_subtitle',
        'subtitleResource': {
          'url': 'https://subs.example/movie.vtt',
          'headers': {'Origin': 'https://page.example'},
          'label': 'English',
        },
      },
    }));
    expect(command, isA<ControlCmd>());
    final control = command as ControlCmd;
    expect(control.subtitleResource?.headers['Origin'], 'https://page.example');
    expect(control.subtitleResource?.label, 'English');
  });

  test('rejects malformed and header-injecting late subtitle resources', () {
    expect(
        validLateSubtitleResource(const SubtitleRequest(
          url: 'https://subs.example/movie.vtt',
          headers: {'Origin': 'https://page.example'},
        )),
        isTrue);
    expect(
        validLateSubtitleResource(const SubtitleRequest(
          url: 'file:///tmp/movie.vtt',
        )),
        isFalse);
    expect(
        validLateSubtitleResource(const SubtitleRequest(
          url: 'https://user:secret@subs.example/movie.vtt',
        )),
        isFalse);
    expect(
        validLateSubtitleResource(const SubtitleRequest(
          url: 'https://subs.example/movie.vtt',
          headers: {'Origin': 'https://page.example\r\nHost: other.example'},
        )),
        isFalse);
  });
}
