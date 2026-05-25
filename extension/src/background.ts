import type {
  PlayPayload,
  BrowserPayload,
} from "../../protocol/generated/typescript/messages";

import { CONFIG } from "./config";
import { HlsParser } from "./hls-parser";

// ==================== Video detection ====================

const VIDEO_CONTENT_TYPES = [
  "video/",
  "mpegurl",
  "application/dash",
  "application/x-mpegurl",
  "application/vnd.apple.mpegurl",
  "text/vtt",
  "application/x-subrip",
  ".vtt",
  ".srt",
];

interface VideoData {
  url: string;
  tabId: number;
  contentType: string;
  detectedBy: string;
  originUrl: string;
  timestamp: number;
  headers?: Record<string, string>;
  subtitles?: string[];
  subtitlePreview?: string;
  qualities?: unknown[];
}

interface StoredHeaders {
  headers: Record<string, string>;
  tabId: number;
  timestamp: number;
}

const tabVideos = new Map<number, VideoData[]>();
const tabSeenUrls = new Map<number, Set<string>>();
const tabHeadersCaptured = new Map<number, Set<string>>();
const requestHeadersMap = new Map<string, StoredHeaders>();

const CLEANUP_INTERVAL_MS = 30_000;
const HEADER_TTL_MS = 60_000;

setInterval(() => {
  const now = Date.now();
  for (const [id, data] of requestHeadersMap.entries()) {
    if (now - data.timestamp > HEADER_TTL_MS) requestHeadersMap.delete(id);
  }
}, CLEANUP_INTERVAL_MS);

function getTabVideos(tabId: number): VideoData[] {
  if (!tabVideos.has(tabId)) tabVideos.set(tabId, []);
  return tabVideos.get(tabId)!;
}
function getTabSeenUrls(tabId: number): Set<string> {
  if (!tabSeenUrls.has(tabId)) tabSeenUrls.set(tabId, new Set());
  return tabSeenUrls.get(tabId)!;
}
function getTabHeadersCaptured(tabId: number): Set<string> {
  if (!tabHeadersCaptured.has(tabId)) tabHeadersCaptured.set(tabId, new Set());
  return tabHeadersCaptured.get(tabId)!;
}
function cleanupTab(tabId: number) {
  tabVideos.delete(tabId);
  tabSeenUrls.delete(tabId);
  tabHeadersCaptured.delete(tabId);
}

browser.tabs.onRemoved.addListener((tabId) => cleanupTab(tabId));

function handleNavigation(details: { frameId: number; tabId: number; url: string }) {
  if (details.frameId !== 0) return;
  cleanupTab(details.tabId);
  browser.tabs.sendMessage(details.tabId, { type: "clear_videos" }).catch(() => {});
}

if (browser.webNavigation) {
  browser.webNavigation.onCommitted.addListener(handleNavigation);
  browser.webNavigation.onHistoryStateUpdated.addListener(handleNavigation);
}

function notifyContentScript(video: VideoData, tabId: number, headers: Record<string, string> | null) {
  const hasHeaders = headers && Object.keys(headers).length > 0;
  const seenUrls = getTabSeenUrls(tabId);
  const headersCaptured = getTabHeadersCaptured(tabId);

  if (seenUrls.has(video.url)) {
    if (headersCaptured.has(video.url) || !hasHeaders) return;
  }

  seenUrls.add(video.url);
  if (seenUrls.size > 500) seenUrls.delete(seenUrls.keys().next().value!);

  if (hasHeaders) {
    video.headers = headers!;
    headersCaptured.add(video.url);
    if (headersCaptured.size > 500) headersCaptured.delete(headersCaptured.keys().next().value!);
  }

  const videos = getTabVideos(tabId);
  const idx = videos.findIndex((v) => v.url === video.url);
  if (idx !== -1) videos[idx] = { ...videos[idx], ...video };
  else {
    videos.push(video);
    if (videos.length > 50) videos.shift();
  }

  if (tabId > 0) {
    browser.tabs.sendMessage(tabId, { type: "video_detected", ...video }).catch(() => {});
  }
}

