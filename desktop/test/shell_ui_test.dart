import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/shell/nav_sidebar.dart';
import 'package:playbridge_desktop/shell/receiver_status.dart';
import 'package:playbridge_desktop/ui/pb_theme.dart';
import 'package:playbridge_desktop/ui/pb_widgets.dart';

Widget _host(Widget child) => MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(body: Center(child: child)),
    );

ReceiverStatusInfo _status({
  String hostInfo = '192.168.1.5',
  PairingPhase phase = PairingPhase.idle,
  String? serverError,
  String? discoveryError,
  String? tlsError,
}) =>
    describeReceiverStatus(
      deviceName: 'Studio Mac',
      hostInfo: hostInfo,
      phase: phase,
      serverError: serverError,
      discoveryError: discoveryError,
      tlsError: tlsError,
    );

void main() {
  group('describeReceiverStatus', () {
    test('ready names the device', () {
      final s = _status();
      expect(s.health, ReceiverHealth.ready);
      expect(s.label, 'Ready to receive');
      expect(s.detail, 'Phones see this as Studio Mac');
    });

    test('is honest when the receiver is starting or failed', () {
      expect(_status(hostInfo: 'starting…').health, ReceiverHealth.starting);
      final failed = _status(serverError: 'port in use');
      expect(failed.health, ReceiverHealth.failed);
      expect(failed.label, isNot('Ready to receive'));
      expect(failed.extra, 'port in use');
      expect(_status(tlsError: 'bad cert').health, ReceiverHealth.failed);
    });

    test('discovery failure and pairing are reported', () {
      final limited = _status(discoveryError: 'no mdns');
      expect(limited.health, ReceiverHealth.limited);
      expect(limited.extra, 'mDNS: no mdns');
      expect(_status(phase: PairingPhase.awaitingCode).health,
          ReceiverHealth.pairing);
    });
  });

  testWidgets('sidebar lists the new destinations and reports taps',
      (tester) async {
    NavDest? tapped;
    await tester.pumpWidget(_host(SizedBox(
      height: 700,
      child: NavSidebar(
        selected: NavDest.home,
        status: _status(),
        onSelect: (d) => tapped = d,
      ),
    )));
    for (final label in [
      'Home',
      'Bookmarks',
      'Devices',
      'Watch Together',
      'Settings',
      'Ready to receive',
      'Phones see this as Studio Mac',
    ]) {
      expect(find.text(label), findsOneWidget, reason: label);
    }
    await tester.tap(find.text('Devices'));
    expect(tapped, NavDest.devices);
    expect(find.text('Now Playing'), findsNothing);
  });

  testWidgets('PbButton activates by tap and by keyboard', (tester) async {
    var taps = 0;
    await tester.pumpWidget(_host(PbButton(
      label: 'Go',
      autofocus: true,
      onPressed: () => taps++,
    )));
    await tester.tap(find.text('Go'));
    expect(taps, 1);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    expect(taps, 2);
  });

  testWidgets('PbSwitchRow toggles and tabs select', (tester) async {
    bool? value;
    int? tab;
    await tester.pumpWidget(_host(Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          width: 500,
          child: PbSwitchRow(
            title: 'Private',
            value: false,
            onChanged: (v) => value = v,
          ),
        ),
        PbSegmentedTabs(
          labels: const ['A', 'B'],
          selectedIndex: 0,
          onChanged: (i) => tab = i,
        ),
      ],
    )));
    await tester.tap(find.text('Private'));
    expect(value, isTrue);
    await tester.tap(find.text('B'));
    expect(tab, 1);
  });
}
