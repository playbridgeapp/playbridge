// PlayBridge Unified Video Detector - Background Script
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
browser.tabs.onRemoved.addListener((tabId) => {
    cleanupTab(tabId);
});

// Clear on navigation to a new page in the same tab
function handleNavigation(details) {
    if (details.frameId === 0) { // Main frame only
        console.log(`[VideoDetector BG] Clearing videos for tab ${details.tabId} due to navigation: ${details.url}`);
        cleanupTab(details.tabId);
        // Inform content script to hide/reset UI
        browser.tabs.sendMessage(details.tabId, { type: 'clear_videos' }).catch((e) => {
            console.log(`[VideoDetector BG] Failed to send clear_videos to tab ${details.tabId}:`, e.message);
        });
    }
}

if (browser.webNavigation) {
    if (browser.webNavigation.onCommitted) {
        browser.webNavigation.onCommitted.addListener(handleNavigation);
    }
    if (browser.webNavigation.onHistoryStateUpdated) {
        browser.webNavigation.onHistoryStateUpdated.addListener(handleNavigation);
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
    if (seenUrls.size > 500) {
        seenUrls.delete(seenUrls.keys().next().value);
    }

    if (hasHeaders) {
        headersCaptured.add(video.url);
        if (headersCaptured.size > 500) {
            headersCaptured.delete(headersCaptured.keys().next().value);
        }
        video.headers = headers;
    }

    const videos = getTabVideos(tabId);
    const existingIndex = videos.findIndex(v => v.url === video.url);
    if (existingIndex !== -1) {
        videos[existingIndex] = { ...videos[existingIndex], ...video };
    } else {
        videos.push(video);
        if (videos.length > 50) {
            videos.shift(); // Keep only last 50 unique videos per tab
        }
    }

    console.log(`[VideoDetector BG] VIDEO DETECTED tab=${tabId} (Headers: ${hasHeaders}): ${video.url.substring(0, 80)}`);

    if (tabId && tabId > 0) {
        browser.tabs.sendMessage(tabId, {
            type: 'video_detected',
            ...video
        }).catch(() => {});
    }
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
        fetch(videoData.url, { method: 'GET', headers: { 'Range': 'bytes=0-1500' } })
            .then(res => res.text())
            .then(text => {
                // Split lines
                const lines = text.split(/\r?\n/);
                const validLines = [];
                
                for (let i = 0; i < lines.length; i++) {
                    const line = lines[i].trim();
                    // Skip empty lines
                    if (!line) continue;
                    // Skip WEBVTT header
                    if (line === 'WEBVTT' || line.startsWith('Kind:') || line.startsWith('Language:')) continue;
                    // Skip sequence numbers (just digits)
                    if (/^\d+$/.test(line)) continue;
                    // Skip timestamps (e.g. 00:00:20,000 --> 00:00:24,400 or 00:00.000 --> ...)
                    if (line.includes('-->')) continue;
                    
                    // It's a text line, strip HTML tags (like <i>, <b>, <font>)
                    const cleanLine = line.replace(/<[^>]+>/g, '').trim();
                    if (cleanLine) {
                        validLines.push(cleanLine);
                    }
                    
                    // Allow up to 6 lines of preview
                    if (validLines.length >= 6) break;
                }
                
                if (validLines.length > 0) {
                    videoData.subtitlePreview = validLines.join(' • ') + "...";
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

// 2. Confirm Video & Merge Headers
let extraInfoSpec = ["responseHeaders", "blocking"];

browser.webRequest.onHeadersReceived.addListener(
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

            const urlLower = details.url.toLowerCase().split('?')[0];
            const isSegment = ['.ts', '.m4s'].some(ext => urlLower.endsWith(ext)) || urlLower.includes('/segment') || urlLower.includes('frag');
            if (isSegment) {
                if (storedData) requestHeadersMap.delete(details.requestId);
                return; // ignore fragments to save memory and CPU
            }

            const isVideoContentType = VIDEO_CONTENT_TYPES.some(type => contentType.includes(type));
            const isM3u8Url = details.url.toLowerCase().includes('m3u8');
            
            const hasVideoExtension = ['.mp4', '.mkv', '.webm', '.avi', '.mov', '.flv', '.m4v', '.wmv', '.3gp'].some(ext => urlLower.endsWith(ext));
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
let playbridgePort = 8765; // Default port, configurable via settings

// Status values: disconnected, connecting, connected
let wsStatus = 'disconnected'; 
let intentionalDisconnect = false;
let reconnectTimeout = null;
let reconnectDelay = 5000; // Exponential backoff: 5s → 10s → 20s → 60s max
let heartbeatInterval = null;

function updateWsStatus(newStatus) {
    wsStatus = newStatus;
    const statusMsg = { type: 'ws_status_update', status: wsStatus };
    // Broadcast to extension pages (popup, options) via runtime messaging
    browser.runtime.sendMessage(statusMsg).catch(() => {});
    // Broadcast to content scripts via tabs messaging
    browser.tabs.query({}).then(tabs => {
        tabs.forEach(tab => {
            browser.tabs.sendMessage(tab.id, statusMsg).catch(() => {});
        });
    });
}

function connectWebSocket(ip, pin) {
    intentionalDisconnect = false;
    if (reconnectTimeout) {
        clearTimeout(reconnectTimeout);
        reconnectTimeout = null;
    }

    if (wsConnection) {
        // Prevent the old connection's onclose from interpreting this as a disconnect
        // that should wipe out the new connection we are about to create.
        wsConnection.onclose = null;
        wsConnection.onerror = null;
        wsConnection.close();
        wsConnection = null;
    }
    
    playbridgeIp = ip;
    playbridgePin = pin;
    
    // Save to storage
    browser.storage.local.set({ pb_ip: ip, pb_pin: pin });
    
    updateWsStatus('connecting');
    
    try {
        wsConnection = new WebSocket(`ws://${ip}:${playbridgePort}`);
        
        wsConnection.onopen = () => {
            console.log('[VideoDetector BG] WS Connected to PlayBridge TV');
            reconnectDelay = 5000; // Reset backoff on successful connect
            updateWsStatus('connected');
            
            // Send Auth
            if (pin) {
                const authMsg = {
                    type: "auth",
                    pin: pin
                };
                wsConnection.send(JSON.stringify(authMsg));
            }

            // Start client-side heartbeat to detect dead connections
            if (heartbeatInterval) clearInterval(heartbeatInterval);
            heartbeatInterval = setInterval(() => {
                if (wsConnection && wsConnection.readyState === WebSocket.OPEN) {
                    try {
                        wsConnection.send(JSON.stringify({ type: 'ping' }));
                    } catch (e) {
                        console.log('[VideoDetector BG] Heartbeat send failed, closing');
                        wsConnection.close();
                    }
                }
            }, 30000); // Ping every 30 seconds
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
            if (heartbeatInterval) { clearInterval(heartbeatInterval); heartbeatInterval = null; }
            
            if (!intentionalDisconnect) {
                console.log(`[VideoDetector BG] Unintentional disconnect, reconnecting in ${reconnectDelay / 1000}s...`);
                reconnectTimeout = setTimeout(() => {
                    if (wsStatus === 'disconnected' && !intentionalDisconnect) {
                        // Prefer pb_ip/pb_pin (set on every connectWebSocket call) over savedConnections
                        browser.storage.local.get(['pb_ip', 'pb_pin', 'savedConnections']).then(res => {
                            if (res.pb_ip) {
                                console.log('[VideoDetector BG] Auto-reconnecting to last-used', res.pb_ip);
                                connectWebSocket(res.pb_ip, res.pb_pin || '');
                            } else if (res.savedConnections && res.savedConnections.length > 0) {
                                const lastConn = res.savedConnections[0];
                                console.log('[VideoDetector BG] Auto-reconnecting to saved', lastConn.ip);
                                connectWebSocket(lastConn.ip, lastConn.pin || '');
                            }
                        });
                    }
                }, reconnectDelay);
                // Exponential backoff: 5s → 10s → 20s → 40s → 60s max
                reconnectDelay = Math.min(reconnectDelay * 2, 60000);
            }
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
browser.storage.local.get(['pb_ip', 'pb_pin', 'pb_port', 'savedConnections']).then(res => {
    if (res.pb_port) playbridgePort = res.pb_port;
    if (res.pb_ip) {
        // Auto connect on start if we have an IP
        connectWebSocket(res.pb_ip, res.pb_pin || '');
    } else if (res.savedConnections && res.savedConnections.length > 0) {
        const lastConn = res.savedConnections[0];
        console.log('[VideoDetector BG] Using last saved connection on startup:', lastConn.ip);
        connectWebSocket(lastConn.ip, lastConn.pin || '');
    }
});

// Handle messages from content scripts / popups
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
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
        sendResponse({ cleared: true });
        return true;
    }

    if (message.action === 'getCurrentTabUrl') {
        // If message comes from a content script/iframe, we can just use the sender's tab url
        if (sender.tab && sender.tab.url) {
            sendResponse({ url: sender.tab.url });
        } else {
            // Otherwise query the active tab
            browser.tabs.query({ active: true, currentWindow: true }).then(tabs => {
                const currentTab = tabs[0];
                sendResponse({ url: currentTab ? currentTab.url : null });
            }).catch(() => sendResponse({ url: null }));
        }
        return true;
    }
    
    // WS Related Messages
    if (message.action === 'wsGetStatus') {
        browser.storage.local.get(['pb_ip', 'pb_pin', 'pb_port']).then(res => {
            sendResponse({ 
                status: wsStatus, 
                ip: res.pb_ip || '', 
                pin: res.pb_pin || '',
                port: res.pb_port || 8765
            });
        });
        return true;
    }
    
    if (message.action === 'wsConnect') {
        if (message.port) {
            playbridgePort = message.port;
            browser.storage.local.set({ pb_port: message.port });
        }
        connectWebSocket(message.ip, message.pin);
        sendResponse({ connecting: true });
        return true;
    }
    
    if (message.action === 'wsDisconnect') {
        intentionalDisconnect = true;
        if (reconnectTimeout) {
            clearTimeout(reconnectTimeout);
            reconnectTimeout = null;
        }
        if (wsConnection) {
            wsConnection.close();
            // Clear settings so it doesn't auto-connect
            browser.storage.local.remove(['pb_ip', 'pb_pin']);
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
    
    if (message.action === 'wsSendToTv') {
        if (wsStatus === 'connected' && wsConnection) {
            let command;
            if (message.target === 'browser') {
                command = {
                    type: "command",
                    action: "browser",
                    payload: { url: message.url }
                };
            } else if (message.target === 'player') {
                command = {
                    type: "command",
                    action: "play",
                    payload: {
                        url: message.url,
                        title: message.url,
                        contentType: "unknown"
                    }
                };
            }
            
            if (command) {
                console.log(`[VideoDetector BG] Sending ${message.target} command to TV:`, command);
                wsConnection.send(JSON.stringify(command));
                sendResponse({ success: true, reason: null });
            } else {
                sendResponse({ success: false, reason: "Invalid target" });
            }
        } else {
            sendResponse({ success: false, reason: "Not connected to TV" });
        }
        return true;
    }
    
    return false;
});

// === Context Menu ===
browser.menus.create({
    id: "playbridge-parent",
    title: "PlayBridge",
    contexts: ["all"],
    icons: { "16": "icon.png" }
});

browser.menus.create({
    id: "playbridge-play",
    parentId: "playbridge-parent",
    title: "Play on TV",
    contexts: ["link", "video", "audio"]
});

browser.menus.create({
    id: "playbridge-open",
    parentId: "playbridge-parent",
    title: "Open on TV",
    contexts: ["all"]
});

browser.menus.onClicked.addListener((info, tab) => {
    if (wsStatus !== 'connected' || !wsConnection) {
        browser.notifications.create('pb-not-connected', {
            type: 'basic',
            iconUrl: 'icon.png',
            title: 'PlayBridge',
            message: 'Not connected to TV. Open the extension to connect.'
        });
        return;
    }

    let command;

    if (info.menuItemId === "playbridge-play") {
        const url = info.srcUrl || info.linkUrl;
        if (!url) return;
        command = {
            type: "command",
            action: "play",
            payload: { url, title: url, contentType: "unknown" }
        };
    } else if (info.menuItemId === "playbridge-open") {
        const url = info.linkUrl || info.pageUrl || (tab && tab.url);
        if (!url) return;
        command = {
            type: "command",
            action: "browser",
            payload: { url }
        };
    }

    if (command) {
        console.log('[PlayBridge] Context menu →', command);
        wsConnection.send(JSON.stringify(command));
    }
});
