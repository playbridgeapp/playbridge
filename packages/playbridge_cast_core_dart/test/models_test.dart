import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:test/test.dart';

void main() {
  test('decodes a discovered receiver', () {
    final event = ReceiverEvent.fromJsonString('''
      {
        "event":"found",
        "receiver":{
          "id":"playbridge:receiver-1",
          "protocol":"PlayBridge",
          "name":"Living Room",
          "addresses":["192.0.2.4"],
          "port":8765,
          "wss_port":8766,
          "location":null,
          "uuid":"receiver-1"
        }
      }
    ''');

    expect(event, isA<ReceiverFound>());
    final receiver = (event as ReceiverFound).receiver;
    expect(receiver.protocol, ReceiverProtocol.playBridge);
    expect(receiver.addresses, ['192.0.2.4']);
    expect(receiver.port, 8765);
    expect(receiver.wssPort, 8766);
  });

  test('protocol masks remain compatible with the C ABI', () {
    expect(ReceiverProtocol.playBridge.mask, 1);
    expect(ReceiverProtocol.dlna.mask, 2);
    expect(ReceiverProtocol.roku.mask, 4);
    expect(ReceiverProtocol.dial.mask, 8);
  });

  test('rejects unknown events', () {
    expect(
      () => ReceiverEvent.fromJsonString('{"event":"future"}'),
      throwsFormatException,
    );
  });

  test('serializes native receiver endpoints', () {
    final endpoint = ReceiverEndpoint(
      protocol: ReceiverProtocol.googleCast,
      addresses: ['2001:db8::1', '192.0.2.8'],
      port: 8009,
    );

    expect(endpoint.toJson(), {
      'protocol': 'google_cast',
      'addresses': ['2001:db8::1', '192.0.2.8'],
      'port': 8009,
    });
    expect(
      () => ReceiverEndpoint(
        protocol: ReceiverProtocol.playBridge,
        addresses: ['192.0.2.1'],
      ).toJson(),
      throwsStateError,
    );
  });

  test('allows a DLNA endpoint identified only by its description URL', () {
    final endpoint = ReceiverEndpoint(
      protocol: ReceiverProtocol.dlna,
      addresses: const [],
      location: 'http://192.0.2.12:1400/device.xml',
    );

    expect(endpoint.toJson(), {
      'protocol': 'dlna',
      'addresses': <String>[],
      'location': 'http://192.0.2.12:1400/device.xml',
    });
  });

  test('decodes connected capabilities', () {
    final event = CastSessionEvent.fromJsonString('''
      {
        "event":"connected",
        "protocol":"roku",
        "capabilities":{
          "load":true,
          "playback_control":true,
          "seek":false,
          "status":true,
          "receiver_app_available":false
        },
        "name":"Living Room Roku"
      }
    ''');

    expect(event, isA<CastSessionConnected>());
    final connected = event as CastSessionConnected;
    expect(connected.protocol, ReceiverProtocol.roku);
    expect(connected.capabilities.load, isTrue);
    expect(connected.capabilities.seek, isFalse);
    expect(connected.capabilities.receiverAppAvailable, isFalse);
  });

  test('decodes status with fractional seconds', () {
    final event = CastSessionEvent.fromJsonString('''
      {
        "event":"status",
        "request_id":42,
        "status":{
          "state":"playing",
          "position_seconds":12.25,
          "duration_seconds":90.5
        }
      }
    ''') as CastSessionStatus;

    expect(event.requestId, '42');
    expect(event.status.state, PlaybackState.playing);
    expect(event.status.position, const Duration(milliseconds: 12250));
    expect(event.status.durationSeconds, 90.5);
  });

  test('decodes correlated and connection errors', () {
    final correlated = CastSessionEvent.fromJsonString(
      '{"event":"error","request_id":"7","operation":"load",'
      '"message":"unsupported media"}',
    ) as CastSessionError;
    final connection = CastSessionEvent.fromJsonString(
      '{"event":"error","operation":"connect","message":"offline"}',
    ) as CastSessionError;

    expect(correlated.requestId, '7');
    expect(correlated.operation, 'load');
    expect(connection.requestId, isNull);
    expect(connection.toString(), contains('offline'));
  });

  test('rejects malformed session events', () {
    expect(
      () => CastSessionEvent.fromJsonString('{"event":"operation"}'),
      throwsA(anything),
    );
    expect(
      () => CastSessionEvent.fromJsonString('{"event":"future"}'),
      throwsFormatException,
    );
  });
}
