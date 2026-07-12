import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as crypto;
import 'package:pointycastle/export.dart';
import 'package:x25519/x25519.dart' as x;

/// Dart port of the shared Kotlin `SasCrypto` object.
///
/// Provides X25519 key generation, ECDH shared-secret derivation, SHA-256,
/// HMAC-SHA-256, HKDF (RFC 5869), and the 6-digit SAS code used by the
/// Commit-Challenge-Reveal-Confirmation pairing protocol.
///
/// Uses only packages already in pubspec: `x25519` (X25519) and `crypto`
/// (SHA-256 / HMAC).
class SasCrypto {
  SasCrypto._();

  // ───────────────────────── Key generation ─────────────────────────────────

  /// An ephemeral X25519 keypair (32-byte private, 32-byte public).
  static ({Uint8List privateKey, Uint8List publicKey}) generateX25519KeyPair() {
    final keyPair = x.generateKeyPair();
    return (
      privateKey: Uint8List.fromList(keyPair.privateKey),
      publicKey: Uint8List.fromList(keyPair.publicKey),
    );
  }

  /// Compute the X25519 shared secret from [privateKey] and [peerPublicKey].
  static Uint8List calculateECDH(
      Uint8List privateKey, Uint8List peerPublicKey) {
    return x.X25519(privateKey, peerPublicKey);
  }

  // ───────────────────────── Hash / MAC ─────────────────────────────────────

  /// SHA-256 digest.
  static Uint8List sha256(Uint8List data) =>
      Uint8List.fromList(crypto.sha256.convert(data).bytes);

  /// HMAC-SHA-256.
  static Uint8List hmacSha256(Uint8List key, Uint8List data) {
    final hmac = crypto.Hmac(crypto.sha256, key);
    return Uint8List.fromList(hmac.convert(data).bytes);
  }

  // ───────────────────────── HKDF (RFC 5869) ───────────────────────────────

  /// HKDF-Extract: `PRK = HMAC-SHA-256(salt, ikm)`.
  static Uint8List hkdfExtract({Uint8List? salt, required Uint8List ikm}) {
    final actualSalt = salt ?? Uint8List(32); // 32 zero bytes
    return hmacSha256(actualSalt, ikm);
  }

  /// HKDF-Expand: derives [length] bytes from [prk] + [info].
  static Uint8List hkdfExpand(Uint8List prk,
      {Uint8List? info, int length = 32}) {
    final okm = Uint8List(length);
    var t = Uint8List(0);
    var offset = 0;
    var counter = 1;
    while (offset < length) {
      final input = BytesBuilder()
        ..add(t)
        ..add(info ?? Uint8List(0))
        ..addByte(counter);
      t = hmacSha256(prk, Uint8List.fromList(input.toBytes()));
      final toCopy = (t.length < length - offset) ? t.length : length - offset;
      okm.setRange(offset, offset + toCopy, t);
      offset += toCopy;
      counter++;
    }
    return okm;
  }

  // ───────────────────────── SAS derivation ─────────────────────────────────

  /// Derives a 6-digit Short Authentication String from [sharedSecret] and
  /// [transcript].
  ///
  /// `truncate(SHA-256(sharedSecret ‖ transcript))` → first 4 bytes
  /// big-endian unsigned → `% 1_000_000` → zero-padded.
  static String generateSAS(Uint8List sharedSecret, Uint8List transcript) {
    final combined = Uint8List.fromList(sharedSecret + transcript);
    final hash = sha256(combined);
    final value = ((hash[0] & 0xFF) << 24) |
        ((hash[1] & 0xFF) << 16) |
        ((hash[2] & 0xFF) << 8) |
        (hash[3] & 0xFF);
    final sasInt = (value & 0x7FFFFFFF) % 1000000;
    return sasInt.toString().padLeft(6, '0');
  }

  // ───────────────────────── Nonce ───────────────────────────────────────────

  /// Cryptographically secure random bytes.
  static Uint8List generateNonce([int size = 16]) => _platformSeed(size);

  /// AES-256-GCM. The returned bytes are ciphertext followed by the 16-byte tag.
  static Uint8List aesGcmEncrypt({
    required Uint8List key,
    required Uint8List nonce,
    required Uint8List plaintext,
    required Uint8List aad,
  }) {
    final cipher = GCMBlockCipher(AESEngine())
      ..init(
        true,
        AEADParameters(KeyParameter(key), 128, nonce, aad),
      );
    return cipher.process(plaintext);
  }

  static Uint8List aesGcmDecrypt({
    required Uint8List key,
    required Uint8List nonce,
    required Uint8List ciphertext,
    required Uint8List aad,
  }) {
    final cipher = GCMBlockCipher(AESEngine())
      ..init(
        false,
        AEADParameters(KeyParameter(key), 128, nonce, aad),
      );
    return cipher.process(ciphertext);
  }

  // ───────────────────────── Internal ───────────────────────────────────────

  static Uint8List _platformSeed(int length) {
    final rng = Random.secure();
    return Uint8List.fromList(List.generate(length, (_) => rng.nextInt(256)));
  }
}