function processAndNotifyVideo(videoData: VideoData, tabId: number, headers: Record<string, string> | null) {
  if (videoData.url.toLowerCase().includes("m3u8")) {
    HlsParser.parsePlaylist(videoData.url).then((playlist) => {
      if (playlist.videoQualities.length > 0) videoData.qualities = playlist.videoQualities;
      notifyContentScript(videoData, tabId, headers);
    }).catch(() => notifyContentScript(videoData, tabId, headers));
  } else if (
    videoData.detectedBy === "subtitle_extension" ||
    videoData.url.endsWith(".srt") ||
    videoData.url.endsWith(".vtt")
  ) {
    fetch(videoData.url, { method: "GET", headers: { Range: "bytes=0-1500" } })
      .then((r) => r.text())
      .then((text) => {
        const lines = text.split(/\r?\n/);
        const validLines: string[] = [];
        for (const line of lines) {
          const l = line.trim();
          if (!l || l === "WEBVTT" || l.startsWith("Kind:") || l.startsWith("Language:")) continue;
          if (/^\d+$/.test(l) || l.includes("-->")) continue;
          const clean = l.replace(/<[^>]+>/g, "").trim();
          if (clean) validLines.push(clean);
          if (validLines.length >= 6) break;
        }
        if (validLines.length > 0) videoData.subtitlePreview = validLines.join(" • ") + "...";
        notifyContentScript(videoData, tabId, headers);
      })
      .catch(() => notifyContentScript(videoData, tabId, headers));
  } else {
    notifyContentScript(videoData, tabId, headers);
  }
}

// ==================== WebSocket / protocol ====================

let wsConnection: WebSocket | null = null;
let playbridgePort: number = CONFIG.DEFAULT_PORT;
let wsStatus: "disconnected" | "connecting" | "connected" = "disconnected";
let intentionalDisconnect = false;
let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
let reconnectDelay = 5_000;
let heartbeatInterval: ReturnType<typeof setInterval> | null = null;

function updateWsStatus(status: typeof wsStatus) {
  wsStatus = status;
  const msg = { type: "ws_status_update", status };
  browser.runtime.sendMessage(msg).catch(() => {});
  browser.tabs.query({}).then((tabs) => {
    for (const tab of tabs) {
      if (tab.id) browser.tabs.sendMessage(tab.id, msg).catch(() => {});
    }
  });
}

function connectWebSocket(ip: string, pin: string) {
  intentionalDisconnect = false;
  if (reconnectTimeout) { clearTimeout(reconnectTimeout); reconnectTimeout = null; }

  if (wsConnection) {
    wsConnection.onclose = null;
    wsConnection.onerror = null;
    wsConnection.close();
    wsConnection = null;
  }

  browser.storage.local.set({ pb_ip: ip, pb_pin: pin });
  updateWsStatus("connecting");

  try {
    wsConnection = new WebSocket(`ws://${ip}:${playbridgePort}`);

    wsConnection.onopen = () => {
      reconnectDelay = 5_000;
      updateWsStatus("connected");
      if (pin) wsConnection!.send(JSON.stringify({ type: "auth", pin }));

      if (heartbeatInterval) clearInterval(heartbeatInterval);
      heartbeatInterval = setInterval(() => {
        if (wsConnection?.readyState === WebSocket.OPEN) {
          try { wsConnection.send(JSON.stringify({ type: "ping" })); }
          catch (_) { wsConnection!.close(); }
        }
      }, 30_000);
    };

    wsConnection.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data as string);
        if (msg.type === "ping") wsConnection!.send(JSON.stringify({ type: "pong" }));
      } catch (_) {}
    };

    wsConnection.onclose = () => {
      updateWsStatus("disconnected");
      wsConnection = null;
      if (heartbeatInterval) { clearInterval(heartbeatInterval); heartbeatInterval = null; }

      if (!intentionalDisconnect) {
        reconnectTimeout = setTimeout(() => {
          if (wsStatus !== "disconnected" || intentionalDisconnect) return;
          browser.storage.local.get(["pb_ip", "pb_pin", "savedConnections"]).then((res) => {
            if (res.pb_ip) {
              connectWebSocket(res.pb_ip as string, (res.pb_pin as string) ?? "");
            } else if (Array.isArray(res.savedConnections) && res.savedConnections.length > 0) {
              const c = res.savedConnections[0] as { ip: string; pin?: string };
              connectWebSocket(c.ip, c.pin ?? "");
            }
          });
        }, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, 60_000);
      }
    };

    wsConnection.onerror = () => { /* close handler fires */ };
  } catch (e) {
    console.error("[PlayBridge BG] WS setup error:", e);
    updateWsStatus("disconnected");
  }
}

function sendPlayCommand(payload: PlayPayload): boolean {
  if (wsStatus !== "connected" || !wsConnection) return false;
  // The standalone `play` command was removed — a single video is sent as a
  // one-item playlist so the TV always sets up a queue.
  const command = {
    type: "command",
    action: "playlist",
    payload: { items: [payload], startIndex: 0 },
  };
  wsConnection.send(JSON.stringify(command));
  return true;
}


function sendBrowserCommand(payload: BrowserPayload): boolean {
  if (wsStatus !== "connected" || !wsConnection) return false;
  wsConnection.send(JSON.stringify({ type: "command", action: "browser", payload }));
  return true;
}

