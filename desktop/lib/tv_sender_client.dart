import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart' as crypto;
import 'package:flutter/foundation.dart';
import 'package:pointycastle/asn1.dart';
import 'package:web_socket_channel/io.dart';

import 'protocol.dart';

/// Computes the OkHttp-style SPKI pin `sha256/<base64(SHA-256(SubjectPublicKeyInfo
/// DER))>` from a certificate's DER bytes.
///
/// This MUST match what the receivers issue as `certFingerprint`: the phone's
/// [PinningTrustManager] uses `CertificatePinner.pin(leaf)` (= SHA-256 of
/// `cert.publicKey.encoded`, i.e. the SubjectPublicKeyInfo), and the Dart
/// receiver's `CertManager` computes the same over its public key. We locate the
/// SPKI inside the TBSCertificate as the unique SEQUENCE whose children are an
/// AlgorithmIdentifier SEQUENCE followed by a BIT STRING.
///
/// NOTE: the ASN.1 walk is the one bit that needs a real device check — pair
/// once and confirm the computed pin equals the TV-delivered `certFingerprint`.
String? spkiPinFromCertDer(Uint8List der) {
  try {
    final cert = ASN1Parser(der).nextObject() as ASN1Sequence;
    final tbs = cert.elements!.first as ASN1Sequence;
    for (final el in tbs.elements!) {
      if (el is ASN1Sequence &&
          el.elements != null &&
          el.elements!.length == 2 &&
          el.elements![0] is ASN1Sequence &&
          el.elements![1] is ASN1BitString) {
        final spkiDer = el.encodedBytes;
        if (spkiDer == null) return null;
        final digest = crypto.sha256.convert(spkiDer).bytes;
        return 'sha256/${base64.encode(digest)}';
      }
    }
  } catch (e) {
    debugPrint('[tv-sender] SPKI pin parse failed: $e');
  }
  return null;
}

enum SenderConnectionState {
  disconnected,
  connecting,

  /// `pairing_request` sent — waiting for the TV user to tap Allow.
  waitingForApproval,
  connected,

  /// TV user denied (or the request timed out on the TV).
  pairingDenied,

  /// Saved token rejected (e.g. TV reinstall) — caller should wipe + re-pair.
  authFailed,

  /// Served cert didn't match the pinned fingerprint — possible MITM; refuse.
  pinMismatch,
  error,
}

/// Token + SPKI pin issued by a TV at pairing/auth, for the caller to persist.
@immutable
class TvCredentials {
  final String token;
  final String? certFingerprint;
  const TvCredentials(this.token, this.certFingerprint);
}

/// Sender-side WebSocket client: connects the desktop to a TV receiver over
/// pinned `wss://` (or plain `ws://` fallback), runs the pairing/auth handshake,
/// then ships cast commands. Dart counterpart to the phone's `WebSocketClient`.
class TvSenderClient {
  IOWebSocketChannel? _channel;
  StreamSubscription? _sub;
  bool _userClosed = false;

  // Pin the server presented this handshake, and whether it failed to match.
  String? _capturedPin;
  bool _pinMismatch = false;

  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();

  SenderConnectionState _current = SenderConnectionState.disconnected;

  /// Connection-state changes (UI binds this).
  Stream<SenderConnectionState> get state => _state.stream;
  SenderConnectionState get currentState => _current;

  /// Non-handshake messages from the TV (status / playlist_status / tracks / …).
  Stream<String> get messages => _messages.stream;

  /// Emitted whenever the TV issues a token (first pairing or token refresh) so
  /// the caller can persist it via [TvConnectionStore].
  Stream<TvCredentials> get credentials => _credentials.stream;

  bool get isConnected => _current == SenderConnectionState.connected;

  void _setState(SenderConnectionState s) {
    _current = s;
    if (!_state.isClosed) _state.add(s);
  }

