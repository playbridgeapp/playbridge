import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/tray_controller.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_client.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:tray_manager/tray_manager.dart';

TvRecord savedDevice(String name, String id, TvProtocol protocol) => TvRecord(
      uuid: id,
      protocol: protocol,
      name: name,
      host: '192.0.2.1',
      port: 8765,
      lastConnected: DateTime(2026),
    );

class _FakePlayer extends Fake implements PlayerController {}

class _FakeServer extends Fake implements ReceiverServer {}

class _FakeStore extends Fake implements PairingStore {}

class _FakeSender extends Fake implements TvSenderController {
  _FakeSender(this.pairedTvs);

  @override
  final List<TvRecord> pairedTvs;
  TvRecord? reconnected;
  bool? routeThroughDesktop;
  int playPauseCalls = 0;
  int stopCalls = 0;
  bool connected = false;
  bool casting = false;
  String playbackState = '';

  @override
  bool get isConnected => connected;

  @override
  SenderConnectionState get state => connected
      ? SenderConnectionState.connected
      : SenderConnectionState.disconnected;

  @override
  bool get isCasting => casting;

  @override
  String get remoteState => playbackState;

  @override
  TvRecord? get activeTv => null;

  @override
  Future<void> reconnect(TvRecord tv) async => reconnected = tv;

  @override
  Future<void> setCastRouteThroughProxy(bool value) async =>
      routeThroughDesktop = value;

  @override
  Future<bool> playPause() async {
    playPauseCalls++;
    return true;
  }

  @override
  Future<bool> stopCast() async {
    stopCalls++;
    return true;
  }
}