function videoDataToPlayPayload(video: VideoData): PlayPayload {
  return {
    url: video.url,
    contentType: video.contentType,
    headers: video.headers ?? {},
    detectedBy: video.detectedBy,
    subtitles: video.subtitles ?? [],
  };
}

// ==================== Request header capture ====================

browser.webRequest.onBeforeSendHeaders.addListener(
  (details) => {
    if (details.method === "OPTIONS") return;
    const headers: Record<string, string> = {};
    const skip = ["host", "connection", "accept-encoding", "content-length", "upgrade-insecure-requests"];
    for (const h of details.requestHeaders ?? []) {
      if (!skip.includes(h.name.toLowerCase())) headers[h.name] = h.value ?? "";
    }
    if (Object.keys(headers).length > 0) {
      requestHeadersMap.set(details.requestId, { headers, tabId: details.tabId, timestamp: Date.now() });
    }
  },
  { urls: ["<all_urls>"] },
  ["requestHeaders"],
);

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    const ctHeader = details.responseHeaders?.find((h) => h.name.toLowerCase() === "content-type");
    const contentType = ctHeader?.value?.toLowerCase() ?? "unknown";
    const stored = requestHeadersMap.get(details.requestId);

    if (!ctHeader) { if (stored) requestHeadersMap.delete(details.requestId); return; }

    const captured = getTabHeadersCaptured(details.tabId);
    const seen = getTabSeenUrls(details.tabId);
    if (seen.has(details.url) && (captured.has(details.url) || !stored)) {
      if (stored) requestHeadersMap.delete(details.requestId);
      return;
    }

    const urlLower = details.url.toLowerCase().split("?")[0];
    const isSegment = [".ts", ".m4s"].some((ext) => urlLower.endsWith(ext)) ||
      urlLower.includes("/segment") || urlLower.includes("frag");
    if (isSegment) { if (stored) requestHeadersMap.delete(details.requestId); return; }

    const isVideoContentType = VIDEO_CONTENT_TYPES.some((t) => contentType.includes(t));
    const isM3u8Url = details.url.toLowerCase().includes("m3u8");
    const hasVideoExt = [".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".m4v", ".wmv", ".3gp"]
      .some((ext) => urlLower.endsWith(ext));
    const hasSubExt = [".vtt", ".srt"].some((ext) => urlLower.endsWith(ext));
    const isVideoExtMatch = hasVideoExt && (
      contentType.includes("octet-stream") || contentType.includes("binary") || !contentType
    );

    let isVideo = false;
    let detectedBy = "unknown";
    if (isVideoContentType) { isVideo = true; detectedBy = "content_type"; }
    else if (isM3u8Url) { isVideo = true; detectedBy = "url_pattern_m3u8"; }
    else if (isVideoExtMatch || hasVideoExt) { isVideo = true; detectedBy = "url_extension"; }
    else if (hasSubExt) { isVideo = true; detectedBy = "subtitle_extension"; }

    if (isVideo) {
      processAndNotifyVideo(
        { url: details.url, tabId: details.tabId, contentType, detectedBy, originUrl: details.originUrl ?? "", timestamp: Date.now() },
        details.tabId,
        stored?.headers ?? null,
      );
    } else {
      const skipTypes = ["image", "font", "stylesheet", "script"];
      if (details.statusCode === 200 && !skipTypes.includes(details.type) && browser.webRequest.filterResponseData) {
        try {
          const filter = browser.webRequest.filterResponseData(details.requestId);
          const decoder = new TextDecoder("utf-8");
          let checked = false;
          let acc = "";
          filter.ondata = (ev) => {
            filter.write(ev.data);
            if (checked) return;
            acc += decoder.decode(ev.data, { stream: true });
            if (acc.length >= 7) {
              checked = true;
              if (acc.trim().startsWith("#EXTM3U")) {
                processAndNotifyVideo(
                  { url: details.url, tabId: details.tabId, contentType, detectedBy: "body_content_m3u8", originUrl: details.originUrl ?? "", timestamp: Date.now() },
                  details.tabId,
                  stored?.headers ?? null,
                );
              }
              filter.disconnect();
            }
          };
          filter.onstop = () => { try { filter.disconnect(); } catch (_) {} };
          filter.onerror = () => {};
        } catch (_) {}
      }
    }

    if (stored) requestHeadersMap.delete(details.requestId);
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders", "blocking"],
);

