// Content script - runs in the context of web pages
// Receives video info from background and communicates with native app via URL hash

let videos = [];
let seenUrls = new Set();

// Listen for messages from background script
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === 'video_detected') {
        console.log('[VideoDetector Content] Received:', message.url.substring(0, 60));

        let video = null;
        let shouldSignal = false;
        const existingIndex = videos.findIndex(v => v.url === message.url);

        if (existingIndex === -1) {
            // New video
            video = {
                url: message.url,
                contentType: message.contentType || '',
                detectedBy: message.detectedBy || 'unknown',
                originUrl: message.originUrl,
                headers: message.headers || {},
                timestamp: message.timestamp || Date.now()
            };
            videos.push(video);
            seenUrls.add(message.url);
            shouldSignal = true;
        } else {
            // Existing video - check if we need to update headers
            const newHeaders = message.headers || {};
            if (Object.keys(newHeaders).length > 0) {
                console.log('[VideoDetector Content] Updating headers for existing video');
                // Merge headers
                videos[existingIndex] = {
                    ...videos[existingIndex],
                    headers: newHeaders,
                    contentType: message.contentType || videos[existingIndex].contentType
                };
                video = videos[existingIndex];
                shouldSignal = true;
            }
        }

        if (shouldSignal && video) {
            const count = videos.length;

            console.log('[VideoDetector Content] Total: ' + count + ', signaling native...');

            // Method 1: Update title
            const originalTitle = document.title.replace(/\s*\[PlayBridge:\d+\]$/, '');
            document.title = originalTitle + ' [PlayBridge:' + count + ']';

            // Method 2: localStorage
            try {
                localStorage.setItem('playbridge_videos', JSON.stringify(videos));
                localStorage.setItem('playbridge_video_count', count.toString());
            } catch (e) { }

            // Method 3: Hash Signal
            try {
                const encodedVideo = encodeURIComponent(JSON.stringify(video));
                const beacon = '#playbridge-video=' + encodedVideo;

                const oldHash = window.location.hash;
                window.location.replace(window.location.href.split('#')[0] + beacon);

                setTimeout(() => {
                    if (oldHash) {
                        window.location.replace(window.location.href.split('#')[0] + oldHash);
                    } else {
                        history.replaceState(null, '', window.location.href.split('#')[0]);
                    }
                }, 100);
            } catch (e) {
                console.log('[VideoDetector Content] Hash signal error:', e.message);
            }
        }

        sendResponse({ received: true, count: videos.length });
    }
    
    if (message.type === 'bridge_feedback') {
        window.dispatchEvent(new CustomEvent('PlayBridgeFeedback', {
            detail: message
        }));
    }

    return true;
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

console.log('[VideoDetector Content] Loaded');

// --- PlayBridge JS Bridge ---
// Injected into the page world to provide window.playbridge.cast()
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
        })();
    `;
    (document.head || document.documentElement).appendChild(bridgeScript);
    bridgeScript.remove();
})();

// Listen for the event from the page world
window.addEventListener('PlayBridgeCast', (event) => {
    const payload = event.detail;
    if (!payload || !payload.url) return;
    
    console.log('[VideoDetector Content] Bridge Cast Request:', payload.url.substring(0, 50));
    
    // Forward to background script
    browser.runtime.sendMessage({
        type: 'cast',
        url: payload.url,
        title: payload.title
    }).catch(e => {
        // This might fail if the extension was reloaded or the tab is closing
    });
});
