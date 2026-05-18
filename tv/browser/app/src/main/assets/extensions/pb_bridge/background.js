// PlayBridge JS Bridge — background script
// Connects to native app and forwards eval requests to content scripts.
console.log("[PB Bridge] Background script starting");

const nativePort = browser.runtime.connectNative("pbBridge");

console.log("[PB Bridge] Native port created");

nativePort.onMessage.addListener(async (message) => {
    console.log("[PB Bridge] Received from native:", JSON.stringify(message));
    if (message.type === "eval") {
        try {
            // Forward to all content scripts and collect result
            const tabs = await browser.tabs.query({ active: true });
            if (tabs.length > 0) {
                const results = await browser.tabs.sendMessage(tabs[0].id, {
                    type: "eval",
                    code: message.code
                });
                console.log("[PB Bridge] Eval result:", results);
                nativePort.postMessage({ type: "result", result: String(results || "") });
            } else {
                console.warn("[PB Bridge] No active tabs");
                nativePort.postMessage({ type: "error", error: "no_active_tab" });
            }
        } catch (e) {
            console.error("[PB Bridge] Error:", e);
            nativePort.postMessage({ type: "error", error: e.message });
        }
    }
});

nativePort.onDisconnect.addListener(() => {
    console.log("[PB Bridge] Native port disconnected");
});
