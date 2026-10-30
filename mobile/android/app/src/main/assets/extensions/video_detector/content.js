"use strict";(()=>{var T="__PLAYBRIDGE_PAGE_CHANNEL_BOOT__";function ve(e){return{onMessage(t){e.onmessage=n=>t(n.data)},post(t){try{e.postMessage(JSON.stringify(t))}catch{}}}}function he(e=T){return`
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
`}var K=he(T);function we(){return`playbridge-boot-${globalThis.crypto?.randomUUID?.()??`${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`}`}function Ee(e,t){let n=r=>{r.stopImmediatePropagation(),window.removeEventListener(e,n,!0);let l=r.ports?.[0];l&&t(ve(l))};return window.addEventListener(e,n,!0),()=>{window.removeEventListener(e,n,!0)}}function X(e){let t=we(),n=null,r=Ee(t,l=>{n=l});try{let l=document.createElement("script");l.textContent=e.split(T).join(JSON.stringify(t)),(document.documentElement||document.head||document.body).appendChild(l),l.remove()}finally{r()}return n}var _e=new Set(["open","play","replace","append","jump","supply","unlink","ping","destination","choose_destination"]),ke=new Set(["channel","pageRequestId","operation","sessionId","payload"]),R=["needitems","statechange","ended"],z="playbridge-page-api";function S(e){return typeof e=="string"&&e.length>0&&e.length<=128&&!/[\x00-\x1f\x7f]/.test(e)}function V(e){try{if(!e||typeof e!="object"||Array.isArray(e))return null;let t=e;if(Object.keys(t).some(c=>!ke.has(c)))return null;let{channel:n,pageRequestId:r,operation:l,sessionId:b,payload:g}=t;return n!==void 0&&n!=="linked"||!S(r)||typeof l!="string"||!_e.has(l)||b!=null&&!S(b)||!g||typeof g!="object"||Array.isArray(g)?null:{type:"linked",pageRequestId:r,operation:l,sessionId:b??null,payload:g}}catch{return null}}function Se(e){let t=window;if(t.playbridge_injected_version===6)return;t.playbridge_injected=!0,t.playbridge_injected_version=6;var n=new MessageChannel,r=MessagePort.prototype.postMessage.bind(n.port1),l=JSON.parse,b=null;n.port1.onmessage=function(i){if(!(typeof i.data!="string"||!b)){var o;try{o=l(i.data)}catch{return}o&&typeof o=="object"&&b(o)}},window.dispatchEvent(new MessageEvent(e,{ports:[n.port2]}));function g(i){try{return r(i),!0}catch{return!1}}function c(i){b=i}var s=Promise,a=Error,f=CustomEvent,m=EventTarget,k=Function.prototype.call.bind(EventTarget.prototype.dispatchEvent),P=setTimeout,ce=clearTimeout,$=crypto.randomUUID?crypto.randomUUID.bind(crypto):null,ue=["needitems","statechange","ended"],O=Object.create(null),L=0,q=Object.create(null),pe=0;function M(i,o,u){var p=new a(u);p.code=o,i(p)}function y(i,o,u,p){var _=$?$():"request-"+ ++pe;return new s(function(me,N){if(L>=32){M(N,"resource_limit","Too many pending linked cast requests");return}var be=P(function(){O[_]&&(delete O[_],L--,M(N,"timeout","Linked cast request timed out"))},i==="open"||i==="play"?66e4:45e3);O[_]={resolve:me,reject:N,timeout:be,onResponse:p},L++;var ye=g({channel:"linked",pageRequestId:_,operation:i,sessionId:o||null,payload:u||{}});ye||J(_,{ok:!1,error:"invalid_request",message:"Linked cast request could not be sent"})})}function J(i,o){var u=O[i];if(u){if(delete O[i],L--,ce(u.timeout),o=o&&typeof o=="object"?o:{},!o.ok){M(u.reject,o.error||"linked_cast_failed",o.message||o.error||"Linked cast failed");return}if(!u.onResponse){u.resolve(o);return}try{u.resolve(u.onResponse(o))}catch(p){u.reject(p)}}}function fe(i){var o=q[i.sessionId];if(!(!o||ue.indexOf(i.event)===-1)){var u=i.detail&&typeof i.detail=="object"?i.detail:{};i.event==="ended"&&delete q[i.sessionId],k(o,new f(i.event,{detail:u}))}}c(function(i){if(i.channel==="linked"){if(i.type==="response"&&typeof i.pageRequestId=="string")J(i.pageRequestId,i.response);else if(i.type==="event"&&typeof i.sessionId=="string")fe(i);else if(i.type==="capabilities"&&i.capabilities&&typeof i.capabilities=="object"){var o=i.capabilities;(o.playback===0||o.playback===1)&&(t.playbridge.capabilities.playback=o.playback),typeof o.linkedCast=="number"&&(t.playbridge.capabilities.linkedCast=o.linkedCast)}}});function ge(i){var o=new m;return o.sessionId=i,o.replace=function(u,p,_){return y("replace",i,{items:u,startIndex:p||0,metadata:_})},o.append=function(u,p){return y("append",i,{items:u,privateNetworkOrigins:p&&p.privateNetworkOrigins||[]})},o.jump=function(u){return y("jump",i,{index:u})},o.provideItems=function(u,p){return y("supply",i,{requestId:u,items:p&&p.items||[],endOfList:!!(p&&p.endOfList),privateNetworkOrigins:p&&p.privateNetworkOrigins||[]})},o.unlink=function(){return y("unlink",i,{})},o}function Y(i){if(typeof i.sessionId!="string")throw new a("Linked cast failed");var o=ge(i.sessionId);return q[i.sessionId]=o,y("ping",i.sessionId,{ready:!0},function(){}).then(null,function(){}),o}t.playbridge=t.playbridge||{},t.playbridge.cast=function(i){g({channel:"cast",payload:i})},t.playbridge.capabilities=Object.assign({},t.playbridge.capabilities,{linkedCast:1,explicitHeaders:1,privateNetworkOriginPermission:1,playback:0}),t.playbridge.getPlaybackDestination=function(){return y("destination",null,{})},t.playbridge.choosePlaybackDestination=function(i){return y("choose_destination",null,i||{})},t.playbridge.play=function(i){return y("play",null,i,Y)},t.playbridge.linkCast=function(i){return y("open",null,i,Y)}}function Q(e={}){if((e.target??"geckoview")==="desktop"){let r=e.bootEvent??T;return`(${Se.toString()})(${r});`}return`
    (function() {
      if (window.playbridge_injected_version === 6) return;
      ${K}
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
  `}var Z=Q({target:"geckoview"}),Me=Q({target:"desktop"});var Ie=globalThis.browser??globalThis.chrome,h=Ie;function B(e){return{videos:e?.videos!==!1,images:e?.images!==!1,audio:e?.audio!==!1,subtitles:e?.subtitles!==!1,domScanning:e?.domScanning!==!1,networkDetection:e?.networkDetection!==!1,responseScanning:e?.responseScanning!==!1,navigationRescans:e?.navigationRescans!==!1,playerProbes:e?.playerProbes!==!1,visibilityOverrides:e?.visibilityOverrides!==!1,detectInBridgedSites:e?.detectInBridgedSites===!0}}var ee="playbridge-detection-policy";function te(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;return t.type==="detection_policy"&&Number.isSafeInteger(t.revision)&&t.revision>=0&&typeof t.enabled=="boolean"&&typeof t.browserEnabled=="boolean"&&Array.isArray(t.bridgedAppOrigins)&&t.bridgedAppOrigins.every(n=>typeof n=="string")&&(t.options==null||typeof t.options=="object"&&!Array.isArray(t.options)&&Object.values(t.options).every(n=>typeof n=="boolean"))}var Pe=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function ne(e){return/^https?:/i.test(e)&&!Pe.test(e)}function ie(e,t=""){let n=t.toLowerCase().split(";",1)[0].trim();if(/^(video|audio|image)\//.test(n))return null;let r=e.replace(/^\uFEFF/,"").trimStart().slice(0,8192);return/^WEBVTT(?:[ \t]|\r?\n|$)/.test(r)?"vtt":/^(?:\d+\s*\r?\n)?\d{1,2}:\d{2}:\d{2},\d{3}\s*-->\s*\d{1,2}:\d{2}:\d{2},\d{3}/.test(r)?"srt":n==="text/vtt"?"vtt":n.includes("subrip")?"srt":null}function Oe(e,t=""){if(typeof e!="string"||!e||e.length>2097152||new TextEncoder().encode(e).length>2097152)return null;let n=ie(e,t);return n?{subtitleText:e,subtitleFormat:n}:null}function Te(e){let t=2166136261,n=2654435769;for(let r=0;r<e.length;r++){let l=e.charCodeAt(r);t=Math.imul(t^l,16777619),n=Math.imul(n^l,2246822507)}return(t>>>0).toString(16).padStart(8,"0")+(n>>>0).toString(16).padStart(8,"0")}async function re(e,t=()=>!0){try{let n=Object.getOwnPropertyDescriptor(Blob.prototype,"size").get.call(e),r=Object.getOwnPropertyDescriptor(Blob.prototype,"type").get.call(e);if(!t()||!n||n>2097152||/^(video|audio|image)\//i.test(r))return null;let l=await Blob.prototype.slice.call(e,0,8192).text();return!t()||!ie(l,r)?null:Oe(await Blob.prototype.text.call(e),r)}catch{return null}}var j=class{constructor(){this.labels=new Map}accept(t,n="Subtitles"){let r=Te(t.subtitleText),l=this.labels.get(r);return l&&(l!=="Subtitles"||n==="Subtitles")?null:(this.labels.set(r,n),{url:`playbridge-subtitle:${r}.${t.subtitleFormat}`,subtitleLabel:n})}};function oe(e){let t=!0,n=new j,r=new Set,l=()=>{};function b(c){for(let s of document.querySelectorAll("track"))if(s.src===c&&(s.kind==="subtitles"||s.kind==="captions"))return(s.label||s.srclang||"Subtitles").slice(0,256);return"Subtitles"}async function g(c,s){if(!t)return;let a=await re(c,()=>t);if(!t||!a)return;let f=n.accept(a,b(s));f&&e({...a,...f})}try{let c=window.wrappedJSObject;if(c?.URL&&typeof exportFunction=="function"){let s=c.URL,a=s.createObjectURL,f=exportFunction(function(m){let k=Reflect.apply(a,this,arguments);try{g(m,k).catch(()=>{})}catch{}return k},c);s.createObjectURL=f,l=()=>{s.createObjectURL===f&&(s.createObjectURL=a)}}}catch{}return{track(c){let s=c.src;if(!(!t||!s.startsWith("blob:")||r.has(s))){r.add(s);try{let a=window.wrappedJSObject,f=typeof content<"u"?content.fetch.bind(content):a?.fetch.bind(a);if(!f){r.delete(s);return}f(s).then(m=>{if(!t)return null;let k=m.headers.get("content-type")??"",P=Number(m.headers.get("content-length"));return/^(video|audio|image)\//i.test(k)||!Number.isFinite(P)||P<=0||P>2097152?null:m.blob()}).then(m=>m&&g(m,s)).catch(()=>{}).finally(()=>r.delete(s))}catch{r.delete(s)}}},stop(){t=!1,r.clear();try{l()}catch{}}}}var v=!1,G=!1,W=!0,d=B(),D=0,C,x;h.runtime.onMessage.addListener(e=>(e?.type==="detector_same_document_navigation"&&d.navigationRescans&&ae(),!1));function w(e,t,n,r,l){!(e==="dom_image_found"?d.images:e==="dom_audio_found"?d.audio:e==="dom_subtitle_found"?d.subtitles:d.videos)||!v||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&h.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:r,height:l}).catch(()=>{})}function se(e){if(!v||!d.domScanning||!d.images)return;let t=e.currentSrc||e.src;if(!t||!ne(t))return;let n=e.naturalWidth,r=e.naturalHeight;n<64||r<64||n*r<16384||w("dom_image_found",t,void 0,n,r)}var U=new WeakSet;function A(e){if(!(!v||!d.domScanning)){if(e instanceof HTMLVideoElement){if(d.videos&&w("dom_video_found",e.currentSrc||e.src),d.images&&e.poster&&w("dom_image_found",e.poster,void 0,e.videoWidth||void 0,e.videoHeight||void 0),d.videos)for(let t of Array.from(e.querySelectorAll("source")))w("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){if(!d.audio)return;w("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))w("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");if(t instanceof HTMLAudioElement?!d.audio:!d.videos)return;w(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}if(typeof HTMLTrackElement<"u"&&e instanceof HTMLTrackElement){(e.kind==="subtitles"||e.kind==="captions")&&(e.src.startsWith("blob:")?x?.track(e):w("dom_subtitle_found",e.src));return}e instanceof HTMLImageElement&&d.images&&(se(e),!e.complete&&!U.has(e)&&(U.add(e),e.addEventListener("load",()=>{U.delete(e),se(e)},{once:!0})))}}function F(){return[d.videos||d.images?"video":"",d.audio?"audio":"",d.videos||d.audio?"source":"",d.images?"img":"",d.subtitles?"track":""].filter(Boolean).join(", ")}function ae(){if(!v||!d.domScanning)return;let e=F();e&&document.querySelectorAll(e).forEach(A)}function E(e){if(G=e,e=e&&W,e!==v){if(v=e,!e){x?.stop(),x=void 0,C?.disconnect(),C=void 0,document.removeEventListener("DOMContentLoaded",H),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}d.subtitles&&(x=oe(t=>{!v||!d.subtitles||h.runtime.sendMessage({action:"blob_subtitle_found",origin:window.location.href,...t}).catch(()=>{})})),d.domScanning&&F()&&(C=new MutationObserver(t=>{if(v)for(let n of t){for(let r of n.addedNodes){if(r.nodeType!==1)continue;let l=r;A(l),l.querySelectorAll?.(F()).forEach(A)}n.type==="attributes"&&n.target.nodeType===1&&A(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",H,{once:!0}),de()):H()),Ce()}}function de(){!v||!document.documentElement||C?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster","kind","label","srclang"]})}function H(){de(),ae()}window.addEventListener("pagehide",()=>{W=!1,E(G)});window.addEventListener("pageshow",()=>{W=!0,E(G)});var I,le=!1;function Re(){if(I)return I;let e=h.runtime.connect({name:ee});return I=e,e.onMessage.addListener(t=>{I===e&&t?.update===D&&E(t.ok===!0&&le)}),e.onDisconnect.addListener(()=>{I===e&&(I=void 0,D+=1,E(!1))}),e}try{let e=h.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(!te(t))return;let n=++D,r=B(t.options);if((!t.enabled||JSON.stringify(d)!==JSON.stringify(r))&&E(!1),d=r,le=t.enabled,window.top!==window){E(t.enabled);return}try{Re().postMessage({update:n,policy:t})}catch{E(!1)}}),e.onDisconnect.addListener(()=>{D+=1,E(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!v||!d.playerProbes||!d.videos||!t||typeof t!="string"||!t.startsWith("http")||h.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));function je(){if(window.top!==window)return;let e=X(Z);if(!e)return;let t=e,n=new Set,r,l=(c,s)=>{n.delete(c),t.post({channel:"linked",type:"response",pageRequestId:c,response:s})};function b(){if(r)return r;let c=h.runtime.connect({name:z});return r=c,c.onMessage.addListener(s=>{if(r!==c||!s||typeof s!="object")return;let a=s;if(a.type==="response"&&S(a.pageRequestId)&&n.has(a.pageRequestId)){let f=a.response&&typeof a.response=="object"?a.response:{};l(a.pageRequestId,f)}else if(a.type==="event"&&S(a.sessionId)&&R.includes(a.event)){let f=a.detail&&typeof a.detail=="object"?a.detail:{};t.post({channel:"linked",type:"event",sessionId:a.sessionId,event:a.event,detail:f})}}),c.onDisconnect.addListener(()=>{if(r===c){r=void 0;for(let s of[...n])l(s,{ok:!1,error:"native_unavailable"})}}),c}function g(c,s){try{b().postMessage(c)}catch{s&&l(s,{ok:!1,error:"native_unavailable"})}}t.onMessage(c=>{if(window.top!==window||!c||typeof c!="object")return;let s=c.channel;if(s==="cast"){g({type:"cast",payload:c.payload});return}if(s!=="linked")return;let a=V(c);if(!a){let m=c.pageRequestId;S(m)&&t.post({channel:"linked",type:"response",pageRequestId:m,response:{ok:!1,error:"invalid_request"}});return}let f=navigator.userActivation&&typeof navigator.userActivation.isActive=="boolean"?navigator.userActivation.isActive:null;if(a.operation==="choose_destination"&&f===!1){t.post({channel:"linked",type:"response",pageRequestId:a.pageRequestId,response:{ok:!1,error:"user_gesture_required"}});return}n.add(a.pageRequestId),g(a.operation==="choose_destination"?{...a,userActivation:f}:a,a.pageRequestId)})}je();function Ce(){if(window.top!==window||!d.visibilityOverrides&&!(d.playerProbes&&d.videos))return;let e=document.createElement("script");e.textContent=`(function() {
      var hiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
      var visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      if (${d.visibilityOverrides}) try {
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
      var timers = ${d.playerProbes&&d.videos} ? [setTimeout(probe, 1500), setTimeout(probe, 4000)] : [];
      window.addEventListener('PlayBridgeStopDetection', function stop() {
        timers.forEach(clearTimeout);
        if (${d.visibilityOverrides}) try {
          if (hiddenDescriptor) Object.defineProperty(document, 'hidden', hiddenDescriptor);
          else delete document.hidden;
          if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
          else delete document.visibilityState;
        } catch (_) { /* page may reject restoring the original descriptor */ }
        window.removeEventListener('PlayBridgeStopDetection', stop);
      }, { once: true });
  })();`,(document.documentElement||document.head||document.body).appendChild(e),e.remove()}})();
