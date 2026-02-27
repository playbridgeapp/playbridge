// PlayBridge Video Detector - Background Script
// Detects video URLs per-tab and sends them to content script for storage
// Uses per-tab Maps so each tab tracks its own videos independently

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

console.log('[VideoDetector BG] Starting (Per-Tab Mode)...');

// Per-tab video storage
const tabVideos = new Map();           // geckoTabId → [{url, ...}]
const tabSeenUrls = new Map();         // geckoTabId → Set<url>
const tabHeadersCaptured = new Map();  // geckoTabId → Set<url>

// Map to store headers temporarily: requestId -> { headers, tabId, timestamp }
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

// Clean up state for a closed tab
function cleanupTab(tabId) {
    tabVideos.delete(tabId);
    tabSeenUrls.delete(tabId);
    tabHeadersCaptured.delete(tabId);
    console.log(`[VideoDetector BG] Cleaned up tab ${tabId}`);
}

// Listen for tab removal to free memory
browser.tabs.onRemoved.addListener((tabId) => {
    cleanupTab(tabId);
});

// Send video to content script for storage (per-tab)
function notifyContentScript(video, tabId, headers = null) {
    const hasHeaders = headers && Object.keys(headers).length > 0;
    const seenUrls = getTabSeenUrls(tabId);
    const headersCaptured = getTabHeadersCaptured(tabId);

    if (seenUrls.has(video.url)) {
        // If we already saw this URL, check if we're improving it with headers
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

    const count = videos.length;
    console.log('[VideoDetector BG] VIDEO DETECTED tab=' + tabId + ' #' + count + ' (Headers: ' + hasHeaders + '): ' + video.url.substring(0, 80));

    // Send to content script in the specific tab only
    if (tabId && tabId > 0) {
        browser.tabs.sendMessage(tabId, {
            type: 'video_detected',
            ...video
        }).catch(e => {
            // Ignore tab closed errors
        });
    }

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



const DEBUG = false; // Set to false to disable verbose logging

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
// We check the response Content-Type. If it's a video, we retrieve the stored headers.
browser.webRequest.onHeadersReceived.addListener(
    (details) => {
        const contentTypeHeader = details.responseHeaders?.find(
            h => h.name.toLowerCase() === 'content-type'
        );

        const contentType = contentTypeHeader ? contentTypeHeader.value.toLowerCase() : 'unknown';
        if (DEBUG) console.log(`[VideoDetector BG] Response: ${details.url} | Content-Type: ${contentType}`);

        // Always check map for cleanup, but first use it if video
        const storedData = requestHeadersMap.get(details.requestId);

        if (contentTypeHeader) {
            const isVideoContentType = VIDEO_CONTENT_TYPES.some(type => contentType.includes(type));
            const isM3u8Url = details.url.toLowerCase().includes('m3u8');
            
            // Check for common video extensions
            const videoExtensions = ['.mp4', '.mkv', '.webm', '.avi', '.mov', '.flv'];
            const urlLower = details.url.toLowerCase().split('?')[0]; // Ignore query params
            const hasVideoExtension = videoExtensions.some(ext => urlLower.endsWith(ext));
            
            // Check for subtitle extensions
            const subtitleExtensions = ['.vtt', '.srt'];
            const hasSubtitleExtension = subtitleExtensions.some(ext => urlLower.endsWith(ext));
            
            // If it's octet-stream, we MUST rely on extension.
            // But we can also be more aggressive: if it ends in .mp4, it's a video, period.
            const isVideoExtensionMatch = hasVideoExtension && (
                contentType.includes('octet-stream') || 
                contentType.includes('application/x-google-chrome-pdf') || // sometimes misidentified?
                contentType.includes('binary') ||
                !contentType // or no content type?
            );

            const isVideo = isVideoContentType || isM3u8Url || isVideoExtensionMatch || hasVideoExtension || hasSubtitleExtension; 
            // hasVideoExtension covers cases where server sends wrong type (e.g. text/plain for .mp4)

            if (isVideo) {
                if (DEBUG) console.log(`[VideoDetector BG] CONFIRMED VIDEO (Type: ${contentType}, URL match: ${isM3u8Url || hasVideoExtension}): ${details.url.substring(0, 50)}...`);

                const headers = storedData ? storedData.headers : null;
                
                let detectedBy = 'unknown';
                if (isVideoContentType) detectedBy = 'content_type';
                else if (isM3u8Url) detectedBy = 'url_pattern_m3u8';
                else if (hasVideoExtension) detectedBy = 'url_extension';
                else if (hasSubtitleExtension) detectedBy = 'subtitle_extension';

                notifyContentScript({
                    url: details.url,
                    tabId: details.tabId,
                    contentType: contentType,
                    detectedBy: detectedBy,
                    originUrl: details.originUrl || '',
                    timestamp: Date.now()
                }, details.tabId, headers);
            } else {
                // Check if it's a potential hidden M3U8 stream in a generic response type
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
                                    if (DEBUG) console.log(`[VideoDetector BG] HIDDEN M3U8 DETECTED in body: ${details.url.substring(0, 50)}...`);
                                    const headers = storedData ? storedData.headers : null;
                                    notifyContentScript({
                                        url: details.url,
                                        tabId: details.tabId,
                                        contentType: contentType,
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
    return false;
});

console.log('[VideoDetector BG] Loaded (Per-Tab Mode)');
