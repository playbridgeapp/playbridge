# Dashboard shortcuts

The Android shell keeps the dashboard reachable without walking back through website or library history.

- Browser, Library, Devices, Cast History, and IPTV retain their existing dashboard button.
- Secondary native screens have a small dashboard handle on the right edge. Tap once to return to the dashboard.
- Bridged Apps and browsers with hidden controls have a small PlayBridge handle on the right edge. Tap to open a three-item menu: **Dashboard**, **Devices**, and **Refresh**. Devices opens the existing device picker without leaving the page. Refresh closes the menu and reloads the current page, exiting video fullscreen first if needed.
- During browser video fullscreen, the handle is hidden while idle. A screen tap reveals it for 3.5 seconds; an open menu stays visible until dismissed. The tap still reaches the website. This is an interaction timer because websites do not expose a standard player-controls visibility event.

The handle has a 28 × 36 dp visual and a 48 × 48 dp touch target. Its glass palette follows Nuvio's dock: charcoal `#1C1C1E`, a 0.75 dp white rim fading from 27% to 2% opacity, and pale icons and labels. The handle uses 55% tint; the menu uses 82% tint to keep labels readable. This treatment stays dark in both app themes. It uses translucency and a subtle highlight without capturing or blurring the website; Nuvio's Haze backdrop blur and refraction shader are not part of this implementation. It stays within the system safe area, sits outside page transitions, and has no repeating animation.

Dashboard navigation uses the existing shell route. Bridged App tabs, website state, dashboard tile-page selection, and cast sessions are retained. Android Back continues to follow website history. Selecting Dashboard from video fullscreen exits fullscreen first. Opening the full Devices screen also exits fullscreen first.

Focused on-device checks live in `PlayBridgeEdgeShortcutTest`: one- and two-tap navigation, menu dismissal, fullscreen visibility, and forwarding touches into an AndroidView.
