import 'package:flutter/gestures.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/player_surface_click.dart';

void main() {
  final timeout = kDoubleTapTimeout;
  final t0 = DateTime(2024, 1, 1);

  test('first click is a single', () {
    final click = PlayerSurfaceClick(timeout: timeout);
    expect(click.isDouble(t0), isFalse);
  });

  test('second click within the double-tap timeout is a double', () {
    final click = PlayerSurfaceClick(timeout: timeout);
    expect(click.isDouble(t0), isFalse);
    expect(click.isDouble(t0.add(timeout)), isTrue);
  });

  test('click after the timeout is a new single', () {
    final click = PlayerSurfaceClick(timeout: timeout);
    expect(click.isDouble(t0), isFalse);
    expect(
      click.isDouble(t0.add(timeout + const Duration(milliseconds: 1))),
      isFalse,
    );
  });

  test('a third click after a pair starts a new single', () {
    final click = PlayerSurfaceClick(timeout: timeout);
    expect(click.isDouble(t0), isFalse);
    expect(click.isDouble(t0.add(const Duration(milliseconds: 50))), isTrue);
    expect(click.isDouble(t0.add(const Duration(milliseconds: 80))), isFalse);
  });
}
