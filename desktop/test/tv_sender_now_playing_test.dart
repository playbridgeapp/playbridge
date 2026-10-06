import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _RecordingTransport implements TvTransport {
  bool acceptsControls = true;
  bool acceptsLoads = true;
  Future<bool>? pendingQueue;

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Future<bool> castVideo(PlayPayload video) async => acceptsLoads;

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async => acceptsLoads;

  @override
  Future<bool> queueAdd(PlayPayload item) async =>
      await (pendingQueue ?? Future.value(acceptsLoads));

  @override
  Future<bool> sendControl(String command) async => acceptsControls;

  @override
  Future<void> dispose() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<(TvSenderController, _RecordingTransport)> makeSender({
    Future<StreamProxyLease> Function(Iterable<String>)? retainProxyUrls,
  }) async {
    final transport = _RecordingTransport();
    final sender = TvSenderController(
      identity: await PairingStore.load(),
      store: await TvConnectionStore.load(),
      transport: transport,
      retainProxyUrls: retainProxyUrls,
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

  test('transient receiver states change UI but do not release proxy ownership',
      () async {
    var closed = 0;
    final (sender, _) = await makeSender(
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await sender
        .castVideo(PlayPayload(url: 'http://phone/video', title: 'Video'));
    for (final message in [
      '{"type":"status","state":"idle"}',
      '{"type":"status","state":"stopped"}',
      '{"type":"status","state":"ended"}',
      '{"type":"error","message":"temporary"}',
      '{"type":"context","active":"idle"}',
      '{"type":"playlist_status","items":[]}',
    ]) {
      sender.handleReceiverMessage(message);
    }
    expect(closed, 0);
    await sender.stopCast();
    expect(closed, 1);
    await sender.stopCast();
    expect(closed, 1);
  });

  test('successful replacement releases the old bundle and queue leases',
      () async {
    final closed = <String>[];
    final (sender, _) = await makeSender(retainProxyUrls: (urls) async {
      final values = urls.toList();
      return StreamProxyLease(() {
        closed.addAll(values);
      });
    });
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    await sender.queueAdd(PlayPayload(url: 'http://phone/queued'));
    await sender.castPlaylist(
        PlaylistPayload(items: [PlayPayload(url: 'http://phone/new')]));
    expect(closed, ['http://phone/old', 'http://phone/queued']);
    await sender.stopCast();
    expect(closed,
        ['http://phone/old', 'http://phone/queued', 'http://phone/new']);
  });

  test('failed replacement and append keep existing playback ownership',
      () async {
    final closed = <String>[];
    final (sender, transport) = await makeSender(retainProxyUrls: (urls) async {
      final values = urls.toList();
      return StreamProxyLease(() {
        closed.addAll(values);
      });
    });
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    transport.acceptsLoads = false;
    expect(await sender.castVideo(PlayPayload(url: 'http://phone/failed')),
        isFalse);
    expect(await sender.queueAdd(PlayPayload(url: 'http://phone/failed-queue')),
        isFalse);
    expect(closed, ['http://phone/failed', 'http://phone/failed-queue']);
    await sender.stopCast();
    expect(closed.last, 'http://phone/old');
  });

  test('late queue completion after stop cannot resurrect ownership', () async {
    final closed = <String>[];
    final (sender, transport) = await makeSender(retainProxyUrls: (urls) async {
      final values = urls.toList();
      return StreamProxyLease(() {
        closed.addAll(values);
      });
    });
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    final pending = Completer<bool>();
    transport.pendingQueue = pending.future;
    final addition = sender.queueAdd(PlayPayload(url: 'http://phone/queued'));
    await Future<void>.delayed(Duration.zero);
    await sender.stopCast();
    pending.complete(true);
    await addition;
    expect(closed, ['http://phone/old', 'http://phone/queued']);
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
