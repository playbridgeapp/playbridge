// Content script - runs in the context of web pages
// Receives video info from background and communicates with native app via URL hash

let videos = [];
let seenUrls = new Set();

// Listen for messages from background script
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === 'video_detected') {
        console.log('[VideoDetector Content] Received:', message.url.substring(0, 60));

        // Check if we already have this URL
        if (!seenUrls.has(message.url)) {
            seenUrls.add(message.url);

            const video = {
                url: message.url,
                contentType: message.contentType || '',
                detectedBy: message.detectedBy || 'unknown',
                timestamp: message.timestamp || Date.now()
            };

            videos.push(video);
            const count = videos.length;

            console.log('[VideoDetector Content] Total: ' + count + ', signaling native...');

            // Method 1: Update title with count (for signaling)
            const originalTitle = document.title.replace(/\s*\[PlayBridge:\d+\]$/, '');
            document.title = originalTitle + ' [PlayBridge:' + count + ']';

            // Method 2: Store in localStorage (backup)
            try {
                localStorage.setItem('playbridge_videos', JSON.stringify(videos));
                localStorage.setItem('playbridge_video_count', count.toString());
            } catch (e) { }

            // Method 3: Trigger hash change with encoded video data
            // This will be picked up by onLocationChange observer
            try {
                const encodedVideo = encodeURIComponent(JSON.stringify(video));
                const beacon = '#playbridge-video=' + encodedVideo;

                // Temporarily change hash to signal new video (don't change history)
                const oldHash = window.location.hash;
                window.location.replace(window.location.href.split('#')[0] + beacon);

                // Restore original hash after a brief moment
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
