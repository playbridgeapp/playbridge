import Foundation

/// JavaScript injected into every page/frame at document-start. It replaces the GeckoView
/// WebExtension (`assets/extensions/video_detector/`): on WKWebView there's no `webRequest`, so we
/// monkey-patch `fetch`/`XMLHttpRequest` (the network sniffer), scan the DOM for `<video>/<source>`
/// (port of `content.js`), keep media playing when backgrounded (visibility shim), and expose
/// `window.playbridge.cast()`. Everything reports to native via
/// `window.webkit.messageHandlers.playbridge.postMessage(...)`.
enum DetectionScript {
    /// The single user script source. Runs in the page world, all frames.
    static let source = #"""
    (function () {
      if (window.__playbridge_detector) return;
      window.__playbridge_detector = true;

      // Players that break when their visibility state is faked (YouTube) run in
      // "safe mode": detection (fetch/XHR/DOM) still runs, but the visibility shim is
      // skipped so the player doesn't error out.
      var __pb_host = (location.hostname || '').toLowerCase();
      var __pb_safe = /(^|\.)(youtube\.com|youtube-nocookie\.com|googlevideo\.com|youtu\.be)$/.test(__pb_host);

      var SEGMENT = /\.(ts|m4s|fmp4|cmfv|cmfa)(\?|$)/i;
      var MEDIA = /\.(m3u8|mpd|mp4|m4v|mov|mkv|webm|avi|flv|wmv|3gp|vtt|srt)(\?|$)/i;
      var MEDIA_CT = /(video\/|mpegurl|application\/dash|application\/octet-stream|text\/vtt|application\/x-subrip)/i;

      function post(msg) {
        try { window.webkit.messageHandlers.playbridge.postMessage(msg); } catch (e) {}
      }

      function report(url, contentType, detectedBy) {
        if (!url || typeof url !== 'string') return;
        if (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) return;
        if (url.indexOf('http') !== 0) {
          // Resolve protocol-relative / relative URLs against the document.
          try { url = new URL(url, location.href).href; } catch (e) { return; }
        }
        var path = url.split('?')[0];
        if (SEGMENT.test(path)) return; // HLS/DASH media segments aren't standalone streams
        post({
          type: 'video',
          url: url,
          contentType: contentType || '',
          detectedBy: detectedBy || 'unknown',
          originUrl: location.href,
          ua: navigator.userAgent
        });
      }

      function looksMedia(url) {
        if (!url) return false;
        var path = ('' + url).split('?')[0];
        return MEDIA.test(path) && !SEGMENT.test(path);
      }

      // ── fetch hook ──────────────────────────────────────────────────────────
      var origFetch = window.fetch;
      if (origFetch) {
        window.fetch = function (input, init) {
          var url = (typeof input === 'string') ? input : (input && input.url);
          if (looksMedia(url)) report(url, '', 'fetch_url');
          var p = origFetch.apply(this, arguments);
          try {
            return p.then(function (resp) {
              try {
                var ct = resp.headers && resp.headers.get('content-type');
                if (ct && MEDIA_CT.test(ct)) report(resp.url || url, ct, 'fetch_content_type');
              } catch (e) {}
              return resp;
            });
          } catch (e) { return p; }
        };
      }

      // ── XHR hook ────────────────────────────────────────────────────────────
      var origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (method, url) {
        this.__pb_url = url;
        if (looksMedia(url)) report(url, '', 'xhr_url');
        return origOpen.apply(this, arguments);
      };
      var origSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.send = function () {
        var xhr = this;
        xhr.addEventListener('readystatechange', function () {
          if (xhr.readyState === 2) {
            try {
              var ct = xhr.getResponseHeader('content-type');
              if (ct && MEDIA_CT.test(ct)) report(xhr.responseURL || xhr.__pb_url, ct, 'xhr_content_type');
            } catch (e) {}
          }
        });
        return origSend.apply(this, arguments);
      };

      // ── DOM scan (port of content.js) ─────────────────────────────────────────
      function scanEl(el) {
        if (!el || !el.tagName) return;
        if (el.tagName === 'VIDEO' || el.tagName === 'SOURCE') {
          if (el.src && el.src.indexOf('http') === 0) report(el.src, '', 'dom_video_element');
        }
      }
      function scanAll() { document.querySelectorAll('video, source').forEach(scanEl); }
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', scanAll);
      } else { scanAll(); }
      try {
        new MutationObserver(function (muts) {
          for (var i = 0; i < muts.length; i++) {
            var m = muts[i];
            for (var j = 0; j < m.addedNodes.length; j++) {
              var n = m.addedNodes[j];
              if (n.nodeType !== 1) continue;
              scanEl(n);
              if (n.querySelectorAll) n.querySelectorAll('video, source').forEach(scanEl);
            }
            if (m.type === 'attributes' && m.target && m.target.nodeType === 1) scanEl(m.target);
          }
        }).observe(document.documentElement, {
          childList: true, subtree: true, attributes: true, attributeFilter: ['src']
        });
      } catch (e) {}

      // ── Background-playback shim (skipped in safe mode) ───────────────────────
      if (!__pb_safe) {
        try {
          Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
          Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
          window.addEventListener('visibilitychange', function (e) { e.stopImmediatePropagation(); }, true);
        } catch (e) {}
      }

      // ── window.playbridge.cast() bridge ───────────────────────────────────────
      window.playbridge = {
        cast: function (payload) { post({ type: 'cast', payload: payload }); }
      };
    })();
    """#
}
