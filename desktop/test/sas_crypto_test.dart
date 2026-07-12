import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/sas_crypto.dart';

void main() {
  test('credential encryption authenticates ciphertext and transcript', () {
    final key = Uint8List.fromList(List<int>.generate(32, (i) => i));
    final nonce = Uint8List.fromList(List<int>.generate(12, (i) => i + 32));
    final aad = SasCrypto.sha256(Uint8List.fromList(utf8.encode('transcript')));
    final plaintext = Uint8List.fromList(utf8.encode('{"token":"secret"}'));

    final ciphertext = SasCrypto.aesGcmEncrypt(
      key: key,
      nonce: nonce,
      plaintext: plaintext,
      aad: aad,
    );
    expect(
      SasCrypto.aesGcmDecrypt(
        key: key,
        nonce: nonce,
        ciphertext: ciphertext,
        aad: aad,
      ),
      plaintext,
    );

    final tampered = Uint8List.fromList(ciphertext)..[0] ^= 1;
    expect(
      () => SasCrypto.aesGcmDecrypt(
        key: key,
        nonce: nonce,
        ciphertext: tampered,
        aad: aad,
      ),
      throwsA(anything),
    );
  });
}
