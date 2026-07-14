/// Compile-time app version.
///
/// MUST match `version:` in pubspec.yaml (build-metadata part excluded) —
/// guarded by `test/update_test.dart` so CI fails if they drift.
const String kAppVersion = '0.7.0';

/// Dotted numeric version (`0.6.4`, `1.10.2`) with sane comparison semantics.
///
/// Mirrors the Android apps' `AppVersion`: parse leniently (ignore a leading
/// `v`, tolerate suffixes after the numeric part), compare component-wise with
/// missing components treated as 0 (`1.2` == `1.2.0`).
class AppVersion implements Comparable<AppVersion> {
  const AppVersion(this.parts);

  final List<int> parts;

  static AppVersion? parse(String? raw) {
    if (raw == null) return null;
    var s = raw.trim();
    if (s.startsWith('v') || s.startsWith('V')) s = s.substring(1);
    final m = RegExp(r'^(\d+(?:\.\d+)*)').firstMatch(s);
    if (m == null) return null;
    return AppVersion(
      m.group(1)!.split('.').map(int.parse).toList(growable: false),
    );
  }

  static final AppVersion current = AppVersion.parse(kAppVersion)!;

  @override
  int compareTo(AppVersion other) {
    final n =
        parts.length > other.parts.length ? parts.length : other.parts.length;
    for (var i = 0; i < n; i++) {
      final a = i < parts.length ? parts[i] : 0;
      final b = i < other.parts.length ? other.parts[i] : 0;
      if (a != b) return a.compareTo(b);
    }
    return 0;
  }

  bool operator >(AppVersion other) => compareTo(other) > 0;
  bool operator <(AppVersion other) => compareTo(other) < 0;
  bool operator >=(AppVersion other) => compareTo(other) >= 0;
  bool operator <=(AppVersion other) => compareTo(other) <= 0;

  @override
  bool operator ==(Object other) =>
      other is AppVersion && compareTo(other) == 0;

  @override
  int get hashCode {
    // Trailing zeros don't affect equality, so strip them before hashing.
    var end = parts.length;
    while (end > 0 && parts[end - 1] == 0) {
      end--;
    }
    return Object.hashAll(parts.sublist(0, end));
  }

  @override
  String toString() => parts.join('.');
}
