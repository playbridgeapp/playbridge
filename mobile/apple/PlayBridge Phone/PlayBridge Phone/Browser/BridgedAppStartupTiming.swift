// SPDX-License-Identifier: MPL-2.0
import Foundation

/// Debug-only, in-memory timings. Labels and elapsed milliseconds only: no page data.
final class BridgedAppStartupTiming {
    enum Event: String {
        case tileTap = "tile_tap", rulesReady = "rules_ready", webViewCreated = "web_view_created"
        case sessionReused = "session_reused", committed = "navigation_committed"
        case firstContentfulPaint = "first_contentful_paint", paintOpportunity = "paint_opportunity"
    }
    #if DEBUG
    private var start: TimeInterval?
    private var recorded = Set<String>()
    #endif

    func begin(at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        #if DEBUG
        start = time
        recorded.removeAll()
        mark(.tileTap)
        #endif
    }

    func mark(_ event: Event) {
        #if DEBUG
        guard let start, recorded.insert(event.rawValue).inserted else { return }
        let milliseconds = Int((ProcessInfo.processInfo.systemUptime - start) * 1000)
        print("[BridgedStartup] \(event.rawValue) elapsed_ms=\(milliseconds)")
        #endif
    }

    #if DEBUG
    // Isolated-world, main-frame signal. WebKit versions without Paint Timing only
    // report a frame opportunity; that is explicitly not proof of meaningful content.
    static let paintScript = #"""
    (() => {
      let sent = false;
      const send = value => { if (!sent) { sent = true; window.webkit.messageHandlers.startupPaint.postMessage(value); } };
      if (typeof PerformanceObserver !== 'undefined' && PerformanceObserver.supportedEntryTypes?.includes('paint')) {
        const observer = new PerformanceObserver(list => {
          if (list.getEntries().some(entry => entry.name === 'first-contentful-paint')) { send('fcp'); observer.disconnect(); }
        });
        observer.observe({type: 'paint', buffered: true});
      } else {
        const ready = () => requestAnimationFrame(() => requestAnimationFrame(() => send('frame')));
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, {once: true});
        else ready();
      }
    })();
    """#
    #endif
}
