"use strict";(()=>{var P=`
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
        playback: 1,
        localPlaybackOrientation: 1
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
  `;var C=globalThis.browser??globalThis.chrome,a=C;function h(e){return{videos:e?.videos!==!1,images:e?.images!==!1,audio:e?.audio!==!1,subtitles:e?.subtitles!==!1,domScanning:e?.domScanning!==!1,networkDetection:e?.networkDetection!==!1,responseScanning:e?.responseScanning!==!1,navigationRescans:e?.navigationRescans!==!1,playerProbes:e?.playerProbes!==!1,visibilityOverrides:e?.visibilityOverrides!==!1,detectInBridgedSites:e?.detectInBridgedSites===!0}}var k="playbridge-detection-policy";function S(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;return t.type==="detection_policy"&&Number.isSafeInteger(t.revision)&&t.revision>=0&&typeof t.enabled=="boolean"&&typeof t.browserEnabled=="boolean"&&Array.isArray(t.bridgedAppOrigins)&&t.bridgedAppOrigins.every(n=>typeof n=="string")&&(t.options==null||typeof t.options=="object"&&!Array.isArray(t.options)&&Object.values(t.options).every(n=>typeof n=="boolean"))}var N=new Set(["open","play","replace","append","jump","supply","unlink","ping","destination","choose_destination"]),M=new Set(["pageRequestId","operation","sessionId","payload"]);function m(e){return typeof e=="string"&&e.length>0&&e.length<=128&&!/[\x00-\x1f\x7f]/.test(e)}function O(e){try{if(!e||typeof e!="object"||Array.isArray(e))return null;let t=e;if(Object.keys(t).some(s=>!M.has(s)))return null;let{pageRequestId:n,operation:r,sessionId:o,payload:d}=t;return!m(n)||typeof r!="string"||!N.has(r)||o!=null&&!m(o)||!d||typeof d!="object"||Array.isArray(d)?null:{action:"page_linked_cast",pageRequestId:n,operation:r,sessionId:o??null,payload:d}}catch{return null}}var B=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function q(e){return/^https?:/i.test(e)&&!B.test(e)}var L=16*1024,J=new Set(["status","resolve","manage","cancel"]);function U(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;if(Object.keys(t).some(o=>!["requestId","operation","payload"].includes(o))||typeof t.operation!="string"||!J.has(t.operation))return!1;if(t.operation==="cancel")return Object.keys(t).length===1;if(typeof t.requestId!="string"||t.requestId.length<1||t.requestId.length>128||/[\x00-\x1f\x7f]/.test(t.requestId)||!t.payload||typeof t.payload!="object"||Array.isArray(t.payload))return!1;let n=t.payload;if(t.operation!=="resolve")return Object.keys(n).length===0;if(Object.keys(n).some(o=>!["repoUrl","scraperIds","tmdbId","mediaType","season","episode"].includes(o))||typeof n.repoUrl!="string"||n.repoUrl.length>2048||typeof n.tmdbId!="string"||!/^[1-9][0-9]{0,9}$/.test(n.tmdbId)||!["movie","tv"].includes(String(n.mediaType)))return!1;let r;try{r=new URL(n.repoUrl)}catch{return!1}return r.protocol!=="https:"||r.username||r.password||r.hash||!Array.isArray(n.scraperIds)||n.scraperIds.length<1||n.scraperIds.length>32||n.scraperIds.some(o=>typeof o!="string"||!o.trim()||o.length>128||/[\x00-\x1f\x7f]/.test(o))||new Set(n.scraperIds).size!==n.scraperIds.length?!1:n.mediaType==="movie"?n.season==null&&n.episode==null:Number.isInteger(n.season)&&Number(n.season)>=0&&Number(n.season)<=1e4&&Number.isInteger(n.episode)&&Number(n.episode)>=1&&Number(n.episode)<=1e4}var H=`
(function() {
  var bridge = window.playbridge = window.playbridge || {};
  if (bridge.plugins) return;
  var pending = new Map();
  bridge.capabilities = Object.assign({}, bridge.capabilities, { nativePlugins: 0 });
  function invoke(operation, payload) {
    return new Promise(function(resolve, reject) {
      if (pending.size >= 4) { reject(new Error('Too many pending device plugin requests')); return; }
      var id = crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random();
      var json;
      try { json = JSON.stringify({ requestId: id, operation: operation, payload: payload || {} }); }
      catch (_) { reject(new Error('Invalid device plugin request')); return; }
      if (new TextEncoder().encode(json).length > ${L}) { reject(new Error('Device plugin request too large')); return; }
      var timer = setTimeout(function() {
        pending.delete(id);
        reject(new Error('Device plugin request timed out'));
      }, 65000);
      pending.set(id, { operation: operation, resolve: resolve, reject: reject, timer: timer });
      window.dispatchEvent(new CustomEvent('PlayBridgePluginsRequestJson', { detail: json }));
    });
  }
  window.addEventListener('PlayBridgePluginsResponseJson', function(event) {
    var message;
    try { message = JSON.parse(event.detail); } catch (_) { return; }
    if (message.type === 'plugin_capabilities') {
      bridge.capabilities.nativePlugins = message.available === true ? 1 : 0;
      window.dispatchEvent(new Event('PlayBridgePluginsReady'));
      return;
    }
    if (message.type === 'plugin_disconnected') {
      bridge.capabilities.nativePlugins = 0;
      pending.forEach(function(waiter) { clearTimeout(waiter.timer); waiter.reject(new Error('Device plugins disconnected')); });
      pending.clear();
      return;
    }
    var waiter = pending.get(message.requestId);
    if (!waiter) return;
    pending.delete(message.requestId);
    clearTimeout(waiter.timer);
    if (message.ok === true) {
      if (waiter.operation === 'status') bridge.capabilities.nativePlugins = message.data && message.data.available === true ? 1 : 0;
      waiter.resolve(message.data || {});
    } else {
      var error = new Error(message.error || 'Device plugin request failed');
      error.code = message.error || 'native_plugins_unavailable';
      waiter.reject(error);
    }
  });
  bridge.plugins = {
    status: function() { return invoke('status', {}); },
    resolve: function(request) { return invoke('resolve', request); },
    manage: function() { return invoke('manage', {}); },
    cancel: function() {
      pending.forEach(function(waiter, id) {
        if (waiter.operation !== 'resolve') return;
        pending.delete(id);
        clearTimeout(waiter.timer);
        waiter.reject(new Error('Device plugin resolution cancelled'));
      });
      window.dispatchEvent(new CustomEvent('PlayBridgePluginsRequestJson', { detail: JSON.stringify({ operation: 'cancel' }) }));
    }
  };
})();
`;function R(){if(window.top!==window)return;let e,t=!1,n;function r(s){window.dispatchEvent(new CustomEvent("PlayBridgePluginsResponseJson",{detail:JSON.stringify(s)}))}window.addEventListener("PlayBridgePluginsRequestJson",(s=>{if(window.top!==window||typeof s.detail!="string"||new TextEncoder().encode(s.detail).length>L)return;let c;try{let b=JSON.parse(s.detail);if(!U(b)){let f=b?.requestId;typeof f=="string"&&f.length>=1&&f.length<=128&&!/[\x00-\x1f\x7f]/.test(f)&&r({type:"plugin_response",requestId:f,ok:!1,error:"invalid_request"});return}c=b}catch{return}if(c.operation==="manage"&&navigator.userActivation?.isActive!==!0){r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"user_gesture_required"});return}if(!e||t){r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"native_plugins_unavailable"});return}try{e.postMessage(c)}catch{r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"native_plugins_unavailable"})}}));function o(){t=!1,n=void 0;try{e=a.runtime.connectNative("plugins"),e.onMessage.addListener(s=>{s?.type==="plugin_capabilities"&&(n=s),r(s)}),e.onDisconnect.addListener(()=>{t=!0,r({type:"plugin_disconnected"})})}catch{t=!0,r({type:"plugin_disconnected"})}}o();let d=document.createElement("script");d.textContent=H,(document.documentElement||document.head||document.body).appendChild(d),d.remove(),n&&r(n),window.addEventListener("hashchange",()=>{if(!t)try{e?.postMessage({operation:"cancel"})}catch{}}),window.addEventListener("pagehide",()=>{t=!0;try{e?.disconnect()}catch{}r({type:"plugin_disconnected"})}),window.addEventListener("pageshow",s=>{s.persisted&&t&&o()})}var u=!1,i=h(),y=0,w,j=globalThis.cloneInto;a.runtime.onMessage.addListener(e=>{if(e?.type==="bridge_feedback"){let t=typeof j=="function"?j(e,window):e;window.dispatchEvent(new CustomEvent("PlayBridgeFeedback",{detail:t}))}else e?.type==="detector_same_document_navigation"&&i.navigationRescans&&T();return!1});function l(e,t,n,r,o){!(e==="dom_image_found"?i.images:e==="dom_audio_found"?i.audio:e==="dom_subtitle_found"?i.subtitles:i.videos)||!u||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&a.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:r,height:o}).catch(()=>{})}function D(e){if(!u||!i.domScanning||!i.images)return;let t=e.currentSrc||e.src;if(!t||!q(t))return;let n=e.naturalWidth,r=e.naturalHeight;n<64||r<64||n*r<16384||l("dom_image_found",t,void 0,n,r)}var E=new WeakSet;function v(e){if(!(!u||!i.domScanning)){if(e instanceof HTMLVideoElement){if(i.videos&&l("dom_video_found",e.currentSrc||e.src),i.images&&e.poster&&l("dom_image_found",e.poster,void 0,e.videoWidth||void 0,e.videoHeight||void 0),i.videos)for(let t of Array.from(e.querySelectorAll("source")))l("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){if(!i.audio)return;l("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))l("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");if(t instanceof HTMLAudioElement?!i.audio:!i.videos)return;l(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}if(typeof HTMLTrackElement<"u"&&e instanceof HTMLTrackElement){(e.kind==="subtitles"||e.kind==="captions")&&l("dom_subtitle_found",e.src);return}e instanceof HTMLImageElement&&i.images&&(D(e),!e.complete&&!E.has(e)&&(E.add(e),e.addEventListener("load",()=>{E.delete(e),D(e)},{once:!0})))}}function _(){return[i.videos||i.images?"video":"",i.audio?"audio":"",i.videos||i.audio?"source":"",i.images?"img":"",i.subtitles?"track":""].filter(Boolean).join(", ")}function T(){if(!u||!i.domScanning)return;let e=_();e&&document.querySelectorAll(e).forEach(v)}function g(e){if(e!==u){if(u=e,!e){w?.disconnect(),w=void 0,document.removeEventListener("DOMContentLoaded",I),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}i.domScanning&&_()&&(w=new MutationObserver(t=>{if(u)for(let n of t){for(let r of n.addedNodes){if(r.nodeType!==1)continue;let o=r;v(o),o.querySelectorAll?.(_()).forEach(v)}n.type==="attributes"&&n.target.nodeType===1&&v(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",I,{once:!0}),A()):I()),G()}}function A(){!u||!document.documentElement||w?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]})}function I(){A(),T()}var p,x=!1;function W(){if(p)return p;let e=a.runtime.connect({name:k});return p=e,e.onMessage.addListener(t=>{p===e&&t?.update===y&&g(t.ok===!0&&x)}),e.onDisconnect.addListener(()=>{p===e&&(p=void 0,y+=1,g(!1))}),e}try{let e=a.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(!S(t))return;let n=++y,r=h(t.options);if((!t.enabled||JSON.stringify(i)!==JSON.stringify(r))&&g(!1),i=r,x=t.enabled,window.top!==window){g(t.enabled);return}try{W().postMessage({update:n,policy:t})}catch{g(!1)}}),e.onDisconnect.addListener(()=>{y+=1,g(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!u||!i.playerProbes||!i.videos||!t||typeof t!="string"||!t.startsWith("http")||a.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeCast",(e=>{window.top===window&&a.runtime.sendMessage({action:"page_cast_requested",payload:e.detail,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeLinkedRequest",(e=>{if(window.top!==window)return;let t=O(e.detail);if(!t){let n=e.detail?.pageRequestId;m(n)&&window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:n,response:{ok:!1,error:"invalid_request"}})}));return}if(t.operation==="choose_destination"&&navigator.userActivation&&!navigator.userActivation.isActive){window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t.pageRequestId,response:{ok:!1,error:"user_gesture_required"}})}));return}a.runtime.sendMessage(t).then(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:n})}))}).catch(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:{ok:!1,error:"native_unavailable",message:n?.message}})}))})}));a.runtime.onMessage.addListener(e=>{window.top!==window||e?.type!=="linked_cast_event"||window.dispatchEvent(new CustomEvent("PlayBridgeLinkedEventJson",{detail:JSON.stringify(e.event??{})}))});(function(){if(window.top!==window)return;let t=document.createElement("script");t.textContent=P,(document.documentElement||document.head||document.body).appendChild(t),t.remove()})();R();function G(){if(window.top!==window||!i.visibilityOverrides&&!(i.playerProbes&&i.videos))return;let e=document.createElement("script");e.textContent=`(function() {
      var hiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
      var visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      if (${i.visibilityOverrides}) try {
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
      var timers = ${i.playerProbes&&i.videos} ? [setTimeout(probe, 1500), setTimeout(probe, 4000)] : [];
      window.addEventListener('PlayBridgeStopDetection', function stop() {
        timers.forEach(clearTimeout);
        if (${i.visibilityOverrides}) try {
          if (hiddenDescriptor) Object.defineProperty(document, 'hidden', hiddenDescriptor);
          else delete document.hidden;
          if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
          else delete document.visibilityState;
        } catch (_) {}
        window.removeEventListener('PlayBridgeStopDetection', stop);
      }, { once: true });
  })();`,(document.documentElement||document.head||document.body).appendChild(e),e.remove()}})();
