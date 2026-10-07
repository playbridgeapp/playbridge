import Foundation
import WebKit

/// Website casting API. The page-world shim never reaches native directly: the
/// `playbridgePageCast` handler exists only in [world], where a broker owns the
/// per-document token and attests user activation. Shim and broker share a private
/// MessageChannel handed over at document start, before any page script runs. Native
/// still validates every request against the originating main frame and document.
enum PageCastScript {
    static let world = WKContentWorld.world(name: "PlayBridge.PageCast")
    static let handlerName = "playbridgePageCast"

    private static let bootPlaceholder = "__PLAYBRIDGE_PAGE_CAST_BOOT__"

    /// Both scripts are injected with the same random boot name per tab, so neither
    /// injection order nor a page listener can intercept the port handover.
    static func scripts(bootName: String = "playbridge-page-cast-" + UUID().uuidString) -> (page: String, broker: String) {
        let quoted = "'" + bootName.filter { $0.isLetter || $0.isNumber || $0 == "-" } + "'"
        return (pageSource.replacingOccurrences(of: bootPlaceholder, with: quoted),
                brokerSource.replacingOccurrences(of: bootPlaceholder, with: quoted))
    }

    /// Isolated-world broker. Page scripts cannot read its token, call its handler or
    /// replace its delivery function.
    static let brokerSource = #"""
    (function () {
      if (window.top !== window || window.__playbridgePageCastBroker) return;
      window.__playbridgePageCastBroker = true;
      var boot = __PLAYBRIDGE_PAGE_CAST_BOOT__;
      var token = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID() : Date.now() + '-' + Math.random() + '-' + Math.random();
      var channel = new MessageChannel();
      var port = channel.port1;
      var offered = false;
      function offer() {
        if (offered) return;
        offered = true;
        window.removeEventListener(boot + '-ready', offer, true);
        window.dispatchEvent(new MessageEvent(boot + '-port', {ports: [channel.port2]}));
      }
      function reply(message) { try { port.postMessage(JSON.stringify(message)); } catch (_) {} }
      port.onmessage = function (event) {
        var message = event.data;
        if (!message || typeof message !== 'object' || typeof message.requestId !== 'string') return;
        var activation = (navigator.userActivation && typeof navigator.userActivation.isActive === 'boolean')
          ? navigator.userActivation.isActive : null;
        try {
          window.webkit.messageHandlers.playbridgePageCast.postMessage({
            type: 'pageCastRequest', documentToken: token, requestId: message.requestId,
            operation: message.operation, sessionId: message.sessionId == null ? null : message.sessionId,
            payload: message.payload, userActivation: activation
          });
        } catch (_) {
          if (message.operation !== 'cancel') {
            reply({requestId: message.requestId, ok: false, error: 'native_unavailable'});
          }
        }
      };
      window.__playbridgePageCastDeliver = function (message) {
        if (!message || typeof message !== 'object' || message.documentToken !== token) return;
        var copy = {};
        Object.keys(message).forEach(function (key) { if (key !== 'documentToken') copy[key] = message[key]; });
        reply(copy);
      };
      window.addEventListener(boot + '-ready', offer, true);
      window.dispatchEvent(new Event(boot + '-broker'));
    })();
    """#

    /// Page-world API. Everything it needs is captured before page scripts run; state
    /// lives in this closure, so later scripts cannot observe or settle other callers'
    /// requests by patching Map, Promise.prototype.then or dispatchEvent.
    static let pageSource = #"""
    (function () {
      if (window.top !== window || window.__playbridgePageCastInstalled) return;
      window.__playbridgePageCastInstalled = true;
      window.playbridge_injected = true;
      window.playbridge_injected_version = 5;

      var boot = __PLAYBRIDGE_PAGE_CAST_BOOT__;
      var PromiseC = Promise, ErrorC = Error, CustomEventC = CustomEvent, EventTargetC = EventTarget;
      var call = Function.prototype.call;
      var dispatch = call.bind(EventTarget.prototype.dispatchEvent);
      var send = call.bind(MessagePort.prototype.postMessage);
      var parse = JSON.parse, stringify = JSON.stringify;
      var keys = Object.keys, defineProperty = Object.defineProperty, assign = Object.assign;
      var setTimer = setTimeout, clearTimer = clearTimeout;
      var addListener = window.addEventListener.bind(window);
      var removeListener = window.removeEventListener.bind(window);
      var dispatchWindow = window.dispatchEvent.bind(window);
      var encoder = new TextEncoder();
      var encode = call.bind(TextEncoder.prototype.encode);
      var allowedEvents = {needitems: true, statechange: true, ended: true};

      var port = null;
      var pending = Object.create(null), pendingCount = 0;
      var sessions = Object.create(null), sessionCount = 0;
      var inactive = false;
      var sequence = 0;

      function accept(event) {
        if (port) return;
        var candidate = event.ports && event.ports[0];
        if (!candidate) return;
        event.stopImmediatePropagation();
        port = candidate;
        removeListener(boot + '-port', accept, true);
        removeListener(boot + '-broker', announce, true);
        port.onmessage = receive;
      }
      function announce() { if (!port) dispatchWindow(new Event(boot + '-ready')); }
      addListener(boot + '-port', accept, true);
      addListener(boot + '-broker', announce, true);
      announce();

      function failure(code, message) {
        var error = new ErrorC(message || code);
        error.code = code;
        return error;
      }
      function post(operation, sessionId, payload, requestId) {
        if (!port) throw failure('native_unavailable', 'PlayBridge is unavailable');
        send(port, {requestId: requestId || 'request-' + (++sequence), operation: operation,
          sessionId: sessionId || null, payload: payload});
      }
      function bestEffort(operation, sessionId, payload) {
        try { post(operation, sessionId, payload); } catch (_) {}
      }
      function settle(requestId) {
        var waiter = pending[requestId];
        if (!waiter) return null;
        delete pending[requestId];
        pendingCount--;
        clearTimer(waiter.timeout);
        return waiter;
      }
      // done(error, response) runs inside this closure; promises are only handed to callers.
      function call_(operation, sessionId, payload, done) {
        if (inactive) { done(failure('page_unavailable', 'This page is no longer active')); return; }
        if (sessionId && !sessions[sessionId]) {
          done(failure('session_ended', 'This linked cast has ended')); return;
        }
        if (pendingCount >= 32) {
          done(failure('resource_limit', 'Too many pending cast requests')); return;
        }
        var value;
        try {
          var json = stringify(payload === undefined ? {} : payload);
          if (typeof json !== 'string') throw failure('invalid_payload', 'Cast payload must be JSON');
          if (encode(encoder, json).length > 65536) {
            throw failure('resource_limit', 'Cast payload exceeds 64 KiB');
          }
          value = parse(json);
        } catch (error) {
          done(error && error.code ? error : failure('invalid_payload', 'Cast payload must be JSON')); return;
        }
        var requestId = 'request-' + (++sequence);
        var timeout = setTimer(function () {
          if (!settle(requestId)) return;
          bestEffort('cancel', sessionId, {requestId: requestId, reason: 'timeout'});
          done(failure('timeout', 'Cast request timed out'));
        }, operation === 'open' || operation === 'play' || operation === 'cast' ? 600000 : 30000);
        pending[requestId] = {operation: operation, sessionId: sessionId, done: done, timeout: timeout};
        pendingCount++;
        try { post(operation, sessionId, value, requestId); }
        catch (_) {
          settle(requestId);
          done(failure('native_unavailable', 'PlayBridge is unavailable'));
        }
      }
      function request(operation, sessionId, payload) {
        return new PromiseC(function (resolve, reject) {
          call_(operation, sessionId, payload, function (error, response) {
            if (error) reject(error); else resolve(response);
          });
        });
      }
      function endSession(sessionId, detail) {
        var session = sessions[sessionId];
        if (!session) return;
        delete sessions[sessionId];
        sessionCount--;
        clearTimer(session.readyTimer);
        clearTimer(session.heartbeat);
        keys(pending).forEach(function (requestId) {
          var waiter = pending[requestId];
          if (waiter.sessionId !== sessionId) return;
          settle(requestId);
          if (waiter.operation === 'unlink') waiter.done(null, {ok: true, sessionId: sessionId});
          else waiter.done(failure('session_ended', 'This linked cast has ended'));
        });
        session.events.length = 0;
        dispatch(session.target, new CustomEventC('ended', {detail: detail || {reason: 'unlinked'}}));
      }
      function heartbeat(sessionId, ready) {
        call_('ping', sessionId, ready ? {ready: true} : {}, function (error) {
          if (error) {
            if (!sessions[sessionId]) return;
            bestEffort('unlink', sessionId, {});
            endSession(sessionId, {reason: error.code || 'connection_lost'});
            return;
          }
          var session = sessions[sessionId];
          if (!session) return;
          session.heartbeat = setTimer(function () { heartbeat(sessionId, false); }, 20000);
        });
      }
      function makeSession(sessionId) {
        var target = new EventTargetC();
        defineProperty(target, 'sessionId', {value: sessionId, enumerable: true});
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
          return new PromiseC(function (resolve, reject) {
            call_('unlink', sessionId, {}, function (error, response) {
              if (error) { reject(error); return; }
              endSession(sessionId, {reason: 'unlinked'});
              resolve(response);
            });
          });
        };
        var session = {target: target, ready: false, events: [], readyTimer: null, heartbeat: null};
        sessions[sessionId] = session;
        sessionCount++;
        // Promise continuations get to attach listeners before initial needitems/statechange.
        session.readyTimer = setTimer(function () {
          if (!sessions[sessionId]) return;
          session.ready = true;
          heartbeat(sessionId, true);
          var queued = session.events.splice(0);
          queued.forEach(function (event) { deliverEvent(event); });
        }, 0);
        return target;
      }
      function deliverEvent(message) {
        var session = sessions[message.sessionId];
        if (!session || allowedEvents[message.event] !== true) return;
        if (!session.ready) {
          if (session.events.length >= 32) session.events.shift();
          session.events.push(message);
          return;
        }
        if (message.event === 'ended') endSession(message.sessionId, message.detail);
        else dispatch(session.target, new CustomEventC(message.event, {detail: message.detail || {}}));
      }
      function receive(event) {
        if (inactive || typeof event.data !== 'string') return;
        var message;
        try { message = parse(event.data); } catch (_) { return; }
        if (!message || typeof message !== 'object') return;
        if (message.event) { if (typeof message.sessionId === 'string') deliverEvent(message); return; }
        if (typeof message.requestId !== 'string') return;
        var waiter = settle(message.requestId);
        if (!waiter) return;
        if (message.ok !== true) {
          waiter.done(failure(message.error || 'cast_failed', message.message || message.error));
        } else if (waiter.operation === 'open' || waiter.operation === 'play') {
          if (typeof message.sessionId !== 'string' || !message.sessionId || message.sessionId.length > 256) {
            waiter.done(failure('invalid_response', 'PlayBridge returned an invalid session'));
          } else if (sessionCount >= 8 || sessions[message.sessionId]) {
            if (!sessions[message.sessionId]) bestEffort('unlink', message.sessionId, {});
            waiter.done(failure('resource_limit', 'Too many linked cast sessions'));
          } else waiter.done(null, makeSession(message.sessionId));
        } else waiter.done(null, message);
      }

      var api = window.playbridge;
      if (!api || (typeof api !== 'object' && typeof api !== 'function')) api = {};
      window.playbridge = api;
      // Android's one-off API is fire-and-forget. Do not create unhandled rejections
      // on existing websites that intentionally do not await cast().
      api.cast = function (payload) { call_('cast', null, payload, function () {}); };
      api.capabilities = assign({}, api.capabilities, {
        linkedCast: 1, playback: 1, localPlaybackOrientation: 1, explicitHeaders: 1, privateNetworkOriginPermission: 1
      });
      api.linkCast = function (payload) { return request('open', null, payload); };
      api.play = function (payload) { return request('play', null, payload); };
      api.getPlaybackDestination = function () { return request('destination', null, {}); };
      // The isolated broker attests user activation and native enforces it; this early
      // rejection only gives sites the same error without a round trip.
      api.choosePlaybackDestination = function (options) {
        if (window.navigator && window.navigator.userActivation && !window.navigator.userActivation.isActive) {
          return PromiseC.reject(failure('user_gesture_required', 'Choose a playback device by tapping its destination button'));
        }
        return request('choose_destination', null, options || {});
      };

      addListener('pagehide', function () {
        inactive = true;
        keys(pending).forEach(function (requestId) {
          var waiter = settle(requestId);
          bestEffort('cancel', waiter.sessionId, {requestId: requestId, reason: 'page_hidden'});
          waiter.done(failure('page_unavailable', 'The page was closed or navigated away'));
        });
        keys(sessions).forEach(function (sessionId) {
          bestEffort('unlink', sessionId, {});
          endSession(sessionId, {reason: 'page_unavailable'});
        });
      });
      // Restoring a back/forward-cache document may create a new session, but the
      // previous one cannot be resurrected. Same-document SPA changes stay linked.
      addListener('pageshow', function (event) { if (event.persisted) inactive = false; });
    })();
    """#
}
