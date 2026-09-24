import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _RecordingTransport implements TvTransport {
  bool acceptsControls = true;

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Future<bool> castVideo(PlayPayload video) async => true;

  @override
  Future<bool> sendControl(String command) async => acceptsControls;

  @override
  Future<void> dispose() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<(TvSenderController, _RecordingTransport)> makeSender() async {
    final transport = _RecordingTransport();
    final sender = TvSenderController(
      identity: await PairingStore.load(),
      store: await TvConnectionStore.load(),
      transport: transport,
    );
    addTearDown(sender.dispose);
    return (sender, transport);
  }

  test('stop rejects late old status but accepts the next cast immediately',
      () async {
    final (sender, _) = await makeSender();
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"Old",'
        '"playbackId":"old"}');
    expect(sender.castingTitle, 'Old');

    expect(await sender.stopCast(), isTrue);
    expect(sender.isCasting, isFalse);
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","title":"Old",'
        '"playbackId":"old"}');
    sender
        .handleReceiverMessage('{"type":"playlist_status","items":[{"index":0,'
            '"title":"Old"}],"playbackId":"old"}');
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","title":"Old"}');
    expect(sender.isCasting, isFalse);

    expect(
        await sender.castVideo(
            PlayPayload(url: 'https://example.com/new', title: 'New')),
        isTrue);
    expect(sender.castingTitle, 'New');
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","title":"Old"}');
    expect(sender.castingTitle, 'New');

    sender.handleReceiverMessage('{"type":"context","active":"player"}');
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","title":"Old"}');
    expect(sender.castingTitle, 'New');
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"New",'
        '"playbackId":"new"}');
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","title":"Old",'
        '"playbackId":"old"}');
    expect(sender.castingTitle, 'New');
    expect(sender.remoteState, 'playing');
  });

  test('idle and empty playlist each clear stale Now Playing state', () async {
    final (sender, _) = await makeSender();
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"One",'
        '"playbackId":"one"}');
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    expect(sender.isCasting, isFalse);
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"One"}');
    expect(sender.isCasting, isFalse);

    sender.handleReceiverMessage('{"type":"context","active":"player"}');
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"Two",'
        '"playbackId":"two"}');
    sender.handleReceiverMessage(
        '{"type":"playlist_status","items":[],"currentIndex":0}');
    expect(sender.isCasting, isFalse);
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"Two"}');
    expect(sender.isCasting, isFalse);
  });

  test('failed stop leaves the current playback visible', () async {
    final (sender, transport) = await makeSender();
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"Current",'
        '"playbackId":"current"}');
    transport.acceptsControls = false;
    expect(await sender.stopCast(), isFalse);
    expect(sender.castingTitle, 'Current');
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","title":"Current",'
        '"playbackId":"current"}');
    expect(sender.remoteState, 'paused');
  });

  test('a legacy receiver without playback IDs resumes on a new context',
      () async {
    final (sender, _) = await makeSender();
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"Old"}');
    expect(await sender.stopCast(), isTrue);
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","title":"Old"}');
    expect(sender.isCasting, isFalse);

    sender.handleReceiverMessage('{"type":"context","active":"player"}');
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","title":"New"}');
    expect(sender.castingTitle, 'New');
  });
}
