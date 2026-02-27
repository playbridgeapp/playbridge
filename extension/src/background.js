// PlayBridge Unified Video Detector - Background Script
// Detects video URLs per-tab and sends them to content script for storage
// Uses per-tab Maps so each tab tracks its own videos independently

if (typeof ServiceWorkerGlobalScope !== 'undefined' && self instanceof ServiceWorkerGlobalScope) {
    importScripts("hls-parser.js");
}

// Polyfill browser API
const browserAPI = (typeof browser !== 'undefined') ? browser : chrome;

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

console.log('[VideoDetector BG] Starting Unified Background Script...');

// Per-tab video storage
const tabVideos = new Map();           // tabId → [{url, ...}]
const tabSeenUrls = new Map();         // tabId → Set<url>
const tabHeadersCaptured = new Map();  // tabId → Set<url>
const requestHeadersMap = new Map();   // requestId -> { headers, tabId, timestamp }

const CLEANUP_INTERVAL_MS = 30000;
const HEADER_TTL_MS = 60000;

setInterval(() => {
    const now = Date.now();
    for (const [requestId, data] of requestHeadersMap.entries()) {
        if (now - data.timestamp > HEADER_TTL_MS) {
            requestHeadersMap.delete(requestId);
        }
    }
}, CLEANUP_INTERVAL_MS);

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

function cleanupTab(tabId) {
    tabVideos.delete(tabId);
    tabSeenUrls.delete(tabId);
    tabHeadersCaptured.delete(tabId);
}

// Clear on tab close
browserAPI.tabs.onRemoved.addListener((tabId) => {
    cleanupTab(tabId);
});

// Clear on navigation to a new page in the same tab
function handleNavigation(details) {
    if (details.frameId === 0) { // Main frame only
        console.log(`[VideoDetector BG] Clearing videos for tab ${details.tabId} due to navigation: ${details.url}`);
        cleanupTab(details.tabId);
        // Inform content script to hide/reset UI
        browserAPI.tabs.sendMessage(details.tabId, { type: 'clear_videos' }).catch((e) => {
            console.log(`[VideoDetector BG] Failed to send clear_videos to tab ${details.tabId}:`, e.message);
        });
        
        // Update global storage if needed
        const allVideos = [];
        for (const [, vids] of tabVideos) allVideos.push(...vids);
        browserAPI.storage.local.set({
            detectedVideos: allVideos,
            lastUpdate: Date.now(),
            count: allVideos.length
        });
    }
}

if (browserAPI.webNavigation) {
    if (browserAPI.webNavigation.onCommitted) {
        browserAPI.webNavigation.onCommitted.addListener(handleNavigation);
    }
    if (browserAPI.webNavigation.onHistoryStateUpdated) {
        browserAPI.webNavigation.onHistoryStateUpdated.addListener(handleNavigation);
    }
} else {
    console.error("[VideoDetector BG] webNavigation API is NOT available!");
}

function notifyContentScript(video, tabId, headers = null) {
    const hasHeaders = headers && Object.keys(headers).length > 0;
    const seenUrls = getTabSeenUrls(tabId);
    const headersCaptured = getTabHeadersCaptured(tabId);

    if (seenUrls.has(video.url)) {
        if (headersCaptured.has(video.url) || !hasHeaders) return;
    }

    seenUrls.add(video.url);
    if (hasHeaders) {
        headersCaptured.add(video.url);
        video.headers = headers;
    }

    const videos = getTabVideos(tabId);
    const existingIndex = videos.findIndex(v => v.url === video.url);
    if (existingIndex !== -1) {
        videos[existingIndex] = { ...videos[existingIndex], ...video };
    } else {
        videos.push(video);
    }

    console.log(`[VideoDetector BG] VIDEO DETECTED tab=${tabId} (Headers: ${hasHeaders}): ${video.url.substring(0, 80)}`);

    if (tabId && tabId > 0) {
        browserAPI.tabs.sendMessage(tabId, {
            type: 'video_detected',
            ...video
        }).catch(() => {});
    }

    const allVideos = [];
    for (const [, vids] of tabVideos) allVideos.push(...vids);
    browserAPI.storage.local.set({
        detectedVideos: allVideos,
        lastUpdate: Date.now(),
        count: allVideos.length
    });
}

