import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';

void main() {
  group('decideTvCastRoute', () {
    test('Google Cast direct mode is not overridden by captured headers', () {
      final route = decideTvCastRoute(
        protocol: TvProtocol.googleCast,
        proxyEnabled: false,
        alreadyProxied: false,
        hasSyntheticPlaylist: false,
        hasCompanionAudio: false,
        hasHeaders: true,
      );

      expect(route.useProxy, isFalse);
      expect(route.forwardHeadersDirectly, isFalse);
    });

    test('proxy mode replays headers through Desktop', () {
      final route = decideTvCastRoute(
        protocol: TvProtocol.googleCast,
        proxyEnabled: true,
        alreadyProxied: false,
        hasSyntheticPlaylist: false,
        hasCompanionAudio: false,
        hasHeaders: true,
      );

      expect(route.useProxy, isTrue);
      expect(route.forwardHeadersDirectly, isFalse);
    });

    test('synthetic and demuxed streams remain proxy-only', () {
      final synthetic = decideTvCastRoute(
        protocol: TvProtocol.googleCast,
        proxyEnabled: false,
        alreadyProxied: false,
        hasSyntheticPlaylist: true,
        hasCompanionAudio: false,
        hasHeaders: false,
      );
      final demuxed = decideTvCastRoute(
        protocol: TvProtocol.googleCast,
        proxyEnabled: false,
        alreadyProxied: false,
        hasSyntheticPlaylist: false,
        hasCompanionAudio: true,
        hasHeaders: false,
      );

      expect(synthetic.useProxy, isTrue);
      expect(demuxed.useProxy, isTrue);
    });

    test('PlayBridge protocol can carry direct headers', () {
      final route = decideTvCastRoute(
        protocol: TvProtocol.playBridge,
        proxyEnabled: false,
        alreadyProxied: false,
        hasSyntheticPlaylist: false,
        hasCompanionAudio: false,
        hasHeaders: true,
      );

      expect(route.useProxy, isFalse);
      expect(route.forwardHeadersDirectly, isTrue);
    });
  });
}
