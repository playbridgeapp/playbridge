import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/screen_mirror_receiver.dart';
import 'package:playbridge_desktop/screen_mirror_surface.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/ui/pb_theme.dart';
import 'package:playbridge_desktop/update/app_version.dart';
import 'package:playbridge_desktop/update/update_checker.dart';
import 'package:playbridge_desktop/update/update_gate.dart';

class _Checker extends UpdateChecker {
  UpdateState current = const UpdateIdle();
  UpdateInfo? accepted;
  int dismissed = 0;

  @override
  UpdateState get state => current;

  void set(UpdateState next) {
    current = next;
    notifyListeners();
  }

  @override
  Future<void> accept(UpdateInfo info) async => accepted = info;

  @override
  void dismiss() {
    dismissed++;
    set(const UpdateIdle());
  }
}

class _Sender extends ChangeNotifier implements TvSenderController {
  String? error;
  ReceiverReconnectStatus? retrying;
  int disconnects = 0;

  @override
  String? get lastPlaybackError => error;

  @override
  ReceiverReconnectStatus? get reconnectStatus => retrying;

  @override
  String get selectionName => 'Living Room TV';

  @override
  void clearPlaybackError() {
    error = null;
    notifyListeners();
  }

  @override
  Future<void> disconnect() async {
    disconnects++;
    retrying = null;
    notifyListeners();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final _info = UpdateInfo(
  version: AppVersion.parse('0.17.0')!,
  archiveUrl: 'https://example.test/playbridge-desktop-macos-0.17.0.zip',
);

Widget _app(Widget gate) => MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(
        body: Stack(fit: StackFit.expand, children: [
          const Center(child: Text('Home')),
          gate,
        ]),
      ),
    );

void main() {
  testWidgets('idle renders nothing and leaves the page usable',
      (tester) async {
    await tester.pumpWidget(_app(UpdateGate(checker: _Checker())));
    expect(find.text('Home'), findsOneWidget);
    expect(find.byType(Positioned), findsNothing);
  });

  testWidgets('update available: Update and restart accepts, Later dismisses',
      (tester) async {
    final checker = _Checker()..current = UpdateAvailable(_info);
    await tester.pumpWidget(_app(UpdateGate(checker: checker)));
    expect(find.text('PlayBridge 0.17.0 is available'), findsOneWidget);
    // A corner card, not a barrier: the page behind is still hit-testable.
    expect(find.text('Home').hitTestable(), findsOneWidget);
    final card = tester.getRect(find.byKey(const ValueKey('update-card')));
    final window = tester.getRect(find.byType(Scaffold));
    expect(card.right, lessThanOrEqualTo(window.right));
    expect(card.width, lessThanOrEqualTo(420));
    expect(card.bottom, greaterThan(window.height / 2));

    await tester.tap(find.text('Update and restart'));
    expect(checker.accepted, same(_info));
    await tester.tap(find.text('Later'));
    await tester.pump();
    expect(checker.dismissed, 1);
    expect(find.text('Later'), findsNothing);
  });

  testWidgets('downloading, installing and error keep their actions',
      (tester) async {
    final checker = _Checker();
    await tester.pumpWidget(_app(UpdateGate(checker: checker)));

    checker.set(UpdateDownloading(_info, 0.25));
    await tester.pump();
    expect(find.text('Downloading PlayBridge 0.17.0…'), findsOneWidget);
    expect(find.text('25%'), findsOneWidget);
    expect(find.text('Home').hitTestable(), findsOneWidget);

    checker.set(UpdateInstalling(_info));
    await tester.pump();
    expect(find.text('Installing PlayBridge 0.17.0…'), findsOneWidget);

    checker.set(const UpdateError('Download failed: x', manual: true));
    await tester.pump();
    expect(find.text('Download failed: x'), findsOneWidget);
    expect(find.text('Download page'), findsOneWidget);
    await tester.tap(find.text('Dismiss'));
    await tester.pump();
    expect(checker.state, isA<UpdateIdle>());

    checker.set(const UpdateError('background', manual: false));
    await tester.pump();
    expect(find.text('background'), findsNothing);
  });

  testWidgets('up to date clears itself after a few seconds', (tester) async {
    final checker = _Checker();
    await tester.pumpWidget(_app(UpdateGate(checker: checker)));
    checker.set(const UpdateUpToDate());
    await tester.pump();
    expect(find.text("You're on the latest version"), findsOneWidget);
    await tester.pump(const Duration(seconds: 4));
    expect(checker.dismissed, 1);
  });

  testWidgets('cast failed and reconnecting cards follow the sender',
      (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final sender = _Sender();
    await tester
        .pumpWidget(_app(UpdateGate(checker: _Checker(), sender: sender)));
    expect(find.byKey(const ValueKey('cast-failed-card')), findsNothing);

    sender.error = 'The TV said the video format is not supported.';
    sender.retrying = const ReceiverReconnectStatus(
        attempt: 4, maxAttempts: 30, deviceName: 'Living Room TV');
    sender.notifyListeners();
    await tester.pump();
    expect(find.text("Living Room TV couldn't play that"), findsOneWidget);
    expect(find.text('The TV said the video format is not supported.'),
        findsOneWidget);
    expect(find.text('Reconnecting to Living Room TV'), findsOneWidget);
    expect(find.text('Try 4 of 30'), findsOneWidget);
    expect(find.text('Home').hitTestable(), findsOneWidget);

    await tester.tap(find.text('Dismiss'));
    await tester.pump();
    expect(find.byKey(const ValueKey('cast-failed-card')), findsNothing);

    await tester.tap(find.text('Stop trying'));
    await tester.pump();
    expect(sender.disconnects, 1);
    expect(find.byKey(const ValueKey('reconnecting-card')), findsNothing);
  });

  testWidgets(
      'screen mirror bar shows callbacks, hides after 3 s, returns on hover',
      (tester) async {
    final receiver = ScreenMirrorReceiver(send: (_, __) {});
    addTearDown(receiver.dispose);
    var fullScreen = 0;
    var stopped = 0;
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(
        body: ScreenMirrorSurface(
          receiver: receiver,
          deviceName: 'Pixel 9',
          onToggleFullScreen: () => fullScreen++,
          onStop: () => stopped++,
        ),
      ),
    ));
    expect(find.text('Connecting…'), findsOneWidget);
    expect(find.text('Connecting to Pixel 9…'), findsOneWidget);
    await tester.tap(find.text('Full screen'));
    await tester.tap(find.text('Stop showing'));
    expect((fullScreen, stopped), (1, 1));

    await tester.pump(const Duration(seconds: 4));
    await tester.pump(const Duration(milliseconds: 300));
    expect(tester.widget<AnimatedOpacity>(find.byType(AnimatedOpacity)).opacity,
        0);

    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    await mouse.addPointer(location: const Offset(10, 10));
    await mouse.moveTo(const Offset(40, 40));
    await tester.pump();
    expect(tester.widget<AnimatedOpacity>(find.byType(AnimatedOpacity)).opacity,
        1);
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('screen mirror without callbacks has no buttons', (tester) async {
    final receiver = ScreenMirrorReceiver(send: (_, __) {});
    addTearDown(receiver.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(body: ScreenMirrorSurface(receiver: receiver)),
    ));
    expect(find.text('Full screen'), findsNothing);
    expect(find.text('Stop showing'), findsNothing);
    expect(find.text('Connecting screen mirror…'), findsOneWidget);
    await tester.pump(const Duration(seconds: 4));
  });
}
