import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/receiver_capability_policy.dart';

void main() {
  group('ReceiverDescriptorParser', () {
    test('recognizedV1 returns true only for numeric 1', () {
      expect(ReceiverDescriptorParser.recognizedV1({'version': 1}), isTrue);
      expect(ReceiverDescriptorParser.recognizedV1({'version': 1.0}), isTrue);
      expect(ReceiverDescriptorParser.recognizedV1({'version': '1'}), isFalse);
      expect(ReceiverDescriptorParser.recognizedV1({'version': 2}), isFalse);
      expect(ReceiverDescriptorParser.recognizedV1({'version': 0}), isFalse);
      expect(ReceiverDescriptorParser.recognizedV1({}), isFalse);
      expect(ReceiverDescriptorParser.recognizedV1(null), isFalse);
      expect(ReceiverDescriptorParser.recognizedV1('version: 1'), isFalse);
    });

    test('parses minimal valid v1', () {
      final desc = ReceiverDescriptorParser.parse({
        'version': 1,
        'surfaces': <String, dynamic>{},
      });
      expect(desc, isNotNull);
      expect(desc!.version, 1);
      expect(desc.surfaces, isEmpty);
      expect(desc.volume, isNull);
      expect(desc.host, isNull);
      expect(desc.companionHints, isEmpty);
    });

    test('rejects payloads larger than 64 KiB', () {
      final hugeString = 'a' * (65 * 1024);
      final raw = {
        'version': 1,
        'surfaces': {
          'player': {
            'engines': {
              'mpv': {
                'transport': [hugeString],
              }
            }
          }
        }
      };
      expect(ReceiverDescriptorParser.recognizedV1(raw), isTrue);
      expect(ReceiverDescriptorParser.parse(raw), isNull);
    });

    test('rejects unrecognized top-level keys in v1', () {
      final raw = {
        'version': 1,
        'surfaces': <String, dynamic>{},
        'unexpected_field': 'fail',
      };
      expect(ReceiverDescriptorParser.recognizedV1(raw), isTrue);
      expect(ReceiverDescriptorParser.parse(raw), isNull);
    });

    test('rejects duplicate or invalid identifiers', () {
      final raw = {
        'version': 1,
        'surfaces': {
          'player': {
            'engines': {
              'mpv': {
                'transport': ['play', 'play'], // duplicate
              }
            }
          }
        }
      };
      expect(ReceiverDescriptorParser.parse(raw), isNull);

      final emptyId = {
        'version': 1,
        'surfaces': {
          'player': {
            'engines': {
              'mpv': {
                'transport': [''], // empty
              }
            }
          }
        }
      };
      expect(ReceiverDescriptorParser.parse(emptyId), isNull);

      final longId = {
        'version': 1,
        'surfaces': {
          'player': {
            'engines': {
              'mpv': {
                'transport': ['a' * 65], // > 64 chars
              }
            }
          }
        }
      };
      expect(ReceiverDescriptorParser.parse(longId), isNull);
    });

    test('rejects invalid volume step', () {
      final zeroStep = {
        'version': 1,
        'surfaces': <String, dynamic>{},
        'volume': {
          'scope': 'app',
          'operations': ['relative_step'],
          'step': 0.0,
        },
      };
      expect(ReceiverDescriptorParser.parse(zeroStep), isNull);

      final overStep = {
        'version': 1,
        'surfaces': <String, dynamic>{},
        'volume': {
          'scope': 'app',
          'operations': ['relative_step'],
          'step': 1.5,
        },
      };
      expect(ReceiverDescriptorParser.parse(overStep), isNull);

      final stringStep = {
        'version': 1,
        'surfaces': <String, dynamic>{},
        'volume': {
          'scope': 'app',
          'operations': ['relative_step'],
          'step': '0.05',
        },
      };
      expect(ReceiverDescriptorParser.parse(stringStep), isNull);
    });
  });

  group('Pure Resolver: Real descriptors', () {
    final androidTvDescriptor = {
      'version': 1,
      'surfaces': {
        'player': {
          'engines': {
            'exo': {
              'transport': [
                'play',
                'pause',
                'toggle',
                'stop',
                'seek_absolute',
                'seek_relative'
              ],
              'keys': [
                'dpad_up',
                'dpad_down',
                'dpad_left',
                'dpad_right',
                'dpad_center',
                'back',
                'home'
              ],
              'settings': [
                'loop',
                'engine',
                'speed',
                'scaling',
                'quality',
                'audio_boost',
                'subtitle_offset'
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
              'reporting': [
                'status',
                'context',
                'tracks',
                'playlist_status',
                'player_settings'
              ],
            },
            'mpv': {
              'transport': [
                'play',
                'pause',
                'toggle',
                'stop',
                'seek_absolute',
                'seek_relative'
              ],
              'keys': [
                'dpad_up',
                'dpad_down',
                'dpad_left',
                'dpad_right',
                'dpad_center',
                'back',
                'home'
              ],
              'settings': [
                'loop',
                'engine',
                'speed',
                'scaling',
                'quality',
                'audio_boost',
                'subtitle_offset'
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
        'browser': {
          'engines': {
            'webview': {
              'transport': ['toggle', 'seek_absolute', 'seek_relative'],
              'keys': [
                'dpad_up',
                'dpad_down',
                'dpad_left',
                'dpad_right',
                'dpad_center',
                'back',
                'home',
                'key_enter'
              ],
              'pointer': ['move', 'click', 'down', 'up', 'scroll', 'zoom'],
              'text': ['replace', 'enter'],
              'browser': [
                'launch',
                'forward',
                'reload',
                'cycle_source',
                'unmute',
                'fullscreen',
                'manage_user_scripts',
                'manage_user_agent'
              ],
              'reporting': ['context', 'status', 'fullscreen'],
            },
            'gecko': {
              'transport': ['toggle', 'seek_absolute', 'seek_relative'],
              'keys': [
                'dpad_up',
                'dpad_down',
                'dpad_left',
                'dpad_right',
                'dpad_center',
                'back',
                'home',
                'key_enter'
              ],
              'pointer': ['move', 'click', 'down', 'up', 'scroll'], // no zoom
              'text': ['replace', 'enter'],
              'browser': [
                'launch',
                'forward',
                'reload',
                'cycle_source',
                'unmute',
                'fullscreen',
                'manage_user_scripts',
                'manage_user_agent'
              ],
              'reporting': ['context', 'status', 'fullscreen'],
            },
          },
        },
      },
      'volume': {
        'scope': 'system',
        'operations': ['relative_step', 'mute_toggle'],
      },
      'host': {'osFamily': 'android', 'deviceClass': 'tv'},
      'companionHints': ['android_tv_remote_v2'],
    };

    final appleTvWithVolume = {
      'version': 1,
      'surfaces': {
        'player': {
          'engines': {
            'avplayer': {
              'transport': [
                'play',
                'pause',
                'toggle',
                'stop',
                'seek_absolute',
                'seek_relative'
              ],
              'keys': [
                'dpad_up',
                'dpad_down',
                'dpad_left',
                'dpad_right',
                'dpad_center',
                'back',
                'home'
              ],
              'settings': ['loop', 'engine'],
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
              'reporting': [
                'status',
                'context',
                'tracks',
                'playlist_status',
                'player_settings'
              ],
            },
            'mpv': {
              'transport': [
                'play',
                'pause',
                'toggle',
                'stop',
                'seek_absolute',
                'seek_relative'
              ],
              'keys': [
                'dpad_up',
                'dpad_down',
                'dpad_left',
                'dpad_right',
                'dpad_center',
                'back',
                'home'
              ],
              'settings': ['loop', 'engine'],
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
      'volume': {
        'scope': 'app',
        'operations': [
          'relative_step',
          'absolute',
          'mute_toggle',
          'level_report',
          'mute_report'
        ],
        'step': 0.05,
      },
      'host': {'osFamily': 'tvos', 'deviceClass': 'tv'},
    };

    final appleTvNoVolume = {
      'version': 1,
      'surfaces': {
        'player': {
          'engines': {
            'avplayer': {
              'transport': [
                'play',
                'pause',
                'toggle',
                'stop',
                'seek_absolute',
                'seek_relative'
              ],
              'keys': [
                'dpad_up',
                'dpad_down',
                'dpad_left',
                'dpad_right',
                'dpad_center',
                'back',
                'home'
              ],
              'settings': ['loop', 'engine'],
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
      'host': {'osFamily': 'tvos', 'deviceClass': 'tv'},
    };

    final desktopDescriptor = {
      'version': 1,
      'surfaces': {
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
      },
      'volume': {
        'scope': 'system',
        'operations': ['relative_step'],
        'fallbackScope': 'app',
      },
      'host': {'osFamily': 'macos', 'deviceClass': 'desktop'},
    };

    final cliDescriptor = {
      'version': 1,
      'surfaces': {
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
              'queue': ['replace', 'append', 'jump_index'],
              'reporting': ['status', 'context', 'playlist_status'],
            },
          },
        },
      },
      'host': {'osFamily': 'macos', 'deviceClass': 'cli'},
    };

    test('Android TV player controls', () {
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: androidTvDescriptor,
        activeSurface: 'player',
        activeEngine: 'exo',
        mediaKind: 'video',
      );
      expect(p.isDescriptorV1, isTrue);
      expect(p.isLegacy, isFalse);
      expect(p.isMalformedV1, isFalse);

      // Transport
      expect(p.canPlayPause, isTrue);
      expect(p.canStop, isTrue);
      expect(p.canSeekAbsolute, isTrue);
      expect(p.canSeekRelative, isTrue);

      // Keys (7 nav keys + mute)
      expect(p.supportedKeys, containsAll(['dpad_up', 'dpad_down', 'dpad_left', 'dpad_right', 'dpad_center', 'back', 'home', 'mute']));

      // Settings
      expect(p.canLoop, isTrue);
      expect(p.canSwitchEngine, isTrue);
      expect(p.availablePlayerEngines, equals({'exo', 'mpv'}));
      expect(p.canSpeed, isTrue);
      expect(p.canScaling, isTrue);
      expect(p.canQuality, isTrue);
      expect(p.canAudioBoost, isTrue);
      expect(p.canSubtitleOffset, isTrue);

      // Volume (system scope, TV volume label)
      expect(p.hasVolume, isTrue);
      expect(p.volumeLabel, 'TV volume');
      expect(p.canAdjustVolume, isTrue);
      expect(p.canToggleMute, isTrue);
      expect(p.canSetVolume, isFalse); // No absolute slider in Android TV

      // Browser launch
      expect(p.canLaunchBrowser, isTrue);
      expect(p.launchBrowserEngines, equals({'webview', 'gecko'}));
    });

    test('Apple TV with App volume controls', () {
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: appleTvWithVolume,
        activeSurface: 'player',
        activeEngine: 'avplayer',
        mediaKind: 'video',
        volumeState: const ReceiverVolumeState(
          scope: 'app',
          level: 0.42,
          muted: false,
        ),
      );
      expect(p.isDescriptorV1, isTrue);

      // Transport
      expect(p.canPlayPause, isTrue);
      expect(p.canStop, isTrue);
      expect(p.canSeekAbsolute, isTrue);
      expect(p.canSeekRelative, isTrue);

      // Keys (7 nav keys + mute)
      expect(p.supportedKeys, containsAll(['dpad_up', 'dpad_down', 'dpad_left', 'dpad_right', 'dpad_center', 'back', 'home', 'mute']));

      // Settings: ONLY loop and engine; unsupported settings are false
      expect(p.canLoop, isTrue);
      expect(p.canSwitchEngine, isTrue);
      expect(p.availablePlayerEngines, equals({'avplayer', 'mpv'}));
      expect(p.canSpeed, isFalse);
      expect(p.canScaling, isFalse);
      expect(p.canQuality, isFalse);
      expect(p.canAudioBoost, isFalse);
      expect(p.canSubtitleOffset, isFalse);

      // Volume: App volume!
      expect(p.hasVolume, isTrue);
      expect(p.volumeLabel, 'App volume');
      expect(p.volumeScope, 'app');
      expect(p.canAdjustVolume, isTrue);
      expect(p.canSetVolume, isTrue); // Absolute slider enabled
      expect(p.canToggleMute, isTrue);
      expect(p.volumeStep, 0.05);
      expect(p.currentVolumeLevel, 0.42);
      expect(p.isMuted, isFalse);

      // Browser: not advertised
      expect(p.canLaunchBrowser, isFalse);
    });

    test('Apple TV with no volume block displays no volume controls', () {
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: appleTvNoVolume,
        activeSurface: 'player',
        activeEngine: 'avplayer',
        mediaKind: 'video',
        volumeState: const ReceiverVolumeState(scope: 'app', level: 0.5),
      );
      expect(p.isDescriptorV1, isTrue);
      expect(p.hasVolume, isFalse);
      expect(p.volumeLabel, isNull);
      expect(p.canAdjustVolume, isFalse);
      expect(p.canSetVolume, isFalse);
      expect(p.canToggleMute, isFalse);
      expect(p.currentVolumeLevel, isNull);
    });

    test('Desktop receiver controls', () {
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: desktopDescriptor,
        activeSurface: 'player',
        activeEngine: 'internal_mpv',
        mediaKind: 'video',
      );
      expect(p.isDescriptorV1, isTrue);

      // Transport
      expect(p.canPlayPause, isTrue);
      expect(p.canStop, isTrue);
      expect(p.canSeekAbsolute, isTrue);
      expect(p.canSeekRelative, isTrue);

      // Keys: none advertised
      expect(p.supportedKeys, isEmpty);

      // Settings: none advertised
      expect(p.canLoop, isFalse);
      expect(p.canSwitchEngine, isFalse);

      // Volume: system with app fallback, relative only
      expect(p.hasVolume, isTrue);
      expect(p.volumeLabel, 'TV volume');
      expect(p.volumeScope, 'system');
      expect(p.fallbackScope, 'app');
      expect(p.canAdjustVolume, isTrue);
      expect(p.canSetVolume, isFalse);
      expect(p.canToggleMute, isFalse);

      // When volume state reports actual app scope, label changes to App volume
      final pReported = resolveReceiverCapabilityPolicy(
        rawDescriptor: desktopDescriptor,
        activeSurface: 'player',
        activeEngine: 'internal_mpv',
        mediaKind: 'video',
        volumeState: const ReceiverVolumeState(scope: 'app', level: 0.8),
      );
      expect(pReported.volumeLabel, 'App volume');
    });

    test('CLI receiver controls', () {
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: cliDescriptor,
        activeSurface: 'player',
        activeEngine: 'internal_mpv',
        mediaKind: 'video',
      );
      expect(p.isDescriptorV1, isTrue);

      // Transport
      expect(p.canPlayPause, isTrue);
      expect(p.canStop, isTrue);
      expect(p.canSeekAbsolute, isTrue);
      expect(p.canSeekRelative, isTrue);

      // No volume
      expect(p.hasVolume, isFalse);
      expect(p.volumeLabel, isNull);
      expect(p.canAdjustVolume, isFalse);
      expect(p.canSetVolume, isFalse);
      expect(p.canToggleMute, isFalse);

      // No keys, no settings, no browser
      expect(p.supportedKeys, isEmpty);
      expect(p.canLoop, isFalse);
      expect(p.canSwitchEngine, isFalse);
      expect(p.canLaunchBrowser, isFalse);

      // Queue: index jump only, no item jump
      expect(p.canPlaylistJumpIndex, isTrue);
      expect(p.canPlaylistJumpItem, isFalse);
      expect(p.canQueueAppend, isTrue);
      expect(p.canQueueRemove, isFalse);
    });
  });

  group('Resolver: Fallback and Malformed', () {
    test('null descriptor uses exact legacy fallback', () {
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: null,
        mediaKind: 'video',
      );
      expect(p.isLegacy, isTrue);
      expect(p.isDescriptorV1, isFalse);
      expect(p.isMalformedV1, isFalse);

      expect(p.canPlayPause, isTrue);
      expect(p.canStop, isTrue);
      expect(p.canSeekAbsolute, isTrue);
      expect(p.canSeekRelative, isTrue);

      expect(p.hasVolume, isTrue);
      expect(p.volumeLabel, 'TV volume');
      expect(p.canAdjustVolume, isTrue);
      expect(p.canSetVolume, isFalse);
      expect(p.canToggleMute, isFalse);

      expect(p.canLoop, isFalse);
      expect(p.canSwitchEngine, isFalse);
    });

    test('unknown version uses exact legacy fallback', () {
      final v2 = {
        'version': 2,
        'futureField': true,
      };
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: v2,
        mediaKind: 'video',
      );
      expect(p.isLegacy, isTrue);
      expect(p.isDescriptorV1, isFalse);
      expect(p.isMalformedV1, isFalse);
      expect(p.canPlayPause, isTrue);
      expect(p.hasVolume, isTrue);
    });

    test('string version "1" uses legacy fallback', () {
      final stringV = {
        'version': '1',
        'surfaces': <String, dynamic>{},
      };
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: stringV,
        mediaKind: 'video',
      );
      expect(p.isLegacy, isTrue);
      expect(p.isDescriptorV1, isFalse);
      expect(p.isMalformedV1, isFalse);
    });

    test('empty descriptor {version: 1, surfaces: {}} exposes NO controls', () {
      final empty = {
        'version': 1,
        'surfaces': <String, dynamic>{},
      };
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: empty,
        mediaKind: 'video',
      );
      expect(p.isDescriptorV1, isTrue);
      expect(p.isLegacy, isFalse);
      expect(p.isMalformedV1, isFalse);

      expect(p.canPlayPause, isFalse);
      expect(p.canStop, isFalse);
      expect(p.canSeekAbsolute, isFalse);
      expect(p.canSeekRelative, isFalse);
      expect(p.hasVolume, isFalse);
      expect(p.volumeLabel, isNull);
      expect(p.canAdjustVolume, isFalse);
      expect(p.canLoop, isFalse);
      expect(p.canSwitchEngine, isFalse);
      expect(p.supportedKeys, isEmpty);
    });

    test('malformed recognized v1 fails closed', () {
      final malformed = {
        'version': 1,
        'surfaces': 'invalid_not_a_map',
      };
      final p = resolveReceiverCapabilityPolicy(
        rawDescriptor: malformed,
        mediaKind: 'video',
      );
      expect(p.isMalformedV1, isTrue);
      expect(p.isLegacy, isFalse);
      expect(p.isDescriptorV1, isFalse);

      expect(p.canPlayPause, isFalse);
      expect(p.canStop, isFalse);
      expect(p.canSeekAbsolute, isFalse);
      expect(p.hasVolume, isFalse);
      expect(p.canAdjustVolume, isFalse);
    });
  });

  group('Resolver: Engine intersection and Context transitions', () {
    final browserWithGecko = {
      'version': 1,
      'surfaces': {
        'browser': {
          'engines': {
            'webview': {
              'transport': ['toggle'],
              'keys': ['dpad_center', 'key_enter'],
              'pointer': ['move', 'click', 'zoom'],
              'browser': ['reload', 'fullscreen'],
            },
            'gecko': {
              'transport': ['toggle'],
              'keys': ['dpad_center', 'key_enter'],
              'pointer': ['move', 'click'], // no zoom!
              'browser': ['reload'], // no fullscreen!
            },
          },
        },
      },
    };

    test('unknown active engine takes intersection of all surface engines', () {
      final pIntersection = resolveReceiverCapabilityPolicy(
        rawDescriptor: browserWithGecko,
        activeSurface: 'browser',
        activeEngine: null, // unknown engine
      );
      expect(pIntersection.canPointerMove, isTrue);
      expect(pIntersection.canPointerClick, isTrue);
      expect(pIntersection.canPointerZoom, isFalse); // Restricted by intersection!
      expect(pIntersection.canBrowserReload, isTrue);
      expect(pIntersection.canBrowserFullscreen, isFalse); // Restricted by intersection!

      // When active engine state arrives as webview:
      final pWebView = resolveReceiverCapabilityPolicy(
        rawDescriptor: browserWithGecko,
        activeSurface: 'browser',
        activeEngine: 'webview',
      );
      expect(pWebView.canPointerZoom, isTrue);
      expect(pWebView.canBrowserFullscreen, isTrue);

      // When active engine state arrives as gecko:
      final pGecko = resolveReceiverCapabilityPolicy(
        rawDescriptor: browserWithGecko,
        activeSurface: 'browser',
        activeEngine: 'gecko',
      );
      expect(pGecko.canPointerZoom, isFalse);
      expect(pGecko.canBrowserFullscreen, isFalse);
    });

    test('idle surface disables player and browser transport', () {
      final pIdle = resolveReceiverCapabilityPolicy(
        rawDescriptor: browserWithGecko,
        activeSurface: 'idle',
      );
      expect(pIdle.canPlayPause, isFalse);
      expect(pIdle.canPointerMove, isFalse);
    });
  });

  group('Resolver: Settings availability and Media restrictions', () {
    final tvDescriptor = {
      'version': 1,
      'surfaces': {
        'player': {
          'engines': {
            'mpv': {
              'transport': ['play', 'pause', 'toggle', 'stop', 'seek_absolute', 'seek_relative'],
              'settings': ['loop', 'engine', 'speed', 'scaling'],
              'imageTransforms': ['pan', 'zoom'],
            },
          },
        },
      },
    };

    test('settings availability false flags restrict declared operations', () {
      final pWithFlags = resolveReceiverCapabilityPolicy(
        rawDescriptor: tvDescriptor,
        activeSurface: 'player',
        activeEngine: 'mpv',
        settingsAvailability: const ReceiverSettingsAvailability(
          loopAvailable: false,
          speedAvailable: false,
        ),
      );
      expect(pWithFlags.canLoop, isFalse);
      expect(pWithFlags.canSpeed, isFalse);
      expect(pWithFlags.canScaling, isTrue);
    });

    test('image media kind restricts seek and loop, enables image transforms', () {
      final pImage = resolveReceiverCapabilityPolicy(
        rawDescriptor: tvDescriptor,
        activeSurface: 'player',
        activeEngine: 'mpv',
        mediaKind: 'image',
      );
      expect(pImage.canSeekAbsolute, isFalse);
      expect(pImage.canSeekRelative, isFalse);
      expect(pImage.canLoop, isFalse);
      expect(pImage.canImagePan, isTrue);
      expect(pImage.canImageZoom, isTrue);
    });

    test('live media restricts seek and loop', () {
      final pLive = resolveReceiverCapabilityPolicy(
        rawDescriptor: tvDescriptor,
        activeSurface: 'player',
        activeEngine: 'mpv',
        mediaKind: 'video',
        isLive: true,
      );
      expect(pLive.canSeekAbsolute, isFalse);
      expect(pLive.canSeekRelative, isFalse);
      expect(pLive.canLoop, isFalse);
    });
  });
}
