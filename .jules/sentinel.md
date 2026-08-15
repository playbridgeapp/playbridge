## 2025-02-09 - XSS Vulnerability in Extension Popup
**Vulnerability:** XSS vulnerability in `extension/src/ui/popup.js` where user-controlled connection properties (`ip`, `pin`) were rendered directly into the DOM using `item.innerHTML = \`<span class="saved-connection-ip">${conn.ip}</span>\``.
**Learning:** The extension builds some DOM views manually without a UI framework, increasing the risk of DOM-based XSS when interpolating variables into HTML strings.
**Prevention:** Always use safe DOM API methods (`document.createElement` and `textContent`) or a sanitization library instead of interpolating untrusted input into `innerHTML`.

## 2026-07-21 - Local File Access Vulnerability in TV App
**Vulnerability:** The Android TV app's `SystemWebViewEngine` had `allowFileAccess` set to `true`. This is a high-risk security misconfiguration because it allows the arbitrary web content loaded into the system WebView to access and read local files on the device filesystem.
**Learning:** The WebView should be treated as untrusted, especially since it's used as a browser to navigate to arbitrary URLs. Giving it file system access unnecessarily expands the attack surface for potential XSS or path traversal attacks to access sensitive application data.
**Prevention:** Explicitly configure `allowFileAccess = false` on all WebViews that handle remote, untrusted content unless there is a verified, strict requirement for local file access (and even then, restrict it securely).

## 2025-02-09 - Insecure Mixed Content Policy in TV App WebView
**Vulnerability:** The Android TV app's `SystemWebViewEngine` had `mixedContentMode` set to `WebSettings.MIXED_CONTENT_ALWAYS_ALLOW`. This is a critical security risk as it permits the loading of insecure active content (like scripts and iframes) over HTTP within a secure HTTPS context, exposing the app to Man-In-The-Middle (MITM) attacks and XSS.
**Learning:** `MIXED_CONTENT_ALWAYS_ALLOW` should never be used in a WebView that loads remote, untrusted content, as it completely disables the browser's mixed content protections.
**Prevention:** Always use `WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE` (or `MIXED_CONTENT_NEVER_ALLOW`) for WebViews to ensure the browser blocks active insecure content from loading on secure origins.

## 2026-07-21 - Local Content Provider Access Vulnerability in TV App
**Vulnerability:** The Android TV app's `SystemWebViewEngine` had `allowContentAccess` set to `true`. This is a critical security misconfiguration because it allows the arbitrary web content loaded into the system WebView to access and read local content provider data via `content://` URIs on the device, potentially exposing sensitive application data or internal processes (e.g. `PlayerProcessBridgeProvider`).
**Learning:** Just as `allowFileAccess` is dangerous, `allowContentAccess` is equally risky when a WebView acts as a browser to navigate to untrusted arbitrary URLs. It unnecessarily expands the attack surface.
**Prevention:** Explicitly configure `allowContentAccess = false` on all WebViews that handle remote, untrusted content to prevent access to the application's ContentProviders.

## 2025-02-09 - Plaintext Token Storage in Apple TV App
**Vulnerability:** The Apple TV app's `WebSocketServer` stored plaintext bearer tokens for paired devices in `UserDefaults` and authorized connections using exact matches. These stored tokens could be extracted from preferences or device backups and reused to gain unauthorized sender privileges.
**Learning:** Ordinary application preferences like `UserDefaults` are not a secure secret store, and retaining a plaintext copy of tokens after issuance introduces persistent credentials that are highly portable.
**Prevention:** Always convert newly generated and incoming secrets to hashed token verifiers (e.g. SHA-256) before saving them to `UserDefaults` and using them in server state lookups. The plaintext token should only ever exist ephemerally during issuance and initial client transmission.

## 2025-02-09 - Missed Verifier Argument in Apple TV App Token Migration
**Vulnerability:** While mitigating SEC-004 (hashed token storage), `approvePairing` correctly hashed the token but called `completeAuth` with the plaintext `token` instead of `tokenVerifier`. The subsequent `handleAuth` exchange attempts to double-hash.
**Learning:** During token migrations or generation where multiple versions (plaintext and hash) exist in scope, functions down the execution path can inadvertently re-bind to the original value, breaking state alignment across the system.
**Prevention:** Follow through all state updates within the function block that consume the token and assert they strictly receive the hashed `tokenVerifier` when representing server-side storage identity.
