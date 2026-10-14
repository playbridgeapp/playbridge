import 'dart:convert';
import 'dart:io';

enum SenderConnectionState { authFailed, connected }
class LegacyDesktopParser {
  SenderConnectionState? state;
  void _setState(SenderConnectionState value) { state = value; }
  void _close() {}
  void parse(Map<dynamic, dynamic> obj) { _handleAuthResponse(obj); }
  void _handleAuthResponse(Map<dynamic, dynamic> obj) {
    if (obj['success'] != true) {
      _setState(SenderConnectionState.authFailed);
      _close();
      return;
    }
    // SEC-005: legacy echoed tokens in auth_response are ignored.
    _setState(SenderConnectionState.connected);
  }

}

void main(List<String> args) {
  final root = args.single;
  final cases = jsonDecode(File('$root/manifest.json').readAsStringSync()) as List;
  var count = 0;
  for (final item in cases) {
    if (item['valid'] != true || !['AuthResponse', 'CredentialBundle'].contains(item['schema'])) continue;
    final obj = jsonDecode(File('$root/${item['file']}').readAsStringSync()) as Map<String, dynamic>;
    final without = Map<String, dynamic>.from(obj)..remove('capabilities');
    if (item['schema'] == 'AuthResponse') {
      final old = LegacyDesktopParser()..parse(without);
      final incoming = LegacyDesktopParser()..parse(obj);
      if (old.state != incoming.state) throw StateError(item['file']);
    } else {
      // The original post-decryption field reads; host/pin/crypto work is separate.
      final token = obj['token'] as String?;
      final certFp = (obj['certFingerprint'] as String?)?.trim();
      if (token != without['token'] || certFp != (without['certFingerprint'] as String?)?.trim()) {
        throw StateError(item['file']);
      }
    }
    count++;
  }
  print('Retained Desktop manual fields OK: $count auth/decrypted-bundle projections');
}
