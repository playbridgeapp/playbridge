const HLS_MIME = 'application/x-mpegURL';
const DASH_MIME = 'application/dash+xml';

function cleanString(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function pathFromUrl(url, baseUrl) {
  try {
    return new URL(url, baseUrl || 'https://receiver.invalid/').pathname.toLowerCase();
  } catch (_) {
    return String(url || '').split('?')[0].split('#')[0].toLowerCase();
  }
}

export function detectStreamKind(url, contentType) {
  const type = String(contentType || '').toLowerCase();
  const path = pathFromUrl(url);
  if (
    type.includes('mpegurl') ||
    /\.m3u8$/i.test(path) ||
    /[?&](format|type|ext)=m3u8\b/i.test(String(url || ''))
  ) {
    return 'hls';
  }
  if (type.includes('dash') || type.includes('mpd') || /\.mpd$/i.test(path)) {
    return 'dash';
  }
  return 'progressive';
}

export function inferContentType(url, contentType) {
  const explicit = cleanString(contentType);
  if (explicit) return explicit;
  const path = pathFromUrl(url);
  if (/\.m3u8$/i.test(path)) return HLS_MIME;
  if (/\.mpd$/i.test(path)) return DASH_MIME;
  if (/\.mp3$/i.test(path)) return 'audio/mpeg';
  if (/\.(m4a|aac)$/i.test(path)) return 'audio/mp4';
  if (/\.webm$/i.test(path)) return 'video/webm';
  if (/\.mp4$/i.test(path)) return 'video/mp4';
  return 'video/mp4';
}

export function sourceTypeForKind(kind, contentType) {
  if (kind === 'hls') return HLS_MIME;
  if (kind === 'dash') return DASH_MIME;
  return inferContentType('', contentType);
}

function normalizeArtwork(metadata, input) {
  const images = (metadata && metadata.images) || input.images || [];
  const artwork = [];
  for (const image of images) {
    const url = cleanString(typeof image === 'string' ? image : image && image.url);
    if (url) artwork.push({ url });
  }
  const fallback = cleanString(input.posterUrl || input.artworkUrl || input.artUrl);
  if (!artwork.length && fallback) artwork.push({ url: fallback });
  return artwork;
}

function normalizeSubtitleTracks(input) {
  const tracks = [];
  for (const track of input.tracks || input.subtitleTracks || []) {
    const kind = String(track && (track.type || track.kind) || '').toUpperCase();
    if (kind && kind !== 'TEXT' && kind !== 'SUBTITLES' && kind !== 'CAPTIONS') continue;
    const url = cleanString(track && (track.trackContentId || track.contentId || track.url || track.src));
    if (!url) continue;
    tracks.push({
      id: track.trackId == null ? tracks.length + 1 : track.trackId,
      url,
      contentType: cleanString(track.trackContentType || track.contentType) || 'text/vtt',
      language: cleanString(track.language) || 'und',
      label: cleanString(track.name || track.label) || 'Subtitles',
      subtype: cleanString(track.subtype) || 'SUBTITLES'
    });
  }
  const subtitleUrl = cleanString(input.subtitleUrl);
  if (subtitleUrl && !tracks.some((track) => track.url === subtitleUrl)) {
    tracks.push({
      id: tracks.length + 1,
      url: subtitleUrl,
      contentType: 'text/vtt',
      language: 'und',
      label: 'Subtitles',
      subtype: 'SUBTITLES'
    });
  }
  return tracks;
}

function normalizeStartPosition(input) {
  if (Number.isFinite(input.currentTime)) return Math.max(0, input.currentTime);
  if (Number.isFinite(input.startPosition)) return Math.max(0, input.startPosition);
  if (Number.isFinite(input.startPositionMs)) return Math.max(0, input.startPositionMs / 1000);
  return 0;
}

function isExplicitTs(input) {
  const customData = input.customData || {};
  return [input.hlsFormat, input.segmentFormat, input.hlsSegmentFormat, customData.hlsFormat, customData.segmentFormat]
    .some((value) => String(value || '').toLowerCase() === 'ts');
}

/**
 * Normalize browser protocol media and CAF MediaInformation into ReceiverMedia.
 */
export function normalizeReceiverMedia(input, options = {}) {
  input = input || {};
  const metadata = input.metadata || {};
  const url = cleanString(input.contentUrl) || cleanString(input.url) || cleanString(input.contentId);
  if (!url) throw new Error('Media URL is required');
  const parsed = new URL(url, options.baseUrl || 'https://receiver.invalid/');
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Only HTTP and HTTPS media URLs are supported');
  }

  const contentType = inferContentType(url, input.contentType);
  const sourceKind = detectStreamKind(url, contentType);
  const streamType = String(input.streamType || (input.isLive ? 'LIVE' : 'BUFFERED')).toUpperCase();
  const live = streamType === 'LIVE';
  return {
    url,
    contentType,
    sourceKind,
    streamType: live ? 'LIVE' : streamType === 'NONE' ? 'NONE' : 'BUFFERED',
    live,
    title: cleanString(input.title || metadata.title) || 'Media',
    artwork: normalizeArtwork(metadata, input),
    subtitleTracks: normalizeSubtitleTracks(input),
    startPosition: Number.isFinite(options.startPosition)
      ? Math.max(0, options.startPosition)
      : normalizeStartPosition(input),
    hlsSegmentFormat: cleanString(input.hlsSegmentFormat),
    hlsVideoSegmentFormat: cleanString(input.hlsVideoSegmentFormat),
    explicitTs: sourceKind === 'hls' && isExplicitTs(input)
  };
}

export const receiverMediaTypes = { HLS_MIME, DASH_MIME };
