import Foundation

/// JavaScript injected into every page/frame at document-start. It replaces the GeckoView
/// WebExtension (`assets/extensions/video_detector/`): on WKWebView there's no `webRequest`, so we
/// monkey-patch `fetch`/`XMLHttpRequest` (the network sniffer), scan the DOM for `<video>/<source>`
/// (port of `content.js`) and keep media playing when backgrounded (visibility shim).
/// PageCastScript provides the casting API separately. Detection reports via
/// `window.webkit.messageHandlers.playbridge.postMessage(...)`.
enum DetectionScript {
    /// The single user script source. Runs in the page world, all frames.
    static let source = #"""
    (function () {
      if (window.__playbridgeSetDetectionEnabled) return;
      var stopDetection = null;
      function setEnabled(enabled) {
        if (!!stopDetection === !!enabled) return;
        if (stopDetection) { var stop = stopDetection; stopDetection = null; stop(); }
        if (enabled) stopDetection = startDetection();
      }
      window.__playbridgeSetDetectionEnabled = setEnabled;
      window.addEventListener('pageshow', function () {
        try { window.webkit.messageHandlers.playbridge.postMessage({type: 'detectionPolicyRequest'}); } catch (_) {}
      });
      setEnabled(window.__playbridgeDetectionEnabled !== false);

      function startDetection() {
        var active = true;
        var cleanups = [];
        var readers = new Set();
        function cancelReader(reader) {
          if (!readers.delete(reader)) return;
          try { Promise.resolve(reader.cancel()).catch(function () {}); } catch (_) {}
        }
        function listen(target, type, fn, options) {
          target.addEventListener(type, fn, options);
          cleanups.push(function () { target.removeEventListener(type, fn, options); });
        }

        // Players that break when their visibility state is faked (YouTube) run in
        // "safe mode": detection (fetch/XHR/DOM) still runs, but the visibility shim is
        // skipped so the player doesn't error out.
        var __pb_host = (location.hostname || '').toLowerCase();
        var __pb_safe = /(^|\.)(youtube\.com|youtube-nocookie\.com|googlevideo\.com|youtu\.be)$/.test(__pb_host);

        var SEGMENT = /\.(ts|m4s|fmp4|cmfv|cmfa)(\?|$)/i;
        var MEDIA = /\.(m3u8|mpd|mp4|m4v|mov|mkv|webm|avi|flv|wmv|3gp|mp3|m4a|aac|ogg|oga|opus|wav|flac|weba|vtt|srt)(\?|$)/i;
        var MEDIA_CT = /(video\/|audio\/|mpegurl|application\/dash|application\/octet-stream|text\/vtt|application\/x-subrip)/i;
        var IMAGE_NOISE = /(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;
        var SUBTITLE_SCAN_LIMIT = 256 * 1024;

        function post(msg) {
          if (!active) return;
          try { window.webkit.messageHandlers.playbridge.postMessage(msg); } catch (e) {}
        }

        // Keep prior streams on SPA navigation, but tell native ranking which
        // view is current. Only the main frame owns the tab's media lifecycle.
        var lastPageURL = location.href;
        function pageChanged() {
          if (window !== window.top || location.href === lastPageURL) return;
          lastPageURL = location.href;
          post({type: 'mediaLifecycle'});
          scanAll();
        }
        ['pushState', 'replaceState'].forEach(function (name) {
          var original = history[name];
          var wrapped = function () {
            var result = original.apply(this, arguments);
            pageChanged();
            return result;
          };
          history[name] = wrapped;
          cleanups.push(function () { if (history[name] === wrapped) history[name] = original; });
        });
        listen(window, 'popstate', pageChanged);
        listen(window, 'hashchange', pageChanged);

        function report(url, contentType, detectedBy, mediaKind) {
          if (!url || typeof url !== 'string') return;
          if (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) return;
          if (url.indexOf('http') !== 0) {
            // Resolve protocol-relative / relative URLs against the document.
            try { url = new URL(url, location.href).href; } catch (e) { return; }
          }
          var path = url.split('?')[0];
          if (SEGMENT.test(path)) return; // HLS/DASH media segments aren't standalone streams
          var message = {
            type: 'video',
            url: url,
            contentType: contentType || '',
            detectedBy: detectedBy || 'unknown',
            originUrl: location.href,
            ua: navigator.userAgent
          };
          if (mediaKind) message.mediaKind = mediaKind;
          post(message);
        }

        function looksMedia(url) {
          if (!url) return false;
          var path = ('' + url).split('?')[0];
          return MEDIA.test(path) && !SEGMENT.test(path);
        }

        function subtitleTypeFromDisposition(value) {
          var match = /filename\*?\s*=\s*(?:utf-8''|["'])?[^;"']+\.(srt|vtt)(?:["']|;|$)/i.exec(value || '');
          if (!match) return '';
          return match[1].toLowerCase() === 'vtt' ? 'text/vtt' : 'application/x-subrip';
        }

        function subtitleTypeFromBody(body) {
          var trimmed = (body || '').replace(/^\uFEFF/, '').replace(/^\s+/, '');
          if (/^WEBVTT(?:[ \t].*)?(?:\r?\n|$)/i.test(trimmed)) return 'text/vtt';
          if (/^(?:\d{1,7}\s*\r?\n\s*)?\d{1,2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{3}(?:\s|$)/.test(trimmed.slice(0, 8192))) {
            return 'application/x-subrip';
          }
          return '';
        }

        function shouldInspectSubtitleBody(contentType) {
          var ct = (contentType || '').toLowerCase();
          if (/^(video|audio|image)\//.test(ct) || /font|css/.test(ct)) return false;
          return !ct || /text\/|json|javascript|xml|octet-stream|vtt|subrip/.test(ct);
        }

        function scanSubtitleBody(url, body, disposition, detectedBy) {
          if (!active) return;
          var contentType = subtitleTypeFromBody(body) || subtitleTypeFromDisposition(disposition);
          if (contentType) report(url, contentType, detectedBy || 'body_content_subtitle', 'subtitle');
        }

        function inspectFetchSubtitle(resp, fallbackURL) {
          if (!active) return;
          try {
            var url = resp.url || fallbackURL;
            var contentType = (resp.headers && resp.headers.get('content-type')) || '';
            var disposition = (resp.headers && resp.headers.get('content-disposition')) || '';
            var dispositionType = subtitleTypeFromDisposition(disposition);
            if (dispositionType) report(url, dispositionType, 'subtitle_disposition', 'subtitle');
            if (!shouldInspectSubtitleBody(contentType) || !resp.clone) return;

            var length = parseInt((resp.headers && resp.headers.get('content-length')) || '', 10);
            if (isFinite(length) && length > SUBTITLE_SCAN_LIMIT) return;
            var copy = resp.clone();

            // WKWebView supports streamed response bodies. Keep the duplicate read bounded so
            // generic text/API responses cannot turn subtitle sniffing into an unbounded copy.
            if (copy.body && copy.body.getReader && typeof TextDecoder !== 'undefined') {
              var reader = copy.body.getReader();
              readers.add(reader);
              var decoder = new TextDecoder();
              var bytes = 0;
              var text = '';
              function pump() {
                if (!active) { cancelReader(reader); return; }
                reader.read().then(function (part) {
                  if (!active) { cancelReader(reader); return; }
                  if (part.done) {
                    readers.delete(reader);
                    text += decoder.decode();
                    scanSubtitleBody(url, text, disposition, 'body_content_subtitle');
                    return;
                  }
                  bytes += part.value.byteLength;
                  if (bytes > SUBTITLE_SCAN_LIMIT) { cancelReader(reader); return; }
                  text += decoder.decode(part.value, {stream: true});
                  pump();
                }).catch(function () { readers.delete(reader); });
              }
              pump();
            } else if (copy.text) {
              copy.text().then(function (text) {
                if (text.length <= SUBTITLE_SCAN_LIMIT) {
                  scanSubtitleBody(url, text, disposition, 'body_content_subtitle');
                }
              }).catch(function () {});
            }
          } catch (e) {}
        }

        // ── fetch hook ──────────────────────────────────────────────────────────
        var origFetch = window.fetch;
        if (origFetch) {
          var wrappedFetch = function (input, init) {
            if (!active) return origFetch.apply(this, arguments);
            var url = (typeof input === 'string') ? input : (input && input.url);
            if (looksMedia(url)) report(url, '', 'fetch_url');
            var p = origFetch.apply(this, arguments);
            try {
              return p.then(function (resp) {
                try {
                  if (!active) return resp;
                  var ct = resp.headers && resp.headers.get('content-type');
                  if (ct && MEDIA_CT.test(ct)) report(resp.url || url, ct, 'fetch_content_type');
                  inspectFetchSubtitle(resp, url);
                } catch (e) {}
                return resp;
              });
            } catch (e) { return p; }
          };
          window.fetch = wrappedFetch;
          cleanups.push(function () { if (window.fetch === wrappedFetch) window.fetch = origFetch; });
        }

        // ── XHR hook ────────────────────────────────────────────────────────────
        var origOpen = XMLHttpRequest.prototype.open;
        var wrappedOpen = function (method, url) {
          if (!active) return origOpen.apply(this, arguments);
          this.__pb_url = url;
          if (looksMedia(url)) report(url, '', 'xhr_url');
          return origOpen.apply(this, arguments);
        };
        var origSend = XMLHttpRequest.prototype.send;
        var wrappedSend = function () {
          if (!active) return origSend.apply(this, arguments);
          var xhr = this;
          xhr.addEventListener('readystatechange', function () {
            if (!active) return;
            if (xhr.readyState === 2) {
              try {
                var ct = xhr.getResponseHeader('content-type');
                if (ct && MEDIA_CT.test(ct)) report(xhr.responseURL || xhr.__pb_url, ct, 'xhr_content_type');
                var dispositionType = subtitleTypeFromDisposition(xhr.getResponseHeader('content-disposition') || '');
                if (dispositionType) report(xhr.responseURL || xhr.__pb_url, dispositionType, 'subtitle_disposition', 'subtitle');
              } catch (e) {}
            }
          });
          xhr.addEventListener('loadend', function () {
            if (!active) return;
            try {
              var ct = xhr.getResponseHeader('content-type') || '';
              var disposition = xhr.getResponseHeader('content-disposition') || '';
              var length = parseInt(xhr.getResponseHeader('content-length') || '', 10);
              if (!shouldInspectSubtitleBody(ct) || (isFinite(length) && length > SUBTITLE_SCAN_LIMIT)) return;
              var url = xhr.responseURL || xhr.__pb_url;
              if (xhr.responseType === '' || xhr.responseType === 'text') {
                if (typeof xhr.responseText === 'string' && xhr.responseText.length <= SUBTITLE_SCAN_LIMIT) {
                  scanSubtitleBody(url, xhr.responseText, disposition, 'body_content_subtitle');
                }
              } else if (xhr.responseType === 'arraybuffer' && xhr.response && typeof TextDecoder !== 'undefined') {
                if (xhr.response.byteLength <= SUBTITLE_SCAN_LIMIT) {
                  scanSubtitleBody(url, new TextDecoder().decode(xhr.response), disposition, 'body_content_subtitle');
                }
              } else if (xhr.responseType === 'blob' && xhr.response && typeof xhr.response.text === 'function') {
                if (xhr.response.size <= SUBTITLE_SCAN_LIMIT) {
                  xhr.response.text().then(function (body) {
                    if (body.length <= SUBTITLE_SCAN_LIMIT) {
                      scanSubtitleBody(url, body, disposition, 'body_content_subtitle');
                    }
                  }).catch(function () {});
                }
              }
            } catch (e) {}
          });
          return origSend.apply(this, arguments);
        };
        XMLHttpRequest.prototype.open = wrappedOpen;
        XMLHttpRequest.prototype.send = wrappedSend;
        cleanups.push(function () {
          if (XMLHttpRequest.prototype.open === wrappedOpen) XMLHttpRequest.prototype.open = origOpen;
          if (XMLHttpRequest.prototype.send === wrappedSend) XMLHttpRequest.prototype.send = origSend;
        });

        // ── DOM scan (port of content.js) ─────────────────────────────────────────
        function scanEl(el) {
          if (!active || !el || !el.tagName) return;
          if (el.tagName === 'VIDEO') {
            if (el.currentSrc || el.src) report(el.currentSrc || el.src, '', 'dom_video_element', 'video');
            if (el.poster) report(el.poster, '', 'dom_image_element', 'image');
          } else if (el.tagName === 'SOURCE') {
            if (el.src) report(el.src, el.type || '', el.parentElement && el.parentElement.tagName === 'AUDIO' ? 'dom_audio_element' : 'dom_video_element',
                               el.parentElement && el.parentElement.tagName === 'AUDIO' ? 'audio' : 'video');
          } else if (el.tagName === 'AUDIO') {
            if (el.src) report(el.src, '', 'dom_audio_element', 'audio');
          } else if (el.tagName === 'IMG') {
            var imageURL = el.currentSrc || el.src;
            var width = el.naturalWidth || el.width || el.clientWidth || 0;
            var height = el.naturalHeight || el.height || el.clientHeight || 0;
            if (imageURL && !IMAGE_NOISE.test(imageURL) && width >= 64 && height >= 64 && width * height >= 16384) {
              report(imageURL, '', 'dom_image_element', 'image');
            }
            if (!el.complete && !el.__pb_waiting_image) {
              el.__pb_waiting_image = true;
              el.addEventListener('load', function () { scanEl(el); }, {once: true});
            }
          } else if (el.tagName === 'TRACK') {
            if (el.src && el.src.indexOf('http') === 0) report(el.src, '', 'dom_track_element', 'subtitle');
          }
        }
        function scanAll() { if (!active) return; document.querySelectorAll('video, audio, source, track, img').forEach(scanEl); }
        if (document.readyState === 'loading') {
          listen(document, 'DOMContentLoaded', scanAll);
        } else { scanAll(); }
        try {
          var observer = new MutationObserver(function (muts) {
            if (!active) return;
            for (var i = 0; i < muts.length; i++) {
              var m = muts[i];
              for (var j = 0; j < m.addedNodes.length; j++) {
                var n = m.addedNodes[j];
                if (n.nodeType !== 1) continue;
                scanEl(n);
                if (n.querySelectorAll) n.querySelectorAll('video, audio, source, track, img').forEach(scanEl);
              }
              if (m.type === 'attributes' && m.target && m.target.nodeType === 1) scanEl(m.target);
            }
          });
          observer.observe(document.documentElement, {
            childList: true, subtree: true, attributes: true, attributeFilter: ['src']
          });
          cleanups.push(function () { observer.disconnect(); });
        } catch (e) {}

        // ── Background-playback shim (skipped in safe mode) ───────────────────────
        if (!__pb_safe) {
          try {
            var previousVisibility = Object.getOwnPropertyDescriptor(document, 'visibilityState');
            var previousHidden = Object.getOwnPropertyDescriptor(document, 'hidden');
            var visible = function () { return 'visible'; };
            var unhidden = function () { return false; };
            Object.defineProperty(document, 'visibilityState', { get: visible, configurable: true });
            Object.defineProperty(document, 'hidden', { get: unhidden, configurable: true });
            listen(window, 'visibilitychange', function (e) { e.stopImmediatePropagation(); }, true);
            cleanups.push(function () {
              if ((Object.getOwnPropertyDescriptor(document, 'visibilityState') || {}).get === visible) {
                if (previousVisibility) Object.defineProperty(document, 'visibilityState', previousVisibility);
                else delete document.visibilityState;
              }
              if ((Object.getOwnPropertyDescriptor(document, 'hidden') || {}).get === unhidden) {
                if (previousHidden) Object.defineProperty(document, 'hidden', previousHidden);
                else delete document.hidden;
              }
            });
          } catch (e) {}
        }

        // The main-frame website casting API is installed separately by PageCastScript.
        return function () {
          active = false;
          readers.forEach(cancelReader);
          readers.clear();
          cleanups.forEach(function (cleanup) { try { cleanup(); } catch (_) {} });
        };
      }
    })();
    """#
}
