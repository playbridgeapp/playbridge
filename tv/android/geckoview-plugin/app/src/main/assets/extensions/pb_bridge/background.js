// Runtime-owned native port. Video messages carry browser-supplied frame identity;
// page scripts cannot reach the content script's isolated channel.
let nativePort = null;
let retryMs = 1000;
const frames = new Map();

function postNative(message) {
    try { nativePort?.postMessage(message); } catch (_) { /* Reconnect replays live state. */ }
}

function forwardVideo(tabId, frameId, documentId, data) {
    postNative({ type: "video", tabId, frameId, documentId, data });
}

browser.runtime.onMessage.addListener((message, sender) => {
    // GeckoView reports tab.active = false for the plugin's single session, so don't filter
    // on it: the plugin only ever has one tab, and frame identity still comes from `sender`.
    if (message.type !== "video" || !sender.tab || typeof message.data !== "string") return;
    const tabId = sender.tab.id;
    const frameId = sender.frameId;
    const key = `${tabId}:${frameId}`;
    try {
        const data = JSON.parse(message.data);
        let frame = frames.get(key);
        if (!frame || frame.documentId !== message.documentId) {
            frame = { tabId, frameId, documentId: message.documentId, active: null, status: null };
            frames.set(key, frame);
        }
        if (data.type === "active") {
            frame.active = message.data;
            if (!data.active) frame.status = null;
        }
        if (data.type === "status") frame.status = message.data;
        forwardVideo(tabId, frameId, message.documentId, message.data);
    } catch (_) { /* Ignore malformed controller messages without logging page data. */ }
});

// A frame can disappear without a final controller report. Retire it on navigation,
// including all subframes on a top-level commit, so manual pins cannot strand the D-pad.
function retireFrames(tabId, frameId = null) {
    for (const [key, frame] of frames) {
        if (frame.tabId !== tabId || (frameId !== null && frame.frameId !== frameId)) continue;
        forwardVideo(tabId, frame.frameId, frame.documentId,
            JSON.stringify({ type: "active", active: false }));
        frames.delete(key);
    }
}
browser.webNavigation.onCommitted.addListener(({ tabId, frameId }) => {
    retireFrames(tabId, frameId === 0 ? null : frameId);
});
browser.tabs.onRemoved.addListener(tabId => retireFrames(tabId));

async function handleMessage(message) {
    retryMs = 1000;
    if (message.type === "video_cmd") {
        try {
            await browser.tabs.sendMessage(message.tabId, {
                type: "video_cmd", cmd: message.cmd, documentId: message.documentId
            }, { frameId: message.frameId });
        } catch (_) {
            // A delayed failure from an old document must not retire its replacement.
            if (frames.get(`${message.tabId}:${message.frameId}`)?.documentId === message.documentId) {
                retireFrames(message.tabId, message.frameId);
            }
        }
    } else if (message.type === "eval") {
        try {
            // Not {active: true}: GeckoView doesn't mark its session's tab active, so that
            // query returned nothing and every eval (text input, maximize) failed.
            const tabs = await browser.tabs.query({});
            if (tabs.length > 0) {
                // Every frame receives eval; only the frame with the focused input acts.
                const result = await browser.tabs.sendMessage(tabs[0].id, {
                    type: "eval", code: message.code
                });
                postNative({ type: "result", result: String(result || "") });
            } else {
                postNative({ type: "error", error: "no_active_tab" });
            }
        } catch (_) {
            postNative({ type: "error", error: "eval_failed" });
        }
    }
}

function connect() {
    try {
        nativePort = browser.runtime.connectNative("pbBridge");
        nativePort.onMessage.addListener(handleMessage);
        nativePort.onDisconnect.addListener(() => {
            nativePort = null;
            retryMs = Math.min(retryMs * 2, 8000);
            setTimeout(connect, retryMs);
        });
        for (const frame of frames.values()) {
            if (frame.active) forwardVideo(frame.tabId, frame.frameId, frame.documentId, frame.active);
            if (frame.status) forwardVideo(frame.tabId, frame.frameId, frame.documentId, frame.status);
        }
    } catch (_) {
        nativePort = null;
        retryMs = Math.min(retryMs * 2, 8000);
        setTimeout(connect, retryMs);
    }
}
connect();
