// DOM Elements
const tabBtns = document.querySelectorAll('.tab-btn');
const tabContents = document.querySelectorAll('.tab-content');
const closeBtn = document.getElementById('close-btn');

const videosList = document.getElementById('videos-list');
const noVideosMsg = document.getElementById('no-videos-msg');

const subtitlesList = document.getElementById('subtitles-list');
const noSubtitlesMsg = document.getElementById('no-subtitles-msg');
const masterPlayBtn = document.getElementById('master-play-btn');
const actionBar = document.getElementById('action-bar');

const openCurrentTabBtn = document.getElementById('open-current-tab-btn');
const customUrlInput = document.getElementById('custom-url-input');
const openUrlBrowserBtn = document.getElementById('open-url-browser-btn');
const openUrlPlayerBtn = document.getElementById('open-url-player-btn');

const tvIpInput = document.getElementById('tv-ip');
const tvPinInput = document.getElementById('tv-pin');
const tvPortInput = document.getElementById('tv-port');
const connectBtn = document.getElementById('connect-btn');
const disconnectBtn = document.getElementById('disconnect-btn');

const showOverlayToggle = document.getElementById('show-overlay-toggle');

const statusDot = document.getElementById('status-dot');
const statusText = document.getElementById('status-text');
const toastEl = document.getElementById('toast');

// State
let currentVideos = [];
let videoItems = [];
let subtitleItems = [];
let selectedVideoUrl = null;
let selectedSubtitleUrl = null;
let savedConnections = [];

// Navigation
tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        tabBtns.forEach(b => b.classList.remove('active'));
        tabContents.forEach(c => c.classList.remove('active'));
        
        btn.classList.add('active');
        document.getElementById(`${btn.dataset.tab}-tab`).classList.add('active');
    });
});

closeBtn.addEventListener('click', () => {
    // Send message to content script to hide iframe
    window.parent.postMessage({ action: 'pb_close_popup' }, '*');
});

// Toast
let toastTimeout;
function showToast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.remove('hidden');
    toastEl.classList.add('show');
    clearTimeout(toastTimeout);
    toastTimeout = setTimeout(() => {
        toastEl.classList.remove('show');
        setTimeout(() => toastEl.classList.add('hidden'), 300);
    }, 2000);
}

// Clipboard helper (navigator.clipboard doesn't work inside iframes)
function copyToClipboard(text) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    try {
        document.execCommand('copy');
        showToast('URL copied');
    } catch (e) {
        showToast('Copy failed');
    }
    document.body.removeChild(textarea);
}

// Background Communication
function loadVideos() {
    if (browser.tabs && browser.tabs.query) {
        browser.tabs.query({ active: true, currentWindow: true }).then(tabs => {
            const currentTab = tabs[0];
            const msg = { action: 'getVideos' };
            if (currentTab) msg.tabId = currentTab.id;
            
            browser.runtime.sendMessage(msg).then(response => {
                if (response) {
                    currentVideos = response.videos || [];
                    renderVideos();
                }
            }).catch(err => console.error("Error loading videos:", err));
        }).catch(() => {
            // Fallback for Firefox if tabs.query fails in some context
            browser.runtime.sendMessage({ action: 'getVideos' }).then(response => {
                if (response) {
                    currentVideos = response.videos || [];
                    renderVideos();
                }
            });
        });
    } else {
        browser.runtime.sendMessage({ action: 'getVideos' }).then(response => {
            if (response) {
                currentVideos = response.videos || [];
                renderVideos();
            }
        }).catch(err => console.error("Error loading videos:", err));
    }
}

function loadStatus() {
    browser.runtime.sendMessage({ action: 'wsGetStatus' }).then(response => {
        if (response) {
            updateStatusUI(response.status);
            if (response.ip) tvIpInput.value = response.ip;
            if (response.pin) tvPinInput.value = response.pin;
            if (response.port && tvPortInput) tvPortInput.value = response.port;
        }
    });
    
    // Load UI visibility state and saved connections
    browser.storage.local.get(['showPlayOverlay', 'savedConnections'], function(result) {
        if (result.showPlayOverlay !== undefined) {
            showOverlayToggle.checked = result.showPlayOverlay;
        } else {
            // Default to true if not set
            showOverlayToggle.checked = true;
            browser.storage.local.set({ showPlayOverlay: true });
        }
        
        if (result.savedConnections) {
            savedConnections = result.savedConnections;
            renderSavedConnections();
        }
    });
}

