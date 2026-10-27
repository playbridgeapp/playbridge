"use strict";(()=>{var O="__PLAYBRIDGE_PAGE_CHANNEL_BOOT__";function he(e){return{onMessage(t){e.onmessage=n=>t(n.data)},post(t){try{e.postMessage(JSON.stringify(t))}catch{}}}}function Ee(e=O){return`
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
`}var C=Ee(O);function _e(){return`playbridge-boot-${globalThis.crypto?.randomUUID?.()??`${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`}`}function Ie(e,t){let n=i=>{i.stopImmediatePropagation(),window.removeEventListener(e,n,!0);let o=i.ports?.[0];o&&t(he(o))};return window.addEventListener(e,n,!0),()=>{window.removeEventListener(e,n,!0)}}function L(e){let t=_e(),n=null,i=Ie(t,o=>{n=o});try{let o=document.createElement("script");o.textContent=e.split(O).join(JSON.stringify(t)),(document.documentElement||document.head||document.body).appendChild(o),o.remove()}finally{i()}return n}var ke=new Set(["open","play","replace","append","jump","supply","unlink","ping","destination","choose_destination"]),Pe=new Set(["channel","pageRequestId","operation","sessionId","payload"]),R=["needitems","statechange","ended"],z="playbridge-page-api";function k(e){return typeof e=="string"&&e.length>0&&e.length<=128&&!/[\x00-\x1f\x7f]/.test(e)}function V(e){try{if(!e||typeof e!="object"||Array.isArray(e))return null;let t=e;if(Object.keys(t).some(s=>!Pe.has(s)))return null;let{channel:n,pageRequestId:i,operation:o,sessionId:m,payload:f}=t;return n!==void 0&&n!=="linked"||!k(i)||typeof o!="string"||!ke.has(o)||m!=null&&!k(m)||!f||typeof f!="object"||Array.isArray(f)?null:{type:"linked",pageRequestId:i,operation:o,sessionId:m??null,payload:f}}catch{return null}}function Se(e){let t=window;if(t.playbridge_injected_version===6)return;t.playbridge_injected=!0,t.playbridge_injected_version=6;var n=new MessageChannel,i=MessagePort.prototype.postMessage.bind(n.port1),o=JSON.parse,m=null;n.port1.onmessage=function(r){if(!(typeof r.data!="string"||!m)){var l;try{l=o(r.data)}catch{return}l&&typeof l=="object"&&m(l)}},window.dispatchEvent(new MessageEvent(e,{ports:[n.port2]}));function f(r){try{return i(r),!0}catch{return!1}}function s(r){m=r}var a=Promise,d=Error,c=CustomEvent,y=EventTarget,I=Function.prototype.call.bind(EventTarget.prototype.dispatchEvent),S=setTimeout,pe=clearTimeout,Y=crypto.randomUUID?crypto.randomUUID.bind(crypto):null,ge=["needitems","statechange","ended"],T=Object.create(null),j=0,M=Object.create(null),fe=0;function B(r,l,p){var g=new d(p);g.code=l,r(g)}function v(r,l,p,g){var _=Y?Y():"request-"+ ++fe;return new a(function(ve,U){if(j>=32){B(U,"resource_limit","Too many pending linked cast requests");return}var be=S(function(){T[_]&&(delete T[_],j--,B(U,"timeout","Linked cast request timed out"))},r==="open"||r==="play"?66e4:45e3);T[_]={resolve:ve,reject:U,timeout:be,onResponse:g},j++;var we=f({channel:"linked",pageRequestId:_,operation:r,sessionId:l||null,payload:p||{}});we||K(_,{ok:!1,error:"invalid_request",message:"Linked cast request could not be sent"})})}function K(r,l){var p=T[r];if(p){if(delete T[r],j--,pe(p.timeout),l=l&&typeof l=="object"?l:{},!l.ok){B(p.reject,l.error||"linked_cast_failed",l.message||l.error||"Linked cast failed");return}if(!p.onResponse){p.resolve(l);return}try{p.resolve(p.onResponse(l))}catch(g){p.reject(g)}}}function me(r){var l=M[r.sessionId];if(!(!l||ge.indexOf(r.event)===-1)){var p=r.detail&&typeof r.detail=="object"?r.detail:{};r.event==="ended"&&delete M[r.sessionId],I(l,new c(r.event,{detail:p}))}}s(function(r){if(r.channel==="linked"){if(r.type==="response"&&typeof r.pageRequestId=="string")K(r.pageRequestId,r.response);else if(r.type==="event"&&typeof r.sessionId=="string")me(r);else if(r.type==="capabilities"&&r.capabilities&&typeof r.capabilities=="object"){var l=r.capabilities;(l.playback===0||l.playback===1)&&(t.playbridge.capabilities.playback=l.playback),typeof l.linkedCast=="number"&&(t.playbridge.capabilities.linkedCast=l.linkedCast)}}});function ye(r){var l=new y;return l.sessionId=r,l.replace=function(p,g,_){return v("replace",r,{items:p,startIndex:g||0,metadata:_})},l.append=function(p,g){return v("append",r,{items:p,privateNetworkOrigins:g&&g.privateNetworkOrigins||[]})},l.jump=function(p){return v("jump",r,{index:p})},l.provideItems=function(p,g){return v("supply",r,{requestId:p,items:g&&g.items||[],endOfList:!!(g&&g.endOfList),privateNetworkOrigins:g&&g.privateNetworkOrigins||[]})},l.unlink=function(){return v("unlink",r,{})},l}function X(r){if(typeof r.sessionId!="string")throw new d("Linked cast failed");var l=ye(r.sessionId);return M[r.sessionId]=l,v("ping",r.sessionId,{ready:!0},function(){}).then(null,function(){}),l}t.playbridge=t.playbridge||{},t.playbridge.cast=function(r){f({channel:"cast",payload:r})},t.playbridge.capabilities=Object.assign({},t.playbridge.capabilities,{linkedCast:1,explicitHeaders:1,privateNetworkOriginPermission:1,playback:0,nativePlugins:0}),t.playbridge.getPlaybackDestination=function(){return v("destination",null,{})},t.playbridge.choosePlaybackDestination=function(r){return v("choose_destination",null,r||{})},t.playbridge.play=function(r){return v("play",null,r,X)},t.playbridge.linkCast=function(r){return v("open",null,r,X)}}function Q(e={}){if((e.target??"geckoview")==="desktop"){let i=e.bootEvent??O;return`(${Se.toString()})(${i});`}return`
    (function() {
      if (window.playbridge_injected_version === 6) return;
      ${C}
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
      var sessionEvents = ${JSON.stringify(R)};
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
  `}var Z=Q({target:"geckoview"}),Ge=Q({target:"desktop"});var Te=globalThis.browser??globalThis.chrome,b=Te;function H(e){return{videos:e?.videos!==!1,images:e?.images!==!1,audio:e?.audio!==!1,subtitles:e?.subtitles!==!1,domScanning:e?.domScanning!==!1,networkDetection:e?.networkDetection!==!1,responseScanning:e?.responseScanning!==!1,navigationRescans:e?.navigationRescans!==!1,playerProbes:e?.playerProbes!==!1,visibilityOverrides:e?.visibilityOverrides!==!1,detectInBridgedSites:e?.detectInBridgedSites===!0}}var ee="playbridge-detection-policy";function te(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;return t.type==="detection_policy"&&Number.isSafeInteger(t.revision)&&t.revision>=0&&typeof t.enabled=="boolean"&&typeof t.browserEnabled=="boolean"&&Array.isArray(t.bridgedAppOrigins)&&t.bridgedAppOrigins.every(n=>typeof n=="string")&&(t.options==null||typeof t.options=="object"&&!Array.isArray(t.options)&&Object.values(t.options).every(n=>typeof n=="boolean"))}var Oe=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function ne(e){return/^https?:/i.test(e)&&!Oe.test(e)}var ie=16*1024,je=new Set(["status","resolve","manage","cancel"]);function Ce(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;if(Object.keys(t).some(o=>!["requestId","operation","payload"].includes(o))||typeof t.operation!="string"||!je.has(t.operation))return!1;if(t.operation==="cancel")return Object.keys(t).length===1;if(typeof t.requestId!="string"||t.requestId.length<1||t.requestId.length>128||/[\x00-\x1f\x7f]/.test(t.requestId)||!t.payload||typeof t.payload!="object"||Array.isArray(t.payload))return!1;let n=t.payload;if(t.operation!=="resolve")return Object.keys(n).length===0;if(Object.keys(n).some(o=>!["repoUrl","scraperIds","tmdbId","mediaType","season","episode"].includes(o))||typeof n.repoUrl!="string"||n.repoUrl.length>2048||typeof n.tmdbId!="string"||!/^[1-9][0-9]{0,9}$/.test(n.tmdbId)||!["movie","tv"].includes(String(n.mediaType)))return!1;let i;try{i=new URL(n.repoUrl)}catch{return!1}return i.protocol!=="https:"||i.username||i.password||i.hash||!Array.isArray(n.scraperIds)||n.scraperIds.length<1||n.scraperIds.length>32||n.scraperIds.some(o=>typeof o!="string"||!o.trim()||o.length>128||/[\x00-\x1f\x7f]/.test(o))||new Set(n.scraperIds).size!==n.scraperIds.length?!1:n.mediaType==="movie"?n.season==null&&n.episode==null:Number.isInteger(n.season)&&Number(n.season)>=0&&Number(n.season)<=1e4&&Number.isInteger(n.episode)&&Number(n.episode)>=1&&Number(n.episode)<=1e4}var Le=`
(function() {
  var bridge = window.playbridge = window.playbridge || {};
  if (bridge.plugins) return;
  ${C}
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
      if (encode(json).length > ${ie}) { reject(new ErrorCtor('Device plugin request too large')); return; }
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
`;function re(){if(window.top!==window)return;let e,t=!1,n,i=null;function o(s){i?.post(s)}function m(s){if(typeof s!="string"||new TextEncoder().encode(s).length>ie)return;let a;try{let d=JSON.parse(s);if(!Ce(d)){let c=d?.requestId;typeof c=="string"&&c.length>=1&&c.length<=128&&!/[\x00-\x1f\x7f]/.test(c)&&o({type:"plugin_response",requestId:c,ok:!1,error:"invalid_request"});return}a=d}catch{return}if(a.operation==="manage"&&navigator.userActivation?.isActive!==!0){o({type:"plugin_response",requestId:a.requestId,ok:!1,error:"user_gesture_required"});return}if(!e||t){o({type:"plugin_response",requestId:a.requestId,ok:!1,error:"native_plugins_unavailable"});return}try{e.postMessage(a)}catch{o({type:"plugin_response",requestId:a.requestId,ok:!1,error:"native_plugins_unavailable"})}}function f(){t=!1,n=void 0;try{e=b.runtime.connectNative("plugins"),e.onMessage.addListener(s=>{s?.type==="plugin_capabilities"&&(n=s),o(s)}),e.onDisconnect.addListener(()=>{t=!0,o({type:"plugin_disconnected"})})}catch{t=!0,o({type:"plugin_disconnected"})}}f(),i=L(Le),i?.onMessage(m),n&&o(n),window.addEventListener("hashchange",()=>{if(!t)try{e?.postMessage({operation:"cancel"})}catch{}}),window.addEventListener("pagehide",()=>{t=!0;try{e?.disconnect()}catch{}o({type:"plugin_disconnected"})}),window.addEventListener("pageshow",s=>{s.persisted&&t&&f()})}function oe(e,t=""){let n=t.toLowerCase().split(";",1)[0].trim();if(/^(video|audio|image)\//.test(n))return null;let i=e.replace(/^\uFEFF/,"").trimStart().slice(0,8192);return/^WEBVTT(?:[ \t]|\r?\n|$)/.test(i)?"vtt":/^(?:\d+\s*\r?\n)?\d{1,2}:\d{2}:\d{2},\d{3}\s*-->\s*\d{1,2}:\d{2}:\d{2},\d{3}/.test(i)?"srt":n==="text/vtt"?"vtt":n.includes("subrip")?"srt":null}function Re(e,t=""){if(typeof e!="string"||!e||e.length>2097152||new TextEncoder().encode(e).length>2097152)return null;let n=oe(e,t);return n?{subtitleText:e,subtitleFormat:n}:null}function qe(e){let t=2166136261,n=2654435769;for(let i=0;i<e.length;i++){let o=e.charCodeAt(i);t=Math.imul(t^o,16777619),n=Math.imul(n^o,2246822507)}return(t>>>0).toString(16).padStart(8,"0")+(n>>>0).toString(16).padStart(8,"0")}async function se(e,t=()=>!0){try{let n=Object.getOwnPropertyDescriptor(Blob.prototype,"size").get.call(e),i=Object.getOwnPropertyDescriptor(Blob.prototype,"type").get.call(e);if(!t()||!n||n>2097152||/^(video|audio|image)\//i.test(i))return null;let o=await Blob.prototype.slice.call(e,0,8192).text();return!t()||!oe(o,i)?null:Re(await Blob.prototype.text.call(e),i)}catch{return null}}var q=class{constructor(){this.labels=new Map}accept(t,n="Subtitles"){let i=qe(t.subtitleText),o=this.labels.get(i);return o&&(o!=="Subtitles"||n==="Subtitles")?null:(this.labels.set(i,n),{url:`playbridge-subtitle:${i}.${t.subtitleFormat}`,subtitleLabel:n})}};function ae(e){let t=!0,n=new q,i=new Set,o=()=>{};function m(s){for(let a of document.querySelectorAll("track"))if(a.src===s&&(a.kind==="subtitles"||a.kind==="captions"))return(a.label||a.srclang||"Subtitles").slice(0,256);return"Subtitles"}async function f(s,a){if(!t)return;let d=await se(s,()=>t);if(!t||!d)return;let c=n.accept(d,m(a));c&&e({...d,...c})}try{let s=window.wrappedJSObject;if(s?.URL&&typeof exportFunction=="function"){let a=s.URL,d=a.createObjectURL,c=exportFunction(function(y){let I=Reflect.apply(d,this,arguments);try{f(y,I).catch(()=>{})}catch{}return I},s);a.createObjectURL=c,o=()=>{a.createObjectURL===c&&(a.createObjectURL=d)}}}catch{}return{track(s){let a=s.src;if(!(!t||!a.startsWith("blob:")||i.has(a))){i.add(a);try{let d=window.wrappedJSObject,c=typeof content<"u"?content.fetch.bind(content):d?.fetch.bind(d);if(!c){i.delete(a);return}c(a).then(y=>{if(!t)return null;let I=y.headers.get("content-type")??"",S=Number(y.headers.get("content-length"));return/^(video|audio|image)\//i.test(I)||!Number.isFinite(S)||S<=0||S>2097152?null:y.blob()}).then(y=>y&&f(y,a)).catch(()=>{}).finally(()=>i.delete(a))}catch{i.delete(a)}}},stop(){t=!1,i.clear();try{o()}catch{}}}}var w=!1,$=!1,J=!0,u=H(),N=0,x,A;b.runtime.onMessage.addListener(e=>(e?.type==="detector_same_document_navigation"&&u.navigationRescans&&le(),!1));function h(e,t,n,i,o){!(e==="dom_image_found"?u.images:e==="dom_audio_found"?u.audio:e==="dom_subtitle_found"?u.subtitles:u.videos)||!w||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&b.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:i,height:o}).catch(()=>{})}function de(e){if(!w||!u.domScanning||!u.images)return;let t=e.currentSrc||e.src;if(!t||!ne(t))return;let n=e.naturalWidth,i=e.naturalHeight;n<64||i<64||n*i<16384||h("dom_image_found",t,void 0,n,i)}var G=new WeakSet;function D(e){if(!(!w||!u.domScanning)){if(e instanceof HTMLVideoElement){if(u.videos&&h("dom_video_found",e.currentSrc||e.src),u.images&&e.poster&&h("dom_image_found",e.poster,void 0,e.videoWidth||void 0,e.videoHeight||void 0),u.videos)for(let t of Array.from(e.querySelectorAll("source")))h("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){if(!u.audio)return;h("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))h("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");if(t instanceof HTMLAudioElement?!u.audio:!u.videos)return;h(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}if(typeof HTMLTrackElement<"u"&&e instanceof HTMLTrackElement){(e.kind==="subtitles"||e.kind==="captions")&&(e.src.startsWith("blob:")?A?.track(e):h("dom_subtitle_found",e.src));return}e instanceof HTMLImageElement&&u.images&&(de(e),!e.complete&&!G.has(e)&&(G.add(e),e.addEventListener("load",()=>{G.delete(e),de(e)},{once:!0})))}}function W(){return[u.videos||u.images?"video":"",u.audio?"audio":"",u.videos||u.audio?"source":"",u.images?"img":"",u.subtitles?"track":""].filter(Boolean).join(", ")}function le(){if(!w||!u.domScanning)return;let e=W();e&&document.querySelectorAll(e).forEach(D)}function E(e){if($=e,e=e&&J,e!==w){if(w=e,!e){A?.stop(),A=void 0,x?.disconnect(),x=void 0,document.removeEventListener("DOMContentLoaded",F),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}u.subtitles&&(A=ae(t=>{!w||!u.subtitles||b.runtime.sendMessage({action:"blob_subtitle_found",origin:window.location.href,...t}).catch(()=>{})})),u.domScanning&&W()&&(x=new MutationObserver(t=>{if(w)for(let n of t){for(let i of n.addedNodes){if(i.nodeType!==1)continue;let o=i;D(o),o.querySelectorAll?.(W()).forEach(D)}n.type==="attributes"&&n.target.nodeType===1&&D(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",F,{once:!0}),ue()):F()),Ne()}}function ue(){!w||!document.documentElement||x?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster","kind","label","srclang"]})}function F(){ue(),le()}window.addEventListener("pagehide",()=>{J=!1,E($)});window.addEventListener("pageshow",()=>{J=!0,E($)});var P,ce=!1;function Ae(){if(P)return P;let e=b.runtime.connect({name:ee});return P=e,e.onMessage.addListener(t=>{P===e&&t?.update===N&&E(t.ok===!0&&ce)}),e.onDisconnect.addListener(()=>{P===e&&(P=void 0,N+=1,E(!1))}),e}try{let e=b.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(!te(t))return;let n=++N,i=H(t.options);if((!t.enabled||JSON.stringify(u)!==JSON.stringify(i))&&E(!1),u=i,ce=t.enabled,window.top!==window){E(t.enabled);return}try{Ae().postMessage({update:n,policy:t})}catch{E(!1)}}),e.onDisconnect.addListener(()=>{N+=1,E(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!w||!u.playerProbes||!u.videos||!t||typeof t!="string"||!t.startsWith("http")||b.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));function De(){if(window.top!==window)return;let e=L(Z);if(!e)return;let t=e,n=new Set,i,o=(s,a)=>{n.delete(s),t.post({channel:"linked",type:"response",pageRequestId:s,response:a})};function m(){if(i)return i;let s=b.runtime.connect({name:z});return i=s,s.onMessage.addListener(a=>{if(i!==s||!a||typeof a!="object")return;let d=a;if(d.type==="response"&&k(d.pageRequestId)&&n.has(d.pageRequestId)){let c=d.response&&typeof d.response=="object"?d.response:{};o(d.pageRequestId,c)}else if(d.type==="event"&&k(d.sessionId)&&R.includes(d.event)){let c=d.detail&&typeof d.detail=="object"?d.detail:{};t.post({channel:"linked",type:"event",sessionId:d.sessionId,event:d.event,detail:c})}}),s.onDisconnect.addListener(()=>{if(i===s){i=void 0;for(let a of[...n])o(a,{ok:!1,error:"native_unavailable"})}}),s}function f(s,a){try{m().postMessage(s)}catch{a&&o(a,{ok:!1,error:"native_unavailable"})}}t.onMessage(s=>{if(window.top!==window||!s||typeof s!="object")return;let a=s.channel;if(a==="cast"){f({type:"cast",payload:s.payload});return}if(a!=="linked")return;let d=V(s);if(!d){let y=s.pageRequestId;k(y)&&t.post({channel:"linked",type:"response",pageRequestId:y,response:{ok:!1,error:"invalid_request"}});return}let c=navigator.userActivation&&typeof navigator.userActivation.isActive=="boolean"?navigator.userActivation.isActive:null;if(d.operation==="choose_destination"&&c===!1){t.post({channel:"linked",type:"response",pageRequestId:d.pageRequestId,response:{ok:!1,error:"user_gesture_required"}});return}n.add(d.pageRequestId),f(d.operation==="choose_destination"?{...d,userActivation:c}:d,d.pageRequestId)})}De();re();function Ne(){if(window.top!==window||!u.visibilityOverrides&&!(u.playerProbes&&u.videos))return;let e=document.createElement("script");e.textContent=`(function() {
      var hiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
      var visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      if (${u.visibilityOverrides}) try {
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
      var timers = ${u.playerProbes&&u.videos} ? [setTimeout(probe, 1500), setTimeout(probe, 4000)] : [];
      window.addEventListener('PlayBridgeStopDetection', function stop() {
        timers.forEach(clearTimeout);
        if (${u.visibilityOverrides}) try {
          if (hiddenDescriptor) Object.defineProperty(document, 'hidden', hiddenDescriptor);
          else delete document.hidden;
          if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
          else delete document.visibilityState;
        } catch (_) { /* page may reject restoring the original descriptor */ }
        window.removeEventListener('PlayBridgeStopDetection', stop);
      }, { once: true });
  })();`,(document.documentElement||document.head||document.body).appendChild(e),e.remove()}})();
