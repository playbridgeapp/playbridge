import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/cert_manager.dart';

void main() {
  late Directory tempDir;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('pb_cert_test_');
  });
  tearDown(() {
    if (tempDir.existsSync()) tempDir.deleteSync(recursive: true);
  });

  test('generates a cert with a sha256/<base64> SPKI pin', () async {
    final cm = await CertManager.loadOrCreate(dir: tempDir);
    expect(cm.fingerprint, startsWith('sha256/'));
    // SHA-256 is 32 bytes → 44 base64 chars (with padding).
    expect(cm.fingerprint.length, 'sha256/'.length + 44);
    expect(cm.securityContext, isNotNull);
  });

  test('persists and reuses the same identity across reloads', () async {
    final first = await CertManager.loadOrCreate(dir: tempDir);
    final second = await CertManager.loadOrCreate(dir: tempDir);
    expect(second.fingerprint, equals(first.fingerprint));
    expect(File('${tempDir.path}/tls_cert.pem').existsSync(), isTrue);
    expect(File('${tempDir.path}/tls_key.pem').existsSync(), isTrue);
  });

  test('generates a distinct identity in a fresh directory', () async {
    final a = await CertManager.loadOrCreate(dir: tempDir);
    final otherDir = Directory.systemTemp.createTempSync('pb_cert_test2_');
    addTearDown(() => otherDir.deleteSync(recursive: true));
    final b = await CertManager.loadOrCreate(dir: otherDir);
    expect(b.fingerprint, isNot(equals(a.fingerprint)));
  });
}