function saveConnection(ip, pin) {
    // Remove if already exists to move to top
    savedConnections = savedConnections.filter(c => !(c.ip === ip && c.pin === pin));
    
    // Add to top
    savedConnections.unshift({ ip, pin });
    
    // Keep only last 5
    if (savedConnections.length > 5) {
        savedConnections = savedConnections.slice(0, 5);
    }
    
    browser.storage.local.set({ savedConnections: savedConnections });
    renderSavedConnections();
}

// UI Renderers
function renderSavedConnections() {
    const container = document.getElementById('saved-connections-container');
    const list = document.getElementById('saved-connections-list');
    
    if (!savedConnections || savedConnections.length === 0) {
        container.style.display = 'none';
        return;
    }
    
    container.style.display = 'block';
    list.innerHTML = '';
    
    savedConnections.forEach((conn, index) => {
        const item = document.createElement('div');
        item.className = 'saved-connection-item';
        
        item.innerHTML = `
            <div class="saved-connection-info">
                <span class="saved-connection-ip">${conn.ip}</span>
                <span class="saved-connection-pin">PIN: ${conn.pin ? '*'.repeat(conn.pin.length) : 'None'}</span>
            </div>
            <button class="delete-connection-btn" title="Remove connection">
                <svg xmlns="http://www.w3.org/2000/svg" height="18" viewBox="0 0 24 24" width="18" fill="currentColor"><path d="M0 0h24v24H0z" fill="none"/><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>
            </button>
        `;
        
        // Auto-fill and connect on click
        item.addEventListener('click', () => {
            tvIpInput.value = conn.ip;
            tvPinInput.value = conn.pin;
            
            // Connect directly
            browser.runtime.sendMessage({
                action: 'wsConnect',
                ip: conn.ip,
                pin: conn.pin
            });
            updateStatusUI('connecting');
        });
        
        // Delete connection
        item.querySelector('.delete-connection-btn').addEventListener('click', (e) => {
            e.stopPropagation();
            savedConnections.splice(index, 1);
            browser.storage.local.set({ savedConnections: savedConnections });
            renderSavedConnections();
        });
        
        list.appendChild(item);
    });
}

function updateStatusUI(status) {
    statusDot.className = `dot mx-${status}`;
    
    if (status === 'connected') {
        statusText.textContent = 'Connected';
        statusText.style.color = 'var(--success)';
        connectBtn.classList.add('hidden');
        disconnectBtn.classList.remove('hidden');
        tvIpInput.disabled = true;
        tvPinInput.disabled = true;
        if (tvPortInput) tvPortInput.disabled = true;
        
        // Save successful connection
        if (tvIpInput.value && tvPinInput.value) {
            saveConnection(tvIpInput.value, tvPinInput.value);
        }
    } else if (status === 'connecting') {
        statusText.textContent = 'Connecting...';
        statusText.style.color = 'var(--accent)';
        connectBtn.classList.add('hidden');
        disconnectBtn.classList.add('hidden');
        tvIpInput.disabled = true;
        tvPinInput.disabled = true;
        if (tvPortInput) tvPortInput.disabled = true;
    } else {
        statusText.textContent = 'Disconnected';
        statusText.style.color = 'var(--danger)';
        connectBtn.classList.remove('hidden');
        disconnectBtn.classList.add('hidden');
        tvIpInput.disabled = false;
        tvPinInput.disabled = false;
        if (tvPortInput) tvPortInput.disabled = false;
    }
}