  /// Connects to a TV. Pass [token] = null/empty for first-time pairing (sends
  /// `pairing_request`, waits for Allow); pass a saved token to reconnect
  /// silently. [expectedPin] enforces the pinned cert on `wss://` reconnects;
  /// null means trust-on-first-use (first pairing).
  Future<void> connect({
    required String host,
    required int port,
    int? wssPort,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    await _close();
    _userClosed = false;
    _capturedPin = null;
    _pinMismatch = false;
    _setState(SenderConnectionState.connecting);

    final resolvedWssPort = wssPort ?? (port + 1);
    final uri = Uri.parse('wss://$host:$resolvedWssPort/');

    final customClient = HttpClient()
      ..badCertificateCallback = (X509Certificate cert, String h, int p) {
        final pin = spkiPinFromCertDer(cert.der);
        _capturedPin = pin;
        if (expectedPin == null) return true; // trust-on-first-use at pairing
        if (pin == expectedPin) return true;
        _pinMismatch = true;
        return false;
      };

    try {
      final channel = IOWebSocketChannel.connect(
        uri,
        customClient: customClient,
        pingInterval: const Duration(seconds: 15),
      );
      await channel.ready;
      _channel = channel;
      _sub =
          channel.stream.listen(_onMessage, onError: _onError, onDone: _onDone);

      if (token == null || token.isEmpty) {
        channel.sink.add(senderPairingRequestJson(
          deviceName: deviceName,
          deviceUUID: deviceUUID,
        ));
        _setState(SenderConnectionState.waitingForApproval);
      } else {
        channel.sink.add(senderAuthJson(token));
      }
    } catch (e) {
      await _close();
      if (_pinMismatch) {
        _setState(SenderConnectionState.pinMismatch);
      } else {
        debugPrint('[tv-sender] connect failed: $e');
        _setState(SenderConnectionState.error);
      }
    }
  }

  void _onMessage(dynamic data) {
    final text = data is String ? data : utf8.decode(data as List<int>);
    try {
      final obj = jsonDecode(text);
      if (obj is Map) {
        switch (obj['type']) {
          case 'pairing_approved':
            _handlePairingApproved(obj);
            return;
          case 'pairing_denied':
            _setState(SenderConnectionState.pairingDenied);
            _close();
            return;
          case 'auth_response':
            _handleAuthResponse(obj);
            return;
        }
      }
    } catch (_) {
      // Not JSON / not a handshake frame — fall through to forward.
    }
    if (!_messages.isClosed) _messages.add(text);
  }

  void _handlePairingApproved(Map<dynamic, dynamic> obj) {
    final token = obj['token'] as String?;
    final certFp = (obj['certFingerprint'] as String?)?.trim();
    // Bind the delivered fingerprint to the cert actually served this handshake.
    if (certFp != null &&
        certFp.isNotEmpty &&
        _capturedPin != null &&
        certFp != _capturedPin) {
      debugPrint(
          '[tv-sender] approved pin ($certFp) != served ($_capturedPin) — refusing');
      _setState(SenderConnectionState.pinMismatch);
      _close();
      return;
    }
    final pin = (certFp != null && certFp.isNotEmpty) ? certFp : _capturedPin;
    if (token != null && token.isNotEmpty && !_credentials.isClosed) {
      _credentials.add(TvCredentials(token, pin));
    }
    _setState(SenderConnectionState.connected);
  }

  void _handleAuthResponse(Map<dynamic, dynamic> obj) {
    if (obj['success'] != true) {
      _setState(SenderConnectionState.authFailed);
      _close();
      return;
    }
    final token = obj['token'] as String?;
    final certFp = (obj['certFingerprint'] as String?)?.trim();
    final pin = (certFp != null && certFp.isNotEmpty) ? certFp : _capturedPin;
    if (token != null && token.isNotEmpty && !_credentials.isClosed) {
      _credentials.add(TvCredentials(token, pin));
    }
    _setState(SenderConnectionState.connected);
  }

  void _onError(Object error) {
    debugPrint('[tv-sender] socket error: $error');
    if (_pinMismatch) {
      _setState(SenderConnectionState.pinMismatch);
    } else if (!_userClosed) {
      _setState(SenderConnectionState.error);
    }
  }

  void _onDone() {
    if (_current != SenderConnectionState.authFailed &&
        _current != SenderConnectionState.pinMismatch &&
        _current != SenderConnectionState.pairingDenied) {
      _setState(SenderConnectionState.disconnected);
    }
  }

  /// Sends a pre-built protocol message (use the `sender*Json` builders).
  bool send(String message) {
    final c = _channel;
    if (c == null) return false;
    c.sink.add(message);
    return true;
  }

  bool sendPing() => send(senderPingJson());

  Future<void> disconnect() async {
    _userClosed = true;
    await _close();
    _setState(SenderConnectionState.disconnected);
  }

  Future<void> _close() async {
    await _sub?.cancel();
    _sub = null;
    final c = _channel;
    _channel = null;
    if (c != null) {
      try {
        await c.sink.close();
      } catch (_) {}
    }
  }

  Future<void> dispose() async {
    await _close();
    await _state.close();
    await _messages.close();
    await _credentials.close();
  }
}
