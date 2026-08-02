"use strict";(()=>{var l=globalThis.browser??globalThis.chrome,r=l;var p=/(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;function d(e){return/^https?:/i.test(e)&&!p.test(e)}var s=globalThis.cloneInto;r.runtime.onMessage.addListener(e=>{if(e?.type==="bridge_feedback"){let t=typeof s=="function"?s(e,window):e;window.dispatchEvent(new CustomEvent("PlayBridgeFeedback",{detail:t}))}return!1});function o(e,t,i,n,m){!t||t.startsWith("blob:")||t.startsWith("data:")||t.startsWith("http")&&r.runtime.sendMessage({action:e,url:t,origin:window.location.href,contentType:i,width:n,height:m}).catch(()=>{})}function u(e){let t=e.currentSrc||e.src;if(!t||!d(t))return;let i=e.naturalWidth||e.width||e.clientWidth,n=e.naturalHeight||e.height||e.clientHeight;i<64||n<64||i*n<16384||o("dom_image_found",t,void 0,i,n)}var c=new WeakSet;function a(e){if(e instanceof HTMLVideoElement){o("dom_video_found",e.currentSrc||e.src),e.poster&&o("dom_image_found",e.poster,void 0,e.videoWidth||e.clientWidth,e.videoHeight||e.clientHeight);for(let t of Array.from(e.querySelectorAll("source")))o("dom_video_found",t.src,t.type);return}if(e instanceof HTMLAudioElement){o("dom_audio_found",e.currentSrc||e.src);for(let t of Array.from(e.querySelectorAll("source")))o("dom_audio_found",t.src,t.type);return}if(e instanceof HTMLSourceElement){let t=e.closest("audio, video");o(t instanceof HTMLAudioElement?"dom_audio_found":"dom_video_found",e.src,e.type);return}e instanceof HTMLImageElement&&(u(e),!e.complete&&!c.has(e)&&(c.add(e),e.addEventListener("load",()=>u(e),{once:!0})))}function f(){document.querySelectorAll("video, audio, source, img").forEach(a)}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",f):f();var g=new MutationObserver(e=>{for(let t of e){for(let i of t.addedNodes){if(i.nodeType!==1)continue;let n=i;a(n),n.querySelectorAll?.("video, audio, source, img").forEach(a)}t.type==="attributes"&&t.target.nodeType===1&&a(t.target)}});g.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src","srcset","poster"]});window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!t||typeof t!="string"||!t.startsWith("http")||r.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));(function(){let t=document.createElement("script");t.textContent=`
    (function() {
      if (window.playbridge_injected) return;
      window.playbridge_injected = true;
      window.playbridge = {
        cast: function(payload) {
          window.dispatchEvent(new CustomEvent('PlayBridgeCast', { detail: payload }));
        }
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
