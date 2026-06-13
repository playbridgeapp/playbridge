// PlayBridge Video Detector - Background Script
// Detects video URLs per-tab via webRequest interception and reports them to the
// native app over GeckoView native messaging (runtime.sendNativeMessage).
// Per-tab state is reset on every top-level navigation (including reloads).

const NATIVE_APP_ID = 'browser';

const VIDEO_CONTENT_TYPES = [
    'video/',
    'mpegurl',
    'application/dash',
    'application/x-mpegurl',
    'application/vnd.apple.mpegurl',
    'text/vtt',
    'application/x-subrip',
    '.vtt',
    '.srt'
];

const VIDEO_EXTENSIONS = ['.mp4', '.mkv', '.webm', '.avi', '.mov', '.flv', '.m4v', '.wmv', '.3gp'];
const SUBTITLE_EXTENSIONS = ['.vtt', '.srt'];
// HLS/DASH media segments — not playable streams on their own; suppress so they
// don't flood the detected-videos list before the segment-prefix cleanup in Kotlin.
const SEGMENT_EXTENSIONS = ['.ts', '.m4s', '.fmp4', '.cmfv', '.cmfa'];

console.log('[VideoDetector BG] Starting (Native Messaging Mode)...');

// Per-tab video storage
const tabVideos = new Map();           // geckoTabId → [{url, ...}]
const tabSeenUrls = new Map();         // geckoTabId → Set<url>
const tabHeadersCaptured = new Map();  // geckoTabId → Set<url>

// Map to store headers temporarily: requestId -> { headers, tabId, timestamp }
const requestHeadersMap = new Map();

// Configuration for cleanup
const CLEANUP_INTERVAL_MS = 30000; // 30 seconds
const HEADER_TTL_MS = 60000; // 1 minute

const DEBUG = false; // Set to true for verbose logging

// Periodic cleanup to prevent memory leaks from incomplete requests
setInterval(() => {
    const now = Date.now();
    let cleanedCount = 0;
    for (const [requestId, data] of requestHeadersMap.entries()) {
        if (now - data.timestamp > HEADER_TTL_MS) {
            requestHeadersMap.delete(requestId);
            cleanedCount++;
        }
    }
    if (cleanedCount > 0) {
        console.log(`[VideoDetector BG] Cleaned up ${cleanedCount} stale header entries`);
    }
}, CLEANUP_INTERVAL_MS);

// ── Native messaging ──────────────────────────────────────────────────────────
// GeckoView routes runtime.sendNativeMessage(NATIVE_APP_ID, ...) to the
// MessageDelegate registered in Components.kt (processMessage). This replaces
// the old document.title / localStorage / URL-hash signaling hacks, which were
// racy (100ms restore window, coalesced location changes) and broke on sites
// that manage their own hash/history.
function sendToNative(message) {
    try {
        browser.runtime.sendNativeMessage(NATIVE_APP_ID, message).catch((e) => {
            if (DEBUG) console.log('[VideoDetector BG] sendNativeMessage failed:', e?.message);
        });
    } catch (e) {
        console.error('[VideoDetector BG] sendNativeMessage threw:', e);
    }
}

const tabLastUrl = new Map();         // geckoTabId → lastUrl

// Helper: get or create per-tab structures
function getTabVideos(tabId) {
    if (!tabVideos.has(tabId)) tabVideos.set(tabId, []);
    return tabVideos.get(tabId);
}
function getTabSeenUrls(tabId) {
    if (!tabSeenUrls.has(tabId)) tabSeenUrls.set(tabId, new Set());
    return tabSeenUrls.get(tabId);
}
function getTabHeadersCaptured(tabId) {
    if (!tabHeadersCaptured.has(tabId)) tabHeadersCaptured.set(tabId, new Set());
    return tabHeadersCaptured.get(tabId);
}

// Clean up state for a tab (closed or navigated away)
function cleanupTab(tabId) {
    tabVideos.delete(tabId);
    tabSeenUrls.delete(tabId);
    tabHeadersCaptured.delete(tabId);
    tabLastUrl.delete(tabId);
    if (DEBUG) console.log(`[VideoDetector BG] Cleaned up tab ${tabId}`);
}

// Listen for tab removal to free memory
browser.tabs.onRemoved.addListener((tabId) => {
    cleanupTab(tabId);
});

// ── Navigation reset ──────────────────────────────────────────────────────────
// Detect significant navigations (including reloads, link clicks, history state
// updates via pushState/replaceState, and fragment/hash updates).
// This clears the per-tab detection state so previously-seen URLs are re-reported,
// and tells the native side to reset its list for the tab.

