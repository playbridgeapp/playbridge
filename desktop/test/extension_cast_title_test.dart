import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/extension_cast_title.dart';

void main() {
  group('titleForExtensionCast', () {
    test('uses Android-style fallback when title is missing', () {
      expect(titleForExtensionCast(null), 'Video from browser');
      expect(titleForExtensionCast(''), 'Video from browser');
      expect(titleForExtensionCast('   '), 'Video from browser');
    });

    test('appends via browser to a page title', () {
      expect(
        titleForExtensionCast('Some Movie — Watch Online'),
        'Some Movie — Watch Online · via browser',
      );
    });

    test('does not double-label titles that already mention browser', () {
      expect(
        titleForExtensionCast('Clip · via browser'),
        'Clip · via browser',
      );
      expect(
        titleForExtensionCast('Video from browser'),
        'Video from browser',
      );
    });
  });
}
