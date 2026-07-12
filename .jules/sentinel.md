## 2025-02-09 - Hardcoded Keystore Password in Android TV App
**Vulnerability:** The Android TV app used a hardcoded string (`"playbridge"`) as the password for its dynamically generated app-private TLS PKCS12 keystore in `TlsIdentity.kt`.
**Learning:** While the keystore file is sandboxed in the app's internal storage, using a hardcoded password provides no defense-in-depth against attackers who manage to read files from that directory (e.g. via an arbitrary file read vulnerability or on a rooted device). The key shouldn't be accessible using a globally known constant.
**Prevention:** Generate a strong, random password (e.g., using `UUID.randomUUID()`) when the keystore is first created, and store it securely (e.g., in a local text file with restricted permissions alongside the keystore, or via EncryptedSharedPreferences).

## 2025-02-09 - XSS Vulnerability in Extension Popup
**Vulnerability:** XSS vulnerability in `extension/src/ui/popup.js` where user-controlled connection properties (`ip`, `pin`) were rendered directly into the DOM using `item.innerHTML = \`<span class="saved-connection-ip">${conn.ip}</span>\``.
**Learning:** The extension builds some DOM views manually without a UI framework, increasing the risk of DOM-based XSS when interpolating variables into HTML strings.
**Prevention:** Always use safe DOM API methods (`document.createElement` and `textContent`) or a sanitization library instead of interpolating untrusted input into `innerHTML`.
