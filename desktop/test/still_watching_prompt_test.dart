import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/still_watching_prompt.dart';

void main() {
  Future<void> pumpPrompt(
    WidgetTester tester, {
    required VoidCallback onContinue,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StillWatchingPrompt(
            remainingSeconds: 60,
            onContinue: onContinue,
          ),
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets('Enter activates the focused prompt button', (tester) async {
    var continueCount = 0;
    await pumpPrompt(
      tester,
      onContinue: () => continueCount++,
    );

    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    expect(continueCount, 1);
    expect(find.text('Stop now'), findsNothing);
  });

  testWidgets('Space and Escape continue', (tester) async {
    var continueCount = 0;
    await pumpPrompt(
      tester,
      onContinue: () => continueCount++,
    );

    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    expect(continueCount, 2);
  });
}
