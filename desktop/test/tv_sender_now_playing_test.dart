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
  Future<bool>? pendingLoad;
  int loads = 0;
  int queueAdds = 0;

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Future<bool> castVideo(PlayPayload video) async {
    loads++;
    return await (pendingLoad ?? Future.value(acceptsLoads));
  }

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async {
    loads++;
    return await (pendingLoad ?? Future.value(acceptsLoads));
  }

  @override
  Future<bool> queueAdd(PlayPayload item) async {
    queueAdds++;
    return await (pendingQueue ?? Future.value(acceptsLoads));
  }

  @override
  Future<bool> sendControl(String command) async => acceptsControls;

  @override
  Future<void> dispose() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _RecordingBrowserTransport extends _RecordingTransport
    implements BrowserTransport {
  @override
  TvProtocol get protocol => TvProtocol.webBrowser;

  @override
  Future<bool> castBrowserMedia(
      {required String url,
      String? title,
      String? contentType,
      String? posterUrl,
      String? subtitleUrl,
      Duration? startPosition}) async {
    loads++;
    return acceptsLoads;
  }
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<(TvSenderController, _RecordingTransport)> makeSender({
    Future<StreamProxyLease> Function(Iterable<String>)? retainProxyUrls,
    bool browser = false,
  }) async {
    final transport =
        browser ? _RecordingBrowserTransport() : _RecordingTransport();
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

  testWidgets(
      'sustained empty playlist releases without repeated idle extending grace',
      (tester) async {
    var closed = 0;
    final (sender, _) = await makeSender(
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await sender.castVideo(PlayPayload(url: 'http://phone/video'));
    sender.handleReceiverMessage('{"type":"playlist_status","items":[]}');
    await tester.pump(const Duration(minutes: 4));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    expect(closed, 0);
    await tester.pump(const Duration(minutes: 1));
    expect(closed, 1);
  });

  testWidgets(
      'same playback ID can resume and pause beyond the inactivity grace',
      (tester) async {
    var closed = 0;
    final (sender, _) = await makeSender(
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await sender.castVideo(PlayPayload(url: 'http://phone/video'));
    sender.handleReceiverMessage(
        '{"type":"status","state":"playing","playbackId":"current"}');
    sender.handleReceiverMessage(
        '{"type":"status","state":"idle","playbackId":"current"}');
    await tester.pump(const Duration(minutes: 1));
    sender.handleReceiverMessage(
        '{"type":"status","state":"paused","playbackId":"current"}');
    await tester.pump(const Duration(days: 2));
    expect(closed, 0);
    await sender.stopCast();
    expect(closed, 1);
  });

  testWidgets('legacy resumed playback also cancels idle grace',
      (tester) async {
    var closed = 0;
    final (sender, _) = await makeSender(
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await sender.castVideo(PlayPayload(url: 'http://phone/video'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    await tester.pump(const Duration(minutes: 1));
    sender.handleReceiverMessage('{"type":"status","state":"paused"}');
    await tester.pump(const Duration(hours: 12));
    expect(closed, 0);
  });

  testWidgets(
      'successful replacement cancels old grace but failed replacement does not',
      (tester) async {
    final closed = <String>[];
    final (sender, transport) = await makeSender(retainProxyUrls: (urls) async {
      final values = urls.toList();
      return StreamProxyLease(() {
        closed.addAll(values);
      });
    });
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    await sender.castVideo(PlayPayload(url: 'http://phone/new'));
    await tester.pump(TvSenderController.proxyIdleGrace);
    expect(closed, ['http://phone/old']);
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    transport.acceptsLoads = false;
    await sender.castVideo(PlayPayload(url: 'http://phone/failed'));
    await tester.pump(TvSenderController.proxyIdleGrace);
    expect(closed,
        ['http://phone/old', 'http://phone/failed', 'http://phone/new']);
  });

  testWidgets(
      'stored nonempty playlist does not cancel receiver-terminal grace',
      (tester) async {
    var closed = 0;
    final (sender, _) = await makeSender(
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await sender.castVideo(PlayPayload(url: 'http://phone/video'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    sender.handleReceiverMessage(
        '{"type":"playlist_status","items":[{"title":"Old"}]}');
    await tester.pump(TvSenderController.proxyIdleGrace);
    expect(closed, 1);
  });

  testWidgets('idle expiry during transport loading retires only held leases',
      (tester) async {
    final closed = <String>[];
    final (sender, transport) = await makeSender(retainProxyUrls: (urls) async {
      final values = urls.toList();
      return StreamProxyLease(() {
        closed.addAll(values);
      });
    });
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    final pending = Completer<bool>();
    transport.pendingLoad = pending.future;
    final loading = sender.castVideo(PlayPayload(url: 'http://phone/new'));
    await tester.pump();
    await tester.pump(TvSenderController.proxyIdleGrace);
    expect(closed, ['http://phone/old']);
    pending.complete(true);
    expect(await loading, isTrue);
    expect(closed, ['http://phone/old']);
    await sender.stopCast();
    expect(closed, ['http://phone/old', 'http://phone/new']);
  });

  testWidgets(
      'timer defers only acquisition so a shared registration cannot be revoked',
      (tester) async {
    var references = 0;
    var revoked = false;
    Completer<void>? scan;
    final (sender, _) = await makeSender(retainProxyUrls: (_) async {
      if (scan != null) await scan.future;
      if (revoked) throw StateError('registration already revoked');
      references++;
      return StreamProxyLease(() {
        if (--references == 0) revoked = true;
      });
    });
    await sender.castVideo(PlayPayload(url: 'http://phone/shared'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    await tester
        .pump(TvSenderController.proxyIdleGrace - const Duration(seconds: 1));
    scan = Completer<void>();
    final loading = sender.castPlaylist(
        PlaylistPayload(items: [PlayPayload(url: 'http://phone/shared')]));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(references, 1);
    expect(revoked, isFalse);
    scan.complete();
    expect(await loading, isTrue);
    expect(references, 1);
    await sender.stopCast();
    expect(revoked, isTrue);
  });

  testWidgets('failed acquisition releases a deferred retired bundle',
      (tester) async {
    var closed = 0;
    Completer<StreamProxyLease>? scan;
    final (sender, transport) = await makeSender(
        retainProxyUrls: (_) async => scan != null
            ? await scan.future
            : StreamProxyLease(() {
                closed++;
              }));
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    await tester
        .pump(TvSenderController.proxyIdleGrace - const Duration(seconds: 1));
    scan = Completer<StreamProxyLease>();
    final loading = sender.castVideo(PlayPayload(url: 'http://phone/new'));
    final failure = expectLater(loading, throwsStateError);
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(closed, 0);
    scan.completeError(StateError('scan failed'));
    await failure;
    expect(closed, 1);
    expect(transport.loads, 1);
  });

  testWidgets(
      'pause recovery cancels deferred idle retirement even when acquisition fails',
      (tester) async {
    var closed = 0;
    Completer<StreamProxyLease>? scan;
    final (sender, _) = await makeSender(
        retainProxyUrls: (_) async => scan != null
            ? await scan.future
            : StreamProxyLease(() {
                closed++;
              }));
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    await tester
        .pump(TvSenderController.proxyIdleGrace - const Duration(seconds: 1));
    scan = Completer<StreamProxyLease>();
    final loading = sender.castVideo(PlayPayload(url: 'http://phone/new'));
    final failure = expectLater(loading, throwsStateError);
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    sender.handleReceiverMessage('{"type":"status","state":"paused"}');
    scan.completeError(StateError('scan failed'));
    await failure;
    await tester.pump(TvSenderController.proxyIdleGrace);
    expect(closed, 0);
    await sender.stopCast();
    expect(closed, 1);
  });

  testWidgets('acquisition timeout is bounded and closes a late result',
      (tester) async {
    var closed = 0;
    Completer<StreamProxyLease>? scan;
    final (sender, transport) = await makeSender(
        retainProxyUrls: (_) async => scan != null
            ? await scan.future
            : StreamProxyLease(() {
                closed++;
              }));
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    sender.handleReceiverMessage('{"type":"context","active":"idle"}');
    await tester
        .pump(TvSenderController.proxyIdleGrace - const Duration(seconds: 1));
    scan = Completer<StreamProxyLease>();
    final loading = sender.castVideo(PlayPayload(url: 'http://phone/new'));
    final failure = expectLater(loading, completion(isFalse));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(closed, 0);
    await tester.pump(TvSenderController.proxyLeaseAcquisitionTimeout);
    await failure;
    expect(closed, 1);
    scan.complete(StreamProxyLease(() {
      closed++;
    }));
    await tester.pump();
    expect(closed, 2);
    expect(transport.loads, 1);
  });

  for (final operation in ['video', 'playlist', 'url', 'browser', 'queue']) {
    testWidgets(
        '$operation acquisition timeout returns false without disturbing held playback',
        (tester) async {
      var closed = 0;
      Completer<StreamProxyLease>? scan;
      final (sender, transport) = await makeSender(
          browser: operation == 'browser',
          retainProxyUrls: (_) async => scan != null
              ? await scan.future
              : StreamProxyLease(() {
                  closed++;
                }));
      await sender
          .castVideo(PlayPayload(url: 'https://example.invalid/old.mp4'));
      scan = Completer<StreamProxyLease>();
      final payload = PlayPayload(url: 'https://example.invalid/new.mp4');
      final loading = switch (operation) {
        'video' => sender.castVideo(payload),
        'playlist' => sender.castPlaylist(PlaylistPayload(items: [payload])),
        'queue' => sender.queueAdd(payload),
        _ => sender.castUrl(payload.url),
      };
      final failure = expectLater(loading, completion(isFalse));
      await tester.pump();
      await tester.pump(TvSenderController.proxyLeaseAcquisitionTimeout);
      await failure;
      expect(closed, 0);
      expect(transport.loads, 1);
      expect(transport.queueAdds, 0);
      scan.complete(StreamProxyLease(() {
        closed++;
      }));
      await tester.pump();
      expect(closed, 1);
      await sender.stopCast();
      expect(closed, 2);
    });
  }

  for (final detach in [false, true]) {
    testWidgets(
        '${detach ? 'dispose' : 'stop'} during acquisition prevents a stale send',
        (tester) async {
      var closed = 0;
      Completer<StreamProxyLease>? scan;
      final (sender, transport) = await makeSender(
          retainProxyUrls: (_) async => scan != null
              ? await scan.future
              : StreamProxyLease(() {
                  closed++;
                }));
      await sender.castVideo(PlayPayload(url: 'http://phone/old'));
      scan = Completer<StreamProxyLease>();
      final loading = sender.castVideo(PlayPayload(url: 'http://phone/new'));
      await tester.pump();
      if (detach) {
        sender.dispose();
      } else {
        await sender.stopCast();
      }
      expect(closed, 1);
      scan.complete(StreamProxyLease(() {
        closed++;
      }));
      expect(await loading, isFalse);
      expect(closed, 2);
      expect(transport.loads, 1);
    });
  }

  testWidgets('explicit stop during load still invalidates pending ownership',
      (tester) async {
    var closed = 0;
    final (sender, transport) = await makeSender(
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await sender.castVideo(PlayPayload(url: 'http://phone/old'));
    final pending = Completer<bool>();
    transport.pendingLoad = pending.future;
    final loading = sender.castVideo(PlayPayload(url: 'http://phone/new'));
    await tester.pump();
    await sender.stopCast();
    pending.complete(true);
    expect(await loading, isFalse);
    expect(closed, 2);
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
