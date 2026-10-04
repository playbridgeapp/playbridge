"use strict";(()=>{var _=`
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
  `;var j=globalThis.browser??globalThis.chrome,a=j;function b(e){return{videos:e?.videos!==!1,images:e?.images!==!1,audio:e?.audio!==!1,subtitles:e?.subtitles!==!1,domScanning:e?.domScanning!==!1,networkDetection:e?.networkDetection!==!1,responseScanning:e?.responseScanning!==!1,navigationRescans:e?.navigationRescans!==!1,playerProbes:e?.playerProbes!==!1,visibilityOverrides:e?.visibilityOverrides!==!1,detectInBridgedSites:e?.detectInBridgedSites===!0}}var D=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function I(e){return/^https?:/i.test(e)&&!D.test(e)}var k=16*1024,R=new Set(["status","resolve","manage","cancel"]);function T(e){if(!e||typeof e!="object"||Array.isArray(e))return!1;let t=e;if(Object.keys(t).some(o=>!["requestId","operation","payload"].includes(o))||typeof t.operation!="string"||!R.has(t.operation))return!1;if(t.operation==="cancel")return Object.keys(t).length===1;if(typeof t.requestId!="string"||t.requestId.length<1||t.requestId.length>128||/[\x00-\x1f\x7f]/.test(t.requestId)||!t.payload||typeof t.payload!="object"||Array.isArray(t.payload))return!1;let n=t.payload;if(t.operation!=="resolve")return Object.keys(n).length===0;if(Object.keys(n).some(o=>!["repoUrl","scraperIds","tmdbId","mediaType","season","episode"].includes(o))||typeof n.repoUrl!="string"||n.repoUrl.length>2048||typeof n.tmdbId!="string"||!/^[1-9][0-9]{0,9}$/.test(n.tmdbId)||!["movie","tv"].includes(String(n.mediaType)))return!1;let r;try{r=new URL(n.repoUrl)}catch{return!1}return r.protocol!=="https:"||r.username||r.password||r.hash||!Array.isArray(n.scraperIds)||n.scraperIds.length<1||n.scraperIds.length>32||n.scraperIds.some(o=>typeof o!="string"||!o.trim()||o.length>128||/[\x00-\x1f\x7f]/.test(o))||new Set(n.scraperIds).size!==n.scraperIds.length?!1:n.mediaType==="movie"?n.season==null&&n.episode==null:Number.isInteger(n.season)&&Number(n.season)>=0&&Number(n.season)<=1e4&&Number.isInteger(n.episode)&&Number(n.episode)>=1&&Number(n.episode)<=1e4}var x=`
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
      if (new TextEncoder().encode(json).length > ${k}) { reject(new Error('Device plugin request too large')); return; }
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
`;function S(){if(window.top!==window)return;let e,t=!1,n;function r(s){window.dispatchEvent(new CustomEvent("PlayBridgePluginsResponseJson",{detail:JSON.stringify(s)}))}window.addEventListener("PlayBridgePluginsRequestJson",(s=>{if(window.top!==window||typeof s.detail!="string"||new TextEncoder().encode(s.detail).length>k)return;let c;try{let w=JSON.parse(s.detail);if(!T(w)){let l=w?.requestId;typeof l=="string"&&l.length>=1&&l.length<=128&&!/[\x00-\x1f\x7f]/.test(l)&&r({type:"plugin_response",requestId:l,ok:!1,error:"invalid_request"});return}c=w}catch{return}if(c.operation==="manage"&&navigator.userActivation?.isActive!==!0){r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"user_gesture_required"});return}if(!e||t){r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"native_plugins_unavailable"});return}try{e.postMessage(c)}catch{r({type:"plugin_response",requestId:c.requestId,ok:!1,error:"native_plugins_unavailable"})}}));function o(){t=!1,n=void 0;try{e=a.runtime.connectNative("plugins"),e.onMessage.addListener(s=>{s?.type==="plugin_capabilities"&&(n=s),r(s)}),e.onDisconnect.addListener(()=>{t=!0,r({type:"plugin_disconnected"})})}catch{t=!0,r({type:"plugin_disconnected"})}}o();let p=document.createElement("script");p.textContent=x,(document.documentElement||document.head||document.body).appendChild(p),p.remove(),n&&r(n),window.addEventListener("hashchange",()=>{if(!t)try{e?.postMessage({operation:"cancel"})}catch{}}),window.addEventListener("pagehide",()=>{t=!0;try{e?.disconnect()}catch{}r({type:"plugin_disconnected"})}),window.addEventListener("pageshow",s=>{s.persisted&&t&&o()})}var d=!1,i=b(),g=0,m,P=globalThis.cloneInto;a.runtime.onMessage.addListener(e=>{if(e?.type==="bridge_feedback"){let t=typeof P=="function"?P(e,window):e;window.dispatchEvent(new CustomEvent("PlayBridgeFeedback",{detail:t}))}else e?.type==="detector_same_document_navigation"&&i.navigationRescans&&q();return!1});function u(e,t,n,r,o){!(e==="dom_image_found"?i.images:e==="dom_audio_found"?i.audio:e==="dom_subtitle_found"?i.subtitles:i.videos)||!d||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&a.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:r,height:o}).catch(()=>{})}function O(e){if(!d||!i.domScanning||!i.images)return;let t=e.currentSrc||e.src;if(!t||!I(t))return;let n=e.naturalWidth,r=e.naturalHeight;n<64||r<64||n*r<16384||u("dom_image_found",t,void 0,n,r)}var y=new WeakSet;function v(e){if(!(!d||!i.domScanning)){if(e instanceof HTMLVideoElement){if(i.videos&&u("dom_video_found",e.currentSrc||e.src),i.images&&e.poster&&u("dom_image_found",e.poster,void 0,e.videoWidth||void 0,e.videoHeight||void 0),i.videos)for(let t of Array.from(e.querySelectorAll("source")))u("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){if(!i.audio)return;u("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))u("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");if(t instanceof HTMLAudioElement?!i.audio:!i.videos)return;u(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}if(typeof HTMLTrackElement<"u"&&e instanceof HTMLTrackElement){(e.kind==="subtitles"||e.kind==="captions")&&u("dom_subtitle_found",e.src);return}e instanceof HTMLImageElement&&i.images&&(O(e),!e.complete&&!y.has(e)&&(y.add(e),e.addEventListener("load",()=>{y.delete(e),O(e)},{once:!0})))}}function E(){return[i.videos||i.images?"video":"",i.audio?"audio":"",i.videos||i.audio?"source":"",i.images?"img":"",i.subtitles?"track":""].filter(Boolean).join(", ")}function q(){if(!d||!i.domScanning)return;let e=E();e&&document.querySelectorAll(e).forEach(v)}function f(e){if(e!==d){if(d=e,!e){m?.disconnect(),m=void 0,document.removeEventListener("DOMContentLoaded",h),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}i.domScanning&&E()&&(m=new MutationObserver(t=>{if(d)for(let n of t){for(let r of n.addedNodes){if(r.nodeType!==1)continue;let o=r;v(o),o.querySelectorAll?.(E()).forEach(v)}n.type==="attributes"&&n.target.nodeType===1&&v(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",h,{once:!0}),L()):h()),M()}}function L(){!d||!document.documentElement||m?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]})}function h(){L(),q()}try{let e=a.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(t?.type!=="detection_policy")return;let n=++g,r=b(t.options);(!t.enabled||JSON.stringify(i)!==JSON.stringify(r))&&f(!1),i=r,a.runtime.sendMessage({action:"detector_policy",policy:t}).then(()=>{n===g&&f(t.enabled===!0)}).catch(()=>{n===g&&f(!1)})}),e.onDisconnect.addListener(()=>{g+=1,f(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!d||!i.playerProbes||!i.videos||!t||typeof t!="string"||!t.startsWith("http")||a.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeCast",(e=>{window.top===window&&a.runtime.sendMessage({action:"page_cast_requested",payload:e.detail,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeLinkedRequest",(e=>{if(window.top!==window)return;let t=e.detail;if(t?.operation==="choose_destination"&&navigator.userActivation&&!navigator.userActivation.isActive){window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t.pageRequestId,response:{ok:!1,error:"user_gesture_required"}})}));return}a.runtime.sendMessage({action:"page_linked_cast",...t}).then(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:n})}))}).catch(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:{ok:!1,error:"native_unavailable",message:n?.message}})}))})}));a.runtime.onMessage.addListener(e=>{window.top!==window||e?.type!=="linked_cast_event"||window.dispatchEvent(new CustomEvent("PlayBridgeLinkedEventJson",{detail:JSON.stringify(e.event??{})}))});(function(){if(window.top!==window)return;let t=document.createElement("script");t.textContent=_,(document.documentElement||document.head||document.body).appendChild(t),t.remove()})();S();function M(){if(window.top!==window||!i.visibilityOverrides&&!(i.playerProbes&&i.videos))return;let e=document.createElement("script");e.textContent=`(function() {
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