function renderVideos() {
    videoItems = currentVideos.filter(v => 
        v.detectedBy !== 'subtitle_extension' && !v.url.endsWith('.srt') && !v.url.endsWith('.vtt')
    );
    subtitleItems = currentVideos.filter(v => 
        v.detectedBy === 'subtitle_extension' || v.url.endsWith('.srt') || v.url.endsWith('.vtt')
    );
    
    // Priority Sort: M3U8 > MP4 > others
    videoItems.sort((a, b) => {
        const aM3u8 = a.url.includes('m3u8');
        const bM3u8 = b.url.includes('m3u8');
        if (aM3u8 && !bM3u8) return -1;
        if (!aM3u8 && bM3u8) return 1;
        const aMp4 = a.url.includes('.mp4');
        const bMp4 = b.url.includes('.mp4');
        if (aMp4 && !bMp4) return -1;
        if (!aMp4 && bMp4) return 1;
        return Math.sign(b.timestamp - a.timestamp);
    });

    if (!selectedVideoUrl && videoItems.length > 0) {
        selectedVideoUrl = videoItems[0].url; // Select first by default
    }

    if (videoItems.length === 0) {
        noVideosMsg.classList.remove('hidden');
        videosList.classList.add('hidden');
        actionBar.classList.add('hidden');
    } else {
        noVideosMsg.classList.add('hidden');
        videosList.classList.remove('hidden');
        actionBar.classList.remove('hidden');
        videosList.innerHTML = '';

        videoItems.forEach((video) => {
            const item = document.createElement('div');
            item.className = 'video-item' + (selectedVideoUrl === video.url ? ' selected' : '');
            
            const typeStr = video.contentType || 'Unknown Type';
            const detectStr = video.detectedBy || 'unknown';
            
            // URL display (safe textContent)
            const urlDiv = document.createElement('div');
            urlDiv.className = 'vid-url-row';
            const urlText = document.createElement('div');
            urlText.className = 'vid-url';
            urlText.title = video.url;
            urlText.textContent = video.url;
            const copyBtn = document.createElement('button');
            copyBtn.className = 'copy-url-btn';
            copyBtn.title = 'Copy URL';
            copyBtn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" height="14" viewBox="0 0 24 24" width="14" fill="currentColor"><path d="M0 0h24v24H0z" fill="none"/><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>';
            copyBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                copyToClipboard(video.url);
            });
            urlDiv.appendChild(urlText);
            urlDiv.appendChild(copyBtn);
            item.appendChild(urlDiv);

            // Meta row (safe textContent)
            const metaDiv = document.createElement('div');
            metaDiv.className = 'vid-meta';
            const typeSpan = document.createElement('span');
            typeSpan.className = 'vid-type';
            typeSpan.textContent = typeStr;
            const detectSpan = document.createElement('span');
            detectSpan.textContent = detectStr;
            metaDiv.appendChild(typeSpan);
            metaDiv.appendChild(detectSpan);
            item.appendChild(metaDiv);
            
            // Qualities Dropdown for HLS
            if (video.qualities && video.qualities.length > 0) {
                const select = document.createElement('select');
                select.className = 'quality-select';
                
                const autoOpt = document.createElement('option');
                autoOpt.value = 'auto';
                autoOpt.textContent = 'Auto (Master Playlist)';
                select.appendChild(autoOpt);
                
                video.qualities.forEach((q, idx) => {
                    const opt = document.createElement('option');
                    opt.value = idx.toString();
                    opt.textContent = `${q.resolution} (${Math.round(q.bandwidth / 1024)} kbps)`;
                    select.appendChild(opt);
                });
                
                select.addEventListener('click', e => e.stopPropagation()); // prevent row select toggle
                item.appendChild(select);
            }
            
            item.addEventListener('click', () => {
                if (selectedVideoUrl === video.url) {
                    selectedVideoUrl = null;
                    item.classList.remove('selected');
                } else {
                    selectedVideoUrl = video.url;
                    document.querySelectorAll('#videos-list .video-item').forEach(el => el.classList.remove('selected'));
                    item.classList.add('selected');
                }
            });
            
            videosList.appendChild(item);
        });

        // Action bar: Deselect + Clear All
        const listActions = document.createElement('div');
        listActions.className = 'list-actions';
        const deselectBtn = document.createElement('button');
        deselectBtn.className = 'list-action-btn';
        deselectBtn.textContent = 'Deselect';
        deselectBtn.addEventListener('click', () => {
            selectedVideoUrl = null;
            document.querySelectorAll('#videos-list .video-item').forEach(el => el.classList.remove('selected'));
        });
        const clearBtn = document.createElement('button');
        clearBtn.className = 'list-action-btn danger';
        clearBtn.textContent = 'Clear All';
        clearBtn.addEventListener('click', () => {
            browser.runtime.sendMessage({ action: 'clearVideos' }).then(() => {
                currentVideos = [];
                videoItems = [];
                subtitleItems = [];
                selectedVideoUrl = null;
                selectedSubtitleUrl = null;
                renderVideos();
                showToast('Videos cleared');
            });
        });
        listActions.appendChild(deselectBtn);
        listActions.appendChild(clearBtn);
        videosList.appendChild(listActions);
    }

    if (subtitleItems.length === 0) {
        noSubtitlesMsg.classList.remove('hidden');
        subtitlesList.classList.add('hidden');
    } else {
        noSubtitlesMsg.classList.add('hidden');
        subtitlesList.classList.remove('hidden');
        subtitlesList.innerHTML = '';

        subtitleItems.forEach((sub) => {
            const item = document.createElement('div');
            item.className = 'video-item' + (selectedSubtitleUrl === sub.url ? ' selected' : '');
            
            // URL display (safe textContent)
            const urlDiv = document.createElement('div');
            urlDiv.className = 'vid-url-row';
            const urlText = document.createElement('div');
            urlText.className = 'vid-url';
            urlText.title = sub.url;
            urlText.textContent = sub.url;
            const copyBtn = document.createElement('button');
            copyBtn.className = 'copy-url-btn';
            copyBtn.title = 'Copy URL';
            copyBtn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" height="14" viewBox="0 0 24 24" width="14" fill="currentColor"><path d="M0 0h24v24H0z" fill="none"/><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>';
            copyBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                copyToClipboard(sub.url);
            });
            urlDiv.appendChild(urlText);
            urlDiv.appendChild(copyBtn);
            item.appendChild(urlDiv);

            // Preview
            if (sub.subtitlePreview) {
                const previewDiv = document.createElement('div');
                previewDiv.className = 'vid-preview';
                previewDiv.textContent = sub.subtitlePreview;
                item.appendChild(previewDiv);
            }

            // Meta row (safe textContent)
            const metaDiv = document.createElement('div');
            metaDiv.className = 'vid-meta';
            const typeSpan = document.createElement('span');
            typeSpan.className = 'vid-type';
            typeSpan.textContent = sub.contentType || 'Subtitle';
            const detectSpan = document.createElement('span');
            detectSpan.textContent = sub.detectedBy || 'unknown';
            metaDiv.appendChild(typeSpan);
            metaDiv.appendChild(detectSpan);
            item.appendChild(metaDiv);
            
            item.addEventListener('click', () => {
                if (selectedSubtitleUrl === sub.url) {
                    selectedSubtitleUrl = null;
                    item.classList.remove('selected');
                } else {
                    selectedSubtitleUrl = sub.url;
                    document.querySelectorAll('#subtitles-list .video-item').forEach(el => el.classList.remove('selected'));
                    item.classList.add('selected');
                }
            });
            
            subtitlesList.appendChild(item);
        });

        // Action bar: Deselect + Clear All
        const listActions = document.createElement('div');
        listActions.className = 'list-actions';
        const deselectBtn = document.createElement('button');
        deselectBtn.className = 'list-action-btn';
        deselectBtn.textContent = 'Deselect';
        deselectBtn.addEventListener('click', () => {
            selectedSubtitleUrl = null;
            document.querySelectorAll('#subtitles-list .video-item').forEach(el => el.classList.remove('selected'));
        });
        const clearBtn = document.createElement('button');
        clearBtn.className = 'list-action-btn danger';
        clearBtn.textContent = 'Clear All';
        clearBtn.addEventListener('click', () => {
            browser.runtime.sendMessage({ action: 'clearVideos' }).then(() => {
                currentVideos = [];
                videoItems = [];
                subtitleItems = [];
                selectedVideoUrl = null;
                selectedSubtitleUrl = null;
                renderVideos();
                showToast('Subtitles cleared');
            });
        });
        listActions.appendChild(deselectBtn);
        listActions.appendChild(clearBtn);
        subtitlesList.appendChild(listActions);
    }
}

