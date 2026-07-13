/// Labels stream titles that arrive from the browser extension so Now Casting /
/// the TV UI can show browser origin the way the Android phone browser does.
///
/// Android falls back to `"Video from browser"` when no page title is known.
/// When a title is present we append a stable suffix rather than replacing it.
String titleForExtensionCast(String? title) {
  final trimmed = title?.trim();
  if (trimmed == null || trimmed.isEmpty) {
    return 'Video from browser';
  }
  final lower = trimmed.toLowerCase();
  if (lower.contains('via browser') || lower.endsWith('from browser')) {
    return trimmed;
  }
  return '$trimmed · via browser';
}
