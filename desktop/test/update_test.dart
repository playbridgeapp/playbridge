import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/update/app_version.dart';
import 'package:playbridge_desktop/update/update_checker.dart';

void main() {
  group('AppVersion', () {
    test('parses plain and prefixed versions', () {
      expect(AppVersion.parse('0.6.4').toString(), '0.6.4');
      expect(AppVersion.parse('v1.10.2').toString(), '1.10.2');
      expect(AppVersion.parse('1.2.3-beta')!.toString(), '1.2.3');
      expect(AppVersion.parse('nonsense'), isNull);
      expect(AppVersion.parse(null), isNull);
    });

    test('compares component-wise, missing components are zero', () {
      expect(AppVersion.parse('0.7.0')! > AppVersion.parse('0.6.4')!, isTrue);
      expect(AppVersion.parse('0.6.4')! > AppVersion.parse('0.6.10')!, isFalse);
      expect(AppVersion.parse('1.10.0')! > AppVersion.parse('1.9.9')!, isTrue);
      expect(AppVersion.parse('1.2')! == AppVersion.parse('1.2.0')!, isTrue);
      expect(AppVersion.parse('1.2')!.hashCode,
          AppVersion.parse('1.2.0')!.hashCode);
    });

    test('kAppVersion matches pubspec.yaml (drift guard)', () {
      // flutter test runs with the package root as cwd.
      final pubspec = File('pubspec.yaml').readAsStringSync();
      final m =
          RegExp(r'^version:\s*([\d.]+)', multiLine: true).firstMatch(pubspec);
      expect(m, isNotNull, reason: 'no version: line in pubspec.yaml');
      expect(kAppVersion, m!.group(1),
          reason: 'lib/update/app_version.dart kAppVersion is out of sync '
              'with pubspec.yaml — update both together.');
    });
  });

  group('asset version pattern', () {
    test('matches the CI artifact names for all three platforms', () {
      const cases = {
        'https://github.com/playbridgeapp/playbridge/releases/download/'
            'desktop-v0.7.0/playbridge-desktop-macos-0.7.0.zip': '0.7.0',
        'playbridge-desktop-windows-1.10.2.zip': '1.10.2',
        'playbridge-desktop-linux-0.7.0.tar.gz': '0.7.0',
      };
      cases.forEach((input, version) {
        final m = UpdateChecker.assetVersionPattern.firstMatch(input);
        expect(m?.group(1), version, reason: input);
      });
    });

    test('does not match other product assets', () {
      const nonMatches = [
        'playbridge-phone-0.8.0-app-universal-release.apk',
        'playbridge-tv-player-0.5.0-universal-release.apk',
        'playbridge-extension-0.3.0.xpi',
        'https://github.com/playbridgeapp/playbridge/releases', // fallback page
      ];
      for (final input in nonMatches) {
        expect(UpdateChecker.assetVersionPattern.hasMatch(input), isFalse,
            reason: input);
      }
    });
  });
}
