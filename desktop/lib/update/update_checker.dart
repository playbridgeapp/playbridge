import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'app_version.dart';
import 'update_installer.dart';

/// Desktop port of the Android apps' `UpdateChecker`.
///
/// Version resolution reuses the website's download endpoint instead of the
/// GitHub API: a GET to `https://playbridge.app/download/<os>` with redirects
/// disabled returns a `Location` pointing at the latest release asset, e.g.
/// `…/releases/download/desktop-v0.7.0/playbridge-desktop-macos-0.7.0.zip`.
/// We parse the version from the filename and keep the URL for the download.
/// One cached request, no API rate limits — same trick as the phone/TV apps.
///
/// Unlike Android (system installer), the desktop accept path is a true
/// in-place self-update: [UpdateInstaller] downloads the archive, stages it,
/// and hands off to a detached swap script that replaces the install and
/// relaunches the app. See update_installer.dart for the per-OS details.
sealed class UpdateState {
  const UpdateState();
}

class UpdateIdle extends UpdateState {
  const UpdateIdle();
}

class UpdateChecking extends UpdateState {
  const UpdateChecking({required this.manual});
  final bool manual;
}

class UpdateUpToDate extends UpdateState {
  const UpdateUpToDate();
}

class UpdateAvailable extends UpdateState {
  const UpdateAvailable(this.info);
  final UpdateInfo info;
}

class UpdateDownloading extends UpdateState {
  const UpdateDownloading(this.info, this.fraction);
  final UpdateInfo info;

  /// 0..1, or null when the server didn't send a Content-Length.
  final double? fraction;
}

/// Archive downloaded; staging/extracting/handing off to the swap script.
class UpdateInstalling extends UpdateState {
  const UpdateInstalling(this.info);
  final UpdateInfo info;
}

class UpdateError extends UpdateState {
  const UpdateError(this.message, {required this.manual});
  final String message;

  /// Errors from background checks stay silent; manual ones surface in the UI.
  final bool manual;
}

class UpdateInfo {
  const UpdateInfo({required this.version, required this.archiveUrl});
  final AppVersion version;
  final String archiveUrl;
}

class UpdateChecker extends ChangeNotifier {
  UpdateChecker({UpdateInstaller? installer, this.endpointOverride})
      : installer = installer ?? UpdateInstaller();

  final UpdateInstaller installer;

  /// Test seam — replaces the platform download endpoint when non-null.
  final String? endpointOverride;

  UpdateState _state = const UpdateIdle();
  UpdateState get state => _state;

  /// Guards against overlapping checks (startup + a fast manual tap).
  bool _checking = false;

  void _set(UpdateState s) {
    _state = s;
    notifyListeners();
  }

  /// The website endpoint whose 302 Location names the latest release asset.
  static String platformEndpoint() {
    final os = Platform.isMacOS
        ? 'macos'
        : Platform.isWindows
            ? 'windows'
            : 'linux';
    return 'https://playbridge.app/download/$os';
  }

  /// Matches `…playbridge-desktop-<os>-0.7.0.zip|.tar.gz` in the resolved
  /// asset filename/URL. Kept in sync with the CI artifact names and the
  /// website's `functions/download/[platform].ts` patterns.
  static final RegExp assetVersionPattern =
      RegExp(r'playbridge-desktop-(?:macos|windows|linux)-(\d+(?:\.\d+)+)');

  /// Check for a newer version.
  ///
  /// [manual] true when the user tapped "Check for updates" — surfaces
  /// Checking / UpToDate / Error states. Startup checks (false) stay silent
  /// unless an update actually exists.
  Future<void> check({required bool manual}) async {
    if (_checking) return;
    // Don't stomp an in-flight download/install with a background check.
    final current = _state;
    if (!manual &&
        (current is UpdateDownloading ||
            current is UpdateInstalling ||
            current is UpdateAvailable)) {
      return;
    }
    _checking = true;
    debugPrint('UpdateChecker: check(manual=$manual) starting');
    _set(UpdateChecking(manual: manual));
    try {
      final info = await _resolveLatest();
      if (info == null) {
        debugPrint('UpdateChecker: up to date (v$kAppVersion)');
        _set(manual ? const UpdateUpToDate() : const UpdateIdle());
      } else {
        debugPrint('UpdateChecker: update available ${info.version}');
        _set(UpdateAvailable(info));
      }
    } catch (e) {
      debugPrint('UpdateChecker: check failed: $e');
      _set(manual
          ? UpdateError('Update check failed: $e', manual: true)
          : const UpdateIdle());
    } finally {
      _checking = false;
    }
  }

  /// Resolve the latest [UpdateInfo], or null if nothing newer is published.
  Future<UpdateInfo?> _resolveLatest() async {
    final endpoint = endpointOverride ?? platformEndpoint();
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10)
      ..userAgent = 'PlayBridge-Desktop-Updater';
    try {
      // GET (not HEAD) with redirects disabled: the Cloudflare Pages function
      // only handles GET, and a 302 carries no body, so this reads the
      // Location header without downloading the archive.
      final req = await client.getUrl(Uri.parse(endpoint));
      req.followRedirects = false;
      final resp = await req.close();
      await resp.drain<void>();

      final location = resp.headers.value(HttpHeaders.locationHeader);
      debugPrint('UpdateChecker: HTTP ${resp.statusCode}, Location=$location');
      if (resp.statusCode < 300 || resp.statusCode >= 400 || location == null) {
        throw StateError(
            'Expected a redirect from $endpoint but got HTTP ${resp.statusCode}');
      }

      final raw = assetVersionPattern.firstMatch(location)?.group(1);
      final latest = AppVersion.parse(raw);
      if (latest == null) {
        // The endpoint fell back to the releases *page* (no asset matched) or
        // the naming changed — treat as a failed check, not "up to date".
        throw StateError("Couldn't parse a version from redirect: $location");
      }

      if (latest <= AppVersion.current) return null;
      return UpdateInfo(version: latest, archiveUrl: location);
    } finally {
      client.close(force: true);
    }
  }

  /// User accepted an [UpdateAvailable] update: download, stage, swap, relaunch.
  ///
  /// On success this method never returns — the process exits and the swap
  /// script relaunches the new build.
  Future<void> accept(UpdateInfo info) async {
    debugPrint('UpdateChecker: accept ${info.version} (${info.archiveUrl})');
    _set(UpdateDownloading(info, null));
    final File archive;
    try {
      archive = await installer.download(
        info.archiveUrl,
        onProgress: (fraction) {
          if (_state is UpdateDownloading) {
            _set(UpdateDownloading(info, fraction));
          }
        },
      );
    } catch (e) {
      debugPrint('UpdateChecker: download failed: $e');
      _set(UpdateError('Download failed: $e', manual: true));
      return;
    }

    _set(UpdateInstalling(info));
    try {
      // Stages the new build, spawns the detached swap script, and exits the
      // process. Anything after this line only runs on failure.
      await installer.installAndRestart(archive);
    } catch (e) {
      debugPrint('UpdateChecker: install failed: $e');
      _set(UpdateError(
        'Install failed: $e\nYou can update manually from the downloads page.',
        manual: true,
      ));
    }
  }

  /// Open the platform download endpoint in the browser (manual fallback).
  void openDownloadPage() {
    installer.openInBrowser(endpointOverride ?? platformEndpoint());
  }

  void dismiss() => _set(const UpdateIdle());
}
