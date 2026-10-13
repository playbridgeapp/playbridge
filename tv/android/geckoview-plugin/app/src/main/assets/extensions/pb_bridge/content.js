// This window is Gecko's isolated content-script global. Never export the channel to the page.
// The shared controller only needs DOM access; <video> nodes remain visible in this world.
const videoDocumentId = crypto.randomUUID();
let lastVideoActive = null;
let videoDocumentLive = true;

function postVideo(data) {
    if (!videoDocumentLive) return;
    browser.runtime.sendMessage({ type: "video", documentId: videoDocumentId, data })
        .catch(() => {}); // A closing document/runtime cannot deliver its final message.
}

window.pbVideoChannel = {
    onmessage: null,
    postMessage(data) {
        try {
            const message = JSON.parse(data);
            if (message.type === "active") lastVideoActive = data;
            // Reassert activation before periodic status. Native navigation/recreation resets
            // its selector even when the controller's score has not changed.
            if (message.type === "status" && lastVideoActive) postVideo(lastVideoActive);
            postVideo(data);
        } catch (_) { /* Only controller JSON crosses this bridge. */ }
    }
};

window.addEventListener("pagehide", () => {
    postVideo(JSON.stringify({ type: "active", active: false }));
    videoDocumentLive = false;
});
window.addEventListener("pageshow", () => {
    videoDocumentLive = true; // BFCache restores keep the same isolated controller.
    if (lastVideoActive) postVideo(lastVideoActive);
});

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === "video_cmd") {
        if (videoDocumentLive && (!message.documentId || message.documentId === videoDocumentId)) {
            window.pbVideoChannel.onmessage?.({ data: message.cmd });
        }
        return;
    }
    if (message.type === "focus_cmd") {
        // Every frame receives this. The controller acts only when this document
        // holds an editable focus, so a main-frame iframe element is left alone.
        if (videoDocumentLive && window.__pbvc) {
            if (message.op === "enter") window.__pbvc.submitFocused();
            else if (message.op === "text") window.__pbvc.applyText(typeof message.b64 === "string" ? message.b64 : "");
        }
        sendResponse("");
        return true;
    }
    if (message.type === "eval") {
        try {
            const result = eval(message.code);
            sendResponse(String(result || ""));
        } catch (_) {
            sendResponse("error: eval_failed");
        }
        return true;
    }
});
