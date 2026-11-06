# Dashboard shortcuts

The Android shell keeps the dashboard reachable without walking back through website history.

- Browser, Cast History, and IPTV retain their existing dashboard button.
- Secondary native screens have a small dashboard handle on the right edge. Tap once to return to the dashboard.
- Bridged Apps have a small PlayBridge handle, on the right edge by default. Ordinary browser tabs never show it, including with hidden controls or in video fullscreen. Tap to open the menu: **Back**, **Dashboard**, **Remote**, **Play on**, **Reload** and **App Settings**. Play on opens the existing device picker without leaving the page. Reload closes the menu and reloads the current page, exiting video fullscreen first if needed.
- Drag the handle to move it, like the hidden-controls button. On release it snaps to the nearer left or right edge and keeps its height; its flat side faces the edge. The position is saved on the device and shared by all bridged apps. The handle and the hidden-controls button exclude their own 48 dp area from the system back gesture, so taps and drags at the edge are not taken as Back; the rest of the edge keeps the gesture.
- During bridged-app video fullscreen, the handle is hidden while idle. A screen tap reveals it for 3.5 seconds; an open menu stays visible until dismissed. The tap still reaches the website. This is an interaction timer because websites do not expose a standard player-controls visibility event.

The handle has a 28 × 36 dp visual and a 48 × 48 dp touch target. Its glass palette uses charcoal `#1C1C1E`, a 0.75 dp white rim fading from 27% to 2% opacity, and pale icons and labels. The handle uses 55% tint; the menu uses 82% tint to keep labels readable. This treatment stays dark in both app themes. It uses translucency and a subtle highlight without capturing or blurring the website; backdrop blur and refraction shaders are not part of this implementation. It stays within the system safe area, sits outside page transitions, and has no repeating animation.

Dashboard navigation uses the existing shell route. Bridged App tabs, website state, dashboard tile-page selection, and cast sessions are retained. Android Back continues to follow website history. Selecting Dashboard from video fullscreen exits fullscreen first. Opening the full Devices screen also exits fullscreen first.

Focused on-device checks live in `PlayBridgeEdgeShortcutTest`: one- and two-tap navigation, menu dismissal, fullscreen visibility, and forwarding touches into an AndroidView.
