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

## 2026-07-21 - Inconsistent Bearer-Token Storage (SEC-004)
**Vulnerability:** Sender authorization tokens were stored in plaintext on both Android TV and Apple TV receivers. While the desktop client correctly stored SHA-256 verifiers, the TV platforms persisted the original tokens directly in standard preference files (DataStore / UserDefaults).
**Learning:** Storing authentication tokens in plaintext on the receiving device expands the attack surface; local backups, debugging tools, or processes with access to preference files can extract the token and impersonate the sender to the TV.
**Prevention:** Authorization tokens must never be persisted in plaintext. Use secure cryptographic hashing (like SHA-256) on the token immediately, store only the hash, and authenticate clients by generating the hash of the provided token and comparing it against stored records. Ensure legacy plaintext records are transparently migrated upon their next successful use.
