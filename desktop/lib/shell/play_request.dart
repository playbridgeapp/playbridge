import 'dart:io';

/// File extensions the desktop accepts for local play and for sending to a TV.
const Set<String> kPlayableExtensions = {
  'mp4',
  'm4v',
  'mkv',
  'webm',
  'avi',
  'mov',
  'wmv',
  'flv',
  'mp3',
  'flac',
  'm4a',
  'aac',
  'ogg',
  'wav',
};

/// Files and links pulled out of something the user dropped, picked or pasted.
class PlayRequest {
  const PlayRequest({this.files = const [], this.urls = const []});

  final List<File> files;
  final List<String> urls;

  bool get isEmpty => files.isEmpty && urls.isEmpty;
  int get count => files.length + urls.length;
}

String _extension(String path) {
  final dot = path.lastIndexOf('.');
  return dot >= 0 ? path.substring(dot + 1).toLowerCase() : '';
}

/// Sorts dropped paths into playable files and http(s) links; anything else is ignored.
PlayRequest classifyPlayablePaths(Iterable<String> paths) {
  final files = <File>[];
  final urls = <String>[];
  for (final path in paths) {
    if (path.startsWith('http://') || path.startsWith('https://')) {
      urls.add(path);
      continue;
    }
    // Some other scheme (ftp:, smb:, ...) is neither a local file nor a link we play.
    if (path.contains('://')) continue;
    if (kPlayableExtensions.contains(_extension(path))) files.add(File(path));
  }
  return PlayRequest(files: files, urls: urls);
}

/// A pasted link if it is a usable http(s) address, otherwise null.
String? parsePlayableLink(String input) {
  final text = input.trim();
  if (text.isEmpty || text.contains(RegExp(r'\s'))) return null;
  final uri = Uri.tryParse(text);
  if (uri == null || !uri.hasAuthority || uri.host.isEmpty) return null;
  if (uri.scheme != 'http' && uri.scheme != 'https') return null;
  return text;
}
