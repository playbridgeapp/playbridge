import 'dart:convert';
import 'dart:io';

import 'package:basic_utils/basic_utils.dart';
import 'package:crypto/crypto.dart' as crypto;
import 'package:path_provider/path_provider.dart';

/// The receiver's persistent self-signed TLS identity for `wss://`.
///
/// The keypair is generated once and reused across restarts, so the SPKI pin
/// stays stable (renewing the cert without changing the key keeps existing
/// pairings valid — see ../../protocol/README.md "TLS pinning: cert_fingerprint").
class CertManager {
  CertManager._({required this.securityContext, required this.fingerprint});

  /// Pass to `shelf_io.serve(..., securityContext: ...)`.
  final SecurityContext securityContext;

  /// OkHttp-style SPKI pin `sha256/<base64>`, delivered to senders at pairing.
  final String fingerprint;

  static const _certFile = 'tls_cert.pem';
  static const _keyFile = 'tls_key.pem';
  static const _pinFile = 'tls_pin.txt';

  /// Loads the persisted identity, generating one on first run.
  static Future<CertManager> loadOrCreate({
    String commonName = 'PlayBridge Desktop',
  }) async {
    final base = await getApplicationSupportDirectory();
    final dir = Directory('${base.path}/tls');
    if (!dir.existsSync()) dir.createSync(recursive: true);

    final cert = File('${dir.path}/$_certFile');
    final key = File('${dir.path}/$_keyFile');
    final pin = File('${dir.path}/$_pinFile');

    final String certPem;
    final String keyPem;
    final String fingerprint;
    if (cert.existsSync() && key.existsSync() && pin.existsSync()) {
      certPem = await cert.readAsString();
      keyPem = await key.readAsString();
      fingerprint = (await pin.readAsString()).trim();
    } else {
      final pair = CryptoUtils.generateRSAKeyPair(keySize: 2048);
      final priv = pair.privateKey as RSAPrivateKey;
      final pub = pair.publicKey as RSAPublicKey;
      final csr = X509Utils.generateRsaCsrPem({'CN': commonName}, priv, pub);
      certPem = X509Utils.generateSelfSignedCertificate(
        priv,
        csr,
        3650,
        sans: ['localhost', '127.0.0.1'],
      );
      keyPem = CryptoUtils.encodeRSAPrivateKeyToPem(priv);
      fingerprint = _spkiPin(pub);
      await cert.writeAsString(certPem, flush: true);
      await key.writeAsString(keyPem, flush: true);
      await pin.writeAsString(fingerprint, flush: true);
    }

    final ctx = SecurityContext()
      ..useCertificateChainBytes(utf8.encode(certPem))
      ..usePrivateKeyBytes(utf8.encode(keyPem));

    return CertManager._(securityContext: ctx, fingerprint: fingerprint);
  }

  /// `sha256/<base64( SHA-256( DER SubjectPublicKeyInfo ) )>`.
  static String _spkiPin(RSAPublicKey pub) {
    final pem = CryptoUtils.encodeRSAPublicKeyToPem(pub); // "PUBLIC KEY" == SPKI
    final b64 = pem
        .replaceAll('-----BEGIN PUBLIC KEY-----', '')
        .replaceAll('-----END PUBLIC KEY-----', '')
        .replaceAll(RegExp(r'\s'), '');
    final der = base64.decode(b64);
    final digest = crypto.sha256.convert(der).bytes;
    return 'sha256/${base64.encode(digest)}';
  }
}
