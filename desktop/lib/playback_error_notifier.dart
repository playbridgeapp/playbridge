import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:local_notifier/local_notifier.dart';

/// Shows one "Playback failed" desktop notification at a time.
///
/// macOS uses Apple's UserNotifications framework. local_notifier uses the older
/// NSUserNotification API there, which never registers the app with Notification
/// Center, so current macOS drops its notifications without an error. Windows and
/// Linux keep local_notifier, which works on both.
class PlaybackErrorNotifier {
  PlaybackErrorNotifier({required this.onClick});

  /// Runs when the person clicks the notification.
  final VoidCallback onClick;

  static const _id = 1;

  final _plugin = FlutterLocalNotificationsPlugin();
  Future<bool>? _macReady;
  LocalNotification? _shown;

  /// Sets up local_notifier on Windows and Linux. Call once at startup.
  static Future<void> setUp() async {
    if (Platform.isMacOS) return;
    await localNotifier.setup(appName: 'PlayBridge');
  }

  Future<void> show({required String title, required String body}) async {
    await close();
    if (Platform.isMacOS) {
      if (!await (_macReady ??= _readyMac())) return;
      await _plugin.show(
        _id,
        title,
        body,
        const NotificationDetails(
          macOS: DarwinNotificationDetails(
            presentAlert: true,
            presentBanner: true,
            presentList: true,
            presentSound: true,
          ),
        ),
      );
      return;
    }
    final notification = LocalNotification(title: title, body: body);
    notification.onClick = onClick;
    _shown = notification;
    await notification.show();
  }

  Future<void> close() async {
    if (Platform.isMacOS) {
      if (_macReady != null) await _plugin.cancel(_id);
      return;
    }
    final notification = _shown;
    _shown = null;
    await notification?.close();
  }

  /// Initialises the plugin and asks for permission the first time a notification
  /// is needed, rather than at launch. Returns whether notifications are allowed.
  Future<bool> _readyMac() async {
    await _plugin.initialize(
      const InitializationSettings(
        macOS: DarwinInitializationSettings(
          requestAlertPermission: false,
          requestBadgePermission: false,
          requestSoundPermission: false,
        ),
      ),
      onDidReceiveNotificationResponse: (_) => onClick(),
    );
    final mac = _plugin.resolvePlatformSpecificImplementation<
        MacOSFlutterLocalNotificationsPlugin>();
    return await mac?.requestPermissions(alert: true, sound: true) ?? false;
  }
}
