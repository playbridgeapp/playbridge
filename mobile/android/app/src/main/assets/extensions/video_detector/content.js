"use strict";(()=>{var h=globalThis.browser??globalThis.chrome,i=h;var b=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function m(e){return/^https?:/i.test(e)&&!b.test(e)}var r=!1,l=0,u,f=globalThis.cloneInto;i.runtime.onMessage.addListener(e=>{if(e?.type==="bridge_feedback"){let t=typeof f=="function"?f(e,window):e;window.dispatchEvent(new CustomEvent("PlayBridgeFeedback",{detail:t}))}else e?.type==="detector_same_document_navigation"&&v();return!1});function s(e,t,n,o,a){!r||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&i.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:o,height:a}).catch(()=>{})}function g(e){let t=e.currentSrc||e.src;if(!t||!m(t))return;let n=e.naturalWidth||e.width||e.clientWidth,o=e.naturalHeight||e.height||e.clientHeight;n<64||o<64||n*o<16384||s("dom_image_found",t,void 0,n,o)}var w=new WeakSet;function c(e){if(r){if(e instanceof HTMLVideoElement){s("dom_video_found",e.currentSrc||e.src),e.poster&&s("dom_image_found",e.poster,void 0,e.videoWidth||e.clientWidth,e.videoHeight||e.clientHeight);for(let t of Array.from(e.querySelectorAll("source")))s("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){s("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))s("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");s(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}e instanceof HTMLImageElement&&(g(e),!e.complete&&!w.has(e)&&(w.add(e),e.addEventListener("load",()=>g(e),{once:!0})))}}function v(){r&&document.querySelectorAll("video, audio, source, img").forEach(c)}function d(e){if(e!==r){if(r=e,!e){u?.disconnect(),u=void 0,document.removeEventListener("DOMContentLoaded",p),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}u=new MutationObserver(t=>{if(r)for(let n of t){for(let o of n.addedNodes){if(o.nodeType!==1)continue;let a=o;c(a),a.querySelectorAll?.("video, audio, source, img").forEach(c)}n.type==="attributes"&&n.target.nodeType===1&&c(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",p,{once:!0}),y()):p(),E()}}function y(){!r||!document.documentElement||u?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]})}function p(){y(),v()}try{let e=i.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(t?.type!=="detection_policy")return;let n=++l;t.enabled||d(!1),i.runtime.sendMessage({action:"detector_policy",policy:t}).then(()=>{n===l&&d(t.enabled===!0)}).catch(()=>d(!1))}),e.onDisconnect.addListener(()=>{l+=1,d(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!r||!t||typeof t!="string"||!t.startsWith("http")||i.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeCast",(e=>{window.top===window&&i.runtime.sendMessage({action:"page_cast_requested",payload:e.detail,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeLinkedRequest",(e=>{if(window.top!==window)return;let t=e.detail;i.runtime.sendMessage({action:"page_linked_cast",...t}).then(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:n})}))}).catch(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:{ok:!1,error:"native_unavailable",message:n?.message}})}))})}));i.runtime.onMessage.addListener(e=>{window.top!==window||e?.type!=="linked_cast_event"||window.dispatchEvent(new CustomEvent("PlayBridgeLinkedEventJson",{detail:JSON.stringify(e.event??{})}))});(function(){if(window.top!==window)return;let t=document.createElement("script");t.textContent=`
    (function() {
      if (window.playbridge_injected_version === 4) return;
      window.playbridge_injected = true;
      window.playbridge_injected_version = 4;
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
          }, operation === 'open' ? 660000 : 45000);
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
        privateNetworkOriginPermission: 1
      });
      window.playbridge.linkCast = function(payload) {
        return request('open', null, payload).then(function(response) {
          var session = LinkedCastSession(response.sessionId);
          sessions.set(response.sessionId, session);
          return session;
        });
      };
    })();
  `,(document.documentElement||document.head||document.body).appendChild(t),t.remove()})();function E(){if(window.top!==window)return;let e=document.createElement("script");e.textContent=`(function() {
      var hiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
      var visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      try {
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
      var timers = [setTimeout(probe, 1500), setTimeout(probe, 4000)];
      window.addEventListener('PlayBridgeStopDetection', function stop() {
        timers.forEach(clearTimeout);
        try {
          if (hiddenDescriptor) Object.defineProperty(document, 'hidden', hiddenDescriptor);
          else delete document.hidden;
          if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
          else delete document.visibilityState;
        } catch (_) {}
        window.removeEventListener('PlayBridgeStopDetection', stop);
      }, { once: true });
  })();`,(document.documentElement||document.head||document.body).appendChild(e),e.remove()}})();