// User Actions
showOverlayToggle.addEventListener('change', (e) => {
    browser.storage.local.set({ showPlayOverlay: e.target.checked });
});

// Utility to wait for connection
function ensureConnected() {
    return new Promise((resolve) => {
        // We know that if a user just clicked connect, they want to connect.
        // Even if it says "Disconnected", it might be an intermediate state 
        // because bg script closes the old connection and emits 'disconnected' first.

        if (statusText.textContent === 'Connected') {
            resolve(true);
            return;
        }
        
        // Wait up to 3 seconds for connection regardless of current text, 
        // as we might be in the middle of a disconnect-then-reconnect cycle
        let timeout;
        
        const listener = (message) => {
            if (message.type === 'ws_status_update') {
                if (message.status === 'connected') {
                    browser.runtime.onMessage.removeListener(listener);
                    clearTimeout(timeout);
                    resolve(true);
                }
                // We IGNORE 'disconnected' and 'connecting' events here, 
                // we ONLY care if it successfully connects within 3s.
                // If it fails to connect, the 3s timeout will catch it.
            }
        };
        
        browser.runtime.onMessage.addListener(listener);
        
        timeout = setTimeout(() => {
            browser.runtime.onMessage.removeListener(listener);
            resolve(statusText.textContent === 'Connected');
        }, 3000);
    });
}

