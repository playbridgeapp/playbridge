// PlayBridge JS Bridge — content script
// Listens for eval requests from background script and executes in page context.
console.log("[PB Bridge] Content script loaded");

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === "eval") {
        try {
            const result = eval(message.code);
            console.log("[PB Bridge] Eval result:", result);
            sendResponse(String(result || ""));
        } catch (e) {
            console.error("[PB Bridge] Eval error:", e);
            sendResponse("error: " + e.message);
        }
        return true; // Keep the message channel open for sendResponse
    }
});
