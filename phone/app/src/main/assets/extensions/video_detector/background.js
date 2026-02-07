// PlayBridge Video Detector - Background Script
// Detects video URLs and sends them to content script for storage
// Refactored to use Store & Forward mechanism

const VIDEO_CONTENT_TYPES = [
    'video/',
    'mpegurl',
    'application/dash',
    'application/x-mpegurl',
    'application/vnd.apple.mpegurl'
];

console.log('[VideoDetector BG] Starting...');

// Store detected videos to avoid duplicates
let detectedVideos = [];
let seenUrls = new Set();
let urlHeadersCaptured = new Set();

// Map to store headers temporarily: requestId -> { headers: Object, timestamp: Number }
const requestHeadersMap = new Map();

// Configuration for cleanup
const CLEANUP_INTERVAL_MS = 30000; // 30 seconds
const HEADER_TTL_MS = 60000; // 1 minute

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


// Send video to content script for storage
function notifyContentScript(video, tabId, headers = null) {
    const hasHeaders = headers && Object.keys(headers).length > 0;

    if (seenUrls.has(video.url)) {
        // If we already saw this URL, check if we're improving it with headers
        if (urlHeadersCaptured.has(video.url) || !hasHeaders) {
            return;
        }
    }

    seenUrls.add(video.url);
    if (hasHeaders) {
        urlHeadersCaptured.add(video.url);
        video.headers = headers;
    }

    // Update if exists or push new
    const existingIndex = detectedVideos.findIndex(v => v.url === video.url);
    if (existingIndex !== -1) {
        detectedVideos[existingIndex] = { ...detectedVideos[existingIndex], ...video };
    } else {
        detectedVideos.push(video);
    }

    const count = detectedVideos.length;
    console.log('[VideoDetector BG] VIDEO DETECTED #' + count + ' (Headers: ' + hasHeaders + '): ' + video.url.substring(0, 80));

    // Send to content script in the specific tab
    if (tabId && tabId > 0) {
        browser.tabs.sendMessage(tabId, {
            type: 'video_detected',
            ...video
        }).catch(e => {
            // Ignore tab closed errors
        });
    }

    // Also broadcast to all tabs (useful for iframes)
    browser.tabs.query({}).then(tabs => {
        tabs.forEach(tab => {
            browser.tabs.sendMessage(tab.id, {
                type: 'video_detected',
                ...video
            }).catch(() => { });
        });
    }).catch(() => { });

    // Store in extension storage as backup
    browser.storage.local.set({
        detectedVideos: detectedVideos,
        lastUpdate: Date.now(),
        count: count
    });
}


// 1. Capture Headers (Store)
// We capture headers for ALL requests because we don't know the Content-Type yet.
// We store them keyed by requestId.
browser.webRequest.onBeforeSendHeaders.addListener(
    (details) => {
        const headers = {};
        if (details.requestHeaders) {
            details.requestHeaders.forEach(h => {
                const name = h.name.toLowerCase();
                if (['cookie', 'user-agent', 'referer', 'origin', 'authorization'].includes(name)) {
                    headers[h.name] = h.value;
                }
            });
        }

        if (Object.keys(headers).length > 0) {
            requestHeadersMap.set(details.requestId, {
                headers: headers,
                timestamp: Date.now()
            });
        }
    },
    { urls: ["<all_urls>"] },
    ["requestHeaders"]
);


// 2. Confirm Video & Merge Headers (Forward)
// We check the response Content-Type. If it's a video, we retrieve the stored headers.
browser.webRequest.onHeadersReceived.addListener(
    (details) => {
        const contentTypeHeader = details.responseHeaders?.find(
            h => h.name.toLowerCase() === 'content-type'
        );

        // Always check map for cleanup, but first use it if video
        const storedData = requestHeadersMap.get(details.requestId);

        if (contentTypeHeader) {
            const contentType = contentTypeHeader.value.toLowerCase();
            const isVideoContentType = VIDEO_CONTENT_TYPES.some(type => contentType.includes(type));
            const isM3u8Url = details.url.toLowerCase().includes('m3u8');
            const isVideo = isVideoContentType || isM3u8Url;

            if (isVideo) {
                console.log(`[VideoDetector BG] CONFIRMED VIDEO (Type: ${contentType}, URL match: ${isM3u8Url}): ${details.url.substring(0, 50)}...`);

                const headers = storedData ? storedData.headers : null;

                notifyContentScript({
                    url: details.url,
                    tabId: details.tabId,
                    contentType: contentType,
                    detectedBy: isVideoContentType ? 'content_type' : (isM3u8Url ? 'url_pattern' : 'unknown'),
                    originUrl: details.originUrl || '',
                    timestamp: Date.now()
                }, details.tabId, headers);
            }
        }

        // Cleanup: Once headers are received, we don't need to store the request headers anymore
        // for this specific request ID.
        if (storedData) {
            requestHeadersMap.delete(details.requestId);
        }
    },
    { urls: ["<all_urls>"] },
    ["responseHeaders"]
);

// Handle messages from content scripts
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.action === 'getVideos') {
        sendResponse({ videos: detectedVideos, count: detectedVideos.length });
        return true;
    }
    if (message.action === 'clearVideos') {
        detectedVideos = [];
        seenUrls.clear();
        urlHeadersCaptured.clear();
        requestHeadersMap.clear(); // Also clear the map just in case
        browser.storage.local.set({ detectedVideos: [], lastUpdate: Date.now(), count: 0 });
        sendResponse({ cleared: true });
        return true;
    }
    return false;
});

console.log('[VideoDetector BG] Loaded (Store & Forward Mode)');