void main() {
  test('menu separates sender and receiver and handles empty saved list', () {
    final menu = buildTrayMenu(
      senderStatus: 'Disconnected',
      receiverStatus: 'Waiting for phone',
      savedDevices: const [],
      nearbyDeviceKeys: const {},
      activeDeviceKey: null,
      canDisconnect: false,
      routeThroughDesktop: false,
      launchAtLogin: false,
      hasRemotePlayback: false,
      remoteTitle: null,
      remotePlaybackState: '',
    );

    expect(menu.items!.first.key, 'show');
    expect(menu.items!.where((item) => item.label == 'Sender').single.disabled,
        isTrue);
    expect(
        menu.items!.where((item) => item.label == 'Receiver').single.disabled,
        isTrue);
    expect(
        menu.items!
            .where((item) => item.label == 'Disconnected')
            .single
            .disabled,
        isTrue);
    expect(menu.getMenuItem('sender_disconnect')!.disabled, isTrue);
    expect(menu.getMenuItem('sender_manage')!.label, 'Find or manage devices…');
    expect(
      menu.items!
          .singleWhere((item) => item.label == 'Saved devices')
          .submenu!
          .items!
          .first
          .label,
      'No saved devices',
    );
    expect(menu.getMenuItem('route_direct')!.checked, isTrue);
    expect(menu.getMenuItem('route_desktop')!.checked, isFalse);
    expect(
        menu.items!.singleWhere((item) => item.label == 'Not playing').disabled,
        isTrue);
    expect(menu.getMenuItem('sender_play_pause'), isNull);
    expect(menu.getMenuItem('sender_stop'), isNull);
  });

  test('saved devices are selectable and current route is shown', () {
    final older = savedDevice('Bedroom', 'bedroom', TvProtocol.playBridge);
    final nearby = savedDevice('Living room', 'living', TvProtocol.googleCast);
    final menu = buildTrayMenu(
      senderStatus: 'Selected: Living room',
      receiverStatus: 'Paired · idle',
      savedDevices: [older, nearby],
      nearbyDeviceKeys: {nearby.identityKey},
      activeDeviceKey: nearby.identityKey,
      canDisconnect: true,
      routeThroughDesktop: true,
      launchAtLogin: true,
      hasRemotePlayback: true,
      remoteTitle: 'An episode',
      remotePlaybackState: 'paused',
    );

    final devices = menu.items!
        .singleWhere((item) => item.label == 'Saved devices')
        .submenu!
        .items!;
    expect(devices.where((item) => item.label == 'PlayBridge').single.disabled,
        isTrue);
    expect(devices.where((item) => item.label == 'Google Cast').single.disabled,
        isTrue);
    expect(
        devices.map((item) => item.label).toList(),
        containsAllInOrder([
          'PlayBridge',
          'Bedroom · Not found',
          'Google Cast',
          'Living room',
        ]));
    expect(menu.getMenuItem('sender_device:${nearby.identityKey}')!.checked,
        isTrue);
    expect(menu.getMenuItem('sender_device:${nearby.identityKey}')!.disabled,
        isTrue);
    expect(menu.getMenuItem('sender_device:${older.identityKey}')!.disabled,
        isFalse);
    expect(menu.getMenuItem('sender_disconnect')!.disabled, isFalse);
    expect(
      menu.items!
          .whereType<MenuItem>()
          .singleWhere((item) => item.label?.startsWith('Send mode:') == true)
          .label,
      'Send mode: Via this desktop',
    );
    expect(menu.getMenuItem('route_direct')!.checked, isFalse);
    expect(menu.getMenuItem('route_desktop')!.checked, isTrue);
    expect(menu.getMenuItem('launch_at_login')!.checked, isTrue);
    expect(
        menu.items!
            .singleWhere((item) => item.label == 'Paused: An episode')
            .disabled,
        isTrue);
    expect(menu.getMenuItem('sender_play_pause')!.label, 'Resume playback');
    expect(menu.getMenuItem('sender_stop')!.label, 'Stop playback');
  });

  test('saved devices group by protocol with found devices first', () {
    final nearby = savedDevice('Zebra', 'nearby', TvProtocol.playBridge);
    final missing = savedDevice('Alpha', 'missing', TvProtocol.playBridge);
    final cast = savedDevice('Kitchen', 'cast', TvProtocol.googleCast);
    final menu = buildTrayMenu(
      senderStatus: 'Disconnected',
      receiverStatus: 'Waiting for phone',
      savedDevices: [missing, cast, nearby],
      nearbyDeviceKeys: {nearby.identityKey},
      activeDeviceKey: null,
      canDisconnect: false,
      routeThroughDesktop: false,
      launchAtLogin: false,
      hasRemotePlayback: false,
      remoteTitle: null,
      remotePlaybackState: '',
    );

    final labels = menu.items!
        .singleWhere((item) => item.label == 'Saved devices')
        .submenu!
        .items!
        .map((item) => item.label)
        .toList();
    expect(
        labels,
        containsAllInOrder([
          'PlayBridge',
          'Zebra',
          'Alpha · Not found',
          'Google Cast',
          'Kitchen · Not found',
        ]));
    expect(menu.getMenuItem('sender_device:${missing.identityKey}')!.disabled,
        isFalse);
  });

  test('playing menu offers pause and normalizes long titles', () {
    final menu = buildTrayMenu(
      senderStatus: 'Connected',
      receiverStatus: 'Paired · idle',
      savedDevices: const [],
      nearbyDeviceKeys: const {},
      activeDeviceKey: null,
      canDisconnect: true,
      routeThroughDesktop: false,
      launchAtLogin: false,
      hasRemotePlayback: true,
      remoteTitle: '  Episode\n${List.filled(90, 'x').join()}  ',
      remotePlaybackState: 'playing',
    );

    expect(menu.getMenuItem('sender_play_pause')!.label, 'Pause playback');
    final title = menu.items!
        .singleWhere((item) => item.label?.startsWith('Now playing:') == true)
        .label!;
    expect(title, startsWith('Now playing: Episode '));
    expect(title, endsWith('…'));
    expect(title.contains('\n'), isFalse);
  });

  test('device and mode menu actions use the sender controller', () async {
    final saved = savedDevice('Living room', 'living', TvProtocol.playBridge);
    final sender = _FakeSender([saved]);
    final tray = TrayController(
      player: _FakePlayer(),
      server: _FakeServer(),
      store: _FakeStore(),
      sender: sender,
      showSender: () async {},
    );

    tray.onTrayMenuItemClick(
        MenuItem(key: 'sender_device:${saved.identityKey}'));
    tray.onTrayMenuItemClick(MenuItem(key: 'route_desktop'));
    await Future<void>.delayed(Duration.zero);

    expect(sender.reconnected, same(saved));
    expect(sender.routeThroughDesktop, isTrue);

    tray.onTrayMenuItemClick(MenuItem(key: 'sender_play_pause'));
    tray.onTrayMenuItemClick(MenuItem(key: 'sender_stop'));
    await Future<void>.delayed(Duration.zero);
    expect(sender.playPauseCalls, 0);
    expect(sender.stopCalls, 0);

    sender
      ..connected = true
      ..casting = true
      ..playbackState = 'playing';
    tray.onTrayMenuItemClick(MenuItem(key: 'sender_play_pause'));
    tray.onTrayMenuItemClick(MenuItem(key: 'sender_stop'));
    await Future<void>.delayed(Duration.zero);
    expect(sender.playPauseCalls, 1);
    expect(sender.stopCalls, 1);
  });
}
