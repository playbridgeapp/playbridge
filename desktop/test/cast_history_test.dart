import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/history_store.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _RecordingTransport implements TvTransport {
  PlayPayload? video;
  PlaylistPayload? playlist;
  PlayPayload? queued;
  @override
  TvProtocol get protocol => TvProtocol.playBridge;
  @override
  Future<bool> castVideo(PlayPayload video) async {
    this.video = video;
    return true;
  }

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async {
    this.playlist = playlist;
    return true;
  }

  @override
  Future<bool> queueAdd(PlayPayload item) async {
    queued = item;
    return true;
  }

  @override
  Future<void> dispose() async {}
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test(
      'sender preference persists and marks all media paths without mutating inputs',
      () async {
    final identity = await PairingStore.load();
    expect(identity.preventReceiverHistory, isFalse);
    final transport = _RecordingTransport();
    final sender = TvSenderController(
      identity: identity,
      store: await TvConnectionStore.load(),
      transport: transport,
    );
    addTearDown(sender.dispose);
    final video = PlayPayload(url: 'https://example.com/video', title: 'Video');
    await sender.castVideo(video);
    expect(transport.video!.skipHistory, isFalse);

    await identity.setPreventReceiverHistory(true);
    expect((await PairingStore.load()).preventReceiverHistory, isTrue);
    await sender.castVideo(video);
    expect(transport.video!.skipHistory, isTrue);
    expect(video.hasSkipHistory(), isFalse);

    final playlist = PlaylistPayload(
        items: [video, video], startIndex: 1, skipPreplay: true);
    await sender.castPlaylist(playlist);
    expect(transport.playlist!.items.every((item) => item.skipHistory), isTrue);
    expect(transport.playlist!.startIndex, 1);
    expect(transport.playlist!.skipPreplay, isTrue);
    expect(playlist.items.every((item) => !item.hasSkipHistory()), isTrue);
    await sender.queueAdd(video);
    expect(transport.queued!.skipHistory, isTrue);

    await identity.setPreventReceiverHistory(false);
    await sender.castVideo(video);
    expect(transport.video!.skipHistory, isFalse);
  });

  test(
      'receiver carries wire privacy into history storage and preserves existing entries',
      () async {
    final history = await HistoryStore.load();
    const url = 'https://example.com/video';
    await history.addOrBump(url: url, title: 'Existing');
    await history.toggleFavorite(url);
    final prefs = await SharedPreferences.getInstance();
    final before = prefs.getString('pb.history_v1');

    final command = parseCommand(senderSingleVideoCommandJson(
      PlayPayload(url: url, title: 'Private', skipHistory: true),
    )) as PlaylistCmd;
    final item = receiverQueueItemFromPayload(command.items.single);
    expect(item.skipHistory, isTrue);
    await history.addOrBump(
        url: item.url, title: item.title, skipHistory: item.skipHistory);
    await history.addOrBump(
        url: 'https://example.com/new-private',
        title: 'Private',
        skipHistory: true);
    expect(prefs.getString('pb.history_v1'), before);
    expect(history.items.single.title, 'Existing');
    expect(history.items.single.isFavorite, isTrue);

    final queued = parseCommand(senderQueueAddJson(
      PlayPayload(url: url, skipHistory: true),
    )) as QueueAddCmd;
    expect(receiverQueueItemFromPayload(queued.item).skipHistory, isTrue);
    final publicItem =
        receiverQueueItemFromPayload(PlayPayload(url: url, title: 'Public'));
    await history.addOrBump(
        url: publicItem.url,
        title: publicItem.title,
        skipHistory: publicItem.skipHistory);
    expect((await HistoryStore.load()).items.single.title, 'Public');
    expect(history.items.single.isFavorite, isTrue);
  });
}
