import 'dart:convert';
import 'dart:io';
import '../generated/dart/lib/messages.pb.dart';

void main(List<String> args) {
  final root = args.single;
  final obj = jsonDecode(File('$root/unknown-identifiers-auth-v1.json').readAsStringSync()) as Map<String,dynamic>;
  final auth = AuthResponse()..mergeFromProto3Json(obj);
  if (auth.capabilities.version != 1 ||
      !auth.capabilities.surfaces.containsKey('future_surface') ||
      !auth.capabilities.surfaces['player']!.engines.containsKey('future_engine') ||
      auth.capabilities.volume.scope != 'future_scope') throw StateError('open identifiers lost');
  final raw = jsonDecode(File('$root/unknown-identifiers-bundle-v1.json').readAsStringSync()) as Map<String,dynamic>;
  final bundle = CredentialBundle()..mergeFromProto3Json(raw);
  if (bundle.token != 'TEST_ONLY_NOT_A_CREDENTIAL' || bundle.toString().contains(bundle.token)) {
    throw StateError('credential diagnostics/serialization mismatch');
  }
  if (!bundle.capabilities.surfaces.containsKey('future_surface')) throw StateError('bundle descriptor lost');
  final zero = VolumePayload(scope: 'app', level: 0.0).toProto3Json() as Map;
  if (zero['level'] != 0.0) throw StateError('required zero-volume field omitted');
  final failed = jsonDecode(File('$root/status-terminal-error.json').readAsStringSync()) as Map<String,dynamic>;
  final status = StatusMessage()..mergeFromProto3Json(failed);
  if (status.state != 'error' || status.error != "The media couldn't be loaded (HTTP 403)" ||
      status.playbackId != 'playback-1' || status.currentItemId != 'item-1') {
    throw StateError('terminal status reason or identity lost');
  }
  final normal = StatusMessage(type: 'status', state: 'playing');
  if ((normal.toProto3Json() as Map).containsKey('error')) throw StateError('absent error emitted');
  print('Generated Dart models OK: open IDs, auth/bundle decoding, redacted token diagnostics, optional terminal status reason');
}
