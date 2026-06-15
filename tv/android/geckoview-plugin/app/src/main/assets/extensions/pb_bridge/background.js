// PlayBridge JS Bridge — background script
// Connects to native app and forwards eval requests to content scripts.
//
// The native message delegate is registered per browser engine/session, which may not exist
// yet when this background script first runs (the extension is pre-warmed at app startup).
// So connectNative can fail initially — we retry on disconnect until the delegate is present.
console.log("[PB Bridge] Background script starting");

let nativePort = null;

async function handleMessage(message) {
    retryMs = 1000; // a real message proves the bridge works — reset backoff
    console.log("[PB Bridge] Received from native:", JSON.stringify(message));
    if (message.type === "eval") {
        try {
            const tabs = await browser.tabs.query({ active: true });
            if (tabs.length > 0) {
                // Deliver to every frame; the frame holding the focused element acts, others no-op.
                const results = await browser.tabs.sendMessage(tabs[0].id, {
                    type: "eval",
                    code: message.code
                });
                console.log("[PB Bridge] Eval result:", results);
                nativePort?.postMessage({ type: "result", result: String(results || "") });
            } else {
                console.warn("[PB Bridge] No active tabs");
                nativePort?.postMessage({ type: "error", error: "no_active_tab" });
            }
        } catch (e) {
            console.error("[PB Bridge] Error:", e);
            nativePort?.postMessage({ type: "error", error: e.message });
        }
    }
}

let retryMs = 1000;
function connect() {
    try {
        nativePort = browser.runtime.connectNative("pbBridge");
        console.log("[PB Bridge] Native port created");
        nativePort.onMessage.addListener(handleMessage);
        nativePort.onDisconnect.addListener(() => {
            console.log("[PB Bridge] Native port disconnected — retrying");
            nativePort = null;
            // Back off (capped) so a persistent failure doesn't spam every second.
            retryMs = Math.min(retryMs * 2, 8000);
            setTimeout(connect, retryMs);
        });
    } catch (e) {
        console.error("[PB Bridge] connectNative failed — retrying", e);
        nativePort = null;
        retryMs = Math.min(retryMs * 2, 8000);
        setTimeout(connect, retryMs);
    }
}

connect();
