"use strict";(()=>{var s=globalThis.browser??globalThis.chrome,n=s;var a=globalThis.cloneInto;n.runtime.onMessage.addListener(e=>{if(e?.type==="bridge_feedback"){let t=typeof a=="function"?a(e,window):e;window.dispatchEvent(new CustomEvent("PlayBridgeFeedback",{detail:t}))}return!1});function l(e){!e||e.startsWith("blob:")||e.startsWith("data:")||e.startsWith("http")&&n.runtime.sendMessage({action:"dom_video_found",url:e,origin:window.location.href}).catch(()=>{})}function o(e){(e.tagName==="VIDEO"||e.tagName==="SOURCE")&&l(e.src)}function d(){document.querySelectorAll("video, source").forEach(o)}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",d):d();var c=new MutationObserver(e=>{for(let t of e){for(let i of t.addedNodes){if(i.nodeType!==1)continue;let r=i;o(r),r.querySelectorAll?.("video, source").forEach(o)}t.type==="attributes"&&t.target.nodeType===1&&o(t.target)}});c.observe(document.documentElement,{childList:!0,subtree:!0,attributes:!0,attributeFilter:["src"]});window.addEventListener("PlayBridgeMediaFound",(e=>{let t=e.detail&&e.detail.url;!t||typeof t!="string"||!t.startsWith("http")||n.runtime.sendMessage({action:"player_video_found",url:t,origin:window.location.href}).catch(()=>{})}));(function(){let t=document.createElement("script");t.textContent=`
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