function isSignificantNavigation(url1, url2) {
    if (!url1 || !url2) return true;
    if (url1 === url2) return false;

    // Get URL without hash/fragment
    const noFrag1 = url1.split('#')[0];
    const noFrag2 = url2.split('#')[0];

    // If pathname or search query changed, it's a significant navigation
    if (noFrag1 !== noFrag2) return true;

    // Base URL is the same. Check if the fragment changed and looks like an SPA route path
    const frag1 = url1.split('#')[1] || '';
    const frag2 = url2.split('#')[1] || '';

    if (frag1 !== frag2) {
        // If either fragment contains a slash (common for hash routing like #/watch/123), it's a route change
        if (frag1.includes('/') || frag2.includes('/')) {
            return true;
        }
    }

    return false;
}

function handleNavigation(tabId, url, transitionType = '', force = false) {
    const lastUrl = tabLastUrl.get(tabId);
    if (!force && !isSignificantNavigation(lastUrl, url)) {
        if (DEBUG) console.log(`[VideoDetector BG] Same-document route change ignored (insignificant): ${url.substring(0, 80)}`);
        tabLastUrl.set(tabId, url);
        return;
    }

    tabLastUrl.set(tabId, url);
    cleanupTab(tabId);
    sendToNative({
        type: 'navigation',
        tabId: tabId,
        url: url,
        originUrl: url,
        transitionType: transitionType,
        timestamp: Date.now()
    });
    console.log(`[VideoDetector BG] Navigation reset triggered tab=${tabId} (${transitionType}): ${url.substring(0, 80)}`);
}

// 1. Committed top-level document navigations (e.g. link clicks, reloads)
browser.webNavigation.onCommitted.addListener((details) => {
    if (details.frameId !== 0) return;
    handleNavigation(details.tabId, details.url, details.transitionType || 'committed', true);
});

// 2. History API state updates (e.g. pushState / replaceState route updates in SPA)
browser.webNavigation.onHistoryStateUpdated.addListener((details) => {
    if (details.frameId !== 0) return;
    handleNavigation(details.tabId, details.url, 'history_state_updated', false);
});

// 3. Fragment/hash updates (e.g. hash routing SPA route updates)
browser.webNavigation.onReferenceFragmentUpdated.addListener((details) => {
    if (details.frameId !== 0) return;
    handleNavigation(details.tabId, details.url, 'reference_fragment_updated', false);
});

// ── Reporting ─────────────────────────────────────────────────────────────────
// Dedupe per tab, store, and push to the native app. Requests without a real
// tab ID (e.g. fired from service workers have tabId === -1) are still sent —
// the native side resolves the Kotlin tab from originUrl, falling back to the
// selected tab.
function reportVideo(video, tabId, headers = null) {
    const hasHeaders = headers && Object.keys(headers).length > 0;
    const seenUrls = getTabSeenUrls(tabId);
    const headersCaptured = getTabHeadersCaptured(tabId);

    if (seenUrls.has(video.url)) {
        // If we already saw this URL, only continue if we're improving it with headers
        if (headersCaptured.has(video.url) || !hasHeaders) {
            return;
        }
    }

    seenUrls.add(video.url);
    if (hasHeaders) {
        headersCaptured.add(video.url);
        video.headers = headers;
    }

    // Update if exists or push new
    const videos = getTabVideos(tabId);
    const existingIndex = videos.findIndex(v => v.url === video.url);
    if (existingIndex !== -1) {
        videos[existingIndex] = { ...videos[existingIndex], ...video };
    } else {
        videos.push(video);
    }

    console.log('[VideoDetector BG] VIDEO DETECTED tab=' + tabId + ' #' + videos.length +
        ' (Headers: ' + hasHeaders + ', by: ' + video.detectedBy + '): ' + video.url.substring(0, 80));

    sendToNative({
        type: 'video_detected',
        ...video
    });

    // Store in extension storage as backup (per-tab)
    const allVideos = [];
    for (const [, vids] of tabVideos) {
        allVideos.push(...vids);
    }
    browser.storage.local.set({
        detectedVideos: allVideos,
        lastUpdate: Date.now(),
        count: allVideos.length
    });
}

// 0. Log all requests (Debug)
browser.webRequest.onBeforeRequest.addListener(
    (details) => {
        if (DEBUG) console.log(`[VideoDetector BG] Request: ${details.url} (Type: ${details.type})`);
    },
    { urls: ["<all_urls>"] }
);

