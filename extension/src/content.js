// PlayBridge Unified Video Detector - Content Script
// Receives video info from background and communicates with native app or desktop UI

let videos = [];
let seenUrls = new Set();
let uiInjected = false;
let fabVisible = true;

browser.storage.local.get(['showPlayOverlay'], function(result) {
    if (result.showPlayOverlay === false) {
        fabVisible = false;
    }
});

function injectDesktopUI() {
    if (uiInjected || !fabVisible) return;
    
    const fabContainer = document.createElement('div');
    fabContainer.id = 'playbridge-ext-fab-container';
    
    // Create floating button
    const fab = document.createElement('div');
    fab.id = 'playbridge-ext-fab';
    fab.title = "Play on TV";
    fab.innerHTML = `
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="white" width="24px" height="24px">
            <path d="M0 0h24v24H0V0z" fill="none"/>
            <path d="M21 3H3c-1.11 0-2 .89-2 2v12c0 1.1.89 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.11-.9-2-2-2zm0 14H3V5h18v12zm-5-6l-7 4V7z"/>
        </svg>
    `;
    
    // Create hide button
    const hideBtn = document.createElement('div');
    hideBtn.id = 'playbridge-ext-hide';
    hideBtn.title = "Hide Button Permanently";
    hideBtn.innerHTML = `
        <svg xmlns="http://www.w3.org/2000/svg" height="18" viewBox="0 0 24 24" width="18" fill="white">
            <path d="M0 0h24v24H0V0z" fill="none"/>
            <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12 19 6.41z"/>
        </svg>
    `;
    
    fabContainer.appendChild(fab);
    fabContainer.appendChild(hideBtn);
    
    // Create iframe for popup
    const popupFrame = document.createElement('iframe');
    popupFrame.id = 'playbridge-ext-popup';
    popupFrame.src = browser.runtime.getURL('ui/popup.html');
    popupFrame.allowTransparency = "true";
    
    // Styles
    const style = document.createElement('style');
    style.textContent = `
        #playbridge-ext-fab-container {
            position: fixed;
            bottom: 24px;
            right: 24px;
            z-index: 2147483647;
            display: flex;
            align-items: flex-end;
            gap: 8px;
        }
        #playbridge-ext-fab {
            width: 56px;
            height: 56px;
            background-color: #fca311;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            box-shadow: 0 4px 12px rgba(0,0,0,0.3);
            transition: transform 0.2s, background-color 0.2s;
        }
        #playbridge-ext-fab:hover {
            transform: scale(1.05);
            background-color: #ffb703;
        }
        #playbridge-ext-hide {
            width: 24px;
            height: 24px;
            background-color: rgba(0,0,0,0.5);
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            opacity: 0;
            transition: opacity 0.2s, background-color 0.2s;
            margin-bottom: 2px;
        }
        #playbridge-ext-fab-container:hover #playbridge-ext-hide {
            opacity: 1;
        }
        #playbridge-ext-hide:hover {
            background-color: #ff4757;
        }
        #playbridge-ext-popup {
            position: fixed;
            bottom: 90px;
            right: 24px;
            width: 360px;
            height: 500px;
            max-height: calc(100vh - 120px);
            border: none;
            border-radius: 12px;
            box-shadow: 0 8px 32px rgba(0,0,0,0.4);
            z-index: 2147483647;
            display: none;
            background: transparent;
        }
        #playbridge-ext-popup.visible {
            display: block;
        }
        @media (max-width: 400px) {
            #playbridge-ext-popup {
                width: calc(100vw - 48px);
            }
        }
    `;
    
    document.head.appendChild(style);
    document.body.appendChild(popupFrame);
    document.body.appendChild(fabContainer);
    
    // Toggle popup
    fab.addEventListener('click', () => {
        popupFrame.classList.toggle('visible');
    });
    
    // Hide deeply
    hideBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        browser.storage.local.set({ showPlayOverlay: false });
        fabContainer.style.display = 'none';
        popupFrame.classList.remove('visible');
    });
    
    // Hide popup when clicking outside
    document.addEventListener('click', (e) => {
        if (!fabContainer.contains(e.target) && popupFrame.classList.contains('visible')) {
            popupFrame.classList.remove('visible');
        }
    });

    // Listen for messages from iframe to close popup
    window.addEventListener('message', (event) => {
        if (event.data && event.data.action === 'pb_close_popup') {
            popupFrame.classList.remove('visible');
        }
    });
    
    uiInjected = true;
}

browser.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.showPlayOverlay !== undefined) {
        fabVisible = changes.showPlayOverlay.newValue;
        const container = document.getElementById('playbridge-ext-fab-container');
        if (fabVisible) {
            if (!uiInjected && videos.length > 0) {
                injectDesktopUI();
            } else if (container) {
                container.style.display = 'flex';
            }
        } else if (container) {
            container.style.display = 'none';
            const popup = document.getElementById('playbridge-ext-popup');
            if (popup) popup.classList.remove('visible');
        }
    }
});

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === 'video_detected') {
        const existingIndex = videos.findIndex(v => v.url === message.url);

        if (existingIndex === -1) {
            videos.push({
                url: message.url,
                contentType: message.contentType || '',
                detectedBy: message.detectedBy || 'unknown',
                originUrl: message.originUrl,
                headers: message.headers || {},
                timestamp: message.timestamp || Date.now()
            });
            if (videos.length > 50) {
                videos.shift(); // Keep only last 50
            }
            
            seenUrls.add(message.url);
            if (seenUrls.size > 500) {
                seenUrls.delete(seenUrls.keys().next().value);
            }
            
            // Inject UI when videos are detected
            injectDesktopUI();
        } else {
            const newHeaders = message.headers || {};
            if (Object.keys(newHeaders).length > 0) {
                videos[existingIndex] = {
                    ...videos[existingIndex],
                    headers: newHeaders,
                    contentType: message.contentType || videos[existingIndex].contentType
                };
            }
        }

        sendResponse({ received: true, count: videos.length });
    } else if (message.type === 'clear_videos') {
        videos = [];
        seenUrls.clear();
        
        // Hide Desktop UI
        const fabContainer = document.getElementById('playbridge-ext-fab-container');
        const popupFrame = document.getElementById('playbridge-ext-popup');
        
        if (fabContainer) fabContainer.style.display = 'none';
        if (popupFrame) popupFrame.classList.remove('visible');
        
        // Tell iframe to clear its state if open
        const popupWindow = popupFrame ? popupFrame.contentWindow : null;
        if (popupWindow) {
            popupWindow.postMessage({ action: 'pb_videos_cleared' }, '*');
        }

        sendResponse({ cleared: true });
    }
    return true;
});

console.log('[VideoDetector Content] Loaded');