masterPlayBtn.addEventListener('click', async () => {
    if (!selectedVideoUrl) {
        showToast('Please select a video first');
        return;
    }
    
    // Wait for connection if currently connecting
    if (statusText.textContent === 'Connecting...') {
        showToast('Waiting for TV to connect...');
    }
    const isConnected = await ensureConnected();
    if (!isConnected) {
        showToast('Please connect to TV first');
        return;
    }
    
    const videoObj = videoItems.find(v => v.url === selectedVideoUrl);
    if (!videoObj) return;

    let urlToSend = selectedVideoUrl;
    
    // Check if quality selected
    if (videoObj.qualities && videoObj.qualities.length > 0) {
        const itemEls = document.querySelectorAll('#videos-list .video-item');
        const itemEl = Array.from(itemEls).find(el => el.querySelector('.vid-url')?.title === selectedVideoUrl);
        if (itemEl) {
            const selectEl = itemEl.querySelector('.quality-select');
            if (selectEl && selectEl.value !== 'auto') {
                const qIdx = parseInt(selectEl.value, 10);
                if (!isNaN(qIdx) && videoObj.qualities[qIdx]) {
                    urlToSend = videoObj.qualities[qIdx].url;
                }
            }
        }
    }
    
    const videoPayload = { ...videoObj, url: urlToSend };
    
    browser.runtime.sendMessage({ 
        action: 'wsPlayOnTv', 
        video: videoPayload,
        subtitleUrl: selectedSubtitleUrl
    }).then(res => {
        if (res && res.success) {
            showToast('Playing on TV');
        } else {
            if (res && res.reason === "Not connected to TV") {
                showToast('Please connect to TV first');
            } else {
                showToast('Error: ' + (res?.reason || 'Unknown'));
            }
        }
    }).catch(err => console.error("Error sending play command:", err));
});