// 1. Capture Headers (Store)
// We capture headers for ALL requests because we don't know the Content-Type yet.
// We store them keyed by requestId.
browser.webRequest.onBeforeSendHeaders.addListener(
    (details) => {
        if (details.method === 'OPTIONS') return;

        const headers = {};
        if (details.requestHeaders) {
            details.requestHeaders.forEach(h => {
                const name = h.name.toLowerCase();
                const skipHeaders = ['host', 'connection', 'accept-encoding', 'content-length', 'upgrade-insecure-requests'];
                if (!skipHeaders.includes(name)) {
                    headers[h.name] = h.value;
                }
            });
        }

        if (Object.keys(headers).length > 0) {
            requestHeadersMap.set(details.requestId, {
                headers: headers,
                tabId: details.tabId,
                timestamp: Date.now()
            });
        }
    },
    { urls: ["<all_urls>"] },
    ["requestHeaders"]
);

// 2. Confirm Video & Merge Headers (Forward)
// All URL-based checks run even when the response has NO Content-Type header —
// sketchy live-stream origins frequently omit it, and the old code skipped
// every check (including the m3u8 body sniffer) in that case.
browser.webRequest.onHeadersReceived.addListener(
    (details) => {
        const contentTypeHeader = details.responseHeaders?.find(
            h => h.name.toLowerCase() === 'content-type'
        );
        const contentType = contentTypeHeader ? contentTypeHeader.value.toLowerCase() : '';
        if (DEBUG) console.log(`[VideoDetector BG] Response: ${details.url} | Content-Type: ${contentType || 'none'}`);

        const storedData = requestHeadersMap.get(details.requestId);
        const urlFull = details.url.toLowerCase();
        const urlPath = urlFull.split('?')[0]; // Ignore query params for extension checks

        // Suppress HLS/DASH media segments early
        if (SEGMENT_EXTENSIONS.some(ext => urlPath.endsWith(ext))) {
            if (storedData) requestHeadersMap.delete(details.requestId);
            return;
        }

        const isVideoContentType = contentType !== '' && VIDEO_CONTENT_TYPES.some(type => contentType.includes(type));
        const isM3u8Url = urlFull.includes('m3u8');
        // DASH manifests are often served as text/xml or octet-stream, which the
        // content-type list misses — match the URL like we do for m3u8.
        const isMpdUrl = urlPath.endsWith('.mpd') || urlFull.includes('.mpd?');
        const hasVideoExtension = VIDEO_EXTENSIONS.some(ext => urlPath.endsWith(ext));
        const hasSubtitleExtension = SUBTITLE_EXTENSIONS.some(ext => urlPath.endsWith(ext));

        const isVideo = isVideoContentType || isM3u8Url || isMpdUrl || hasVideoExtension || hasSubtitleExtension;

        if (isVideo) {
            if (DEBUG) console.log(`[VideoDetector BG] CONFIRMED VIDEO (Type: ${contentType || 'none'}): ${details.url.substring(0, 50)}...`);

            const headers = storedData ? storedData.headers : null;

            let detectedBy = 'unknown';
            if (isVideoContentType) detectedBy = 'content_type';
            else if (isM3u8Url) detectedBy = 'url_pattern_m3u8';
            else if (isMpdUrl) detectedBy = 'url_pattern_mpd';
            else if (hasVideoExtension) detectedBy = 'url_extension';
            else if (hasSubtitleExtension) detectedBy = 'subtitle_extension';

            reportVideo({
                url: details.url,
                tabId: details.tabId,
                contentType: contentType || null,
                detectedBy: detectedBy,
                originUrl: details.originUrl || '',
                timestamp: Date.now()
            }, details.tabId, headers);
        } else {
            // Check if it's a potential hidden M3U8 stream in a generic (or absent)
            // response type by sniffing the first bytes of the body for #EXTM3U.
            const skipTypes = ['image', 'font', 'stylesheet', 'script'];
            if (details.statusCode === 200 && !skipTypes.includes(details.type)) {
                try {
                    const filter = browser.webRequest.filterResponseData(details.requestId);
                    const decoder = new TextDecoder("utf-8");
                    let checked = false;
                    let accumulatedData = "";

                    filter.ondata = (event) => {
                        if (checked) {
                            filter.write(event.data);
                            return;
                        }

                        filter.write(event.data); // pass data through unchanged
                        accumulatedData += decoder.decode(event.data, { stream: true });

                        if (accumulatedData.length >= 7) {
                            checked = true;
                            const trimmed = accumulatedData.trim();
                            if (trimmed.startsWith("#EXTM3U")) {
                                console.log(`[VideoDetector BG] HIDDEN M3U8 DETECTED in body: ${details.url.substring(0, 50)}...`);
                                const headers = storedData ? storedData.headers : null;
                                reportVideo({
                                    url: details.url,
                                    tabId: details.tabId,
                                    contentType: contentType || null,
                                    detectedBy: 'body_content_m3u8',
                                    originUrl: details.originUrl || '',
                                    timestamp: Date.now()
                                }, details.tabId, headers);
                            }
                            // Stop intercepting further chunks to save resources
                            filter.disconnect();
                        }
                    };

                    filter.onstop = (event) => {
                        try { filter.disconnect(); } catch (e) {}
                    };

                    filter.onerror = (event) => {
                        if (DEBUG) console.log(`[VideoDetector BG] Filter error: ${filter.error}`);
                    };
                } catch (e) {
                    // filterResponseData might throw if request cannot be filtered
                    if (DEBUG) console.error(`[VideoDetector BG] filterResponseData error:`, e);
                }
            }
        }

        // --- HTTP Error Detection ---
        // If it's a main_frame request and status is >= 400, notify the native app
        if (details.type === 'main_frame' && details.statusCode >= 400) {
            console.log(`[VideoDetector BG] HTTP ERROR detected: ${details.statusCode} for ${details.url}`);

            const errorMsg = {
                type: 'http_error',
                url: details.url,
                originUrl: details.url,
                statusCode: details.statusCode,
                tabId: details.tabId,
                timestamp: Date.now()
            };

            sendToNative(errorMsg);

            // Also update storage so polling can find it if needed
            browser.storage.local.set({
                lastHttpError: errorMsg
            });
        }

        // Cleanup: Once headers are received, we don't need to store the request headers anymore
        // for this specific request ID.
        if (storedData) {
            requestHeadersMap.delete(details.requestId);
        }
    },
    { urls: ["<all_urls>"] },
    ["responseHeaders", "blocking"]
);

