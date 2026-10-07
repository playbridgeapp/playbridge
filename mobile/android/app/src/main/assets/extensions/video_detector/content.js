"use strict";(()=>{var j="__PLAYBRIDGE_PAGE_CHANNEL_BOOT__",b=`
  var channel = new MessageChannel();
  var channelPost = MessagePort.prototype.postMessage.bind(channel.port1);
  var parseJson = JSON.parse;
  var channelHandler = null;
  channel.port1.onmessage = function(event) {
    if (typeof event.data !== 'string' || !channelHandler) return;
    var message;
    try { message = parseJson(event.data); } catch (_) { return; }
    if (message && typeof message === 'object') channelHandler(message);
  };
  window.dispatchEvent(new MessageEvent(${j}, { ports: [channel.port2] }));
  function channelSend(message) {
    try { channelPost(message); return true; } catch (_) { return false; }
  }
  function channelReceive(handler) { channelHandler = handler; }
`;function G(){return`playbridge-boot-${globalThis.crypto?.randomUUID?.()??`${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`}`}function w(e){let t=G(),n=null,o=u=>{if(u.stopImmediatePropagation(),n)return;let l=u.ports?.[0];l&&(n=l)};window.addEventListener(t,o,!0);try{let u=document.createElement("script");u.textContent=e.split(j).join(JSON.stringify(t)),(document.documentElement||document.head||document.body).appendChild(u),u.remove()}finally{window.removeEventListener(t,o,!0)}let i=n;return i?{onMessage(u){i.onmessage=l=>u(l.data)},post(u){try{i.postMessage(JSON.stringify(u))}catch{}}}:null}var W=new Set(["open","play","replace","append","jump","supply","unlink","ping","destination","choose_destination"]),$=new Set(["channel","pageRequestId","operation","sessionId","payload"]),h=["needitems","statechange","ended"],R="playbridge-page-api";function m(e){return typeof e=="string"&&e.length>0&&e.length<=128&&!/[\x00-\x1f\x7f]/.test(e)}function A(e){try{if(!e||typeof e!="object"||Array.isArray(e))return null;let t=e;if(Object.keys(t).some(s=>!$.has(s)))return null;let{channel:n,pageRequestId:o,operation:i,sessionId:u,payload:l}=t;return n!==void 0&&n!=="linked"||!m(o)||typeof i!="string"||!W.has(i)||u!=null&&!m(u)||!l||typeof l!="object"||Array.isArray(l)?null:{type:"linked",pageRequestId:o,operation:i,sessionId:u??null,payload:l}}catch{return null}}var C=`
    (function() {
      if (window.playbridge_injected_version === 6) return;
      ${b}
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
      var sessionEvents = ${JSON.stringify(h)};
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
  `;var J=globalThis.browser??globalThis.chrome,p=J;function P(e){return{videos:e?.videos!==!1,images:e?.images!==!1,audio:e?.audio!==!1,subtitles:e?.subtitles!==!1,domScanning:e?.domScanning!==!1,networkDetection:e?.networkDetection!==!1,responseScanning:e?.responseScanning!==!1,navigationRescans:e?.navigationRescans!==!1,playerProbes:e?.playerProbes!==!1,visibilityOverrides:e?.visibilityOverrides!==!1,detectInBridgedSites:e?.detectInBridgedSites===!0}}var L="playbridge-detection-policy";function T(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;return t.type==="detection_policy"&&Number.isSafeInteger(t.revision)&&t.revision>=0&&typeof t.enabled=="boolean"&&typeof t.browserEnabled=="boolean"&&Array.isArray(t.bridgedAppOrigins)&&t.bridgedAppOrigins.every(n=>typeof n=="string")&&(t.options==null||typeof t.options=="object"&&!Array.isArray(t.options)&&Object.values(t.options).every(n=>typeof n=="boolean"))}var K=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function D(e){return/^https?:/i.test(e)&&!K.test(e)}var N=16*1024,F=new Set(["status","resolve","manage","cancel"]);function Y(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;if(Object.keys(t).some(i=>!["requestId","operation","payload"].includes(i))||typeof t.operation!="string"||!F.has(t.operation))return!1;if(t.operation==="cancel")return Object.keys(t).length===1;if(typeof t.requestId!="string"||t.requestId.length<1||t.requestId.length>128||/[\x00-\x1f\x7f]/.test(t.requestId)||!t.payload||typeof t.payload!="object"||Array.isArray(t.payload))return!1;let n=t.payload;if(t.operation!=="resolve")return Object.keys(n).length===0;if(Object.keys(n).some(i=>!["repoUrl","scraperIds","tmdbId","mediaType","season","episode"].includes(i))||typeof n.repoUrl!="string"||n.repoUrl.length>2048||typeof n.tmdbId!="string"||!/^[1-9][0-9]{0,9}$/.test(n.tmdbId)||!["movie","tv"].includes(String(n.mediaType)))return!1;let o;try{o=new URL(n.repoUrl)}catch{return!1}return o.protocol!=="https:"||o.username||o.password||o.hash||!Array.isArray(n.scraperIds)||n.scraperIds.length<1||n.scraperIds.length>32||n.scraperIds.some(i=>typeof i!="string"||!i.trim()||i.length>128||/[\x00-\x1f\x7f]/.test(i))||new Set(n.scraperIds).size!==n.scraperIds.length?!1:n.mediaType==="movie"?n.season==null&&n.episode==null:Number.isInteger(n.season)&&Number(n.season)>=0&&Number(n.season)<=1e4&&Number.isInteger(n.episode)&&Number(n.episode)>=1&&Number(n.episode)<=1e4}var V=`
(function() {
  var bridge = window.playbridge = window.playbridge || {};
  if (bridge.plugins) return;
  ${b}
  var pending = Object.create(null);
  var pendingCount = 0;
  var sequence = 0;
  var PromiseCtor = Promise;
  var ErrorCtor = Error;
  var EventCtor = Event;
  var dispatchWindow = window.dispatchEvent.bind(window);
  var stringify = JSON.stringify;
  var encode = TextEncoder.prototype.encode.bind(new TextEncoder());
  var setTimer = setTimeout;
  var clearTimer = clearTimeout;
  var randomId = crypto.randomUUID ? crypto.randomUUID.bind(crypto) : null;
  bridge.capabilities = Object.assign({}, bridge.capabilities, { nativePlugins: 0 });
  function rejectAll(message, onlyResolve) {
    for (var id in pending) {
      var waiter = pending[id];
      if (onlyResolve && waiter.operation !== 'resolve') continue;
      delete pending[id];
      pendingCount--;
      clearTimer(waiter.timer);
      waiter.reject(new ErrorCtor(message));
    }
  }
  function invoke(operation, payload) {
    return new PromiseCtor(function(resolve, reject) {
      if (pendingCount >= 4) { reject(new ErrorCtor('Too many pending device plugin requests')); return; }
      var id = randomId ? randomId() : 'plugin-' + (++sequence);
      var json;
      try { json = stringify({ requestId: id, operation: operation, payload: payload || {} }); }
      catch (_) { reject(new ErrorCtor('Invalid device plugin request')); return; }
      if (encode(json).length > ${N}) { reject(new ErrorCtor('Device plugin request too large')); return; }
      var timer = setTimer(function() {
        if (!pending[id]) return;
        delete pending[id];
        pendingCount--;
        reject(new ErrorCtor('Device plugin request timed out'));
      }, 65000);
      pending[id] = { operation: operation, resolve: resolve, reject: reject, timer: timer };
      pendingCount++;
      if (!channelSend(json)) {
        delete pending[id];
        pendingCount--;
        clearTimer(timer);
        reject(new ErrorCtor('Device plugins unavailable'));
      }
    });
  }
  channelReceive(function(message) {
    if (message.type === 'plugin_capabilities') {
      bridge.capabilities.nativePlugins = message.available === true ? 1 : 0;
      dispatchWindow(new EventCtor('PlayBridgePluginsReady'));
      return;
    }
    if (message.type === 'plugin_disconnected') {
      bridge.capabilities.nativePlugins = 0;
      rejectAll('Device plugins disconnected', false);
      return;
    }
    if (typeof message.requestId !== 'string') return;
    var waiter = pending[message.requestId];
    if (!waiter) return;
    delete pending[message.requestId];
    pendingCount--;
    clearTimer(waiter.timer);
    if (message.ok === true) {
      if (waiter.operation === 'status') bridge.capabilities.nativePlugins = message.data && message.data.available === true ? 1 : 0;
      waiter.resolve(message.data || {});
    } else {
      var error = new ErrorCtor(message.error || 'Device plugin request failed');
      error.code = message.error || 'native_plugins_unavailable';
      waiter.reject(error);
    }
  });
  bridge.plugins = {
    status: function() { return invoke('status', {}); },
    resolve: function(request) { return invoke('resolve', request); },
    manage: function() { return invoke('manage', {}); },
    cancel: function() {
      rejectAll('Device plugin resolution cancelled', true);
      channelSend(stringify({ operation: 'cancel' }));
    }
  };
})();
`;function x(){if(window.top!==window)return;let e,t=!1,n,o=null;function i(s){o?.post(s)}function u(s){if(typeof s!="string"||new TextEncoder().encode(s).length>N)return;let d;try{let a=JSON.parse(s);if(!Y(a)){let c=a?.requestId;typeof c=="string"&&c.length>=1&&c.length<=128&&!/[\x00-\x1f\x7f]/.test(c)&&i({type:"plugin_response",requestId:c,ok:!1,error:"invalid_request"});return}d=a}catch{return}if(d.operation==="manage"&&navigator.userActivation?.isActive!==!0){i({type:"plugin_response",requestId:d.requestId,ok:!1,error:"user_gesture_required"});return}if(!e||t){i({type:"plugin_response",requestId:d.requestId,ok:!1,error:"native_plugins_unavailable"});return}try{e.postMessage(d)}catch{i({type:"plugin_response",requestId:d.requestId,ok:!1,error:"native_plugins_unavailable"})}}function l(){t=!1,n=void 0;try{e=p.runtime.connectNative("plugins"),e.onMessage.addListener(s=>{s?.type==="plugin_capabilities"&&(n=s),i(s)}),e.onDisconnect.addListener(()=>{t=!0,i({type:"plugin_disconnected"})})}catch{t=!0,i({type:"plugin_disconnected"})}}l(),o=w(V),o?.onMessage(u),n&&i(n),window.addEventListener("hashchange",()=>{if(!t)try{e?.postMessage({operation:"cancel"})}catch{}}),window.addEventListener("pagehide",()=>{t=!0;try{e?.disconnect()}catch{}i({type:"plugin_disconnected"})}),window.addEventListener("pageshow",s=>{s.persisted&&t&&l()})}var g=!1,r=P(),_=0,E;p.runtime.onMessage.addListener(e=>(e?.type==="detector_same_document_navigation"&&r.navigationRescans&&U(),!1));function f(e,t,n,o,i){!(e==="dom_image_found"?r.images:e==="dom_audio_found"?r.audio:e==="dom_subtitle_found"?r.subtitles:r.videos)||!g||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&p.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:o,height:i}).catch(()=>{})}function M(e){if(!g||!r.domScanning||!r.images)return;let t=e.currentSrc||e.src;if(!t||!D(t))return;let n=e.naturalWidth,o=e.naturalHeight;n<64||o<64||n*o<16384||f("dom_image_found",t,void 0,n,o)}var k=new WeakSet;function I(e){if(!(!g||!r.domScanning)){if(e instanceof HTMLVideoElement){if(r.videos&&f("dom_video_found",e.currentSrc||e.src),r.images&&e.poster&&f("dom_image_found",e.poster,void 0,e.videoWidth||void 0,e.videoHeight||void 0),r.videos)for(let t of Array.from(e.querySelectorAll("source")))f("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){if(!r.audio)return;f("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))f("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");if(t instanceof HTMLAudioElement?!r.audio:!r.videos)return;f(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}if(typeof HTMLTrackElement<"u"&&e instanceof HTMLTrackElement){(e.kind==="subtitles"||e.kind==="captions")&&f("dom_subtitle_found",e.src);return}e instanceof HTMLImageElement&&r.images&&(M(e),!e.complete&&!k.has(e)&&(k.add(e),e.addEventListener("load",()=>{k.delete(e),M(e)},{once:!0})))}}function q(){return[r.videos||r.images?"video":"",r.audio?"audio":"",r.videos||r.audio?"source":"",r.images?"img":"",r.subtitles?"track":""].filter(Boolean).join(", ")}function U(){if(!g||!r.domScanning)return;let e=q();e&&document.querySelectorAll(e).forEach(I)}function y(e){if(e!==g){if(g=e,!e){E?.disconnect(),E=void 0,document.removeEventListener("DOMContentLoaded",S),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}r.domScanning&&q()&&(E=new MutationObserver(t=>{if(g)for(let n of t){for(let o of n.addedNodes){if(o.nodeType!==1)continue;let i=o;I(i),i.querySelectorAll?.(q()).forEach(I)}n.type==="attributes"&&n.target.nodeType===1&&I(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",S,{once:!0}),B()):S()),Q()}}function B(){!g||!document.documentElement||E?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]})}function S(){B(),U()}var v,H=!1;function X(){if(v)return v;let e=p.runtime.connect({name:L});return v=e,e.onMessage.addListener(t=>{v===e&&t?.update===_&&y(t.ok===!0&&H)}),e.onDisconnect.addListener(()=>{v===e&&(v=void 0,_+=1,y(!1))}),e}try{let e=p.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(!T(t))return;let n=++_,o=P(t.options);if((!t.enabled||JSON.stringify(r)!==JSON.stringify(o))&&y(!1),r=o,H=t.enabled,window.top!==window){y(t.enabled);return}try{X().postMessage({update:n,policy:t})}catch{y(!1)}}),e.onDisconnect.addListener(()=>{_+=1,y(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!g||!r.playerProbes||!r.videos||!t||typeof t!="string"||!t.startsWith("http")||p.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));function z(){if(window.top!==window)return;let e=w(C);if(!e)return;let t=e,n=new Set,o,i=(s,d)=>{n.delete(s),t.post({channel:"linked",type:"response",pageRequestId:s,response:d})};function u(){if(o)return o;let s=p.runtime.connect({name:R});return o=s,s.onMessage.addListener(d=>{if(o!==s||!d||typeof d!="object")return;let a=d;if(a.type==="response"&&m(a.pageRequestId)&&n.has(a.pageRequestId)){let c=a.response&&typeof a.response=="object"?a.response:{};i(a.pageRequestId,c)}else if(a.type==="event"&&m(a.sessionId)&&h.includes(a.event)){let c=a.detail&&typeof a.detail=="object"?a.detail:{};t.post({channel:"linked",type:"event",sessionId:a.sessionId,event:a.event,detail:c})}}),s.onDisconnect.addListener(()=>{if(o===s){o=void 0;for(let d of[...n])i(d,{ok:!1,error:"native_unavailable"})}}),s}function l(s,d){try{u().postMessage(s)}catch{d&&i(d,{ok:!1,error:"native_unavailable"})}}t.onMessage(s=>{if(window.top!==window||!s||typeof s!="object")return;let d=s.channel;if(d==="cast"){l({type:"cast",payload:s.payload});return}if(d!=="linked")return;let a=A(s);if(!a){let O=s.pageRequestId;m(O)&&t.post({channel:"linked",type:"response",pageRequestId:O,response:{ok:!1,error:"invalid_request"}});return}let c=navigator.userActivation&&typeof navigator.userActivation.isActive=="boolean"?navigator.userActivation.isActive:null;if(a.operation==="choose_destination"&&c===!1){t.post({channel:"linked",type:"response",pageRequestId:a.pageRequestId,response:{ok:!1,error:"user_gesture_required"}});return}n.add(a.pageRequestId),l(a.operation==="choose_destination"?{...a,userActivation:c}:a,a.pageRequestId)})}z();x();function Q(){if(window.top!==window||!r.visibilityOverrides&&!(r.playerProbes&&r.videos))return;let e=document.createElement("script");e.textContent=`(function() {
      var hiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
      var visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      if (${r.visibilityOverrides}) try {
        Object.defineProperty(document, 'hidden', { configurable: true, get: function() { return false; } });
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: function() { return 'visible'; } });
      } catch (_) {}
      function report(url) {
        if (!url || typeof url !== 'string' || !url.startsWith('http')) return;
        window.dispatchEvent(new CustomEvent('PlayBridgeMediaFound', { detail: { url: url } }));
      }
      function probe() {
        try {
          if (window.jwplayer) {
            // best-effort: many pages expose jwplayer().getPlaylist
            try {
              var jw = window.jwplayer();
              var pl = jw && jw.getPlaylist && jw.getPlaylist();
              if (Array.isArray(pl)) {
                pl.forEach(function(item) {
                  if (item && item.file) report(item.file);
                  if (item && item.sources) item.sources.forEach(function(s) { if (s && s.file) report(s.file); });
                });
              }
            } catch (e) {}
          }
        } catch (e) {}
      }
      var timers = ${r.playerProbes&&r.videos} ? [setTimeout(probe, 1500), setTimeout(probe, 4000)] : [];
      window.addEventListener('PlayBridgeStopDetection', function stop() {
        timers.forEach(clearTimeout);
        if (${r.visibilityOverrides}) try {
          if (hiddenDescriptor) Object.defineProperty(document, 'hidden', hiddenDescriptor);
          else delete document.hidden;
          if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
          else delete document.visibilityState;
        } catch (_) {}
        window.removeEventListener('PlayBridgeStopDetection', stop);
      }, { once: true });
  })();`,(document.documentElement||document.head||document.body).appendChild(e),e.remove()}})();