// ==================== Runtime message handler ====================

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const msg = message as Record<string, unknown>;

  if (msg.action === "getVideos") {
    const tabId = (msg.tabId ?? sender.tab?.id) as number | undefined;
    const videos = tabId ? (tabVideos.get(tabId) ?? []) : [];
    sendResponse({ videos, count: videos.length });
    return true;
  }

  if (msg.action === "clearVideos") {
    const tabId = (msg.tabId ?? sender.tab?.id) as number | undefined;
    if (tabId) cleanupTab(tabId);
    else { tabVideos.clear(); tabSeenUrls.clear(); tabHeadersCaptured.clear(); requestHeadersMap.clear(); }
    sendResponse({ cleared: true });
    return true;
  }

  if (msg.action === "getCurrentTabUrl") {
    if (sender.tab?.url) { sendResponse({ url: sender.tab.url }); return true; }
    browser.tabs.query({ active: true, currentWindow: true }).then((tabs) =>
      sendResponse({ url: tabs[0]?.url ?? null })
    ).catch(() => sendResponse({ url: null }));
    return true;
  }

  if (msg.action === "wsGetStatus") {
    browser.storage.local.get(["pb_ip", "pb_pin", "pb_port"]).then((res) =>
      sendResponse({ status: wsStatus, ip: res.pb_ip ?? "", pin: res.pb_pin ?? "", port: res.pb_port ?? CONFIG.DEFAULT_PORT })
    );
    return true;
  }

  if (msg.action === "wsConnect") {
    if (msg.port) { playbridgePort = msg.port as number; browser.storage.local.set({ pb_port: msg.port }); }
    connectWebSocket(msg.ip as string, msg.pin as string);
    sendResponse({ connecting: true });
    return true;
  }

  if (msg.action === "wsDisconnect") {
    intentionalDisconnect = true;
    if (reconnectTimeout) { clearTimeout(reconnectTimeout); reconnectTimeout = null; }
    if (wsConnection) { wsConnection.close(); browser.storage.local.remove(["pb_ip", "pb_pin"]); }
    sendResponse({ disconnected: true });
    return true;
  }

  if (msg.action === "wsPlayOnTv") {
    const tabId = (sender.tab?.id ?? msg.tabId) as number | undefined;
    const videos = tabId ? getTabVideos(tabId) : [];
    const video = videos.find((v) => v.url === msg.url) ?? msg.video as VideoData | undefined;
    if (video) {
      if (msg.subtitleUrl) video.subtitles = [msg.subtitleUrl as string];
      const success = sendPlayCommand(videoDataToPlayPayload(video));
      sendResponse({ success, reason: success ? null : "Not connected to TV" });
    } else {
      sendResponse({ success: false, reason: "Video not found" });
    }
    return true;
  }

  if (msg.action === "wsSendToTv") {
    if (wsStatus !== "connected" || !wsConnection) {
      sendResponse({ success: false, reason: "Not connected to TV" });
      return true;
    }
    let success = false;
    if (msg.target === "browser") {
      success = sendBrowserCommand({ url: msg.url as string });
    } else if (msg.target === "player") {
      success = sendPlayCommand({ url: msg.url as string, headers: {}, subtitles: [], title: msg.url as string });
    }
    sendResponse({ success, reason: success ? null : "Invalid target" });
    return true;
  }

  return false;
});

// ==================== Context menu ====================

browser.menus.create({ id: "playbridge-parent", title: "PlayBridge", contexts: ["all"], icons: { "16": "icon.png" } });
browser.menus.create({ id: "playbridge-play", parentId: "playbridge-parent", title: "Play on TV", contexts: ["link", "video", "audio"] });
browser.menus.create({ id: "playbridge-open", parentId: "playbridge-parent", title: "Open on TV", contexts: ["all"] });

browser.menus.onClicked.addListener((info, tab) => {
  if (wsStatus !== "connected" || !wsConnection) {
    browser.notifications.create("pb-not-connected", { type: "basic", iconUrl: "icon.png", title: "PlayBridge", message: "Not connected to TV." });
    return;
  }
  if (info.menuItemId === "playbridge-play") {
    const url = info.srcUrl ?? info.linkUrl;
    if (url) sendPlayCommand({ url, headers: {}, subtitles: [] });
  } else if (info.menuItemId === "playbridge-open") {
    const url = info.linkUrl ?? info.pageUrl ?? tab?.url;
    if (url) sendBrowserCommand({ url });
  }
});

// ==================== Startup ====================

browser.storage.local.get(["pb_ip", "pb_pin", "pb_port", "savedConnections"]).then((res) => {
  if (res.pb_port) playbridgePort = res.pb_port as number;
  if (res.pb_ip) {
    connectWebSocket(res.pb_ip as string, (res.pb_pin as string) ?? "");
  } else if (Array.isArray(res.savedConnections) && res.savedConnections.length > 0) {
    const c = res.savedConnections[0] as { ip: string; pin?: string };
    connectWebSocket(c.ip, c.pin ?? "");
  }
});
