import { PAGE_CHANNEL_PRELUDE, PAGE_CHANNEL_BOOT } from "./page-channel";
import { LINKED_PAGE_EVENTS } from "./page-relay";

export interface PagePlaybackBridgeOptions {
  target?: "geckoview" | "desktop";
  bootEvent?: string;
}

export function initDesktopPageBridge(bootEventName: string): void {
  const win = window as any;
  if (win.playbridge_injected_version === 6) return;
  win.playbridge_injected = true;
  win.playbridge_injected_version = 6;
  var channel = new MessageChannel();
  var channelPost = MessagePort.prototype.postMessage.bind(channel.port1);
  var parseJson = JSON.parse;
  var channelHandler: ((msg: any) => void) | null = null;
  channel.port1.onmessage = function (event: any) {
    if (typeof event.data !== "string" || !channelHandler) return;
    var message: any;
    try {
      message = parseJson(event.data);
    } catch (_) {
      return;
    }
    if (message && typeof message === "object") channelHandler(message);
  };
  window.dispatchEvent(
    new MessageEvent(bootEventName, { ports: [channel.port2] }),
  );
  function channelSend(message: any) {
    try {
      channelPost(message);
      return true;
    } catch (_) {
      return false;
    }
  }
  function channelReceive(handler: any) {
    channelHandler = handler;
  }
  var PromiseCtor = Promise;
  var ErrorCtor = Error;
  var CustomEventCtor = CustomEvent;
  var EventTargetCtor = EventTarget;
  var dispatch = Function.prototype.call.bind(EventTarget.prototype.dispatchEvent);
  var setTimer = setTimeout;
  var clearTimer = clearTimeout;
  var randomId = crypto.randomUUID ? crypto.randomUUID.bind(crypto) : null;
  var sessionEvents = ["needitems", "statechange", "ended"];
  var pending: Record<string, any> = Object.create(null);
  var pendingCount = 0;
  var sessions: Record<string, any> = Object.create(null);
  var sequence = 0;
  function fail(reject: any, code: string, message: string) {
    var error = new ErrorCtor(message) as any;
    error.code = code;
    reject(error);
  }
  function request(operation: string, sessionId: string | null, payload: any, onResponse?: any) {
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
  function settle(pageRequestId: string, response: any) {
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
  function sessionEvent(message: any) {
    var session = sessions[message.sessionId];
    if (!session || sessionEvents.indexOf(message.event) === -1) return;
    var detail = message.detail && typeof message.detail === 'object' ? message.detail : {};
    if (message.event === 'ended') delete sessions[message.sessionId];
    dispatch(session, new CustomEventCtor(message.event, { detail: detail }));
  }
  channelReceive(function(message: any) {
    if (message.channel !== 'linked') return;
    if (message.type === 'response' && typeof message.pageRequestId === 'string') settle(message.pageRequestId, message.response);
    else if (message.type === 'event' && typeof message.sessionId === 'string') sessionEvent(message);
    else if (message.type === 'capabilities' && message.capabilities && typeof message.capabilities === 'object') {
      var caps = message.capabilities;
      if (caps.playback === 0 || caps.playback === 1) {
        win.playbridge.capabilities.playback = caps.playback;
      }
      if (typeof caps.linkedCast === 'number') {
        win.playbridge.capabilities.linkedCast = caps.linkedCast;
      }
    }
  });
  function LinkedCastSession(sessionId: string) {
    var target = new EventTargetCtor() as any;
    target.sessionId = sessionId;
    target.replace = function(items: any, startIndex: any, metadata: any) {
      return request('replace', sessionId, { items: items, startIndex: startIndex || 0, metadata: metadata });
    };
    target.append = function(items: any, options: any) {
      return request('append', sessionId, {
        items: items,
        privateNetworkOrigins: (options && options.privateNetworkOrigins) || []
      });
    };
    target.jump = function(index: number) { return request('jump', sessionId, { index: index }); };
    target.provideItems = function(requestId: string, result: any) {
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
  function openSession(response: any) {
    if (typeof response.sessionId !== 'string') throw new ErrorCtor('Linked cast failed');
    var session = LinkedCastSession(response.sessionId);
    sessions[response.sessionId] = session;
    request('ping', response.sessionId, { ready: true }, function() {}).then(null, function() {});
    return session;
  }
  win.playbridge = win.playbridge || {};
  win.playbridge.cast = function(payload: any) {
    channelSend({ channel: 'cast', payload: payload });
  };
  win.playbridge.capabilities = Object.assign({}, win.playbridge.capabilities, {
    linkedCast: 1,
    explicitHeaders: 1,
    privateNetworkOriginPermission: 1,
    playback: 0,
    nativePlugins: 0
  });
  win.playbridge.getPlaybackDestination = function() { return request('destination', null, {}); };
  win.playbridge.choosePlaybackDestination = function(options: any) { return request('choose_destination', null, options || {}); };
  win.playbridge.play = function(payload: any) { return request('play', null, payload, openSession); };
  win.playbridge.linkCast = function(payload: any) { return request('open', null, payload, openSession); };
}

export function createPagePlaybackBridgeScript(options: PagePlaybackBridgeOptions = {}): string {
  const target = options.target ?? "geckoview";
  const isDesktop = target === "desktop";
  if (isDesktop) {
    const bootEvent = options.bootEvent ?? PAGE_CHANNEL_BOOT;
    return `(${initDesktopPageBridge.toString()})(${bootEvent});`;
  }
  return `
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
        else if (message.type === 'capabilities' && message.capabilities && typeof message.capabilities === 'object') {
          Object.assign(window.playbridge.capabilities, message.capabilities);
        }
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
}

export const PAGE_PLAYBACK_BRIDGE_SCRIPT = createPagePlaybackBridgeScript({ target: "geckoview" });
export const DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT = createPagePlaybackBridgeScript({ target: "desktop" });
