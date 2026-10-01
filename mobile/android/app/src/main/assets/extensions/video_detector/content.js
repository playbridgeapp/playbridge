"use strict";(()=>{var _=globalThis.browser??globalThis.chrome,r=_;function f(e){return{videos:e?.videos!==!1,images:e?.images!==!1,audio:e?.audio!==!1,subtitles:e?.subtitles!==!1,domScanning:e?.domScanning!==!1,networkDetection:e?.networkDetection!==!1,responseScanning:e?.responseScanning!==!1,navigationRescans:e?.navigationRescans!==!1,playerProbes:e?.playerProbes!==!1,visibilityOverrides:e?.visibilityOverrides!==!1,detectInBridgedSites:e?.detectInBridgedSites===!0}}var S=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function w(e){return/^https?:/i.test(e)&&!S.test(e)}var s=!1,i=f(),u=0,l,b=globalThis.cloneInto;r.runtime.onMessage.addListener(e=>{if(e?.type==="bridge_feedback"){let t=typeof b=="function"?b(e,window):e;window.dispatchEvent(new CustomEvent("PlayBridgeFeedback",{detail:t}))}else e?.type==="detector_same_document_navigation"&&i.navigationRescans&&y();return!1});function a(e,t,n,o,d){!(e==="dom_image_found"?i.images:e==="dom_audio_found"?i.audio:e==="dom_subtitle_found"?i.subtitles:i.videos)||!s||!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&r.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:o,height:d}).catch(()=>{})}function h(e){if(!s||!i.domScanning||!i.images)return;let t=e.currentSrc||e.src;if(!t||!w(t))return;let n=e.naturalWidth,o=e.naturalHeight;n<64||o<64||n*o<16384||a("dom_image_found",t,void 0,n,o)}var m=new WeakSet;function p(e){if(!(!s||!i.domScanning)){if(e instanceof HTMLVideoElement){if(i.videos&&a("dom_video_found",e.currentSrc||e.src),i.images&&e.poster&&a("dom_image_found",e.poster,void 0,e.videoWidth||void 0,e.videoHeight||void 0),i.videos)for(let t of Array.from(e.querySelectorAll("source")))a("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){if(!i.audio)return;a("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))a("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");if(t instanceof HTMLAudioElement?!i.audio:!i.videos)return;a(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}if(typeof HTMLTrackElement<"u"&&e instanceof HTMLTrackElement){(e.kind==="subtitles"||e.kind==="captions")&&a("dom_subtitle_found",e.src);return}e instanceof HTMLImageElement&&i.images&&(h(e),!e.complete&&!m.has(e)&&(m.add(e),e.addEventListener("load",()=>{m.delete(e),h(e)},{once:!0})))}}function v(){return[i.videos||i.images?"video":"",i.audio?"audio":"",i.videos||i.audio?"source":"",i.images?"img":"",i.subtitles?"track":""].filter(Boolean).join(", ")}function y(){if(!s||!i.domScanning)return;let e=v();e&&document.querySelectorAll(e).forEach(p)}function c(e){if(e!==s){if(s=e,!e){l?.disconnect(),l=void 0,document.removeEventListener("DOMContentLoaded",g),window.dispatchEvent(new Event("PlayBridgeStopDetection"));return}i.domScanning&&v()&&(l=new MutationObserver(t=>{if(s)for(let n of t){for(let o of n.addedNodes){if(o.nodeType!==1)continue;let d=o;p(d),d.querySelectorAll?.(v()).forEach(p)}n.type==="attributes"&&n.target.nodeType===1&&p(n.target)}}),document.readyState==="loading"?(document.addEventListener("DOMContentLoaded",g,{once:!0}),E()):g()),I()}}function E(){!s||!document.documentElement||l?.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]})}function g(){E(),y()}try{let e=r.runtime.connectNative("detectorPolicy");e.onMessage.addListener(t=>{if(t?.type!=="detection_policy")return;let n=++u,o=f(t.options);(!t.enabled||JSON.stringify(i)!==JSON.stringify(o))&&c(!1),i=o,r.runtime.sendMessage({action:"detector_policy",policy:t}).then(()=>{n===u&&c(t.enabled===!0)}).catch(()=>{n===u&&c(!1)})}),e.onDisconnect.addListener(()=>{u+=1,c(!1)})}catch{}window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!s||!i.playerProbes||!i.videos||!t||typeof t!="string"||!t.startsWith("http")||r.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeCast",(e=>{window.top===window&&r.runtime.sendMessage({action:"page_cast_requested",payload:e.detail,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeLinkedRequest",(e=>{if(window.top!==window)return;let t=e.detail;r.runtime.sendMessage({action:"page_linked_cast",...t}).then(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:n})}))}).catch(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:{ok:!1,error:"native_unavailable",message:n?.message}})}))})}));r.runtime.onMessage.addListener(e=>{window.top!==window||e?.type!=="linked_cast_event"||window.dispatchEvent(new CustomEvent("PlayBridgeLinkedEventJson",{detail:JSON.stringify(e.event??{})}))});(function(){if(window.top!==window)return;let t=document.createElement("script");t.textContent=`
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
  `,(document.documentElement||document.head||document.body).appendChild(t),t.remove()})();function I(){if(window.top!==window||!i.visibilityOverrides&&!(i.playerProbes&&i.videos))return;let e=document.createElement("script");e.textContent=`(function() {
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
