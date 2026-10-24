"use strict";(()=>{var C="__PLAYBRIDGE_PAGE_CHANNEL_BOOT__";function me(e){return{onMessage(t){e.onmessage=n=>t(n.data)},post(t){try{e.postMessage(JSON.stringify(t))}catch{}}}}function ve(e=C){return`
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
  window.dispatchEvent(new MessageEvent(${e}, { ports: [channel.port2] }));
  function channelSend(message) {
    try { channelPost(message); return true; } catch (_) { return false; }
  }
  function channelReceive(handler) { channelHandler = handler; }
`}var E=ve(C);function ye(){return`playbridge-boot-${globalThis.crypto?.randomUUID?.()??`${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`}`}function be(e,t){let n=o=>{o.stopImmediatePropagation(),window.removeEventListener(e,n,!0);let r=o.ports?.[0];r&&t(me(r))};return window.addEventListener(e,n,!0),()=>{window.removeEventListener(e,n,!0)}}function _(e){let t=ye(),n=null,o=be(t,r=>{n=r});try{let r=document.createElement("script");r.textContent=e.split(C).join(JSON.stringify(t)),(document.documentElement||document.head||document.body).appendChild(r),r.remove()}finally{o()}return n}var we=new Set(["open","play","replace","append","jump","supply","unlink","ping","destination","choose_destination"]),he=new Set(["channel","pageRequestId","operation","sessionId","payload"]),A=["needitems","statechange","ended"],$="playbridge-page-api";function I(e){return typeof e=="string"&&e.length>0&&e.length<=128&&!/[\x00-\x1f\x7f]/.test(e)}function J(e){try{if(!e||typeof e!="object"||Array.isArray(e))return null;let t=e;if(Object.keys(t).some(d=>!he.has(d)))return null;let{channel:n,pageRequestId:o,operation:r,sessionId:m,payload:f}=t;return n!==void 0&&n!=="linked"||!I(o)||typeof r!="string"||!we.has(r)||m!=null&&!I(m)||!f||typeof f!="object"||Array.isArray(f)?null:{type:"linked",pageRequestId:o,operation:r,sessionId:m??null,payload:f}}catch{return null}}function Ee(e){let t=window;if(t.playbridge_injected_version===6)return;t.playbridge_injected=!0,t.playbridge_injected_version=6;var n=new MessageChannel,o=MessagePort.prototype.postMessage.bind(n.port1),r=JSON.parse,m=null;n.port1.onmessage=function(i){if(!(typeof i.data!="string"||!m)){var s;try{s=r(i.data)}catch{return}s&&typeof s=="object"&&m(s)}},window.dispatchEvent(new MessageEvent(e,{ports:[n.port2]}));function f(i){try{return o(i),!0}catch{return!1}}function d(i){m=i}var c=Promise,l=Error,g=CustomEvent,j=EventTarget,oe=Function.prototype.call.bind(EventTarget.prototype.dispatchEvent),se=setTimeout,ae=clearTimeout,G=crypto.randomUUID?crypto.randomUUID.bind(crypto):null,de=["needitems","statechange","ended"],O=Object.create(null),S=0,T=Object.create(null),le=0;function D(i,s,u){var p=new l(u);p.code=s,i(p)}function v(i,s,u,p){var h=G?G():"request-"+ ++le;return new c(function(pe,x){if(S>=32){D(x,"resource_limit","Too many pending linked cast requests");return}var ge=se(function(){O[h]&&(delete O[h],S--,D(x,"timeout","Linked cast request timed out"))},i==="open"||i==="play"?66e4:45e3);O[h]={resolve:pe,reject:x,timeout:ge,onResponse:p},S++;var fe=f({channel:"linked",pageRequestId:h,operation:i,sessionId:s||null,payload:u||{}});fe||H(h,{ok:!1,error:"invalid_request",message:"Linked cast request could not be sent"})})}function H(i,s){var u=O[i];if(u){if(delete O[i],S--,ae(u.timeout),s=s&&typeof s=="object"?s:{},!s.ok){D(u.reject,s.error||"linked_cast_failed",s.message||s.error||"Linked cast failed");return}if(!u.onResponse){u.resolve(s);return}try{u.resolve(u.onResponse(s))}catch(p){u.reject(p)}}}function ue(i){var s=T[i.sessionId];if(!(!s||de.indexOf(i.event)===-1)){var u=i.detail&&typeof i.detail=="object"?i.detail:{};i.event==="ended"&&delete T[i.sessionId],oe(s,new g(i.event,{detail:u}))}}d(function(i){if(i.channel==="linked"){if(i.type==="response"&&typeof i.pageRequestId=="string")H(i.pageRequestId,i.response);else if(i.type==="event"&&typeof i.sessionId=="string")ue(i);else if(i.type==="capabilities"&&i.capabilities&&typeof i.capabilities=="object"){var s=i.capabilities;(s.playback===0||s.playback===1)&&(t.playbridge.capabilities.playback=s.playback),typeof s.linkedCast=="number"&&(t.playbridge.capabilities.linkedCast=s.linkedCast)}}});function ce(i){var s=new j;return s.sessionId=i,s.replace=function(u,p,h){return v("replace",i,{items:u,startIndex:p||0,metadata:h})},s.append=function(u,p){return v("append",i,{items:u,privateNetworkOrigins:p&&p.privateNetworkOrigins||[]})},s.jump=function(u){return v("jump",i,{index:u})},s.provideItems=function(u,p){return v("supply",i,{requestId:u,items:p&&p.items||[],endOfList:!!(p&&p.endOfList),privateNetworkOrigins:p&&p.privateNetworkOrigins||[]})},s.unlink=function(){return v("unlink",i,{})},s}function W(i){if(typeof i.sessionId!="string")throw new l("Linked cast failed");var s=ce(i.sessionId);return T[i.sessionId]=s,v("ping",i.sessionId,{ready:!0},function(){}).then(null,function(){}),s}t.playbridge=t.playbridge||{},t.playbridge.cast=function(i){f({channel:"cast",payload:i})},t.playbridge.capabilities=Object.assign({},t.playbridge.capabilities,{linkedCast:1,explicitHeaders:1,privateNetworkOriginPermission:1,playback:0,nativePlugins:0}),t.playbridge.getPlaybackDestination=function(){return v("destination",null,{})},t.playbridge.choosePlaybackDestination=function(i){return v("choose_destination",null,i||{})},t.playbridge.play=function(i){return v("play",null,i,W)},t.playbridge.linkCast=function(i){return v("open",null,i,W)}}function K(e={}){if((e.target??"geckoview")==="desktop"){let o=e.bootEvent??C;return`(${Ee.toString()})(${o});`}return`
    (function() {
      if (window.playbridge_injected_version === 6) return;
      ${E}
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
      var sessionEvents = ${JSON.stringify(A)};
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
  `}var F=K({target:"geckoview"}),xe=K({target:"desktop"});var _e=globalThis.browser??globalThis.chrome,y=_e;function N(e){return{videos:e?.videos!==!1,images:e?.images!==!1,audio:e?.audio!==!1,subtitles:e?.subtitles!==!1,domScanning:e?.domScanning!==!1,networkDetection:e?.networkDetection!==!1,responseScanning:e?.responseScanning!==!1,navigationRescans:e?.navigationRescans!==!1,playerProbes:e?.playerProbes!==!1,visibilityOverrides:e?.visibilityOverrides!==!1,detectInBridgedSites:e?.detectInBridgedSites===!0}}var Y="playbridge-detection-policy";function X(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;return t.type==="detection_policy"&&Number.isSafeInteger(t.revision)&&t.revision>=0&&typeof t.enabled=="boolean"&&typeof t.browserEnabled=="boolean"&&Array.isArray(t.bridgedAppOrigins)&&t.bridgedAppOrigins.every(n=>typeof n=="string")&&(t.options==null||typeof t.options=="object"&&!Array.isArray(t.options)&&Object.values(t.options).every(n=>typeof n=="boolean"))}var Ie=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function V(e){return/^https?:/i.test(e)&&!Ie.test(e)}var z=16*1024,ke=new Set(["status","resolve","manage","cancel"]);function Pe(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;if(Object.keys(t).some(r=>!["requestId","operation","payload"].includes(r))||typeof t.operation!="string"||!ke.has(t.operation))return!1;if(t.operation==="cancel")return Object.keys(t).length===1;if(typeof t.requestId!="string"||t.requestId.length<1||t.requestId.length>128||/[\x00-\x1f\x7f]/.test(t.requestId)||!t.payload||typeof t.payload!="object"||Array.isArray(t.payload))return!1;let n=t.payload;if(t.operation!=="resolve")return Object.keys(n).length===0;if(Object.keys(n).some(r=>!["repoUrl","scraperIds","tmdbId","mediaType","season","episode"].includes(r))||typeof n.repoUrl!="string"||n.repoUrl.length>2048||typeof n.tmdbId!="string"||!/^[1-9][0-9]{0,9}$/.test(n.tmdbId)||!["movie","tv"].includes(String(n.mediaType)))return!1;let o;try{o=new URL(n.repoUrl)}catch{return!1}return o.protocol!=="https:"||o.username||o.password||o.hash||!Array.isArray(n.scraperIds)||n.scraperIds.length<1||n.scraperIds.length>32||n.scraperIds.some(r=>typeof r!="string"||!r.trim()||r.length>128||/[\x00-\x1f\x7f]/.test(r))||new Set(n.scraperIds).size!==n.scraperIds.length?!1:n.mediaType==="movie"?n.season==null&&n.episode==null:Number.isInteger(n.season)&&Number(n.season)>=0&&Number(n.season)<=1e4&&Number.isInteger(n.episode)&&Number(n.episode)>=1&&Number(n.episode)<=1e4}var Oe=`
(function() {
  var bridge = window.playbridge = window.playbridge || {};
  if (bridge.plugins) return;
  ${E}
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
      if (encode(json).length > ${z}) { reject(new ErrorCtor('Device plugin request too large')); return; }
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
`;function Q(){if(window.top!==window)return;let e,t=!1,n,o=null;function r(d){o?.post(d)}function m(d){if(typeof d!="string"||new TextEncoder().encode(d).length>z)return;let c;try{let l=JSON.parse(d);if(!Pe(l)){let g=l?.requestId;typeof g=="string"&&g.length>=1&&g.length<=128&&!/[\x00-\x1f\x7f]/.test(g)&&r({type:"plugin_response",requestId:g,ok:!1,error:"invalid_request"});return}c=l}catch{return}if(c.operation==="manage"&&navigator.userActivation?.isActive!==!0){r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"user_gesture_required"});return}if(!e||t){r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"native_plugins_unavailable"});return}try{e.postMessage(c)}catch{r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"native_plugins_unavailable"})}}function f(){t=!1,n=void 0;try{e=y.runtime.connectNative("plugins"),e.onMessage.addListener(d=>{d?.type==="plugin_capabilities"&&(n=d),r(d)}),e.onDisconnect.addListener(()=>{t=!0,r({type:"plugin_disconnected"})})}catch{t=!0,r({type:"plugin_disconnected"})}}f(),o=_(Oe),o?.onMessage(m),n&&r(n),window.addEventListener("hashchange",()=>{if(!t)try{e?.postMessage({operation:"cancel"})}catch{}}),window.addEventListener("pagehide",()=>{t=!0;try{e?.disconnect()}catch{}r({type:"plugin_disconnected"})}),window.addEventListener("pageshow",d=>{d.persisted&&t&&f()})}function Ce(e){return!!e&&typeof e=="object"&&!Array.isArray(e)&&Object.keys(e).length===1&&e.type==="app_reopened"}var je=`
(function() {
  ${E}
  var EventCtor = Event;
  var dispatchWindow = window.dispatchEvent.bind(window);
  channelReceive(function(message) {
    if (message.type === 'app_reopened' && Object.keys(message).length === 1) {
      dispatchWindow(new EventCtor('PlayBridgeAppReopened'));
    }
  });
})();
`;function Z(){if(window.top!==window)return()=>!1;let e=_(je);return t=>Ce(t)?(e?.post(t),!0):!1}var b=!1,a=N(),L=0,R,te=()=>!1;y.runtime.onMessage.addListener(e=>(e?.type==="detector_same_document_navigation"&&a.navigationRescans&&ne(),!1));function w(e,t,n,o,r){!(e==="dom_image_found"?a.images:e==="dom_audio_found"?a.audio:e==="dom_subtitle_found"?a.subtitles:a.videos)||!b||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&y.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:o,height:r}).catch(()=>{})}function ee(e){if(!b||!a.domScanning||!a.images)return;let t=e.currentSrc||e.src;if(!t||!V(t))return;let n=e.naturalWidth,o=e.naturalHeight;n<64||o<64||n*o<16384||w("dom_image_found",t,void 0,n,o)}var M=new WeakSet;function q(e){if(!(!b||!a.domScanning)){if(e instanceof HTMLVideoElement){if(a.videos&&w("dom_video_found",e.currentSrc||e.src),a.images&&e.poster&&w("dom_image_found",e.poster,void 0,e.videoWidth||void 0,e.videoHeight||void 0),a.videos)for(let t of Array.from(e.querySelectorAll("source")))w("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){if(!a.audio)return;w("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))w("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");if(t instanceof HTMLAudioElement?!a.audio:!a.videos)return;w(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}if(typeof HTMLTrackElement<"u"&&e instanceof HTMLTrackElement){(e.kind==="subtitles"||e.kind==="captions")&&w("dom_subtitle_found",e.src);return}e instanceof HTMLImageElement&&a.images&&(ee(e),!e.complete&&!M.has(e)&&(M.add(e),e.addEventListener("load",()=>{M.delete(e),ee(e)},{once:!0})))}}function U(){return[a.videos||a.images?"video":"",a.audio?"audio":"",a.videos||a.audio?"source":"",a.images?"img":"",a.subtitles?"track":""].filter(Boolean).join(", ")}function ne(){if(!b||!a.domScanning)return;let e=U();e&&document.querySelectorAll(e).forEach(q)}function P(e){if(e!==b){if(b=e,!e){R?.disconnect(),R=void 0,document.removeEventListener("DOMContentLoaded",B),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}a.domScanning&&U()&&(R=new MutationObserver(t=>{if(b)for(let n of t){for(let o of n.addedNodes){if(o.nodeType!==1)continue;let r=o;q(r),r.querySelectorAll?.(U()).forEach(q)}n.type==="attributes"&&n.target.nodeType===1&&q(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",B,{once:!0}),ie()):B()),Re()}}function ie(){!b||!document.documentElement||R?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]})}function B(){ie(),ne()}var k,re=!1;function Se(){if(k)return k;let e=y.runtime.connect({name:Y});return k=e,e.onMessage.addListener(t=>{k===e&&t?.update===L&&P(t.ok===!0&&re)}),e.onDisconnect.addListener(()=>{k===e&&(k=void 0,L+=1,P(!1))}),e}try{let e=y.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(te(t)||!X(t))return;let n=++L,o=N(t.options);if((!t.enabled||JSON.stringify(a)!==JSON.stringify(o))&&P(!1),a=o,re=t.enabled,window.top!==window){P(t.enabled);return}try{Se().postMessage({update:n,policy:t})}catch{P(!1)}}),e.onDisconnect.addListener(()=>{L+=1,P(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!b||!a.playerProbes||!a.videos||!t||typeof t!="string"||!t.startsWith("http")||y.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));function Ae(){if(window.top!==window)return;let e=_(F);if(!e)return;let t=e,n=new Set,o,r=(d,c)=>{n.delete(d),t.post({channel:"linked",type:"response",pageRequestId:d,response:c})};function m(){if(o)return o;let d=y.runtime.connect({name:$});return o=d,d.onMessage.addListener(c=>{if(o!==d||!c||typeof c!="object")return;let l=c;if(l.type==="response"&&I(l.pageRequestId)&&n.has(l.pageRequestId)){let g=l.response&&typeof l.response=="object"?l.response:{};r(l.pageRequestId,g)}else if(l.type==="event"&&I(l.sessionId)&&A.includes(l.event)){let g=l.detail&&typeof l.detail=="object"?l.detail:{};t.post({channel:"linked",type:"event",sessionId:l.sessionId,event:l.event,detail:g})}}),d.onDisconnect.addListener(()=>{if(o===d){o=void 0;for(let c of[...n])r(c,{ok:!1,error:"native_unavailable"})}}),d}function f(d,c){try{m().postMessage(d)}catch{c&&r(c,{ok:!1,error:"native_unavailable"})}}t.onMessage(d=>{if(window.top!==window||!d||typeof d!="object")return;let c=d.channel;if(c==="cast"){f({type:"cast",payload:d.payload});return}if(c!=="linked")return;let l=J(d);if(!l){let j=d.pageRequestId;I(j)&&t.post({channel:"linked",type:"response",pageRequestId:j,response:{ok:!1,error:"invalid_request"}});return}let g=navigator.userActivation&&typeof navigator.userActivation.isActive=="boolean"?navigator.userActivation.isActive:null;if(l.operation==="choose_destination"&&g===!1){t.post({channel:"linked",type:"response",pageRequestId:l.pageRequestId,response:{ok:!1,error:"user_gesture_required"}});return}n.add(l.pageRequestId),f(l.operation==="choose_destination"?{...l,userActivation:g}:l,l.pageRequestId)})}Ae();Q();te=Z();function Re(){if(window.top!==window||!a.visibilityOverrides&&!(a.playerProbes&&a.videos))return;let e=document.createElement("script");e.textContent=`(function() {
      var hiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
      var visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      if (${a.visibilityOverrides}) try {
        Object.defineProperty(document, 'hidden', { configurable: true, get: function() { return false; } });
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: function() { return 'visible'; } });
      } catch (_) { /* page may already lock these properties */ }
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
            } catch (e) { /* player API shape varies; skip this probe */ }
          }
        } catch (e) { /* jwplayer may be missing or throw */ }
      }
      var timers = ${a.playerProbes&&a.videos} ? [setTimeout(probe, 1500), setTimeout(probe, 4000)] : [];
      window.addEventListener('PlayBridgeStopDetection', function stop() {
        timers.forEach(clearTimeout);
        if (${a.visibilityOverrides}) try {
          if (hiddenDescriptor) Object.defineProperty(document, 'hidden', hiddenDescriptor);
          else delete document.hidden;
          if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
          else delete document.visibilityState;
        } catch (_) { /* page may reject restoring the original descriptor */ }
        window.removeEventListener('PlayBridgeStopDetection', stop);
      }, { once: true });
  })();`,(document.documentElement||document.head||document.body).appendChild(e),e.remove()}})();
