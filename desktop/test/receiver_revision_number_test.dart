import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/receiver_capability_policy.dart';

/// A session that is authenticated and subscribed, as after a successful auth.
ReceiverProtocolSession _subscribedSession() {
  final session = ReceiverProtocolSession();
  final generation = session.begin(1);
  session.acceptCredentials(generation, {
    'features': ['receiver_capabilities_v1'],
    'capabilities': {'version': 1, 'surfaces': {}},
  });
  expect(session.subscription(generation), isNotNull);
  return session;
}

bool _observeRevision(ReceiverProtocolSession session, Object? revision) =>
    session.observe(session.generation, {
      'type': 'receiver_capabilities',
      'revision': revision,
      'capabilities': {'version': 1, 'surfaces': {}},
    });

void main() {
  const max = 9007199254740991;

  test('accepts integral numbers in range, including 1.0 and 1e0', () {
    for (final revision in [1, 1.0, 1e0, max, max.toDouble()]) {
      final session = _subscribedSession();
      expect(_observeRevision(session, revision), isTrue, reason: '$revision');
    }
  });

  test('rejects fractions, strings, zero, negatives, and 2^53 and above', () {
    for (final revision in [
      1.5,
      9007199254740992,
      9007199254740992.0,
      '1',
      0,
      0.0,
      -1
    ]) {
      final session = _subscribedSession();
      expect(_observeRevision(session, revision), isFalse, reason: '$revision');
      expect(session.revision, 0);
    }
  });

  test('a rounded value near 2^53 is accepted, as the rule states', () {
    // Binary floating point rounds 9007199254740991.1 to 2^53 - 1.
    final session = _subscribedSession();
    expect(_observeRevision(session, 9007199254740991.1), isTrue);
    expect(session.revision, max);
  });
}
