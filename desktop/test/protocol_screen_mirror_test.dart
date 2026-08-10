import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/protocol.dart';

void main() {
  const sessionId = '65b616a4-98d7-441b-831b-5be94ea65b06';

  test('parses the versioned screen mirror signaling commands', () {
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'screen_mirror_start',
        'payload': {'sessionId': sessionId, 'protocolVersion': 1},
      })),
      isA<ScreenMirrorStartCmd>()
          .having((command) => command.sessionId, 'sessionId', sessionId),
    );
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'screen_mirror_offer',
        'payload': {'sessionId': sessionId, 'sdp': 'v=0\r\n'},
      })),
      isA<ScreenMirrorOfferCmd>()
          .having((command) => command.sdp, 'sdp', 'v=0\r\n'),
    );
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'screen_mirror_candidate',
        'payload': {
          'sessionId': sessionId,
          'sdpMid': '0',
          'sdpMLineIndex': 0,
          'candidate': 'candidate:1 1 udp 1 192.168.1.40 1234 typ host',
        },
      })),
      isA<ScreenMirrorCandidateCmd>()
          .having((command) => command.sdpMid, 'sdpMid', '0')
          .having((command) => command.sdpMLineIndex, 'index', 0),
    );
    expect(
      parseCommand(jsonEncode({
        'type': 'command',
        'action': 'screen_mirror_stop',
        'payload': {'sessionId': sessionId, 'reason': 'user_stopped'},
      })),
      isA<ScreenMirrorStopCmd>()
          .having((command) => command.reason, 'reason', 'user_stopped'),
    );
  });

  test('rejects unsupported versions and malformed session ids', () {
    for (final payload in [
      {'sessionId': sessionId},
      {'sessionId': sessionId, 'protocolVersion': 2},
      {'sessionId': 'not-a-uuid', 'protocolVersion': 1},
    ]) {
      expect(
        parseCommand(jsonEncode({
          'type': 'command',
          'action': 'screen_mirror_start',
          'payload': payload,
        })),
        isA<UnknownCmd>(),
      );
    }
  });
}
