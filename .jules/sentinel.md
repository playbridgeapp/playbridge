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

## 2026-08-16 - Plaintext Bearer Token Storage in Apple TV
**Vulnerability:** The Apple TV app's `WebSocketServer` previously stored bearer authentication tokens in plaintext in `UserDefaults`. This is a medium-risk vulnerability (SEC-004) where an attacker gaining local access or backups could extract tokens and authenticate as the paired sender.
**Learning:** Legacy preferences storage mechanisms (like `UserDefaults`) are not safe places to store high-entropy bearer tokens. If you must use them instead of a secure vault (like Keychain), the tokens must be stored only as non-reversible hashed verifiers, using constant-time comparison algorithms for authorization.
**Prevention:** Store hashes of secrets instead of plaintext values when persisting to disk, unless the secret must be used in a form requiring the raw value (which is not the case for an authentication verifier).