function processAndNotifyVideo(videoData, tabId, headers) {
    // If it's an m3u8 playlist, fetch and parse HLS qualities
    if (videoData.url.toLowerCase().includes('m3u8')) {
        HlsParser.parsePlaylist(videoData.url).then(playlist => {
            if (playlist && playlist.videoQualities && playlist.videoQualities.length > 0) {
                videoData.qualities = playlist.videoQualities;
            }
            notifyContentScript(videoData, tabId, headers);
        }).catch(err => {
            console.error("[VideoDetector BG] Error parsing HLS:", err);
            notifyContentScript(videoData, tabId, headers);
        });
    } 
    // If it's a subtitle, fetch a small preview of the text
    else if (videoData.detectedBy === 'subtitle_extension' || videoData.url.endsWith('.srt') || videoData.url.endsWith('.vtt')) {
        fetch(videoData.url, { method: 'GET', headers: { 'Range': 'bytes=0-500' } })
            .then(res => res.text())
            .then(text => {
                // Clean up WebVTT headers and timestamps to get just a bit of readable text
                let preview = text.replace(/WEBVTT[\s\S]*?(?=\r?\n\r?\n)/i, '') // Remove WEBVTT header
                                  .replace(/[\d:,]+ --> [\d:,]+[^\n]*\n/g, '') // Remove timestamps
                                  .replace(/<[^>]+>/g, '') // Remove tags like <i>
                                  .replace(/^\s*\d+\s*$/gm, '') // Remove sequence numbers
                                  .replace(/\r?\n|\r/g, ' ') // Flatten to single line
                                  .trim()
                                  .substring(0, 80);
                                  
                if (preview.length > 0) {
                    videoData.subtitlePreview = preview + "...";
                }
                notifyContentScript(videoData, tabId, headers);
            })
            .catch(err => {
                console.error("[VideoDetector BG] Error fetching subtitle preview:", err);
                notifyContentScript(videoData, tabId, headers);
            });
    } else {
        notifyContentScript(videoData, tabId, headers);
    }
}

const DEBUG = true;

