import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:playbridge_desktop/cert_manager.dart';
import 'package:playbridge_desktop/receiver_capabilities.dart';
import 'package:playbridge_desktop/sas_crypto.dart';

const _legacy = {
  'players': ['internal_mpv'],
  'browsers': <String>[],
  'mediaKinds': ['video', 'audio', 'image'],
  'features': [
    'queue_crud_v1',
    'stable_item_ids',
    'command_results',
    'subtitle_resource_add_v1',
    'progress_webhook_v1',
  ],
  'screenMirrorWebRtc': true,
};

void _expectLegacy(Map<String, dynamic> message) {
  for (final field in _legacy.entries) {
    expect(message[field.key], field.value);
  }
}

void main() {
  for (final os in ['macos', 'windows', 'linux']) {
    test('Desktop descriptor on $os advertises only implemented WSS controls',
        () {
      final descriptor = buildDesktopReceiverCapabilities(operatingSystem: os);
      expect(descriptor.keys,
          unorderedEquals(['version', 'surfaces', 'volume', 'host']));
      expect(descriptor['version'], 1);
      expect(descriptor['host'], {'osFamily': os, 'deviceClass': 'desktop'});
      expect(descriptor['volume'], {
        'scope': 'system',
        'operations': ['relative_step'],
        'fallbackScope': 'app',
      });
      expect(descriptor['surfaces'], {
        'player': {
          'engines': {
            'internal_mpv': {
              'transport': [
                'play',
                'pause',
                'toggle',
                'stop',
                'seek_absolute',
                'seek_relative'
              ],
              'imageTransforms': [
                'pan',
                'scroll',
                'zoom',
                'rotate',
                'anchor',
                'reset'
              ],
              'tracks': [
                'audio_select',
                'subtitle_select',
                'subtitle_url_add',
                'subtitle_resource_add'
              ],
              'queue': [
                'replace',
                'append',
                'jump_index',
                'jump_item',
                'remove',
                'move',
                'clear'
              ],
              'reporting': ['status', 'context', 'tracks', 'playlist_status'],
            },
          },
        },
      });
    });
  }

  test('unverified OS does not promise system volume', () {
    final descriptor =
        buildDesktopReceiverCapabilities(operatingSystem: 'future_os');
    expect(descriptor.containsKey('volume'), isFalse);
    expect(descriptor['host'],
        {'osFamily': 'future_os', 'deviceClass': 'desktop'});
  });

  final library =
      File('native/cast_core/macos/libplaybridge_cast_core_ffi.dylib');
  test(
      'production Desktop config survives protected pairing and reconnect auth',
      () async {
    final directory =
        await Directory.systemTemp.createTemp('desktop-capabilities-');
    ReceiverRuntime? runtime;
    WebSocket? socket;
    StreamIterator<dynamic>? frames;
    final http = HttpClient();
    try {
      final certificate = await CertManager.loadOrCreate(
          commonName: 'Loopback Desktop', dir: directory);
      final config = buildDesktopReceiverRuntimeConfig(
        name: 'Loopback Desktop',
        uuid: 'loopback-desktop',
        certificateDer: certificate.certificateDer,
        privateKeyDer: certificate.privateKeyDer,
        privateKeyKind: certificate.privateKeyKind,
        preferredPort: 0,
        authorizedTokens: const [],
      );
      _expectLegacy(config.toJson());
      expect(config.advertise, isFalse);
      expect(config.fallbackAttempts, 32);
      expect(config.capabilities, buildDesktopReceiverCapabilities());
      runtime =
          ReceiverRuntime.start(config, libraryPath: library.absolute.path);
      final port = await runtime.started.timeout(const Duration(seconds: 5));
      http.badCertificateCallback = (cert, host, _) =>
          host == '127.0.0.1' &&
          base64Encode(cert.der) == certificate.certificateDer;
      final endpoint = 'wss://127.0.0.1:$port/';
      socket = await WebSocket.connect(endpoint, customClient: http);
      frames = StreamIterator(socket);
      Future<Map<String, dynamic>> nextFrame() async {
        if (!await frames!.moveNext().timeout(const Duration(seconds: 5))) {
          throw StateError('Loopback receiver closed before response');
        }
        return jsonDecode(frames!.current as String) as Map<String, dynamic>;
      }

      final sender = SasCrypto.generateX25519KeyPair();
      final nonceS = SasCrypto.generateNonce();
      final commit =
          SasCrypto.sha256(Uint8List.fromList(sender.publicKey + nonceS));
      socket.add(jsonEncode({
        'type': 'pairing_commit',
        'deviceName': 'Loopback Sender',
        'deviceUUID': 'loopback-sender',
        'commit': base64Encode(commit),
      }));
      final challenge = await nextFrame();
      expect(challenge['type'], 'pairing_challenge');
      final receiverPublic = base64Decode(challenge['tvEphPub'] as String);
      final nonceT = base64Decode(challenge['nonceT'] as String);
      final shared = SasCrypto.calculateECDH(sender.privateKey, receiverPublic);
      final transcript = Uint8List.fromList(
          commit + receiverPublic + nonceT + sender.publicKey + nonceS);
      socket.add(jsonEncode({
        'type': 'pairing_reveal',
        'senderEphPub': base64Encode(sender.publicKey),
        'nonceS': base64Encode(nonceS),
      }));
      final prk = SasCrypto.hkdfExtract(ikm: shared);
      final confirmationKey = SasCrypto.hkdfExpand(prk,
          info: Uint8List.fromList(utf8.encode('confirmationKey')));
      socket.add(jsonEncode({
        'type': 'pairing_confirmation',
        'mac': base64Encode(SasCrypto.hmacSha256(confirmationKey, transcript)),
      }));
      final approval = await nextFrame();
      expect(approval.keys, unorderedEquals(['type', 'nonce', 'ciphertext']));
      expect(approval['type'], 'pairing_approved');
      final credentialKey = SasCrypto.hkdfExpand(prk,
          info: Uint8List.fromList(utf8.encode('playbridgeCredentialKey-v1')));
      final bundle = jsonDecode(utf8.decode(SasCrypto.aesGcmDecrypt(
        key: credentialKey,
        nonce: base64Decode(approval['nonce'] as String),
        ciphertext: base64Decode(approval['ciphertext'] as String),
        aad: SasCrypto.sha256(transcript),
      ))) as Map<String, dynamic>;
      expect(bundle['capabilities'], config.capabilities);
      _expectLegacy(bundle);
      // Assert without ever including a pin or credential in test output.
      expect(bundle['certFingerprint'] == certificate.fingerprint, isTrue);
      final token = bundle['token'] as String;
      expect(token.isNotEmpty, isTrue);
      await socket.close();
      await frames.cancel();
      socket = await WebSocket.connect(endpoint, customClient: http);
      frames = StreamIterator(socket);
      socket.add(jsonEncode({'type': 'auth', 'token': token}));
      final auth = await nextFrame();
      expect(auth['success'], isTrue);
      expect(auth.containsKey('token'), isFalse);
      expect(auth['capabilities'], bundle['capabilities']);
      _expectLegacy(auth);
    } finally {
      await socket?.close();
      await frames?.cancel();
      http.close(force: true);
      runtime?.dispose();
      await directory.delete(recursive: true);
    }
  },
      skip: !Platform.isMacOS || !library.existsSync()
          ? 'The packaged macOS native library is unavailable'
          : false);
}
