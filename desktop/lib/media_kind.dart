enum MediaKind {
  video('video'),
  audio('audio'),
  image('image');

  const MediaKind(this.wireValue);
  final String wireValue;

  static MediaKind? fromWire(String? value) =>
      switch (value?.trim().toLowerCase()) {
        'video' => MediaKind.video,
        'audio' => MediaKind.audio,
        'image' => MediaKind.image,
        _ => null,
      };
}

const _audioExtensions = {
  'mp3',
  'm4a',
  'aac',
  'ogg',
  'oga',
  'opus',
  'wav',
  'flac',
  'weba'
};
const _imageExtensions = {
  'jpg',
  'jpeg',
  'png',
  'webp',
  'avif',
  'gif',
  'bmp',
  'heic',
  'heif'
};
const _videoExtensions = {
  'mp4',
  'm4v',
  'mkv',
  'webm',
  'avi',
  'mov',
  'wmv',
  'flv',
  'ts',
  'm2ts',
  'mpg',
  'mpeg'
};

MediaKind resolveMediaKind({
  String? declared,
  required String url,
  String? contentType,
}) {
  final explicit = MediaKind.fromWire(declared);
  if (explicit != null) return explicit;

  final mime = (contentType ?? '').split(';').first.trim().toLowerCase();
  if (mime.startsWith('audio/')) return MediaKind.audio;
  if (mime.startsWith('image/')) return MediaKind.image;
  if (mime.startsWith('video/')) return MediaKind.video;

  final path = url.split('?').first.split('#').first.toLowerCase();
  final dot = path.lastIndexOf('.');
  final extension = dot < 0 ? '' : path.substring(dot + 1);
  if (_audioExtensions.contains(extension)) return MediaKind.audio;
  if (_imageExtensions.contains(extension)) return MediaKind.image;
  if (_videoExtensions.contains(extension)) return MediaKind.video;
  return MediaKind.video;
}