// 1. Capture Headers
browserAPI.webRequest.onBeforeSendHeaders.addListener(
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

// 2. Confirm Video & Merge Headers
let extraInfoSpec = ["responseHeaders"];
// In Chrome/Edge MV3, "blocking" is not allowed in extraInfoSpec unless it's declarativeNetRequest, but we don't block.
// However Firefox/Gecko views require blocking to call filterResponseData.
// Let's add blocking conditionally based on whether we compiled for V2.
// We will just handle the filterResponseData gracefully.

// Actually, webRequest.onHeadersReceived requires "blocking" to modify headers, but we just read them.
// Wait, filterResponseData REQUIRES "blocking" on onHeadersReceived in Firefox.
// So we just specify blocking here. The build process can strip it for chrome if necessary later.
if (typeof browser !== 'undefined' && browser.webRequest && browser.webRequest.filterResponseData) {
    extraInfoSpec.push("blocking");
}

browserAPI.webRequest.onHeadersReceived.addListener(
    (details) => {
        const contentTypeHeader = details.responseHeaders?.find(
            h => h.name.toLowerCase() === 'content-type'
        );

        const contentType = contentTypeHeader ? contentTypeHeader.value.toLowerCase() : 'unknown';
        const storedData = requestHeadersMap.get(details.requestId);

        if (contentTypeHeader) {
            // Prevent spam from range requests. If already processed fully, ignore.
            const captured = getTabHeadersCaptured(details.tabId);
            const seen = getTabSeenUrls(details.tabId);
            
            if (seen.has(details.url)) {
                if (captured.has(details.url) || !storedData) {
                    if (storedData) requestHeadersMap.delete(details.requestId);
                    return;
                }
            }

            let isVideo = false;
            let detectedBy = 'unknown';

            const isVideoContentType = VIDEO_CONTENT_TYPES.some(type => contentType.includes(type));
            const isM3u8Url = details.url.toLowerCase().includes('m3u8');
            
            const urlLower = details.url.toLowerCase().split('?')[0];
            const hasVideoExtension = ['.mp4', '.mkv', '.webm', '.avi', '.mov', '.flv'].some(ext => urlLower.endsWith(ext));
            const hasSubtitleExtension = ['.vtt', '.srt'].some(ext => urlLower.endsWith(ext));
            
            const isVideoExtensionMatch = hasVideoExtension && (
                contentType.includes('octet-stream') || 
                contentType.includes('application/x-google-chrome-pdf') || 
                contentType.includes('binary') ||
                !contentType
            );

            if (isVideoContentType) { isVideo = true; detectedBy = 'content_type'; }
            else if (isM3u8Url) { isVideo = true; detectedBy = 'url_pattern_m3u8'; }
            else if (isVideoExtensionMatch || hasVideoExtension) { isVideo = true; detectedBy = 'url_extension'; }
            else if (hasSubtitleExtension) { isVideo = true; detectedBy = 'subtitle_extension'; }

            if (isVideo) {
                if (DEBUG) console.log(`[VideoDetector BG] CONFIRMED VIDEO: ${details.url.substring(0, 50)}...`);
                
                processAndNotifyVideo({
                    url: details.url,
                    tabId: details.tabId,
                    contentType: contentType,
                    detectedBy: detectedBy,
                    originUrl: details.originUrl || '',
                    timestamp: Date.now()
                }, details.tabId, storedData ? storedData.headers : null);
            } else {
                // Check hidden M3U8 for Firefox/Gecko
                const skipTypes = ['image', 'font', 'stylesheet', 'script'];
                if (details.statusCode === 200 && !skipTypes.includes(details.type) && typeof browser !== 'undefined' && browser.webRequest.filterResponseData) {
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
                            filter.write(event.data);
                            accumulatedData += decoder.decode(event.data, { stream: true });
                            
                            if (accumulatedData.length >= 7) {
                                checked = true;
                                const trimmed = accumulatedData.trim();
                                if (trimmed.startsWith("#EXTM3U")) {
                                    if (DEBUG) console.log(`[VideoDetector BG] HIDDEN M3U8 DETECTED`);
                                    processAndNotifyVideo({
                                        url: details.url,
                                        tabId: details.tabId,
                                        contentType: contentType,
                                        detectedBy: 'body_content_m3u8',
                                        originUrl: details.originUrl || '',
                                        timestamp: Date.now()
                                    }, details.tabId, storedData ? storedData.headers : null);
                                }
                                filter.disconnect();
                            }
                        };
                        filter.onstop = () => { try { filter.disconnect(); } catch (e) {} };
                        filter.onerror = () => { if (DEBUG) console.log(`Filter error`); };
                    } catch (e) {
                         // Ignored
                    }
                }
            }
        }

        if (storedData) {
            requestHeadersMap.delete(details.requestId);
        }
    },
    { urls: ["<all_urls>"] },
    extraInfoSpec
);

// WebSocket Management for Desktop Extensions (PlayBridge TV communication)
let wsConnection = null;
let playbridgeIp = null;
let playbridgePin = null;

// Status values: disconnected, connecting, connected
let wsStatus = 'disconnected'; 

function updateWsStatus(newStatus) {
    wsStatus = newStatus;
    // Broadcast status to all tabs so popup knows
    browserAPI.tabs.query({}).then(tabs => {
        tabs.forEach(tab => {
            browserAPI.tabs.sendMessage(tab.id, { type: 'ws_status_update', status: wsStatus })
                .catch(() => {});
        });
    });
}