// Handle messages from content scripts
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    // Video element detected by DOM observer in content.js
    if (message.action === 'dom_video_found' || message.action === 'player_video_found') {
        const tabId = sender.tab?.id ?? -1;
        reportVideo({
            url: message.url,
            tabId: tabId,
            contentType: null,
            detectedBy: message.action === 'player_video_found' ? 'player_config' : 'dom_video_element',
            originUrl: message.origin,
            timestamp: Date.now()
        }, tabId, null); // headers arrive later via webRequest and will update this entry
        return false;
    }
    if (message.action === 'getVideos') {
        const tabId = message.tabId || (sender.tab && sender.tab.id);
        const videos = tabId ? (tabVideos.get(tabId) || []) : [];
        sendResponse({ videos: videos, count: videos.length });
        return true;
    }
    if (message.action === 'clearVideos') {
        const tabId = message.tabId || (sender.tab && sender.tab.id);
        if (tabId) {
            cleanupTab(tabId);
        } else {
            // Clear all tabs
            tabVideos.clear();
            tabSeenUrls.clear();
            tabHeadersCaptured.clear();
            requestHeadersMap.clear();
        }
        browser.storage.local.set({ detectedVideos: [], lastUpdate: Date.now(), count: 0 });
        sendResponse({ cleared: true });
        return true;
    }
    if (message.type === 'cast') {
        // Forward to native app (Components.kt onBridgeCastRequest) via GeckoView Port
        try {
            const port = browser.runtime.connectNative(NATIVE_APP_ID);

            // Listen for feedback from native app
            port.onMessage.addListener((response) => {
                console.log('[VideoDetector BG] Feedback received from native:', response);
                if (response.type === 'feedback') {
                    // Forward to the specific tab that started the cast
                    if (message.tabId) {
                        browser.tabs.sendMessage(message.tabId, {
                            type: 'bridge_feedback',
                            ...response
                        }).catch(() => {});
                    }
                }
            });

            port.postMessage(message);
            // Increased timeout to allow for feedback round-trip
            setTimeout(() => port.disconnect(), 1000);
        } catch (e) {
            console.error('[PlayBridge Bridge] Native connection failed:', e);
        }
        return true;
    }
    return false;
});

console.log('[VideoDetector BG] Loaded (Native Messaging Mode)');
