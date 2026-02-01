// PlayBridge Video Detector - Background Script
// Detects video URLs and sends them to content script for storage

const VIDEO_CONTENT_TYPES = [
    'video/',
    'mpegurl',
    'application/dash',
    'application/x-mpegurl',
    'application/vnd.apple.mpegurl'
];

const VIDEO_URL_PATTERNS = [
    /\.m3u8(\?|$)/i,
    /\.m3u(\?|$)/i,
    /\.mpd(\?|$)/i,
    /\.mp4(\?|$)/i,
    /\.webm(\?|$)/i,
    /\.mkv(\?|$)/i,
    /googlevideo\.com\/videoplayback/i
];

console.log('[VideoDetector BG] Starting...');

// Store detected videos 
let detectedVideos = [];
let seenUrls = new Set();

// Send video to content script for storage in page localStorage
function notifyContentScript(video, tabId) {
    if (seenUrls.has(video.url)) {
        return;
    }

    seenUrls.add(video.url);
    detectedVideos.push(video);

    const count = detectedVideos.length;
    console.log('[VideoDetector BG] VIDEO #' + count + ': ' + video.url.substring(0, 80));

    // Send to content script in the tab
    if (tabId && tabId > 0) {
        browser.tabs.sendMessage(tabId, {
            type: 'video_detected',
            ...video
        }).catch(e => {
            console.log('[VideoDetector BG] Tab message failed:', e.message);
        });
    }

    // Also send to all tabs (in case the video is for an iframe)
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

// Listen for video URL patterns in network requests
browser.webRequest.onBeforeRequest.addListener(
    (details) => {
        const url = details.url.toLowerCase();
        const isVideoUrl = VIDEO_URL_PATTERNS.some(pattern => pattern.test(url));

        if (isVideoUrl || details.type === 'media') {
            console.log('[VideoDetector BG] MATCH (URL): ' + details.url.substring(0, 80));

            notifyContentScript({
                url: details.url,
                tabId: details.tabId,
                detectedBy: 'url_pattern',
                requestType: details.type,
                timestamp: Date.now()
            }, details.tabId);
        }
    },
    { urls: ["<all_urls>"] }
);

// Listen for video content-types in HTTP responses
browser.webRequest.onHeadersReceived.addListener(
    (details) => {
        const contentTypeHeader = details.responseHeaders?.find(
            h => h.name.toLowerCase() === 'content-type'
        );

        if (!contentTypeHeader) return;

        const contentType = contentTypeHeader.value.toLowerCase();
        const isVideo = VIDEO_CONTENT_TYPES.some(type => contentType.includes(type));

        if (isVideo) {
            console.log('[VideoDetector BG] MATCH (type): ' + contentType);

            notifyContentScript({
                url: details.url,
                tabId: details.tabId,
                contentType: contentType,
                detectedBy: 'content_type',
                originUrl: details.originUrl || '',
                timestamp: Date.now()
            }, details.tabId);
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
        browser.storage.local.set({ detectedVideos: [], lastUpdate: Date.now(), count: 0 });
        sendResponse({ cleared: true });
        return true;
    }
    return false;
});

console.log('[VideoDetector BG] Loaded');
