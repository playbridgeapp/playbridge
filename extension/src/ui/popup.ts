// @ts-nocheck
import browser from "../browser";
import {
    effectiveHlsRole,
    filterPrimaryCastCandidates,
} from "../core/media-candidate";
import {
    VIDEO_CAST_OVERLAY_DEFAULT_POSITION,
    VIDEO_CAST_OVERLAY_STORAGE_KEYS,
    getVideoCastOverlayPreferences,
    resetVideoCastOverlaySiteOverride,
    setShowVideoCastOverlay,
    setVideoCastOverlaySiteOverride,
} from "../settings";
import {
    formatDeviceProtocol,
    isActiveDevice,
    protocolChips,
    defaultProtocol,
    filterByProtocol,
    DEVICE_PICKER_PROTOCOL_STORAGE_KEY,
} from "../core/device-protocol";

// DOM Elements
const tabBtns = document.querySelectorAll('.tab-btn');
const tabContents = document.querySelectorAll('.tab-content');
const dataConsentBanner = document.getElementById('data-consent-banner');
const reviewDataConsentBtn = document.getElementById('review-data-consent-btn');
const dataConsentCard = document.getElementById('data-consent-card');
const dataConsentSettingStatus = document.getElementById('data-consent-setting-status');
const manageDataConsentBtn = document.getElementById('manage-data-consent-btn');
const showVideoCastOverlayToggle = document.getElementById('show-video-cast-overlay');
const showVideoCastOverlaySiteToggle = document.getElementById('show-video-cast-overlay-site');
const siteOverlayHost = document.getElementById('site-overlay-host');
const siteOverlaySupported = document.getElementById('site-overlay-supported');
const siteOverlayUnavailable = document.getElementById('site-overlay-unavailable');
const siteOverlayGlobalOff = document.getElementById('site-overlay-global-off');
const siteOverlayPositionFieldset = document.getElementById('site-overlay-position-fieldset');
const resetSiteOverlayBtn = document.getElementById('reset-site-overlay');
const siteOverlayPositionInputs = document.querySelectorAll('input[name="video-cast-overlay-position"]');

const videosList = document.getElementById('videos-list');
const noVideosMsg = document.getElementById('no-videos-msg');
const noVideosTitle = noVideosMsg?.querySelector('p');
const noVideosHint = noVideosMsg?.querySelector('span');

const subtitlesList = document.getElementById('subtitles-list');
const noSubtitlesMsg = document.getElementById('no-subtitles-msg');
const masterPlayBtn = document.getElementById('master-play-btn');
const actionBar = document.getElementById('action-bar');

const openCurrentTabBtn = document.getElementById('open-current-tab-btn');
const customUrlInput = document.getElementById('custom-url-input');
const openUrlBrowserBtn = document.getElementById('open-url-browser-btn');
const openUrlPlayerBtn = document.getElementById('open-url-player-btn');

const refreshBtn = document.getElementById('refresh-btn');
const statusHint = document.getElementById('status-hint');
const devicesList = document.getElementById('devices-list');
const devicesSubtitle = document.getElementById('devices-subtitle');

const statusDot = document.getElementById('status-dot');
const statusText = document.getElementById('status-text');
const toastEl = document.getElementById('toast');

// State
let currentVideos = [];
let videoItems = [];
let subtitleItems = [];
let selectedVideoUrl = null;
let selectedSubtitleUrl = null;
let currentStatus = null;
let bridgeDevices = [];
let bridgeCapabilities = null;
let bridgeSelection = null;
let bridgeTarget = null;
let lastStatusKey = '';
let lastDevicesKey = '';
let overlayPreferences = null;
let dataConsent = { required: false, granted: true, version: 1 };
let rememberedProtocol = null;
let selectedPopupProtocol = null;

// Navigation
tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        tabBtns.forEach(b => b.classList.remove('active'));
        tabContents.forEach(c => c.classList.remove('active'));
        
        btn.classList.add('active');
        document.getElementById(`${btn.dataset.tab}-tab`).classList.add('active');
    });
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

