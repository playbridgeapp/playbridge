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
}
