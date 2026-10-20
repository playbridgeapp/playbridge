import 'dart:io';

import 'package:playbridge_cast_core/playbridge_cast_core.dart';

/// Static native advertisement for ReceiverServer's implemented dispatch only.
/// Local player controls and the hosted-browser protocol are separate surfaces.
Map<String, Object?> buildDesktopReceiverCapabilities(
    {String? operatingSystem, bool macNativeVolume = true}) {
  final os = operatingSystem ?? Platform.operatingSystem;
  return {
    'version': 1,
    'surfaces': const {
      'player': {
        'engines': {
          'internal_mpv': {
            'transport': [
              'play',
              'pause',
              'toggle',
              'stop',
              'seek_absolute',
              'seek_relative',
            ],
            'imageTransforms': [
              'pan',
              'scroll',
              'zoom',
              'rotate',
              'anchor',
              'reset'
            ],
            'settings': ['speed', 'loop', 'scaling', 'subtitle_offset'],
            'tracks': [
              'audio_select',
              'subtitle_select',
              'subtitle_url_add',
              'subtitle_resource_add',
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
            'reporting': [
              'status',
              'context',
              'tracks',
              'playlist_status',
              'player_settings'
            ],
          },
        },
      },
    },
    // Implemented by SystemVolume (Core Audio on Windows). Relative stepping
    // can fall back to player attenuation; reports always name the actual scope.
    if (const {'macos', 'windows', 'linux'}.contains(os))
      'volume': {
        'scope': 'system',
        'operations': [
          'relative_step',
          if (os != 'macos' || macNativeVolume) ...[
            'absolute',
            'mute_toggle',
            'level_report',
            'mute_report'
          ],
        ],
        if (os == 'macos') 'step': 0.0625,
        'fallbackScope': 'app',
      },
    'host': {'osFamily': os, 'deviceClass': 'desktop'},
  };
}

/// Production runtime config, also exercised by loopback serialization tests.
/// All legacy advertisement fields retain ReceiverServer's existing values.
ReceiverRuntimeConfig buildDesktopReceiverRuntimeConfig({
  required String name,
  required String uuid,
  required String certificateDer,
  required String privateKeyDer,
  required String privateKeyKind,
  required int preferredPort,
  required List<String> authorizedTokens,
  bool macNativeVolume = true,
}) =>
    ReceiverRuntimeConfig(
      name: name,
      uuid: uuid,
      certificateDer: certificateDer,
      privateKeyDer: privateKeyDer,
      privateKeyKind: privateKeyKind,
      preferredPort: preferredPort,
      authorizedTokens: authorizedTokens,
      players: const ['internal_mpv'],
      mediaKinds: const ['video', 'audio', 'image'],
      features: const [
        'queue_crud_v1',
        'stable_item_ids',
        'command_results',
        'subtitle_resource_add_v1',
        'progress_webhook_v1',
        'receiver_capabilities_v1',
      ],
      screenMirrorWebRtc: true,
      capabilities:
          buildDesktopReceiverCapabilities(macNativeVolume: macNativeVolume),
    );
