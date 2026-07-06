## 2025-02-09 - XSS Vulnerability in Extension Popup
**Vulnerability:** XSS vulnerability in `extension/src/ui/popup.js` where user-controlled connection properties (`ip`, `pin`) were rendered directly into the DOM using `item.innerHTML = \`<span class="saved-connection-ip">${conn.ip}</span>\``.
**Learning:** The extension builds some DOM views manually without a UI framework, increasing the risk of DOM-based XSS when interpolating variables into HTML strings.
**Prevention:** Always use safe DOM API methods (`document.createElement` and `textContent`) or a sanitization library instead of interpolating untrusted input into `innerHTML`.