function createCopyIcon() {
    const namespace = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(namespace, 'svg');
    svg.setAttribute('height', '14');
    svg.setAttribute('width', '14');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'currentColor');
    svg.setAttribute('aria-hidden', 'true');

    const outline = document.createElementNS(namespace, 'path');
    outline.setAttribute('d', 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z');
    svg.appendChild(outline);
    return svg;
}

// Background Communication
function loadVideos() {
    if (dataConsent.required && !dataConsent.granted) {
        currentVideos = [];
        selectedVideoUrl = null;
        selectedSubtitleUrl = null;
        renderVideos();
        return;
    }
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

function renderDataConsent(status) {
    dataConsent = status;
    const blocked = status.required && !status.granted;
    dataConsentBanner.classList.toggle('hidden', !blocked);
    dataConsentCard.classList.toggle('hidden', !status.required);
    dataConsentSettingStatus.textContent = status.granted ? 'Allowed' : 'Off';
    manageDataConsentBtn.textContent = status.granted ? 'Review or disable' : 'Review';

    tabBtns.forEach(btn => {
        btn.disabled = blocked && ['videos', 'subtitles', 'urls'].includes(btn.dataset.tab);
    });

    if (noVideosTitle) {
        noVideosTitle.textContent = blocked ? 'Media detection is off' : 'No videos detected yet';
    }
    if (noVideosHint) {
        noVideosHint.textContent = blocked
            ? 'Review media access to detect streams for casting'
            : 'Play a video on this page to detect streams';
    }

    if (blocked) {
        currentVideos = [];
        selectedVideoUrl = null;
        selectedSubtitleUrl = null;
        renderVideos();
    } else {
        loadVideos();
    }
}

async function loadDataConsent() {
    try {
        const status = await browser.runtime.sendMessage({ action: 'getDataConsent' });
        if (status && typeof status.granted === 'boolean') {
            renderDataConsent(status);
        }
    } catch (e) {
        console.error('Failed to load media data consent', e);
    }
}

function openDataConsent() {
    browser.runtime.sendMessage({ action: 'openDataConsent' }).catch(() => {
        showToast('Could not open media access settings');
    });
}

reviewDataConsentBtn?.addEventListener('click', openDataConsent);
manageDataConsentBtn?.addEventListener('click', openDataConsent);

function applyStatus(s) {
    currentStatus = s;
    bridgeCapabilities = s.capabilities || null;
    bridgeSelection = s.selection || null;
    bridgeTarget = s.target || null;

    if (devicesSubtitle) {
        if (bridgeCapabilities?.selectDevice) {
            devicesSubtitle.textContent =
                'Click a device or This computer to select where casts play. Pairing is done in the desktop app.';
        } else {
            devicesSubtitle.textContent =
                'Pairing and choosing a TV is done in the PlayBridge desktop app. This list is informational.';
        }
    }

    const selKey = JSON.stringify(s.selection || null);
    const capKey = JSON.stringify(s.capabilities || null);
    const statusKey = `${s.status}|${s.activeTv || ''}|${s.desktopConnected ? 1 : 0}|${s.target || ''}|${selKey}|${capKey}`;
    if (statusKey !== lastStatusKey) {
        lastStatusKey = statusKey;
        updateStatusUI(s);
    }

    const devKey = JSON.stringify(s.devices || []) + `|${selKey}|${s.target || ''}|${capKey}`;
    if (devKey !== lastDevicesKey) {
        lastDevicesKey = devKey;
        bridgeDevices = s.devices || [];
        renderDevices();
    }
}

function loadStatus() {
    browser.runtime.sendMessage({ action: 'wsGetStatus' }).then(response => {
        if (response) applyStatus(response);
    });
}

// UI Renderers
function renderDevices() {
    if (!devicesList) return;
    devicesList.replaceChildren();

    if (bridgeCapabilities?.selectDevice) {
        // 1. "This computer" row first
        const isLocalActive =
            bridgeSelection?.kind === 'local' || bridgeTarget === 'local';

        const localItem = document.createElement('div');
        localItem.className = 'saved-connection-item' + (isLocalActive ? ' active' : '');

        const localInfo = document.createElement('div');
        localInfo.className = 'saved-connection-info';
        const localName = document.createElement('span');
        localName.className = 'saved-connection-ip';
        localName.textContent = 'This computer';
        const localSubtitle = document.createElement('span');
        localSubtitle.className = 'saved-connection-pin';
        localSubtitle.textContent = 'Play audio/video on this computer';
        localInfo.appendChild(localName);
        localInfo.appendChild(localSubtitle);
        localItem.appendChild(localInfo);

        const localActions = document.createElement('div');
        localActions.className = 'saved-connection-actions';
        if (isLocalActive) {
            const badge = document.createElement('span');
            badge.className = 'badge-active';
            badge.textContent = 'Active';
            localActions.appendChild(badge);
        }
        localItem.appendChild(localActions);

        localItem.addEventListener('click', () => {
            browser.runtime.sendMessage({ action: 'selectLocal' }).then(() => {
                loadStatus();
                showToast('Selected This computer');
            }).catch(() => {});
        });
        devicesList.appendChild(localItem);

        // 2. Devices
        const chips = protocolChips(bridgeDevices);
        const showChips = chips.length >= 2;
        const activeProtocol = showChips
            ? defaultProtocol(bridgeDevices, bridgeSelection, rememberedProtocol, currentStatus?.activeTv)
            : null;
        const visibleDevices = showChips && activeProtocol
            ? filterByProtocol(bridgeDevices, activeProtocol)
            : bridgeDevices;

        visibleDevices.forEach((d) => {
            const isTvActive = isActiveDevice(d, bridgeSelection, currentStatus?.activeTv);

            const item = document.createElement('div');
            item.className = 'saved-connection-item' + (isTvActive ? ' active' : '');

            const info = document.createElement('div');
            info.className = 'saved-connection-info';
            const name = document.createElement('span');
            name.className = 'saved-connection-ip';
            name.textContent = d.name;
            info.appendChild(name);

            const actions = document.createElement('div');
            actions.className = 'saved-connection-actions';

            if (d.paired) {
                const tag = document.createElement('span');
                tag.className = isTvActive
                    ? 'badge-active'
                    : d.online !== false
                    ? 'badge-online'
                    : 'badge-offline';
                tag.textContent = isTvActive
                    ? 'Active'
                    : d.online !== false
                    ? 'Online'
                    : 'Offline';
                actions.appendChild(tag);

                item.addEventListener('click', () => {
                    browser.runtime
                        .sendMessage({ action: 'selectDevice', id: d.id || d.uuid })
                        .then(() => {
                            loadStatus();
                            showToast(`Selected ${d.name}`);
                        })
                        .catch(() => {});
                });
            } else {
                const pairBtn = document.createElement('button');
                pairBtn.type = 'button';
                pairBtn.className = 'pair-app-btn';
                pairBtn.textContent = 'Pair in app';
                pairBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    browser.runtime
                        .sendMessage({ action: 'showApp', pairId: d.id || d.uuid })
                        .catch(() => {});
                    showToast('Opening PlayBridge…');
                });
                actions.appendChild(pairBtn);

                item.addEventListener('click', () => {
                    browser.runtime
                        .sendMessage({ action: 'showApp', pairId: d.id || d.uuid })
                        .catch(() => {});
                    showToast('Opening PlayBridge…');
                });
            }

            item.appendChild(info);
            item.appendChild(actions);
            devicesList.appendChild(item);
        });

        if (showChips) {
            const chipsRow = document.createElement('div');
            chipsRow.className = 'protocol-chips-row';
            chips.forEach((chip) => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'protocol-chip' + (chip === activeProtocol ? ' active' : '');
                btn.textContent = formatDeviceProtocol(chip) || chip;
                btn.setAttribute('data-protocol', chip);
                btn.addEventListener('click', () => {
                    rememberedProtocol = chip;
                    try {
                        browser.storage.local
                            .set({ [DEVICE_PICKER_PROTOCOL_STORAGE_KEY]: chip })
                            .catch(() => {});
                    } catch {
                        // ignore
                    }
                    renderDevices();
                });
                chipsRow.appendChild(btn);
            });
            devicesList.appendChild(chipsRow);
        }
        return;
    }

    // Backward compatibility: read-only list
    if (!bridgeDevices || bridgeDevices.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'subtitle';
        empty.textContent = 'No TVs found on the network yet.';
        devicesList.appendChild(empty);
        return;
    }

    const legacyChips = protocolChips(bridgeDevices);
    const legacyShowChips = legacyChips.length >= 2;
    const legacyActiveProtocol = legacyShowChips
        ? defaultProtocol(bridgeDevices, bridgeSelection, rememberedProtocol, currentStatus?.activeTv)
        : null;
    const legacyVisibleDevices = legacyShowChips && legacyActiveProtocol
        ? filterByProtocol(bridgeDevices, legacyActiveProtocol)
        : bridgeDevices;

    legacyVisibleDevices.forEach((d) => {
        const item = document.createElement('div');
        item.className = 'saved-connection-item';
        const info = document.createElement('div');
        info.className = 'saved-connection-info';
        const name = document.createElement('span');
        name.className = 'saved-connection-ip';
        name.textContent = d.name;
        info.appendChild(name);

        const actions = document.createElement('div');
        actions.className = 'saved-connection-actions';

        const tag = document.createElement('span');
        tag.className = 'saved-connection-pin';
        tag.textContent = d.paired ? 'paired' : 'not paired';
        actions.appendChild(tag);

        item.appendChild(info);
        item.appendChild(actions);
        devicesList.appendChild(item);
    });

    if (legacyShowChips) {
        const chipsRow = document.createElement('div');
        chipsRow.className = 'protocol-chips-row';
        legacyChips.forEach((chip) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'protocol-chip' + (chip === legacyActiveProtocol ? ' active' : '');
            btn.textContent = formatDeviceProtocol(chip) || chip;
            btn.setAttribute('data-protocol', chip);
            btn.addEventListener('click', () => {
                rememberedProtocol = chip;
                try {
                    browser.storage.local
                        .set({ [DEVICE_PICKER_PROTOCOL_STORAGE_KEY]: chip })
                        .catch(() => {});
                } catch {
                    // ignore
                }
                renderDevices();
            });
            chipsRow.appendChild(btn);
        });
        devicesList.appendChild(chipsRow);
    }
}

