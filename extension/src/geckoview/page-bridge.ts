import { PAGE_CHANNEL_PRELUDE } from "./page-channel";
import { LINKED_PAGE_EVENTS } from "./page-relay";

/**
 * Page-world API. Media and document authority stay in the isolated/native transports.
 * Requests, responses and session events use the private channel from page-channel.ts.
 * Primordials are captured at document start so later page scripts cannot observe or
 * resolve another caller's request by patching Map, Promise.prototype.then or
 * EventTarget.prototype.dispatchEvent.
 */
export const PAGE_PLAYBACK_BRIDGE_SCRIPT = `
    (function() {
      if (window.playbridge_injected_version === 6) return;
      ${PAGE_CHANNEL_PRELUDE}
      window.playbridge_injected = true;
      window.playbridge_injected_version = 6;
      var PromiseCtor = Promise;
      var ErrorCtor = Error;
      var CustomEventCtor = CustomEvent;
      var EventTargetCtor = EventTarget;
      var dispatch = Function.prototype.call.bind(EventTarget.prototype.dispatchEvent);
      var setTimer = setTimeout;
      var clearTimer = clearTimeout;
      var randomId = crypto.randomUUID ? crypto.randomUUID.bind(crypto) : null;
      var sessionEvents = ${JSON.stringify(LINKED_PAGE_EVENTS)};
      var pending = Object.create(null);
      var pendingCount = 0;
      var sessions = Object.create(null);
      var sequence = 0;
      function fail(reject, code, message) {
        var error = new ErrorCtor(message);
        error.code = code;
        reject(error);
      }
      function request(operation, sessionId, payload, onResponse) {
        var pageRequestId = randomId ? randomId() : 'request-' + (++sequence);
        return new PromiseCtor(function(resolve, reject) {
          if (pendingCount >= 32) {
            fail(reject, 'resource_limit', 'Too many pending linked cast requests');
            return;
          }
          var timeout = setTimer(function() {
            if (!pending[pageRequestId]) return;
            delete pending[pageRequestId];
            pendingCount--;
            fail(reject, 'timeout', 'Linked cast request timed out');
          }, (operation === 'open' || operation === 'play') ? 660000 : 45000);
          pending[pageRequestId] = { resolve: resolve, reject: reject, timeout: timeout, onResponse: onResponse };
          pendingCount++;
          var sent = channelSend({
            channel: 'linked', pageRequestId: pageRequestId, operation: operation,
            sessionId: sessionId || null, payload: payload || {}
          });
          if (!sent) settle(pageRequestId, { ok: false, error: 'invalid_request', message: 'Linked cast request could not be sent' });
        });
      }
      function settle(pageRequestId, response) {
        var waiter = pending[pageRequestId];
        if (!waiter) return;
        delete pending[pageRequestId];
        pendingCount--;
        clearTimer(waiter.timeout);
        response = response && typeof response === 'object' ? response : {};
        if (!response.ok) {
          fail(waiter.reject, response.error || 'linked_cast_failed', response.message || response.error || 'Linked cast failed');
          return;
        }
        if (!waiter.onResponse) { waiter.resolve(response); return; }
        try { waiter.resolve(waiter.onResponse(response)); }
        catch (error) { waiter.reject(error); }
      }
      function sessionEvent(message) {
        var session = sessions[message.sessionId];
        if (!session || sessionEvents.indexOf(message.event) === -1) return;
        var detail = message.detail && typeof message.detail === 'object' ? message.detail : {};
        if (message.event === 'ended') delete sessions[message.sessionId];
        dispatch(session, new CustomEventCtor(message.event, { detail: detail }));
      }
      channelReceive(function(message) {
        if (message.channel !== 'linked') return;
        if (message.type === 'response' && typeof message.pageRequestId === 'string') settle(message.pageRequestId, message.response);
        else if (message.type === 'event' && typeof message.sessionId === 'string') sessionEvent(message);
      });
      function LinkedCastSession(sessionId) {
        var target = new EventTargetCtor();
        target.sessionId = sessionId;
        target.replace = function(items, startIndex, metadata) {
          return request('replace', sessionId, { items: items, startIndex: startIndex || 0, metadata: metadata });
        };
        target.append = function(items, options) {
          return request('append', sessionId, {
            items: items,
            privateNetworkOrigins: (options && options.privateNetworkOrigins) || []
          });
        };
        target.jump = function(index) { return request('jump', sessionId, { index: index }); };
        target.provideItems = function(requestId, result) {
          return request('supply', sessionId, {
            requestId: requestId,
            items: (result && result.items) || [],
            endOfList: !!(result && result.endOfList),
            privateNetworkOrigins: (result && result.privateNetworkOrigins) || []
          });
        };
        target.unlink = function() { return request('unlink', sessionId, {}); };
        return target;
      }
      function openSession(response) {
        if (typeof response.sessionId !== 'string') throw new ErrorCtor('Linked cast failed');
        var session = LinkedCastSession(response.sessionId);
        sessions[response.sessionId] = session;
        // Native waits for this ping, so listeners attached after resolution see the first events.
        request('ping', response.sessionId, { ready: true }, function() {}).then(null, function() {});
        return session;
      }
      window.playbridge = window.playbridge || {};
      window.playbridge.cast = function(payload) {
        channelSend({ channel: 'cast', payload: payload });
      };
      window.playbridge.capabilities = Object.assign({}, window.playbridge.capabilities, {
        linkedCast: 1,
        explicitHeaders: 1,
        privateNetworkOriginPermission: 1,
        playback: 1,
        localPlaybackOrientation: 1
      });
      window.playbridge.getPlaybackDestination = function() { return request('destination', null, {}); };
      window.playbridge.choosePlaybackDestination = function(options) { return request('choose_destination', null, options || {}); };
      window.playbridge.play = function(payload) { return request('play', null, payload, openSession); };
      window.playbridge.linkCast = function(payload) { return request('open', null, payload, openSession); };
    })();
  `;
