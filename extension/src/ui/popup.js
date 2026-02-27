const browserAPI = (typeof browser !== 'undefined') ? browser : chrome;

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

const tvIpInput = document.getElementById('tv-ip');
const tvPinInput = document.getElementById('tv-pin');
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

// Background Communication
function loadVideos() {
    // Since this runs in an iframe injected into the content page,
    // we can simply send a message to the background script.
    // The background script will use sender.tab.id automatically.
    browserAPI.runtime.sendMessage({ action: 'getVideos' }).then(response => {
        if (response) {
            currentVideos = response.videos || [];
            renderVideos();
        }
    }).catch(err => console.error("Error loading videos:", err));
}

function loadStatus() {
    browserAPI.runtime.sendMessage({ action: 'wsGetStatus' }).then(response => {
        if (response) {
            updateStatusUI(response.status);
            if (response.ip) tvIpInput.value = response.ip;
            if (response.pin) tvPinInput.value = response.pin;
        }
    });
    
    // Load UI visibility state
    browserAPI.storage.local.get(['showPlayOverlay'], function(result) {
        if (result.showPlayOverlay !== undefined) {
            showOverlayToggle.checked = result.showPlayOverlay;
        } else {
            // Default to true if not set
            showOverlayToggle.checked = true;
            browserAPI.storage.local.set({ showPlayOverlay: true });
        }
    });
}

// UI Renderers
function updateStatusUI(status) {
    statusDot.className = `dot mx-${status}`;
    
    if (status === 'connected') {
        statusText.textContent = 'Connected';
        statusText.style.color = 'var(--success)';
        connectBtn.classList.add('hidden');
        disconnectBtn.classList.remove('hidden');
        tvIpInput.disabled = true;
        tvPinInput.disabled = true;
    } else if (status === 'connecting') {
        statusText.textContent = 'Connecting...';
        statusText.style.color = 'var(--accent)';
        connectBtn.classList.add('hidden');
        disconnectBtn.classList.add('hidden');
        tvIpInput.disabled = true;
        tvPinInput.disabled = true;
    } else {
        statusText.textContent = 'Disconnected';
        statusText.style.color = 'var(--danger)';
        connectBtn.classList.remove('hidden');
        disconnectBtn.classList.add('hidden');
        tvIpInput.disabled = false;
        tvPinInput.disabled = false;
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
            
            item.innerHTML = `
                <div class="vid-url" title="${video.url}">${video.url}</div>
                <div class="vid-meta">
                    <span class="vid-type">${typeStr}</span>
                    <span>${detectStr}</span>
                </div>
            `;
            
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
                selectedVideoUrl = video.url;
                document.querySelectorAll('#videos-list .video-item').forEach(el => el.classList.remove('selected'));
                item.classList.add('selected');
            });
            
            videosList.appendChild(item);
        });
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
            
            let previewHtml = '';
            if (sub.subtitlePreview) {
                previewHtml = `<div class="vid-preview">${sub.subtitlePreview}</div>`;
            }
            
            item.innerHTML = `
                <div class="vid-url" title="${sub.url}">${sub.url}</div>
                ${previewHtml}
                <div class="vid-meta">
                    <span class="vid-type">${sub.contentType || 'Subtitle'}</span>
                    <span>${sub.detectedBy || 'unknown'}</span>
                </div>
            `;
            
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
    }
}

// User Actions
showOverlayToggle.addEventListener('change', (e) => {
    browserAPI.storage.local.set({ showPlayOverlay: e.target.checked });
});

masterPlayBtn.addEventListener('click', () => {
    if (!selectedVideoUrl) {
        showToast('Please select a video first');
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
    
    browserAPI.runtime.sendMessage({ 
        action: 'wsPlayOnTv', 
        video: videoPayload,
        subtitleUrl: selectedSubtitleUrl
    }).then(res => {
        if (res && res.success) {
            showToast('Playing on TV');
        } else {
            if (res && res.reason === "Not connected to TV") {
                showToast('Please connect to TV first');
                document.querySelector('.tab-btn[data-tab="settings"]').click();
            } else {
                showToast('Error: ' + (res?.reason || 'Unknown'));
            }
        }
    }).catch(err => console.error("Error sending play command:", err));
});
connectBtn.addEventListener('click', () => {
    const ip = tvIpInput.value.trim();
    const pin = tvPinInput.value.trim();
    
    if (!ip) {
        showToast('Please enter an IP address');
        return;
    }
    
    browserAPI.runtime.sendMessage({
        action: 'wsConnect',
        ip: ip,
        pin: pin
    });
    updateStatusUI('connecting');
});

disconnectBtn.addEventListener('click', () => {
    browserAPI.runtime.sendMessage({ action: 'wsDisconnect' });
    updateStatusUI('disconnected');
});

// Listen for updates from background
browserAPI.runtime.onMessage.addListener((message) => {
    if (message.type === 'ws_status_update') {
        updateStatusUI(message.status);
    } else if (message.type === 'video_detected') {
        // Reload videos if a new one is detected while popup is open
        loadVideos();
    }
});

// Init
window.addEventListener('DOMContentLoaded', () => {
    loadStatus();
    loadVideos();
});
