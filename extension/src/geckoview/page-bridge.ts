/** Page-world API. Media and document authority stay in the isolated/native transports. */
export const PAGE_PLAYBACK_BRIDGE_SCRIPT = `
    (function() {
      if (window.playbridge_injected_version === 5) return;
      window.playbridge_injected = true;
      window.playbridge_injected_version = 5;
      var pending = new Map();
      var sessions = new Map();
      function request(operation, sessionId, payload) {
        var pageRequestId = (crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random());
        return new Promise(function(resolve, reject) {
          if (pending.size >= 32) {
            var limitError = new Error('Too many pending linked cast requests');
            limitError.code = 'resource_limit';
            reject(limitError);
            return;
          }
          var timeout = setTimeout(function() {
            pending.delete(pageRequestId);
            var timeoutError = new Error('Linked cast request timed out');
            timeoutError.code = 'timeout';
            reject(timeoutError);
          }, (operation === 'open' || operation === 'play') ? 660000 : 45000);
          pending.set(pageRequestId, { resolve: resolve, reject: reject, timeout: timeout });
          window.dispatchEvent(new CustomEvent('PlayBridgeLinkedRequest', {
            detail: { pageRequestId: pageRequestId, operation: operation, sessionId: sessionId || null, payload: payload || {} }
          }));
        });
      }
      window.addEventListener('PlayBridgeLinkedResponseJson', function(event) {
        var detail;
        try { detail = JSON.parse(event.detail || '{}'); }
        catch (_) { return; }
        var waiter = pending.get(detail.pageRequestId);
        if (!waiter) return;
        pending.delete(detail.pageRequestId);
        clearTimeout(waiter.timeout);
        var response = detail.response || {};
        if (response.ok) waiter.resolve(response);
        else {
          var error = new Error(response.message || response.error || 'Linked cast failed');
          error.code = response.error || 'linked_cast_failed';
          waiter.reject(error);
        }
      });
      window.addEventListener('PlayBridgeLinkedEventJson', function(event) {
        var detail;
        try { detail = JSON.parse(event.detail || '{}'); }
        catch (_) { return; }
        var session = sessions.get(detail.sessionId);
        if (!session) return;
        session.dispatchEvent(new CustomEvent(detail.event || 'statechange', { detail: detail.detail || {} }));
        if (detail.event === 'ended') sessions.delete(detail.sessionId);
      });
      function LinkedCastSession(sessionId) {
        var target = new EventTarget();
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
      window.playbridge = window.playbridge || {};
      window.playbridge.cast = function(payload) {
        window.dispatchEvent(new CustomEvent('PlayBridgeCast', { detail: payload }));
      };
      window.playbridge.capabilities = Object.assign({}, window.playbridge.capabilities, {
        linkedCast: 1,
        explicitHeaders: 1,
        privateNetworkOriginPermission: 1,
        playback: 1
      });
      window.playbridge.getPlaybackDestination = function() { return request('destination', null, {}); };
      window.playbridge.choosePlaybackDestination = function(options) { return request('choose_destination', null, options || {}); };
      window.playbridge.play = function(payload) {
        return request('play', null, payload).then(function(response) {
          var session = LinkedCastSession(response.sessionId);
          sessions.set(response.sessionId, session);
          void request('ping', response.sessionId, { ready: true }).catch(function() {});
          return session;
        });
      };
      window.playbridge.linkCast = function(payload) {
        return request('open', null, payload).then(function(response) {
          var session = LinkedCastSession(response.sessionId);
          sessions.set(response.sessionId, session);
          void request('ping', response.sessionId, { ready: true }).catch(function() {});
          return session;
        });
      };
    })();
  `;
