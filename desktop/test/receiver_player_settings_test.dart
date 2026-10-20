import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/media_kind.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/remote_player_settings.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/subtitle_delay.dart';
import 'package:playbridge_desktop/video_scaling.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Engine extends PlayerEngine {
  void emit() => notifyListeners();
  @override
  Future<void> dispose() async {
    super.dispose();
  }

  String currentState = 'idle';
  double rate = 1;
  int delay = 0;
  String scaling = 'Fit';
  bool loop = false;
  bool live = false;
  bool seekable = true;
  int height = 1080;
  @override
  String get state => currentState;
  @override
  int get positionMs => 500;
  @override
  int get durationMs => live ? 0 : 1000;
  @override
  bool get isLive => live;
  @override
  bool get isSeekable => seekable;
  @override
  int get currentVideoHeight => height;
  @override
  dynamic get tracks => null;
  @override
  dynamic get track => null;
  @override
  Future<void> setAudioTrack(dynamic t) async {}
  @override
  Future<void> setSubtitleTrack(dynamic t) async {}
  @override
  Future<void> setPlaybackRate(double rate) async {
    this.rate = rate;
  }

  @override
  Future<void> setSubtitleDelayMs(int delayMs) async {
    delay = delayMs;
  }

  @override
  Future<void> setVideoScaling(String mode) async {
    scaling = mode;
  }

  @override
  Future<void> setLooping(bool enabled) async {
    loop = enabled;
  }

  @override
  Future<void> open(QueueItem item) async {
    currentState = 'playing';
    notifyListeners();
  }

  @override
  Future<void> resume() async {
    currentState = 'playing';
  }

  @override
  Future<void> pause() async {
    currentState = 'paused';
  }

  @override
  Future<void> seek(Duration position) async {}
  @override
  Future<void> stop() async {
    currentState = 'idle';
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('parsing clamps and snaps offered speeds; rejects malformed settings',
      () {
    for (final entry in {
      'speed:-9': .25,
      'speed:9': 2.0,
      'speed:1.3': 1.25,
      'speed:1.5': 1.5
    }.entries) {
      expect((parseRemotePlayerSetting(entry.key) as RemotePlaybackSpeed).rate,
          entry.value);
    }
    expect(
        (parseRemotePlayerSetting('sub_offset:-500') as RemoteSubtitleOffset)
            .deltaMs,
        -500);
    expect((parseRemotePlayerSetting('loop_on') as RemoteLoop).enabled, isTrue);
    expect(
        (parseRemotePlayerSetting('loop_off') as RemoteLoop).enabled, isFalse);
    expect((parseRemotePlayerSetting('scaling:Fill') as RemoteScaling).mode,
        VideoScalingMode.fill);
    for (final command in [
      'speed:',
      'speed:x',
      'speed:NaN',
      'speed:Infinity',
      'speed:-Infinity',
      'sub_offset:0.1',
      'sub_offset:',
      'sub_offset:9999999999999999999999999',
      'loop:true',
      'scaling:zoom',
      'scaling:wat',
      'scaling:',
      'audio_boost'
    ]) {
      expect(parseRemotePlayerSetting(command), isNull, reason: command);
    }
  });

  test('mpv scaling transitions set both properties and reject unknowns', () {
    expect(mpvScalingProperties('Fit'), {'keepaspect': 'yes', 'panscan': '0'});
    expect(
        mpvScalingProperties('Zoom'), {'keepaspect': 'yes', 'panscan': '1.0'});
    expect(mpvScalingProperties('Fill'), {'keepaspect': 'no', 'panscan': '0'});
    expect(mpvScalingProperties('Bad'), isNull);
  });

  test(
      'offset adds and saturates including signed-int extremes without overflow',
      () {
    expect(adjustSubtitleDelayMs(200, -500), -300);
    expect(adjustSubtitleDelayMs(119950, 100), 120000);
    expect(adjustSubtitleDelayMs(-119950, -100), -120000);
    expect(adjustSubtitleDelayMs(100, 9223372036854775807), 120000);
    expect(adjustSubtitleDelayMs(-100, -9223372036854775808), -120000);
  });

  group('receiver uses the local controller state', () {
    late _Engine engine;
    late PlayerController player;
    late ReceiverServer server;
    late List<Map<String, Object?>> frames;
    late List<Map<String, Object?>> direct;
    QueueItem item(int index) => QueueItem(
        url: 'https://example.invalid/$index.mp4', title: 'Item $index');
    Future<void> command(String value) =>
        server.handleCommandForTest(ControlCmd(value));

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      await StreamProxyServer.instance.stop();
      final store = await PairingStore.load();
      engine = _Engine();
      player = PlayerController(engineForTest: engine);
      server = ReceiverServer(player: player, store: store);
      frames = [];
      direct = [];
      server.onBroadcastForTest = (frame) {
        if (frame['type'] == 'player_settings') frames.add(frame);
      };
      server.onSendForTest = (id, frame) {
        expect(id, 7);
        direct.add(frame);
      };
      server.attachPlayerListenersForTest();
      await player.playPlaylist([item(1), item(2)], 0);
      frames.clear();
    });
    tearDown(() async {
      await server.stop();
      player.dispose();
      await StreamProxyServer.instance.stop();
    });

    test('remote settings apply and local changes publish complete snapshots',
        () async {
      await command('speed:9');
      expect(player.playbackRate, 2);
      expect(engine.rate, 2);
      await command('sub_offset:500');
      await command('sub_offset:-100');
      expect(player.subtitleDelayMs, 400);
      expect(engine.delay, 400);
      await command('sub_offset:9223372036854775807');
      expect(player.subtitleDelayMs, 120000);
      await command('loop_on');
      expect(player.looping && engine.loop, isTrue);
      await command('scaling:Zoom');
      expect(engine.scaling, 'Zoom');
      await command('loop_off');
      expect(engine.loop, isFalse);
      await player.setPlaybackRate(1.25);
      await player.setSubtitleDelayMs(-300);
      await player.setVideoScaling(VideoScalingMode.fill);
      expect(frames.last, {
        'type': 'player_settings',
        'speed': 1.25,
        'scaling': 'Fill',
        'subtitleOffsetMs': -300,
        'loop': false,
        'engine': 'mpv',
        'audioBoost': false,
        'speedAvailable': true,
        'scalingAvailable': true,
        'audioBoostAvailable': false,
        'qualityAvailable': false,
        'qualityMaxHeight': 0,
        'currentVideoHeight': 1080,
        'isLive': false,
        'isSeekable': true,
      });
      final before = frames.length;
      await command('speed:NaN');
      await command('sub_offset:bad');
      await command('scaling:invalid');
      expect(frames.length, before);
      expect(player.playbackRate, 1.25);
      expect(player.videoScaling, VideoScalingMode.fill);
    });

    test('context query always re-sends current settings, even unchanged',
        () async {
      await command('speed:1.5');
      await server.handleCommandForTest(const ContextQueryCmd(),
          connectionId: 7);
      await server.handleCommandForTest(const ContextQueryCmd(),
          connectionId: 7);
      expect(direct.length, 2);
      expect(direct[0], direct[1]);
      expect(direct.last['speed'], 1.5);
    });

    test(
        'new items reset speed/loop; preserve existing desktop scaling/delay lifetimes',
        () async {
      await command('speed:1.5');
      await command('loop_on');
      await command('scaling:Zoom');
      await command('sub_offset:400');
      player
          .notifyCompletedForTest(); // loop-file repeats this file, never advances queue
      expect(player.currentIndex, 0);
      await player.jumpTo(1);
      expect(engine.rate, 1);
      expect(engine.loop, isFalse);
      expect(engine.scaling, 'Zoom');
      expect(engine.delay, 400);
      expect(frames.last['speed'], 1);
      expect(frames.last['loop'], false);
      await player.playPlaylist([item(3)], 0);
      expect(engine.scaling, 'Fit');
      expect(player.subtitleDelayMs, 400);
      // A different item with identical settings must still produce a snapshot.
      frames.clear();
      await player.playPlaylist([item(4)], 0);
      expect(frames, hasLength(1));
    });

    test('images report unavailability and ignore setting commands', () async {
      await player.playPlaylist([
        QueueItem(
            url: 'https://example.invalid/a.jpg',
            title: 'Image',
            declaredMediaKind: 'image')
      ], 0);
      expect(player.currentMediaKind, MediaKind.image);
      expect(frames.last['speedAvailable'], false);
      expect(frames.last['scalingAvailable'], false);
      expect(frames.last['currentVideoHeight'], 0);
      expect(frames.last['isLive'], false);
      expect(frames.last['isSeekable'], false);
      await command('speed:2');
      await command('loop_on');
      await command('sub_offset:100');
      await command('scaling:Fill');
      expect(player.playbackRate, 1);
      expect(player.looping, false);
      expect(player.subtitleDelayMs, 0);
      expect(player.videoScaling, VideoScalingMode.fit);
    });

    test(
        'live/seekability and height changes are reported without polling native stats',
        () async {
      engine.live = true;
      engine.seekable = false;
      engine.height = 720;
      engine.emit();
      expect(frames.last['isLive'], true);
      expect(frames.last['isSeekable'], false);
      expect(frames.last['currentVideoHeight'], 720);
    });
  });
}
