import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/send_to_tv_screen.dart';

void main() {
  testWidgets('pairing dialog remains valid through its dismissal animation',
      (tester) async {
    String? submittedCode;

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              submittedCode = await showBrowserPairingCodeDialog(
                context,
                receiverName: 'Living room TV',
              );
            },
            child: const Text('Open pairing'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Open pairing'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '123456');
    await tester.tap(find.text('Pair'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(submittedCode, '123456');
    expect(find.byType(TextField), findsNothing);
  });
}
