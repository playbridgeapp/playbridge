import 'dart:convert';

import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:test/test.dart';

ReceiverRuntimeConfig config({Map<String, Object?>? capabilities}) =>
    ReceiverRuntimeConfig(
      name: 'Synthetic Receiver',
      uuid: 'synthetic-receiver',
      certificateDer: '',
      privateKeyDer: '',
      privateKeyKind: 'pkcs8',
      players: const ['legacy_engine'],
      capabilities: capabilities,
    );

void main() {
  test('legacy and explicit null configs omit capabilities', () {
    expect(config().toJson().containsKey('capabilities'), isFalse);
    expect(config(capabilities: null).toJson().containsKey('capabilities'),
        isFalse);
    expect(config().toJson()['players'], ['legacy_engine']);
    expect(receiverRuntimeAbiVersion, 1);
  });

  test('descriptor JSON preserves unknown IDs and explicit empty sets', () {
    final descriptor = <String, Object?>{
      'version': 1,
      'surfaces': {
        'future_surface': {
          'engines': {
            'future_engine': {
              'keys': ['HOME', 'home', 'future_key'],
              'pointer': <String>[],
            },
          },
        },
      },
      'host': {'osFamily': 'future_os'},
    };
    final encoded =
        jsonDecode(jsonEncode(config(capabilities: descriptor).toJson()))
            as Map<String, dynamic>;
    expect(encoded['capabilities'], descriptor);
    final withoutDescriptor = Map<String, Object?>.from(encoded)
      ..remove('capabilities');
    expect(withoutDescriptor, config().toJson());
  });

  test('valid complete empty descriptor remains present rather than legacy',
      () {
    const descriptor = <String, Object?>{
      'version': 1,
      'surfaces': <String, Object?>{},
    };
    expect(
        config(capabilities: descriptor).toJson()['capabilities'], descriptor);
  });
}