function updateStatusUI(s) {
    const status = s.status;
    const activeTv = s.activeTv;
    statusDot.className = `dot mx-${status}`;

    if (bridgeCapabilities?.selectDevice) {
        if (s.selection?.kind === 'local' || s.target === 'local') {
            statusText.textContent = 'Ready · This computer';
            statusText.style.color = 'var(--success)';
            if (statusHint) statusHint.textContent = 'Casts play on this computer.';
            return;
        }
        if (s.selection?.kind === 'tv') {
            const name = s.selection.name || 'TV';
            if (s.selection.status === 'connected') {
                statusText.textContent = `Connected · ${name}`;
                statusText.style.color = 'var(--success)';
                if (statusHint) statusHint.textContent = `Casts go to: ${name}`;
            } else if (s.selection.status === 'offline') {
                statusText.textContent = `Offline · ${name}`;
                statusText.style.color = 'var(--danger)';
                if (statusHint) statusHint.textContent = `${name} is offline. Choose another device or This computer.`;
            } else {
                statusText.textContent = `Connecting · ${name}`;
                statusText.style.color = 'var(--accent)';
                if (statusHint) statusHint.textContent = `Connecting to ${name}…`;
            }
            return;
        }
    }

    if (status === 'connected') {
        statusText.textContent = activeTv ? `Connected · ${activeTv}` : 'Connected';
        statusText.style.color = 'var(--success)';
        if (statusHint) statusHint.textContent = `Casts go to: ${activeTv}`;
    } else if (status === 'connecting') {
        statusText.textContent = 'Desktop ready · no TV';
        statusText.style.color = 'var(--accent)';
        if (statusHint) {
            statusHint.textContent = s.desktopConnected
                ? 'Desktop app is running — open it and connect a TV to cast.'
                : 'Open the PlayBridge desktop app to start casting.';
        }
    } else {
        statusText.textContent = 'Desktop app not running';
        statusText.style.color = 'var(--danger)';
        if (statusHint) statusHint.textContent = 'Open the PlayBridge desktop app to start casting.';
    }
}