function connectWebSocket(ip, pin) {
    if (wsConnection) {
        wsConnection.close();
    }
    
    playbridgeIp = ip;
    playbridgePin = pin;
    
    // Save to storage
    browserAPI.storage.local.set({ pb_ip: ip, pb_pin: pin });
    
    updateWsStatus('connecting');
    
    try {
        wsConnection = new WebSocket(`ws://${ip}:8765`);
        
        wsConnection.onopen = () => {
            console.log('[VideoDetector BG] WS Connected to PlayBridge TV');
            updateWsStatus('connected');
            
            // Send Auth
            if (pin) {
                const authMsg = {
                    type: "auth",
                    pin: pin
                };
                wsConnection.send(JSON.stringify(authMsg));
            }
        };
        
        wsConnection.onmessage = (event) => {
            console.log('[VideoDetector BG] WS Message:', event.data);
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'ping') {
                    wsConnection.send(JSON.stringify({ type: 'pong' }));
                }
            } catch (e) {}
        };
        
        wsConnection.onclose = () => {
            console.log('[VideoDetector BG] WS Disconnected');
            updateWsStatus('disconnected');
            wsConnection = null;
        };
        
        wsConnection.onerror = (e) => {
            console.log('[VideoDetector BG] WS Error', e);
            // close handler will fire
        };
    } catch (e) {
        console.error('[VideoDetector BG] WS setup error:', e);
        updateWsStatus('disconnected');
    }
}

function sendTvPlayCommand(videoData) {
    if (wsStatus === 'connected' && wsConnection) {
        const payload = {
            url: videoData.url,
            contentType: videoData.contentType,
            headers: videoData.headers,
            detectedBy: videoData.detectedBy
        };
        
        if (videoData.subtitles && videoData.subtitles.length > 0) {
            payload.subtitles = videoData.subtitles;
        }
        
        const command = {
            type: "command",
            action: "play",
            payload: payload
        };
        
        console.log('[VideoDetector BG] Sending play command to TV:', command);
        wsConnection.send(JSON.stringify(command));
        return true;
    }
    return false;
}

// Load saved settings
browserAPI.storage.local.get(['pb_ip', 'pb_pin']).then(res => {
    if (res.pb_ip) {
        // Auto connect on start if we have an IP
        connectWebSocket(res.pb_ip, res.pb_pin || '');
    }
});

// Handle messages from content scripts / popups
browserAPI.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.action === 'getVideos') {
        const tabId = message.tabId || (sender.tab && sender.tab.id);
        const videos = tabId ? (tabVideos.get(tabId) || []) : [];
        sendResponse({ videos: videos, count: videos.length });
        return true;
    }
    
    if (message.action === 'clearVideos') {
        const tabId = message.tabId || (sender.tab && sender.tab.id);
        if (tabId) cleanupTab(tabId);
        else {
            tabVideos.clear();
            tabSeenUrls.clear();
            tabHeadersCaptured.clear();
            requestHeadersMap.clear();
        }
        browserAPI.storage.local.set({ detectedVideos: [], lastUpdate: Date.now(), count: 0 });
        sendResponse({ cleared: true });
        return true;
    }
    
    // WS Related Messages
    if (message.action === 'wsGetStatus') {
        browserAPI.storage.local.get(['pb_ip', 'pb_pin']).then(res => {
            sendResponse({ 
                status: wsStatus, 
                ip: res.pb_ip || '', 
                pin: res.pb_pin || '' 
            });
        });
        return true;
    }
    
    if (message.action === 'wsConnect') {
        connectWebSocket(message.ip, message.pin);
        sendResponse({ connecting: true });
        return true;
    }
    
    if (message.action === 'wsDisconnect') {
        if (wsConnection) {
            wsConnection.close();
            // Clear settings so it doesn't auto-connect
            browserAPI.storage.local.remove(['pb_ip', 'pb_pin']);
        }
        sendResponse({ disconnected: true });
        return true;
    }
    
    if (message.action === 'wsPlayOnTv') {
        const tabId = sender.tab ? sender.tab.id : message.tabId;
        const videos = tabId ? getTabVideos(tabId) : [];
        const video = videos.find(v => v.url === message.url) || message.video;
        
        if (video) {
            // Include subtitle explicitly from message if selected
            if (message.subtitleUrl) {
                video.subtitles = [message.subtitleUrl];
            }
            
            const success = sendTvPlayCommand(video);
            sendResponse({ success: success, reason: success ? null : "Not connected to TV" });
        } else {
            sendResponse({ success: false, reason: "Video not found" });
        }
        // Send a synchronous-like response using sendResponse to prevent error output
        // although true is returned, we called sendResponse before returning.
        return true;
    }
    
    return false;
});