openCurrentTabBtn.addEventListener('click', async () => {
    // Wait for connection if currently connecting
    if (statusText.textContent === 'Connecting...') {
        showToast('Waiting for TV to connect...');
    }
    const isConnected = await ensureConnected();
    if (!isConnected) {
        showToast('Please connect to TV first');
        return;
    }

    browser.runtime.sendMessage({ action: 'getCurrentTabUrl' }).then(res => {
        const url = res?.url;
        if (url) {
            browser.runtime.sendMessage({
                action: 'wsSendToTv',
                url: url,
                target: 'browser'
            }).then(sendRes => {
                if (sendRes && sendRes.success) {
                    showToast('Opening tab on TV');
                } else {
                    if (sendRes && sendRes.reason === "Not connected to TV") {
                        showToast('Please connect to TV first');
                    } else {
                        showToast('Error: ' + (sendRes?.reason || 'Unknown'));
                    }
                }
            }).catch(err => console.error("Error sending open command:", err));
        } else {
            showToast('Cannot get current tab URL');
        }
    }).catch(err => console.error("Error asking bg for tab url:", err));
});

openUrlBrowserBtn.addEventListener('click', async () => {
    const url = customUrlInput.value.trim();
    if (!url) {
        showToast('Please enter a URL');
        return;
    }
    
    // Wait for connection if currently connecting
    if (statusText.textContent === 'Connecting...') {
        showToast('Waiting for TV to connect...');
    }
    const isConnected = await ensureConnected();
    if (!isConnected) {
        showToast('Please connect to TV first');
        return;
    }
    
    browser.runtime.sendMessage({
        action: 'wsSendToTv',
        url: url,
        target: 'browser'
    }).then(res => {
        if (res && res.success) {
            showToast('Opening URL on TV');
        } else {
            if (res && res.reason === "Not connected to TV") {
                showToast('Please connect to TV first');
            } else {
                showToast('Error: ' + (res?.reason || 'Unknown'));
            }
        }
    }).catch(err => console.error("Error sending open command:", err));
});

openUrlPlayerBtn.addEventListener('click', async () => {
    const url = customUrlInput.value.trim();
    if (!url) {
        showToast('Please enter a URL');
        return;
    }
    
    // Wait for connection if currently connecting
    if (statusText.textContent === 'Connecting...') {
        showToast('Waiting for TV to connect...');
    }
    const isConnected = await ensureConnected();
    if (!isConnected) {
        showToast('Please connect to TV first');
        return;
    }
    
    browser.runtime.sendMessage({
        action: 'wsSendToTv',
        url: url,
        target: 'player'
    }).then(res => {
        if (res && res.success) {
            showToast('Playing URL on TV');
        } else {
            if (res && res.reason === "Not connected to TV") {
                showToast('Please connect to TV first');
            } else {
                showToast('Error: ' + (res?.reason || 'Unknown'));
            }
        }
    }).catch(err => console.error("Error sending play command:", err));
});

connectBtn.addEventListener('click', () => {
    const ip = tvIpInput.value.trim();
    const pin = tvPinInput.value.trim();
    const port = tvPortInput ? parseInt(tvPortInput.value.trim(), 10) : 8765;
    
    if (!ip) {
        showToast('Please enter an IP address');
        return;
    }
    
    if (!pin) {
        showToast('PIN is required');
        return;
    }
    
    browser.runtime.sendMessage({
        action: 'wsConnect',
        ip: ip,
        pin: pin,
        port: port || 8765
    });
    updateStatusUI('connecting');
});

disconnectBtn.addEventListener('click', () => {
    browser.runtime.sendMessage({ action: 'wsDisconnect' });
    updateStatusUI('disconnected');
});

// Listen for updates from background
browser.runtime.onMessage.addListener((message) => {
    if (message.type === 'ws_status_update') {
        updateStatusUI(message.status);
    } else if (message.type === 'video_detected') {
        // Reload videos if a new one is detected while popup is open
        loadVideos();
    }
});

// Listen for updates from the content script (iframe parent)
window.addEventListener('message', (event) => {
    if (event.data && event.data.action === 'pb_videos_cleared') {
        currentVideos = [];
        videoItems = [];
        subtitleItems = [];
        selectedVideoUrl = null;
        selectedSubtitleUrl = null;
        renderVideos();
    }
});

// Init
window.addEventListener('DOMContentLoaded', () => {
    loadStatus();
    loadVideos();
});
