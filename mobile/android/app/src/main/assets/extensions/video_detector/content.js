// Content script - runs in the context of web pages
// Video detections are reported by the background script directly to the native
// app via native messaging; this script only handles DOM/player-level detection
// and the window.playbridge cast bridge.

// Listen for messages from background script
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === 'bridge_feedback') {
        // cloneInto exports the object into the page compartment — without it,
        // Firefox's Xray wrappers hide `detail` from page-world listeners.
        const detail = typeof cloneInto === 'function'
            ? cloneInto(message, window)
            : message;
        window.dispatchEvent(new CustomEvent('PlayBridgeFeedback', { detail }));
    }
    return false;
});

// ── Video element observer ────────────────────────────────────────────────────
// Catches <video src="..."> set in HTML or via JS assignment.
// webRequest covers most cases, but scanning the DOM ensures we don't miss
// elements whose network load started before the extension listener was ready.

function reportVideoSrc(src) {
    if (!src || src.startsWith('blob:') || src.startsWith('data:') || !src.startsWith('http')) return;
    browser.runtime.sendMessage({
        action: 'dom_video_found',
        url: src,
        origin: window.location.href
    }).catch(() => {});
}

function scanElement(el) {
    if (el.tagName === 'VIDEO' || el.tagName === 'SOURCE') reportVideoSrc(el.src);
}

function scanAll() {
    document.querySelectorAll('video, source').forEach(scanElement);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scanAll);
} else {
    scanAll();
}

const videoObserver = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
            if (node.nodeType !== 1) continue;
            scanElement(node);
            node.querySelectorAll?.('video, source').forEach(scanElement);
        }
        if (mutation.type === 'attributes' && mutation.target.nodeType === 1) {
            scanElement(mutation.target);
        }
    }
});

videoObserver.observe(document.documentElement, {
    childList: true, subtree: true,
    attributes: true, attributeFilter: ['src']
});

// ── Player config probe (page world) ─────────────────────────────────────────
// Reported via the PlayBridgeMediaFound CustomEvent from the injected script
// below. Finds stream URLs configured in common JS players (JW Player,
// Video.js, hls.js) before playback starts — these never hit the network (so
// webRequest can't see them) and use blob: element src under MSE (so the DOM
// observer can't either).
window.addEventListener('PlayBridgeMediaFound', (event) => {
    const url = event.detail && event.detail.url;
    if (!url || typeof url !== 'string' || !url.startsWith('http')) return;
    browser.runtime.sendMessage({
        action: 'player_video_found',
        url: url,
        origin: window.location.href
    }).catch(() => {});
});

console.log('[VideoDetector Content] Loaded');

// --- PlayBridge JS Bridge ---
// Injected into the page world to provide window.playbridge.cast() and to
// probe JS player configs for stream URLs.
(function injectBridge() {
    const bridgeScript = document.createElement('script');
    bridgeScript.textContent = `
        (function() {
            if (window.playbridge_injected) return;
            window.playbridge_injected = true;
            window.playbridge = {
                cast: function(payload) {
                    window.dispatchEvent(new CustomEvent('PlayBridgeCast', { detail: payload }));
                }
            };

            // --- BACKGROUND PLAYBACK FIX ---
            // Override visibility state so sites like YouTube don't pause when backgrounded
            try {
                Object.defineProperty(document, 'visibilityState', {
                    get: function() { return 'visible'; },
                    configurable: true
                });
                Object.defineProperty(document, 'hidden', {
                    get: function() { return false; },
                    configurable: true
                });

                // Also override the visibilitychange event
                window.addEventListener('visibilitychange', function(e) {
                    e.stopImmediatePropagation();
                }, true);

                console.log('[PlayBridge JS Bridge] Shim + Background Fix injected');
            } catch (e) {
                console.warn('[PlayBridge JS Bridge] Failed to inject background fix', e);
            }

            // --- PLAYER CONFIG PROBE ---
            // Read stream URLs out of common JS player configs so streams are
            // detected even before the user presses play.
            var pbReported = {};
            function pbReport(url) {
                if (!url || typeof url !== 'string' || url.indexOf('http') !== 0) return;
                if (pbReported[url]) return;
                pbReported[url] = true;
                window.dispatchEvent(new CustomEvent('PlayBridgeMediaFound', { detail: { url: url } }));
            }
            function pbProbePlayers() {
                // JW Player
                try {
                    if (typeof window.jwplayer === 'function') {
                        var jw = window.jwplayer();
                        var list = jw && jw.getPlaylist && jw.getPlaylist();
                        if (list && list.length) {
                            for (var i = 0; i < list.length; i++) {
                                var item = list[i];
                                if (item.file) pbReport(item.file);
                                var sources = item.sources || [];
                                for (var j = 0; j < sources.length; j++) {
                                    if (sources[j].file) pbReport(sources[j].file);
                                }
                            }
                        }
                    }
                } catch (e) {}
                // Video.js
                try {
                    var vjs = window.videojs;
                    if (vjs) {
                        var players = (vjs.getAllPlayers && vjs.getAllPlayers()) ||
                            (vjs.players && Object.keys(vjs.players).map(function(k) { return vjs.players[k]; })) || [];
                        for (var p = 0; p < players.length; p++) {
                            try {
                                var player = players[p];
                                if (!player) continue;
                                var src = player.currentSrc && player.currentSrc();
                                if (src) pbReport(src);
                                var srcObj = player.currentSource && player.currentSource();
                                if (srcObj && srcObj.src) pbReport(srcObj.src);
                            } catch (e) {}
                        }
                    }
                } catch (e) {}
                // hls.js (common global instance pattern)
                try {
                    if (window.hls && window.hls.url) pbReport(window.hls.url);
                } catch (e) {}
            }
            // Players initialize at unpredictable times; probe a few times after load.
            [2000, 5000, 10000, 20000].forEach(function(t) { setTimeout(pbProbePlayers, t); });
        })();
    `;
    (document.head || document.documentElement).appendChild(bridgeScript);
    bridgeScript.remove();
})();

window.addEventListener('PlayBridgeCast', (event) => {
    const payload = event.detail;
    if (!payload) return;

    // Normalize to items array
    let items = [];
    let startIndex = payload.startIndex || 0;
    let metadata = payload.metadata || null;

    if (Array.isArray(payload)) {
        items = payload;
    } else if (payload.items && Array.isArray(payload.items)) {
        items = payload.items;
        startIndex = payload.startIndex || 0;
        metadata = payload.metadata || null;
    } else if (payload.url) {
        // If it's a single item with url/title/metadata, just wrap it
        items = [payload];
    }

    if (items.length === 0) return;

    console.log('[VideoDetector Content] Bridge Cast Request:', items.length, 'items, startIndex:', startIndex);

    // Forward to background script
    browser.runtime.sendMessage({
        type: 'cast',
        items: items,
        startIndex: startIndex,
        metadata: metadata
    }).catch(e => {
        // This might fail if the extension was reloaded or the tab is closing
    });
});
