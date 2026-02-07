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
    return true;
});

console.log('[VideoDetector Content] Loaded');