function hlsRoleLabel(video) {
    if (video.isSyntheticMaster) {
        return video.hasSeparateAudio
            ? 'HLS synthetic master (session A/V)'
            : 'HLS synthetic master';
    }
    const role = effectiveHlsRole(video);
    switch (role) {
        case 'master':
            return video.hasSeparateAudio ? 'HLS master (demuxed A/V)' : 'HLS master';
        case 'video_media':
            return video.audioUrl || video.hasSeparateAudio
                ? 'HLS live video (+ audio session)'
                : 'HLS video';
        case 'media':
            return 'HLS media';
        default:
            return null;
    }
}

function renderVideos() {
    // Hide demuxed audio and demuxed video media when a master exists for the group.
    const castable = filterPrimaryCastCandidates(
        currentVideos.filter(v =>
            v.detectedBy !== 'subtitle_extension' && !v.url.endsWith('.srt') && !v.url.endsWith('.vtt')
        )
    );
    videoItems = castable;
    subtitleItems = currentVideos.filter(v => 
        v.detectedBy === 'subtitle_extension' || v.url.endsWith('.srt') || v.url.endsWith('.vtt')
    );
    
    // Priority Sort: HLS master > other M3U8 > MP4 > others; then recency
    videoItems.sort((a, b) => {
        const roleRank = (v) => {
            const role = effectiveHlsRole(v);
            if (role === 'master') return 4;
            if (role === 'video_media' || role === 'media') return 3;
            if (String(v.url).includes('m3u8')) return 2;
            if (String(v.url).includes('.mp4')) return 1;
            return 0;
        };
        const rd = roleRank(b) - roleRank(a);
        if (rd !== 0) return rd;
        const aM3u8 = a.url.includes('m3u8');
        const bM3u8 = b.url.includes('m3u8');
        if (aM3u8 && !bM3u8) return -1;
        if (!aM3u8 && bM3u8) return 1;
        const aMp4 = a.url.includes('.mp4');
        const bMp4 = b.url.includes('.mp4');
        if (aMp4 && !bMp4) return -1;
        if (!aMp4 && bMp4) return 1;
        const aSeen = a.lastSeen ?? a.timestamp ?? 0;
        const bSeen = b.lastSeen ?? b.timestamp ?? 0;
        return Math.sign(bSeen - aSeen);
    });

    // Drop selection if it was a demuxed child that is no longer listed.
    if (selectedVideoUrl && !videoItems.some((v) => v.url === selectedVideoUrl)) {
        selectedVideoUrl = null;
    }
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
        videosList.replaceChildren();

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
            copyBtn.setAttribute('aria-label', 'Copy URL');
            copyBtn.appendChild(createCopyIcon());
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
            const roleLabel = hlsRoleLabel(video);
            typeSpan.textContent = roleLabel ? `${roleLabel} · ${typeStr}` : typeStr;
            const detectSpan = document.createElement('span');
            detectSpan.textContent = detectStr;
            metaDiv.appendChild(typeSpan);
            metaDiv.appendChild(detectSpan);
            item.appendChild(metaDiv);
            
            // Qualities Dropdown for HLS masters.
            // Demuxed A/V masters must stay on Auto — picking a video media
            // playlist would drop the separate audio track.
            if (video.qualities && video.qualities.length > 0) {
                const select = document.createElement('select');
                select.className = 'quality-select';
                
                const autoOpt = document.createElement('option');
                autoOpt.value = 'auto';
                autoOpt.textContent = video.hasSeparateAudio
                    ? 'Auto (master · includes audio)'
                    : 'Auto (Master Playlist)';
                select.appendChild(autoOpt);
                
                if (!video.hasSeparateAudio) {
                    video.qualities.forEach((q, idx) => {
                        const opt = document.createElement('option');
                        opt.value = idx.toString();
                        opt.textContent = `${q.resolution} (${Math.round(q.bandwidth / 1024)} kbps)`;
                        select.appendChild(opt);
                    });
                } else {
                    select.disabled = true;
                    select.title = 'Demuxed live stream: cast the master so audio stays attached';
                }
                
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
        subtitlesList.replaceChildren();

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
            copyBtn.setAttribute('aria-label', 'Copy URL');
            copyBtn.appendChild(createCopyIcon());
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

function reportPlaybackResult(res, fallbackSuccessMsg = 'Playing on TV') {
    if (res && (res.success || res.ok)) {
        showToast(
            res.target === 'local'
                ? 'Playing on this computer'
                : (res.targetName ? `Playing on ${res.targetName}` : fallbackSuccessMsg),
        );
    } else {
        if (res?.code === 'app_not_running') {
            showToast('PlayBridge desktop app is not running.');
        } else if (res?.error === 'destination_offline') {
            showToast(`${res.targetName || 'TV'} is offline`);
        } else {
            showToast('Error: ' + (res?.error || res?.reason || 'Unknown'));
        }
    }
}

masterPlayBtn.addEventListener('click', async () => {
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
    
    browser.runtime.sendMessage({ 
        action: 'wsPlayOnTv', 
        video: videoPayload,
        subtitleUrl: selectedSubtitleUrl
    }).then(res => {
        reportPlaybackResult(res, 'Playing on TV');
    }).catch(err => {
        console.error("Error sending play command:", err);
        showToast('Error sending play command');
    });
});

openCurrentTabBtn.addEventListener('click', async () => {
    browser.runtime.sendMessage({ action: 'getCurrentTabUrl' }).then(res => {
        const url = res?.url;
        if (url) {
            browser.runtime.sendMessage({
                action: 'wsSendToTv',
                url: url,
                target: 'browser'
            }).then(sendRes => {
                reportPlaybackResult(sendRes, 'Opening tab on TV');
            }).catch(err => {
                console.error("Error sending open command:", err);
                showToast('Error sending open command');
            });
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
    
    browser.runtime.sendMessage({
        action: 'wsSendToTv',
        url: url,
        target: 'browser'
    }).then(res => {
        reportPlaybackResult(res, 'Opening URL on TV');
    }).catch(err => {
        console.error("Error sending open command:", err);
        showToast('Error sending open command');
    });
});

openUrlPlayerBtn.addEventListener('click', async () => {
    const url = customUrlInput.value.trim();
    if (!url) {
        showToast('Please enter a URL');
        return;
    }
    
    browser.runtime.sendMessage({
        action: 'wsSendToTv',
        url: url,
        target: 'player'
    }).then(res => {
        reportPlaybackResult(res, 'Playing URL on TV');
    }).catch(err => {
        console.error("Error sending play command:", err);
        showToast('Error sending play command');
    });
});

if (refreshBtn) {
    refreshBtn.addEventListener('click', () => {
        if (bridgeCapabilities?.selectDevice) {
            browser.runtime.sendMessage({ action: 'refreshDevices' });
        } else {
            browser.runtime.sendMessage({ action: 'wsConnect' });
        }
        loadStatus();
        showToast('Refreshing…');
    });
}

// Listen for updates from background
browser.runtime.onMessage.addListener((message) => {
    if (message.type === 'ws_status_update') {
        applyStatus(message);
    } else if (message.type === 'video_detected') {
        // Reload videos if a new one is detected while popup is open
        loadVideos();
    } else if (message.type === 'data_consent_changed' && message.status) {
        renderDataConsent(message.status);
        loadOverlaySetting();
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

// Settings: global and hostname-specific overlay controls.
function renderOverlaySettings(preferences) {
    overlayPreferences = preferences;
    const supported = !!preferences.siteKey;

    showVideoCastOverlayToggle.checked = !!preferences.globalEnabled;
    siteOverlayHost.textContent = supported ? preferences.siteKey : 'Not available';
    siteOverlaySupported.classList.toggle('hidden', !supported);
    siteOverlayUnavailable.classList.toggle('hidden', supported);
    siteOverlayGlobalOff.classList.toggle('hidden', !supported || preferences.globalEnabled);

    showVideoCastOverlaySiteToggle.checked = !!preferences.siteEnabled;
    showVideoCastOverlaySiteToggle.disabled = !supported || !preferences.globalEnabled;
    siteOverlayPositionFieldset.disabled = !supported || !preferences.globalEnabled || !preferences.siteEnabled;
    resetSiteOverlayBtn.disabled = !supported || !preferences.hasSiteOverride;

    const position = preferences.position || VIDEO_CAST_OVERLAY_DEFAULT_POSITION;
    siteOverlayPositionInputs.forEach(input => {
        input.checked = input.value === position;
    });
}

async function loadOverlaySetting() {
    if (!showVideoCastOverlayToggle) return;
    try {
        const tabs = await browser.tabs.query({ active: true, currentWindow: true });
        const tabId = tabs[0]?.id;
        const preferences = await browser.runtime.sendMessage({
            action: 'getOverlayPreferences',
            ...(typeof tabId === 'number' ? { tabId } : {}),
        });
        if (preferences && typeof preferences.globalEnabled === 'boolean') {
            renderOverlaySettings(preferences);
            return;
        }
    } catch (e) {
        console.error('Failed to load site overlay setting', e);
    }
    renderOverlaySettings(await getVideoCastOverlayPreferences(browser.storage.local, null));
}

if (showVideoCastOverlayToggle) {
    showVideoCastOverlayToggle.addEventListener('change', async () => {
        const enabled = !!showVideoCastOverlayToggle.checked;
        try {
            await setShowVideoCastOverlay(browser.storage.local, enabled);
            await loadOverlaySetting();
            showToast(enabled ? 'Cast overlay enabled globally' : 'Cast overlay disabled globally');
        } catch (e) {
            console.error('Failed to save overlay setting', e);
            await loadOverlaySetting();
            showToast('Could not save setting');
        }
    });
}

if (showVideoCastOverlaySiteToggle) {
    showVideoCastOverlaySiteToggle.addEventListener('change', async () => {
        const preferences = overlayPreferences;
        if (!preferences?.siteKey) return;
        try {
            await setVideoCastOverlaySiteOverride(browser.storage.local, preferences.siteKey, {
                enabled: !!showVideoCastOverlaySiteToggle.checked,
                position: preferences.position || VIDEO_CAST_OVERLAY_DEFAULT_POSITION,
            });
            await loadOverlaySetting();
            showToast(showVideoCastOverlaySiteToggle.checked ? 'Cast overlay enabled for this site' : 'Cast overlay disabled for this site');
        } catch (e) {
            console.error('Failed to save site overlay setting', e);
            await loadOverlaySetting();
            showToast('Could not save site setting');
        }
    });
}

siteOverlayPositionInputs.forEach(input => {
    input.addEventListener('change', async () => {
        if (!input.checked || !overlayPreferences?.siteKey) return;
        const preferences = overlayPreferences;
        try {
            await setVideoCastOverlaySiteOverride(browser.storage.local, preferences.siteKey, {
                enabled: preferences.siteEnabled,
                position: input.value,
            });
            await loadOverlaySetting();
            showToast('Cast button position saved for this site');
        } catch (e) {
            console.error('Failed to save overlay position', e);
            await loadOverlaySetting();
            showToast('Could not save position');
        }
    });
});

if (resetSiteOverlayBtn) {
    resetSiteOverlayBtn.addEventListener('click', async () => {
        const siteKey = overlayPreferences?.siteKey;
        if (!siteKey) return;
        try {
            await resetVideoCastOverlaySiteOverride(browser.storage.local, siteKey);
            await loadOverlaySetting();
            showToast('Site setting reset');
        } catch (e) {
            console.error('Failed to reset site overlay setting', e);
            showToast('Could not reset site setting');
        }
    });
}

async function loadRememberedProtocol() {
    try {
        const stored = await browser.storage.local.get(DEVICE_PICKER_PROTOCOL_STORAGE_KEY);
        if (stored && typeof stored[DEVICE_PICKER_PROTOCOL_STORAGE_KEY] === 'string') {
            rememberedProtocol = stored[DEVICE_PICKER_PROTOCOL_STORAGE_KEY];
            renderDevices();
        }
    } catch (e) {
        // ignore
    }
}

browser.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;
    if (DEVICE_PICKER_PROTOCOL_STORAGE_KEY in changes) {
        const next = changes[DEVICE_PICKER_PROTOCOL_STORAGE_KEY]?.newValue;
        rememberedProtocol = typeof next === 'string' ? next : null;
        renderDevices();
    }
    if (VIDEO_CAST_OVERLAY_STORAGE_KEYS.some(key => key in changes)) {
        loadOverlaySetting();
    }
});

// Init
window.addEventListener('DOMContentLoaded', async () => {
    await loadDataConsent();
    await loadRememberedProtocol();
    loadStatus();
    loadOverlaySetting();
});
