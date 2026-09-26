import Foundation

/// Page-owned website casting API. Native validates every request against the
/// originating main frame and document; this bridge only handles delivery/lifetime.
enum PageCastScript {
    static let source = #"""
    (function () {
      if (window.top !== window || window.__playbridgePageCastInstalled) return;
      window.__playbridgePageCastInstalled = true;
      window.playbridge_injected = true;
      window.playbridge_injected_version = 4;

      var pending = new Map();
      var sessions = new Map();
      var inactive = false;
      var sequence = 0;
      var prefix = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID() : Date.now() + '-' + Math.random();
      var encoder = new TextEncoder();

      function failure(code, message) {
        var error = new Error(message || code);
        error.code = code;
        return error;
      }
      function nextId() { return prefix + '-' + (++sequence); }
      function post(operation, sessionId, payload, requestId) {
        window.webkit.messageHandlers.playbridge.postMessage({
          type: 'pageCastRequest', documentToken: prefix, requestId: requestId || nextId(),
          operation: operation, sessionId: sessionId || null, payload: payload
        });
      }
      function bestEffort(operation, sessionId, payload) {
        try { post(operation, sessionId, payload); } catch (_) {}
      }
      function request(operation, sessionId, payload) {
        return new Promise(function (resolve, reject) {
          if (inactive) { reject(failure('page_unavailable', 'This page is no longer active')); return; }
          if (sessionId && !sessions.has(sessionId)) {
            reject(failure('session_ended', 'This linked cast has ended')); return;
          }
          if (pending.size >= 32) {
            reject(failure('resource_limit', 'Too many pending cast requests')); return;
          }
          var value;
          try {
            var json = JSON.stringify(payload === undefined ? {} : payload);
            if (typeof json !== 'string') throw failure('invalid_payload', 'Cast payload must be JSON');
            if (encoder.encode(json).length > 65536) {
              throw failure('resource_limit', 'Cast payload exceeds 64 KiB');
            }
            value = JSON.parse(json);
          } catch (error) {
            reject(error.code ? error : failure('invalid_payload', 'Cast payload must be JSON')); return;
          }
          var requestId = nextId();
          var timeout = setTimeout(function () {
            pending.delete(requestId);
            bestEffort('cancel', sessionId, {requestId: requestId, reason: 'timeout'});
            reject(failure('timeout', 'Cast request timed out'));
          }, operation === 'open' || operation === 'cast' ? 600000 : 30000);
          pending.set(requestId, {
            operation: operation, sessionId: sessionId, resolve: resolve,
            reject: reject, timeout: timeout
          });
          try { post(operation, sessionId, value, requestId); }
          catch (_) {
            clearTimeout(timeout);
            pending.delete(requestId);
            reject(failure('native_unavailable', 'PlayBridge is unavailable'));
          }
        });
      }
      function endSession(sessionId, detail) {
        var session = sessions.get(sessionId);
        if (!session) return;
        sessions.delete(sessionId);
        clearTimeout(session.readyTimer);
        clearTimeout(session.heartbeat);
        pending.forEach(function (waiter, requestId) {
          if (waiter.sessionId !== sessionId) return;
          clearTimeout(waiter.timeout);
          pending.delete(requestId);
          if (waiter.operation === 'unlink') waiter.resolve({ok: true, sessionId: sessionId});
          else waiter.reject(failure('session_ended', 'This linked cast has ended'));
        });
        session.events.length = 0;
        session.target.dispatchEvent(new CustomEvent('ended', {detail: detail || {reason: 'unlinked'}}));
      }
      function heartbeat(sessionId, ready) {
        request('ping', sessionId, ready ? {ready: true} : {}).then(function () {
          var session = sessions.get(sessionId);
          if (!session) return;
          session.heartbeat = setTimeout(function () { heartbeat(sessionId, false); }, 20000);
        }, function (error) {
          if (!sessions.has(sessionId)) return;
          bestEffort('unlink', sessionId, {});
          endSession(sessionId, {reason: error.code || 'connection_lost'});
        });
      }
      function makeSession(sessionId) {
        var target = new EventTarget();
        Object.defineProperty(target, 'sessionId', {value: sessionId, enumerable: true});
        target.replace = function (items, startIndex, metadata) {
          return request('replace', sessionId, {items: items, startIndex: startIndex || 0, metadata: metadata});
        };
        target.append = function (items, options) {
          return request('append', sessionId, {
            items: items, privateNetworkOrigins: (options && options.privateNetworkOrigins) || []
          });
        };
        target.jump = function (index) { return request('jump', sessionId, {index: index}); };
        target.provideItems = function (requestId, result) {
          return request('supply', sessionId, {
            requestId: requestId, items: (result && result.items) || [],
            endOfList: !!(result && result.endOfList),
            privateNetworkOrigins: (result && result.privateNetworkOrigins) || []
          });
        };
        target.unlink = function () {
          return request('unlink', sessionId, {}).then(function (response) {
            endSession(sessionId, {reason: 'unlinked'});
            return response;
          });
        };
        var session = {target: target, ready: false, events: [], readyTimer: null, heartbeat: null};
        sessions.set(sessionId, session);
        // Promise continuations get to attach listeners before initial needitems/statechange.
        session.readyTimer = setTimeout(function () {
          if (!sessions.has(sessionId)) return;
          session.ready = true;
          heartbeat(sessionId, true);
          var queued = session.events.splice(0);
          queued.forEach(function (event) { deliverEvent(event); });
        }, 0);
        return target;
      }
      function deliverEvent(message) {
        var session = sessions.get(message.sessionId);
        if (!session || ['needitems', 'statechange', 'ended'].indexOf(message.event) === -1) return;
        if (!session.ready) {
          if (session.events.length >= 32) session.events.shift();
          session.events.push(message);
          return;
        }
        if (message.event === 'ended') endSession(message.sessionId, message.detail);
        else session.target.dispatchEvent(new CustomEvent(message.event, {detail: message.detail || {}}));
      }
      window.__playbridgePageCastReceive = function (message) {
        if (inactive) return;
        if (typeof message === 'string') {
          try { message = JSON.parse(message); } catch (_) { return; }
        }
        if (!message || typeof message !== 'object' || message.documentToken !== prefix) return;
        if (message.event) { deliverEvent(message); return; }
        var waiter = pending.get(message.requestId);
        if (!waiter) return;
        pending.delete(message.requestId);
        clearTimeout(waiter.timeout);
        if (message.ok !== true) {
          waiter.reject(failure(message.error || 'cast_failed', message.message || message.error));
        } else if (waiter.operation === 'open') {
          if (typeof message.sessionId !== 'string' || !message.sessionId || message.sessionId.length > 256) {
            waiter.reject(failure('invalid_response', 'PlayBridge returned an invalid session'));
          } else if (sessions.size >= 8 || sessions.has(message.sessionId)) {
            if (!sessions.has(message.sessionId)) bestEffort('unlink', message.sessionId, {});
            waiter.reject(failure('resource_limit', 'Too many linked cast sessions'));
          } else waiter.resolve(makeSession(message.sessionId));
        } else waiter.resolve(message);
      };

      var api = window.playbridge;
      if (!api || (typeof api !== 'object' && typeof api !== 'function')) api = {};
      window.playbridge = api;
      // Android's one-off API is fire-and-forget. Do not create unhandled rejections
      // on existing websites that intentionally do not await cast().
      api.cast = function (payload) { request('cast', null, payload).catch(function () {}); };
      api.capabilities = Object.assign({}, api.capabilities, {
        linkedCast: 1, explicitHeaders: 1, privateNetworkOriginPermission: 1
      });
      api.linkCast = function (payload) { return request('open', null, payload); };

      window.addEventListener('pagehide', function () {
        inactive = true;
        pending.forEach(function (waiter, requestId) {
          clearTimeout(waiter.timeout);
          bestEffort('cancel', waiter.sessionId, {requestId: requestId, reason: 'page_hidden'});
          waiter.reject(failure('page_unavailable', 'The page was closed or navigated away'));
        });
        pending.clear();
        Array.from(sessions.keys()).forEach(function (sessionId) {
          bestEffort('unlink', sessionId, {});
          endSession(sessionId, {reason: 'page_unavailable'});
        });
      });
      // Restoring a back/forward-cache document may create a new session, but the
      // previous one cannot be resurrected. Same-document SPA changes stay linked.
      window.addEventListener('pageshow', function (event) { if (event.persisted) inactive = false; });
    })();
    """#
}
