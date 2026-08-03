import { normalizeReceiverMedia } from './shared/media.js';
import { classifyPlaybackError, createLoadLifecycle } from './shared/lifecycle.js';
import { createReceiverPresentation, redactUrl, redactUrlsInText } from './shared/presentation.js';

function sanitizedMediaForLog(media) {
  return {
    url: redactUrl(media && media.url),
    contentType: media && media.contentType,
    sourceKind: media && media.sourceKind,
    streamType: media && media.streamType,
    subtitleTracks: media && media.subtitleTracks && media.subtitleTracks.length
  };
}

function sanitizedErrorForLog(error) {
  return { category: error.category, code: error.code, message: redactUrlsInText(error.message) };
}

function removeAuthenticationHints(media) {
  if (!media || !media.customData || typeof media.customData !== 'object') return;
  const customData = { ...media.customData };
  for (const key of ['headers', 'httpHeaders', 'requestHeaders', 'authorization', 'credentials', 'cookies']) {
    delete customData[key];
  }
  media.customData = customData;
}

export function applyReceiverMediaToLoadRequest(request, messages) {
  if (!request || !request.media) throw new Error('LOAD media is required');
  const normalized = normalizeReceiverMedia(request.media, { startPosition: request.currentTime });
  const media = request.media;
  removeAuthenticationHints(media);
  media.contentUrl = normalized.url;
  if (!media.contentId) media.contentId = normalized.url;
  media.contentType = normalized.contentType;
  media.streamType = normalized.streamType;

  if (normalized.sourceKind === 'hls' && normalized.explicitTs) {
    const hlsFormats = messages && messages.HlsSegmentFormat;
    const videoFormats = messages && messages.HlsVideoSegmentFormat;
    if (!media.hlsSegmentFormat) {
      media.hlsSegmentFormat = hlsFormats && hlsFormats.TS_AAC || 'TS_AAC';
    }
    if (!media.hlsVideoSegmentFormat) {
      media.hlsVideoSegmentFormat = videoFormats && videoFormats.MPEG2_TS || 'MPEG2_TS';
    }
  }
  return { request, normalized };
}

export function startCastReceiver(dependencies = {}) {
  const castApi = dependencies.castApi || globalThis.cast;
  if (!castApi || !castApi.framework) throw new Error('Google Cast Application Framework is unavailable');
  const framework = castApi.framework;
  const messages = framework.messages;
  const context = dependencies.context || framework.CastReceiverContext.getInstance();
  const playerManager = dependencies.playerManager || context.getPlayerManager();
  const presentation = dependencies.presentation ||
    createReceiverPresentation(dependencies.document || globalThis.document, { mode: 'cast' });
  const logger = dependencies.logger || console;
  const lifecycle = createLoadLifecycle();
  let currentMedia = null;

  function log(event, details) {
    const method = event === 'error' ? 'error' : 'info';
    if (logger && typeof logger[method] === 'function') {
      logger[method]('[playbridge-cast] ' + event, details || '');
    }
  }

  playerManager.setMessageInterceptor(messages.MessageType.LOAD, (request) => {
    const generation = lifecycle.begin();
    try {
      const result = applyReceiverMediaToLoadRequest(request, messages);
      currentMedia = result.normalized;
      presentation.showMedia(currentMedia, 'buffering');
      log('load', sanitizedMediaForLog(currentMedia));
      if (!lifecycle.isCurrent(generation)) throw new Error('LOAD was superseded');
      return result.request;
    } catch (error) {
      const classified = classifyPlaybackError(error);
      presentation.showStatus(classified.message, true);
      log('error', sanitizedErrorForLog(classified));
      return Promise.reject(error);
    }
  });

  const playerEvents = framework.events || {};
  const eventTypes = playerEvents.EventType || {};
  const addPlayerEvent = (type, handler) => {
    if (type != null && typeof playerManager.addEventListener === 'function') {
      playerManager.addEventListener(type, handler);
    }
  };
  addPlayerEvent(eventTypes.PLAYER_LOADING, () => {
    if (currentMedia) presentation.showMedia(currentMedia, 'buffering');
    log('player-loading');
  });
  addPlayerEvent(eventTypes.PLAYER_LOAD_COMPLETE, () => log('manifest-loaded', sanitizedMediaForLog(currentMedia)));
  addPlayerEvent(eventTypes.SEGMENT_DOWNLOADED, (event) => {
    log('segment-downloaded', { url: redactUrl(event && event.url) });
  });
  addPlayerEvent(eventTypes.BUFFERING, (event) => {
    if (currentMedia) presentation.showMedia(currentMedia, event && event.isBuffering ? 'buffering' : 'playing');
    log('buffering', { buffering: Boolean(event && event.isBuffering) });
  });
  addPlayerEvent(eventTypes.PLAYING, () => {
    if (currentMedia) presentation.showMedia(currentMedia, 'playing');
    log('playing');
  });
  addPlayerEvent(eventTypes.ERROR, (event) => {
    const classified = classifyPlaybackError(event && (event.detailedErrorCode || event.error) || event);
    presentation.showStatus(classified.message, true);
    log('error', sanitizedErrorForLog(classified));
  });
  const returnToReady = (reason) => {
    lifecycle.cancel();
    currentMedia = null;
    presentation.showReady();
    log(reason);
  };
  addPlayerEvent(eventTypes.REQUEST_STOP, () => returnToReady('stopped'));
  addPlayerEvent(eventTypes.MEDIA_FINISHED, (event) => {
    const reason = event && event.endedReason || 'finished';
    if (String(reason).toLowerCase() === 'interrupted') {
      // CAF emits this for the item displaced by a replacement LOAD. The new
      // media is already current, so clearing it here hides the live receiver UI.
      log('interrupted');
      return;
    }
    returnToReady(String(reason).toLowerCase());
  });

  const contextEvents = framework.system && framework.system.EventType || {};
  if (typeof context.addEventListener === 'function') {
    if (contextEvents.READY != null) {
      context.addEventListener(contextEvents.READY, () => {
        if (!currentMedia) presentation.showReady();
        log('receiver-ready');
      });
    }
    if (contextEvents.SHUTDOWN != null) {
      context.addEventListener(contextEvents.SHUTDOWN, (event) => {
        lifecycle.cancel();
        log('shutdown', { reason: event && event.reason });
      });
    }
  }

  const options = new framework.CastReceiverOptions();
  options.useShakaForHls = true;
  presentation.showReady();
  context.start(options);
  return { context, playerManager, lifecycle };
}

if (typeof window !== 'undefined' && globalThis.cast && globalThis.document) {
  startCastReceiver();
}
