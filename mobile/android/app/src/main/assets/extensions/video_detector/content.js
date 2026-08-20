"use strict";(()=>{var m=globalThis.browser??globalThis.chrome,o=m;var g=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function d(e){return/^https?:/i.test(e)&&!g.test(e)}var u=globalThis.cloneInto;o.runtime.onMessage.addListener(e=>{if(e?.type==="bridge_feedback"){let t=typeof u=="function"?u(e,window):e;window.dispatchEvent(new CustomEvent("PlayBridgeFeedback",{detail:t}))}else e?.type==="detector_same_document_navigation"&&a();return!1});function r(e,t,n,i,p){!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&o.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:n,width:i,height:p}).catch(()=>{})}function c(e){let t=e.currentSrc||e.src;if(!t||!d(t))return;let n=e.naturalWidth||e.width||e.clientWidth,i=e.naturalHeight||e.height||e.clientHeight;n<64||i<64||n*i<16384||r("dom_image_found",t,void 0,n,i)}var l=new WeakSet;function s(e){if(e instanceof HTMLVideoElement){r("dom_video_found",e.currentSrc||e.src),e.poster&&r("dom_image_found",e.poster,void 0,e.videoWidth||e.clientWidth,e.videoHeight||e.clientHeight);for(let t of Array.from(e.querySelectorAll("source")))r("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){r("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))r("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");r(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}e instanceof HTMLImageElement&&(c(e),!e.complete&&!l.has(e)&&(l.add(e),e.addEventListener("load",()=>c(e),{once:!0})))}function a(){document.querySelectorAll("video, audio, source, img").forEach(s)}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",a):a();var f=new MutationObserver(e=>{for(let t of e){for(let n of t.addedNodes){if(n.nodeType!==1)continue;let i=n;s(i),i.querySelectorAll?.("video, audio, source, img").forEach(s)}t.type==="attributes"&&t.target.nodeType===1&&s(t.target)}});f.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]});window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!t||typeof t!="string"||!t.startsWith("http")||o.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeCast",(e=>{window.top===window&&o.runtime.sendMessage({action:"page_cast_requested",payload:e.detail,origin:window.location.href}).catch(()=>{})}));window.addEventListener("PlayBridgeLinkedRequest",(e=>{if(window.top!==window)return;let t=e.detail;o.runtime.sendMessage({action:"page_linked_cast",...t}).then(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:n})}))}).catch(n=>{window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson",{detail:JSON.stringify({pageRequestId:t?.pageRequestId,response:{ok:!1,error:"native_unavailable",message:n?.message}})}))})}));o.runtime.onMessage.addListener(e=>{window.top!==window||e?.type!=="linked_cast_event"||window.dispatchEvent(new CustomEvent("PlayBridgeLinkedEventJson",{detail:JSON.stringify(e.event??{})}))});(function(){if(window.top!==window)return;let t=document.createElement("script");t.textContent=`
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
      try {
        Object.defineProperty(document, 'hidden', { get: function() { return false; } });
        Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
      } catch (e) {}
      function report(url) {
        if (!url || typeof url !== 'string' || !url.startsWith('http')) return;
        window.dispatchEvent(new CustomEvent('PlayBridgeMediaFound', { detail: { url: url } }));
      }
      function probe() {
        try {
          if (window.jwplayer) {
            var players = typeof window.jwplayer === 'function' ? [] : [];
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
      setTimeout(probe, 1500);
      setTimeout(probe, 4000);
    })();
  `,(document.documentElement||document.head||document.body).appendChild(t),t.remove()})();})();
